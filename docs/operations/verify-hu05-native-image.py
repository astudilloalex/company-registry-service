"""Verify the actual non-root HU05 image; all database/provider fixtures stay external."""
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
EVIDENCE = pathlib.Path(os.environ.get('EVIDENCE_DIR', ROOT / 'build' / 'hu05-evidence' / 'oci'))
EVIDENCE.mkdir(parents=True, exist_ok=True)
IMAGE = 'company-registry-service:hu05-native'
ON_ERROR_STOP = 'ON_ERROR_STOP=1'
suffix = uuid.uuid4().hex[:10]
database = 'codex-hu05-db-' + suffix
application = None


def podman(*args, **kwargs):
    return subprocess.run(['podman', *args], check=True, capture_output=True, text=True, **kwargs).stdout.strip()


def sql(statement):
    return podman('exec', database, 'psql', '-v', ON_ERROR_STOP, '-At', '-U', 'postgres', '-d', 'hu05', '-c', statement)


def request(base, path, method='GET', body=None, headers=None):
    payload = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    req = urllib.request.Request(base + path, data=payload, method=method,
                                 headers={'Accept': 'application/json', **({'Content-Type': 'application/json'} if payload is not None else {}), **(headers or {})})
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
    application = 'codex-hu05-app-' + suffix
    env = {'DB_USERNAME': 'company_registry_runtime', 'DB_PASSWORD': 'oci_runtime_test',
           'DB_REACTIVE_URL': f'postgresql://127.0.0.1:{database_port}/hu05',
           'DB_JDBC_URL': f'jdbc:postgresql://127.0.0.1:{database_port}/hu05',
           'FLYWAY_DB_USERNAME': 'company_registry_migration', 'FLYWAY_DB_PASSWORD': 'oci_migration_test',
           'COMPANY_OUTBOX_MODE': mode, 'PARTY_REGISTRY_BASE_URL': 'http://127.0.0.1:9',
           'COMPANY_SITE_REGISTRATION_WAIT_BUDGET': '2s', 'COMPANY_SITE_MUTATION_WAIT_BUDGET': '2s',
           'GEOGRAPHIC_REFERENCE_BASE_URL': 'http://127.0.0.1:9',
           'RABBITMQ_HOST': '127.0.0.1', 'RABBITMQ_PORT': '9'}
    args = ['run', '-d', '--name', application, '--network', 'host']
    for key, value in env.items():
        args += ['-e', key + '=' + value]
    args += [IMAGE, '-Dquarkus.http.host=127.0.0.1', f'-Dquarkus.http.port={port}',
             '-Dcompany-registry.idempotency.active-key-id=' + active,
             '-Dcompany-registry.idempotency.keys.oci-k1=' + base64.b64encode(bytes(range(32))).decode(),
             '-Dcompany-registry.idempotency.keys.oci-k2=' + base64.b64encode(bytes(range(32, 64))).decode()]
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
    raise RuntimeError('HU05 image did not become ready: ' + podman('logs', application))


def stop(log_name):
    global application
    if application:
        (EVIDENCE / log_name).write_text(podman('logs', application))
        podman('rm', '-f', application)
        application = None


def verify_commit_deadline(base, mode):
    tenant = str(uuid.uuid4())
    company = sql(f"INSERT INTO companies(tenant_id,party_id,code,operating_name,created_by,updated_by) VALUES('{tenant}',uuidv7(),'DEADLINE','DEADLINE','Creator','Creator') RETURNING id").splitlines()[0]
    path = f'/v1/companies/{company}/sites'
    headers = {'Tenant-Id': tenant, 'Process-Id': str(uuid.uuid4()), 'User-Id': 'Deadline-Actor', 'Idempotency-Key': 'deadline'}
    body = {'code': 'DEADLINE', 'name': 'Deadline'}
    name = f'hu05_commit_{uuid.uuid4().hex}'
    lock_key = uuid.uuid4().int % (2**63)
    holder = subprocess.Popen(['podman', 'exec', '-i', database, 'psql', '-XqAt', '-v', ON_ERROR_STOP,
                               '-U', 'postgres', '-d', 'hu05'], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, text=True, bufsize=1)
    sql(f"CREATE FUNCTION {name}() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.tenant_id='{tenant}'::uuid THEN PERFORM pg_advisory_xact_lock({lock_key}); END IF; RETURN NEW; END $$")
    sql(f"CREATE CONSTRAINT TRIGGER {name} AFTER INSERT ON company_sites DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION {name}()")
    try:
        holder.stdin.write(f"BEGIN; DO $$ BEGIN PERFORM pg_advisory_xact_lock({lock_key}); END $$;\n\\echo HU05_LOCK_HELD\n")
        holder.stdin.flush()
        deadline = time.monotonic() + 10
        while True:
            left = deadline - time.monotonic()
            if left <= 0 or not select.select([holder.stdout], [], [], left)[0]:
                raise RuntimeError('The native commit fixture did not acquire its lock')
            if holder.stdout.readline().strip() == 'HU05_LOCK_HELD':
                break
        with ThreadPoolExecutor(max_workers=1) as calls:
            started = time.monotonic()
            pending = calls.submit(request, base, path, 'POST', body, headers)
            observed = False
            while time.monotonic() - started < 1.5:
                if sql("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted") != '0':
                    observed = True
                    break
                if pending.done():
                    break
            assert observed, 'The native COMMIT never reached the observed lock barrier'
            response = pending.result(timeout=5)
            elapsed = time.monotonic() - started
        assert response == (503, {'status': 503, 'code': 'dependency-unavailable'}), response
        assert elapsed < 3, elapsed
    finally:
        try:
            holder.communicate('ROLLBACK;\n\\q\n', timeout=10)
        except subprocess.TimeoutExpired:
            holder.kill()
            holder.communicate()
        sql(f"DROP TRIGGER {name} ON company_sites; DROP FUNCTION {name}()")
    status, accepted = request(base, path, 'POST', body, headers)
    assert status == 201, accepted
    assert request(base, path, 'POST', body, headers) == (201, accepted)
    counts = sql(f"SELECT (SELECT count(*) FROM company_sites WHERE tenant_id='{tenant}'),(SELECT count(*) FROM company_idempotency_records WHERE tenant_id='{tenant}'),(SELECT count(*) FROM company_outbox_events WHERE tenant_id='{tenant}')")
    assert counts == ('1|1|0' if mode == 'disabled' else '1|1|1'), counts
    return {'budgetSeconds': 2, 'elapsedSeconds': round(elapsed, 3), 'commitLockObserved': observed, 'httpStatus': 503, 'sameKeyReplay': True, 'counts': counts}


def prepare_v4():
    # Quarkus 3.40.1 does not expose Flyway target; provision authentic history externally.
    jars = sorted((ROOT / 'build/quarkus-app/lib/main').glob('*.jar'))
    if not jars:
        raise RuntimeError('Build the JVM artifact first to provide the existing Flyway/JDBC fixture classpath')
    helper = EVIDENCE / 'PrepareV4.java'
    helper.write_text('import org.flywaydb.core.Flyway;\nclass PrepareV4 {\n    public static void main(String[] args) {\n        Flyway.configure().dataSource(args[0], "company_registry_migration", "oci_migration_test")\n            .locations("filesystem:" + args[1]).target("4").load().migrate();\n    }\n}\n')
    result = subprocess.run(['java', '-cp', os.pathsep.join(str(path) for path in jars), str(helper.resolve()),
                             'jdbc:postgresql://127.0.0.1:' + database_port + '/hu05',
                             str(ROOT / 'src/main/resources/db/migration')], check=True, capture_output=True, text=True)
    (EVIDENCE / 'prepare-v4.log').write_text(result.stdout + result.stderr)


def replay_mutations(base, fixture):
    for attempt in fixture['attempts']:
        headers = {'Tenant-Id': fixture['tenant'], 'User-Id': 'Replay-Actor',
                   'Process-Id': str(uuid.uuid4()), 'Idempotency-Key': attempt['key'],
                   'If-Match': str(attempt['expected'])}
        status, replay = request(base, f"{fixture['path']}{attempt['suffix']}", attempt['method'], attempt['body'], headers)
        assert status == 200 and replay['data'] == attempt['data'], replay
    assert sql(f"SELECT version FROM company_sites WHERE id='{fixture['site']}'") == '4'


def mutation_body(index, action):
    if index == 0:
        return {'name': ' compañía  ágil '}
    if action == 'activate':
        return None
    return {}


def assert_mutation_outbox_events(events, site, company, mode):
    assert len(events) == (0 if mode == 'disabled' else 4), events
    expected_statuses = {4: 'CLOSED', 2: 'ACTIVE', 3: 'INACTIVE'}
    for index, event in enumerate(events, start=1):
        payload = event['payload']
        assert set(payload) == {'siteId', 'companyId', 'code', 'name', 'siteType', 'status', 'updatedAt', 'updatedBy'}
        assert event['aggregate_version'] == index
        assert event['event_type'] == ('company.site.closed.v1' if index == 4 else 'company.site.updated.v1')
        assert payload['siteId'] == site and payload['companyId'] == company
        assert payload['updatedBy'] == 'Actor-aB' and event['causation_id'] is None
        assert payload['status'] == expected_statuses.get(index, 'DRAFT')
        assert event['status'] == 'PENDING' and event['publish_attempts'] == 0 and event['version'] == 0


def verify_mutations(base, mode):
    tenant = str(uuid.uuid4())
    company = sql(f"INSERT INTO companies(tenant_id,party_id,code,operating_name,created_by,updated_by) VALUES('{tenant}',uuidv7(),'HU05','PARENT','Creator','Creator') RETURNING id").splitlines()[0]
    headers = {'Tenant-Id': tenant, 'User-Id': 'Actor-aB', 'Process-Id': str(uuid.uuid4()), 'Idempotency-Key': 'create'}
    path = f'/v1/companies/{company}/sites'
    status, accepted = request(base, path, 'POST', {'code': 'SITE', 'name': 'NAME', 'siteType': 'HEADQUARTERS'}, headers)
    assert status == 201, accepted
    site = accepted['data']['siteId']
    path = f'{path}/{site}'
    fixture = {'tenant': tenant, 'company': company, 'site': site, 'path': path, 'attempts': []}
    for index, (action, expected) in enumerate([('update', 0), ('update', 1), ('activate', 1), ('update', 2), ('deactivate', 2), ('close', 3)]):
        if index in (3, 4):
            company_status = 'ACTIVE' if index == 3 else 'INACTIVE'
            sql(f"BEGIN; SELECT id FROM companies WHERE id='{company}' FOR UPDATE; SELECT id FROM company_sites WHERE id='{site}' FOR UPDATE; UPDATE companies SET status='{company_status}' WHERE id='{company}'; COMMIT")
        parent_before = sql(f"SELECT row_to_json(c) FROM companies c WHERE id='{company}'")
        method = 'PATCH' if action == 'update' else 'POST'
        suffix = '' if action == 'update' else f'/{action}'
        body = mutation_body(index, action)
        h = {**headers, 'Idempotency-Key': f'hu05-{index}', 'If-Match': str(expected)}
        status, result = request(base, f'{path}{suffix}', method, body, h)
        assert status == 200 and len(result['data']) == 12, result
        assert result['data']['version'] == (expected if index in (1, 3) else expected + 1), result
        assert result['data']['effectivelyOperational'] is (index == 3)
        assert parent_before == sql(f"SELECT row_to_json(c) FROM companies c WHERE id='{company}'")
        fixture['attempts'].append({'method': method, 'suffix': suffix, 'body': body,
                                    'expected': expected, 'key': h['Idempotency-Key'], 'data': result['data']})
    assert request(base, f'{path}/close', 'POST', {}, {**headers, 'If-Match': '4', 'Idempotency-Key': 'redundant'}) == (409, {'status': 409, 'code': 'site-closed'})
    assert request(base, f'{path}/activate', 'POST', {'reason': 'unsupported'}, {**headers, 'If-Match': '4'}) == (400, {'status': 400, 'code': 'request-body-contains-unknown-fields'})
    sql(f"UPDATE companies SET status='ARCHIVED',version=version+1 WHERE id='{company}'")
    replay_mutations(base, fixture)
    events = json.loads(sql(f"SELECT coalesce(json_agg(row_to_json(e) ORDER BY aggregate_version),'[]'::json) FROM company_outbox_events e WHERE aggregate_id='{site}' AND event_type<>'company.site.created.v1'"))
    assert_mutation_outbox_events(events, site, company, mode)
    (EVIDENCE / f'hu05-events-{mode}.json').write_text(json.dumps(events, indent=2))
    return fixture


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
    podman('run', '-d', '--name', database, '-p', '127.0.0.1::5432', '-e', 'POSTGRES_DB=hu05', '-e', 'POSTGRES_USER=postgres',
           '-e', 'POSTGRES_PASSWORD=oci_admin_test', '-e', 'DB_PASSWORD=oci_runtime_test', '-e', 'FLYWAY_DB_PASSWORD=oci_migration_test', 'docker.io/library/postgres:18-alpine')
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        # The init-only server accepts Unix sockets before restarting; TCP becomes
        # ready only when the final server is available for bootstrap and the app.
        probe = subprocess.run(['podman', 'exec', database, 'pg_isready', '-h', '127.0.0.1', '-U', 'postgres', '-d', 'hu05'], capture_output=True)
        if probe.returncode == 0:
            break
        time.sleep(.2)
    else:
        raise RuntimeError('HU05 database did not start')
    podman('exec', '-i', database, 'psql', '-v', ON_ERROR_STOP, '-U', 'postgres', '-d', 'hu05', input=(ROOT / 'docs/database/bootstrap-local.sql').read_text())
    database_port = podman('port', database, '5432/tcp').rsplit(':', 1)[1]
    # Use genuine V4 Flyway history, then let the native process install V5.
    prepare_v4()
    legacy_base = start('stored-only', 'oci-k1', migrate=False)
    assert sql("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1") == '4'
    legacy_history = sql("SELECT json_agg(json_build_array(version,checksum) ORDER BY installed_rank) FROM flyway_schema_history")
    legacy_tenant = str(uuid.uuid4())
    legacy_company = sql(f"INSERT INTO companies(tenant_id,party_id,code,operating_name,created_by,updated_by) VALUES('{legacy_tenant}',uuidv7(),'LEGACY','LEGACY','Creator','Creator') RETURNING id").splitlines()[0]
    legacy_headers = {'Tenant-Id': legacy_tenant, 'User-Id': 'LegacyActor', 'Process-Id': str(uuid.uuid4()), 'Idempotency-Key': 'legacy-v4'}
    legacy_path = '/v1/companies/' + legacy_company + '/sites'
    legacy_body = {'code': 'LEGACY', 'name': 'V4 SITE'}
    legacy_status, legacy_acceptance = request(legacy_base, legacy_path, 'POST', legacy_body, legacy_headers)
    assert legacy_status == 201, legacy_acceptance
    stop('v4-before-upgrade.log')
    for mode in ['disabled', 'stored-only', 'published']:
        base = start(mode, 'oci-k1')
        assert podman('exec', application, 'id', '-u') == '1001'
        assert sql("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1") == '5'
        assert legacy_history == sql("SELECT json_agg(json_build_array(version,checksum) ORDER BY installed_rank) FROM flyway_schema_history WHERE version IN ('1','2','3','4')")
        assert request(base, legacy_path, 'POST', legacy_body, legacy_headers) == (201, legacy_acceptance)
        (EVIDENCE / f'{mode}-process.txt').write_text(podman('top', application, 'user', 'args'))
        tenant = str(uuid.uuid4())
        company = sql(f"INSERT INTO companies(tenant_id,party_id,code,operating_name,description,logo_url,created_by,updated_by) VALUES('{tenant}',uuidv7(),'CODE','COMPANY','DESCRIPTION','https://host/LogoAb.png','Creator','Creator') RETURNING id;").splitlines()[0]
        path = '/v1/companies/' + company + '/sites'
        headers = {'Tenant-Id': tenant, 'Process-Id': str(uuid.uuid4()), 'User-Id': 'Actor-aB', 'Idempotency-Key': 'create'}
        body = {'code': ' ß ', 'name': ' compañía  ágil '}
        before = sql(f"SELECT row_to_json(c) FROM companies c WHERE id='{company}'")
        status, accepted = request(base, path, 'POST', body, headers)
        assert status == 201, accepted
        original = accepted['data']
        assert len(original) == 12 and original['version'] == 0 and original['effectivelyOperational'] is False
        assert original['code'] == 'SS' and original['siteType'] == 'BRANCH'
        assert original['status'] == 'DRAFT' and original['createdAt'] == original['updatedAt']
        assert before == sql(f"SELECT row_to_json(c) FROM companies c WHERE id='{company}'")
        site = original['siteId']
        assert request(base, path + '/' + site, headers=headers)[1]['data'] == original
        second_headers = {**headers, 'Idempotency-Key': 'second'}
        assert request(base, path, 'POST', {'code': 'TWO', 'name': 'Second'}, second_headers)[0] == 201
        page = request(base, path + '?limit=1', headers=headers)[1]
        assert page['numberOfElements'] == 1 and page['nextCursor']
        next_page = request(base, path + '?limit=1&cursor=' + page['nextCursor'], headers=headers)[1]
        assert next_page['numberOfElements'] == 1 and 'nextCursor' not in next_page
        assert page['data'][0]['siteId'] != next_page['data'][0]['siteId']
        bad = request(base, path, 'POST', {**body, 'siteType': None}, headers)
        assert bad == (400, {'status': 400, 'code': 'site-type-null-not-allowed'}), bad
        sql(f"UPDATE company_sites SET name='LATER',status='CLOSED',version=1,updated_by='Later' WHERE id='{site}'")
        sql(f"UPDATE companies SET status='ARCHIVED',version=1,updated_by='Archiver' WHERE id='{company}'")
        history = request(base, path + '/' + site, headers=headers)[1]['data']
        assert history['status'] == 'CLOSED' and history['effectivelyOperational'] is False
        assert request(base, path, 'POST', body, headers)[1]['data'] == original
        contract_status, contract = request(base, '/q/openapi')
        assert contract_status == 200 and 'post' in contract['paths']['/v1/companies/{companyId}/sites']
        assert request(base, '/v1/_verification/success', headers=headers)[0] == 404
        counts = sql(f"SELECT (SELECT count(*) FROM companies WHERE tenant_id='{tenant}'),(SELECT count(*) FROM company_idempotency_records WHERE tenant_id='{tenant}'),(SELECT count(*) FROM company_outbox_events WHERE tenant_id='{tenant}');")
        assert counts == ('1|2|0' if mode == 'disabled' else '1|2|2'), counts
        assert sql(f"SELECT count(*) FROM company_sites WHERE tenant_id='{tenant}'") == '2'
        if mode != 'disabled':
            assert sql(f"SELECT bool_and(status='PENDING' AND publish_attempts=0 AND version=0 AND published_at IS NULL) FROM company_outbox_events WHERE tenant_id='{tenant}'") == 't'
            events = json.loads(sql(f"SELECT json_agg(row_to_json(e) ORDER BY aggregate_version) FROM company_outbox_events e WHERE tenant_id='{tenant}'"))
            (EVIDENCE / f'events-{mode}.json').write_text(json.dumps(events, indent=2))
            for event in events:
                assert len(event['payload']) == 8 and event['payload']['companyId'] == company
                assert event['aggregate_type'] == 'COMPANY_SITE' and event['aggregate_version'] == 0
                assert event['payload']['siteId'] == event['aggregate_id'] and event['event_type'] == 'company.site.created.v1'
                assert event['payload']['createdBy'] == 'Actor-aB' and event['causation_id'] is None
        mutation_fixture = verify_mutations(base, mode)
        commit_deadline = verify_commit_deadline(base, mode)
        stop(f'{mode}-original.log')
        restart_mode = 'published' if mode == 'disabled' else 'disabled'
        base = start(restart_mode, 'oci-k2')
        replay_mutations(base, mutation_fixture)
        replay_headers = {**headers, 'Process-Id': str(uuid.uuid4()), 'User-Id': 'Replay-aB'}
        status, replay = request(base, path, 'POST', body, replay_headers)
        assert status == 201 and replay['data'] == original
        assert request(base, path + '/' + site, headers=replay_headers)[1]['data']['status'] == 'CLOSED'
        assert sql(f"SELECT version FROM companies WHERE id='{company}'") == '1'
        assert counts == sql(f"SELECT (SELECT count(*) FROM companies WHERE tenant_id='{tenant}'),(SELECT count(*) FROM company_idempotency_records WHERE tenant_id='{tenant}'),(SELECT count(*) FROM company_outbox_events WHERE tenant_id='{tenant}');")
        stop(f'{mode}-restarted.log')
        report['modes'].append({'mode': mode, 'restartMode': restart_mode, 'counts': counts, 'replayAfterRestartRotationArchival': True, 'commitDeadline': commit_deadline, 'providers': 'connection refused', 'broker': '127.0.0.1:9', 'user': '1001'})
    (EVIDENCE / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))
finally:
    if application:
        subprocess.run(['podman', 'rm', '-f', application], capture_output=True)
    subprocess.run(['podman', 'rm', '-f', database], capture_output=True)
