# Tasks

## 1. Políticas puras de contacto e intención

- [x] 1.1 Implementar EMAIL V1 en la política especializada: ASCII antes de minúsculas, dot-atom-text, dominio, límites 254/64 y alias conservados; verificar pruebas puras con mayúsculas equivalentes, Unicode, puntos/componentes inválidos, límites de etiquetas y fronteras 254/255 y 64/65.
- [x] 1.2 Completar normalización telefónica flexible y clasificación MOBILE sin actualizar libphonenumber 9.0.41; verificar E.164, MOBILE/FIXED_LINE_OR_MOBILE, rechazo de fijo exclusivo, formatos nacionales/extensiones/letras y misma cifra PHONE/MOBILE/WHATSAPP bajo canales compatibles.
- [x] 1.3 Crear ContactInformation/ContactInformationPatch/ContactInformationPolicy con label canónico de 80 puntos de código, defaults POST y presencia PATCH; verificar requerido/null/blanco, expansión a mayúsculas, reclasificación compatible/incompatible, equivalencia y no-op en pruebas puras.
- [x] 1.4 Crear ContactPrimaryPolicy para creación, selección y reclasificación; verificar cuatro categorías independientes, cero permitido, sustitución de destino, isPrimary omitido/false y ausencia de reemplazo automático en pruebas puras.
- [x] 1.5 Implementar ContactIntentPolicyV1 sin I/O: familia EMAIL/PHONE de value presente, tipo/label canónicos y presencia independiente; verificar PATCH sin contactType, equivalencia de formatos y distinción entre intención omitida y explícita sin consultar un contacto almacenado.
- [x] 1.6 Actualizar docs/architecture/text-normalization.md con el contrato aprobado y su cambio respecto de T00/HU07 AC07; verificar ejemplos contra las pruebas anteriores y ejecutar ArchitectureTest para conservar Domain libre de HTTP, JSON, persistencia, CDI y Mutiny.

## 2. Evolución SQL y repositorio calificado

- [x] 2.1 Añadir V8__add_site_contact_management_support.sql con preflight EMAIL vigente, detección de colisiones por minúsculas y CHECK del perfil canónico; verificar casos Java/SQL compartidos, bloqueo sin reescritura, preservación de eliminados históricos y unicidad vigente mediante ContactIntegrityTest ampliado.
- [x] 2.2 Incorporar a V8 metadatos obligatorios de los tres namespaces HU07 y el índice parcial de página; verificar SQL válido/inválido por operación, HTTP 201/200, no-op, propiedad compuesta y catálogo válido/listo con orden/predicado completos.
- [x] 2.3 Añadir pruebas de instalación limpia y upgrade V7→V8 con datasets compatibles, emails incompatibles y aceptaciones sin procedencia; verificar seis tablas, snapshots/eventos previos intactos, checksums V1–V7, privilegios runtime/migrador y Flyway.validate.
- [x] 2.4 Extender SiteRepository/ReactiveSiteRepository con consultas calificadas de contacto vigente, primario y duplicado excluyendo el objetivo; verificar aislamiento tenant/Company/Site, exclusión de eliminados y misma cifra en distintos tipos con PostgreSQL real.
- [x] 2.5 Incorporar updateContact calificado preservando identidad y creación, y usar el mapeo ContactEntity existente; verificar auditoría, desmarcado antes de promoción, baja lógica y rechazo de reasignación de Site mediante pruebas de repositorio.
- [x] 2.6 Clasificar uq_company_site_contacts_current_value como CONTACT_VALUE y conservar COMPLETION_KEY; verificar traducción de duplicado, rollback y error saneado ante violación inesperada de primaria en PersistenceFailuresTest y pruebas de aceptación SQL.
- [x] 2.7 Actualizar docs/database/v1-scheme.dbml, docs/database/constraint-verification.md y fixtures vigentes afectados por emails mixtos; verificar correspondencia con catálogo V8 y conservar sin reinterpretación fixtures de esquemas anteriores, snapshots opacos y actores con mayúsculas.

## 3. Contratos de aceptación, fingerprints y snapshots

- [x] 3.1 Definir ContactScope/ContactMutation con comandos y resultados View/Deletion neutrales y puertos ContactFingerprints/ContactResults; verificar namespaces, clave obligatoria/opcional, propiedad, revisión y validez de resultados mediante pruebas de contrato de Application.
- [x] 3.2 Implementar ContactFingerprintV1 y ConfiguredContactFingerprints con HmacKeys, política seleccionada y defaults/presencia canónicos; verificar bytes/hash dorados, equivalente EMAIL/teléfono, PATCH sin tipo, cambio de recurso/If-Match y exclusión de actor/proceso/clave idempotente.
- [x] 3.3 Implementar ContactResultCodec formato 1 con propiedad interna, nulabilidad y comprobante mínimo; verificar round-trip JSONB real, UUID histórico en UPDATE/DELETE, auditoría no-op/efectiva, revisión sin desbordamiento, corrupción/tipos/formato desconocidos y ausencia de consultas a filas actuales.
- [x] 3.4 Ampliar docs/architecture/persistence-contract.md con namespaces, política/biblioteca/metadatos 1, formato 1, retención y rollback; verificar pruebas de rotación K1→K2, clave/política faltantes y preservación de los formatos HU01–HU06.

## 4. Orquestación local y recuperación

- [x] 4.1 Implementar MutateContact con replay inicial, Company→Site, relectura de aceptación, ausencia/revisión/estados, validación efectiva y duplicado previo a primarios; verificar matriz de estados, PATCH no-op, reclasificación, clave opcional y Company intacta en pruebas de Application.
- [x] 4.2 Integrar contactos afectados, Site, completado y SiteMutationEvents en una aceptación; verificar auditoría común, avance único, tres modos outbox y rollback ante fallos después de desmarcado, cambio Site, completado o evento mediante pruebas de atomicidad con PostgreSQL.
- [x] 4.3 Implementar plazo total y recuperación tras finalización confirmada, sin segunda escritura; verificar respuesta perdida tras commit, colisión de clave, rollback/cierre pendiente, disminución del tiempo restante y rechazo 503 sin recuperación concurrente.
- [x] 4.4 Añadir ContactOperations y company-registry.site-contact.wait-budget/COMPANY_SITE_CONTACT_WAIT_BUDGET con default 10s y validación eager; verificar configuración positiva, cero/negativa/desbordada y un perfil HTTP con espera real de lock acotada.
- [x] 4.5 Documentar protocolo de contactos en docs/architecture/adr-002-company-site-invariants.md y docs/contracts/events/README.md; verificar que el ejemplo child reutiliza exactamente los ocho campos Site y los tests del evento excluyen contactId, label, emails y teléfonos.

## 5. Consultas y cursores de contactos

- [x] 5.1 Implementar ReadContacts y página calificada con join de revisión Site, filtros AND, orden descendente y limit+1; verificar detalle, página vacía, eliminados, filtros por tipo/primario y desempate por UUID en pruebas de repositorio y Application.
- [x] 5.2 Implementar ContactCursors/AuthenticatedContactCursors con discriminador de colección, ámbito y filtros autenticados; verificar alteración, otra colección/tenant/Company/Site, rotación y cambios permitidos de actor/proceso/límite sin fuga de material criptográfico.
- [x] 5.3 Documentar consultas en docs/contracts/company-site-contact-management.md; verificar que los ejemplos reflejan revisión actual compartida, filtros exactos, límite 50/1–200, numberOfElements/nextCursor y ausencia de snapshot entre páginas.

## 6. Frontera REST y contrato público

- [x] 6.1 Añadir requests/reader JSON estricto conservando intención cruda hasta seleccionar política; verificar documento completo, desconocidos/duplicados, tipos/requeridos antes de canonización, orden fijo por fase, omisión/null/blanco y DELETE sin body o con objeto vacío mediante pruebas de frontera.
- [x] 6.2 Añadir ContactResource, DTOs View/Deletion y traducción descriptiva de errores con ResponseManager; verificar las cinco rutas reales, headers, cuatro tipos, primarios, no-op, comprobante 200, label null omitido, aislamiento y precedencia 400/404/412/409 en pruebas REST.
- [x] 6.3 Extender docs/contracts/company-registry.openapi.yaml con rutas, esquemas cerrados, perfil EMAIL, teléfonos flexibles, replay y catálogo completo; verificar esquemas/ejemplos contra respuestas HTTP y preservar los contratos OpenAPI HU01–HU06.
- [x] 6.4 Completar docs/contracts/company-site-contact-management.md, README.md y docs/contracts/planned-operations.md; verificar ejemplos ejecutables de creación, value-only PATCH, reclasificación primaria, baja/replay y advertencia contractual de sustitución aprobada de AC07.

## 7. Concurrencia entre agregados y aceptación durable

- [x] 7.1 Añadir carreras REST/SQL con transacciones independientes para primarios por POST, PATCH y reclasificación; verificar un ganador efectivo por revisión, 412 del perdedor y ausencia de desmarcado parcial o doble primario.
- [x] 7.2 Hacer competir contactos con direcciones, información/cierre Site y archivado Company siguiendo el protocolo real; verificar serialización Company→Site, revisión/auditoría correctas y rechazos acordes con ausencia, versión y estado según el orden de confirmación.
- [x] 7.3 Probar misma clave con intención equivalente y distinta, incluida colisión entre Sites/Companies del mismo tenant; verificar una aceptación, resultado original o 409 correspondiente, rollback del perdedor y ningún snapshot de otro ámbito.
- [x] 7.4 Probar replay de CREATE/UPDATE/DELETE y no-op tras cambios de valor/tipo, eliminación, cierre, archivo, reinicio y rotación; verificar status/revisión/auditoría originales, Process-Id actual y ausencia de evento o mutación adicionales, incluyendo PATCH con value sin tipo.
- [x] 7.5 Verificar detalle/página durante cambios concurrentes de contacto, dirección y Site; comprobar que cada resultado usa una revisión coherente de sentencia y que una página estable se recorre sin omisiones/repeticiones por empate.

## 8. Verificación integrada y proceso productivo

- [x] 8.1 Crear PackagedContactIT con fixtures fuera del artefacto productivo; verificar las cinco rutas, teléfono móvil/fijo ambiguo, ASCII, replay, consultas y rechazo de presupuestos inválidos en un proceso JVM real con PostgreSQL y servicios externos inaccesibles.
- [x] 8.2 Ejecutar ./gradlew test build verifyProductionIsolation y ./gradlew quarkusIntTest, junto con la variante de verificación aplicable; comprobar regresiones HU01–HU06, Flyway, arquitectura y ausencia de stubs/rutas de prueba en producción, registrando resultados y alcance efectivos.
- [x] 8.3 Construir el ejecutable nativo productivo con los comandos Podman existentes y ejecutar PackagedContactIT sobre ese binario; verificar recursos libphonenumber, codecs, OpenAPI, migración y outbox sin broker, conservando comandos, identidad del artefacto y reportes en docs/operations/native-verification.md.
- [x] 8.4 Crear docs/operations/hu07-acceptance-review.md con evidencia AC01–AC17 y la sustitución acordada de AC07; verificar trazabilidad de escenarios, resultados reales y límites, y ejecutar openspec validate add-company-site-contact-management --strict antes de considerar finalizada la implementación.
