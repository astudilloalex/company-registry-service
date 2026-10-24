# Spec Delta

## Purpose

Ejecutar Company Registry de forma privada y repetible en el VPS existente, preservando aislamiento, configuración protegida y compatibilidad de los datos durante despliegues y recuperación de versiones.

## ADDED Requirements

### Requirement: Ejecución privada en la plataforma existente
El servicio SHALL ejecutar su imagen nativa como company-registry-service mediante Podman rootless y Quadlet en Debian 13, escuchando en el puerto interno 8083. SHALL reutilizar la infraestructura existente sin publicar puertos del host, rutas Nginx ni conexiones directas a Keycloak.

#### Scenario: Acceso exclusivamente interno
- **WHEN** se inspecciona el despliegue instalado
- **THEN** el servicio responde por su alias interno company-registry-service:8083
- **AND** no existen mapeos de puertos del host ni rutas públicas para Company Registry

#### Scenario: Instalación en el VPS existente
- **WHEN** se prepara la entrega
- **THEN** reutiliza el PostgreSQL 18, RabbitMQ y servicios internos existentes
- **AND** no introduce otro VPS, servidor PostgreSQL, broker ni plataforma de orquestación

### Requirement: Tres redes desde el primer despliegue
El contenedor SHALL pertenecer a internal-services.network, company-db.network y messaging.network desde su primera instalación y en los tres modos outbox. La configuración SHALL preservar esos enlaces al recrear el contenedor y resolver los nombres efectivos de red en el contexto del usuario rootless.

#### Scenario: Disabled con mensajería conectada
- **WHEN** el primer despliegue arranca con COMPANY_OUTBOX_MODE=disabled y sin credenciales RabbitMQ
- **THEN** el contenedor pertenece a las tres redes aprobadas
- **AND** la pertenencia a messaging no activa conexiones AMQP ni entrega de eventos

#### Scenario: Reinicio y recreación de contenedores
- **WHEN** se reinicia el VPS o se recrean Company Registry y PostgreSQL desde su configuración persistente
- **THEN** Company conserva sus tres redes y PostgreSQL conserva el acceso por company-db
- **AND** Party y Geographic Reference siguen resolviéndose por sus nombres internos

#### Scenario: Red ausente o perteneciente a otro contexto rootless
- **WHEN** una red requerida no está disponible para el usuario que ejecuta el despliegue
- **THEN** la validación previa falla antes de reemplazar el servicio activo
- **AND** informa la dependencia ausente sin crear otra red con identidad incompatible

### Requirement: Restricciones de ejecución y recursos medidos
El contenedor SHALL usar un usuario no root, capacidades innecesarias eliminadas, NoNewPrivileges y filesystem raíz de lectura con temporales acotados. SHALL aplicar límites de memoria, CPU y procesos respaldados por mediciones identificables del servicio y exportar sus logs a journald.

#### Scenario: Imagen con restricciones productivas
- **WHEN** se ejecuta la imagen final con la configuración prevista
- **THEN** la aplicación y sus comprobaciones de salud funcionan bajo las restricciones declaradas
- **AND** la evidencia registra UID, restricciones y límites efectivos

#### Scenario: Selección de límites
- **WHEN** se fijan los límites operativos
- **THEN** se documentan versión, carga y mediciones de reposo, peticiones concurrentes y trabajo outbox aislado
- **AND** los valores no se justifican solamente por el consumo del compilador nativo o por otro servicio

### Requirement: Base lógica y credenciales aisladas
El servicio SHALL utilizar company_registry_service en PostgreSQL 18 con credenciales distintas para runtime y migraciones. Runtime SHALL tener solo permisos operativos necesarios, sin DDL, modificación del historial Flyway ni acceso a datos de bases ajenas. El aprovisionamiento SHALL preservar los permisos y datos de los otros servicios.

#### Scenario: Permisos positivos y negativos
- **WHEN** se verifican las credenciales runtime
- **THEN** las operaciones autorizadas sobre tablas Company funcionan y DDL e intentos de modificar el historial Flyway se rechazan
- **AND** se rechaza el acceso a datos de Party, Geographic Reference y Keycloak

#### Scenario: Migración con identidad separada
- **WHEN** se instala o reinicia la aplicación
- **THEN** Flyway aplica o valida sus migraciones con la identidad de migración
- **AND** la persistencia operacional conserva acceso reactivo y no adquiere permisos de propietario

### Requirement: Configuración productiva externa y protegida
La configuración instalada SHALL proceder de los secretos y variables del entorno production de GitHub. SHALL externalizar credenciales, URLs internas, timeouts, modo outbox y parámetros operativos, validar valores antes de la activación y mantener los archivos y secretos accesibles solo a sus identidades operativas.

#### Scenario: Configuración incompleta o insegura
- **WHEN** faltan credenciales obligatorias, hay placeholders, valores duplicados, perfil distinto de prod o un puerto interno distinto de 8083
- **THEN** el despliegue falla antes de sustituir la versión activa
- **AND** el diagnóstico no reproduce valores secretos

#### Scenario: Lectura de configuración por el contenedor
- **WHEN** el contenedor arranca con usuario no root y filesystem raíz de lectura
- **THEN** puede leer su configuración y keyring mediante los mecanismos protegidos previstos
- **AND** las credenciales no quedan incluidas en la imagen, el repositorio ni los artefactos de diagnóstico

### Requirement: Conservación del keyring durante despliegue y rollback
El despliegue SHALL conservar cada ID y secreto HMAC que los registros aceptados requieran. SHALL validar la presencia de la clave activa y rechazar eliminar o sustituir material histórico necesario. Recuperar una imagen anterior SHALL conservar un keyring compatible con la base actual.

#### Scenario: Rollback después de aceptar con una clave nueva
- **WHEN** una versión acepta operaciones con K2 y después se recupera una imagen compatible anterior
- **THEN** los replays originales de K1 y K2 siguen verificándose
- **AND** recuperar la versión anterior no elimina K2 ni cambia el secreto asociado a un ID existente

#### Scenario: Keyring insuficiente
- **WHEN** la configuración entrante omite una clave requerida por aceptaciones existentes o su clave activa
- **THEN** la validación impide activarla
- **AND** conserva la configuración vigente sin generar material de reemplazo automáticamente

### Requirement: Política inicial disabled y activación posterior explícita
La primera instalación SHALL configurar COMPANY_OUTBOX_MODE=disabled manteniendo las tres redes. SHALL operar sin credenciales ni conexión RabbitMQ y omitir nuevos eventos según el contrato existente. Published SHALL activarse posteriormente mediante configuración y reinicio, después de verificar credenciales, colas y bindings del destino.

#### Scenario: Primera entrega sin consumidores
- **WHEN** se instala el servicio en el VPS antes de preparar consumidores
- **THEN** sirve operaciones válidas en disabled sin requerir disponibilidad RabbitMQ
- **AND** no crea eventos outbox para esas nuevas aceptaciones

#### Scenario: Activación futura
- **WHEN** están verificados los destinos y el operador cambia production a published y ejecuta el despliegue
- **THEN** se habilita la publicación usando el mismo formato de artefacto y las tres redes ya conectadas
- **AND** no fabrica eventos de operaciones aceptadas en disabled ni reactiva FAILED o PUBLISHED

### Requirement: Supervisión de proceso independiente de dependencias opcionales
La supervisión SHALL comprobar readiness durante el arranque y liveness para recuperar fallos del proceso. Una caída de PostgreSQL posterior al arranque SHALL reflejarse en readiness sin causar por sí sola un ciclo de reinicios. Party, Geographic Reference y RabbitMQ SHALL conservar su aislamiento respecto de la salud global.

#### Scenario: Recuperación de un proceso fallido
- **WHEN** el proceso termina inesperadamente o incumple liveness de forma sostenida
- **THEN** systemd intenta recuperarlo con una política de reinicio y tiempos acotados
- **AND** el servicio vuelve a superar las comprobaciones de arranque antes de aceptarse como disponible

#### Scenario: PostgreSQL deja de responder
- **WHEN** se pierde PostgreSQL después de un arranque correcto mientras el proceso responde
- **THEN** readiness informa indisponibilidad y liveness conserva el estado del proceso
- **AND** recuperar conectividad permite volver a readiness satisfactoria sin exigir reiniciar la aplicación

### Requirement: Activación verificable y rollback compatible
El despliegue SHALL descargar y validar la imagen y configuración antes de detener la versión activa. SHALL aceptarse únicamente tras readiness y smoke tests satisfactorios. Ante un fallo SHALL recuperar la versión anterior compatible, preservando datos, historial Flyway, aceptaciones idempotentes, outbox y claves necesarias.

#### Scenario: Fallo de descarga o validación previa
- **WHEN** falla la descarga, el render o la validación del servicio generado
- **THEN** la versión activa y su configuración siguen operando
- **AND** el despliegue termina con error identificable

#### Scenario: Smoke test falla después de readiness
- **WHEN** la nueva versión alcanza readiness pero falla una comprobación obligatoria posterior
- **THEN** la operación sigue siendo fallida y se intenta recuperar la imagen y configuración compatibles anteriores
- **AND** se comprueba la salud de la versión recuperada sin revertir migraciones ni borrar registros

#### Scenario: Primera instalación fallida o recuperación incompleta
- **WHEN** falla una primera instalación sin versión previa o no puede completarse el rollback
- **THEN** el despliegue informa el resultado real y conserva los elementos protegidos necesarios para intervención
- **AND** no presenta una recuperación incompleta como satisfactoria ni ejecuta una versión incompatible con el esquema actual
