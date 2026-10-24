# Spec Delta

## Purpose

Entregar versiones verificadas de Company Registry desde GitHub hasta el VPS mediante un pipeline trazable, con controles de calidad y configuración productiva protegida.

## ADDED Requirements

### Requirement: Disparadores de validación y despliegue
El pipeline SHALL validar pull requests y ejecutar la entrega automáticamente ante actualizaciones de main. SHALL admitir ejecución manual desde main. Las ejecuciones de PR u otras referencias SHALL NOT desplegar al VPS ni consumir secretos del entorno production.

#### Scenario: Validación de un pull request
- **WHEN** se abre o actualiza un PR
- **THEN** se ejecutan sus controles de calidad con infraestructura y configuración de pruebas
- **AND** la ejecución no publica una imagen productiva ni recibe credenciales de despliegue

#### Scenario: Entrega automática o manual desde main
- **WHEN** una actualización de main o una ejecución manual desde main inicia el pipeline
- **THEN** la entrega supera los controles requeridos antes de publicar y desplegar
- **AND** los resultados identifican commit, ejecución e imagen correspondientes

#### Scenario: Ejecución manual en otra referencia
- **WHEN** se solicita una ejecución manual desde una rama o etiqueta distinta de main
- **THEN** no se ejecuta el despliegue productivo
- **AND** no se expone configuración de production a esa referencia

### Requirement: Controles de calidad obligatorios
La entrega SHALL fallar ante errores de formato o análisis estático configurado, compilación, arquitectura, pruebas unitarias, integración PostgreSQL o contratos OpenAPI, esquema y eventos. Las pruebas SHALL utilizar infraestructura desechable y sus resultados SHALL ser trazables a las fuentes de la entrega.

#### Scenario: Un control requerido falla
- **WHEN** falla una comprobación obligatoria o una suite requerida no se ejecuta
- **THEN** la entrega queda fallida y no activa la nueva versión en el VPS
- **AND** los reportes permiten identificar el control y la revisión afectados

#### Scenario: Aislamiento de la verificación
- **WHEN** se verifican migraciones, concurrencia, fallos de proveedores o publicación RabbitMQ
- **THEN** se utilizan bases, broker y proveedores controlados de pruebas
- **AND** las pruebas no detienen ni modifican PostgreSQL, RabbitMQ o proveedores compartidos del VPS

### Requirement: Artefacto nativo e imagen final verificados
La entrega SHALL construir un ejecutable nativo compatible con Java 25 y la arquitectura del VPS y comprobar su comportamiento en un proceso real. SHALL verificar también la imagen final con las restricciones productivas, contratos empaquetados y ausencia de rutas auxiliares. Una suite JVM o compilación nativa exitosa por sí sola SHALL ser insuficiente.

#### Scenario: Verificación del ejecutable distribuible
- **WHEN** se prepara una versión publicable
- **THEN** las pruebas del proceso nativo verifican persistencia, contratos, contexto, clientes y comportamiento outbox
- **AND** el ejecutable utilizado pertenece al artefacto productivo identificado en la entrega

#### Scenario: Verificación de la imagen final
- **WHEN** se prueba la imagen que se publicará
- **THEN** arranca con configuración productiva de prueba, usuario no root y restricciones previstas
- **AND** sus comprobaciones de salud funcionan y sus rutas auxiliares de verificación están ausentes

### Requirement: Publicación y despliegue por identidad de imagen
La entrega SHALL publicar en GHCR la imagen comprobada, registrar su digest y asociarlo con el commit y resultados de verificación. El despliegue SHALL utilizar esa identidad inmutable. Una recuperación de versión SHALL reutilizar el digest previamente conservado sin reconstruirlo desde una etiqueta mutable.

#### Scenario: Imagen publicada y activada
- **WHEN** el pipeline activa una imagen en el VPS
- **THEN** la referencia efectiva corresponde al digest publicado para la imagen verificada
- **AND** un cambio posterior de latest o de otra etiqueta no cambia esa referencia

#### Scenario: Recuperación con dependencias que han cambiado
- **WHEN** se recupera una versión anterior y una dependencia SNAPSHOT ha cambiado en el registro
- **THEN** se utiliza el digest anterior conservado
- **AND** no se presume que reconstruir el mismo commit reproduciría el mismo binario

### Requirement: Configuración desde el entorno production
El job de despliegue SHALL obtener secretos y variables del entorno production de GitHub y validar la configuración antes de activar cambios. Los secretos de aplicación y SSH SHALL limitarse al despliegue. Los archivos temporales SHALL ser privados, transferirse por SSH con identidad del servidor verificada y excluirse de logs, imágenes y artefactos públicos.

#### Scenario: Secreto obligatorio ausente
- **WHEN** falta una credencial de base, keyring o acceso SSH necesaria para la instalación
- **THEN** falla el despliegue antes de reemplazar la versión activa
- **AND** el error identifica el parámetro sin mostrar su valor

#### Scenario: RabbitMQ opcional en disabled
- **WHEN** production configura disabled y no contiene credenciales RabbitMQ
- **THEN** el render y despliegue pueden completarse con las tres redes presentes
- **AND** los controles no exigen conectividad, colas ni bindings del broker

#### Scenario: Limpieza de temporales
- **WHEN** finaliza o falla una ejecución de despliegue
- **THEN** se eliminan sus temporales de secretos que ya no sean necesarios
- **AND** cualquier material conservado para recuperación permanece protegido y se informa sin revelar su contenido

### Requirement: Un único despliegue activo
El pipeline SHALL impedir despliegues productivos simultáneos y SHALL NOT cancelar una activación en curso por la llegada de una ejecución posterior. El acceso al entorno productivo SHALL limitarse a la referencia main.

#### Scenario: Actualización durante una activación
- **WHEN** llega otro disparador mientras una entrega está activando configuración o verificando salud
- **THEN** esa activación conserva exclusividad hasta terminar o recuperarse
- **AND** la ejecución posterior no modifica sus archivos o contenedores concurrentemente

### Requirement: Smoke tests como condición de aceptación
El despliegue SHALL superar comprobaciones internas de liveness, readiness, contrato y operaciones de verificación antes de declararse satisfactorio. Las pruebas de negocio SHALL usar un ámbito de prueba dedicado y respetar idempotencia y contexto tenant. Un fallo SHALL conservar el estado fallido de la entrega e iniciar la recuperación compatible prevista.

#### Scenario: Fallo funcional con readiness satisfactoria
- **WHEN** readiness responde correctamente pero falla un smoke test obligatorio
- **THEN** la entrega no queda aceptada
- **AND** el procedimiento de despliegue intenta recuperar la versión compatible anterior y registra el resultado

#### Scenario: Verificación de negocio controlada
- **WHEN** se verifica creación, lectura y replay de Company y Site en el VPS
- **THEN** se utilizan tenant, Party y claves idempotentes identificados para esa verificación
- **AND** no se utiliza ni modifica información de negocio ajena al ámbito de prueba

### Requirement: Diagnósticos trazables sin secretos
El pipeline SHALL conservar resultados de controles, identidad del artefacto y diagnósticos saneados de despliegue. SHALL distinguir pruebas JVM, procesos nativos, comprobaciones de imagen y verificaciones reales del VPS, sin atribuir a una frontera resultados obtenidos en otra.

#### Scenario: Fallo de build o despliegue
- **WHEN** una ejecución termina con error
- **THEN** los reportes identifican el paso, commit y artefacto involucrados cuando estén disponibles
- **AND** excluyen archivos de entorno, keyring, claves SSH, contraseñas y contenido confidencial de respuestas o logs
