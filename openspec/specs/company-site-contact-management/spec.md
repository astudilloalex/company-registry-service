# company-site-contact-management Specification

## Purpose

Gestionar los canales de contacto subordinados a Site con valores canónicos, selección primaria por tipo, eliminación histórica y aceptación consistente con la revisión compartida del agregado.

## Requirements

### Requirement: Operaciones de contacto dentro de Site
El servicio SHALL exponer POST/GET de colección en `/v1/companies/{companyId}/sites/{siteId}/contacts` y GET/PATCH/DELETE individual con `/{contactId}`. POST SHALL devolver 201; las demás operaciones 200 con el envelope compartido. Todas SHALL exigir contexto de negocio; los GET SHALL NOT exigir headers de mutación.

#### Scenario: Ciclo completo de contacto
- **WHEN** un cliente crea, consulta, modifica y elimina un contacto propio con entradas válidas
- **THEN** obtiene las representaciones aceptadas y la eliminación conserva el registro histórico

### Requirement: Pertenencia y ausencia de contactos
Todo acceso SHALL calificarse por tenant/Company/Site. Company ausente o ajena SHALL producir 404 company-not-found; después Site ausente o ajeno SHALL producir site-not-found; después contacto ausente, ajeno o eliminado SHALL producir contact-not-found. Las mutaciones individuales nuevas SHALL comprobar esta ausencia antes de la versión Site.

#### Scenario: Contacto de otra sede o tenant
- **WHEN** una ruta propia contiene un contactId que no pertenece a su Site
- **THEN** devuelve 404 contact-not-found sin revelar propietario, versión ni datos ajenos

#### Scenario: Eliminación previa y revisión obsoleta
- **WHEN** una mutación nueva apunta a un contacto eliminado con If-Match obsoleto
- **THEN** devuelve 404 contact-not-found sin otro efecto

### Requirement: Creación explícita y campos admitidos
POST SHALL exigir contactType y value. SHALL admitir solo EMAIL/PHONE/MOBILE/WHATSAPP, tras normalizar el tipo a mayúsculas y retirar espacios exteriores. label omitido SHALL equivaler a null e isPrimary omitido a false. El servidor SHALL generar contactId UUIDv7 y auditoría; el cliente SHALL NOT establecer propiedad o metadatos administrados.

#### Scenario: Primer contacto sin selección primaria
- **WHEN** se registra un contacto válido sin label ni isPrimary
- **THEN** se crea con label null y no primario aunque sea el primero de su tipo
- **AND** sus pares de auditoría de creación y actualización comparten actor e instante

### Requirement: Valores canónicos por canal
EMAIL y teléfonos SHALL cumplir la política especializada de business-text-normalization. label SHALL usar strip y mayúsculas Locale.ROOT con máximo 80 puntos de código canónicos, preservando acentos y espacios internos. Un label presente no nulo SHALL rechazar blanco. Comparación de cambios y duplicados SHALL usar los valores canónicos.

#### Scenario: Email y etiqueta equivalentes
- **WHEN** se registra EMAIL con ` Ventas@Example.COM ` y label ` atención  comercial `
- **THEN** se almacena ventas@example.com y `ATENCIÓN  COMERCIAL`
- **AND** se conservan exactamente actor, clave y proceso

### Requirement: PATCH con presencia y limpieza explícitas
PATCH SHALL admitir contactType, value, label e isPrimary. Los omitidos SHALL conservarse; label null SHALL limpiarlo. contactType/value presentes SHALL rechazar null o blanco e isPrimary SHALL exigir booleano JSON no nulo. No SHALL coercionar números o booleanos a texto ni convertir label blanco en una limpieza.

#### Scenario: Omisión, null y blanco distintos
- **WHEN** se omite label, se envía null o se envía blanco
- **THEN** respectivamente se conserva, se elimina o se rechaza con 400

#### Scenario: Valor obligatorio limpiado
- **WHEN** PATCH envía value null o contactType null
- **THEN** rechaza toda la solicitud con el código descriptivo del campo

### Requirement: Reclasificación y validación del resultado
PATCH SHALL permitir cambiar contactType conservando contactId y propiedad. SHALL validar el valor resultante bajo el tipo resultante y rechazar duplicados de destino sin fusionar contactos. Si el valor omitido resulta incompatible con el nuevo canal, SHALL rechazar la mutación completa; el cliente deberá proporcionar un value compatible.

#### Scenario: Número reclasificado
- **WHEN** MOBILE pasa a WHATSAPP sin otro cambio y su número cumple la política telefónica
- **THEN** conserva el número canónico y el identificador bajo WHATSAPP

#### Scenario: Conversión incompatible
- **WHEN** PHONE pasa a EMAIL sin enviar un email válido
- **THEN** devuelve 400 contact-value-invalid sin cambiar tipo, primarios o revisión

### Requirement: Unicidad vigente por tipo y valor
Dos contactos no eliminados del mismo Site y contactType SHALL NOT compartir el valor canónico. La creación, actualización o reclasificación duplicada SHALL producir 409 contact-value-already-exists. Los eliminados SHALL liberar esa reserva; otros tipos o Sites SHALL poder reutilizar el valor. La persistencia SHALL imponer esta unicidad.

#### Scenario: Emails equivalentes y canales diferentes
- **WHEN** existe EMAIL ventas@example.com y se crea otro EMAIL con Ventas@Example.COM
- **THEN** rechaza el duplicado
- **AND** un mismo número puede coexistir como MOBILE y WHATSAPP

#### Scenario: Duplicado al promover o reclasificar
- **WHEN** una mutación produciría un valor duplicado y además sustituiría un primario
- **THEN** rechaza todo sin desmarcar al primario anterior ni consumir revisión, clave o evento

### Requirement: Selección primaria explícita por tipo
Cada Site SHALL admitir como máximo un primario vigente por contactType y permitir cero. La selección SHALL ser explícita; PATCH que omite isPrimary SHALL conservarlo. Desmarcar o eliminar un primario SHALL NOT seleccionar otro automáticamente. Los cuatro tipos SHALL mantener selecciones independientes.

#### Scenario: Primarios coexistentes
- **WHEN** se selecciona un primario EMAIL, PHONE, MOBILE y WHATSAPP
- **THEN** pueden coexistir los cuatro sin permitir dos primarios del mismo tipo

#### Scenario: Retirar el primario
- **WHEN** se elimina o desmarca al primario y existen contactos alternativos de su tipo
- **THEN** ninguno se promueve automáticamente

### Requirement: Sustitución primaria atómica
Crear o actualizar con isPrimary true SHALL desmarcar al primario anterior del tipo y seleccionar al contacto objetivo atómicamente. Solo contactos vigentes SHALL ser elegibles. El cambio conjunto SHALL consumir una sola revisión Site y un solo evento habilitado, independientemente del número de contactos afectados.

#### Scenario: Sustitución mediante POST
- **WHEN** se crea B con isPrimary true y A era primario del mismo tipo
- **THEN** B queda primario y A no primario con una única revisión y auditoría común

#### Scenario: Selección concurrente
- **WHEN** dos selecciones efectivas compiten con la misma revisión Site
- **THEN** confirma como máximo una y la otra devuelve 412, sin doble primario ni cambios parciales

### Requirement: Primarios durante reclasificación
Cambiar contactType omitiendo isPrimary SHALL conservar su valor. Un primario reclasificado SHALL sustituir al primario de destino y dejar el tipo anterior sin reemplazo automático. isPrimary false explícito SHALL permitir reclasificar sin desplazar al primario de destino.

#### Scenario: Primario pasa de MOBILE a WHATSAPP
- **WHEN** A es primario MOBILE, B es primario WHATSAPP y A cambia únicamente a WHATSAPP sin duplicar valor
- **THEN** A queda primario WHATSAPP, B no primario y MOBILE sin primario, en una aceptación

#### Scenario: Reclasificación desmarcada
- **WHEN** A cambia a WHATSAPP con isPrimary false
- **THEN** conserva al primario WHATSAPP anterior

### Requirement: Estados editables y consultas terminales
Las nuevas mutaciones SHALL permitirse con Company y Site en DRAFT/ACTIVE/INACTIVE. Company ARCHIVED o Site CLOSED SHALL bloquearlas con 409, incluso no-op, después de ausencia y revisión. GET SHALL permitir esos estados terminales para contactos no eliminados; el replay SHALL conservar la aceptación previa.

#### Scenario: Preparación de una sede
- **WHEN** Site y Company son editables aunque no estén operativos
- **THEN** pueden gestionarse contactos sin activar ni cambiar el estado de ninguno

#### Scenario: Estado terminal con contacto existente
- **WHEN** una mutación nueva con revisión vigente encuentra Company ARCHIVED o Site CLOSED
- **THEN** devuelve el conflicto de estado y siguen disponibles GET y replay válidos

### Requirement: Revisión compartida del Site
POST/PATCH/DELETE SHALL exigir If-Match de Site, también para PATCH no-op. Cada cambio efectivo SHALL incrementar Site.version exactamente una vez y conservar los datos y revisión de Company. Contact SHALL NOT tener revisión independiente. Todos los escritores compatibles de Site e hijos SHALL coordinarse sobre la misma revisión.

#### Scenario: Carrera con dirección o información Site
- **WHEN** un contacto compite con una dirección o cambio Site usando la misma revisión
- **THEN** confirma como máximo un cambio efectivo y el otro devuelve 412 sin sobrescribirlo

### Requirement: Auditoría común de filas afectadas
Todo contacto efectivamente modificado, incluido un primario desmarcado indirectamente, SHALL compartir updatedAt/updatedBy con Site: un instante UTC de microsegundos y el actor exacto. SHALL preservar createdAt/createdBy y auditoría de filas intactas. La creación SHALL usar ese instante y actor para ambos pares.

#### Scenario: Promoción con desmarcado indirecto
- **WHEN** promover B desmarca A
- **THEN** A, B y Site comparten auditoría de actualización y conservan las auditorías originales de creación

### Requirement: PATCH sin cambio efectivo
Un PATCH válido cuyo resultado canónico coincida con el persistido SHALL devolver 200 con contacto, revisión y auditoría conservados. SHALL verificar pertenencia, revisión y editabilidad sin evento. Con clave SHALL conservar una aceptación durable del no-op; sin clave SHALL omitirla.

#### Scenario: Entrada equivalente o vacía
- **WHEN** llega `{}`, un email equivalente por minúsculas, un teléfono equivalente por formato o una limpieza redundante
- **THEN** con precondiciones válidas no cambia ninguna fila de negocio ni genera evento
- **AND** una clave opcional permite reproducir ese resultado original

### Requirement: Identidad idempotente independiente por operación
POST SHALL exigir Idempotency-Key y PATCH/DELETE SHALL admitirla opcionalmente. La identidad SHALL ser tenant/operación/clave, con namespaces company.site.contact.create.v1, company.site.contact.update.v1 y company.site.contact.delete.v1. Una clave presente SHALL ser opaca, no blanca y de hasta 128 puntos de código.

#### Scenario: Reutilización en otro recurso
- **WHEN** una clave aceptada se reutiliza dentro de la misma operación y tenant para otro contacto, Site o Company
- **THEN** devuelve 409 idempotency-key-already-used-with-different-request sin revelar su snapshot

### Requirement: Huella de intención original de contacto
La huella SHALL incluir tenant, operación, Company, Site, contactId de ruta, If-Match original e intención canónica. PATCH SHALL distinguir omitido/null/valor y POST SHALL materializar defaults. SHALL excluir actor, proceso, clave, estado actual y valores generados, usando política y material criptográfico versionados.

#### Scenario: Equivalencia de creación
- **WHEN** se reintenta con otro orden JSON, mayúsculas equivalentes de email/tipo/label, formato telefónico equivalente o isPrimary omitido frente a false
- **THEN** reproduce la aceptación si mantiene el resto de la intención y el If-Match original

#### Scenario: Intención diferente
- **WHEN** cambia el If-Match o la presencia de un campo PATCH, incluso `{}` frente a label null redundante
- **THEN** devuelve conflicto de clave antes de consultar el contacto actual

### Requirement: Intención parcial reproducible sin tipo actual
Un PATCH con value presente y contactType omitido SHALL poder compararse idempotentemente sin leer el contacto actual. La política original SHALL normalizar la familia email o teléfono suministrada sin inferirla del estado actual. La validación del valor contra el tipo efectivo SHALL ocurrir al evaluar una aceptación nueva.

#### Scenario: Replay después de cambiar tipo o eliminar
- **WHEN** un PATCH aceptado con solo value se reintenta tras reclasificar o eliminar el contacto
- **THEN** reproduce el resultado original usando la política histórica, también con formato equivalente de su value

### Requirement: Replay original independiente de datos actuales
Un replay equivalente SHALL devolver status, contacto o comprobante, siteVersion y auditoría originales con Process-Id actual. SHALL preceder al estado actual y funcionar tras modificaciones, eliminación, cierre, archivo, reinicio y rotación. SHALL NOT crear revisión, auditoría, aceptación o evento adicionales.

#### Scenario: Reintento de creación después de eliminar
- **WHEN** se repite una creación aceptada tras eliminar su contacto
- **THEN** devuelve el 201 y representación originales aunque GET produzca 404

#### Scenario: Aceptación concurrente mientras se espera
- **WHEN** el mismo intento confirma después de la consulta inicial y antes de evaluar el intento protegido
- **THEN** devuelve esa aceptación antes de comprobar ausencia, revisión o estado actuales

### Requirement: Aceptación completa y atómica
Contactos afectados, revisión/auditoría Site, aceptación cuando exista clave y evento habilitado SHALL confirmar o revertirse juntos. Los rechazos SHALL NOT consumir claves. Una colisión de clave SHALL revertir efectos provisionales y resolver el resultado durable sin repetir la mutación ni exponer otra intención.

#### Scenario: Fallo después de desmarcar un primario
- **WHEN** falla la actualización, aceptación o evento obligatorio después del desmarcado
- **THEN** revierte todos los contactos, Site y las demás filas del intento

#### Scenario: Misma clave en paralelo
- **WHEN** dos solicitudes equivalentes compiten por una clave
- **THEN** ambas pueden recuperar una única aceptación, con como máximo una mutación y evento

### Requirement: Plazo total y finalización incierta
Las operaciones de mutación SHALL tener un plazo total finito que incluya consultas, esperas, commit/cierre y recuperación. Un resultado durable incierto SHALL producir 503 sin afirmar rollback ni repetir escrituras. La recuperación SHALL comenzar solo tras finalizar la transacción fallida, conservando el tiempo restante.

#### Scenario: Respuesta perdida tras commit
- **WHEN** se pierde la respuesta de una aceptación confirmada con clave
- **THEN** el mismo reintento puede recuperar su resultado original sin segunda mutación

#### Scenario: Plazo agotado sin finalización conocida
- **WHEN** vence el plazo y no se conoce el resultado final
- **THEN** devuelve 503 dependency-unavailable sin otra escritura o recuperación concurrente

### Requirement: Eliminación lógica y comprobante mínimo
DELETE SHALL conservar la fila, fijar deletedAt/deletedBy, retirar isPrimary y actualizar auditoría con el instante/actor de Site. SHALL devolver 200 con solo contactId, siteId, siteVersion, deletedAt y deletedBy en data. Un DELETE nuevo sobre eliminado SHALL devolver 404; replay SHALL devolver su comprobante original.

#### Scenario: Eliminar y reutilizar valor
- **WHEN** se elimina el primario y después se registra su mismo valor y tipo con una clave nueva y revisión vigente
- **THEN** la fila histórica permanece, el nuevo contacto tiene otra identidad y no existe reemplazo primario implícito

### Requirement: Representación pública de contacto
POST/PATCH/GET individual y listado SHALL usar contactId, siteId, siteVersion, contactType, value, isPrimary y los cuatro campos de auditoría. SHALL añadir label solo si no es null. SHALL excluir tenantId, companyId, revisión propia, metadatos de eliminación, verificaciones de disponibilidad y material idempotente.

#### Scenario: Resultado canónico completo
- **WHEN** se representa un contacto vigente
- **THEN** IDs son UUID strings, siteVersion entero, isPrimary booleano, fechas UTC/microsegundos y value canónico
- **AND** conserva los actores exactos y omite label null

### Requirement: Revisión coherente en consultas
Cada consulta SHALL leer contacto y revisión actual Site coherentemente. Los elementos de una página SHALL compartir la revisión observada. Otro cambio Site o hijo puede aumentar siteVersion sin modificar el contacto. Una página vacía SHALL devolver data vacío y permitir obtener la revisión mediante GET Site.

#### Scenario: Otro hijo cambia después de aceptar
- **WHEN** una dirección incrementa Site sin cambiar el contacto
- **THEN** GET contacto conserva sus datos con la nueva revisión, mientras replay conserva la revisión original

### Requirement: Listado filtrado y paginado vigente
GET de colección SHALL excluir eliminados y admitir únicamente contactType, isPrimary, cursor y limit una vez cada uno. Los filtros SHALL ser exactos EMAIL/PHONE/MOBILE/WHATSAPP y true/false, combinados con AND. SHALL ordenar por createdAt DESC/contactId DESC, con límite 50 por defecto y rango 1–200.

#### Scenario: Primarios y colección vacía
- **WHEN** se filtra por tipo e isPrimary true
- **THEN** devuelve como máximo un contacto; isPrimary true sin tipo admite hasta cuatro
- **AND** un ámbito propio sin coincidencias devuelve 200 y data vacío

#### Scenario: Empate de fechas y navegación
- **WHEN** se pagina un conjunto estable con fechas de creación iguales y límite 1
- **THEN** el identificador desempata sin repetir ni omitir registros

### Requirement: Cursor exclusivo y envelope de colección
El cursor SHALL autenticar formato, colección contacts, tenant/Company/Site, filtros, orden y frontera. SHALL rechazar otro ámbito o colección. El envelope SHALL tener data array, numberOfElements y nextCursor solo con continuación, sin totales ni pagination anidada. Cada página SHALL observar valores actuales sin prometer snapshot entre páginas.

#### Scenario: Cursor ajeno o manipulado
- **WHEN** se usa un cursor de addresses, otro ámbito/filtros o contenido modificado
- **THEN** devuelve 400 contact-cursor-invalid-or-incompatible sin revelar datos o material criptográfico

#### Scenario: Cambiar límite o actor
- **WHEN** se continúa un cursor válido con otro límite permitido, actor o Process-Id válidos
- **THEN** conserva ámbito, filtros y orden sin exigir los valores previos de esos tres parámetros

### Requirement: Evento mínimo por cambio de contacto
Cada cambio efectivo SHALL almacenar un company.site.updated.v1 con outbox stored-only o published; disabled SHALL omitirlo. SHALL identificar Site y su revisión/auditoría resultantes mediante el contrato mínimo existente, sin contacto, label, email ni teléfono. No-op y replay SHALL omitir eventos; HU07 SHALL almacenar PENDING sin publicar.

#### Scenario: Cambio conjunto de primarios
- **WHEN** una aceptación modifica varios contactos con outbox habilitado
- **THEN** conserva un evento con aggregateVersion igual a siteVersion y actor/correlación originales
- **AND** su payload contiene solo siteId, companyId, code, name, siteType, status, updatedAt y updatedBy

### Requirement: Gestión local sin verificación de canales
Las operaciones SHALL funcionar sin Party Registry, Geographic Reference, servicios de mensajería o RabbitMQ. SHALL NOT consultar existencia del buzón, disponibilidad de WhatsApp o titularidad del número ni enviar mensajes. Validar un teléfono SHALL significar compatibilidad con el plan de numeración admitido, no prueba de asignación o disponibilidad.

#### Scenario: WhatsApp y dependencias inaccesibles
- **WHEN** se registra un WHATSAPP válido con precondiciones locales satisfechas y servicios externos inaccesibles
- **THEN** puede aceptarse sin llamadas de verificación o mensajería
