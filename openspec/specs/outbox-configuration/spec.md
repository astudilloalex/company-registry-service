# outbox-configuration Specification

## Purpose

Establecer modos y parámetros de outbox compatibles con la futura entrega transaccional de eventos y con un arranque independiente de RabbitMQ.

## Requirements

### Requirement: Modos explícitos y valor por defecto
La configuración SHALL aceptar `company-registry.outbox.mode` y su variable `COMPANY_OUTBOX_MODE`, con valores `disabled`, `stored-only` y `published`. Sin configuración SHALL seleccionar `disabled`; valores desconocidos SHALL rechazarse.

#### Scenario: Valor por defecto
- **WHEN** se inicia el servicio sin modo explícito ni configuración de broker
- **THEN** selecciona `disabled` y arranca sin conectarse a RabbitMQ ni exigir sus credenciales

#### Scenario: Modos válidos y desconocidos
- **WHEN** se evalúa cada uno de los tres valores aprobados o un valor ajeno al contrato
- **THEN** los valores aprobados son reconocidos y el desconocido produce un error claro de configuración

### Requirement: Semántica de persistencia separada de publicación
El contrato SHALL establecer que disabled omite nuevos eventos y que stored-only/published los guardan junto con la mutación. Únicamente published SHALL reservar y publicar pendientes de forma asíncrona después del commit. Ningún modo SHALL publicar dentro de la transacción de negocio ni borrar historia automáticamente.

#### Scenario: Selección de política
- **WHEN** se prueba la política de modo con una escritura de prueba
- **THEN** disabled no solicita persistencia de evento y stored-only/published seleccionan almacenamiento
- **AND** únicamente published habilita la reserva y entrega asíncrona de pendientes

#### Scenario: Historia al desactivar almacenamiento
- **WHEN** el servicio reinicia en disabled contra una outbox que ya contiene eventos
- **THEN** las nuevas mutaciones no generan eventos y no se publican los conservados
- **AND** se preservan filas, identidad, payload, estados e historia existentes

### Requirement: Parámetros del publisher preparados
El contrato SHALL incluir intervalo, lote, timeout, claim lease, backoff inicial/máximo, máximo de intentos, exchange y routing key. SHALL validar duraciones/cantidades positivas y representables, backoff máximo no menor al inicial, reserva mayor al timeout y destinos válidos antes de procesar. SHALL mantener los nombres de variables externas requeridos por HU08.

#### Scenario: Configuración inválida
- **WHEN** se proporciona tamaño no positivo, duración inválida, backoff invertido o destino vacío
- **THEN** la validación rechaza la configuración sin comenzar procesamiento

#### Scenario: Reserva o cantidad fuera del rango soportado
- **WHEN** el lease no supera el timeout o una duración/cantidad excede su límite documentado o representable
- **THEN** el arranque falla con un error de configuración saneado antes de reservar eventos
- **AND** no inicia envíos ni modifica filas

### Requirement: Contrato de evento y entrega versionado
La persistencia SHALL conservar payload JSONB, identidad del agregado/versión, esquema del evento, correlación/causalidad y metadatos de entrega con versionado optimista. SHALL diferenciar la versión del agregado de la versión técnica del registro outbox.

#### Scenario: Round-trip de evento
- **WHEN** se almacena y recupera un evento de prueba con sus metadatos
- **THEN** se conservan identidad, payload y versiones sin convertir valores de protocolo a mayúsculas

### Requirement: Broker opcional en la base técnica
El servicio SHALL arrancar y operar en disabled/stored-only sin broker ni credenciales RabbitMQ y sin iniciar conexiones o Dev Services del broker. En published SHALL activar la entrega sin hacer depender de RabbitMQ el arranque disponible ni las operaciones de negocio; las credenciales y destinos requeridos sí SHALL validarse antes de procesar.

#### Scenario: Broker inaccesible
- **WHEN** el servicio, el ejecutable nativo y su imagen Podman arrancan en disabled con RabbitMQ inaccesible
- **THEN** completan su arranque y comprobaciones operacionales sin esperar al broker

#### Scenario: Stored-only sin configuración RabbitMQ
- **WHEN** el mismo artefacto arranca en stored-only sin credenciales y con broker inexistente
- **THEN** almacena los eventos habilitados sin intentar conectarse, reservarlos o enviarlos
- **AND** su disponibilidad operacional no depende de RabbitMQ

#### Scenario: Published con configuración válida y broker caído
- **WHEN** el servicio arranca en published con configuración RabbitMQ válida pero broker temporalmente inaccesible
- **THEN** puede quedar disponible para operaciones de negocio y conserva sus eventos
- **AND** aplica los límites de entrega y expone su estado degradado mediante métricas y logs

### Requirement: Valores iniciales y rangos operacionales explícitos
El sistema SHALL conservar los defaults de polling 5s, lote 25, timeout 5s, reserva 30s, backoff inicial 1s, máximo 5m y 10 intentos. SHALL documentar y validar sus rangos soportados al arrancar en cualquiera de los modos.

#### Scenario: Defaults de entrega
- **WHEN** no se sobrescriben los parámetros del publicador
- **THEN** usa los siete valores iniciales definidos y mantiene disabled como modo por defecto

#### Scenario: Rangos soportados
- **WHEN** se configura polling mayor de 1h, lote fuera de 1–500, timeout mayor de 5m, reserva mayor de 1h, backoff mayor de 24h o intentos fuera de 1–100
- **THEN** la configuración se rechaza antes de procesar eventos
- **AND** duraciones cero/negativas, desbordamientos, reserva menor o igual al timeout y backoff máximo inferior al inicial también se rechazan

### Requirement: Variables de despliegue conservadas
La configuración SHALL aceptar las variables COMPANY_OUTBOX_MODE y COMPANY_OUTBOX_* de destino, planificación y reintento definidas por HU08, junto con sus propiedades documentadas. Los overrides válidos SHALL aplicarse a las operaciones de almacenamiento/entrega correspondientes sin reconstruir el artefacto.

#### Scenario: Overrides del publicador
- **WHEN** el despliegue configura COMPANY_OUTBOX_MODE, COMPANY_OUTBOX_EXCHANGE, COMPANY_OUTBOX_ROUTING_KEY, COMPANY_OUTBOX_POLL_INTERVAL, COMPANY_OUTBOX_BATCH_SIZE, COMPANY_OUTBOX_PUBLISH_TIMEOUT, COMPANY_OUTBOX_CLAIM_LEASE, COMPANY_OUTBOX_INITIAL_BACKOFF, COMPANY_OUTBOX_MAXIMUM_BACKOFF y COMPANY_OUTBOX_MAXIMUM_ATTEMPTS
- **THEN** los valores válidos se reflejan en el modo y los límites/destinos efectivos
- **AND** las propiedades correspondientes quedan documentadas bajo company-registry.outbox

### Requirement: Destino configurable que conserva el tipo de evento
El exchange por defecto SHALL ser company.events y la routing key por defecto SHALL ser eventType. COMPANY_OUTBOX_ROUTING_KEY SHALL aceptar una plantilla con exactamente un marcador literal %s que se sustituye por eventType. Plantillas vacías, sin marcador, con varios marcadores, otros formatos o resultado inválido SHALL rechazarse antes de enviar.

#### Scenario: Enrutamiento por defecto para los siete tipos
- **WHEN** se usa la plantilla por defecto %s
- **THEN** cada mensaje utiliza su eventType exacto como routing key en company.events

#### Scenario: Destino externo adaptado explícitamente
- **WHEN** el despliegue configura otro exchange existente y la plantilla integration.%s
- **THEN** la routing key contiene integration. seguido del eventType original
- **AND** la verificación de bindings se realiza contra esos destinos efectivos sin alterar el envelope

#### Scenario: Override reservado anterior sin tipo
- **WHEN** se conserva el valor fijo company.events como override de routing key
- **THEN** el arranque rechaza esa plantilla y señala la adaptación requerida sin publicar
- **AND** la documentación explica que el valor reservado anterior se sustituye por %s para activar enrutamiento por tipo

### Requirement: Configuración RabbitMQ del entorno existente
Published SHALL aceptar RABBITMQ_HOST, RABBITMQ_PORT, RABBITMQ_USERNAME, RABBITMQ_PASSWORD y RABBITMQ_VIRTUAL_HOST; SHALL usar platform como virtual host por defecto, una cuenta dedicada y permisos mínimos sobre el destino configurado. El servicio SHALL NOT crear brokers, virtual hosts ni colas de consumidores en producción.

#### Scenario: Credenciales y destino válidos
- **WHEN** se activa published con host, puerto válido y credenciales dedicadas
- **THEN** utiliza el virtual host platform salvo override explícito a otro existente y el exchange configurado de tipo topic durable
- **AND** las credenciales no aparecen en logs ni diagnósticos

#### Scenario: Credenciales ausentes en modos sin entrega
- **WHEN** arranca en disabled o stored-only sin valores RABBITMQ_*
- **THEN** su ausencia no provoca error ni conexión al broker

#### Scenario: Configuración de entrega incompleta
- **WHEN** arranca en published sin host, usuario o password requerido, o con puerto inválido
- **THEN** falla la validación de configuración con un diagnóstico saneado sin modificar pendientes

### Requirement: Transición de modo mediante reinicio
El cambio de modo SHALL aplicarse mediante configuración y reinicio del mismo artefacto. Stored-only a published SHALL habilitar pendientes conservados sin repetir mutaciones. El cambio SHALL NOT generar hechos retrospectivos de disabled, reactivar FAILED ni reenviar PUBLISHED automáticamente.

#### Scenario: Activar un backlog almacenado
- **WHEN** se reinicia de stored-only a published contra la misma base
- **THEN** los PENDING elegibles se publican con su identidad y contenido originales
- **AND** no se repite ni incrementa la mutación de Company/Site

#### Scenario: Volver a almacenar sin enviar
- **WHEN** se reinicia de published a stored-only
- **THEN** deja de reservar/enviar y continúa almacenando nuevos eventos
- **AND** los pendientes y reservas conservados quedan disponibles para una activación posterior conforme a sus plazos

#### Scenario: Evento que nunca se almacenó
- **WHEN** una aceptación realizada en disabled se reproduce tras habilitar almacenamiento o publicación
- **THEN** devuelve el replay contratado sin fabricar un evento retrospectivo

#### Scenario: Estados terminales tras cambiar modo o límites
- **WHEN** se modifica el modo o aumenta el máximo de intentos y el proceso reinicia
- **THEN** FAILED y PUBLISHED conservan su estado y no se reactivan automáticamente
