#!/usr/bin/env python3
"""Exercise a native OCI image, private topology, backup/restore and measured load.

All containers and networks belong to this run and are removed in finally.
Requires rootless Podman and the already-built production image; never uses a VPS.
"""

from concurrent.futures import ThreadPoolExecutor
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
from uuid import uuid4
from urllib.request import urlopen


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "deploy/vps"))
import verify

spec = importlib.util.spec_from_file_location("render_env", ROOT / "deploy/vps/render-env.py")
renderer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(renderer)


PROVIDER = '''from http.server import BaseHTTPRequestHandler, HTTPServer
import json
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        path = self.path.split('?',1)[0]
        if path.endswith('/identifiers'):
            result = {'status':200,'code':'successful','data':[],'numberOfElements':0}
        else:
            party = path.split('/')[-1]
            result = {'status':200,'code':'successful','data':{'partyId':party,'type':'LEGAL_ENTITY',
                'recordStatus':'ACTIVE','displayName':'SMOKE','version':0,
                'createdAt':'2026-10-23T00:00:00Z','updatedAt':'2026-10-23T00:00:00Z',
                'createdBy':'fixture','updatedBy':'fixture','legalEntityDetails':{'legalName':'SMOKE',
                'tradeName':'SMOKE','legalFormCode':'SAS','incorporationCountryCode':'EC'}}}
        self.send_response(200)
        self.send_header('Content-Type','application/json')
        self.end_headers()
        self.wfile.write(json.dumps(result).encode())
    def log_message(self,*args): pass
HTTPServer(('0.0.0.0',8080),Handler).serve_forever()
'''


def podman(*arguments: str, **kwargs) -> str:
    result = subprocess.run(["podman", *arguments], capture_output=True, text=True, check=False, **kwargs)
    if result.returncode:
        diagnostic = ROOT / "build/t01-operations/container-errors.log"
        with diagnostic.open("a") as output:
            output.write(result.stderr + "\n")
        diagnostic.chmod(0o600)
        raise RuntimeError("Isolated container command failed: " + " ".join(arguments[:2]))
    return result.stdout.strip()


def eventually(operation, timeout: int = 90):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            return operation()
        except (OSError, ValueError, RuntimeError):
            time.sleep(1)
    raise RuntimeError("Isolated acceptance condition timed out")


def main() -> int:
    image = sys.argv[1] if len(sys.argv) > 1 else "localhost/company-registry-service:t01"
    evidence = ROOT / "build/t01-operations"
    evidence.mkdir(parents=True, exist_ok=True)
    prefix = "company-t01-" + uuid4().hex[:10]
    networks = {suffix: prefix + "-" + suffix for suffix in verify.NETWORKS}
    database, provider, app = (prefix + suffix for suffix in ("-postgres", "-provider", "-app"))
    created_containers = []
    created_networks = []
    samples = []
    try:
        with tempfile.TemporaryDirectory(prefix="company-t01-") as directory:
            work = Path(directory)
            keyring = work / "keys.properties"
            keyring.write_text(renderer.KEY_PREFIX + "K1=" + __import__("base64").b64encode(os.urandom(32)).decode() + "\n")
            keyring.chmod(0o600)
            environment = work / "application.env"
            values = renderer.parse_environment((ROOT / "deploy/quadlet/company-registry-service.env.example").read_text()
                        .replace("REPLACE_WITH_RUNTIME_DATABASE_PASSWORD", "runtime-test")
                        .replace("REPLACE_WITH_MIGRATION_DATABASE_PASSWORD", "migration-test")
                        .replace("REPLACE_WITH_MEASURED_MEMORY_LIMIT", "256M")
                        .replace("REPLACE_WITH_MEASURED_CPU_QUOTA", "100%")
                        .replace("REPLACE_WITH_MEASURED_TASK_LIMIT", "128"))

            def write_environment(mode):
                values["COMPANY_OUTBOX_MODE"] = mode
                environment.write_text("".join(f"{name}={value}\n" for name, value in values.items()))
                environment.chmod(0o600)

            for suffix, network in networks.items():
                arguments = ["network", "create", "--label", "company-registry.t01=" + prefix]
                if suffix == "company-db":
                    arguments.append("--internal")
                podman(*arguments, network)
                created_networks.append(network)
            podman("run", "-d", "--name", database, "--network", networks["company-db"],
                   "--network-alias", "postgresql", "--env", "POSTGRES_PASSWORD=admin-test",
                   "--env", "DB_PASSWORD=runtime-test", "--env", "FLYWAY_DB_PASSWORD=migration-test",
                   "--volume", str(ROOT / "deploy/database") + ":/provision:ro", "docker.io/library/postgres:18-alpine")
            created_containers.append(database)
            # The image's temporary bootstrap server accepts Unix sockets only.
            # Wait for TCP so provisioning cannot race its initialization restart.
            eventually(lambda: podman("exec", database, "pg_isready", "-h", "127.0.0.1", "-U", "postgres"))

            def sql(query):
                return podman("exec", database, "psql", "-U", "postgres", "-d", "company_registry_service", "-At", "-c", query)

            podman("exec", database, "psql", "-U", "postgres", "-f", "/provision/provision.sql")
            podman("exec", database, "psql", "-U", "postgres", "-f", "/provision/provision.sql")
            podman("exec", database, "sh", "-c", 'cat /provision/company-pg_hba.conf.example "$PGDATA/pg_hba.conf" > "$PGDATA/pg_hba.conf.new" && mv "$PGDATA/pg_hba.conf.new" "$PGDATA/pg_hba.conf"')
            sql("SELECT pg_reload_conf()")
            podman("exec", database, "psql", "-U", "postgres", "-d", "postgres", "-c", "CREATE DATABASE unrelated_service")
            for role, password in (("company_registry_runtime", "runtime-test"), ("company_registry_migration", "migration-test")):
                denied = subprocess.run(["podman", "exec", "--env", "PGPASSWORD=" + password, database,
                            "psql", "-h", "127.0.0.1", "-U", role, "-d", "unrelated_service", "-c", "SELECT 1"],
                            capture_output=True, check=False)
                assert denied.returncode != 0, "Company roles must not connect to unrelated databases"

            provider_file = work / "provider.py"
            provider_file.write_text(PROVIDER)
            provider_file.chmod(0o644)
            podman("run", "-d", "--name", provider, "--network", networks["internal-services"],
                   "--network-alias", "party-registry-service", "--network-alias", "geographic-reference-service",
                   "--volume", str(provider_file) + ":/provider.py:ro", "docker.io/library/python:3.13-slim", "python3", "/provider.py")
            created_containers.append(provider)

            def start(mode):
                write_environment(mode)
                arguments = ["run", "-d", "--name", app, "--user", "1001:1001", "--userns", "keep-id:uid=1001,gid=1001",
                             "--cap-drop=all", "--security-opt=no-new-privileges", "--read-only", "--read-only-tmpfs=true",
                             "--memory=256m", "--cpus=1", "--pids-limit=128", "--publish", "127.0.0.1:8083:8083",
                             "--env-file", str(environment), "--volume", str(keyring) + ":/run/secrets/company-keys.properties:ro"]
                for network in networks.values():
                    arguments.extend(("--network", network))
                podman(*arguments, image)
                if app not in created_containers:
                    created_containers.append(app)
                eventually(lambda: verify.request("/q/health/ready"))
                podman("exec", app, "/usr/bin/curl", "--fail", "--silent", "--max-time", "3",
                       "--output", "/dev/null", "http://127.0.0.1:8083/q/health/ready")
                inspection = json.loads(podman("inspect", app))[0]
                verify.validate_inspection(inspection, mode, set(networks.values()))
                verify.finalize_database(inspection, database)
                assert sql("SELECT has_table_privilege('company_registry_runtime','flyway_schema_history','INSERT')") == "f"

            start("disabled")
            fixtures = work / "smoke.json"
            tenant, party = str(uuid4()), str(uuid4())
            fixtures.write_text(json.dumps({"tenantId": tenant, "partyId": party}))
            verify.smoke(fixtures)
            assert sql("SELECT count(*) FROM company_outbox_events") == "0"
            site = json.loads(sql("SELECT json_build_object('company',company_id,'site',id) FROM company_sites LIMIT 1"))
            headers = {"Tenant-Id": tenant, "Process-Id": str(uuid4()), "User-Id": "acceptance"}
            site_path = f"/v1/companies/{site['company']}/sites/{site['site']}"
            podman("stop", provider)
            verify.request(site_path, headers=headers)
            try:
                verify.request(f"/v1/companies/{site['company']}", headers=headers)
                raise AssertionError("Enriched reads must report Party loss")
            except ValueError as error:
                assert "503" in str(error)
            podman("start", provider)

            with ThreadPoolExecutor(max_workers=16) as executor:
                requests = [executor.submit(verify.request, site_path, headers=headers) for _ in range(320)]
                while not all(request.done() for request in requests):
                    samples.append(json.loads(podman("stats", "--no-stream", "--format", "json", app))[0])
                for request in requests:
                    request.result()
            with urlopen(verify.BASE_URL + "/q/metrics", timeout=5) as response:
                metrics = response.read().decode()
            assert "http_client_requests" in metrics, "Provider request latency/outcomes must be measured"
            assert "postgresql_queue_size" in metrics, "Reactive pool metrics must be measured"
            assert tenant not in metrics and party not in metrics, "Identity must not become metric labels"
            evidence.joinpath("metrics.prom").write_text(metrics)
            podman("stop", database)
            verify.request("/q/health/live")
            try:
                # Quarkus 3.40's built-in reactive datasource health check waits
                # up to 20 seconds; distinguish its 503 from our client's timeout.
                verify.request("/q/health/ready", timeout=30)
                raise AssertionError("Readiness must report PostgreSQL loss")
            except ValueError as error:
                assert "503" in str(error)
            podman("start", database)
            eventually(lambda: verify.request("/q/health/ready"))
            podman("rm", "-f", app)
            start("stored-only")
            tenant, party = str(uuid4()), str(uuid4())
            fixtures.write_text(json.dumps({"tenantId": tenant, "partyId": party}))
            verify.smoke(fixtures)
            assert int(sql("SELECT count(*) FROM company_outbox_events WHERE status='PENDING'")) >= 2
            snapshot = sql("SELECT json_build_object('companies',(SELECT count(*) FROM companies),'sites',(SELECT count(*) FROM company_sites),'completions',(SELECT count(*) FROM company_idempotency_records),'pending',(SELECT count(*) FROM company_outbox_events WHERE status='PENDING'),'history',(SELECT count(*) FROM flyway_schema_history))")
            backup = work / "backup"
            result = subprocess.run(["bash", str(ROOT / "deploy/vps/backup.sh"), str(backup), database, str(keyring)],
                                    env={**os.environ, "PGPASSWORD": "migration-test"}, capture_output=True, text=True)
            assert result.returncode == 0, "Consistent backup must succeed"
            podman("rm", "-f", app)
            podman("exec", database, "psql", "-U", "postgres", "-d", "postgres", "-c", "DROP DATABASE company_registry_service WITH (FORCE)")
            podman("exec", database, "psql", "-U", "postgres", "-d", "postgres", "-c", "CREATE DATABASE company_registry_service OWNER company_registry_migration")
            podman("cp", str(backup / "company_registry_service.dump"), database + ":/tmp/company.dump")
            podman("exec", database, "pg_restore", "-U", "postgres", "--role=company_registry_migration", "--exit-on-error", "--no-owner", "--no-acl", "-d", "company_registry_service", "/tmp/company.dump")
            restored = sql("SELECT json_build_object('companies',(SELECT count(*) FROM companies),'sites',(SELECT count(*) FROM company_sites),'completions',(SELECT count(*) FROM company_idempotency_records),'pending',(SELECT count(*) FROM company_outbox_events WHERE status='PENDING'),'history',(SELECT count(*) FROM flyway_schema_history))")
            assert snapshot == restored, "Restore must preserve business, idempotency, outbox and migration history"
            assert keyring.read_bytes() == (backup / "company-keys.properties").read_bytes()
            start("stored-only")
            podman("stop", provider)
            # Both POSTs now replay stored results without Party connectivity.
            replay_headers = {"Tenant-Id": tenant, "Process-Id": str(uuid4()), "User-Id": "deployment-smoke",
                              "Idempotency-Key": "deployment-smoke-company-v1"}
            verify.request("/v1/companies", "POST", {"partyId": party, "code": "DEPLOYMENT-SMOKE",
                           "operatingName": "Deployment Smoke"}, replay_headers, 201)
            assert int(sql("SELECT count(*) FROM company_outbox_events WHERE status='PENDING'")) == json.loads(snapshot)["pending"]
            evidence.joinpath("container-stats.json").write_text(json.dumps(samples, indent=2))
            evidence.joinpath("acceptance.json").write_text(json.dumps({"image": image, "snapshot": json.loads(snapshot),
                "verified": ["three private networks", "localhost/container port 8083", "non-root/read-only/keyring",
                "disabled without RabbitMQ", "stored-only pending events", "Party failure isolation", "320 concurrent reads",
                "PostgreSQL loss/readiness recovery", "negative database access", "Flyway history protection",
                "consistent backup/restore", "durable replay after restore"]}, indent=2))
            print("Native OCI operational acceptance passed; evidence: build/t01-operations/")
    except BaseException:
        for name in created_containers:
            result = subprocess.run(["podman", "logs", "--tail=120", name], capture_output=True, text=True, check=False)
            log = evidence / (name + ".log")
            log.write_text(result.stdout + result.stderr)
            log.chmod(0o600)
        raise
    finally:
        for name in reversed(created_containers):
            subprocess.run(["podman", "rm", "-f", "-v", name], capture_output=True, check=False)
        for network in reversed(created_networks):
            subprocess.run(["podman", "network", "rm", network], capture_output=True, check=False)
    return 0


if __name__ == "__main__":
    sys.exit(main())
