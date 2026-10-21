# Proposal

## Why

La [HU07 / issue #9](https://github.com/astudilloalex/company-registry-service/issues/9) requiere mantener canales de contacto de las sedes con unicidad, selección primaria e historial, compartiendo la revisión y consistencia de Site. La base contiene tabla, índices y normalización telefónica; faltan la gestión completa y un contrato preciso de validación e idempotencia.

## What Changes

- Incorporar POST/GET de colección y GET/PATCH/DELETE individual bajo `/v1/companies/{companyId}/sites/{siteId}/contacts`, con aislamiento tenant/Company/Site, eliminación lógica y respuestas con `siteVersion`.
- **BREAKING**: sustituir la preservación de la parte local del email por normalización íntegra a minúsculas. EMAIL V1 admite una dirección simple ASCII, hasta 254 caracteres y 64 antes de `@`, sin formatos entre comillas, listas o nombres de presentación. Esta decisión acordada modifica el requisito previo y prevalece sobre HU07 AC07 en cuanto a conservación de mayúsculas.
- Admitir teléfonos internacionales con espacios, guiones y paréntesis; exigir prefijo `+`, validar con libphonenumber y almacenar E.164. MOBILE acepta MOBILE/FIXED_LINE_OR_MOBILE; PHONE y WHATSAPP admiten números internacionalmente válidos sin probar disponibilidad ni enviar mensajes.
- Normalizar tipos/etiquetas a mayúsculas; permitir cambiar `contactType` mediante PATCH validando el valor resultante y rechazando duplicados vigentes por Site/tipo/valor canónico. El mismo número puede coexistir como MOBILE y WHATSAPP.
- Sustituir primarios atómicamente al crear, actualizar o reclasificar; conservar isPrimary omitido y no elegir reemplazos automáticos. Un cambio conjunto consume una sola revisión Site y un evento habilitado.
- Exigir If-Match de Site en todas las mutaciones, clave idempotente en POST y admitir clave opcional en PATCH/DELETE. Preservar no-op, replay original, auditoría común y aceptación atómica con el evento mínimo `company.site.updated.v1`.
- Adoptar las convenciones HU06 de PATCH, estados editables, comprobante DELETE 200 y paginación con cursor autenticado, filtros contactType/isPrimary, límite 50 por defecto y rango 1–200.
- Extender OpenAPI, documentación y verificación JVM/PostgreSQL/proceso empaquetado/nativo, con evolución Flyway que detecte históricos incompatibles sin reescribirlos silenciosamente.

## Capabilities

### New Capabilities

- `company-site-contact-management`: ciclo completo de contactos Site, pertenencia, PATCH, duplicados, primarios, consultas, revisión compartida, replay, auditoría y evento seguro.

### Modified Capabilities

- `business-text-normalization`: EMAIL íntegramente en minúsculas con perfil ASCII V1 y normalización telefónica flexible con clasificación MOBILE explícita.
- `company-data-integrity`: integridad canónica de contactos vigentes, metadatos y snapshots de aceptación HU07, índice de paginación y conservación de históricos/checksums.
- `business-api-conventions`: contrato HTTP HU07, catálogo de errores descriptivos y precedencia de entrada, replay, pertenencia, revisión y reglas efectivas.

## Impact

Domain incorpora políticas puras de contacto; Application coordina mutaciones y lecturas; API añade recursos, DTOs y lectura JSON estricta; Infrastructure amplía repositorio, clasificación de unicidad, codecs, fingerprints, cursores y composición reactiva. Se reutilizan libphonenumber 9.0.41, los seis objetos tabulares existentes, AtomicOperation, claves HMAC y SiteMutationEvents; no cambia el payload publicado de eventos ni se añade otra revisión al contacto.

La migración siguiente a V7 preservará V1–V7, sus checksums y los replays HU01–HU06. El despliegue debe detectar contactos vigentes incompatibles con el nuevo perfil y colisiones tras minúsculas; su corrección no se inventará ni se mezclará con una migración silenciosa. Permanecen fuera de alcance notificaciones, sesiones/verificación de WhatsApp, consentimiento, CRM, restauración/historial público y publicación RabbitMQ HU08.
