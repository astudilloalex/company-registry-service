# Tasks

Las casillas representan implementación futura; ninguna se completa durante la planificación. Ver `design.md` para decisiones y evidencia de origen. Las pruebas y documentos se entregan con su bloque, no al final. La entrega nativa es obligatoria: un build JVM exitoso no satisface la aceptación de T00.

## 1. Baseline y dependencias

- [x] 1.1 Configurar toolchain/release Java 25 manteniendo Quarkus plugin/BOM 3.40.1 y el wrapper existente; verificar `./gradlew --version` y `./gradlew compileJava` con la herramienta elegida.
- [x] 1.2 Incorporar extensiones REST Jackson, cliente REST, Hibernate Reactive/Panache, PostgreSQL reactivo, Validator, OpenAPI, context propagation, health y fundamento Messaging; verificar resolución alineada al BOM y ausencia de ORM/JDBC operacional.
- [x] 1.3 Incorporar `api-response-quarkus-errors` publicado con versión explícita y repositorio/credenciales externas según Party; verificar resolución del artefacto y registrar coordenadas/versiones en documentación técnica sin copiar clases de la librería.
- [x] 1.4 Incorporar dependencias de pruebas JUnit Jupiter, Mockito, Vert.x, RestAssured, ArchUnit, parser OpenAPI y PostgreSQL/Testcontainers o Dev Services; verificar descubrimiento y ejecución de pruebas con una prueba fundacional real, sin exigir broker.

## 2. Límites arquitectónicos y decisiones

- [x] 2.1 Establecer paquetes `com.alexastudillo.companyregistry.{domain,application,api,infrastructure}` con responsabilidades útiles; verificar imports y documentar composición reactiva y ausencia de use cases pass-through en `docs/architecture/`.
- [x] 2.2 Registrar ADR del perfil reactivo, entrega nativa Java 25, puertos de I/O en Application y transacciones Panache programáticas en composición/adaptadores; verificar alineación del baseline nativo con Party, la diferencia con la ubicación de repositorios sugerida en la issue y Domain libre de Mutiny/Quarkus.
- [x] 2.3 Registrar ADR de Party único por tenant, sede legal, versiones Company/Site, estados terminales y prerrequisitos de activación; verificar alineación con #3/#5/#7, lock order Company→Site y preparación de Site bajo Company DRAFT/INACTIVE.
- [x] 2.4 Añadir reglas arquitectónicas de dominio puro, dirección de dependencias y prohibición de HTTP/ORM en Application; verificar que pruebas de arquitectura detectan violaciones representativas y pasan con los paquetes reales.

## 3. Modelo y normalización independientes

- [x] 3.1 Definir enums de Company/Site/tipos y modelos mínimos de identidad/versión con invariantes, preservando agregados Company y Site separados; verificar unitariamente valores válidos, versión no negativa y estados terminales/políticas básicas documentadas sin workflows de HU.
- [x] 3.2 Implementar política central de texto con trim exterior y `Locale.ROOT`; verificar acentos, espacios internos, locale turco, idempotencia, nulos opcionales, blancos y expansión de longitud antes de validar.
- [x] 3.3 Implementar reglas especializadas de email/teléfono y exclusiones de opacos; verificar preservación de email local, URL, UUID, claves/correlación y salida telefónica E.164, rechazando entrada ambigua sin país inventado.
- [x] 3.4 Documentar la matriz campo→normalización→validación en `docs/architecture/text-normalization.md`; verificar correspondencia con pruebas unitarias y con los CHECK de V1, incluyendo compatibilidad Java/PostgreSQL para texto acentuado.

## 4. Configuración PostgreSQL y Flyway

- [x] 4.1 Añadir Flyway/JDBC PostgreSQL exclusivamente para migraciones y configurar PostgreSQL 18, acceso runtime reactivo, migrate/validate y generación DDL desactivada; verificar arranque limpio y ausencia de schema update/create de Hibernate.
- [x] 4.2 Configurar URLs y credenciales runtime/migrador independientes por entorno; verificar en PostgreSQL que migrador crea el esquema y runtime puede DML pero no DDL.
- [x] 4.3 Documentar/proveer bootstrap local de base, roles y grants con PostgreSQL 18 compatible con Podman, separado de tablas de aplicación; verificar el procedimiento desde una base vacía y registrar variables en README.
- [x] 4.4 Añadir test de instalación y validación Flyway repetida, conservando contenido/checksum de V1; verificar las seis tablas más historial técnico y persistencia inalterada de schema history en reinicios.

## 5. Integridad SQL y DBML

- [x] 5.1 Crear `docs/database/v1-scheme.dbml` e inventario `docs/database/constraint-verification.md` desde la V1 existente; verificar seis tablas, siete enums y trazabilidad de cada PK/UNIQUE/FK/CHECK/índice/triggers a un caso válido e inválido.
- [x] 5.2 Verificar con tests PostgreSQL Company: PK, unicidad tenant/id/código/Party, versiones, texto y auditoría no vacíos, identidad inmutable y ARCHIVED terminal; completar en el inventario el nombre de cada prueba y restricción.
- [x] 5.3 Verificar Site: FK tenant-qualified, unicidades, texto/auditoría/versiones, identidad y CLOSED terminal; probar carrera real con dos transacciones por HEADQUARTERS y aceptación de histórico cerrado sin duplicado vigente.
- [x] 5.4 Verificar Address: FK, país/división/texto, coordenadas nulas/en par/límites/fuera de rango, primaria parcial, auditoría, borrado lógico e identidad inmutable; comprobar que cada CHECK aparece en el inventario y tiene evidencia PostgreSQL.
- [x] 5.5 Verificar Contact: FK, tipos, valor/formato E.164, etiqueta, unicidad de valor vigente/primario, auditoría, borrado lógico e identidad; comprobar positivos/negativos y preservación de email en PostgreSQL.
- [x] 5.6 Verificar Idempotency: PK compuesta, FKs Company/Site de tenant/Company correctos, site nulo permitido, hash, estado HTTP, versiones/snapshot y actor; comprobar rechazos cross-tenant/cross-company y JSONB inválido.
- [x] 5.7 Verificar Outbox: identidad única, enums, evento/agregado/versiones, payload, correlación/causación, intentos, publicación/fallo/auditoría e índices; comprobar casos válidos/negativos y completar todo el inventario.
- [x] 5.8 Si las pruebas demuestran un defecto contractual, corregir mediante migración posterior sin alterar V1 y actualizar DBML/inventario; verificar instalación limpia y upgrade con historia previa. Si no hay defecto, registrar evidencia de que no fue necesaria otra migración.

## 6. Puertos y persistencia reactiva

- [x] 6.1 Definir contexto neutral, resultados/fallos y puertos de persistencia mínimos para Company/Site/idempotencia/outbox en Application; verificar que sus firmas no exponen DTOs HTTP, ORM o detalles SQL y documentar contrato de atomicidad/versionado.
- [x] 6.2 Mapear Company y Site con UUIDv7, enums nombrados, Instant/auditoría y versiones iniciales cero; verificar round-trip reactivo y queries tenant-qualified contra PostgreSQL 18 con contexto Vert.x correcto.
- [x] 6.3 Mapear Address y Contact preservando propietario, borrado lógico y formatos; verificar round-trip y consultas tenant/Company a través de Site sin filtrado global inseguro.
- [x] 6.4 Mapear Idempotency con PK compuesta/snapshot versionado y Outbox con JSONB/versiones separadas; verificar round-trip de ambos y metadatos completos sin normalizar valores opacos.
- [x] 6.5 Implementar el límite reactivo de sesión/transacción y traducción de fallos técnicos; verificar con fixture commit y rollback conjunto de agregado/idempotencia/outbox, sin registro comercial ni sesiones compartidas concurrentemente.

## 7. Frontera HTTP y contrato OpenAPI

- [x] 7.1 Integrar `ResponseManager` CDI, catálogo propio de `ApiResponseCode` y traducción de fallos neutrales en API; verificar con recursos exclusivos de test éxito con DTO/envelope y errores conocidos/inesperados sin mappers globales locales.
- [x] 7.2 Implementar validación de contexto limitada a `/v1`, propagación exacta de Process-Id y aislamiento/limpieza reactiva; verificar headers ausentes, duplicados, inválidos y requests concurrentes con procesos diferentes.
- [x] 7.3 Configurar Jackson estricto y normalización previa a validación; verificar por HTTP body inválido/desconocido, longitudes canónicas y detalles de validación estables por campo usando la librería compartida.
- [x] 7.4 Configurar el fallback compartido de errores de negocio; verificar 404/405/415/500, HTTP status igual a envelope.status y ausencia de stack traces, mensajes internos, SQL, entidades o payloads externos.
- [x] 7.5 Crear `docs/contracts/company-registry.openapi.yaml` con componentes comunes, DTOs/envelope, errores, headers y ejemplos; empaquetarlo como `META-INF/openapi.yaml` y verificar parser, ejemplos y coincidencia entre documento fuente y publicado, preparando la misma aserción para el binario nativo sin rutas comerciales ficticias.
- [x] 7.6 Documentar en contrato/inventario de HU `Idempotency-Key`, decimal If-Match, replay/409/412 y paginación cursor/limit 1–200 default 50 con orden/metadatos definidos; verificar nombres/esquemas contra la versión real de la librería y las convenciones de Party.
- [x] 7.7 Añadir validación de firmas de recursos y prueba de health/OpenAPI sin contexto; verificar `Uni<RestResponse<ApiResponse<Dto>>>` en los fixtures de negocio, DTO de API como payload y exclusión de fixtures del artefacto productivo.

## 8. Contratos externos y clientes

- [x] 8.1 Definir puertos Party/país/división y modelos neutrales de referencia; verificar que permiten validar existencia/tipo/estado/país y documentar procedencia de rutas/respuestas/headers en `docs/contracts/`.
- [x] 8.2 Implementar cliente/adaptador Party sobre `GET /v1/parties/{partyId}` con contexto y proyección mínima; verificar con stub ausencia, tipo incompatible, estado, ID incorrecto y exclusión de detalles legales del contrato interno.
- [x] 8.3 Implementar cliente/adaptador de país alpha-2 desde el contrato constatado; verificar ID/código/estado, ausencia y respuestas incompatibles con stub, preservando headers que el proveedor admite sin inventar company-id.
- [x] 8.4 Resolver específicamente el esquema de respuesta y headers del request Postman `feaec323-d194-401e-9891-bf97b90a088f`; verificar con contrato del proveedor. Si está confirmado, implementar/pruebar lookup país→countryId→división; si no, entregar puerto y bloqueo documentado de HU06 sin adaptador habilitado ni validación permisiva.
- [x] 8.5 Configurar URLs y timeouts de conexión/lectura finitos para clientes habilitados y traducir fallos a errores neutrales; verificar timeout, indisponibilidad, payload inválido y arranque sin proveedores vivos mediante pruebas y README de configuración.

## 9. Configuración outbox

- [x] 9.1 Implementar modos `disabled`, `stored-only`, `published` y política de almacenamiento con default disabled; verificar todos los valores, rechazo de desconocidos y selección de persistencia mediante pruebas de configuración/política.
- [x] 9.2 Definir parámetros de intervalo/lote/timeout/lease/backoff/intentos/exchange/routing key según la tabla del diseño; verificar validaciones y overrides por entorno, documentando defaults y que published aún no publica en T00.
- [x] 9.3 Desactivar conexiones/canales/Dev Services RabbitMQ y evitar credenciales obligatorias de broker en disabled; verificar arranque y health sin broker, ausencia de envíos/scheduler y responsabilidades reservadas para HU08.

## 10. LikeC4 central y trazabilidad arquitectónica

- [x] 10.1 Entregar `docs/architecture/company-registry-architecture-handoff.md` con elementos Company/base lógica, relaciones Party/Geographic/PostgreSQL/RabbitMQ opcional y vistas propuestas; verificar IDs/estilos contra el modelo central sin duplicar catálogo ni crear PostgreSQL independiente.
- [x] 10.2 Obtener el cambio coordinado del repositorio central que incorpore y valide los diagramas LikeC4; verificar enlace/revisión y alineación con esta implementación. Esta tarea depende del alcance externo: mantenerla pendiente y reportar el criterio de T00 abierto mientras solo exista el handoff.

## 11. Entrega y verificación nativas

- [x] 11.1 Configurar source sets de integración y aserciones compartidas, perfil explícito de lanzamiento y ejecución nativa con el plugin Quarkus, siguiendo Party; separar fuentes/variantes de verificación de producción y excluir `sourceSets.test.output`. Verificar descubrimiento de suites y que el binario externo no depende de inyección/mocks CDI de `src/test`.
- [x] 11.2 Configurar builder Mandrel JDK 25 UBI 9 y runtime Podman explícito; preparar recursos/metadatos nativos mínimos para la librería compartida, DTOs, clientes, Flyway y JSONB e inicialización runtime del generador UUIDv7 si mantiene aleatoriedad. Verificar configuración y documentar versiones/plataforma sin acoplar Domain a native-image.
- [x] 11.3 Construir binarios nativos productivo y de verificación con salidas diferenciadas usando los comandos de `design.md` y la selección explícita de cada variante; verificar proceso/binario nativo real, builder compatible con Java 25, arquitectura y ausencia de fallback JVM, registrando versión/digest y resultado.
- [x] 11.4 Añadir/ejecutar pruebas del proceso nativo contra PostgreSQL 18: Flyway limpio/validate repetido con roles separados, round-trip de seis mapeos, enums/JSONB/Instant y rollback atómico del fixture; verificar historial intacto y runtime sin DDL, usando la variante de verificación solo para las operaciones auxiliares necesarias.
- [x] 11.5 Ejecutar pruebas HTTP contra la variante nativa de verificación para éxito/envelope, fallos conocidos, headers, validación, JSON estricto, 404/405/415 y 500 saneado; verificar igualdad status HTTP/envelope, serialización de la librería publicada y ausencia de mensajes internos/trazas.
- [x] 11.6 Ejecutar validación de clientes desde el proceso nativo contra stubs alcanzables por URL: Party, país y división solo si su contrato fue confirmado; verificar serialización, contexto, ausencia, timeout, indisponibilidad y respuestas incompatibles sin mocks in-process.
- [x] 11.7 Verificar en el binario productivo health/OpenAPI sin contexto, migraciones/contrato embebidos sin montar fuentes, arranque disabled sin RabbitMQ/proveedores y ausencia de rutas/datos auxiliares. Verificar generación UUIDv7 válida en procesos independientes mediante la variante de verificación cuando haga falta acceso auxiliar, sin semillas capturadas durante el build.
- [x] 11.8 Ajustar lo necesario de `src/main/docker/Dockerfile.native` conservando la referencia UBI 9 Minimal/usuario no privilegiado; verificar `podman build -f src/main/docker/Dockerfile.native -t company-registry-service:native .` y arranque del binario productivo sin JVM con arquitectura/libc compatibles, PostgreSQL accesible y broker ausente.
- [x] 11.9 Documentar en README y `docs/operations/native-verification.md` build/run/test nativos, variantes, perfiles, socket Podman/Testcontainers, variables, puertos y recursos de compilación utilizados; verificar los comandos documentados, mantener JVM como ruta complementaria y enlazar evidencia de cada prueba nativa sin marcar verificaciones omitidas como aceptadas.

## 12. Integración y aceptación final

- [x] 12.1 Ejecutar `./gradlew clean build`, `./gradlew quarkusIntTest` para JVM y build/test nativos con los flags documentados en `design.md`; comprobar en conjunto arquitectura, SQL, HTTP, clientes y modos sin bloqueos de event-loop, más arranque real de la imagen nativa Podman, y registrar resultados diferenciados por artefacto.
- [x] 12.2 Revisar los 16 criterios de aceptación de #2 y el requisito nativo confirmado por el usuario con evidencia y enlaces a documentos/pruebas/cambio central; verificar que no se declara completada una integración bloqueada, diagramas pendientes ni build/pruebas nativas omitidos. Preparar los archivos para revisión; commit solo cuando se solicite.
