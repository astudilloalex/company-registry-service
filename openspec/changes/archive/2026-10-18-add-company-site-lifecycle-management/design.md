# Design

## Context

La motivación y el alcance están en [proposal.md](proposal.md). Este documento concreta la implementación futura; este cambio no modifica código, migraciones, contratos publicados ni especificaciones principales.

La base inspeccionada ya incluye Site/SiteStatus/SiteType/AggregateVersion, `Site.operationalUnder`, políticas de normalización y la matriz completa de transiciones. `SiteRepository` y `ReactiveSiteRepository` todavía no ofrecen bloqueo ni actualización de Site. `SiteEntity` tiene `@Version`, pero la escritura de Company existente emplea UPDATE condicionado por versión para evitar incrementos dobles. V1 impone propiedad compuesta, código único por Company, máximo un HEADQUARTERS no CLOSED e inmutabilidad de Site CLOSED; no garantiza por sí sola que Company ACTIVE conserve una sede legal ACTIVE.

HU04 aporta `RegisterSite`, `ReadSites`, DTO de doce campos, bloqueo Company mediante `CompanyRepository.findForUpdate`, keyring HMAC y codec `SiteResultCodec`. Este último está restringido a creación DRAFT/0/false y no debe ampliarse para reinterpretar aceptaciones HU05. HU02 aporta PATCH con presencia explícita, aceptación opcional y recuperación posterior a rollback. `AtomicOperation`, `ReactiveAtomicOperation` y `DatabaseBudget` proporcionan límites de espera, checkpoints y traducción de fallos técnicos.

Las migraciones actuales llegan a V4. El catálogo SQL del outbox ya admite updated/closed de COMPANY_SITE y revisiones positivas; faltan sus productores y esquemas. Gradle empaqueta los contratos y separa producción de fuentes auxiliares de verificación. HU03, HU06 y HU07 no tienen aún endpoints productivos; su coordinación se prueba mediante escritores auxiliares compatibles, no implementándolos dentro de HU05.

## Goals / Non-Goals

**Goals:** implementar políticas puras y casos de uso significativos sobre los puertos existentes; dar un punto local de aceptación protegido y reproducible; preservar contratos HU01/HU02/HU04 y su material histórico; hacer comprobables las carreras de estado, código, sede legal y clave idempotente.

**Non-Goals:** un framework CRUD/command bus genérico, acoplar revisiones Company/Site, ejecutar I/O externo bajo locks, añadir tablas de alias de códigos, cambiar el mapper global, crear autenticación nueva, publicar al broker o desarrollar endpoints de historias posteriores. El contexto confiable existente sigue definiendo el acceso interno.

## Decisions

### 1. Separar política de información, política de transición y aceptación local

Domain reutilizará SiteStatus y BusinessText y añadirá intención parcial y políticas puras de información/elegibilidad/sede legal. Recibirá valores de dominio; no tendrá Mutiny, JSON, SQL, ORM o HTTP. La omisión de siteType en PATCH conserva el valor; la política de creación que aplica BRANCH por omisión permanece congelada.

Application incorporará un caso de uso de actualización y otro de comandos de ciclo de vida en `application/site`, con comando/resultado neutrales, operación explícita y fallos diferenciados. Ambos compartirán solo la coordinación local que resulte necesaria, usando puertos de persistencia, firma, resultados y eventos con responsabilidades concretas. Los nombres de tipos nuevos son detalles ajustables; los contratos de operación/política/snapshot no lo son.

API traducirá ruta, headers y documentos a esas intenciones y mapeará el resultado a SiteResponse. Infrastructure implementará I/O y composición CDI. Se conservarán las reglas de ArchitectureTest, las sesiones secuenciales y la independencia de agregados. Alternativa descartada: un único modelo nullable para creación y PATCH mezclaría ausencia, null y defaults; un motor genérico añadiría abstracciones sin necesidad demostrada.

### 2. Entrada cerrada con preservación de intención

El lector PATCH conservará presencia y strings originales hasta seleccionar la política de aceptación vigente o histórica. Admitirá solo code/name/siteType. Los comandos tendrán una frontera de lectura que consuma y valide el documento completo: ausencia o `{}` producen la misma intención vacía; null/array/escalar, campos, duplicados o tokens posteriores se rechazan. No se ignorará un body porque el método REST no reciba parámetros.

Las cabeceras se validarán incluso para replay: contexto existente, UUIDs de ruta, If-Match único decimal int64 y clave opcional exacta de hasta 128 puntos de código. La convención decimal interna no es un ETag HTTP. Se preservan códigos y comportamientos anteriores fuera de HU05.

El lector detectará estructura y tipos sin canonización destructiva. La política seleccionada valida y canoniza los campos efectivos antes de aceptar/devolver replay; una futura política no reinterpretará huellas históricas. Dentro del documento se respetará JSON inválido/tokens → raíz → desconocidos → duplicados → code/name/siteType. No se promete reordenar diagnósticos que el framework deba producir antes de entrar al recurso, como media type 415. Alternativas descartadas: ignorar body de comandos admite campos administrados silenciosamente; normalizar globalmente el body destruye la entrada necesaria para una política histórica.

### 3. Toda aceptación nueva sigue Company antes de Site

La estrategia elegida usa PESSIMISTIC_WRITE de la Company calificada y después del Site calificado para las cuatro operaciones y los no-op. Reutiliza el lock Company de HU04 y añade `SiteRepository.findForUpdate(tenant, company, site)`. La Company cargada no se modifica ni se envía a update. No se exige If-Match de Company ni se bloquean otras sedes innecesariamente.

```text
Validar frontera y conservar intencion
       |
       v
Buscar completado si hay clave
       +--> Coincide --> 200 original
       +--> Otra intencion --> 409 de clave
       |
       v
BEGIN
  Bloquear Company calificada
  Reconsultar completado si hay clave --> replay/conflicto si aparecio
  Resolver Company propia o 404
  Bloquear Site calificado o 404
  Comprobar version Site o 412
  Comprobar Company ARCHIVED, Site CLOSED y comando
  Decidir resultado y comprobar invariantes/unicidad
  UPDATE Site solo si hay cambio efectivo
  Guardar completado si hay clave, incluido no-op
  Guardar un evento habilitado solo si hay cambio
COMMIT --> 200 con resultado aceptado
```

La reconsulta de completado precede a evaluar la ausencia/estado actual, incluida una aceptación que pudo aparecer durante la espera. Ninguna decisión de estado se toma con una lectura previa no protegida. Propiedad precede a versión y estados: un Site fuera de Company devuelve site-not-found aun si el padre propio está ARCHIVED.

Bloquear solo HEADQUARTERS no cubriría la regla adoptada que prohíbe cualquier mutación bajo ARCHIVED. Bloqueos compartidos del padre para algunas operaciones permitirían más paralelismo pero exigirían coordinar reclasificaciones y modos de bloqueo; se difieren. Se acepta serialización de escrituras bajo una Company para V1. GET conserva consultas ordinarias sin locks de escritura.

### 4. Sede legal, unicidad y liberación de código

Con Company y Site protegidos, la política comprobará versión, padre ARCHIVED, Site CLOSED, elegibilidad y protección de sede legal. activate de ACTIVE o deactivate de INACTIVE devuelve site-already-in-requested-state; close de CLOSED devuelve site-closed por precedencia terminal. DRAFT no admite deactivate. El no-op exitoso queda limitado a PATCH editable.

La versión V1 tiene como máximo un HEADQUARTERS no CLOSED: DRAFT e INACTIVE también ocupan la reserva. No se podrá cerrar, desactivar o reclasificar la sede legal ACTIVE de Company ACTIVE. HU03 deberá bloquear Company antes de comprobar/bloquear la sede legal y revalidar antes de activar Company; así, activación y retirada de sede legal se excluyen. Esta garantía se refiere a escritores que cumplen el protocolo, no a impedir SQL privilegiado arbitrario.

Los diagnósticos de código y reserva HEADQUARTERS se limitarán a tenant/Company y excluirán el propio Site. El índice SQL seguirá arbitrando escritores concurrentes. Si un PATCH cambia code y siteType, tras elegibilidad/protección se comprobará primero código y luego otra reserva HEADQUARTERS; una colisión SQL conocida conserva su causa real. `companies.occupied` no sirve para estas comprobaciones.

El UPDATE confirmado de code A a B libera A automáticamente; no existe tabla de alias. CLOSED mantiene su código porque rechaza cambios ordinarios. La sustitución legal V1 requiere Company INACTIVE, cerrar la sede anterior, registrar otra con código libre, activar Site y reactivar Company mediante HU03. Alternativas descartadas: reservar todos los códigos anteriores ampliaría el modelo con historial no solicitado; reemplazar atómicamente sede legal ampliaría la historia.

### 5. Escritura condicionada y una sola revisión

La futura actualización de `ReactiveSiteRepository` usará un único UPDATE con tenantId/companyId/siteId y versión esperada como predicados. Solo podrá escribir code, name, siteType, status, updatedAt, updatedBy y version=version+1. El resultado contendrá exactamente la revisión esperada más uno; se rechazará un incremento fuera de int64 sin wraparound ni escrituras parciales. Cero filas por versión se traducirá a STALE_VERSION.

Se seguirá el precedente Company de bulk HQL/SQL compatible con las restricciones y enums actuales, evitando mutar además la entidad gestionada: `@Version` no debe sumar una segunda vez al flush. No-op no emitirá UPDATE Site. Se capturará un instante UTC truncado a microsegundos al aceptar; respuesta, snapshot y evento de cambio usarán el mismo instante y actor. La fecha/actor de aceptación de un no-op pertenecen al completado y pueden diferir de la auditoría Site intacta.

HU06/HU07 deberán usar el mismo predicado y revisión del Site al mutar hijos. Se comprobará su compatibilidad mediante escritores de prueba que actualicen hijo y Site en una transacción Company → Site. Alternativa descartada: confiar solo en un SELECT previo o actualizar Site y luego hijos sin un único commit permite actualizaciones perdidas y revisiones incoherentes.

### 6. Cuatro ámbitos idempotentes y huella de intención

| Acción | Operación persistida | Intención firmada adicional |
| --- | --- | --- |
| PATCH | company.site.update.v1 | Presencia y valor canónico de code/name/siteType |
| activate | company.site.activate.v1 | Objeto vacío |
| deactivate | company.site.deactivate.v1 | Objeto vacío |
| close | company.site.close.v1 | Objeto vacío |

Se usará el keyring existente y una política HU05 versión 1 con documento JSON UTF-8 compacto y orden fijo: fingerprintVersion, tenantId, operation, companyId, siteId, expectedVersion y request. UUIDs se codifican canónicamente, expectedVersion como string decimal y request usa orden code/name/siteType para PATCH. Cada campo tiene present booleano y, cuando está presente, value canónico; los tres null son inválidos. Para comandos request es `{}` tanto sin body como con objeto vacío.

Actor, Process-Id, clave, estados y datos actuales, fechas generadas y modo outbox no participan. El recurso está en huella y completado, no añade una dimensión a la PK tenant/operación/clave. Por tanto, cambiar Site/Company o If-Match bajo la misma clave/operación es conflicto; la misma cadena en otra operación/tenant es independiente. `{}` y un campo explícito equivalente al actual siguen siendo intenciones distintas.

Una firma se verifica con política y keyId históricos y comparación constante de MAC; se retendrán sin TTL. No se cambia el encoding de HU01/HU02/HU04. Alternativas descartadas: un ámbito compartido para los cuatro comandos mezcla aceptaciones; firmar el resultado actual hace que cambios posteriores alteren la equivalencia; firmar JSON crudo confunde orden/presentación con intención.

### 7. Snapshot propio de HU05 y respuesta uniforme

Las cuatro operaciones devolverán 200 successful con SiteResponse completo. Nueva aceptación calcula effectivelyOperational desde Company bloqueada y Site resultante; GET conserva el join local actual de HU04. El replay reproduce datos originales, incluido el booleano, aunque Company se haya activado/desactivado o archivado. Process-Id de respuesta pertenece al intento actual y no forma parte del snapshot de negocio.

Habrá un codec HU05 versión 1 independiente de `SiteResultCodec` de creación. Se conservarán SiteRecord, booleano original y metadatos internos de propiedad necesarios. Como decisión técnica, se incluirá el status de Company observado al aceptar dentro del snapshot interno para verificar el booleano sin reconsultar Company; no será campo público ni columna de Company/Site. Se validarán shape cerrado, tipos estrictos, UTC/microsegundos, propiedad contra completado, operación y status/revisión resultantes.

PATCH puede conservar la versión original en no-op o avanzar uno; activate/deactivate/close deben avanzar uno y resultar ACTIVE/INACTIVE/CLOSED respectivamente. Un resultado no-op no debe exigirse con updatedBy igual al actor del completado ni updatedAt igual a su createdAt. Las aceptaciones conservan su propia procedencia de política/keyId, expectedVersion y status 200. Falta de clave/política, formato desconocido o corrupción da 500 saneado, sin reparar snapshots o ejecutar otra mutación.

Alternativas descartadas: ampliar el codec DRAFT/0 de HU04 rompe su contrato histórico; recalcular operatividad en replay modifica la aceptación original; devolver 204 obliga al cliente a consultar otra vez para conocer la versión y pierde el contrato de resultado completo adoptado.

### 8. Atomicidad, recuperación y presupuesto único

`AtomicOperation` mantendrá cambio Site, completado opcional y evento habilitado en una transacción secuencial. Un no-op con clave solo inserta completado; sin clave no lo inserta. Rechazos no se guardan como aceptaciones. Una colisión de completado entre Companies puede ocurrir después de modificar Site provisionalmente: el perdedor debe revertir todos sus efectos.

Solo después de rollback/finalización y cierre confirmados se abrirá una sesión nueva en la base primaria para resolver aceptación durable. Se buscará primero el completado: coincidencia devuelve replay y diferencia conflicto de clave, antes de traducir revisión o unicidad observadas. Sin completado, causas conocidas SITE_CODE/SITE_HEADQUARTERS conservan sus 409; revisión obsoleta confirmada conserva 412; indisponibilidad o incertidumbre da 503. Unicidades desconocidas e incoherencias internas se sanean como 500. No se infiere una aceptación de datos parecidos ni se reintenta automáticamente la escritura, con o sin clave.

Se añadirá `company-registry.site-mutation.wait-budget`, variable `COMPANY_SITE_MUTATION_WAIT_BUDGET`, valor inicial 10s positivo/finito. Es un detalle técnico derivado de HU04. Un presupuesto único cubre búsqueda, conexión, locks, escritura, commit, limpieza y recuperación. Checkpoints aplican statement_timeout/lock_timeout restantes y el deadline de suscripción incluye adquisición/finalización; se conserva la reserva de rollback existente (hasta 100 ms y como máximo 10% del remanente).

No se recupera en sesión fallida ni mientras su finalización sea incierta. Cancelación vuelve al contexto Vert.x propietario y no crea una escritura suscrita por separado. Un timeout no prueba rollback de un commit remoto: 503 permite un nuevo intento con la misma clave/entrada. Se reutilizan las categorías transitorias existentes de deadlock/serialización. La alternativa de reiniciar el plazo por fase haría que la espera total dejase de estar acotada.

### 9. Eventos mínimos y selección única

Los contratos normativos están en [company-site-updated-event](specs/company-site-updated-event/spec.md) y [company-site-closed-event](specs/company-site-closed-event/spec.md). Productores neutrales recibirán el resultado aceptado y contexto original; Infrastructure lo mapeará al outbox usando OutboxPolicy. El payload tendrá ocho campos y el envelope once, con revisión Site resultante positiva, esquema 1, UUIDv7 distinto y causationId null HTTP.

PATCH efectivo, activate y deactivate producirán updated; close producirá solo closed. No-op/replay producen cero. updated admite DRAFT/ACTIVE/INACTIVE y closed exige CLOSED. HU06/HU07 podrán emitir updated incrementando revisión/auditoría aunque los otros seis campos del payload no cambien. No se añade un changeType/changedFields ni datos de hijos o effectivelyOperational. Un consumidor que necesite esos detalles consulta el servicio; este contrato no permite reconstruir el historial de direcciones/contactos.

Se crearán esquemas Draft 2020-12 cerrados y ejemplos para ambos eventos bajo docs/contracts/events, validando también igualdad identidad/fechas, revisión y actor fuera del esquema cuando sean invariantes cruzadas. HU08 recibirá hechos originales con exchange company.events y routing keys específicas; stored-only/published guardan PENDING y no activan publicación. El almacenamiento conserva el hecho aunque cambien agregados o modo outbox. La alternativa de serializar SiteResponse completo filtraría una proyección dependiente de Company y acoplaría eventos a la API de consulta.

### 10. Errores propios y compatibilidad HTTP

La delta [business-api-conventions](specs/business-api-conventions/spec.md) fija el catálogo exacto y su precedencia. El API usará fallos neutrales diferenciados y ApiResponseException con ResponseManager/mapper compartidos. No se sustituirán motivos por resource-conflict o validation-error genéricos. Los nuevos códigos de If-Match son if-match-required, if-match-duplicated e if-match-invalid; los anteriores endpoints mantienen sus errores publicados.

OpenAPI incorporará PATCH y tres POST con If-Match obligatorio, clave opcional, body PATCH cerrado y comandos sin parámetros, respuestas Site completas y errores de 400/404/409/412/415/500/503. Los bodies de error HU05 conocidos tendrán únicamente status/code. Los doce campos Site serán requeridos. Health/OpenAPI mantienen su excepción de contexto; no se añaden campos de Company o datos de hijos.

### 11. Evidencia y coordinación con historias posteriores

Las pruebas concurrentes usarán peticiones o conexiones/transacciones independientes y barreras observables, con plazos; una sesión reactiva nunca ejecutará operaciones paralelas. Se verificarán ambos órdenes de carrera entre mutación y archivado, retirada de sede legal y activación Company, dos reclasificaciones a HEADQUARTERS, códigos y aceptaciones. Los escritores auxiliares seguirán Company → Site y representarán solo la parte local del protocolo HU03/HU06/HU07; permanecerán fuera del artefacto productivo. Si esos endpoints ya existen al aplicar, se usará además su integración real.

| AC de issue #7 | Escenarios/evidencia de aceptación requerida |
| --- | --- |
| AC01 | PATCH parcial y campos permitidos con versión vigente, omisión de siteType conserva |
| AC02 | Identidad/propiedad/auditoría administrada rechazada aunque coincida, FKs y snapshots calificados |
| AC03 | Mayúsculas, strip, acentos/espacios y expansión Unicode antes de límites/huella |
| AC04 | DRAFT/INACTIVE → ACTIVE bajo los tres estados Company editables y rechazo ARCHIVED |
| AC05 | Company INACTIVE/Site ACTIVE conserva estado propio y operatividad false |
| AC06 | ACTIVE → INACTIVE permitido, sede legal protegida y otros estados rechazados |
| AC07 | Cierre desde los tres estados admitidos, terminalidad PATCH/comandos e historial legible |
| AC08 | Cerrar/desactivar/reclasificar sede legal ACTIVE bajo Company ACTIVE da 409 |
| AC09 | Reclasificaciones concurrentes conservan un HEADQUARTERS no CLOSED y excluyen el propio Site |
| AC10 | Activación Company contra modificación incompatible de sede legal en ambos órdenes |
| AC11 | 412 también para no-op/terminal, carreras de cambios Site y escritores de hijos |
| AC12 | Tenant/Company/Site, 404 no reveladores y precedencia pertenencia antes de estados |
| AC13 | Cuatro operaciones separadas, equivalencia/presencia, misma clave concurrente, reinicio/rotación |
| AC14 | Eventos correctos y únicos, modos, schemas e invariantes, rollback conjunto y commit incierto |
| AC15 | `{}`/igualdad canónica sin cambio de versión/auditoría/evento y aceptación durable opcional |

Los escenarios adicionales adoptados cubren comandos redundantes 409, ARCHIVED para cualquier sede, comandos body ausente/`{}`, error precedence, liberación A → B, códigos CLOSED reservados y operatividad histórica distinta del GET actual. Se mantendrán regresiones de fingerprints y snapshots HU01/HU02/HU04 y pruebas que no contacten proveedores.

La aceptación futura incluye pruebas puras, PostgreSQL 18/HTTP, artefacto JVM y reinicio con DB conservada, esquemas reales y proceso/imagen nativos no-root. Se ampliarán verifyProductionIsolation, metadatos nativos de Infrastructure y los harness existentes, evitando fixtures en producción. La evidencia de proceso nativo no se sustituye por compilación JVM ni se declara ejecutada desde esta propuesta.

## Risks / Trade-offs

- [Contención entre sedes de una Company] -> Transacciones locales cortas, presupuesto único y medición de esperas; ajustar modos de bloqueo solo con evidencia y nuevas pruebas de invariantes.
- [HU03/HU06/HU07 usan otro orden] -> Contrato Company → Site y revisión Site documentado; pruebas independientes compatibles ahora e integración real cuando existan esos endpoints.
- [Lock correcto pero versión incrementada dos veces] -> UPDATE condicionado explícito y entidades gestionadas sin modificar; pruebas de revisión exacta, no-op y concurrencia.
- [Recuperación antes de cerrar la transacción perdedora] -> Usar la señal de finalización existente; 503 ante incertidumbre sin abrir sesión de recuperación prematura.
- [Snapshot histórico confundido con lectura actual] -> Codec HU05 separado, status Company original solo interno, booleano original en replay y escenarios de Company activada/desactivada después.
- [Clasificación incorrecta al recuperar colisiones] -> Resolver aceptación durable primero; conservar causas conocidas y tratar restricciones desconocidas como fallos internos.
- [Metadatos de no-op confundidos con auditoría de cambio] -> Validar fecha/actor del completado de forma independiente de updatedAt/updatedBy Site.
- [Diferencias JSONB/reflexión nativa] -> Round-trip real de tipos y proceso nativo/reinicio con DB conservada y contratos empaquetados.
- [Consumidores interpretan updated como historial de hijos] -> Documentar su semántica de revisión mínima y consulta autorizada de detalles; conservar hecho/eventId originales.

## Migration Plan

1. En implementación, añadir la siguiente migración libre; con el estado inspeccionado será `V5__enforce_site_mutation_completion_metadata.sql`. Nunca editar V1–V4 ni reasignar una versión ya aplicada.
2. Prevalidar las cuatro operaciones HU05 y detener ante históricos incompatibles sin borrar aceptaciones ni inventar procedencia. Añadir `ck_company_idempotency_site_mutation_metadata` con Site propio no nulo, expectedVersion no nulo/no negativo, status 200 y procedencia HMAC válida; conservar constraints genéricos, seis tablas y namespaces anteriores.
3. Actualizar DBML, inventario y contrato de persistencia y verificar instalación vacía/upgrade V4 con datos HU01/HU02/HU04. El outbox ya reconoce updated/closed y no necesita modificar enums ni tipos de evento aprobados.
4. Desplegar política/codec HU05 y contratos junto con las cuatro rutas, presupuesto positivo y keyring histórico existente. Mantener los formatos previos y no añadir dependencia operacional de Party/Geography/broker.
5. Ejecutar las comprobaciones trazadas a AC01–AC15 y registrar resultados reales en docs/operations/hu05-acceptance-review.md, con límites explícitos de fixtures locales frente a integración futura.
6. Si se revierte la aplicación, conservar V5, Sites, completados/eventos y claves históricas. Restaurar una aplicación compatible o suspender rutas HU05; no borrar aceptaciones, reiniciar revisiones ni hacer rollback destructivo del esquema.
