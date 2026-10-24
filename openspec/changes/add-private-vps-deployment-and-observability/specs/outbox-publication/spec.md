# Spec Delta

## ADDED Requirements

### Requirement: Muestreo periódico independiente del modo outbox
El servicio SHALL consultar periódicamente pendientes y antigüedad mediante lectura acotada en disabled, stored-only y published. La observación SHALL funcionar sin credenciales o conexión RabbitMQ en modos sin entrega y SHALL NOT reservar eventos ni modificar hechos, intentos, estados o planificación.

#### Scenario: Historia pendiente bajo disabled
- **WHEN** existen eventos históricos PENDING y el servicio arranca en disabled sin credenciales RabbitMQ
- **THEN** una consulta periódica válida actualiza cantidad, antigüedad y disponibilidad del backlog
- **AND** conserva todas las filas e intentos y no abre conexiones AMQP

#### Scenario: Nuevos pendientes en stored-only
- **WHEN** una mutación válida almacena un evento en stored-only y se completa el siguiente muestreo
- **THEN** las métricas reflejan ese pendiente sin requerir un publicador activo
- **AND** su estado y metadatos de entrega permanecen intactos

#### Scenario: Consulta y entrega concurrentes
- **WHEN** se observan métricas mientras published procesa eventos
- **THEN** la observación utiliza lecturas independientes y no adquiere reservas de publicación
- **AND** la vista representa un instante observado sin prometer un snapshot entre consultas ni bloquear la entrega

### Requirement: Disponibilidad de mediciones separada de entrega
El servicio SHALL distinguir mediciones válidas de backlog de su indisponibilidad. Hasta el primer muestreo exitoso o ante un fallo/timeout de consulta SHALL exponer disponibilidad cero y cantidad/edad no disponibles. El fallo de un envío SHALL NOT invalidar por sí solo una medición de base válida ni convertir una consulta de métricas en trabajo de entrega.

#### Scenario: Base sana sin publicación habilitada
- **WHEN** se obtiene una medición válida de una outbox vacía en disabled o stored-only
- **THEN** cantidad y edad son cero y disponibilidad es uno
- **AND** esos valores acreditan una consulta exitosa, no una publicación

#### Scenario: Fallo y recuperación del muestreo
- **WHEN** una consulta falla o supera su plazo y posteriormente otra completa correctamente
- **THEN** la medición pasa por estado no disponible y después recupera los valores observados
- **AND** registra un diagnóstico saneado sin alterar eventos ni repetir mutaciones

#### Scenario: Broker caído con base disponible
- **WHEN** el broker falla pero una consulta de backlog a PostgreSQL completa correctamente
- **THEN** sus métricas continúan reflejando pendientes y antigüedad válidos
- **AND** los fallos de entrega se observan mediante sus resultados separados
