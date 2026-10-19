# Spec Delta

## ADDED Requirements

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
