# Spec Delta

## MODIFIED Requirements

### Requirement: Estados compatibles con productores de Company
El esquema SHALL admitir DRAFT, ACTIVE, INACTIVE y ARCHIVED. HU02 SHALL emitir únicamente correcciones efectivas en estados editables, sin cambiar status. HU03 SHALL producir company.updated.v1 al archivar desde DRAFT/INACTIVE con resultado ARCHIVED y el mismo contrato V1. SHALL NOT introducir company.archived.v1 ni habilitar archivado o edición ARCHIVED mediante PATCH.

#### Scenario: Corrección operacional
- **WHEN** HU02 modifica una Company ACTIVE
- **THEN** el evento conserva status ACTIVE y registra la nueva versión de Company

#### Scenario: Preparación contractual para archivado
- **WHEN** se valida un ejemplo de company.updated.v1 con status ARCHIVED y metadatos coherentes
- **THEN** su estado satisface el esquema
- **AND** esta aceptación contractual no habilita un endpoint de archivado ni permite PATCH sobre una Company ya archivada

#### Scenario: Archivado confirmado por HU03
- **WHEN** archive confirma DRAFT → ARCHIVED o INACTIVE → ARCHIVED con almacenamiento habilitado
- **THEN** produce exactamente company.updated.v1 con status ARCHIVED y la revisión/auditoría resultantes
- **AND** no produce company.activated.v1, company.deactivated.v1 ni eventos Site por ese archivado

### Requirement: Contexto original de la modificación
tenantId SHALL proceder del Tenant-Id, correlationId del Process-Id y payload.updatedBy del User-Id de la aceptación original. Fechas SHALL ser UTC con precisión de microsegundos. Para PATCH HTTP y POST archive HTTP causationId SHALL ser null explícito. La clave idempotente SHALL NOT convertirse en un identificador causal.

#### Scenario: Trazabilidad y repetición bajo otro contexto
- **WHEN** se acepta una corrección y después se reproduce con otro actor o proceso válido
- **THEN** el evento conserva actor, proceso y fecha originales sin otra inserción
- **AND** únicamente el header de respuesta del replay usa el Process-Id del intento actual

#### Scenario: Contexto del archivado original
- **WHEN** se acepta archive y se reintenta con otro actor o proceso válido
- **THEN** el evento ARCHIVED mantiene updatedBy, occurredAt y correlationId originales con causationId null
- **AND** el replay no inserta otro evento

### Requirement: Almacenamiento atómico condicionado por modo
Una modificación efectiva HU02 o un archivado HU03 SHALL guardar un único evento PENDING en stored-only/published junto a Company y su completado opcional. disabled SHALL omitirlo. Intentos y versión técnica SHALL empezar en cero, sin confirmación ni errores de entrega. Ambas operaciones SHALL funcionar sin broker y SHALL NOT publicar mensajes en ningún modo.

#### Scenario: Modo con o sin almacenamiento
- **WHEN** confirma un cambio efectivo bajo disabled, stored-only o published
- **THEN** disabled no añade evento y los otros modos guardan exactamente uno
- **AND** no se requiere conexión RabbitMQ, aunque la petición no lleve clave idempotente

#### Scenario: No-op, replay o rollback
- **WHEN** una petición es no-op, replay o revierte su aceptación
- **THEN** no añade un evento confirmado de actualización
- **AND** el fallo de una inserción de evento requerida revierte modificación, auditoría, versión y completado del intento

#### Scenario: Archivado atómico con clave opcional
- **WHEN** archive confirma o falla una escritura obligatoria, con o sin clave
- **THEN** Company ARCHIVED, el completado requerido y el evento habilitado confirman o revierten juntos
- **AND** rechazar archive sobre ACTIVE/ARCHIVED no genera un evento ni consume clave

### Requirement: Contrato verificable y handoff de publicación
HU02 SHALL entregar esquema JSON versionado y ejemplo de company.updated.v1; HU03 SHALL añadir un ejemplo ARCHIVED sin cambiar el payload ni el esquema V1. Ambos SHALL incluirse en recursos JVM/nativos. SHALL documentarse exchange company.events y routing key company.updated.v1 para HU08, validación sin broker y revisión de versión para cambios incompatibles.

#### Scenario: Revisión del productor empaquetado
- **WHEN** se revisa el evento real persistido o el artefacto JVM/nativo sin acceso al directorio de documentación
- **THEN** puede validarse con el esquema entregado y verificarse su relación con Company y auditoría
- **AND** scheduler, claims, leases, publicación, confirms y reintentos de entrega siguen perteneciendo a HU08

#### Scenario: Hecho de archivado empaquetado
- **WHEN** se inspecciona el evento real de archive en JVM/nativo y su ejemplo ARCHIVED
- **THEN** ambos satisfacen el esquema existente y las igualdades companyId=aggregateId y updatedAt=occurredAt
- **AND** el productor HU02 sigue rechazando una corrección nueva sobre Company ARCHIVED
