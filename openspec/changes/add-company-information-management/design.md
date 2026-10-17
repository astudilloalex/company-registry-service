# Design

## Context

Ver `proposal.md` para motivación y alcance; las specs de este cambio fijan el comportamiento observable. T00 y HU01 están archivadas y sincronizadas en `openspec/specs/`.

Hechos del código inspeccionado:

- `CompanyResource` implementa POST y GET; `CreateCompanyRequest` usa strings nullable y no distingue omisión de null. La entrada es cerrada y utiliza `@CompanyJsonBody` y `StrictStringDeserializer`.
- `ExpectedVersionParser` ya implementa la convención interna decimal int64; `CompanyResponseCode` ya contiene 409 y 412. La API común valida contexto y conserva Process-Id.
- `CompanyRepository` ofrece insert/find/page/occupied, sin puertos de modificación o lectura protegida. `occupied` comprueba code **o partyId** y no excluye el propio recurso: no sirve para recuperar una actualización.
- `CompanyEntity` tiene `@Version`. V1 protege identidad y auditoría de creación, bloquea cambios efectivos de ARCHIVED y arbitra unicidad tenant/code y tenant/party. El outbox ya admite company.updated.v1 y exige versión de agregado positiva para eventos posteriores a creación.
- `AtomicOperation` y `ReactiveAtomicOperation` aportan transacciones reactivas secuenciales, cierre de sesión y timeouts de servidor. `RegisterCompany` y `DatabaseBudget` muestran recuperación después de rollback/sesión cerrada y precedencia del completado.
- `Fingerprints`, `AcceptedResults`, `ConfiguredFingerprints` y `AcceptedResultCodec` están tipados para creación enriquecida. Sus contratos históricos no se pueden reinterpretar como resultados operacionales de PATCH.
- V2 añade procedencia de fingerprint y restringe la forma de company.create.v1; la tabla ya dispone de expected_aggregate_version y snapshot JSONB. No hacen falta nuevas tablas ni columnas.
- Gradle empaqueta todo `docs/contracts/events/` y el OpenAPI versionado; `verifyProductionIsolation` verifica explícitamente el contrato de creación. Las suites empaquetadas y nativas existentes pueden extenderse para HU02.

## Goals / Non-Goals

**Goals:** definir una aceptación local serializable respecto de una Company, incluyendo no-op; mantener formatos históricos de creación; hacer distinguibles conflictos, precondiciones y resultados inciertos; ofrecer verificación con transacciones reales y proceso productivo.

**Non-Goals:** crear un framework genérico de mutaciones, una jerarquía CRUD, transacciones distribuidas, sincronización de Party o infraestructura de autenticación. Se conserva la premisa de contexto confiable de despliegue de T00/HU01; validar User-Id no introduce por sí solo un nuevo mecanismo de autenticación. El workflow de archivado y el publisher siguen en sus HU.

## Decisions

### 1. Presencia tipada y límites de capas

La API añadirá un DTO de actualización propio y un lector que reconozca exactamente las cuatro propiedades, distinga omisión de null, rechace duplicados/tipos no string y conserve los valores estructurados originales. Se usará `application/json`, consistente con el estilo del proyecto; no se incorporará un motor genérico de JSON Patch/Merge Patch. El nuevo lector seguirá el mecanismo `@CompanyJsonBody` y el envelope común, limitando sus reglas al DTO PATCH.

Un valor puro de cambio de campo representará `absent` y `present(value)`, admitiendo null explícito. Domain será dueño de la política operacional: aplicar únicamente campos presentes, validar nulabilidad, normalizar, comparar información efectiva y restringir estados. La política recibirá valores de negocio, no `RegistryData`, JSON ni entidades ORM. Se reutilizarán `BusinessText` y la validación URL existente; si esta última se extrae de `RegistrationPolicy`, se conservará exactamente su comportamiento y el fingerprint de creación.

Application orquestará un caso de uso de actualización con contexto, Company ID, versión esperada, clave opcional y patch neutral. Sus resultados serán una `CompanyRecord` aceptada y, si hace falta internamente, una disposición de cambio/no-op/replay. API decidirá estados/códigos HTTP y mapeará un DTO operacional propio. Infrastructure implementará persistencia, codecs, claves y composición CDI. Solo los puertos con I/O o serialización histórica efectiva justifican interfaces nuevas.

Alternativas: strings nullable colapsan omisión/null; mapas Jackson en Application acoplan transporte; reutilizar el comando de registro exigiría partyId o borraría campos omitidos. Se descartan.

### 2. Resultado operacional y precedencia de validación

El DTO PATCH tendrá los campos operacionales/auditoría de Company con los mismos nombres de HU01 y omisión HTTP de opcionales nulos. No tendrá Party. `CompanyResponse` y sus mappers de POST/GET conservarán su contrato enriquecido.

Orden de evaluación:

1. Validar headers de contexto, UUID del path, shape JSON, campos/tipos admitidos, un solo If-Match y clave opcional. If-Match es obligatorio también para replay. La gramática decimal estricta se aplica al valor extraído por HTTP; el OWS exterior de RFC 9112 §5.1 es sintaxis de transporte y no forma parte del valor. Espacios internos y headers repetidos siguen siendo inválidos. El lector conserva texto original; no aplica una política de normalización futura antes de decidir qué política histórica corresponde.
2. Si hay clave, buscar `(tenant, company.update.v1, key)` en una sesión local. Si existe, verificar huella con su procedencia y decodificar el resultado sin leer Company actual.
3. Sin aceptación, entrar a la transacción, resolver Company propia de forma protegida y volver a comprobar el completado después de adquirir la protección. Releer la aceptación antes de declarar ausencia/versión/estado actuales evita que un competidor ya confirmado reciba 412 en lugar de replay.
4. Si continúa sin aceptación: ausencia da 404; después comparar versión; después comprobar estado editable; después normalizar/validar con política vigente y comparar los cuatro campos.
5. Confirmar resultado no-op o cambio efectivo con sus escrituras correspondientes y devolverlo solo después del commit.

Una clave existente con otra intención da 409 sin mostrar snapshot ni consultar existencia global. Un ARCHIVED con precondición obsoleta da 412; con versión vigente da 409, incluido `{}`. Un replay coincidente evita ambos rechazos actuales. Nulabilidad, longitud y blancos se comprueban dentro de la política pertinente; los errores estructurales se rechazan siempre en la frontera.

Alternativas: enriquecer PATCH añadiría una dependencia externa innecesaria; comprobar primero versión actual rompería los reintentos ya aceptados. Ambas contradicen las decisiones acordadas.

### 3. Protección de fila y escritura condicionada

Se ampliará `CompanyRepository` con una lectura tenant-qualified para modificación y una escritura condicionada con versión esperada explícita. Infrastructure usará `SELECT ... WHERE tenant_id = :tenant AND id = :id FOR UPDATE` dentro de la transacción local. La espera queda acotada; no habrá llamadas externas durante el lock ni trabajo paralelo en la misma sesión. Las futuras operaciones que involucren Sites conservarán el orden Company antes de Site.

El lock se adquiere también para no-op: leer sin protección y comparar solo en memoria podría aceptar una versión que cambió antes de guardar el completado. La protección termina al confirmar el no-op o la modificación, no permanece abierta mientras se construye una respuesta externa.

Para cambios efectivos se hará una única escritura que incluya tenant, ID y versión original en su predicado y actualice exclusivamente los cuatro campos operacionales, updatedAt, updatedBy y version = original + 1. Una ausencia de fila afectada se tratará como versión obsoleta. No se tocarán status, identidad, auditoría de creación ni hijos. Se evitará cargar/dejar sucia una entidad que pueda hacer un segundo flush: la escritura explícita no se combinará con otro incremento automático de `@Version`. La respuesta se construirá con los valores efectivamente confirmados, manteniendo precisión de fechas.

La unicidad final corresponde a `uq_companies_tenant_code`. No se reutilizará `occupied`, que siempre vería la Party propia ocupada, ni se confiará en un precheck para arbitrar carreras. Una consulta preventiva, si se necesitara para diagnóstico, deberá excluir el propio ID y no sustituirá la restricción SQL. Un no-op nunca ejecutará un UPDATE Company para provocar una validación de versión.

Alternativas: una lectura desprotegida más UPDATE condicionado resuelve cambios efectivos, pero requiere otro mecanismo para el punto de aceptación de no-op. El lock breve unifica ambos caminos y es compatible con los locks que requiere el ciclo de vida; la precondición del cliente sigue siendo optimista.

### 4. Límite transaccional y auditoría

```text
Contexto + entrada estructural validos
                 |
                 v
Clave? --> Completado existente? --> Replay historico
                 |
                 v
BEGIN -- bloqueo Company por tenant/ID
  Releer completado --> Replay si un competidor confirmo
  Ausencia --> 404
  Version distinta --> 412
  ARCHIVED --> 409
  Canonizar/aplicar/comparar
       |
       +--> No-op: Company intacta + completado si hay clave
       |
       +--> Cambio: UPDATE version+1 + completado si hay clave
                    + evento si el modo lo habilita
COMMIT
                 |
                 v
Resultado operacional 200
```

Se reutilizarán `AtomicOperation`, reloj UTC e IDs. Se capturará un instante de aceptación a microsegundos después de la espera protegida; para cambio efectivo será el mismo updatedAt en Company, snapshot y evento. En no-op se conservará la auditoría del agregado: el completado podrá tener su propio createdAt/createdBy del intento que lo aceptó, sin convertirlos en una modificación de Company. Solo un cambio efectivo necesita un nuevo eventId.

No se creará completado sin clave ni se almacenarán fallos. Con clave, incluso `{}` o limpiar un null ya nulo deben confirmar un snapshot para que un replay posterior no dependa del estado nuevo. Si una operación requerida falla, se revierte todo el intento antes de recuperar en otra sesión.

### 5. Fingerprint de intención y snapshot independientes de creación

La operación estable será `company.update.v1`. Se reutilizarán keyring externo y HMAC SHA-256, creando una política de actualización versión 1 y codec de resultado operacional versión 1 propios. No se modificarán los bytes de `CreationFingerprintV1`, las firmas de los puertos de creación ni la interpretación de sus snapshots. Un codec operacional podrá compartir utilidades sin hacer opcional la Party en resultados históricos de creación.

El documento canónico de huella tendrá orden fijo: fingerprintVersion, tenantId, operation, companyId, expectedVersion y patch. expectedVersion se codificará como decimal canónico en string; UUIDs tipados serán canónicos. patch contendrá code, operatingName, description y logoUrl en ese orden. Cada entrada tendrá un booleano present y, solo cuando sea true, un value que puede ser null. Los textos presentes se normalizan con la política aplicable y las URLs preservan contenido. Se usará JSON UTF-8 compacto y escape estable, conservando ceros/nulos/tipos sin concatenaciones ambiguas.

Se excluyen actor, Process-Id, clave idempotente, fechas generadas, datos actuales de Company y cualquier Party. `{}` y `{"description":null}` son intenciones diferentes aunque sean no-op sobre una fila particular. La comparación histórica selecciona versión/keyId del completado y recalcula con el patch original estructurado; no compara únicamente contra una huella precomputada con la clave activa. Rotación, retención sin TTL y MAC independiente por solicitud siguen la política HU01.

El snapshot conservará exclusivamente la proyección operacional aceptada, tipos y nulos internos; la omisión HTTP se aplica después. La decodificación verificará versión de esquema, forma, tenant y Company contra el completado, status de resultado 200, siteId null y precondición original; no reconstruirá desde Company actual. Procedencia, política, clave o snapshot desconocidos/corruptos dan 500 saneado.

Alternativas: firmar JSON crudo confunde orden/capitalización; firmar el estado fusionado exige leer Company en replay y pierde la diferencia entre intención y no-op; usar el codec de creación rompe el contrato enriquecido previo. Se descartan.

### 6. Carreras, rollback y resultado incierto

La segunda lectura del completado después del lock resuelve reintentos de la misma Company que inicialmente no vieron la aceptación. Dos peticiones con la misma clave y distintas Companies pueden bloquear filas diferentes: la PK de completados decide la aceptación. El perdedor debe revertir su UPDATE y evento antes de leer el ganador en una sesión nueva; la comparación de companyId en la huella produce 409, nunca un snapshot de otro recurso.

Se conservará la clasificación por restricción: COMPANY_CODE permite un 409 de negocio, COMPLETION_KEY requiere recuperación durable; una unicidad desconocida o fallo de integridad interno no debe convertirse en un conflicto esperado. Para HU02, un fallo de persistencia inválida después de validación se saneará como 500; 404/409/412 de negocio se resolverán antes o mediante señales conocidas.

Tras una colisión conocida o interrupción de commit con clave, recuperar primero el completado desde la base primaria. Si coincide, replay; si difiere, 409; si la señal original es una colisión de código confirmada y no hay aceptación, 409; si sigue incierto, 503. No se inferirá una aceptación de que la fila actual coincida con el patch, ni un conflicto de la Party propia. Sin clave, no hay promesa de recuperación del resultado original ni reintento automático tras commit incierto.

Se propondrá `company-registry.update.wait-budget`, variable `COMPANY_UPDATE_WAIT_BUDGET`, valor inicial 10s positivo y finito. Un solo `DatabaseBudget` cubrirá las fases locales de consulta, escritura, limpieza y recuperación, aplicando checkpoints de statement_timeout/lock_timeout al servidor con el tiempo restante. No se reiniciará el presupuesto por fase. Los abortos transitorios de PostgreSQL por deadlock/serialización, además de timeout/indisponibilidad, se clasificarán como indisponibilidad recuperable, sin reejecutar ciegamente el comando. Cancelación y cierre deben terminar sin dejar escritura ejecutándose por separado.

Alternativas: recuperar en la sesión fallida viola el límite transaccional; devolver siempre 409 ante incertidumbre inventa un resultado; reintentar toda la mutación sin comprobar el completado puede repetir una aceptación.

### 7. Evento, almacenamiento y compatibilidad con HU03/HU08

Un candidato propio de actualización se construirá desde el resultado efectivo confirmado, con el envelope de once campos y el payload de siete exigidos por la spec. Se reutilizará `OutboxPolicy` y el mapeo neutral `OutboxRecord`. La serialización común del envelope podrá extraerse de `CompanyCreatedOutbox.envelope` sin cambiar su salida histórica ni añadir un publisher.

El esquema será `docs/contracts/events/company.updated.v1.schema.json` y el ejemplo `company.updated.v1.example.json`, siguiendo Draft 2020-12 y el esquema de creación como precedente. El esquema admite los cuatro estados Company; el productor HU02 exige un estado editable. Las invariantes cruzadas Company ID/aggregateId, versión y fechas/actor se comprobarán en productor y tests. causationId será null explícito en HTTP; no se expondrán claves ni description/logoUrl.

stored-only/published insertan PENDING con intentos y versión técnica cero; disabled no inserta. El snapshot original no cambia con replays, nuevas modificaciones o cambios de modo. El handoff documentará exchange company.events y routing key company.updated.v1, dejando publicación y configuración de bindings a HU08. No se implementará archivado para probar el esquema: se usará un candidato/fixture contractual aislado.

### 8. OpenAPI, errores y verificación

Se añadirá PATCH al path existente y schemas de request/DTO/envelope operacionales. El parámetro IdempotencyKey actual es obligatorio para POST: se añadirá un componente opcional específico de PATCH, sin volverlo opcional en creación. ExpectedVersion mantiene formato decimal; la versión retornada va en data.version, sin introducir un protocolo ETag diferente. El request marcará propiedades nullable apropiadas y todas opcionales, con additionalProperties false y ejemplos de limpieza/no-op.

| Condición | Contrato |
| --- | --- |
| Cambio efectivo, no-op o replay | 200 / successful, Company operacional |
| Contexto/body/path/key/If-Match inválidos o ausentes cuando obligatorios | 400 / catálogo existente |
| Media type no soportado | 415 / envelope común |
| Company ausente/ajena sin replay | 404 / not-found |
| Nueva operación con versión obsoleta | 412 / aggregate-version-mismatch |
| ARCHIVED con versión vigente, código ocupado o clave con otra intención | 409 / resource-conflict |
| Indisponibilidad, espera agotada o resultado incierto no resuelto | 503 / dependency-unavailable |
| Snapshot/procedencia corruptos, fallo técnico inesperado | 500 / server-error |

Domain tests verificarán presencia/canonización/igualdad/estado; codec tests verificarán equivalencia y diferencias de intención, snapshots y rotación sin alterar HU01. Tests PostgreSQL verificarán V3 sobre vacío y upgrade, predicados tenant/version, no-op realmente sin UPDATE, arbitraje de código y rollback Company/completado/outbox. Se usarán transacciones y conexiones independientes con barreras observables, no sleeps como prueba de orden ni concurrencia sobre una misma sesión reactiva.

REST y proceso productivo cubrirán Company local sin Party, campos omitidos/null/prohibidos/tipos, contexto, códigos y ejemplos OpenAPI, replay tras cambios/archivado/rotación/reinicio, no-op con y sin clave y los tres modos outbox. Los estados ARCHIVED y cambios posteriores se prepararán con fixtures privilegiados de test, sin crear endpoints HU03. Se ampliarán `verifyProductionIsolation`, pruebas de contratos y evidencias empaquetadas/nativas para exigir el esquema nuevo y ausencia de fixtures. La validación OpenSpec y las suites JVM no sustituyen la ejecución del binario y su imagen no-root.

## Risks / Trade-offs

- [Bloqueo breve también para no-op] -> Da un punto de aceptación consistente; limitar espera, mantener una sola Company por operación y medir con carreras reales, sin I/O externo durante el lock.
- [Colisión de clave entre Companies después de escribir] -> La PK arbitra dentro de la misma transacción; rollback completo y recuperación en sesión nueva, sin devolver snapshots para intenciones distintas.
- [Doble incremento por SQL explícito y @Version] -> Usar un solo mecanismo de escritura, evitar entidades sucias en esa sesión y comprobar revisión/auditoría exactas contra PostgreSQL.
- [Confundir ocupado por Party propia con conflicto de actualización] -> No reutilizar occupied en HU02; traducir solo la restricción de código pertinente y resolver primero completados.
- [Perder compatibilidad de fingerprints o resultados HU01] -> Políticas y codecs de actualización separados, snapshots previos conservados y pruebas de regresión tras V3/rotación.
- [Evento sin description/logoUrl] -> Es un contrato de metadatos acordado; los consumidores obtienen esos valores mediante la API cuando los necesiten. El GET enriquecido conserva su dependencia de Party.
- [Respuesta perdida sin clave] -> No prometer replay ni repetir el comando; documentar el alcance de la clave opcional y la incertidumbre posible.
- [Diferencias de JSON/JSONB/reflexión en nativo] -> Verificar valores nulos, presencia, fechas, MAC y nuevos DTOs/codecs en el proceso real y en imagen no-root.

## Migration Plan

1. Implementar V3 `V3__enforce_company_update_completion_metadata.sql` como ampliación de restricciones sobre la tabla existente. Para operation company.update.v1 exigir procedencia no nula, versión de fingerprint positiva, expected_aggregate_version no nulo/no negativo, response_http_status 200 y site_id null, conservando las demás validaciones y las seis tablas.
2. Antes de validar la restricción, detectar filas update históricas incompatibles y detener con diagnóstico controlado; no inventar claves, completar snapshots a partir del estado actual o borrar aceptaciones. Los registros HU01 y namespaces técnicos mantienen exactamente su contrato.
3. Probar instalación limpia, upgrade desde V2 con aceptaciones HU01, restricciones inválidas, replay enriquecido previo y checksums V1/V2. Mantener runtime DML y migrador separados.
4. Desplegar codecs/política y keyring histórico compatibles junto con el productor, endpoint y contrato OpenAPI. Configurar el presupuesto de actualización; no añadir credenciales de broker ni proveedor para PATCH.
5. Verificar suites JVM/contrato/concurrencia, artefacto empaquetado, binario e imagen nativos y publicar evidencia AC01-AC12 y casos límite. La implementación seguirá las tareas de este cambio solo cuando el usuario invoque apply.
6. Si se revierte la aplicación, conservar V3, Companies, completados, eventos y claves históricas. Restaurar una versión capaz de reproducir HU02 o suspender PATCH; no eliminar aceptaciones ni reparar migraciones para volver atrás.
