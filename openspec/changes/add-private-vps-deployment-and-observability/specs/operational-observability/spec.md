# Spec Delta

## Purpose

Permitir diagnosticar la disponibilidad y el comportamiento de Company Registry mediante salud, logs y métricas privadas, manteniendo correlación y confidencialidad sin requerir una plataforma de recolección.

## ADDED Requirements

### Requirement: Liveness y readiness con responsabilidades separadas
El servicio SHALL ofrecer liveness y readiness sin cabeceras de negocio. Readiness SHALL reflejar la disponibilidad de la persistencia operacional PostgreSQL. Las dependencias externas SHALL NOT incorporarse como condición global de salud ni provocar fallos de liveness por su sola indisponibilidad.

#### Scenario: Gestión sin contexto tenant
- **WHEN** se consultan los endpoints de salud sin Tenant-Id, User-Id o Process-Id
- **THEN** responden con el estado operacional correspondiente sin exigir contexto de negocio
- **AND** no exponen credenciales ni información confidencial

#### Scenario: Pérdida y recuperación de PostgreSQL
- **WHEN** PostgreSQL deja de estar disponible después del arranque y después se recupera
- **THEN** readiness refleja el fallo y su recuperación mediante la persistencia operacional
- **AND** liveness no falla por el solo estado de PostgreSQL

#### Scenario: Dependencia externa inaccesible
- **WHEN** Party, Geographic Reference o RabbitMQ están inaccesibles y PostgreSQL sigue disponible
- **THEN** la salud global puede permanecer disponible
- **AND** las operaciones dependientes conservan sus errores controlados y las lecturas locales siguen funcionando

### Requirement: Logs estructurados y correlacionados
Los logs operacionales SHALL tener una estructura procesable y permitir relacionar una petición con su tenant y Process-Id validados. Los registros de finalización SHALL indicar operación, resultado y duración. El contexto SHALL preservarse en composición asíncrona y limpiarse también ante error o cancelación.

#### Scenario: Peticiones concurrentes de distintos tenants
- **WHEN** se ejecutan peticiones concurrentes con contextos distintos
- **THEN** cada registro correlacionado contiene únicamente los identificadores validados de su petición
- **AND** una petición o ruta operacional posterior no hereda el contexto anterior

#### Scenario: Fallo antes o después de validar contexto
- **WHEN** una petición tiene contexto inválido o falla durante el procesamiento
- **THEN** sus logs reflejan el contexto validado que realmente exista, resultado y duración disponibles
- **AND** no fabrican identidades ni conservan contexto después de finalizar

### Requirement: Diagnósticos sin contenido confidencial
Logs y métricas SHALL excluir contraseñas, material criptográfico, valores completos de contacto y datos confidenciales de Party. Los errores de dependencias y persistencia SHALL utilizar diagnósticos saneados. Los identificadores de tenant, usuario, proceso, agregado y evento SHALL NOT utilizarse como etiquetas métricas.

#### Scenario: Error con datos sensibles en su causa
- **WHEN** una dependencia devuelve una causa que contiene credenciales o contenido confidencial
- **THEN** los logs y métricas expuestos contienen únicamente información saneada
- **AND** no reproducen cuerpos, sentencias con valores, configuración secreta ni la causa sensible original

#### Scenario: Inspección de etiquetas
- **WHEN** se generan métricas bajo tráfico de muchos tenants e identificadores
- **THEN** las etiquetas se mantienen dentro de conjuntos operativos acotados
- **AND** las rutas se representan por plantillas sin UUIDs ni valores de consulta

### Requirement: Métricas de peticiones y dependencias
El servicio SHALL exponer métricas internas de cantidad, resultado y duración de peticiones HTTP, y latencia y fallos de llamadas a Party y Geographic Reference. SHALL distinguir resultados controlados y fallos de transporte sin cambiar los contratos HTTP de negocio.

#### Scenario: Tráfico exitoso y fallido
- **WHEN** se procesan peticiones exitosas, rechazos y errores controlados
- **THEN** las métricas permiten distinguir sus resultados y observar su duración
- **AND** los valores corresponden a peticiones realmente procesadas

#### Scenario: Timeout de un proveedor
- **WHEN** una llamada a Party o Geographic Reference supera su timeout o pierde conectividad
- **THEN** sus métricas permiten identificar la dependencia y el fallo
- **AND** se conserva la respuesta de dependencia acordada sin introducir respuestas parciales de Company

### Requirement: Métricas del pool PostgreSQL operacional
El servicio SHALL exponer mediciones del pool PostgreSQL reactivo que permitan observar utilización, capacidad y espera de adquisición. La evidencia SHALL diferenciar esas mediciones de las del acceso JDBC reservado para migraciones.

#### Scenario: Persistencia bajo concurrencia
- **WHEN** se ejecutan operaciones concurrentes que utilizan PostgreSQL
- **THEN** se observan mediciones identificables del pool reactivo y su presión de adquisición
- **AND** métricas de un pool JDBC por sí solas no acreditan la observabilidad de la persistencia operacional

### Requirement: Resultados de publicación diferenciados
Las métricas outbox SHALL conservar la distinción entre confirmación del broker, transición PUBLISHED confirmada, reintento y fallo terminal. SHALL mostrar pendientes, antigüedad y disponibilidad de la medición según el contrato outbox, sin interpretar datos no disponibles como backlog cero.

#### Scenario: Confirmación sin transición persistida
- **WHEN** RabbitMQ confirma un mensaje y después falla la transición local
- **THEN** se observa la confirmación y el resultado incierto correspondiente
- **AND** no se incrementa un resultado PUBLISHED como si su transacción estuviera confirmada

#### Scenario: Proceso iniciado sin medición válida
- **WHEN** el servicio todavía no ha completado una consulta de backlog o esa consulta falla
- **THEN** las mediciones distinguen indisponibilidad de un valor cero real
- **AND** consultar métricas no activa la entrega ni altera registros

### Requirement: Consulta privada y límites de la evidencia
La operación SHALL permitir consultar logs mediante journald y métricas por un endpoint interno sin publicar puertos del host. SHALL documentar retención efectiva de logs y reinicio de contadores con el proceso. La aceptación de esta etapa SHALL usar evidencia directa y no requerir colectores, dashboards ni alertas continuas.

#### Scenario: Diagnóstico en el VPS
- **WHEN** el operador inspecciona una versión desplegada
- **THEN** puede consultar salud, métricas y logs de esa versión por mecanismos privados
- **AND** identifica su imagen y configuración sin acceder a contenido secreto

#### Scenario: Reinicio sin colector
- **WHEN** se reinicia el servicio
- **THEN** la documentación distingue nuevos contadores del proceso de los registros conservados en PostgreSQL y journald
- **AND** no promete historia de métricas ni persistencia de logs que no haya sido comprobada
