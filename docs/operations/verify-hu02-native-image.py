"""Verify the actual non-root HU02 image; all database/provider fixtures stay external."""
import base64
import hashlib
import json
import os
import pathlib
import socket
import subprocess
import time
import urllib.error
import urllib.request
import uuid

ROOT = pathlib.Path.cwd()
EVIDENCE = pathlib.Path(os.environ.get('EVIDENCE_DIR', ROOT / 'build' / 'hu02-evidence' / 'oci'))
EVIDENCE.mkdir(parents=True, exist_ok=True)
IMAGE = 'company-registry-service:hu02-native'
suffix = uuid.uuid4().hex[:10]
database = 'codex-hu02-db-' + suffix
application = None


def podman(*args, **kwargs):
    return subprocess.run(['podman', *args], check=True, capture_output=True, text=True, **kwargs).stdout.strip()


def sql(statement):
    return podman('exec', database, 'psql', '-v', 'ON_ERROR_STOP=1', '-At', '-U', 'postgres', '-d', 'hu02', '-c', statement)


def request(base, path, method='GET', body=None, headers=None):
    payload = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    req = urllib.request.Request(base + path, data=payload, method=method,
                                 headers={'Accept': 'application/json', **({'Content-Type': 'application/json'} if payload is not None else {}), **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as response:
        return response.code, json.load(response)


def start(mode, active):
    global application
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    application = 'codex-hu02-app-' + suffix
    env = {'DB_USERNAME': 'company_registry_runtime', 'DB_PASSWORD': 'oci_runtime_test',
           'DB_REACTIVE_URL': f'postgresql://127.0.0.1:{database_port}/hu02',
           'DB_JDBC_URL': f'jdbc:postgresql://127.0.0.1:{database_port}/hu02',
           'FLYWAY_DB_USERNAME': 'company_registry_migration', 'FLYWAY_DB_PASSWORD': 'oci_migration_test',
           'COMPANY_OUTBOX_MODE': mode, 'PARTY_REGISTRY_BASE_URL': 'http://127.0.0.1:9',
           'GEOGRAPHIC_REFERENCE_BASE_URL': 'http://127.0.0.1:9',
           'RABBITMQ_HOST': '127.0.0.1', 'RABBITMQ_PORT': '9'}
    args = ['run', '-d', '--name', application, '--network', 'host']
    for key, value in env.items():
        args += ['-e', key + '=' + value]
    args += [IMAGE, '-Dquarkus.http.host=127.0.0.1', f'-Dquarkus.http.port={port}',
             '-Dcompany-registry.idempotency.active-key-id=' + active,
             '-Dcompany-registry.idempotency.keys.oci-k1=' + base64.b64encode(bytes(range(32))).decode(),
             '-Dcompany-registry.idempotency.keys.oci-k2=' + base64.b64encode(bytes(range(32, 64))).decode()]
    podman(*args)
    base = f'http://127.0.0.1:{port}'
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        try:
            if request(base, '/q/health/ready')[0] == 200:
                return base
        except (OSError, ValueError):
            pass
        time.sleep(.2)
    raise RuntimeError('HU02 image did not become ready: ' + podman('logs', application))


def stop(log_name):
    global application
    if application:
        (EVIDENCE / log_name).write_text(podman('logs', application))
        podman('rm', '-f', application)
        application = None


report = {'image': IMAGE, 'modes': [], 'runnerSha256': hashlib.sha256((ROOT / 'build/company-registry-service-1.0.0-SNAPSHOT-runner').read_bytes()).hexdigest()}
with socket.socket() as refused:
    refused.settimeout(1)
    refusal = refused.connect_ex(('127.0.0.1', 9))
    if refusal == 0:
        raise RuntimeError('The provider/broker refusal endpoint must be unreachable for this verification')
    report['dependencyRefusalProbe'] = {'endpoint': '127.0.0.1:9', 'connectErrno': refusal}

try:
    report['imageId'] = podman('image', 'inspect', '--format', '{{.Id}}', IMAGE)
    report['configuredUser'] = podman('image', 'inspect', '--format', '{{.Config.User}}', IMAGE)
    assert report['configuredUser'] == '1001', report
    podman('run', '-d', '--name', database, '-p', '127.0.0.1::5432', '-e', 'POSTGRES_DB=hu02', '-e', 'POSTGRES_USER=postgres',
           '-e', 'POSTGRES_PASSWORD=oci_admin_test', '-e', 'DB_PASSWORD=oci_runtime_test', '-e', 'FLYWAY_DB_PASSWORD=oci_migration_test', 'docker.io/library/postgres:18-alpine')
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        probe = subprocess.run(['podman', 'exec', database, 'pg_isready', '-U', 'postgres', '-d', 'hu02'], capture_output=True)
        if probe.returncode == 0:
            break
        time.sleep(.2)
    else:
        raise RuntimeError('HU02 database did not start')
    podman('exec', '-i', database, 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'hu02', input=(ROOT / 'docs/database/bootstrap-local.sql').read_text())
    database_port = podman('port', database, '5432/tcp').rsplit(':', 1)[1]
    for mode in ['disabled', 'stored-only', 'published']:
        base = start(mode, 'oci-k1')
        assert podman('exec', application, 'id', '-u') == '1001'
        (EVIDENCE / f'{mode}-process.txt').write_text(podman('top', application, 'user', 'args'))
        tenant = str(uuid.uuid4())
        company = sql(f"INSERT INTO companies(tenant_id,party_id,code,operating_name,description,logo_url,created_by,updated_by) VALUES('{tenant}',uuidv7(),'CODE','COMPANY','DESCRIPTION','https://host/LogoAb.png','Creator','Creator') RETURNING id;").splitlines()[0]
        path = '/v1/companies/' + company
        headers = {'Tenant-Id': tenant, 'Process-Id': str(uuid.uuid4()), 'User-Id': 'Updater-aB', 'If-Match': '0', 'Idempotency-Key': 'change'}
        body = {'operatingName': ' compañía ágil ', 'description': None, 'logoUrl': None}
        status, accepted = request(base, path, 'PATCH', body, headers)
        assert status == 200, accepted
        original = accepted['data']
        assert original['version'] == 1 and original['operatingName'] == 'COMPAÑÍA ÁGIL'
        assert not {'party', 'description', 'logoUrl'} & original.keys()
        noop_headers = {**headers, 'If-Match': '1', 'Idempotency-Key': 'noop'}
        assert request(base, path, 'PATCH', {}, noop_headers)[1]['data'] == original
        unkeyed = {key: value for key, value in noop_headers.items() if key != 'Idempotency-Key'}
        assert request(base, path, 'PATCH', {'operatingName': 'LATER'}, unkeyed)[1]['data']['version'] == 2
        sql(f"UPDATE companies SET status='ARCHIVED',version=version+1,updated_by='Archiver' WHERE id='{company}'")
        contract_status, contract = request(base, '/q/openapi')
        assert contract_status == 200 and 'patch' in contract['paths']['/v1/companies/{companyId}']
        assert request(base, '/v1/_verification/success', headers=headers)[0] == 404
        counts = sql(f"SELECT (SELECT count(*) FROM companies WHERE tenant_id='{tenant}'),(SELECT count(*) FROM company_idempotency_records WHERE tenant_id='{tenant}'),(SELECT count(*) FROM company_outbox_events WHERE tenant_id='{tenant}');")
        assert counts == ('1|2|0' if mode == 'disabled' else '1|2|2'), counts
        if mode != 'disabled':
            assert sql(f"SELECT bool_and(status='PENDING' AND publish_attempts=0 AND version=0 AND published_at IS NULL) FROM company_outbox_events WHERE tenant_id='{tenant}'") == 't'
            events = json.loads(sql(f"SELECT json_agg(row_to_json(e) ORDER BY aggregate_version) FROM company_outbox_events e WHERE tenant_id='{tenant}'"))
            (EVIDENCE / f'events-{mode}.json').write_text(json.dumps(events, indent=2))
            for event in events:
                assert len(event['payload']) == 7 and event['payload']['companyId'] == company
                assert event['payload']['updatedBy'] == 'Updater-aB' and event['causation_id'] is None
        stop(f'{mode}-original.log')
        base = start('disabled', 'oci-k2')
        replay_headers = {**headers, 'Process-Id': str(uuid.uuid4()), 'User-Id': 'Replay-aB'}
        status, replay = request(base, path, 'PATCH', body, replay_headers)
        assert status == 200 and replay['data'] == original
        assert request(base, path, 'PATCH', {}, noop_headers)[1]['data'] == original
        assert sql(f"SELECT version FROM companies WHERE id='{company}'") == '3'
        assert counts == sql(f"SELECT (SELECT count(*) FROM companies WHERE tenant_id='{tenant}'),(SELECT count(*) FROM company_idempotency_records WHERE tenant_id='{tenant}'),(SELECT count(*) FROM company_outbox_events WHERE tenant_id='{tenant}');")
        stop(f'{mode}-restarted.log')
        report['modes'].append({'mode': mode, 'counts': counts, 'replayAfterRestartRotationArchival': True, 'providers': 'connection refused', 'broker': '127.0.0.1:9', 'user': '1001'})
    (EVIDENCE / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))
finally:
    if application:
        subprocess.run(['podman', 'rm', '-f', application], capture_output=True)
    subprocess.run(['podman', 'rm', '-f', database], capture_output=True)
