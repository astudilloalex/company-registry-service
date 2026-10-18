# Tasks

## 1. Políticas puras de dirección

- [x] 1.1 Incorporar el valor de dirección y canonización de requeridos/opcionales reutilizando BusinessText; verificar con pruebas puras de mayúsculas, acentos, espacios internos, expansión Unicode, límites y blancos.
- [x] 1.2 Incorporar coordenadas exactas y presencia del par en creación/PATCH; verificar límites inclusivos, valores apenas fuera de rango, miembro omitido, nulls, seis decimales, ceros finales y equivalencia numérica sin redondeo.
- [x] 1.3 Incorporar PATCH con presencia/null/valor y decisión de cambio efectivo; verificar omisión, limpieza, requeridos nulos, `{}` y equivalencias canónicas sin mutación.
- [x] 1.4 Incorporar decisiones de primaria y reclasificación PHYSICAL/MAILING; verificar selección explícita, conservación de isPrimary omitido, desplazamiento de destino y ausencia de reemplazo automático.
- [x] 1.5 Documentar las reglas de dirección/coordenadas en docs/architecture/text-normalization.md y extender las pruebas de límites arquitectónicos; verificar Domain sin dependencias de transporte, persistencia, Mutiny o Application.

## 2. Contratos Application y validación geográfica

- [x] 2.1 Definir comandos CREATE/UPDATE/DELETE, intención, resultados de dirección/comprobante y selección paginada neutrales; verificar sus invariantes de versión, clave obligatoria de creación y propiedad mediante pruebas de contratos sin infraestructura.
- [x] 2.2 Añadir consulta de división con país ya resuelto y conservar la entrada alpha-2 actual; verificar con el cliente real y ReferenceStubServer una sola resolución de país, identidad/código/pertenencia y compatibilidad de las pruebas actuales de ambos adaptadores.
- [x] 2.3 Incorporar planificación y observación de validación geográfica por nuevas asignaciones; verificar país/división ACTIVE, ausencia diferenciada, cambio de país con división omitida, null explícito y ausencia de llamadas al conservar referencias o retirar solo división.
- [x] 2.4 Extender las pruebas contractuales de Geografía para DRAFT/DEPRECATED/RETIRED, 404, 200 incompatible, 503, timeout y headers admitidos; actualizar docs/contracts/external-references.md y verificar rutas/DTOs contra la evidencia ya documentada del proveedor.

## 3. Persistencia y evolución del esquema

- [x] 3.1 Añadir V7 con preflight y CHECK de metadatos de los tres namespaces HU06; verificar instalación limpia, upgrade de V6, rechazo de históricos incompatibles y conservación de seis tablas, checksums, datos y privilegios usando PostgreSQL 18 aislado.
- [x] 3.2 Añadir el índice parcial paginado por Site/createdAt/id y actualizar docs/database/v1-scheme.dbml, constraint-verification.md y la evidencia de persistencia; verificar catálogo válido/listo, predicado y restricciones previas de primaria/identidad/coordenadas intactas.
- [x] 3.3 Ampliar SiteRepository/ReactiveSiteRepository con detalle vigente y página calificada que lea Site.version junto a las direcciones; verificar aislamiento, eliminadas excluidas, padres ausentes, página vacía, filtros AND y orden con fechas empatadas.
- [x] 3.4 Incorporar escrituras calificadas de dirección, desmarcado de primaria, reclasificación y eliminación lógica con auditoría común; verificar en PostgreSQL el orden democión/promoción, los índices inmediatos y la conservación de identidad y auditoría de creación.
- [x] 3.5 Integrar actualización única de revisión/auditoría Site reutilizando su escritura condicionada y el orden Company/Site; verificar que varios cambios de dirección no disparen un segundo incremento ORM y que Company permanezca intacta.

## 4. Huellas, snapshots y cursores HU06

- [x] 4.1 Implementar la política HMAC HU06 versión 1 y sus tres namespaces; verificar vectores estables para defaults POST, presencia PATCH, DELETE vacío, If-Match original, jerarquía/addressId, equivalencia decimal y exclusión de actor/proceso/modo outbox.
- [x] 4.2 Implementar codec independiente formato 1 para dirección y comprobante, con validación de propiedad, tipos, revisión y auditoría; verificar round-trip JSONB, nulls internos, no-op, resultado efectivo y rechazo saneado de corrupción o versiones desconocidas.
- [x] 4.3 Implementar cursor autenticado exclusivo de direcciones; verificar tenant/Company/Site/filtros/orden, frontera fecha/ID, manipulación, otra colección, keyId/formato desconocidos y cambios permitidos de actor/proceso/limit.
- [x] 4.4 Documentar política, formato, keyring, rotación y retención HU06 en README/docs/architecture/persistence-contract.md; verificar con pruebas de K1/K2 y otra instancia que se reproduzcan snapshots originales sin TTL ni reinterpretación de HU01–HU05.

## 5. Orquestación atómica y coordinación remota

- [x] 5.1 Implementar el camino local protegido CREATE/UPDATE/DELETE con replay antes de estado actual y ausencia de dirección antes de revisión; verificar creación, no-op, comprobante, 404 de eliminadas, 412 de existentes y matriz de estados con pruebas de caso de uso.
- [x] 5.2 Incorporar preparación/finalización de sesión antes de Geografía y nueva protección posterior; verificar con barreras que no queden locks durante HTTP y que un replay, eliminación, revisión o estado concurrentes prevalezcan sobre el resultado remoto según las specs.
- [ ] 5.3 Componer dirección, desmarcados indirectos, Site, completado opcional y SiteMutatedOutbox en una aceptación; verificar con fallos inyectados rollback de todas las filas, auditoría común, cero eventos para no-op/replay y modos disabled/stored-only/published sin broker.
- [ ] 5.4 Añadir el plazo monotónico HU06 y configuración COMPANY_SITE_ADDRESS_WAIT_BUDGET con default 10s; verificar valores inválidos al arranque, timeout externo, espera de locks, finalización incierta, cierre previo a recuperación y ausencia de reejecución automática.
- [x] 5.5 Verificar con transacciones/peticiones independientes carreras de primarias, reclasificación, misma clave, claves distintas y misma clave entre Companies; comprobar una aceptación efectiva o conflicto preciso sin datos ajenos, cambios parciales ni versiones/eventos extra.
- [x] 5.6 Extender las carreras reales con HU05 y ciclo de vida Company, más un escritor de otro hijo que siga el protocolo; verificar versión compartida y orden Company/Site, documentando límites y presupuesto HU06 en docs/architecture/persistence-contract.md.

## 6. API, consultas y contrato publicado

- [x] 6.1 Incorporar DTOs/lectores JSON de creación, PATCH y DELETE vacío; verificar tipos exactos, ausencia/null/valor, duplicados, desconocidos, tokens posteriores, precisión decimal y precedencia estructural mediante pruebas de frontera HTTP.
- [x] 6.2 Incorporar las cinco rutas con parsers de contexto/UUID/If-Match/clave existentes y traducción del catálogo HU06; verificar todos los códigos 400/404/409/412/415/502/503/500, Process-Id actual y ausencia de payloads/diagnósticos internos.
- [x] 6.3 Implementar representación de dirección con siteVersion y comprobante mínimo DELETE 200; verificar campos exactos, opcionales omitidos, números JSON, auditoría y diferencias entre GET actual y replay original después de eliminar/cerrar/archivar.
- [x] 6.4 Integrar listado con filtros addressType/isPrimary, limit y cursor; verificar página vacía, límite 1/50/200, parámetros inválidos/repetidos, desempate, continuación, metadatos superiores y siteVersion coherente durante mutaciones concurrentes.
- [x] 6.5 Extender docs/contracts/company-registry.openapi.yaml y crear docs/contracts/company-site-address-management.md con ejemplos y catálogos; actualizar planned-operations.md y verificar OpenAPI contra respuestas reales usando las utilidades contractuales existentes.
- [x] 6.6 Extender las pruebas HTTP de ausencia de llamadas geográficas en GET/DELETE/replay/no-op y correcciones sin nueva referencia, incluyendo proveedores caídos y estados terminales; verificar 404 antes de revisión para eliminadas y 412 antes de estado para direcciones existentes.

## 7. Integración del artefacto y aceptación completa

- [ ] 7.1 Ampliar composición y metadata de serialización nativa HU06 y la suite de proceso empaquetado de src/integrationTest; verificar rutas reales, números exactos, snapshots tras reinicio, contrato OpenAPI/evento y arranque offline sin fixtures en producción.
- [ ] 7.2 Ejecutar la regresión JVM/PostgreSQL y ./gradlew clean build, ./gradlew quarkusIntTest y ./gradlew verifyProductionIsolation con los perfiles documentados; verificar HU01–HU05 intactas y registrar resultados reales, sin atribuir cobertura nativa al build JVM.
- [ ] 7.3 Construir y verificar el ejecutable nativo mediante el flujo Mandrel/Podman existente; comprobar creación/PATCH/DELETE/replay/paginación y precisión con las rutas HU06, registrando comando, artefacto y resultados en docs/operations/native-verification.md.
- [ ] 7.4 Entregar docs/operations/hu06-acceptance-review.md con matriz AC01–AC16, resultados reproducibles JVM/empaquetado/nativo, concurrencia, fallos de dependencia, rollback y retención; verificar que cada criterio y escenario acordado tenga evidencia y que OpenSpec valide estrictamente.
