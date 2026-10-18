# Proposal

## Why

HU04 ([issue #6](https://github.com/astudilloalex/company-registry-service/issues/6)) necesita registrar y consultar sedes operacionales independientes de Company para habilitar los posteriores ciclos de vida, direcciones y contactos. La base actual ya proporciona el agregado Site, persistencia reactiva e integridad SQL, pero carece de los casos de uso, endpoints, replay y contrato de evento de esta historia.

## What Changes

- Incorporar `POST /v1/companies/{companyId}/sites`, `GET /v1/companies/{companyId}/sites/{siteId}` y `GET /v1/companies/{companyId}/sites`, con contexto obligatorio y pertenencia tenant/Company verificada localmente. El POST exige `Idempotency-Key`, sin requerir `If-Match`.
- Aceptar únicamente `code`, `name` y `siteType`. Canonizar código/nombre antes de validar límites de 64/200 puntos de código. Omitir siteType equivale a BRANCH; null y valores fuera de HEADQUARTERS/BRANCH/WAREHOUSE/OTHER se rechazan.
- Registrar UUIDv7, DRAFT, versión 0 y auditoría UTC/microsegundos. Bloquear Company para toda creación nueva y rechazar ARCHIVED, sin modificar versión ni auditoría del padre. Conservar código único por Company incluso para CLOSED y como máximo un HEADQUARTERS no cerrado.
- Devolver una proyección local uniforme con `siteId`, `companyId`, `code`, `name`, `siteType`, `status`, `version`, `effectivelyOperational` y cuatro campos de auditoría. Calcular operatividad desde Company ACTIVE y Site ACTIVE; excluir Party, direcciones y contactos.
- Conservar visibilidad histórica, incluyendo CLOSED por defecto y sedes de Company ARCHIVED. Devolver 404 `company-not-found` para padre inexistente/ajeno, 404 `site-not-found` para Site fuera de ese ámbito y 200 con `data: []` para Company propia sin coincidencias.
- Ofrecer filtros exactos code/siteType/status con AND y paginación hacia adelante: límite 1–200, predeterminado 50, orden created_at/id descendente, cursor autenticado ligado a tenant/Company/filtros/orden, nextCursor y numberOfElements, sin totales globales.
- Confirmar Site, snapshot idempotente y outbox habilitado en una transacción. Usar fingerprint HMAC versionado incluyendo Company de la ruta y replay 201 original aun después de cierre/archivado. Resolver carreras en sesión nueva tras rollback, con espera finita y 503 ante incertidumbre no resuelta.
- Exponer causas concretas directamente en `code`, siguiendo Party Registry: `site-code-already-exists`, `headquarters-already-exists`, `idempotency-key-already-used-with-different-request` y `site-registration-not-allowed-for-archived-company` para 409; validaciones descriptivas con 400. Preservar contratos HU01/HU02 y el mapper compartido.
- Producir `company.site.created.v1` con agregado COMPANY_SITE, revisión 0 y payload cerrado de ocho campos: siteId/companyId/code/name/siteType/status/createdAt/createdBy. Persistir condicionalmente; mantener publicación en HU08.
- Ampliar restricciones de completados e índice de consulta mediante migración incremental, preservando V1–V3, seis tablas, aceptaciones y claves históricas. Entregar contratos y evidencia JVM, concurrencia, reinicio y proceso nativo.

## Capabilities

### New Capabilities

- `company-site-registration`: admisión local, entrada canónica, bloqueo del padre, unicidad, auditoría, idempotencia durable y recuperación concurrente del registro de Site.
- `company-site-retrieval`: representación local, operatividad derivada, historial, aislamiento tenant/Company y listado filtrado con cursor.
- `company-site-created-event`: hecho de creación de Site, payload/envelope versionados y almacenamiento transaccional condicional para HU08.

### Modified Capabilities

- `business-api-conventions`: precisar el catálogo descriptivo en el code principal de HU04 y su compatibilidad con el envelope y contratos existentes.
- `company-data-integrity`: exigir forma persistente de aceptación HU04, procedencia de fingerprint, snapshot local e índice de paginación, conservando restricciones y datos anteriores.

## Impact

Afecta Domain/Application/API/Infrastructure de Site, puertos existentes, composición CDI, Flyway, OpenAPI, DBML, contratos de eventos y verificaciones de artefactos empaquetados/nativos. Reutiliza Quarkus reactivo, PostgreSQL 18, librería compartida, keyring y outbox actuales; no añade dependencias operacionales externas. HU03/HU05, reemplazo atómico de sede legal, direcciones, contactos y publicación RabbitMQ permanecen fuera de alcance. Este cambio crea planificación; su implementación requiere iniciar apply por separado.
