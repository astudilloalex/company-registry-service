import base64,hashlib,http.server,json,os,pathlib,socket,subprocess,threading,time,urllib.request,urllib.error,uuid
root=pathlib.Path.cwd(); evidence=pathlib.Path(os.environ.get('EVIDENCE_DIR', root/'build'/'company-hu01-evidence'));evidence.mkdir(parents=True,exist_ok=True)
image='company-registry-service:hu01-native'; suffix=uuid.uuid4().hex[:10];db='codex-hu01-db-'+suffix;app=None
APPLICATION_JSON='application/json'
COMPANIES_PATH='/v1/companies'

def run(args,**kw):
 return subprocess.run(['podman',*args],check=True,capture_output=True,text=True,**kw).stdout.strip()
def freeport():
 with socket.socket() as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]
def request(url,method='GET',body=None,headers=None):
 data=None if body is None else json.dumps(body,ensure_ascii=False).encode()
 req=urllib.request.Request(url,data=data,method=method,headers={'Accept':APPLICATION_JSON,**({'Content-Type':APPLICATION_JSON} if data else {}),**(headers or {})})
 try:
  with urllib.request.urlopen(req,timeout=12) as response:return response.status,json.load(response)
 except urllib.error.HTTPError as response:return response.code,json.load(response)
class Provider(http.server.BaseHTTPRequestHandler):
 def log_message(self,*args):
  # Suppress default server request logging.
  pass
 def do_GET(self):
  party=self.path.split('/')[3];now='2026-10-17T14:30:00.123456Z'
  if '/identifiers' in self.path:
   data=[{'identifierId':str(uuid.uuid4()),'partyId':party,'identifierSchemeId':str(uuid.uuid4()),'schemeCode':'EC_RUC','maskedValue':'*********0001','status':'VERIFIED','isPrimary':True,'issuedOn':'2020-01-15','version':1,'createdAt':now,'updatedAt':now}]
   body={'status':200,'code':'successful','data':data,'nextCursor':None,'numberOfElements':1}
  else:
   body={'status':200,'code':'successful','data':{'partyId':party,'type':'LEGAL_ENTITY','recordStatus':'ACTIVE','displayName':'COMPAÑÍA','version':1,'createdAt':now,'updatedAt':now,'createdBy':'Provider-aB','updatedBy':'Provider-aB','legalEntityDetails':{'legalName':'COMPAÑÍA S.A.S.','incorporationCountryCode':'EC','incorporatedOn':'2020-01-15'}}}
  raw=json.dumps(body,ensure_ascii=False).encode();self.send_response(200);self.send_header('Content-Type',APPLICATION_JSON);self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Provider);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
report={'image':image,'runnerSha256':hashlib.sha256((root/'build/company-registry-service-1.0.0-SNAPSHOT-runner').read_bytes()).hexdigest(),'modes':[]}
try:
 report['imageId']=run(['image','inspect','--format','{{.Id}}',image]);report['configuredUser']=run(['image','inspect','--format','{{.Config.User}}',image]);assert report['configuredUser']=='1001'
 run(['run','-d','--name',db,'-p','127.0.0.1::5432','-e','POSTGRES_DB=company_registry_hu01','-e','POSTGRES_USER=postgres','-e','POSTGRES_PASSWORD=oci_admin_test','-e','DB_PASSWORD=oci_runtime_test','-e','FLYWAY_DB_PASSWORD=oci_migration_test','docker.io/library/postgres:18-alpine'])
 end=time.monotonic()+45
 while True:
  p=subprocess.run(['podman','exec',db,'pg_isready','-U','postgres','-d','company_registry_hu01'],capture_output=True)
  if p.returncode==0:break
  if time.monotonic()>end:raise RuntimeError('OCI database did not start')
  time.sleep(.2)
 run(['exec','-i',db,'psql','-v','ON_ERROR_STOP=1','-U','postgres','-d','company_registry_hu01'],input=(root/'docs/database/bootstrap-local.sql').read_text())
 dbport=run(['port',db,'5432/tcp']).rsplit(':',1)[1]
 for mode in ['disabled','stored-only','published']:
  app='codex-hu01-app-'+suffix;port=freeport();tenant=str(uuid.uuid4());party=str(uuid.uuid4());process=str(uuid.uuid4())
  env={'DB_USERNAME':'company_registry_runtime','DB_PASSWORD':'oci_runtime_test','DB_REACTIVE_URL':f'postgresql://127.0.0.1:{dbport}/company_registry_hu01','DB_JDBC_URL':f'jdbc:postgresql://127.0.0.1:{dbport}/company_registry_hu01','FLYWAY_DB_USERNAME':'company_registry_migration','FLYWAY_DB_PASSWORD':'oci_migration_test','COMPANY_OUTBOX_MODE':mode,'PARTY_REGISTRY_BASE_URL':f'http://127.0.0.1:{server.server_port}','GEOGRAPHIC_REFERENCE_BASE_URL':'http://127.0.0.1:9'}
  args=['run','-d','--name',app,'--network','host']
  for key,value in env.items():args+=['-e',key+'='+value]
  args += [image,'-Dquarkus.http.host=127.0.0.1',f'-Dquarkus.http.port={port}','-Dcompany-registry.idempotency.active-key-id=oci-k1','-Dcompany-registry.idempotency.keys.oci-k1='+base64.b64encode(bytes(range(32))).decode()]
  run(args);base=f'http://127.0.0.1:{port}';end=time.monotonic()+45
  while True:
   try:
    if request(base+'/q/health/ready')[0]==200:break
   except (OSError,ValueError):pass
   if time.monotonic()>end:raise RuntimeError('OCI app did not start: '+run(['logs',app]))
   time.sleep(.2)
  assert run(['exec',app,'id','-u'])=='1001';assert json.loads(run(['inspect','--format','{{json .Mounts}}',app]))==[]
  headers={'Tenant-Id':tenant,'User-Id':'OCI-Actor-aB','Process-Id':process,'Idempotency-Key':'OCI-Key-aB'}
  status,created=request(base+COMPANIES_PATH,'POST',{'partyId':party,'code':' acme ','operatingName':' compañía ágil ','logoUrl':'https://host/Logo-aB.png'},headers);assert status==201,(status,created)
  assert created['data']['party']['identifiers'][0]['isPrimary'] is True
  assert created['data']['party']['legalEntityDetails']['incorporatedOn']=='2020-01-15'
  cid=created['data']['companyId'];assert request(base+COMPANIES_PATH+'/'+cid,headers=headers)[0]==200
  status,listing=request(base+COMPANIES_PATH,headers=headers);assert status==200 and listing['numberOfElements']==1
  assert request(base+'/v1/_verification/success',headers=headers)[0]==404
  status,contract=request(base+'/q/openapi');assert status==200 and set(contract['paths'])=={COMPANIES_PATH,COMPANIES_PATH+'/{companyId}'}
  sql=f"SELECT (SELECT count(*) FROM companies WHERE tenant_id='{tenant}'),(SELECT count(*) FROM company_idempotency_records WHERE tenant_id='{tenant}'),(SELECT count(*) FROM company_outbox_events WHERE tenant_id='{tenant}');"
  counts=run(['exec',db,'psql','-At','-U','postgres','-d','company_registry_hu01','-c',sql]);assert counts==('1|1|0' if mode=='disabled' else '1|1|1'),counts
  if mode!='disabled':
   state=run(['exec',db,'psql','-At','-U','postgres','-d','company_registry_hu01','-c',f"SELECT status||':'||publish_attempts||':'||version FROM company_outbox_events WHERE tenant_id='{tenant}'"]);assert state=='PENDING:0:0'
  report['modes'].append({'mode':mode,'httpCreation':201,'counts':counts,'user':'1001','fixtureStatus':404,'businessPaths':sorted(contract['paths'])})
  (evidence/f'oci-{mode}.log').write_text(run(['logs',app]));run(['rm','-f',app]);app=None
 (evidence/'oci.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
finally:
 if app:subprocess.run(['podman','rm','-f',app],capture_output=True)
 subprocess.run(['podman','rm','-f',db],capture_output=True)
 server.shutdown();server.server_close()
