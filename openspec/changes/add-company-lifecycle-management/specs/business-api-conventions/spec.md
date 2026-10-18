# Spec Delta

## MODIFIED Requirements

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

## ADDED Requirements

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
