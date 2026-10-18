# Spec Delta

## MODIFIED Requirements

### Requirement: Idempotencia tenant y Company qualified
La idempotencia SHALL usar PK `(tenant_id,operation,idempotency_key)`, fingerprint y resultado JSON versionado reproducible. site_id SHALL ser nullable para operaciones Company y obligatorio para company.site.create.v1. Sus referencias SHALL impedir tenant/Company/Site incompatibles y validar versión esperada, estado y snapshot. Cada completado HU01/HU02/HU04 SHALL identificar política de fingerprint y clave HMAC sin guardar el secreto.

#### Scenario: Idempotencia de Company y Site
- **WHEN** se guarda una finalización de operación Company con Company propia y `site_id` nulo, o una operación Site con Site de esa Company y tenant
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

#### Scenario: Completado HU04 reproducible
- **WHEN** se confirma company.site.create.v1
- **THEN** conserva Company y Site propios, site_id no nulo, versión esperada null, HTTP 201, hash y procedencia completos y snapshot con versión positiva
- **AND** el resultado local original reproduce Site DRAFT/version 0/effectivelyOperational false sin consultar datos actuales

### Requirement: Snapshot enriquecido original preservado
El completado de creación Company (`company.create.v1`) SHALL guardar el resultado original completo y versionado, incluyendo la proyección pública de Party e identificadores enmascarados. SHALL conservar sus tipos, valores opacos y nulabilidad interna sin convertirla en una tabla espejo o columnas legales de Company. Su lectura SHALL reproducir la representación HTTP aceptada.

#### Scenario: Round-trip y respuesta histórica
- **WHEN** se guarda y recupera el snapshot con Party, legalEntityDetails e identifiers
- **THEN** mantiene IDs, estados, versiones, fechas, actores, flags booleanos y maskedValue originales
- **AND** la omisión HTTP de opcionales nulos no cambia el contenido efectivo ni requiere consultar proveedores

#### Scenario: Material ajeno al resultado público
- **WHEN** se serializa un completado
- **THEN** no incorpora secretos HMAC, respuestas técnicas del proveedor, valores legales completos de identificadores ni datos de transporte que deban pertenecer al reintento

## ADDED Requirements

### Requirement: Evolución incremental del soporte HU04
La evolución HU04 SHALL añadir una migración posterior a V3 que preserve checksums V1–V3, las seis tablas, privilegios separados y datos existentes. SHALL validar nuevos metadatos de creación Site sin inventar procedencia ni reconstruir aceptaciones históricas. Un histórico HU04 incompatible SHALL detener la migración con diagnóstico controlado.

#### Scenario: Instalación limpia y upgrade
- **WHEN** se migra PostgreSQL 18 vacío o una base V3 con Companies, Sites y aceptaciones/eventos HU01/HU02
- **THEN** instala el nuevo soporte manteniendo datos, restricciones anteriores y checksums
- **AND** siguen funcionando los replays anteriores y runtime conserva permisos DML sin DDL

#### Scenario: Aceptación histórica incompatible
- **WHEN** una fila previa company.site.create.v1 no cumple procedencia, resultado o propiedad exigidos
- **THEN** la migración se detiene antes de habilitar el contrato
- **AND** no elimina la fila, inventa una clave histórica ni sustituye su snapshot con datos actuales

### Requirement: Forma persistente de aceptación de creación Site
Los completados company.site.create.v1 SHALL exigir site_id no nulo, expected_aggregate_version null, response_http_status 201, fingerprint_version positivo no nulo y fingerprint_key_id no nulo con formato `[A-Za-z0-9_-]{1,64}`. SHALL conservar las restricciones de hash, snapshot, clave compuesta y referencias tenant/Company/Site.

#### Scenario: Metadatos obligatorios inválidos
- **WHEN** una inserción o actualización HU04 omite Site/procedencia, usa versión no positiva, keyId inválido, versión esperada no null o estado distinto de 201
- **THEN** PostgreSQL rechaza la escritura sin debilitar HU01/HU02 ni namespaces técnicos históricos

#### Scenario: Referencias y contenido incompatibles
- **WHEN** la aceptación referencia Site de otra Company/tenant, duplica su clave, usa hash no hexadecimal de 64 caracteres, snapshot no objeto o versión de snapshot no positiva
- **THEN** la persistencia rechaza la escritura

### Requirement: Snapshot local original de Site conservado
La aceptación HU04 SHALL conservar todos los valores y tipos de la proyección pública local original y datos de propiedad necesarios para verificarla. SHALL mantener operatividad false, status DRAFT, revisión 0 y auditoría original sin incorporar Party/direcciones/contactos ni material secreto. Su lectura SHALL verificar coherencia con tenant/Company/Site del completado.

#### Scenario: Round-trip y estado posterior
- **WHEN** se guarda y recupera el snapshot después de cambiar, activar o cerrar Site o archivar Company
- **THEN** conserva exactamente IDs, texto, tipo, booleano, enteros, fechas y actores originales
- **AND** el replay no calcula otra operatividad ni modifica la aceptación

#### Scenario: Snapshot corrupto o ajeno al propietario
- **WHEN** su versión, identidad, tipos o propiedad no corresponden al completado
- **THEN** el servicio devuelve un fallo 500 saneado sin devolver datos ajenos ni reconstruir el resultado

### Requirement: Soporte persistente de paginación Site
El esquema SHALL incluir un índice de company_sites por tenant_id, company_id, created_at DESC e id DESC para el listado acordado. SHALL preservar código único por Company, la FK compuesta y el índice parcial de HEADQUARTERS no cerrado. La evolución SHALL NOT añadir una columna mutable de operatividad efectiva.

#### Scenario: Índice y restricciones instalados
- **WHEN** se inspecciona el catálogo después de migrar
- **THEN** el índice incluye ámbito y orden completos y está listo/válido
- **AND** permanecen las restricciones de propiedad, código e histórico legal, documentadas y verificadas con PostgreSQL 18

### Requirement: Reversión compatible con aceptaciones HU04
Un rollback de aplicación SHALL conservar migraciones, Sites, completados, eventos y claves históricas necesarios para replay HU04. SHALL restaurar una aplicación compatible o suspender las rutas afectadas, sin borrar aceptaciones, reiniciar revisiones ni introducir TTL.

#### Scenario: Reversión después de aceptar sedes
- **WHEN** se revierte un despliegue que ya confirmó registros HU04
- **THEN** conserva el estado durable y el material histórico para recuperar las respuestas aceptadas
- **AND** siguen intactos los snapshots enriquecidos HU01 y operacionales HU02
