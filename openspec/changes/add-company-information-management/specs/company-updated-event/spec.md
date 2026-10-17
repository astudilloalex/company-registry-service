# Spec Delta

## Purpose

Definir el hecho de actualización de Company como un contrato de integración estable, conservado atómicamente y preparado para los productores de información operacional y archivado y para su entrega futura.

## ADDED Requirements

### Requirement: Envelope versionado de actualización
`company.updated.v1` SHALL usar eventId, tenantId, aggregateType, aggregateId, aggregateVersion, eventType, eventSchemaVersion, occurredAt, correlationId, causationId y payload. SHALL identificar COMPANY, la Company modificada, su versión resultante positiva y esquema 1. eventId SHALL ser UUIDv7 propio, distinto del ID de Company e independiente de la versión técnica outbox.

#### Scenario: Identidad del cambio confirmado
- **WHEN** una corrección efectiva produce un evento
- **THEN** aggregateId identifica Company, aggregateVersion coincide con su nueva versión y eventSchemaVersion es 1
- **AND** eventId es distinto del ID de Company y la versión técnica de entrega comienza separadamente en cero

### Requirement: Payload cerrado de metadatos resultantes
El payload SHALL contener exactamente companyId, partyId, code, operatingName, status, updatedAt y updatedBy. SHALL reflejar el estado resultante canónico y cumplir companyId=aggregateId y updatedAt=occurredAt. SHALL excluir description, logoUrl, datos Party/legales, identificadores, contactos y material de idempotencia.

#### Scenario: Cambio solo de descripción o URL
- **WHEN** una corrección efectiva únicamente modifica o limpia description o logoUrl
- **THEN** produce el mismo tipo de evento con los siete metadatos resultantes y la nueva versión
- **AND** no incorpora esos valores ni campos adicionales al payload

#### Scenario: Campos o igualdades incompatibles
- **WHEN** un evento contiene propiedades adicionales, tipo inválido, companyId distinto de aggregateId o updatedAt distinto de occurredAt
- **THEN** el contrato de evento lo rechaza

### Requirement: Estados compatibles con productores de Company
El esquema SHALL admitir DRAFT, ACTIVE, INACTIVE y ARCHIVED. El productor HU02 SHALL emitir únicamente correcciones efectivas en estados editables, sin cambiar status. ARCHIVED SHALL quedar reservado para el productor de archivado HU03 con el mismo contrato, sin introducir un nuevo tipo de evento ni implementar ese workflow en HU02.

#### Scenario: Corrección operacional
- **WHEN** HU02 modifica una Company ACTIVE
- **THEN** el evento conserva status ACTIVE y registra la nueva versión de Company

#### Scenario: Preparación contractual para archivado
- **WHEN** se valida un ejemplo de company.updated.v1 con status ARCHIVED y metadatos coherentes
- **THEN** su estado satisface el esquema
- **AND** esta aceptación contractual no habilita un endpoint de archivado ni permite PATCH sobre una Company ya archivada

### Requirement: Contexto original de la modificación
tenantId SHALL proceder del Tenant-Id, correlationId del Process-Id y payload.updatedBy del User-Id de la aceptación original. Fechas SHALL ser UTC con precisión de microsegundos. Para PATCH HTTP causationId SHALL ser null explícito. La clave idempotente SHALL NOT convertirse en un identificador causal.

#### Scenario: Trazabilidad y repetición bajo otro contexto
- **WHEN** se acepta una corrección y después se reproduce con otro actor o proceso válido
- **THEN** el evento conserva actor, proceso y fecha originales sin otra inserción
- **AND** únicamente el header de respuesta del replay usa el Process-Id del intento actual

### Requirement: Almacenamiento atómico condicionado por modo
Una modificación efectiva SHALL guardar un único evento PENDING en stored-only/published dentro de la misma aceptación que Company y su completado opcional. disabled SHALL omitirlo. Intentos y versión técnica SHALL empezar en cero, sin confirmación ni errores de entrega. HU02 SHALL funcionar sin broker y SHALL NOT publicar mensajes en ningún modo.

#### Scenario: Modo con o sin almacenamiento
- **WHEN** confirma un cambio efectivo bajo disabled, stored-only o published
- **THEN** disabled no añade evento y los otros modos guardan exactamente uno
- **AND** no se requiere conexión RabbitMQ, aunque la petición no lleve clave idempotente

#### Scenario: No-op, replay o rollback
- **WHEN** una petición es no-op, replay o revierte su aceptación
- **THEN** no añade un evento confirmado de actualización
- **AND** el fallo de una inserción de evento requerida revierte modificación, auditoría, versión y completado del intento

### Requirement: Snapshot de evento preservado
Una vez aceptado, el hecho de actualización SHALL conservar identidad, metadatos y payload originales aunque cambien Company, Party o el modo outbox. La entrega futura SHALL serializar lo almacenado y conservarlo ante redelivery, con deduplicación por eventId y revisión por aggregateVersion, sin prometer orden global ni entrega exactamente una vez.

#### Scenario: Mutación posterior antes de entregar
- **WHEN** Company cambia otra vez o se archiva antes de publicar un evento almacenado
- **THEN** ese evento mantiene la versión, estado, valores y auditoría de su aceptación original

### Requirement: Contrato verificable y handoff de publicación
HU02 SHALL entregar un esquema JSON versionado y ejemplo de company.updated.v1, incluidos en los recursos del artefacto JVM/nativo. SHALL documentar exchange company.events y routing key company.updated.v1 para HU08. El contrato SHALL permitir validación sin broker y cambios incompatibles SHALL requerir revisión explícita de versión.

#### Scenario: Revisión del productor empaquetado
- **WHEN** se revisa el evento real persistido o el artefacto JVM/nativo sin acceso al directorio de documentación
- **THEN** puede validarse con el esquema entregado y verificarse su relación con Company y auditoría
- **AND** scheduler, claims, leases, publicación, confirms y reintentos de entrega siguen perteneciendo a HU08
