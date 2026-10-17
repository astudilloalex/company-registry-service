# Tasks

## 1. Política operacional y presencia de campos

- [x] 1.1 Añadir un modelo puro que distinga campo ausente, null y valor presente para los cuatro campos editables; verificar con pruebas Domain que aplicar un patch conserva los omitidos y limpia únicamente opcionales.
- [x] 1.2 Implementar canonización y validación de información operacional usando BusinessText y la regla URL HU01, preservando las reglas del registro si se extrae una utilidad; verificar límites posteriores a uppercase, Unicode, blancos, nulabilidad, URLs exactas y regresión RegistrationPolicyTest/CreationFingerprintV1Test.
- [x] 1.3 Implementar la decisión de cambio efectivo/no-op y estado editable sin depender de RegistryData o frameworks; verificar DRAFT/ACTIVE/INACTIVE, rechazo ARCHIVED, `{}`, null redundante e igualdad canónica con pruebas puras y ArchitectureTest.
- [x] 1.4 Documentar en docs/architecture/text-normalization.md la aplicación de reglas a campos presentes y sus límites; verificar que ejemplos y exclusiones coincidan con las pruebas de la política y no cambien la canonización HU01.

## 2. Evolución del esquema V3

- [x] 2.1 Crear V3__enforce_company_update_completion_metadata.sql con las restricciones de company.update.v1 y diagnóstico previo de filas incompatibles; verificar instalación limpia y upgrade desde V2 con Companies/completados/eventos HU01, conservando datos y checksums V1/V2.
- [x] 2.2 Añadir pruebas PostgreSQL de procedencia/expectedVersion requeridos, HTTP 200, siteId null, hash/snapshot/versiones válidos y referencias tenant; verificar rechazo de cada variante inválida y aceptación tanto de cambio efectivo como no-op sin alterar namespaces previos.
- [x] 2.3 Actualizar inventario de restricciones, pruebas de catálogo/documentación y contrato persistente/DBML afectados por V3; verificar seis tablas, separación runtime/migrador y correspondencia entre restricciones declaradas, instaladas y probadas.

## 3. Fingerprint y snapshot de actualización

- [x] 3.1 Definir puertos/codec de fingerprint HU02 con operación company.update.v1, política 1 y keyring existente; verificar bytes canónicos, HMAC, orden JSON y equivalencia de texto sin modificar las firmas ni resultados históricos de creación.
- [x] 3.2 Verificar diferencias de intención en pruebas del fingerprint: resourceId, If-Match original, presencia frente a null, valor normalizado y URL exacta; comprobar que actor/proceso no afectan la huella y que `{}` difiere de una limpieza explícita aunque ambos sean no-op.
- [x] 3.3 Implementar codec de resultado operacional versión 1 con validación de forma/propiedad y conservación de nulos/tipos; verificar round-trip JSONB real, fechas/actores/versiones y rechazo saneado de snapshot, política, procedencia o clave histórica corruptos/desconocidos.
- [x] 3.4 Añadir pruebas de rotación y regresión que usen la política/keyId histórica al comparar reintentos, incluidos perdedores de carrera; actualizar la documentación de claves/snapshots para HU02 y verificar que los replays enriquecidos HU01 siguen iguales.

## 4. Persistencia de modificación protegida

- [x] 4.1 Ampliar CompanyRepository con lectura protegida tenant/ID y escritura con versión esperada explícita; implementar los adaptadores reactivos y verificar con PostgreSQL que recursos ausentes/ajenos no se modifican y las firmas respetan ArchitectureTest.
- [x] 4.2 Implementar el UPDATE único con predicado tenant/ID/version y campos permitidos, evitando doble flush/incremento por @Version; verificar incremento exacto, auditoría de creación intacta, precisión temporal, preservación de status e independencia de Site.
- [x] 4.3 Añadir pruebas directas con transacciones independientes de protección de fila, predicado obsoleto y no-op sin UPDATE; verificar que el bloqueo protege el punto de aceptación y que la unicidad de código arbitra carreras sin tratar la Party propia como conflicto.
- [x] 4.4 Completar la clasificación de fallos necesarios para HU02, incluidos deadlock/serialización transitorios y unicidades conocidas; verificar 412 neutral, indisponibilidad recuperable, constraint de código/clave y unicidad desconocida saneada, conservando significado de cancelación/fallos de negocio.
- [x] 4.5 Actualizar docs/architecture/persistence-contract.md con las operaciones de modificación, timeouts, límites de sesión y único mecanismo de incremento; verificar sus afirmaciones con las pruebas de repositorio, fallo y rollback.

## 5. Productor company.updated.v1

- [x] 5.1 Implementar candidato/mapper del evento desde la Company resultante con envelope de once campos y payload cerrado de siete; verificar UUIDv7 distinto, nueva versión, actor/fechas/IDs coherentes, causationId null y rechazo de campos/datos ajenos al contrato.
- [x] 5.2 Entregar company.updated.v1.schema.json y company.updated.v1.example.json en docs/contracts/events; verificar ejemplo y evento producido con Draft 2020-12, estados DRAFT/ACTIVE/INACTIVE/ARCHIVED y pruebas negativas de tipos/propiedades/invariantes cruzadas.
- [x] 5.3 Integrar almacenamiento con OutboxPolicy para disabled/stored-only/published; verificar con PostgreSQL PENDING, contadores cero, auditoría y rollback del evento junto con modificación/completado, también en peticiones sin clave y con broker inaccesible.
- [x] 5.4 Documentar el handoff HU03/HU08 en docs/contracts/events/README.md, exchange/routing key y preservación del snapshot; verificar evento inalterado después de cambios posteriores y regresión del envelope de creación si se extraen utilidades compartidas.

## 6. Orquestación y replay durable opcional

- [x] 6.1 Implementar el caso de uso Application con prioridad del completado, bloqueo Company y segunda lectura del completado antes de evaluar existencia/versión/estado; verificar escenarios de replay, 404, 412, ARCHIVED 409 y política de entrada mediante pruebas de orquestación.
- [x] 6.2 Coordinar cambio efectivo, auditoría, completado opcional y evento habilitado con AtomicOperation; verificar aceptación única y rollback real ante fallos obligatorios en cada escritura, sin consumir claves ni dejar datos o eventos parciales.
- [x] 6.3 Implementar no-op con/sin clave, guardando snapshot solo cuando corresponda; verificar `{}`, equivalencia canónica y limpieza redundante con auditoría/versión intactas, sin UPDATE Company ni evento, y replay original después de otra modificación/archivado.
- [x] 6.4 Implementar recuperación durable después de colisiones o commit incierto con clave, fuera de la sesión fallida; verificar replay del ganador, 409 por otra intención, 503 no resuelto y ausencia de inferencias basadas en occupied/Party propia o en el estado actual coincidente.
- [x] 6.5 Integrar DatabaseBudget y composición/configuración company-registry.update.wait-budget con COMPANY_UPDATE_WAIT_BUDGET y default 10s; verificar valor positivo/finito, presupuesto compartido entre fases, timeouts de servidor, rollback/cierre antes de recuperar y ausencia de escritura abandonada o reintento ciego sin clave.
- [x] 6.6 Documentar el contrato de actualización/replay y presupuesto local en docs/contracts/company-information-management.md y README.md; verificar que incluye no-op durable, rotación, prioridad del replay, incertidumbre sin clave y la distinción entre auditoría Company y auditoría del completado.

## 7. Frontera PATCH y OpenAPI

- [x] 7.1 Implementar DTO/lector PATCH con presencia explícita y reglas cerradas locales al nuevo body; verificar null/ausencia/duplicados, tipos sin coerción, propiedades desconocidas y todos los campos inmutables/sistema, con errores 400 sin efectos ni regresiones POST.
- [x] 7.2 Añadir PATCH a CompanyResource y mapear el resultado operacional a un DTO API propio con ResponseManager; verificar 200 successful, versión/auditoría y omisión de Party/opcionales nulos, además de las firmas públicas esperadas por ResourceSignatureTest.
- [x] 7.3 Validar headers/path y traducir fallos neutrales conservando Process-Id; verificar headers faltantes/repetidos, límites int64 sin comillas, clave opcional válida/inválida, UUID ajeno, estados editables/ARCHIVED y códigos 400/404/409/412/415/500/503 sin exponer detalles internos.
- [x] 7.4 Ampliar OpenAPI con request/respuesta locales, ejemplos de modificación/limpieza/no-op/replay y parámetro idempotente opcional propio; verificar mediante tests de contrato que POST mantiene clave obligatoria, GET sigue enriquecido e If-Match conserva la convención decimal.
- [x] 7.5 Actualizar docs/contracts/planned-operations.md y el contrato HU02 con endpoint, precedencia de errores y ejemplos ejecutables; verificar REST con Party/Geography inaccesibles y contadores de proveedor en cero para cambio, no-op y replay.

## 8. Concurrencia y recuperación integradas

- [x] 8.1 Añadir pruebas REST/PostgreSQL con conexiones independientes y barreras observables de dos cambios efectivos sobre la misma versión; verificar un solo éxito, un 412, incremento único y coherencia de Company/completados/eventos, incluyendo carreras no-op frente a cambio efectivo.
- [x] 8.2 Probar reintentos concurrentes de la misma clave sobre la misma Company y sobre Companies distintas; verificar una sola aceptación, replay idéntico o 409 según intención y rollback de todos los efectos del perdedor antes de recuperación en sesión nueva.
- [x] 8.3 Probar colisión canónica de código y dos Companies que compiten por código libre, incluyendo código ocupado por ARCHIVED y reutilización entre tenants; verificar 409 pertinente, ausencia de cambios parciales y ausencia de falsos conflictos por código/Party propios.
- [x] 8.4 Probar replay de cambio y no-op tras actualizaciones posteriores/archivado con fixtures, cambio de actor/proceso, rotación y cambio de modo outbox; verificar snapshots/versión originales y cero nuevas escrituras o eventos, mientras Company actual conserva su revisión posterior.
- [x] 8.5 Probar timeout de lock, abortos transitorios, respuesta perdida y resultado de commit incierto con/sin clave; verificar 503 o recuperación durable según corresponda, liberación efectiva de locks y posibilidad de reintento después de rollback real.

## 9. Verificación de artefacto y aceptación integrada

- [x] 9.1 Extender las aserciones de verifyProductionIsolation y pruebas de recursos empaquetados al esquema/ejemplo de actualización; ejecutar las suites JVM completas y comprobar OpenAPI/rutas HU02 presentes, contratos HU01 conservados y fixtures ausentes de producción.
- [x] 9.2 Extender PackagedCompanyIT/PackagedRestartIT o suites productivas equivalentes para PATCH local, limpieza/no-op, replay durable tras reinicio y los modos outbox; ejecutar contra el artefacto JVM real sin depender de clases CDI exclusivas de unit tests y registrar resultados.
- [x] 9.3 Construir y verificar el binario nativo y su imagen no-root usando el procedimiento vigente, con PostgreSQL 18 y proveedores/broker inaccesibles para PATCH; comprobar DTOs/presencia, JSONB/MAC, replay tras reinicio, contrato de evento empaquetado y operaciones health/OpenAPI independientes.
- [x] 9.4 Entregar docs/operations/hu02-acceptance-review.md con trazabilidad AC01-AC12 y casos límite a pruebas/evidencia JVM, concurrencia y nativo/imagen; verificar comandos reproducibles, resultados observados y ninguna afirmación de publicación RabbitMQ o workflows HU03 completados.
- [x] 9.5 Cubrir PATCH seguido de GET después de limpiar description, comprobando omisión del opcional, información operacional actual y Party solo en GET.
- [x] 9.6 Cubrir reutilización de la misma Idempotency-Key entre creación y actualización y entre tenants, comprobando aceptaciones y replays independientes sin nuevas escrituras.
