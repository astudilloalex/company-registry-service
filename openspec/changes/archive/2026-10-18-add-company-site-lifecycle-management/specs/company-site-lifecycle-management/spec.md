# Spec Delta

## Purpose

Administrar el ciclo de vida independiente de Site y aceptar sus mutaciones locales con protección de Company, versión compartida con hijos, auditoría, replay durable y consistencia de sede legal.

## ADDED Requirements

### Requirement: Frontera de mutaciones HU05
PATCH Site y POST activate/deactivate/close bajo `/v1/companies/{companyId}/sites/{siteId}` SHALL exigir contexto Tenant-Id/User-Id/Process-Id válido, UUIDs de ruta y un If-Match de Site. SHALL admitir Idempotency-Key opcional y devolver Process-Id del intento actual. Ninguna mutación SHALL aceptar identidad, estado o versión desde el body.

#### Scenario: Solicitud local válida
- **WHEN** una de las cuatro operaciones recibe contexto, ruta, entrada y precondición válidos
- **THEN** puede resolver replay o intentar una aceptación local dentro de tenant y Company

#### Scenario: Frontera inválida
- **WHEN** falla un header obligatorio, UUID, clave presente o estructura del body
- **THEN** devuelve 400 descriptivo antes de devolver un replay o escribir
- **AND** el Process-Id se devuelve únicamente si pudo validarse

### Requirement: Comandos sin parámetros de body
activate, deactivate y close SHALL admitir body ausente o `{}` como la misma intención. Cualquier body presente SHALL ser un objeto JSON vacío; null, arrays, escalares, propiedades, JSON inválido y tokens posteriores SHALL rechazarse. PATCH SHALL conservar su contrato de body obligatorio.

#### Scenario: Ausencia y objeto vacío equivalentes
- **WHEN** un comando aceptado se repite con la misma clave, ruta y versión cambiando solo entre ausencia de body y `{}`
- **THEN** devuelve el mismo resultado original

#### Scenario: Parámetro no soportado
- **WHEN** un comando incluye status, reason u otra propiedad, o una raíz no objeto
- **THEN** devuelve 400 descriptivo sin aceptar ni ejecutar la transición

### Requirement: Matriz de transiciones Site
activate SHALL admitir DRAFT → ACTIVE e INACTIVE → ACTIVE. deactivate SHALL admitir ACTIVE → INACTIVE. close SHALL admitir DRAFT → CLOSED, ACTIVE → CLOSED e INACTIVE → CLOSED, sujeto a invariantes. CLOSED SHALL ser terminal y todas las demás transiciones SHALL rechazarse.

#### Scenario: Activación preparada
- **WHEN** un Site DRAFT o INACTIVE se activa bajo Company DRAFT, ACTIVE o INACTIVE cumpliendo versión e invariantes
- **THEN** Site pasa a ACTIVE sin cambiar el estado de Company

#### Scenario: Desactivación
- **WHEN** un Site ACTIVE elegible se desactiva
- **THEN** pasa a INACTIVE

#### Scenario: Cierre desde estado no terminal
- **WHEN** un Site DRAFT, ACTIVE o INACTIVE elegible se cierra
- **THEN** pasa a CLOSED conservando su identidad y registros históricos

#### Scenario: Estado inicial incompatible
- **WHEN** se intenta desactivar DRAFT u otra transición fuera de la matriz
- **THEN** devuelve 409 sin cambiar Site, completados o eventos

### Requirement: Comandos nuevos al estado actual
Sin replay previo y con versión vigente, un comando nuevo cuyo estado destino ya esté alcanzado SHALL devolver 409. SHALL NOT tratarse como no-op exitoso ni consumir clave. La restricción terminal CLOSED SHALL aplicarse también a un nuevo close sobre CLOSED.

#### Scenario: Activar activo o desactivar inactivo
- **WHEN** activate apunta a ACTIVE o deactivate apunta a INACTIVE con versión vigente y padre editable
- **THEN** devuelve 409 site-already-in-requested-state sin efectos

#### Scenario: Cerrar cerrado
- **WHEN** close apunta a CLOSED sin replay y con versión vigente
- **THEN** devuelve 409 site-closed
- **AND** un reintento equivalente de un cierre aceptado devuelve su 200 original

### Requirement: Padre archivado y sede cerrada inmutables
Sin replay coincidente, Company ARCHIVED SHALL bloquear toda mutación nueva HU05 y Site CLOSED SHALL bloquear PATCH y comandos, incluso no-op. SHALL evaluar versión antes de estados. Los GET históricos SHALL continuar disponibles y un cambio de Company SHALL NOT sobrescribir estados Site.

#### Scenario: Las cuatro operaciones bajo ARCHIVED
- **WHEN** una Company ARCHIVED recibe PATCH, activate, deactivate o close con Site propio y versión vigente
- **THEN** devuelve 409 site-mutation-not-allowed-for-archived-company sin aceptación nueva

#### Scenario: Cerrado bajo padre editable
- **WHEN** Site CLOSED recibe cualquiera de las cuatro operaciones nuevas con versión vigente
- **THEN** devuelve 409 site-closed aunque PATCH sea `{}`

#### Scenario: Independencia de estados
- **WHEN** Company se vuelve INACTIVE y Site conserva ACTIVE
- **THEN** Site mantiene estado, versión y auditoría y las consultas calculan operatividad false

### Requirement: Aislamiento de mutaciones por propiedad
Una aceptación nueva SHALL resolver Company en el tenant y luego Site dentro de esa Company y tenant. Padre inexistente/ajeno SHALL devolver 404 company-not-found; Site inexistente/ajeno SHALL devolver 404 site-not-found. Estos resultados SHALL preceder a comprobar versiones o estados de recursos fuera del ámbito.

#### Scenario: Padre ajeno o ausente
- **WHEN** Company no se resuelve dentro del tenant
- **THEN** devuelve el mismo company-not-found sin consultar globalmente su propiedad

#### Scenario: Site fuera de Company
- **WHEN** Company es propia pero Site es inexistente, de otra Company o de otro tenant
- **THEN** devuelve site-not-found sin revelar dueño, versión o estado
- **AND** este 404 precede a un rechazo por estado del padre

### Requirement: Versión esperada obligatoria de Site
If-Match SHALL ser único y decimal canónico no negativo int64 según la convención interna existente. Falta, repetición o formato inválido SHALL devolver 400. Sin replay, una versión distinta de Site.version SHALL devolver 412 aggregate-version-mismatch, también para no-op y recursos terminales.

#### Scenario: Cabecera inválida
- **WHEN** falta If-Match, se repite o contiene comillas, signo, comodín, ceros iniciales, espacios internos o desbordamiento
- **THEN** devuelve 400 sin recuperar ni guardar aceptación

#### Scenario: Versión obsoleta
- **WHEN** Site propio tiene una revisión diferente de la esperada y no existe replay coincidente
- **THEN** devuelve 412 antes de comprobar estados, transición o sede legal

#### Scenario: Reintento de aceptación anterior
- **WHEN** se repite la solicitud original de una aceptación con su If-Match original aunque Site haya avanzado
- **THEN** el replay coincidente devuelve la revisión aceptada sin aplicar la comparación de versión actual

### Requirement: Protección concurrente Company antes de Site
Toda aceptación nueva HU05, incluso no-op, SHALL proteger Company antes de Site y revalidar propiedad, versión, estados e invariantes en esa protección. SHALL coordinarse con escritores de ciclo de vida Company que sigan el mismo orden. Adquirir protección SHALL NOT alterar versión, estado ni auditoría Company.

#### Scenario: Carrera con archivado
- **WHEN** una mutación de cualquier tipo de Site compite con archivado de su Company
- **THEN** si archivado confirma primero, la mutación nueva se rechaza
- **AND** si la mutación confirma primero, precede al archivado sin modificar la revisión del padre

#### Scenario: Dos cambios con la misma versión
- **WHEN** dos cambios efectivos del mismo Site usan la misma versión con claves distintas o sin clave
- **THEN** confirma como máximo uno y el otro devuelve 412, con un único incremento

#### Scenario: Información y mutación de hijo concurrentes
- **WHEN** un cambio HU05 compite con una mutación de dirección/contacto que utiliza la misma versión Site y protocolo compatible
- **THEN** no sobrescriben silenciosamente cambios y confirma como máximo un cambio efectivo contra la revisión original

### Requirement: Sede legal activa preservada
Cada Company SHALL tener como máximo un HEADQUARTERS no CLOSED. Una mutación SHALL NOT cerrar, desactivar o reclasificar la sede legal ACTIVE de una Company ACTIVE. La coordinación con activación Company SHALL preservar que una Company que queda ACTIVE tenga exactamente una sede legal ACTIVE.

#### Scenario: Operaciones protegidas
- **WHEN** cerrar, desactivar o reclasificar retiraría la única sede legal ACTIVE de una Company ACTIVE
- **THEN** devuelve 409 active-headquarters-required y conserva el estado previo

#### Scenario: Carrera con activación Company
- **WHEN** activación Company y una mutación incompatible de su sede legal compiten con protección compatible
- **THEN** si la activación confirma primero, se rechaza la mutación incompatible
- **AND** si la mutación confirma primero, la activación no puede confirmar sin una sede legal ACTIVE válida

#### Scenario: Reemplazo secuencial V1
- **WHEN** Company está INACTIVE, se cierra la sede legal, se registra otra con código libre y se activa
- **THEN** el cierre libera la reserva de HEADQUARTERS y conserva el código de la sede cerrada
- **AND** la posterior reactivación Company queda a cargo de HU03

### Requirement: Revisión y auditoría independientes
Cada cambio efectivo SHALL incrementar Site.version exactamente una vez y fijar updatedAt/updatedBy a un único instante UTC con precisión de microsegundos y actor exacto. SHALL conservar createdAt/createdBy y la identidad. Las mutaciones de hijos SHALL compartir Site.version; no-op y replay SHALL conservar versión y auditoría Site.

#### Scenario: Corrección o transición efectiva
- **WHEN** confirma un cambio HU05
- **THEN** respuesta, snapshot y evento habilitado coinciden en revisión, datos y auditoría resultantes
- **AND** Company mantiene sus cuatro valores de auditoría y su versión

#### Scenario: Revisión compartida futura
- **WHEN** una mutación compatible de dirección/contacto incrementa Site.version
- **THEN** un comando HU05 nuevo con la revisión anterior recibe 412

### Requirement: Respuesta completa de aceptación Site
Las cuatro mutaciones SHALL devolver 200 successful con los doce campos públicos de Site. En aceptaciones nuevas, effectivelyOperational SHALL calcularse bajo protección como Company ACTIVE y Site resultante ACTIVE. El replay SHALL conservar el booleano original; GET SHALL continuar calculándolo desde datos actuales.

#### Scenario: Preparación y activación posterior de Company
- **WHEN** activar Site bajo Company DRAFT devuelve ACTIVE/versión 1/false y posteriormente Company se activa
- **THEN** un GET puede devolver ACTIVE/versión 1/true
- **AND** el replay original sigue devolviendo ACTIVE/versión 1/false

#### Scenario: Cierre y respuesta local
- **WHEN** confirma close
- **THEN** devuelve Site completo CLOSED con operatividad false, versión y auditoría resultantes
- **AND** no elimina el recurso ni devuelve un éxito sin representación

### Requirement: Identidad idempotente por operación
Idempotency-Key SHALL ser opcional en las cuatro mutaciones y, si aparece, única, opaca, no blanca y de hasta 128 puntos de código. SHALL usar tenant/operación/clave con operaciones company.site.update.v1, company.site.activate.v1, company.site.deactivate.v1 y company.site.close.v1 independientes.

#### Scenario: Clave ausente o inválida
- **WHEN** se omite la clave o se presenta repetida, blanca o excesiva
- **THEN** la omisión permite la operación sin completado y un valor inválido devuelve 400

#### Scenario: Misma cadena en operaciones o tenants distintos
- **WHEN** una cadena se usa en activate y deactivate, o en tenants diferentes
- **THEN** identifica aceptaciones independientes

#### Scenario: Misma clave en otro recurso
- **WHEN** una clave aceptada se reutiliza en la misma operación y tenant para otro Site o Company
- **THEN** devuelve 409 idempotency-key-already-used-with-different-request sin revelar el snapshot

### Requirement: Huella de intención original Site
La huella SHALL vincular tenant, operación, Company, Site, If-Match original e intención normalizada con presencia explícita de campos PATCH. SHALL usar política HMAC SHA-256 versionada y clave histórica identificada. SHALL excluir actor, proceso, estados actuales, valores generados, modo outbox y la propia clave idempotente.

#### Scenario: Solicitudes equivalentes
- **WHEN** cambia orden JSON, presentación canónica de code/name, actor o proceso y se conserva la misma intención original
- **THEN** permite replay bajo la misma identidad idempotente

#### Scenario: Cambio de versión o presencia
- **WHEN** cambia If-Match o la presencia/valor efectivo de un campo, incluido `{}` frente a `{"name":"CENTRO"}` aunque ambos fueran no-op
- **THEN** devuelve el conflicto de clave antes de evaluar la revisión actual

#### Scenario: Histórico retenido
- **WHEN** una aceptación con K1 se reintenta después de activar K2 o reiniciar otra instancia
- **THEN** verifica con la política y K1 almacenadas, sin TTL ni reescritura del completado

### Requirement: Replay durable anterior al estado actual
Una solicitud válida coincidente con aceptación durable SHALL devolver su 200 y snapshot originales antes de evaluar existencia, versión o estados actuales. SHALL preservar datos, operatividad y auditoría originales, usando Process-Id actual. SHALL NOT incrementar revisiones, crear completados adicionales ni generar eventos.

#### Scenario: Cambios, cierre o archivado posteriores
- **WHEN** una aceptación se repite tras modificaciones Site, cierre o archivado Company
- **THEN** devuelve exactamente los datos originales sin consultar el estado actual ni reejecutar la operación

#### Scenario: Cambio de modo outbox
- **WHEN** se repite una aceptación creada con almacenamiento disabled después de habilitar outbox
- **THEN** no crea un evento retrospectivo

#### Scenario: Aceptación concurrente durante espera
- **WHEN** un intento no encontró aceptación inicialmente y otra petición equivalente confirma mientras espera protección
- **THEN** vuelve a resolver la aceptación antes de rechazar por revisión o estado actuales

#### Scenario: Material histórico inválido
- **WHEN** falta política/clave histórica o el snapshot no satisface su versión, tipos, identidad o propiedad
- **THEN** devuelve 500 server-error saneado sin reconstruir el resultado ni presentar un conflicto de contenido ficticio

### Requirement: Aceptación atómica y claves consumidas solo por éxito
Cambio Site, completado cuando hay clave y evento habilitado SHALL confirmar o revertirse juntos. Un no-op con clave SHALL confirmar solo su aceptación. Los rechazos SHALL NOT consumirse como aceptaciones. Una colisión de clave SHALL resolverse contra la aceptación durable después de revertir el intento perdedor.

#### Scenario: Fallo obligatorio
- **WHEN** falla escritura Site, completado o evento antes del commit
- **THEN** no queda efecto parcial y la clave queda disponible si el intento revirtió

#### Scenario: Misma clave en paralelo
- **WHEN** dos solicitudes equivalentes compiten por la misma identidad
- **THEN** confirma una aceptación y como máximo un cambio/evento, y la perdedora recupera el mismo resultado

#### Scenario: Clave compartida entre Companies
- **WHEN** dos solicitudes distintas de la misma operación/tenant bajo Companies diferentes compiten por la misma clave
- **THEN** la perdedora revierte cualquier cambio/evento provisional y devuelve conflicto de clave sin exponer datos de la ganadora

### Requirement: Plazo finito y resultado incierto explícito
Consulta de aceptación, adquisición de protección, escrituras, finalización, limpieza y recuperación SHALL tener un plazo finito configurable. Si no puede establecerse el resultado durable, SHALL devolver 503 dependency-unavailable sin afirmar rollback ni reejecutar ciegamente. La recuperación SHALL iniciar solo tras finalizar/cerrar la transacción fallida.

#### Scenario: Commit confirmado y respuesta perdida
- **WHEN** el commit confirmó una aceptación con clave pero se perdió su respuesta
- **THEN** un reintento equivalente puede recuperar el resultado durable sin otra mutación

#### Scenario: Agotamiento o finalización incierta
- **WHEN** vence el plazo o no se confirma el cierre de una transacción fallida
- **THEN** devuelve 503 sin abrir una recuperación que compita con esa transacción ni abandonar otra escritura suscrita

#### Scenario: Operación sin clave
- **WHEN** una operación sin clave termina con resultado de commit incierto
- **THEN** devuelve 503 sin prometer reproducción de su resultado original ni repetirla automáticamente

### Requirement: Mutaciones locales sin proveedores
PATCH y comandos HU05 SHALL resolver propiedad, estados e invariantes con Company Registry local y SHALL funcionar sin Party Registry, Geographic Reference ni RabbitMQ disponibles. La publicación y los workflows Company/direcciones/contactos SHALL permanecer fuera del efecto de estas operaciones.

#### Scenario: Dependencias remotas inaccesibles
- **WHEN** la base local funciona y los proveedores/broker están caídos
- **THEN** una mutación o replay válido puede completar sin invocarlos
