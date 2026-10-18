# company-site-updated-event Specification

## Purpose

Conservar el hecho original de actualización operacional de Site con un contrato mínimo versionado y almacenamiento atómico, reutilizable por futuras mutaciones de direcciones y contactos.

## Requirements

### Requirement: Envelope de actualización Site
company.site.updated.v1 SHALL usar los once campos del envelope existente: eventId, tenantId, aggregateType, aggregateId, aggregateVersion, eventType, eventSchemaVersion, occurredAt, correlationId, causationId y payload. SHALL identificar COMPANY_SITE, Site y su revisión resultante positiva, esquema 1 y UUIDv7 de evento distinto del Site.

#### Scenario: Identidad de nueva revisión
- **WHEN** un cambio efectivo HU05 de información, tipo, activación o desactivación confirma con evento habilitado
- **THEN** el evento identifica al Site afectado y la revisión resultante, con eventType company.site.updated.v1
- **AND** la versión técnica de entrega permanece independiente

### Requirement: Payload mínimo de Site actualizado
El payload SHALL contener exactamente siteId, companyId, code, name, siteType, status, updatedAt y updatedBy, con valores canónicos resultantes. status SHALL admitir DRAFT, ACTIVE e INACTIVE. SHALL cumplir siteId igual a aggregateId y updatedAt igual a occurredAt, sin incluir operatividad, datos Company/Party, direcciones, contactos ni material idempotente.

#### Scenario: Hecho completo de actualización
- **WHEN** se produce una actualización de Site
- **THEN** los ocho campos describen el resultado aceptado con sus tipos, límites y auditoría

#### Scenario: Campo o estado fuera del contrato
- **WHEN** un evento incluye effectivelyOperational, changedFields, datos de dirección/contacto, otra propiedad o status CLOSED
- **THEN** su contrato cerrado lo rechaza

### Requirement: Contexto original de actualización preservado
tenantId SHALL ser el tenant aceptado, correlationId el Process-Id original y updatedBy el actor exacto de la aceptación. occurredAt/updatedAt SHALL usar un único instante UTC con precisión de microsegundos. causationId SHALL ser null explícito para las mutaciones HTTP HU05.

#### Scenario: Respuesta perdida y nuevo proceso
- **WHEN** un replay usa otro actor/proceso válidos
- **THEN** el evento almacenado mantiene la correlación, actor e instante originales sin duplicarse

### Requirement: Un evento actualizado por cambio efectivo habilitado
Cada cambio efectivo HU05 distinto del cierre SHALL almacenar exactamente un updated cuando outbox está stored-only o published; disabled SHALL omitirlo. El evento habilitado SHALL confirmar con Site y el completado opcional. No-op y replay SHALL NOT producirlo. HU05 SHALL almacenar PENDING sin publicar al broker.

#### Scenario: Modos outbox
- **WHEN** una actualización confirma bajo disabled, stored-only o published con broker inaccesible
- **THEN** persiste respectivamente cero, uno o un evento PENDING con intentos y versión técnica cero, sin publicación

#### Scenario: Fallo del evento
- **WHEN** falla una escritura de evento habilitado
- **THEN** revierte también Site y la aceptación del intento

#### Scenario: No-op o replay
- **WHEN** se acepta un PATCH sin cambios o se recupera una aceptación
- **THEN** no aparece otro evento ni incremento de revisión

### Requirement: Evento común para cambios de hijos
El contrato SHALL permitir que HU06/HU07 produzcan company.site.updated.v1 con revisión y auditoría resultantes de Site aun cuando code/name/siteType/status no cambien. SHALL mantener el payload mínimo sin direcciones completas, coordenadas, correos o teléfonos. Los consumidores que necesiten detalles SHALL consultarlos bajo la política de acceso aplicable.

#### Scenario: Cambio de dirección o contacto
- **WHEN** un futuro productor compatible modifica un hijo y avanza Site de revisión N a N+1
- **THEN** puede emitir el mismo contrato con aggregateVersion N+1, auditoría nueva y datos básicos Site conservados
- **AND** HU05 no incorpora el CRUD del hijo ni agrega datos sensibles al evento

### Requirement: Hecho inmutable y handoff updated
El evento confirmado SHALL conservar identidad, payload y metadatos originales aunque cambien Site o Company. SHALL entregarse esquema JSON y ejemplo empaquetados JVM/nativo. HU08 SHALL utilizar exchange company.events y routing key company.site.updated.v1 con entrega al menos una vez, sin promesa de orden global ni entrega exactamente una vez.

#### Scenario: Publicación posterior a otra mutación
- **WHEN** HU08 entrega el evento después de otros cambios
- **THEN** utiliza el hecho almacenado y conserva eventId/contenido ante redelivery, sin reconstruirlo con valores actuales

#### Scenario: Verificación contractual independiente
- **WHEN** se valida ejemplo y evento real del proceso empaquetado o nativo sin RabbitMQ
- **THEN** puede comprobarse su contrato cerrado y sus invariantes cruzadas
