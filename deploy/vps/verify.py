#!/usr/bin/env python3
"""Verify private container deployment and optionally exercise durable smoke fixtures."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import UUID, uuid4


BASE_URL = "http://127.0.0.1:8083"
NETWORKS = {"company-db", "internal-services", "messaging"}
APPLICATION_TABLES = ("companies", "company_sites", "company_site_addresses", "company_site_contact_points",
                      "company_idempotency_records", "company_outbox_events")


def finalize_database(container: dict, postgres_container: str | None = None) -> None:
    """Apply minimal runtime grants after Flyway, using its existing migration identity."""
    environment = {name: val for value in container["Config"]["Env"] if "=" in value for name, val in [value.split("=", 1)]}
    runtime, migration = (environment[name] for name in ("DB_USERNAME", "FLYWAY_DB_USERNAME"))
    if any(not re.fullmatch(r"[a-z_][a-z0-9_]{0,62}", role) or role == "postgres" for role in (runtime, migration)) or runtime == migration:
        raise ValueError("Unsafe database role configuration")
    password = environment["FLYWAY_DB_PASSWORD"]
    target = postgres_container or environment["POSTGRES_CONTAINER"]
    if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_.-]*", target):
        raise ValueError("Invalid PostgreSQL container name")
    tables = ",".join("public." + table for table in APPLICATION_TABLES)
    sql = (f'GRANT SELECT, INSERT, UPDATE, DELETE ON {tables} TO "{runtime}";\n'
           f'REVOKE ALL ON public.flyway_schema_history FROM "{runtime}";\n'
           f"SELECT NOT has_table_privilege('{runtime}', 'public.flyway_schema_history', 'INSERT');\n")
    result = subprocess.run(["podman", "exec", "-i", "--env", "PGPASSWORD", target, "psql", "--no-psqlrc",
                             "--set=ON_ERROR_STOP=1", "--host=127.0.0.1", "--username=" + migration,
                             "--dbname=company_registry_service", "-At"],
                            input=sql, capture_output=True, text=True, check=True,
                            env={**os.environ, "PGPASSWORD": password})
    if result.stdout.splitlines()[-1:] != ["t"]:
        raise ValueError("Runtime still has write access to Flyway history")


def validate_inspection(container: dict, mode: str, networks: set[str] = NETWORKS) -> None:
    """Check effective network, port, user, and security restrictions without logging env."""
    if not container["State"]["Running"]:
        raise ValueError("Company Registry container is not running")
    if set(container["NetworkSettings"]["Networks"]) != networks:
        raise ValueError("Company Registry must join exactly the three approved networks")
    ports = container["NetworkSettings"]["Ports"]
    if ports != {"8083/tcp": [{"HostIp": "127.0.0.1", "HostPort": "8083"}]}:
        raise ValueError("Company Registry must publish only 127.0.0.1:8083 to container port 8083")
    config = container["Config"]
    if config["User"] != "1001:1001":
        raise ValueError("Company Registry must run as the non-root application user")
    environment = {name: val for value in config["Env"] if "=" in value for name, val in [value.split("=", 1)]}
    if environment.get("COMPANY_OUTBOX_MODE") != mode or environment.get("QUARKUS_HTTP_PORT") != "8083":
        raise ValueError("Effective application mode or port does not match the deployment")
    host = container["HostConfig"]
    if not host["ReadonlyRootfs"] or "no-new-privileges" not in host["SecurityOpt"]:
        raise ValueError("Container filesystem and privilege restrictions are missing")
    if host.get("CapAdd"):
        raise ValueError("Unexpected added container capabilities")


def request(path: str, method: str = "GET", body: dict | None = None,
            headers: dict | None = None, expected: int = 200, timeout: float = 15) -> dict:
    """Perform bounded local requests and disclose only status on failure."""
    data = None if body is None else json.dumps(body).encode()
    outgoing = Request(BASE_URL + path, data=data, method=method,
                       headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urlopen(outgoing, timeout=timeout) as response:
            if response.status != expected:
                raise ValueError("Unexpected smoke response status")
            return json.load(response)
    except HTTPError as error:
        raise ValueError(f"Deployment HTTP verification failed with status {error.code}") from None


def accept_and_replay(path: str, body: dict, headers: dict, key: str) -> dict:
    """Verify an accepted original result is preserved by an identical keyed retry."""
    context = {**headers, "Idempotency-Key": key}
    original = request(path, "POST", body, context, 201)["data"]
    replay = request(path, "POST", body, {**context, "Process-Id": str(uuid4())}, 201)["data"]
    if original != replay:
        raise ValueError("Deployment idempotency replay did not preserve the original result")
    return original


def smoke(fixtures: Path) -> None:
    """Reuse a dedicated Company and Site through stable idempotency keys, preserving history."""
    settings = json.loads(fixtures.read_text(encoding="utf-8"))
    tenant = str(UUID(settings["tenantId"]))
    party = str(UUID(settings["partyId"]))
    headers = {"Tenant-Id": tenant, "Process-Id": str(uuid4()), "User-Id": "deployment-smoke"}
    company = accept_and_replay("/v1/companies", {"partyId": party, "code": "DEPLOYMENT-SMOKE",
                               "operatingName": "Deployment Smoke"}, headers, "deployment-smoke-company-v1")
    company_id = str(UUID(company["companyId"]))
    request(f"/v1/companies/{company_id}", headers=headers)
    site = accept_and_replay(f"/v1/companies/{company_id}/sites", {"code": "SMOKE-SITE", "name": "Deployment Smoke Site"},
                             headers, "deployment-smoke-site-v1")
    site_id = str(UUID(site["siteId"]))
    request(f"/v1/companies/{company_id}/sites/{site_id}", headers=headers)
    print("Dedicated Company/Site creation, replay, and retrieval verified")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("disabled", "stored-only", "published"), default="disabled")
    parser.add_argument("--smoke-fixtures", type=Path)
    parser.add_argument("--finalize-database", action="store_true",
                        help="Grant application DML and revoke runtime Flyway history access after migration")
    args = parser.parse_args()
    try:
        result = subprocess.run(["podman", "inspect", "company-registry-service"],
                                capture_output=True, text=True, check=True)
        container = json.loads(result.stdout)[0]
        validate_inspection(container, args.mode)
        if args.finalize_database:
            finalize_database(container)
        for endpoint in ("live", "ready", "started"):
            if request("/q/health/" + endpoint).get("status") != "UP":
                raise ValueError("Application health is not UP")
        with urlopen(BASE_URL + "/q/metrics", timeout=5) as response:
            metrics = response.read().decode()
        if not all(name in metrics for name in ("company_outbox_pending", "company_outbox_oldest_age_seconds", "postgresql_")):
            raise ValueError("Required outbox or reactive pool metrics are missing")
        if args.smoke_fixtures:
            smoke(args.smoke_fixtures)
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        print("ERROR: " + (str(error) if isinstance(error, ValueError) else "Deployment inspection could not complete"), file=sys.stderr)
        return 1
    print("Three private networks, localhost:8083, container security, health, and metrics verified")
    return 0


if __name__ == "__main__":
    sys.exit(main())
