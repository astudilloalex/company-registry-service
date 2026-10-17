# Spec Delta

## MODIFIED Requirements

### Requirement: Idempotencia tenant y Company qualified
La idempotencia SHALL usar PK `(tenant_id,operation,idempotency_key)`, fingerprint y resultado JSON versionado reproducible, con `site_id` nullable. Sus referencias SHALL impedir asociar tenant, Company y Site incompatibles y validar versión esperada, estado HTTP y contenido del resultado. Cada nuevo completado HU01/HU02 SHALL identificar además su política de fingerprint y su clave HMAC sin guardar el secreto.

#### Scenario: Idempotencia de Company y Site
- **WHEN** se guarda una finalización con Company propia y `site_id` nulo, o con Site de esa Company y tenant
- **THEN** PostgreSQL acepta el registro
- **AND** rechaza sitios de otra Company/tenant, claves repetidas, hash inválido, versión de esquema no positiva o snapshot no objeto

#### Scenario: Completado HU01 reproducible
- **WHEN** se confirma company.create.v1
- **THEN** el registro conserva hash hexadecimal de 64 caracteres, versión positiva de fingerprint, identificador no vacío de clave, HTTP 201 y snapshot de respuesta con versión positiva
- **AND** siteId y versión esperada son null porque el registro crea un agregado nuevo

#### Scenario: Completado HU02 reproducible
- **WHEN** se confirma company.update.v1 con clave, tanto si cambia Company como si es no-op
- **THEN** conserva Company propia, siteId null, versión esperada original no negativa, HTTP 200, hash y procedencia completos y snapshot con versión de esquema positiva
- **AND** el snapshot reproduce la Company operacional aceptada sin Party ni dependencia de sus valores actuales

## ADDED Requirements

### Requirement: Evolución incremental de completados de actualización
La evolución HU02 SHALL añadir V3 sin modificar V1/V2 ni sus checksums, crear tablas o eliminar datos. SHALL conservar las seis tablas, referencias, restricciones y aceptaciones HU01. Los registros históricos incompatibles de company.update.v1 SHALL detectarse antes de habilitar el contrato, sin inventar procedencia ni transformar snapshots aceptados.

#### Scenario: Instalación limpia y upgrade de HU01
- **WHEN** se instala el esquema completo en PostgreSQL 18 vacío o se actualiza una base V2 con Companies, completados y eventos HU01
- **THEN** V3 se aplica y valida preservando los datos, claves compuestas y checksums previos
- **AND** un replay de creación sigue devolviendo su snapshot enriquecido original

#### Scenario: Aceptación histórica HU02 incompatible
- **WHEN** una fila previa company.update.v1 no satisface los metadatos requeridos
- **THEN** la preparación o migración se detiene con diagnóstico controlado
- **AND** no borra esa aceptación ni atribuye su hash a la clave activa para continuar

### Requirement: Forma persistente de la aceptación HU02
Los completados company.update.v1 SHALL exigir fingerprint_version positivo, fingerprint_key_id válido no nulo, expected_aggregate_version no negativo no nulo, response_http_status 200 y site_id null, además de las restricciones existentes de hash, snapshot y propiedad. Una omisión o incoherencia SHALL rechazarse persistentemente.

#### Scenario: Metadatos inválidos
- **WHEN** un completado HU02 omite procedencia o versión esperada, usa una clave inválida, versión negativa, status distinto de 200 o siteId no nulo
- **THEN** PostgreSQL rechaza la inserción o actualización sin debilitar los contratos HU01 ni namespaces técnicos históricos

#### Scenario: Referencia, snapshot y clave incompatibles
- **WHEN** el completado apunta a Company de otro tenant, duplica su clave compuesta, usa hash inválido, snapshot no objeto o versión de snapshot no positiva
- **THEN** la persistencia lo rechaza también para company.update.v1

### Requirement: Snapshot operacional original conservado
La aceptación HU02 SHALL conservar el resultado operacional completo, con tipos, auditoría, versión, valores opacos y nulabilidad interna originales. SHALL seguir siendo reproducible tras reinicio, mutaciones posteriores y rotación de clave. SHALL NOT introducir TTL, limpieza automática o reinterpretación de las políticas y snapshots HU01.

#### Scenario: Round-trip del resultado parcial limpiado
- **WHEN** un resultado aceptado con description/logoUrl nulos se guarda y recupera
- **THEN** su representación interna conserva esos nulos y su representación HTTP los omite según el contrato
- **AND** IDs, versión, fechas y actores permanecen iguales al resultado aceptado

#### Scenario: No-op durable y reversión de despliegue
- **WHEN** se guarda un no-op con clave o se revierte una versión de aplicación después de aceptar actualizaciones
- **THEN** se conserva el resultado y el material histórico necesario para su replay
- **AND** la reversión mantiene V3 y datos confirmados, restaurando una aplicación compatible o suspendiendo las rutas afectadas
