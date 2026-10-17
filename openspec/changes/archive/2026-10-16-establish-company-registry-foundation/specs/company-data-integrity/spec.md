# Spec Delta

## Purpose

Garantizar la integridad del registro operacional multi-tenant y de sus registros técnicos mediante un contrato de base de datos reproducible y probado.

## ADDED Requirements

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
La idempotencia SHALL usar PK `(tenant_id,operation,idempotency_key)`, fingerprint y resultado JSON versionado reproducible, con `site_id` nullable. Sus referencias SHALL impedir asociar tenant, Company y Site incompatibles y validar versión esperada, estado HTTP y contenido del resultado.

#### Scenario: Idempotencia de Company y Site
- **WHEN** se guarda una finalización con Company propia y `site_id` nulo, o con Site de esa Company y tenant
- **THEN** PostgreSQL acepta el registro
- **AND** rechaza sitios de otra Company/tenant, claves repetidas, hash inválido, versión de esquema no positiva o snapshot no objeto

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
