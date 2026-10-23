# Spec Delta

## ADDED Requirements

### Requirement: Evolución incremental del soporte HU07
HU07 SHALL añadir una migración posterior a V7 preservando las seis tablas, checksums V1–V7, datos, restricciones y privilegios separados. SHALL detectar contactos EMAIL vigentes incompatibles, colisiones por minúsculas y aceptaciones HU07 sin metadatos válidos. Un histórico incompatible SHALL detener la instalación sin borrar, fusionar, normalizar ni atribuir procedencia automáticamente.

#### Scenario: Instalación limpia y upgrade desde V7
- **WHEN** se migra PostgreSQL 18 vacío o una base V7 con contactos compatibles y aceptaciones/eventos HU01–HU06
- **THEN** el soporte HU07 se instala y valida conservando identidades, datos y checksums anteriores
- **AND** runtime mantiene DML sin DDL y siguen disponibles los replays anteriores

#### Scenario: Emails históricos incompatibles
- **WHEN** hay EMAIL no eliminados con mayúsculas, sintaxis fuera del perfil, exceso de longitud o valores que colisionarían tras minúsculas
- **THEN** la migración se detiene con diagnóstico controlado sin exponer sus valores ni alterar contactos, Site, auditoría o eventos

#### Scenario: Aceptación HU07 sin procedencia
- **WHEN** existe un completado HU07 sin Site, versión esperada o procedencia verificable
- **THEN** se detiene la migración sin asignar la clave activa ni reconstruir su snapshot desde datos actuales

### Requirement: Integridad canónica de EMAIL vigente
La persistencia SHALL exigir que todo EMAIL no eliminado satisfaga el perfil simple ASCII V1, íntegramente en minúsculas, con hasta 254 caracteres y 64 locales. SHALL conservar el índice único parcial Site/tipo/value; valores equivalentes SHALL NOT coexistir vigentes. Los EMAIL eliminados anteriores SHALL conservar su representación histórica sin reescritura.

#### Scenario: Escritura SQL no canónica
- **WHEN** una escritura intenta guardar EMAIL vigente en mayúsculas, no ASCII o fuera del perfil y límites aprobados
- **THEN** PostgreSQL la rechaza incluso si omite la API

#### Scenario: Historia anterior preservada
- **WHEN** existe un EMAIL eliminado anterior con mayúsculas o longitud admitida por la columna histórica
- **THEN** la migración conserva sus bytes y metadatos de eliminación sin bloquear un nuevo contacto canónico vigente

### Requirement: Unicidad e identidad de contactos preservadas
El esquema SHALL conservar la FK de contacto a Site, identidad y creación inmutables, label canónico de hasta 80 caracteres, auditoría y borrado coherentes, formato E.164 y los dos índices únicos parciales vigentes. Una baja SHALL liberar valor y primario sin permitir un contacto eliminado primario ni perder el histórico.

#### Scenario: Valor, tipo y primario
- **WHEN** se intenta duplicar Site/tipo/valor vigente o un primario vigente Site/tipo
- **THEN** la base rechaza la segunda escritura
- **AND** admite el mismo teléfono bajo MOBILE y WHATSAPP o después de eliminar la fila anterior

#### Scenario: Borrado e identidad inválidos
- **WHEN** se cambia id/site_id/created_at/created_by o se elimina sin fecha/actor coherentes y sin retirar is_primary
- **THEN** se conservan los rechazos de integridad existentes

### Requirement: Forma persistente de aceptación de contacto
Los completados company.site.contact.create.v1, company.site.contact.update.v1 y company.site.contact.delete.v1 SHALL exigir Company/Site propios, Site no nulo, versión esperada original no negativa, fingerprint_version positivo y fingerprint_key_id válido no nulo. SHALL exigir HTTP 201 para create y 200 para update/delete, preservando hash, snapshot versionado y clave tenant/operación/clave.

#### Scenario: Forma inválida de cada operación
- **WHEN** se inserta o actualiza una aceptación omitiendo Site, revisión o procedencia, usando revisión negativa o estado incorrecto
- **THEN** la base rechaza la escritura, incluido un PATCH no-op
- **AND** keyId cumple `[A-Za-z0-9_-]{1,64}`, el hash es hexadecimal de 64 caracteres y el snapshot es un objeto con formato positivo

#### Scenario: Propiedad o identidad incompatibles
- **WHEN** un completado referencia Site de otra Company/tenant o duplica tenant/operación/clave
- **THEN** la persistencia lo rechaza sin debilitar los contratos HU01–HU06

### Requirement: Snapshot original de contacto conservado
Los snapshots create/update SHALL preservar la representación aceptada, propiedad verificable, contactId, siteVersion, auditoría, tipo, valor canónico, selección primaria y nulabilidad interna de label. SHALL usar formato HU07 independiente y reproducible sin consultar contactos, agregados o servicios actuales. Los valores de contacto SHALL NOT trasladarse al evento de integración.

#### Scenario: Round-trip histórico
- **WHEN** una aceptación se guarda y recupera desde JSONB después de reclasificar, eliminar, cerrar o archivar
- **THEN** conserva UUIDs, enteros, booleanos, textos, label null y fechas/actores originales
- **AND** la respuesta HTTP omite label null sin reconstruir el resultado

### Requirement: Snapshot mínimo de eliminación de contacto
El snapshot delete SHALL conservar la propiedad interna verificable y el comprobante original contactId, siteId, siteVersion, deletedAt y deletedBy con HTTP 200. SHALL excluir email, teléfono y label del resultado de eliminación. SHALL seguir disponible cuando el contacto esté excluido de consultas ordinarias.

#### Scenario: Replay tras otra revisión y reinicio
- **WHEN** se recupera una eliminación aceptada después de avanzar Site o reiniciar otra instancia
- **THEN** devuelve el comprobante original sin calcular la revisión actual ni renovar auditoría de borrado

### Requirement: Coherencia verificable de aceptación HU07
La lectura de completados SHALL verificar operación, propiedad, identidad, formato, tipos, auditoría y relación con la versión esperada. create/delete SHALL avanzar uno; update SHALL conservarla para no-op o avanzar uno. Material desconocido, corrupto o incompatible SHALL producir 500 saneado sin reparar, revelar otra propiedad ni reejecutar.

#### Scenario: Revisión o identidad incompatibles
- **WHEN** snapshot y completado discrepan en tenant/Company/Site, contactId de ruta, operación o revisión resultante
- **THEN** rechaza el histórico sin consultar el contacto actual como sustituto

#### Scenario: Auditoría de cambio y no-op
- **WHEN** se verifica un cambio efectivo o PATCH no-op
- **THEN** el efectivo coincide con fecha/actor de aceptación y el no-op puede conservar auditoría anterior
- **AND** se admite una identidad histórica válida de contacto sin exigir que haya sido generada por HU07

### Requirement: Índice paginado de contactos vigentes
El esquema SHALL disponer de un índice de company_site_contact_points por site_id, created_at DESC e id DESC para deleted_at null. SHALL conservar las restricciones que permiten calificar tenant/Company mediante Site. SHALL NOT añadir una versión propia ni duplicar siteVersion en contacto.

#### Scenario: Catálogo después de migrar
- **WHEN** se inspecciona PostgreSQL después de instalar HU07
- **THEN** el índice está válido/listo con ámbito, orden y predicado completos y siguen vigentes la FK y los índices únicos parciales

### Requirement: Retención y rollback compatibles HU07
Aceptaciones HU07, políticas, formatos, metadatos de validación y claves históricas necesarios SHALL conservarse sin TTL que habilite otra aceptación. Un rollback SHALL conservar migraciones, contactos eliminados, Site, completados y eventos, restaurando código compatible o suspendiendo rutas HU07. SHALL preservar los bytes y semántica histórica HU01–HU06.

#### Scenario: Rotación o nueva política de validación
- **WHEN** cambia la clave activa, política telefónica o instancia después de una aceptación
- **THEN** el replay utiliza el material original, sin reinterpretar la intención con reglas actuales

#### Scenario: Reversión del despliegue
- **WHEN** se revierte la aplicación después de aceptar o eliminar contactos
- **THEN** conserva la revisión, historia y comprobantes sin borrar claves, restaurar filas por defecto o reescribir migraciones aplicadas
