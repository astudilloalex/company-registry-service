# Proposal

## Why

HU05 ([issue #7](https://github.com/astudilloalex/company-registry-service/issues/7)) necesita corregir y administrar sedes sin perder su independencia respecto de Company ni permitir que una Company ACTIVE quede sin sede legal ACTIVE. HU04 ya entrega registro, consultas y bases transaccionales; faltan las mutaciones de Site, su replay durable y los contratos de actualización/cierre.

## What Changes

- Incorporar PATCH de Site y POST activate/deactivate/close, con contexto obligatorio, If-Match de Site y pertenencia tenant/Company/Site. Toda aceptación devuelve 200 successful con la representación local completa de doce campos.
- Aplicar PATCH parcial cerrado sobre code/name/siteType: omisión conserva, null se rechaza, code/name se canonizan y siteType exige enum exacto. `{}` e igualdad canónica son no-op con versión vigente y recurso editable. Renombrar code libera el anterior; CLOSED conserva su código actual.
- Implementar las seis transiciones permitidas y CLOSED terminal. Un comando nuevo que pide el estado actual devuelve 409; Company ARCHIVED bloquea toda mutación nueva, incluso no-op. Los comandos admiten body ausente o `{}` equivalentes y rechazan otro contenido.
- Serializar toda mutación nueva, incluidos no-op, mediante Company → Site, revalidando versión, estados e invariantes dentro de la transacción. Proteger HEADQUARTERS no cerrado único y la sede legal ACTIVE de una Company ACTIVE, con coordinación compatible con HU03 y sin alterar versión/auditoría del padre.
- Ofrecer idempotencia opcional e independiente para company.site.update.v1, company.site.activate.v1, company.site.deactivate.v1 y company.site.close.v1. La identidad es tenant/operación/clave; la huella vincula Company, Site, versión original e intención canónica con presencia de campos. Solo las aceptaciones, incluidos no-op, consumen clave.
- Resolver replay antes del estado actual y conservar snapshot, operatividad y auditoría originales, con Process-Id del intento actual. Calcular operatividad de nuevas respuestas bajo bloqueo; GET mantiene cálculo actual. Confirmar cambio, completado opcional y evento habilitado juntos, con recuperación acotada de resultados inciertos.
- Publicar errores descriptivos con precedencia entrada → replay/conflicto de clave → pertenencia → versión → estados → invariantes/unicidad, preservando contratos anteriores.
- Incorporar company.site.updated.v1 y company.site.closed.v1 con envelope existente y payload cerrado siteId/companyId/code/name/siteType/status/updatedAt/updatedBy. Cerrar produce únicamente closed; no-op/replay no generan evento. Excluir operatividad, direcciones y contactos del evento; preparar reutilización por HU06/HU07.
- Ampliar integridad de completados mediante migración incremental y entregar OpenAPI, esquemas/ejemplos de eventos y verificación JVM, concurrente, reinicio y nativa.

## Capabilities

### New Capabilities

- `company-site-information-management`: corrección parcial, normalización, liberación de códigos y no-op de información Site.
- `company-site-lifecycle-management`: transiciones, protección de sede legal y contrato compartido de aceptación, versión, auditoría, replay y coordinación Company/Site de las cuatro mutaciones.
- `company-site-updated-event`: hecho versionado de actualización Site y almacenamiento condicional para HU08 y futuros productores HU06/HU07.
- `company-site-closed-event`: hecho terminal de cierre Site y almacenamiento transaccional condicional.

### Modified Capabilities

- `company-site-retrieval`: extender la representación pública uniforme a mutaciones y precisar operatividad actual frente al resultado histórico de replay.
- `business-api-conventions`: incorporar catálogo y precedencia descriptivos HU05 conservando HU01/HU02/HU04.
- `company-data-integrity`: exigir forma persistente y procedencia de aceptaciones HU05, snapshots originales y evolución compatible del esquema.

## Impact

Afecta Domain/Application/API/Infrastructure de Site, puertos reactivos, composición CDI, Flyway/DBML, OpenAPI, contratos de eventos y suites JVM/nativas. Reutiliza keyring, outbox y PostgreSQL 18 existentes. Quedan fuera implementación de HU03, CRUD de direcciones/contactos, reemplazo atómico de sede legal, borrado físico y publicación RabbitMQ. Las carreras con HU03/HU06/HU07 se verifican con transacciones auxiliares de prueba compatibles hasta que existan sus endpoints reales. Este cambio contiene planificación; apply se inicia por separado.
