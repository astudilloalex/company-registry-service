# Proposal

## Why

Company Registry permite registrar y consultar compañías, pero todavía no corregir su información operacional. HU02 ([issue #4](https://github.com/astudilloalex/company-registry-service/issues/4)) incorpora esa capacidad sobre la base T00/HU01 existente, preservando aislamiento tenant, identidad legal, concurrencia, auditoría y replay durable.

## What Changes

- Añadir `PATCH /v1/companies/{companyId}` para modificar exclusivamente `code`, `operatingName`, `description` y `logoUrl`, distinguiendo campos omitidos, `null` explícito y valores presentes. Reutilizar las políticas canónicas y límites de HU01.
- Exigir contexto de negocio e `If-Match` decimal; admitir `Idempotency-Key` opcional. Mantener 400 para precondición ausente/inválida, 412 para versión obsoleta y 404 indistinguible para Company ausente o ajena.
- Devolver HTTP 200 con Company operacional, versión y auditoría, sin Party ni llamadas a proveedores. Permitir edición en DRAFT/ACTIVE/INACTIVE y rechazar nuevos PATCH sobre ARCHIVED con 409, incluso si serían no-op.
- Aceptar `{}` y peticiones canónicamente equivalentes como no-op con versión vigente: conservar auditoría/versión y omitir evento. Con clave, guardar también ese resultado aceptado para replay posterior.
- Implementar protección de concurrencia en PostgreSQL y aceptación atómica de modificación efectiva, completado opcional y evento habilitado. Resolver replay antes de validar la versión o estado actuales, incluyendo después de modificaciones o archivado posteriores.
- Introducir `company.updated.v1` con envelope versionado y payload cerrado de siete metadatos: `companyId`, `partyId`, `code`, `operatingName`, `status`, `updatedAt`, `updatedBy`. Guardarlo condicionalmente sin publicar RabbitMQ.
- Ampliar el contrato persistente de completados `company.update.v1` mediante V3, preservando V1/V2, las seis tablas, los snapshots HU01 y sus políticas históricas.
- Extender OpenAPI, documentación, esquema/ejemplo del evento y verificación de contrato, concurrencia, recuperación durable, JVM y nativo.

## Capabilities

### New Capabilities

- `company-information-management`: actualización operacional parcial tenant-scoped, precondiciones, no-op, auditoría, resultado local y replay idempotente opcional.
- `company-updated-event`: contrato de `company.updated.v1`, trazabilidad, snapshot y almacenamiento transaccional condicional, preparado para HU03/HU08.

### Modified Capabilities

- `company-data-integrity`: procedencia y forma obligatorias de los completados HU02, evolución incremental V3 y protección persistente de las aceptaciones de actualización.

## Impact

API de Company y catálogo de errores; políticas puras Domain; orquestación/puertos Application; adaptadores reactivos, codecs, composición y outbox Infrastructure; nueva migración Flyway V3; contrato OpenAPI y recursos de eventos empaquetados; pruebas existentes y nuevas contra PostgreSQL 18, JVM y binario/imagen nativos. No se requieren nuevas dependencias externas, tablas ni consultas a Party/Geography.

Quedan fuera activación, desactivación, archivado, cambios de Site, modificación legal, nuevos mecanismos de autenticación y publisher RabbitMQ. El productor de archivado pertenece a HU03 y la entrega asíncrona a HU08; el esquema del evento admite ARCHIVED para ese uso futuro. POST/GET y los replays de creación conservan sus contratos enriquecidos actuales. HU01 ya está archivada y sincronizada en las specs principales.
