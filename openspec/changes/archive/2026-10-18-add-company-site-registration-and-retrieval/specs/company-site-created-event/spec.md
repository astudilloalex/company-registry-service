# Spec Delta

## Purpose

Conservar el hecho original de creación de una sede mediante un contrato de integración versionado, almacenado atómicamente cuando corresponde y preparado para la publicación futura de HU08.

## ADDED Requirements

### Requirement: Envelope de creación de Site versionado
El evento SHALL contener exactamente eventId, tenantId, aggregateType, aggregateId, aggregateVersion, eventType, eventSchemaVersion, occurredAt, correlationId, causationId y payload. SHALL usar UUIDv7 propio, COMPANY_SITE, siteId como aggregateId, aggregateVersion 0, eventType `company.site.created.v1` y eventSchemaVersion 1.

#### Scenario: Identidad y versiones del evento
- **WHEN** se produce el hecho de una sede recién registrada
- **THEN** eventId es distinto de siteId, aggregateId identifica la sede y las versiones de agregado/esquema son 0/1
- **AND** la versión técnica de entrega del outbox permanece independiente

### Requirement: Payload cerrado de ocho campos de Site
El payload SHALL contener exactamente siteId, companyId, code, name, siteType, status, createdAt y createdBy. SHALL conservar valores canónicos aceptados, tipo admitido y status DRAFT. SHALL mantener siteId igual a aggregateId y createdAt igual a occurredAt.

#### Scenario: Campos e invariantes del payload
- **WHEN** se valida el evento producido o su ejemplo contractual
- **THEN** contiene los ocho campos con sus tipos y límites de registro
- **AND** coincide con la identidad, Company, tipo, texto y auditoría de la sede aceptada

#### Scenario: Datos fuera del contrato
- **WHEN** se intenta incluir effectivelyOperational, Party, direcciones, contactos, versión duplicada en payload o material de idempotencia
- **THEN** el contrato cerrado rechaza esos campos
- **AND** la operatividad efectiva continúa siendo una proyección de consulta

### Requirement: Contexto original de creación preservado
El evento SHALL usar tenantId del contexto, correlationId del Process-Id y createdBy del User-Id originales exactos. occurredAt/createdAt SHALL ser el instante UTC de creación con precisión de microsegundos. causationId SHALL ser null explícito para la creación HTTP.

#### Scenario: Correlación y causalidad HTTP
- **WHEN** una petición crea una sede y un evento habilitado
- **THEN** conserva tenant, actor y proceso originales y la misma fecha aceptada de Site
- **AND** no convierte la clave idempotente o Process-Id en un causationId inventado

#### Scenario: Reintento bajo otro actor o proceso
- **WHEN** se recupera una aceptación con otro User-Id o Process-Id válidos
- **THEN** el evento almacenado conserva su contexto original y no aparece otro evento

### Requirement: Persistencia condicional atómica de Site-created
El modo disabled SHALL omitir el nuevo evento. stored-only y published SHALL guardar exactamente un evento PENDING con intentos y versión técnica cero, sin publicación confirmada, en la transacción de Site y completado. HU04 SHALL producir/almacenar el hecho sin publicar mensajes.

#### Scenario: Modos aprobados sin broker
- **WHEN** registra Site bajo cada modo con RabbitMQ inaccesible
- **THEN** disabled deja cero eventos y stored-only/published dejan uno PENDING con el estado inicial contratado
- **AND** ningún modo conecta un publisher de HU04

#### Scenario: Fallo de evento o pérdida de carrera
- **WHEN** falla una escritura requerida o el intento pierde la aceptación idempotente
- **THEN** revierte Site, completado y evento del intento antes de resolver la aceptación ganadora

### Requirement: Hecho original inmutable para entrega futura
El evento confirmado SHALL conservar identidad, payload, fecha, contexto y revisión inicial aunque cambie Site o Company. La publicación futura SHALL utilizar el snapshot almacenado y preservar eventId en reintentos de entrega, con deduplicación posible por ese identificador.

#### Scenario: Mutación o cierre posterior
- **WHEN** Site cambia de código, nombre, tipo, estado o versión, o Company se archiva antes de publicar
- **THEN** el hecho de creación conserva sus datos DRAFT y aggregateVersion 0 originales

#### Scenario: Replay tras cambio de modo
- **WHEN** una creación aceptada en disabled se reintenta con almacenamiento habilitado
- **THEN** no se fabrica un evento retrospectivo

### Requirement: Handoff contractual verificable para HU08
La entrega SHALL incluir esquema JSON y ejemplo validable de company.site.created.v1, empaquetados para JVM/nativo. SHALL documentar exchange company.events y routing key company.site.created.v1 para HU08, cuya entrega será al menos una vez sin prometer orden global ni exactamente una entrega. Cambios incompatibles SHALL requerir revisión de versión.

#### Scenario: Verificación independiente de publicación
- **WHEN** se prueba el productor y el proceso nativo sin RabbitMQ
- **THEN** puede validarse su evento real contra el esquema y ejemplo empaquetados
- **AND** scheduler, claims, leases, confirmaciones y reintentos de publicación permanecen en HU08
