"""Verify the actual non-root HU04 image; all database/provider fixtures stay external."""
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
EVIDENCE = pathlib.Path(os.environ.get('EVIDENCE_DIR', ROOT / 'build' / 'hu04-evidence' / 'oci'))
EVIDENCE.mkdir(parents=True, exist_ok=True)
IMAGE = 'company-registry-service:hu04-native'
ON_ERROR_STOP = 'ON_ERROR_STOP=1'
suffix = uuid.uuid4().hex[:10]
database = 'codex-hu04-db-' + suffix
application = None


def podman(*args, **kwargs):
    return subprocess.run(['podman', *args], check=True, capture_output=True, text=True, **kwargs).stdout.strip()


def sql(statement):
    return podman('exec', database, 'psql', '-v', ON_ERROR_STOP, '-At', '-U', 'postgres', '-d', 'hu04', '-c', statement)


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
    application = 'codex-hu04-app-' + suffix
    env = {'DB_USERNAME': 'company_registry_runtime', 'DB_PASSWORD': 'oci_runtime_test',
           'DB_REACTIVE_URL': f'postgresql://127.0.0.1:{database_port}/hu04',
           'DB_JDBC_URL': f'jdbc:postgresql://127.0.0.1:{database_port}/hu04',
           'FLYWAY_DB_USERNAME': 'company_registry_migration', 'FLYWAY_DB_PASSWORD': 'oci_migration_test',
           'COMPANY_OUTBOX_MODE': mode, 'PARTY_REGISTRY_BASE_URL': 'http://127.0.0.1:9',
           'COMPANY_SITE_REGISTRATION_WAIT_BUDGET': '2s',
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
    raise RuntimeError('HU04 image did not become ready: ' + podman('logs', application))


def stop(log_name):
    global application
    if application:
        (EVIDENCE / log_name).write_text(podman('logs', application))
        podman('rm', '-f', application)
        application = None


def verify_commit_deadline(base, mode):
    tenant = str(uuid.uuid4())
    company = sql(f"INSERT INTO companies(tenant_id,party_id,code,operating_name,created_by,updated_by) VALUES('{tenant}',uuidv7(),'DEADLINE','DEADLINE','Creator','Creator') RETURNING id").splitlines()[0]
    path = '/v1/companies/' + company + '/sites'
    headers = {'Tenant-Id': tenant, 'Process-Id': str(uuid.uuid4()), 'User-Id': 'Deadline-Actor', 'Idempotency-Key': 'deadline'}
    body = {'code': 'DEADLINE', 'name': 'Deadline'}
    name = 'hu04_commit_' + uuid.uuid4().hex
    lock_key = uuid.uuid4().int % (2**63)
    holder = subprocess.Popen(['podman', 'exec', '-i', database, 'psql', '-XqAt', '-v', ON_ERROR_STOP,
                               '-U', 'postgres', '-d', 'hu04'], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, text=True, bufsize=1)
    sql(f"CREATE FUNCTION {name}() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.tenant_id='{tenant}'::uuid THEN PERFORM pg_advisory_xact_lock({lock_key}); END IF; RETURN NEW; END $$")
    sql(f"CREATE CONSTRAINT TRIGGER {name} AFTER INSERT ON company_sites DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION {name}()")
    try:
        holder.stdin.write(f"BEGIN; DO $$ BEGIN PERFORM pg_advisory_xact_lock({lock_key}); END $$;\n\\echo HU04_LOCK_HELD\n")
        holder.stdin.flush()
        deadline = time.monotonic() + 10
        while True:
            left = deadline - time.monotonic()
            if left <= 0 or not select.select([holder.stdout], [], [], left)[0]:
                raise RuntimeError('The native commit fixture did not acquire its lock')
            if holder.stdout.readline().strip() == 'HU04_LOCK_HELD':
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
    podman('run', '-d', '--name', database, '-p', '127.0.0.1::5432', '-e', 'POSTGRES_DB=hu04', '-e', 'POSTGRES_USER=postgres',
           '-e', 'POSTGRES_PASSWORD=oci_admin_test', '-e', 'DB_PASSWORD=oci_runtime_test', '-e', 'FLYWAY_DB_PASSWORD=oci_migration_test', 'docker.io/library/postgres:18-alpine')
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        # The init-only server accepts Unix sockets before restarting; TCP becomes
        # ready only when the final server is available for bootstrap and the app.
        probe = subprocess.run(['podman', 'exec', database, 'pg_isready', '-h', '127.0.0.1', '-U', 'postgres', '-d', 'hu04'], capture_output=True)
        if probe.returncode == 0:
            break
        time.sleep(.2)
    else:
        raise RuntimeError('HU04 database did not start')
    podman('exec', '-i', database, 'psql', '-v', ON_ERROR_STOP, '-U', 'postgres', '-d', 'hu04', input=(ROOT / 'docs/database/bootstrap-local.sql').read_text())
    database_port = podman('port', database, '5432/tcp').rsplit(':', 1)[1]
    for mode in ['disabled', 'stored-only', 'published']:
        base = start(mode, 'oci-k1')
        assert podman('exec', application, 'id', '-u') == '1001'
        assert sql("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1") == '4'
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
        commit_deadline = verify_commit_deadline(base, mode)
        stop(f'{mode}-original.log')
        restart_mode = 'published' if mode == 'disabled' else 'disabled'
        base = start(restart_mode, 'oci-k2')
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
