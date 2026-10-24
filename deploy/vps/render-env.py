#!/usr/bin/env python3
"""Render and validate private Company Registry deployment configuration."""

import base64
import json
import os
from pathlib import Path
import re
import stat
import sys
from urllib.parse import urlsplit


KEY_PREFIX = "company-registry.idempotency.keys."
KEY_ID = re.compile(r"[A-Za-z0-9_-]{1,64}")
OPTIONAL_VALUES = {"RABBITMQ_USERNAME", "RABBITMQ_PASSWORD"}
SECRET_NAMES = ("DB_PASSWORD", "FLYWAY_DB_PASSWORD", "COMPANY_IDEMPOTENCY_KEYS_JSON")


def unique_object(pairs: list[tuple[str, str]]) -> dict[str, str]:
    """Reject duplicate JSON keys rather than silently replacing protection keys."""
    result = {}
    for name, value in pairs:
        if name in result:
            raise ValueError("Duplicate idempotency key ID")
        result[name] = value
    return result


def validate_value(name: str, value: str) -> None:
    """Keep Podman values literal and diagnostics independent of secret contents."""
    if not isinstance(value, str) or any(ord(c) < 32 or ord(c) == 127 for c in value):
        raise ValueError(f"Invalid single-line configuration: {name}")
    if "REPLACE_WITH_" in value or (not value.strip() and name not in OPTIONAL_VALUES):
        raise ValueError(f"Missing production value: {name}")


def parse_environment(content: str) -> dict[str, str]:
    """Read NAME=value without shell evaluation, interpolation, or lost spaces."""
    if "\r" in content:
        raise ValueError("Environment must use LF line endings")
    values = {}
    for line in content.split("\n"):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        name, separator, value = line.partition("=")
        if not separator or not re.fullmatch(r"[A-Z][A-Z0-9_]*", name) or name in values:
            raise ValueError("Invalid or duplicate environment variable")
        validate_value(name, value)
        values[name] = value
    return values


def decode_keys(keys: object) -> dict[str, bytes]:
    """Validate the same key IDs and minimum secret size as the application."""
    if not isinstance(keys, dict) or not keys:
        raise ValueError("Idempotency keyring must be a nonempty JSON object")
    decoded = {}
    for name, value in keys.items():
        if not isinstance(name, str) or not KEY_ID.fullmatch(name) or not isinstance(value, str):
            raise ValueError("Invalid idempotency keyring entry")
        try:
            secret = base64.b64decode(value, validate=True)
        except ValueError as error:
            raise ValueError("Invalid idempotency key encoding") from error
        if len(secret) < 32:
            raise ValueError("Idempotency keys must contain at least 32 bytes")
        decoded[name] = secret
    return decoded


def load_keys(path: Path) -> dict[str, bytes]:
    """Accept only key entries in the external properties file."""
    keys = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        name, separator, value = line.partition("=")
        if not separator or not name.startswith(KEY_PREFIX):
            raise ValueError("Unexpected property in idempotency keyring")
        key_id = name.removeprefix(KEY_PREFIX)
        if key_id in keys:
            raise ValueError("Duplicate idempotency key ID")
        keys[key_id] = value
    return decode_keys(keys)


def validate_database(values: dict[str, str]) -> None:
    """Require separate service roles and matching Company Registry databases."""
    roles = [values[name] for name in ("DB_USERNAME", "FLYWAY_DB_USERNAME")]
    if len(set(roles)) != 2 or any(not re.fullmatch(r"[a-z_][a-z0-9_]{0,62}", r) or r == "postgres" for r in roles):
        raise ValueError("Use distinct non-administrator database roles")
    endpoints = []
    for name in ("DB_REACTIVE_URL", "DB_JDBC_URL"):
        value = values[name]
        if name == "DB_JDBC_URL" and not value.startswith("jdbc:"):
            raise ValueError("DB_JDBC_URL must be a JDBC URL")
        try:
            parsed = urlsplit(value.removeprefix("jdbc:"))
            endpoint = (parsed.hostname, parsed.port or 5432, parsed.path)
        except ValueError:
            raise ValueError(f"Invalid private database URL: {name}") from None
        if (parsed.scheme != "postgresql" or not parsed.hostname or parsed.username or parsed.password
                or parsed.path != "/company_registry_service" or parsed.fragment):
            raise ValueError(f"Invalid private database URL: {name}")
        endpoints.append(endpoint)
    if endpoints[0] != endpoints[1]:
        raise ValueError("Runtime and Flyway must target the same logical database")


def validate_service_urls(values: dict[str, str]) -> None:
    """Ensure dependent service URLs target internal private hosts."""
    for name, host in (("PARTY_REGISTRY_BASE_URL", "party-registry-service"),
                       ("GEOGRAPHIC_REFERENCE_BASE_URL", "geographic-reference-service")):
        try:
            parsed = urlsplit(values[name])
            valid_port = parsed.port is None or 1 <= parsed.port <= 65535
        except ValueError:
            raise ValueError(f"Invalid internal service URL: {name}") from None
        if (not valid_port or parsed.scheme not in {"http", "https"} or parsed.hostname != host
                or parsed.username is not None or parsed.password is not None or parsed.query or parsed.fragment):
            raise ValueError(f"Use the internal service name: {name}")


def validate_messaging(values: dict[str, str]) -> None:
    """Ensure valid outbox mode and private RabbitMQ settings."""
    mode = values["COMPANY_OUTBOX_MODE"]
    if mode not in {"disabled", "stored-only", "published"}:
        raise ValueError("Invalid COMPANY_OUTBOX_MODE")
    if mode == "published" and any(not values.get(n, "").strip() for n in ("RABBITMQ_HOST", *OPTIONAL_VALUES)):
        raise ValueError("Published mode requires RabbitMQ host and credentials")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", values["RABBITMQ_HOST"]):
        raise ValueError("RABBITMQ_HOST must be a private hostname")


def validate_limits(values: dict[str, str]) -> None:
    """Ensure resource limits and integer settings remain within bounded ranges."""
    integer_ranges = {"RABBITMQ_PORT": (1, 65535), "COMPANY_PIDS_LIMIT": (16, 65535),
                      "QUARKUS_DATASOURCE_REACTIVE_MAX_SIZE": (1, 500)}
    for name, (minimum, maximum) in integer_ranges.items():
        if not values[name].isascii() or not values[name].isdecimal() or not minimum <= int(values[name]) <= maximum:
            raise ValueError(f"Invalid bounded integer: {name}")
    if not re.fullmatch(r"[1-9]\d*[MG]", values["COMPANY_MEMORY_MAX"]):
        raise ValueError("COMPANY_MEMORY_MAX must be a positive M/G limit")
    if not re.fullmatch(r"[1-9]\d{0,3}%", values["COMPANY_CPU_QUOTA"]):
        raise ValueError("COMPANY_CPU_QUOTA must be a positive percentage")


def validate_configuration(values: dict[str, str], keys: dict[str, bytes]) -> None:
    """Enforce the agreed port, production profile, private URLs, and bounded limits."""
    expected = {"QUARKUS_PROFILE": "prod", "QUARKUS_HTTP_HOST": "0.0.0.0", "QUARKUS_HTTP_PORT": "8083",
                "QUARKUS_CONFIG_LOCATIONS": "file:/run/secrets/company-keys.properties"}
    if any(values.get(name) != value for name, value in expected.items()):
        raise ValueError("Production requires port 8083 and the protected keyring location")
    if values["COMPANY_IDEMPOTENCY_ACTIVE_KEY_ID"] not in keys:
        raise ValueError("Active idempotency key is absent from the keyring")
    validate_database(values)
    if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_.-]*", values["POSTGRES_CONTAINER"]):
        raise ValueError("Invalid PostgreSQL container name")
    validate_service_urls(values)
    validate_messaging(values)
    validate_limits(values)


def private_file(path: Path) -> None:
    """Reject links and group/world-readable production configuration."""
    metadata = path.lstat()
    if not stat.S_ISREG(metadata.st_mode) or metadata.st_mode & 0o077 or metadata.st_uid != os.getuid():
        raise ValueError("Configuration must be a deployment-user-owned regular file with mode 0600")


def validate_files(environment: Path, keyring: Path, previous: Path | None = None) -> dict[str, str]:
    """Preflight a deployment and forbid removal or replacement of historical keys."""
    private_file(environment)
    private_file(keyring)
    values = parse_environment(environment.read_text(encoding="utf-8"))
    keys = load_keys(keyring)
    validate_configuration(values, keys)
    if previous is not None:
        private_file(previous)
        if any(keys.get(name) != secret for name, secret in load_keys(previous).items()):
            raise ValueError("Historical idempotency keys cannot be removed or replaced")
    return values


def write_private(path: Path, content: str) -> None:
    """Create a new private file without following or replacing an existing path."""
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as output:
        output.write(content)


def render(template: Path, output: Path, keyring: Path) -> None:
    """Render environment and a separate keyring from protected workflow inputs."""
    for name in SECRET_NAMES:
        if not os.environ.get(name, "").strip():
            raise ValueError(f"Missing production secret: {name}")
    try:
        keys = decode_keys(json.loads(os.environ["COMPANY_IDEMPOTENCY_KEYS_JSON"], object_pairs_hook=unique_object))
    except json.JSONDecodeError as error:
        raise ValueError("Invalid idempotency keyring JSON") from error
    lines = []
    for line in template.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            lines.append(line)
            continue
        name, separator, default = line.partition("=")
        if not separator:
            raise ValueError("Invalid environment template")
        # Optional broker placeholders represent absent credentials, not secrets.
        if name in OPTIONAL_VALUES and "REPLACE_WITH_" in default:
            default = ""
        value = os.environ.get(name)
        if value is None or (not value and name not in OPTIONAL_VALUES):
            value = default
        lines.append(f"{name}={value}")
    content = "\n".join(lines) + "\n"
    validate_configuration(parse_environment(content), keys)
    properties = "".join(f"{KEY_PREFIX}{name}={base64.b64encode(secret).decode()}\n" for name, secret in sorted(keys.items()))
    write_private(keyring, properties)
    try:
        write_private(output, content)
    except BaseException:
        keyring.unlink()
        raise


def render_quadlet(arguments: list[str]) -> None:
    """Substitute only validated configuration into the reviewed Quadlet template."""
    template, output, image, environment, keyring = arguments
    values = validate_files(Path(environment), Path(keyring))
    replacements = {"IMAGE": image, "ENVIRONMENT_FILE": environment, "KEYRING_FILE": keyring,
                    "MEMORY_MAX": values["COMPANY_MEMORY_MAX"], "CPU_QUOTA": values["COMPANY_CPU_QUOTA"],
                    "PIDS_LIMIT": values["COMPANY_PIDS_LIMIT"]}
    content = Path(template).read_text(encoding="utf-8")
    for name, value in replacements.items():
        content = content.replace(f"@@{name}@@", value)
    if "@@" in content:
        raise ValueError("Unresolved Quadlet placeholder")
    # The deployment script owns this mktemp-created file.
    Path(output).write_text(content, encoding="utf-8")


def main() -> int:
    """Expose generation and read-only deployment preflight without secret output."""
    try:
        arguments = sys.argv[1:]
        if arguments[:1] == ["--validate"] and len(arguments) in {3, 4}:
            validate_files(*(Path(value) for value in arguments[1:]))
        elif arguments[:1] == ["--quadlet"] and len(arguments) == 6:
            render_quadlet(arguments[1:])
        elif len(arguments) == 3:
            render(*(Path(value) for value in arguments))
        else:
            raise ValueError("Usage: render-env.py TEMPLATE OUTPUT_ENV OUTPUT_KEYRING | --validate ENV KEYS [OLD_KEYS] | --quadlet TEMPLATE OUTPUT IMAGE ENV KEYS")
    except (ValueError, OSError, KeyError) as error:
        # KeyError contains a variable name; decoding errors are sanitized above.
        message = str(error) if isinstance(error, (ValueError, KeyError)) else "Unable to access private configuration files"
        print(f"ERROR: {message}", file=sys.stderr)
        return 1
    print("Company Registry deployment configuration validated")
    return 0


if __name__ == "__main__":
    sys.exit(main())
