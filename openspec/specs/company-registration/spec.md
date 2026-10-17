# company-registration Specification

## Purpose

Registrar compañías operacionales por tenant con identidad legal externa validada, auditoría coherente, idempotencia durable y recuperación verificable de solicitudes concurrentes.

## Requirements

### Requirement: Contexto y clave obligatorios para registrar
`POST /v1/companies` SHALL exigir exactamente un `Tenant-Id`, `User-Id`, `Process-Id` e `Idempotency-Key` válidos antes de ejecutar el registro o devolver un replay. La clave SHALL ser opaca, no vacía y de hasta 128 puntos de código, preservada exactamente. La identidad idempotente SHALL ser `(tenant, company.create.v1, key)`.

#### Scenario: Header obligatorio ausente, repetido o inválido
- **WHEN** una petición inicial o de replay incumple la validación de cualquiera de los cuatro headers
- **THEN** recibe HTTP 400 con envelope compartido y no ejecuta el registro ni devuelve un resultado guardado
- **AND** la respuesta conserva el Process-Id únicamente cuando pudo validarse

#### Scenario: Clave aislada por tenant y operación
- **WHEN** se utiliza el mismo texto de clave en otro tenant o en otra operación
- **THEN** esa identidad es independiente y no permite recuperar el completado ajeno

### Requirement: Entrada cerrada y canónica de registro
El registro SHALL aceptar únicamente `partyId`, `code`, `operatingName`, `description` y `logoUrl`. Los tres primeros SHALL ser obligatorios; los dos últimos opcionales. SHALL validar el contenido canónico antes de comprobar unicidad o calcular el fingerprint, preservando los valores opacos.

#### Scenario: Campos generados o desconocidos en el body
- **WHEN** se envía `companyId`, `id`, `tenantId`, `status`, `version`, auditoría, `party`, `identifiers`, metadatos outbox u otro atributo no declarado
- **THEN** recibe HTTP 400 y no realiza escrituras

#### Scenario: Normalización y límites efectivos
- **WHEN** se reciben código, nombre o descripción con espacios exteriores o mezcla de mayúsculas y minúsculas
- **THEN** se aplica la canonización de texto de negocio existente antes de validar los límites de 64, 200 y 500 puntos de código respectivamente
- **AND** se conservan acentos y espacios internos; un requerido ausente o blanco y una descripción proporcionada en blanco son inválidos

#### Scenario: UUID y URL
- **WHEN** se registra una referencia UUID válida y una URL opcional válida de hasta 1000 puntos de código
- **THEN** el UUID externo no necesita ser UUIDv7 y la URL conserva su contenido sensible a mayúsculas
- **AND** JSON o UUID inválidos, valores escalares incompatibles y URL vacía o con espacios exteriores se rechazan sin efectos

### Requirement: Elegibilidad de Party para nuevas creaciones
Una nueva Company SHALL referenciar una Party `LEGAL_ENTITY` resoluble en el tenant solicitado y en estado `DRAFT`, `ACTIVE` o `INACTIVE`. SHALL rechazar `NATURAL_PERSON` y `ARCHIVED`. Estas reglas de admisión SHALL aplicarse a nuevas creaciones, sin introducir activación de Company ni sincronización de ciclos de vida.

#### Scenario: Estados de Party admitidos
- **WHEN** Party Registry confirma una entidad legal del tenant en cualquiera de los tres estados admitidos
- **THEN** la Company puede registrarse y nace en DRAFT aunque la Party ya esté ACTIVE

#### Scenario: Ausencia y referencia de otro tenant
- **WHEN** la Party no puede resolverse dentro del tenant actual
- **THEN** se rechaza con el mismo HTTP 404 y código público de referencia ausente, sin revelar si existe en otro tenant
- **AND** no se crea Company, completado ni evento

#### Scenario: Tipo o estado incompatible
- **WHEN** la Party es NATURAL_PERSON o está ARCHIVED
- **THEN** recibe un fallo público estable de referencia inválida y no se crea ningún registro de la operación

#### Scenario: Dependencia no disponible o incompatible
- **WHEN** el detalle o los identificadores requeridos de Party no pueden obtenerse por timeout, indisponibilidad o contrato inválido
- **THEN** la creación falla de forma controlada con 503 para timeout/indisponibilidad o 502 para contrato incompatible
- **AND** no confirma Company, completado ni evento

### Requirement: Identidad, versión y auditoría iniciales
Toda nueva Company SHALL tener UUIDv7 generado por el servidor, estado `DRAFT`, versión `0` y un único instante de creación UTC con precisión de microsegundos. SHALL establecer `createdAt = updatedAt` y `createdBy = updatedBy = User-Id` validado, preservando el actor exactamente.

#### Scenario: Creación aceptada
- **WHEN** el registro confirma correctamente
- **THEN** devuelve HTTP 201 con envelope compartido, representación enriquecida y los valores iniciales acordados
- **AND** el instante coincide entre persistencia, respuesta y snapshot; tenant y Party quedan inmutables

### Requirement: Unicidad de Company por tenant
El registro SHALL mantener como máximo una Company por `(tenant, partyId)` y por `(tenant, code canónico)`, incluso después de archivar una Company. Un duplicado bajo otra clave idempotente SHALL producir HTTP 409 sin devolver un resultado de una clave diferente.

#### Scenario: Duplicados concurrentes con distintas claves
- **WHEN** transacciones independientes intentan registrar el mismo Party o código en un tenant con claves diferentes
- **THEN** como máximo una confirma y las restantes reciben conflicto de negocio
- **AND** las operaciones perdedoras no dejan completados ni eventos adicionales

#### Scenario: Reutilización entre tenants
- **WHEN** dos tenants usan el mismo código o referencia Party y cada validación del proveedor es válida en su ámbito
- **THEN** la unicidad de un tenant no bloquea ni expone los datos del otro

### Requirement: Fingerprint efectivo determinista y versionado
El fingerprint SHALL ser HMAC SHA-256 del contenido efectivo canónico y versionado: tenant, operación y los cinco campos de registro. Su serialización SHALL ser determinista en UTF-8 con orden y escapes estables y opcionales explícitos. SHALL excluir actor, proceso, clave idempotente y datos generados o consultados al proveedor.

#### Scenario: Solicitudes efectivamente equivalentes
- **WHEN** solo cambian el orden de propiedades, espacios de presentación JSON, mayúsculas/espacios exteriores del texto de negocio, actor, proceso, o un opcional pasa de omitido a null
- **THEN** el contenido efectivo comparado es el mismo y una clave aceptada permite replay

#### Scenario: Cambio significativo
- **WHEN** cambia Party, código/nombre/descripcion canónicos o el contenido exacto de una URL válida bajo la misma clave aceptada
- **THEN** recibe HTTP 409 y no obtiene la respuesta guardada ni crea registros
- **AND** los espacios internos significativos y diferencias de mayúsculas de la URL cuentan como cambios

### Requirement: Rotación compatible de fingerprints
Cada completado HU01 SHALL identificar la versión de política y la clave HMAC usadas. Los reintentos SHALL verificarse con esa versión y clave originales. Nuevas creaciones SHALL usar la clave activa; las claves y reglas históricas SHALL conservarse mientras existan completados que dependan de ellas.

#### Scenario: Replay después de rotación y reinicio
- **WHEN** una creación aceptada con K1 se reintenta después de activar K2 o reiniciar otra instancia
- **THEN** se verifica con la política y K1 indicadas por su completado y devuelve el resultado original
- **AND** no reescribe el fingerprint histórico ni la auditoría

#### Scenario: Clave histórica o política ausente
- **WHEN** el servicio no dispone de la clave o política indicada por el completado
- **THEN** falla de forma controlada y saneada sin presentar un 409 de contenido diferente ni ejecutar otra creación

### Requirement: Replay durable de la respuesta completa original
Una clave aceptada con contenido equivalente SHALL devolver el estado HTTP 201 y el snapshot original completo, incluyendo `party`, `legalEntityDetails`, `identifiers`, auditoría y versión inicial. SHALL funcionar sin consultar el agregado actual o Party Registry y sin escrituras, incrementos de versión o eventos adicionales.

#### Scenario: Cambios posteriores o pérdida de respuesta
- **WHEN** se pierde la respuesta tras commit o cambian posteriormente Company, Party o sus identificadores
- **THEN** el reintento equivalente recupera exactamente los valores aceptados originalmente, aunque el proveedor esté caído o la Party ahora esté ARCHIVED
- **AND** la cabecera Process-Id corresponde al reintento mientras los actores/fechas del snapshot permanecen originales

#### Scenario: Cambio del modo outbox antes del replay
- **WHEN** una creación en disabled se repite tras seleccionar stored-only o published
- **THEN** se devuelve el snapshot y no se agrega retrospectivamente un evento

### Requirement: Atomicidad del registro aceptado
Company, completado idempotente con respuesta enriquecida y evento habilitado SHALL confirmarse en una sola transacción local. Solo un resultado confirmado SHALL devolverse como aceptación. Un fallo anterior al commit SHALL revertir todas las escrituras de esa operación y no consumir su clave.

#### Scenario: Fallo al guardar completado o evento
- **WHEN** falla cualquiera de las escrituras obligatorias después de insertar provisionalmente Company
- **THEN** no queda ninguna Company, completado o evento de ese intento
- **AND** un intento posterior con la misma clave puede completar el registro

### Requirement: Resolución durable de solicitudes concurrentes
PostgreSQL SHALL arbitrar las carreras mediante la unicidad existente. Una solicitud que pierde una carrera esperada SHALL resolver la clave durable después del rollback y cierre de su sesión, mediante una lectura nueva. Un completado equivalente SHALL dar replay; uno distinto, 409; sin completado y con código/Party ocupado, conflicto de negocio.

#### Scenario: Misma clave y contenido equivalente en paralelo
- **WHEN** dos solicitudes equivalentes de la misma identidad idempotente compiten en transacciones independientes
- **THEN** como máximo una Company y un completado confirman, con como máximo un evento habilitado
- **AND** después de confirmar la ganadora, la perdedora devuelve el mismo 201 y snapshot mediante recuperación durable
- **AND** esto se cumple aunque la primera colisión ocurra sobre la unicidad de Company antes de insertar el completado

#### Scenario: Misma clave y contenido diferente en paralelo
- **WHEN** dos solicitudes con contenido efectivo diferente compiten por la misma clave y una confirma
- **THEN** la otra revierte su Company provisional y devuelve 409 después de comparar el completado ganador

#### Scenario: Ganadora que revierte
- **WHEN** el intento que retiene la unicidad termina en rollback
- **THEN** otra solicitud válida puede confirmar sin quedar bloqueada por una reserva idempotente fallida

### Requirement: Espera finita y resultado de commit incierto
Las esperas de concurrencia y recuperación SHALL tener plazos finitos configurables. Si no puede determinarse el resultado dentro del plazo o se pierde la conexión durante el commit, SHALL devolverse un fallo temporal controlado que permita reintentar con la misma clave. SHALL evitar afirmar un rollback que no pudo comprobarse.

#### Scenario: Timeout de espera o resultado incierto
- **WHEN** vence el plazo de la operación o se pierde la respuesta de commit
- **THEN** recibe HTTP 503 si no puede recuperarse un completado confirmado
- **AND** no se guarda una aceptación ficticia ni un completado de fallo y el mismo key/input permite resolver el resultado posteriormente
