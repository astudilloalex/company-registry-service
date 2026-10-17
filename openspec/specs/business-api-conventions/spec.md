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
Los fallos conocidos SHALL traducirse al catálogo público del servicio mediante el módulo compartido. Errores de validación SHALL conservar detalles estables por campo. Fallos inesperados SHALL producir HTTP 500 con `server-error` sin mensajes internos ni trazas.

#### Scenario: Fallo inesperado
- **WHEN** una operación de pruebas genera una excepción con información interna
- **THEN** la respuesta contiene `status: 500` y `code: server-error`
- **AND** no expone mensaje de excepción, stack trace, SQL o payload de proveedor

#### Scenario: Fallos de framework soportados
- **WHEN** se produce 404, 405, JSON inválido o media type no soportado en la API de negocio
- **THEN** el fallo usa el envelope compartido y el estado HTTP correspondiente

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
