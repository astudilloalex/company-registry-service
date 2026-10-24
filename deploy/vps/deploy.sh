#!/usr/bin/env bash
set -Eeuo pipefail

readonly APP_NAME="company-registry-service"
readonly SERVICE_NAME="${APP_NAME}.service"
readonly IMAGE_REFERENCE="${1:?Usage: deploy.sh IMAGE_REFERENCE SOURCE_DIRECTORY [NEW_ENVIRONMENT_FILE NEW_KEYRING_FILE]}"
readonly SOURCE_DIRECTORY="${2:-${HOME}/.local/share/${APP_NAME}}"
readonly CONFIG_HOME="${XDG_CONFIG_HOME:-${HOME}/.config}"
readonly QUADLET_DIRECTORY="${CONFIG_HOME}/containers/systemd"
readonly QUADLET_TEMPLATE="${SOURCE_DIRECTORY}/${APP_NAME}.container.template"
readonly QUADLET_FILE="${QUADLET_DIRECTORY}/${APP_NAME}.container"
readonly ENVIRONMENT_FILE="${DEPLOYMENT_ENVIRONMENT_FILE:-/home/containers/deployment/env/${APP_NAME}.env}"
readonly INCOMING_ENVIRONMENT_FILE="${3:-}"
readonly KEYRING_FILE="${DEPLOYMENT_KEYRING_FILE:-/home/containers/deployment/secrets/${APP_NAME}.properties}"
readonly INCOMING_KEYRING_FILE="${4:-}"
readonly RENDERER="${SOURCE_DIRECTORY}/render-env.py"
readonly VERIFIER="${SOURCE_DIRECTORY}/verify.py"
readonly REGISTRY_AUTH_FILE="${CONFIG_HOME}/containers/auth.json"
readonly HEALTHCHECK_URL="${HEALTHCHECK_URL:-http://127.0.0.1:8083/q/health/ready}"
readonly -a REQUIRED_NETWORKS=("internal-services" "company-db" "messaging")

umask 077
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"
export DBUS_SESSION_BUS_ADDRESS="${DBUS_SESSION_BUS_ADDRESS:-unix:path=${XDG_RUNTIME_DIR}/bus}"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

wait_until_healthy() {
  for ((attempt = 1; attempt <= 60; attempt++)); do
    if curl --fail --silent --show-error --max-time 2 "${HEALTHCHECK_URL}" >/dev/null; then
      return 0
    fi
    sleep 1
  done
  return 1
}

show_service_logs() {
  journalctl --user --unit "${SERVICE_NAME}" --lines 80 --no-pager >&2 || true
}

cleanup_files=()
backup_directory=""
transaction_active=false
environment_replaced=false
had_previous_quadlet=false
had_previous_environment=false

atomic_install() {
  local source_file="$1" destination_file="$2" temporary_file
  temporary_file="$(mktemp "${destination_file}.new.XXXXXX")" || return 1
  cleanup_files+=("${temporary_file}")
  install -m 600 "${source_file}" "${temporary_file}" || return 1
  mv -f -- "${temporary_file}" "${destination_file}"
}

rollback() {
  printf 'Rolling back the environment and Quadlet configuration...\n' >&2
  systemctl --user stop "${SERVICE_NAME}" || true

  if [[ "${environment_replaced}" == true ]]; then
    if [[ "${had_previous_environment}" == true ]]; then
      atomic_install "${backup_directory}/previous.env" "${ENVIRONMENT_FILE}" || return 1
    else
      rm -f -- "${ENVIRONMENT_FILE}" || return 1
    fi
  fi

  if [[ "${had_previous_quadlet}" == true ]]; then
    atomic_install "${backup_directory}/previous.container" "${QUADLET_FILE}" || return 1
  else
    rm -f -- "${QUADLET_FILE}" || return 1
  fi
  systemctl --user daemon-reload || return 1
  if [[ "${had_previous_quadlet}" == true ]]; then
    systemctl --user restart "${SERVICE_NAME}" || return 1
    wait_until_healthy || return 1
  fi
}

cleanup() {
  local exit_status=$? keep_backup=false
  trap - EXIT INT TERM HUP
  set +e
  if [[ "${transaction_active}" == true ]]; then
    if ! rollback; then
      keep_backup=true
      printf 'ERROR: Rollback was incomplete; private recovery files remain in %s\n' "${backup_directory}" >&2
    fi
    exit_status=1
  fi
  rm -f -- "${cleanup_files[@]}"
  if [[ -n "${backup_directory}" && "${keep_backup}" == false ]]; then
    rm -f -- "${backup_directory}/previous.env" "${backup_directory}/previous.container"
    rmdir -- "${backup_directory}"
  fi
  exit "${exit_status}"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

for required_command in curl flock id install journalctl mktemp mv podman python3 rm rmdir sleep systemctl uname; do
  command -v "${required_command}" >/dev/null 2>&1 ||
    fail "Missing required command on the VPS: ${required_command}"
done

case "$(uname -m)" in
  x86_64 | amd64) ;;
  *)
    fail "This workflow publishes linux/amd64 images; the VPS architecture is $(uname -m)"
    ;;
esac

if [[ ! "${IMAGE_REFERENCE}" =~ ^ghcr\.io/astudilloalex/company-registry-service:sha-[0-9a-f]{40}$ ]]; then
  fail "Unexpected image reference: ${IMAGE_REFERENCE}"
fi

[[ -f "${QUADLET_TEMPLATE}" ]] ||
  fail "Quadlet template not found: ${QUADLET_TEMPLATE}"

[[ -f "${RENDERER}" ]] || fail "Deployment configuration validator is missing"
[[ -f "${VERIFIER}" ]] || fail "Post-deployment verifier is missing"
for destination in "${ENVIRONMENT_FILE}" "${KEYRING_FILE}"; do
  [[ "${destination}" =~ ^/[-a-zA-Z0-9_./]+$ ]] || fail "Configuration destinations must be simple absolute paths"
done

# Serialize manual and automated deployments before validating historical keys.
install -d -m 700 "${HOME}/.local/share/${APP_NAME}"
exec 9>"${HOME}/.local/share/${APP_NAME}/deploy.lock"
flock --nonblock 9 || fail "Another Company Registry deployment is running"

if [[ -n "${INCOMING_ENVIRONMENT_FILE}" ]]; then
  [[ "${INCOMING_ENVIRONMENT_FILE}" != "${ENVIRONMENT_FILE}" ]] ||
    fail "Stage the new environment separately from the installed production file"
  environment_to_validate="${INCOMING_ENVIRONMENT_FILE}"
  [[ -n "${INCOMING_KEYRING_FILE}" && "${INCOMING_KEYRING_FILE}" != "${KEYRING_FILE}" ]] ||
    fail "Stage the incoming keyring separately from the installed file"
  keyring_to_validate="${INCOMING_KEYRING_FILE}"
else
  [[ -f "${ENVIRONMENT_FILE}" ]] ||
    fail "Provide a generated NEW_ENVIRONMENT_FILE or provision ${ENVIRONMENT_FILE} before deployment"
  environment_to_validate="${ENVIRONMENT_FILE}"
  [[ -z "${INCOMING_KEYRING_FILE}" ]] || fail "Provide both incoming configuration files together"
  keyring_to_validate="${KEYRING_FILE}"
fi

previous_keyring=()
if [[ -e "${KEYRING_FILE}" || -L "${KEYRING_FILE}" ]]; then
  previous_keyring+=("${KEYRING_FILE}")
fi
if [[ -e "${ENVIRONMENT_FILE}" || -L "${ENVIRONMENT_FILE}" ]]; then
  python3 "${RENDERER}" --validate "${ENVIRONMENT_FILE}" "${KEYRING_FILE}" || fail "Installed configuration is not private or valid"
fi
python3 "${RENDERER}" --validate "${environment_to_validate}" "${keyring_to_validate}" "${previous_keyring[@]}" ||
  fail "Production configuration validation failed"

missing_networks=()
for network_name in "${REQUIRED_NETWORKS[@]}"; do
  if ! podman network exists "${network_name}"; then
    missing_networks+=("${network_name}")
  fi
done

if (( ${#missing_networks[@]} > 0 )); then
  fail "Required Podman networks are missing for user $(id -un): ${missing_networks[*]}. Install and start them from the VPS infrastructure configuration"
fi

pull_options=()
if [[ -f "${REGISTRY_AUTH_FILE}" ]]; then
  python3 -c 'import runpy,sys; from pathlib import Path; runpy.run_path(sys.argv[1])["private_file"](Path(sys.argv[2]))' \
    "${RENDERER}" "${REGISTRY_AUTH_FILE}" || fail "Registry authentication file must be private"
  pull_options+=(--authfile "${REGISTRY_AUTH_FILE}")
fi

printf 'Pulling %s before restarting the service...\n' "${IMAGE_REFERENCE}"
if ! podman pull "${pull_options[@]}" "${IMAGE_REFERENCE}"; then
  fail "Unable to pull the image. If GHCR is private, log in with podman using ${REGISTRY_AUTH_FILE}"
fi

install -d -m 700 "${QUADLET_DIRECTORY}"
backup_directory="$(mktemp -d)"

if [[ -f "${QUADLET_FILE}" ]]; then
  install -m 600 "${QUADLET_FILE}" "${backup_directory}/previous.container"
  had_previous_quadlet=true
fi
if [[ -f "${ENVIRONMENT_FILE}" ]]; then
  install -m 600 "${ENVIRONMENT_FILE}" "${backup_directory}/previous.env"
  had_previous_environment=true
fi

new_quadlet="$(mktemp)"
cleanup_files+=("${new_quadlet}")
transaction_active=true
if [[ -n "${INCOMING_ENVIRONMENT_FILE}" ]]; then
  # Keyrings are additive and immutable. Keep this superset even on rollback:
  # requests accepted before a failed rollout may already depend on a new key.
  install -d -m 700 "${KEYRING_FILE%/*}"
  atomic_install "${INCOMING_KEYRING_FILE}" "${KEYRING_FILE}"
  install -d -m 700 "${ENVIRONMENT_FILE%/*}"
  environment_replaced=true
  atomic_install "${INCOMING_ENVIRONMENT_FILE}" "${ENVIRONMENT_FILE}"
fi
python3 "${RENDERER}" --quadlet "${QUADLET_TEMPLATE}" "${new_quadlet}" \
  "${IMAGE_REFERENCE}" "${ENVIRONMENT_FILE}" "${KEYRING_FILE}"
atomic_install "${new_quadlet}" "${QUADLET_FILE}"
systemctl --user daemon-reload

if ! systemctl --user cat "${SERVICE_NAME}" >/dev/null; then
  fail "Quadlet could not generate ${SERVICE_NAME}; inspect the Podman generator output on the VPS"
fi

if ! systemctl --user restart "${SERVICE_NAME}"; then
  show_service_logs
  fail "The new Quadlet service did not start"
fi

if ! wait_until_healthy; then
  show_service_logs
  fail "The service did not become healthy at ${HEALTHCHECK_URL}"
fi

# Validate effective Podman settings as well as HTTP readiness before committing.
configured_mode="$(python3 -c 'import runpy,sys; from pathlib import Path; print(runpy.run_path(sys.argv[1])["parse_environment"](Path(sys.argv[2]).read_text())["COMPANY_OUTBOX_MODE"])' "${RENDERER}" "${ENVIRONMENT_FILE}")"
python3 "${VERIFIER}" --mode "${configured_mode}" --finalize-database || fail "Post-deployment verification failed"

systemctl --user --no-pager --full status "${SERVICE_NAME}"
transaction_active=false
printf 'Deployment completed: %s\n' "${IMAGE_REFERENCE}"

# Business fixtures are optional and cannot roll back a committed deployment.
if [[ -f "${SOURCE_DIRECTORY}/smoke-fixtures.json" ]]; then
  if ! timeout --kill-after=5s 90s python3 "${VERIFIER}" \
    --mode "${configured_mode}" --smoke-fixtures "${SOURCE_DIRECTORY}/smoke-fixtures.json"; then
    printf '::warning::Optional business smoke tests failed or timed out; deployment remains installed.\n' >&2
  fi
else
  printf 'Business smoke tests skipped: no fixtures configured.\n'
fi
