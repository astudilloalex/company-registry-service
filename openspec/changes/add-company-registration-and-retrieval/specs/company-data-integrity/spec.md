# Spec Delta

## MODIFIED Requirements

### Requirement: Idempotencia tenant y Company qualified
La idempotencia SHALL usar PK `(tenant_id,operation,idempotency_key)`, fingerprint y resultado JSON versionado reproducible, con `site_id` nullable. Sus referencias SHALL impedir asociar tenant, Company y Site incompatibles y validar versión esperada, estado HTTP y contenido del resultado. Cada nuevo completado HU01 SHALL identificar además su política de fingerprint y su clave HMAC sin guardar el secreto.

#### Scenario: Idempotencia de Company y Site
- **WHEN** se guarda una finalización con Company propia y `site_id` nulo, o con Site de esa Company y tenant
- **THEN** PostgreSQL acepta el registro
- **AND** rechaza sitios de otra Company/tenant, claves repetidas, hash inválido, versión de esquema no positiva o snapshot no objeto

#### Scenario: Completado HU01 reproducible
- **WHEN** se confirma company.create.v1
- **THEN** el registro conserva hash hexadecimal de 64 caracteres, versión positiva de fingerprint, identificador no vacío de clave, HTTP 201 y snapshot de respuesta con versión positiva
- **AND** siteId y versión esperada son null porque el registro crea un agregado nuevo

## ADDED Requirements

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
El completado de creación SHALL guardar el resultado original completo y versionado, incluyendo la proyección pública de Party e identificadores enmascarados. SHALL conservar sus tipos, valores opacos y nulabilidad interna sin convertirla en una tabla espejo o columnas legales de Company. Su lectura SHALL reproducir la representación HTTP aceptada.

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
