# Spec Delta

## Purpose

Permitir correcciones parciales de la información operacional de Site con intención explícita, normalización canónica, unicidad y protección de la sede legal.

## ADDED Requirements

### Requirement: Body cerrado de actualización Site
`PATCH /v1/companies/{companyId}/sites/{siteId}` SHALL aceptar un objeto JSON application/json con únicamente code, name y siteType. SHALL admitir `{}` y rechazar body ausente, JSON inválido, raíz no objeto, propiedades desconocidas/duplicadas y contenido posterior. SHALL aplicar el contrato compartido de mutaciones HU05.

#### Scenario: Body vacío explícito
- **WHEN** se envía `{}` con contexto, versión y recurso editables
- **THEN** evalúa una actualización sin campos presentes y puede aceptarla como no-op

#### Scenario: Documento inválido
- **WHEN** falta el body o contiene JSON inválido, null, array, escalar, duplicados o tokens posteriores
- **THEN** devuelve HTTP 400 descriptivo sin aceptar la actualización

#### Scenario: Campos fuera de contrato
- **WHEN** se envía id, siteId, companyId, tenantId, status, version, effectivelyOperational, auditoría u otra propiedad no admitida
- **THEN** devuelve 400 aunque el valor coincida con el almacenado

### Requirement: Presencia y nulabilidad de campos Site
Un campo omitido SHALL conservar su valor actual. Los tres campos SHALL rechazar null explícito. code y name SHALL rechazar valores blancos; todos los valores presentes SHALL ser strings sin coerción. Omitir siteType en PATCH SHALL conservarlo, sin aplicar el default BRANCH del registro.

#### Scenario: Nombre parcial sobre sede legal
- **WHEN** una sede HEADQUARTERS recibe únicamente name válido
- **THEN** conserva code y HEADQUARTERS y actualiza solo el nombre cuando es diferente

#### Scenario: Null, blanco o tipo incompatible
- **WHEN** un campo presente es null, code/name queda blanco o cualquier campo contiene número, booleano, objeto o array
- **THEN** devuelve 400 con el código correspondiente, sin cambiar el recurso

### Requirement: Canonización compatible de información Site
code y name presentes SHALL usar strip y mayúsculas Locale.ROOT antes de validar límites de 64 y 200 puntos de código, comparar cambios, comprobar unicidad o firmar. SHALL conservar acentos y espacios internos significativos. siteType SHALL aceptar únicamente HEADQUARTERS, BRANCH, WAREHOUSE y OTHER exactos.

#### Scenario: Equivalencia y espacios internos
- **WHEN** se envía nombre o código equivalente por mayúsculas o espacios exteriores
- **THEN** compara y firma el valor canónico y conserva los espacios internos significativos

#### Scenario: Expansión Unicode
- **WHEN** convertir el texto a mayúsculas aumenta su longitud
- **THEN** aplica el límite al texto canónico final, contado en puntos de código

#### Scenario: Enum inválido
- **WHEN** siteType contiene `branch`, una cadena vacía u otro valor fuera de los cuatro admitidos
- **THEN** devuelve 400 sin normalizarlo silenciosamente

### Requirement: Edición limitada a Site y Company editables
Sin replay coincidente, PATCH SHALL permitirse para Site DRAFT, ACTIVE o INACTIVE bajo Company DRAFT, ACTIVE o INACTIVE. Con versión vigente, Site CLOSED o Company ARCHIVED SHALL producir 409, incluso para `{}` o valores equivalentes. SHALL preservar identidad, propiedad y auditoría de creación.

#### Scenario: Corrección admisible
- **WHEN** Site y Company son editables y el PATCH válido cumple versión e invariantes
- **THEN** acepta la corrección conservando el estado propio de Site y todos los valores inmutables

#### Scenario: No-op sobre estado terminal
- **WHEN** un PATCH nuevo equivalente llega a Site CLOSED o Company ARCHIVED con versión vigente
- **THEN** devuelve 409 sin completado nuevo ni evento
- **AND** una versión obsoleta produce 412 antes del rechazo por estado

### Requirement: Reserva del código actual por Company
El code canónico resultante SHALL ser único por Company, incluyendo el código actual de Sites CLOSED. Un cambio efectivo de code SHALL liberar el código anterior al confirmar, sin reservar alias históricos. El propio código actual SHALL NOT considerarse ocupado por otra sede. Companies distintas SHALL poder usar el mismo código.

#### Scenario: Renombrado y reutilización
- **WHEN** una sede cambia de A a B y confirma sin colisión
- **THEN** B queda reservado por esa sede y A puede usarse en un registro o PATCH posterior de la misma Company

#### Scenario: Código de sede cerrada
- **WHEN** otra sede CLOSED conserva el código solicitado
- **THEN** devuelve 409 site-code-already-exists y no libera ni modifica códigos

#### Scenario: Código propio o Company diferente
- **WHEN** el código solicitado coincide con el actual de la misma sede o solo está ocupado en otra Company
- **THEN** no produce una colisión de código por esa coincidencia

#### Scenario: Renombrado revertido
- **WHEN** falla la aceptación antes de confirmar el cambio de A a B
- **THEN** A sigue reservado y B no queda reservado por el intento revertido

### Requirement: No-op de información Site
Un PATCH válido que conserve el estado canónico persistido SHALL devolver 200 con Site completo, preservando versión, updatedAt y updatedBy, sin evento. SHALL comprobar versión y estados antes de admitir el no-op. Con Idempotency-Key SHALL conservar una aceptación durable; sin clave SHALL omitir el completado.

#### Scenario: Objeto vacío o texto equivalente
- **WHEN** `{}` o valores equivalentes se aceptan con versión vigente
- **THEN** conserva el agregado y devuelve su resultado aceptado sin actualización de auditoría ni evento

#### Scenario: No-op con clave
- **WHEN** un no-op válido incluye clave
- **THEN** guarda su snapshot original y permite replay posterior aunque Site o Company cambien
- **AND** la auditoría del completado describe la aceptación y puede diferir de la auditoría intacta de Site

#### Scenario: No-op concurrente
- **WHEN** un no-op nuevo compite con un cambio efectivo del mismo Site
- **THEN** solo acepta el no-op si su versión sigue vigente en el punto protegido
- **AND** aceptar primero el no-op no consume una revisión del agregado

### Requirement: Reclasificación sujeta a sede legal
Cambiar siteType SHALL conservar la unicidad de HEADQUARTERS no CLOSED y SHALL NOT dejar Company ACTIVE sin su sede legal ACTIVE. La reserva ocupada por el propio Site SHALL excluirse de la comprobación de otra sede legal. Esta mutación SHALL usar el mismo protocolo Company antes de Site que las transiciones.

#### Scenario: Segunda sede legal
- **WHEN** reclasificar un Site produciría dos HEADQUARTERS no CLOSED
- **THEN** devuelve 409 headquarters-already-exists y conserva ambos agregados

#### Scenario: Retirar clasificación legal activa
- **WHEN** se intenta cambiar a otro tipo la única sede legal ACTIVE de Company ACTIVE
- **THEN** devuelve 409 active-headquarters-required sin versión, aceptación o evento nuevos

#### Scenario: Reclasificación permitida
- **WHEN** Company es DRAFT o INACTIVE y el nuevo tipo preserva unicidad
- **THEN** admite el cambio efectivo con una sola revisión nueva y conserva el estado de Site
