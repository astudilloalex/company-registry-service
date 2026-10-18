# Tasks

Tareas de implementación futura de HU05. Todas comienzan pendientes; crear esta planificación no constituye implementación ni evidencia de aceptación. La matriz AC01–AC15 está en design.md. Las pruebas de historias posteriores usarán escritores auxiliares compatibles fuera de producción, sustituidos o complementados por integración real si sus endpoints existen al aplicar.

## 1. Políticas y contratos neutrales Site

- [x] 1.1 Añadir intención parcial y política pura de información Site reutilizando BusinessText/FieldChange; verificar mediante pruebas de omisión, null, `{}`, enum exacto, límites 64/200, acentos/espacios y expansión Unicode que PATCH conserva siteType omitido y no cambia los defaults HU04.
- [x] 1.2 Añadir política de elegibilidad/transición y protección de sede legal sobre los modelos existentes; verificar con pruebas puras las dieciséis combinaciones origen/destino, comandos al estado actual, CLOSED/ARCHIVED, preparación bajo DRAFT/INACTIVE y restricciones de HEADQUARTERS ACTIVE.
- [x] 1.3 Definir comandos/resultados/fallos neutrales y las cuatro operaciones idempotentes en application/site, y documentar decisiones HU05 en ADR 002/text-normalization; verificar ArchitectureTest y trazabilidad de las reglas a las deltas sin introducir framework genérico o dependencias exteriores al núcleo.

## 2. Migración e integridad de completados HU05

- [x] 2.1 Añadir la siguiente migración libre, prevista V5__enforce_site_mutation_completion_metadata.sql, con diagnóstico previo y CHECK de las cuatro operaciones; verificar instalación PostgreSQL 18 vacía, validación repetida, seis tablas y checksums V1–V4 intactos.
- [x] 2.2 Añadir pruebas SQL de inserción/actualización inválidas de site_id, versión esperada, HTTP 200, fingerprintVersion/keyId, hash, JSON objeto, snapshotVersion y referencias compuestas; verificar rechazo por restricciones esperadas y preservación de namespaces HU01/HU02/HU04/técnicos.
- [x] 2.3 Probar upgrade desde V4 con datos, aceptaciones y eventos anteriores y un histórico HU05 incompatible; verificar preservación de identidades/snapshots, detención controlada sin procedencia inventada y privilegios DML/runtime separados de migración.
- [x] 2.4 Actualizar DBML, constraint-verification y persistence-contract para el nuevo CHECK; verificar SchemaCatalogIntegrityTest/SchemaDocumentationTest y conservar las restricciones existentes de código, HEADQUARTERS e inmutabilidad CLOSED.

## 3. Persistencia de mutación protegida

- [x] 3.1 Ampliar SiteRepository y su adaptador con bloqueo tenant/Company/Site y UPDATE condicionado por versión; verificar con PostgreSQL aislamiento, cero filas como STALE_VERSION, campos permitidos, una revisión exacta, identidad/auditoría de creación intactas y ausencia de doble incremento por @Version.
- [x] 3.2 Añadir diagnósticos calificados de código y HEADQUARTERS que excluyan el propio Site; verificar código propio, colisión canónica/CLOSED, renombrado A → B que libera A solo al commit y rollback que mantiene la reserva original, sin reutilizar companies.occupied.
- [x] 3.3 Integrar o reutilizar clasificación neutral de STALE_VERSION/SITE_CODE/SITE_HEADQUARTERS/COMPLETION_KEY y documentar Company → Site; verificar pruebas de traducción, restricciones desconocidas como fallo interno y regresión de los adaptadores Company/HU04.

## 4. Fingerprints y snapshots HU05

- [x] 4.1 Implementar política HMAC versión 1 y selección histórica sobre las cuatro operaciones y el keyring existente; verificar vectores deterministas de tenant/operación/Company/Site/If-Match, presencia PATCH, equivalencia canónica y ausencia de body frente a `{}` en comandos.
- [x] 4.2 Verificar exclusiones de actor/proceso/clave/estados/modo y separación de operaciones/tenants con pruebas de firma; comprobar conflicto al cambiar recurso, versión o presencia y conservar sin cambios los vectores HU01/HU02/HU04.
- [x] 4.3 Implementar codec HU05 independiente con estado Company original interno y representación pública de doce campos; verificar round-trip JSONB de cambios/no-op/transiciones, booleano histórico, microsegundos, propiedad, estados/revisiones por operación y corrupción, sin exigir que la auditoría Site de no-op coincida con el completado.
- [x] 4.4 Documentar formato versionado, retención/rotación y reversión en README/persistence-contract; verificar replay con K1 después de K2, rechazo saneado de política/clave/formato desconocidos y compatibilidad de los codecs anteriores mediante pruebas de regresión.

## 5. Productores y contratos de eventos

- [x] 5.1 Añadir candidatos neutrales y adaptadores outbox updated/closed usando OutboxPolicy; verificar los ocho campos de payload y once de envelope, versión resultante, UUIDv7 distinto, contexto original y causalidad null con pruebas del productor.
- [x] 5.2 Crear esquemas JSON cerrados y ejemplos de company.site.updated.v1/company.site.closed.v1; verificar Draft 2020-12 e invariantes cruzadas con eventos reales, estados admitidos, ausencia de datos de hijos/operatividad y un ejemplo compatible de revisión por cambio de hijo.
- [x] 5.3 Probar disabled/stored-only/published, selección exclusiva closed para cierre y cero eventos en no-op/replay; documentar routing keys y hechos originales en el handoff de HU08 y verificar rollback conjunto ante fallo de evento sin conexión RabbitMQ.

## 6. Casos de uso de aceptación y recuperación

- [x] 6.1 Implementar actualización Site con lookup inicial, Company → reconsulta de completado → Site y validaciones protegidas; verificar casos reactivos de PATCH efectivo/no-op, 404/412/estados, independencia Company y respuesta/auditoría coherentes con el snapshot.
- [x] 6.2 Implementar activate/deactivate/close con el mismo protocolo y las políticas de transición/sede legal; verificar cada transición permitida/prohibida, redundancia 409, padre ARCHIVED, cierre terminal y selección correcta de un evento.
- [x] 6.3 Integrar aceptación opcional atómica y recuperación en sesión nueva solo tras rollback/cierre confirmados; verificar fallos inyectados en cambio/completado/evento, aceptación durable antes de traducir unicidad/versión, no consumo de claves fallidas y máximo un efecto para solicitudes equivalentes.
- [x] 6.4 Añadir configuración/composición CDI de site-mutation.wait-budget (10s inicial, positivo/finito), presupuesto único y deadline siguiendo HU04; verificar adquisición/locks/commit/limpieza/cancelación/recovery acotados y 503 ante finalización incierta sin escritura independiente ni reejecución automática, y documentar la variable en README.
- [x] 6.5 Añadir pruebas de recuperación ante respuesta perdida/commit incierto con y sin clave; verificar snapshot original tras mutación/cierre/archivado, no-op durable, Process-Id actual y estados/operatividad históricos sin consultas a proveedores ni estado actual en replay.

## 7. Frontera HTTP, respuesta y documentación

- [x] 7.1 Añadir lector/DTO PATCH con presencia explícita y lector de comandos que consuma todo el documento; verificar estructura, duplicados/desconocidos, tipos, null/ausencia/`{}`, Unicode, tokens posteriores y precedencia de campo preservando strings para política histórica.
- [x] 7.2 Añadir parsing If-Match opcionalmente reutilizable sin cambiar rutas anteriores y catálogo/traductor HU05; verificar cada código de la delta API, status/code exactos, pertenencia antes de estados y versión antes de terminalidad mediante pruebas de frontera/HTTP.
- [x] 7.3 Exponer las cuatro rutas con contexto y ResponseManager, mapeando el resultado a SiteResponse; verificar HTTP 200 con doce campos, revisión/auditoría, header Process-Id, no-op, comandos redundantes, sede legal, aislamiento y ejecución con Party/Geography/broker inaccesibles sin llamadas.
- [x] 7.4 Ampliar OpenAPI y sus aserciones contractuales para las cuatro rutas, body PATCH/comandos, headers, respuestas y códigos; verificar ejemplos, 400/404/409/412/415/500/503, publicación empaquetada y regresión de rutas/schemas HU01/HU02/HU04.
- [x] 7.5 Crear docs/contracts/company-site-lifecycle-management.md y actualizar planned-operations/contrato HU04 con ejemplos comprobados por las pruebas HTTP; verificar que describen códigos liberados frente a CLOSED, namespaces idempotentes, body de comandos y operatividad de aceptación/replay/GET sin presentar HU03 como implementado.

## 8. Integración concurrente y aceptación local

- [x] 8.1 Añadir carreras HTTP con barreras observables y transacciones independientes para dos cambios efectivos contra la misma revisión y no-op contra cambio; verificar un solo incremento y 412 para el perdedor, y que el no-op aceptado primero no consume versión.
- [x] 8.2 Añadir carreras por clave equivalente/diferente dentro y entre Companies, y escenarios donde el competidor revierte; verificar un completado, máximo un evento/cambio, rollback del perdedor, conflicto específico y ausencia de snapshot ajeno.
- [x] 8.3 Añadir carreras de reclasificaciones a HEADQUARTERS y adquisición de código libre entre Sites, incluida creación HU04 contra PATCH; verificar máximo una reserva/código por Company y errores descriptivos sin efectos parciales, conservando código CLOSED.
- [x] 8.4 Añadir escritores de prueba compatibles para archivado/activación Company y competir en ambos órdenes contra PATCH, comandos y retiro de sede legal; verificar rechazo de toda mutación posterior a ARCHIVED y el invariante Company ACTIVE con exactamente una sede legal ACTIVE.
- [x] 8.5 Añadir escritores de prueba de dirección y contacto que respeten Company → Site y la revisión común; verificar carreras contra HU05 sin sobrescritura silenciosa ni revisiones dobles y mantener auxiliares fuera de producción. Documentar los límites de esta evidencia en el contrato de persistencia.

## 9. Artefactos, reinicio y evidencia global

- [x] 9.1 Registrar metadatos nativos HU05 en Infrastructure y ampliar verifyProductionIsolation/harness empaquetado; verificar que el artefacto JVM contiene OpenAPI y los dos schemas/ejemplos nuevos y carece de rutas/clases auxiliares productivas.
- [x] 9.2 Ampliar suites de proceso/reinicio conservando PostgreSQL y ejecutar regresión ./gradlew test, ./gradlew build, ./gradlew quarkusIntTest y verifyProductionIsolation; verificar replays de las cuatro operaciones/no-op después de cambios, rotación, modo outbox, nuevo proceso y regresiones HU01/HU02/HU04.
- [x] 9.3 Ampliar el harness nativo siguiendo native-verification.md y ejecutar binario e imagen Podman no-root; verificar migración incremental, DTOs/JSONB/fechas/booleanos/HMAC, errores, schemas/eventos reales, reinicio/replay y aislamiento productivo, registrando comandos/artefactos reales.
- [x] 9.4 Crear docs/operations/hu05-acceptance-review.md con matriz AC01–AC15 y escenarios adicionales de design.md; verificar cada afirmación contra resultados JVM/concurrentes/empaquetados/nativos ejecutados, identificar los escritores auxiliares usados y dejar explícita cualquier comprobación todavía pendiente.
