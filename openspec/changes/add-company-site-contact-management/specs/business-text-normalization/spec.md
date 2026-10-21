# Spec Delta

## MODIFIED Requirements

### Requirement: Preservación de valores opacos
La política genérica de mayúsculas SHALL excluir UUIDs, emails, URLs, material criptográfico, claves de idempotencia, correlaciones y valores de protocolos externos. Cada valor SHALL respetar su contrato específico. EMAIL V1 SHALL usar su política especializada de minúsculas para la dirección completa; los restantes valores opacos SHALL conservar su contenido exacto.

#### Scenario: Valores sensibles al contenido exacto
- **WHEN** se procesan `Mi.Usuario@example.com`, `https://host/LogoAb.png` y una clave `Key-aB`
- **THEN** ninguna conversión genérica a mayúsculas modifica esos valores
- **AND** la política específica EMAIL produce mi.usuario@example.com, mientras URL y clave conservan su contenido exacto

## ADDED Requirements

### Requirement: Canonización íntegra de EMAIL V1
EMAIL SHALL retirar espacios exteriores y exigir ASCII antes de convertir toda la dirección a minúsculas Locale.ROOT, incluida la parte local. Esta representación SHALL usarse para almacenamiento vigente, comparación e idempotencia. SHALL preservar puntos y sufijos con signo más, sin aplicar equivalencias particulares de proveedores.

#### Scenario: Diferencias de mayúsculas
- **WHEN** se comparan ` Ventas@Example.COM ` y ventas@example.com
- **THEN** ambas entradas producen exactamente ventas@example.com

#### Scenario: Alias distintos y caracteres internacionales
- **WHEN** se comparan ventas+facturas@example.com y ventas@example.com
- **THEN** siguen siendo valores distintos
- **AND** josé@example.com y una letra no ASCII que se convertiría a ASCII mediante minúsculas se rechazan antes de esa conversión

### Requirement: Sintaxis simple de email ASCII
EMAIL SHALL contener una dirección sin nombre de presentación, lista, comentario, comillas ni literal IP. La parte local SHALL usar dot-atom-text ASCII según RFC 5322. El dominio SHALL tener al menos dos etiquetas de 1–63 caracteres ASCII alfanuméricos con guiones solo interiores. SHALL rechazar controles, espacios internos, puntos iniciales/finales y componentes vacíos.

#### Scenario: Dirección común con alias
- **WHEN** se valida ventas.ec+facturas@sub.empresa.com
- **THEN** satisface el perfil sintáctico sin comprobar DNS, registro de dominio ni existencia del buzón

#### Scenario: Dirección incompatible con el perfil
- **WHEN** se envía a..b@example.com, a@example..com, a@-example.com, a@localhost, un literal IP o una dirección entre comillas
- **THEN** se rechaza como valor de contacto inválido

### Requirement: Límites de EMAIL V1
La dirección canónica EMAIL SHALL contener hasta 254 caracteres ASCII y su parte local hasta 64. Los límites SHALL medirse después de retirar espacios exteriores. El perfil SHALL rechazar el exceso antes de persistencia; una columna histórica de mayor capacidad SHALL NOT ampliar el contrato público.

#### Scenario: Fronteras de longitud
- **WHEN** una dirección satisface la sintaxis y alcanza 254 caracteres totales o 64 locales sin exceder el otro límite
- **THEN** se admite
- **AND** 255 totales o 65 locales se rechazan con contact-value-too-long

### Requirement: Entrada internacional flexible para teléfonos
PHONE/MOBILE/WHATSAPP SHALL exigir prefijo internacional `+` explícito y admitir solo dígitos ASCII, espacios, guiones y paréntesis después de retirar espacios exteriores. SHALL rechazar letras, extensiones y formatos nacionales sin inferir país. El resultado SHALL ser un teléfono válido del plan admitido y E.164 con hasta 15 dígitos.

#### Scenario: Formatos equivalentes
- **WHEN** se procesa ` +1 (202) 555-0123 ` o +12025550123 bajo un canal compatible
- **THEN** ambas entradas producen +12025550123 para almacenamiento, unicidad e idempotencia

#### Scenario: Representación no admitida
- **WHEN** falta `+`, hay una extensión, letras, prefijo inválido, número no válido o resultado de más de 15 dígitos
- **THEN** devuelve un fallo neutral sin adivinar país ni truncar el número

### Requirement: Clasificación de canal MOBILE
MOBILE SHALL admitir únicamente números válidos clasificados MOBILE o FIXED_LINE_OR_MOBILE por la política telefónica aprobada. SHALL rechazar FIXED_LINE exclusivo y otras clasificaciones. PHONE y WHATSAPP SHALL admitir números internacionalmente válidos del plan soportado sin exigir clasificación móvil.

#### Scenario: Clasificación móvil ambigua
- **WHEN** un número válido se clasifica FIXED_LINE_OR_MOBILE
- **THEN** puede registrarse como MOBILE

#### Scenario: Línea fija y WhatsApp
- **WHEN** un número válido se clasifica exclusivamente FIXED_LINE
- **THEN** se rechaza como MOBILE con contact-mobile-number-required
- **AND** puede admitirse como PHONE o WHATSAPP sin probar disponibilidad del canal
