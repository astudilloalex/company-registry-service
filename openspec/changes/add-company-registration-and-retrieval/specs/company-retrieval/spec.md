# Spec Delta

## Purpose

Consultar compañías del tenant mediante una representación operacional uniforme, enriquecida desde Party Registry, y navegar colecciones mediante filtros exactos y cursores deterministas.

## ADDED Requirements

### Requirement: Contexto obligatorio en todas las consultas
Los GET individuales y de colección SHALL exigir exactamente un `Tenant-Id`, `User-Id` y `Process-Id` válidos, con las mismas reglas de contexto de la API de negocio. SHALL preservar Process-Id en respuestas y propagar los tres headers a cada llamada a Party Registry. Las lecturas SHALL funcionar sin Idempotency-Key ni If-Match.

#### Scenario: Consulta sin contexto o con contexto repetido
- **WHEN** falta, se repite o es inválida alguna de las tres cabeceras en un GET
- **THEN** responde HTTP 400 antes de consultar o devolver datos
- **AND** no aplica la excepción de rutas operacionales a los endpoints comerciales

#### Scenario: Correlación de lectura compuesta
- **WHEN** la consulta obtiene detalle Party y una o más páginas de identificadores
- **THEN** todas las llamadas usan tenant, actor y proceso de esa petición, sin generar otro Process-Id ni reutilizar contexto de una petición concurrente

### Requirement: Representación uniforme de Company
Creación, GET individual y cada elemento del listado SHALL usar el mismo DTO público: `companyId`, `partyId`, `code`, `operatingName`, `status`, `version`, los cuatro campos de auditoría y `party`, más `description` y `logoUrl` cuando tengan valor. SHALL usar el envelope compartido y omitir opcionales nulos de la representación HTTP.

#### Scenario: Registro y consulta consistentes
- **WHEN** se registra o consulta una Company
- **THEN** sus datos están en `data`, con `status` HTTP y `code: successful` en el envelope
- **AND** los campos de negocio son canónicos; nombres legales, estado y versión de Party mantienen sus nombres y valores del proveedor

#### Scenario: Auditoría obligatoria
- **WHEN** se devuelve una Company individual o un elemento de colección
- **THEN** incluye `createdAt`, `createdBy`, `updatedAt` y `updatedBy`, con fechas UTC/microsegundos y actores exactos
- **AND** la lectura no cambia esos campos ni la versión

### Requirement: Detalle público de Party incluido
`party` SHALL incluir `partyId`, `type`, `displayName`, `recordStatus`, `version`, `createdAt`, `updatedAt`, `createdBy`, `updatedBy`, `legalEntityDetails` e `identifiers`. SHALL obtenerlos de los contratos públicos de Party Registry y mantener igualdad entre `data.partyId` y `data.party.partyId`, sin inventar campos del proveedor.

#### Scenario: Detalle de persona jurídica
- **WHEN** el proveedor devuelve una Party LEGAL_ENTITY resoluble para la Company del tenant
- **THEN** `legalEntityDetails` incluye `legalName` e `incorporationCountryCode`, y conserva `tradeName`, `legalFormCode`, `incorporatedOn` y `dissolvedOn` cuando tengan valor
- **AND** no agrega `naturalPersonDetails` ni un tenantId inexistente en el contrato de Party

#### Scenario: Cambios de Party después del registro
- **WHEN** cambia el estado, versión, detalle legal o auditoría de Party después de crear Company
- **THEN** un GET normal muestra la proyección obtenida actualmente, incluyendo ARCHIVED si ese es su estado
- **AND** no reaplica la elegibilidad de nueva creación ni sincroniza el estado o versión de Company

### Requirement: Colección de identificadores dentro de Party
`party.identifiers` SHALL ser un array de las proyecciones públicas de identificadores de Party Registry, sin truncar silenciosamente la colección paginada del proveedor ni descartar estados mediante filtros locales implícitos. Una colección válida sin registros SHALL representarse como `[]`.

#### Scenario: Proyección de cada identificador
- **WHEN** se incorpora un identificador del proveedor a la respuesta
- **THEN** conserva `identifierId`, `partyId`, `identifierSchemeId`, `schemeCode`, `maskedValue`, `status`, `isPrimary`, `version`, `createdAt` y `updatedAt`
- **AND** conserva `issuerCode`, `issuedOn`, `expiresOn`, `verifiedAt` y `verifiedBy` cuando tengan valor
- **AND** no obtiene ni expone valores legales completos, normalizados, cifrados o material criptográfico

#### Scenario: Identificadores en varias páginas
- **WHEN** Party Registry devuelve un cursor siguiente para la colección sin filtros de estado
- **THEN** se sigue su navegación contractual hasta completar la colección o producir un fallo controlado dentro del plazo configurado
- **AND** cada identificador corresponde a la Party solicitada y se preserva el orden del proveedor sin confundir una primera página vacía o parcial con colección completa

### Requirement: Consulta por identificador técnico con aislamiento tenant
`GET /v1/companies/{companyId}` SHALL devolver HTTP 200 con la representación actual enriquecida únicamente cuando la Company pertenece al tenant actual. Un ID válido inexistente o de otro tenant SHALL producir el mismo HTTP 404, sin consultar Party para una Company no autorizada por el ámbito local.

#### Scenario: Company propia
- **WHEN** existe una Company con el ID solicitado dentro del tenant
- **THEN** devuelve su información operacional, estado, versión, auditoría y enriquecimiento actuales

#### Scenario: Company ausente o ajena
- **WHEN** el ID no existe en el tenant, aunque exista en otro
- **THEN** devuelve el mismo envelope y código de no encontrado, sin exponer datos ni existencia ajena

#### Scenario: UUID malformado
- **WHEN** el path no contiene un UUID válido conforme al contrato documentado
- **THEN** recibe HTTP 400 saneado y no se convierte en una búsqueda global o en 500

### Requirement: Filtros exactos de colección
`GET /v1/companies` SHALL admitir únicamente `code`, `partyId`, `status`, `cursor` y `limit`, una vez cada uno. Los filtros SHALL combinarse mediante AND y evaluarse siempre dentro del tenant. `code` SHALL usar la misma canonización de creación; Party será UUID válido y status un estado Company declarado.

#### Scenario: Búsqueda por código o Party
- **WHEN** se filtra por código canónico o Party existente en el tenant
- **THEN** devuelve una colección de como máximo una Company por la unicidad del contrato
- **AND** una búsqueda sin coincidencias devuelve HTTP 200 con `data: []`, no un 404 ni datos de otro tenant

#### Scenario: Filtros combinados
- **WHEN** code, partyId y status válidos no coinciden todos con una misma Company
- **THEN** la colección está vacía sin ampliar la búsqueda ni consultar al proveedor para validar la existencia de un filtro Party

#### Scenario: Parámetros desconocidos, repetidos o inválidos
- **WHEN** un parámetro es desconocido, repetido, está en blanco o tiene UUID/estado/límite inválido
- **THEN** devuelve HTTP 400 sin ignorar silenciosamente la condición ni ampliar el conjunto de resultados

### Requirement: Paginación determinista y acotada
La colección SHALL usar `limit` de 1 a 200, por defecto 50, y orden `created_at DESC, id DESC`. El cursor opaco SHALL estar protegido contra alteraciones y ligado a tenant, filtros efectivos y orden. SHALL devolver `nextCursor` cuando haya continuación y `numberOfElements` en el nivel superior del envelope compartido.

#### Scenario: Continuación con fechas iguales
- **WHEN** varias Companies comparten createdAt y se solicita la página siguiente
- **THEN** el desempate por ID conserva el orden y no repite ni omite filas de un conjunto estable por usar únicamente la fecha como frontera

#### Scenario: Cursor ajeno o incompatible
- **WHEN** un cursor está manipulado, malformado, usa formato/clave desconocidos, pertenece a otro tenant o cambia los filtros u orden
- **THEN** responde HTTP 400 sin divulgar su contenido ni reutilizarlo como autorización

#### Scenario: Metadatos compartidos y conjunto vacío
- **WHEN** se devuelve una colección vacía o su última página
- **THEN** `data` sigue siendo un array y numberOfElements coincide con su tamaño
- **AND** los metadatos opcionales nulos se omiten y no se introduce un objeto pagination anidado

#### Scenario: Navegación bajo cambios concurrentes
- **WHEN** se insertan o modifican Companies entre peticiones de páginas
- **THEN** se conserva el orden y la frontera del cursor sin prometer un snapshot congelado entre peticiones

### Requirement: Fallos explícitos de enriquecimiento
Una respuesta exitosa SHALL contener todo el enriquecimiento requerido. Timeout/indisponibilidad SHALL producir 503; una respuesta de proveedor incompatible o una referencia ya persistida que no puede resolverse en su tenant SHALL producir 502. SHALL evitar respuestas 200 parciales, objetos Party vacíos o arrays vacíos inventados para ocultar fallos.

#### Scenario: Proveedor no disponible en GET
- **WHEN** una Company propia existe pero falla una llamada necesaria de Party o identificadores
- **THEN** devuelve el error de dependencia saneado y el Process-Id de la petición
- **AND** no modifica Company, completados, auditoría ni outbox

#### Scenario: Listado enriquecido dentro de límites de I/O
- **WHEN** una página contiene varias Companies con colecciones de identificadores extensas
- **THEN** la composición aplica concurrencia y plazo global finitos configurables y falla explícitamente si no puede completarse
- **AND** no presenta una colección parcial como completa ni promete una transacción distribuida entre Company y Party Registry
