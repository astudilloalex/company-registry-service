# Proposal

## Why

HU03 ([issue #5](https://github.com/astudilloalex/company-registry-service/issues/5)) necesita habilitar, suspender y archivar Companies mediante decisiones explícitas, manteniendo identidad legal válida al activar y una matriz legal ACTIVE mientras Company esté ACTIVE. T00/HU01/HU04/HU05 ya proporcionan estados, referencias, sedes y protección transaccional; falta conectar estas bases en los comandos Company y demostrar sus carreras reales.

## What Changes

- Incorporar POST activate/deactivate/archive bajo `/v1/companies/{companyId}`, con contexto de negocio obligatorio, If-Match decimal de Company e Idempotency-Key opcional. Admitir body ausente o `{}` y devolver 200 successful con Company operacional completa, sin enriquecimiento Party.
- Aplicar DRAFT → ACTIVE/ARCHIVED, ACTIVE → INACTIVE e INACTIVE → ACTIVE/ARCHIVED. ARCHIVED es terminal. Un comando nuevo al estado actual devuelve 409 sin consumir clave; un replay equivalente conserva su 200 original, aunque Company haya avanzado o esté archivada.
- Exigir en activación Party del tenant, LEGAL_ENTITY y ACTIVE mediante el puerto mínimo existente, además de exactamente una HEADQUARTERS actual ACTIVE. Consultar Party fuera de sesiones/bloqueos de base de datos y revalidar propiedad, versión, transición y matriz bajo protección Company → Site antes de aceptar. Desactivación y archivado son locales.
- Persistir cada transición efectiva con un solo incremento Company y auditoría UTC/microsegundos, preservando identidad, información operacional, Sites y sus versiones/estados/auditoría. Mantener operatividad Site derivada y las restricciones HU02/HU04/HU05 frente a Company ARCHIVED.
- Añadir tres operaciones independientes de idempotencia, huellas HMAC versionadas y snapshots de ciclo de vida propios. Resolver aceptación antes de estado actual y después de esperar bloqueo; acotar validación remota, transacción, finalización y recuperación de resultado incierto, sin repetir escrituras automáticamente.
- Producir company.activated.v1 y company.deactivated.v1; completar el productor de archivado company.updated.v1, cuyo esquema ya admite ARCHIVED pero cuya fábrica actual lo rechaza. Aplicar almacenamiento atómico condicionado por modo, sin introducir company.archived.v1 ni publicar RabbitMQ.
- Añadir integridad de completados HU03 mediante V6, conservando seis tablas, migraciones V1–V5 y aceptaciones anteriores. Entregar contrato HTTP y de eventos, documentación y verificación real JVM/PostgreSQL/concurrente, empaquetada y nativa.

## Capabilities

### New Capabilities

- `company-lifecycle-management`: transiciones Company, elegibilidad de activación, coordinación Company/matriz, versión, auditoría y aceptación/replay durable de los tres comandos.
- `company-activated-event`: contrato versionado y persistencia condicional del hecho de activación o reactivación Company.
- `company-deactivated-event`: contrato versionado y persistencia condicional del hecho de desactivación Company.

### Modified Capabilities

- `business-api-conventions`: catálogo descriptivo, cuerpos vacíos, respuestas y precedencia HU03, preservando contratos HU01/HU02/HU04/HU05.
- `company-data-integrity`: metadatos, snapshots, retención y evolución incremental de aceptaciones HU03.
- `company-updated-event`: habilitar el productor real de archivado y su trazabilidad, atomicidad y evidencia contractual, manteniendo el payload V1 y las restricciones del productor HU02.

## Impact

Afecta políticas Domain, orquestación Application, recursos/errores API, puertos/repositorios y composición Infrastructure, Flyway/DBML, OpenAPI, esquemas/ejemplos de eventos y suites JVM/nativas. Reutiliza PartyReferences, keyring, presupuestos/transacciones y outbox existentes. La operatividad derivada y las protecciones Site ya especificadas no cambian de contrato; se verifican con los nuevos endpoints Company.

Quedan fuera sincronización posterior con Party, validación geográfica, requisitos nuevos de dirección/contacto para activar, creación automática o reemplazo atómico de matriz, cascadas Site, borrado físico, autenticación/Keycloak, despliegue productivo y publisher HU08. La autorización de este cambio cubre planificación; la implementación requiere iniciar apply por separado.
