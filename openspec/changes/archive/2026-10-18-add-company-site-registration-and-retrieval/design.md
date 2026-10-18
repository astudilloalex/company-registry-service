# Design

## Context

La motivación y alcance están en [proposal.md](proposal.md). La base inspeccionada incluye `Site`, `SiteType`, `SiteStatus`, `Site.operationalUnder`, `RegistryData.SiteRecord`, `SiteEntity` y un `SiteRepository` con insert/find y acceso a hijos; faltan registro, listado y recursos comerciales de Site. Se conservará este agregado en lugar de crear otro modelo de identidad equivalente.

V1 ya impone `(tenant_id,company_id)` contra Company, código único `(company_id,code)` y HEADQUARTERS no CLOSED mediante `uq_company_sites_open_headquarters`. Su unicidad de código incluye CLOSED. Los completados ya tienen site_id y referencia compuesta; V2/V3 añaden procedencia y contratos HU01/HU02, pero no exigen todavía la forma de company.site.create.v1. Los índices Site actuales no cubren tenant/Company/fecha/ID del listado.

`CompanyRepository.findForUpdate` usa PESSIMISTIC_WRITE; `AtomicOperation`, `DatabaseBudget`, keyring HMAC, snapshots JSONB, outbox y UUIDv7 ya existen. `PersistenceFailures` distingue código/Party de Company y clave de completado, pero clasifica las unicidades Site como inesperadas. El mapper compartido acepta códigos propios mediante `ApiResponseException`; no transporta detalles de negocio en esa excepción. El servicio hermano Party Registry confirma el patrón de causa concreta en el code principal mediante `PartyResponseCode`, `PartyApiErrorTranslator` y pruebas HTTP que exigen únicamente status/code.

Los endpoints actuales HU01/HU02, su validación, fingerprints, snapshots y eventos son contratos previos que deben conservarse. La convención general de detalles de validación se precisa mediante la delta [business-api-conventions](specs/business-api-conventions/spec.md): el nuevo catálogo HU04 usa un único code principal descriptivo.

## Goals / Non-Goals

**Goals:** coordinar nuevas creaciones con el estado local protegido del padre, preservar el replay original, entregar lecturas coherentes de estado propio/efectivo y mantener causas de fallo verificables desde el cliente. Cada puerto nuevo tendrá una función concreta de I/O, firma, cursor, snapshot o evento.

**Non-Goals:** un framework genérico CRUD/idempotente, herencia de entidades para acoplar Company/Site, persistencia de operatividad derivada, llamadas distribuidas dentro de transacciones o sustitución del mapper global. La autenticación del administrador/servicio autorizado sigue siendo responsabilidad del contexto confiable ya definido; no se introduce un mecanismo de seguridad nuevo.

## Decisions

### 1. Reutilizar el agregado y separar los nuevos flujos por capacidad

Domain ampliará sus políticas puras para code/name/tipo efectivo y admisión de registro. Recibirá conceptos de Company/Site, nunca JSON, `RegistryData`, ORM o estados HTTP. Se reutilizarán `BusinessText`, versiones iniciales y la regla `Site.operationalUnder`.

Application añadirá un área `application/site` con comando de registro, resultado local, selección/frontera de página, casos de uso de registro/consulta y fallos neutrales diferenciados. El resultado incluirá SiteRecord y el booleano efectivo; el DTO público será propiedad de `api/site`. Infrastructure añadirá composición `SiteOperations`, codecs y adaptadores necesarios, reutilizando los repositorios actuales.

Los puertos de firma/snapshot/cursor/evento de Company tienen tipos de comando/resultado HU01/HU02 concretos. Se crearán equivalentes Site solo para esas fronteras reales, compartiendo utilidades puras cuando no alteren los bytes históricos. No se hará opcional Party dentro del resultado de creación Company para reutilizarlo con Site.

Alternativas descartadas: serializar SiteEntity/Domain como respuesta rompe la separación actual; expandir RegisterCompany con ramas de Site mezcla admisión externa y local; generalizar todos los agregados antes de HU04 añade una abstracción sin necesidad demostrada.

### 2. Entrada cerrada que conserve presencia y texto original

El DTO/lector de creación reconocerá exactamente code/name/siteType y conservará su entrada estructurada original. La presencia de siteType se representará explícitamente: ausente usa BRANCH; presente null falla; un string solo acepta los cuatro enums exactos. Code/name admiten null en el modelo de entrada para producir sus códigos requeridos, pero nunca en una aceptación. Otros tipos JSON tendrán fallos propios.

El lector consumirá el objeto completo para reconocer duplicados, desconocidos, raíces null/no objeto, límites del parser y tokens posteriores, sin confiar en mensajes Jackson ni orden de propiedades. Se validará un único fallo de campo en orden code, name y siteType; en code/name, requerido o tipo precede a longitud canónica. Las entradas estructurales y headers inválidos se rechazan siempre, también durante reintentos.

Dentro del body, la precedencia será documento inválido/tokens posteriores, raíz no objeto, propiedades desconocidas, propiedades duplicadas y luego reglas de code/name/siteType. El lector conservará los tipos o diagnósticos de campo hasta evaluar ese orden; no lanzará el primer fallo semántico solo porque su propiedad apareció antes en JSON. Esta regla no cambia la validación del contexto global ni promete reordenar errores independientes producidos por el framework antes de leer el body.

La canonización/validación efectiva usará la política aplicable a la aceptación: vigente para nuevos registros e histórica para comparar un completado. Se preservan los strings originales para no reinterpretar una huella antigua con una política futura. Las reglas de propiedad/default de la versión 1 quedarán congeladas junto con esa política. CompanyId de ruta se convierte a UUID tipado y no se admite en el body.

Se añadirá una frontera de lectura específica de Site, siguiendo el precedente del lector PATCH e interceptores propios, pero sin pasar sus fallos por una conversión genérica a bad-request que pierda la causa. Los errores se traducen a `ApiResponseException` con el código específico y el mapper compartido construye la respuesta. No se utilizará Bean Validation como vía que sustituya el catálogo propio por validation-error/errors[].

Alternativas descartadas: un enum nullable con default de SQL confunde omisión y null; normalizar en un deserializador destructivo pierde entrada necesaria para políticas históricas; inspeccionar texto de excepciones para elegir un código no es un contrato estable.

### 3. Replay primero y Company protegida para toda creación nueva

La operación será `company.site.create.v1`. Tras validar contexto y sintaxis, se buscará el completado durable en una sesión local corta. Si existe, se verificará su firma y pertenencia de snapshot; una entrada equivalente devuelve 201 original sin leer Company/Site actuales. Una entrada distinta devuelve el código específico de clave reutilizada. El Process-Id de respuesta pertenece al reintento.

Sin aceptación, la transacción adquirirá `CompanyRepository.findForUpdate(tenant,companyId)` para los cuatro tipos Site. Tras resolver esa protección se consultará de nuevo el completado antes de evaluar ausencia o estado del padre. Esto cubre la aceptación que pudo confirmar mientras se esperaba. Si sigue sin completado: Company ausente/ajena da company-not-found; ARCHIVED da site-registration-not-allowed-for-archived-company; DRAFT/ACTIVE/INACTIVE admiten el registro sujeto a unicidad.

La Company cargada no se modifica ni se envía a update: el bloqueo no requiere If-Match y no cambia estado, versión o auditoría del padre. Solo Site nuevo recibe versión 0. Las futuras operaciones HU03/HU05 deberán seguir Company antes de Site para que un archivado confirmado primero bloquee el registro y un registro confirmado primero preceda al archivado. HU04 prepara ese protocolo y no implementa archivado.

```text
Contexto + sintaxis valida
           |
           v
Buscar completado --> Existe --> Verificar entrada
           |                         |
           |                         +--> Coincide --> 201 original
           |                         |
           |                         +--> Otra entrada --> 409 especifico
           v
BEGIN: bloquear Company por tenant/ID
  Reconsultar completado --> replay o conflicto si aparecio
  Company no propia --> 404 company-not-found
  Company ARCHIVED --> 409 especifico
  Validar contenido efectivo y restricciones del registro
  Insertar Site DRAFT/version 0
  Insertar aceptacion original
  Almacenar evento habilitado
COMMIT --> 201 local
```

El instante de creación se capturará una vez, después de obtener la protección y descartar replay, truncado a microsegundos. Será el mismo en Site, snapshot y evento. CreatedBy/updatedBy conservarán el actor exacto. La transacción será secuencial, sin compartir sesión entre tareas concurrentes ni hacer HTTP a proveedores.

Alternativas descartadas: validar ARCHIVED antes del lock deja una carrera; incrementar Company para protegerse acopla agregados; bloquear únicamente HEADQUARTERS no protege BRANCH/WAREHOUSE/OTHER contra archivado. El coste aceptado es serializar creaciones de una misma Company.

### 4. Fingerprint y snapshot Site independientes y reproducibles

Se reutilizará el keyring externo, con política de firma Site versión 1 y un codec propio de snapshot versión 1. El documento HMAC SHA-256 se serializará como JSON UTF-8 compacto, con orden fijo: fingerprintVersion, tenantId, operation, companyId, code, name y siteType. UUIDs se codifican canónicamente y los textos ya están canonizados. Ausencia de siteType y BRANCH explícito producen los mismos bytes.

Actor, proceso, clave idempotente, IDs/fechas generados, estados actuales y modo outbox quedan fuera de la huella. La PK continúa siendo tenant/operación/clave: Company participa en el contenido, no añade otra dimensión a la clave. Por tanto, dos Companies del mismo tenant que reutilicen la misma clave compiten por una aceptación y no pueden recuperar datos entre sí.

El completado conservará tenant, Company, Site, hash, procedencia de política/keyId, expectedVersion null, resultado 201 y snapshot versionado. El codec preservará los doce valores públicos originales y la propiedad interna necesaria para reconstruir/verificar SiteRecord. Tenant interno no se expondrá en el DTO. Se comprobarán propiedad contra el completado, tipos, identidad, versión de esquema, DRAFT, versión inicial, booleano false y auditoría inicial coherente antes de devolver el resultado.

El replay no recalcula effectivelyOperational: permanece false, aunque un GET actual pueda mostrar ACTIVE/true o historial CLOSED/false. No se reescriben fechas, actores o eventos por rotación, reinicio o cambio de modo. Una versión, clave o snapshot histórico desconocido/corrupto produce server-error saneado; no se inventa una discrepancia de solicitud ni otra creación. No habrá TTL.

Alternativas descartadas: firmar JSON crudo confunde formato con intención; omitir Company permite replay por la ruta equivocada; construir replay con datos actuales rompe la aceptación original. Se preservarán sin cambios los codecs y políticas HU01/HU02.

### 5. Unicidad, recuperación y plazo de base de datos

Se ampliará la clasificación neutral de persistencia con causas Site para `uq_company_sites_code` y `uq_company_sites_open_headquarters`, conservando COMPLETION_KEY y las causas Company. Las unicidades desconocidas, incluida una inconsistencia inesperada de evento, no serán conflictos de negocio. Tras la validación de la solicitud, una violación interna de propiedad/shape se saneará como 500, sin reutilizar un 400 genérico de persistencia para culpar al cliente.

El índice único sigue siendo la autoridad final. Los diagnósticos de código/HQ se consultarán dentro del ámbito correcto; no se reutilizará `companies.occupied`, que consulta otra identidad. Se podrá comprobar código y reserva de sede legal bajo el bloqueo del padre para producir causas concretas; esas comprobaciones no sustituyen las restricciones SQL frente a otros escritores. Si ambas restricciones aplican, la comprobación documentada evaluará código antes de la reserva HEADQUARTERS; una colisión SQL real se clasificará por su restricción conocida.

Una carrera de completado entre Companies diferentes puede ocurrir después de insertar Site. El perdedor revierte todo su intento y cierra la sesión antes de buscar la aceptación en una sesión nueva contra la base primaria. La recuperación busca primero el completado: coincidencia da replay; diferencia da el 409 descriptivo de clave. Sin aceptación, una colisión de código/HQ conocida conserva su 409 específico. La incertidumbre restante devuelve 503, sin inferir una aceptación por observar datos parecidos ni reejecutar la creación automáticamente.

Como detalle técnico inicial se usará `company-registry.site-registration.wait-budget`, variable `COMPANY_SITE_REGISTRATION_WAIT_BUDGET`, predeterminado 10s positivo y finito. Un único DatabaseBudget cubrirá consultas, espera del lock, escrituras, finalización y recuperación, aplicando checkpoints de statement_timeout/lock_timeout con tiempo restante. No se reiniciará el plazo por fase. Si se agotó, puede terminar en 503 y un reintento posterior resolver el resultado. Cancelación/limpieza no deben dejar una escritura suscrita por separado. Se reutiliza la clasificación transitoria de deadlock/serialización existente.

El caso de uso impondrá además un deadline de suscripción desde la búsqueda inicial hasta el resultado final y rechazará una aceptación que llegue fuera del plazo. Cada fase atómica limitará adquisición, commit y espera de limpieza con su remanente; reservará el menor de 10% de ese tiempo y 100 ms para rollback/cierre normales. El trabajo que no termina fallará dentro de la transacción antes de consumir esa reserva. Los temporizadores y la cancelación volverán al contexto Vert.x propietario, y no se esperará indefinidamente una limpieza bloqueada. Vencer el deadline no demuestra rollback de un commit remoto: la respuesta será 503 y el reintento consultará la aceptación durable.

El fallo neutral de plazo distinguirá una finalización aún no confirmada. Esa condición no permitirá abrir recuperación automática antes de cerrar la transacción; solamente un fallo con rollback/cierre confirmados podrá iniciar la sesión nueva de recuperación. El protocolo se conservará también en los casos Company que comparten el adaptador.

Alternativas descartadas: recuperación en sesión fallida, devolver siempre conflicto ante timeout y reservas idempotentes solo en memoria no permiten la recuperación durable acordada.

### 6. Consultas con estado local coherente y sin locks de escritura

Las consultas verificarán primero Company por tenant para distinguir company-not-found de una colección vacía. La lectura individual exigirá también Site dentro de Company/tenant; su ausencia o propiedad ajena da site-not-found. Company ARCHIVED y Site CLOSED son legibles.

Se ampliará SiteRepository con consulta/listado calificados y proyección neutral que incluya Site y Company actuales. El estado del padre usado para cada resultado se leerá junto con Site en la misma sentencia de detalle/página mediante join local; la comprobación previa de existencia no se reutilizará como un estado potencialmente desfasado para calcular operatividad. Todas las filas de una página utilizarán el estado de Company visible en esa consulta. La política de dominio produce el booleano y el API mapper genera el DTO propio.

Las lecturas usan sesión y consultas secuenciales, sin bloqueo pesimista del padre, sin escribir estados y sin consultar Party. No se cargan direcciones/contactos. Un filtro status se aplica a Site, por lo que ACTIVE puede coexistir con effectivelyOperational false bajo Company no ACTIVE.

Alternativas descartadas: reutilizar ReadCompanies introduce enriquecimiento externo; consultar el padre por cada Site añade I/O redundante; persistir un flag de operatividad exige sincronizaciones y rompe el estado independiente.

### 7. Cursores exclusivos de Site y consulta acotada

Se seguirá el algoritmo HU01: consultar limit+1 filas con orden createdAt/id descendente, seleccionar hasta limit y usar el último elemento seleccionado como frontera cuando hay continuación. La condición de avance compara timestamp completo y UUID para resolver empates. No habrá count global ni cursor hacia atrás.

El cursor Site tendrá versión y keyId propios en su documento autenticado, Company explícita y contexto de colección diferenciado. Se reutilizarán primitivas HMAC con dominio de firma `company.site.cursor.v1`, sin cambiar `company.cursor.v1`. El payload incluirá tenantId, companyId, code canónico, siteType, status, orden, createdAt e id. Se validarán shape, firma, formato y ámbito; límite de longitud inicial 4096 caracteres como el cursor existente. Actor/proceso y limit no definen el ámbito: puede cambiar el tamaño de página sin cambiar filtros ni posición.

La query aceptará únicamente code/siteType/status/cursor/limit, una vez cada uno, combinando filtros con AND. Los enums serán exactos y code reutilizará su política canónica. Los límites grandes se validarán sin overflow antes de convertir a una representación pequeña; su gramática decimal será la de enteros sin signo, rechazando fracciones y whitespace. El catálogo de la delta API distingue formato, rango y cursor inválido/incompatible. El envelope incluirá numberOfElements y nextCursor solo cuando proceda, omitiendo totales/prevCursor.

Alternativas descartadas: offset o fecha sin desempate pueden repetir/omitir en un conjunto estable; reutilizar el cursor Company sin dominio/Company explícitos permite intercambios indebidos; contar todo el conjunto añade un coste no requerido.

### 8. Evento y contrato HTTP compartido

El productor Site utilizará OutboxPolicy/OutboxRepository existentes y un candidato propio del núcleo. [company-site-created-event](specs/company-site-created-event/spec.md) fija los ocho campos y once campos de envelope; el resultado de consulta no será el payload de integración. La causalidad HTTP será null explícito. La entrega utilizará los metadatos originales del outbox, sin reconstruir un hecho desde Site/Company actuales.

La futura implementación añadirá `docs/contracts/events/company.site.created.v1.schema.json` y su ejemplo, con esquema cerrado Draft 2020-12 y validación de invariantes cruzadas siteId/aggregateId, fechas, versiones y actor. El handoff de HU08 documentará exchange company.events y routing key company.site.created.v1, respetando que stored-only/published almacenan PENDING pero no publican en HU04.

API ampliará el OpenAPI actual con los tres endpoints y schemas Site/request/envelopes/errores. Los doce campos de Site serán requeridos. El catálogo normativo completo está en [business-api-conventions](specs/business-api-conventions/spec.md): el API translator convertirá fallos neutrales distintos a códigos propios mediante `ApiResponseException`; el handler de la librería seguirá produciendo el envelope y los fallos inesperados. No se cambia la librería compartida ni se crea un mapper global local.

Los tipos/propiedades de HU01/HU02 y los ejemplos existentes no se alterarán. La excepción de gestión /q, el contexto global y sus códigos actuales permanecen. Las nuevas validaciones de campos/path/key/query y parsing Site serán específicas de HU04; los mensajes internos no se usan como código público ni se devuelven al cliente.

### 9. Verificación y trazabilidad de aceptación

Las políticas puras cubrirán presencia/default, tipos admitidos, Unicode, límites, pertenencia y operatividad. PostgreSQL 18 verificará migración, índices, metadatos, join calificado, versiones/auditoría intactas y atomicidad real. Las carreras usarán transacciones/conexiones independientes con barreras observables y plazos, no sleeps como evidencia de orden ni concurrencia sobre una misma sesión reactiva.

Las pruebas HTTP exigirán shape exacto de cada fallo y código descriptivo, los doce campos públicos, filtros y aislamiento de cursores. Los escenarios de archivado/cierre/activación se prepararán mediante fixtures SQL privilegiados o adaptadores de test fuera del artefacto productivo, sin añadir endpoints HU03/HU05. Se comprobará que proveedores caídos no reciben llamadas HU04. Las pruebas de reinicio y clave histórica usarán la aceptación durable y una base conservada.

| AC del issue #6 | Evidencia requerida |
| --- | --- |
| AC01 | Creación DRAFT/0, DTO y auditoría inicial; Company DRAFT/ACTIVE/INACTIVE |
| AC02 | Padre ajeno/inexistente con company-not-found y sin escrituras |
| AC03 | Código canónico duplicado y código reservado por CLOSED |
| AC04 | Mismo código permitido en Companies distintas |
| AC05 | HEADQUARTERS DRAFT/ACTIVE/INACTIVE ocupa la reserva |
| AC06 | Carrera independiente: máximo un HEADQUARTERS y un evento habilitado |
| AC07 | CLOSED histórico permite reemplazo con código libre |
| AC08 | Canonización, acentos, espacios y expansión Unicode |
| AC09 | ARCHIVED protegido y ambos órdenes de carrera con archivado fixture |
| AC10 | Replay original tras cambios/cierre/archivado, carrera, reinicio y rotación |
| AC11 | Detalle/listado tenant+Company+Site, historial y paginación |
| AC12 | Matriz Company/Site, estado propio preservado y booleano calculado |
| AC13 | Evento real validado contra esquema, modos y rollback conjunto |
| AC14 | Versión, estado y auditoría completos de Company intactos |

Se ampliarán `verifyProductionIsolation`, el contrato OpenAPI compartido de tests y los ejecutores empaquetados/nativos para los nuevos DTOs, recursos y snapshots. La reflexión requerida se registrará en Infrastructure, conservando el núcleo sin anotaciones nativas. Gradle ya empaqueta META-INF/contracts/events; las comprobaciones deberán exigir explícitamente el nuevo esquema y ejemplo. La aceptación incluye binario nativo e imagen Podman no-root, con ausencia verificada de fixtures productivos.

## Risks / Trade-offs

- [Serialización por Company] -> Bloqueo breve sin I/O externo, presupuesto finito y pruebas de espera; Companies distintas conservan concurrencia salvo colisión intencional de clave.
- [Archivado implementado posteriormente con otro protocolo] -> Documentar Company antes de Site y probar ambos órdenes con escritor fixture compatible; HU03 deberá respetarlo.
- [Pérdida del motivo al pasar por validación genérica] -> Frontera Site propia, fallos neutrales explícitos y pruebas HTTP del code raíz exacto.
- [Snapshot original confundido con consulta actual] -> Resultados/cursor separados de la aceptación; booleano false inmutable del replay y tests después de cambios de estado.
- [Colisión de clave después de insertar bajo otro padre] -> Rollback completo y cierre antes de recuperar en sesión nueva; verificar Company en huella/snapshot.
- [Diferencias de JSONB o reflexión en nativo] -> Round-trip real de tipos/auditoría y proceso nativo con reinicio y rotación, sin usar solo evidencia JVM.
- [Código histórico no reutilizable] -> Documentar que CLOSED libera la reserva de sede legal pero conserva su código; probar ambos resultados distintos.

## Migration Plan

1. En implementación, añadir V4 `V4__add_site_registration_support.sql` sin editar V1–V3. Exigir metadatos de company.site.create.v1 con `ck_company_idempotency_site_creation_metadata` e instalar `ix_company_sites_tenant_company_created` sobre `(tenant_id, company_id, created_at DESC, id DESC)`.
2. Detectar aceptaciones Site históricas incompatibles antes de validar el nuevo CHECK; detener con diagnóstico sin inventar claves/políticas ni borrar snapshots. Preservar seis tablas, FKs, unicidades y privilegios. Actualizar DBML e inventario de restricciones con evidencia.
3. Probar instalación vacía y upgrade desde V3 con Companies/Sites/eventos/completados HU01/HU02; contrastar checksums e identidad del catálogo y replays anteriores. La nueva restricción no afecta namespaces técnicos ajenos.
4. Entregar política/codec Site versión 1, keyring histórico y presupuesto configurado junto con endpoints, contratos, evento y recursos nativos. No añadir credenciales de proveedores ni broker para HU04.
5. Ejecutar verificaciones unitarias, HTTP, PostgreSQL, concurrencia, proceso JVM empaquetado y proceso/imagen nativos; registrar evidencia AC01–AC14 y decisiones adicionales confirmadas.
6. Si se revierte la aplicación después de aceptar HU04, conservar V4 y datos/keys. Restaurar una aplicación capaz de reproducir Site o suspender sus rutas; no revertir mediante borrado de aceptaciones o reparación de migraciones.
