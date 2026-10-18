# business-api-conventions Specification

## Purpose

Establecer una frontera HTTP consistente y comprobable para los endpoints de negocio que se incorporarán incrementalmente a Company Registry.

## Requirements

### Requirement: Contexto obligatorio en la API de negocio
Las rutas de negocio bajo `/v1` SHALL exigir exactamente un `Tenant-Id`, `User-Id` y `Process-Id`. Tenant y proceso SHALL ser UUID canónicos en minúsculas; usuario SHALL ser texto no vacío de hasta 128 puntos de código sin controles. El contexto SHALL proceder de headers, no del body. SHALL rechazar representaciones UUID no canónicas sin normalizar silenciosamente el valor recibido.

#### Scenario: Contexto válido
- **WHEN** se recibe una petición con headers válidos
- **THEN** la frontera entrega tenant, actor y proceso a la operación sin alterar sus valores opacos

#### Scenario: Contexto inválido
- **WHEN** falta un header, se repite o su valor es inválido
- **THEN** la frontera devuelve HTTP 400 con envelope compartido y un código estable que identifica la regla incumplida

#### Scenario: UUID de cabecera con mayúsculas
- **WHEN** Tenant-Id o Process-Id contiene letras hexadecimales mayúsculas, espacios o una representación UUID abreviada
- **THEN** recibe HTTP 400 antes de consultar completados, Company o proveedores
- **AND** un Process-Id inválido no se devuelve; si el proceso era válido y falló el tenant se conserva exactamente el proceso aceptado

#### Scenario: Propagación compatible con Party Registry
- **WHEN** Company acepta los tres headers y llama al detalle o identificadores de Party Registry
- **THEN** propaga tenant/proceso canónicos en minúsculas y actor exacto sin generar identificadores nuevos ni modificar su contenido entre micros

#### Scenario: Aplicabilidad uniforme
- **WHEN** se ejecuta un POST inicial, un GET individual, un listado o un replay bajo v1
- **THEN** se aplica la misma validación obligatoria de las tres cabeceras
- **AND** health, métricas y OpenAPI mantienen la excepción operacional existente

### Requirement: Correlación preservada y aislada
La API SHALL propagar el `Process-Id` validado a integraciones y respuestas, incluidos errores posteriores a su validación. El contexto SHALL mantenerse aislado entre solicitudes concurrentes a través de fronteras asíncronas.

#### Scenario: Error después de validar proceso
- **WHEN** el `Process-Id` es válido pero falla otro header o la operación
- **THEN** la respuesta conserva exactamente ese `Process-Id`
- **AND** no contiene datos de correlación de otra solicitud

### Requirement: Respuesta JSON uniforme
Toda respuesta JSON de negocio SHALL usar el contrato compartido con `status`, `code` y `data` cuando corresponda. `status` SHALL coincidir con el estado HTTP. Los datos públicos SHALL ser DTOs de API sin exponer entidades de dominio o persistencia.

#### Scenario: Respuesta exitosa verificable
- **WHEN** una operación de verificación HTTP de pruebas finaliza correctamente
- **THEN** devuelve el envelope compartido, un código estable de éxito y `data` con su DTO
- **AND** la verificación no requiere añadir un endpoint de negocio ficticio a producción

### Requirement: Errores estables y saneados
Los fallos conocidos SHALL traducirse al catálogo público mediante el módulo compartido. La validación SHALL identificar detalles estables por campo: HU03/HU04/HU05 usarán un único code principal descriptivo y las otras operaciones conservarán su contrato de detalle. HU03/HU05 SHALL preservar los códigos y envelopes HU01/HU02/HU04. Fallos inesperados SHALL producir HTTP 500 server-error sin mensajes internos ni trazas.

#### Scenario: Fallo inesperado
- **WHEN** una operación de pruebas genera una excepción con información interna
- **THEN** la respuesta contiene status 500 y code server-error
- **AND** no expone mensaje de excepción, stack trace, SQL o payload de proveedor

#### Scenario: Fallos de framework soportados
- **WHEN** se produce 404, 405, JSON inválido o media type no soportado en la API de negocio
- **THEN** el fallo usa el envelope compartido y el estado HTTP correspondiente

#### Scenario: Causa concreta de HU04 visible al consumidor
- **WHEN** HU04 rechaza una creación porque ya existe HEADQUARTERS no cerrado
- **THEN** el body es `{"status":409,"code":"headquarters-already-exists"}`
- **AND** no sustituye el motivo por resource-conflict ni lo relega a errors[]

#### Scenario: Compatibilidad de operaciones anteriores
- **WHEN** se ejecutan las rutas HU01/HU02 y sus casos de validación/conflicto ya publicados
- **THEN** conservan sus códigos, detalles y envelopes anteriores sin adoptar silenciosamente el catálogo HU04

#### Scenario: Compatibilidad HU04 tras añadir HU05
- **WHEN** se ejecutan registro y consultas Site existentes
- **THEN** conservan códigos, obligatoriedad de headers y envelopes HU04, incluido el código propio de registro bajo Company ARCHIVED

#### Scenario: Compatibilidad después de incorporar HU03
- **WHEN** se ejecutan HU01/HU02/HU04/HU05 después de añadir comandos Company
- **THEN** conservan sus códigos y envelopes actuales, incluidos los rechazos HU02/HU04/HU05 bajo Company ARCHIVED
- **AND** los nuevos conflictos HU03 exponen su causa en code sin convertirla en resource-conflict ni errors[]

### Requirement: Entrada JSON cerrada
La API SHALL rechazar JSON inválido y campos desconocidos. SHALL aplicar normalización canónica de campos de negocio antes de validar longitudes/reglas y antes de unicidad o fingerprinting de operaciones futuras.

#### Scenario: Campo inesperado
- **WHEN** una petición JSON contiene un atributo no admitido por su DTO
- **THEN** devuelve un error estable dentro del envelope y no ejecuta la operación

### Requirement: Contrato común de idempotencia y concurrencia
El OpenAPI inicial SHALL definir `Idempotency-Key` opaca y `If-Match` de versión esperada, indicando por operación su obligatoriedad futura. SHALL documentar replay de resultado original, conflicto HTTP 409 por clave con entrada diferente y HTTP 412 por versión obsoleta.

#### Scenario: Lectura del contrato base
- **WHEN** se revisan los componentes de OpenAPI
- **THEN** la creación Company reserva clave obligatoria, las mutaciones usan versión de su agregado y las lecturas no exigen headers de mutación
- **AND** los contratos planificados no se presentan como workflows ya implementados

### Requirement: Paginación documentada y acotada
El contrato base SHALL definir paginación cursor-based determinista, `limit` acotado y metadatos compatibles con el envelope compartido. SHALL describir validación de parámetros, orden estable, continuidad y aislamiento tenant/filtros, sin implementar listados en T00.

#### Scenario: Consumo del contrato de colección
- **WHEN** un implementador de HU01 o HU04 consulta la documentación
- **THEN** encuentra nombres, límites, orden, estructura de metadatos y tratamiento de cursor inválido explícitos

### Requirement: Endpoints operacionales independientes
Health, métricas y OpenAPI SHALL mantener contratos nativos sin exigir headers de negocio. La configuración de errores de negocio SHALL estar limitada a `/v1`.

#### Scenario: Verificación operacional sin contexto
- **WHEN** se consultan health y OpenAPI sin Tenant-Id, User-Id o Process-Id
- **THEN** responden mediante su protocolo nativo sin rechazo por contexto ni doble envelope

#### Scenario: Contrato empaquetado en ejecutable nativo
- **WHEN** el binario nativo arranca sin acceso al directorio de documentación del repositorio
- **THEN** publica el contrato OpenAPI empaquetado a partir de la fuente versionada
- **AND** health y OpenAPI mantienen su contrato sin headers de negocio

### Requirement: Conflictos HU04 con causa principal descriptiva
Los conflictos conocidos HU04 SHALL devolver HTTP 409 y exactamente status/code en el body. El code SHALL describir la condición concreta mediante el catálogo aprobado, no una categoría genérica ni una causa interna de persistencia. La respuesta SHALL conservar Process-Id válido como header.

#### Scenario: Código ocupado
- **WHEN** se rechaza un registro nuevo por código canónico ocupado en Company
- **THEN** devuelve `site-code-already-exists`

#### Scenario: Sede legal ocupada
- **WHEN** se rechaza un nuevo HEADQUARTERS porque existe otro no cerrado
- **THEN** devuelve `headquarters-already-exists`

#### Scenario: Clave aceptada con otra entrada
- **WHEN** la misma identidad idempotente se usa con otra solicitud efectiva, incluida otra Company
- **THEN** devuelve `idempotency-key-already-used-with-different-request`

#### Scenario: Registro nuevo bajo padre archivado
- **WHEN** una creación nueva se rechaza por Company ARCHIVED
- **THEN** devuelve `site-registration-not-allowed-for-archived-company`

### Requirement: Validación de campos HU04 con HTTP 400
La validación conocida de entrada HU04 SHALL devolver HTTP 400 y un único code principal descriptivo. SHALL usar los límites canónicos de Site y distinguir ausencia/blanco, longitud, tipo JSON y siteType null/no soportado. SHALL NOT convertir estos errores en 422 ni usar validation-error con errors[] para reemplazar su catálogo propio.

#### Scenario: Requeridos y límites canónicos
- **WHEN** code falta/es null/queda blanco o supera 64 puntos de código
- **THEN** devuelve respectivamente `site-code-required` o `site-code-too-long`
- **AND** para name requerido o mayor de 200 devuelve respectivamente `site-name-required` o `site-name-too-long`

#### Scenario: Tipo de sede explícito inválido
- **WHEN** siteType es null o una cadena no admitida
- **THEN** devuelve respectivamente `site-type-null-not-allowed` o `site-type-not-supported`

#### Scenario: Tipo JSON no string
- **WHEN** code, name o siteType contiene un valor JSON no string distinto del null tratado por sus reglas propias
- **THEN** devuelve respectivamente `site-code-must-be-string`, `site-name-must-be-string` o `site-type-must-be-string`

### Requirement: Fallos estructurales HU04 descriptivos
El registro HU04 SHALL distinguir body ausente, JSON malformado, raíz no objeto y propiedades desconocidas/duplicadas mediante códigos HTTP 400 documentados. SHALL rechazar contenido posterior y SHALL NOT exponer valores enviados, mensajes Jackson o nombres internos. Si hay varios errores, SHALL devolver un único motivo según precedencia documentada.

#### Scenario: Ausencia y JSON inválido
- **WHEN** falta el body o el documento está malformado o contiene tokens posteriores
- **THEN** devuelve respectivamente `request-body-required` o `request-body-invalid-json`

#### Scenario: Raíz y propiedades
- **WHEN** la raíz es null/array/escalar, se incluye una propiedad desconocida o se repite una propiedad
- **THEN** devuelve respectivamente `request-body-must-be-object`, `request-body-contains-unknown-fields` o `request-body-contains-duplicate-fields`

#### Scenario: Varias infracciones de campo
- **WHEN** un objeto estructuralmente válido incumple varias reglas de code/name/siteType
- **THEN** devuelve el primer fallo según code, name y siteType, con requerido/tipo antes de longitud en cada campo
- **AND** ese orden no depende del orden de propiedades del JSON

### Requirement: Ámbito y cabeceras HU04 descriptivos
HU04 SHALL usar 404 company-not-found para Company inexistente/ajena y 404 site-not-found para Site ausente/fuera de Company propia. UUIDs de ruta y claves inválidas SHALL producir HTTP 400 específico. El contexto compartido SHALL conservar sus códigos actuales. Los GET SHALL NOT exigir cabeceras de mutación.

#### Scenario: Recurso no resoluble en el ámbito
- **WHEN** Company no se resuelve en el tenant, o Site no se resuelve dentro de Company propia
- **THEN** devuelve respectivamente `company-not-found` o `site-not-found`, sin distinguir existencia ajena

#### Scenario: UUID de ruta inválido
- **WHEN** companyId o siteId es malformado
- **THEN** devuelve respectivamente `company-id-invalid` o `site-id-invalid`

#### Scenario: Idempotency-Key no válida
- **WHEN** falta, se repite, está en blanco o excede 128 puntos de código
- **THEN** devuelve respectivamente `idempotency-key-required`, `idempotency-key-duplicated`, `idempotency-key-blank` o `idempotency-key-too-long`

### Requirement: Fallos de consulta HU04 identificables
El listado Site SHALL rechazar consultas inválidas con HTTP 400 y code descriptivo, sin ampliar el ámbito ni revelar contenido criptográfico. SHALL distinguir parámetros no admitidos/repetidos, filtros inválidos, límite inválido y cursor inválido/incompatible.

#### Scenario: Parámetros no admitidos o repetidos
- **WHEN** aparece un parámetro desconocido o se repite uno admitido
- **THEN** devuelve respectivamente `query-parameter-not-supported` o `query-parameter-repeated`

#### Scenario: Filtros inválidos
- **WHEN** code proporcionado queda blanco o supera su límite, siteType no es admitido o status no es un estado Site declarado
- **THEN** devuelve respectivamente `site-code-required`, `site-code-too-long`, `site-type-not-supported` o `site-status-not-supported`

#### Scenario: Límite inválido
- **WHEN** limit no es un entero decimal admitido o su valor queda fuera de 1–200
- **THEN** devuelve respectivamente `site-page-limit-must-be-an-integer` o `site-page-limit-must-be-between-1-and-200`
- **AND** un entero decimal demasiado grande para la representación interna se rechaza como fuera de rango, no como 500

#### Scenario: Cursor inválido o incompatible
- **WHEN** cursor está blanco, malformado, manipulado, usa formato/clave desconocidos o no corresponde a la colección/tenant/Company/filtros/orden
- **THEN** devuelve `site-cursor-invalid-or-incompatible` sin revelar material interno

### Requirement: Validación descriptiva de cuerpos HU05
Los errores conocidos de body HU05 SHALL devolver 400 y un único code descriptivo. SHALL distinguir ausencia requerida, JSON inválido, raíz no objeto, propiedades desconocidas/duplicadas y fallos de campo. Un comando sin body SHALL ser válido; PATCH sin body SHALL rechazarse. El catálogo SHALL conservar los códigos Site HU04 aplicables.

#### Scenario: Documento y propiedades
- **WHEN** PATCH carece de body, el JSON es inválido/con tokens posteriores, la raíz presente no es objeto, o hay propiedades desconocidas/duplicadas
- **THEN** devuelve respectivamente request-body-required, request-body-invalid-json, request-body-must-be-object, request-body-contains-unknown-fields o request-body-contains-duplicate-fields

#### Scenario: Reglas de campos presentes
- **WHEN** code/name presentes son null/blancos o exceden sus límites canónicos
- **THEN** devuelve site-code-required/site-name-required o site-code-too-long/site-name-too-long según el campo
- **AND** campos omitidos en PATCH no producen errores de requerido

#### Scenario: Tipo JSON o enum inválido
- **WHEN** code, name o siteType presentes tienen tipo no string distinto del null tratado por sus reglas
- **THEN** devuelve site-code-must-be-string, site-name-must-be-string o site-type-must-be-string
- **AND** siteType null devuelve site-type-null-not-allowed y enum inválido site-type-not-supported

#### Scenario: Varias infracciones de cuerpo
- **WHEN** un documento presenta varias infracciones
- **THEN** elige primero JSON inválido/tokens, raíz, desconocidos, duplicados y después code/name/siteType, con requerido/tipo antes de longitud en cada campo
- **AND** el resultado no depende del orden de propiedades ni expone sus valores

### Requirement: Cabeceras y ámbito HU05 identificables
HU05 SHALL conservar los códigos de contexto existentes y usar los códigos Site de UUID/clave aplicables. If-Match ausente, repetido o inválido SHALL devolver 400 con causa propia. La clave idempotente ausente SHALL ser admitida. Company/Site fuera del ámbito SHALL usar 404 no revelador.

#### Scenario: Versión esperada mal formada
- **WHEN** If-Match falta, se repite o no cumple el decimal canónico int64 existente
- **THEN** devuelve respectivamente if-match-required, if-match-duplicated o if-match-invalid

#### Scenario: Ruta o clave inválidas
- **WHEN** companyId/siteId son inválidos o la clave presente es repetida, blanca o excesiva
- **THEN** usa company-id-invalid/site-id-invalid o idempotency-key-duplicated/idempotency-key-blank/idempotency-key-too-long según corresponda

#### Scenario: Resolución fuera del ámbito
- **WHEN** Company no se resuelve en el tenant o Site no se resuelve en Company propia
- **THEN** devuelve respectivamente company-not-found o site-not-found, sin versión ni estado ajenos

### Requirement: Conflictos y fallos HU05 descriptivos
Los errores HU05 conocidos de negocio SHALL devolver exactamente status/code y conservar Process-Id válido. SHALL distinguir versión, estado, sede legal, código y clave idempotente. Indisponibilidad/resultado no resuelto SHALL devolver 503 dependency-unavailable y material histórico inválido 500 server-error saneado.

#### Scenario: Precondición obsoleta
- **WHEN** una aceptación nueva encuentra versión Site distinta de la suministrada
- **THEN** devuelve 412 aggregate-version-mismatch

#### Scenario: Padre archivado y sede cerrada
- **WHEN** falla la admisión por Company ARCHIVED o, con padre editable, por Site CLOSED
- **THEN** devuelve respectivamente 409 site-mutation-not-allowed-for-archived-company o 409 site-closed

#### Scenario: Comando redundante o transición prohibida
- **WHEN** bajo padre/Site editables un comando nuevo apunta al estado actual o intenta otra transición no permitida
- **THEN** devuelve respectivamente 409 site-already-in-requested-state o 409 site-transition-not-allowed

#### Scenario: Protección o duplicación de sede legal
- **WHEN** la operación retiraría la sede legal ACTIVE de Company ACTIVE o crearía otro HEADQUARTERS no cerrado
- **THEN** devuelve respectivamente 409 active-headquarters-required o 409 headquarters-already-exists

#### Scenario: Código o clave ocupados
- **WHEN** otra sede ocupa el código resultante o la clave aceptada corresponde a otra intención
- **THEN** devuelve respectivamente 409 site-code-already-exists o 409 idempotency-key-already-used-with-different-request

#### Scenario: Incertidumbre e histórico inválido
- **WHEN** no se resuelve una aceptación dentro del plazo o falta material histórico necesario
- **THEN** devuelve respectivamente 503 dependency-unavailable o 500 server-error, sin mensajes internos ni escrituras reparadoras

### Requirement: Precedencia de aceptación HU05
HU05 SHALL rechazar entrada de frontera inválida antes de replay. Luego SHALL resolver aceptación/conflicto de clave, pertenencia, versión, estados e invariantes/unicidad, en ese orden. En estados SHALL evaluar Company ARCHIVED antes de Site CLOSED y después elegibilidad del comando. En comprobaciones de unicidad previas SHALL evaluar código antes de otra reserva HEADQUARTERS.

#### Scenario: Entrada inválida con clave aceptada
- **WHEN** una solicitud incluye clave aceptada pero falla cabecera, ruta o estructura/tipo de body
- **THEN** devuelve 400 antes de devolver su snapshot

#### Scenario: Clave incompatible y revisión actual
- **WHEN** una clave aceptada se presenta con otra intención y además la versión actual difiere
- **THEN** devuelve conflicto de clave y no 412

#### Scenario: Pertenencia antes de estado
- **WHEN** Company es propia pero Site está fuera de su ámbito y el padre está ARCHIVED
- **THEN** devuelve site-not-found antes de revelar una decisión de negocio sobre la sede ajena

#### Scenario: Versión antes de terminalidad
- **WHEN** un Site propio CLOSED bajo Company ARCHIVED se solicita con versión obsoleta
- **THEN** devuelve 412
- **AND** con versión vigente devuelve el conflicto de padre archivado antes del conflicto de sede cerrada

### Requirement: Cabeceras y ámbito HU03 identificables
HU03 SHALL conservar códigos de contexto existentes y usar company-id-invalid para UUID de ruta inválido. If-Match ausente/repetido/inválido SHALL devolver 400 con causa propia. La clave será opcional; un valor presente inválido SHALL producir 400 descriptivo. Company ausente/ajena SHALL devolver 404 company-not-found.

#### Scenario: Precondición ausente o mal formada
- **WHEN** If-Match falta, se repite o no es decimal canónico int64 no negativo
- **THEN** devuelve respectivamente if-match-required, if-match-duplicated o if-match-invalid
- **AND** comillas, signos, comodines, ceros iniciales, espacios internos y desbordamiento se rechazan; OWS exterior conserva la convención HTTP existente

#### Scenario: Clave opcional y validación
- **WHEN** Idempotency-Key se omite, se repite, queda blanca o excede 128 puntos de código
- **THEN** omitirla es válido y los otros casos devuelven idempotency-key-duplicated, idempotency-key-blank o idempotency-key-too-long
- **AND** un valor aceptado conserva sus bytes/texto opacos sin normalización de negocio

#### Scenario: Ámbito no resoluble
- **WHEN** Company no pertenece al tenant o no existe
- **THEN** devuelve company-not-found sin versión, estado ni referencia Party ajenos

### Requirement: Cuerpos vacíos HU03 con rechazo descriptivo
HU03 SHALL aceptar body ausente o `{}` y devolver 400 descriptivo ante JSON inválido/tokens posteriores, raíz no objeto o propiedades no admitidas. SHALL preservar el envelope común para 415 y validar entrada antes de replay, sin exponer valores ni mensajes del parser.

#### Scenario: Body inválido
- **WHEN** el documento es inválido/con tokens posteriores, su raíz es null/array/escalar o contiene cualquier propiedad
- **THEN** devuelve respectivamente request-body-invalid-json, request-body-must-be-object o request-body-contains-unknown-fields
- **AND** el orden de fallo estructural es JSON/tokens, raíz y propiedades; una propiedad duplicada tampoco puede aceptarse

#### Scenario: Clave aceptada con entrada inválida
- **WHEN** una clave aceptada se presenta con body o headers inválidos
- **THEN** devuelve el error de frontera antes de exponer el snapshot

### Requirement: Conflictos locales HU03 descriptivos
Los rechazos HU03 conocidos SHALL contener exactamente status/code y conservar Process-Id válido. SHALL distinguir 412 aggregate-version-mismatch de los conflictos 409 de terminalidad, redundancia, transición, matriz e idempotencia. Estos conflictos SHALL NOT consumir clave ni modificar auditoría, revisión o eventos.

#### Scenario: Estados incompatibles
- **WHEN** Company está ARCHIVED, el comando nuevo apunta al estado actual ACTIVE/INACTIVE o se intenta otra transición prohibida, con versión vigente
- **THEN** devuelve respectivamente company-archived, company-already-in-requested-state o company-transition-not-allowed con HTTP 409

#### Scenario: Matriz y clave
- **WHEN** falta matriz legal actual, la matriz actual no está ACTIVE o una clave aceptada corresponde a otra intención
- **THEN** devuelve respectivamente 409 legal-headquarters-required, active-headquarters-required o idempotency-key-already-used-with-different-request

#### Scenario: Versión obsoleta
- **WHEN** un comando propio nuevo encuentra una revisión Company distinta
- **THEN** devuelve 412 aggregate-version-mismatch antes de estados o matriz

### Requirement: Elegibilidad Party y dependencia HU03 identificables
Activación SHALL distinguir ausencia de Party, tipo incompatible, estado no activo, respuesta inválida e indisponibilidad. SHALL devolver un único code descriptivo con envelope compartido sin payloads remotos. La incertidumbre local SHALL usar 503 dependency-unavailable y el histórico ilegible 500 server-error.

#### Scenario: Referencia no elegible
- **WHEN** Party responde ausencia dentro del tenant, NATURAL_PERSON o LEGAL_ENTITY no ACTIVE
- **THEN** devuelve respectivamente 404 party-reference-not-found, 409 party-must-be-legal-entity o 409 party-must-be-active
- **AND** verifica tipo antes de actividad cuando concurren ambas incompatibilidades

#### Scenario: Transporte y contrato
- **WHEN** la consulta Party expira/no está disponible o devuelve una respuesta incompatible
- **THEN** devuelve respectivamente 503 dependency-unavailable o 502 dependency-response-invalid
- **AND** no confunde esos fallos con una referencia inexistente

#### Scenario: Persistencia o material histórico
- **WHEN** no se resuelve el resultado durable o faltan política/clave/snapshot históricos válidos
- **THEN** devuelve respectivamente 503 dependency-unavailable o 500 server-error saneado
- **AND** no reconstruye la aceptación ni ejecuta otra transición

### Requirement: Precedencia de evaluación HU03
HU03 SHALL evaluar entrada, aceptación/conflicto de clave, propiedad, versión y estado, en ese orden. En activación SHALL comprobar después matriz y resultado Party. Las validaciones locales SHALL repetirse bajo protección tras HTTP; un replay surgido durante esa espera SHALL preceder también a un fallo remoto.

#### Scenario: Prioridad de clave y terminalidad
- **WHEN** una clave corresponde a otra intención y Company además avanzó o se archivó
- **THEN** devuelve conflicto de clave; una intención equivalente devuelve su resultado original
- **AND** sin replay, versión obsoleta precede a company-archived y terminalidad precede a redundancia

#### Scenario: Fallo remoto y cambio local concurrentes
- **WHEN** Party falla pero otro comando efectivo cambió Company durante la llamada
- **THEN** al volver a proteger Company resuelve primero replay/conflicto de clave y luego la versión actual
- **AND** devuelve 412 si no hay replay y la versión ya no coincide; aplica el fallo Party solo si las comprobaciones locales siguen siendo válidas

#### Scenario: Fallo local conocido antes de HTTP
- **WHEN** una fase local protegida encuentra Company ausente, revisión obsoleta, transición prohibida o matriz no elegible
- **THEN** rechaza en ese punto sin necesitar una llamada Party
- **AND** una comprobación preliminar exitosa no sustituye la revalidación final antes de aceptar
