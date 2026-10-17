# Spec Delta

## MODIFIED Requirements

### Requirement: Contexto obligatorio en la API de negocio
Las rutas de negocio bajo `/v1` SHALL exigir exactamente un `Tenant-Id`, `User-Id` y `Process-Id`. Tenant y proceso SHALL ser UUID canónicos en minúsculas; usuario SHALL ser texto no vacío de hasta 128 puntos de código sin controles. El contexto SHALL proceder de headers, no del body. SHALL rechazar representaciones UUID no canónicas sin normalizar silenciosamente el valor recibido.

#### Scenario: Contexto válido
- **WHEN** se recibe una petición con headers válidos
- **THEN** la frontera entrega tenant, actor y proceso a la operación sin alterar sus valores opacos

#### Scenario: Contexto inválido
- **WHEN** falta un header, se repite o su valor es inválido
- **THEN** la frontera devuelve HTTP 400 con envelope compartido y un código estable que identifica la regla incumplida

#### Scenario: UUID de cabecera con mayúsculas
- **WHEN** Tenant-Id o Process-Id contiene letras hexadecimales mayúsculas, espacios o una representación UUID abreviada
- **THEN** recibe HTTP 400 antes de consultar completados, Company o proveedores
- **AND** un Process-Id inválido no se devuelve; si el proceso era válido y falló el tenant se conserva exactamente el proceso aceptado

#### Scenario: Propagación compatible con Party Registry
- **WHEN** Company acepta los tres headers y llama al detalle o identificadores de Party Registry
- **THEN** propaga tenant/proceso canónicos en minúsculas y actor exacto sin generar identificadores nuevos ni modificar su contenido entre micros

#### Scenario: Aplicabilidad uniforme
- **WHEN** se ejecuta un POST inicial, un GET individual, un listado o un replay bajo v1
- **THEN** se aplica la misma validación obligatoria de las tres cabeceras
- **AND** health, métricas y OpenAPI mantienen la excepción operacional existente
