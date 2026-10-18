"""Verify the actual non-root HU03 image; all database/provider fixtures stay external."""
import base64
import hashlib
import json
import os
import pathlib
import select
import socket
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor

ROOT = pathlib.Path.cwd()
EVIDENCE = pathlib.Path(os.environ.get('EVIDENCE_DIR', ROOT / 'build' / 'hu03-evidence' / 'oci'))
EVIDENCE.mkdir(parents=True, exist_ok=True)
APPLICATION_JSON = 'application/json'
COMPANIES_PATH = '/v1/companies'
IMAGE = 'company-registry-service:hu03-native'
ON_ERROR_STOP = 'ON_ERROR_STOP=1'
suffix = uuid.uuid4().hex[:10]
database = f'codex-hu03-db-{suffix}'
application = None


def podman(*args, **kwargs):
    return subprocess.run(['podman', *args], check=True, capture_output=True, text=True, **kwargs).stdout.strip()


def sql(statement):
    return podman('exec', database, 'psql', '-v', ON_ERROR_STOP, '-At', '-U', 'postgres', '-d', 'hu03', '-c', statement)


def request(base, path, method='GET', body=None, headers=None):
    payload = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    request_headers = {'Accept': APPLICATION_JSON}
    if payload is not None:
        request_headers['Content-Type'] = APPLICATION_JSON
    if headers:
        request_headers |= headers
    req = urllib.request.Request(f'{base}{path}', data=payload, method=method, headers=request_headers)
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as response:
        return response.code, json.load(response)


def start(mode, active, migrate=True):
    global application
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    application = f'codex-hu03-app-{suffix}'
    env = {'DB_USERNAME': 'company_registry_runtime', 'DB_PASSWORD': 'oci_runtime_test',
           'DB_REACTIVE_URL': f'postgresql://127.0.0.1:{database_port}/hu03',
           'DB_JDBC_URL': f'jdbc:postgresql://127.0.0.1:{database_port}/hu03',
           'FLYWAY_DB_USERNAME': 'company_registry_migration', 'FLYWAY_DB_PASSWORD': 'oci_migration_test',
           'COMPANY_OUTBOX_MODE': mode, 'PARTY_REGISTRY_BASE_URL': f'http://127.0.0.1:{provider.server_port}',
           'COMPANY_LIFECYCLE_WAIT_BUDGET': '2s', 'COMPANY_SITE_REGISTRATION_WAIT_BUDGET': '2s', 'COMPANY_SITE_MUTATION_WAIT_BUDGET': '2s',
           'GEOGRAPHIC_REFERENCE_BASE_URL': 'http://127.0.0.1:9',
           'RABBITMQ_HOST': '127.0.0.1', 'RABBITMQ_PORT': '9'}
    args = ['run', '-d', '--name', application, '--network', 'host']
    for key, value in env.items():
        args += ['-e', f'{key}={value}']
    k1 = base64.b64encode(bytes(range(32))).decode()
    k2 = base64.b64encode(bytes(range(32, 64))).decode()
    args += [IMAGE, '-Dquarkus.http.host=127.0.0.1', f'-Dquarkus.http.port={port}',
             f'-Dcompany-registry.idempotency.active-key-id={active}',
             f'-Dcompany-registry.idempotency.keys.oci-k1={k1}',
             f'-Dcompany-registry.idempotency.keys.oci-k2={k2}']
    if not migrate:
        args += ['-Dquarkus.flyway.migrate-at-start=false']
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
    raise RuntimeError(f'HU03 image did not become ready: {podman("logs", application)}')


def stop(log_name):
    global application
    if application:
        (EVIDENCE / log_name).write_text(podman('logs', application))
        podman('rm', '-f', application)
        application = None


from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import threading


class PartyProvider(BaseHTTPRequestHandler):
    available = True
    minimal = False
    kind = 'LEGAL_ENTITY'
    requests = []

    def log_message(self, *args):
        # Suppress default server request logging.
        pass

    def do_GET(self):
        PartyProvider.requests.append((self.path, dict(self.headers)))
        status = 200 if PartyProvider.available else 503
        if not PartyProvider.available:
            body = {'status': 503, 'code': 'dependency-unavailable'}
        elif '/identifiers' in self.path:
            body = {'status': 200, 'code': 'successful', 'data': [], 'numberOfElements': 0,
                    'totalElements': 0, 'totalPages': 0, 'nextCursor': None, 'prevCursor': None}
        else:
            party = self.path.split('/')[-1]
            data = {'partyId': party, 'type': PartyProvider.kind, 'recordStatus': 'ACTIVE'}
            if not PartyProvider.minimal:
                data.update({'displayName': 'COMPANY', 'version': 3, 'createdAt': '2026-10-17T14:30:00.123456Z',
                             'updatedAt': '2026-10-17T14:30:00.123456Z', 'createdBy': 'Creator', 'updatedBy': 'Editor',
                             'legalEntityDetails': {'legalName': 'COMPANY', 'tradeName': 'COMPANY', 'legalFormCode': 'SAS',
                                                    'incorporationCountryCode': 'EC', 'incorporatedOn': '2020-01-15', 'dissolvedOn': None}})
            body = {'status': 200, 'code': 'successful', 'data': data}
        payload = json.dumps(body).encode()
        self.send_response(status)
        self.send_header('Content-Type', APPLICATION_JSON)
        self.send_header('Content-Length', str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


def prepare_v5():
    jars = sorted((ROOT / 'build/quarkus-app/lib/main').glob('*.jar'))
    if not jars:
        jars = sorted((ROOT / 'build/hu03-evidence/jvm-app/lib/main').glob('*.jar'))
    assert jars, 'Build the JVM artifact first for external Flyway provisioning'
    helper = EVIDENCE / 'PrepareV5.java'
    helper.write_text('import org.flywaydb.core.Flyway;\nclass PrepareV5 {\n'
                      'public static void main(String[] args) {\n'
                      'Flyway.configure().dataSource(args[0], "company_registry_migration", "oci_migration_test")\n'
                      '.locations("filesystem:" + args[1]).target("5").load().migrate();\n}}\n')
    result = subprocess.run(['java', '-cp', os.pathsep.join(str(path) for path in jars), str(helper.resolve()),
                             f'jdbc:postgresql://127.0.0.1:{database_port}/hu03', str(ROOT / 'src/main/resources/db/migration')],
                            check=True, capture_output=True, text=True)
    (EVIDENCE / 'prepare-v5.log').write_text(result.stdout + result.stderr)


def headers(tenant, key=None, version=None):
    result = {'Tenant-Id': tenant, 'User-Id': 'Actor-aB', 'Process-Id': str(uuid.uuid4())}
    if key is not None:
        result['Idempotency-Key'] = key
    if version is not None:
        result['If-Match'] = str(version)
    return result


def assert_response(actual, status, code=None):
    assert actual[0] == status, actual
    if code:
        assert actual[1] == {'status': status, 'code': code}, actual
    return actual[1].get('data')


def legacy_fixture(base):
    tenant = str(uuid.uuid4())
    party = str(uuid.uuid4())
    calls = []
    body = {'partyId': party, 'code': 'LEGACY', 'operatingName': 'LEGACY'}
    h = headers(tenant, 'legacy-company')
    company = assert_response(request(base, COMPANIES_PATH, 'POST', body, h), 201)
    calls.append((COMPANIES_PATH, 'POST', body, h, company, 201))
    path = f"{COMPANIES_PATH}/{company['companyId']}"
    h = headers(tenant, 'legacy-update', 0)
    update = assert_response(request(base, path, 'PATCH', {'operatingName': 'UPDATED'}, h), 200)
    calls.append((path, 'PATCH', {'operatingName': 'UPDATED'}, h, update, 200))
    h = headers(tenant, 'legacy-site')
    site_body = {'code': 'LEGACY', 'name': 'LEGACY', 'siteType': 'HEADQUARTERS'}
    sites_path = f'{path}/sites'
    site = assert_response(request(base, sites_path, 'POST', site_body, h), 201)
    calls.append((sites_path, 'POST', site_body, h, site, 201))
    h = headers(tenant, 'legacy-site-activate', 0)
    site_path = f"{sites_path}/{site['siteId']}"
    activate_path = f'{site_path}/activate'
    active = assert_response(request(base, activate_path, 'POST', None, h), 200)
    calls.append((activate_path, 'POST', None, h, active, 200))
    sql(f"INSERT INTO company_site_addresses(site_id,country_code,address_line_1,created_by,updated_by) VALUES('{site['siteId']}','EC','ADDRESS','Creator','Creator')")
    sql(f"INSERT INTO company_site_contact_points(site_id,contact_type,value,created_by,updated_by) VALUES('{site['siteId']}','EMAIL','User@example.com','Creator','Creator')")
    return calls


def table_snapshots():
    return {table: sql(f"SELECT jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text FROM {table} t") for table in
            ['companies', 'company_sites', 'company_site_addresses', 'company_site_contact_points',
             'company_idempotency_records', 'company_outbox_events']}


def replay_legacy(base, calls):
    for path, method, body, h, data, status in calls:
        assert assert_response(request(base, path, method, body, h), status) == data


LIFECYCLE_STATUS = {
    'activate': 'ACTIVE',
    'deactivate': 'INACTIVE',
    'archive': 'ARCHIVED',
}
LIFECYCLE_EVENT_TYPE = {
    'activate': 'company.activated.v1',
    'deactivate': 'company.deactivated.v1',
    'archive': 'company.updated.v1',
}


def assert_lifecycle_event(event, attempt, company):
    action, version, _key, data = attempt
    assert event['event_type'] == LIFECYCLE_EVENT_TYPE[action]
    assert event['aggregate_version'] == version + 1
    assert event['aggregate_type'] == 'COMPANY'
    assert event['payload']['status'] == data['status']
    assert event['payload']['updatedAt'] == data['updatedAt']
    assert event['payload']['companyId'] == company
    assert event['payload']['updatedBy'] == 'Actor-aB'
    assert len(event['payload']) == 7
    assert event['causation_id'] is None
    assert event['status'] == 'PENDING'
    assert event['publish_attempts'] == 0
    assert event['version'] == 0
    assert event['published_at'] is None
    assert event['id'] != company
    assert uuid.UUID(event['id']).version == 7


def lifecycle_fixture(base, mode):
    tenant = str(uuid.uuid4())
    party = str(uuid.uuid4())
    company = sql(f"INSERT INTO companies(tenant_id,party_id,code,operating_name,created_by,updated_by) VALUES('{tenant}','{party}','HU03','COMPANY','Creator','Creator') RETURNING id").splitlines()[0]
    path = f'{COMPANIES_PATH}/{company}'
    activate_path = f'{path}/activate'
    sites_path = f'{path}/sites'
    assert_response(request(base, activate_path, 'POST', None, headers(tenant, 'reusable', 0)), 409, 'legal-headquarters-required')
    site = assert_response(request(base, sites_path, 'POST', {'code': 'HQ', 'name': 'HQ', 'siteType': 'HEADQUARTERS'}, headers(tenant, 'site')), 201)
    site_path = f"{sites_path}/{site['siteId']}"
    assert_response(request(base, f'{site_path}/activate', 'POST', None, headers(tenant, 'site-activate', 0)), 200)
    site_before = sql(f"SELECT to_jsonb(s) FROM company_sites s WHERE id='{site['siteId']}'")
    PartyProvider.minimal = True
    PartyProvider.kind = 'NATURAL_PERSON'
    assert_response(request(base, activate_path, 'POST', None, headers(tenant, 'reusable', 0)), 409, 'party-must-be-legal-entity')
    PartyProvider.kind = 'LEGAL_ENTITY'
    PartyProvider.requests.clear()
    attempts = []
    provider_calls = 0
    for version, action in enumerate(['activate', 'deactivate', 'activate', 'deactivate', 'archive']):
        h = headers(tenant, f'hu03-{version}', version)
        data = assert_response(request(base, f'{path}/{action}', 'POST', None if version % 2 == 0 else {}, h), 200)
        assert data['version'] == version + 1
        assert len(data) == 10, data
        assert data['status'] == LIFECYCLE_STATUS[action], data
        assert 'description' not in data
        assert 'logoUrl' not in data
        assert 'party' not in data
        attempts.append((action, version, h['Idempotency-Key'], data))
        if version == 2:
            PartyProvider.available = False
            provider_calls = len(PartyProvider.requests)
    assert len(PartyProvider.requests) == provider_calls == 2, PartyProvider.requests
    assert all(p == f'/v1/parties/{party}' for p, h in PartyProvider.requests), PartyProvider.requests
    assert site_before == sql(f"SELECT to_jsonb(s) FROM company_sites s WHERE id='{site['siteId']}'")
    assert assert_response(request(base, site_path, headers=headers(tenant)), 200)['effectivelyOperational'] is False
    assert_response(request(base, f'{path}/archive', 'POST', None, headers(tenant, None, 5)), 409, 'company-archived')
    assert_response(request(base, activate_path, 'POST', {'reason': 'invalid'}, headers(tenant, 'hu03-0', 0)), 400, 'request-body-contains-unknown-fields')
    assert sql(f"SELECT bool_and(result_snapshot->'description'='null'::jsonb AND result_snapshot->'logoUrl'='null'::jsonb) FROM company_idempotency_records WHERE company_id='{company}' AND operation IN ('company.activate.v1','company.deactivate.v1','company.archive.v1')") == 't'
    events = json.loads(sql(f"SELECT coalesce(json_agg(row_to_json(e) ORDER BY aggregate_version),'[]'::json) FROM company_outbox_events e WHERE aggregate_id='{company}'"))
    assert len(events) == (0 if mode == 'disabled' else 5), events
    for event, attempt in zip(events, attempts):
        assert_lifecycle_event(event, attempt, company)
    (EVIDENCE / f'events-{mode}.json').write_text(json.dumps(events, indent=2))
    return {'tenant': tenant, 'company': company, 'path': path, 'attempts': attempts, 'eventCount': len(events)}


def replay_lifecycle(base, fixture):
    before = len(PartyProvider.requests)
    for action, version, key, original in fixture['attempts']:
        h = headers(fixture['tenant'], key, version)
        h['User-Id'] = 'ReplayActor'
        assert assert_response(request(base, f"{fixture['path']}/{action}", 'POST', {}, h), 200) == original
    assert len(PartyProvider.requests) == before
    assert sql(f"SELECT version FROM companies WHERE id='{fixture['company']}'") == '5'
    assert sql(f"SELECT count(*) FROM company_outbox_events WHERE aggregate_id='{fixture['company']}'") == str(fixture['eventCount'])


report = {'image': IMAGE, 'modes': [], 'runnerSha256': hashlib.sha256((ROOT / 'build/company-registry-service-1.0.0-SNAPSHOT-runner').read_bytes()).hexdigest()}
with socket.socket() as refused:
    refused.settimeout(1)
    refusal = refused.connect_ex(('127.0.0.1', 9))
    assert refusal != 0, 'The geographic/broker refusal endpoint must be unreachable'
    report['dependencyRefusalProbe'] = {'endpoint': '127.0.0.1:9', 'connectErrno': refusal}
provider = ThreadingHTTPServer(('127.0.0.1', 0), PartyProvider)
threading.Thread(target=provider.serve_forever, daemon=True).start()
try:
    report['imageId'] = podman('image', 'inspect', '--format', '{{.Id}}', IMAGE)
    report['configuredUser'] = podman('image', 'inspect', '--format', '{{.Config.User}}', IMAGE)
    assert report['configuredUser'] == '1001', report
    podman('run', '-d', '--name', database, '-p', '127.0.0.1::5432', '-e', 'POSTGRES_DB=hu03', '-e', 'POSTGRES_USER=postgres',
           '-e', 'POSTGRES_PASSWORD=oci_admin_test', '-e', 'DB_PASSWORD=oci_runtime_test', '-e', 'FLYWAY_DB_PASSWORD=oci_migration_test', 'docker.io/library/postgres:18-alpine')
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        if subprocess.run(['podman', 'exec', database, 'pg_isready', '-h', '127.0.0.1', '-U', 'postgres', '-d', 'hu03'], capture_output=True).returncode == 0:
            break
        time.sleep(.2)
    else:
        raise RuntimeError('Isolated HU03 database did not start')
    podman('exec', '-i', database, 'psql', '-v', ON_ERROR_STOP, '-U', 'postgres', '-d', 'hu03', input=(ROOT / 'docs/database/bootstrap-local.sql').read_text())
    database_port = podman('port', database, '5432/tcp').rsplit(':', 1)[1]
    prepare_v5()
    base = start('stored-only', 'oci-k1', migrate=False)
    legacy = legacy_fixture(base)
    historical_tables = table_snapshots()
    history = sql("SELECT json_agg(json_build_array(version,checksum) ORDER BY installed_rank) FROM flyway_schema_history")
    assert sql("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1") == '5'
    stop('v5-before-upgrade.log')
    for mode in ['disabled', 'stored-only', 'published']:
        PartyProvider.available = True
        base = start(mode, 'oci-k1')
        assert podman('exec', application, 'id', '-u') == '1001'
        assert sql("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1") == '6'
        assert sql("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE' AND table_name<>'flyway_schema_history'") == '6'
        assert history == sql("SELECT json_agg(json_build_array(version,checksum) ORDER BY installed_rank) FROM flyway_schema_history WHERE version IN ('1','2','3','4','5')")
        if mode == 'disabled':
            assert historical_tables == table_snapshots()
        replay_legacy(base, legacy)
        fixture = lifecycle_fixture(base, mode)
        contract_status, contract = request(base, '/q/openapi')
        assert contract_status == 200
        assert all('post' in contract['paths'][f'{COMPANIES_PATH}/{{companyId}}/{action}'] for action in ['activate', 'deactivate', 'archive'])
        assert request(base, '/v1/_verification/success', headers=headers(fixture['tenant']))[0] == 404
        stop(f'{mode}-original.log')
        restart_mode = 'published' if mode == 'disabled' else 'disabled'
        base = start(restart_mode, 'oci-k2')
        replay_lifecycle(base, fixture)
        replay_legacy(base, legacy)
        # A new local deactivate/archive also succeeds with the provider returning 503.
        local_tenant = str(uuid.uuid4())
        local_company = sql(f"INSERT INTO companies(tenant_id,party_id,code,operating_name,status,created_by,updated_by) VALUES('{local_tenant}',uuidv7(),'LOCAL','LOCAL','ACTIVE','Creator','Creator') RETURNING id").splitlines()[0]
        local_path = f'{COMPANIES_PATH}/{local_company}'
        before = len(PartyProvider.requests)
        assert_response(request(base, f'{local_path}/deactivate', 'POST', None, headers(local_tenant, 'local-deactivate', 0)), 200)
        assert_response(request(base, f'{local_path}/archive', 'POST', {}, headers(local_tenant, 'local-archive', 1)), 200)
        assert len(PartyProvider.requests) == before
        stop(f'{mode}-rotated.log')
        report['modes'].append({'mode': mode, 'uid': 1001, 'transitions': 5, 'events': fixture['eventCount'],
                                'rotationReplay': True, 'providerIndependentLocalCommands': True, 'nullSnapshots': True})
        print(json.dumps(report['modes'][-1]), flush=True)
    report['upgrade'] = {'from': 5, 'to': 6, 'checksumsPreserved': True, 'sixTablesPreserved': True,
                         'hu01hu02hu04hu05ReplaysPreserved': True}
    (EVIDENCE / 'report.json').write_text(json.dumps(report, indent=2))
finally:
    stop('failure-or-final.log')
    podman('rm', '-f', database)
    provider.shutdown()
    provider.server_close()

