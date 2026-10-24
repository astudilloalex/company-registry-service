#!/usr/bin/env bash
# Create one consistent logical snapshot plus its historical protection material.
set -Eeuo pipefail
umask 077
readonly destination="${1:?Usage: backup.sh NEW_PRIVATE_DIRECTORY [POSTGRES_CONTAINER] [KEYRING_FILE]}"
readonly postgres_container="${2:-postgresql}"
readonly keyring="${3:-/home/containers/deployment/secrets/company-registry-service.properties}"
: "${PGPASSWORD:?Supply the migration/backup role password through the protected environment}"
export PGPASSWORD
[[ ! -e "${destination}" && ! -L "${destination}" ]] || { printf 'Backup destination must be new\n' >&2; exit 1; }
[[ -f "${keyring}" && ! -L "${keyring}" ]] || { printf 'Protected historical keyring is missing\n' >&2; exit 1; }
mkdir -m 700 -- "${destination}"
podman exec --env PGPASSWORD "${postgres_container}" pg_dump \
  --host=127.0.0.1 --username="${PGUSER:-company_registry_migration}" \
  --dbname=company_registry_service --format=custom --no-owner --no-acl \
  > "${destination}/company_registry_service.dump"
podman exec -i "${postgres_container}" pg_restore --list \
  < "${destination}/company_registry_service.dump" > /dev/null
# Keys are additive: copying after the DB snapshot includes every key it can need.
install -m 600 -- "${keyring}" "${destination}/company-keys.properties"
(
  cd -- "${destination}"
  sha256sum company_registry_service.dump company-keys.properties > SHA256SUMS
)
printf 'Consistent database snapshot and historical keyring prepared in %s\n' "${destination}"
printf 'Hand this private directory to the existing encrypted off-VPS backup job.\n'
