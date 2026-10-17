# external-reference-validation Specification

## Purpose

Definir fronteras internas de validación para identidades legales y referencias geográficas, manteniendo aislados los contratos y fallos de los proveedores.

## Requirements

### Requirement: Validación de Party legal por contrato
El servicio SHALL resolver Party en el tenant actual mediante contratos internos de Party Registry, manteniendo una proyección mínima de identidad, tipo y ciclo de vida para decidir. SHALL distinguir esa validación de la lectura pública enriquecida de HU01, sin asumir propiedad de nombres legales o identificadores oficiales; únicamente el replay permite conservar su proyección pública histórica no autoritativa.

#### Scenario: Referencia legal válida
- **WHEN** Party Registry devuelve una Party LEGAL_ENTITY en el contexto tenant solicitado
- **THEN** el puerto entrega un resultado neutral con identidad, tipo y estado suficientes para las políticas futuras
- **AND** permite distinguir validación de existencia/tipo de la exigencia de estado ACTIVE para activar Company

#### Scenario: Referencia no resoluble
- **WHEN** Party Registry devuelve ausencia para el tenant actual o un tipo incompatible
- **THEN** la frontera diferencia ausencia e incompatibilidad sin divulgar información de otros tenants

#### Scenario: Validación y enriquecimiento tienen propósitos distintos
- **WHEN** HU01 solicita los datos públicos de Party para componer una respuesta
- **THEN** obtiene una proyección de lectura separada del resultado mínimo de elegibilidad, sin convertir datos legales en atributos autoritativos del agregado Company

### Requirement: Validación geográfica por contratos existentes
El servicio SHALL definir puertos para validar país y división administrativa usando códigos compatibles con su persistencia. La validación SHALL permitir comprobar existencia, actividad y pertenencia de la división al país indicado según el contrato del proveedor.

#### Scenario: País válido
- **WHEN** se consulta el país por código alpha-2 mediante el contrato documentado
- **THEN** el adaptador verifica que la respuesta corresponde al código solicitado y proporciona su identidad/estado al contrato interno

#### Scenario: División incompatible
- **WHEN** el proveedor confirma que una división no pertenece al país solicitado o no es válida para la operación
- **THEN** el adaptador devuelve un resultado neutral de referencia inválida

### Requirement: Prohibición de contratos inventados
Si no existe evidencia suficiente de ruta, respuesta y semántica de una validación geográfica requerida, el servicio SHALL documentar la dependencia y mantener bloqueada la funcionalidad afectada. SHALL evitar rutas supuestas, respuestas simuladas en producción o aceptación silenciosa.

#### Scenario: Ruta publicada sin esquema confirmado
- **WHEN** una colección describe consulta por código de división pero no incluye una respuesta verificable
- **THEN** se registra la ruta como evidencia parcial y se exige contrato/respuesta del proveedor antes de habilitar su adaptador
- **AND** la base técnica puede avanzar sin declarar resuelta esa integración

### Requirement: I/O externo acotado y trazable
Toda consulta externa SHALL usar I/O no bloqueante, URL configurable y timeouts de conexión y lectura finitos. SHALL propagar el contexto admitido por el proveedor sin traducir Tenant-Id a Company-Id sin acuerdo explícito.

#### Scenario: Timeout o indisponibilidad
- **WHEN** el proveedor no responde dentro del plazo o no está disponible
- **THEN** el adaptador finaliza con un fallo neutral de dependencia sin filtrar payloads o excepciones técnicas
- **AND** no convierte la indisponibilidad en referencia válida o inexistente

#### Scenario: Prueba contractual aislada
- **WHEN** se prueban los adaptadores habilitados contra stubs fieles a sus contratos
- **THEN** se verifican rutas, headers, timeout, ausencia, errores y respuesta incompatible sin depender de servicios desplegados

#### Scenario: Tres cabeceras en cada consulta Party
- **WHEN** se consulta el detalle de Party o cualquier página de sus identificadores
- **THEN** se envían Tenant-Id, User-Id y Process-Id de la petición actual con el mismo contexto de correlación
- **AND** esta ampliación no inventa Tenant-Id ni Company-Id para Geographic Reference, cuyo contrato sigue siendo independiente

### Requirement: Sin acceso a bases externas
El servicio SHALL validar referencias externas exclusivamente a través de contratos de servicio, sin consultar bases ajenas ni crear claves foráneas entre servicios.

#### Scenario: Revisión de integración
- **WHEN** se inspeccionan configuración de datasources y migraciones
- **THEN** solo existe acceso persistente propio de Company Registry y los proveedores aparecen como clientes de red

### Requirement: Lectura contractual de Party e identificadores
El enriquecimiento SHALL usar `GET /v1/parties/{partyId}` y `GET /v1/parties/{partyId}/identifiers` con sus DTOs públicos reales. SHALL validar identidad, ownership, tipos, estados y envelope/HTTP, y respetar cursores y metadatos de la colección antes de componer el array de identificadores de HU01.

#### Scenario: Detalle y lista válidos
- **WHEN** las respuestas corresponden a Party y tenant solicitados y satisfacen sus contratos
- **THEN** se proyectan el detalle general, legalEntityDetails y los identificadores públicos, incluido maskedValue y sus metadatos opcionales
- **AND** no se solicita descifrado ni se incorpora información privada o campos no publicados

#### Scenario: Respuesta o navegación incompatible
- **WHEN** el proveedor devuelve otra Party, un identificador de otro propietario, enums desconocidos, tipos incorrectos, envelope inconsistente o cursor repetido que impide avanzar
- **THEN** se produce un fallo neutral de contrato inválido, sin aceptar la respuesta ni devolver datos parciales

#### Scenario: Página vacía no es indisponibilidad
- **WHEN** el proveedor confirma una colección vacía válida sin continuación
- **THEN** el enriquecimiento contiene identifiers vacío
- **AND** un timeout, HTTP de indisponibilidad o body inválido nunca se convierte en identifiers vacío

### Requirement: Enriquecimiento actual e histórico diferenciados
Los GET normales SHALL obtener los datos actuales del proveedor. Un replay aceptado SHALL usar exclusivamente la proyección pública histórica de su snapshot, sin acceder al proveedor ni sustituir campos guardados. Esa proyección SHALL permanecer ajena al modelo autoritativo de Company y al payload del evento de creación.

#### Scenario: Replay con proveedor inaccesible
- **WHEN** existe un completado HU01 equivalente con su snapshot enriquecido válido
- **THEN** devuelve su Party e identificadores originales aunque el proveedor no esté disponible o sus datos actuales sean diferentes

#### Scenario: Proveedor inaccesible antes de aceptar
- **WHEN** una creación sin completado aceptado no puede obtener todo el enriquecimiento requerido
- **THEN** falla sin confirmar Company, completado ni evento

#### Scenario: Referencia persistida actualmente no resoluble
- **WHEN** un GET encuentra una Company propia pero su Party o la ruta requerida de identificadores responde como referencia no resoluble
- **THEN** se devuelve un fallo de dependencia/contrato controlado sin presentar Company como inexistente ni inventar una Party vacía
