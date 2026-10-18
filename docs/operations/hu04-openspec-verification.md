# Verificación OpenSpec — HU04, issue #6

Cambio: `add-company-site-registration-and-retrieval`, esquema `spec-driven`.
Revisión: 2026-10-17, America/Guayaquil. Se contrastaron propuesta, diseño,
cinco deltas, tareas, fuentes, pruebas y evidencia de ejecución.

| Dimensión | Resultado |
| --- | --- |
| Completitud | 35/35 tareas marcadas; implementación localizada para 44/44 requisitos |
| Corrección | 43/44 requisitos sin divergencias detectadas; plazo parcial (W1). 101 escenarios revisados: 99 con evidencia de pruebas, uno parcial (W1) y uno sin prueba específica (W2) |
| Coherencia | Arquitectura/patrones consistentes; decisión 5 del diseño parcialmente cumplida (W1) |

41 requisitos ADDED y tres MODIFIED; no hay REMOVED ni RENAMED.
Todos los controles aplicables se revisaron. Las carencias encontradas se reportan
como hallazgos, no como controles omitidos. Las casillas completas se contrastaron
con fuentes y evidencia; el estado OpenSpec no se usó como veredicto automático.

## Hallazgos

**CRITICAL: 0.** No se encontraron tareas pendientes ni requisitos totalmente ausentes.

**WARNING W1 — El presupuesto no acota toda la operación.**

[RegisterSite.java:153](../../src/main/java/com/alexastudillo/companyregistry/application/site/RegisterSite.java#L153)
mide fases y pasa el remanente al adaptador, pero no instala un timeout de la
suscripción ni comprueba el plazo al terminar.
[ReactiveAtomicOperation.java:11](../../src/main/java/com/alexastudillo/companyregistry/infrastructure/persistence/ReactiveAtomicOperation.java#L11)
solo aplica `statement_timeout`/`lock_timeout` después de obtener sesión.
La adquisición de conexión, una espera sin sentencia activa y la finalización no
quedan acotadas por esos parámetros. Su hook de cancelación espera `finalized`
sin límite propio en la línea 35. Una fase que no termina puede exceder el plazo
configurado sin producir el 503 previsto.

Se ejecutó una sonda aislada sobre las clases compiladas de Application con un
`AtomicOperation` que simula una fase de lectura que no emite: presupuesto 50 ms,
observador externo 350 ms. Terminó por `io.smallrye.mutiny.TimeoutException` del
observador, no por `SiteFailure.UNAVAILABLE` del servicio. La sonda temporal está
en `/tmp/Hu04DeadlineProbe.java`; no altera fuentes. Demuestra la falta de plazo
en el caso de uso; no es una prueba de una caída real de red.

**Acción:** imponer un plazo compartido que abarque adquisición, commit,
rollback/cierre y recuperación, preservando la incertidumbre del commit y sin
escrituras suscritas por separado. Añadir pruebas de fase sin terminación y
finalización que excede el remanente: 503, ninguna reejecución ciega y aceptación
recuperable mediante la misma clave. La cancelación explícita en
[SiteBudgetTest.java:156](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/persistence/SiteBudgetTest.java#L156)
no verifica que el presupuesto cancele por sí mismo. Actualizar después la
evidencia de aceptación completa. Afecta a «Espera finita e incertidumbre explícita»,
«Timeout o commit incierto» y decisión 5 del diseño.

**WARNING W2 — Falta la prueba de cambios entre páginas.**

[SiteHttpTest.java:85](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteHttpTest.java#L85)
recorre un conjunto estable con fechas iguales. `PackagedSiteIT` y el harness OCI
también crean sus filas antes de navegar. No hay una prueba que inserte o cambie
sedes entre la primera página y la continuación. La consulta en
[ReactiveSiteRepository.java:26](../../src/main/java/com/alexastudillo/companyregistry/infrastructure/persistence/ReactiveSiteRepository.java#L26)
aplica frontera y filtros a los datos actuales, pero falta comprobar ese escenario.

**Acción:** añadir prueba PostgreSQL/HTTP que obtenga el cursor, inserte filas a
ambos lados de la frontera y cambie por fixture el estado de una fila pendiente;
continuar con el cursor original y verificar frontera y filtros actuales. Cubre
«Cambios entre páginas» sin prometer una colección histórica congelada.

**SUGGESTION: 0.** No se detectaron desviaciones significativas de patrones Java.

## Evidencia ejecutada e inspeccionada

- Reejecución focalizada durante esta revisión: **63 pruebas, 22 suites, cero
  fallos/errores/omitidos**, `BUILD SUCCESSFUL` en 1m 30s. Incluye Site, clasificación
  de persistencia, arquitectura y OpenAPI. XML en
  `build/hu04-evidence/verify/results/test/xml`; log `/tmp/hu04-verify-tests.log`.
- Comando: `QUARKUS_HTTP_TEST_PORT=0 DOCKER_HOST=unix:///run/user/1000/podman/podman.sock TESTCONTAINERS_RYUK_DISABLED=true ./gradlew -I docs/operations/hu04-test-results.gradle -Phu04EvidenceRun=verify test --tests '*Site*' --tests '*PersistenceFailuresTest' --tests '*ArchitectureTest' --tests '*OpenApiHttpTest' --console=plain`.
- `openspec validate add-company-site-registration-and-retrieval --strict` pasó
  nuevamente; `status` e `instructions apply` confirman todos los artefactos y tareas.
- Evidencia previa inspeccionada: 254 tests JVM sin fallos/errores/omitidos;
  integración JVM y nativa con siete ejecutados en cada una, sin fallos/errores y
  cuatro casos de fixtures excluidos del artefacto productivo en cada ejecución.
  Esas exclusiones no son controles HU04 omitidos: las rutas productivas se
  verifican mediante `PackagedSiteIT` y aislamiento del artefacto.
- Evidencia OCI previa: tres modos, UID 1001, reinicio/rotación/replay,
  proveedores rechazando conexiones y verificación de contadores/eventos. Runner
  SHA-256 `851c5e175215d0843435b89c82eef94548b31b81d9a5cb840e82cb8f7cccd187`.
  No se reconstruyeron ni reejecutaron binario o imagen durante esta revisión.
- V1–V3 comparadas byte a byte con `0d2ca32`: intactas. Políticas/codecs históricos
  Company revisados sin cambios; sin TTL ni publisher HU08.
- Informes anteriores: `build/hu04-evidence/jvm/results-summary.json`,
  `build/hu04-evidence/native/results-summary.json`, `build/hu04-evidence/oci/report.json`
  y [hu04-acceptance-review.md](hu04-acceptance-review.md). W1/W2 precisan la
  afirmación previa de cobertura completa.

## Coherencia con el diseño

Se sigue la separación Domain/Application/API/Infrastructure y se reutilizan
Site/SiteRecord. Se preservan texto original y presencia, política v1 congelada,
replay antes de admisión, bloqueo del padre para los cuatro tipos, segunda
consulta de completado, auditoría independiente y recuperación tras cierre de la
transacción fallida. Fingerprint/snapshot propios mantienen procedencia histórica.
El join escalar evita reutilizar estado gestionado del padre; cursor HMAC separado,
limit+1 y frontera fecha/UUID respetan el diseño. Evento y API mantienen contratos
cerrados, mapper compartido y ausencia de publicación/proveedores en HU04.
La decisión 5 queda parcial por W1; la estrategia de evidencia debe incorporar W2.
No se proponen cambios de estilo que no afecten a estos contratos.

## Trazabilidad de requisitos y escenarios

Las pruebas de cada requisito reúnen la evidencia de sus escenarios. Se incluyen
pruebas compartidas cuando la regla es global (contexto/envelope); no se exige
un test HTTP separado por cada redacción repetida entre deltas. La reversión
operacional se contrasta con el runbook de conservación y las pruebas de reinicio.

| ID | Requisito | Implementación | Pruebas | Resultado |
| --- | --- | --- | --- | --- |
| 1.1 | [Errores estables y saneados](../../openspec/changes/add-company-site-registration-and-retrieval/specs/business-api-conventions/spec.md#L5) | [SiteApiFailures.java](../../src/main/java/com/alexastudillo/companyregistry/api/site/SiteApiFailures.java) | [FrameworkHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/FrameworkHttpTest.java), [CompanyRegistrationHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/reference/CompanyRegistrationHttpTest.java), [CompanyUpdateHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/update/CompanyUpdateHttpTest.java), [SiteHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteHttpTest.java) | Conforme |
| 1.2 | [Conflictos HU04 con causa principal descriptiva](../../openspec/changes/add-company-site-registration-and-retrieval/specs/business-api-conventions/spec.md#L28) | [SiteResponseCode.java](../../src/main/java/com/alexastudillo/companyregistry/api/site/SiteResponseCode.java) | [SiteHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteHttpTest.java), [SiteConcurrencyHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteConcurrencyHttpTest.java) | Conforme |
| 1.3 | [Validación de campos HU04 con HTTP 400](../../openspec/changes/add-company-site-registration-and-retrieval/specs/business-api-conventions/spec.md#L47) | [SiteRegistrationPolicy.java](../../src/main/java/com/alexastudillo/companyregistry/domain/model/SiteRegistrationPolicy.java) | [SiteRegistrationPolicyTest.java](../../src/test/java/com/alexastudillo/companyregistry/domain/model/SiteRegistrationPolicyTest.java), [SiteHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteHttpTest.java) | Conforme |
| 1.4 | [Fallos estructurales HU04 descriptivos](../../openspec/changes/add-company-site-registration-and-retrieval/specs/business-api-conventions/spec.md#L63) | [CreateSiteRequestReader.java](../../src/main/java/com/alexastudillo/companyregistry/api/site/CreateSiteRequestReader.java) | [SiteBoundaryTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteBoundaryTest.java), [SiteHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteHttpTest.java) | Conforme |
| 1.5 | [Ámbito y cabeceras HU04 descriptivos](../../openspec/changes/add-company-site-registration-and-retrieval/specs/business-api-conventions/spec.md#L79) | [SiteResource.java](../../src/main/java/com/alexastudillo/companyregistry/api/site/SiteResource.java) | [SiteHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteHttpTest.java), [BusinessContextHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/BusinessContextHttpTest.java) | Conforme |
| 1.6 | [Fallos de consulta HU04 identificables](../../openspec/changes/add-company-site-registration-and-retrieval/specs/business-api-conventions/spec.md#L94) | [SiteResource.java](../../src/main/java/com/alexastudillo/companyregistry/api/site/SiteResource.java) | [SiteHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteHttpTest.java), [SiteCursorTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/persistence/SiteCursorTest.java) | Conforme |
| 2.1 | [Idempotencia tenant y Company qualified](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-data-integrity/spec.md#L5) | [V4__add_site_registration_support.sql](../../src/main/resources/db/migration/V4__add_site_registration_support.sql) | [SiteCreationMetadataIntegrityTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/database/SiteCreationMetadataIntegrityTest.java), [IdempotencyIntegrityTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/database/IdempotencyIntegrityTest.java), [SiteResultMappingTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/persistence/SiteResultMappingTest.java) | Conforme |
| 2.2 | [Snapshot enriquecido original preservado](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-data-integrity/spec.md#L28) | [AcceptedResultCodec.java](../../src/main/java/com/alexastudillo/companyregistry/infrastructure/persistence/AcceptedResultCodec.java) | [AcceptedResultCodecTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/persistence/AcceptedResultCodecTest.java), [PackagedRestartIT.java](../../src/integrationTest/java/com/alexastudillo/companyregistry/PackagedRestartIT.java) | Conforme |
| 2.3 | [Evolución incremental del soporte HU04](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-data-integrity/spec.md#L42) | [V4__add_site_registration_support.sql](../../src/main/resources/db/migration/V4__add_site_registration_support.sql) | [SiteCreationMetadataMigrationTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/database/SiteCreationMetadataMigrationTest.java), [DatabaseStartupTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/database/DatabaseStartupTest.java), [SchemaCatalogIntegrityTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/database/SchemaCatalogIntegrityTest.java) | Conforme |
| 2.4 | [Forma persistente de aceptación de creación Site](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-data-integrity/spec.md#L55) | [V4__add_site_registration_support.sql](../../src/main/resources/db/migration/V4__add_site_registration_support.sql) | [SiteCreationMetadataIntegrityTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/database/SiteCreationMetadataIntegrityTest.java) | Conforme |
| 2.5 | [Snapshot local original de Site conservado](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-data-integrity/spec.md#L66) | [SiteResultCodec.java](../../src/main/java/com/alexastudillo/companyregistry/infrastructure/persistence/SiteResultCodec.java) | [SiteResultCodecTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/persistence/SiteResultCodecTest.java), [SiteResultMappingTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/persistence/SiteResultMappingTest.java), [PackagedSiteIT.java](../../src/integrationTest/java/com/alexastudillo/companyregistry/PackagedSiteIT.java) | Conforme |
| 2.6 | [Soporte persistente de paginación Site](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-data-integrity/spec.md#L78) | [V4__add_site_registration_support.sql](../../src/main/resources/db/migration/V4__add_site_registration_support.sql) | [SchemaCatalogIntegrityTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/database/SchemaCatalogIntegrityTest.java), [SiteIntegrityTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/database/SiteIntegrityTest.java) | Conforme |
| 2.7 | [Reversión compatible con aceptaciones HU04](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-data-integrity/spec.md#L86) | [docs/architecture/persistence-contract.md](../../docs/architecture/persistence-contract.md) | [PackagedSiteIT.java](../../src/integrationTest/java/com/alexastudillo/companyregistry/PackagedSiteIT.java), [PackagedRestartIT.java](../../src/integrationTest/java/com/alexastudillo/companyregistry/PackagedRestartIT.java) | Conforme |
| 3.1 | [Envelope de creación de Site versionado](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-created-event/spec.md#L9) | [SiteCreated.java](../../src/main/java/com/alexastudillo/companyregistry/application/site/SiteCreated.java) | [SiteCreatedContractTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/outbox/SiteCreatedContractTest.java) | Conforme |
| 3.2 | [Payload cerrado de ocho campos de Site](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-created-event/spec.md#L17) | [SiteCreatedOutbox.java](../../src/main/java/com/alexastudillo/companyregistry/infrastructure/outbox/SiteCreatedOutbox.java) | [SiteCreatedContractTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/outbox/SiteCreatedContractTest.java) | Conforme |
| 3.3 | [Contexto original de creación preservado](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-created-event/spec.md#L30) | [SiteCreated.java](../../src/main/java/com/alexastudillo/companyregistry/application/site/SiteCreated.java) | [SiteCreatedAtomicTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/outbox/SiteCreatedAtomicTest.java), [PackagedSiteIT.java](../../src/integrationTest/java/com/alexastudillo/companyregistry/PackagedSiteIT.java) | Conforme |
| 3.4 | [Persistencia condicional atómica de Site-created](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-created-event/spec.md#L42) | [SiteCreatedOutbox.java](../../src/main/java/com/alexastudillo/companyregistry/infrastructure/outbox/SiteCreatedOutbox.java) | [SiteCreatedAtomicTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/outbox/SiteCreatedAtomicTest.java), [SiteConcurrencyHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteConcurrencyHttpTest.java), [PackagedSiteIT.java](../../src/integrationTest/java/com/alexastudillo/companyregistry/PackagedSiteIT.java) | Conforme |
| 3.5 | [Hecho original inmutable para entrega futura](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-created-event/spec.md#L54) | [SiteCreatedOutbox.java](../../src/main/java/com/alexastudillo/companyregistry/infrastructure/outbox/SiteCreatedOutbox.java) | [PackagedSiteIT.java](../../src/integrationTest/java/com/alexastudillo/companyregistry/PackagedSiteIT.java) | Conforme |
| 3.6 | [Handoff contractual verificable para HU08](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-created-event/spec.md#L65) | [docs/contracts/events/README.md](../../docs/contracts/events/README.md) | [SiteCreatedContractTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/outbox/SiteCreatedContractTest.java), [PackagedSiteIT.java](../../src/integrationTest/java/com/alexastudillo/companyregistry/PackagedSiteIT.java) | Conforme |
| 4.1 | [Contexto y clave obligatorios para registrar Site](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L9) | [SiteResource.java](../../src/main/java/com/alexastudillo/companyregistry/api/site/SiteResource.java) | [SiteHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteHttpTest.java), [BusinessContextHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/BusinessContextHttpTest.java) | Conforme |
| 4.2 | [Body cerrado de registro de Site](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L22) | [CreateSiteRequestReader.java](../../src/main/java/com/alexastudillo/companyregistry/api/site/CreateSiteRequestReader.java) | [SiteBoundaryTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteBoundaryTest.java), [SiteHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteHttpTest.java) | Conforme |
| 4.3 | [Código y nombre canónicos de Site](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L38) | [SiteRegistrationPolicy.java](../../src/main/java/com/alexastudillo/companyregistry/domain/model/SiteRegistrationPolicy.java) | [SiteRegistrationPolicyTest.java](../../src/test/java/com/alexastudillo/companyregistry/domain/model/SiteRegistrationPolicyTest.java), [SiteHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteHttpTest.java) | Conforme |
| 4.4 | [Tipo efectivo BRANCH por omisión](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L53) | [SiteRegistrationPolicy.java](../../src/main/java/com/alexastudillo/companyregistry/domain/model/SiteRegistrationPolicy.java) | [SiteRegistrationPolicyTest.java](../../src/test/java/com/alexastudillo/companyregistry/domain/model/SiteRegistrationPolicyTest.java), [SiteFingerprintTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/persistence/SiteFingerprintTest.java) | Conforme |
| 4.5 | [Admisión bajo Company propia protegida](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L65) | [RegisterSite.java](../../src/main/java/com/alexastudillo/companyregistry/application/site/RegisterSite.java) | [RegisterSiteTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/persistence/RegisterSiteTest.java), [SiteConcurrencyHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteConcurrencyHttpTest.java) | Conforme |
| 4.6 | [Identidad y auditoría iniciales independientes](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L85) | [SiteRegistration.java](../../src/main/java/com/alexastudillo/companyregistry/application/site/SiteRegistration.java) | [SiteRegistrationTest.java](../../src/test/java/com/alexastudillo/companyregistry/application/site/SiteRegistrationTest.java), [RegisterSiteTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/persistence/RegisterSiteTest.java) | Conforme |
| 4.7 | [Código único por Company incluyendo historial](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L93) | [RegisterSite.java](../../src/main/java/com/alexastudillo/companyregistry/application/site/RegisterSite.java) | [SiteHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteHttpTest.java), [SiteConcurrencyHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteConcurrencyHttpTest.java) | Conforme |
| 4.8 | [Una sede legal no cerrada por Company](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L104) | [RegisterSite.java](../../src/main/java/com/alexastudillo/companyregistry/application/site/RegisterSite.java) | [SiteHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteHttpTest.java), [SiteConcurrencyHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteConcurrencyHttpTest.java) | Conforme |
| 4.9 | [Fingerprint efectivo específico y determinista](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L116) | [SiteCreationFingerprintV1.java](../../src/main/java/com/alexastudillo/companyregistry/infrastructure/persistence/SiteCreationFingerprintV1.java) | [SiteFingerprintTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/persistence/SiteFingerprintTest.java), [SiteConcurrencyHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteConcurrencyHttpTest.java) | Conforme |
| 4.10 | [Procedencia y conservación de aceptación Site](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L128) | [ConfiguredSiteFingerprints.java](../../src/main/java/com/alexastudillo/companyregistry/infrastructure/persistence/ConfiguredSiteFingerprints.java) | [SiteFingerprintTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/persistence/SiteFingerprintTest.java), [SiteResultCodecTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/persistence/SiteResultCodecTest.java), [PackagedSiteIT.java](../../src/integrationTest/java/com/alexastudillo/companyregistry/PackagedSiteIT.java) | Conforme |
| 4.11 | [Replay original previo al estado actual](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L140) | [RegisterSite.java](../../src/main/java/com/alexastudillo/companyregistry/application/site/RegisterSite.java) | [RegisterSiteTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/persistence/RegisterSiteTest.java), [PackagedSiteIT.java](../../src/integrationTest/java/com/alexastudillo/companyregistry/PackagedSiteIT.java) | Conforme |
| 4.12 | [Aceptación local atómica](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L152) | [ReactiveAtomicOperation.java](../../src/main/java/com/alexastudillo/companyregistry/infrastructure/persistence/ReactiveAtomicOperation.java) | [RegisterSiteTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/persistence/RegisterSiteTest.java), [SiteCreatedAtomicTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/outbox/SiteCreatedAtomicTest.java) | Conforme |
| 4.13 | [Resolución durable de registros concurrentes](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L160) | [RegisterSite.java](../../src/main/java/com/alexastudillo/companyregistry/application/site/RegisterSite.java) | [RegisterSiteTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/persistence/RegisterSiteTest.java), [SiteConcurrencyHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteConcurrencyHttpTest.java) | Conforme |
| 4.14 | [Espera finita e incertidumbre explícita](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L180) | [ReactiveAtomicOperation.java](../../src/main/java/com/alexastudillo/companyregistry/infrastructure/persistence/ReactiveAtomicOperation.java) | [SiteBudgetTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/persistence/SiteBudgetTest.java), [RegisterSiteTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/persistence/RegisterSiteTest.java) | Parcial: W1 |
| 4.15 | [Registro independiente de proveedores](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L188) | [RegisterSite.java](../../src/main/java/com/alexastudillo/companyregistry/application/site/RegisterSite.java) | [PackagedSiteIT.java](../../src/integrationTest/java/com/alexastudillo/companyregistry/PackagedSiteIT.java) | Conforme |
| 5.1 | [Contexto obligatorio y consulta local de Site](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L9) | [SiteResource.java](../../src/main/java/com/alexastudillo/companyregistry/api/site/SiteResource.java) | [SiteHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteHttpTest.java), [BusinessContextHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/BusinessContextHttpTest.java), [PackagedSiteIT.java](../../src/integrationTest/java/com/alexastudillo/companyregistry/PackagedSiteIT.java) | Conforme |
| 5.2 | [Representación pública uniforme de Site](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L20) | [SiteResponse.java](../../src/main/java/com/alexastudillo/companyregistry/api/site/SiteResponse.java) | [SiteHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteHttpTest.java), [PackagedSiteIT.java](../../src/integrationTest/java/com/alexastudillo/companyregistry/PackagedSiteIT.java) | Conforme |
| 5.3 | [Resolución del padre dentro del tenant](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L32) | [ReadSites.java](../../src/main/java/com/alexastudillo/companyregistry/application/site/ReadSites.java) | [SiteHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteHttpTest.java) | Conforme |
| 5.4 | [Site calificado por tenant y Company](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L43) | [ReactiveSiteRepository.java](../../src/main/java/com/alexastudillo/companyregistry/infrastructure/persistence/ReactiveSiteRepository.java) | [SiteHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteHttpTest.java) | Conforme |
| 5.5 | [Historial visible sin exclusiones implícitas](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L58) | [ReactiveSiteRepository.java](../../src/main/java/com/alexastudillo/companyregistry/infrastructure/persistence/ReactiveSiteRepository.java) | [SiteHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteHttpTest.java), [PackagedSiteIT.java](../../src/integrationTest/java/com/alexastudillo/companyregistry/PackagedSiteIT.java) | Conforme |
| 5.6 | [Operatividad efectiva calculada al consultar](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L70) | [Site.java](../../src/main/java/com/alexastudillo/companyregistry/domain/model/Site.java) | [SiteRegistrationTest.java](../../src/test/java/com/alexastudillo/companyregistry/application/site/SiteRegistrationTest.java), [SiteReadCoherenceTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/persistence/SiteReadCoherenceTest.java), [PackagedSiteIT.java](../../src/integrationTest/java/com/alexastudillo/companyregistry/PackagedSiteIT.java) | Conforme |
| 5.7 | [Filtros exactos y parámetros cerrados](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L87) | [SiteResource.java](../../src/main/java/com/alexastudillo/companyregistry/api/site/SiteResource.java) | [SiteHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteHttpTest.java) | Conforme |
| 5.8 | [Paginación determinista y acotada de sedes](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L103) | [ReactiveSiteRepository.java](../../src/main/java/com/alexastudillo/companyregistry/infrastructure/persistence/ReactiveSiteRepository.java) | [SiteHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteHttpTest.java), [SiteRegistrationTest.java](../../src/test/java/com/alexastudillo/companyregistry/application/site/SiteRegistrationTest.java) | Prueba pendiente: W2 |
| 5.9 | [Cursor autenticado y exclusivo de la colección Site](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L119) | [AuthenticatedSiteCursors.java](../../src/main/java/com/alexastudillo/companyregistry/infrastructure/persistence/AuthenticatedSiteCursors.java) | [SiteCursorTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/persistence/SiteCursorTest.java), [SiteHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteHttpTest.java) | Conforme |
| 5.10 | [Metadatos de avance en el envelope compartido](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L135) | [ReadSites.java](../../src/main/java/com/alexastudillo/companyregistry/application/site/ReadSites.java) | [SiteHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteHttpTest.java), [PaginationLibraryContractTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/PaginationLibraryContractTest.java) | Conforme |

### Escenarios

La evidencia de cada ID está en la fila del requisito correspondiente.

| ID | Escenario | Resultado |
| --- | --- | --- |
| 1.1.1 | [Fallo inesperado](../../openspec/changes/add-company-site-registration-and-retrieval/specs/business-api-conventions/spec.md#L8) | Evidencia de pruebas del requisito |
| 1.1.2 | [Fallos de framework soportados](../../openspec/changes/add-company-site-registration-and-retrieval/specs/business-api-conventions/spec.md#L13) | Evidencia de pruebas del requisito |
| 1.1.3 | [Causa concreta de HU04 visible al consumidor](../../openspec/changes/add-company-site-registration-and-retrieval/specs/business-api-conventions/spec.md#L17) | Evidencia de pruebas del requisito |
| 1.1.4 | [Compatibilidad de operaciones anteriores](../../openspec/changes/add-company-site-registration-and-retrieval/specs/business-api-conventions/spec.md#L22) | Evidencia de pruebas del requisito |
| 1.2.1 | [Código ocupado](../../openspec/changes/add-company-site-registration-and-retrieval/specs/business-api-conventions/spec.md#L31) | Evidencia de pruebas del requisito |
| 1.2.2 | [Sede legal ocupada](../../openspec/changes/add-company-site-registration-and-retrieval/specs/business-api-conventions/spec.md#L35) | Evidencia de pruebas del requisito |
| 1.2.3 | [Clave aceptada con otra entrada](../../openspec/changes/add-company-site-registration-and-retrieval/specs/business-api-conventions/spec.md#L39) | Evidencia de pruebas del requisito |
| 1.2.4 | [Registro nuevo bajo padre archivado](../../openspec/changes/add-company-site-registration-and-retrieval/specs/business-api-conventions/spec.md#L43) | Evidencia de pruebas del requisito |
| 1.3.1 | [Requeridos y límites canónicos](../../openspec/changes/add-company-site-registration-and-retrieval/specs/business-api-conventions/spec.md#L50) | Evidencia de pruebas del requisito |
| 1.3.2 | [Tipo de sede explícito inválido](../../openspec/changes/add-company-site-registration-and-retrieval/specs/business-api-conventions/spec.md#L55) | Evidencia de pruebas del requisito |
| 1.3.3 | [Tipo JSON no string](../../openspec/changes/add-company-site-registration-and-retrieval/specs/business-api-conventions/spec.md#L59) | Evidencia de pruebas del requisito |
| 1.4.1 | [Ausencia y JSON inválido](../../openspec/changes/add-company-site-registration-and-retrieval/specs/business-api-conventions/spec.md#L66) | Evidencia de pruebas del requisito |
| 1.4.2 | [Raíz y propiedades](../../openspec/changes/add-company-site-registration-and-retrieval/specs/business-api-conventions/spec.md#L70) | Evidencia de pruebas del requisito |
| 1.4.3 | [Varias infracciones de campo](../../openspec/changes/add-company-site-registration-and-retrieval/specs/business-api-conventions/spec.md#L74) | Evidencia de pruebas del requisito |
| 1.5.1 | [Recurso no resoluble en el ámbito](../../openspec/changes/add-company-site-registration-and-retrieval/specs/business-api-conventions/spec.md#L82) | Evidencia de pruebas del requisito |
| 1.5.2 | [UUID de ruta inválido](../../openspec/changes/add-company-site-registration-and-retrieval/specs/business-api-conventions/spec.md#L86) | Evidencia de pruebas del requisito |
| 1.5.3 | [Idempotency-Key no válida](../../openspec/changes/add-company-site-registration-and-retrieval/specs/business-api-conventions/spec.md#L90) | Evidencia de pruebas del requisito |
| 1.6.1 | [Parámetros no admitidos o repetidos](../../openspec/changes/add-company-site-registration-and-retrieval/specs/business-api-conventions/spec.md#L97) | Evidencia de pruebas del requisito |
| 1.6.2 | [Filtros inválidos](../../openspec/changes/add-company-site-registration-and-retrieval/specs/business-api-conventions/spec.md#L101) | Evidencia de pruebas del requisito |
| 1.6.3 | [Límite inválido](../../openspec/changes/add-company-site-registration-and-retrieval/specs/business-api-conventions/spec.md#L105) | Evidencia de pruebas del requisito |
| 1.6.4 | [Cursor inválido o incompatible](../../openspec/changes/add-company-site-registration-and-retrieval/specs/business-api-conventions/spec.md#L110) | Evidencia de pruebas del requisito |
| 2.1.1 | [Idempotencia de Company y Site](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-data-integrity/spec.md#L8) | Evidencia de pruebas del requisito |
| 2.1.2 | [Completado HU01 reproducible](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-data-integrity/spec.md#L13) | Evidencia de pruebas del requisito |
| 2.1.3 | [Completado HU02 reproducible](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-data-integrity/spec.md#L18) | Evidencia de pruebas del requisito |
| 2.1.4 | [Completado HU04 reproducible](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-data-integrity/spec.md#L23) | Evidencia de pruebas del requisito |
| 2.2.1 | [Round-trip y respuesta histórica](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-data-integrity/spec.md#L31) | Evidencia de pruebas del requisito |
| 2.2.2 | [Material ajeno al resultado público](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-data-integrity/spec.md#L36) | Evidencia de pruebas del requisito |
| 2.3.1 | [Instalación limpia y upgrade](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-data-integrity/spec.md#L45) | Evidencia de pruebas del requisito |
| 2.3.2 | [Aceptación histórica incompatible](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-data-integrity/spec.md#L50) | Evidencia de pruebas del requisito |
| 2.4.1 | [Metadatos obligatorios inválidos](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-data-integrity/spec.md#L58) | Evidencia de pruebas del requisito |
| 2.4.2 | [Referencias y contenido incompatibles](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-data-integrity/spec.md#L62) | Evidencia de pruebas del requisito |
| 2.5.1 | [Round-trip y estado posterior](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-data-integrity/spec.md#L69) | Evidencia de pruebas del requisito |
| 2.5.2 | [Snapshot corrupto o ajeno al propietario](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-data-integrity/spec.md#L74) | Evidencia de pruebas del requisito |
| 2.6.1 | [Índice y restricciones instalados](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-data-integrity/spec.md#L81) | Evidencia de pruebas del requisito |
| 2.7.1 | [Reversión después de aceptar sedes](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-data-integrity/spec.md#L89) | Evidencia de pruebas del requisito |
| 3.1.1 | [Identidad y versiones del evento](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-created-event/spec.md#L12) | Evidencia de pruebas del requisito |
| 3.2.1 | [Campos e invariantes del payload](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-created-event/spec.md#L20) | Evidencia de pruebas del requisito |
| 3.2.2 | [Datos fuera del contrato](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-created-event/spec.md#L25) | Evidencia de pruebas del requisito |
| 3.3.1 | [Correlación y causalidad HTTP](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-created-event/spec.md#L33) | Evidencia de pruebas del requisito |
| 3.3.2 | [Reintento bajo otro actor o proceso](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-created-event/spec.md#L38) | Evidencia de pruebas del requisito |
| 3.4.1 | [Modos aprobados sin broker](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-created-event/spec.md#L45) | Evidencia de pruebas del requisito |
| 3.4.2 | [Fallo de evento o pérdida de carrera](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-created-event/spec.md#L50) | Evidencia de pruebas del requisito |
| 3.5.1 | [Mutación o cierre posterior](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-created-event/spec.md#L57) | Evidencia de pruebas del requisito |
| 3.5.2 | [Replay tras cambio de modo](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-created-event/spec.md#L61) | Evidencia de pruebas del requisito |
| 3.6.1 | [Verificación independiente de publicación](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-created-event/spec.md#L68) | Evidencia de pruebas del requisito |
| 4.1.1 | [Contexto y clave válidos](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L12) | Evidencia de pruebas del requisito |
| 4.1.2 | [Clave inválida o contexto incompleto](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L17) | Evidencia de pruebas del requisito |
| 4.2.1 | [Body ausente o malformado](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L25) | Evidencia de pruebas del requisito |
| 4.2.2 | [Campos administrados o desconocidos](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L29) | Evidencia de pruebas del requisito |
| 4.2.3 | [Propiedad duplicada o tipo incompatible](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L34) | Evidencia de pruebas del requisito |
| 4.3.1 | [Canonización y expansión Unicode](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L41) | Evidencia de pruebas del requisito |
| 4.3.2 | [Código requerido o demasiado largo](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L45) | Evidencia de pruebas del requisito |
| 4.3.3 | [Nombre requerido o demasiado largo](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L49) | Evidencia de pruebas del requisito |
| 4.4.1 | [Omisión equivalente a BRANCH explícito](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L56) | Evidencia de pruebas del requisito |
| 4.4.2 | [Null o tipo no soportado](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L60) | Evidencia de pruebas del requisito |
| 4.5.1 | [Estados de Company admitidos](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L68) | Evidencia de pruebas del requisito |
| 4.5.2 | [Company inexistente o ajena](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L72) | Evidencia de pruebas del requisito |
| 4.5.3 | [Company archivada](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L76) | Evidencia de pruebas del requisito |
| 4.5.4 | [Carrera con archivado](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L80) | Evidencia de pruebas del requisito |
| 4.6.1 | [Aceptación inicial](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L88) | Evidencia de pruebas del requisito |
| 4.7.1 | [Duplicado canónico e histórico](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L96) | Evidencia de pruebas del requisito |
| 4.7.2 | [Reutilización entre Companies](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L100) | Evidencia de pruebas del requisito |
| 4.8.1 | [Sede legal existente](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L107) | Evidencia de pruebas del requisito |
| 4.8.2 | [Reemplazo después del cierre](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L111) | Evidencia de pruebas del requisito |
| 4.9.1 | [Entrada equivalente](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L119) | Evidencia de pruebas del requisito |
| 4.9.2 | [Cambio significativo o de Company](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L123) | Evidencia de pruebas del requisito |
| 4.10.1 | [Rotación y reinicio](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L131) | Evidencia de pruebas del requisito |
| 4.10.2 | [Material histórico desconocido o corrupto](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L135) | Evidencia de pruebas del requisito |
| 4.11.1 | [Estado posterior archivado o cerrado](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L143) | Evidencia de pruebas del requisito |
| 4.11.2 | [Cambio de modo outbox](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L148) | Evidencia de pruebas del requisito |
| 4.12.1 | [Fallo del completado o evento](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L155) | Evidencia de pruebas del requisito |
| 4.13.1 | [Misma clave equivalente en paralelo](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L163) | Evidencia de pruebas del requisito |
| 4.13.2 | [Misma clave para distintas Companies](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L168) | Evidencia de pruebas del requisito |
| 4.13.3 | [Distintas claves para la misma sede legal](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L172) | Evidencia de pruebas del requisito |
| 4.13.4 | [Competidor que revierte](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L176) | Evidencia de pruebas del requisito |
| 4.14.1 | [Timeout o commit incierto](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L183) | Parcial: W1 |
| 4.15.1 | [Proveedores caídos](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L191) | Evidencia de pruebas del requisito |
| 5.1.1 | [Consulta independiente de proveedores](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L12) | Evidencia de pruebas del requisito |
| 5.1.2 | [Contexto inválido](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L16) | Evidencia de pruebas del requisito |
| 5.2.1 | [Estructura y tipos de la respuesta](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L23) | Evidencia de pruebas del requisito |
| 5.2.2 | [Colección homogénea](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L28) | Evidencia de pruebas del requisito |
| 5.3.1 | [Padre inexistente o de otro tenant](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L35) | Evidencia de pruebas del requisito |
| 5.3.2 | [Company propia sin coincidencias](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L39) | Evidencia de pruebas del requisito |
| 5.4.1 | [Consulta propia](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L46) | Evidencia de pruebas del requisito |
| 5.4.2 | [Site ausente o fuera del ámbito](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L50) | Evidencia de pruebas del requisito |
| 5.4.3 | [Identificador malformado](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L54) | Evidencia de pruebas del requisito |
| 5.5.1 | [Sede cerrada y padre archivado](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L61) | Evidencia de pruebas del requisito |
| 5.5.2 | [Listado completo por defecto](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L66) | Evidencia de pruebas del requisito |
| 5.6.1 | [Combinaciones de estados](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L73) | Evidencia de pruebas del requisito |
| 5.6.2 | [Filtro de estado propio](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L78) | Evidencia de pruebas del requisito |
| 5.6.3 | [Consulta posterior frente a replay](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L83) | Evidencia de pruebas del requisito |
| 5.7.1 | [Código canónico y filtros combinados](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L90) | Evidencia de pruebas del requisito |
| 5.7.2 | [Filtros sin coincidencia conjunta](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L95) | Evidencia de pruebas del requisito |
| 5.7.3 | [Parámetro inválido o no admitido](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L99) | Evidencia de pruebas del requisito |
| 5.8.1 | [Desempate por identificador](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L106) | Evidencia de pruebas del requisito |
| 5.8.2 | [Límites de página](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L110) | Evidencia de pruebas del requisito |
| 5.8.3 | [Cambios entre páginas](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L115) | Sin prueba específica: W2 |
| 5.9.1 | [Cursor de otro ámbito o colección](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L122) | Evidencia de pruebas del requisito |
| 5.9.2 | [Filtros equivalentes](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L126) | Evidencia de pruebas del requisito |
| 5.9.3 | [Integridad o formato inválido](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L131) | Evidencia de pruebas del requisito |
| 5.10.1 | [Página intermedia o final](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L138) | Evidencia de pruebas del requisito |

## Evaluación final

Sin hallazgos críticos. **Dos advertencias y cero sugerencias**; todos los controles
aplicables fueron revisados. Listo para archivar con las observaciones indicadas,
según el criterio consultivo de la skill. Se recomienda resolver W1 y añadir la
prueba W2 antes de afirmar cumplimiento completo del diseño y las especificaciones.
No se archivó el cambio ni se corrigió la implementación durante esta verificación.
