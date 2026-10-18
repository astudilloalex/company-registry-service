# Spec Delta

## Purpose

Registrar sedes operacionales independientes dentro de una Company y tenant, con admisión local, integridad concurrente, auditoría y recuperación durable de la aceptación original.

## ADDED Requirements

### Requirement: Contexto y clave obligatorios para registrar Site
`POST /v1/companies/{companyId}/sites` SHALL exigir contexto Tenant-Id/User-Id/Process-Id válido y exactamente una Idempotency-Key opaca, no blanca y de hasta 128 puntos de código. SHALL preservar la clave y usar `(tenant, company.site.create.v1, key)` como identidad idempotente. SHALL NOT requerir If-Match.

#### Scenario: Contexto y clave válidos
- **WHEN** se presenta una solicitud válida con contexto completo y una clave admitida, sin If-Match
- **THEN** puede ejecutar el registro o recuperar su aceptación sin exigir una versión del padre
- **AND** el Process-Id de la respuesta corresponde a la petición actual

#### Scenario: Clave inválida o contexto incompleto
- **WHEN** falta, se repite, está en blanco o excede el límite la clave, o falla el contexto obligatorio
- **THEN** recibe HTTP 400 con el code descriptivo correspondiente antes de crear o devolver una aceptación
- **AND** el Process-Id solo se conserva cuando pudo validarse

### Requirement: Body cerrado de registro de Site
El POST SHALL admitir únicamente `code`, `name` y `siteType` en un objeto JSON. SHALL rechazar body ausente, JSON inválido, propiedades desconocidas o duplicadas, valores de tipo incompatible y contenido posterior al objeto. Los errores conocidos SHALL usar HTTP 400 y un code principal descriptivo.

#### Scenario: Body ausente o malformado
- **WHEN** falta el body o el texto no constituye JSON válido
- **THEN** recibe respectivamente `request-body-required` o `request-body-invalid-json` con HTTP 400 y sin escrituras

#### Scenario: Campos administrados o desconocidos
- **WHEN** se envía id, siteId, tenantId, companyId, status, version, auditoría, effectivelyOperational u otra propiedad no admitida
- **THEN** recibe HTTP 400 `request-body-contains-unknown-fields`
- **AND** no puede alterar el ámbito de la ruta ni valores generados

#### Scenario: Propiedad duplicada o tipo incompatible
- **WHEN** el objeto contiene una propiedad repetida, una propiedad con tipo incompatible, una raíz distinta de objeto o tokens posteriores
- **THEN** recibe HTTP 400 con el code estructural o de campo documentado y no ejecuta la creación

### Requirement: Código y nombre canónicos de Site
El registro SHALL canonizar code y name mediante la política de texto existente antes de validar, comprobar unicidad o firmar. SHALL exigir valores no blancos de hasta 64 y 200 puntos de código respectivamente, preservando acentos y espacios internos significativos.

#### Scenario: Canonización y expansión Unicode
- **WHEN** se registran nombres y códigos con mayúsculas/minúsculas o espacios exteriores, incluido texto cuya conversión aumenta su longitud
- **THEN** persiste sus valores canónicos en mayúsculas y aplica los límites sobre esa representación final

#### Scenario: Código requerido o demasiado largo
- **WHEN** code falta, es null o queda en blanco, o supera 64 puntos de código después de canonizar
- **THEN** recibe respectivamente HTTP 400 `site-code-required` o `site-code-too-long`

#### Scenario: Nombre requerido o demasiado largo
- **WHEN** name falta, es null o queda en blanco, o supera 200 puntos de código después de canonizar
- **THEN** recibe respectivamente HTTP 400 `site-name-required` o `site-name-too-long`

### Requirement: Tipo efectivo BRANCH por omisión
siteType SHALL aceptar exactamente HEADQUARTERS, BRANCH, WAREHOUSE y OTHER. Su omisión SHALL producir BRANCH. Null explícito SHALL rechazarse, y SHALL NOT admitir tipos arbitrarios ni normalizar silenciosamente un valor enum desconocido.

#### Scenario: Omisión equivalente a BRANCH explícito
- **WHEN** dos entradas solo difieren entre omitir siteType y enviar `BRANCH`
- **THEN** tienen el mismo tipo efectivo y contenido para la idempotencia

#### Scenario: Null o tipo no soportado
- **WHEN** siteType es null o una cadena distinta de los cuatro valores exactos admitidos
- **THEN** recibe respectivamente HTTP 400 `site-type-null-not-allowed` o `site-type-not-supported`
- **AND** una cadena vacía o `branch` no se convierte en BRANCH

### Requirement: Admisión bajo Company propia protegida
Toda creación nueva de cualquier tipo de Site SHALL bloquear Company dentro de la transacción y verificar que existe en el tenant y está DRAFT, ACTIVE o INACTIVE. SHALL rechazar Company ARCHIVED y mantener el orden de protección Company antes de Site compatible con futuros cambios de ciclo de vida.

#### Scenario: Estados de Company admitidos
- **WHEN** una Company propia está DRAFT, ACTIVE o INACTIVE y se presenta un registro válido
- **THEN** admite la creación de una sede DRAFT con la misma propiedad tenant/Company

#### Scenario: Company inexistente o ajena
- **WHEN** una nueva creación referencia un ID inexistente dentro del tenant, aunque exista en otro
- **THEN** recibe el mismo HTTP 404 `company-not-found` y no expone datos ajenos

#### Scenario: Company archivada
- **WHEN** la nueva creación observa Company ARCHIVED bajo protección transaccional
- **THEN** recibe HTTP 409 `site-registration-not-allowed-for-archived-company`

#### Scenario: Carrera con archivado
- **WHEN** un registro nuevo y un archivado compiten por la misma Company con protección compatible
- **THEN** si el archivado confirma primero se rechaza el registro
- **AND** si el registro confirma primero, su aceptación precede al archivado sin modificar la revisión del padre

### Requirement: Identidad y auditoría iniciales independientes
Site SHALL recibir UUIDv7 del servidor, status DRAFT y version 0. SHALL fijar createdAt igual a updatedAt en un único instante UTC con precisión de microsegundos y createdBy igual a updatedBy con el User-Id exacto. Registrar Site SHALL NOT modificar versión, estado ni auditoría de Company.

#### Scenario: Aceptación inicial
- **WHEN** confirma un registro válido
- **THEN** devuelve HTTP 201 `successful` con Site DRAFT/version 0 y auditoría inicial coherente con persistencia y snapshot
- **AND** Company mantiene exactamente su versión, estado y cuatro campos de auditoría anteriores

### Requirement: Código único por Company incluyendo historial
El código canónico SHALL ser único dentro de Company para todos los estados Site, incluido CLOSED. Companies diferentes SHALL poder reutilizar el código. Una clave nueva para un código ocupado SHALL recibir HTTP 409 `site-code-already-exists`.

#### Scenario: Duplicado canónico e histórico
- **WHEN** otra sede de la misma Company ya conserva el código efectivo solicitado, aunque esté CLOSED
- **THEN** la nueva solicitud recibe `site-code-already-exists` sin Site, completado o evento adicionales

#### Scenario: Reutilización entre Companies
- **WHEN** dos Companies distintas registran el mismo código efectivo bajo claves independientes
- **THEN** el código de una no impide el registro en la otra

### Requirement: Una sede legal no cerrada por Company
Cada Company SHALL tener como máximo un HEADQUARTERS cuyo estado sea distinto de CLOSED. DRAFT, ACTIVE e INACTIVE SHALL ocupar esa reserva. Un segundo registro SHALL recibir HTTP 409 `headquarters-already-exists`; el historial CLOSED SHALL permitir un reemplazo con código libre.

#### Scenario: Sede legal existente
- **WHEN** ya existe HEADQUARTERS DRAFT, ACTIVE o INACTIVE y se solicita otro con código libre
- **THEN** recibe `headquarters-already-exists` sin duplicar sedes legales

#### Scenario: Reemplazo después del cierre
- **WHEN** las anteriores sedes legales están CLOSED y el nuevo registro usa otro código libre
- **THEN** registra un nuevo HEADQUARTERS DRAFT y conserva el historial cerrado
- **AND** este registro no activa Company ni implementa el cierre de la sede anterior

### Requirement: Fingerprint efectivo específico y determinista
La aceptación SHALL usar HMAC SHA-256 con política versionada sobre tenant, operación, companyId de ruta, code/name canónicos y siteType efectivo. SHALL excluir clave idempotente, actor, proceso, IDs y fechas generadas, modo outbox y estados actuales. La serialización SHALL ser determinista y conservar espacios internos significativos.

#### Scenario: Entrada equivalente
- **WHEN** solo cambian presentación u orden JSON, mayúsculas/espacios exteriores de código/nombre, omisión frente a BRANCH, actor o proceso
- **THEN** una clave previamente aceptada permite replay del mismo contenido efectivo

#### Scenario: Cambio significativo o de Company
- **WHEN** bajo una clave aceptada cambia Company, código/nombre efectivo o siteType efectivo
- **THEN** recibe HTTP 409 `idempotency-key-already-used-with-different-request`
- **AND** no obtiene el snapshot ni crea una sede en el otro ámbito

### Requirement: Procedencia y conservación de aceptación Site
Cada completado SHALL identificar versión de fingerprint, clave HMAC histórica y versión del snapshot. El replay SHALL verificar con esa procedencia original. Aceptaciones, reglas y claves necesarias SHALL conservarse sin TTL ni limpieza que permita otra creación aceptada con la misma identidad.

#### Scenario: Rotación y reinicio
- **WHEN** se reintenta una creación aceptada con K1 después de activar K2, reiniciar el proceso o usar otra instancia
- **THEN** verifica con K1 y la política almacenada y devuelve el resultado original

#### Scenario: Material histórico desconocido o corrupto
- **WHEN** falta la clave/política histórica o el snapshot no satisface su contrato versionado
- **THEN** falla con HTTP 500 `server-error` saneado
- **AND** no presenta una diferencia de contenido ficticia ni ejecuta otra creación

### Requirement: Replay original previo al estado actual
Una solicitud válida equivalente a una aceptación durable SHALL devolver su HTTP 201 y snapshot original completo sin consultar Company/Site actuales ni proveedores. SHALL preservar DRAFT, versión 0, effectivelyOperational false y auditoría original, sin nuevas escrituras, eventos o cambios de versión.

#### Scenario: Estado posterior archivado o cerrado
- **WHEN** Company fue archivada o Site modificado, activado o cerrado después del registro aceptado
- **THEN** el reintento equivalente devuelve exactamente el resultado original antes de aplicar reglas de admisión actuales
- **AND** el Process-Id de respuesta es el del reintento y la auditoría del contenido continúa siendo la original

#### Scenario: Cambio de modo outbox
- **WHEN** una creación aceptada en disabled se repite con stored-only o published
- **THEN** devuelve el snapshot sin agregar retroactivamente un evento

### Requirement: Aceptación local atómica
El registro SHALL confirmar Site, completado con respuesta local original y evento habilitado en una única transacción local. SHALL devolver aceptación solo después del commit. Un fallo anterior al commit SHALL revertir las tres escrituras y SHALL NOT consumir la clave.

#### Scenario: Fallo del completado o evento
- **WHEN** falla una escritura obligatoria tras insertar provisionalmente Site
- **THEN** no queda Site, aceptación ni evento atribuible al intento revertido
- **AND** la misma clave puede completar un intento posterior válido

### Requirement: Resolución durable de registros concurrentes
Las restricciones transaccionales SHALL arbitrar código, sede legal e identidad idempotente. Tras una carrera perdida SHALL resolverse primero la aceptación durable después del rollback y cierre de la sesión fallida. Una aceptación equivalente SHALL dar replay; otra entrada SHALL dar conflicto de clave; una colisión de negocio conocida SHALL conservar su causa pública específica.

#### Scenario: Misma clave equivalente en paralelo
- **WHEN** dos transacciones independientes compiten con igual identidad y contenido efectivo
- **THEN** confirma como máximo un Site, un completado y un evento habilitado
- **AND** la perdedora recupera el mismo 201 original, incluso si chocó primero contra unicidad de Site

#### Scenario: Misma clave para distintas Companies
- **WHEN** dos creaciones bajo Companies diferentes del mismo tenant compiten por la misma clave y una confirma
- **THEN** la perdedora revierte su Site y evento provisionales y recibe `idempotency-key-already-used-with-different-request`

#### Scenario: Distintas claves para la misma sede legal
- **WHEN** creaciones concurrentes con claves y códigos distintos intentan ocupar HEADQUARTERS de la misma Company
- **THEN** como máximo una confirma y la otra recibe `headquarters-already-exists`

#### Scenario: Competidor que revierte
- **WHEN** el intento que retiene una reserva o bloqueo termina en rollback
- **THEN** otro registro válido puede confirmar sin una aceptación de fallo ni una reserva idempotente huérfana

### Requirement: Espera finita e incertidumbre explícita
Las fases de bloqueo, escritura, limpieza y recuperación SHALL respetar un plazo local finito configurable. Si no puede determinarse una aceptación confirmada dentro del plazo o se pierde la confirmación de commit, SHALL devolver HTTP 503 `dependency-unavailable` y permitir reintentar la misma clave/entrada sin afirmar un rollback no demostrado.

#### Scenario: Timeout o commit incierto
- **WHEN** vence la espera o se pierde la respuesta del commit y la recuperación no determina el resultado
- **THEN** devuelve 503 sin guardar un completado de fallo ni reejecutar ciegamente la creación
- **AND** un reintento posterior puede recuperar la aceptación si efectivamente confirmó

### Requirement: Registro independiente de proveedores
HU04 SHALL resolver propiedad y admisión con Company Registry local. El registro y replay SHALL funcionar sin Party Registry, Geographic Reference ni RabbitMQ disponibles. SHALL NOT registrar direcciones/contactos ni ejecutar transiciones de ciclo de vida como efecto secundario.

#### Scenario: Proveedores caídos
- **WHEN** la base local está disponible y los proveedores externos están inaccesibles
- **THEN** el registro válido o replay puede completar su respuesta local sin invocarlos
