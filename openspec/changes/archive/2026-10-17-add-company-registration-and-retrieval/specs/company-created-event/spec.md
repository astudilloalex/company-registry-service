# Spec Delta

## Purpose

Ofrecer un contrato estable del hecho de creación de Company, almacenado atómicamente cuando corresponda y preparado para la publicación asíncrona de HU08.

## ADDED Requirements

### Requirement: Envelope versionado del evento de creación
El evento SHALL tener `eventId`, `tenantId`, `aggregateType`, `aggregateId`, `aggregateVersion`, `eventType`, `eventSchemaVersion`, `occurredAt`, `correlationId`, `causationId` y `payload`. SHALL usar UUIDv7 propio como eventId, tipo `company.created.v1`, agregado `COMPANY`, versión de agregado `0` y versión de esquema `1`.

#### Scenario: Identidad y versiones de creación
- **WHEN** se construye el evento de una Company recién registrada
- **THEN** aggregateId identifica esa Company, eventId es distinto y aggregateVersion es 0
- **AND** eventSchemaVersion es 1 y permanece independiente de la versión técnica de entrega del registro outbox

### Requirement: Payload operacional cerrado
El payload SHALL contener exactamente `companyId`, `partyId`, `code`, `operatingName`, `status`, `createdAt` y `createdBy`. SHALL reflejar el registro aceptado con texto canónico y status `DRAFT`. SHALL mantener igualdad de companyId con aggregateId y de createdAt con occurredAt.

#### Scenario: Campos aprobados y referencia legal
- **WHEN** se valida el evento contra su esquema
- **THEN** el payload contiene los siete campos acordados con sus tipos y límites de Company
- **AND** usa partyId como referencia sin incorporar party, legalEntityDetails, identifiers, valores legales completos o enmascarados, description, logoUrl, emails, teléfonos o material de idempotencia

### Requirement: Trazabilidad original y causalidad HTTP
El evento SHALL conservar tenantId del Tenant-Id, correlationId del Process-Id y payload.createdBy del User-Id de la creación confirmada. SHALL usar fechas UTC/microsegundos y `causationId: null` explícito para HU01 HTTP, sin convertir la clave idempotente ni el proceso en un identificador causante inventado.

#### Scenario: Contexto de creación preservado
- **WHEN** una petición válida registra Company y su evento
- **THEN** el envelope y payload contienen el tenant, proceso y actor originales exactos conforme a sus tipos
- **AND** occurredAt y createdAt coinciden con el instante aceptado de Company

#### Scenario: Replay bajo otro proceso
- **WHEN** se repite una petición aceptada con otro Process-Id o actor válido
- **THEN** no cambia el evento ya almacenado, su contexto o sus fechas, y no aparece un evento adicional

### Requirement: Persistencia condicional y atómica del evento
El modo `disabled` SHALL omitir el nuevo evento. Los modos `stored-only` y `published` SHALL guardarlo como `PENDING` junto con Company y completado en la misma transacción. SHALL comenzar con cero intentos, versión técnica cero y sin publicación confirmada. HU01 SHALL implementar producción y almacenamiento, sin enviar mensajes.

#### Scenario: Cada modo aprobado
- **WHEN** una creación confirma bajo disabled, stored-only o published
- **THEN** disabled deja cero eventos nuevos y los otros modos dejan exactamente un evento PENDING de esa creación
- **AND** el éxito no exige una conexión RabbitMQ

#### Scenario: Fallo de persistencia o carrera perdida
- **WHEN** falla la creación, el completado, el evento o la transacción pierde la carrera idempotente
- **THEN** no queda un evento confirmado atribuible al intento revertido

### Requirement: Identidad y snapshot inmutables para publicación futura
Después de confirmar la creación, la identidad, contenido, versión de agregado, fecha y contexto del evento SHALL conservarse en los posteriores intentos de entrega. La publicación futura SHALL usar el snapshot almacenado y no reconstruir el hecho consultando Company o Party actuales.

#### Scenario: Company cambia antes de publicar
- **WHEN** Company cambia de nombre, código, estado o versión después de su creación y antes de la entrega
- **THEN** company.created.v1 conserva los datos originales y aggregateVersion 0

#### Scenario: Reintento de entrega
- **WHEN** HU08 vuelve a entregar un evento por recuperación o confirmación incierta
- **THEN** conserva el mismo eventId y contenido, permitiendo deduplicación por eventId
- **AND** el contrato declara entrega al menos una vez sin prometer orden global ni exactamente una entrega

### Requirement: Contrato de integración entregable a HU08
El cambio SHALL entregar esquema JSON y ejemplo validable de company.created.v1, incluyendo causalidad nullable explícita. SHALL documentar exchange `company.events` y routing key `company.created.v1` para la futura publicación, cuya configuración y bindings efectivos corresponden a HU08. Cambios incompatibles SHALL requerir una revisión de versión explícita.

#### Scenario: Validación independiente del broker
- **WHEN** se revisa o prueba el productor HU01 sin RabbitMQ
- **THEN** puede validar el evento almacenado contra el contrato publicado y verificar campos, identidad y trazabilidad
- **AND** scheduler, claims, leases, confirms y reintentos AMQP permanecen en HU08
