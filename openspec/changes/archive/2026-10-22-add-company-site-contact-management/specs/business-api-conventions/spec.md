# Spec Delta

## MODIFIED Requirements

### Requirement: Errores estables y saneados
Los fallos conocidos SHALL traducirse al catálogo público mediante el módulo compartido. La validación SHALL identificar detalles estables por campo: HU03/HU04/HU05/HU06/HU07 usarán un único code principal descriptivo y las otras operaciones conservarán su contrato de detalle. HU03/HU05 SHALL preservar los códigos y envelopes HU01/HU02/HU04. Fallos inesperados SHALL producir HTTP 500 server-error sin mensajes internos ni trazas.

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

#### Scenario: Compatibilidad después de incorporar HU07
- **WHEN** se ejecutan HU01–HU06 después de añadir contactos
- **THEN** conservan sus códigos, envelopes y material histórico de replay
- **AND** HU07 devuelve solo status/code en errores y conserva Process-Id válido como cabecera, sin emails, teléfonos o label

## ADDED Requirements

### Requirement: Cabeceras y rutas HU07 identificables
HU07 SHALL conservar el contexto compartido y exigir If-Match decimal canónico int64 no negativo de Site en POST/PATCH/DELETE. POST SHALL exigir clave idempotente; PATCH/DELETE SHALL admitir su omisión. Company/Site/Contact UUID de ruta inválidos SHALL producir 400 con company-id-invalid/site-id-invalid/contact-id-invalid. GET SHALL NOT exigir headers de mutación.

#### Scenario: Cabecera obligatoria ausente
- **WHEN** falta If-Match en una mutación o Idempotency-Key en POST
- **THEN** devuelve 400 if-match-required o idempotency-key-required

#### Scenario: Versión o clave inválidas
- **WHEN** If-Match está repetido, fuera de int64 o no es decimal canónico, o la clave presente está repetida, blanca o supera 128 puntos de código
- **THEN** usa if-match-duplicated/if-match-invalid o idempotency-key-duplicated/idempotency-key-blank/idempotency-key-too-long
- **AND** no admite comillas, comodines, signos, espacios o ceros iniciales en la revisión

### Requirement: JSON HU07 cerrado y estructuralmente válido
POST/PATCH SHALL exigir un objeto JSON application/json con solo contactType, value, label e isPrimary. DELETE SHALL aceptar body ausente o `{}`. SHALL rechazar estructura inválida, tokens posteriores, raíz no objeto y propiedades desconocidas/duplicadas. La precedencia SHALL ser JSON/tokens, raíz, desconocidos, duplicados, tipos/requeridos y después canonización, con orden contactType/value/label/isPrimary dentro de cada fase de campos.

#### Scenario: Documento inválido
- **WHEN** falta el body requerido, el JSON es inválido, la raíz no es objeto o hay propiedades desconocidas/duplicadas
- **THEN** devuelve respectivamente request-body-required, request-body-invalid-json, request-body-must-be-object, request-body-contains-unknown-fields o request-body-contains-duplicate-fields

#### Scenario: Propiedades gestionadas por el servidor
- **WHEN** el body incluye contactId, tenantId, siteId, siteVersion, auditoría, borrado u otra propiedad no admitida
- **THEN** devuelve 400 request-body-contains-unknown-fields aunque coincida con el valor actual

### Requirement: Errores de campos de contacto
Los textos presentes SHALL ser strings sin coerción. contactType/value requeridos ausentes, nulos o blancos SHALL producir contact-type-required/contact-value-required. Tipo no string SHALL producir contact-type-must-be-string/contact-value-must-be-string/contact-label-must-be-string. Tipo no soportado SHALL producir contact-type-not-supported; label blanco/excesivo, contact-label-blank/contact-label-too-long.

#### Scenario: Nulos y tipo primitivo
- **WHEN** PATCH envía label null, value null o isPrimary no booleano JSON no nulo
- **THEN** respectivamente limpia label, rechaza con contact-value-required o rechaza con contact-is-primary-must-be-boolean

#### Scenario: Error de varios campos
- **WHEN** aparecen varias infracciones de campo en el mismo documento
- **THEN** evalúa primero tipos/requeridos de todos los campos y después su canonización, manteniendo contactType/value/label/isPrimary en cada fase
- **AND** el resultado es independiente del orden de propiedades JSON

### Requirement: Errores de validación especializada de contacto
EMAIL fuera del perfil ASCII o sintaxis, teléfono inválido e incompatibilidad entre familia y tipo efectivo SHALL producir 400 contact-value-invalid. Exceder límites de longitud EMAIL SHALL producir contact-value-too-long. Un teléfono válido exclusivamente fijo u otra clasificación no móvil bajo MOBILE SHALL producir contact-mobile-number-required. SHALL omitir el valor presentado del error.

#### Scenario: Email o teléfono no admisible
- **WHEN** se presenta EMAIL no ASCII, dirección con sintaxis no admitida, número nacional o extensión
- **THEN** devuelve contact-value-invalid

#### Scenario: Longitud o clasificación
- **WHEN** un EMAIL ASCII sintácticamente válido excede 254 caracteres totales o 64 locales, o un teléfono válido no satisface la clasificación MOBILE
- **THEN** devuelve respectivamente contact-value-too-long o contact-mobile-number-required

### Requirement: Errores de consulta de contactos
El listado SHALL rechazar con 400 parámetros desconocidos/repetidos, filtros no exactos, límite no entero/fuera de 1–200 y cursor inválido/incompatible. SHALL usar códigos descriptivos estables sin exponer valores o material criptográfico. contactType de consulta SHALL exigir EMAIL/PHONE/MOBILE/WHATSAPP exacto e isPrimary true/false exacto.

#### Scenario: Parámetros y filtros
- **WHEN** hay parámetros desconocidos/repetidos o contactType/isPrimary de consulta inválidos
- **THEN** devuelve query-parameter-not-supported/query-parameter-repeated o contact-type-not-supported/contact-is-primary-filter-invalid

#### Scenario: Límite y cursor
- **WHEN** limit no es entero, está fuera de rango o el cursor no corresponde al ámbito, colección, filtros o formato
- **THEN** devuelve contact-page-limit-must-be-an-integer, contact-page-limit-must-be-between-1-and-200 o contact-cursor-invalid-or-incompatible
- **AND** un entero desbordado se trata como fuera de rango, no como 500

### Requirement: Rechazos locales HU07 con causa principal
HU07 SHALL distinguir 404 company-not-found/site-not-found/contact-not-found, 412 aggregate-version-mismatch y 409 por Company archivada, Site cerrado, valor duplicado o clave incompatible. SHALL usar site-mutation-not-allowed-for-archived-company, site-closed, contact-value-already-exists e idempotency-key-already-used-with-different-request. Incertidumbre SHALL producir 503 dependency-unavailable e histórico inválido 500 server-error.

#### Scenario: Valor ocupado
- **WHEN** crear, actualizar o reclasificar produciría un duplicado vigente del mismo Site y tipo
- **THEN** devuelve 409 contact-value-already-exists sin revelar la identidad o contenido del otro contacto

#### Scenario: Estado terminal
- **WHEN** contacto y revisión son válidos pero Company está ARCHIVED o, con padre editable, Site CLOSED
- **THEN** devuelve respectivamente 409 site-mutation-not-allowed-for-archived-company o site-closed

### Requirement: Precedencia de aceptación HU07
HU07 SHALL validar headers/rutas, estructura y tipos/requeridos antes de replay. La intención SHALL canonizarse con la política histórica para replay o vigente para un intento nuevo. Después SHALL resolver replay/conflicto, pertenencia/ausencia, revisión Site, Company ARCHIVED, Site CLOSED y reglas del contacto resultante, en ese orden.

#### Scenario: Recurso eliminado y revisión obsoleta
- **WHEN** una mutación nueva apunta a contacto eliminado con revisión obsoleta y padre terminal
- **THEN** devuelve 404 contact-not-found; si existe el contacto, la revisión obsoleta produce 412 antes del estado

#### Scenario: Clave incompatible o entrada inválida
- **WHEN** una clave aceptada acompaña otra intención o una estructura JSON inválida
- **THEN** la intención diferente devuelve 409 antes del estado actual y la estructura inválida devuelve 400 antes del replay

#### Scenario: Validación dependiente del tipo conservado
- **WHEN** PATCH omite contactType y cambia value a una familia incompatible con el contacto existente
- **THEN** valida esa incompatibilidad tras pertenencia, revisión y estado, sin leer filas actuales para comparar un replay

### Requirement: Publicación contractual HU07 verificable
OpenAPI SHALL describir las cinco rutas, headers por operación, cuerpos cerrados, respuestas, comprobante DELETE 200, paginación, errores y ejemplos canónicos. SHALL documentar la decisión de EMAIL íntegramente en minúsculas y ASCII. El contrato SHALL empaquetarse para JVM/nativo, conservar APIs anteriores y verificarse contra el proceso real sin stubs de producción.

#### Scenario: Consumidor del contrato publicado
- **WHEN** consulta el OpenAPI del proceso empaquetado o nativo
- **THEN** puede distinguir siteVersion compartida, límites ASCII, teléfonos flexibles, selección primaria y replay histórico
- **AND** health/OpenAPI siguen disponibles sin contexto de negocio ni conectividad externa
