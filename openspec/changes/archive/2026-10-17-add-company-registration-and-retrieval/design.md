# Design

## Context

La motivación y las cinco decisiones confirmadas están en [proposal.md](proposal.md). Los contratos observables están en los seis deltas de `specs/`. Este diseño es necesario por la combinación de concurrencia, migración, criptografía, lectura entre servicios y ejecución nativa.

Base inspeccionada de Company: commit `88b4c5d27284a1ee053227074fad1d717c2af5f7`. T00 ya aporta:

- `Company` como identidad/estado/versión mínimos; `RegistryData.CompanyRecord` contiene texto y auditoría.
- `CompanyRepository.find/insert`, `CompletionRepository.find/insert`, `OutboxRepository` y `AtomicOperation` en Application. Los adaptadores usan Hibernate Reactive/Panache, sesiones Vert.x y escrituras secuenciales.
- `ReactiveCompanyRepository.insert` y `ReactiveCompletionRepository.insert` hacen flush. `PersistenceFailures` reconoce SQLSTATE 23505 pero lo reduce a DUPLICATE sin identificar su unicidad de negocio.
- V1 contiene las seis tablas, PK de completado `(tenant_id,operation,idempotency_key)`, Company obligatoria en el completado, JSONB y unicidades Company/outbox. No contiene versión de fingerprint ni ID de clave.
- `BusinessContextFilter` valida contexto antes del recurso; los tests actuales aceptan Process-Id con mayúsculas. El usuario confirmó endurecer tenant/proceso a UUID canónico en minúsculas para alinearlos con Party sin reescribir correlaciones.
- OpenAPI tiene `paths: {}`; su `CompanyResponse` reservado carece de actores de auditoría y Party. El mapper JSONB está separado de la omisión de nulos HTTP.

Evidencia de Party: checkout local con HEAD `fd603dd08fd980b96b5f35056345d638e4080bb2`, sus DTOs `PartyDetailResponse`, `LegalEntityDetailsResponse`, `PartyIdentifierResponse`, `RequestContextFilter`, `PartyIdentifierResource` y `docs/contracts/party-registry.openapi.yaml`. El detalle no incorpora identificadores: se componen dos contratos reales. El listado de identificadores es histórico, paginado y sin filtro de estado por defecto; sus cursores dependen de Party, tenant, filtros y limit. Su DTO publica maskedValue y no el identificador completo. No se cambia ese repositorio en HU01.

## Goals / Non-Goals

**Goals:** mantener Domain puro y la orquestación en Application; ofrecer una aceptación durable por clave entre instancias; reproducir el resultado enriquecido completo; acotar I/O y esperas; incorporar rutas reales compatibles con la base nativa; verificar contratos externos sin depender de despliegues vivos.

**Non-Goals:** un protocolo de reserva previa o una tabla nueva de idempotencia; transacciones distribuidas o locks locales entre servicios; copias autoritativas de Party; modificación de HU02-HU07; publisher, scheduler, claims o RabbitMQ en HU01. La actualización de documentación central de arquitectura, si requiere otro repositorio, será coordinación aparte y no una escritura implícita de este cambio local.

## Decisions

### 1. Límites de aplicación y composición

La API parsea headers/body/query, conserva una copia estructurada de la entrada y transforma resultados neutrales en DTOs públicos usando `ResponseManager`. Application orquesta registro/consulta mediante puertos propios y resultados tipados, sin HTTP, Jackson, SQL o entidades ORM. Domain mantiene políticas síncronas de normalización/elegibilidad/valores iniciales. Infrastructure provee clientes, codec de snapshots/fingerprints/cursores, configuración, reloj/IDs y fronteras de sesión/transacción.

Se amplían los puertos existentes con consultas tenant-qualified y una proyección de página neutral. Se añade una lectura enriquecida Party separada del puerto mínimo de validación: los datos legales son un read model externo, no nuevos atributos del agregado Company. Se reutiliza el cliente y su configuración; no se crean datasources externos. Serializar el snapshot y mapearlo a DTOs pertenece a las fronteras, manteniendo el documento neutral versionado ya admitido por `RegistryData`.

Alternativa evaluada: transportar DTOs del proveedor o `RestResponse` hasta Application simplificaría el primer mapping, pero rompería las fronteras comprobadas por ArchUnit. Se conservan modelos de transporte solo en los adaptadores.

### 2. Secuencia de registro y recuperación por unicidad

```text
Validate context + closed input + key
                  |
        Read completion, close session
             |                 |
           Found             Absent
             |                 |
      Verify stored policy   Canonical input + Party detail/identifiers
             |                 |
      Replay / conflict      Build candidate result + optional event
                               |
                      Open short write transaction
                      Recheck completion
                      Insert Company -> completion -> optional outbox
                               |
                             Commit
                               |
                         Return accepted 201

Expected unique failure -> finish rollback -> close failed session
                               |
                      Open fresh read session
                      Resolve durable completion
                      Replay / key conflict / business conflict
```

No sesión externa envolverá todo el pipeline: encadenar `withSession` dentro de otra sesión puede reutilizarla. Cada lectura inicial, escritura y recuperación tendrá un ciclo de vida cerrado explícito. Cada solicitud concurrente tendrá su propia sesión/conexión; las tres escrituras de una solicitud se ejecutarán secuencialmente. Las llamadas HTTP completas preceden la transacción de escritura y no retienen locks de negocio.

Se conservan las restricciones como árbitro. El adaptador traduce únicamente las colisiones esperadas de `uq_companies_tenant_code`, `uq_companies_tenant_party` y `pk_company_idempotency_records` a categorías neutrales verificables. Un duplicado desconocido, por ejemplo una identidad outbox inesperada, no se presenta como conflicto de entrada. No se entregan SQL ni nombres de restricciones al cliente.

Después de una colisión esperada, la recuperación consulta primero `(tenant, company.create.v1, key)` en la base primaria. Si existe, verifica el contenido usando su política/clave histórica: equivalente da replay; diferente da 409. Si no existe y código/Party están ocupados, da conflicto de negocio, sin devolver la Company de otra clave. Esta recuperación también cubre el fallo en el INSERT Company antes de insertar completado. Si no se puede resolver el resultado, devuelve fallo temporal, no un éxito ni un conflicto inventado.

La transacción que confirma es la ganadora; no se promete prioridad por orden de llegada. Si quien retenía la unicidad revierte, el competidor puede continuar. Los errores de commit, cancelación o red no autorizan afirmar que no hubo commit: se intenta recuperación durable dentro del presupuesto; si sigue incierto, 503 permite repetir el mismo key/input. No se persisten fallos como aceptaciones ni se reintenta ciegamente todo el comando.

Alternativas evaluadas: una reserva IN_PROGRESS previa requeriría cambiar el modelo, que actualmente exige Company en cada completado, y resolver reservas abandonadas. Locks advisory por clave son posibles pero añadirían un protocolo nuevo; para HU01 se adopta el arbitraje y recuperación confirmados por el usuario.

### 3. Fingerprint y rotación

La operación estable es `company.create.v1`. Cada nueva aceptación usa política 1 y la clave activa configurada. El documento canónico tiene campos en este orden fijo: `fingerprintVersion`, `tenantId`, `operation`, `request`; request contiene `partyId`, `code`, `operatingName`, `description`, `logoUrl` en ese orden. Se serializa en JSON UTF-8 compacto, con escapes estables, sin depender del orden recibido ni del ObjectMapper HTTP. Los strings se codifican con un codec versionado, sin concatenación ambigua de delimitadores. Se usa HmacSHA256 de JCA y hexadecimal minúsculo de 64 caracteres; el estado mutable del MAC no se comparte entre solicitudes.

Los UUID tipados se representan canónicamente. Código/nombre/descripción usan `BusinessText`; ausencia y null de los dos opcionales producen null explícito. Una descripción en blanco falla. La URL validada conserva su contenido exacto; como valor inicial de implementación se aceptan URLs absolutas HTTP/HTTPS sin espacios exteriores, sin descargar el recurso. No se colapsan espacios internos ni se incorpora normalización Unicode nueva. Actor, proceso, clave idempotente, ID/fechas generadas y Party enriquecida no participan en la huella.

El input estructurado original se conserva hasta resolver el completado. En replay se aplican la política y clave indicadas por el registro histórico, incluso en recuperación tras colisión: una huella precomputada con la clave activa nueva no se compara directamente con una guardada con la anterior. Los codecs/políticas históricas son inmutables; una versión desconocida o snapshot corrupto falla de forma saneada, sin reconsultar estado actual.

Configuración externa propuesta: `company-registry.idempotency.active-key-id` y un keyring indexado por ID, con secretos Base64 de al menos 32 bytes aleatorios. Se valida configuración necesaria para servir HU01, sin secretos por defecto ni material criptográfico en logs. El mismo ID identifica el mismo secreto en todas las instancias. En una rotación se distribuyen primero K1+K2 a todas las instancias, después se activa K2; se conserva K1 y su política mientras existan completados dependientes. No se introduce TTL. La versión del fingerprint, del snapshot, del evento y del agregado son conceptos independientes.

Alternativas evaluadas: hashear el JSON recibido confunde diferencias de presentación con diferencias efectivas. Comparar siempre con la clave activa rompe replays tras una rotación. Se mantiene HMAC y los dos metadatos explícitos aprobados.

### 4. Party e identificadores: datos actuales frente a snapshot original

Para una nueva creación, `GET /v1/parties/{partyId}` debe confirmar LEGAL_ENTITY en DRAFT/ACTIVE/INACTIVE. ARCHIVED y NATURAL_PERSON producen referencia inválida; ausencia del tenant produce reference-not-found sin buscar existencia global. Se obtiene además `GET /v1/parties/{partyId}/identifiers`, sin filtro de estado, para formar el resultado completo antes de abrir la escritura.

La colección anidada conserva la semántica histórica publicada, sin añadir un filtro de vigencia no acordado. Se recorre el cursor del proveedor manteniendo `limit=200` y el resto de parámetros; se validan envelope, páginas, tipos, ownership y progreso. Se conserva el orden del proveedor, se detectan IDs duplicados/cursor repetido y se evita truncar a la primera página. `[]` se usa únicamente para colección vacía confirmada. Cada página propaga los tres headers aceptados. No se requieren RUC ni identificadores VERIFIED como prerrequisito nuevo de HU01.

Los GET Company primero resuelven filas propias y cierran la sesión local; después componen el read model externo. Un listado sin coincidencias no hace llamadas Party. En una página con varias Companies se limita el paralelismo HTTP, sin compartir sesión reactiva de persistencia entre ramas. Los registros actuales ARCHIVED de Party pueden mostrarse en GET: la elegibilidad anterior pertenece exclusivamente a la creación.

Un timeout/indisponibilidad del enriquecimiento da 503; contrato incompatible o referencia persistida no resoluble da 502. No se devuelve 200 parcial. Para POST estos fallos ocurren antes de la aceptación y no dejan escrituras. No se añaden reintentos genéricos de HTTP ni una caché legal local para ocultarlos. La lectura entre servicios no promete un único snapshot distribuido; la proyección aceptada de POST es la observada antes de su commit.

El snapshot idempotente guarda todos los valores públicos originales de Company y Party, incluida la colección de identificadores enmascarados. Esta copia histórica no autoritativa es una consecuencia explícita del replay completo confirmado; no añade columnas legales a companies, tablas espejo o sincronización de Party. Se conserva el documento neutral de resultado y HTTP 201 como metadato; la API aplica el envelope compartido. La igualdad prometida es del contenido JSON aceptado, no del orden físico de propiedades o espacios de serialización JSONB. Los opcionales internos pueden ser null y se omiten en HTTP conforme a la política pública.

Alternativas evaluadas: un GET de cada replay mostraría datos nuevos y dependería de la disponibilidad de Party. Una respuesta sin Party preservaría autonomía local, pero contradice la representación enriquecida aprobada. Se adopta snapshot completo para replay y enriquecimiento actual para GET.

### 5. Representación, auditoría y errores HTTP

Un DTO API uniforme sirve para POST, GET individual y cada elemento de colección. Incluye todos los campos acordados; Party reusa nombres y tipos del contrato remoto sin exponer clases externas o entidades ORM. Su versión y auditoría no se confunden con las de Company. Cada identificador conserva las propiedades de `PartyIdentifierResponse`, incluidos sus metadatos opcionales y maskedValue; no se solicita un valor completo.

Se captura una sola vez `Clock.instant()` a microsegundos y el User-Id validado para iniciar las cuatro propiedades de auditoría. El snapshot y el evento usan ese mismo instante. Las lecturas y replays no actualizan auditoría; un replay mantiene actores/fechas originales en sus datos históricos y utiliza Process-Id del intento actual en el header de respuesta. La correlación original del evento permanece en outbox.

Ejemplo completo de la respuesta aprobada, con datos ilustrativos y opcionales nulos omitidos. Un GET usa el mismo DTO con envelope HTTP 200; un replay conserva este resultado original 201:

```json
{
  "status": 201,
  "code": "successful",
  "data": {
    "companyId": "0199e000-0000-7000-8000-000000000001",
    "partyId": "018f1234-5678-7000-8000-000000000004",
    "code": "ACME",
    "operatingName": "COMPAÑÍA ÁGIL",
    "description": "OPERACIONES COMERCIALES EN ECUADOR",
    "logoUrl": "https://example.com/LogoAcme.png",
    "status": "DRAFT",
    "version": 0,
    "createdAt": "2026-10-17T14:30:00.123456Z",
    "createdBy": "Actor-aB",
    "updatedAt": "2026-10-17T14:30:00.123456Z",
    "updatedBy": "Actor-aB",
    "party": {
      "partyId": "018f1234-5678-7000-8000-000000000004",
      "type": "LEGAL_ENTITY",
      "displayName": "COMPAÑÍA ÁGIL",
      "recordStatus": "ACTIVE",
      "version": 2,
      "createdAt": "2026-10-10T10:00:00.000000Z",
      "updatedAt": "2026-10-15T12:00:00.000000Z",
      "createdBy": "Actor-original",
      "updatedBy": "Actor-aB",
      "legalEntityDetails": {
        "legalName": "COMPAÑÍA ÁGIL S.A.S.",
        "tradeName": "COMPAÑÍA ÁGIL",
        "legalFormCode": "SAS",
        "incorporationCountryCode": "EC",
        "incorporatedOn": "2020-01-15"
      },
      "identifiers": [
        {
          "identifierId": "0199e000-0000-7000-8000-000000000002",
          "partyId": "018f1234-5678-7000-8000-000000000004",
          "identifierSchemeId": "0199e000-0000-7000-8000-000000000003",
          "schemeCode": "EC_RUC",
          "maskedValue": "*********0001",
          "status": "VERIFIED",
          "isPrimary": true,
          "issuerCode": "SRI",
          "issuedOn": "2020-01-15",
          "verifiedAt": "2026-10-12T09:00:00.000000Z",
          "verifiedBy": "Actor-verificador",
          "version": 1,
          "createdAt": "2026-10-10T10:05:00.000000Z",
          "updatedAt": "2026-10-12T09:00:00.000000Z"
        }
      ]
    }
  }
}
```

El body de creación es cerrado. Tenant y proceso de headers se aceptan solo en UUID canónico minúsculo; mayúsculas, abreviaturas y espacios fallan antes del lookup idempotente. User-Id se conserva exactamente. Se actualizan las pruebas T00 que actualmente construyen Process-Id en mayúsculas: esa representación pasa a ser un escenario negativo, no se elimina la comprobación de correlación exacta. Los UUID de referencias se parsean como valores y se envían canónicamente al proveedor; no se exige que referencias externas sean UUIDv7.

Se reutilizan los códigos públicos existentes en vez de expandir el catálogo sin necesidad:

| Condición | HTTP / código |
| --- | --- |
| JSON, clave, path/query o body inválido | 400 / bad-request o validation-error según frontera |
| Headers de contexto inválidos | 400 / process-id-invalid, tenant-id-invalid, user-id-invalid |
| Party ausente en nueva creación | 404 / reference-not-found |
| Tipo o estado Party incompatible | 400 / reference-invalid |
| Company ausente/ajena en GET | 404 / not-found |
| Código, Party o clave aceptada con contenido distinto | 409 / resource-conflict |
| Proveedor incompatible/referencia persistida no resoluble | 502 / dependency-response-invalid |
| Dependencia indisponible, timeout o aceptación incierta | 503 / dependency-unavailable |
| Snapshot/clave/política histórica inválida o fallo inesperado | 500 / server-error saneado |

Los detalles técnicos permanecen internos y tipados; no se devuelve un snapshot bajo una clave con contenido distinto. POST/replay aceptados devuelven 201 y GET 200. If-Match no se exige a estas operaciones. Health/OpenAPI conservan su contrato operacional.

### 6. Consultas y cursores de Company

Se amplía CompanyRepository con filtros exactos opcionales, todos tenant-qualified, y una página de resultados neutrales. Código se canoniza con la misma política de creación; PartyId es UUID y status enum Company exacto. Los filtros combinan AND. Un filtro sin coincidencias produce lista vacía, sin verificar globalmente la Party del filtro.

Se usa keyset por `(created_at, id)` descendente y consulta de hasta `limit + 1` para determinar continuación; nunca solo ID/fecha ni offset. El índice existente `(tenant_id, created_at DESC, id DESC)` sirve de punto de partida. Se inspecciona EXPLAIN para filtro status con volumen representativo antes de justificar otro índice; no se modifica V1.

El cursor de formato 1 codifica tenant, filtros efectivos normalizados, orden y la pareja límite con precisión temporal completa; lleva keyId y MAC autenticado, con separación de propósito respecto al fingerprint. El keyring puede compartirse con namespace criptográfico distinto. No depende de User-Id, Process-Id ni del limit, que puede cambiar dentro de 1..200. Clave/versión desconocida, firma inválida o contexto diferente producen 400, sin mostrar contenido del cursor.

El valor inicial de HU01 es navegación hacia delante: `nextCursor` cuando existe continuación y `numberOfElements` siempre. `prevCursor`, `totalElements` y `totalPages` son opcionales y se omiten; el contrato compartido no exige calcularlos. No se promete un snapshot entre requests. Un array de Companies usa el mismo DTO enriquecido que GET individual, sin objeto pagination anidado.

### 7. Evento y límites de publicación

Se construye un candidato inmutable con el evento aprobado, independiente del snapshot REST enriquecido. El envelope tiene los once campos especificados; el payload tiene exactamente siete. `eventId` propio y Company ID son UUIDv7 distintos. aggregateVersion es 0, eventSchemaVersion 1 y causationId null explícito. Tenant/proceso/actor originales se asignan a tenantId/correlationId/payload.createdBy.

`OutboxPolicy` guarda el evento PENDING dentro de la misma transacción para stored-only/published y lo omite en disabled. Intentos y versión técnica empiezan en 0; publishedAt y diagnósticos son null. El replay no llama al almacenamiento outbox. El esquema JSON versionado y su ejemplo se ubicarán en `docs/contracts/events/`, con validación de formato/campos/igualdades y ausencia de datos legales. Se empaquetarán los recursos que necesite la validación de producción/nativa.

HU08 recibirá exchange company.events y routing key company.created.v1 como contrato para esta clase de mensaje. El default técnico actual de routing key es company.events; su alineación por tipo y los bindings se harán al implementar HU08, sin activar un conector en HU01. El futuro publisher leerá el evento almacenado y mantendrá eventId/contexto entre entregas al menos una vez.

Ejemplo normativo de forma, con valores ilustrativos:

```json
{
  "eventId": "0199e000-0000-7000-8000-000000000005",
  "tenantId": "018f1234-5678-7000-8000-000000000001",
  "aggregateType": "COMPANY",
  "aggregateId": "0199e000-0000-7000-8000-000000000001",
  "aggregateVersion": 0,
  "eventType": "company.created.v1",
  "eventSchemaVersion": 1,
  "occurredAt": "2026-10-17T14:30:00.123456Z",
  "correlationId": "018f1234-5678-7000-8000-000000000002",
  "causationId": null,
  "payload": {
    "companyId": "0199e000-0000-7000-8000-000000000001",
    "partyId": "018f1234-5678-7000-8000-000000000004",
    "code": "ACME",
    "operatingName": "COMPAÑÍA ÁGIL",
    "status": "DRAFT",
    "createdAt": "2026-10-17T14:30:00.123456Z",
    "createdBy": "Actor-aB"
  }
}
```

### 8. Presupuestos operacionales y verificación

Valores iniciales de diseño ajustables por configuración: presupuesto compartido de espera/recuperación de registro 10s, plazo global de enriquecimiento por operación 30s y concurrencia HTTP de enriquecimiento 4. Se conservan los timeouts del cliente Party ya configurados; el plazo global incluye todas las páginas y la espera por permisos de concurrencia. La espera de locks/consultas debe cancelarse en la base dentro del presupuesto, no únicamente abandonar el Uni del cliente. Una cancelación no deja trabajo de escritura suelto ni conduce a reusar la sesión fallida.

Las pruebas de carreras usan sesiones/conexiones independientes y barreras o señales observables de PostgreSQL; no sleeps como prueba de orden ni dos ramas con la misma sesión. Se cuentan Company/completado/evento y se comparan snapshots, no solo estados HTTP. Las pruebas de respuesta perdida, reinicio, rotación entre instancias y cambios posteriores usan fixtures de prueba; no requieren implementar HU02 o mutaciones públicas ajenas al alcance.

Se amplían los stubs reales de `ReferenceStubServer` con detalle e identificadores paginados y las suites de REST/JVM/proceso nativo. El artefacto productivo debe exponer las rutas HU01 reales mientras sigue excluyendo rutas auxiliares; se actualizan las aserciones T00 de paths vacíos/ausencia de rutas comerciales para distinguir negocio implementado de fixtures. Se conserva el proceso de build nativo y su evidencia separada de una suite JVM exitosa.

## Risks / Trade-offs

- [Las consultas actuales dependen de Party] -> Fallos 502/503 explícitos, timeouts y deadline global; replay completo independiente del proveedor.
- [Party/identificadores pueden cambiar entre llamadas y commit] -> No se promete snapshot distribuido ni sincronización de ciclos de vida; el resultado observado se conserva en el completado y HU03 vuelve a validar al activar.
- [Colecciones históricas grandes aumentan coste de lectura y tamaño del snapshot] -> Recorrer páginas con plazo global y concurrencia limitada, medir bytes/latencia en pruebas; nunca truncar ni cambiar la representación aprobada sin revisión de contrato.
- [Rotación o despliegues mezclados pueden comparar huellas con claves distintas] -> Leer keyId/política del completado y recalcular para cada comparación; desplegar primero todo el keyring, conservar claves y codecs históricos sin TTL.
- [Recuperación accidental en la sesión fallida] -> Fronteras externas explícitas y pruebas de fallo real SQL seguidas de lectura en sesión nueva.
- [Proyección legal histórica en idempotencia] -> Limitarla al DTO público acordado con maskedValue, sin valores completos ni duplicación autoritativa; acceso tenant-qualified y ausencia de payloads sensibles en logs/eventos.
- [Cambio de aceptación de UUIDs con mayúsculas] -> Aclaración confirmada por el usuario; documentar minúsculas, ajustar fixtures y verificar contra el contrato real de Party sin normalización silenciosa.
- [Mayor superficie de serialización nativa] -> Probar tipos anidados, listas, nullable, Instant/LocalDate, JSONB y MAC en el binario real.

## Migration Plan

1. Crear `V2__add_idempotency_fingerprint_metadata.sql` al implementar: añadir fingerprint_version (smallint) y fingerprint_key_id (varchar(64)) a la tabla existente. No editar V1 ni crear tablas nuevas.
2. Para operaciones históricas de T00 permitir ambos metadatos null juntos, o ambos completos y válidos. Para company.create.v1 exigir ambos no nulos, versión positiva y keyId no blanco. Antes de activar la restricción comprobar si existe un completado HU01 previo sin procedencia: detener la migración con diagnóstico controlado, sin inventar la clave activa para su hash. La producción T00 no expone HU01, pero no se asume que la tabla esté vacía.
3. Ampliar modelo/mapper de completados de forma compatible con fixtures y otros namespaces históricos; los nuevos completados HU01 siempre aportan ambos metadatos. Verificar instalación limpia y upgrade con snapshots previos, referencias, checksums y seis tablas intactos.
4. Distribuir secretos/configuración y documentación de headers canónicos antes de habilitar las rutas. Instalar esquema/DTOs/lectores y validar configuración de políticas/keyring y deadlines en el despliegue.
5. Ejecutar pruebas JVM, empaquetadas y nativas contra PostgreSQL 18 y stubs fieles, y publicar contrato/evidencia revisables. No declarar publicación RabbitMQ completada por guardar un evento.
6. Si se revierte la aplicación, conservar V2 y todas las aceptaciones/eventos. Restaurar una versión compatible con metadatos/snapshots o suspender las rutas nuevas. No borrar completados, reiniciar versiones, reparar Flyway ni retirar claves aún necesarias.

No quedan decisiones de producto diferidas para este cambio. Los valores de configuración y los nombres de tipos internos son decisiones de implementación revisables sin alterar los contratos acordados.
