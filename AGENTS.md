# Trabajo con agentes en este proyecto

## Referencia y alcance

`PLAN.md` contiene el plan Android aprobado. Leerlo antes de implementar. No iniciar la implementación de la app por el mero hecho de cargar estos archivos: trabajar sobre la tarea asignada por el usuario.

Para cambios del proyecto que afecten la app/APK, código auxiliar, pruebas, configuración o documentación, se usa este flujo de tres subagentes y la comunicación entre ellos dentro de la tarea. El agente principal coordina; no se crean chats independientes para este flujo.

## Coordinación y roles fijos

El coordinador es el hilo principal, no un subagente, y usa `gpt-6.1-sol` con esfuerzo `max`, configurados como defaults de proyecto en `.codex/config.toml`. Estos defaults no fuerzan el modelo de un chat ya abierto si la UI o la sesión seleccionan otro explícitamente; confirma el modelo/esfuerzo efectivos al coordinar. No crees un archivo de agente para el coordinador.

| Nombre | Modelo | Esfuerzo | Responsabilidad |
| --- | --- | --- | --- |
| implementer | gpt-6-luna | xhigh | Código de producción, configuración/documentación asignadas, integración y correcciones |
| tester | gpt-6-luna | xhigh | QA de comportamiento end-to-end, integración, regresiones y evidencia |
| reviewer | gpt-6.1-sol | max | Revisión sin editar archivos y recomendaciones de corrección |

Las definiciones de los tres subagentes se encuentran en `.codex/agents/`. Mantener estos modelos y esfuerzos. Si el entorno no permite utilizarlos, informar del bloqueo; no sustituirlos silenciosamente. Si el mecanismo de lanzamiento exige contexto sin historial para seleccionar un modelo, pasar explícitamente el alcance, los archivos del plan y la evidencia necesaria.

Máximo tres subagentes abiertos simultáneamente, además del coordinador. Reutilizar los agentes existentes. Los subagentes no delegan a nuevos agentes.

La configuración sigue los formatos oficiales de [agentes personalizados](https://learn.chatgpt.com/docs/agent-configuration/subagents) y [configuración TOML](https://learn.chatgpt.com/docs/config-file/config-reference).

## Ciclo de trabajo

1. El coordinador delimita una unidad de trabajo y asigna al implementador los archivos y requisitos correspondientes.
2. El implementador entrega cambios y explica las comprobaciones ejecutadas y sus limitaciones.
3. El tester realiza QA proporcional al cambio, priorizando comportamiento end-to-end, integración, regresiones y casos de fallo con evidencia reproducible. Cuando el alcance afecta un flujo o datos de la app, verifica lo observable para el usuario y el estado persistido (por ejemplo, Room, audio o archivos) cuando corresponda; éxito de comandos, compilación, mocks o pruebas unitarias no sustituye esas comprobaciones. No añade pruebas unitarias redundantes. Para cambios solo de configuración/documentación, valida parseo/formato, valores, referencias y coherencia del diff; no instala APK ni usa el micrófono.
4. El reviewer inspecciona cambios y resultados. Envía al implementador y al coordinador los errores y próximos pasos recomendados.
5. El coordinador reactiva al implementador para corregir. Después solicita las pruebas afectadas y una nueva revisión. Si hay cualquier edición posterior, se reabren las comprobaciones afectadas; la evidencia anterior no valida el nuevo diff.
6. Antes del OK técnico final, tester y reviewer confirman que validaron el mismo diff/revisión exactos y, cuando aplique, el mismo artefacto final (identificado por hash o versión). Cerrar la etapa técnica solo cuando el QA aplicable termine, no queden hallazgos accionables ni bloqueos en comprobaciones necesarias. Si falta una comprobación necesaria, informar del bloqueo y del trabajo pendiente sin declarar validación completa.

La comunicación puede ser directa entre subagentes; el coordinador es responsable de que se entregue y de reactivar a un agente inactivo. Si no existe mensajería directa, reenviar el informe a través del coordinador. No confundir enviar un mensaje con iniciar un nuevo turno del destinatario.

## Publicación en GitHub

- Cuando el QA aplicable termina y el reviewer no tiene hallazgos accionables ni quedan bloqueos en comprobaciones necesarias sobre el mismo diff final, el coordinador da un OK técnico explícito vinculado a esa revisión y al artefacto exacto cuando aplique.
- Ese OK técnico autoriza al implementador a crear un commit con los cambios aprobados y hacer push a `origin/main` en el repositorio privado `jeanmhf/voztranslate-android`. No se espera otra aprobación del usuario.
- El implementador no publica antes de recibir ese OK. El coordinador puede pedir correcciones; cualquier edición después del QA/review requiere repetir las comprobaciones afectadas y confirmar el artefacto final antes del OK.
- Para cambios de APK, validar el artefacto final y adjuntarlo como asset de Release cuando corresponda; registrar su hash/versión para evitar publicar un APK distinto del validado. Los cambios solo de configuración/documentación no requieren una nueva Release APK.
- El OK técnico no significa que la publicación ya terminó. La tarea se cierra tras verificar que el commit aprobado está en `origin/main` y, para APKs, que la Release y el asset publicado corresponden al artefacto validado. Si falla el push o la subida, informar el bloqueo sin afirmar que se publicó.
- Publicar solo los cambios aprobados de la tarea. No incluir credenciales, `local.properties`, compilaciones, grabaciones ni modelos descargados; respetar `.gitignore`. No forzar pushes ni reescribir historial. Si el push falla, conservar el trabajo local y reportar el bloqueo sin afirmar que se publicó.

## Propiedad de archivos y límites

- Evitar escrituras simultáneas en los mismos archivos. Ejecutar las etapas secuencialmente cuando compartan archivos o dependan de cambios anteriores.
- El implementador modifica producción y la configuración/documentación asignadas. El tester modifica pruebas y sus recursos; solicita cambios de producción al implementador.
- El reviewer solo lee y reporta. Es una instrucción de comportamiento: los permisos técnicos se heredan de la sesión principal.
- El coordinador resuelve discrepancias usando requisitos y evidencia. Si una decisión cambia el alcance aprobado, consultar al usuario.
- No eliminar ni sobrescribir trabajo ajeno. No cambiar modelos, requisitos, licencias o criterios de aceptación para eludir un bloqueo.

## Informes y comprobaciones

Cada entrega identifica cambios, comandos ejecutados, resultados y comprobaciones pendientes. Cada fallo incluye resultado esperado, resultado observado y reproducción. Cada hallazgo de revisión incluye prioridad P0-P3, ubicación, evidencia, impacto, una solución concreta con condición de aceptación y la prueba o comprobación que la valida.

El reviewer debe decirle al implementador cómo corregir cada hallazgo accionable, además de informar al coordinador. El implementador es quien modifica producción; el reviewer no aplica el cambio. Si la mensajería directa no está disponible, el coordinador reenvía los pasos y reactiva al implementador.

No inventar resultados. Las pruebas con dobles no acreditan calidad real de modelos. El emulador no acredita rendimiento en el teléfono objetivo. Validar latencia, memoria, batería y temperatura con dispositivo físico según PLAN.md cuando esos resultados sean parte del alcance. Para cambios solo de configuración/documentación, limitar la validación a los artefactos documentales y de configuración; no instalar APK ni usar micrófono.

Si un ciclo repite el mismo fallo sin progreso, detener los reintentos, documentar lo intentado y elevar el bloqueo al coordinador para decidir el siguiente paso.
