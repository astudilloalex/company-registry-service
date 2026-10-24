# Evidencia de implementación HU08

## Autorización para continuar

El 2026-10-23 el usuario respondió «Si continúa» a la solicitud explícita de
continuar implementando y dejar pendiente la validación SonarQube en Antigravity.
Esa excepción permitió implementar sin acreditar el control del editor. El usuario solicitó después revisar todos los cambios de la rama; el control se retomó y completó en la revisión SonarQube descrita al final de este registro. Los apartados anteriores a ella conservan la evidencia histórica del aplazamiento.

## Preparación inicial: tarea 1.1 aplazada

Comprobación realizada el 2026-10-23 a las 04:40 UTC, sobre HEAD
`6dcd65eee2e56dfbd079d869ec89a51b7ec7d87a`.

OpenSpec está instalado en `/home/alex/.nvm/versions/node/v25.1.0/bin`.
Tras incorporar ese directorio al PATH de los comandos de esta sesión:

- `openspec list --json` confirmó la raíz local del proyecto.
- `openspec status --change add-transactional-outbox-publication --json`
  confirmó el esquema `spec-driven` y los artefactos de planificación disponibles.
- `openspec instructions apply --change add-transactional-outbox-publication --json`
  devolvió `ready`, con 0/43 tareas completas; se leyeron los cinco archivos de contexto.
- `openspec validate add-transactional-outbox-publication --strict` terminó con
  código 0: `Change 'add-transactional-outbox-publication' is valid`.

### Comprobación de Antigravity y SonarQube

El enlace `/usr/local/bin/antigravity-ide` apunta a una ruta inexistente.
La instalación accesible tiene CLI en
`/opt/antigravity-ide/bin/antigravity-ide`.

La ejecución de esa CLI con `--list-extensions` incluyó
`sonarsource.sonarlint-vscode`. La extensión está instalada en
`/home/alex/.antigravity-ide/extensions/sonarsource.sonarlint-vscode-5.7.0-linux-x64`.
El comando también informó `EROFS` al intentar crear el directorio de logs
`/home/alex/.config/Antigravity IDE/logs/20261022T234055`.
Su código de salida 0 acredita el listado, no un análisis de fuentes.

Las herramientas disponibles en esta sesión no exponen control nativo del
editor ni lectura de sus diagnósticos. No pudo verificarse el ciclo requerido
de abrir archivo, esperar análisis, leer diagnósticos y cerrar pestaña.
La presencia de la extensión no acredita que ese control haya pasado.

### Registro por archivo

| Ruta | SHA-256 de la revisión | Análisis SonarQube en editor | Correcciones | Cierre de pestaña |
| --- | --- | --- | --- | --- |
| `src/main/java/com/alexastudillo/companyregistry/infrastructure/outbox/OutboxConfiguration.java` | `36d25f55f410d7e9d2c95078ad7007315f4bf2fce6ce9dcb4f0156c60736c22e` | Pendiente: archivo existente elegido para verificar acceso, sin abrir ni analizar | Sin editar | No verificado |

No se modificaron archivos Java/Python ni dependencias y no se ejecutaron
pruebas de implementación. En esa comprobación la tarea 1.1 quedó sin marcar. La validación de
OpenSpec comprueba los artefactos de planificación y no sustituye SonarQube,
Gradle ni las pruebas PostgreSQL/RabbitMQ/JVM/nativas solicitadas.

Para continuar conforme a la decisión 10 de `design.md`, hace falta acceso
operativo al editor y sus diagnósticos. Si el usuario decide posponer ese
control, debe quedar explícita esa excepción antes de modificar clases;
no se declarará cumplida la validación que siga pendiente.

## Dependencias: tarea 1.2

`./gradlew compileJava dependencyInsight --dependency smallrye-reactive-messaging-rabbitmq --configuration runtimeClasspath --console=plain` y la consulta equivalente de `vertx-rabbitmq-client` terminaron correctamente. Quarkus 3.40.1 / Java 25 conservados; SmallRye RabbitMQ 4.37.0, Mutiny Vert.x RabbitMQ 3.23.1 y Vert.x RabbitMQ 4.5.34. Logs de sesión: `/tmp/company-hu08-dependencies.log` y `/tmp/company-hu08-compile.log`.

## Progreso funcional verificado

Las tareas de implementación se marcan por su comportamiento y pruebas ejecutadas bajo la excepción de editor autorizada; en ese momento 1.1 y 9.4 estaban pendientes y ningún checkbox acreditaba SonarQube. La primera suite global ejecutó 571 pruebas: 569 pasaron y dos detectaron el inventario de índice V9 ausente en DBML/documentación, ya corregido y pendiente de repetición final. Contratos (ocho ejemplos para siete tipos), configuración, coordinación, arquitectura y entrega RabbitMQ/PostgreSQL pasaron en esa ejecución. Las nuevas pruebas de procesos/migración/observabilidad se verifican por separado antes del cierre.

## JVM anterior al ajuste final de logs

`./gradlew -I docs/operations/hu08-test-results.gradle -Phu08EvidenceRun=final-jvm test build --console=plain`: BUILD SUCCESSFUL, 577 pruebas, cero fallos y cero omisiones. Reportes en `build/hu08-evidence/final-jvm/results/test/`; log `build/hu08-evidence/company-hu08-final-build.log`. `verifyProductionIsolation` pasó en la ejecución de procesos posterior. Los JAR productivos excluyen los harness HU08 y sus hashes están en `build/hu08-evidence/jvm-artifact-sha256.txt`. RabbitMQ Java client efectivo: 5.35.0.

## Revisión final de JVM y procesos

Tras el ajuste final del texto de logs, la ejecución aislada volvió a pasar `test build`: 577 pruebas, cero fallos y cero omisiones. `verifyProductionIsolation quarkusIntTest` pasó con 31 casos declarados: 27 ejecutados, cero fallos; cuatro omisiones condicionales de PackagedVerificationIT porque el artefacto es productivo y excluye rutas de verificación. Los diez escenarios HU08 sí se ejecutaron completos. Las regresiones de captura usan scheduler deshabilitado y credenciales ficticias sin conexión; sus recursos se restringieron a las clases anotadas para impedir mezclar bases con HU08. La suite HU08 conserva entrega y broker reales. Logs/reportes definitivos bajo build/hu08-evidence/final-jvm y jvm-process; hashes en jvm-artifact-sha256.txt.

## Artefacto nativo final

Build Mandrel/Podman: BUILD SUCCESSFUL en 4m 24s, Mandrel 25.0.4.1-Final, Linux amd64, builder digest sha256:50ce63f80dabf588d0e3c43857ae9d1a981d78a3e9ff42579965be23d47254c7. Suite nativa productiva PackagedOutboxIT: 10/10, sin fallos ni omisiones. ELF x86-64 de 119.065.656 bytes con los siete JSON Schemas presentes como bytes exactos.

`008c1c369d1b79b24c1411757f6b3d999822ee39f85bc419cfd7e0f8eae5a06d  build/company-registry-service-1.0.0-SNAPSHOT-runner`

Reportes bajo `build/hu08-evidence/native/results/quarkusIntTest/`; logs de procesos reales bajo `native/processes/`. La matriz nativa se limita a HU08; la regresión completa de negocio se acreditó en JVM. Los 46 hashes del manifiesto coinciden con las fuentes finales. Se verificó ausencia de los secretos de prueba/inyección en los logs conservados de procesos. SonarQube y cierre de pestañas siguen sin acreditar. Véase `docs/operations/hu08-acceptance-review.md`.

## Estado anterior a la revisión SonarQube

`openspec validate add-transactional-outbox-publication --strict` pasa. Progreso funcional: 41/43 tareas. Solo 1.1 y 9.4 permanecen abiertas: acceso/ciclo y revisión SonarQube por archivo en Antigravity, pospuestos por autorización explícita del usuario. No se acredita análisis de editor ni cierre de pestañas. El cambio conserva sus artefactos para revisión y no se archiva con esos controles pendientes.

## Revisión SonarQube solicitada sobre la rama completa

El 2026-10-23 se revisaron los 67 archivos modificados o añadidos respecto de `origin/main`/HEAD `6dcd65e`, incluidos los no rastreados. Los 46 archivos Java tienen cero incidencias SonarQube, cero diagnósticos Java y cero hotspots; se cerró cada pestaña después de recoger los diagnósticos. El manifiesto `source-quality-manifest.md` contiene las rutas, hashes y horas UTC finales. No hay Python modificado.

Se recuperó el acceso mediante el CLI real de Antigravity y un puente temporal de desarrollo que usa la API del editor, sin instalarlo en el perfil. El ciclo inicial analizó OutboxConfiguration sin editarlo antes de las correcciones de esta revisión. Se ejecutó `SonarLint.AnalyseOpenFile`, se esperó la finalización del servidor, se leyeron `languages.getDiagnostics` y se cerró la pestaña con `tabGroups.close`; no se infiere éxito de un timeout o de la compilación. El colector maneja el singular «1 issue» y la rotación de logs, y repite los análisis que no obtuvieron evidencia completa. Tras recompilar se repitió DeliveryCoordinator para verificar la resolución de tipos actualizada.

Versiones efectivas: Antigravity IDE 1.107.0, SonarQube for IDE 5.7.0, analizador Java 8.36.0.45637, Java 25 y 571 reglas Java activas. Se corrigieron 17 hallazgos: S7467×7, S1192×5, S1481×2, S3358×1, S6213×1 y S6813×1, además de tres advertencias Java. Se aplicaron también S5778/S5411 preventivamente en las pruebas. No se añadieron suppressions/NOSONAR ni se cambiaron reglas, perfiles, exclusiones o filtros.

Los restantes archivos se revisaron manualmente y con el análisis de texto/secretos disponible. La extensión no aporta reglas semánticas de Markdown, SQL, properties o Gradle en este entorno; su resultado no se presenta como un Quality Gate de servidor. Evidencia reproducible en `build/hu08-evidence/sonarqube/review.json`, eventos y fragmentos reales en `editor-events.jsonl` y puente temporal conservado en `bridge/`. Los resultados funcionales anteriores corresponden a sus revisiones; las nuevas ejecuciones posteriores a las correcciones se registran por separado.

El estado actual de OpenSpec es **43/43 tareas completas** y `openspec validate add-transactional-outbox-publication --strict` pasa. La revalidación posterior a SonarQube ejecutó `test build`: **577 pruebas, cero fallos, errores u omisiones**, en `build/hu08-evidence/sonar-final-jvm/results/test/`. El artefacto productivo pasó `verifyProductionIsolation` y los **10 escenarios PackagedOutboxIT en JVM**, sin fallos ni omisiones, en `sonar-jvm-process/results/quarkusIntTest/`. Se contrastaron 188 clases de test/contrato/integración/verificación contra las entradas del JAR sin encontrar ninguna; los siete esquemas empaquetados coinciden byte a byte. Comandos y hashes JVM de esta revisión en `build/hu08-evidence/sonarqube/`.

La reconstrucción nativa posterior a SonarQube pasó en **4m 27s**, con el mismo Mandrel 25.0.4.1-Final/Podman y los flags documentados. Los **10 escenarios nativos PackagedOutboxIT** pasaron sin fallos ni omisiones en **1m 8s**. El nuevo ELF x86-64 mide 119.065.656 bytes, contiene los siete esquemas exactos y tiene SHA-256 `665b7d3a7ad6cd99d7d3fbd73a5c4023f0e6ab8c37bb6e65d89d2b20ba34e8f6`. Reportes en `build/hu08-evidence/sonar-native/results/quarkusIntTest/`, log y metadatos en `sonarqube/native-*.log` y `native-artifact.json`. El cierre conserva las 46 revisiones Java vigentes sin ediciones posteriores, y vuelve a analizar la documentación actualizada. No se archiva el cambio como parte de esta revisión.

## Corrección W1 y revalidación vigente

OutboxTelemetry usa `@Startup` para registrar los tres gauges en todos los modos.
Las nuevas pruebas HTTP reprodujeron la ausencia de series antes de la corrección
y pasaron después. Disabled y stored-only exponen NaN/NaN/0 sin conexiones AMQP
ni intentos de entrega; también se comprobó contra JAR, ELF e imagen runtime.

La revalidación pasó 579 pruebas JVM completas, 10 escenarios de procesos JVM y
10 nativos, sin fallos, errores u omisiones. El ELF tiene SHA-256
`328d5646880a41c6a0916c08fd9d2783b702c5eed84a588ecead4ead8c1683f0` y siete esquemas exactos; el JAR excluye las 189 clases
fuente auxiliares contrastadas. La imagen expone los gauges en los tres modos.

SonarQube volvió a analizar los 47 Java con el modelo Java restablecido, AST,
Java 25 y classpath resuelto: cero incidencias, hotspots o diagnósticos Java,
hashes vigentes y pestañas cerradas. La validación OpenSpec estricta pasó:
43/43 tareas, 24 requisitos y 50 escenarios; cero hallazgos activos.

Evidencia actual en `build/hu08-evidence/w1/`, `source-quality-manifest.md` y
`verification-report.md`. El detalle de ejecuciones y los resultados anteriores
se distinguen en `docs/operations/hu08-acceptance-review.md`. W1 queda cerrada
y el cambio conserva sus artefactos activos para revisión.
