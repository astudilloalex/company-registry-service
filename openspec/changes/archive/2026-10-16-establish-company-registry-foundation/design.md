# Design

## Context

Ver `proposal.md` para la motivación. Fuentes consultadas el 2026-10-15:

- [T00 / issue #2](https://github.com/astudilloalex/company-registry-service/issues/2), sin comentarios, y su épica #1. HU01 (#3), HU03 (#5) y HU05 (#7) aclaran responsabilidades futuras, prerrequisitos y versionado; se consultan para evitar rediseños, no para incorporar sus workflows a T00.
- Company Registry: baseline `ba6bce51bcb8d17bde5d56f8afd4f474c9dd2f8e`. Build con ARC y quarkus-junit, Java source/target 25, Quarkus plugin/BOM 3.40.1 y wrapper Gradle 9.7.0. `application.properties` vacío, sin fuentes Java ni pruebas. README generado. Dockerfiles JVM con OpenJDK 25 y nativo con UBI 9 Minimal; estos archivos generados aún no constituyen evidencia de ejecución nativa.
- La V1 existente, de 978 líneas, ya declara seis tablas, siete enums, índices parciales y triggers. La issue enumera cinco enums obligatorios; los otros dos son los tipos de dirección/contacto y son compatibles. No hay tags locales; esto no demuestra que V1 nunca haya sido aplicada.
- [Party Registry](https://github.com/astudilloalex/party-registry-service/tree/fd603dd08fd980b96b5f35056345d638e4080bb2): mismo Quarkus 3.40.1 y Java 25; build, configuración, `PartyResource`, DTO de detalle, filtro de contexto, parser de headers/paginación, mapper y contratos consultados por GitHub.
- Party consume `com.alexastudillo.libraries:api-response-quarkus-errors:1.0.0-SNAPSHOT` desde Maven/GitHub Packages y usa Flyway separado del runtime reactivo. Se toma como referencia verificable, sin copiar su implementación masiva ni sus peculiaridades de validación.
- Confirmación del usuario: Company Registry se entrega nativo como Party Registry. La referencia consultada usa `quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25`, inicialización runtime de su generador UUIDv7, `Dockerfile.native` con `registry.access.redhat.com/ubi9/ubi-minimal:9.7` y usuario 1001, y pruebas empaquetadas/nativas separadas de los fixtures CDI de `src/test`. No se considera opcional la verificación nativa en T00.
- LikeC4 MCP, proyecto `default`: existe Party Registry y PostgreSQL compartido con bases lógicas separadas; no existe Company Registry. Fuentes: [microservices.c4](/home/alex/Documents/Development/architecture/alex-astudillo-architecture/catalog/system/microservices.c4:9), [database.c4](/home/alex/Documents/Development/architecture/alex-astudillo-architecture/catalog/system/database.c4:3) y [party-registry-overview.c4](/home/alex/Documents/Development/architecture/alex-astudillo-architecture/architectures/party-registry/party-registry-overview.c4:2).
- [Colección Geographic Reference](https://go.postman.co/collection/15834347-d3591c82-bd52-46a9-973d-a7a102d4b9b3), revisada mediante MCP Postman: consultas de país y división por código publicadas, sin ejemplos de respuesta guardados. Party ya versiona un contrato de país alpha-2 con procedencia explícita.

No se ha ejecutado el build ni PostgreSQL durante esta planificación; presencia de SQL y coincidencia de versiones no son pruebas de compatibilidad ni integridad.

## Goals / Non-Goals

**Goals:**

- Hacer verificables las seis capabilities de esta propuesta mediante pruebas de sus fronteras, preservando V1.
- Entregar y verificar binario e imagen nativos para la plataforma Linux objetivo, con JVM disponible para desarrollo y diagnóstico complementario.
- Establecer puertos y modelos mínimos útiles, separación de credenciales, mapeos reactivos y contratos públicos reutilizables por las HU.
- Dejar decisiones y bloqueos concretos para aplicar sin investigación genérica adicional.

**Non-Goals:**

- Los modelos y políticas fundacionales no son workflows de registro, listado, actualización o activación completos.
- Las pruebas de frontera usarán recursos exclusivos de test; T00 no necesita exponer un CRUD simulado ni un endpoint comercial de diagnóstico.
- La estructura de claim/retry configura HU08; no implementa scheduler, publicación, infraestructura RabbitMQ ni despliegue de producción.

## Decisions

### 1. Conservar el baseline compartido y verificarlo

Mantener Java 25, plugin/BOM Quarkus 3.40.1 y wrapper existente, con toolchain/release 25. La selección se basa en la coincidencia real con Party; la validación de dependencias y la evidencia de build forman parte de aplicación, antes de introducir otra versión.

Añadir solo extensiones necesarias: REST Jackson y cliente REST Jackson, Hibernate Reactive/Panache, reactive-pg-client, Hibernate Validator, OpenAPI, context propagation, health; Flyway, JDBC PostgreSQL y soporte PostgreSQL de Flyway como isla de migración. Usar SmallRye Reactive Messaging como fundamento; el conector/canal RabbitMQ puede esperar a HU08 para evitar activación accidental. Cualquier extensión de métricas añadida se limita al fundamento, sin adelantar T01.

Fijar la versión del módulo publicado de respuestas en propiedades de dependencia. La versión observada en Party es SNAPSHOT, no una release inmutable: comprobar disponibilidad y registrar la selección exacta; usar credenciales de Packages por entorno/propiedades externas. No copiar clases de la librería ni sustituirla por un envelope local. ArchUnit, parser OpenAPI, JUnit Jupiter, Mockito, RestAssured, herramientas de test Vert.x y PostgreSQL real cubren las fronteras necesarias.

**Alternativa descartada:** actualizar versiones o instalar todas las extensiones reactivas por anticipación; amplía el cambio sin evidencia de necesidad.

#### 1.1. Entrega nativa obligatoria alineada con Party

Usar build nativo en contenedor con Mandrel JDK 25 UBI 9, compatible con el BOM elegido, y seleccionar Podman explícitamente. El builder de referencia es `quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25`; registrar versión/digest resuelto y arquitectura al verificarlo. Una instalación local compatible de GraalVM/Mandrel puede documentarse como alternativa de construcción, sin reemplazar la ruta reproducible con Podman.

Comandos base de Party que deberán validarse y documentarse al implementar:

```shell
./gradlew quarkusBuild -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false -Dquarkus.native.container-build=true -Dquarkus.native.container-runtime=podman
./gradlew quarkusIntTest -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false -Dquarkus.native.container-build=true -Dquarkus.native.container-runtime=podman
podman build -f src/main/docker/Dockerfile.native -t company-registry-service:native .
```

El artefacto de entrega será el binario `build/*-runner` y su imagen OCI con UBI 9 Minimal, usuario no privilegiado, puerto explícito y ejecución directa sin JVM. El `Dockerfile.native` existente coincide con la referencia de Party; verificar libc, arquitectura y librerías necesarias antes de cambiarlo. El artefacto JVM permanece útil para desarrollo y regresiones; completar `./gradlew clean build` no sustituye construir y probar el nativo. La promoción de esa imagen a producción sigue perteneciendo a T01.

La verificación nativa debe cubrir compatibilidad de `api-response-quarkus-errors`, Bean Validation, DTOs de API/clientes, snapshots JSONB, enums PostgreSQL e Instant. Usar metadatos automáticos de las extensiones; añadir registros de reflexión/recursos específicos solo si las pruebas demuestran que son necesarios, en Infrastructure/API o configuración de build. No añadir anotaciones Quarkus al Domain para resolver native-image.

Incluir y verificar `db/migration/**` y el contrato generado en `META-INF/openapi.yaml` mediante los mecanismos de sus extensiones. El binario debe arrancar sin montar fuentes/documentos, aplicar/validar Flyway y publicar el contrato esperado. Ningún recurso auxiliar de test se incluye en la entrega productiva.

Si el generador Java UUIDv7 mantiene `SecureRandom` u otro estado aleatorio, inicializar su componente concreto en runtime, como Party; no copiar el FQN de Party ni habilitar inicialización global indiscriminada. Comprobar UUIDv7 válidos en procesos independientes y evitar capturar semillas durante la compilación. Los defaults `uuidv7()` PostgreSQL siguen siendo compatibles.

**Alternativa descartada:** dejar nativo como una opción del README, aceptar solo tests JVM o usar un builder JDK inferior al bytecode Java 25.

#### 1.2. Pruebas nativas y separación de artefactos

Separar pruebas unitarias/`@QuarkusTest` de las pruebas externas del binario con `@QuarkusIntegrationTest`. Seguir el patrón de Party: source set `integrationTest`, aserciones compartidas en `contractTest`, `setExtraNativeTest` y perfil de lanzamiento explícito para `quarkusIntTest`/`testNative` cuando corresponda al plugin. No incluir `sourceSets.test.output` en classpaths de verificación empaquetada ni depender de `@Inject`/mocks CDI de `src/test` para alterar un proceso nativo ya construido.

La suite debe utilizar PostgreSQL 18 real y stubs HTTP que el binario pueda alcanzar con URLs configurables. Health, OpenAPI, Flyway y ausencia de rutas auxiliares se comprueban contra el **binario productivo**. Para ejercitar mapeos/atomicidad, éxito/error de negocio y clientes antes de implementar las HU, construir una **variante nativa de verificación** con fuentes auxiliares separadas y habilitación explícita al construir. Las rutas/fixtures de esa variante no se compilan en producción. Mantener salidas/etiquetas y evidencia diferenciadas para no confundir el binario de verificación con el entregable; un flag de runtime que solo oculte endpoints de prueba en la imagen productiva es insuficiente.

Documentar en `docs/operations/native-verification.md` comandos, perfiles, socket rootless Podman para Testcontainers, configuración PostgreSQL/roles, stubs, plataforma y recursos de compilación utilizados. La falta de builder, memoria o runtime de contenedores se reporta como bloqueo de verificación; no se marca nativo aceptado con una prueba omitida ni se recupera usando JVM silenciosamente.

### 2. Clean Architecture y repositorios asíncronos

Paquete base propuesto: `com.alexastudillo.companyregistry`, con `domain`, `application`, `api` e `infrastructure`.

| Límite | Responsabilidades de T00 |
| --- | --- |
| Domain | Company/Site como conceptos separados, enums, identidad/versiones con invariantes, políticas puras de texto/contactos y estados; sin anotaciones, HTTP, ORM o Mutiny. |
| Application | Contexto neutral, resultados/fallos internos, puertos de persistencia e integración y contrato de atomicidad requerido por las futuras HU; `Uni` permitido para I/O por elección explícita del perfil reactivo. |
| API | Contexto de headers, DTOs/validación, catálogo propio de códigos y traducciones a la librería, mapper de respuestas y configuración de contrato. |
| Infrastructure | Entidades ORM y adaptadores, Flyway, clientes externos, configuración outbox, composición CDI y observabilidad técnica. |

La issue menciona repositorios dentro de Domain. Para conservar Domain síncrono y libre de Mutiny, esta propuesta ubica las firmas de repositorios de I/O en Application y mantiene sus conceptos en Domain. Es una decisión de diseño explícita para revisión, no autorización para poner `Uni` en entidades/políticas. No crear repositorios CRUD genéricos ni use cases pass-through.

Usar Hibernate Reactive con fronteras de sesión y transacción programáticas en adaptadores/composición que envuelvan operaciones de Application. No introducir el estilo preview reactive `@Transactional` ni mezclarlo con Panache. Las escrituras futuras de agregado + finalización idempotente + evento compartirán una única transacción local; los tests fundacionales comprobarán commit/rollback mediante un fixture, sin implementar registro comercial.

**Alternativa descartada:** `Uni` en Domain o anotaciones de persistencia en agregados; contradicen la independencia requerida.

### 3. Flyway y mapeos sin reescribir V1

Configurar PostgreSQL 18 con acceso reactivo de runtime y JDBC reservado a Flyway. Habilitar migrate-at-start, validación de migraciones y protección contra clean en uso normal. Desactivar generación/actualización DDL por Hibernate; utilizar validación compatible con el BOM cuando sea aplicable. No habilitar JDBC/JTA para el flujo operacional.

Variables independientes: `DB_USERNAME`, `DB_PASSWORD`, `DB_REACTIVE_URL`, `DB_JDBC_URL`, `FLYWAY_DB_USERNAME`, `FLYWAY_DB_PASSWORD`, con overrides locales/tests documentados. Mismo destino lógico de base y usuarios distintos: migrador con DDL, runtime con DML/grants necesarios, sin `CREATE`. Bootstrap de roles/grants queda separado de las seis tablas de aplicación.

Preservar V1 íntegra porque su estado de despliegue no se conoce. Crear una nueva migración solo ante un defecto demostrado por pruebas; nunca editar una migración aplicada ni ejecutar repair para ocultar cambios. `flyway_schema_history` no cuenta como séptima tabla del contrato de negocio/técnico de la issue.

Mapear las seis tablas en Infrastructure: enums PostgreSQL nombrados, `timestamptz`/Instant, JSONB, PK compuesta de idempotencia, versiones de Company/Site/outbox y referencias qualificadas. Preferir composición explícita sobre cascadas ORM; queries de hijos deben verificar tenant/Company vía la sede. El FK simple de direcciones/contactos a Site no sustituye ese filtro de tenant.

Todos los IDs generados son UUIDv7: mantener defaults `uuidv7()` de PostgreSQL 18 y una generación equivalente encapsulada si se necesitan IDs antes del insert. Comprobar compatibilidad Hibernate/versión inicial cero y enum/JSON mediante round-trip real. Los UUID externos de Party/tenant no se convierten arbitrariamente a UUIDv7.

**Alternativa descartada:** recrear V1, usar H2 o confiar en schema auto-update; no verifican PostgreSQL 18 ni conservan el historial.

### 4. Decisiones de negocio que deben quedar en ADR

1. **Party–Company:** máximo una Company por Party dentro del tenant, incluso al archivar; la identidad legal permanece en Party. No FK ni copia legal local.
2. **Sede legal:** HEADQUARTERS significa sede legal; máximo una no CLOSED. DRAFT/INACTIVE también ocupan esa unicidad. Una Company ACTIVE requiere exactamente una sede legal ACTIVE; el índice parcial solo garantiza el máximo, no la precondición completa.
3. **Versionado:** Company y Site son agregados distintos. Direcciones y contactos comparten `company_sites.version`. Creación en 0, cada mutación efectiva +1; no-op/replay no incrementan. `aggregate_version` de evento y `version` técnico outbox son diferentes.
4. **Ciclo de vida:** Company permite DRAFT→ACTIVE/ARCHIVED, ACTIVE→INACTIVE e INACTIVE→ACTIVE/ARCHIVED; ARCHIVED es terminal. Site permite DRAFT→ACTIVE/CLOSED, ACTIVE→INACTIVE/CLOSED e INACTIVE→ACTIVE/CLOSED; CLOSED es terminal. Activar Site bajo Company DRAFT/INACTIVE permite preparar la activación de Company. Operatividad se deriva de ambos ACTIVE, sin columna adicional ni cascada de estados.
5. **Consistencia futura:** activación Company y cambios de sede legal usarán orden de locks Company→Site, con revalidación local bajo transacción. Mantener llamadas HTTP fuera de locks prolongados; no afirmar atomicidad distribuida con Party. El reemplazo V1 sigue desactivar Company→cerrar sede→registrar/activar reemplazo→reactivar. Implementar esos workflows y carreras corresponde a HU03/HU05; T00 fija contratos/políticas e índice único concurrente.

**Alternativa descartada:** exigir Company ACTIVE para activar la primera sede; produciría dependencia circular en los prerrequisitos.

### 5. Frontera HTTP explícita y OpenAPI fundacional

Consumir el módulo `api-response-quarkus-errors` y el `ResponseManager` CDI. Toda futura firma de negocio será `Uni<RestResponse<ApiResponse<ResponseDto>>>`. Mapear Application→DTO antes del envelope; traducir fallos neutrales en API a `ApiResponseException`. El mapper compartido produce el error final. No añadir mapper global local, handler de autenticación ni filtro genérico de éxitos.

Contexto limitado a `/v1` y `/v1/*`: un Process-Id UUID canónico validado primero y conservado exactamente; un Tenant-Id UUID canónico; un User-Id no vacío, ≤128 code points y sin controles. Contexto reactivo por request y limpieza de MDC garantizada. No usar autenticación Keycloak ni inferir autorización de headers; son contratos internos confiables.

Activar JSON desconocido como fallo y respetar la configuración necesaria para que los errores Jackson alcancen el mapper compartido. La API genera una copia normalizada antes de Bean Validation y el núcleo reutiliza la política pura. Errores por campo estables a través del mecanismo de validación compartido, sin copiar peculiaridades de Party que eliminen esos detalles. Configurar fallback pre-REST compartido para `/v1/*` y verificar también el path raíz `/v1` según soporte de la librería. `/q` conserva su protocolo.

Crear `docs/contracts/company-registry.openapi.yaml` con OpenAPI 3.1.x, componentes comunes de contexto, DTOs/envelope, catálogo inicial de errores, headers y ejemplos. Copiarlo a `META-INF/openapi.yaml` al empaquetar como fuente única y comprobar su publicación desde el ejecutable nativo. En T00 `paths` describe únicamente endpoints realmente disponibles; puede ser `{}` mientras no haya negocio implementado. Un inventario separado vincula rutas futuras a HU01–HU07. Los recursos de verificación HTTP viven exclusivamente en fuentes/perfil de test y en la variante nativa de verificación descrita en 1.2; no se publican como negocio disponible ni se incluyen en el binario productivo.

Convenciones propuestas, basadas en Party:

- `Idempotency-Key`: una clave no vacía, máximo 128 caracteres, preservada exactamente. Obligatoria al registrar Company; futura configuración por operación para otras mutaciones. Fingerprint del contenido efectivo con HMAC SHA-256 conforme al comentario V1; clave por configuración externa cuando se implemente el workflow, sin secreto obligatorio para el bootstrap vacío. Snapshot de respuesta original con versión de esquema; no reconsultar el estado actual para replay.
- `If-Match`: un decimal canónico no negativo `0|[1-9][0-9]*` dentro de int64, sin comillas ni wildcard, conforme a la convención actual de Party. Documentar explícitamente esta convención interna; no inventar ETags HTTP en T00. Falta/formato inválido: 400; versión obsoleta: 412; conflicto idempotente: 409.
- Paginación: `cursor` opaco y `limit` 1–200, por defecto 50; orden `created_at DESC, id DESC`, filtros exactos Company `code`, `partyId`, `status` y rechazo de parámetros desconocidos/repetidos. `data` es lista y metadatos compartidos contienen `nextCursor`, `prevCursor`, `totalElements`, `totalPages`, `numberOfElements`, respetando omisión de nulos. Cursor ligado a tenant, filtros y orden; cursor inválido/incompatible: 400. La navegación no promete snapshot entre requests concurrentes. Listado/codec de cursor se implementan en HU01/HU04, no en T00.

Pruebas con fixtures HTTP: éxito, validación, traducción de fallo conocido, headers ausentes/repetidos/inválidos, JSON inválido/desconocido, 404/405/415 y 500 saneado; coincidencia status HTTP/envelope y separación de health/OpenAPI. Revisar además firmas/tipos genéricos: compilar no prueba cumplimiento.

**Alternativa descartada:** exponer una Company ficticia solo para demostrar el envelope o publicar todos los endpoints futuros como implementados.

### 6. Integraciones sustentadas por evidencia

| Puerto | Evidencia y adaptación |
| --- | --- |
| Party | `GET /v1/parties/{partyId}` de Party. Headers Tenant-Id/User-Id/Process-Id. Respuesta usa `data.partyId`, `type`, `recordStatus`; el tenant se restringe en el servidor y no aparece como campo del DTO. Proyectar solo identidad/tipo/estado, sin copiar nombres/detalles legales. Verificar ID y contrato; un 404 es ausencia en ese tenant, no una búsqueda global. |
| País | `GET /api/v1/countries/by-alpha2/{alpha2Code}`; contrato versionado por Party y request Postman `ed5c0238-71ab-455a-32b8-e63856198cb5`. Conservar ID/alpha2/status para la validación y consulta posterior. |
| División | Ruta publicada: `GET /api/v1/countries/{countryId}/administrative-divisions/by-canonical-code/{canonicalCode}`, request Postman `feaec323-d194-401e-9891-bf97b90a088f`. Requiere countryId resuelto desde país alpha-2. No hay ejemplo guardado: confirmar esquema, status y correspondencia con country antes de implementar un DTO/adapter habilitado. |

Diferencia de headers geográficos: Postman usa process-id/user-id y company-id opcional; Party versionó Tenant-Id/User-Id/Process-Id como adaptación de su contexto. T00 debe preservar Process-Id/User-Id y documentar Tenant-Id solo si el proveedor lo admite; **no** convertir tenant en company-id. Guardar esta procedencia por cada contrato, sin sustituir la especificación del proveedor por una suposición.

URLs configurables `PARTY_REGISTRY_BASE_URL` y `GEOGRAPHIC_REFERENCE_BASE_URL`; conectar en 2 s y leer en 5 s como valores iniciales sobrescribibles, siguiendo Party. No iniciar llamadas al boot ni requerir que los servicios estén vivos. Traducir timeout/unavailable/ausencia/contrato inválido a fallos neutrales; sin retry genérico o payload externo en errores públicos.

El puerto de división y la documentación de bloqueo se entregan aunque no se confirme la respuesta. Eso cumple el mandato explícito de T00 sobre dependencias no disponibles: HU06 permanece bloqueada para referencias administrativas; no se inventa la implementación ni se marca el lookup como completado.

**Alternativa descartada:** declarar que no existe ruta de división o implementar endpoints supuestos; Postman demuestra la ruta pero no completa su respuesta.

### 7. Modos outbox sin publisher anticipado

Configurar `company-registry.outbox.mode=${COMPANY_OUTBOX_MODE:disabled}` y una política de almacenamiento independiente del envío. disabled omite persistencia; stored-only/published permiten persistir. published es un valor válido reservado a HU08, no una promesa de entrega en T00. Ningún canal/emitter/scheduler de envío se activa en este cambio.

Contrato inicial siguiendo nombres/defaults de Party:

| Propiedad bajo `company-registry.outbox` | Variable | Default |
| --- | --- | --- |
| `mode` | `COMPANY_OUTBOX_MODE` | `disabled` |
| `publisher.poll-interval` | `COMPANY_OUTBOX_POLL_INTERVAL` | `5s` |
| `publisher.batch-size` | `COMPANY_OUTBOX_BATCH_SIZE` | `25` |
| `publisher.publish-timeout` | `COMPANY_OUTBOX_PUBLISH_TIMEOUT` | `5s` |
| `publisher.claim-lease` | `COMPANY_OUTBOX_CLAIM_LEASE` | `30s` |
| `publisher.initial-backoff` | `COMPANY_OUTBOX_INITIAL_BACKOFF` | `1s` |
| `publisher.maximum-backoff` | `COMPANY_OUTBOX_MAXIMUM_BACKOFF` | `5m` |
| `publisher.maximum-attempts` | `COMPANY_OUTBOX_MAXIMUM_ATTEMPTS` | `10` |
| `publisher.exchange` | `COMPANY_OUTBOX_EXCHANGE` | `company.events` |
| `publisher.routing-key` | `COMPANY_OUTBOX_ROUTING_KEY` | `company.events` |

Validar positividad, backoff y destinos. No provisionar exchange ni colas. Desactivar Dev Services de RabbitMQ y evitar placeholders obligatorios de broker al iniciar disabled. La configuración de claim lease no obliga a añadir columnas/tablas ahora; HU08 decidirá su mecanismo manteniendo historial incremental.

**Alternativa descartada:** copiar la configuración completa de publicación de Party a T00; puede conectar al broker incluso con el fundamento incompleto.

### 8. DBML y arquitectura central coordinada

Crear `docs/database/v1-scheme.dbml` fiel al estado SQL efectivo, con notas explícitas para CHECK, índices parciales y triggers que DBML no represente completamente. Mantener inventario nombre→regla→prueba en `docs/database/constraint-verification.md`.

El modelo LikeC4 central debe incorporar Company Registry, su base lógica bajo PostgreSQL existente, relaciones a Party/Geographic Reference, relación RabbitMQ futura/opcional y vistas de contexto/componentes que respeten los estilos/kinds centrales. No crear una instancia PostgreSQL independiente ni un segundo catálogo local.

Este cambio OpenSpec es repo-local: su `allowedEditRoots` no incluye el repositorio de arquitectura. Entregar en `docs/architecture/company-registry-architecture-handoff.md` el inventario exacto de elementos/relaciones/vistas propuestos y registrar el cambio central enlazado. La incorporación/validación y commit de diagramas requieren un cambio coordinado en el repositorio central; un handoff por sí solo no satisface el criterio completo de diagramas de la issue. No cerrar ese criterio ni marcar esa tarea como hecha hasta contar con evidencia central.

**Alternativa descartada:** diagramas locales divergentes o editar silenciosamente otro repositorio dentro de una aplicación repo-local.

### 9. Verificación por fronteras

| Frontera | Evidencia mínima |
| --- | --- |
| Unitarias | Normalización, formatos de contacto, enums/políticas, modo y parámetros outbox. |
| Arquitectura | Dominio puro, dependencias hacia dentro, puertos sin ORM/HTTP y tipos de respuesta API. |
| Migración | PostgreSQL 18 real: seis tablas, siete enums, PK/UNIQUE/FK/CHECK/índices/triggers, defaults UUIDv7, instalación limpia, validate repetido e historial intacto. |
| Concurrencia SQL | Dos conexiones/transacciones independientes compiten por HEADQUARTERS; una sola confirma. No emular la carrera con un único contexto reactivo compartido. |
| Persistencia | Round-trip de seis mapeos, tenant/Company scope y rollback atómico del fixture agregado/idempotencia/outbox sobre contexto Vert.x. |
| HTTP | Fixtures exclusivos de test para envelope/errores/framework/JSON/contexto; health y OpenAPI nativos. |
| Clientes | Stubs fieles para Party/país; división solo si su contrato se confirma. Casos de timeout, ausencia y respuesta incompatible. |
| Empaquetado JVM | `./gradlew clean build`, `./gradlew quarkusIntTest` y `java -jar build/quarkus-app/quarkus-run.jar` como verificación complementaria. |
| Binario nativo productivo | Build/test nativos obligatorios según 1.1, arranque sin JVM, Flyway con roles separados, recursos embebidos, health/OpenAPI, rutas auxiliares ausentes y disabled sin broker. Repetir arranque/validate preservando historial. |
| Variante nativa de verificación | Suite externa según 1.2: round-trip de seis mapeos y transacción, enums/JSONB/Instant, DTOs/envelope/error/validación, UUIDv7 y clientes/timeouts contra stubs, sin mocks in-process de `@QuarkusTest`. |
| Imagen OCI nativa | Construcción y arranque reales con Podman usando usuario no privilegiado, arquitectura/libc compatibles, PostgreSQL accesible y broker ausente. |

No sustituir las pruebas PostgreSQL por mocks ni usar JDBC en runtime para facilitar los fixtures. JDBC en tests de migración es una frontera de verificación, no habilitación de acceso JDBC operacional.

## Risks / Trade-offs

- [V1 ya pudo aplicarse] → Conservarla; cualquier defecto demostrado se corrige con una versión nueva y prueba de actualización.
- [SNAPSHOT compartido o credenciales Packages no disponibles] → Validar resolución al inicio de aplicación y reportar bloqueo preciso; no recrear el contrato en el servicio.
- [Ruta geográfica sin respuesta verificable y headers divergentes] → Versionar evidencia, confirmar con proveedor y mantener bloqueada únicamente la funcionalidad afectada.
- [LikeC4 vive fuera del alcance repo-local] → Handoff concreto y cambio central coordinado; no declarar T00 completamente aceptada sin diagramas alineados.
- [Mayúsculas Java/PostgreSQL dependen de reglas Unicode/collation distintas] → Casos con acentos/expansiones y prueba de compatibilidad de texto canónico; no alterar opacos.
- [Hibernate Reactive + Flyway con credenciales separadas] → Prueba con usuario runtime sin DDL y validación real de orden de arranque/mapeos en el BOM elegido.
- [published entendido como entrega activa] → Documentar explícitamente la reserva de HU08 y comprobar que T00 no inicia conexión al broker.
- [Build JVM exitoso oculta recursos/reflexión ausentes en nativo] → Pruebas externas sobre binarios nativos productivo y de verificación con recursos embebidos, librería compartida, mapeos y clientes reales contra stubs.
- [Builder/runtime incompatibles o capacidad de compilación insuficiente] → Validar JDK 25, arquitectura, libc y versiones/digests; registrar recursos utilizados y reportar el bloqueo sin omitir la aceptación nativa.
- [Fixtures de pruebas incorporados a la imagen de entrega] → Separar fuentes, classpaths y salidas por variante al construir, y probar su ausencia en el binario productivo.

## Migration Plan

1. Resolver build y módulo de respuestas con Java 25; validar builder Mandrel JDK 25 y runtime UBI 9 compatibles y registrar evidencia del baseline nativo.
2. Configurar PostgreSQL 18 local/de tests y usuarios separados. Aplicar V1 existente y validar inventario de restricciones antes de decidir si hace falta otra migración.
3. Incorporar modelos, puertos, mapeos y pruebas unitarias/de persistencia por fronteras; integrar contratos y configuración.
4. Validar HTTP en JVM y proceso nativo, construir la variante nativa de verificación y el binario productivo por separado y ejecutar sus suites. Construir/arrancar la imagen nativa con outbox disabled, proveedores sin disponibilidad y sin proxy; conservar evidencia de ausencia de fixtures en producción.
5. Alinear README, DBML, OpenAPI, ADR y handoff. Coordinar los diagramas centrales y conservar evidencia del cambio externo.
6. Rollback de aplicación mediante la imagen nativa anterior compatible; las migraciones son forward-only. Un schema defectuoso se corrige mediante migración posterior, no reescribiendo historial ni limpiando datos. En bases efímeras de tests se permite recrear la instalación.

## Open Questions

- ¿Qué respuesta exacta y reglas de actividad/correspondencia expone el lookup de división publicado? La tarea ya contempla confirmar evidencia o entregar el puerto bloqueado, sin cambiar el alcance fundacional.
- ¿V1 fue ejecutada en un entorno persistente? La estrategia conservadora de preservarla se mantiene en cualquiera de los casos.
- ¿Qué referencia tendrá el cambio coordinado de LikeC4 central? El modelo y relaciones propuestos quedan definidos; falta el enlace a su entrega, no decidir una arquitectura alternativa.
