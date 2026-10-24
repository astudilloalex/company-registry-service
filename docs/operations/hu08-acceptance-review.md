# HU08 — evidencia de aceptación

## Alcance implementado

Entrega reactiva de los siete contratos V1 desde el hecho almacenado, mediante
reservas PostgreSQL cortas, resultados condicionales y confirmaciones RabbitMQ.
Los puertos y la coordinación viven en Application; SQL, transporte, scheduler,
contextos y observabilidad viven en Infrastructure. Domain conserva independencia
de frameworks. No se incorporan consumidores, reprocesamiento administrativo,
garantías de orden, nuevos payloads ni aprovisionamiento de infraestructura compartida.

La revisión SonarQube en Antigravity del 2026-10-23 está **completa**: los 47 archivos Java modificados o añadidos tienen cero incidencias, cero diagnósticos Java y cero hotspots, con hash y cierre de pestaña acreditados por archivo. El alcance total incluye 69 archivos. Véase el [manifiesto de calidad](../../openspec/changes/add-transactional-outbox-publication/source-quality-manifest.md).

## Entorno y dependencias

- Java/Quarkus: 25 / 3.40.1, conservados.
- SmallRye RabbitMQ: 4.37.0; Mutiny Vert.x RabbitMQ: 3.23.1; Vert.x RabbitMQ: 4.5.34; AMQP Java client: 5.35.0.
- PostgreSQL de pruebas: `postgres:18-alpine`.
- RabbitMQ de pruebas: `rabbitmq:4.1.4-management-alpine`, vhost `platform`.
- Cuenta productora de pruebas con configure/write limitados a `company.events`
  y sin permisos de lectura; cuenta de harness independiente para colas temporales.
- Mandrel: 25.0.4.1-Final / native-image 25.0.4.1, Linux amd64.
- Builder: `quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25`, digest
  `sha256:50ce63f80dabf588d0e3c43857ae9d1a981d78a3e9ff42579965be23d47254c7`.

## Matriz trazable

Los grupos AC corresponden a la matriz del diseño aprobado; cada fila distingue
evidencia real y pruebas deterministas.

| Criterios | Evidencia | Frontera |
| --- | --- | --- |
| AC01–AC03, AC16 | `OutboxConfigurationTest`, `OutboxBootTest`, `PackagedOutboxIT.modesWithoutCredentialsKeepPendingIdentityUntilPublishedRestart` | Mapping real; procesos con cero conexiones contadas por proxy; replay sin backfill |
| AC04–AC06 | `CompanyCreatedAtomicTest` y `PackagedOutboxIT.httpCreationReplayAndNoOpPreserveOneOriginalFact`, `rejectedRequiredOutboxWriteRollsBackHttpBusinessAndCannotLeakToBroker` | HTTP, productores, PostgreSQL y RabbitMQ reales; trigger de rechazo solo en pruebas |
| AC07–AC09 | `DeliveryCoordinatorTest`, `RabbitConfirmedPublisherTest`, `PackagedOutboxIT.brokerConfirmIsRequiredAndControlledNackIsTerminalAtBudget`, `timeoutWithRealConfirmHeldPreservesFailedAfterLatePhysicalAck` | Fakes deterministas separados de proxy AMQP que retiene confirms o transforma ack en nack |
| AC10–AC12 | `DeliveryIntegrationTest`, `PackagedOutboxIT.sevenTypesAndRoutingWorkAcrossTwoPublisherProcesses`, `killedProcessAfterReservationRecoversIdenticalDuplicateOnLeaseExpiry`, `brokerConfirmedButLocalCommitMissingRecoversAfterRealProcessDeath`, `restartRecoversOnlyEligibleHistoryAndDoesNotBlockLaterAggregateRevision` | Reservas/transiciones PostgreSQL reales, procesos terminados efectivamente, duplicados con mismos bytes/eventId |
| AC13–AC15 | `StoredEventSerializerTest`, `RabbitConfirmedPublisherTest`, `DeliveryIntegrationTest.invalidFactFailsWithoutSendingOrCancellingValidSibling`, recorrido de siete tipos en `PackagedOutboxIT` | Esquemas empaquetados, ejemplos incluidos ARCHIVED/hijos, contratos cerrados, contexto original y cero emisiones inválidas |
| AC17 | `OutboxTelemetryTest`, métricas de `DeliveryIntegrationTest`, `PackagedOutboxIT.initialBrokerOutageAndLossAfterConnectionKeepBusinessHealthyAndRecover` | Confirmaciones separadas de commits, NaN ante indisponibilidad, etiquetas acotadas, API sana sin broker antes/después de conectar |

`AmqpFaultProxy`, los triggers de espera/rechazo y los recursos de contenedores
son exclusivamente de pruebas. El proxy nack transforma una confirmación real
en el frame negativo; no acredita un rechazo espontáneo del broker por falta de
disco. Las terminaciones usan `destroyForcibly`, no una simulación de reinicio.

## Índice V9 y persistencia

V1–V8 permanecen sin cambios. V9 añade únicamente
`ix_company_outbox_due_priority`, parcial para PENDING, sobre
`coalesce(next_attempt_at,created_at),created_at,id`. La prueba carga 20.000 filas,
actualiza estadísticas y compara planes con/sin el índice dentro de una
transacción de pruebas que revierte su eliminación temporal. El fixture usa
payload mínimo; esta comparación no es una estimación de capacidad productiva.

Los planes están en `build/hu08-evidence/postgres-delivery-plan*.txt`.
La primera comparación comparable registró 56,032 ms sin V9, con ordenación,
y 0,085 ms con V9, mediante Index Scan sin ordenación en disco.
`OutboxDeliveryMigrationTest` ejecuta V1–V8 y luego V9 en un esquema aislado,
comparando filas e historia/checksums V1–V8 antes/después. Las pruebas existentes
conservan instalación limpia, restricciones y separación de roles runtime/migración.

## Comandos reproducibles

Las ejecuciones usan el socket Podman rootless indicado en el README:

```sh
export DOCKER_HOST=unix:///run/user/1000/podman/podman.sock
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew -I docs/operations/hu08-test-results.gradle -Phu08EvidenceRun=final-jvm test build
./gradlew -I docs/operations/hu08-test-results.gradle -Phu08EvidenceRun=jvm-process quarkusIntTest
./gradlew quarkusBuild -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false -Dquarkus.native.container-build=true -Dquarkus.native.container-runtime=podman -Dquarkus.native.native-image-xmx=5g '-Dquarkus.native.additional-build-args=--initialize-at-run-time=com.alexastudillo.companyregistry.infrastructure.persistence.UuidV7Generator,--parallelism=2'
./gradlew -I docs/operations/hu08-test-results.gradle -Phu08EvidenceRun=native quarkusIntTest --tests '*PackagedOutboxIT' -x quarkusBuild -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false
```

Ejecutar las suites completas y de procesos en serie: además de puertos HTTP pueden compartir estado global y artefactos de build. Las ejecuciones definitivas se registran por separado. La ejecución global intermedia de 571
pruebas detectó dos omisiones documentales del índice, corregidas. Otra ejecución
intermedia coincidió en el puerto HTTP con integración y no acredita aceptación
final. No se reutilizan sus resultados como si correspondieran al cierre.

## Resultados anteriores a las correcciones SonarQube

La ejecución JVM final de `test build`, posterior al ajuste de logs, pasó
577 pruebas, cero fallos y cero omisiones. La regresión completa
`verifyProductionIsolation quarkusIntTest --fail-fast` pasó sus 31 casos declarados:
27 ejecutados sin fallos y cuatro omisiones condicionales de `PackagedVerificationIT`
porque se verificó el artefacto productivo sin rutas auxiliares. Los diez escenarios
HU08 se ejecutaron completos, incluidos confirms retenidos, nack inyectado,
timeout/ack tardío, dos publicadores, dos terminaciones forzadas, mezcla de historia,
modos sin credenciales y recuperación del broker antes/después de conectar.

Los fixtures HU01–HU07 conservan la captura en los tres modos con scheduler
explícitamente deshabilitado y credenciales ficticias sin conexión. Sus recursos
quedaron restringidos a las clases anotadas, evitando mezclar bases con la suite
HU08, que mantiene scheduler, PostgreSQL y RabbitMQ reales. La comprobación del
JAR contrastó sus entradas con 188 clases fuente de test/contrato/integración/
verificación: ninguna está en el paquete productivo.

El build nativo productivo pasó en 4m 24s con Mandrel/Podman, `-Xmx5g` y dos hilos.
La misma clase `PackagedOutboxIT` pasó sus diez escenarios contra el ELF real,
cero fallos y cero omisiones, desde directorios temporales fuera de fuentes.
Se comprobaron los bytes exactos de los siete JSON Schemas dentro del ejecutable,
no solo su presencia en el classpath del harness. El ELF Linux x86-64 mide
119.065.656 bytes. Su hash, contratos y logs están en
`build/hu08-evidence/native-artifact-sha256.txt`, `native-contracts.md` y
`native/results/quarkusIntTest/`.

La matriz nativa es la de entrega HU08; la regresión completa HU01–HU07 de esta
revisión se ejecutó en JVM. No se presenta esa regresión JVM como ejecución
nativa adicional. El build informó opciones experimentales/deprecadas originadas
en metadatos de dependencias; no se añadieron suppressions ni se cambiaron
Java/Quarkus para ocultarlas. Estos resultados corresponden a la revisión anterior a las correcciones SonarQube; las ejecuciones posteriores se distinguen a continuación.

Los reportes se conservan bajo `build/hu08-evidence/<ejecución>/results/` y los
logs de procesos bajo `build/hu08-evidence/jvm/processes/` o `native/processes/`.
El manifiesto de archivos revisados y hashes está en los artefactos del cambio,
con estado SonarQube por archivo. La revisión efectiva del editor ya acredita cero incidencias en las fuentes finales.

## Revalidación posterior a SonarQube, anterior a W1

El 2026-10-23 se corrigieron 17 hallazgos SonarQube y tres advertencias Java. Las revisiones finales de los 46 archivos Java acreditan cero incidencias y hotspots, cero diagnósticos Java y pestañas cerradas. El control abarca los 67 archivos de la rama, incluidos los no rastreados; para Markdown, SQL, properties y Gradle se acredita la revisión manual y el análisis de texto/secretos disponible, sin atribuirles un analizador semántico ausente. SonarQube for IDE 5.7.0 usó Java 25, analizador 8.36.0.45637 y 571 reglas Java activas. No se alteraron perfiles ni exclusiones ni se añadieron suppressions.

| Verificación sobre las fuentes corregidas | Resultado | Evidencia |
| --- | --- | --- |
| `test build` | 577 pruebas; cero fallos, errores u omisiones; 5m 10s | `build/hu08-evidence/sonar-final-jvm/results/test/` |
| `verifyProductionIsolation` y `quarkusIntTest --tests '*PackagedOutboxIT' --fail-fast` | 10/10 JVM; cero fallos u omisiones; 1m 45s | `build/hu08-evidence/sonar-jvm-process/results/quarkusIntTest/` |
| Build Mandrel/Podman con los mismos flags documentados | Correcto; 4m 27s | `build/hu08-evidence/sonarqube/native-build.log` |
| `quarkusIntTest --tests '*PackagedOutboxIT' -x quarkusBuild -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false` | 10/10 nativos; cero fallos u omisiones; 1m 8s | `build/hu08-evidence/sonar-native/results/quarkusIntTest/` |
| OpenSpec estricto | Válido; 43/43 tareas completas | `tasks.md` y manifiesto de calidad del cambio |

Las ejecuciones nuevas usan `-I docs/operations/hu08-test-results.gradle` y `-Phu08EvidenceRun=sonar-final-jvm`, `sonar-jvm-process` o `sonar-native`, respectivamente. Se ejecutaron en serie sobre PostgreSQL/RabbitMQ aislados. Esta repetición de procesos se limita a los diez casos HU08 en ambos formatos; la regresión completa de procesos anterior se conserva separada. Las 577 pruebas sí abarcan la suite JVM completa.

El ELF nuevo es Linux x86-64, 119.065.656 bytes, con los bytes exactos de los siete esquemas. SHA-256: `665b7d3a7ad6cd99d7d3fbd73a5c4023f0e6ab8c37bb6e65d89d2b20ba34e8f6`. El JAR productivo vuelve a excluir las 188 clases de test/contrato/integración/verificación contrastadas y conserva los siete esquemas exactos. No hubo cambios posteriores en las fuentes Java analizadas.

El reporte de los 67 archivos, hashes, diagnósticos y cierre de pestañas está en `build/hu08-evidence/sonarqube/review.json`; los eventos reales del editor y el puente reproducible están en el mismo directorio. `test-summary.json`, `jvm-artifact-sha256.txt` y `native-artifact.json` identifican las pruebas y artefactos nuevos. El resultado SonarQube corresponde a la extensión de Antigravity, sin presentarlo como un Quality Gate de servidor.

## Corrección W1 y revalidación vigente

OutboxTelemetry registra los tres gauges al arrancar mediante `@Startup`, en
los tres modos. En disabled y stored-only, `/q/metrics` responde 200 y expone
pendientes y antigüedad como NaN y disponibilidad como 0 hasta una consulta
válida. Registrar o consultar las métricas conserva cero conexiones AMQP y
el evento en PENDING:0:none. El contrato operacional recoge esta semántica.

Las dos nuevas pruebas HTTP de arranque fallaron antes de la corrección por
ausencia de las series: 3 pruebas, 2 fallos y 0 errores de arranque. Después
pasaron en la suite completa. La suite PackagedOutboxIT también comprueba los
tres valores, cero conexiones y conservación del evento en ambos modos,
contra el JAR y el ELF reales.

| Verificación de W1 | Resultado | Evidencia |
| --- | --- | --- |
| `test build` | 579 pruebas; cero fallos, errores u omisiones; 9m 15s | `build/hu08-evidence/w1-final-jvm/results/test/` |
| `verifyProductionIsolation` y PackagedOutboxIT JVM | 10/10; cero fallos u omisiones; 2m 10s | `build/hu08-evidence/w1-jvm-process/results/quarkusIntTest/` |
| Reconstrucción Mandrel/Podman, 5g y dos hilos | Correcta; 6m 19s | `build/hu08-evidence/w1/native-build.log` |
| PackagedOutboxIT contra el nuevo ELF | 10/10; cero fallos u omisiones; 1m 23s | `build/hu08-evidence/w1-native/results/quarkusIntTest/` |
| Imagen Dockerfile.native-micro y PostgreSQL 18 aislado | Tres gauges presentes y salud UP en todos los modos; NaN/0 sin entrega; fila completa conservada al detener entrega | `build/hu08-evidence/w1/image/container-verification.json` |
| SonarQube en Antigravity | 47 Java reanalizados; cero incidencias, hotspots y diagnósticos Java; hashes y pestañas cerradas por archivo | `build/hu08-evidence/w1/sonar-review.json` |
| OpenSpec | 43/43 tareas, 24 requisitos y 50 escenarios; 0 CRITICAL, 0 WARNING y 0 SUGGESTION; validación estricta correcta | `openspec/changes/add-transactional-outbox-publication/verification-report.md` |

Las ejecuciones usaron el init script documentado con `-Phu08EvidenceRun=w1-final-jvm`,
`w1-jvm-process` y `w1-native`, en serie. El ELF mide 119,065,656 bytes;
SHA-256 `328d5646880a41c6a0916c08fd9d2783b702c5eed84a588ecead4ead8c1683f0`. Contiene los siete esquemas exactos. El JAR conserva
esos esquemas y excluye las 189 clases fuente auxiliares contrastadas. V1–V8 y
el contrato OpenAPI de negocio siguen sin cambios. Los hashes Java probados,
compilados y analizados coinciden.

La reproducción original de W1, con gauges ausentes en disabled y stored-only,
se conserva por separado. La nueva imagen registra
`outboxGauges={disabled:true,published:true,stored-only:true}`; se limpiaron sus
contenedores e imagen desechables. La comprobación usa el perfil de pruebas;
la matriz AMQP se acredita mediante las suites JVM y ELF con RabbitMQ real.

Se repitieron los 47 análisis Java después de restablecer el servidor Java del
editor. La evidencia final acredita Java 25, 571 reglas, análisis AST y classpath
resuelto; el intento con modelo incompleto se conserva como antecedente y no
acredita el resultado final. Se reanalizaron también los archivos no Java
actualizados y se cotejaron los hashes de los restantes, con las limitaciones
de lenguaje descritas en el manifiesto. No se alteraron reglas ni exclusiones
ni se añadieron suppressions. El resultado es de SonarQube for IDE.

El reporte, los eventos reales del editor, los comandos, las revisiones anteriores
y el procedimiento de imagen están bajo `build/hu08-evidence/w1/`. W1 queda cerrada;
la nueva verificación mantiene el cambio activo para revisión.

## Despliegue y rollback

Aplicar el [contrato de entrega](../contracts/outbox-publication.md): desplegar
stored-only, verificar cuenta/vhost/exchange topic durable y colas/bindings contra
rutas efectivas, migrar overrides fijos a `%s`, activar published mediante reinicio
y observar backlog, edad, reintentos, FAILED e incertidumbre. PUBLISHED acredita
confirmación del broker y puede corresponder a un mensaje sin binding.

Para detener o revertir código, reiniciar stored-only/disabled conservando
filas, snapshots, aceptaciones, claves e historia Flyway. V9 es aditiva; no editar
migraciones aplicadas ni borrar hechos ni reactivar terminales. FAILED conserva
incertidumbre y no prueba que un mensaje nunca se haya entregado.
