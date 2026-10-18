# company-activated-event Specification

## Purpose

Definir el hecho de activación o reactivación Company como un contrato de integración estable, almacenado con la aceptación original y preparado para entrega posterior por HU08.

## Requirements

### Requirement: Envelope versionado de activación Company
company.activated.v1 SHALL usar los once campos eventId, tenantId, aggregateType, aggregateId, aggregateVersion, eventType, eventSchemaVersion, occurredAt, correlationId, causationId y payload. SHALL identificar COMPANY, la Company activada, revisión resultante positiva y esquema 1; eventId SHALL ser UUIDv7 distinto de Company.

#### Scenario: Activación inicial y reactivación
- **WHEN** confirma DRAFT → ACTIVE o INACTIVE → ACTIVE con almacenamiento habilitado
- **THEN** el evento identifica la revisión Company resultante y eventType company.activated.v1
- **AND** aggregateVersion no se confunde con la versión técnica outbox

### Requirement: Payload cerrado de activación resultante
El payload SHALL contener exactamente companyId, partyId, code, operatingName, status, updatedAt y updatedBy con status ACTIVE. SHALL cumplir companyId=aggregateId y updatedAt=occurredAt. SHALL excluir descripción, logo, proyección Party, identificadores, datos de matriz/Site, direcciones, contactos y material de idempotencia.

#### Scenario: Metadatos canónicos y privados excluidos
- **WHEN** se serializa el hecho aceptado
- **THEN** conserva código/nombre canónicos y actor exacto en los siete campos aprobados
- **AND** no incorpora el resultado de la validación remota ni datos legales adicionales

#### Scenario: Contrato incompatible
- **WHEN** el payload tiene propiedades adicionales, status distinto de ACTIVE o igualdades de identidad/fecha incompatibles
- **THEN** la validación contractual lo rechaza

### Requirement: Contexto original de activación
tenantId, correlationId y updatedBy SHALL proceder del Tenant-Id, Process-Id y User-Id de la aceptación original. Las fechas SHALL ser UTC con precisión de microsegundos y causationId SHALL ser null explícito para el comando HTTP. La clave idempotente SHALL NOT convertirse en causalidad.

#### Scenario: Reintento con otro contexto
- **WHEN** una activación aceptada se reproduce con otro actor/proceso
- **THEN** el evento conserva actor, correlación y fecha originales sin otra inserción
- **AND** el header de respuesta usa únicamente el Process-Id del intento actual

### Requirement: Un solo evento habilitado por activación efectiva
stored-only/published SHALL guardar exactamente un evento PENDING en la transacción de Company y su completado opcional, con intentos/versión técnica cero y sin metadatos de entrega. disabled SHALL omitirlo. Activación SHALL NOT generar además company.updated.v1 ni eventos Site. Rechazo y replay SHALL NOT generar eventos.

#### Scenario: Modos y clave opcional
- **WHEN** una activación confirma en cualquiera de los tres modos, con o sin clave
- **THEN** disabled no añade evento y los otros modos guardan exactamente company.activated.v1
- **AND** no existe conexión/publicación RabbitMQ como parte de HU03

#### Scenario: Fallo de almacenamiento requerido
- **WHEN** falla insertar el evento habilitado
- **THEN** revierten Company, auditoría, incremento y completado de ese intento

### Requirement: Hecho de activación inmutable para entrega
El evento almacenado SHALL conservar identidad, revisión, contexto y payload originales después de desactivar/archivar Company o cambiar Party/modo outbox. HU08 SHALL entregar el hecho guardado con el mismo eventId ante redelivery, sin reconstruirlo desde datos actuales ni prometer entrega exactamente una vez u orden global.

#### Scenario: Desactivación antes de publicar
- **WHEN** Company queda INACTIVE o ARCHIVED después de guardar una activación
- **THEN** el evento sigue describiendo ACTIVE y su revisión original

### Requirement: Contrato de activación verificable y empaquetado
HU03 SHALL entregar esquema JSON versionado y ejemplo de company.activated.v1, incluidos en los recursos JVM/nativos. SHALL documentar exchange company.events y routing key company.activated.v1 para HU08. Su validación SHALL funcionar sin broker y un cambio incompatible SHALL requerir revisión de versión.

#### Scenario: Validar hecho real del proceso
- **WHEN** se inspecciona un evento almacenado por el proceso empaquetado o nativo sin acceso al directorio fuente
- **THEN** satisface el esquema entregado y las igualdades con Company, versión, auditoría y contexto
- **AND** scheduler, claims, leases, confirms y reintentos de publicación quedan en HU08
