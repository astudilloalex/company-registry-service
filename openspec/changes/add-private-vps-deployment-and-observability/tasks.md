# Tasks

El alcance inicial instala outbox en `disabled`, conserva las tres redes y entrega métricas internas y logs en journald. Backups y restauración (AC16), recolección histórica, alertas y activación productiva de `published` quedan fuera de esta entrega. Ninguna tarea está ejecutada por la creación de esta propuesta.

## 1. Verificación reproducible del proyecto

- [ ] 1.1 Comprobar resolución de dependencias desde un runner limpio con Java 25 y Gradle 9.7.0, sin depender de mavenLocal; verificar acceso de lectura a GitHub Packages con GITHUB_TOKEN y documentar permisos y comandos reproducibles.
- [ ] 1.2 Configurar los gates de formato y análisis estático compatibles con Java 25, con alcance y baseline explícitos; verificar que una infracción controlada hace fallar cada gate y que el código aceptado los supera sin una reforma cosmética general.
- [ ] 1.3 Consolidar el comando obligatorio de arquitectura, unitarias, integración PostgreSQL/RabbitMQ y contratos OpenAPI/esquema/eventos; verificar reportes de todas las suites y rechazo de una suite requerida omitida usando infraestructura desechable.
- [ ] 1.4 Integrar verifyProductionIsolation en la verificación JVM antes del empaquetado nativo; verificar que detecta rutas auxiliares en una variante inválida y documentar la separación entre artefactos de verificación y distribución.

## 2. Aprovisionamiento persistente y aislamiento PostgreSQL

- [ ] 2.1 Versionar el preflight de usuario rootless, Podman/systemd, cgroup v2, linger, arquitectura/CPU y nombres efectivos de redes; verificarlo con salidas controladas y documentar la identificación del contexto operativo que posee la infraestructura.
- [ ] 2.2 Preparar los recursos declarativos de company-db.network y la adhesión persistente de PostgreSQL, conservando sus otras redes; verificar en infraestructura desechable la recreación de ambos contenedores y la resolución DNS por las tres redes previstas.
- [ ] 2.3 Preparar el aprovisionamiento idempotente de company_registry_service y sus identidades runtime/migración en PostgreSQL 18; verificar aplicación y validación de V1–V9 con Flyway sin modificar migraciones históricas.
- [ ] 2.4 Revisar grants, PUBLIC, membresías y autenticación para limitar runtime a operaciones autorizadas; verificar DML permitido, rechazo de DDL/escritura del historial Flyway/acceso a datos ajenos y preservación de accesos de otros servicios en pruebas aisladas.
- [ ] 2.5 Documentar los pasos iniciales de base, redes y arranque persistente, separados de cada despliegue; verificar que los comandos y comprobaciones identifican prerequisitos ausentes sin recrear infraestructura compartida durante una entrega.

## 3. Renderer de production y compatibilidad de claves

- [ ] 3.1 Crear el renderer de configuración externa con inventario de secretos/variables de GitHub production; verificar casos válidos y rechazo de ausencias, duplicados, placeholders, perfil distinto de prod y puerto distinto de 8083 sin mostrar valores.
- [ ] 3.2 Implementar validación condicional de RabbitMQ y límites/timeouts; verificar que disabled y stored-only renderizan sin credenciales AMQP y que published exige su configuración, manteniendo las tres redes en todos los casos.
- [ ] 3.3 Convertir COMPANY_IDEMPOTENCY_KEYRING_JSON a las propiedades existentes mediante un secreto Podman; verificar IDs, duplicados JSON, Base64, mínimo de 32 bytes, clave activa y lectura como UID 1001 desde /run/secrets/company-keys.properties.
- [ ] 3.4 Implementar comprobación de compatibilidad con el keyring protegido instalado y los IDs requeridos por aceptaciones persistidas; verificar rechazo de eliminación o sustitución de claves y replay válido con K1/K2 después de una rotación y recuperación compatible.
- [ ] 3.5 Preparar configuración por versión, permisos 0700/0600 en host y montaje del keyring 0400; verificar que limpieza y rollback conservan claves necesarias, eliminan temporales no referenciados y no incluyen secretos en imagen, reportes o salidas.
- [ ] 3.6 Documentar altas y rotación del keyring, variables iniciales disabled y gestión de credenciales de base/SSH/GHCR; verificar el procedimiento con valores de prueba y aclarar que las versiones de configuración conservadas no constituyen backups de PostgreSQL.

## 4. Salud, logs y métricas internas

- [ ] 4.1 Verificar y completar liveness/readiness sobre la persistencia operacional; probar arranque sin base y pérdida/recuperación posterior, distinguiendo proceso vivo de disponibilidad y preservando el aislamiento global de Party, Geographic Reference y RabbitMQ.
- [ ] 4.2 Incorporar logging JSON y tenantId validado junto a processId en el contexto técnico; verificar concurrencia, composición asíncrona, error, cancelación y limpieza de claves propias, con registros de finalización que indiquen ruta normalizada, resultado y duración.
- [ ] 4.3 Completar métricas HTTP y de clientes después de comprobar los binders existentes; verificar resultados, latencia y timeouts con etiquetas acotadas, manteniendo los errores contractuales y las lecturas locales sin llamadas adicionales a proveedores.
- [ ] 4.4 Exponer utilización, capacidad y espera de adquisición del pool PostgreSQL reactivo; verificar variación bajo concurrencia y que las series observadas corresponden al pool operacional, diferenciándolas de JDBC/Flyway.
- [ ] 4.5 Separar el muestreo periódico de DeliveryStore.backlog del flujo de entrega, con sesiones independientes, consulta acotada y sin solapamientos; verificar backlog en los tres modos, cero AMQP en disabled/stored-only y ausencia de reservas o cambios de filas/intentos.
- [ ] 4.6 Actualizar pruebas de telemetría y procesos para el muestreo: verificar NaN/0 antes del primer resultado o ante fallo/timeout, 0/0/1 para vacío válido, recuperación posterior y medición válida pese a fallo del broker, conservando los resultados confirmed/published/retry/failed.
- [ ] 4.7 Probar sanitización con marcadores sensibles en errores de HTTP, proveedores, persistencia y conector; verificar ausencia de secretos, contactos, cuerpos confidenciales, rutas con IDs y etiquetas con identidades en logs/métricas.
- [ ] 4.8 Documentar consulta privada de salud, métricas y journalctl --user, catálogo de series y límites de retención/reinicio de contadores; verificar los ejemplos en un entorno de prueba sin añadir colectores, dashboards ni alertas.

## 5. Ejecutable nativo e imagen bajo restricciones

- [ ] 5.1 Configurar la compilación de CI con Docker, builder Mandrel JDK 25/UBI 9, compatibilidad de CPU e inicialización runtime de UuidV7Generator; verificar un build limpio y registrar versiones/digests sin heredar el runtime Podman local.
- [ ] 5.2 Ejecutar las suites de proceso nativo del artefacto productivo con la configuración Gradle existente; verificar persistencia, contexto, clientes, contratos, idempotencia y modos outbox, identificando fixtures condicionales sin omitir casos obligatorios ni publicar la variante de verificación.
- [ ] 5.3 Crear el Quadlet con alias company-registry-service, puerto interno 8083, tres redes incondicionales y ausencia de PublishPort, alineando EXPOSE y ejemplos de la imagen distribuida; verificar generación de unidad, usuario no root, capacidades, NoNewPrivileges, raíz de lectura, temporales y journald en pruebas automatizadas.
- [ ] 5.4 Integrar comprobación de arranque por readiness y supervisión continua por liveness con recuperación systemd; verificar que las sondas existen en la imagen final, consultan el puerto 8083, funcionan bajo sus restricciones y coordinan parada ordenada y presupuestos de tiempo.
- [ ] 5.5 Probar la imagen final con configuración productiva desechable y los tres modos outbox; verificar salud, OpenAPI, ausencia de rutas auxiliares y conexiones AMQP indebidas, y registrar evidencia separada de las pruebas JVM y de proceso nativo.
- [ ] 5.6 Medir memoria, CPU, conexiones y procesos en reposo, concurrencia HTTP y trabajo outbox aislado; verificar que los límites parametrizados de Quadlet y sus márgenes se justifican con resultados identificados y documentar arquitectura/CPU y requisitos operativos.

## 6. Activación, smoke tests y recuperación

- [ ] 6.1 Adaptar el script de Party con exclusividad local, preflight de redes/configuración/compatibilidad, validación de unidad y descarga por digest antes del reemplazo; verificar que fallos previos conservan la versión activa y que ejecuciones simultáneas no interfieren.
- [ ] 6.2 Implementar selección atómica de unidad con recursos por versión, daemon-reload, reinicio y espera acotada de readiness; verificar que el servicio efectivo apunta al digest y configuración previstos y que una interrupción permite identificar el estado instalado.
- [ ] 6.3 Implementar smoke tests internos sobre company-registry-service:8083 de salud, OpenAPI, métricas y Company/Site con tenant/Party dedicados y claves idempotentes estables; verificar primera creación y repeticiones mediante replay/lectura, sin crear datos adicionales por release ni imprimir respuestas confidenciales.
- [ ] 6.4 Mantener la entrega pendiente de aceptación hasta finalizar todos los smoke tests; inyectar fallos posteriores a readiness y verificar recuperación del digest/configuración compatibles, conservación de esquema/datos/outbox y replay con K1/K2.
- [ ] 6.5 Cubrir primera instalación fallida, recuperación fallida e incompatibilidad de esquema/keyring; verificar resultado de error veraz, recursos protegidos conservados y ausencia de reversión de migraciones, borrado de historia o ejecución incompatible.
- [ ] 6.6 Documentar entrega, diagnóstico, recuperación compatible, limpieza y activación futura de published; verificar los comandos en laboratorio e incluir comprobación previa de colas/bindings, ausencia de backfill de disabled y de reactivación automática de terminales.

## 7. Pipeline GitHub Actions y publicación GHCR

- [ ] 7.1 Crear el workflow con verify para PR y dependencias verify → build_publish → deploy para push a main y ejecución manual desde main; verificar filtros, permisos mínimos y exclusión de otras referencias del acceso a production.
- [ ] 7.2 Conectar todos los gates y verificaciones JVM/nativas/de imagen antes de publicar; verificar con un fallo controlado que ninguna imagen se promueve ni entrega y que los reportes distinguen las fronteras realmente comprobadas.
- [ ] 7.3 Publicar en GHCR la misma imagen final verificada, asociando commit y digest, y pasar ese digest al despliegue; verificar identidad efectiva y recuperación por digest sin reconstrucciones ni selección mediante latest.
- [ ] 7.4 Limitar secretos/variables de production al job de despliegue y transferir configuración privada por SSH con servidor verificado; verificar limpieza en éxito/error y ausencia de secretos, keyring y claves SSH en logs, cachés o artefactos.
- [ ] 7.5 Configurar exclusividad productiva sin cancelar una activación en curso; verificar dos disparadores concurrentes y documentar el comportamiento de ejecuciones pendientes sin prometer orden FIFO.
- [ ] 7.6 Documentar preparación de GitHub production, acceso al paquete GHCR y ejecución automática/manual; verificar el workflow con configuración de prueba y reportes saneados antes de habilitar su primera entrega productiva.

## 8. Integración y aceptación en el VPS

- [ ] 8.1 Recopilar los datos del preflight real, preparar recursos medidos y ámbito smoke, y configurar GitHub production con COMPANY_OUTBOX_MODE=disabled; verificar prerequisitos y permisos sin incluir valores secretos en la evidencia ni requerir credenciales RabbitMQ.
- [ ] 8.2 Aplicar o verificar el aprovisionamiento persistente y realizar la primera entrega desde main; comprobar digest, perfil/puerto, UID, tres redes, DNS, ausencia de puertos publicados/ruta Nginx y conservación de redes y accesos de los otros servicios.
- [ ] 8.3 Comprobar en el VPS salud, smoke idempotente, métricas y logs correlacionados y saneados; verificar que disabled no genera eventos nuevos ni conexiones AMQP y registrar la retención efectiva de journald.
- [ ] 8.4 Verificar recuperación del proceso y entrega manual desde main con rollback compatible del propio servicio; contrastar los fallos de base/proveedores/broker con evidencia de laboratorio sin detener infraestructura compartida del VPS.
- [ ] 8.5 Consolidar la matriz AC01–AC17 con evidencia JVM, nativo, imagen y VPS según corresponda; verificar que los controles productivos de published y AC16 figuran diferidos, que no se atribuyen backups/restauración a esta entrega y que los criterios pendientes no se presentan como cumplidos.
