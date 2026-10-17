# Proposal

## Why

La [issue #2, T00](https://github.com/astudilloalex/company-registry-service/issues/2) bloquea HU01–HU08 y T01: el servicio tiene un bootstrap Gradle y una migración SQL, pero todavía carece de aplicación, configuración y pruebas que permitan demostrar una base ejecutable. Se necesita consolidar esa base conforme a Party Registry antes de implementar capacidades de negocio incrementales.

## What Changes

- Completar el esqueleto Quarkus reactivo con Java 25, Gradle Kotlin DSL y la línea 3.40.1 ya compartida con Party Registry, con entrega nativa obligatoria y verificación de compatibilidad y resolución de dependencias.
- Establecer límites Clean Architecture, modelos/enums y políticas puras, normalización canónica y contratos de persistencia e integración sin implementar CRUD completo.
- Activar Flyway como autoridad exclusiva del esquema PostgreSQL 18, reutilizando `V1__create_company_registry_schema.sql`, con credenciales de runtime y migración independientes.
- Documentar y verificar las seis tablas de negocio/técnicas, sus restricciones, UUIDv7, relaciones tenant-qualified, índices parciales, auditoría, borrado lógico, idempotencia y outbox. Preservar el historial de V1; cualquier corrección necesaria será incremental.
- Crear DBML, contrato OpenAPI inicial y convenciones HTTP `/v1`: contexto obligatorio, propagación de `Process-Id`, envelope compartido, errores estables, JSON estricto, concurrencia y paginación documentada.
- Definir puertos para Party y referencias geográficas con clientes no bloqueantes, URL configurable y timeouts finitos. La ruta de divisiones existe en Postman; su respuesta y semántica deben confirmarse antes de habilitar el adaptador.
- Definir configuración outbox `disabled`, `stored-only`, `published`, por defecto `disabled`, y mapeos persistentes preparados para HU08.
- Añadir pruebas unitarias, arquitectónicas, HTTP y PostgreSQL, build nativo con Mandrel/GraalVM compatible con Java 25 e imagen nativa compatible con Podman, siguiendo Party Registry. Conservar JVM para desarrollo y verificaciones complementarias.
- Exigir pruebas contra el ejecutable nativo de arranque/Flyway, persistencia reactiva, JSON y respuestas/errores compartidos, clientes habilitados, recursos OpenAPI y outbox disabled sin broker; incluir inicialización segura en runtime de la generación UUIDv7, instrucciones locales y decisiones de arquitectura.
- Preparar la incorporación de Company Registry al LikeC4 empresarial central; documentar el cambio coordinado en ese repositorio como dependencia del entregable arquitectónico.

## Capabilities

### New Capabilities

- `service-foundation`: arranque reproducible, perfil reactivo, separación de responsabilidades, ejecutable/imagen nativos obligatorios y verificación JVM/nativa.
- `company-data-integrity`: contrato de las seis tablas, aislamiento de referencias, restricciones, migraciones e integridad concurrente.
- `business-api-conventions`: contexto HTTP, respuesta/error compartidos, entrada estricta y contratos base de concurrencia, idempotencia y paginación.
- `business-text-normalization`: canonización determinista de texto de negocio y tratamiento especializado de contactos/valores opacos.
- `external-reference-validation`: validación contractual de Party, países y divisiones mediante puertos internos y fallos controlados.
- `outbox-configuration`: modos, parámetros validados y contrato persistente sin publicación en esta entrega.

### Modified Capabilities

Ninguna: no existen specs principales en este repositorio.

## Impact

Se afectarán build/configuración, `src/main/java`, pruebas JVM/nativas, Dockerfile nativo, recursos de contratos, README y documentación de base de datos/arquitectura. Se incorporará el módulo publicado `api-response-quarkus-errors`, Hibernate Reactive, cliente PostgreSQL reactivo, Flyway/JDBC exclusivo de migraciones y extensiones REST/validación/operación necesarias. El entregable de ejecución será nativo; compilar únicamente JVM no satisface T00. El build deberá registrar builder JDK 25, plataforma objetivo e imagen de runtime compatibles.

El modelo empresarial es la fuente de verdad de LikeC4. Party Registry y Geographic Reference son proveedores HTTP; sus bases de datos no son integraciones permitidas. T00 entrega fundamento y pruebas de frontera; HU01–HU07 implementarán los workflows y HU08 la publicación RabbitMQ. Producción, proxy público, Keycloak, administración de tenants e infraestructura del broker pertenecen a otros alcances.
