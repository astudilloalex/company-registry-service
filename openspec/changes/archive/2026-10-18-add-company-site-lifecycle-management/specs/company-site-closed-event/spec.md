# Spec Delta

## Purpose

Conservar el cierre terminal de Site como un hecho original de integración, con payload mínimo coherente con actualización y almacenamiento transaccional preparado para HU08.

## ADDED Requirements

### Requirement: Envelope de cierre Site
company.site.closed.v1 SHALL usar el envelope existente de once campos, UUIDv7 propio distinto del Site, aggregateType COMPANY_SITE, aggregateId igual a siteId, aggregateVersion resultante positiva y eventSchemaVersion 1. SHALL conservar tenant, contexto y revisión de la aceptación original.

#### Scenario: Identificación del cierre
- **WHEN** close confirma con outbox habilitado
- **THEN** el evento identifica la revisión terminal del Site y eventType company.site.closed.v1
- **AND** su versión de agregado no se confunde con la versión técnica de entrega

### Requirement: Payload mínimo de Site cerrado
El payload SHALL contener exactamente siteId, companyId, code, name, siteType, status, updatedAt y updatedBy. status SHALL ser CLOSED. Los valores SHALL corresponder al cierre aceptado, con siteId igual a aggregateId y updatedAt igual a occurredAt. SHALL excluir operatividad, direcciones, contactos, información legal y material idempotente.

#### Scenario: Estado terminal y datos conservados
- **WHEN** una sede se cierra
- **THEN** su evento conserva código, nombre, tipo y propiedad resultantes y fija status CLOSED

#### Scenario: Payload incompatible
- **WHEN** se usa otro status, un campo adicional o identidad/fecha contradictorias
- **THEN** el esquema cerrado o la validación de invariantes cruzadas rechaza el evento

### Requirement: Auditoría original del cierre
occurredAt y payload.updatedAt SHALL ser el mismo instante UTC con precisión de microsegundos usado al cerrar Site. payload.updatedBy SHALL conservar User-Id exacto y correlationId el Process-Id originales. causationId SHALL ser null explícito para close HTTP.

#### Scenario: Replay del cierre
- **WHEN** el cierre aceptado se reintenta con otro actor/proceso válidos
- **THEN** devuelve su aceptación sin modificar el evento ni su auditoría/correlación originales

### Requirement: Exclusividad y atomicidad del evento de cierre
Un cierre efectivo SHALL almacenar solo company.site.closed.v1 y SHALL NOT almacenar updated por la misma aceptación. disabled SHALL omitirlo; stored-only y published SHALL guardar un PENDING con intentos y versión técnica cero, junto con Site y completado opcional. HU05 SHALL NOT publicar al broker.

#### Scenario: Cierre por cada modo
- **WHEN** confirma close en disabled, stored-only o published
- **THEN** el número de eventos nuevos es cero, uno o uno respectivamente y ningún evento es updated

#### Scenario: Fallo de persistencia
- **WHEN** falla el evento de cierre habilitado antes de commit
- **THEN** Site no queda cerrado ni se conserva el completado del intento revertido

#### Scenario: Comando redundante o replay
- **WHEN** se presenta un close nuevo sobre CLOSED o un replay coincidente
- **THEN** el primero devuelve 409 y el segundo su resultado original, sin eventos adicionales

### Requirement: Hecho inmutable y handoff closed
El evento cerrado SHALL conservar identidad y contenido originales para entrega posterior. SHALL entregarse esquema JSON y ejemplo empaquetados JVM/nativo. HU08 SHALL utilizar exchange company.events y routing key company.site.closed.v1, preservando eventId ante redelivery con entrega al menos una vez y sin orden global garantizado.

#### Scenario: Entrega tras archivado Company
- **WHEN** el cierre se publica después de archivar Company
- **THEN** se entrega el hecho original sin consultar estados actuales ni recalcular su payload

#### Scenario: Validación fuera del repositorio
- **WHEN** un proceso empaquetado o nativo arranca sin acceso al directorio docs
- **THEN** conserva el esquema/ejemplo de cierre como recursos verificables y puede producir el evento sin broker
