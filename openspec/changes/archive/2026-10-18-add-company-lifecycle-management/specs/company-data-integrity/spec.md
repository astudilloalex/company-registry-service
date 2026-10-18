# Spec Delta

## ADDED Requirements

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
