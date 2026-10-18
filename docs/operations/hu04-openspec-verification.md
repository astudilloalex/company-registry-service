# Verificación OpenSpec — HU04, issue #6

Cambio: `add-company-site-registration-and-retrieval`, esquema `spec-driven`.
Reverificación: 2026-10-17, America/Guayaquil. Se contrastaron propuesta, diseño,
cinco deltas, tareas, fuentes, pruebas y artefactos ejecutados después de corregir W1/W2.

| Dimensión | Resultado |
| --- | --- |
| Completitud | 35/35 tareas; implementación y evidencia para 44/44 requisitos |
| Corrección | 44/44 requisitos conformes en la revisión; 101/101 escenarios con evidencia |
| Coherencia | Las nueve decisiones del diseño y los patrones del proyecto se siguen |

41 requisitos ADDED y tres MODIFIED; sin REMOVED ni RENAMED. Todos los controles
aplicables se revisaron; no hay controles omitidos ni pendientes de verificación.
Las cuatro exclusiones de fixtures en cada ejecución de integración pertenecen
al artefacto de verificación, no a rutas ni escenarios productivos HU04 omitidos.

## Hallazgos

**CRITICAL: 0. WARNING: 0. SUGGESTION: 0.**

**W1 corregida.** `RegisterSite.execute` impone un deadline desde la primera
búsqueda hasta la finalización/recuperación y rechaza resultados tardíos.
`ReactiveAtomicOperation` limita la fase completa, reserva tiempo para rollback,
señala cancelación en el contexto propietario y acota la espera de limpieza.
`PersistenceFailure.finalizationUncertain` impide recuperar en una transacción
cuyo cierre todavía no se confirmó. Registro/actualización de Company conservan
el mismo protocolo seguro. El plazo es de espera local: un 503 por commit incierto
no afirma rollback; el mismo key permite resolver la aceptación durable posterior.
Los timers respetan la granularidad de 1 ms de Vert.x.

Pruebas: [SiteDeadlineTest.java](../../src/test/java/com/alexastudillo/companyregistry/application/site/SiteDeadlineTest.java),
[SiteBudgetTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/persistence/SiteBudgetTest.java)
y la prueba de COMMIT real del harness de imagen nativa. Cubren adquisición,
limpieza pendiente, presupuesto submilisegundo, recuperación sin respuesta,
finalización no confirmada, rollback de escritura inconclusa y replay tras commit.

**W2 corregida.**
[SiteHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteHttpTest.java)
obtiene el cursor y luego inserta sedes por encima y debajo de su frontera,
cambia estados dentro/fuera del filtro y verifica los filtros actuales con AND,
la pertenencia a Company y la continuación con otro tamaño de página. La nueva
fila anterior a la frontera aparece en una consulta inicial nueva, mientras la
continuación usa su frontera original. No se promete un snapshot congelado.

## Evidencia final

- Regresión JVM: **263 pruebas**, 78 suites, cero fallos/errores/omisiones.
- Proceso JVM productivo: **7 pruebas ejecutadas**, cero fallos/errores y cuatro
  exclusiones de fixtures; `build` y `verifyProductionIsolation` pasaron.
- Proceso nativo productivo: **7 pruebas ejecutadas**, cero fallos/errores y las
  mismas cuatro exclusiones de fixtures. Binario reconstruido con las fuentes finales.
- Imagen nativa UID 1001: tres modos, reinicio/rotación/replay y commit real
  bloqueado, 503 y mismo-key retry con conteos exactos.
- Runner SHA-256: `afe00651f69f2d82e1517a091773c9bdb2f009aa93040caf044b3d200b321c3b`. Image ID: `2aff72f06e8651d16ec633cbf9beb91a24a2b718244faf3b33863285653ecf65`.
- V1–V3 intactas; sin cambios de políticas/codecs históricos Company, sin TTL,
  publisher HU08 ni rutas de ciclo de vida HU03/HU05.
- Manifiesto de 147 archivos productivos/configuración: las fuentes finales
  coinciden con las que pasaron la regresión JVM.
- `openspec validate --strict`: válido; 35/35 tareas contrastadas con fuentes/evidencia.

| Modo de imagen | Tiempo observado con presupuesto 2 s | Resultado | Sites/completados/eventos tras reintento |
| --- | --- | --- | --- |
| disabled | 2.003 s | 503 | 1|1|0 |
| stored-only | 2.004 s | 503 | 1|1|1 |
| published | 2.003 s | 503 | 1|1|1 |

XML, JSON y logs aceptados: `build/hu04-evidence/fix-final-jvm/`,
`fix-final-native/`, `fix-final-oci/`. Comandos y detalles en
[hu04-acceptance-review.md](hu04-acceptance-review.md). Los diagnósticos SQL
negativos y el rollback defensivo del COMMIT forzado son parte de las pruebas;
se comprueba la reutilización de clave/recursos y no se observan errores no
capturados de timers o de contexto en la ejecución JVM aceptada.

## Coherencia

Se conserva la separación Domain/Application/API/Infrastructure, agregado Site,
entrada original y política v1, replay primero, bloqueo Company para todos los
tipos, segunda consulta de aceptación, auditoría independiente y recuperación
tras cierre confirmado. Fingerprints y snapshots históricos no cambian.
El join escalar, cursor HMAC separado, limit+1 y frontera completa permanecen.
Evento/API siguen cerrados y reutilizan outbox/mapper compartidos sin proveedores
ni publicación. La decisión 5 documenta ahora el deadline integral y la señal
neutral de finalización; la estrategia de pruebas incluye cambios entre páginas.

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
| 4.14 | [Espera finita e incertidumbre explícita](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L180) | [ReactiveAtomicOperation.java](../../src/main/java/com/alexastudillo/companyregistry/infrastructure/persistence/ReactiveAtomicOperation.java) | [SiteBudgetTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/persistence/SiteBudgetTest.java), [RegisterSiteTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/persistence/RegisterSiteTest.java), [SiteDeadlineTest.java](../../src/test/java/com/alexastudillo/companyregistry/application/site/SiteDeadlineTest.java) | Conforme |
| 4.15 | [Registro independiente de proveedores](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L188) | [RegisterSite.java](../../src/main/java/com/alexastudillo/companyregistry/application/site/RegisterSite.java) | [PackagedSiteIT.java](../../src/integrationTest/java/com/alexastudillo/companyregistry/PackagedSiteIT.java) | Conforme |
| 5.1 | [Contexto obligatorio y consulta local de Site](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L9) | [SiteResource.java](../../src/main/java/com/alexastudillo/companyregistry/api/site/SiteResource.java) | [SiteHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteHttpTest.java), [BusinessContextHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/BusinessContextHttpTest.java), [PackagedSiteIT.java](../../src/integrationTest/java/com/alexastudillo/companyregistry/PackagedSiteIT.java) | Conforme |
| 5.2 | [Representación pública uniforme de Site](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L20) | [SiteResponse.java](../../src/main/java/com/alexastudillo/companyregistry/api/site/SiteResponse.java) | [SiteHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteHttpTest.java), [PackagedSiteIT.java](../../src/integrationTest/java/com/alexastudillo/companyregistry/PackagedSiteIT.java) | Conforme |
| 5.3 | [Resolución del padre dentro del tenant](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L32) | [ReadSites.java](../../src/main/java/com/alexastudillo/companyregistry/application/site/ReadSites.java) | [SiteHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteHttpTest.java) | Conforme |
| 5.4 | [Site calificado por tenant y Company](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L43) | [ReactiveSiteRepository.java](../../src/main/java/com/alexastudillo/companyregistry/infrastructure/persistence/ReactiveSiteRepository.java) | [SiteHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteHttpTest.java) | Conforme |
| 5.5 | [Historial visible sin exclusiones implícitas](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L58) | [ReactiveSiteRepository.java](../../src/main/java/com/alexastudillo/companyregistry/infrastructure/persistence/ReactiveSiteRepository.java) | [SiteHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteHttpTest.java), [PackagedSiteIT.java](../../src/integrationTest/java/com/alexastudillo/companyregistry/PackagedSiteIT.java) | Conforme |
| 5.6 | [Operatividad efectiva calculada al consultar](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L70) | [Site.java](../../src/main/java/com/alexastudillo/companyregistry/domain/model/Site.java) | [SiteRegistrationTest.java](../../src/test/java/com/alexastudillo/companyregistry/application/site/SiteRegistrationTest.java), [SiteReadCoherenceTest.java](../../src/test/java/com/alexastudillo/companyregistry/infrastructure/persistence/SiteReadCoherenceTest.java), [PackagedSiteIT.java](../../src/integrationTest/java/com/alexastudillo/companyregistry/PackagedSiteIT.java) | Conforme |
| 5.7 | [Filtros exactos y parámetros cerrados](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L87) | [SiteResource.java](../../src/main/java/com/alexastudillo/companyregistry/api/site/SiteResource.java) | [SiteHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteHttpTest.java) | Conforme |
| 5.8 | [Paginación determinista y acotada de sedes](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L103) | [ReactiveSiteRepository.java](../../src/main/java/com/alexastudillo/companyregistry/infrastructure/persistence/ReactiveSiteRepository.java) | [SiteHttpTest.java](../../src/test/java/com/alexastudillo/companyregistry/api/site/SiteHttpTest.java), [SiteRegistrationTest.java](../../src/test/java/com/alexastudillo/companyregistry/application/site/SiteRegistrationTest.java) | Conforme |
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
| 4.14.1 | [Timeout o commit incierto](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-registration/spec.md#L183) | Conforme |
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
| 5.8.3 | [Cambios entre páginas](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L115) | Evidencia de pruebas del requisito |
| 5.9.1 | [Cursor de otro ámbito o colección](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L122) | Evidencia de pruebas del requisito |
| 5.9.2 | [Filtros equivalentes](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L126) | Evidencia de pruebas del requisito |
| 5.9.3 | [Integridad o formato inválido](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L131) | Evidencia de pruebas del requisito |
| 5.10.1 | [Página intermedia o final](../../openspec/changes/add-company-site-registration-and-retrieval/specs/company-site-retrieval/spec.md#L138) | Evidencia de pruebas del requisito |


## Evaluación final

Todas las comprobaciones pasaron. Sin críticos, advertencias ni sugerencias.
Listo para archivar. Esta verificación no archiva el cambio.
