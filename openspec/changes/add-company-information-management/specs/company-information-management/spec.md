# Spec Delta

## Purpose

Permitir correcciones parciales y seguras de la información operacional de Company, con aislamiento tenant, control de versión y reproducción durable opcional del resultado aceptado.

## ADDED Requirements

### Requirement: Frontera HTTP de actualización operacional
`PATCH /v1/companies/{companyId}` SHALL exigir el contexto de negocio existente y un UUID de recurso válido. SHALL aceptar un body objeto `application/json`, incluyendo `{}`, y devolver errores con el envelope compartido y Process-Id válido del intento. SHALL rechazar body ausente, null, array, JSON inválido y propiedades duplicadas.

#### Scenario: Contexto o identificador inválidos
- **WHEN** falta, se repite o es inválido un header de contexto, o el companyId está malformado
- **THEN** responde 400 antes de resolver aceptaciones o modificar persistencia
- **AND** tenant/proceso exigen UUID canónico en minúsculas y el actor conserva su contenido exacto

#### Scenario: Body cerrado y sin ambigüedad
- **WHEN** el body está ausente, es null/array, contiene JSON inválido o repite una propiedad
- **THEN** responde 400 sin guardar Company, completado ni evento
- **AND** un media type no soportado responde 415 mediante el contrato común

### Requirement: Campos mutables y presencia explícita
El body SHALL admitir únicamente `code`, `operatingName`, `description` y `logoUrl`. Un campo omitido SHALL conservar su valor; null SHALL limpiar description/logoUrl y SHALL rechazarse en code/operatingName. Los valores no nulos SHALL ser strings sin coerción. Identidad, estado, versión, auditoría y cualquier otra propiedad SHALL rechazarse incluso si coinciden con el valor actual.

#### Scenario: Modificación y limpieza parciales
- **WHEN** se envía operatingName con valor y description con null, omitiendo code y logoUrl
- **THEN** cambia únicamente el nombre y limpia la descripción
- **AND** conserva el código y la URL actuales

#### Scenario: Nulabilidad o tipo inválidos
- **WHEN** code u operatingName son null, o un campo recibe número, booleano, objeto o array
- **THEN** responde 400 sin convertir el valor a texto ni modificar persistencia

#### Scenario: Intento de cambiar identidad o campos del sistema
- **WHEN** aparece id, companyId, tenantId, partyId, createdAt, createdBy, status, version, updatedAt o updatedBy
- **THEN** responde 400 aunque la propiedad sea null o reproduzca el valor almacenado

### Requirement: Canonización compatible con el registro
Los campos presentes de texto de negocio SHALL usar strip y mayúsculas Locale.ROOT antes de comprobar límites de 64/200/500 puntos de código para code/operatingName/description. Los blancos SHALL rechazarse. logoUrl SHALL mantener su contenido exacto y satisfacer las reglas HU01 de URL HTTP/HTTPS absoluta, sin espacios exteriores y hasta 1000 puntos de código.

#### Scenario: Canonización antes de unicidad y comparación
- **WHEN** se suministra `  compañía  ágil ` como operatingName
- **THEN** el valor efectivo es `COMPAÑÍA  ÁGIL`, conservando acentos y espacios internos
- **AND** se usa esa representación para comparar cambios y para la huella de la política aplicable

#### Scenario: Longitud final o URL inválida
- **WHEN** la conversión a mayúsculas excede el límite, un texto está en blanco o la URL es inválida
- **THEN** responde 400 sin modificar datos
- **AND** una URL válida como `https://host/LogoAb.png` conserva su representación

### Requirement: Aislamiento tenant de la actualización
Una nueva operación SHALL resolver y modificar Company exclusivamente dentro del tenant del contexto. Un UUID válido ausente o perteneciente a otro tenant SHALL devolver el mismo 404 `not-found`, sin consultas globales ni llamadas externas. La operación SHALL conservar identidad y auditoría de creación y no modificar ningún agregado Site.

#### Scenario: Recurso ajeno o inexistente
- **WHEN** no existe aceptación previa para la clave y Company no pertenece al tenant solicitado o no existe
- **THEN** devuelve el mismo 404 y no deja escrituras ni revela su existencia en otro tenant

#### Scenario: Edición limitada al agregado
- **WHEN** una corrección propia confirma
- **THEN** tenantId, partyId, ID y auditoría de creación mantienen sus valores
- **AND** las versiones y datos de Site, direcciones y contactos permanecen intactos

### Requirement: Precondición obligatoria de versión Company
Cada intento SHALL incluir exactamente un If-Match decimal canónico no negativo dentro de int64, sin comillas, signos, comodines, espacios ni ceros iniciales. Ausencia, repetición o valor inválido SHALL devolver 400. Sin replay previo, una versión distinta de la actual SHALL devolver 412 `aggregate-version-mismatch`, también para un body que sería no-op.

#### Scenario: Precondición inválida
- **WHEN** falta If-Match, se repite o contiene `"0"`, `*`, `01`, `-1` o un entero fuera de int64
- **THEN** responde 400 antes de aceptar o reproducir una operación

#### Scenario: Versión obsoleta con campos equivalentes
- **WHEN** no hay aceptación previa coincidente y If-Match es obsoleto aunque el body sea `{}` o igual a los datos actuales
- **THEN** responde 412 sin nuevo completado ni evento

### Requirement: Restricción por ciclo de vida
Sin replay previo, la edición SHALL permitirse en DRAFT, ACTIVE e INACTIVE. Con versión vigente, un PATCH nuevo sobre ARCHIVED SHALL responder 409 `resource-conflict`, incluso si sería no-op. La comprobación de versión SHALL preceder al rechazo por estado y ninguna corrección SHALL cambiar status.

#### Scenario: Estados editables
- **WHEN** una Company DRAFT, ACTIVE o INACTIVE recibe una corrección válida con versión vigente
- **THEN** la acepta conservando su estado actual

#### Scenario: Company archivada
- **WHEN** una Company ARCHIVED recibe un PATCH nuevo con versión vigente, incluyendo `{}`
- **THEN** responde 409 sin alterar auditoría, versión, completados ni eventos
- **AND** si la versión era obsoleta responde 412 antes de evaluar el estado

### Requirement: Protección frente a actualizaciones perdidas
La aceptación SHALL protegerse en la base de datos frente a modificaciones concurrentes. Dos correcciones efectivas con la misma versión original y claves distintas o sin clave SHALL confirmar como máximo una; la otra SHALL responder 412. Una corrección efectiva SHALL incrementar Company.version exactamente una vez y guardar los cuatro valores operacionales resultantes coherentes.

#### Scenario: Dos cambios efectivos concurrentes
- **WHEN** dos peticiones con claves distintas o sin clave usan la misma versión y pretenden cambios efectivos en la misma Company
- **THEN** como máximo una confirma y la otra devuelve 412
- **AND** la revisión aumenta en uno y no se pierden datos mediante una sobrescritura posterior

#### Scenario: No-op concurrente con cambio efectivo
- **WHEN** un no-op nuevo compite con una modificación efectiva
- **THEN** solo acepta el no-op si su versión era vigente en el punto protegido de aceptación
- **AND** un no-op aceptado antes no impide que una modificación efectiva use esa revisión todavía vigente

### Requirement: No-op sin efectos sobre el agregado
Una petición válida que no modifica los cuatro campos después de normalizar SHALL devolver 200 con la Company aceptada, conservando versión, updatedAt y updatedBy y sin evento. `{}` SHALL seguir la misma regla. Sin Idempotency-Key SHALL omitir el completado; con clave SHALL conservar su aceptación durable.

#### Scenario: Valores equivalentes o limpieza redundante
- **WHEN** se envía `{}`, texto equivalente tras normalización o null para un opcional ya nulo, con versión vigente y estado editable
- **THEN** devuelve el resultado actual sin modificar auditoría o versión ni producir evento
- **AND** guarda exactamente un completado si hay clave válida y ninguno si se omite

### Requirement: Auditoría del cambio efectivo
Cada corrección efectiva SHALL conservar createdAt/createdBy y establecer updatedAt con un instante UTC a precisión de microsegundos y updatedBy con el actor exacto del contexto aceptado. El resultado, el snapshot idempotente y el evento habilitado SHALL coincidir en auditoría, datos operacionales y versión resultante.

#### Scenario: Actor e instante de modificación
- **WHEN** un actor válido realiza un cambio efectivo
- **THEN** el nuevo updatedBy corresponde a ese actor y updatedAt al instante aceptado
- **AND** no se recalculan valores distintos para la respuesta, el completado y el evento

### Requirement: Resultado operacional independiente de proveedores
Actualización, no-op y replay SHALL devolver 200 `successful` con companyId, partyId, code, operatingName, status, version y los cuatro campos de auditoría, más description/logoUrl cuando tengan valor. SHALL omitir Party y opcionales nulos, y operar sin consultas a Party, Geography o RabbitMQ. POST/GET SHALL conservar su representación enriquecida HU01.

#### Scenario: Proveedores inaccesibles
- **WHEN** la base local funciona y los proveedores externos no son accesibles
- **THEN** un PATCH válido, un no-op o un replay confirma según sus reglas locales
- **AND** la respuesta no contiene party ni dispara enriquecimiento o publicación

#### Scenario: Opcional limpiado y consulta posterior
- **WHEN** PATCH limpia description y después se consulta Company mediante GET con su proveedor disponible
- **THEN** ambas respuestas omiten description y muestran los valores operacionales actuales correspondientes
- **AND** GET mantiene Party mientras PATCH devuelve únicamente Company operacional

### Requirement: Clave de actualización opcional y acotada
Idempotency-Key SHALL ser opcional; cuando aparece SHALL ser una sola clave opaca válida de hasta 128 puntos de código, preservada según el contrato existente. El ámbito SHALL ser `(tenant, company.update.v1, key)`. Claves inválidas SHALL devolver 400; la misma cadena SHALL poder usarse en otro tenant u operación sin colisión.

#### Scenario: Omisión o clave inválida
- **WHEN** se omite la clave o se proporciona vacía, en blanco, repetida o fuera de límite
- **THEN** la omisión permite una actualización sin replay durable y un valor inválido responde 400

#### Scenario: Separación por tenant y operación
- **WHEN** una clave ya identifica una creación o una actualización de otro tenant
- **THEN** no impide una aceptación company.update.v1 en el tenant actual

### Requirement: Identidad de la intención idempotente
La huella SHALL vincular tenant, operación, companyId, If-Match original y campos presentes normalizados, distinguiendo omitido de null. SHALL excluir actor, proceso y estado actual de Company. Una clave aceptada con intención distinta SHALL devolver 409 sin revelar su snapshot. La comparación SHALL usar la política y clave históricas del completado.

#### Scenario: Equivalencia canónica
- **WHEN** una clave aceptada se repite para el mismo recurso/precondición y campos con texto canónicamente equivalente, aunque cambie su orden JSON
- **THEN** identifica la misma intención y reproduce la aceptación
- **AND** una URL cuyo contenido cambia de mayúsculas/minúsculas mantiene esa diferencia

#### Scenario: Presencia, recurso o precondición distintos
- **WHEN** se reutiliza una clave aceptada cambiando companyId, If-Match, valor efectivo o presencia de un campo, incluyendo `{}` frente a `{"description":null}`
- **THEN** devuelve 409 `resource-conflict` antes de evaluar la revisión actual del agregado

### Requirement: Replay original anterior a la evaluación actual
Una aceptación coincidente SHALL reproducir su resultado original 200 antes de evaluar existencia, versión o estado actuales de Company. SHALL preservar datos, versión y actores originales sin nuevas escrituras, usando Process-Id del intento actual. SHALL funcionar tras cambios posteriores, archivado, reinicio, rotación de clave o cambio de modo outbox.

#### Scenario: Reintento después de otra modificación
- **WHEN** una aceptación K1 con If-Match 3 devolvió versión 4, otra operación avanzó a 5 y se repite K1 con su entrada e If-Match 3 originales
- **THEN** devuelve el snapshot versión 4 sin responder 412 y sin modificar la Company versión 5

#### Scenario: Replay de no-op después de archivado
- **WHEN** un no-op con clave fue aceptado y Company fue modificada o archivada posteriormente
- **THEN** su reintento idéntico devuelve el resultado original sin revalidar el estado actual ni generar un evento

#### Scenario: Contexto actual e histórico inválido
- **WHEN** un replay válido utiliza otro actor/proceso, o el completado requiere una clave/política/snapshot que no puede interpretarse
- **THEN** en el primer caso conserva la auditoría original y devuelve el Process-Id actual
- **AND** en el segundo responde 500 `server-error` saneado sin volver a aplicar la corrección ni atribuirle la clave activa

### Requirement: Aceptación durable atómica
Un cambio efectivo, su completado cuando exista clave y el evento cuando esté habilitado SHALL confirmar o revertirse juntos. Un no-op con clave SHALL confirmar su completado sin modificar Company. Los fallos SHALL dejar la clave disponible si hubo rollback y SHALL NOT persistirse como aceptaciones. Una clave idéntica concurrente SHALL producir una sola aceptación.

#### Scenario: Fallo en escritura obligatoria
- **WHEN** falla la persistencia de Company, del completado requerido o del evento habilitado
- **THEN** no queda ningún efecto parcial confirmado de ese intento
- **AND** la petición puede reintentarse con la misma clave si la transacción revirtió

#### Scenario: Carrera por clave
- **WHEN** dos peticiones compiten por la misma clave del tenant y operación
- **THEN** solo se conserva un completado; intención idéntica devuelve el mismo resultado y distinta devuelve 409
- **AND** cualquier modificación o evento del intento perdedor se revierte, incluso si apuntaba a otra Company

### Requirement: Recuperación acotada de resultado incierto
Tras una colisión idempotente o resultado de commit incierto con clave, la operación SHALL intentar resolver el completado durable antes de declarar conflicto o versión obsoleta. Espera local, locks y recuperación SHALL estar acotados. Una aceptación irresoluble o indisponibilidad SHALL responder 503; sin clave SHALL NOT prometer reproducción del resultado original.

#### Scenario: Commit confirmado y respuesta perdida
- **WHEN** se pierde la respuesta después de confirmar una operación con clave y se reintenta idénticamente
- **THEN** devuelve el snapshot durable original sin modificar nuevamente el agregado

#### Scenario: Espera agotada o commit no resoluble
- **WHEN** el presupuesto local se agota o no puede establecerse el resultado durable
- **THEN** responde 503 `dependency-unavailable` sin afirmar que una respuesta perdida prueba rollback
- **AND** no deja trabajo de escritura abandonado ni reintenta ciegamente la mutación

### Requirement: Unicidad de código operacional
Un cambio de code SHALL conservar unicidad canónica por tenant, incluyendo códigos ocupados por Companies ARCHIVED. Una colisión confirmada SHALL devolver 409 `resource-conflict` sin consumirse como aceptación. La comprobación SHALL distinguir otra Company del propio recurso y la protección persistente SHALL arbitrar las carreras.

#### Scenario: Colisión canónica y código propio
- **WHEN** otra Company del tenant ocupa el código normalizado solicitado, o el código enviado coincide con el del propio recurso
- **THEN** el primer caso devuelve 409 y el segundo no constituye una colisión
- **AND** tenants diferentes pueden usar el mismo código sin compartir datos

#### Scenario: Dos Companies solicitan el mismo código libre
- **WHEN** dos Companies del tenant intentan adquirir concurrentemente el mismo código canónico
- **THEN** como máximo una confirma y la otra responde 409 sin modificación, completado o evento parcial
