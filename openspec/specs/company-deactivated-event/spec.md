# company-deactivated-event Specification

## Purpose

Definir el hecho de suspensión operacional de Company como un contrato estable que conserva su aceptación original, sin alterar las sedes y con entrega posterior a cargo de HU08.

## Requirements

### Requirement: Envelope versionado de desactivación Company
company.deactivated.v1 SHALL usar eventId, tenantId, aggregateType, aggregateId, aggregateVersion, eventType, eventSchemaVersion, occurredAt, correlationId, causationId y payload. SHALL identificar COMPANY, la Company desactivada, revisión resultante positiva y esquema 1. eventId SHALL ser UUIDv7 distinto de Company y la versión técnica outbox SHALL ser independiente.

#### Scenario: Suspensión efectiva
- **WHEN** confirma ACTIVE → INACTIVE con almacenamiento habilitado
- **THEN** el evento identifica la revisión Company resultante y eventType company.deactivated.v1

### Requirement: Payload cerrado de desactivación resultante
El payload SHALL contener exactamente companyId, partyId, code, operatingName, status, updatedAt y updatedBy con status INACTIVE. SHALL cumplir companyId=aggregateId y updatedAt=occurredAt. SHALL excluir descripción, logo, proyección Party, identificadores, datos Site, direcciones, contactos y material de idempotencia.

#### Scenario: Estado Company sin cascada
- **WHEN** se serializa una desactivación con sedes ACTIVE existentes
- **THEN** el payload describe únicamente Company INACTIVE
- **AND** no contiene cambios ni versiones artificiales de Site

#### Scenario: Evento incompatible
- **WHEN** aparecen campos adicionales, status distinto de INACTIVE o identidad/fecha incoherentes
- **THEN** la validación contractual rechaza el evento

### Requirement: Contexto original de desactivación
tenantId, correlationId y updatedBy SHALL proceder de Tenant-Id, Process-Id y User-Id originales. Las fechas SHALL ser UTC con precisión de microsegundos y causationId SHALL ser null explícito para HTTP. SHALL conservar valores opacos exactos y excluir la clave idempotente de causalidad y payload.

#### Scenario: Replay bajo otro actor
- **WHEN** se reproduce una desactivación con otro actor o Process-Id válidos
- **THEN** el evento mantiene el contexto de la aceptación original sin otra inserción
- **AND** la respuesta usa el Process-Id actual únicamente en su header

### Requirement: Un solo evento habilitado por desactivación efectiva
stored-only/published SHALL guardar exactamente un evento PENDING junto a Company y su completado opcional, con intentos/versión técnica cero y sin metadatos de entrega. disabled SHALL omitirlo. Desactivación SHALL NOT producir además company.updated.v1 ni eventos Site. Rechazo y replay SHALL NOT crear eventos.

#### Scenario: Aceptación local sin proveedores
- **WHEN** una desactivación elegible confirma en cualquiera de los modos, con o sin clave y con proveedores/broker inaccesibles
- **THEN** disabled omite evento y stored-only/published guardan exactamente company.deactivated.v1
- **AND** HU03 no publica RabbitMQ

#### Scenario: Fallo de escritura obligatoria
- **WHEN** falla almacenar el evento habilitado
- **THEN** Company, revisión, auditoría y completado del intento se revierten juntos

### Requirement: Hecho de desactivación inmutable para entrega
El evento SHALL conservar identidad, revisión, contexto y payload originales tras reactivar/archivar Company o cambiar Party/modo outbox. HU08 SHALL entregar el hecho almacenado conservando eventId en redelivery; SHALL NOT reconstruirlo desde datos actuales ni prometer orden global o entrega exactamente una vez.

#### Scenario: Reactivación antes de publicar
- **WHEN** Company vuelve a ACTIVE después de almacenar su desactivación
- **THEN** el evento conservado sigue describiendo INACTIVE en su revisión original

### Requirement: Contrato de desactivación verificable y empaquetado
HU03 SHALL entregar esquema JSON versionado y ejemplo de company.deactivated.v1 incluidos en recursos JVM/nativos. SHALL documentar exchange company.events y routing key company.deactivated.v1 para HU08. La validación SHALL funcionar sin broker y un cambio incompatible SHALL requerir revisión de versión.

#### Scenario: Revisión del evento real
- **WHEN** se inspecciona un hecho almacenado por el proceso empaquetado o nativo sin acceso a las fuentes
- **THEN** satisface el esquema entregado y sus igualdades de identidad, revisión, auditoría y contexto
- **AND** claims, leases, scheduler, confirms y reintentos de entrega permanecen en HU08
