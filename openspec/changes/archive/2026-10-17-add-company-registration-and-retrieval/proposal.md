# Proposal

## Why

HU01 ([issue #3](https://github.com/astudilloalex/company-registry-service/issues/3)) debe convertir la base T00 en una capacidad verificable de registro y consulta de compañías operacionales por tenant. El servicio ya tiene normalización, clientes, integridad SQL y transacciones reactivas, pero todavía no implementa rutas comerciales, replay durable ni consultas paginadas.

## What Changes

- Implementar `POST /v1/companies`, `GET /v1/companies/{companyId}` y `GET /v1/companies`, con filtros exactos `code`, `partyId` y `status`, paginación por cursor y aislamiento tenant. Exigir exactamente un `Tenant-Id`, `User-Id` y `Process-Id` en todas las peticiones de negocio, incluidos GET y replay; propagar los tres a Party Registry. Según la aclaración de interoperabilidad confirmada, Tenant-Id y Process-Id serán UUIDs canónicos en minúsculas: se rechazarán otras representaciones en vez de normalizarlas silenciosamente.
- Registrar Company con UUIDv7, estado `DRAFT`, versión `0`, texto canónico y auditoría del servidor en UTC/microsegundos. Admitir únicamente `partyId`, `code`, `operatingName`, `description` y `logoUrl` como entrada; conservar unicidad por tenant/código y tenant/Party, incluso después de archivar Company.
- Aplicar las cinco decisiones acordadas:
  1. PostgreSQL arbitra las carreras mediante las restricciones existentes. Después del rollback y cierre de la sesión perdedora, consultar el completado durable en una sesión nueva para devolver replay, conflicto de clave o conflicto de negocio; la espera es finita.
  2. Calcular HMAC SHA-256 sobre una representación efectiva canónica, determinista y versionada. Equiparar opcionales omitidos y `null`; excluir actor/proceso del fingerprint. Persistir versión de fingerprint e identificador de clave para verificar reintentos con claves anteriores durante la rotación.
  3. Permitir nuevas creaciones con Party `LEGAL_ENTITY` del tenant en `DRAFT`, `ACTIVE` o `INACTIVE`; rechazar `ARCHIVED`. Exigir Party `ACTIVE` para activar Company sigue perteneciendo a HU03.
  4. Devolver una representación uniforme de Company, con `createdAt`, `createdBy`, `updatedAt`, `updatedBy`, y `party` enriquecido con `legalEntityDetails` e `identifiers` según los DTOs públicos reales del proveedor. Los identificadores contienen `maskedValue`. Incluir esa respuesta completa en el snapshot original de creación para que el replay no consulte datos actuales.
  5. Producir `company.created.v1` con el envelope de HU08 y payload cerrado: `companyId`, `partyId`, `code`, `operatingName`, `status`, `createdAt`, `createdBy`. Versión de agregado `0`, esquema `1`, correlación original y causalidad `null` para esta operación HTTP. El evento conserva su identidad y contenido; no incorpora el enriquecimiento legal de la respuesta REST.
- Guardar Company, completado y evento habilitado en la misma transacción. `disabled` omite el evento; `stored-only` y `published` lo almacenan como `PENDING`. Los replays no escriben ni generan eventos.
- Publicar el OpenAPI real, ejemplos de respuesta/evento, configuración de secretos y plazos, y pruebas de políticas, PostgreSQL concurrente, contratos REST y proceso nativo.
- Mantener fuera de alcance actualizaciones, activación/archival, sedes, direcciones/contactos, cambios en Party Registry y publicación RabbitMQ. La futura publicación de HU08 usará exchange `company.events` y routing key `company.created.v1` para este evento.

## Capabilities

### New Capabilities

- `company-registration`: registro canónico, elegibilidad de Party, idempotencia durable con rotación y resolución de carreras, y atomicidad de la creación.
- `company-retrieval`: representación enriquecida uniforme, auditoría, consultas tenant-scoped por ID/código/Party y colección paginada.
- `company-created-event`: contrato de `company.created.v1`, trazabilidad, snapshot operacional inmutable e inserción outbox condicional.

### Modified Capabilities

- `business-api-conventions`: exigir UUIDs canónicos en minúsculas para Tenant-Id y Process-Id en todas las rutas de negocio, manteniendo propagación y eco exactos de los valores aceptados.
- `external-reference-validation`: conservar la validación mínima y añadir una proyección de lectura de Party e identificadores, con contratos y fallos externos explícitos; permitir únicamente el snapshot histórico no autoritativo requerido por el replay.
- `company-data-integrity`: ampliar el completado idempotente con metadatos de fingerprint mediante una migración incremental, preservando V1, claves compuestas, seis tablas y snapshots existentes.

## Impact

Se amplían puertos y modelos neutrales en Application, composición y adaptadores reactivos en Infrastructure, DTOs/recursos/mapeo de errores en API y políticas puras de Domain. Se reutilizan `AtomicOperation`, `BusinessText`, `CompanyRepository`, `CompletionRepository`, `OutboxPolicy` y la biblioteca de respuestas compartida.

La respuesta enriquecida hace que las consultas actuales dependan de los contratos de detalle e identificadores de Party Registry; un replay aceptado permanece independiente de esa disponibilidad. Party mantiene la autoridad de los datos legales: no se crean columnas de identidad legal ni tablas espejo; el snapshot idempotente es la única copia histórica necesaria para reproducir el resultado aceptado. Nuevos secretos/versiones y plazos requieren configuración externa coherente entre instancias. HU08 recibirá un evento ya documentado y almacenado, sin que HU01 añada un publisher o requiera broker.
