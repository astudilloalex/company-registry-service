# Design

## Context

La motivación y el alcance están en [proposal.md](proposal.md). Los contratos verificables están en los tres deltas de `specs/`.

La inspección del proyecto encontró:

- V1 ya define `company_site_addresses`, `AddressEntity`, `RegistryData.AddressRecord`, tipos PHYSICAL/MAILING, coordenadas `numeric(9,6)`, auditoría, borrado y unicidad parcial de primaria por Site/tipo. V6 es la última migración aplicada por el proyecto.
- `SiteRepository`/`ReactiveSiteRepository` ofrecen inserción de hijos y lectura base, pero `addresses()` es una lista histórica no paginada que no filtra eliminadas. No hay casos de uso ni recursos HTTP de dirección.
- `MutateSite` y `TransitionCompany` coordinan Company antes de Site, aceptaciones durables y outbox. El segundo separa las fases locales de la consulta Party y conserva un plazo total monotónico.
- Los adaptadores `CountryReferenceAdapter` y `DivisionReferenceAdapter` están implementados y probados. La evidencia del proveedor está en `docs/contracts/external-references.md`, incluyendo la revisión `fcf309e265b4b16c6ee664fcc6dec3a5380450e2`. El puerto de división actual vuelve a resolver el país y devuelve null tanto si falta el país como la división.
- `SiteMutated.candidate`, `SiteMutatedOutbox`, los codecs/HMAC históricos, el keyring externo y los cursores Site proporcionan patrones reutilizables. El evento publicado ya admite cambios de hijos sin ampliar su payload.

## Goals / Non-Goals

**Goals:**

- Mantener la consistencia de Site y la precedencia acordada durante llamadas remotas y carreras con otros escritores.
- Expresar las reglas de dirección como políticas puras y mantener transporte, SQL/ORM y serialización en sus fronteras existentes.
- Preservar aceptaciones y formatos previos, con una evolución de esquema incremental y verificación en PostgreSQL y ejecutables reales.

**Non-Goals:**

- Agregado o versión independientes para dirección; sustitución de infraestructura de idempotencia, outbox o autorización privada actual.
- Geocodificación, tablas maestras geográficas, historial REST/restauración de direcciones o publicación HU08.
- Cambiar APIs, políticas de huella, snapshots o eventos HU01–HU05 para acomodar HU06.

## Decisions

### 1. Políticas de hijo y orquestación específica de dirección

Domain incorpora valor/política de dirección, presencia de PATCH y par de coordenadas. Decide canonización, precisión, comparación efectiva y consecuencias de selección/reclasificación. Recibe hechos neutrales de elegibilidad geográfica, sin importar DTOs del proveedor ni contratos Application.

Application incorpora comandos CREATE/UPDATE/DELETE y consultas de dirección bajo `application/site`, con resultados neutrales de dirección o comprobante. La orquestación comparte el protocolo de aceptación, conservando diferencias de creación, PATCH y borrado. API parsea/mapea; Infrastructure compone con `AtomicOperation`, los repositorios, referencias y evento existentes.

Se amplía `SiteRepository` con búsqueda vigente, página filtrada, primaria actual y actualizaciones calificadas de dirección. Sus helpers históricos no se usan como GET HTTP. Se evita introducir un repositorio genérico o una colección ORM cargada completa: las operaciones requieren ámbito y límites explícitos y deben mantenerse secuenciales en una sesión reactiva.

### 2. Intención canónica y coordenadas exactas

POST exige los tres campos de la historia aunque SQL tenga default PHYSICAL. PATCH conserva presencia/null/valor hasta seleccionar la política de fingerprint. Se reutiliza `BusinessText` para strip, Locale.ROOT, límites posteriores a normalización y preservación de acentos/espacios internos.

Las coordenadas se leen como `BigDecimal`, nunca mediante double. Se comprueban el par, tipos, límites originales y representabilidad exacta a escala seis; no se permite redondeo. La representación canónica elimina ceros finales redundantes y equipara cero negativo con cero. Se usa igualdad numérica para no-op y huellas, manteniendo números JSON en HTTP y snapshots.

La alternativa de redondear en PostgreSQL cambia el valor recibido antes de los CHECK y haría ambiguas la huella y la validación de límites. Se mantiene `numeric(9,6)`, validando antes de escribir. Un PATCH que toca coordenadas exige ambos miembros aunque ya exista el otro.

### 3. Preparación local, Geografía sin locks y aceptación protegida

Se adopta el patrón de `TransitionCompany`, con una observación geográfica separada de la aceptación:

```text
Entrada valida --> Consulta de aceptacion --> Replay si existe
                         |
                         v
               Proteccion Company --> Site
               Ausencia --> Revision --> Estados --> Decision local
                         |
                  requiere Geografia
                         v
               Finalizar transaccion y sesion
                         |
                         v
                 Consultas geograficas
                         |
                         v
               Nueva proteccion Company --> Site
               Replay --> Ausencia --> Revision --> Estados
                         |
                         v
               Aplicar observacion --> Aceptacion atomica
```

Para CREATE no se busca una dirección previa. Para UPDATE/DELETE se resuelve la dirección vigente antes de comparar Site.version: una eliminación concurrente devuelve 404 incluso si también avanzó Site. Se reconsulta la aceptación después de adquirir Company y antes de evaluar existencia/estado actuales. Las filas de dirección se adquieren siempre después de Site; si se protegen varias, se mantiene orden determinista de IDs.

Si no hace falta Geografía, la primera fase protegida puede confirmar directamente. Si hace falta, la preparación no cambia filas y finaliza antes de HTTP. La fase final repite la misma precedencia, recalcula el resultado sobre el estado protegido y después aplica la observación del proveedor. También los resultados remotos negativos se conservan como observación: no se lanzan antes de resolver un replay, ausencia o revisión surgidos durante HTTP.

Se descartan HTTP dentro de locks y validación preliminar usada como autorización final. El primer enfoque prolonga el bloqueo de otros Sites de la Company; el segundo permite aceptar contra una revisión que ya cambió. No se promete atomicidad distribuida con cambios geográficos posteriores a la observación.

### 4. Validación geográfica por etapas y sin contratos inventados

Creación valida país y división presente. Un cambio canónico de país valida la combinación resultante, conservando la división omitida. Asignar otra división no nula exige país y división ACTIVE. Conservar la combinación, incluyendo valores explícitos equivalentes, omite HTTP. Retirar solamente la división también omite HTTP: elimina una referencia y no asigna otra, conforme a la regla acordada de nuevas asignaciones.

Se resuelve primero el país por alpha-2 y después la división por countryId/canonicalCode. Se añade al puerto/adaptador de división una entrada para país ya resuelto, conservando la entrada alpha-2 actual como fachada compatible. Esto evita la doble consulta y distingue país ausente de división ausente sin inventar rutas. El adaptador sigue validando identidad, código, pertenencia, estado conocido y envelope; la política de elegibilidad recibe solo los hechos necesarios.

País ausente/no activo corta la consulta de división. Un 404 calificado representa ausencia dentro del país; una respuesta exitosa con otro countryId/código es contrato inválido. Se conservan los headers admitidos user-id/process-id, sin traducir tenant a company-id. Se reutilizan URL y timeouts existentes, sin retry genérico ni llamadas en bootstrap.

### 5. Una transacción para dirección, primarias, Site y aceptación

Bajo Company y Site protegidos se calcula el cambio final. Para sustituir una primaria se desmarca y persiste primero la primaria vigente del tipo de destino y después se inserta/promueve/reclasifica el objetivo. El índice parcial es inmediato; se fuerza ese orden de escritura antes de la promoción. Reclasificar una primaria mueve la reserva de tipo y deja el anterior sin selección automática.

Un único instante UTC truncado a microsegundos y actor exacto alimenta todas las filas efectivamente cambiadas, incluido el desmarcado indirecto y Site. La eliminación usa ese instante también para deletedAt/deletedBy. Las auditorías de creación y las direcciones no modificadas se conservan.

Se usa una sola actualización condicionada del Site, conservando información/estado y cambiando únicamente revisión y auditoría. Se reutiliza la semántica bulk de `SiteRepository.update` sin mutar además una entidad administrada que dispare otro incremento por `@Version`. Company se bloquea, sin modificarlo.

El mismo commit incluye completado con clave y `SiteMutatedOutbox` habilitado. Una violación inesperada del índice bajo el protocolo correcto es un fallo saneado con rollback, no otra selección ni una escritura reintentada automáticamente. Un no-op verifica estados y revisión, conserva auditoría y puede persistir solo su aceptación con clave.

### 6. Identidad y snapshots independientes HU06

La identidad existente tenant/operación/clave se reutiliza con los tres namespaces declarados en la spec. Política de huella HU06 versión 1: JSON UTF-8 compacto con orden fijo de fingerprintVersion, tenantId, operation, companyId, siteId, addressId, expectedVersion y request. addressId es null en CREATE; expectedVersion se firma como decimal string, siguiendo los formatos existentes.

POST materializa los defaults canónicos; PATCH firma presencia y valor de cada campo; DELETE firma intención vacía. El orden de campos del request sigue el contrato de creación: addressType, countryCode, addressLine1, administrativeDivisionCode, addressLine2, houseNumber, postalCode, latitude, longitude, isPrimary. Las coordenadas se codifican como decimal canónico sin exponentes dentro del material firmado; no cambia su tipo numérico público. El hash HMAC excluye clave idempotente, actor, proceso, IDs generados, estado actual y modo outbox.

Un codec HU06 formato 1 cerrado guarda operación, propiedad tenant/Company/Site y dirección aceptada o comprobante. Conserva nulls internos, tipos exactos y siteVersion original; verifica identidad, status HTTP, revisión esperada o esperada+1 y auditoría antes de devolverlo. El addressId de UPDATE/DELETE debe coincidir con la ruta firmada. CREATE verifica UUIDv7 y relación con el Site del completado.

No se añade address_id a la tabla compartida: la propiedad persistente sigue calificada por Site y la identidad específica se verifica mediante intención/snapshot. No se reutilizan ni reinterpretan codecs HU04/HU05. Material histórico desconocido produce 500 saneado; las claves/políticas/snapshots se retienen sin TTL. El no-op tiene auditoría de aceptación propia, que puede diferir de la auditoría preservada de la dirección.

### 7. Plazo total y recuperación de incertidumbre

Se propone `company-registry.site-address.wait-budget`, variable `COMPANY_SITE_ADDRESS_WAIT_BUDGET`, default 10s positivo y finito, coherente con los casos actuales. Se implementa un plazo monotónico propio HU06 siguiendo `LifecycleDeadline`: no se comparte su excepción específica de Company ni se reinicia el presupuesto entre fases.

El plazo cubre lookup, sesiones/locks, HTTP, escrituras, commit, cierre y recuperación. Cada llamada externa respeta el menor límite entre su timeout vigente y el tiempo restante. `AtomicOperation` recibe el remanente y conserva su reserva de limpieza; una transacción no finalizada impide abrir recuperación concurrente.

Tras rollback/cierre confirmados puede consultarse la aceptación en una sesión nueva con el mismo plazo. Un commit de resultado incierto devuelve 503; no se repite la escritura, tenga o no clave. El cliente con clave puede resolver la aceptación después mediante el mismo request. No hay suscripciones independientes de escritura ni await/JDBC en el camino de petición.

### 8. Consultas coherentes y cursores propios

El detalle y la página unen dirección con Site/Company calificados, filtran deletedAt null y leen Site.version como escalar en el mismo resultado que las direcciones. Así una página no mezcla direcciones de una revisión con siteVersion leída previamente o cacheada por ORM. Se comprueban padres para distinguir una colección vacía de un Site inexistente.

La consulta aplica filtros antes de limit+1, con orden createdAt/id descendente e índice parcial nuevo. El cursor usa formato 1, dominio HMAC `company.site.address.cursor.v1`, keyId, tenant/Company/Site, filtros efectivos, orden y frontera fecha/ID. Se excluyen actor, proceso, limit y siteVersion: cambiar de página puede observar otra revisión. Se mantiene el límite técnico de cursor de 4096 caracteres del patrón Site.

Se conserva el envelope de array y metadatos superiores. siteVersion aparece en cada dirección; una colección vacía conserva data vacío y obtiene la versión mediante el GET Site existente, sin inventar metadata adicional. Las consultas y los replays no enriquecen datos geográficos.

### 9. Frontera HTTP y defaults de diseño

API conserva los parsers de contexto, UUID de ruta, clave e If-Match decimal; no introduce ETag. Nuevos lectores JSON cerrados distinguen campos omitidos, nulls y tipos exactos. POST responde 201; PATCH/GET 200 con el DTO de 11 campos obligatorios y seis opcionales; DELETE 200 con el comprobante de cinco campos. No se expone revisión propia ni metadatos de borrado en direcciones vigentes.

La validación de campos usa este orden fijo: addressType, countryCode, addressLine1, administrativeDivisionCode, addressLine2, houseNumber, postalCode, isPrimary y coordenadas. En coordenadas: presencia del par, tipos latitude/longitude, combinación null/número, rangos y precisión. El catálogo y la precedencia pública se definen en `specs/business-api-conventions/spec.md`.

Detalles menores fijados por compatibilidad de patrones: DELETE admite body ausente o `{}` como los comandos existentes; los filtros de tipo/booleano son exactos aunque los valores de escritura se normalicen; los opcionales null de POST equivalen a omisión y isPrimary null se rechaza. No se añaden rutas ni filtros de historial/restauración.

### 10. Evidencia de aceptación separada de la planificación

| Criterios HU06 | Evidencia prevista |
| --- | --- |
| AC01, AC14 | HTTP creación/replay, reinicio, rotación y concurrencia de claves |
| AC02–AC04 | Clientes reales contra stub contractual, ausencia/actividad/pertenencia/timeout y rollback |
| AC05–AC07, AC10 | Políticas puras y HTTP para par, límites originales, seis decimales y canonización |
| AC08–AC09 | PostgreSQL con transacciones independientes, sustitución/reclasificación y auditoría indirecta |
| AC11–AC12 | Carreras reales con HU05, otro hijo y cambios locales durante Geografía |
| AC13 | DELETE, histórico conservado, lecturas filtradas y comprobante original |
| AC15 | Matriz de tenant/Company/Site/addressId y cursores de otro ámbito |
| AC16 | Modos outbox, contrato cerrado del evento y fallos inyectados en la aceptación |

Las suites empaquetadas/nativas prueban los mismos contratos sobre rutas reales y metadata/serialización nuevas. Las pruebas de arquitectura mantienen Domain puro y Application sin HTTP/SQL/Quarkus. Esta propuesta no declara ejecutada ninguna prueba HU06 de implementación.

## Risks / Trade-offs

- [Serialización de Sites de una misma Company] La coordinación ya existente bloquea Company primero; mantener HTTP fuera de las fases protegidas y probar contención/plazo limita su duración.
- [Cambio geográfico después de validar] La aceptación refleja una observación contractual, sin transacción distribuida; las referencias existentes se conservan y nuevas asignaciones vuelven a validar.
- [Índice parcial inmediato durante promoción] Democión persistida antes de promoción y rollback conjunto; probar también reclasificación y fallos entre ambas escrituras.
- [Datos históricos de dirección en snapshots] Guardar solo la representación aceptada necesaria para replay, conservar las mismas fronteras privadas y no incluirla en eventos o diagnósticos.
- [Redondeo SQL y escala BigDecimal] Validar exactitud antes de persistir y comprobar equivalencia HTTP/fingerprint/snapshot en JVM y nativo.
- [Consulta histórica base reutilizada por error] Los GET HU06 usan métodos vigentes y paginados explícitos, con casos de eliminadas y páginas vacías.
- [Aceptación incierta o colisión entre Companies] Recuperar solo después de cierre confirmado; una aceptación ajena a la intención produce conflicto sin revelar su snapshot.

## Migration Plan

1. Añadir V7 con preflight de los tres namespaces, CHECK de metadatos HU06 e índice parcial `(site_id, created_at DESC, id DESC) WHERE deleted_at IS NULL`. Mantener V1–V6, tipos, seis tablas, índice primario y roles actuales.
2. Verificar instalación limpia y upgrade de V6 con datos/aceptaciones/eventos/hijos, además de los históricos HU06 incompatibles que deben detenerse sin modificación.
3. Distribuir claves históricas y configuración finita mediante los mecanismos existentes; desplegar la aplicación con las nuevas rutas, codecs y metadata nativa. Confirmar contrato empaquetado y readiness sin proveedores/broker disponibles.
4. Ejecutar la matriz HU06 y la regresión HU01–HU05, documentando por separado resultados JVM, empaquetados y nativos.
5. Ante rollback, conservar V7, filas, completados, eventos y material histórico. Restaurar una versión compatible con replay HU06 o suspender estas rutas; no eliminar aceptaciones, restaurar direcciones ni reiniciar revisiones para degradar.
