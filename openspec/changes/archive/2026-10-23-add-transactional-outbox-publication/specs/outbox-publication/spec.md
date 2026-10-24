# Spec Delta

## Purpose

Entregar de forma asíncrona y recuperable los hechos de integración conservados por Company Registry, manteniendo su identidad y privacidad e independencia respecto de las operaciones de negocio.

## ADDED Requirements

### Requirement: Catálogo de siete eventos publicables
El publicador SHALL admitir exactamente los siete tipos V1 aprobados de Company y Site y su esquema 1. SHALL reutilizar sus contratos existentes sin añadir tipos, campos ni productores de negocio.

#### Scenario: Publicación de todos los tipos aprobados
- **WHEN** existen eventos válidos de `company.created.v1`, `company.updated.v1`, `company.activated.v1`, `company.deactivated.v1`, `company.site.created.v1`, `company.site.updated.v1` y `company.site.closed.v1`
- **THEN** cada tipo puede publicarse con su agregado, versión, payload y routing key correspondientes
- **AND** archivado Company utiliza updated y cambios Address/Contact utilizan Site updated

### Requirement: Envelope y hecho original inmutables
La entrega SHALL conservar los once campos del envelope aprobado, incluido causationId null explícito cuando corresponda. SHALL serializar el hecho almacenado sin reconstruirlo desde Company, Site, Party o hijos actuales, ni incluir metadatos técnicos de entrega en el mensaje.

#### Scenario: El agregado cambia antes de entregar
- **WHEN** un evento pendiente pertenece a un agregado que ha cambiado de información, estado o revisión
- **THEN** se entrega el snapshot original con su identidad, contexto, fechas UTC y aggregateVersion originales
- **AND** el mensaje contiene eventId, tenantId, aggregateType, aggregateId, aggregateVersion, eventType, eventSchemaVersion, occurredAt, correlationId, causationId y payload

#### Scenario: Reintento después de cambiar el modo o reiniciar
- **WHEN** se vuelve a publicar un mismo evento almacenado
- **THEN** conserva eventId y el contenido del envelope y payload originales
- **AND** intentos, versión técnica, errores, auditoría técnica y fechas de publicación no aparecen en el mensaje

### Requirement: Validación cerrada y privacidad antes de publicar
Antes de enviar, el sistema SHALL validar tipo/esquema, campos permitidos, tipos y límites, agregado, revisión e igualdades de identidad/fecha de cada contrato. Un evento inválido SHALL producir un fallo terminal controlado sin emitirlo ni filtrar contenido sensible.

#### Scenario: Payload con datos no aprobados
- **WHEN** un payload contiene un identificador legal, dirección completa, coordenadas, email, teléfono o cualquier propiedad adicional al contrato
- **THEN** el evento queda FAILED sin envío y con un código estable de contrato inválido
- **AND** los logs y diagnósticos no reproducen esos valores

#### Scenario: Tipo, versión o invariantes incompatibles
- **WHEN** un evento usa un tipo/esquema no soportado, estado incompatible, ID de payload distinto del agregado o fecha de payload distinta de occurredAt
- **THEN** queda FAILED con diagnóstico controlado
- **AND** el rechazo no impide procesar otros eventos válidos del lote

### Requirement: Independencia de la transacción de negocio
La mutación, su versión y auditoría, el completado idempotente aplicable y el evento habilitado SHALL confirmar o revertir juntos. La publicación SHALL comenzar después del commit y SHALL NOT mantener una transacción de negocio abierta esperando RabbitMQ.

#### Scenario: Broker inaccesible durante una creación
- **WHEN** una creación válida confirma en published mientras RabbitMQ está inaccesible
- **THEN** la operación de negocio puede completarse y su evento queda conservado para la política de entrega
- **AND** la indisponibilidad del broker no revierte ni repite la creación

#### Scenario: Fallo de una escritura obligatoria
- **WHEN** falla la mutación, completado requerido o inserción del evento antes de confirmar
- **THEN** no queda un evento confirmado correspondiente al intento revertido
- **AND** el publicador no envía el hecho de ese intento

### Requirement: Procesamiento programado y acotado de pendientes
En published el sistema SHALL procesar periódicamente lotes limitados de eventos PENDING cuyo plazo permita un intento. SHALL omitir PUBLISHED, FAILED y reservas no vencidas, y seleccionar pendientes vencidos sin postergarlos indefinidamente por nuevas inserciones.

#### Scenario: Selección entre pendientes y terminales
- **WHEN** coinciden eventos nuevos, reintentos vencidos, reservas activas, PUBLISHED y FAILED
- **THEN** solo son elegibles los PENDING vencidos o nuevos hasta el límite configurado
- **AND** el trabajo y los envíos simultáneos permanecen acotados

#### Scenario: Llegada continua de eventos nuevos
- **WHEN** existen reintentos vencidos mientras se insertan nuevos eventos
- **THEN** los reintentos conservan una prioridad temporal que permite procesarlos

### Requirement: Reserva excluyente con vencimiento
Cada evento SHALL tener como máximo una reserva activa de publicación. La reserva SHALL confirmar de forma atómica su identidad de intento, vencimiento, última fecha e incremento de intentos antes de enviar. La transacción de reserva SHALL finalizar antes de esperar al broker.

#### Scenario: Dos publicadores reservan simultáneamente
- **WHEN** dos instancias seleccionan los mismos pendientes al mismo tiempo
- **THEN** no obtienen propiedad simultánea de la misma reserva activa
- **AND** pueden procesar otros eventos sin esperar a que termine el envío ajeno

#### Scenario: Caída después de reservar
- **WHEN** una instancia cae después de confirmar la reserva y antes de registrar su resultado
- **THEN** el evento vuelve a ser elegible al vencer la reserva, dentro del máximo de intentos
- **AND** no depende de liberar un lock mantenido por la instancia desaparecida

### Requirement: Resultados protegidos contra intentos anteriores
Publicación, reintento y fallo SHALL actualizar únicamente el estado que todavía corresponde a su reserva sin reemplazo ni resultado previo. Un resultado obsoleto SHALL NOT modificar una reserva posterior, revertir un estado terminal ni sobrescribir un resultado ya registrado.

#### Scenario: Confirmación del primer intento después de recuperar el segundo
- **WHEN** vence una reserva, otro publicador obtiene el siguiente intento y después llega un resultado del anterior
- **THEN** el resultado anterior no modifica el estado ni la planificación del intento nuevo

#### Scenario: Dos resultados del mismo intento
- **WHEN** se intenta registrar un resultado después de que esa reserva ya registró reintento, publicación o fallo
- **THEN** el segundo resultado no sobrescribe el primero
- **AND** la observabilidad distingue un resultado descartado de una transición efectivamente confirmada

### Requirement: Confirmación y metadatos de publicación
El mensaje SHALL usar message ID igual a eventId, content type application/json, entrega persistente, tipo de evento, timestamp del hecho y correlation ID original cuando exista. El evento SHALL pasar a PUBLISHED y registrar publishedAt únicamente después de confirmación positiva del broker.

#### Scenario: Envío sin confirmación todavía
- **WHEN** el mensaje se ha entregado al mecanismo de envío pero no existe confirmación del broker
- **THEN** el registro no queda PUBLISHED ni obtiene publishedAt

#### Scenario: Confirmación positiva
- **WHEN** RabbitMQ confirma el intento que todavía corresponde al registro
- **THEN** se confirma PUBLISHED con publishedAt y se limpia su planificación de reintento y error anterior
- **AND** se conservan identidad y metadatos originales del hecho

### Requirement: Alcance de la confirmación del broker
PUBLISHED SHALL significar publicación confirmada por RabbitMQ, sin acreditar procesamiento consumidor ni existencia de una cola compatible. La operación SHALL exigir como prerrequisito de despliegue preparar y verificar las colas y bindings previstos antes de activar published.

#### Scenario: Confirmación sin una ruta a cola
- **WHEN** el exchange confirma una publicación que no tiene ninguna cola con binding compatible
- **THEN** el registro puede quedar PUBLISHED conforme al criterio de confirmación
- **AND** queda documentado que una cola creada después no recupera automáticamente ese mensaje

#### Scenario: Consumidor detenido con cola durable preparada
- **WHEN** una cola durable vinculada recibe el mensaje persistente y su consumidor está detenido
- **THEN** la ausencia del proceso consumidor no impide confirmar la publicación
- **AND** el almacenamiento y consumo posterior siguen las políticas de esa cola

### Requirement: Publicación al menos una vez y orden independiente
La entrega SHALL permitir duplicados con el mismo eventId y desorden incluso dentro de un mismo agregado. SHALL NOT prometer orden global, orden por agregado ni exactamente una entrega. El contrato consumidor SHALL exigir deduplicación por eventId y detección de desorden o huecos mediante aggregateVersion según su modelo.

#### Scenario: Confirmación seguida de caída antes de persistir el resultado
- **WHEN** RabbitMQ confirma pero el proceso cae antes de confirmar PUBLISHED
- **THEN** el evento puede publicarse nuevamente al recuperar la reserva
- **AND** ambas publicaciones conservan identidad y contenido originales

#### Scenario: Una revisión anterior espera o falla
- **WHEN** el evento de una revisión anterior está en backoff o FAILED y una revisión posterior está pendiente y elegible
- **THEN** la revisión posterior puede publicarse independientemente
- **AND** su versión sigue siendo la revisión de negocio original, no el número de intento

### Requirement: Reintento exponencial limitado
Fallo temporal de broker/conexión, confirmación negativa o timeout SHALL conservar PENDING con error saneado y siguiente intento según backoff exponencial limitado. Un intento SHALL consumir una sola unidad al reservarse; los reintentos de transporte SHALL NOT multiplicar silenciosamente los intentos registrados.

#### Scenario: Fallos recuperables sucesivos
- **WHEN** fallan intentos consecutivos sin agotar el límite
- **THEN** los siguientes plazos se calculan desde el fallo usando min(backoff inicial multiplicado por 2 elevado a intento menos 1, backoff máximo)
- **AND** se conservan última fecha, número de intentos y código de error, sin reenviar inmediatamente dentro del mismo intento

#### Scenario: Confirmación incierta por timeout
- **WHEN** vence el plazo de publicación sin confirmación conocida
- **THEN** se programa recuperación sin asumir que RabbitMQ no recibió el mensaje
- **AND** una eventual entrega repetida conserva eventId

### Requirement: Máximo de intentos también tras caídas
El sistema SHALL respetar el máximo configurado incluso si los procesos caen antes de registrar resultados. Un intento reservado consume presupuesto aunque no pueda determinarse si llegó al broker. Una reserva del último intento que venza sin resultado SHALL terminar en FAILED con diagnóstico de entrega incierta, sin otro envío automático.

#### Scenario: Error conocido en el último intento
- **WHEN** el último intento permitido falla de forma recuperable
- **THEN** queda FAILED con su diagnóstico y no programa otro intento

#### Scenario: Caída durante el último intento
- **WHEN** vence una reserva sin resultado y ya se consumió el máximo
- **THEN** el sistema registra FAILED y conserva la incertidumbre del resultado
- **AND** no reserva el intento máximo más uno ni afirma que el mensaje nunca fue entregado

### Requirement: Fallos terminales e historia conservados
FAILED SHALL conservar el hecho, identidad, intentos y diagnóstico saneado, sin reenvío ni borrado automático. PUBLISHED e historia existente SHALL conservarse. Reinicios, recuperación del broker o cambios de modo/límites SHALL NOT reactivar FAILED; su reprocesamiento administrativo queda fuera de HU08.

#### Scenario: Broker recuperado después del fallo terminal
- **WHEN** vuelve RabbitMQ o reinicia el servicio y existen FAILED junto a PENDING elegibles
- **THEN** se procesan únicamente los pendientes elegibles y se conservan los terminales para diagnóstico

#### Scenario: Contrato o serialización irrecuperable
- **WHEN** el envío no puede construirse conforme al contrato aprobado
- **THEN** se registra un fallo terminal controlado sin agotar intentos de envío inútiles
- **AND** el evento y su diagnóstico permanecen disponibles para investigación

### Requirement: Observabilidad de entrega sin datos sensibles
El sistema SHALL exponer contadores de confirmaciones, reintentos y fallos terminales, pendientes y antigüedad del pendiente más antiguo. Los logs SHALL identificar evento, tenant, correlación, intento y resultado con códigos y detalles saneados, sin payload, credenciales ni datos legales/contactos y sin etiquetas métricas por identidad.

#### Scenario: Publicación, reintento y fallo terminal
- **WHEN** ocurren esos resultados
- **THEN** las métricas y logs permiten distinguirlos y observar acumulación o envejecimiento de pendientes
- **AND** un resultado obsoleto o fallo al guardar una transición no se presenta como una transición confirmada

#### Scenario: Error con credenciales o contenido privado en la causa
- **WHEN** una dependencia falla con un mensaje que contiene material sensible
- **THEN** solo se conserva un diagnóstico de lista permitida y longitud acotada
- **AND** ni logs ni métricas ni last_error_detail exponen la causa original

### Requirement: Disponibilidad de negocio y diagnóstico de entrega separados
Una caída de RabbitMQ SHALL NOT degradar por sí sola la disponibilidad de la API de negocio ni provocar reinicios por liveness. El estado de entrega degradado SHALL ser observable mediante métricas y logs mientras se conservan las comprobaciones de disponibilidad propias del negocio.

#### Scenario: Caída del broker después de publicar correctamente
- **WHEN** una instancia que estuvo conectada pierde RabbitMQ
- **THEN** las operaciones de negocio y sus comprobaciones de disponibilidad pueden continuar si sus demás dependencias están sanas
- **AND** el deterioro de entrega y el backlog son observables

### Requirement: Contratos y recuperación verificables en artefactos distribuidos
La entrega SHALL verificar creación de Company hasta RabbitMQ sobre procesos JVM y nativo reales, contratos/routing de los siete tipos, recuperación y modos sin broker. El artefacto productivo SHALL conservar contratos empaquetados y excluir fixtures y mecanismos de inyección de fallos de pruebas.

#### Scenario: Creación real y contratos empaquetados
- **WHEN** se ejecuta el artefacto fuera del árbol de fuentes con base y broker controlados
- **THEN** una creación válida produce un mensaje verificable contra su contrato empaquetado y queda PUBLISHED
- **AND** los siete tipos disponen de pruebas de contrato y enrutamiento

#### Scenario: Reinicio con historia existente
- **WHEN** un proceso distinto arranca contra la misma outbox con pendientes, reservas vencidas y estados terminales
- **THEN** recupera únicamente el trabajo elegible preservando identidades y resultados terminales
- **AND** la evidencia distingue pruebas JVM, nativas y de inyección de fallos realmente ejecutadas
