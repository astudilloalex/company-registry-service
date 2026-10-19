# company-data-integrity Specification

## Purpose

Garantizar la integridad del registro operacional multi-tenant y de sus registros técnicos mediante un contrato de base de datos reproducible y probado.

## Requirements

### Requirement: Esquema reproducible de seis tablas
Flyway SHALL ser la única autoridad del esquema. El contrato SHALL contener exactamente `companies`, `company_sites`, `company_site_addresses`, `company_site_contact_points`, `company_idempotency_records` y `company_outbox_events`, además del historial técnico de Flyway. Las migraciones aplicadas SHALL conservar contenido y checksum.

#### Scenario: Creación y validación repetida
- **WHEN** se migra PostgreSQL 18 vacío y se valida repetidamente sin cambios
- **THEN** existen las seis tablas del contrato y el historial de Flyway conserva sus versiones y checksums

#### Scenario: Credenciales separadas
- **WHEN** se configuran usuarios diferentes para runtime y migraciones
- **THEN** Flyway aplica el esquema con el usuario migrador y runtime opera sin permisos de creación o modificación de esquema

### Requirement: Estados e identificadores del contrato
Los identificadores generados SHALL ser UUIDv7. El esquema SHALL restringir estados Company a DRAFT/ACTIVE/INACTIVE/ARCHIVED; estados Site a DRAFT/ACTIVE/INACTIVE/CLOSED; tipos Site a HEADQUARTERS/BRANCH/WAREHOUSE/OTHER; agregados outbox a COMPANY/COMPANY_SITE; y estados outbox a PENDING/PUBLISHED/FAILED.

#### Scenario: Generación y enumeraciones
- **WHEN** se crean registros usando la generación de IDs del servicio o de PostgreSQL
- **THEN** los IDs generados son UUIDv7 y valores ajenos a los enums persistidos son rechazados
- **AND** se conservan los tipos de dirección PHYSICAL/MAILING y contacto EMAIL/PHONE/MOBILE/WHATSAPP ya definidos por V1

### Requirement: Unicidad e identidad Company
Cada Company SHALL tener PK `id`, unicidad `(tenant_id,id)`, `(tenant_id,code)` y `(tenant_id,party_id)`, versión no negativa y nombres/códigos/actores requeridos no vacíos. `tenant_id` y `party_id` SHALL ser inmutables después del registro.

#### Scenario: Unicidad tenant-scoped
- **WHEN** dos Companies comparten tenant y Party o código canónico
- **THEN** PostgreSQL rechaza la segunda escritura
- **AND** tenants diferentes pueden reutilizar esas referencias o códigos sin compartir la misma fila

#### Scenario: Mutación de identidad o datos inválidos
- **WHEN** se cambia tenant/Party o se escribe versión negativa, nombre/código vacío o actor en blanco
- **THEN** PostgreSQL rechaza la escritura

### Requirement: Propiedad y sede legal única
Site SHALL referenciar Company mediante `(tenant_id,company_id)`, mantener código único por Company y unicidad `(tenant_id,company_id,id)`, versión no negativa y campos requeridos no vacíos. Cada Company SHALL tener como máximo un HEADQUARTERS cuyo estado no sea CLOSED.

#### Scenario: Referencia de otro tenant
- **WHEN** una sede referencia una Company con tenant incompatible
- **THEN** PostgreSQL rechaza la referencia

#### Scenario: Creación concurrente de sedes legales
- **WHEN** dos transacciones independientes intentan crear sedes HEADQUARTERS no cerradas para la misma Company
- **THEN** como máximo una confirma y la otra recibe una violación de unicidad
- **AND** una sede HEADQUARTERS histórica CLOSED no impide crear el reemplazo

### Requirement: Direcciones e integridad de coordenadas
Las direcciones SHALL referenciar una sede, conservar país y referencia administrativa, y permitir como máximo una primaria no eliminada por sede/tipo. Latitud y longitud SHALL ser ambas nulas o estar ambas pobladas, en rangos [-90,90] y [-180,180]. El borrado lógico SHALL conservar metadatos coherentes.

#### Scenario: Coordenadas inválidas
- **WHEN** se guarda una sola coordenada o valores fuera de rango
- **THEN** PostgreSQL rechaza el registro
- **AND** acepta el par nulo, el par válido y los límites inclusivos

#### Scenario: Primaria y eliminación lógica
- **WHEN** se intenta duplicar una dirección primaria vigente del mismo tipo o eliminar sin actor/fecha coherentes
- **THEN** PostgreSQL rechaza la escritura
- **AND** una dirección eliminada y desmarcada como primaria permanece en el historial sin ocupar la unicidad vigente

### Requirement: Contactos e integridad histórica
Los contactos SHALL referenciar una sede, restringir su tipo a EMAIL/PHONE/MOBILE/WHATSAPP y permitir como máximo un primario no eliminado por sede/tipo. Los valores telefónicos SHALL almacenarse en formato E.164 y el borrado lógico SHALL conservar actor y fecha coherentes.

#### Scenario: Restricciones de contacto
- **WHEN** se inserta un segundo primario vigente del mismo tipo, teléfono no canónico, valor vacío o borrado incoherente
- **THEN** PostgreSQL rechaza la escritura
- **AND** permite conservar contactos eliminados sin bloquear un nuevo primario

### Requirement: Idempotencia tenant y Company qualified
La idempotencia SHALL usar PK `(tenant_id,operation,idempotency_key)`, fingerprint y resultado JSON versionado reproducible. site_id SHALL ser nullable para operaciones Company y obligatorio para company.site.create.v1. Sus referencias SHALL impedir tenant/Company/Site incompatibles y validar versión esperada, estado y snapshot. Cada completado HU01/HU02/HU04 SHALL identificar política de fingerprint y clave HMAC sin guardar el secreto.

#### Scenario: Idempotencia de Company y Site
- **WHEN** se guarda una finalización de operación Company con Company propia y `site_id` nulo, o una operación Site con Site de esa Company y tenant
- **THEN** PostgreSQL acepta el registro
- **AND** rechaza sitios de otra Company/tenant, claves repetidas, hash inválido, versión de esquema no positiva o snapshot no objeto

#### Scenario: Completado HU01 reproducible
- **WHEN** se confirma company.create.v1
- **THEN** el registro conserva hash hexadecimal de 64 caracteres, versión positiva de fingerprint, identificador no vacío de clave, HTTP 201 y snapshot de respuesta con versión positiva
- **AND** siteId y versión esperada son null porque el registro crea un agregado nuevo

#### Scenario: Completado HU02 reproducible
- **WHEN** se confirma company.update.v1 con clave, tanto si cambia Company como si es no-op
- **THEN** conserva Company propia, siteId null, versión esperada original no negativa, HTTP 200, hash y procedencia completos y snapshot con versión de esquema positiva
- **AND** el snapshot reproduce la Company operacional aceptada sin Party ni dependencia de sus valores actuales

#### Scenario: Completado HU04 reproducible
- **WHEN** se confirma company.site.create.v1
- **THEN** conserva Company y Site propios, site_id no nulo, versión esperada null, HTTP 201, hash y procedencia completos y snapshot con versión positiva
- **AND** el resultado local original reproduce Site DRAFT/version 0/effectivelyOperational false sin consultar datos actuales

### Requirement: Integridad persistente del outbox
Outbox SHALL tener unicidad `(tenant_id,aggregate_type,aggregate_id,aggregate_version,event_type)`, índice por estado/próximo intento/creación, payload JSONB, correlación/causalidad, auditoría, intentos, próxima ejecución, diagnóstico y versionado optimista.

#### Scenario: Evento válido y duplicado
- **WHEN** se persiste un evento válido y se repite su identidad de agregado/versión/tipo
- **THEN** la primera escritura se conserva y PostgreSQL rechaza la duplicada

#### Scenario: Metadatos inválidos
- **WHEN** un evento tiene payload no objeto, correlación vacía, intentos/versión negativos o estado de entrega incompatible con sus metadatos
- **THEN** PostgreSQL rechaza la escritura
- **AND** los tipos de evento y su correspondencia con agregado/versiones se mantienen alineados con V1

### Requirement: Verificación exhaustiva del contrato SQL
Cada PK, UNIQUE, FK, índice parcial, CHECK y protección de identidad/estado definida en el contrato SHALL tener evidencia automatizada contra PostgreSQL 18, con casos válidos e inválidos y cobertura concurrente cuando corresponda.

#### Scenario: Catálogo de restricciones verificadas
- **WHEN** se ejecuta la suite de migraciones
- **THEN** el inventario de restricciones del esquema se contrasta con sus pruebas y una restricción ausente o debilitada causa fallo

### Requirement: Evolución incremental de metadatos de fingerprint
El cambio SHALL ampliar la tabla de completados con `fingerprint_version` y `fingerprint_key_id` mediante una nueva migración Flyway, conservando V1, su checksum, seis tablas y datos existentes. SHALL exigir metadatos coherentes para nuevas aceptaciones HU01 y evitar atribuir claves o políticas inventadas a registros históricos.

#### Scenario: Instalación limpia y actualización
- **WHEN** se aplica la migración sobre una base vacía o una base T00 con registros técnicos existentes
- **THEN** permanecen las identidades, snapshots, auditoría, claves compuestas y restricciones previas
- **AND** se verifica versión positiva y clave no vacía de longitud acotada para los nuevos completados HU01

#### Scenario: Históricos sin procedencia verificable
- **WHEN** un registro previo no permite establecer qué política o secreto produjo su hash
- **THEN** se preserva sin asignarle por defecto la clave activa actual
- **AND** si afecta a company.create.v1 y no puede satisfacerse el nuevo contrato, la preparación/migración lo detecta y detiene con diagnóstico controlado sin eliminarlo ni reemplazarlo

#### Scenario: Validación SQL de metadatos
- **WHEN** una nueva aceptación HU01 intenta omitir un metadato, usar versión no positiva o identificador de clave vacío o excesivo
- **THEN** la validación persistente rechaza la escritura sin debilitar las referencias tenant/Company/Site

### Requirement: Snapshot enriquecido original preservado
El completado de creación Company (`company.create.v1`) SHALL guardar el resultado original completo y versionado, incluyendo la proyección pública de Party e identificadores enmascarados. SHALL conservar sus tipos, valores opacos y nulabilidad interna sin convertirla en una tabla espejo o columnas legales de Company. Su lectura SHALL reproducir la representación HTTP aceptada.

#### Scenario: Round-trip y respuesta histórica
- **WHEN** se guarda y recupera el snapshot con Party, legalEntityDetails e identifiers
- **THEN** mantiene IDs, estados, versiones, fechas, actores, flags booleanos y maskedValue originales
- **AND** la omisión HTTP de opcionales nulos no cambia el contenido efectivo ni requiere consultar proveedores

#### Scenario: Material ajeno al resultado público
- **WHEN** se serializa un completado
- **THEN** no incorpora secretos HMAC, respuestas técnicas del proveedor, valores legales completos de identificadores ni datos de transporte que deban pertenecer al reintento

### Requirement: Conservación de aceptaciones durables
El cambio SHALL preservar completados, claves y reglas necesarias para sus replays, sin introducir expiración o limpieza automática que permita repetir una creación aceptada. Un rollback de aplicación SHALL conservar la migración, Companies, completados y outbox confirmados.

#### Scenario: Reversión de aplicación o rotación
- **WHEN** se revierte un despliegue o se rota la clave activa
- **THEN** se conservan los datos y claves históricas requeridos, y se restaura una aplicación compatible o se suspenden las rutas afectadas
- **AND** no se usa reparación de V1, borrado de completados o reinicio de versiones para recuperar compatibilidad

### Requirement: Evolución incremental de completados de actualización
La evolución HU02 SHALL añadir V3 sin modificar V1/V2 ni sus checksums, crear tablas o eliminar datos. SHALL conservar las seis tablas, referencias, restricciones y aceptaciones HU01. Los registros históricos incompatibles de company.update.v1 SHALL detectarse antes de habilitar el contrato, sin inventar procedencia ni transformar snapshots aceptados.

#### Scenario: Instalación limpia y upgrade de HU01
- **WHEN** se instala el esquema completo en PostgreSQL 18 vacío o se actualiza una base V2 con Companies, completados y eventos HU01
- **THEN** V3 se aplica y valida preservando los datos, claves compuestas y checksums previos
- **AND** un replay de creación sigue devolviendo su snapshot enriquecido original

#### Scenario: Aceptación histórica HU02 incompatible
- **WHEN** una fila previa company.update.v1 no satisface los metadatos requeridos
- **THEN** la preparación o migración se detiene con diagnóstico controlado
- **AND** no borra esa aceptación ni atribuye su hash a la clave activa para continuar

### Requirement: Forma persistente de la aceptación HU02
Los completados company.update.v1 SHALL exigir fingerprint_version positivo, fingerprint_key_id válido no nulo, expected_aggregate_version no negativo no nulo, response_http_status 200 y site_id null, además de las restricciones existentes de hash, snapshot y propiedad. Una omisión o incoherencia SHALL rechazarse persistentemente.

#### Scenario: Metadatos inválidos
- **WHEN** un completado HU02 omite procedencia o versión esperada, usa una clave inválida, versión negativa, status distinto de 200 o siteId no nulo
- **THEN** PostgreSQL rechaza la inserción o actualización sin debilitar los contratos HU01 ni namespaces técnicos históricos

#### Scenario: Referencia, snapshot y clave incompatibles
- **WHEN** el completado apunta a Company de otro tenant, duplica su clave compuesta, usa hash inválido, snapshot no objeto o versión de snapshot no positiva
- **THEN** la persistencia lo rechaza también para company.update.v1

### Requirement: Snapshot operacional original conservado
La aceptación HU02 SHALL conservar el resultado operacional completo, con tipos, auditoría, versión, valores opacos y nulabilidad interna originales. SHALL seguir siendo reproducible tras reinicio, mutaciones posteriores y rotación de clave. SHALL NOT introducir TTL, limpieza automática o reinterpretación de las políticas y snapshots HU01.

#### Scenario: Round-trip del resultado parcial limpiado
- **WHEN** un resultado aceptado con description/logoUrl nulos se guarda y recupera
- **THEN** su representación interna conserva esos nulos y su representación HTTP los omite según el contrato
- **AND** IDs, versión, fechas y actores permanecen iguales al resultado aceptado

#### Scenario: No-op durable y reversión de despliegue
- **WHEN** se guarda un no-op con clave o se revierte una versión de aplicación después de aceptar actualizaciones
- **THEN** se conserva el resultado y el material histórico necesario para su replay
- **AND** la reversión mantiene V3 y datos confirmados, restaurando una aplicación compatible o suspendiendo las rutas afectadas

### Requirement: Evolución incremental del soporte HU04
La evolución HU04 SHALL añadir una migración posterior a V3 que preserve checksums V1–V3, las seis tablas, privilegios separados y datos existentes. SHALL validar nuevos metadatos de creación Site sin inventar procedencia ni reconstruir aceptaciones históricas. Un histórico HU04 incompatible SHALL detener la migración con diagnóstico controlado.

#### Scenario: Instalación limpia y upgrade
- **WHEN** se migra PostgreSQL 18 vacío o una base V3 con Companies, Sites y aceptaciones/eventos HU01/HU02
- **THEN** instala el nuevo soporte manteniendo datos, restricciones anteriores y checksums
- **AND** siguen funcionando los replays anteriores y runtime conserva permisos DML sin DDL

#### Scenario: Aceptación histórica incompatible
- **WHEN** una fila previa company.site.create.v1 no cumple procedencia, resultado o propiedad exigidos
- **THEN** la migración se detiene antes de habilitar el contrato
- **AND** no elimina la fila, inventa una clave histórica ni sustituye su snapshot con datos actuales

### Requirement: Forma persistente de aceptación de creación Site
Los completados company.site.create.v1 SHALL exigir site_id no nulo, expected_aggregate_version null, response_http_status 201, fingerprint_version positivo no nulo y fingerprint_key_id no nulo con formato `[A-Za-z0-9_-]{1,64}`. SHALL conservar las restricciones de hash, snapshot, clave compuesta y referencias tenant/Company/Site.

#### Scenario: Metadatos obligatorios inválidos
- **WHEN** una inserción o actualización HU04 omite Site/procedencia, usa versión no positiva, keyId inválido, versión esperada no null o estado distinto de 201
- **THEN** PostgreSQL rechaza la escritura sin debilitar HU01/HU02 ni namespaces técnicos históricos

#### Scenario: Referencias y contenido incompatibles
- **WHEN** la aceptación referencia Site de otra Company/tenant, duplica su clave, usa hash no hexadecimal de 64 caracteres, snapshot no objeto o versión de snapshot no positiva
- **THEN** la persistencia rechaza la escritura

### Requirement: Snapshot local original de Site conservado
La aceptación HU04 SHALL conservar todos los valores y tipos de la proyección pública local original y datos de propiedad necesarios para verificarla. SHALL mantener operatividad false, status DRAFT, revisión 0 y auditoría original sin incorporar Party/direcciones/contactos ni material secreto. Su lectura SHALL verificar coherencia con tenant/Company/Site del completado.

#### Scenario: Round-trip y estado posterior
- **WHEN** se guarda y recupera el snapshot después de cambiar, activar o cerrar Site o archivar Company
- **THEN** conserva exactamente IDs, texto, tipo, booleano, enteros, fechas y actores originales
- **AND** el replay no calcula otra operatividad ni modifica la aceptación

#### Scenario: Snapshot corrupto o ajeno al propietario
- **WHEN** su versión, identidad, tipos o propiedad no corresponden al completado
- **THEN** el servicio devuelve un fallo 500 saneado sin devolver datos ajenos ni reconstruir el resultado

### Requirement: Soporte persistente de paginación Site
El esquema SHALL incluir un índice de company_sites por tenant_id, company_id, created_at DESC e id DESC para el listado acordado. SHALL preservar código único por Company, la FK compuesta y el índice parcial de HEADQUARTERS no cerrado. La evolución SHALL NOT añadir una columna mutable de operatividad efectiva.

#### Scenario: Índice y restricciones instalados
- **WHEN** se inspecciona el catálogo después de migrar
- **THEN** el índice incluye ámbito y orden completos y está listo/válido
- **AND** permanecen las restricciones de propiedad, código e histórico legal, documentadas y verificadas con PostgreSQL 18

### Requirement: Reversión compatible con aceptaciones HU04
Un rollback de aplicación SHALL conservar migraciones, Sites, completados, eventos y claves históricas necesarios para replay HU04. SHALL restaurar una aplicación compatible o suspender las rutas afectadas, sin borrar aceptaciones, reiniciar revisiones ni introducir TTL.

#### Scenario: Reversión después de aceptar sedes
- **WHEN** se revierte un despliegue que ya confirmó registros HU04
- **THEN** conserva el estado durable y el material histórico para recuperar las respuestas aceptadas
- **AND** siguen intactos los snapshots enriquecidos HU01 y operacionales HU02

### Requirement: Evolución incremental del soporte HU05
El soporte HU05 SHALL añadirse mediante una migración posterior a V4, conservando seis tablas, checksums V1–V4, datos, restricciones y separación runtime/migrador. SHALL validar la forma de sus completados sin modificar aceptaciones anteriores. Un histórico HU05 incompatible SHALL detener la migración con diagnóstico controlado, sin inventar procedencia ni borrar resultados.

#### Scenario: Instalación limpia y upgrade desde V4
- **WHEN** se migra una base PostgreSQL 18 vacía o una base V4 con Companies, Sites y aceptaciones/eventos HU01/HU02/HU04
- **THEN** se instala el soporte HU05 conservando datos, claves, replays y checksums anteriores
- **AND** runtime conserva permisos DML sin DDL

#### Scenario: Histórico incompatible
- **WHEN** una fila previa de alguna de las cuatro operaciones HU05 carece de propiedad, resultado o procedencia exigidos
- **THEN** la migración se detiene sin atribuirle la clave activa, reconstruir su snapshot ni eliminar la fila

### Requirement: Forma persistente de aceptación de mutaciones Site
Todo completado HU05 SHALL exigir Company/Site propios, site_id no nulo, expected_aggregate_version original no negativo no nulo, response_http_status 200, fingerprint_version positivo y fingerprint_key_id válido no nulo. SHALL conservar PK tenant/operación/clave, hash hexadecimal de 64 caracteres, snapshot objeto versionado y FKs compuestas.

#### Scenario: Las cuatro operaciones y no-op
- **WHEN** se guarda company.site.update.v1, company.site.activate.v1, company.site.deactivate.v1 o company.site.close.v1
- **THEN** exige la misma forma persistente de metadatos, también para un PATCH no-op con clave

#### Scenario: Metadato faltante o inválido
- **WHEN** se omite Site, versión esperada o procedencia, se usa versión negativa, política no positiva, keyId inválido o estado distinto de 200
- **THEN** PostgreSQL rechaza inserción y actualización de ese completado

#### Scenario: Referencia, identidad y contenido inválidos
- **WHEN** el completado referencia Site de otra Company/tenant, duplica tenant/operación/clave, usa hash inválido, snapshot no objeto o versión de snapshot no positiva
- **THEN** la persistencia lo rechaza manteniendo las restricciones anteriores

#### Scenario: Operaciones anteriores preservadas
- **WHEN** se guardan o recuperan aceptaciones HU01/HU02/HU04 válidas
- **THEN** conservan sus contratos de site_id, versión esperada, estado y snapshots sin reinterpretarlos como HU05

### Requirement: Snapshot original de mutación Site conservado
Los snapshots HU05 SHALL conservar todos los valores y tipos de la representación pública aceptada, incluyendo operatividad, revisión y auditoría originales. SHALL permitir verificar identidad y propiedad contra el completado sin leer Company/Site actuales. SHALL interpretar sus formatos independientemente del snapshot de creación HU04.

#### Scenario: Round-trip de actualización, transición y no-op
- **WHEN** una aceptación HU05 se guarda y recupera desde JSONB
- **THEN** conserva UUIDs, textos, enum, entero, booleano, fechas UTC/microsegundos y actores originales
- **AND** un no-op puede conservar auditoría Site anterior a la fecha/actor del completado

#### Scenario: Revisión e intención coherentes
- **WHEN** se interpreta un completado HU05
- **THEN** la revisión del snapshot es la esperada más uno para un cambio efectivo, o la misma para un PATCH no-op
- **AND** activate/deactivate/close requieren su estado resultante y revisión incrementada

#### Scenario: Snapshot corrupto o propietario incompatible
- **WHEN** la versión de formato es desconocida o identidad, tipos, operación, revisión o propiedad no satisfacen su contrato
- **THEN** devuelve 500 server-error saneado sin revelar datos ajenos, reparar la aceptación ni repetir la mutación

### Requirement: Retención y reversión compatibles HU05
Aceptaciones HU05, políticas, formatos y claves históricas necesarios SHALL conservarse sin TTL ni limpieza que permita otra aceptación con la misma identidad. Revertir la aplicación SHALL conservar migraciones, Sites, completados y eventos confirmados, restaurando una versión compatible o suspendiendo las rutas afectadas.

#### Scenario: Rotación y reinicio
- **WHEN** se activa K2 después de aceptar con K1, se reinicia o se utiliza otra instancia
- **THEN** conserva K1 y el material histórico necesario para el mismo replay

#### Scenario: Reversión de despliegue
- **WHEN** se revierte una aplicación que ya aceptó mutaciones HU05
- **THEN** conserva el esquema y todos los datos confirmados, sin reiniciar revisiones ni borrar completados
- **AND** siguen intactas las aceptaciones enriquecidas HU01, operacionales HU02 y de registro HU04

### Requirement: Evolución incremental del soporte HU03
HU03 SHALL añadir V6 después de V5 conservando las seis tablas, checksums V1–V5, restricciones, privilegios y datos existentes. SHALL validar la forma de sus completados sin inventar procedencia, borrar filas ni reescribir snapshots. Un histórico HU03 incompatible SHALL detener la migración con diagnóstico controlado.

#### Scenario: Instalación limpia y upgrade desde V5
- **WHEN** se migra PostgreSQL 18 vacío o una base V5 con Companies, Sites, hijos, aceptaciones y eventos previos
- **THEN** instala V6 y valida sin alterar datos ni checksums anteriores
- **AND** runtime conserva DML sin DDL y siguen disponibles replays HU01/HU02/HU04/HU05

#### Scenario: Histórico HU03 incompatible
- **WHEN** una fila anterior company.activate.v1, company.deactivate.v1 o company.archive.v1 no cumple la forma exigida
- **THEN** la migración se detiene antes de habilitar el contrato con diagnóstico saneado
- **AND** no asigna la clave activa a ese hash ni reconstruye el resultado desde Company actual

### Requirement: Forma persistente de aceptación de ciclo de vida Company
Los completados company.activate.v1, company.deactivate.v1 y company.archive.v1 SHALL exigir Company propia, site_id null, expected_aggregate_version no nulo/no negativo, response_http_status 200, fingerprint_version positivo y fingerprint_key_id válido no nulo. SHALL conservar PK tenant/operación/clave, hash válido, snapshot objeto versionado y FK compuesta Company.

#### Scenario: Tres operaciones con la misma forma
- **WHEN** se inserta o actualiza una aceptación HU03 válida
- **THEN** se verifica la misma forma para las tres operaciones y keyId cumple `[A-Za-z0-9_-]{1,64}`
- **AND** no se exige un completado cuando el comando carece de Idempotency-Key

#### Scenario: Metadatos omitidos o inválidos
- **WHEN** el completado incluye site_id, omite versión esperada/procedencia, usa versión negativa, política no positiva, keyId inválido o resultado distinto de 200
- **THEN** la base rechaza inserción y actualización

#### Scenario: Propiedad y contenido incompatibles
- **WHEN** apunta a Company ajena al tenant, duplica su clave compuesta, usa hash no hexadecimal de 64 caracteres, snapshot no objeto o formato no positivo
- **THEN** la base rechaza la escritura sin debilitar restricciones anteriores

### Requirement: Snapshot operacional de transición original
El snapshot HU03 SHALL conservar identidad tenant/Company/Party, información operacional con nulabilidad interna explícita, estado resultante, revisión y cuatro valores de auditoría originales. SHALL excluir enriquecimiento Party y datos Site. Su interpretación SHALL ser independiente de formatos HU01/HU02/HU04/HU05 y no consultar agregados actuales.

#### Scenario: Round-trip de cada estado resultante
- **WHEN** se guarda y recupera desde JSONB una activación, desactivación o archivado
- **THEN** conserva tipos UUID/string/entero, ACTIVE/INACTIVE/ARCHIVED, UTC/microsegundos y actores exactos
- **AND** description/logoUrl nulos internos se omiten al representar la respuesta HTTP

#### Scenario: Resultado posterior diferente
- **WHEN** Company cambia otra vez, se archiva o Party deja de estar disponible después de aceptar
- **THEN** el replay sigue devolviendo el resultado operacional original sin consultar esos datos actuales

### Requirement: Coherencia verificable de completado HU03
La lectura de un completado HU03 SHALL verificar operación, resultado, propiedad, formato, tipos, procedencia y revisión esperada más uno sin desbordamiento. activate SHALL exigir resultado ACTIVE, deactivate INACTIVE y archive ARCHIVED. Un histórico incompatible SHALL producir 500 server-error saneado sin reparar ni reejecutar.

#### Scenario: Identidad o semántica corruptas
- **WHEN** snapshot y completado discrepan en tenant/Company, operación/estado, revisión, fecha, nulabilidad o tipos
- **THEN** se rechaza el histórico sin revelar snapshot ajeno ni reinterpretarlo como no-op

#### Scenario: Formato o material histórico desconocidos
- **WHEN** falta el codec/política/clave requeridos por una aceptación
- **THEN** no sustituye su versión o keyId por los activos ni convierte el problema en conflicto de contenido

#### Scenario: Auditoría de aceptación efectiva
- **WHEN** se interpreta el resultado de cualquiera de las tres transiciones aceptadas
- **THEN** snapshot.updatedAt coincide con la fecha del completado y snapshot.updatedBy con su actor
- **AND** una discrepancia se trata como histórico inválido, porque HU03 no acepta comandos no-op

### Requirement: Conservación de aceptaciones HU03 y compatibilidad anterior
Aceptaciones, políticas, formatos y claves históricas HU03 SHALL conservarse sin TTL ni limpieza automática. Un rollback SHALL conservar V6 y todos los agregados/completados/eventos confirmados, restaurando código compatible o suspendiendo rutas afectadas. HU03 SHALL preservar los bytes y semántica de fingerprints/snapshots anteriores.

#### Scenario: Rotación, reinicio y otra instancia
- **WHEN** se acepta con K1 y luego se activa K2, reinicia el servicio o atiende otra instancia
- **THEN** reproduce usando K1 y la política/formato originales

#### Scenario: Rollback y replays anteriores
- **WHEN** se revierte una aplicación después de aceptar HU03
- **THEN** conserva V1–V6, Companies, Sites, hijos, completados, eventos y claves históricas
- **AND** siguen intactos el snapshot enriquecido HU01, el operacional HU02 y los formatos Site HU04/HU05

### Requirement: Evolución incremental del soporte HU06
HU06 SHALL añadir una migración posterior a V6 preservando las seis tablas, checksums V1–V6, datos, restricciones y privilegios runtime/migrador. SHALL detectar aceptaciones HU06 históricas incompatibles antes de habilitar el contrato, sin inventar procedencia, borrar filas o reconstruir snapshots. SHALL conservar las restricciones de coordenadas, identidad, auditoría, borrado e índice parcial de primaria.

#### Scenario: Instalación limpia y upgrade desde V6
- **WHEN** se migra PostgreSQL 18 vacío o una base V6 con Companies, Sites, direcciones, contactos y aceptaciones/eventos previos
- **THEN** se instala y valida el soporte HU06 conservando los datos y checksums anteriores
- **AND** runtime conserva DML sin DDL y siguen disponibles los replays HU01–HU05

#### Scenario: Histórico HU06 incompatible
- **WHEN** una aceptación previa del namespace HU06 no satisface los metadatos exigidos
- **THEN** la migración se detiene con diagnóstico controlado sin asignar la clave activa ni alterar su resultado

### Requirement: Forma persistente de aceptación de dirección
Los completados company.site.address.create.v1, company.site.address.update.v1 y company.site.address.delete.v1 SHALL exigir Company/Site propios, site_id no nulo, expected_aggregate_version original no nulo/no negativo, fingerprint_version positivo y fingerprint_key_id válido no nulo. response_http_status SHALL ser 201 para create y 200 para update/delete. SHALL preservar PK tenant/operación/clave, hash y snapshot versionado.

#### Scenario: Metadatos requeridos en cada operación
- **WHEN** se inserta o modifica un completado HU06 omitiendo Site, versión esperada o procedencia, o usando resultado HTTP incorrecto
- **THEN** PostgreSQL rechaza la escritura, incluido un completado de PATCH no-op
- **AND** fingerprint_key_id cumple `[A-Za-z0-9_-]{1,64}`, hash es hexadecimal de 64 caracteres y snapshot es un objeto con formato positivo

#### Scenario: Referencia de otro ámbito
- **WHEN** el completado referencia Site de otra Company/tenant o duplica tenant/operación/clave
- **THEN** las restricciones persistentes rechazan la escritura sin debilitar los namespaces anteriores

### Requirement: Snapshot histórico completo de dirección
Los snapshots create/update SHALL conservar la representación aceptada, siteVersion, auditoría, tipos numéricos y opcionales nulos internos, además de propiedad verificable contra el completado. SHALL excluir datos técnicos de proveedores y secretos. El formato HU06 SHALL ser independiente de codecs Company/Site anteriores y reproducible sin leer filas ni proveedores actuales.

#### Scenario: Round-trip después de modificar o eliminar
- **WHEN** se recupera una aceptación después de cambios de dirección, eliminación, cierre Site o archivado Company
- **THEN** conserva identificadores, país/división, líneas, coordenadas, flag primario, revisión y auditoría originales
- **AND** mantiene números JSON exactos y omisión HTTP de opcionales nulos sin reconstruirlos

### Requirement: Snapshot mínimo de eliminación
El snapshot delete SHALL conservar la propiedad interna verificable y el comprobante original de addressId, siteId, siteVersion, deletedAt y deletedBy. SHALL mantener HTTP 200 y excluir dirección física y coordenadas del resultado de eliminación. El comprobante SHALL seguir siendo recuperable aunque la fila esté excluida de consultas ordinarias.

#### Scenario: Comprobante después de otros cambios
- **WHEN** se reproduce una eliminación después de avanzar Site o reiniciar otra instancia
- **THEN** devuelve el mismo comprobante y no calcula la revisión actual ni renueva sus metadatos de borrado

### Requirement: Coherencia verificable de aceptaciones HU06
La lectura de completados SHALL verificar operación, propiedad, identidad de dirección, formato, tipos, auditoría y relación con expected_aggregate_version. create/delete SHALL avanzar exactamente uno; update SHALL conservarlo para no-op o avanzar uno. Material histórico corrupto o desconocido SHALL producir 500 saneado sin reparar, revelar datos ajenos ni reejecutar.

#### Scenario: Identidad o revisión incompatibles
- **WHEN** snapshot y completado discrepan en tenant/Company/Site, operación, addressId de ruta o revisión aceptada
- **THEN** se rechaza el histórico sin usar la dirección actual como sustituto

#### Scenario: Auditoría efectiva y no-op
- **WHEN** se verifica un cambio efectivo o un no-op update
- **THEN** el cambio efectivo vincula la auditoría de actualización al instante/actor de aceptación
- **AND** el no-op puede conservar auditoría anterior, con auditoría independiente del completado

### Requirement: Paginación persistente de direcciones vigentes
El esquema SHALL disponer de un índice de company_site_addresses por site_id, created_at DESC e id DESC para filas con deleted_at null. SHALL preservar el índice de primaria vigente por Site/tipo y las referencias que permiten calificar tenant y Company mediante Site. SHALL NOT introducir una versión propia de dirección ni una columna duplicada de siteVersion.

#### Scenario: Índice y propiedad instalados
- **WHEN** se inspecciona el catálogo tras migrar
- **THEN** el índice paginado está válido/listo con ámbito, orden y predicado completos
- **AND** la FK de Site, el índice único parcial y las protecciones de identidad continúan vigentes

### Requirement: Retención y rollback compatibles HU06
Aceptaciones HU06, formatos, políticas y claves históricas SHALL conservarse sin TTL ni limpieza que habilite otra aceptación con la misma identidad. Un rollback SHALL conservar migraciones, direcciones eliminadas, Sites, completados y eventos confirmados, restaurando código compatible o suspendiendo las rutas HU06, sin modificar los contratos históricos HU01–HU05.

#### Scenario: Rotación y otro proceso
- **WHEN** se acepta con K1 y luego se activa K2, se reinicia o responde otra instancia
- **THEN** el replay verifica con la política, formato y K1 originales sin reescribir la aceptación

#### Scenario: Reversión después de eliminar una dirección
- **WHEN** se revierte el despliegue después de aceptar direcciones o eliminaciones
- **THEN** conserva los registros históricos, las revisiones y sus comprobantes sin borrar claves ni restaurar filas por defecto
