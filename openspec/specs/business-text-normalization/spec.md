# business-text-normalization Specification

## Purpose

Garantizar representaciones canónicas consistentes del texto de negocio sin alterar valores cuya semántica depende de preservar su contenido original.

## Requirements

### Requirement: Canonización centralizada del texto de negocio
Nombres, descripciones, códigos de negocio, textos de dirección y etiquetas SHALL eliminar espacios exteriores y convertirse a mayúsculas mediante `Locale.ROOT`, preservando acentos y espacios internos significativos. La operación SHALL ser idempotente e independiente del locale de la máquina.

#### Scenario: Texto acentuado con espacios
- **WHEN** se normaliza `  compañía  ágil  `
- **THEN** el resultado es `COMPAÑÍA  ÁGIL`
- **AND** una segunda normalización produce exactamente el mismo resultado

#### Scenario: Locale independiente
- **WHEN** el mismo texto se normaliza con distintos locales por defecto, incluido turco
- **THEN** produce la misma representación canónica

### Requirement: Orden canónico de evaluación
Las operaciones SHALL usar el valor canónico para validar longitud y contenido, comprobar unicidad y construir fingerprints de idempotencia. Dos entradas equivalentes por la política SHALL representar el mismo contenido efectivo.

#### Scenario: Entradas equivalentes
- **WHEN** se normalizan `  sede norte ` y `SEDE NORTE`
- **THEN** producen el mismo valor para validación, búsqueda de duplicados y fingerprinting

#### Scenario: Longitud posterior a mayúsculas
- **WHEN** convertir a mayúsculas cambia la longitud del texto
- **THEN** se valida el tamaño de la representación final y no únicamente el texto recibido

### Requirement: Preservación de valores opacos
La política de mayúsculas SHALL excluir UUIDs, emails, URLs, material criptográfico, claves de idempotencia, correlaciones y valores de protocolos externos. Los emails SHALL preservar su parte local; el tratamiento de cada valor SHALL respetar su contrato específico.

#### Scenario: Valores sensibles al contenido exacto
- **WHEN** se procesan `Mi.Usuario@example.com`, `https://host/LogoAb.png` y una clave `Key-aB`
- **THEN** ninguna conversión genérica a mayúsculas modifica esos valores

### Requirement: Normalización especializada de teléfonos
PHONE, MOBILE y WHATSAPP SHALL validarse mediante una política telefónica específica y almacenarse en formato internacional E.164. Entradas ambiguas sin contexto suficiente SHALL rechazarse sin inventar un país.

#### Scenario: Teléfono canónico
- **WHEN** se procesa un número internacional válido admitido por la política
- **THEN** se obtiene una representación E.164 compatible con la restricción SQL

#### Scenario: Número ambiguo o inválido
- **WHEN** falta información para interpretar el número o no satisface su formato
- **THEN** la política devuelve un fallo neutral de validación

### Requirement: Política verificable sin infraestructura
La normalización y validación de texto/contactos SHALL poder probarse sin HTTP, Quarkus o base de datos, incluyendo acentos, espacios, nulos opcionales, valores requeridos en blanco y exclusiones.

#### Scenario: Pruebas unitarias independientes
- **WHEN** se ejecuta la suite de políticas puras
- **THEN** cubre las reglas canónicas y casos inválidos sin iniciar dependencias externas
