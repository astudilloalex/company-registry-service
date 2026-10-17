# Spec Delta

## Purpose

Definir fronteras internas de validación para identidades legales y referencias geográficas, manteniendo aislados los contratos y fallos de los proveedores.

## ADDED Requirements

### Requirement: Validación de Party legal por contrato
El servicio SHALL disponer de un puerto para resolver una Party en el tenant actual y conocer su tipo y ciclo de vida. SHALL usar contratos internos de Party Registry y conservar únicamente datos requeridos para decidir, sin asumir propiedad de nombres legales o identificadores oficiales.

#### Scenario: Referencia legal válida
- **WHEN** Party Registry devuelve una Party LEGAL_ENTITY en el contexto tenant solicitado
- **THEN** el puerto entrega un resultado neutral con identidad, tipo y estado suficientes para las políticas futuras
- **AND** permite distinguir validación de existencia/tipo de la exigencia de estado ACTIVE para activar Company

#### Scenario: Referencia no resoluble
- **WHEN** Party Registry devuelve ausencia para el tenant actual o un tipo incompatible
- **THEN** la frontera diferencia ausencia e incompatibilidad sin divulgar información de otros tenants

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

### Requirement: Sin acceso a bases externas
El servicio SHALL validar referencias externas exclusivamente a través de contratos de servicio, sin consultar bases ajenas ni crear claves foráneas entre servicios.

#### Scenario: Revisión de integración
- **WHEN** se inspeccionan configuración de datasources y migraciones
- **THEN** solo existe acceso persistente propio de Company Registry y los proveedores aparecen como clientes de red
