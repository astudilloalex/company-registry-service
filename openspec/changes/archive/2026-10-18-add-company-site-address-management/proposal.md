# Proposal

## Why

La [HU06 / issue #8](https://github.com/astudilloalex/company-registry-service/issues/8) necesita mantener ubicaciones operacionales de las sedes con referencias geográficas autoritativas y un historial de eliminación. La base ya contiene las tablas, restricciones y adaptadores de referencia; faltan los casos de uso y contratos HTTP que coordinen las direcciones con la revisión compartida de Site.

## What Changes

- Incorporar creación, detalle, listado, PATCH y eliminación lógica bajo `/v1/companies/{companyId}/sites/{siteId}/addresses`, con aislamiento tenant/Company/Site y representación de dirección que incluya `siteVersion`.
- Exigir referencias `ACTIVE` para nuevas asignaciones geográficas, preservar las existentes y evitar consultas remotas cuando no se asigna una referencia nueva. Cambiar país conserva la división omitida y valida la combinación resultante; `null` explícito la elimina.
- Aplicar normalización canónica, limpieza explícita de opcionales y coordenadas indivisibles, dentro de rango y representables exactamente con seis decimales.
- Seleccionar primarias explícitamente por tipo, con sustitución y reclasificación atómicas, sin primera primaria ni reemplazo automáticos. Auditar todas las direcciones modificadas con el actor e instante del Site.
- Coordinar cada cambio efectivo con `If-Match` del Site, bloqueo Company antes de Site, un único incremento y un `company.site.updated.v1` cuando outbox esté habilitado. Mantener no-op y replay sin nuevos efectos.
- Exigir clave idempotente en creación y admitirla opcionalmente en PATCH/DELETE; reproducir el resultado histórico, incluido el comprobante mínimo de eliminación HTTP 200.
- Paginar con cursor autenticado, límite 50 por defecto y rango 1–200, orden descendente por creación/ID y filtros cerrados `addressType`/`isPrimary`.
- Extender OpenAPI, documentación y verificaciones JVM/PostgreSQL/proceso empaquetado/nativo, preservando los contratos HU01–HU05 y la publicación separada HU08.

## Capabilities

### New Capabilities

- `company-site-address-management`: ciclo de gestión de direcciones de Site, referencias geográficas, primarias, coordenadas, consultas, concurrencia, replay y auditoría.

### Modified Capabilities

- `company-data-integrity`: evolución incremental de metadatos de aceptación HU06, snapshots históricos de direcciones/eliminación e índice de paginación, conservando seis tablas y checksums previos.
- `business-api-conventions`: contrato HTTP HU06, catálogo descriptivo de errores y precedencia de entrada, replay, pertenencia/ausencia, revisión, estados y geografía.

## Impact

Domain incorpora políticas puras de dirección; Application coordina los nuevos casos de uso mediante puertos; API incorpora DTOs y recursos; Infrastructure amplía persistencia, codecs, fingerprints, cursores y composición reactiva. Se reutilizan `CountryReferences`, `DivisionReferences`, `AtomicOperation`, `company_idempotency_records` y el contrato mínimo de evento Site actualizado, sin alterar sus payloads publicados.

La evolución parte de V6 y requiere una nueva migración Flyway sobre PostgreSQL 18. No requiere tablas maestras geográficas, acceso a bases ajenas, geocodificación ni RabbitMQ disponible. El acceso ordinario excluye eliminadas; el registro se conserva para auditoría interna, sin añadir restauración o rutas de historial en esta historia. Las APIs existentes y sus formatos históricos permanecen compatibles.
