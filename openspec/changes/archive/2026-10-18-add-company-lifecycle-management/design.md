# Design

## Context

Ver `proposal.md`, sección Why, para la motivación HU03. La exploración se hizo sobre la base posterior a HU05 y detectó estos puntos que condicionan la implementación:

| Evidencia actual | Consecuencia para HU03 |
| --- | --- |
| `domain/model/CompanyStatus.java` ya contiene las cinco transiciones y ARCHIVED terminal | Reutilizar la matriz; añadir política de elegibilidad y rechazos tipados, sin duplicar estados |
| `PartyReferenceAdapter` implementa `PartyReferences` con identidad/tipo/estado y contexto tenant | Validar activación con esa proyección, sin usar enriquecimiento de `PartyReads` ni identificadores |
| `RegistrationPolicy.eligible` admite LEGAL_ENTITY DRAFT/ACTIVE/INACTIVE | No reutilizarla para activar ni endurecer registro HU01; activación requiere ACTIVE |
| `ReactiveCompanyRepository.update` escribe información y revisión, pero no status | Añadir una escritura explícita de transición que no reescriba los cuatro campos operacionales |
| `SiteRepository` ofrece ocupación HEADQUARTERS como booleano y bloqueo por Site ID | Añadir resolución de la matriz actual, con estado y bloqueo, calificada por tenant/Company |
| El índice parcial `uq_company_sites_open_headquarters` reserva DRAFT/ACTIVE/INACTIVE y excluye CLOSED | La unicidad máxima no prueba existencia ni actividad; revalidar ambas al activar |
| HU04/HU05 bloquean Company antes de crear o bloquear Site, y HU05 protege matriz ACTIVE bajo Company ACTIVE | Integrarse al protocolo existente, incluidos tipos Site distintos de HEADQUARTERS y PATCH no-op |
| `CompanyUpdated` y su esquema admiten payload ARCHIVED; `candidate` exige estado editable | Añadir una fábrica de archivado con validación propia sin debilitar el productor de PATCH |
| `UpdateResultCodec` admite solo company.update.v1 y resultados editables | Definir codec HU03 independiente; compartir forma de datos no implica compartir política histórica |
| `Site.operationalUnder`, consultas Site y snapshots HU04/HU05 ya separan operatividad actual e histórica | Mantenerlos y demostrar el cambio derivado con transiciones Company reales |
| `SiteMutationConcurrencyHttpTest.companyWriter` es un auxiliar JDBC, no un endpoint HU03 | Las pruebas nuevas deben usar comandos Company reales; los fixtures anteriores no son evidencia completa HU03 |
| V1 ya admite los siete event types y ARCHIVED; V5 endurece completados HU05 | V6 endurece solo namespaces HU03; no se necesitan tablas, enums ni catálogo de eventos adicionales |

La estructura vigente mantiene puertos asíncronos en Application, políticas puras en Domain y persistencia/configuración en Infrastructure. El diseño preserva esa organización y el perfil reactivo; no introduce un repositorio o motor genérico de workflows.

## Goals / Non-Goals

**Goals:**

- Establecer un punto protegido de aceptación que coordine transición Company, matriz y escritores existentes, con una única revisión Company resultante.
- Separar la validación remota del tiempo de retención de locks y garantizar que su resultado no sustituya la revalidación local.
- Preservar la semántica histórica de las operaciones anteriores y permitir verificar HU03 desde el proceso JVM/nativo y la base real.

**Non-Goals:**

- Distribuir una transacción o un lock entre Company y Party. El dato Party se observa en el intento; cambios posteriores quedan fuera de esta garantía.
- Generalizar codecs/fingerprints antiguos o refactorizar todo el catálogo de errores/eventos como condición para HU03.
- Cambiar la semántica de consultas, identidad legal, operatividad Site o las restricciones de HU02/HU04/HU05. El alcance funcional restante está en `proposal.md`.

## Decisions

### 1. Políticas puras y un caso de uso cohesivo

Reutilizar `CompanyStatus.permitsTransitionTo` y `AggregateVersion.next()`. Una política Company específica evaluará terminalidad, redundancia y transición; otra decisión pura de elegibilidad de activación recibirá los hechos mínimos Party/matriz. Los rechazos conservarán motivos distintos hasta su traducción API.

Modelar en Application un comando de ciclo de vida con operación ACTIVATE/DEACTIVATE/ARCHIVE, contexto, companyId, expectedVersion y clave opcional, y un caso de uso que componga las fases indicadas abajo. Agrupar colaboradores por responsabilidades como en `MutateSite`, sin interfaces de paso ni dependencias HTTP/ORM/CDI hacia dentro. El resultado será `RegistryData.CompanyRecord`; la API lo proyectará mediante `CompanyOperationalResponse`.

Alternativas descartadas: aplicar `RegistrationPolicy.eligible` admitiría Parties no activas; pasar por PATCH permitiría saltar la frontera explícita del ciclo de vida. Crear tres orquestaciones duplicadas haría divergir replay, auditoría y recuperación.

### 2. Activación en dos fases locales cortas, con HTTP entre ellas

El flujo de una solicitud válida será:

```text
API: contexto + ruta + If-Match + clave opcional + body vacio
  |
  v
Lookup de completado en sesion corta --> replay/conflicto si existe
  |
  v
ACTIVATE: preparacion local protegida
  Company lock --> recheck completado --> propiedad/version/estado
               --> matriz actual lock --> comprobar ACTIVE
  cerrar transaccion/sesion sin escrituras
  |
  v
PartyReferences.find con plazo restante, sin sesion ni locks
  conservar resultado neutral o fallo esperado de dependencia
  |
  v
Aceptacion local protegida
  Company lock --> recheck completado --> propiedad/version/estado
               --> matriz actual lock --> comprobar ACTIVE
               --> evaluar resultado Party
               --> UPDATE condicional + completado opcional + evento habilitado
  |
  v
Commit/finalizacion --> respuesta 200
```

DEACTIVATE y ARCHIVE pasan directamente del lookup inicial a una única transacción de aceptación: Company lock, recheck de completado, propiedad, versión, estado/transición y escrituras. No necesitan bloquear todas las sedes ni consultar Party. Las mutaciones Site ya bloquean el padre, por lo que compiten en Company incluso sin locks Site adicionales por parte de archive/deactivate.

La preparación de activación usa una transacción **sin escrituras de negocio**, pero capaz de bloquear filas; no se marca como SQL READ ONLY. Permite rechazar un fallo local de forma coherente y sin requerir Party disponible. Reconsulta aceptación después del Company lock. Toda sesión se cierra antes de HTTP. Esta fase nunca reserva una clave, incrementa una revisión, crea un evento ni convierte su resultado favorable en aceptación.

La fase final repite todas las comprobaciones. El partyId de Company es inmutable; la matriz puede cambiar sin incrementar Company.version. Por eso no se conserva un Site ID como prueba de elegibilidad entre fases: se vuelve a resolver HEADQUARTERS no CLOSED. El nuevo puerto `currentHeadquartersForUpdate(tenantId, companyId)` se usa solo después del bloqueo Company y devuelve el registro actual o ausencia. Una multiplicidad imposible bajo el índice existente se trata como fallo interno saneado, nunca como autorización para activar.

El orden único es Company → Site. Si falta matriz, el bloqueo del padre serializa también creaciones/reclasificaciones que intenten ocupar la reserva; no se confía en un lock sobre una fila inexistente. Las consultas permanecen calificadas por tenant/Company. Las transacciones separadas impiden reutilizar una entidad obsoleta del primer nivel de caché.

Resultado Party y fallos esperados `ReferenceFailure` se conservan como un resultado neutral hasta la fase final. Si otro intento equivalente confirmó durante HTTP, su replay prevalece incluso si el proveedor falló. Si no hay replay, propiedad, versión, estado y matriz actuales preceden al resultado remoto. Una Party ausente, no LEGAL_ENTITY o no ACTIVE produce los códigos especificados, con tipo antes de actividad. Fallos inesperados o cancelación no se convierten en elegibilidad ni se ocultan como ausencia.

Alternativas descartadas: retener el lock durante HTTP bloquearía otros comandos por una dependencia lenta; validar matriz solo antes de HTTP permite activaciones inválidas; usar solo Company.version no detecta cambios Site; consultar Party antes de resolver un recurso propio permite llamadas innecesarias y precedencias inconsistentes. La segunda fase protegida añade una transacción corta a activate a cambio de estas garantías.

### 3. Escritura Company específica y revisión independiente

Extender `CompanyRepository` con una operación de transición que reciba el resultado neutral, revisión esperada y estado fuente observado. Infrastructure ejecutará un único bulk UPDATE calificado por tenant/id/revisión/estado fuente, escribiendo únicamente status, updatedAt, updatedBy y version=version+1. Cero filas será el fallo neutral de revisión, traducido a 412 después de resolver cualquier aceptación durable aplicable.

La revisión del resultado se construye una sola vez y se valida como expectedVersion+1; el bulk UPDATE no modifica la entidad gestionada ni deja que `@Version` incremente otra vez. Overflow se detecta antes de persistir y termina saneado sin efectos. Conservar `CompanyRepository.update` para HU02 evita que una corrección adquiera implícitamente potestad de cambiar status.

No se escriben Site, direcciones o contactos. Las consultas existentes calculan la nueva operatividad desde Company/Site actuales; el evento pertenece a COMPANY y usa su revisión. Las pruebas verificarán el estado persistido mediante una sesión nueva, evitando que devolver un registro construido o leer caché oculte una escritura que no cambió status.

### 4. Tres namespaces y formatos históricos independientes

La identidad será `(tenant, operation, key)` con company.activate.v1, company.deactivate.v1 y company.archive.v1. Las tres operaciones pueden compartir una implementación de política HU03 versión 1 que incluya la operación en los bytes, conservando namespaces independientes.

Congelar bytes canónicos con el mismo criterio explícito usado por las políticas actuales: objeto ordenado con fingerprintVersion numérico 1, tenantId UUID canónico, operation, companyId UUID canónico, expectedVersion como decimal string y request como objeto vacío. Firmar HMAC SHA-256 con keyring existente y estado MAC por solicitud. La política excluye las observaciones remotas/locales y todo contexto no perteneciente a la intención. Body ausente y `{}` llegan al mismo comando vacío.

El codec HU03 versión 1 puede conservar la forma cerrada `company/code/operatingName/description/logoUrl/audit` de `CompanyRecord`, con nulls internos explícitos. Su registro y validación son propios: resultado 200, siteId null, tenant/Company coincidentes, operación reconocida, revisión exactamente expectedVersion+1 y destino ACTIVE/INACTIVE/ARCHIVED según operación. Tipos estrictos, UUIDs, enums, enteros sin coerción y fechas UTC/microsegundos se verifican al decodificar. updatedAt/updatedBy del snapshot deben coincidir con fecha/actor del completado, porque todas las aceptaciones HU03 son cambios efectivos. La proyección pública omite opcionales nulos y no almacena Party enriquecida.

No se modifica `UpdateResultCodec` para aceptar ARCHIVED, no se reinterpreta su formato 1 ni se modifica ninguna política HU01/HU02/HU04/HU05. Las pruebas de vectores independientes y round-trip JSONB cubren esa separación. Claves/políticas/formatos desconocidos y corrupción son 500 saneado, nunca un 409 ficticio ni permiso para aceptar de nuevo. Mantener keyrings/políticas/formatos sin TTL.

Tras adquirir Company se reconsulta completado antes de comprobar estados. Una colisión de la PK entre Companies distintas puede ocurrir aunque cada una tenga su propio lock: el intento perdedor revierte su transición/evento y recupera el completado en otra sesión solo después de finalizar/cerrar la fallida. Intención distinta produce conflicto sin devolver el snapshot ganador.

Alternativa descartada: ampliar la PK con companyId permitiría reutilizar una clave dentro de la misma operación/tenant y rompería el contrato existente. Guardar solo estado/versión impediría reproducir información operacional y auditoría originales.

### 5. Un plazo de solicitud que incluye la validación remota

Añadir `company-registry.lifecycle.wait-budget`, variable `COMPANY_LIFECYCLE_WAIT_BUDGET`, default `10s`, positiva, finita y representable en nanosegundos. La cuenta monotónica comienza al suscribir el caso de uso e incluye lookup, preparación, HTTP, fase final, commit, limpieza y recuperación. Los timeouts de conexión/lectura existentes de Party siguen vigentes; la suscripción remota se acota además al tiempo restante y se cancela al expirar.

`DatabaseBudget` actual acumula solo tiempo local y pausa entre fases. No basta con instanciarlo una vez y pausar durante HTTP: HU03 necesita un deadline de solicitud del que cada frontera derive su tiempo restante. Encapsularlo en un colaborador pequeño de HU03 o composición equivalente, sin alterar el significado de presupuestos anteriores. No reiniciar el plazo tras HTTP o al recuperar.

Reutilizar `AtomicOperation` y sus checkpoints/timeout de lock/statement y reserva de limpieza. No suscribir escrituras en segundo plano ni lanzar recuperación mientras transactionFinalized sea false. Un timeout de finalización no prueba rollback: devuelve 503. Si la transacción fallida terminó y queda presupuesto, intentar solo lookup de aceptación, sin repetir la transición. Un error de inserción de completion/evento revierte el intento completo; los fallos no se guardan como aceptaciones.

Alternativa descartada: tres presupuestos completos independientes permitirían que preparación, Party y commit excedieran ampliamente el límite anunciado. Un retry automático de la escritura tras incertidumbre puede aplicar otro ciclo.

### 6. HTTP y compatibilidad de errores

Añadir un recurso de comandos Company con DTO/lector vacío propio o utilidad compartida que preserve exactamente los catálogos existentes. No acoplar la nueva API a `EmptySiteCommand` o al traductor de fallos Site. Reutilizar el parser decimal `ExpectedVersionParser`, la validación de contexto y la respuesta `CompanyOperationalResponse` con `ResponseManager`.

Las decisiones de detalle adoptadas por continuidad con HU05 son body ausente/`{}` equivalentes, 200 con representación completa y errores HU03 con solo status/code. La regla de redundancia 409 frente a replay 200 fue confirmada por el usuario. Los códigos/precedencias completos se definen en el delta `business-api-conventions`; la API traduce fallos neutrales sin cambiar HU01/HU02/HU04/HU05. Registrar las tres rutas, precondiciones, respuestas y ejemplos de error en el OpenAPI fuente empaquetado, incluyendo los dos motivos de matriz y la diferencia entre Party inválida y dependencia fallida.

Alternativas descartadas: devolver el DTO enriquecido HU01 impondría llamadas a identificadores y disponibilidad remota a deactivate/archive/replay; un éxito 204 impediría devolver el snapshot operacional acordado.

### 7. Productores de eventos sin ampliar el catálogo V1

Los hechos company.activated.v1 y company.deactivated.v1 siguen el envelope de once campos y payload de siete campos ya usados por CompanyUpdated, restringiendo status a ACTIVE e INACTIVE respectivamente. Pueden compartir estructura/validaciones dentro de los productores HU03; no requieren un framework genérico de eventos. El evento se construye del resultado aceptado con el mismo instante UTC/microsegundos y actor. Su eventId UUIDv7 es distinto de Company y causationId es null explícito.

Para archive añadir una fábrica específica, por ejemplo `CompanyUpdated.archivedCandidate`, que admita solo ARCHIVED; conservar `candidate` de HU02 limitado a estados editables. Un helper privado puede compartir validación estructural. `CompanyUpdatedOutbox` ya representa ARCHIVED y el esquema existente lo permite: añadir un ejemplo de archivado y verificar el productor real sin cambiar el payload/esquema V1.

El caso de uso selecciona exactamente un evento según operación. `OutboxPolicy` conserva disabled frente a stored-only/published; el almacenamiento es transaccional incluso sin clave. No se generan eventos Site por cambio Company, ni company.updated adicional por activar/desactivar. Persistir hechos originales, no reconstruirlos a partir de Company actual para HU08.

Entregar schemas/examples de activated/deactivated y un ejemplo `company.updated.v1.archived.example.json` bajo `docs/contracts/events`, empaquetados en `META-INF/contracts/events`. Ampliar verificaciones de recursos JVM/nativos y el handoff para exchange company.events con routing key igual a eventType. No añadir connector, canal, scheduler o dependencia de broker.

### 8. V6 y evolución conservadora

V6 añade un CHECK de metadatos para los tres namespaces HU03: site_id null, expected_aggregate_version no nulo/no negativo, response_http_status 200, fingerprint_version no nulo/positivo y fingerprint_key_id no nulo con formato válido. Conservar las validaciones existentes de hash, snapshot objeto/formato, PK y Company FK. Evitar CHECK que acepte NULL por lógica ternaria: exigir los IS NOT NULL explícitos.

Preceder el CHECK con inspección de históricos HU03 y diagnóstico controlado 23514 con indicación de revisar procedencia. No crear ni atribuir secretos/formats, reescribir resultados o borrar filas para hacer pasar el upgrade. Actualizar DBML, catálogo de restricciones y pruebas SQL tanto de INSERT como UPDATE. La validación semántica completa del snapshot continúa en el codec; la migración no reconstruye ni interpreta formatos históricos.

Alternativa descartada: modificar V1/V3 permitiría divergencia de checksums y no entregaría un upgrade reproducible desde V5. Los enums y event types ya están soportados; añadir tablas o nuevas columnas de operatividad no aporta comportamiento requerido.

### 9. Evidencia con endpoints reales y matriz de aceptación

Las pruebas Domain cubren el producto completo de estado/operación, terminalidad, redundancia y combinaciones de elegibilidad. Las pruebas HTTP/SQL separan escenarios para que cada fallo identifique una regla; las carreras utilizan conexiones independientes y barreras observables, sin depender de sleeps.

| Criterios issue #5 | Evidencia necesaria |
| --- | --- |
| AC01–AC02 | HTTP real DRAFT/INACTIVE → ACTIVE con Party y matriz válidas, datos/versiones/auditoría persistidos |
| AC03–AC04 | Ausencia de matriz, DRAFT/INACTIVE, matriz CLOSED histórica, reemplazo válido y cero efectos en rechazo |
| AC05–AC06 | Stub fiel de Party para ausencia, NATURAL_PERSON, estados no activos, timeout, 503, identidad/envelope inválidos y headers propagados |
| AC07–AC08 | Desactivar y reactivar Company con Site ACTIVE; GET cambia operatividad conservando Site y sus hijos; replays HU04/HU05 conservan su booleano histórico |
| AC09–AC10 | Archivar desde ambos estados permitidos, preservación de registros/reservas y rechazo posterior de HU02/HU03/HU04/HU05; lectura histórica |
| AC11 | Dos transiciones y transición frente a PATCH efectivo contra la misma revisión; respuesta perdedora 412; no-op HU02 no inventa un incremento |
| AC12 | Tres namespaces; misma clave equivalente/distinta; colisión entre Companies con rollback; actor/proceso distintos; reinicio/K1→K2; replay después de otro ciclo y con Party caída |
| AC13 | Tres modos y operaciones, un único evento aplicable, schemas/ejemplos/igualdades, con/sin clave, rollback y commit incierto |
| AC14 | activate real frente a deactivate/close/reclasificar matriz en ambos órdenes y archive real frente a registro/mutaciones Site; invariantes SQL finales |

Añadir un caso con HTTP Party retenido por barrera que permita completar otro escritor Company y otro Site: prueba ausencia de locks durante HTTP y revalidación final incluso cuando Company.version no cambia. Añadir carrera donde un intento equivalente confirma mientras el otro recibe fallo remoto: debe prevalecer su replay.

Ejecutar regresión de fingerprints/codecs/HTTP/SQL previos, build JVM, contratos e aislamiento productivo; después proceso JVM empaquetado, binario nativo y OCI no root. Las carreras exhaustivas se demuestran en JVM/PostgreSQL; no se presentan como cobertura nativa exhaustiva. El proceso nativo sí debe ejecutar transición/replay, validación Party, eventos reales, upgrade, reinicio/rotación y errores representativos sin fixtures productivos. El documento de aceptación registra resultados ejecutados, no deduce éxito de un esquema o fixture.

## Risks / Trade-offs

- [Party puede cambiar tras su respuesta] → Documentar el límite entre servicios; validar cada activación nueva, sin promesa de sincronización posterior.
- [Company.version no refleja cambios Site] → Releer y bloquear la matriz en la fase final, conservando Company → Site en todos los escritores.
- [Más rondas locales para activate] → Preparación y aceptación cortas con un deadline compartido; ninguna espera remota retiene una sesión.
- [Errores remotos ocultan una aceptación concurrente] → Conservar el resultado remoto y resolver primero completado y precondiciones locales en la fase final.
- [Bulk UPDATE o caché devuelve un estado que no se escribió] → Método explícito de status, predicado de versión y pruebas de lectura desde otra sesión.
- [Relajar el constructor/codec HU02 afecta históricos] → Fábrica de archivado y formatos HU03 separados, con regresión de bytes y round-trips anteriores.
- [Timeout de commit produce resultado desconocido] → Recuperar solo tras finalización confirmada y dentro del plazo; 503 explícito y mismo-key retry sin repetir automáticamente.
- [Históricos HU03 preexistentes no cumplen V6] → Detener upgrade con diagnóstico y preservar procedencia/snapshots; no reparar de forma automática.

## Migration Plan

1. Implementar y validar V6 sobre base limpia y copia de prueba V5 con datos de todas las capacidades previas, manteniendo checksums y seis tablas.
2. Distribuir el keyring histórico requerido a todas las instancias; reutilizar la configuración existente y añadir el presupuesto HU03. No hay nuevos secretos ni permisos de base externa.
3. Empaquetar y verificar OpenAPI, schemas/examples, metadata nativa y ausencia de fixtures. Ejecutar gates JVM, proceso empaquetado, nativo y OCI antes de considerar HU03 terminado; esto no implica despliegue productivo.
4. Cuando se despliegue una versión compatible, Flyway aplica V6 con el rol migrador y runtime conserva DML. Arranque no realiza consultas Party ni requiere broker.
5. Ante rollback de aplicación conservar V6, todos los datos/eventos/completados y políticas/formatos/claves históricos. Restaurar una versión que interprete HU03 o suspender las rutas afectadas; no deshacer migraciones ni reiniciar versiones.
