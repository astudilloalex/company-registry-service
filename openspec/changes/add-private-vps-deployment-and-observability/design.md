# Design

## Context

La motivación y las decisiones de alcance están en proposal.md. Los contratos de esta entrega están en las cuatro deltas de specs/.

Hallazgos que condicionan el diseño:

- Java 25, Quarkus 3.40.1 y Gradle wrapper 9.7.0 están fijados. Dockerfile.native utiliza UBI 9 Minimal y UID 1001, pero actualmente declara EXPOSE 8080. La configuración general escucha en 8083; el despliegue conservará 8083 y alineará EXPOSE, sondas y ejemplos de la imagen distribuida con ese puerto.
- Flyway migra al arrancar con credenciales separadas. V1–V9 son históricas e inmutables. bootstrap-local.sql prepara roles para pruebas/local y concede DML por defecto a futuras tablas; no acredita por sí solo mínimo privilegio productivo ni aislamiento entre bases.
- build.gradle.kts ya define pruebas de procesos, variante de verificación y verifyProductionIsolation. Esta última inspecciona el JAR y debe ejecutarse antes de deshabilitar ese empaquetado para construir el nativo.
- Micrometer/Prometheus y la entrega HU08 están implementados. OutboxScheduler solo llama a backlog en published; los otros modos registran gauges pero los conservan en NaN/0 hasta una consulta válida. El filtro HTTP introduce processId, pero no tenantId, en MDC.
- Los GET de Company exigen enriquecimiento Party; las lecturas de Site son locales. Mantener salud global disponible ante Party caído no equivale a prometer que esos GET enriquecidos responderán 200.
- Party Registry aporta un workflow main/manual, renderer de entorno, Quadlet y script de recuperación probados. Publica un puerto local y supervisa readiness continuamente; Company requiere adaptación.
- El VPS es temporal, no hay consumidores preparados ni plataforma de recolección. La primera instalación será disabled con las tres redes conectadas. Los backups y su restauración están diferidos por decisión expresa.

Referencias de partida: [workflow de Party](https://github.com/astudilloalex/party-registry-service/blob/main/.github/workflows/deploy-native-vps.yml), su deploy/README.md y los contratos locales docs/contracts/outbox-publication.md y docs/operations/native-verification.md. La inspección del código no acredita el estado real del VPS.

## Goals / Non-Goals

**Goals:**

- Reutilizar el modelo operativo de Party con una instalación trasladable a otro VPS mediante imagen y configuración externa.
- Hacer observable y verificable la frontera entre generación de configuración, activación, aceptación y recuperación de versión.
- Mantener Domain y Application libres de dependencias HTTP, journald, métricas concretas y herramientas de despliegue; reutilizar los puertos existentes para I/O reactivo.

**Non-Goals:**

- Introducir políticas de negocio, endpoints comerciales, eventos, consumidores o garantías de orden/exactamente una vez.
- Ejecutar DDL desde requests, revertir migraciones, borrar historia o usar una restauración de base como rollback de aplicación.
- Añadir backup, objetivos RPO/RTO, restauración, colectores, dashboards, alertas, alta disponibilidad o despliegue sin interrupción.
- Activar published ni crear colas/bindings en el broker compartido durante esta primera entrega.

## Decisions

### 1. Separar aprovisionamiento inicial y entrega de la aplicación

Preparar una vez el usuario operativo rootless, su arranque persistente mediante systemd user/linger, cgroup v2, acceso SSH, redes, base y roles. El usuario deberá poder operar las redes y contenedores existentes dentro del mismo contexto rootless. Reutilizar la cuenta de plataforma si es la que posee esa infraestructura.

Versionar instrucciones y recursos para company-db.network y para conectar PostgreSQL a ella de forma declarativa y persistente. Resolver NetworkName antes de comprobar las redes Podman: el nombre de una unidad .network no garantiza un nombre Podman sin prefijo systemd-. Conservar las demás redes del PostgreSQL compartido.

La base y roles se preparan administrativamente; Flyway sigue siendo la autoridad de sus objetos de esquema. Auditar privilegios PUBLIC, membresías y autenticación existentes; limitar runtime a tablas operacionales y excluir escritura del historial Flyway. Cualquier restricción que afecte al clúster compartido deberá conservar accesos de los otros servicios. Las pruebas de inspección del historial usarán identidad de prueba apropiada, no permisos runtime excesivos.

**Alternativa considerada:** crear/modificar infraestructura compartida durante cada push. Se descarta para limitar el alcance de cada entrega y evitar recreaciones accidentales del servidor de base o del broker.

### 2. Quadlet estable con tres redes y sin puertos publicados

Crear deploy/quadlet/company-registry-service.container.template con las tres referencias de red incondicionales:

- internal-services.network
- company-db.network
- messaging.network

Declarar ContainerName y NetworkAlias company-registry-service, perfil prod y puerto 8083. Alinear renderer, EXPOSE de la imagen, sondas y smoke tests con company-registry-service:8083. Omitir PublishPort; el EXPOSE de la imagen no publica puertos por sí solo. No introducir rutas Nginx ni conexión Keycloak.

Reutilizar UID 1001, NoNewPrivileges, eliminación de capacidades, raíz de lectura, temporales necesarios y LogDriver=journald. Conservar el filtro de recursos del contexto de imagen para excluir configuración productiva. La comprobación HTTP deberá existir y ejecutarse dentro de la imagen final bajo las mismas restricciones.

Publicar inicialmente para la arquitectura Linux/amd64 utilizada por Party y comprobar arquitectura/CPU reales antes de activar el VPS. El build nativo utilizará compatibilidad de CPU y conservará la inicialización en runtime de UuidV7Generator. Una discrepancia de arquitectura detiene la entrega.

**Alternativa considerada:** condicionar messaging al modo outbox o añadir un puerto diagnóstico local. Se descarta: el usuario confirmó las tres redes desde el comienzo y el diagnóstico puede hacerse internamente.

### 3. GitHub production como fuente de configuración

Usar secretos/variables del entorno production para renderizar el entorno y keyring. Las pruebas normales no recibirán credenciales productivas. Restringir el despliegue y el entorno a main; no añadir aprobación manual obligatoria al despliegue automático acordado.

| Configuración | Ubicación |
| --- | --- |
| DB_PASSWORD, FLYWAY_DB_PASSWORD | Secretos production |
| RABBITMQ_USERNAME, RABBITMQ_PASSWORD | Secretos production, opcionales en disabled/stored-only |
| COMPANY_IDEMPOTENCY_KEYRING_JSON | Secreto production propuesto, solo para el renderer |
| VPS_HOST, VPS_USER, VPS_SSH_PRIVATE_KEY, VPS_SSH_KNOWN_HOSTS | Secretos production, siguiendo Party |
| DB_USERNAME, FLYWAY_DB_USERNAME, DB_REACTIVE_URL, DB_JDBC_URL | Variables production |
| PARTY_REGISTRY_BASE_URL, GEOGRAPHIC_REFERENCE_BASE_URL y timeouts | Variables production |
| COMPANY_IDEMPOTENCY_ACTIVE_KEY_ID | Variable production |
| COMPANY_OUTBOX_MODE y COMPANY_OUTBOX_* | Variables production |
| RABBITMQ_HOST, RABBITMQ_PORT, RABBITMQ_VIRTUAL_HOST | Variables production |
| Límites de recursos y tiempos del despliegue | Variables production usadas al renderizar Quadlet |
| COMPANY_SMOKE_TENANT_ID, COMPANY_SMOKE_USER_ID, COMPANY_SMOKE_PARTY_ID | Variables production para el ámbito de verificación |

Valores iniciales: modo disabled, URLs de Party y Geographic Reference por sus nombres internos y puerto 8080, vhost platform, exchange company.events y routing key literal %s. Un renderer basado en Party validará perfil, puerto 8083 de Company, nombres, valores, límites y ausencia de duplicados/placeholders. La validación de broker será condicional a published; el conjunto de redes nunca será condicional.

COMPANY_IDEMPOTENCY_KEYRING_JSON será un mapa ID -> secreto Base64. No es una variable nueva leída por la aplicación: el renderer emitirá las propiedades existentes company-registry.idempotency.keys.<id>. Validará IDs, duplicados JSON, Base64, longitud mínima de 32 bytes y presencia del ID activo. Los errores nombrarán parámetros, no valores.

**Alternativas consideradas:** configuración mantenida solo a mano en el VPS o claves nuevas generadas en cada entrega. Se descartan porque GitHub fue elegido como fuente y las claves forman parte de la compatibilidad persistida.

### 4. Configuración por versión y keyring con evolución independiente

Preparar un directorio privado por ejecución con entorno y metadatos de imagen. El Quadlet activado apuntará a esa configuración concreta. Instalarlo mediante reemplazo atómico después de preparar sus recursos, evitando depender de que dos reemplazos consecutivos de archivos sean una transacción atómica.

Entregar el keyring como secreto Podman montado en /run/secrets/company-keys.properties con UID 1001 y modo 0400; QUARKUS_CONFIG_LOCATIONS señalará esa ruta. Proteger también el material de origen en host y temporales con directorios 0700 y archivos 0600. Los secretos Podman no se consideran cifrado automático del disco.

La validación comparará el keyring entrante con el registro protegido instalado y con los IDs requeridos por aceptaciones existentes cuando se compruebe compatibilidad. Rechazar omitir o cambiar claves conservadas; una rotación añade material antes de cambiar el ID activo. No registrar secretos ni hashes de fingerprints.

El conjunto compatible de claves evoluciona independientemente de la imagen. Si K2 pudo aceptar registros durante una activación, un rollback a una imagen anterior conserva K1 y K2 y puede recuperar K1 como activa. La referencia de keyring compatible debe aplicarse también al Quadlet recuperado. No recuperar ciegamente un keyring antiguo ni borrar el nuevo al limpiar una ejecución fallida.

Conservar lo necesario para la versión actual y la recuperación compatible previa; limpiar solo temporales y recursos no referenciados. Esta conservación de configuración para rollback no introduce un sistema de backups de base.

**Alternativa considerada:** recuperar exactamente todos los archivos anteriores. Se descarta porque podría eliminar claves ya usadas por la base actual.

### 5. Pipeline main/manual con controles explícitos

Organizar tres responsabilidades, con dependencias entre sus jobs:

1. verify: checkout, Java 25, wrapper Gradle, formato/análisis estático configurado, arquitectura, pruebas unitarias e integración PostgreSQL/RabbitMQ, contratos y artefacto JVM.
2. build_publish: solo main, compilación nativa, pruebas del ejecutable, construcción y comprobación de imagen, publicación GHCR y salida del digest.
3. deploy: entorno production, render, transferencia y operación de activación en el VPS.

Los PR ejecutan verify. Push a main y workflow_dispatch desde main ejecutan el recorrido de entrega. Las demás referencias no acceden a production. Serializar la entrega productiva sin cancelar el despliegue activo. No asumir una cola FIFO de todos los commits; la exclusividad protege la activación en curso.

Usar Docker en el runner como Party y Podman rootless en el VPS. En CI sobrescribir explícitamente el runtime nativo Podman configurado en application.properties. Mantener Mandrel JDK 25/UBI 9, la compatibilidad CPU y la opción de inicialización UUID. Fijar versiones de herramientas y registrar digest de builder/base utilizados.

Ejecutar verifyProductionIsolation en el build JVM antes de deshabilitar JAR. Ejecutar las suites de proceso nativo explícitamente mediante la configuración existente quarkusIntTest/testNative; no asumirlas acreditadas por un build exitoso. Nunca publicar la variante -Pverification=true. Las omisiones condicionales de fixtures deberán identificarse y no sustituir casos requeridos del artefacto productivo.

Configurar un gate de formato compatible con Java 25 y un alcance/baseline explícito, evitando una reforma cosmética indiscriminada. Los checks arquitectónicos y de contratos existentes seguirán siendo obligatorios.

Comprobar desde un runner limpio el acceso a java-shared-libraries con GITHUB_TOKEN y permisos de lectura adecuados al paquete. No depender de mavenLocal ni usar credenciales de aplicación/SSH para resolver dependencias. Si falta acceso, corregir ese prerrequisito antes de habilitar el pipeline; no eludirlo mediante secretos productivos en PR.

**Alternativa considerada:** compilar en el VPS o tener un job que publique y despliegue pese a fallos anteriores. Se descarta por consumo, aislamiento y trazabilidad.

### 6. Una imagen comprobada y referenciada por digest

Construir una sola imagen productiva, probarla con configuración externa desechable y las restricciones previstas, publicarla sin reconstrucción y desplegar su digest GHCR. Registrar commit, digest, herramientas y resultados. La etiqueta sha-<commit> sirve para identificación; latest no decide qué se instala.

Preservar el digest compatible anterior. La dependencia HTTP compartida sigue siendo SNAPSHOT: no ampliar esta entrega para cambiar su contrato, pero tampoco reconstruir un commit para simular rollback.

Para una imagen GHCR privada, preaprovisionar autenticación de lectura protegida para el usuario Podman; una imagen pública podrá descargarse sin esa credencial. La visibilidad concreta del paquete es un dato de aprovisionamiento, no un motivo para poner tokens de build en la aplicación.

**Alternativa considerada:** desplegar latest o reconstruir en el host. Se descarta porque no preserva la identidad verificada.

### 7. Activación como operación que termina después de los smoke tests

Adaptar deploy/vps/deploy.sh y sus pruebas de recuperación tomando Party como referencia. Añadir exclusividad local para impedir interferencia con una ejecución manual fuera de Actions.

Secuencia: preflight de contexto/redes/configuración y compatibilidad, validación del Quadlet generado, descarga de imagen, preparación privada de configuración/keyring, selección atómica del Quadlet, daemon-reload, reinicio, readiness, smoke tests y aceptación. Los fallos previos al reemplazo conservan el servicio anterior.

Conservar activa la recuperación hasta que todos los controles obligatorios terminen. Ante fallo posterior al reemplazo, restaurar la imagen/configuración compatibles con keyring vigente y comprobar su salud. La primera instalación fallida no tiene versión anterior: informar fallo y retirar solo su configuración no utilizable. Si la recuperación falla, conservar recursos protegidos y reportar intervención necesaria.

Mantener Flyway migrate-at-start y sus credenciales separadas. Ante PostgreSQL ausente desde el inicio, el proceso puede fallar antes de exponer HTTP y systemd aplicará su política acotada. Ante pérdida posterior de base, readiness baja sin matar por ello un proceso vivo.

No prometer atomicidad de una operación SSH frente a pérdida eléctrica o SIGKILL. Los recursos por versión y la unidad seleccionada deben permitir identificar el estado efectivo y reanudar o recuperar la operación.

**Alternativa considerada:** dar por aceptada la entrega al pasar readiness y ejecutar smoke en un paso independiente posterior. Se descarta porque un fallo funcional podría dejar activa una entrega fallida.

### 8. Salud, reinicio y presupuesto de recursos

Usar readiness en la comprobación de arranque y liveness en el healthcheck continuo, con integración de systemd para recuperar fallos del proceso. La aceptación remota comprobará readiness de manera explícita. Mantener deshabilitado el health del canal RabbitMQ.

Coordinar grace period de aplicación, timeout de parada del contenedor, timeout de systemd y presupuesto del job. Probar la recuperación real ante terminación del proceso; un test de renderer por sí solo no lo acredita.

Medir imagen final en reposo y con peticiones concurrentes y trabajo outbox en entorno aislado. Derivar memoria, CPU, conexiones y procesos con margen documentado; no trasladar 512M/100% de Party ni los 5 GiB del compilador a una supuesta medida de runtime. Estos valores alimentarán variables de despliegue, no constantes arbitrarias.

**Alternativa considerada:** healthcheck continuo contra readiness. Se descarta porque reiniciaría por fallos de base aunque el proceso siguiera respondiendo.

### 9. Instrumentación técnica sin alterar contratos de negocio

Completar JSON de consola mediante la extensión de logging compatible con el BOM y exportarlo a journald. Añadir tenantId validado al contexto técnico junto con processId, preservar propagación asíncrona y limpiar únicamente las claves propias también ante errores y cancelación. Registrar finalización con ruta normalizada, estado y duración, sin cuerpos ni valores de consulta.

Verificar primero los binders Micrometer HTTP/REST client existentes; completar solo lo que falte para latencia, resultado y timeouts. Acreditar específicamente el pool reactivo PostgreSQL; métricas Agroal por sí solas no bastan. Normalizar rutas de servidor/cliente y limitar etiquetas sin identidades.

Mantener lectura Company enriquecida y errores 503/502 actuales. Las lecturas Site y otras operaciones locales no adquirirán llamadas de proveedores para instrumentarse. Verificar sanitización también de logs del framework y del conector.

Documentar consulta privada con herramientas del contenedor y journalctl --user, persistencia/retención efectiva del journal y reinicio de contadores. No incorporar puertos de gestión publicados, colectores ni tracing obligatorio.

**Alternativa considerada:** instrumentar cada política de dominio o añadir un stack completo de observabilidad. Se descarta por los límites de arquitectura y el alcance acordado.

### 10. Observación outbox independiente de la entrega

Separar el muestreo reactivo de backlog del flujo de reserva/envío. Reutilizar DeliveryStore.backlog y contextos/sesiones independientes en Infrastructure. Ejecutar una consulta periódica no solapada en los tres modos, con intervalo positivo configurado y plazo de consulta/finalización acotado; no consultar PostgreSQL durante cada scrape HTTP.

Mantener NaN/0 antes del primer resultado válido y ante fallo/timeout; usar 0/0/1 para una outbox vacía consultada correctamente. El estado de medición depende de la consulta de base, no de un fallo de broker. Preservar nombres de gauges y contadores de HU08, así como la distinción confirmed/published/retry/failed/exhausted.

Actualizar StoredOnlyOutboxMetricsBootTest, OutboxTelemetryTest y los escenarios de procesos que actualmente esperan NaN permanente en modos sin entrega. Controlar el instante previo al primer muestreo y esperar valores posteriores; conservar pruebas de cero conexiones AMQP y de filas/intentos intactos.

**Alternativa considerada:** activar parcialmente el publisher o consultar backlog al leer /q/metrics. Se descarta porque mezcla observación con entrega o hace depender el scrape de I/O no acotado.

### 11. Smoke tests de alcance dedicado y criterios trazables

Preparar las variables de un tenant y una Party LEGAL_ENTITY válida exclusivos de verificación antes de activar el pipeline. Usar claves idempotentes estables, códigos dedicados y payloads constantes para Company/Site; la primera ejecución prueba creación y las posteriores prueban replay y lectura sin generar registros en cada despliegue. CI seguirá acreditando nuevas creaciones y concurrencia en infraestructura aislada.

Comprobar liveness, readiness, OpenAPI, métricas y ausencia de rutas auxiliares internamente, sin publicar puertos. Las respuestas de negocio se validan sin imprimir datos Party ni contactos. Un fallo conocido de prerrequisitos del ámbito de prueba debe detectarse antes de detener el servicio previo cuando sea posible.

| Criterios de la issue #11 | Evidencia prevista |
| --- | --- |
| AC01, AC02, AC13 | Inspección real de redes, DNS, puerto 8083, ausencia de bindings host y ruta Nginx |
| AC03, AC04 | Base dedicada, identidades efectivas y pruebas negativas de privilegios |
| AC05, AC08 | CI PostgreSQL/RabbitMQ y proceso/imagen; comprobación del broker productivo diferida hasta habilitar published |
| AC06 | Imagen y VPS disabled, tres redes, sin credenciales ni conexiones AMQP |
| AC07 | CI stored-only con conservación y medición de pendientes, sin AMQP |
| AC09 | Fallos controlados en CI: lecturas locales disponibles y operaciones dependientes con errores previstos |
| AC10, AC11 | Salud, pérdida/recuperación de base y reinicio de proceso en entorno aislado; salud/reinicio del servicio en VPS |
| AC12 | Gates obligatorios y rechazo verificable de entregas fallidas |
| AC14 | Renderer, permisos, contexto de imagen y revisión de diagnósticos sin secretos |
| AC15 | Series HTTP/clientes/pool/outbox y logs correlacionados en JVM, nativo, imagen y VPS según corresponda |
| AC16 | Diferido por decisión del usuario; no se acredita ni se ejecutan tareas de backup/restore |
| AC17 | Recuperación de digest/configuración compatibles con esquema, eventos y keyring actuales |

**Alternativa considerada:** ejecutar la matriz de fallos contra infraestructura compartida o generar datos de negocio arbitrarios en cada release. Se descarta para preservar otros servicios y mantener el smoke repetible.

## Risks / Trade-offs

- VPS temporal con una instancia y sin backups dentro de este alcance -> reconocer la interrupción del reemplazo y no atribuir capacidad de restauración a esta entrega.
- Acceso o nombres reales de infraestructura diferentes a la referencia -> preflight de usuario, redes, arquitectura, autenticación y permisos antes de activar cambios.
- Cambio de clave o rollback incompatible -> keyring conservado con evolución independiente, pruebas K1/K2 y rechazo de configuraciones incompatibles.
- Build nativo con memoria insuficiente o CPU incompatible -> registrar recursos, usar parámetros medidos de compilación y comprobar la imagen para la plataforma del VPS.
- Dependencia SNAPSHOT mutable -> conservar y usar digests exactos de imágenes verificadas.
- Consultas de backlog costosas al crecer historia -> timeout, muestreo no solapado, inspección de plan/carga y separación del camino HTTP; una nueva optimización de esquema exige migración posterior a V9.
- Fallos de render o logs que revelen secretos -> validación previa, permisos privados, valores literales y pruebas con marcadores sensibles en todas las salidas.
- Metrics sin colector y journald sin retención garantizada -> documentar los límites efectivamente comprobados, sin prometer historia ni alertas.

## Migration Plan

1. Preparar y probar renderer, Quadlet, script de entrega y controles usando infraestructura desechable.
2. Completar instrumentación y muestreo; ejecutar regresiones JVM/nativas y de imagen, incluidos tres modos outbox.
3. Medir runtime y preparar valores de production, recursos, credenciales y ámbito smoke.
4. Aprovisionar o verificar company-db, adhesión persistente de PostgreSQL, base y roles; comprobar las tres redes y acceso interno.
5. Configurar production con modo disabled y keyring protegido; habilitar main automático y ejecución manual con exclusividad.
6. Ejecutar primera entrega, verificar digest, salud, smoke, redes, logs/métricas, reinicio y recuperación compatible; registrar evidencia real.
7. En una etapa posterior, verificar cuenta/vhost/exchange/colas/bindings y cambiar a published mediante configuración y reinicio. Esa activación no recupera eventos de disabled ni reactiva terminales.
8. Mantener AC16 y todo backup/restore como trabajo diferido; este cambio no los ejecuta ni los marca cumplidos.

## Open Questions

Los siguientes datos se recopilarán en tareas específicas de aprovisionamiento/verificación y no cambian los contratos ni el diseño:

- Usuario rootless efectivo, versión Podman/systemd, arquitectura/CPU y NetworkName de las tres unidades instaladas.
- Política actual de autenticación/privilegios PostgreSQL y acceso de Company a la dependencia GitHub Packages desde un runner limpio.
- Visibilidad del paquete GHCR, identidades del ámbito smoke y valores numéricos de recursos después de medirlos.

El modo inicial, las redes, los disparadores, la fuente de secretos y el aplazamiento de backups ya están decididos; no son preguntas pendientes.
