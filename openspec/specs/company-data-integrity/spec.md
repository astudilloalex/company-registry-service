# company-data-integrity Specification

## Purpose

Garantizar la integridad del registro operacional multi-tenant y de sus registros técnicos mediante un contrato de base de datos reproducible y probado.

## Requirements

### Requirement: Esquema reproducible de seis tablas
Flyway SHALL ser la única autoridad del esquema. El contrato SHALL contener exactamente `companies`, `company_sites`, `company_site_addresses`, `company_site_contact_points`, `company_idempotency_records` y `company_outbox_events`, además del historial técnico de Flyway. Las migraciones aplicadas SHALL conservar contenido y checksum.

#### Scenario: Creación y validación repetida
- **WHEN** se migra PostgreSQL 18 vacío y se valida repetidamente sin cambios
- **THEN** existen las seis tablas del contrato y el historial de Flyway conserva sus versiones y checksums

#### Scenario: Credenciales separadas
- **WHEN** se configuran usuarios diferentes para runtime y migraciones
- **THEN** Flyway aplica el esquema con el usuario migrador y runtime opera sin permisos de creación o modificación de esquema

### Requirement: Estados e identificadores del contrato
Los identificadores generados SHALL ser UUIDv7. El esquema SHALL restringir estados Company a DRAFT/ACTIVE/INACTIVE/ARCHIVED; estados Site a DRAFT/ACTIVE/INACTIVE/CLOSED; tipos Site a HEADQUARTERS/BRANCH/WAREHOUSE/OTHER; agregados outbox a COMPANY/COMPANY_SITE; y estados outbox a PENDING/PUBLISHED/FAILED.

#### Scenario: Generación y enumeraciones
- **WHEN** se crean registros usando la generación de IDs del servicio o de PostgreSQL
- **THEN** los IDs generados son UUIDv7 y valores ajenos a los enums persistidos son rechazados
- **AND** se conservan los tipos de dirección PHYSICAL/MAILING y contacto EMAIL/PHONE/MOBILE/WHATSAPP ya definidos por V1

### Requirement: Unicidad e identidad Company
Cada Company SHALL tener PK `id`, unicidad `(tenant_id,id)`, `(tenant_id,code)` y `(tenant_id,party_id)`, versión no negativa y nombres/códigos/actores requeridos no vacíos. `tenant_id` y `party_id` SHALL ser inmutables después del registro.

#### Scenario: Unicidad tenant-scoped
- **WHEN** dos Companies comparten tenant y Party o código canónico
- **THEN** PostgreSQL rechaza la segunda escritura
- **AND** tenants diferentes pueden reutilizar esas referencias o códigos sin compartir la misma fila

#### Scenario: Mutación de identidad o datos inválidos
- **WHEN** se cambia tenant/Party o se escribe versión negativa, nombre/código vacío o actor en blanco
- **THEN** PostgreSQL rechaza la escritura

### Requirement: Propiedad y sede legal única
Site SHALL referenciar Company mediante `(tenant_id,company_id)`, mantener código único por Company y unicidad `(tenant_id,company_id,id)`, versión no negativa y campos requeridos no vacíos. Cada Company SHALL tener como máximo un HEADQUARTERS cuyo estado no sea CLOSED.

#### Scenario: Referencia de otro tenant
- **WHEN** una sede referencia una Company con tenant incompatible
- **THEN** PostgreSQL rechaza la referencia

#### Scenario: Creación concurrente de sedes legales
- **WHEN** dos transacciones independientes intentan crear sedes HEADQUARTERS no cerradas para la misma Company
- **THEN** como máximo una confirma y la otra recibe una violación de unicidad
- **AND** una sede HEADQUARTERS histórica CLOSED no impide crear el reemplazo

### Requirement: Direcciones e integridad de coordenadas
Las direcciones SHALL referenciar una sede, conservar país y referencia administrativa, y permitir como máximo una primaria no eliminada por sede/tipo. Latitud y longitud SHALL ser ambas nulas o estar ambas pobladas, en rangos [-90,90] y [-180,180]. El borrado lógico SHALL conservar metadatos coherentes.

#### Scenario: Coordenadas inválidas
- **WHEN** se guarda una sola coordenada o valores fuera de rango
- **THEN** PostgreSQL rechaza el registro
- **AND** acepta el par nulo, el par válido y los límites inclusivos

#### Scenario: Primaria y eliminación lógica
- **WHEN** se intenta duplicar una dirección primaria vigente del mismo tipo o eliminar sin actor/fecha coherentes
- **THEN** PostgreSQL rechaza la escritura
- **AND** una dirección eliminada y desmarcada como primaria permanece en el historial sin ocupar la unicidad vigente

### Requirement: Contactos e integridad histórica
Los contactos SHALL referenciar una sede, restringir su tipo a EMAIL/PHONE/MOBILE/WHATSAPP y permitir como máximo un primario no eliminado por sede/tipo. Los valores telefónicos SHALL almacenarse en formato E.164 y el borrado lógico SHALL conservar actor y fecha coherentes.

#### Scenario: Restricciones de contacto
- **WHEN** se inserta un segundo primario vigente del mismo tipo, teléfono no canónico, valor vacío o borrado incoherente
- **THEN** PostgreSQL rechaza la escritura
- **AND** permite conservar contactos eliminados sin bloquear un nuevo primario

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

### Requirement: Integridad persistente del outbox
Outbox SHALL tener unicidad `(tenant_id,aggregate_type,aggregate_id,aggregate_version,event_type)`, índice por estado/próximo intento/creación, payload JSONB, correlación/causalidad, auditoría, intentos, próxima ejecución, diagnóstico y versionado optimista.

#### Scenario: Evento válido y duplicado
- **WHEN** se persiste un evento válido y se repite su identidad de agregado/versión/tipo
- **THEN** la primera escritura se conserva y PostgreSQL rechaza la duplicada

#### Scenario: Metadatos inválidos
- **WHEN** un evento tiene payload no objeto, correlación vacía, intentos/versión negativos o estado de entrega incompatible con sus metadatos
- **THEN** PostgreSQL rechaza la escritura
- **AND** los tipos de evento y su correspondencia con agregado/versiones se mantienen alineados con V1

### Requirement: Verificación exhaustiva del contrato SQL
Cada PK, UNIQUE, FK, índice parcial, CHECK y protección de identidad/estado definida en el contrato SHALL tener evidencia automatizada contra PostgreSQL 18, con casos válidos e inválidos y cobertura concurrente cuando corresponda.

#### Scenario: Catálogo de restricciones verificadas
- **WHEN** se ejecuta la suite de migraciones
- **THEN** el inventario de restricciones del esquema se contrasta con sus pruebas y una restricción ausente o debilitada causa fallo

### Requirement: Evolución incremental de metadatos de fingerprint
El cambio SHALL ampliar la tabla de completados con `fingerprint_version` y `fingerprint_key_id` mediante una nueva migración Flyway, conservando V1, su checksum, seis tablas y datos existentes. SHALL exigir metadatos coherentes para nuevas aceptaciones HU01 y evitar atribuir claves o políticas inventadas a registros históricos.

#### Scenario: Instalación limpia y actualización
- **WHEN** se aplica la migración sobre una base vacía o una base T00 con registros técnicos existentes
- **THEN** permanecen las identidades, snapshots, auditoría, claves compuestas y restricciones previas
- **AND** se verifica versión positiva y clave no vacía de longitud acotada para los nuevos completados HU01

#### Scenario: Históricos sin procedencia verificable
- **WHEN** un registro previo no permite establecer qué política o secreto produjo su hash
- **THEN** se preserva sin asignarle por defecto la clave activa actual
- **AND** si afecta a company.create.v1 y no puede satisfacerse el nuevo contrato, la preparación/migración lo detecta y detiene con diagnóstico controlado sin eliminarlo ni reemplazarlo

#### Scenario: Validación SQL de metadatos
- **WHEN** una nueva aceptación HU01 intenta omitir un metadato, usar versión no positiva o identificador de clave vacío o excesivo
- **THEN** la validación persistente rechaza la escritura sin debilitar las referencias tenant/Company/Site

### Requirement: Snapshot enriquecido original preservado
El completado de creación SHALL guardar el resultado original completo y versionado, incluyendo la proyección pública de Party e identificadores enmascarados. SHALL conservar sus tipos, valores opacos y nulabilidad interna sin convertirla en una tabla espejo o columnas legales de Company. Su lectura SHALL reproducir la representación HTTP aceptada.

#### Scenario: Round-trip y respuesta histórica
- **WHEN** se guarda y recupera el snapshot con Party, legalEntityDetails e identifiers
- **THEN** mantiene IDs, estados, versiones, fechas, actores, flags booleanos y maskedValue originales
- **AND** la omisión HTTP de opcionales nulos no cambia el contenido efectivo ni requiere consultar proveedores

#### Scenario: Material ajeno al resultado público
- **WHEN** se serializa un completado
- **THEN** no incorpora secretos HMAC, respuestas técnicas del proveedor, valores legales completos de identificadores ni datos de transporte que deban pertenecer al reintento

### Requirement: Conservación de aceptaciones durables
El cambio SHALL preservar completados, claves y reglas necesarias para sus replays, sin introducir expiración o limpieza automática que permita repetir una creación aceptada. Un rollback de aplicación SHALL conservar la migración, Companies, completados y outbox confirmados.

#### Scenario: Reversión de aplicación o rotación
- **WHEN** se revierte un despliegue o se rota la clave activa
- **THEN** se conservan los datos y claves históricas requeridos, y se restaura una aplicación compatible o se suspenden las rutas afectadas
- **AND** no se usa reparación de V1, borrado de completados o reinicio de versiones para recuperar compatibilidad

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
