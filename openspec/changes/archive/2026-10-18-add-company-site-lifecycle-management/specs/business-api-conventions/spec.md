# Spec Delta

## MODIFIED Requirements

### Requirement: Errores estables y saneados
Los fallos conocidos SHALL traducirse al catálogo público mediante el módulo compartido. La validación SHALL identificar detalles estables por campo: HU04/HU05 usarán un único code principal descriptivo y las otras operaciones conservarán su contrato de detalle. HU05 SHALL preservar los códigos y envelopes HU01/HU02/HU04. Fallos inesperados SHALL producir HTTP 500 server-error sin mensajes internos ni trazas.

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

## ADDED Requirements

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
