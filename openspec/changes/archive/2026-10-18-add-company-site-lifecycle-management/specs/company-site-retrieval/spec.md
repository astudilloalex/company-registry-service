# Spec Delta

## MODIFIED Requirements

### Requirement: Representación pública uniforme de Site
POST de registro, PATCH, comandos HU05, replay, GET individual y elementos del listado SHALL usar un DTO con exactamente siteId, companyId, code, name, siteType, status, version, effectivelyOperational, createdAt, createdBy, updatedAt y updatedBy. SHALL usar el envelope compartido y SHALL NOT incluir Party, tenantId, direcciones o contactos.

#### Scenario: Estructura y tipos de la respuesta
- **WHEN** se devuelve una sede por creación, replay o consulta
- **THEN** data contiene los doce campos públicos, UUIDs como strings, version como entero, effectivelyOperational como booleano y fechas UTC con precisión de microsegundos
- **AND** los actores conservan su contenido exacto y la lectura no modifica auditoría ni versión

#### Scenario: Colección homogénea
- **WHEN** se devuelve una página con varias sedes
- **THEN** cada elemento de data tiene el mismo contrato público que el GET individual

#### Scenario: Mutación con resultado completo
- **WHEN** PATCH, activate, deactivate o close de HU05 confirma o recupera una aceptación
- **THEN** devuelve 200 successful con los doce campos del Site aceptado
- **AND** close conserva la representación completa CLOSED y PATCH no-op conserva versión y auditoría

### Requirement: Operatividad efectiva calculada al consultar
En GET, effectivelyOperational SHALL ser true exactamente cuando Company y Site del ámbito estén ACTIVE y SHALL calcularse desde sus estados locales actuales. En una nueva aceptación HU05 SHALL calcularse bajo protección desde Company y Site resultante; el replay SHALL conservar su valor original. SHALL NOT persistirse como columna mutable ni modificar Site al cambiar Company.

#### Scenario: Combinaciones de estados
- **WHEN** Company/Site están ACTIVE/ACTIVE
- **THEN** la respuesta de consulta calcula effectivelyOperational true
- **AND** cualquier otra combinación calcula false conservando ambos estados originales

#### Scenario: Filtro de estado propio
- **WHEN** se solicita `status=ACTIVE` bajo Company DRAFT, INACTIVE o ARCHIVED
- **THEN** puede devolver sedes con status ACTIVE y effectivelyOperational false
- **AND** el filtro no se interpreta como operatividad efectiva ni agrega otra condición implícita

#### Scenario: Consulta posterior frente a replay
- **WHEN** después de activar una sede y Company se consulta su GET y se repite su creación aceptada
- **THEN** el GET muestra estado y operatividad actuales mientras el replay mantiene DRAFT/version 0/false originales

#### Scenario: Misma revisión Site con operatividad diferente
- **WHEN** HU05 activa Site bajo Company DRAFT y luego HU03 activa Company sin cambiar Site
- **THEN** GET conserva la revisión Site y calcula true
- **AND** el replay HU05 conserva false y toda la representación original, sin consultar Company actual

#### Scenario: Replay anterior a desactivación o archivado
- **WHEN** una aceptación HU05 guardó true y Company después se desactiva o archiva
- **THEN** el replay conserva true mientras GET calcula false
- **AND** ninguna respuesta añade una columna mutable de operatividad al agregado
