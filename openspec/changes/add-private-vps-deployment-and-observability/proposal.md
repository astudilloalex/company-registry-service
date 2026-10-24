# Proposal

## Why

Company Registry ya dispone de HU01–HU08 y evidencia JVM/nativa, pero carece de despliegue productivo repetible, pipeline propio y observabilidad operacional completa. La [issue #11, T01](https://github.com/astudilloalex/company-registry-service/issues/11), debe llevarlo al VPS Debian 13 existente siguiendo el modelo de Party Registry y el alcance acordado para esta primera etapa.

## What Changes

- Desplegar la imagen nativa con Podman rootless y Quadlet, usuario interno no privilegiado, puerto interno 8083 y alias company-registry-service, sin publicar puertos del host ni rutas Nginx.
- Conectar desde el primer despliegue las tres unidades internal-services.network, company-db.network y messaging.network en todos los modos outbox, incluido disabled.
- Reutilizar PostgreSQL 18 con base company_registry_service y roles separados de runtime/migración; preparar el acceso privado y verificar permisos sin modificar las bases de otros servicios.
- Incorporar GitHub Actions para validar PR, construir/probar/publicar la imagen y desplegar automáticamente desde main, con ejecución manual desde main. Utilizar secretos y variables del entorno production y una referencia de imagen por digest.
- Externalizar credenciales, URLs, límites y keyring HMAC; validar y transferir configuración protegida, conservando las claves históricas requeridas por los datos incluso durante rollback.
- Fijar COMPANY_OUTBOX_MODE=disabled en la puesta en marcha inicial. messaging permanece conectada, pero no se requieren credenciales ni conexión RabbitMQ y no se almacenan nuevos eventos. Mantener los tres modos y documentar la futura activación de published cuando existan colas y bindings verificados, sin backfill.
- Completar health checks, logs estructurados correlacionados en journald y métricas internas de HTTP, dependencias, pool PostgreSQL reactivo y outbox. Actualizar la observación de backlog también cuando la entrega está deshabilitada.
- Hacer verificable el despliegue y el rollback compatible de imagen/configuración mediante readiness y smoke tests, manteniendo migraciones, datos, idempotencia y outbox.

**Alcance diferido por decisión del usuario:** backups, destinos externos, automatización de copias y pruebas de restauración; AC16 de la issue permanece diferido. Tampoco se instalarán colectores, dashboards ni alertas continuas. La activación productiva de published y la creación de consumidores/colas/bindings quedan para una etapa posterior; CI sí verificará los tres modos con infraestructura desechable.

## Capabilities

### New Capabilities

- `private-vps-deployment`: ejecución privada en Quadlet, tres redes persistentes, configuración protegida, aislamiento de base y recuperación compatible del despliegue.
- `continuous-delivery`: controles de calidad, verificación del artefacto nativo e imagen, publicación GHCR y despliegue automático/manual desde main con configuración de production.
- `operational-observability`: salud, correlación y privacidad de logs, métricas internas y evidencia operacional sin plataforma de recolección.

### Modified Capabilities

- `outbox-publication`: añadir observación periódica de backlog independiente del modo de entrega, sin conexiones AMQP en disabled/stored-only ni mutaciones realizadas por el muestreo.

## Impact

Afecta los futuros archivos .github/workflows y deploy/, el empaquetado nativo, build.gradle.kts, configuración, filtros de contexto, adaptadores técnicos de métricas y sus pruebas/documentación. Reutiliza Flyway V1–V9, el keyring y contratos existentes, sin cambiar APIs, hechos de integración ni políticas de dominio; cualquier ajuste de esquema que resulte necesario deberá usar una nueva migración.

La preparación del VPS, la configuración de production y las verificaciones reales serán trabajo de implementación. Este cambio crea exclusivamente artefactos de planificación; no configura GitHub, no accede al VPS ni inicia un despliegue.
