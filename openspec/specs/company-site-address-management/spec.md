# company-site-address-management Specification

## Purpose

Gestionar las direcciones subordinadas a Site con referencias geográficas autoritativas, selección explícita de primarias y eliminación histórica, compartiendo la consistencia y revisión del agregado padre.

## Requirements

### Requirement: Operaciones de dirección dentro de Site
El servicio SHALL exponer POST y GET de colección en `/v1/companies/{companyId}/sites/{siteId}/addresses`, y GET, PATCH y DELETE individual en esa ruta seguida de `/{addressId}`. POST SHALL devolver 201; GET, PATCH y DELETE SHALL devolver 200 con el envelope compartido. Todas SHALL exigir el contexto de negocio vigente.

#### Scenario: Ciclo de una dirección
- **WHEN** un cliente autorizado crea, consulta, modifica y elimina una dirección propia con entradas válidas
- **THEN** obtiene los resultados de aceptación de cada operación y la eliminación conserva el registro histórico
- **AND** los GET no requieren If-Match ni Idempotency-Key

### Requirement: Pertenencia y ausencia de direcciones
Todo acceso SHALL calificarse por tenant/Company/Site. Las operaciones individuales SHALL resolver la dirección vigente en esa jerarquía. Company ausente/ajena SHALL producir company-not-found; después Site ausente/ajeno SHALL producir site-not-found; después dirección ausente, ajena o eliminada SHALL producir address-not-found, todos con 404. Las mutaciones individuales nuevas SHALL comprobar esa ausencia antes de comparar la versión Site.

#### Scenario: Dirección de otra sede o tenant
- **WHEN** una ruta propia contiene un addressId que no pertenece a su Site
- **THEN** devuelve 404 address-not-found sin revelar el propietario ni datos ajenos

#### Scenario: Dirección eliminada y revisión obsoleta
- **WHEN** una mutación nueva apunta a una dirección eliminada con un If-Match obsoleto
- **THEN** devuelve 404 address-not-found sin otro cambio de versión, auditoría o evento

### Requirement: Creación con contenido explícito
POST SHALL exigir addressType, countryCode y addressLine1. SHALL admitir administrativeDivisionCode, addressLine2, houseNumber, postalCode, latitude, longitude e isPrimary como opcionales. isPrimary omitido SHALL equivaler a false; los restantes opcionales omitidos SHALL equivaler a null. Identificador y auditoría SHALL ser generados por el servidor, con UUIDv7 para addressId.

#### Scenario: Primera dirección mínima
- **WHEN** se crea una dirección con los tres campos requeridos y sin isPrimary
- **THEN** se crea como no primaria, con coordenadas nulas y auditoría de creación/actualización del actor e instante aceptados
- **AND** no aplica el default SQL PHYSICAL para suplir addressType ausente

### Requirement: Canonización y límites de dirección
Los textos de dirección y códigos SHALL usar strip y mayúsculas Locale.ROOT, preservando acentos y caracteres/espacios internos significativos. SHALL validar después los límites: líneas 300, división 64, houseNumber/postalCode 32 puntos de código y país alpha-2 `[A-Z]{2}`. addressType SHALL normalizarse y admitir solo PHYSICAL o MAILING; los textos presentes no nulos SHALL rechazar blancos.

#### Scenario: Valores equivalentes
- **WHEN** se envían `physical`, ` ec ` y ` avenida  josé `
- **THEN** se usan PHYSICAL, EC y `AVENIDA  JOSÉ` para persistencia, comparación e idempotencia

#### Scenario: Longitud canónica y valores opacos
- **WHEN** las mayúsculas expanden un texto o se procesa un actor/clave idempotente
- **THEN** el límite se aplica al texto canónico final sin normalizar actor, clave ni correlación

### Requirement: PATCH con presencia y limpieza explícitas
PATCH SHALL conservar campos omitidos y aceptar null para limpiar administrativeDivisionCode, addressLine2, houseNumber y postalCode. Los campos addressType, countryCode, addressLine1 e isPrimary presentes SHALL rechazar null. isPrimary SHALL aceptar únicamente booleanos JSON. Los textos opcionales blancos SHALL rechazarse, sin convertirse en limpieza implícita.

#### Scenario: Limpieza parcial
- **WHEN** un PATCH contiene addressLine2 null y houseNumber `12a`, omitiendo los demás campos
- **THEN** elimina la segunda línea, establece houseNumber 12A y conserva los restantes valores

#### Scenario: Omisión, null y blanco diferentes
- **WHEN** se omite postalCode, se envía null o se envía una cadena blanca
- **THEN** respectivamente conserva el valor, lo elimina o rechaza la solicitud con 400

### Requirement: Coordenadas indivisibles y dentro de rango
Las coordenadas SHALL estar ambas nulas o ambas presentes, con latitud entre -90 y 90 y longitud entre -180 y 180 inclusivos. En POST/PATCH, incluir una coordenada SHALL exigir incluir también la otra: dos números o dos null. Omitir ambas en PATCH SHALL conservar el par. El servicio SHALL NOT inferir coordenadas desde el texto.

#### Scenario: Reemplazar o retirar el par
- **WHEN** PATCH envía dos números válidos o ambas coordenadas null
- **THEN** reemplaza o elimina el par completo

#### Scenario: Una coordenada enviada sobre un par existente
- **WHEN** PATCH envía solamente latitude aunque exista longitude almacenada
- **THEN** devuelve 400 sin combinarla con el valor anterior

#### Scenario: Combinación o rango inválidos
- **WHEN** se envía un número y un null, o una latitud/longitud fuera de sus rangos
- **THEN** rechaza toda la solicitud, incluidos valores apenas fuera del límite como latitud 90.0000001

### Requirement: Precisión exacta y equivalencia numérica
Cada coordenada SHALL ser representable exactamente con hasta seis decimales. SHALL admitir ceros finales adicionales que no cambien su valor y rechazar con 400 valores que requieran redondeo. La comparación de cambios y la huella idempotente SHALL usar igualdad numérica y una representación decimal canónica común.

#### Scenario: Ceros adicionales y reintento
- **WHEN** se compara o reintenta el mismo par usando -0.123456 y -0.1234560
- **THEN** se consideran el mismo valor, sin conflicto de clave ni incremento causado por su escala textual

#### Scenario: Valor que requiere redondeo
- **WHEN** se envía una coordenada -0.1234567
- **THEN** devuelve 400 antes de persistir, sin delegar el redondeo a la base

### Requirement: Nuevas referencias geográficas activas
La creación SHALL validar país y división presente mediante Geographic Reference. Cada nueva asignación SHALL exigir existencia, país ACTIVE, división ACTIVE cuando exista y pertenencia de la división al país. SHALL usar únicamente los contratos verificados del proveedor, sin tablas maestras propias, consultas a bases externas ni aceptación permisiva ante falta de contrato.

#### Scenario: País y división elegibles
- **WHEN** el país y la división solicitados existen, están ACTIVE y la división pertenece a ese país
- **THEN** la validación permite continuar con la aceptación local

#### Scenario: Referencia no activa
- **WHEN** el país o la división nueva está DRAFT, DEPRECATED o RETIRED
- **THEN** rechaza la asignación con el conflicto descriptivo correspondiente, sin persistencia parcial

### Requirement: Referencias conservadas sin revalidación
PATCH SHALL decidir la validación desde los valores canónicos efectivos. SHALL validar la combinación resultante si cambia el país o asigna una división distinta no nula. Conservar las referencias, aun enviándolas explícitamente iguales, SHALL omitir Geografía. Retirar únicamente la división SHALL omitir validación remota al no asignar otra referencia.

#### Scenario: Corrección sobre referencia que dejó de estar activa
- **WHEN** se modifica una línea o se reclasifica una dirección conservando país/división, aunque hayan pasado a RETIRED o el proveedor esté caído
- **THEN** puede aceptarse la corrección sin consultas geográficas si cumple las reglas locales

#### Scenario: Retirar división existente
- **WHEN** un PATCH conserva el país y envía administrativeDivisionCode null
- **THEN** elimina la referencia sin exigir una consulta al proveedor

### Requirement: Cambio de país conserva división omitida
Cambiar countryCode SHALL conservar administrativeDivisionCode si se omite, y validar ambos contra el país resultante. El cliente SHALL enviar null explícito para retirar la división. Una combinación resultante inválida SHALL rechazar toda la mutación; el servicio SHALL NOT borrar o sustituir silenciosamente la división.

#### Scenario: País nuevo con división anterior incompatible
- **WHEN** una dirección EC/EC-P recibe únicamente countryCode CO y el proveedor no confirma EC-P bajo CO
- **THEN** conserva la dirección original y devuelve el rechazo de referencia correspondiente

#### Scenario: País nuevo sin división
- **WHEN** recibe countryCode CO y administrativeDivisionCode null
- **THEN** valida el nuevo país ACTIVE y elimina la referencia administrativa si se acepta la operación

### Requirement: Geografía acotada y sin efectos parciales
La validación remota obligatoria SHALL tener plazos finitos y distinguir ausencia, inactividad, indisponibilidad y contrato inválido. Un rechazo SHALL dejar intactas direcciones, Site, aceptación y outbox. GET, DELETE, replay y PATCH sin nuevas asignaciones SHALL funcionar sin llamadas geográficas. SHALL NOT haber consultas de proveedores durante el arranque.

#### Scenario: Timeout obligatorio
- **WHEN** una creación o reasignación no completa la validación geográfica
- **THEN** devuelve 503 dependency-unavailable sin consumir la clave ni modificar ninguna fila de negocio

#### Scenario: Lectura y eliminación con proveedor caído
- **WHEN** Geografía está inaccesible y se consulta o elimina una dirección propia con precondiciones válidas
- **THEN** la operación local puede completar sin invocar al proveedor

### Requirement: Selección primaria explícita por tipo
Cada Site SHALL admitir como máximo una primaria no eliminada por addressType y permitir cero. Crear sin isPrimary SHALL mantener false aunque sea la primera. PATCH que omite isPrimary SHALL conservarlo. Desmarcar o eliminar la primaria SHALL dejar su tipo sin primaria, sin elegir reemplazo automático.

#### Scenario: Primarias independientes
- **WHEN** un Site selecciona una PHYSICAL y una MAILING como primarias
- **THEN** ambas pueden coexistir sin permitir dos primarias del mismo tipo

#### Scenario: Retirar la primaria
- **WHEN** se elimina o desmarca la primaria existiendo otras direcciones del mismo tipo
- **THEN** ninguna otra se promueve automáticamente

### Requirement: Reemplazo primario atómico
Crear o actualizar con isPrimary true SHALL desmarcar otra primaria anterior del tipo y seleccionar la dirección objetivo atómicamente. SHALL preservar la unicidad vigente y rechazar direcciones eliminadas. El cambio conjunto SHALL consumir una sola revisión Site y un solo evento habilitado, independientemente del número de direcciones afectadas.

#### Scenario: Sustitución concurrente
- **WHEN** dos selecciones primarias efectivas compiten con la misma revisión Site
- **THEN** confirma como máximo una y la otra recibe 412, sin doble primaria ni desmarcado parcial

### Requirement: Reclasificación de dirección
PATCH SHALL permitir cambiar addressType entre PHYSICAL y MAILING. Si isPrimary se omite, SHALL conservar su valor; una primaria reclasificada SHALL reemplazar atómicamente la primaria del tipo de destino, dejando el tipo anterior sin reemplazo automático. isPrimary false explícito SHALL permitir reclasificar sin desplazar la primaria de destino.

#### Scenario: Primaria pasa a otro tipo
- **WHEN** A es primaria PHYSICAL, B es primaria MAILING y A recibe solo addressType MAILING
- **THEN** A queda primaria MAILING, B queda no primaria y PHYSICAL queda sin primaria en una única aceptación

#### Scenario: Reclasificación con desmarcado explícito
- **WHEN** A recibe addressType MAILING e isPrimary false
- **THEN** conserva la primaria MAILING anterior y deja A no primaria

### Requirement: Estados editables y consultas históricas
Las nuevas mutaciones SHALL permitirse con Company DRAFT/ACTIVE/INACTIVE y Site DRAFT/ACTIVE/INACTIVE. Company ARCHIVED o Site CLOSED SHALL bloquearlas con 409, incluso PATCH no-op, después de comprobar ausencia y versión. GET SHALL permitir esos estados terminales para direcciones no eliminadas. El replay SHALL mantener su aceptación previa.

#### Scenario: Preparación e inactividad
- **WHEN** Site y Company son editables aunque no estén operativos
- **THEN** puede registrar o corregir su dirección sin activar ninguno de los agregados

#### Scenario: Estado terminal con dirección existente
- **WHEN** una mutación nueva con versión vigente apunta a una dirección existente de Site CLOSED o Company ARCHIVED
- **THEN** devuelve el conflicto de estado, mientras su GET y un replay válido siguen disponibles

### Requirement: Revisión compartida del Site
Toda mutación SHALL exigir If-Match con la revisión padre. Cada cambio efectivo SHALL incrementar Site.version exactamente una vez y conservar estado, datos básicos e identidad de Site y todos los valores de Company. Las mutaciones SHALL coordinarse con los demás escritores del agregado; dirección SHALL NOT tener una revisión independiente.

#### Scenario: Carrera con información Site u otro hijo
- **WHEN** una dirección y otra mutación compatible del mismo Site compiten usando la misma revisión
- **THEN** confirma como máximo un cambio efectivo y el otro recibe 412 sin sobrescribirlo

#### Scenario: Creación con revisión obsoleta
- **WHEN** POST nuevo apunta a Site propio con revisión diferente de If-Match
- **THEN** devuelve 412 sin crear dirección, aceptación ni evento

### Requirement: Auditoría común de filas afectadas
Toda dirección efectivamente cambiada, incluida una primaria desmarcada indirectamente, SHALL actualizar updatedAt/updatedBy con el mismo instante UTC de microsegundos y actor exacto usados por Site. SHALL preservar createdAt/createdBy. La creación SHALL fijar ambos pares al instante aceptado; las filas no modificadas SHALL conservar su auditoría.

#### Scenario: Promoción que desmarca otra dirección
- **WHEN** la selección de A desmarca B
- **THEN** A, B y Site comparten el instante y actor de actualización, con sus auditorías de creación intactas

### Requirement: PATCH sin cambio efectivo
Un PATCH válido cuyo resultado canónico sea igual al persistido SHALL devolver 200 con la dirección y siteVersion conservadas. SHALL verificar pertenencia, revisión y editabilidad, sin cambiar auditoría ni generar evento. Con clave idempotente SHALL guardar una aceptación durable del no-op; sin clave SHALL omitir el completado.

#### Scenario: Texto y coordenadas equivalentes
- **WHEN** se acepta texto equivalente, coordenadas numéricamente iguales o `{}` con revisión vigente
- **THEN** conserva dirección, revisión y auditoría, y guarda únicamente la aceptación si se proporcionó clave

### Requirement: Idempotencia independiente por operación de dirección
POST SHALL exigir Idempotency-Key; PATCH y DELETE SHALL admitirla opcionalmente. La identidad SHALL ser tenant/operación/clave con namespaces company.site.address.create.v1, company.site.address.update.v1 y company.site.address.delete.v1. La clave SHALL ser opaca, no blanca y de hasta 128 puntos de código, con la validación compartida de repetición.

#### Scenario: Reintento y reutilización en otro recurso
- **WHEN** se usa una clave aceptada dentro de la misma operación y tenant para otra dirección, Site o Company
- **THEN** devuelve 409 idempotency-key-already-used-with-different-request sin revelar su snapshot

#### Scenario: Clave opcional ausente
- **WHEN** PATCH o DELETE se presenta sin clave o con una clave inválida
- **THEN** la ausencia permite aceptar sin completado; una clave presente inválida produce 400

### Requirement: Huella de intención original de dirección
La huella SHALL incluir tenant, operación, Company, Site, addressId de ruta cuando exista, If-Match original e intención canónica. PATCH SHALL distinguir omitido/null/valor; POST SHALL equiparar opcionales omitidos con sus defaults efectivos. SHALL usar política HMAC versionada con clave histórica identificada y excluir actor, proceso, estado actual, clave idempotente y valores generados.

#### Scenario: Equivalencia de creación
- **WHEN** se reintenta una creación cambiando orden JSON, mayúsculas equivalentes, escala decimal sin cambio de valor o isPrimary omitido por false
- **THEN** devuelve la misma aceptación si conserva el resto de la intención y el If-Match original

#### Scenario: Cambio de intención original
- **WHEN** cambia If-Match, el recurso o la presencia de un campo PATCH, incluso `{}` frente a una limpieza explícita redundante
- **THEN** devuelve conflicto de clave antes de comprobar el recurso actual

### Requirement: Replay original independiente de datos actuales
Un replay válido SHALL devolver el status y snapshot originales, con siteVersion y auditoría originales y Process-Id actual. SHALL preceder a consultas del recurso o proveedores y funcionar después de modificar/eliminar la dirección, cerrar Site, archivar Company o cambiar estados geográficos. SHALL NOT crear otra revisión, auditoría, aceptación ni evento.

#### Scenario: Reintento después de eliminación
- **WHEN** se repite una creación o actualización aceptada después de eliminar la dirección
- **THEN** devuelve su representación original aunque el GET ordinario produzca 404

#### Scenario: Aceptación concurrente durante una espera
- **WHEN** una aceptación equivalente confirma después de la consulta inicial y antes de evaluar la operación protegida
- **THEN** devuelve esa aceptación antes de aplicar ausencia, revisión, estado o fallo geográfico actuales

### Requirement: Aceptación completa y atómica
Direcciones afectadas, revisión/auditoría Site, completado cuando exista clave y evento habilitado SHALL confirmar o revertirse juntos. Los rechazos SHALL NOT consumir claves. Una colisión idempotente SHALL revertir los efectos provisionales y resolver el resultado durable sin repetir la escritura ni exponer la aceptación de otra intención.

#### Scenario: Fallo al completar o persistir el evento
- **WHEN** falla una escritura obligatoria después de desmarcar una primaria
- **THEN** revierte todas las direcciones, Site y las demás filas del intento

#### Scenario: Misma clave en paralelo
- **WHEN** dos solicitudes equivalentes compiten por una clave
- **THEN** existe una aceptación durable y como máximo una mutación/evento, recuperando ambas el resultado original

### Requirement: Plazo total y resultado incierto
La operación SHALL tener un plazo total finito configurable que cubra consultas, validación remota, esperas, finalización y recuperación. Un resultado durable incierto SHALL producir 503 sin afirmar rollback ni repetir la mutación automáticamente. La recuperación SHALL comenzar solamente tras finalizar la transacción fallida y conservar el tiempo restante.

#### Scenario: Commit confirmado con respuesta perdida
- **WHEN** el commit aceptó una operación con clave y se pierde la respuesta
- **THEN** el mismo reintento puede recuperar su aceptación original

#### Scenario: Finalización no confirmada
- **WHEN** vence el plazo sin conocerse la finalización
- **THEN** devuelve 503 y evita otra escritura o recuperación concurrente contra esa transacción

### Requirement: Eliminación lógica y comprobante mínimo
DELETE SHALL conservar la fila, fijar deletedAt/deletedBy, poner isPrimary false y actualizar su auditoría con el mismo instante/actor. SHALL devolver 200 con exactamente addressId, siteId, siteVersion, deletedAt y deletedBy en data. Una solicitud nueva sobre una dirección eliminada SHALL devolver 404; un replay válido SHALL reproducir el comprobante original.

#### Scenario: Eliminar primaria
- **WHEN** se elimina una primaria propia con precondiciones válidas
- **THEN** conserva sus datos históricos, deja su tipo sin primaria y devuelve el comprobante sin dirección física ni coordenadas

#### Scenario: Segundo DELETE y replay
- **WHEN** llega otro DELETE nuevo o un replay equivalente de la eliminación aceptada
- **THEN** devuelve respectivamente 404 o el 200 original, sin nuevos efectos

### Requirement: Representación pública de dirección
POST, PATCH, GET individual y elementos del listado SHALL usar addressId, siteId, siteVersion, addressType, countryCode, addressLine1, isPrimary y los cuatro campos de auditoría. SHALL añadir solo opcionales no nulos entre administrativeDivisionCode, addressLine2, houseNumber, postalCode, latitude y longitude. SHALL excluir tenantId, versión propia, datos de proveedores y metadatos de eliminación.

#### Scenario: Resultado local completo
- **WHEN** se devuelve una dirección no eliminada
- **THEN** los identificadores son strings UUID, siteVersion entero, isPrimary booleano, coordenadas números JSON y fechas UTC/microsegundos
- **AND** se preservan los actores exactos y se omiten opcionales nulos

### Requirement: SiteVersion coherente en consultas
Las consultas SHALL leer cada dirección y la revisión Site coherentemente, usando la versión actual del agregado. Los elementos de una página SHALL compartir la revisión observada de su Site. La versión SHALL poder cambiar por otro hijo aunque esa dirección no cambie. Una página vacía SHALL devolver data vacío; el cliente SHALL poder obtener la revisión mediante GET de Site.

#### Scenario: Mutación de otra dirección o información Site
- **WHEN** otro cambio incrementa Site sin modificar la dirección consultada
- **THEN** GET devuelve sus datos conservados con la nueva siteVersion

#### Scenario: GET frente a aceptación histórica
- **WHEN** Site avanzó después de aceptar una dirección
- **THEN** GET devuelve la revisión actual y el replay conserva la revisión original

### Requirement: Listado filtrado y paginado de direcciones vigentes
El listado SHALL excluir eliminadas y admitir únicamente addressType, isPrimary, cursor y limit, una vez cada uno. Los filtros SHALL ser exactos PHYSICAL/MAILING y true/false, combinados con AND. SHALL ordenar por createdAt DESC/addressId DESC, con limit 50 por defecto y rango 1–200. Una colección propia sin coincidencias SHALL devolver 200 y data vacío.

#### Scenario: Buscar primaria de un tipo
- **WHEN** se lista con addressType PHYSICAL e isPrimary true
- **THEN** devuelve como máximo una dirección vigente; filtrar solo isPrimary true puede devolver una de cada tipo

#### Scenario: Desempate y límites
- **WHEN** varias direcciones comparten createdAt y se navega un conjunto estable con limit 1
- **THEN** el desempate por addressId evita omisiones/repeticiones y conserva el orden descendente

### Requirement: Cursor exclusivo y metadatos compartidos
El cursor SHALL estar autenticado y vinculado a su formato, colección de direcciones, tenant, Company, Site, filtros efectivos y orden. SHALL conservar fecha/ID de frontera y rechazar otro ámbito. El envelope SHALL tener data array, numberOfElements y nextCursor solo si hay continuación, sin totales ni pagination anidada. La navegación SHALL observar datos actuales, sin snapshot entre páginas.

#### Scenario: Cursor ajeno o modificado
- **WHEN** se reutiliza un cursor de otra colección, tenant, Company, Site o filtros, o se manipula su contenido
- **THEN** devuelve 400 sin ampliar el ámbito ni exponer material criptográfico

#### Scenario: Página vacía o final
- **WHEN** no hay coincidencias o se alcanza el final
- **THEN** devuelve numberOfElements correcto sin nextCursor, prevCursor, totalElements ni totalPages

### Requirement: Evento mínimo de Site por cambio de dirección
Cada cambio efectivo SHALL producir exactamente un company.site.updated.v1 cuando outbox esté stored-only o published; disabled SHALL omitirlo. SHALL identificar COMPANY_SITE, su revisión y auditoría resultantes usando el contrato mínimo existente, sin direcciones, coordenadas ni datos idempotentes. No-op/replay SHALL omitir eventos; HU06 SHALL almacenar PENDING sin publicar.

#### Scenario: Cambio múltiple de primarias
- **WHEN** una aceptación modifica varias direcciones y confirma con outbox habilitado
- **THEN** existe un solo evento con aggregateVersion igual a siteVersion y correlación/actor originales

#### Scenario: Broker caído y cambio de modo
- **WHEN** RabbitMQ está inaccesible o un replay usa un modo outbox distinto del original
- **THEN** la nueva aceptación local puede almacenar el evento y el replay no publica ni crea un evento retrospectivo
