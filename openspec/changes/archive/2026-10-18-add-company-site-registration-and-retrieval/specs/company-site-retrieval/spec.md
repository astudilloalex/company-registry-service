# Spec Delta

## Purpose

Consultar sedes e historial de una Company dentro de su tenant con una proyección local uniforme, operatividad derivada y navegación filtrada determinista hacia adelante.

## ADDED Requirements

### Requirement: Contexto obligatorio y consulta local de Site
Los GET individual y de colección SHALL exigir Tenant-Id/User-Id/Process-Id válidos y SHALL funcionar sin Idempotency-Key ni If-Match. SHALL resolver los datos localmente sin invocar Party Registry, Geographic Reference ni RabbitMQ, preservando el Process-Id actual en la respuesta.

#### Scenario: Consulta independiente de proveedores
- **WHEN** el contexto es válido, la base local está disponible y los proveedores externos están caídos
- **THEN** la consulta propia puede completar sin llamadas externas ni cabeceras de mutación

#### Scenario: Contexto inválido
- **WHEN** falta, se repite o es inválido un header obligatorio
- **THEN** recibe HTTP 400 con el code descriptivo del contexto y no devuelve datos

### Requirement: Representación pública uniforme de Site
POST, replay, GET individual y elementos del listado SHALL usar un DTO con exactamente siteId, companyId, code, name, siteType, status, version, effectivelyOperational, createdAt, createdBy, updatedAt y updatedBy. SHALL usar el envelope compartido y SHALL NOT incluir Party, tenantId, direcciones o contactos.

#### Scenario: Estructura y tipos de la respuesta
- **WHEN** se devuelve una sede por creación, replay o consulta
- **THEN** data contiene los doce campos públicos, UUIDs como strings, version como entero, effectivelyOperational como booleano y fechas UTC con precisión de microsegundos
- **AND** los actores conservan su contenido exacto y la lectura no modifica auditoría ni versión

#### Scenario: Colección homogénea
- **WHEN** se devuelve una página con varias sedes
- **THEN** cada elemento de data tiene el mismo contrato público que el GET individual

### Requirement: Resolución del padre dentro del tenant
Un GET individual o listado SHALL exigir que Company exista dentro del tenant solicitado. Company inexistente o ajena SHALL producir el mismo HTTP 404 `company-not-found`. Una Company propia sin sedes o sin coincidencias SHALL producir HTTP 200 con data como array vacío.

#### Scenario: Padre inexistente o de otro tenant
- **WHEN** la ruta contiene una Company que no puede resolverse en el tenant actual
- **THEN** tanto GET individual como listado devuelven `company-not-found` sin revelar existencia en otro tenant

#### Scenario: Company propia sin coincidencias
- **WHEN** Company es propia y ninguna sede coincide con la colección o filtros solicitados
- **THEN** devuelve `200 successful`, `data: []` y `numberOfElements: 0`, sin nextCursor

### Requirement: Site calificado por tenant y Company
`GET /v1/companies/{companyId}/sites/{siteId}` SHALL devolver la sede actual solo si coinciden tenant, Company y Site. Tras resolver Company propia, un Site inexistente, de otra Company o de otro tenant SHALL producir el mismo HTTP 404 `site-not-found`.

#### Scenario: Consulta propia
- **WHEN** la sede pertenece a la Company y tenant indicados
- **THEN** devuelve HTTP 200 `successful` con su información actual

#### Scenario: Site ausente o fuera del ámbito
- **WHEN** Company es propia pero Site no existe dentro de esa Company y tenant
- **THEN** devuelve `site-not-found` sin buscar ni exponer su propietario global

#### Scenario: Identificador malformado
- **WHEN** companyId o siteId no tiene formato UUID admitido
- **THEN** recibe HTTP 400 con el code descriptivo del identificador, sin transformarlo en 404 o 500

### Requirement: Historial visible sin exclusiones implícitas
Las consultas SHALL permitir Company ARCHIVED y Site CLOSED. Sin filtro status, el listado SHALL incluir DRAFT, ACTIVE, INACTIVE y CLOSED. SHALL conservar el status propio de Site aunque Company cambie de estado, sin sincronizar estados ni filtrar implícitamente registros históricos.

#### Scenario: Sede cerrada y padre archivado
- **WHEN** se consulta una sede CLOSED o una sede de Company ARCHIVED
- **THEN** devuelve sus datos históricos actuales y effectivelyOperational false
- **AND** no aplica la prohibición de nuevas creaciones a la lectura

#### Scenario: Listado completo por defecto
- **WHEN** Company tiene sedes en los cuatro estados y no se proporciona status
- **THEN** todas participan en la colección paginada según su orden y los demás filtros

### Requirement: Operatividad efectiva calculada al consultar
effectivelyOperational SHALL ser true exactamente cuando Company y Site del ámbito consultado estén ACTIVE. SHALL calcularse desde sus estados locales actuales y SHALL NOT persistirse como columna mutable ni modificar Site al cambiar Company.

#### Scenario: Combinaciones de estados
- **WHEN** Company/Site están ACTIVE/ACTIVE
- **THEN** la respuesta calcula effectivelyOperational true
- **AND** cualquier otra combinación calcula false conservando ambos estados originales

#### Scenario: Filtro de estado propio
- **WHEN** se solicita `status=ACTIVE` bajo Company DRAFT, INACTIVE o ARCHIVED
- **THEN** puede devolver sedes con status ACTIVE y effectivelyOperational false
- **AND** el filtro no se interpreta como operatividad efectiva ni agrega otra condición implícita

#### Scenario: Consulta posterior frente a replay
- **WHEN** después de activar una sede y Company se consulta su GET y se repite su creación aceptada
- **THEN** el GET muestra estado y operatividad actuales mientras el replay mantiene DRAFT/version 0/false originales

### Requirement: Filtros exactos y parámetros cerrados
El listado SHALL admitir únicamente code, siteType, status, cursor y limit, una vez cada uno. Los filtros SHALL combinarse con AND dentro del tenant/Company; code SHALL usar la canonización y límite de registro. siteType y status SHALL ser enums exactos admitidos. Parámetros desconocidos, repetidos, blancos o inválidos SHALL producir HTTP 400 descriptivo.

#### Scenario: Código canónico y filtros combinados
- **WHEN** se filtra con un código equivalente por canonización y tipo/estado válidos
- **THEN** devuelve solo las sedes que cumplen todas las condiciones dentro de Company
- **AND** el código corresponde a como máximo una sede por su unicidad

#### Scenario: Filtros sin coincidencia conjunta
- **WHEN** los filtros válidos no coinciden todos con una misma sede de Company propia
- **THEN** devuelve 200 con data vacío sin ampliar la búsqueda

#### Scenario: Parámetro inválido o no admitido
- **WHEN** se repite code, se usa un parámetro ajeno, se envía un filtro blanco o un enum no soportado
- **THEN** devuelve HTTP 400 con un code concreto y no ignora ni normaliza silenciosamente la condición

### Requirement: Paginación determinista y acotada de sedes
La colección SHALL ordenar por created_at DESC e id DESC, con limit predeterminado 50 y rango inclusivo 1–200. Cada página SHALL conservar la frontera completa de fecha e ID. La navegación SHALL ser hacia adelante sobre datos actuales, sin prometer un snapshot congelado entre peticiones.

#### Scenario: Desempate por identificador
- **WHEN** varias sedes comparten createdAt y se solicita sucesivamente cada página de un conjunto estable
- **THEN** el desempate por ID evita repeticiones u omisiones y respeta el límite

#### Scenario: Límites de página
- **WHEN** limit se omite, vale 1 o vale 200
- **THEN** aplica respectivamente 50, 1 o 200
- **AND** 0, 201, valores no enteros o desbordados producen HTTP 400 descriptivo

#### Scenario: Cambios entre páginas
- **WHEN** se insertan o cambian sedes entre peticiones
- **THEN** la continuación respeta su frontera y los filtros actuales sin afirmar una colección histórica congelada

### Requirement: Cursor autenticado y exclusivo de la colección Site
El cursor SHALL estar protegido contra alteraciones y ligado a su formato, colección Site, tenant, Company, filtros efectivos y orden. SHALL NOT servir como autorización ni intercambiarse entre Companies, tenants o listados Company/Site. Un cursor malformado, manipulado, desconocido o incompatible SHALL producir HTTP 400 con code descriptivo.

#### Scenario: Cursor de otro ámbito o colección
- **WHEN** se reutiliza un cursor de otro tenant/Company, del listado de Companies o con filtros diferentes
- **THEN** se rechaza sin devolver datos fuera del ámbito

#### Scenario: Filtros equivalentes
- **WHEN** el código de filtro solo cambia por canonización equivalente
- **THEN** conserva el mismo ámbito efectivo de cursor
- **AND** cambiar actor o Process-Id válidos no invalida su navegación

#### Scenario: Integridad o formato inválido
- **WHEN** se altera el cursor o se usa una versión o clave de firma no disponible
- **THEN** recibe HTTP 400 sin exponer material criptográfico ni contenido interno del cursor

### Requirement: Metadatos de avance en el envelope compartido
El listado SHALL devolver data como array y numberOfElements igual al tamaño de la página en el nivel superior. SHALL incluir nextCursor solo si hay continuación. SHALL omitir prevCursor, totalElements y totalPages y SHALL NOT introducir un objeto pagination anidado.

#### Scenario: Página intermedia o final
- **WHEN** quedan resultados después de la página seleccionada
- **THEN** devuelve nextCursor y numberOfElements correcto
- **AND** en la última página o colección vacía omite nextCursor y los demás metadatos no contratados
