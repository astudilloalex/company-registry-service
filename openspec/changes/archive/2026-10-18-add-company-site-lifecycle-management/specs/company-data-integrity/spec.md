# Spec Delta

## ADDED Requirements

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
