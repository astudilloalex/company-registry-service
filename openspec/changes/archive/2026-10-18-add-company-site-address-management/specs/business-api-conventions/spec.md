# Spec Delta

## MODIFIED Requirements

### Requirement: Errores estables y saneados
Los fallos conocidos SHALL traducirse al catálogo público mediante el módulo compartido. La validación SHALL identificar detalles estables por campo: HU03/HU04/HU05/HU06 usarán un único code principal descriptivo y las otras operaciones conservarán su contrato de detalle. HU03/HU05 SHALL preservar los códigos y envelopes HU01/HU02/HU04. Fallos inesperados SHALL producir HTTP 500 server-error sin mensajes internos ni trazas.

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

#### Scenario: Compatibilidad después de incorporar HU06
- **WHEN** se ejecutan las rutas HU01–HU05 después de añadir direcciones
- **THEN** conservan sus códigos, formatos históricos y envelopes publicados
- **AND** HU06 devuelve sus fallos conocidos con exactamente status/code y Process-Id válido como cabecera

## ADDED Requirements

### Requirement: Cabeceras y rutas HU06 identificables
HU06 SHALL conservar la validación de contexto compartida. Las mutaciones SHALL exigir If-Match decimal canónico del Site; POST SHALL exigir clave idempotente y PATCH/DELETE SHALL admitir su omisión. companyId/siteId/addressId malformados SHALL producir 400 con company-id-invalid/site-id-invalid/address-id-invalid. Headers de mutación inválidos SHALL usar los códigos existentes.

#### Scenario: Cabecera obligatoria ausente
- **WHEN** falta If-Match en POST/PATCH/DELETE o Idempotency-Key en POST
- **THEN** devuelve 400 if-match-required o idempotency-key-required según corresponda

#### Scenario: Versión o clave no admitidas
- **WHEN** If-Match está repetido o no cumple el decimal int64 no negativo, o una clave presente está repetida, blanca o excede 128 puntos de código
- **THEN** devuelve if-match-duplicated/if-match-invalid o idempotency-key-duplicated/idempotency-key-blank/idempotency-key-too-long
- **AND** no acepta comillas, comodines, signos, ceros iniciales, espacios internos ni desbordamiento de la revisión

### Requirement: JSON HU06 cerrado y estructuralmente válido
POST/PATCH SHALL exigir un objeto JSON application/json con solo los campos mutables declarados. DELETE SHALL aceptar body ausente o `{}`. SHALL rechazar JSON inválido, contenido posterior, raíz no objeto y propiedades desconocidas/duplicadas con 400 descriptivo. Un documento con varios errores SHALL priorizar JSON/tokens, raíz, desconocidos, duplicados y luego campos en orden documentado.

#### Scenario: Estructura de documento inválida
- **WHEN** falta un body requerido, el JSON es inválido/con tokens posteriores, la raíz no es objeto o hay campos desconocidos/duplicados
- **THEN** devuelve request-body-required, request-body-invalid-json, request-body-must-be-object, request-body-contains-unknown-fields o request-body-contains-duplicate-fields

#### Scenario: Campo gestionado por el servidor
- **WHEN** POST/PATCH incluye addressId, siteId, siteVersion, tenantId, auditoría, metadatos de eliminación u otra propiedad no admitida
- **THEN** devuelve 400 request-body-contains-unknown-fields aunque el valor coincida con el persistido

### Requirement: Validación descriptiva de campos de dirección
Los textos presentes SHALL ser strings sin coerción e isPrimary SHALL ser booleano JSON no nulo. Requeridos ausentes/nulos/blancos, límites canónicos, país inválido y tipo no soportado SHALL producir 400 con un code descriptivo del campo. Los opcionales nulos permitidos SHALL limpiar; blancos SHALL rechazarse. El orden de validación SHALL ser estable e independiente del orden JSON.

#### Scenario: Requeridos y enumeración
- **WHEN** falla un requerido o addressType normalizado no es PHYSICAL/MAILING
- **THEN** devuelve address-type-required, address-country-code-required, address-line1-required o address-type-not-supported según la regla
- **AND** un país no alpha-2 devuelve address-country-code-invalid

#### Scenario: Tipo, blanco y longitud
- **WHEN** falla un texto de campo F con tipo no string, blanco opcional o longitud excesiva
- **THEN** devuelve respectivamente `F-must-be-string`, `F-blank` o `F-too-long`
- **AND** F usa address-type, address-country-code, address-line1, address-administrative-division-code, address-line2, address-house-number o address-postal-code según el campo y las reglas aplicables
- **AND** isPrimary inválido devuelve address-is-primary-must-be-boolean

### Requirement: Errores de coordenadas HU06 descriptivos
Las coordenadas no nulas SHALL ser números JSON exactos sin coerción desde strings. Falta de miembro, mezcla número/null, tipo inválido, rango y precisión no admitidos SHALL producir 400 con códigos propios. SHALL validar el valor recibido antes de cualquier redondeo persistente y mantener el contrato de par indivisible.

#### Scenario: Par incompleto o incompatible
- **WHEN** aparece solo una coordenada o se mezcla un número y null
- **THEN** devuelve address-coordinates-must-be-provided-together o address-coordinates-must-be-both-null-or-numeric respectivamente

#### Scenario: Tipo, rango o precisión de un miembro
- **WHEN** latitude o longitude no nula no es numérica, está fuera de rango o requiere redondeo a seis decimales
- **THEN** devuelve respectivamente `address-latitude-must-be-number`, `address-latitude-out-of-range` o `address-latitude-precision-exceeded`, con códigos equivalentes address-longitude para longitud

### Requirement: Errores de consulta de dirección
El listado SHALL rechazar con 400 parámetros desconocidos/repetidos, filtros inválidos, límite inválido y cursor incompatible. Los filtros SHALL admitir addressType exacto PHYSICAL/MAILING e isPrimary exacto true/false, sin coerciones. SHALL usar códigos descriptivos sin exponer valores o material criptográfico.

#### Scenario: Parámetros y filtros inválidos
- **WHEN** hay parámetros desconocidos/repetidos o valores no admitidos para addressType/isPrimary
- **THEN** devuelve query-parameter-not-supported/query-parameter-repeated o address-type-not-supported/address-is-primary-filter-invalid

#### Scenario: Límite y cursor
- **WHEN** limit no es decimal entero, está fuera de 1–200 o el cursor está blanco, manipulado, malformado o no corresponde al ámbito/filtros
- **THEN** devuelve address-page-limit-must-be-an-integer, address-page-limit-must-be-between-1-and-200 o address-cursor-invalid-or-incompatible
- **AND** un entero desbordado se trata como fuera de rango, sin producir 500

### Requirement: Rechazos locales HU06 con causa principal
HU06 SHALL distinguir 404 de Company/Site/dirección ausentes, 412 aggregate-version-mismatch, 409 de estados y 409 idempotency-key-already-used-with-different-request. Company ARCHIVED SHALL usar site-mutation-not-allowed-for-archived-company; Site CLOSED SHALL usar site-closed. Incertidumbre local SHALL producir 503 dependency-unavailable e histórico inválido 500 server-error saneado.

#### Scenario: Estados terminales
- **WHEN** dirección y revisión son válidas pero Company está ARCHIVED o, con padre editable, Site está CLOSED
- **THEN** devuelve 409 site-mutation-not-allowed-for-archived-company o 409 site-closed respectivamente

#### Scenario: Recurso ausente
- **WHEN** no se resuelve Company, Site o una dirección vigente en el ámbito
- **THEN** devuelve 404 company-not-found, site-not-found o address-not-found sin revelar datos de otros ámbitos

### Requirement: Rechazos de referencias geográficas HU06
La validación SHALL distinguir país ausente con 404 country-reference-not-found y división ausente dentro del país con 404 administrative-division-reference-not-found. Una referencia conocida no ACTIVE SHALL producir 409 country-must-be-active o administrative-division-must-be-active. SHALL comprobar elegibilidad del país antes de la división.

#### Scenario: Referencia ausente o no activa
- **WHEN** el proveedor confirma ausencia o un estado DRAFT/DEPRECATED/RETIRED
- **THEN** devuelve el code de ausencia o actividad correspondiente sin consumir aceptación ni cambiar datos

### Requirement: Fallos geográficos de transporte y contrato
Timeout/indisponibilidad geográfica SHALL producir 503 dependency-unavailable; una respuesta incompatible SHALL producir 502 dependency-response-invalid. La ausencia confirmada SHALL NOT confundirse con fallo de transporte. La respuesta SHALL excluir payloads y mensajes internos del proveedor.

#### Scenario: División devuelta bajo otro país
- **WHEN** una consulta calificada por país/código recibe 200 con otro countryId o canonicalCode
- **THEN** devuelve 502 dependency-response-invalid, mientras un 404 válido de esa consulta representa ausencia de división en el país solicitado

#### Scenario: Proveedor inaccesible
- **WHEN** la validación obligatoria expira o recibe indisponibilidad
- **THEN** devuelve 503 sin presentarla como dirección válida ni referencia inexistente

### Requirement: Precedencia de aceptación HU06
HU06 SHALL evaluar entrada, aceptación/conflicto idempotente, pertenencia/ausencia de Company/Site/dirección vigente, revisión Site, estados/reglas locales y geografía, en ese orden. Company ARCHIVED SHALL preceder a Site CLOSED. Una aceptación válida SHALL devolver su snapshot antes del estado actual; una dirección eliminada SHALL contar como ausente antes de evaluar If-Match.

#### Scenario: Ausencia frente a revisión y estado
- **WHEN** una mutación nueva apunta a una dirección eliminada con versión obsoleta o padre terminal
- **THEN** devuelve 404 address-not-found
- **AND** una dirección existente con versión obsoleta devuelve 412 antes del conflicto de estado

#### Scenario: Clave incompatible o entrada inválida
- **WHEN** una solicitud con clave aceptada cambia intención o tiene entrada de frontera inválida
- **THEN** la intención diferente devuelve 409 antes del estado actual y la entrada inválida devuelve 400 antes del replay

### Requirement: Revalidación local después de Geografía
Si una operación requiere validación remota, SHALL repetir las comprobaciones locales protegidas antes de aceptar o aplicar su resultado. Un replay concurrente SHALL preceder también a un fallo geográfico. Sin replay, ausencia, revisión y estado actuales SHALL conservar su precedencia; la preparación previa SHALL NOT autorizar escrituras sobre una revisión que cambió durante HTTP.

#### Scenario: Eliminación o cambio durante el proveedor
- **WHEN** durante Geografía otro comando elimina la dirección o avanza Site sin eliminarla
- **THEN** al volver devuelve respectivamente 404 o 412 antes de aplicar el resultado remoto
- **AND** si confirmó la misma aceptación equivalente devuelve el snapshot original

### Requirement: Publicación contractual HU06 verificable
OpenAPI SHALL describir las cinco rutas, headers por operación, cuerpos cerrados, respuestas de dirección/comprobante, paginación, errores y ejemplos canónicos. El contrato SHALL empaquetarse para JVM/nativo y preservar las APIs previas. La verificación SHALL cubrir el ejecutable real sin incorporar stubs ni rutas de prueba a producción.

#### Scenario: Consumidor del contrato publicado
- **WHEN** consulta el OpenAPI del proceso empaquetado o nativo
- **THEN** distingue siteVersion de una revisión propia, DELETE 200, replay histórico, límites y errores HU06
- **AND** health/OpenAPI siguen disponibles sin contexto de negocio ni conectividad de proveedores
