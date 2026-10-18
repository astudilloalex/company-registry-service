# Tasks

Las tareas corresponden exclusivamente a HU04 y sus deltas. La matriz AC01–AC14 de design.md define la aceptación. Los nombres de tipos nuevos pueden ajustarse a las convenciones existentes sin alterar contratos públicos ni políticas históricas.

## 1. Políticas y contratos neutrales de Site

- [x] 1.1 Ampliar el dominio existente con política de registro, canonización code/name, límites Unicode, presencia/default de siteType y admisión bajo Company no archivada; verificar con pruebas puras de los cuatro tipos, null/omisión, blancos, límites y expansión Unicode.
- [x] 1.2 Definir comando/resultado local, fallos neutrales diferenciados y selección/frontera de página en Application, reutilizando Site y SiteRecord; verificar inicialización DRAFT/0, propiedad y matriz de operatividad con pruebas unitarias y ArchitectureTest.
- [x] 1.3 Documentar en las decisiones de arquitectura la admisión local, independencia de versión/auditoría y código reservado por CLOSED; verificar coherencia con ADR 002 y los escenarios AC01/AC07/AC14, sin introducir workflows HU03/HU05.

## 2. Integridad SQL y clasificación de persistencia

- [x] 2.1 Añadir V4__add_site_registration_support.sql con validación previa de históricos, CHECK de completados Site e índice tenant/Company/created_at/id; verificar instalación limpia en PostgreSQL 18, seis tablas y checksums V1–V3 intactos.
- [x] 2.2 Cubrir inserción y actualización inválidas de cada metadato HU04, hash, snapshot y FK compuesta; verificar rechazo SQL de siteId null/ajeno, procedencia inválida, expectedVersion no null y status distinto de 201, conservando restricciones HU01/HU02.
- [x] 2.3 Añadir prueba de upgrade desde V3 con Companies/Sites/completados/eventos previos y un histórico HU04 incompatible; verificar preservación de datos/replays anteriores y detención controlada sin inventar claves o reescribir snapshots.
- [x] 2.4 Ampliar PersistenceFailure/PersistenceFailures con causas Site de código y HEADQUARTERS; verificar traducción de las dos restricciones conocidas, COMPLETION_KEY y unicidades desconocidas, manteniendo estas últimas como inesperadas y los casos Company sin regresión.
- [x] 2.5 Actualizar DBML, inventario de restricciones y contrato de persistencia para V4; verificar SchemaCatalogIntegrityTest, SchemaDocumentationTest, pruebas existentes de sede legal concurrente y privilegios runtime DML/migrador separados.

## 3. Fingerprint y snapshot de aceptación

- [x] 3.1 Implementar firma Site versión 1 sobre el documento canónico definido y keyring existente; verificar vectores deterministas, BRANCH omitido/explícito equivalentes, texto canónico, Company diferente y exclusión de actor/proceso/clave/valores generados.
- [x] 3.2 Implementar codec Site versión 1 y validación de identidad/propiedad/tipos contra el completado; verificar round-trip JSONB real de los doce valores públicos, bool false, versión 0, auditoría microsegundos y rechazo de snapshot corrupto o incompatible.
- [x] 3.3 Integrar selección de política/keyId históricos y documentar retención/rotación sin TTL; verificar replay con K1 después de activar K2, fallos saneados por procedencia desconocida y regresión de fingerprints/codecs HU01/HU02 sin cambiar sus bytes o formatos.

## 4. Contrato y productor de company.site.created.v1

- [x] 4.1 Añadir candidato neutral y adaptador outbox Site que reutilice OutboxPolicy; verificar ocho campos de payload, once de envelope, UUIDv7 distinto, aggregateId=siteId, revisión 0, esquema 1, fechas/actor y causationId null con pruebas del productor.
- [x] 4.2 Crear esquema JSON cerrado y ejemplo en docs/contracts/events; verificar Draft 2020-12 sobre ejemplo y evento producido, incluyendo rechazo de effectivelyOperational, campos extra y discrepancias de invariantes cruzadas.
- [x] 4.3 Probar almacenamiento disabled/stored-only/published y actualizar el handoff de eventos para HU08; verificar cero/un evento PENDING, intentos/versión técnica cero, rollback del evento fallido y ausencia de publicación o conexión RabbitMQ.

## 5. Caso de uso de registro transaccional

- [x] 5.1 Implementar registro con búsqueda inicial de completado, bloqueo Company para todo tipo, segunda búsqueda y admisión protegida; verificar con pruebas reactivas DRAFT/ACTIVE/INACTIVE, padre ajeno/ausente, ARCHIVED, Site inicial y versión/estado/auditoría de Company intactos.
- [x] 5.2 Implementar replay 201 original y recuperación de colisiones después de rollback/cierre en sesión nueva; verificar mediante pruebas de recuperación que una aceptación equivalente precede a estado actual o unicidad, otra entrada conserva su código específico y no se usa companies.occupied para Site.
- [x] 5.3 Integrar presupuesto único positivo de registro Site, checkpoints, configuración y composición CDI; verificar configuración inválida, espera agotada, cancelación, deadlock/serialización e incertidumbre de commit sin reejecución ciega ni escritura suscrita por separado.
- [x] 5.4 Verificar atomicidad real Site/completado/outbox con fallos inyectados en cada escritura y finalización; comprobar cero residuos antes de commit, clave reutilizable después de rollback y resultado durable recuperable cuando el commit confirmó aunque se perdió su respuesta.
- [x] 5.5 Documentar bloqueo Company antes de Site, presupuesto/variable y protocolo de recuperación/reversión en el contrato de persistencia y README; verificar que los ejemplos corresponden a la configuración real y que HU03/HU05 permanecen fuera de producción.

## 6. Consultas locales, filtros y cursores

- [x] 6.1 Ampliar SiteRepository y su adaptador con detalle/listado calificados y estado Company obtenido junto a Site; verificar PostgreSQL tenant+Company+Site, padre inexistente frente a colección vacía y operatividad desde el estado del mismo resultado local.
- [x] 6.2 Implementar filtros AND y consulta limit+1 con frontera completa y nuevo índice; verificar código canónico, tipo/estado exactos, CLOSED por defecto, Company ARCHIVED, límites 1/50/200, fechas iguales y recorrido estable sin duplicados ni omisiones.
- [x] 6.3 Implementar cursor Site autenticado con dominio/versionado y Company explícita; verificar alteración, longitud/formato/clave desconocidos, tenant/Company/filtros ajenos, intercambio Company/Site, código equivalente y cambios de actor/proceso/limit admitidos.
- [x] 6.4 Componer consultas y página local en Application y documentar su contrato de lectura; verificar con pruebas del caso de uso los doce campos, historial, status ACTIVE con operatividad false, nextCursor/numberOfElements y ausencia de llamadas a proveedores o conteos globales.

## 7. API, códigos descriptivos y OpenAPI

- [x] 7.1 Añadir DTO/lector cerrado de creación y DTO/mapping Site; verificar parsing completo, omisión frente a null, duplicados, desconocidos, tipos, raíces/tokens, Unicode y precedencia code/name/siteType mediante pruebas de frontera que preserven entrada histórica original.
- [x] 7.2 Añadir catálogo y traductor HU04 mediante ApiResponseException existente, incluyendo validación/path/key/query/cursor; verificar cada code de la delta business-api-conventions y shape status/code, sin convertir motivos en resource-conflict, bad-request o errors[] genéricos ni alterar HU01/HU02.
- [x] 7.3 Exponer los tres endpoints y probarlos por HTTP con PostgreSQL; verificar contexto, correlación, POST sin If-Match, GET sin clave, 201/200, 404 específicos, 400/409 descriptivos, historial, filtros, errores de cursor y proveedores inaccesibles sin llamadas HU04.
- [x] 7.4 Añadir carreras HTTP de registros bajo la misma Company con peticiones/transacciones independientes y barreras observables; verificar misma clave equivalente/diferente, claves distintas con código canónico igual y HEADQUARTERS distintos, máximo una aceptación/evento y causa pública de los perdedores.
- [x] 7.5 Añadir carreras entre Companies por la misma clave, ganador que revierte y registro frente a archivado fixture en ambos órdenes; verificar rollback del Site perdedor, ausencia de exposición de snapshots ajenos y serialización del padre para BRANCH/WAREHOUSE/OTHER también.
- [x] 7.6 Ampliar OpenAPI, ejemplos y aserciones contractuales con tres operaciones, request cerrado, doce campos Site, errores propios y metadata de avance; verificar parseo/ejemplos, null siteType rechazado, publicación empaquetada y conservación de contratos HU01/HU02.
- [x] 7.7 Completar docs/contracts/company-site-registration-and-retrieval.md y planned-operations con ejemplos de POST/replay/GET/listado y catálogo exacto de errores; verificar cada ejemplo contra las pruebas HTTP/OpenAPI, incluyendo código CLOSED reservado y reemplazo legal con código libre.

## 8. Integración de artefactos y aceptación

- [ ] 8.1 Registrar metadatos de serialización/reflexión en Infrastructure y ampliar verifyProductionIsolation para schema/ejemplo Site; verificar artefacto JVM con contrato OpenAPI/eventos presentes y sin rutas, clases o datos auxiliares de prueba.
- [x] 8.2 Ampliar pruebas de proceso empaquetado y reinicio conservando PostgreSQL; verificar aceptación durable después de mutaciones/cierre/archivado fixtures, cambio de actor/proceso/modo y rotación de keyring, además de consultas locales sin proveedores.
- [ ] 8.3 Extender el harness/evidencia nativa y ejecutar binario nativo e imagen Podman no-root; verificar migración V4, DTOs/JSONB/fechas/booleanos, errores públicos, HMAC/cursor, evento real, reinicio/replay y ausencia de fixtures en producción, usando la configuración nativa documentada.
- [ ] 8.4 Ejecutar regresión completa ./gradlew test, ./gradlew build, ./gradlew quarkusIntTest y verifyProductionIsolation; verificar arquitectura, compilación, integridad/HTTP, contratos HU01/HU02 y diferencias de artefactos sin nuevos fallos o advertencias de compilación.
- [ ] 8.5 Crear docs/operations/hu04-acceptance-review.md con evidencia ejecutada AC01–AC14 y escenarios adicionales acordados; verificar trazabilidad a resultados reales, comandos y artefactos, y dejar pendiente cualquier comprobación que no haya pasado en lugar de afirmar aceptación solo por build JVM.
