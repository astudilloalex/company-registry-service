# Tasks

## 1. Frontera HTTP, políticas y representación pública

- [x] 1.1 Exigir Tenant-Id y Process-Id canónicos en minúsculas en `BusinessContextFilter`, manteniendo User-Id exacto y validación previa al recurso; verificar con `BusinessContextHttpTest` y contratos de errores GET/POST/replay, incluyendo mayúsculas rechazadas, proceso válido conservado ante fallo de tenant y contexto concurrente aislado.
- [x] 1.2 Definir comando/resultado neutrales y políticas puras de registro reutilizando `BusinessText`, reloj e IDs; verificar tests unitarios de LEGAL_ENTITY DRAFT/ACTIVE/INACTIVE admitidos, ARCHIVED/NATURAL_PERSON rechazados, límites posteriores a canonización, opcionales null/blanco, URL opaca válida y auditoría inicial DRAFT/0 coherente.
- [x] 1.3 Crear DTOs API de creación y respuesta Company con auditoría, Party, legalEntityDetails e identifiers según los campos reales del proveedor; verificar serialización tipada de booleanos, versiones, Instant/LocalDate, opcionales omitidos y identifiers vacío, y rechazo de campos generados/desconocidos sin invocar la operación.
- [x] 1.4 Actualizar `docs/contracts/company-registry.openapi.yaml` con las tres rutas, entrada cerrada, respuesta enriquecida, headers en minúsculas canónicas, errores y ejemplos completos; verificar parseo/ejemplos con la suite contractual y documentar en planned-operations qué rutas pasan a estar implementadas al finalizar HU01.

## 2. Fingerprint, metadatos persistidos y replay versionado

- [x] 2.1 Implementar política 1 y codec determinista UTF-8/HmacSHA256 para company.create.v1, conservando input estructurado para verificaciones históricas; verificar vectores de bytes/hash, permutaciones JSON, omitido/null, expansión Unicode, espacios internos, URL sensible a mayúsculas y exclusión de User-Id/Process-Id.
- [x] 2.2 Implementar keyring externo y clave activa, validando IDs/secretos sin defaults ni logs sensibles; verificar nueva firma K2 y verificación K1 desde otra instancia, clave/política histórica ausente saneada, MAC aislado entre requests y documentación de variables, distribución/rotación y conservación sin TTL.
- [x] 2.3 Crear V2 para fingerprint_version/fingerprint_key_id y checks coherentes, sin editar V1 ni atribuir procedencia inventada a históricos; verificar instalación limpia y upgrade PostgreSQL 18 con datos T00, preflight de HU01 sin metadatos, límites inválidos, conservación de seis tablas, snapshots, FKs y checksum V1.
- [x] 2.4 Ampliar `RegistryData.CompletionRecord`, entidad y mapper de completados de forma compatible con namespaces técnicos históricos; verificar round-trip SQL de ambos metadatos y documento JSONB enriquecido con tipos/nulos/valores opacos y actualizar DBML, inventario de restricciones y persistence-contract.
- [x] 2.5 Implementar codec versionado del resultado aceptado completo y sus lectores históricos fuera del núcleo HTTP; verificar que una lectura del completado reproduce el 201 original con Party/identifiers/actores/fechas y que esquema corrupto o desconocido falla sin consultar estado actual.

## 3. Integración contractual de Party e identificadores

- [x] 3.1 Añadir la proyección neutral enriquecida y adaptar el GET real de Party reutilizando cliente/configuración existentes; verificar contra HTTP stub fiel todos los campos generales/legales, tipos/estados, identidad exacta, ausencia tenant-scoped, NATURAL_PERSON y propagación de las tres cabeceras canónicas.
- [x] 3.2 Implementar el cliente de `GET /v1/parties/{partyId}/identifiers` con su envelope paginado real y DTO público seguro; verificar lista vacía y varias páginas, propiedad de cada registro, campos opcionales, cinco estados, maskedValue y ausencia de valores completos o campos inventados.
- [x] 3.3 Componer toda la colección siguiendo cursores del proveedor con limit constante, deadline global y paralelismo HTTP limitado; verificar más de una página, cursor repetido/duplicados, timeout en página posterior, cancelación, respuestas incompatibles y fallo explícito sin truncado o respuesta parcial.
- [x] 3.4 Ampliar `ReferenceStubServer`/fixtures contractuales y documentar contratos/proveniencia en external-references; verificar que 404, 429/5xx, conexión rechazada, timeout y body inválido conservan sus categorías y que no se crean consultas al arrancar ni dependencias RabbitMQ.

## 4. Productor de company.created.v1

- [x] 4.1 Crear esquema JSON y ejemplo bajo `docs/contracts/events/` con los once campos del envelope y siete del payload aprobados; verificar constantes/tipos/límites, companyId igual a aggregateId, fechas iguales, causationId null explícito y rechazo de Party/identifiers/description/logoUrl añadidos al evento.
- [x] 4.2 Implementar candidato y mapping inmutables de creación a outbox usando User-Id/Tenant-Id/Process-Id originales, UUIDv7 propio y agregado versión 0; verificar tests de identidad, auditoría, versión de esquema 1 y separación de la versión técnica de entrega.
- [x] 4.3 Integrar el candidato con `OutboxPolicy` dentro de `AtomicOperation`; verificar en PostgreSQL disabled sin evento y stored-only/published con exactamente un PENDING de intentos/versión técnica cero, rollback ante fallo de evento y ningún acceso al broker.
- [x] 4.4 Documentar el contrato entregado a HU08 y los recursos requeridos para validación nativa; verificar ejemplos contra el esquema y que el artefacto contiene los recursos necesarios, dejando explícitos exchange company.events, routing key company.created.v1 y alineación futura del default sin activar publicación.

## 5. Registro transaccional, resolución concurrente y endpoint POST

- [x] 5.1 Orquestar lookup inicial, política/clave histórica o validación/enriquecimiento previo, candidato y transacción corta con segundo lookup; verificar pruebas de aplicación con providers invocados solo en nuevas creaciones, 201 únicamente tras commit y rollback de Company/completado/evento por cada fallo obligatorio.
- [x] 5.2 Ampliar la clasificación neutral de unicidad para código, Party y PK de completado y resolver la perdedora tras rollback/cierre en una sesión nueva; verificar violaciones SQL reales antes del completado y durante su INSERT, replay equivalente, 409 distinto y ausencia de conversión de duplicados inesperados en conflictos de negocio.
- [x] 5.3 Aplicar el presupuesto finito compartido de locks/consultas/recuperación, cancelando trabajo de base y sin envolver recuperación en la sesión fallida; verificar bloqueo observable en PostgreSQL, timeout 503, cierre/rollback, resultado de commit incierto recuperable y siguiente intento exitoso con la misma clave.
- [x] 5.4 Implementar POST con DTO, mapeo neutral y ResponseManager conforme al catálogo existente; verificar contratos HTTP 201/400/404/409/502/503 y 500 saneado, headers, cuerpo cerrado y ninguna aceptación parcial tras fallos de Party o identificadores.
- [x] 5.5 Probar carreras con sesiones/conexiones independientes: misma clave/contenido equivalente, misma clave/contenido distinto, claves distintas/mismo Party y claves distintas/mismo código; verificar resultados de ambas solicitudes y conteos exactos de Company, completado y outbox usando barreras/señales de lock, sin sleeps como evidencia de corrección.
- [x] 5.6 Probar rollback del competidor que retenía unicidad y rotación K1/K2 entre competidores; verificar que la solicitud elegible restante puede confirmar y que la recuperación compara con la clave/política del completado ganador.
- [x] 5.7 Probar respuesta perdida y replay tras modificar Company/Party/identificadores mediante fixtures, archivar Party, cambiar modo outbox, caer el proveedor y reiniciar el proceso; verificar snapshot completo original, contexto HTTP actual, cero nuevas escrituras/eventos y rechazo de contenido efectivo distinto.
- [x] 5.8 Documentar registro/replay, matriz de conflictos, deadlines, propagación y semántica de commit incierto; verificar ejemplos y comandos contra los contratos HTTP y casos de integración entregados en este grupo.

## 6. Consultas, filtros, cursores y enriquecimiento de lectura

- [x] 6.1 Ampliar CompanyRepository con página/filtros neutrales y queries tenant-qualified para ID, code, partyId y status; verificar PostgreSQL con filtros AND, código canónico, estados incluidos ARCHIVED, fechas empatadas, tenants distintos y lista vacía sin consultar Party.
- [x] 6.2 Implementar cursor autenticado/versionado por tenant, filtros, orden y frontera createdAt/id con separación criptográfica de propósito; verificar límites 1/200/default 50, continuación y fin, claves históricas, firma alterada, versión desconocida, tenant/filtros incompatibles, cambio permitido de limit y metadatos top-level sin totales obligatorios.
- [x] 6.3 Implementar GET individual y colección con enriquecimiento después de cerrar la sesión local y paralelismo HTTP acotado; verificar 200 actual con Party/identifiers, 404 idéntico ausente/ajeno, errores 502/503 sin respuesta parcial y ausencia de cambios de auditoría/versión/eventos.
- [x] 6.4 Actualizar guards de recursos/arquitectura para cubrir rutas productivas y `ApiResponse<List<CompanyResponse>>` tipado sin aceptar entidades o wrappers ad hoc; verificar ResourceSignatureTest/ArchitectureTest y contratos REST negativos de UUID, query desconocida/repetida, cursor inválido y GET sin headers de mutación.
- [x] 6.5 Documentar lectura actual frente a replay, navegación hacia delante y semántica completa de identifiers; verificar ejemplos OpenAPI de colección vacía y enriquecida, orden temporal/UUID y EXPLAIN con filtro status, añadiendo un índice incremental solo si la evidencia de consulta lo requiere.

## 7. Verificación integrada JVM, empaquetada y nativa

- [x] 7.1 Adaptar aserciones T00 que esperan paths vacíos/ausencia de rutas comerciales y las fixtures con Process-Id en mayúsculas; verificar que HU01 real está publicado, las rutas auxiliares siguen ausentes de producción y health/OpenAPI funcionan sin contexto de negocio.
- [x] 7.2 Ejecutar `./gradlew clean build` y `./gradlew quarkusIntTest` con PostgreSQL 18/Podman y proveedores controlados; verificar regresión completa, instalación/upgrade Flyway, contratos compartidos y reinicio/replay contra la base persistida, conservando reportes y corrigiendo fallos reales.
- [x] 7.3 Construir y ejecutar el binario nativo productivo con Mandrel JDK 25/Podman siguiendo native-verification y, cuando corresponda, la variante auxiliar aislada; verificar sobre el proceso real creación/GET/lista, nested Party/identifiers, LocalDate/Instant, JSONB, MAC/rotación, rollback/timeout y replay tras reinicio con proveedores caídos.
- [x] 7.4 Construir/arrancar la imagen nativa no root con configuración propia de base y keyring de prueba; verificar rutas reales y contrato empaquetado, ausencia de fixtures y arranque/registro sin RabbitMQ en los modos aprobados, registrando evidencia separada de los tests JVM.
- [x] 7.5 Actualizar README, documentos HTTP/persistencia y registro de evidencia HU01; verificar la matriz AC01-AC13 siguiente contra reportes reales, ejecutar `git diff --check` y `openspec validate add-company-registration-and-retrieval --strict`, sin marcar implementación completa por existir estos artefactos.

## Acceptance traceability

| Criterio HU01 | Cobertura principal planificada |
| --- | --- |
| AC01 Registro DRAFT/0 | 1.2, 3.1, 5.1, 5.4 |
| AC02 Identidad y tenant de Party | 3.1, 3.4, 5.4 |
| AC03 Rechazo NATURAL_PERSON | 1.2, 3.1, 5.4 |
| AC04 Unicidad Party | 5.2, 5.5 |
| AC05 Unicidad código | 1.2, 5.2, 5.5 |
| AC06 Texto canónico | 1.2, 2.1, 5.4 |
| AC07 Replay idempotente | 2.5, 5.5, 5.6, 5.7 |
| AC08 Clave con contenido diferente | 2.1, 5.2, 5.4, 5.5 |
| AC09 Dependencia indisponible | 3.3, 3.4, 5.1, 5.4 |
| AC10 Aislamiento tenant | 1.1, 5.5, 6.1, 6.2, 6.3 |
| AC11 Consulta con estado/versión/auditoría | 1.3, 6.3, 7.3 |
| AC12 Listado determinista y acotado | 6.1, 6.2, 6.3, 6.5 |
| AC13 Outbox condicional atómico | 4.1, 4.2, 4.3, 5.1, 5.5, 5.7 |

Además se verifican las decisiones añadidas en la conversación: Party ARCHIVED rechazado al crear y visible al consultar, Party/identifiers completos con maskedValue, replay original con proveedor caído, metadatos/rotación de fingerprint y cabeceras UUID en minúsculas propagadas sin modificaciones.

## Workflow follow-up

- HU08 implementará publisher, claims/leases, confirmaciones, reintentos y bindings/routing por tipo usando el contrato de evento de HU01.
- La implementación comienza mediante openspec-apply-change tras revisar estos artefactos; este checklist no autoriza publicación, cambios en otros repositorios, cierre de issues o archivado automático.
