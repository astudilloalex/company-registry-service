# Design

## Context

La motivación y el alcance están en [proposal.md](proposal.md). El contrato nuevo está en [company-site-contact-management](specs/company-site-contact-management/spec.md); los otros tres deltas modifican normalización, integridad y frontera HTTP.

Estado observado durante la exploración:

- V1 ya define `company_site_contact_points`, los cuatro tipos, `value varchar(320)`, `label varchar(80)`, borrado coherente, identidad inmutable y los índices `uq_company_site_contacts_current_value` y `uq_company_site_primary_contact`. V7 es la última migración y añade aceptación/paginación de direcciones.
- `ContactType`, `RegistryData.ContactRecord`, `ContactEntity` e `insertContact` existen. La consulta técnica `ReactiveSiteRepository.contacts` está calificada por tenant/Company/Site, pero incluye eliminados y no ofrece revisión coherente ni paginación pública.
- `ContactValue` conserva todo el email y valida una expresión permisiva hasta 320 caracteres. Todos los canales telefónicos comparten `isValidNumber`; MOBILE aún no comprueba clasificación. libphonenumber 9.0.41 y sus recursos nativos ya están incorporados.
- HU06 aporta `MutateAddress`, `ReadAddresses`, `AddressMutation`, fingerprints/codecs/cursores y composición. Las mutaciones usan Company → Site, revisión compartida y `SiteMutationEvents`. La aceptación de contactos puede ser local, sin las dos fases de Geografía de HU06.
- La nueva política EMAIL contradice deliberadamente la preservación de parte local en `business-text-normalization`, los tests T00 y HU07 AC07. La decisión humana de minúsculas completas sustituye esa expectativa; no se altera el issue ni se reinterpreta como compatibilidad transparente.

El diseño es necesario por la evolución de datos, concurrencia entre hijos, replay de PATCH sin tipo y conservación de material histórico.

## Goals / Non-Goals

**Goals:**

- Mantener políticas síncronas y puras en Domain; orquestar I/O reactivo en Application mediante puertos existentes y específicos cuando sean necesarios.
- Dar a cada aceptación una frontera transaccional completa y a cada replay independencia del estado actual.
- Separar representación canónica, intención original, resultado aceptado y evento mínimo, conservando las versiones históricas de cada contrato.
- Extender las convenciones HU06 sin convertirlas en un framework genérico de hijos ni modificar sus formatos persistidos.

**Non-Goals:**

- Añadir otro agregado, revisión o tablas de contacto; generar abstracciones para futuros canales no solicitados.
- Canonizar masivamente contactos históricos o resolver colisiones mediante fusión, borrado o selección arbitraria de propietario.
- Añadir verificación remota, un publicador de outbox o capacidades excluidas en la propuesta.

## Decisions

### 1. Políticas de valor y PATCH dentro de Domain

Incorporar `ContactInformation`, `ContactInformationPatch`, `ContactInformationPolicy` y `ContactPrimaryPolicy`, aprovechando el patrón de `FieldChange` y booleano con presencia de HU06. Mantener actor, headers, identificadores y claves fuera de la normalización de negocio. `ContactValue` delegará la política EMAIL/teléfono aprobada; no habrá conversor Jackson global de strings.

EMAIL V1 aplica strip → comprobación ASCII → minúsculas Locale.ROOT → límites y sintaxis. El orden evita que una letra Unicode que se vuelva ASCII al convertirla a minúsculas eluda el perfil. El validador puro acepta dot-atom-text ASCII y un dominio de al menos dos etiquetas alfanuméricas, con guiones interiores y límite 63 por etiqueta. Conserva puntos y `+alias`. Usa 254 caracteres totales y 64 locales; el límite de 254 adopta el tamaño práctico de ruta SMTP de 256 octetos descontando los delimitadores. No se consulta DNS ni SMTP. Referencias: [RFC 5321 §4.5.3.1](https://www.rfc-editor.org/rfc/rfc5321.html#section-4.5.3.1), [RFC 5322 §3.2.3](https://www.rfc-editor.org/rfc/rfc5322.html#section-3.2.3).

La política telefónica conserva el `+` explícito y el conjunto ASCII de entrada ya aceptado: dígitos, espacios, paréntesis y guiones. Usa la biblioteca fijada 9.0.41 sin región por defecto, rechaza extensiones, exige `isValidNumber`, verifica E.164 de hasta 15 dígitos y, para MOBILE, comprueba MOBILE/FIXED_LINE_OR_MOBILE. No limita PHONE o WHATSAPP a líneas móviles. La versión de biblioteca y sus metadatos forman parte de la política de validación 1; este cambio no actualiza la dependencia.

PATCH mantiene omitido/null/valor; solo label admite limpieza. Primero combina valores suministrados y conservados, después verifica compatibilidad del valor con el tipo resultante y calcula igualdad canónica. Reclasificar conserva identidad. No-op incluye `{}` y equivalencias canónicas, con la misma admisión que un cambio efectivo.

Alternativas: conservar el email actual contradice la decisión acordada; aceptar EAI/quoted-string amplía V1; una expresión genérica de email del framework no define suficientemente el perfil. Elegir un país desde tenant o dirección cambiaría el significado de entradas ambiguas. Un caso de uso por cada campo no aporta una frontera adicional.

### 2. Intención canónica independiente del tipo actual

Un PATCH que contiene solo `value` no identifica su contactType; no puede normalizarlo usando el contacto actual si debe reproducirse después de eliminarlo o reclasificarlo. `ContactIntentPolicyV1` resolverá una familia léxica sin I/O: tras strip, la presencia de `@` selecciona EMAIL; en su ausencia, `+` selecciona PHONE. Una entrada sin familia válida se rechaza con contact-value-invalid. Ambas familias son disjuntas en el perfil V1. El valor de familia PHONE se valida/normaliza de forma general; la clasificación MOBILE es una comprobación posterior del resultado efectivo.

La representación firmada de un value presente conserva familia y valor canónico. La presencia de contactType es independiente; si está presente se normaliza a su enum. Así, un PATCH sin tipo y otro con tipo explícito son intenciones diferentes aunque produzcan el mismo resultado. Un value léxicamente válido pero incompatible con el tipo resultante se rechaza únicamente al evaluar una aceptación nueva, tras propiedad, revisión y estado.

La lectura HTTP valida documento y después tipos/requeridos de todos los campos, conservando strings originales. Dentro de esa fase usa contactType/value/label/isPrimary. La canonización semántica usa el mismo orden, pero con la política seleccionada: histórica si hay completado, activa si no lo hay. Fallos canónicos de campos aportados preceden a la pertenencia; compatibilidad con el tipo efectivo y clasificación MOBILE se evalúan después de revisión/estados. No debe endurecerse el reader con reglas actuales que impidan un replay válido de una política anterior.

Alternativas: firmar el texto sin normalizar rompería equivalencias telefónicas/email acordadas; consultar el tipo actual rompería el replay independiente; exigir contactType en cualquier PATCH de value alteraría la semántica parcial aprobada.

### 3. Una aceptación local protegida

Crear `MutateContact` y `ReadContacts` en Application y componerlos en `ContactOperations`. Extender `SiteRepository` con detalle/página/primario/update de contactos calificados mediante `ContactScope`; reutilizar `insertContact` y `AtomicOperation`. No introducir un repositorio duplicado con acceso fuera de la misma unidad reactiva.

```text
headers/ruta/JSON -> consultar aceptacion
                          |
                  replay o intento nuevo
                          |
           transaccion: Company -> Site -> contacto
                          |
              ausencia -> revision -> estados
                          |
           politica efectiva -> duplicados -> primarios
                          |
       contactos + Site + aceptacion opcional + evento
                          |
                        COMMIT
```

Un intento nuevo adquiere Company calificada, vuelve a consultar el completado después del bloqueo y resuelve Site/contacto vigente. Valida If-Match antes de Company ARCHIVED y Site CLOSED; después aplica la política efectiva. La búsqueda de duplicado del tipo/valor resultante excluye el contacto objetivo y ocurre antes de desmarcar otro primario. La restricción SQL es la autoridad final.

Usar un instante UTC truncado a microsegundos y actor exacto para todas las filas efectivamente afectadas. Desmarcar el primario de destino antes de insertar/promover/reclasificar el objetivo, porque el índice único parcial es inmediato. Un isPrimary omitido conserva el flag; false no desmarca al destino; el tipo de origen puede quedar vacío. Un no-op no ejecuta updates ni evento y solo guarda aceptación si hay clave.

El cambio de Site se hace con la misma actualización condicional por versión utilizada por HU06. Company solo se bloquea; no se modifica su versión, estado ni auditoría. No habrá suscripciones internas, await, llamadas HTTP ni trabajo paralelo sobre una sesión Hibernate Reactive.

Alternativas: una versión propia de Contact rompe HU07 AC13 y las carreras con direcciones; bloquear primero el hijo invierte el orden establecido; confiar únicamente en el índice no permite desmarcado/promoción atómicos con auditoría común. El bloqueo Company serializa también escritores de otras sedes del mismo padre, un coste ya asumido por HU05/HU06 que este cambio conserva.

### 4. Aceptación durable, snapshots y recuperación

Añadir `ContactMutation` con CREATE/UPDATE/DELETE y resultados View/Deletion, más puertos `ContactFingerprints` y `ContactResults`. Los namespaces serán `company.site.contact.{create,update,delete}.v1`; la identidad sigue tenant/operación/clave.

`ContactFingerprintV1` fija serialización UTF-8, orden de campos y HMAC-SHA256 reutilizando `HmacKeys`. Incluye versión de política, tenant, operación, Company, Site, contactId nullable, revisión original y documento canónico. POST materializa label null/isPrimary false; PATCH codifica presencia separada del valor y la familia canónica de value; DELETE firma intención vacía. Excluye actor, proceso, clave idempotente y datos actuales/generados. Conservar vectores dorados de bytes/hash para detectar deriva.

`ContactResultCodec` formato 1 preserva operación, propiedad interna y resultado. View tiene los diez campos públicos requeridos y label nullable interno; Deletion contiene el comprobante de cinco campos. Validar identidad, Site y tenant contra el completado, versión esperada, estado HTTP, forma/tipos y auditoría. CREATE exige nuevo UUIDv7; UPDATE/DELETE conservan UUIDs históricos válidos. CREATE/DELETE avanzan uno; UPDATE conserva o avanza uno, sin desbordamiento. El no-op puede conservar auditoría anterior a la aceptación.

Decodificar no ejecuta validación telefónica contra metadatos nuevos ni reconstruye resultados. Verifica representación histórica con su formato/política; E.164 y las invariantes canónicas no dependen de una prueba actual de asignación. La política 1 queda vinculada a la versión y recursos actuales: una actualización futura deberá mantener la política histórica y sus metadatos o demostrar igualdad antes de reutilizarla. Nunca se sustituye material faltante por el activo; falla con 500 saneado.

Reutilizar la semántica de finalización de `AtomicOperation` y `PersistenceFailure.transactionFinalized`. Presupuesto `company-registry.site-contact.wait-budget`, por defecto 10s, configurable mediante `COMPANY_SITE_CONTACT_WAIT_BUDGET`; validar positiva/finitud/desbordamiento en composición eager al arrancar, como HU06. El presupuesto incluye lecturas, locks, commit/close y recuperación. Tras rollback/cierre confirmado, una colisión de clave o respuesta perdida puede resolverse leyendo el completado en una sesión nueva con tiempo restante. Nunca repetir la escritura; sin finalización conocida devolver 503 sin recuperación concurrente. Sin clave no se promete recuperación de respuesta original.

Alternativas: devolver un GET actual perdería revisión/auditoría originales; fingerprints sin política versionada romperían replay al cambiar metadatos telefónicos; reintentar automáticamente una transacción incierta puede duplicar aceptación. Introducir TTL contradice la retención establecida.

### 5. Persistencia incremental y clasificación de unicidad

Añadir `V8__add_site_contact_management_support.sql`. Mantener la columna value de 320 caracteres para la historia; aplicar a EMAIL vigentes un CHECK nuevo que garantice ASCII, minúsculas, límites y sintaxis del perfil mediante expresiones SQL deterministas con semántica ASCII. SQL comprueba la salida canónica, no normaliza entradas ni calcula fingerprints. Pruebas compartidas de fronteras válidas/inválidas comprueban equivalencia de aceptación Java/SQL para EMAIL; la clasificación telefónica sigue siendo responsabilidad del dominio.

Antes de añadir restricciones, la migración detecta EMAIL vigentes incompatibles, colisiones por `(site_id,contact_type,lower(value))` y completados HU07 con metadatos insuficientes. Detiene la instalación con mensajes sin valores de contacto. No modifica filas de negocio, snapshots, auditoría ni eventos. EMAIL eliminados históricos quedan fuera del CHECK nuevo y conservan bytes; CHECKs previos de borrado y teléfonos siguen vigentes para todas las filas.

Conservar los dos índices únicos existentes y añadir `ix_company_site_contacts_page(site_id,created_at DESC,id DESC) WHERE deleted_at IS NULL`. Añadir un CHECK de metadatos para los tres namespaces HU07 con Site/versión/procedencia obligatorios y HTTP 201/200 correspondiente; no se añade FK a contactId porque la propiedad y el resultado del hijo se conservan/verifican en el snapshot y la fila histórica permanece.

Extender la clasificación neutral de unicidad con CONTACT_VALUE para `uq_company_site_contacts_current_value`; traducirla a 409 contact-value-already-exists tras rollback confirmado. La colisión de `pk_company_idempotency_records` conserva su recuperación existente. Una violación de primaria a pesar del protocolo protegido indica un fallo de invariantes y devuelve 500 saneado, revirtiendo la transacción; no se presenta como una selección primaria normal rechazada.

Alternativas: reescribir V1 invalida checksums; lower(value) silencioso puede fusionar identidades o cambiar auditoría sin Site/evento; un índice insensible a mayúsculas sin exigir almacenamiento canónico no cumple la decisión. Conservar varchar(320) y exceptuar eliminados permite preservar historia sin admitir nuevas representaciones fuera del perfil.

### 6. Consultas, HTTP y evento existentes como referencia

Las lecturas públicas usan métodos nuevos con `deletedAt IS NULL`, calificación tenant/Company/Site y join que devuelve la revisión del Site en la misma sentencia. Los elementos de cada página comparten snapshot de sentencia; no se promete snapshot entre páginas. La consulta técnica histórica existente no se expone como GET público ni se cambia indiscriminadamente porque otros tests pueden necesitar historia.

Aplicar filtros exactos antes de limit+1 y ordenar por createdAt/id descendentes. `ContactCursors`/`AuthenticatedContactCursors` usan el mecanismo HMAC existente, con discriminador de colección propio, versión y keyId, ámbito/filtros/orden/frontera. Actor/proceso/límite no forman parte de la compatibilidad del cursor. No se incorpora búsqueda por value o label a V1.

Añadir `ContactResource`, DTOs separados de View/Deletion y reader JSON cerrado inspirado en `AddressRequestReader`, sin copiar su dependencia de geografía. Usar `ResponseManager` y los códigos genéricos de éxito existentes; `ContactResponseCode` añade solo las causas descriptivas nuevas. La frontera conserva 415 y errores de framework compartidos, rechaza campos desconocidos/duplicados y publica ejemplos completos de las cinco rutas. La precedencia de errores especializada se documenta en el delta HTTP.

Reutilizar `SiteMutated.candidate` y `SiteMutationEvents`: disabled omite; stored-only/published guardan PENDING, sin publicación. El payload sigue conteniendo exactamente los ocho campos Site existentes, sin contactId, changedFields, label, email o teléfono. No se modifica la especificación del evento porque ya admite mutaciones de hijos con este mismo contrato.

Alternativas: devolver la revisión original en GET impediría coordinar otro hijo; reutilizar un cursor Address sin discriminador mezclaría colecciones; añadir detalles al evento violaría su contrato cerrado y expondría contactos innecesariamente.

### 7. Verificación por fronteras y trazabilidad de aceptación

| Criterios HU07 | Evidencia prevista |
| --- | --- |
| AC01–AC07 | Políticas puras y REST para los cuatro tipos, ASCII/minúsculas completas como sustitución aprobada de AC07, límites y etiquetas. |
| AC08–AC10 | SQL y REST de selección, reclasificación y auditoría de primarios; cero permitido, sin sustitución automática. |
| AC11 | Duplicados EMAIL por mayúsculas y teléfonos por formato, misma cifra MOBILE/WHATSAPP, conflictos en PATCH y reutilización tras borrado. |
| AC12–AC13 | Transacciones independientes y peticiones concurrentes reales: dos primarios, contacto/dirección, contacto/información o cierre Site. |
| AC14 | Bytes/hash dorados, JSONB, reinicio/rotación, valor PATCH sin tipo, replay tras reclasificación/eliminación y conflicto de intención. |
| AC15–AC16 | Comprobante mínimo, historia intacta, GET sin eliminados, jerarquías ajenas y precedencia 404/412/409. |
| AC17 | Tres modos outbox, schema JSON cerrado, un evento por aceptación y rollback conjunto ante fallos de escritura. |

Ampliar los fixtures de contacto T00/HU03/HU05/HU06 que insertan EMAIL vigente con mayúsculas para el esquema final; preservar actores opacos y fixtures de migraciones antiguas que deliberadamente demuestran la semántica anterior. `SiteAddressMetadataMigrationTest` actualmente instala hasta V7 con un email mixto: esa prueba histórica sigue siendo válida y una prueba de upgrade a V8 deberá distinguir el bloqueo esperado de un dataset compatible.

Crear `PackagedContactIT` con fixtures fuera del artefacto productivo; probar JVM y nativo real con la metadata de teléfono empaquetada, OpenAPI, migraciones, replay y ausencia de rutas/stubs de verificación en producción. Los comandos y aislamiento seguirán `docs/operations/native-verification.md`, no una simulación de ejecución nativa en JVM. La revisión de aceptación documentará AC07 acordado, comandos, resultados y límites de evidencia.

## Risks / Trade-offs

- [Minúsculas completas pueden identificar como iguales buzones que otro sistema considera distintos] → Política de negocio aprobada, perfil V1 explícito, detección de colisiones y rechazo sin fusión.
- [Datos T00 vigentes pueden no satisfacer EMAIL V1] → Preflight V8 sin modificaciones; un despliegue con incompatibilidades se detiene. Cualquier remediación de datos reales requiere alcance separado y una decisión explícita; no se inventa dentro de esta migración.
- [Divergencia entre el CHECK EMAIL y la política Java] → Casos compartidos de sintaxis/fronteras y evaluación SQL en locale ASCII; no usar SQL para canonizar solicitudes.
- [Cambios de metadata telefónica pueden cambiar el significado de la intención] → Dependencia y política 1 fijadas, vectores dorados y conservación histórica obligatoria antes de actualizar.
- [Contención Company y aceptación incierta] → Orden de locks existente, presupuesto total y recuperación solo después de finalización conocida; no hay promesa de ausencia de 503 bajo contención.
- [Lecturas paginadas cambian entre páginas] → Cursor autentica selección y frontera, no un snapshot temporal; contrato explícito de lecturas actuales.

## Migration Plan

1. Implementar y probar V8 desde base vacía y V7 compatible, incluyendo catálogo, privilegios y validación de checksums V1–V7. Incluir datasets incompatibles para demostrar bloqueo sin alteración de datos; no ejecutar remediaciones como parte de la instalación.
2. Incorporar el nuevo contrato EMAIL y ajustar únicamente fixtures vigentes afectados, conservando snapshots opacos, actores y pruebas de esquemas anteriores. Confirmar regresiones HU01–HU06 y preservar sus fingerprints/codecs.
3. Desplegar con runtime/migrador separados y claves HMAC histórica/activa disponibles. El migrador ejecuta el preflight antes de habilitar restricciones/rutas; una incompatibilidad detiene la instalación con diagnóstico saneado.
4. Verificar las cinco rutas, OpenAPI, presupuesto de arranque, metadata telefónica y outbox en JVM/nativo real. Mantener broker/proveedores inaccesibles para demostrar independencia local.
5. Ante rollback conservar V8, filas históricas, aceptaciones/eventos y claves. Restaurar código que mantenga EMAIL V1 y los codecs HU07, o suspender rutas afectadas; no deshacer la migración, restituir contactos eliminados o modificar checksums para volver al comportamiento de mayúsculas anterior.
