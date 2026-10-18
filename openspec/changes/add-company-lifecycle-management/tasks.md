# Tasks

## 1. Políticas y contratos neutrales Company

- [ ] 1.1 Añadir política pura de ciclo de vida reutilizando CompanyStatus; verificar la matriz completa de cuatro estados por tres operaciones, ARCHIVED terminal y redundancia 409 con pruebas Domain que distingan cada motivo.
- [ ] 1.2 Añadir la decisión pura de elegibilidad de activación con hechos mínimos Party/matriz; verificar ausencia, NATURAL_PERSON, todos los estados Party y matriz, historial CLOSED y matriz válida sin dirección/contacto; comprobar que RegistrationPolicy conserva sus reglas HU01.
- [ ] 1.3 Definir comando ACTIVATE/DEACTIVATE/ARCHIVE, tres namespaces, resultado CompanyRecord y fallos neutrales Application; verificar invariantes de construcción y reglas de dependencia con ArchitectureTest, sin tipos API/HTTP/ORM/CDI en Application/Domain.
- [ ] 1.4 Actualizar ADR 002 y el handoff arquitectónico con la regla confirmada de comando nuevo 409 frente a replay 200 y la independencia Company/Site; verificar correspondencia entre matriz documentada, políticas y specs HU03.

## 2. Integridad y persistencia de transiciones

- [ ] 2.1 Añadir V6 con diagnóstico previo de históricos incompatibles y CHECK de metadatos de company.activate.v1/company.deactivate.v1/company.archive.v1; verificar instalación limpia, upgrade V5 con datos anteriores, validación repetida y detención 23514 sin borrar ni reinterpretar históricos.
- [ ] 2.2 Añadir pruebas PostgreSQL de INSERT y UPDATE de completados HU03 para site_id null, expectedVersion, resultado 200, fingerprint/keyId, hash, snapshot, PK y FK tenant/Company; verificar SQLSTATE y que las restricciones HU01/HU02/HU04/HU05 permanecen intactas.
- [ ] 2.3 Extender CompanyRepository y su adaptador con transición condicional por tenant/id/versión/estado fuente que escriba status, auditoría y un único incremento; verificar desde sesión nueva el estado realmente persistido, stale version, aislamiento, overflow sin efectos y preservación de información/identidad/Sites, manteniendo update HU02 sin cambio de contrato.
- [ ] 2.4 Añadir resolución y bloqueo de HEADQUARTERS no CLOSED calificada por tenant/Company después de Company lock; verificar ausencia, matriz DRAFT/INACTIVE/ACTIVE, historial CLOSED, ownership y reserva única con PostgreSQL real, sin mutar Site por bloquearlo.
- [ ] 2.5 Actualizar DBML, inventario de restricciones y persistence-contract.md para V6 y la escritura de transición; verificar seis tablas, checksums V1–V5 conservados, índice legal existente y roles runtime DML/migrador DDL mediante pruebas de catálogo y migración.

## 3. Huellas y resultados durables HU03

- [ ] 3.1 Implementar política HMAC HU03 versión 1 y selección de clave histórica para los tres namespaces con los bytes ordenados definidos en design.md; verificar vectores independientes, separación tenant/operación/Company/If-Match y exclusión de actor/proceso/observaciones Party-matriz/modo outbox.
- [ ] 3.2 Implementar codec HU03 propio para CompanyRecord con tipos estrictos, nulls explícitos, propiedad, destino por operación y revisión expectedVersion+1; verificar round-trip JSONB de ACTIVE/INACTIVE/ARCHIVED, identidad/auditoría coherentes con completado y rechazo saneado de corrupción, coerción, overflow o formatos desconocidos.
- [ ] 3.3 Añadir metadata nativa y verificación de recursos/tipos necesarios para los resultados HU03; verificar serialización JVM y regresión de fingerprints/codecs HU01/HU02/HU04/HU05, conservando sus bytes, namespaces y restricción HU02 de estados editables.
- [ ] 3.4 Documentar scopes, equivalencia de body vacío, retención sin TTL y rotación K1→K2 en README/persistence-contract.md; verificar replay con K1 histórico, error saneado por material ausente y ausencia de secretos nuevos o valores sensibles en snapshots/diagnósticos.

## 4. Productores y contratos de eventos

- [ ] 4.1 Implementar hechos/productores company.activated.v1 y company.deactivated.v1 con envelope de once campos y payload de siete campos; verificar estados ACTIVE/INACTIVE, UUIDv7 distinto, identidad/versión/auditoría/contexto, null causal y rechazo de campos o hechos incompatibles con pruebas de contrato.
- [ ] 4.2 Añadir fábrica específica de archivado para CompanyUpdated manteniendo candidate HU02 limitado a estados editables; verificar ARCHIVED bajo el esquema V1 existente y regresión de PATCH, sin company.archived.v1 ni cambios del payload publicado.
- [ ] 4.3 Integrar almacenamiento mediante OutboxPolicy y probar en PostgreSQL selección de un solo evento por operación, tres modos, clave opcional y rollback por fallo de inserción; verificar PENDING, intentos/versión técnica cero y ausencia de eventos Site o publicación/broker requeridos.
- [ ] 4.4 Entregar schemas/examples activated/deactivated y ejemplo company.updated.v1.archived.example.json, actualizar handoff HU08 y comprobaciones de empaquetado; verificar esquemas Draft 2020-12, igualdades entre campos, recursos META-INF/contracts/events y rutas exchange/routing key documentadas.

## 5. Orquestación reactiva y plazo total

- [ ] 5.1 Añadir company-registry.lifecycle.wait-budget / COMPANY_LIFECYCLE_WAIT_BUDGET con default 10s y deadline monotónico compartido; verificar configuración inválida y que lookup, HTTP, finalización y recuperación consumen el mismo plazo, sin cambiar DatabaseBudget de capacidades anteriores; documentar propiedad y límites en README.
- [ ] 5.2 Implementar lookup inicial y preparación protegida de activación con recheck de completado, Company → matriz y cero escrituras; verificar replay/conflicto antes de estado, 404/412/409 locales antes de HTTP y cierre de sesión/transacción antes de llamar a Party.
- [ ] 5.3 Integrar PartyReferences fuera de sesiones/locks, conservando resultado o fallo esperado hasta aceptación final; verificar mediante stub fiel headers, LEGAL_ENTITY ACTIVE, ausencia, incompatibilidades, 502/503, timeout/cancelación acotados y cero llamadas a identificadores/geografía.
- [ ] 5.4 Implementar aceptación final con recheck de clave, revisión/estado y nueva resolución protegida de matriz, seguida de resultado Party y escritura atómica; verificar activación/reactivación, desactivación y archivado locales, un único instante/actor/revisión y preservación de Sites/hijos con pruebas del caso de uso contra PostgreSQL.
- [ ] 5.5 Implementar recuperación de colisión/commit incierto solo después de finalizar/cerrar la transacción fallida y con plazo restante; verificar fallos inyectados en agregado/completado/evento, competidor que revierte, pérdida de respuesta, ausencia de recuperación si finalización es incierta y ninguna reejecución automática con/sin clave.
- [ ] 5.6 Conectar composición CDI y actualizar persistence-contract.md con fases, precedencias y cancelación; verificar arranque sin llamadas remotas/broker y trazas de prueba que demuestren ausencia de suscripciones de escritura abandonadas y de transacciones concurrentes sobre una sesión.

## 6. API y contrato HTTP HU03

- [ ] 6.1 Añadir los tres POST Company con lector vacío, If-Match de Company, clave opcional, CompanyOperationalResponse y ResponseManager; verificar por HTTP body ausente/{} equivalentes, 200 successful, omisión de opcionales nulos, Process-Id actual y ausencia de Party enriquecida/Site/tenantId en data.
- [ ] 6.2 Incorporar catálogo/traducción HU03 descritos en business-api-conventions; verificar cuerpos exactos status/code para propiedad, versión, terminalidad, redundancia, transición, matriz, Party y conflicto idempotente, conservando los contratos HU01/HU02/HU04/HU05 con pruebas de regresión.
- [ ] 6.3 Añadir pruebas de frontera para contexto/ruta/If-Match/clave inválidos, límites y OWS, JSON inválido/tokens, null/array/escalar/propiedades y media type; verificar rechazo antes de replay, código principal descriptivo, 415 con envelope y errores inesperados saneados.
- [ ] 6.4 Probar el ciclo HTTP completo y persistencia: preparación de matriz, activar, desactivar, reactivar y archivar; verificar matriz activa requerida, independencia Site, GET de operatividad actual, replay histórico HU04/HU05 y bloqueo posterior de HU02/HU03/HU04/HU05 incluyendo no-op, con registros/hijos/identidades preservados.
- [ ] 6.5 Actualizar OpenAPI fuente, docs/contracts/company-lifecycle-management.md, planned-operations.md y referencias README que aún declaren HU03 pendiente; verificar las tres rutas/headers/respuestas/errores con parser y pruebas OpenAPI, ResourceSignatureTest, contrato empaquetado y ausencia de rutas de verificación en producción.

## 7. Carreras reales entre capacidades

- [ ] 7.1 Probar activate HTTP real frente a deactivate/close/reclasificación de la matriz en ambos órdenes, con barreras y conexiones independientes; verificar como máximo una decisión incompatible y que toda Company que queda ACTIVE tiene exactamente una HEADQUARTERS actual ACTIVE, sin basarse solo en companyWriter de HU05.
- [ ] 7.2 Probar archive HTTP real frente a registro HU04 y PATCH/no-op/activate/deactivate/close HU05 para tipos Site aplicables, en ambos órdenes; verificar admisión antes del archivado o rechazo posterior con el catálogo original, sin incrementar Company por el escritor Site.
- [ ] 7.3 Probar dos transiciones y transición frente a PATCH HU02 efectivo/no-op con igual versión; verificar el único incremento efectivo, 412 del perdedor cuando corresponda y que un no-op previo conserva la revisión, usando lecturas SQL desde otra sesión.
- [ ] 7.4 Retener la respuesta Party con barrera y ejecutar escritores Company y Site reales; verificar que completan sin esperar el HTTP, que activate relee matriz aunque Company.version no cambie y que replay de un competidor equivalente prevalece sobre fallo remoto/versión/estado posteriores.
- [ ] 7.5 Probar misma clave equivalente/distinta, cambio de If-Match, colisión de clave entre Companies y fallo/rollback del competidor; verificar una aceptación/evento como máximo, reversión del perdedor, no exposición de snapshot ajeno y documentar resultados de AC11/AC12/AC14 en el registro de aceptación HU03.

## 8. Verificación del sistema y artefactos de entrega

- [ ] 8.1 Ejecutar regresión JVM/PostgreSQL completa, build, contratos e aislamiento productivo mediante tareas Gradle vigentes; verificar informes sin fallos de HU01/HU02/HU04/HU05/HU03 y registrar comandos/resultados aislados para evitar colisiones de informes.
- [ ] 8.2 Añadir y ejecutar casos del proceso JVM empaquetado para las tres transiciones, validación Party mínima, errores, eventos reales, reinicio/K1→K2 y replay tras otro ciclo/archivado/cambio de modo; verificar respuestas, filas y contratos desde el proceso sin acceso a fuentes ni fixture CDI de unit tests.
- [ ] 8.3 Construir y ejecutar binario nativo con el procedimiento vigente de docs/operations/native-verification.md; verificar upgrade V5→V6 con históricos preservados, HTTP/Party, snapshots con nulls, eventos, reinicio/rotación y health/OpenAPI, registrando evidencia ejecutada sin equipararla a carreras exhaustivas JVM.
- [ ] 8.4 Ejecutar harness OCI no root en los tres modos con base aislada y configuración externa; verificar UID, readiness, transiciones/replays, eventos/recurso OpenAPI empaquetados y funcionamiento local de deactivate/archive/replay con proveedores caídos, sin despliegue productivo.
- [ ] 8.5 Completar docs/operations/hu03-acceptance-review.md y actualizar native-verification.md con AC01–AC14, comandos, resultados, límites de cobertura y rollback compatible; verificar que cada criterio tiene evidencia real, que no quedan gates requeridos pendientes y que openspec validate add-company-lifecycle-management --strict finaliza sin errores antes de solicitar archivo del cambio.
