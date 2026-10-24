# Tasks

Cada tarea que cree o modifique Java/Python incluye el control por archivo de design.md, decisión 10: abrir en Antigravity, esperar el análisis de la extensión SonarQube, corregir todos los warnings, volver a validar y cerrar la pestaña. Repetir tras cualquier edición posterior y registrar evidencia de la revisión final. No marcar ese trabajo completo con análisis pendiente ni sustituirlo por Gradle; evitar suppressions, NOSONAR, exclusiones o cambios de perfil para ocultar hallazgos.

## 1. Preparación de calidad y dependencias

- [x] 1.1 Comprobar acceso al proyecto y a la extensión SonarQube de Antigravity antes de modificar clases; preparar el registro por archivo con ruta, revisión/hash, resultado y cierre de pestaña. Verificar el ciclo abrir/analizar/leer diagnósticos/cerrar con un archivo existente sin editarlo; registrar cualquier bloqueo real.
- [x] 1.2 Incorporar RabbitMQ Messaging, Scheduler y Micrometer/Prometheus con el BOM vigente, sin cambiar Java/Quarkus. Verificar resolución de dependencias y compilación; documentar las versiones efectivas del conector y transporte en la evidencia HU08.

## 2. Modos, configuración y destinos

- [x] 2.1 Ampliar la validación eager de parámetros existentes con rangos, aritmética representable y lease mayor al timeout. Verificar defaults y fronteras válidas/inválidas mediante OutboxConfigurationTest, incluidos máximo de intentos y backoff sin desbordamiento.
- [x] 2.2 Implementar la plantilla COMPANY_OUTBOX_ROUTING_KEY con default %s, un único marcador literal, destinos sin controles y tamaño AMQP válido en bytes para los siete tipos. Verificar rutas por defecto, override integration.%s y rechazo de company.events fijo, marcadores adicionales, formatos y longitudes inválidas.
- [x] 2.3 Añadir configuración RabbitMQ opcional fuera de published y requerida en published, con platform por defecto y puerto válido; conservar todas las variables HU08. Verificar arranque sin credenciales en disabled/stored-only y rechazo saneado de configuración incompleta en published.
- [x] 2.4 Actualizar README y contrato de configuración con defaults/rangos, propiedades/variables, plantilla y adaptación de overrides reservados anteriores. Verificar que los ejemplos coinciden con las pruebas de configuración y no contienen credenciales reales.

## 3. Validación y serialización del hecho original

- [x] 3.1 Incorporar validación común del envelope almacenado y construcción de sus once campos, sin metadatos técnicos y con causationId null explícito. Verificar estabilidad de identidad/contenido ante reintentos y cambios posteriores del agregado, sin consultar repositorios de negocio/proveedores.
- [x] 3.2 Admitir exactamente los siete tipos/esquema 1 con formas Company/Site compartidas y reglas por evento. Verificar mensajes producidos y ejemplos contra los siete JSON Schemas empaquetados, incluidos Company updated ARCHIVED y Site updated por cambios de hijos.
- [x] 3.3 Rechazar propiedades adicionales/sensibles, tipos o versiones no soportados y violaciones de UUID, revisión, estado o igualdades ID/fecha antes del envío. Verificar fallo terminal controlado, cero emisiones y ausencia de payload/credenciales en diagnóstico, sin cancelar otro evento válido.
- [x] 3.4 Actualizar el handoff de docs/contracts/events/README.md para distinguir productores existentes y entrega HU08, identidad inmutable, desorden por agregado y routing efectivo. Verificar que el catálogo sigue teniendo siete tipos y no exige modificar payloads ni esquemas existentes.

## 4. Reservas y transiciones PostgreSQL

- [x] 4.1 Definir los puertos/proyecciones neutrales de entrega y publicación en application/outbox, incluida una reserva cohesiva que agrupe intento, fechas y versión técnica. Verificar con las reglas arquitectónicas que no aparecen dependencias AMQP/SQL/JSON/Quarkus en Application ni frameworks en Domain.
- [x] 4.2 Implementar reserva de lote PENDING vencido con tiempo PostgreSQL, prioridad temporal coalesce(next_attempt_at,created_at), locks SKIP LOCKED e incremento único de intento/auditoría/versión. Verificar sobre PostgreSQL 18 exclusión de reservas activas y terminales, límite, commit previo al envío y ausencia de inanición ante eventos nuevos.
- [x] 4.3 Implementar PUBLISHED, reintento y FAILED en transacciones nuevas condicionadas por identidad completa de reserva/versión; devolver si la transición confirmó realmente. Verificar con sesiones independientes resultados tardíos tras una reserva nueva y dos resultados del mismo intento sin sobrescritura.
- [x] 4.4 Finalizar pendientes vencidos cuyo presupuesto ya se agotó, conservando error conocido o delivery-outcome-unknown según el caso. Verificar caída durante el último intento, reducción del límite tras reinicio y carrera con confirmación; el contador nunca reserva máximo más uno.
- [x] 4.5 Verificar aislamiento real de dos reservadores/transiciones, liberación de locks y recuperación tras rollback/cancelación; incluir casos de versión y auditoría técnica sin cambios del envelope. La evidencia debe venir de PostgreSQL real, no solo de aserciones sobre el texto SQL.
- [x] 4.6 Evaluar la consulta con backlog representativo y el índice de entrega actual; documentar plan y conclusión. Si un índice de expresión resulta necesario, añadir únicamente V9 y probar instalación limpia/upgrade, privilegios, checksums V1–V8 e historia conservada; si no, registrar que el esquema actual es suficiente.
- [x] 4.7 Documentar reservas, protección de resultados, distinción intento/versión de agregado y recuperación en el contrato de persistencia. Verificar que refleja las transacciones probadas y no introduce locks durante AMQP ni operaciones JDBC en runtime.

## 5. Orquestación reactiva y scheduler

- [x] 5.1 Implementar coordinación de entrega sobre los puertos, con backoff saturado, clasificación neutral de fallos y presupuesto contado al reservar. Verificar pruebas deterministas de éxito, timeout, confirmación negativa, fallo terminal inmediato y agotamiento, preservando eventId y snapshot.
- [x] 5.2 Componer entrega concurrente acotada con contextos Vert.x/sesiones independientes y capacidad alineada al lote. Verificar pipelines reales en paralelo sin uso concurrente de una sesión y que un fallo ordinario no cancela los restantes eventos ni altera su auditoría.
- [x] 5.3 Incorporar scheduler asíncrono sin solapamiento local y selección runtime de modo antes de reservar/enviar. Verificar que disabled/stored-only no invocan entrega, que published procesa pendientes y que dos instancias siguen coordinadas por reservas PostgreSQL.
- [x] 5.4 Manejar fallo/resultado incierto al guardar una transición y cancelación sin repetir mutaciones ni iniciar recuperación sobre una transacción de finalización desconocida. Verificar reserva recuperable tras confirmación seguida de fallo local y descarte de resultados obsoletos.
- [x] 5.5 Documentar intentos lógicos, máximo también tras caídas, posible publicación incierta posterior a timeout/FAILED y ausencia de reactivación administrativa. Verificar que los ejemplos de retry/reinicio coinciden con las pruebas de orquestación.

## 6. Transporte RabbitMQ y pruebas de contrato de entrega

- [x] 6.1 Añadir recurso RabbitMQ de pruebas aislado con imagen/version fijadas, vhost platform y colas/bindings temporales, reutilizando la base de src/contractTest. Verificar limpieza de los recursos y separación respecto de infraestructura/colas productivas y regresiones stored-only.
- [x] 6.2 Implementar el adaptador de publicación con Message/emisor confirmado, metadata persistente, tipo/timestamp/contexto original y routing key por mensaje. Verificar con broker real la recepción de los siete tipos y transición a PUBLISHED únicamente después del confirm; comprobar también el override de plantilla.
- [x] 6.3 Configurar canal lazy, confirms, cero reintentos de envío de aplicación en el conector, buffers/inflight acotados y reconexión inicial de larga duración. Verificar broker ausente al inicio, caída después de conectar, recuperación sin reiniciar y capacidad acotada bajo timeouts.
- [x] 6.4 Verificar confirmación negativa y timeout con fallos controlados, y confirmación después del enqueue sin marcar éxito anticipado. Acreditar resultados y códigos con pruebas del adaptador/conector; mantener proxies y fault injection exclusivamente en pruebas.
- [x] 6.5 Verificar los dos casos operacionales de topología: cola durable con consumidor detenido y publicación confirmada sin binding compatible. Comprobar que el segundo caso puede quedar PUBLISHED y no se recupera automáticamente por crear una cola después.
- [x] 6.6 Documentar cuenta/permisos mínimos, exchange topic durable, vhost existente, bindings como prerrequisito y credenciales externas. Verificar la documentación con el broker aislado; no ejecutar aprovisionamiento de infraestructura compartida como parte de esta tarea.

## 7. Métricas, logs y disponibilidad

- [x] 7.1 Implementar métricas de confirmaciones y transiciones, reintentos, FAILED y descartes, más consultas reactivas de pendientes/edad. Verificar valores después de commit, fallo local posterior a confirm y resultados obsoletos; distinguir indisponibilidad de datos de backlog cero.
- [x] 7.2 Incorporar logs/diagnósticos saneados y contexto por evento con limpieza al terminar. Verificar errores con causas que contengan secretos/payload, ausencia de filtraciones y etiquetas métricas de baja cardinalidad sin identidades.
- [x] 7.3 Separar health de entrega de readiness/liveness de negocio manteniendo las comprobaciones de base existentes. Verificar salud y creación HTTP con broker caído tanto antes como después de una conexión exitosa; el deterioro debe observarse en métricas/logs.
- [x] 7.4 Documentar métricas, interpretación de published/failed, alertas de backlog/fallos y modo de detener entrega sin borrar historia. Verificar las consultas/ejemplos contra las métricas y resultados reales de las pruebas.

## 8. Integración de negocio y recuperación entre procesos

- [x] 8.1 Crear el recorrido HTTP de Company hasta RabbitMQ usando productores y transacción reales. Verificar un evento creado por aceptación, identidad/contexto y esquema correctos, replay/no-op sin duplicar hechos y rollback sin fuga de eventos cuando falla una escritura requerida.
- [x] 8.2 Crear suite de proceso dedicada inspirada en ProcessEnvironmentResource/PackagedCompanyIT con configuración de entrega independiente. Verificar el mismo artefacto en disabled/stored-only sin credenciales ni intentos AMQP, y stored-only → published conservando base, aceptaciones e identidad de pendientes.
- [x] 8.3 Probar dos publicadores contra la misma base y broker con reservas reales, incluidas versiones distintas del mismo agregado. Verificar exclusión de propiedad activa, progreso independiente y que un evento en backoff/FAILED no bloquea una revisión posterior.
- [x] 8.4 Terminar efectivamente un proceso después de reservar y recuperar desde otro proceso contra la misma base; provocar también el límite confirmación recibida/resultado local ausente con harness de pruebas. Verificar lease vencido, duplicados con eventId/contenido estables y resultado tardío descartado.
- [x] 8.5 Reiniciar con mezcla de PENDING, reservas vencidas, PUBLISHED y FAILED, incluyendo último intento incierto y cambio de máximo. Verificar recuperación solo de elegibles, ausencia de backfill de disabled, terminales conservados y ningún intento adicional fuera del límite.
- [x] 8.6 Registrar la matriz JVM de integración y fault injection con comandos, escenarios y resultados. Verificar trazabilidad a AC01–AC17 y distinguir evidencia con broker/DB reales, fakes de orquestación y pruebas de procesos terminados.

## 9. Aceptación del artefacto y cierre de calidad

- [x] 9.1 Ejecutar ./gradlew test y ./gradlew build tras integrar los grupos anteriores; resolver regresiones y verificar verifyProductionIsolation. Registrar reportes y hashes de las fuentes/artefactos validados, sin presentar ejecuciones anteriores como evidencia de la revisión final.
- [x] 9.2 Ejecutar la suite de proceso JVM con quarkusIntTest sobre el artefacto final, incluidos creación hasta broker, siete tipos/routing, modos y reinicios. Verificar que los fixtures de entrega no se empaquetan en producción ni vacían las outbox de las regresiones HU01–HU07.
- [x] 9.3 Construir el ejecutable productivo con Mandrel JDK 25/Podman y ejecutar el proceso nativo real fuera del árbol de fuentes. Verificar creación hasta RabbitMQ, siete contratos/routing empaquetados, modos sin broker y recuperación de pendientes por reinicio; registrar versión, plataforma, hashes y límites de la matriz nativa.
- [x] 9.4 Revisar el manifiesto final de todos los archivos Java/Python creados o modificados, incluidos tests/harness y correcciones posteriores. Verificar por archivo evidencia real de SonarQube en Antigravity con cero warnings en la revisión final y pestaña cerrada, sin suppressions ni controles pendientes; compilar no sustituye esta evidencia.
- [x] 9.5 Consolidar revisión de aceptación HU08, resultados JVM/nativos y procedimiento de despliegue/rollback conservando estados e historia. Verificar consistencia con las dos especificaciones, exclusiones y control SonarQube, y ejecutar openspec validate add-transactional-outbox-publication --strict antes de solicitar revisión.

## Workflow follow-up

- Revisar los artefactos y la evidencia de implementación antes de archivar el cambio con el workflow correspondiente.
- Mantener el reprocesamiento administrativo de FAILED como historia posterior; no añadirlo ni reactivar registros durante HU08.
