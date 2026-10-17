# Spec Delta

## Purpose

Establecer modos y parámetros de outbox compatibles con la futura entrega transaccional de eventos y con un arranque independiente de RabbitMQ.

## ADDED Requirements

### Requirement: Modos explícitos y valor por defecto
La configuración SHALL aceptar `company-registry.outbox.mode` y su variable `COMPANY_OUTBOX_MODE`, con valores `disabled`, `stored-only` y `published`. Sin configuración SHALL seleccionar `disabled`; valores desconocidos SHALL rechazarse.

#### Scenario: Valor por defecto
- **WHEN** se inicia el servicio sin modo explícito ni configuración de broker
- **THEN** selecciona `disabled` y arranca sin conectarse a RabbitMQ ni exigir sus credenciales

#### Scenario: Modos válidos y desconocidos
- **WHEN** se evalúa cada uno de los tres valores aprobados o un valor ajeno al contrato
- **THEN** los valores aprobados son reconocidos y el desconocido produce un error claro de configuración

### Requirement: Semántica de persistencia separada de publicación
El contrato SHALL establecer que `disabled` omite eventos y `stored-only`/`published` habilitan su persistencia junto con la mutación. `published` SHALL reservar además publicación para HU08. T00 SHALL entregar configuración y persistencia sin emitir mensajes ni afirmar que el publisher está implementado.

#### Scenario: Selección de política
- **WHEN** se prueba la política de modo con una escritura de prueba
- **THEN** disabled no solicita persistencia de evento y stored-only/published seleccionan almacenamiento
- **AND** ningún modo ejecuta publicación RabbitMQ en T00

### Requirement: Parámetros del publisher preparados
El contrato SHALL incluir intervalo, tamaño de lote, timeout, claim lease, backoff inicial/máximo, máximo de intentos, exchange y routing key. SHALL validar duraciones y cantidades positivas, backoff máximo no menor al inicial y destinos no vacíos.

#### Scenario: Configuración inválida
- **WHEN** se proporciona tamaño no positivo, duración inválida, backoff invertido o destino vacío
- **THEN** la validación rechaza la configuración sin comenzar procesamiento

### Requirement: Contrato de evento y entrega versionado
La persistencia SHALL conservar payload JSONB, identidad del agregado/versión, esquema del evento, correlación/causalidad y metadatos de entrega con versionado optimista. SHALL diferenciar la versión del agregado de la versión técnica del registro outbox.

#### Scenario: Round-trip de evento
- **WHEN** se almacena y recupera un evento de prueba con sus metadatos
- **THEN** se conservan identidad, payload y versiones sin convertir valores de protocolo a mayúsculas

### Requirement: Broker opcional en la base técnica
T00 SHALL desactivar conexiones, canales y Dev Services de RabbitMQ que obliguen a disponer de broker. SHALL documentar que HU08 incorporará scheduler, claims, reintentos, confirmaciones y publicación efectiva sin cambiar los modos aprobados.

#### Scenario: Broker inaccesible
- **WHEN** el servicio, el ejecutable nativo y su imagen Podman arrancan en disabled con RabbitMQ inaccesible
- **THEN** completan su arranque y comprobaciones operacionales sin esperar al broker
