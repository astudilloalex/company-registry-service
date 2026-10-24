# Design

## Context

La motivación y el alcance acordado están en `proposal.md`. La base observada ya incluye `OutboxPolicy`, los tres modos, los siete productores/esquemas/ejemplos, `OutboxRecord`, `OutboxEntity` y `ReactiveOutboxRepository`, que actualmente solo encuentra e inserta eventos. V1 creó `company_outbox_events`, sus estados PENDING/PUBLISHED/FAILED, intentos, planificación, auditoría, versión técnica e índice de entrega. V1–V8 son migraciones históricas inmutables.

`build.gradle.kts` incorpora Messaging básico, pero aún no RabbitMQ, scheduler ni registro de métricas. El BOM Quarkus 3.40.1 selecciona SmallRye RabbitMQ 4.37.0. Los tests actuales comprueban ausencia física del conector/scheduler; deberán evolucionar a comprobar ausencia efectiva de conexión, reserva y publicación en los modos que no entregan.

Party Registry es la referencia disponible: reserva con `FOR UPDATE SKIP LOCKED`, reutiliza `next_attempt_at` como lease, incrementa intentos al reservar, publica concurrentemente y protege transiciones por intento. Company conservará ese patrón con protección adicional de versión/resultado, límite al recuperar reservas agotadas y los límites de capas/sesiones de sus ADR existentes.

La especificación nueva es `specs/outbox-publication/spec.md`; los cambios de configuración se describen en `specs/outbox-configuration/spec.md`. Los siete contratos de eventos y los endpoints de negocio conservan sus formas actuales.

## Goals / Non-Goals

**Goals:** mantener hechos separados del estado técnico de entrega; hacer verificables reservas, recuperación y confirmaciones reales; acotar recursos e intentos; poder ejecutar el mismo artefacto JVM/nativo en los tres modos; preservar el aislamiento entre Application y adaptadores.

**Non-Goals:** reconstruir hechos históricos, introducir estados de negocio o un cuarto modo outbox, añadir administración de reenvíos, garantizar orden por agregado, ni modificar infraestructura compartida durante la implementación. Los contenedores y colas aislados creados por pruebas pertenecen exclusivamente al entorno de verificación.

## Decisions

### 1. Orquestación en Application y adaptadores técnicos en Infrastructure

Introducir una operación cohesiva en `application/outbox` que reserve trabajo, invoque publicación y registre resultados. Definir allí dos puertos útiles: almacenamiento de entrega y publicación confirmada. Sus proyecciones incluirán un hecho original, una reserva y resultados/fallos neutrales; no contendrán objetos AMQP, JSON, SQL, CDI, scheduler ni métricas. Mutiny sigue permitido por el perfil reactivo aprobado.

El adaptador publicador en `infrastructure/outbox` valida/serializa el hecho y usa el emisor RabbitMQ. Un adaptador de entrega en `infrastructure/persistence` implementa reservas y transiciones; composición y scheduler en Infrastructure seleccionan el modo y delimitan contextos. El scheduler retorna una pipeline asíncrona y evita solapamiento local, mientras PostgreSQL coordina distintas instancias.

Conservar los productores de negocio y sus transacciones. No ampliar `OutboxRepository` con consultas de entrega por simple conveniencia ni trasladar transporte al dominio. Reutilizar sus proyecciones cuando encajen y separar el envelope de los campos técnicos. Como alternativa, copiar toda la orquestación de Party a Infrastructure dificultaría aplicar los puertos y pruebas de coordinación de Company; crear un caso de uso por cada tipo de evento añadiría siete recorridos equivalentes.

### 2. Reservas cortas utilizando el esquema existente

Mantener PENDING durante reserva y reintento. Usar `next_attempt_at` como límite de elegibilidad: lease mientras se está intentando y backoff después de un fallo conocido. No añadir PROCESSING ni columnas de propietario por defecto: los campos existentes permiten identificar y proteger cada reserva.

Cada operación de reserva utiliza una sesión/transacción reactiva corta y un instante obtenido de PostgreSQL, evitando que el desfase entre relojes de instancias adelante la recuperación. Selecciona PENDING nuevos o vencidos con límite de lote y `FOR UPDATE SKIP LOCKED`. Ordena por `coalesce(next_attempt_at, created_at)`, luego `created_at` e `id`; esto evita que inserciones nuevas releguen continuamente reintentos vencidos. Seleccionar por fecha no promete orden de entrega.

Para una fila con presupuesto disponible, incrementar `publish_attempts` exactamente una vez, fijar `last_attempt_at`, `next_attempt_at = claimedAt + lease`, limpiar el error anterior y actualizar auditoría técnica. Confirmar antes de devolver la reserva. El actor técnico será `outbox-publisher`; nunca sustituirá el actor original contenido en el hecho. Hibernate conserva el incremento independiente de `version`.

La reserva devuelta agrupa eventId, tenantId, intento, instante, vencimiento y versión técnica confirmada en un valor inmutable. Conservar el hecho original por separado. Examinar el plan de consulta sobre un backlog representativo; si se demuestra necesario un índice de expresión, añadirlo en V9 mediante Flyway sin editar V1 ni cambiar datos. Ese ajuste físico no cambia requisitos ni justifica nuevas columnas.

### 3. Resultados condicionales y aislamiento de sesiones

Cada resultado usa una transacción nueva y solo modifica una fila PENDING que coincida con eventId/tenantId, intento, versión técnica y fechas de su reserva. La primera transición confirmada cambia versión y planificación; una respuesta duplicada, un resultado tardío tras programar backoff o una reserva reemplazada dejan de coincidir. La transición retorna si realmente modificó/confirmó una fila para no contabilizar estados inventados.

No mantener sesiones/locks durante envío ni ejecutar pipelines simultáneas sobre la misma sesión de Hibernate Reactive. La composición debe crear contextos Vert.x duplicados independientes para las operaciones de entrega concurrentes y abrir/cerrar las sesiones mediante las fronteras programáticas Panache existentes. La concurrencia de transporte no autoriza reutilizar una sesión contextual del poll. Probar esta frontera con PostgreSQL real, no únicamente con fakes de los puertos.

Un fallo al guardar PUBLISHED después de una confirmación deja una reserva recuperable; no convertir esa incertidumbre en una nueva mutación de negocio ni lanzar una escritura competidora mientras se desconoce la finalización anterior. Un resultado obsoleto produce diagnóstico de descarte, sin cambiar el intento vigente. Como alternativa, proteger solo por número de intento permite que dos resultados del mismo intento sobrescriban backoff; mantener un lock durante AMQP perjudica disponibilidad y recuperación.

### 4. Intentos limitados y estados terminales conservados

El presupuesto cuenta reservas confirmadas, no frames AMQP observados. Al recuperar una fila vencida cuyo contador ya alcanzó el máximo, finalizarla sin reservar otro intento. Si la reserva terminó sin resultado conocido, usar `delivery-outcome-unknown`. Conforme a la tarea 4.4, si una política reducida tras reinicio agota una fila en backoff, conservar su error conocido de la lista permitida; usar `attempts-exhausted` como diagnóstico seguro cuando el código histórico no pertenezca a esa lista. Un error registrado en el intento final conserva su código específico.

Limpiar el error al reservar permite distinguir la reserva todavía incierta del backoff por fallo conocido, que sí conserva un código. Las actualizaciones terminales siguen protegidas por versión e identidad de reserva. Una confirmación que compite con la recuperación final tiene un único ganador transaccional. FAILED significa entrega detenida; no demuestra que RabbitMQ nunca recibió el mensaje.

Los fallos recuperables son timeout/confirmación incierta, confirmación negativa y errores transitorios de conexión/publicación. Programar `min(initialBackoff * 2^(attempt-1), maximumBackoff)` desde el instante de fallo, con cálculo saturado sin desbordamientos. Unsupported type/schema, contrato inválido o serialización determinísticamente imposible son terminales inmediatos. Los errores ordinarios de un evento no cancelan los demás.

No reactivar FAILED al reconectar, reiniciar o ampliar el máximo; tampoco borrar PUBLISHED/FAILED ni pendientes. La alternativa de reintentos ilimitados contradice el máximo acordado; regenerar eventId en una recuperación impediría deduplicar.

### 5. Contrato cerrado validado antes de emitir

El serializador común utiliza un registro cerrado de los siete eventType/esquema 1. Reutiliza los esquemas/ejemplos empaquetados como fuente de contrato y los mappers de envelope existentes para preservar los once campos, incluido causationId null. Valida en runtime campos permitidos, tipos, límites, estados, agregado/revisión, UUIDv7 e igualdades de identidad y fechas. La validación de JSON Schema Draft 2020-12 existente sigue en el harness; el runtime no necesita incorporar su librería de pruebas.

Las reglas de validación runtime se prueban contra los siete esquemas y ejemplos, incluidos updated ARCHIVED y Site updated por cambios de hijos. Evitar siete validadores divergentes: compartir validación del envelope y las formas Company/Site, conservando reglas por evento. Obtener todo el contenido desde el registro almacenado; ninguna lectura de Company/Party/Site/hijos forma parte del envío. La serialización debe ser estable al repetir el mismo hecho; no incluir `Map` de metadatos técnicos ni cambiar el snapshot para filtrar propiedades no aprobadas: un hecho inválido se rechaza.

Una alternativa de serializar cualquier JSONB admitiría datos ajenos a los contratos. Validar únicamente los ejemplos durante tests tampoco protege ante filas históricas o inválidas realmente seleccionadas por el publicador.

### 6. RabbitMQ mediante SmallRye, confirms y routing por mensaje

Añadir las extensiones Quarkus RabbitMQ Messaging y Scheduler bajo el BOM existente. Usar un canal de salida `company-outbox`, con `publish-confirms=true`, `lazy-client=true` y `retry-on-fail-attempts=0`. Configurar límite de mensajes en vuelo, buffer del emisor y cola interna de salida a partir del lote, con rechazo controlado al alcanzar capacidad. Todas las reservas del lote pueden iniciar envío concurrentemente; su capacidad debe corresponder al canal para que no esperen turnos secuenciales de 25 × 5s bajo una reserva de 30s.

El adaptador usa el envío de Message que retorna confirmación del emisor, como la frontera `sendMessage` de Party. Completar el enqueue o una llamada fire-and-forget no acredita publicación. Construir metadata con messageId=eventId, JSON, delivery mode 2, eventType, occurredAt y correlationId originales. La routing key va en metadata por mensaje; el default estático del canal no debe sustituir ese valor.

La reconexión de transporte es independiente del presupuesto de entrega de cada evento. El código SmallRye 4.37.0 cachea la obtención inicial del sender, incluida su terminación fallida: evitar agotar un presupuesto corto de conexión que deje el canal inutilizable tras una ausencia inicial del broker. Mantener reconexión inicial habilitada y un presupuesto de conexión de larga duración (`reconnect-attempts=2147483647`, intervalo acotado), sin convertirlo en reenvíos de aplicación. Verificar ausencia inicial, caída posterior y recuperación con el conector real. El timeout externo de cada publicación sigue siendo 5s por defecto y sus reservas siguen limitadas a diez; una publicación incierta puede permanecer en transporte y producir duplicados posteriormente.

Usar virtual host existente `platform` y exchange configurable `company.events`, topic durable. Declarar únicamente el exchange si la cuenta dispone del permiso configure acotado al nombre; preparar cuenta/permisos fuera del runtime, con write limitado al exchange y sin permisos de lectura/creación de colas de consumidores. No crear virtual hosts. Los tests administran únicamente su broker/vhost/colas aislados.

PUBLISHED acredita confirmación de broker; no requiere mandatory/basic.return ni prueba procesamiento consumidor. Verificar bindings de despliegue antes de published. Una cola durable con consumidor detenido puede conservar mensajes; la ausencia de bindings puede producir descarte confirmado. Ver [confirms](https://www.rabbitmq.com/docs/confirms) y [publicación sin ruta](https://www.rabbitmq.com/docs/publishers#unroutable-message-handling). Exigir al menos una cola requeriría una garantía y adaptación de transporte adicionales que se descartaron en la exploración.

### 7. Configuración compatible con el mismo artefacto en tres modos

`OutboxPolicy` conserva el almacenamiento condicional. La orquestación verifica el modo antes de reservar o invocar el adaptador. El canal puede estar definido en el artefacto, pero debe ser lazy y no conectarse mientras no se envíe. No condicionar los modos a build profiles ni a `@IfBuildProperty`: el modo debe cambiar mediante reinicio con configuración. Las propiedades de broker no tendrán placeholders obligatorios fuera de published; en published una validación eager exige host, puerto y credenciales sin revelar valores.

Conservar variables y defaults de HU08. Rangos alineados con Party: polling hasta 1h, lote 1–500, timeout hasta 5m, lease hasta 1h y mayor al timeout, backoffs hasta 24h con máximo no menor al inicial, e intentos 1–100. Validar positividad, representación y aritmética segura en todos los modos. Puerto 1–65535. Default de virtual host platform; cualquier override debe nombrar un vhost previamente existente.

Resolver la tensión entre la variable requerida COMPANY_OUTBOX_ROUTING_KEY y siete claves diferentes mediante una plantilla literal: default `%s`, sustituido una vez por eventType; `integration.%s` permite adaptar un destino explícito. Validar exactamente un marcador y ningún otro formato, caracteres válidos y longitud AMQP en bytes del resultado para cada uno de los siete tipos. No ejecutar `String.format` sobre texto de configuración sin validarlo. Exchange y plantilla no serán vacíos ni contendrán controles; limitar su representación a los tamaños soportados por AMQP.

El valor fijo `company.events` era un placeholder de T00 sin entrega. Al activar HU08, cualquier override fijo anterior deberá migrar a `%s` o a una plantilla válida. Mantenerlo silenciosamente agruparía los siete tipos en una sola ruta y contradice el contrato por defecto. Documentar el cambio de configuración aunque nunca existió publicación funcional con el valor anterior.

### 8. Disponibilidad, observabilidad y contexto del publicador

Añadir Micrometer/Prometheus bajo el BOM y métricas de resultados por tipo/código de baja cardinalidad: confirmaciones, transiciones PUBLISHED, reintentos, fallos terminales y descartes obsoletos; gauges de pendientes totales y edad del más antiguo. Las consultas de backlog son reactivas, periódicas y fuera del camino HTTP; calcular la edad desde occurredAt e incluir pendientes reservados o en backoff, diferenciando ausencia de datos de cero. Contar transiciones solo después de confirmar su transacción y exponer por separado confirmación AMQP y fallo local posterior.

Desactivar el health del canal para que su estado no se agregue a liveness/readiness de negocio; no desactivar la salud de base de datos u otras comprobaciones existentes. La inspección del conector demuestra que puede reportar conexión cerrada después de haber conectado, por lo que simplemente usar lazy-client no basta. Mostrar el estado degradado con métricas de entrega/backlog y logs, sin provocar reinicios automáticos o retirar la API del balanceador por una caída de RabbitMQ.

Los logs estructurados incluyen eventId, tenantId, correlationId, intento y resultado. Proyectar correlationId al contexto de log sin confundirlo con el usuario HTTP actual y limpiar el MDC al terminar cada pipeline. Evitar etiquetas métricas con UUID, actor, correlación o payload. Persistir códigos de una lista permitida y detalles estáticos acotados; no interpolar `Throwable.getMessage`, payload o configuración sensible. El actor de auditoría del registro outbox puede ser técnico; el envelope conserva al actor original de negocio.

### 9. Verificación por frontera y trazabilidad HU08

| Criterios | Verificación prevista |
| --- | --- |
| AC01–AC03, AC16 | Arranque y ejecución del mismo artefacto en cada modo; ausencia real de conexiones en disabled/stored-only; backlog al reiniciar en published. |
| AC04–AC06 | Reutilizar atomicidad HU01–HU07; creación HTTP real con broker caído y fallo de inserción que revierte negocio/completado/evento. |
| AC07–AC09 | Confirms reales, confirmación negativa/timeout, backoff determinista, intentos máximos conocidos e inciertos y ausencia de estado PUBLISHED anticipado. |
| AC10–AC12 | Confirmación seguida de fallo local, duplicados estables, proceso terminado durante reserva, dos instancias, resultados tardíos y último intento vencido. |
| AC13–AC15 | Siete tipos/esquemas, payload cerrado, igualdades de identidad/fecha, versiones, contexto original y ausencia de datos privados en wire/logs/diagnósticos. |
| AC17 | Métricas y logs de resultados/backlog, sin cardinalidad por identidad, y salud de negocio disponible con broker caído. |

Añadir tests puros de política/orquestación y contratos; tests Hibernate Reactive sobre PostgreSQL 18 con sesiones/contextos independientes; y tests RabbitMQ reales con cola/bindings temporales para cada tipo, override de plantilla y ausencia de bindings. La prueba de dos publicadores debe competir en una base real y demostrar propiedad, no limitarse a inspeccionar el texto SQL. Inyectar fallos en límites concretos con fakes/proxies/harness de pruebas; mantenerlos fuera de producción.

Reutilizar `PostgresResource` de `src/contractTest` y los patrones `ProcessEnvironmentResource`/`PackagedCompanyIT`; añadir un recurso y suite de entrega dedicados, evitando que las regresiones de HU01–HU07 en stored-only comiencen a vaciar su outbox. Usar contenedor RabbitMQ de pruebas con imagen/version fijadas y vhost platform. Los tests de confirmación incierta pueden usar un proxy controlado; la recuperación de proceso necesita detener efectivamente una instancia y arrancar otra sobre la misma base.

Ejecutar `./gradlew test`, `./gradlew build`, `verifyProductionIsolation`, proceso JVM con `quarkusIntTest`, build Mandrel JDK 25 mediante Podman y las suites contra el binario nativo real. Mantener el protocolo de evidencia de `docs/operations/native-verification.md`: comandos, versiones, hashes, escenarios efectivamente ejecutados y límites. Verificar en nativo creación hasta broker, siete contratos/routing, modo sin broker y reinicio con pendientes; la matriz exhaustiva de carreras/fault injection puede ejecutarse en JVM, identificándolo expresamente.

### 10. Control SonarQube en Antigravity por archivo

Requisito explícito del usuario para la implementación: después de crear o modificar cualquier archivo Java o Python, abrir ese archivo en el proyecto ya abierto en Antigravity, esperar el análisis de la extensión SonarQube y revisar sus diagnósticos. Corregir todos los warnings del archivo y volver a analizar hasta llegar a cero; repetir después de cualquier modificación posterior, incluidas correcciones motivadas por tests. Atender también diagnósticos Java del editor y fallos de compilación sin sustituir una evidencia por otra.

Cerrar la pestaña de ese archivo al finalizar su validación, tanto si es productivo como de tests o harness, antes de acumular otras pestañas. Registrar ruta, revisión/hash, resultado del análisis, correcciones y cierre de pestaña. Evitar `@SuppressWarnings`, `NOSONAR`, exclusiones o cambios de perfil que oculten un hallazgo; refactorizar conservando comportamiento. Si el editor o la extensión no están accesibles o el análisis no finaliza, registrar la validación como pendiente y comunicar el bloqueo real: no dar por superado el control basándose solo en Gradle, otro analizador o la ausencia de diagnósticos recibidos.

Esta propuesta solo escribe artefactos Markdown y metadatos OpenSpec; no modifica clases ni reclama ejecución de SonarQube. Una suite global exitosa tampoco acredita el análisis de cada archivo modificado.

## Risks / Trade-offs

- [Duplicados o mensajes fuera de orden] → Identidad/snapshot estables y contrato consumidor explícito; no bloquear revisiones posteriores.
- [Broker confirma un mensaje sin destino] → Preparar/verificar bindings antes de published; usar stored-only mientras se provisionan. Una cola futura no recibe automáticamente publicaciones descartadas.
- [Broker caído hasta agotar diez intentos] → Retener FAILED y alertar; su reenvío requiere una historia administrativa posterior. Diez intentos no son una promesa de recuperación automática indefinida.
- [Mensaje incierto sigue en buffers y se publica después de timeout o FAILED] → Buffers acotados, identidad estable y diagnóstico que no asegura ausencia de entrega; el máximo limita intentos lógicos de outbox.
- [Una sesión contextual se comparte en pipelines paralelas] → Contextos/sesiones separados, límites de transacción cortos y pruebas reales de concurrencia/cancelación.
- [Caduca una reserva bajo saturación o lentitud de base] → Lote/capacidad alineados, timeout inferior al lease y transiciones condicionales; una recuperación adicional puede duplicar sin corromper el estado.
- [Canal deja de recuperar después de un fallo inicial] → Reconexión inicial de larga duración y pruebas con el conector real antes/después de la primera conexión.
- [Divergencia entre validación runtime y JSON Schema] → Casos compartidos para siete tipos, estados y propiedades inválidas; esquemas y ejemplos empaquetados.
- [Override de routing key reservado anterior] → Error temprano y adaptación documentada a plantilla con un marcador; probar destinos efectivos.
- [SonarQube no verificable desde la sesión de implementación] → Mantener el control pendiente y comunicarlo expresamente; no silenciar ni sustituir evidencia.

## Migration Plan

1. Construir y verificar la implementación conservando V1–V8. Adoptar el esquema actual; cualquier índice nuevo demostrado necesario se añade únicamente con una migración Flyway posterior, con pruebas de instalación limpia/upgrade y preservación de hechos.
2. Preparar fuera del runtime la cuenta dedicada, permisos mínimos, exchange topic durable en el vhost platform y bindings/colas consumidoras previstos. Ajustar overrides de routing key fijos a plantilla. No crear ni modificar infraestructura compartida como efecto lateral de tests.
3. Desplegar inicialmente disabled o stored-only según la política de captura elegida. Verificar negocio, salud, ausencia de conexión AMQP y configuración; este despliegue no purga historia ni fabrica hechos de períodos disabled.
4. Tras verificar la topología, reiniciar con published y credenciales externas. Observar backlog, edad, reintentos y FAILED, además de confirmar mensajes en colas de verificación autorizadas del entorno correspondiente.
5. Para detener entrega, reiniciar en stored-only y conservar registros. Las reservas abandonadas mantienen su vencimiento; al reactivar solo se recupera lo elegible dentro de los límites.
6. Para rollback de código, detener la entrega y volver al binario previo en stored-only/disabled, conservando las filas, estados y cambios de esquema aditivos. No revertir migraciones históricas, borrar eventos ni reactivar terminales. Registrar que el binario previo no publica aunque reconocía el modo published.
