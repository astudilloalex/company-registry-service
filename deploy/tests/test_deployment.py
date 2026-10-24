"""Verify secret rendering and deployment recovery without contacting a VPS."""

import base64
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest


DEPLOY_DIRECTORY = Path(__file__).resolve().parents[1]
TEMPLATE = DEPLOY_DIRECTORY / "quadlet/company-registry-service.env.example"
RENDERER = DEPLOY_DIRECTORY / "vps/render-env.py"
DEPLOY_SCRIPT = DEPLOY_DIRECTORY / "vps/deploy.sh"
IMAGE = "ghcr.io/astudilloalex/company-registry-service:sha-" + "a" * 40


def production_secrets() -> dict[str, str]:
    """Use only synthetic required secrets; other inputs come from the env template."""
    return {
        "DB_PASSWORD": "database-test-password",
        "FLYWAY_DB_PASSWORD": "migration-test-password",
        "COMPANY_IDEMPOTENCY_KEYS_JSON": json.dumps({"K1": base64.b64encode(bytes(range(32))).decode()}),
    }


def keyring_for(output: Path) -> Path:
    return output.with_suffix(".keys.properties")


def render(output: Path, values: dict[str, str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(RENDERER), str(TEMPLATE), str(output), str(keyring_for(output))],
        env={"PATH": os.environ["PATH"], **values}, text=True, capture_output=True, check=False,
    )


class EnvironmentRenderingTest(unittest.TestCase):
    """Verify literal values, production invariants, and separate historical keys."""

    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)

    def test_literal_password_and_disabled_without_broker_credentials(self) -> None:
        values = production_secrets()
        values["DB_PASSWORD"] = " leading $() `command` # & | \\ ' \" = trailing "
        values["DB_USERNAME"] = "custom_runtime"
        output = self.directory / "production.env"
        result = render(output, values)
        self.assertEqual(result.returncode, 0, result.stderr)
        content = output.read_text()
        self.assertIn(f"DB_PASSWORD={values['DB_PASSWORD']}\n", content)
        self.assertIn("DB_USERNAME=custom_runtime\n", content)
        self.assertIn("COMPANY_OUTBOX_MODE=disabled\n", content)
        self.assertIn("COMPANY_MEMORY_MAX=256M\n", content)
        self.assertIn("COMPANY_CPU_QUOTA=50%\n", content)
        self.assertIn("COMPANY_PIDS_LIMIT=128\n", content)
        self.assertIn("RABBITMQ_PASSWORD=\n", content)
        self.assertNotIn("REPLACE_WITH_", content)
        self.assertNotIn("COMPANY_IDEMPOTENCY_KEYS_JSON=", content)
        for path in (output, keyring_for(output)):
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
        self.assertNotIn(values["DB_PASSWORD"], result.stdout + result.stderr)

    def test_each_missing_required_input_fails_before_creating_files(self) -> None:
        for name in ("DB_PASSWORD", "FLYWAY_DB_PASSWORD", "COMPANY_IDEMPOTENCY_KEYS_JSON"):
            with self.subTest(variable=name):
                values = production_secrets()
                del values[name]
                output = self.directory / (name + ".env")
                result = render(output, values)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(name, result.stderr)
                self.assertFalse(output.exists())
                self.assertFalse(keyring_for(output).exists())

    def test_invalid_values_are_rejected_without_disclosing_secrets(self) -> None:
        invalid_values = (
            ("DB_PASSWORD", "REPLACE_WITH_DATABASE_PASSWORD"),
            ("DB_PASSWORD", "password\nRABBITMQ_HOST=attacker"),
            ("DB_PASSWORD", "password\rnext-line"),
            ("COMPANY_IDEMPOTENCY_KEYS_JSON", "invalid!json"),
            ("COMPANY_IDEMPOTENCY_KEYS_JSON", json.dumps({"K1": "invalid!base64"})),
            ("COMPANY_IDEMPOTENCY_KEYS_JSON", json.dumps({"K1": base64.b64encode(b"x" * 31).decode()})),
            ("COMPANY_IDEMPOTENCY_KEYS_JSON", '{"K1":"first","K1":"second"}'),
            ("COMPANY_IDEMPOTENCY_ACTIVE_KEY_ID", "missing-key"),
            ("RABBITMQ_HOST", "http://rabbitmq"),
            ("RABBITMQ_PORT", "65536"),
            ("COMPANY_OUTBOX_MODE", "invalid-mode"),
            ("QUARKUS_HTTP_PORT", "8080"),
            ("QUARKUS_PROFILE", "dev"),
            ("COMPANY_MEMORY_MAX", "infinity"),
            ("COMPANY_CPU_QUOTA", "0%"),
            ("COMPANY_PIDS_LIMIT", "-1"),
            ("DB_USERNAME", "company_registry_migration"),
            ("DB_REACTIVE_URL", "postgresql://postgresql:5432/party_registry_service"),
            ("DB_JDBC_URL", "jdbc:postgresql://other-db:5432/company_registry_service"),
            ("PARTY_REGISTRY_BASE_URL", "http://localhost:8082"),
            ("PARTY_REGISTRY_BASE_URL", "http://:url-test-secret@party-registry-service:8080"),
            ("DB_REACTIVE_URL", "postgresql://postgresql:invalid-port/company_registry_service"),
        )
        for index, (name, value) in enumerate(invalid_values):
            with self.subTest(variable=name, case=index):
                values = production_secrets()
                values[name] = value
                output = self.directory / f"case-{index}.env"
                result = render(output, values)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(output.exists())
                self.assertFalse(keyring_for(output).exists())
                for secret in (values["DB_PASSWORD"], values["COMPANY_IDEMPOTENCY_KEYS_JSON"]):
                    self.assertNotIn(secret, result.stdout + result.stderr)

    def test_published_requires_credentials_and_stored_only_does_not(self) -> None:
        for mode in ("disabled", "stored-only", "published"):
            with self.subTest(mode=mode):
                values = {**production_secrets(), "COMPANY_OUTBOX_MODE": mode}
                output = self.directory / f"{mode}.env"
                result = render(output, values)
                self.assertEqual(result.returncode == 0, mode != "published", result.stderr)
        values = {**production_secrets(), "COMPANY_OUTBOX_MODE": "published",
                  "RABBITMQ_USERNAME": "publisher-test", "RABBITMQ_PASSWORD": "broker-test-password"}
        result = render(self.directory / "published-valid.env", values)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_existing_outputs_and_symlinks_are_not_overwritten(self) -> None:
        output = self.directory / "existing.env"
        output.write_text("existing production configuration")
        result = render(output, production_secrets())
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(output.read_text(), "existing production configuration")
        self.assertFalse(keyring_for(output).exists())
        link = self.directory / "link.env"
        keyring_for(link).symlink_to(output)
        result = render(link, production_secrets())
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(output.read_text(), "existing production configuration")


MOCK_COMMAND = r'''#!/usr/bin/env python3
"""Simulate external services while keeping filesystem mutations real."""
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys

name = Path(sys.argv[0]).name
arguments = sys.argv[1:]
state_file = Path(os.environ["MOCK_STATE"])
state = json.loads(state_file.read_text()) if state_file.exists() else {"commands": [], "restarts": []}
state["commands"].append([name, *arguments])
mode = os.environ.get("MOCK_MODE", "success")
status = 0

if name == "uname":
    print("x86_64")
elif name == "podman":
    if arguments[:2] == ["network", "exists"] and arguments[-1] == "messaging":
        status = int(mode == "network-failure")
    if arguments[0] == "pull":
        installed = Path(os.environ["DEPLOYMENT_ENVIRONMENT_FILE"])
        state["environment_at_pull"] = hashlib.sha256(installed.read_bytes()).hexdigest() if installed.exists() else None
        status = int(mode == "pull-failure")
elif name == "systemctl":
    if "daemon-reload" in arguments:
        state["reloads"] = state.get("reloads", 0) + 1
        status = int(mode == "reload-failure" and state["reloads"] == 1)
    elif "cat" in arguments:
        status = int(mode == "generator-failure")
    elif "restart" in arguments:
        installed = Path(os.environ["DEPLOYMENT_ENVIRONMENT_FILE"])
        state["restarts"].append(hashlib.sha256(installed.read_bytes()).hexdigest() if installed.exists() else None)
        status = int(mode == "rollback-failure" or (mode == "startup-failure" and len(state["restarts"]) == 1))
        if mode == "termination" and len(state["restarts"]) == 1:
            os.kill(os.getppid(), signal.SIGTERM)
elif name == "curl":
    state["health_requests"] = state.get("health_requests", 0) + 1
    status = int(mode == "health-failure" and state["health_requests"] <= 60)
elif name == "mv":
    if mode == "install-failure" and arguments[-1].endswith(".container") and not state.get("install_failed"):
        state["install_failed"] = True
        status = 1
    else:
        status = subprocess.run(["/bin/mv", *arguments], check=False).returncode
elif name == "timeout":
    status = 124 if mode == "smoke-timeout" else subprocess.run(arguments[2:], check=False).returncode

state_file.write_text(json.dumps(state))
sys.exit(status)
'''


MOCK_VERIFIER = r'''import json
import os
from pathlib import Path
import sys

arguments = sys.argv[1:]
with Path(os.environ["MOCK_VERIFICATIONS"]).open("a") as output:
    output.write(json.dumps(arguments) + "\n")
mode = os.environ.get("MOCK_MODE")
sys.exit(int(mode == "verification-failure" or
             (mode == "smoke-failure" and "--smoke-fixtures" in arguments)))
'''


class DeploymentRecoveryTest(unittest.TestCase):
    """Run the real shell script against isolated files and simulated services."""

    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.stage = self.directory / "stage"
        self.stage.mkdir()
        self.incoming = self.stage / "company-registry-service.env"
        result = render(self.incoming, production_secrets())
        self.assertEqual(result.returncode, 0, result.stderr)
        self.environment = self.directory / "production/env/company-registry-service.env"
        self.keyring = self.directory / "production/secrets/company-registry-service.properties"
        (self.stage / "render-env.py").write_bytes(RENDERER.read_bytes())
        # Shell transaction tests isolate external inspection; verify.py has separate tests.
        (self.stage / "verify.py").write_text(MOCK_VERIFIER)
        self.config_home = self.directory / "config"
        self.quadlet = self.config_home / "containers/systemd/company-registry-service.container"
        (self.stage / "company-registry-service.container.template").write_bytes(
            (DEPLOY_DIRECTORY / "quadlet/company-registry-service.container.template").read_bytes()
        )
        self.mock_bin = self.directory / "bin"
        self.mock_bin.mkdir()
        for name in ("podman", "systemctl", "journalctl", "curl", "sleep", "mv", "uname", "timeout"):
            command = self.mock_bin / name
            command.write_text(MOCK_COMMAND)
            command.chmod(0o755)
        self.backups = self.directory / "temporary"
        self.backups.mkdir()
        self.state_file = self.directory / "state.json"
        self.verifications = self.directory / "verifications.jsonl"

    def provision_previous_deployment(self) -> None:
        """Preserve distinct old credentials and an old image for recovery assertions."""
        self.environment.parent.mkdir(parents=True)
        self.previous_environment = self.incoming.read_bytes().replace(
            b"database-test-password", b"previous-database-password"
        )
        self.environment.write_bytes(self.previous_environment)
        self.environment.chmod(0o600)
        self.quadlet.parent.mkdir(parents=True)
        self.keyring.parent.mkdir(parents=True, exist_ok=True)
        self.keyring.write_bytes(keyring_for(self.incoming).read_bytes())
        self.keyring.chmod(0o600)
        self.previous_quadlet = b"[Container]\nImage=previous-image\n"
        self.quadlet.write_bytes(self.previous_quadlet)

    def deploy(self, mode: str = "success", incoming: bool = True) -> subprocess.CompletedProcess[str]:
        """Exercise deployment without real Podman, systemd, SSH, or network access."""
        arguments = ["bash", str(DEPLOY_SCRIPT), IMAGE, str(self.stage)]
        if incoming:
            arguments.extend((str(self.incoming), str(keyring_for(self.incoming))))
        return subprocess.run(
            arguments,
            env={
                "PATH": f"{self.mock_bin}:/usr/bin:/bin",
                "HOME": str(self.directory),
                "XDG_CONFIG_HOME": str(self.config_home),
                "DEPLOYMENT_ENVIRONMENT_FILE": str(self.environment),
                "DEPLOYMENT_KEYRING_FILE": str(self.keyring),
                "TMPDIR": str(self.backups),
                "MOCK_STATE": str(self.state_file),
                "MOCK_VERIFICATIONS": str(self.verifications),
                "MOCK_MODE": mode,
            },
            text=True,
            capture_output=True,
            timeout=30,
            check=False,
        )

    def test_first_deployment_installs_private_environment_and_correct_quadlet(self) -> None:
        result = self.deploy()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.environment.read_bytes(), self.incoming.read_bytes())
        self.assertIn("RABBITMQ_VIRTUAL_HOST=platform\n", self.environment.read_text())
        self.assertEqual(stat.S_IMODE(self.environment.stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE(self.quadlet.stat().st_mode), 0o600)
        content = self.quadlet.read_text()
        self.assertIn(f"Image={IMAGE}\n", content)
        self.assertIn(f"EnvironmentFile={self.environment}\n", content)
        self.assertNotIn("@@", content)
        self.assertFalse(list(self.backups.iterdir()))
        self.assertIn("PublishPort=127.0.0.1:8083:8083/tcp\n", content)
        self.assertEqual([line for line in content.splitlines() if line.startswith("Network=")],
                         ["Network=internal-services.network", "Network=company-db.network", "Network=messaging.network"])
        self.assertIn("MemoryMax=256M\n", content)
        self.assertIn("CPUQuota=50%\n", content)
        self.assertIn("PidsLimit=128\n", content)
        self.assertIn("TasksMax=128\n", content)
        self.assertIn("Business smoke tests skipped", result.stdout)
        self.assertEqual([json.loads(line) for line in self.verifications.read_text().splitlines()],
                         [["--mode", "disabled", "--finalize-database"]])
        self.assertEqual(self.keyring.read_bytes(), keyring_for(self.incoming).read_bytes())
        for value in (production_secrets()["DB_PASSWORD"], production_secrets()["COMPANY_IDEMPOTENCY_KEYS_JSON"]):
            self.assertNotIn(value, result.stdout + result.stderr)

    def test_success_replaces_existing_configuration_after_image_pull(self) -> None:
        self.provision_previous_deployment()
        result = self.deploy()
        self.assertEqual(result.returncode, 0, result.stderr)
        state = json.loads(self.state_file.read_text())
        self.assertEqual(state["environment_at_pull"], hashlib.sha256(self.previous_environment).hexdigest())
        self.assertEqual(self.environment.read_bytes(), self.incoming.read_bytes())
        self.assertEqual(len(state["restarts"]), 1)
        self.assertFalse(list(self.backups.iterdir()))

    def test_optional_smoke_failure_or_timeout_keeps_the_successful_deployment(self) -> None:
        self.provision_previous_deployment()
        fixtures = self.stage / "smoke-fixtures.json"
        fixtures.write_text(json.dumps({"tenantId": "00000000-0000-0000-0000-000000000001",
                                        "partyId": "00000000-0000-0000-0000-000000000002"}))
        for mode in ("smoke-failure", "smoke-timeout"):
            with self.subTest(mode=mode):
                self.environment.write_bytes(self.previous_environment)
                self.quadlet.write_bytes(self.previous_quadlet)
                self.state_file.unlink(missing_ok=True)
                self.verifications.unlink(missing_ok=True)
                result = self.deploy(mode)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn("Deployment completed", result.stdout)
                self.assertIn("::warning::Optional business smoke tests failed or timed out", result.stderr)
                self.assertNotIn("Rolling back", result.stderr)
                self.assertEqual(self.environment.read_bytes(), self.incoming.read_bytes())
                self.assertIn(f"Image={IMAGE}\n", self.quadlet.read_text())
                state = json.loads(self.state_file.read_text())
                self.assertEqual(len(state["restarts"]), 1)
                self.assertTrue(any(command[:3] == ["timeout", "--kill-after=5s", "90s"]
                                    for command in state["commands"]))
                calls = [json.loads(line) for line in self.verifications.read_text().splitlines()]
                self.assertEqual(calls[0], ["--mode", "disabled", "--finalize-database"])
                if mode == "smoke-failure":
                    self.assertEqual(calls[1], ["--mode", "disabled", "--smoke-fixtures", str(fixtures)])
                else:
                    self.assertEqual(len(calls), 1)
                self.assertFalse(list(self.backups.iterdir()))

    def test_required_verification_still_rolls_back_with_smoke_fixtures(self) -> None:
        self.provision_previous_deployment()
        (self.stage / "smoke-fixtures.json").write_text("{}")
        result = self.deploy("verification-failure")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.environment.read_bytes(), self.previous_environment)
        self.assertEqual(self.quadlet.read_bytes(), self.previous_quadlet)
        self.assertEqual([json.loads(line) for line in self.verifications.read_text().splitlines()],
                         [["--mode", "disabled", "--finalize-database"]])
        state = json.loads(self.state_file.read_text())
        self.assertFalse(any(command[0] == "timeout" for command in state["commands"]))

    def test_failures_restore_both_files_and_restart_with_previous_credentials(self) -> None:
        self.provision_previous_deployment()
        for mode in ("startup-failure", "generator-failure", "reload-failure", "install-failure", "health-failure", "termination", "verification-failure"):
            with self.subTest(failure=mode):
                self.state_file.unlink(missing_ok=True)
                result = self.deploy(mode)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(self.environment.read_bytes(), self.previous_environment)
                self.assertEqual(self.quadlet.read_bytes(), self.previous_quadlet)
                state = json.loads(self.state_file.read_text())
                self.assertEqual(state["restarts"][-1], hashlib.sha256(self.previous_environment).hexdigest())
                self.assertFalse(list(self.backups.iterdir()))

    def test_pull_failure_leaves_previous_service_configuration_untouched(self) -> None:
        self.provision_previous_deployment()
        result = self.deploy("pull-failure")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.environment.read_bytes(), self.previous_environment)
        self.assertEqual(self.quadlet.read_bytes(), self.previous_quadlet)
        self.assertFalse(json.loads(self.state_file.read_text())["restarts"])

    def test_first_deployment_failure_removes_new_environment_and_quadlet(self) -> None:
        result = self.deploy("startup-failure")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.environment.exists())
        self.assertFalse(self.quadlet.exists())
        self.assertFalse(list(self.backups.iterdir()))

    def test_placeholder_and_duplicate_values_fail_before_service_changes(self) -> None:
        self.provision_previous_deployment()
        original = self.incoming.read_text()
        for content in (
            original.replace("database-test-password", "REPLACE_WITH_PASSWORD"),
            original + "DB_PASSWORD=duplicate-password\n",
            original + "DB_PASSWORD=\n",
        ):
            with self.subTest(duplicate="duplicate-password" in content):
                self.incoming.write_text(content)
                result = self.deploy()
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(self.environment.read_bytes(), self.previous_environment)
                self.assertEqual(self.quadlet.read_bytes(), self.previous_quadlet)
                state = json.loads(self.state_file.read_text())
                self.assertFalse(state["restarts"])
                self.assertTrue(all(command[0] == "uname" for command in state["commands"]))

    def test_manual_deployment_reuses_existing_environment(self) -> None:
        self.provision_previous_deployment()
        result = self.deploy(incoming=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.environment.read_bytes(), self.previous_environment)

    def test_failed_recovery_retains_private_backups(self) -> None:
        self.provision_previous_deployment()
        result = self.deploy("rollback-failure")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Rollback was incomplete", result.stderr)
        directories = list(self.backups.iterdir())
        self.assertEqual(len(directories), 1)
        backup = directories[0]
        self.assertEqual(stat.S_IMODE(backup.stat().st_mode), 0o700)
        self.assertEqual((backup / "previous.env").read_bytes(), self.previous_environment)
        self.assertEqual(stat.S_IMODE((backup / "previous.env").stat().st_mode), 0o600)

    def test_removed_or_replaced_historical_keys_fail_before_service_changes(self) -> None:
        self.provision_previous_deployment()
        for content in (
            "company-registry.idempotency.keys.K2=" + base64.b64encode(b"x" * 32).decode() + "\n",
            "company-registry.idempotency.keys.K1=" + base64.b64encode(b"x" * 32).decode() + "\n",
        ):
            with self.subTest(case=content[:40]):
                keyring_for(self.incoming).write_text(content)
                result = self.deploy()
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(self.environment.read_bytes(), self.previous_environment)
                self.assertFalse(json.loads(self.state_file.read_text())["restarts"])

    def test_new_keys_survive_rollback_for_acceptances_during_failed_rollout(self) -> None:
        self.provision_previous_deployment()
        original_keys = keyring_for(self.incoming).read_text()
        new_keys = original_keys + "company-registry.idempotency.keys.K2=" + base64.b64encode(b"x" * 32).decode() + "\n"
        keyring_for(self.incoming).write_text(new_keys)
        self.incoming.write_text(self.incoming.read_text().replace("ACTIVE_KEY_ID=K1", "ACTIVE_KEY_ID=K2"))
        result = self.deploy("startup-failure")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.environment.read_bytes(), self.previous_environment)
        self.assertEqual(self.keyring.read_text(), new_keys)

    def test_nonprivate_keyring_is_rejected_before_container_changes(self) -> None:
        self.provision_previous_deployment()
        keyring_for(self.incoming).chmod(0o644)
        result = self.deploy()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.environment.read_bytes(), self.previous_environment)
        self.assertFalse(json.loads(self.state_file.read_text())["restarts"])

    def test_missing_messaging_network_blocks_even_disabled_mode(self) -> None:
        self.provision_previous_deployment()
        result = self.deploy("network-failure")
        self.assertNotEqual(result.returncode, 0)
        state = json.loads(self.state_file.read_text())
        self.assertFalse(state["restarts"])
        self.assertFalse(any(command[:2] == ["podman", "pull"] for command in state["commands"]))


if __name__ == "__main__":
    unittest.main()
