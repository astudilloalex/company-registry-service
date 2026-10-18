# company-lifecycle-management Specification

## Purpose

Administrar el ciclo de vida operacional de Company con elegibilidad legal al activar, matriz activa protegida y aceptación transaccional con versión, auditoría y replay durable opcional.

## Requirements

### Requirement: Comandos explícitos de ciclo de vida Company
El servicio SHALL exponer POST `/v1/companies/{companyId}/activate`, `/deactivate` y `/archive` con contexto de negocio válido, If-Match obligatorio de Company e Idempotency-Key opcional. SHALL conservar la prohibición de modificar status mediante PATCH y aplicar el envelope compartido.

#### Scenario: Frontera válida
- **WHEN** un comando recibe tenant, actor, proceso, companyId y versión esperada válidos
- **THEN** puede recuperar una aceptación o evaluar una transición propia
- **AND** devuelve el Process-Id validado del intento actual

#### Scenario: Estado por PATCH
- **WHEN** PATCH Company incluye status, incluso con el mismo valor actual
- **THEN** conserva su rechazo HU02 sin ejecutar una transición HU03

### Requirement: Intención vacía y cerrada de comandos Company
Los tres comandos SHALL admitir body ausente o `{}` como intención equivalente. Un body presente SHALL ser un objeto JSON vacío; null, arrays, escalares, propiedades, documentos malformados y tokens posteriores SHALL rechazarse antes de replay. Un body con contenido SHALL exigir application/json.

#### Scenario: Ausencia y objeto vacío
- **WHEN** una aceptación se reintenta cambiando únicamente entre body ausente y `{}`
- **THEN** conserva la misma intención y reproduce el resultado original

#### Scenario: Entrada no admitida
- **WHEN** se incluye status, reason u otra propiedad, o la raíz no es un objeto vacío válido
- **THEN** devuelve 400 descriptivo sin transición, aceptación ni evento
- **AND** un media type incompatible con contenido devuelve 415 mediante el envelope común

### Requirement: Matriz de transiciones Company
activate SHALL admitir DRAFT → ACTIVE e INACTIVE → ACTIVE. deactivate SHALL admitir únicamente ACTIVE → INACTIVE. archive SHALL admitir DRAFT → ARCHIVED e INACTIVE → ARCHIVED. Todas las demás transiciones SHALL rechazarse y ARCHIVED SHALL ser terminal.

#### Scenario: Activación y reactivación
- **WHEN** Company DRAFT o INACTIVE cumple todas las precondiciones de activación
- **THEN** pasa a ACTIVE con una nueva revisión Company

#### Scenario: Desactivación y archivado elegibles
- **WHEN** se desactiva una Company ACTIVE o se archiva una Company DRAFT/INACTIVE con versión vigente
- **THEN** pasa respectivamente a INACTIVE o ARCHIVED

#### Scenario: Transición prohibida
- **WHEN** se intenta desactivar DRAFT o archivar ACTIVE con versión vigente
- **THEN** devuelve 409 company-transition-not-allowed sin efectos

### Requirement: Comando nuevo al estado actual
Sin replay coincidente y con versión vigente, activate sobre ACTIVE o deactivate sobre INACTIVE SHALL devolver 409 company-already-in-requested-state. SHALL NOT aceptar un no-op ni consumir la clave. archive sobre ARCHIVED SHALL aplicar la terminalidad del recurso.

#### Scenario: Comando redundante nuevo
- **WHEN** una clave nueva o una solicitud sin clave apunta al estado actual ACTIVE/INACTIVE
- **THEN** devuelve 409 conservando versión, auditoría y eventos
- **AND** la clave permanece disponible para una futura solicitud admisible

#### Scenario: Replay de transición aceptada
- **WHEN** se repite una aceptación equivalente con su clave y If-Match originales
- **THEN** devuelve su 200 original aunque el destino ya se haya alcanzado o Company haya cambiado después

### Requirement: Pertenencia Company anterior a elegibilidad
Una aceptación nueva SHALL resolver Company exclusivamente dentro del tenant actual. Company ausente o ajena SHALL devolver el mismo 404 company-not-found antes de revelar versión, estado, matriz o referencias legales. SHALL NOT consultar Party para un recurso fuera del ámbito.

#### Scenario: UUID válido ajeno o ausente
- **WHEN** el companyId no se resuelve en el tenant y no hay replay coincidente
- **THEN** devuelve company-not-found sin consultar su propietario global ni proveedores

### Requirement: Precondición obligatoria y revisión compartida Company
If-Match SHALL ser único y decimal canónico no negativo int64 según la convención existente. Entrada inválida SHALL devolver 400. Sin replay coincidente, una revisión distinta de Company.version SHALL devolver 412 aggregate-version-mismatch antes de estados o elegibilidad. HU02 y HU03 SHALL compartir esa revisión.

#### Scenario: Versión obsoleta y recurso terminal
- **WHEN** una Company propia ARCHIVED recibe una solicitud nueva con versión obsoleta
- **THEN** devuelve 412 antes del conflicto por terminalidad

#### Scenario: Carrera de cambios Company
- **WHEN** dos transiciones efectivas, o una transición y un PATCH efectivo HU02, compiten contra la misma revisión con claves distintas o sin clave
- **THEN** confirma como máximo un cambio y el otro devuelve 412
- **AND** la revisión aumenta exactamente una vez

### Requirement: Referencia legal activa verificada al activar
Cada activación o reactivación nueva que supere las comprobaciones locales SHALL validar Party del tenant mediante el contrato interno existente y exigir LEGAL_ENTITY y ACTIVE. SHALL usar la proyección mínima de elegibilidad, sin exigir identificadores ni enriquecimiento público. La validación SHALL NOT acceder a la base Party ni alterar las reglas de registro HU01.

#### Scenario: Party elegible
- **WHEN** el proveedor confirma la identidad solicitada como LEGAL_ENTITY ACTIVE en el contexto tenant actual
- **THEN** satisface la precondición remota de ese intento

#### Scenario: Ausencia o incompatibilidad
- **WHEN** Party no se resuelve, es NATURAL_PERSON o está DRAFT/INACTIVE/ARCHIVED
- **THEN** devuelve respectivamente 404 party-reference-not-found, 409 party-must-be-legal-entity o 409 party-must-be-active
- **AND** no cambia Company, aceptaciones ni eventos

### Requirement: Fallos remotos controlados y sin bloqueos durante HTTP
La validación Party SHALL tener timeouts finitos, propagar Tenant-Id/User-Id/Process-Id actuales y ejecutarse sin mantener sesiones ni bloqueos de base de datos. Con precondiciones locales válidas y sin replay, indisponibilidad/timeout SHALL devolver 503 dependency-unavailable y contrato incompatible 502 dependency-response-invalid.

#### Scenario: Proveedor caído o respuesta inválida
- **WHEN** Party no está disponible, expira o devuelve identidad/tipos/envelope incompatibles
- **THEN** produce el fallo controlado correspondiente sin aceptación parcial ni exposición del payload remoto

#### Scenario: HTTP lento frente a otro escritor
- **WHEN** Party demora una activación y otra solicitud admisible necesita el bloqueo Company
- **THEN** la espera HTTP no retiene ese bloqueo
- **AND** la activación revalida los datos locales después de la respuesta remota

### Requirement: Una matriz legal actual ACTIVE para activar
La activación SHALL verificar localmente exactamente una HEADQUARTERS no CLOSED y que su estado sea ACTIVE. HEADQUARTERS DRAFT/INACTIVE SHALL seguir ocupando la reserva única sin satisfacer activación; las CLOSED históricas SHALL ignorarse. No se exigirán nuevas condiciones de dirección o contacto.

#### Scenario: Matriz ausente o no activa
- **WHEN** no existe matriz actual, o existe una en DRAFT/INACTIVE
- **THEN** rechaza respectivamente con 409 legal-headquarters-required o 409 active-headquarters-required

#### Scenario: Matriz reemplazada e historial
- **WHEN** hay matrices CLOSED históricas y una sola matriz actual ACTIVE
- **THEN** el historial no impide cumplir la precondición local

### Requirement: Coordinación Company antes de matriz
Cada aceptación SHALL proteger Company dentro del tenant y revalidar su revisión y transición. La activación SHALL proteger después la matriz y verificar su elegibilidad en la misma transacción. SHALL coordinarse con HU04/HU05 siguiendo Company → Site, incluidos casos sin matriz, sin cambiar Site por adquirir protección.

#### Scenario: Activación confirma primero
- **WHEN** la activación confirma antes de un cierre, desactivación o reclasificación incompatible de su matriz
- **THEN** Company queda ACTIVE con matriz ACTIVE y HU05 rechaza la retirada con active-headquarters-required

#### Scenario: Retirada de matriz confirma primero
- **WHEN** cierre, desactivación o reclasificación confirma antes de la activación concurrente
- **THEN** la activación revalida y no confirma sin una matriz actual ACTIVE

#### Scenario: Preparación de sede sin cambiar Company
- **WHEN** registrar o activar Site no modifica Company.version durante la validación remota
- **THEN** la comprobación final vuelve a consultar la matriz en lugar de confiar solo en la versión Company

### Requirement: Archivado bloquea nuevas mutaciones ordinarias
Con versión vigente y sin replay coincidente, Company ARCHIVED SHALL rechazar los tres comandos con 409 company-archived. SHALL conservar las prohibiciones HU02/HU04/HU05 sobre información, registro y mutaciones Site, incluidos no-op. El archivado SHALL preservar Company, Sites, direcciones, contactos y reservas de identidad/código.

#### Scenario: Terminalidad y lecturas históricas
- **WHEN** se archiva una Company DRAFT/INACTIVE
- **THEN** sus recursos siguen almacenados y las consultas conservan sus contratos actuales
- **AND** ninguna nueva activación, desactivación, archivado o modificación ordinaria resulta aceptada

#### Scenario: Carrera con escritores Site
- **WHEN** archive compite con registro o mutación de cualquier Site
- **THEN** si archive confirma primero se rechaza el escritor nuevo según HU04/HU05
- **AND** si Site confirma primero, su aceptación precede al archivado sin alterar la revisión Company

### Requirement: Operatividad Site derivada e independencia de agregados
Toda transición Company SHALL preservar estados, versiones y auditoría Site y sus hijos. Las consultas Site SHALL calcular operatividad como Company ACTIVE y Site ACTIVE, sin columna mutable ni cascada. SHALL conservar los snapshots y la operatividad histórica de replays HU04/HU05.

#### Scenario: Suspensión y reactivación de Company
- **WHEN** Company con Site ACTIVE pasa a INACTIVE y posteriormente vuelve a ACTIVE cumpliendo precondiciones
- **THEN** el GET Site pasa de true a false y luego true sin modificar Site.version ni su auditoría

#### Scenario: Replay histórico frente a consulta actual
- **WHEN** Company se desactiva o archiva después de una aceptación Site con operatividad true
- **THEN** GET calcula false y el replay Site conserva su true original

### Requirement: Incremento y auditoría únicos de transición
Cada transición aceptada SHALL incrementar Company.version exactamente una vez, fijar updatedAt a un único instante UTC de microsegundos y updatedBy al actor exacto. SHALL conservar identidad, Party, información operacional y auditoría de creación. Respuesta, completado y evento habilitado SHALL compartir valores resultantes.

#### Scenario: Hecho confirmado coherente
- **WHEN** confirma cualquiera de los tres comandos
- **THEN** Company queda en la revisión esperada más uno con estado y auditoría coherentes en todos sus resultados
- **AND** Site y sus hijos permanecen intactos

### Requirement: Respuesta operacional completa sin enriquecimiento
Los tres comandos y sus replays SHALL devolver 200 successful con companyId, partyId, code, operatingName, status, version y cuatro campos de auditoría; description/logoUrl se incluirán solo si tienen valor. SHALL omitir tenantId, Party enriquecida y datos Site. GET/POST Company SHALL conservar la representación HU01.

#### Scenario: Resultado y opcionales nulos
- **WHEN** una transición confirma con description/logoUrl nulos
- **THEN** devuelve la representación operacional omitiendo esos opcionales
- **AND** no consulta identificadores Party para componer la respuesta

### Requirement: Operaciones idempotentes independientes y opcionales
Idempotency-Key SHALL ser opcional, única, opaca, no blanca y de hasta 128 puntos de código. Su ámbito SHALL ser tenant/operación/clave con company.activate.v1, company.deactivate.v1 y company.archive.v1 independientes. Una aceptación con otra intención en el mismo ámbito SHALL devolver 409 sin revelar el snapshot.

#### Scenario: Separación y colisión
- **WHEN** se reutiliza la misma cadena en otro tenant u operación
- **THEN** puede identificar una aceptación independiente
- **AND** reutilizarla en la misma operación/tenant para otra Company devuelve idempotency-key-already-used-with-different-request

#### Scenario: Sin clave
- **WHEN** se omite Idempotency-Key
- **THEN** puede aceptar la transición sin guardar completado ni prometer replay durable

### Requirement: Huella de intención original Company
La huella SHALL vincular tenant, operación, companyId e If-Match original mediante HMAC SHA-256 con política versionada y clave histórica identificada. SHALL excluir actor, proceso, clave idempotente, estado actual, Party/matriz observadas, valores generados y modo outbox. Body ausente y `{}` SHALL ser equivalentes.

#### Scenario: Otra versión original
- **WHEN** una clave aceptada se reintenta cambiando If-Match o Company
- **THEN** devuelve conflicto de clave antes de evaluar la versión o el estado actuales

#### Scenario: Actor, proceso o keyring posteriores
- **WHEN** una intención equivalente se repite con otro actor/proceso o después de activar K2 conservando K1
- **THEN** verifica con la política/K1 almacenadas y conserva la aceptación original

### Requirement: Replay original anterior a precondiciones actuales
Una solicitud válida coincidente con aceptación durable SHALL devolver su 200 y snapshot originales antes de evaluar estado/versión actuales o consultar Party/matriz. SHALL conservar datos y auditoría originales con Process-Id actual, sin nuevas escrituras ni eventos. SHALL funcionar tras archivado, reinicio y cambio de modo outbox.

#### Scenario: Reintento después de otro ciclo
- **WHEN** una activación aceptada se repite después de desactivar y archivar Company, con Party inaccesible
- **THEN** devuelve el snapshot ACTIVE original sin reactivar ni consultar proveedores

#### Scenario: Modo posterior diferente
- **WHEN** una aceptación de disabled se reproduce después de habilitar almacenamiento outbox
- **THEN** no genera un evento retrospectivo

### Requirement: Aceptación concurrente recuperada antes del rechazo
Después de esperar protección Company y antes de evaluar estado actual, la operación SHALL volver a resolver la aceptación opcional. Si otro intento equivalente confirmó durante Party y queda plazo para resolverlo, SHALL recuperar su resultado incluso si esta consulta falló. Una colisión de clave SHALL resolverse después del rollback/cierre del intento perdedor.

#### Scenario: Competidor confirma durante HTTP
- **WHEN** un intento no encontró completado, otro equivalente confirma y el primero termina su validación remota con plazo restante
- **THEN** el primero reproduce el resultado durable antes de rechazar por versión, estado o fallo remoto

#### Scenario: Clave compartida entre Companies
- **WHEN** dos Companies del tenant compiten por la misma clave/operación con intenciones diferentes
- **THEN** confirma una aceptación y la perdedora revierte sus escrituras/evento y devuelve conflicto de clave

### Requirement: Aceptación y evento atómicos condicionados por modo
Company, su completado opcional y su evento habilitado SHALL confirmar o revertirse juntos. activate SHALL producir solo company.activated.v1; deactivate solo company.deactivated.v1; archive solo company.updated.v1 con ARCHIVED. disabled SHALL omitir almacenamiento y stored-only/published SHALL guardar un evento; HU03 SHALL NOT publicar RabbitMQ.

#### Scenario: Tres comandos y tres modos
- **WHEN** confirma una transición bajo cualquiera de los modos existentes
- **THEN** guarda cero eventos en disabled o exactamente el evento aplicable en stored-only/published, con o sin clave

#### Scenario: Fallo obligatorio o rechazo
- **WHEN** falla Company, completado requerido o evento habilitado, o se rechaza una precondición
- **THEN** no queda efecto parcial confirmado ni completado de fallo
- **AND** la clave permanece disponible si el intento revirtió

### Requirement: Plazo total finito y recuperación sin repetir transición
Lookup, preparación local, Party, bloqueos, escrituras, finalización, limpieza y recuperación SHALL compartir un plazo finito configurable. Un resultado durable irresoluble SHALL devolver 503 sin afirmar rollback ni repetir la transición. La recuperación SHALL comenzar únicamente después de finalizar/cerrar la transacción fallida.

#### Scenario: Commit confirmado y respuesta perdida
- **WHEN** se pierde la respuesta de una aceptación con clave
- **THEN** un reintento equivalente recupera su resultado original sin otro incremento ni evento

#### Scenario: Agotamiento, cancelación o commit incierto
- **WHEN** vence el plazo o no se confirma la finalización de la transacción
- **THEN** devuelve un fallo controlado sin abandonar otra escritura suscrita ni iniciar recuperación concurrente con esa transacción
- **AND** sin clave no promete reproducir el resultado original

### Requirement: Operaciones locales y límite de validación legal
Desactivar, archivar y resolver replay SHALL funcionar sin Party, Geography ni RabbitMQ disponibles. La activación SHALL validar Party para el intento nuevo, sin prometer atomicidad entre servicios ni sincronización automática posterior. El arranque SHALL conservar independencia de proveedores y broker.

#### Scenario: Desactivación y archivado con proveedor caído
- **WHEN** la base local funciona y Company cumple revisión y transición
- **THEN** puede desactivarse o archivarse sin realizar consultas remotas

#### Scenario: Party cambia posteriormente
- **WHEN** Party cambia de estado después de la validación remota de una activación
- **THEN** HU03 no dispara otra transición automática ni modifica estados Site
- **AND** una reactivación nueva vuelve a comprobar la referencia actual
