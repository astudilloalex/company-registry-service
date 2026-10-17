# service-foundation Specification

## Purpose

Proporcionar una base ejecutable nativa y verificable para desarrollar Company Registry de forma incremental con límites de responsabilidad explícitos.

## Requirements

### Requirement: Baseline ejecutable y reproducible
El servicio SHALL compilar, probarse y arrancar como ejecutable nativo a partir de Java 25, Gradle Kotlin DSL y una versión Quarkus 3.x compatible y fijada. SHALL ejecutar persistencia operacional no bloqueante contra PostgreSQL 18 y reservar JDBC exclusivamente para migraciones. JVM SHALL permanecer disponible para desarrollo y pruebas complementarias.

#### Scenario: Instalación local limpia
- **WHEN** se ejecuta el procedimiento documentado con Java 25 y PostgreSQL 18 vacío
- **THEN** el build produce un ejecutable nativo y el servicio arranca aplicando las migraciones
- **AND** el arranque no requiere reverse proxy, RabbitMQ ni disponibilidad de Party o Geographic Reference

#### Scenario: Runtime sin DDL
- **WHEN** el servicio consulta o persiste mediante sus adaptadores operacionales
- **THEN** utiliza acceso reactivo y no ejecuta DDL ni JDBC en el flujo de requests

### Requirement: Límites de responsabilidad comprobables
El servicio SHALL mantener sus políticas de negocio independientes de HTTP, persistencia, mensajería y frameworks. La composición de I/O SHALL ser no bloqueante, con límites explícitos de sesión y transacción, sin acoplar errores del núcleo al transporte.

#### Scenario: Ejecución aislada de políticas
- **WHEN** se prueban enums, normalización e invariantes del dominio
- **THEN** las pruebas se ejecutan sin levantar Quarkus, una base de datos o un broker

#### Scenario: Validación de dependencias
- **WHEN** se ejecutan las verificaciones arquitectónicas
- **THEN** fallan ante dependencias desde el dominio a frameworks, desde el núcleo a adaptadores concretos o ante respuestas HTTP que exponen entidades

### Requirement: Propiedad del contexto Company Registry
El servicio SHALL poseer registros operacionales de compañías, sedes, direcciones, contactos, idempotencia y outbox propios. SHALL tratar identidades legales, catálogos geográficos, usuarios y tenants como responsabilidades externas, sin claves foráneas entre bases de servicios.

#### Scenario: Referencias externas opacas
- **WHEN** se inspeccionan modelos y migraciones
- **THEN** Party y geografía aparecen como referencias validadas por contrato y no como copias autoritativas de identidad legal o catálogos externos

### Requirement: Documentación coherente de la base técnica
La entrega SHALL incluir instrucciones locales, DBML, OpenAPI y decisiones sobre unicidad Party–Company, sede legal, versionado y prerrequisitos de ciclo de vida. Los diagramas LikeC4 SHALL alinearse con el modelo empresarial central y distinguir dependencias actuales de publicación futura.

#### Scenario: Trazabilidad de entregables
- **WHEN** se revisa T00 contra su implementación
- **THEN** cada convención y restricción tiene documentación versionada y evidencia de prueba correspondiente
- **AND** la referencia al modelo central permite identificar el cambio coordinado requerido para sus diagramas

### Requirement: Empaquetado compatible con Podman
La aplicación SHALL producir un ejecutable nativo y una imagen construible y ejecutable con Podman, sin requerir JVM en runtime. Su construcción SHALL usar herramientas compatibles con Java 25 y documentar versiones, plataforma objetivo y comandos reproducibles.

#### Scenario: Arranque del artefacto empaquetado
- **WHEN** se inicia el ejecutable o imagen nativos con configuración de PostgreSQL y outbox deshabilitado
- **THEN** el servicio queda disponible directamente y sus endpoints operacionales responden sin headers de negocio

#### Scenario: Ejecución nativa sin JVM
- **WHEN** se ejecuta la imagen nativa en Podman con usuario no privilegiado
- **THEN** inicia el binario directamente sin instalar ni invocar una JVM
- **AND** el ejecutable es compatible con la arquitectura y librerías de su imagen de runtime

### Requirement: Verificación funcional del ejecutable nativo
La entrega SHALL verificar sobre el binario nativo migraciones, persistencia reactiva, contratos HTTP, serialización, clientes habilitados y configuración outbox. El artefacto productivo SHALL excluir rutas y datos auxiliares de prueba. Un build o suite JVM exitosos SHALL ser insuficientes para aceptar la entrega nativa.

#### Scenario: Fronteras verificadas en proceso nativo
- **WHEN** se ejecutan pruebas de integración contra el binario con PostgreSQL 18 real y proveedores controlados
- **THEN** verifican instalación/validación Flyway, round-trip de mapeos con enums/JSONB/fechas, envelope/errores, clientes y timeouts
- **AND** los fixtures necesarios solo están presentes en una variante construida para verificación, comprobando además su ausencia en el binario productivo

### Requirement: Recursos e inicialización compatibles con ejecución nativa
El ejecutable nativo SHALL disponer de migraciones y contrato OpenAPI empaquetados, además de metadatos necesarios para serializar los DTOs y snapshots realmente utilizados. La generación UUIDv7 que use aleatoriedad SHALL inicializarla en runtime, sin capturar su estado al construir la imagen.

#### Scenario: Recursos accesibles en el binario
- **WHEN** el ejecutable arranca sin el árbol de fuentes montado
- **THEN** encuentra sus migraciones y publica el mismo contrato OpenAPI versionado
- **AND** los DTOs de respuesta, error y clientes y los snapshots se procesan sin fallos por recursos o metadatos ausentes

#### Scenario: Generación UUIDv7 después del arranque
- **WHEN** dos procesos del mismo ejecutable nativo generan identificadores
- **THEN** producen UUIDv7 válidos sin reutilizar estado aleatorio capturado durante la compilación
