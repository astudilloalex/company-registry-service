# Proposal

## Why

HU01–HU07 ya conservan transaccionalmente los siete hechos de Company y Site, pero ningún modo entrega esos eventos a RabbitMQ. HU08 debe completar esa entrega y su recuperación sin convertir la disponibilidad del broker en una dependencia de las operaciones de negocio ([historia #10](https://github.com/astudilloalex/company-registry-service/issues/10)).

## What Changes

- Incorporar un publicador reactivo programado con reservas PostgreSQL excluyentes, vencimiento recuperable, confirmaciones RabbitMQ y transiciones protegidas contra resultados de intentos anteriores.
- Entregar los siete contratos V1 ya existentes desde su snapshot original, con identidad estable, validación cerrada y routing key derivada del tipo de evento. Comprobar un recorrido completo de creación de Company y publicación/enrutamiento de cada tipo.
- Activar publicación únicamente en `published`; preservar `disabled` como valor por defecto y `stored-only` como almacenamiento sin broker. Aplicar cambios de modo mediante configuración y reinicio del mismo artefacto.
- Implementar reintentos con backoff exponencial acotado y máximo de intentos también ante caídas; conservar `FAILED` con diagnóstico y sin reactivación automática.
- Exponer métricas y logs de entrega manteniendo la disponibilidad de negocio independiente de RabbitMQ. Documentar publicación al menos una vez, desorden permitido incluso por agregado y deduplicación por `eventId`.
- Definir `PUBLISHED` como confirmación del broker, sin garantizar procesamiento consumidor ni existencia de una cola de destino. Exigir preparar/verificar las colas y bindings previstos antes de activar `published`.
- **BREAKING (configuración reservada):** reemplazar la routing key fija `company.events` por una plantilla con default `%s`, sustituido por eventType; conservar los nombres de variables de HU08 y adaptar overrides fijos anteriores antes del arranque.
- Incorporar pruebas PostgreSQL/RabbitMQ y de procesos JVM/nativos, incluida recuperación después de confirmación incierta, reinicios y publicadores concurrentes.

Quedan fuera la instalación del broker, nuevos virtual hosts, creación de colas de consumidores en producción, consumidores, eventos adicionales, orden estricto por agregado, entrega exactamente una vez, purga automática y reprocesamiento administrativo de `FAILED`.

## Capabilities

### New Capabilities

- `outbox-publication`: entrega asíncrona de hechos almacenados, contratos y routing, reservas, confirmaciones, recuperación, límites de intentos y observabilidad.

### Modified Capabilities

- `outbox-configuration`: convertir los modos y parámetros preparados en controles efectivos de publicación, con broker opcional fuera de `published`, límites completos, destinos configurables y transición de modo por reinicio.

## Impact

Afecta la composición y los adaptadores outbox/persistencia, nuevas operaciones y puertos de aplicación, `build.gradle.kts`, configuración y recursos de pruebas. Añade extensiones Quarkus de RabbitMQ, scheduler y métricas bajo el BOM vigente; reutiliza productores, esquemas, outbox y contexto originales. No modifica endpoints ni payloads de negocio; cualquier DDL demostrado necesario usará una migración Flyway nueva y preservará V1–V8 e historia existente.

La implementación deberá abrir individualmente cada archivo Java/Python creado o modificado en Antigravity, esperar el análisis de su extensión SonarQube, corregir todos sus warnings y cerrar la pestaña después de validarla. Se corregirán las causas en lugar de silenciar hallazgos con `@SuppressWarnings`, `NOSONAR`, exclusiones o debilitamiento del análisis; la evidencia de editor y las pruebas de ejecución se registrarán por separado.
