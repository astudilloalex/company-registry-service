# Spec Delta

## MODIFIED Requirements

### Requirement: Errores estables y saneados
Los fallos conocidos SHALL traducirse al catálogo público mediante el módulo compartido. La validación SHALL identificar detalles estables por campo: HU04 usará un único code principal descriptivo y las otras operaciones conservarán su contrato de detalle. HU04 SHALL preservar los códigos y envelopes HU01/HU02. Fallos inesperados SHALL producir HTTP 500 `server-error` sin mensajes internos ni trazas.

#### Scenario: Fallo inesperado
- **WHEN** una operación de pruebas genera una excepción con información interna
- **THEN** la respuesta contiene `status: 500` y `code: server-error`
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

## ADDED Requirements

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
