# Trabajo con agentes en este proyecto

## Referencia y alcance

`PLAN.md` contiene el plan Android aprobado. Leerlo antes de implementar. No iniciar la implementación de la app por el mero hecho de cargar estos archivos: trabajar sobre la tarea asignada por el usuario.

Para tareas de implementación de la aplicación, el usuario autoriza este flujo de tres subagentes y la comunicación entre ellos dentro de la tarea. El agente principal coordina; no se crean chats independientes para este flujo.

## Roles fijos

| Nombre | Modelo | Esfuerzo | Responsabilidad |
| --- | --- | --- | --- |
| implementer | gpt-6-luna | xhigh | Código de producción, integración y correcciones |
| tester | gpt-6-luna | xhigh | Pruebas, recursos de prueba y evidencia de validación |
| reviewer | gpt-6-astra | medium | Revisión sin editar archivos y recomendaciones de corrección |

Las definiciones se encuentran en `.codex/agents/`. Mantener estos modelos y esfuerzos. Si el entorno no permite utilizarlos, informar del bloqueo; no sustituirlos silenciosamente. Si el mecanismo de lanzamiento exige contexto sin historial para seleccionar un modelo, pasar explícitamente el alcance, los archivos del plan y la evidencia necesaria.

Máximo tres subagentes abiertos simultáneamente, además del coordinador. Reutilizar los agentes existentes. Los subagentes no delegan a nuevos agentes.

## Ciclo de trabajo

1. El coordinador delimita una unidad de trabajo y asigna al implementador los archivos y requisitos correspondientes.
2. El implementador entrega cambios y explica las comprobaciones ejecutadas y sus limitaciones.
3. El tester escribe y ejecuta las pruebas aplicables sobre esos cambios. Comunica fallos reproducibles al implementador y al coordinador.
4. El reviewer inspecciona cambios y resultados. Envía al implementador y al coordinador los errores y próximos pasos recomendados.
5. El coordinador reactiva al implementador para corregir. Después solicita nuevas pruebas y revisión de las correcciones.
6. Cerrar cuando las pruebas aplicables pasen y no haya hallazgos pendientes. Si falta una comprobación necesaria, informar del bloqueo y del trabajo pendiente sin declarar validación completa.

La comunicación puede ser directa entre subagentes; el coordinador es responsable de que se entregue y de reactivar a un agente inactivo. Si no existe mensajería directa, reenviar el informe a través del coordinador. No confundir enviar un mensaje con iniciar un nuevo turno del destinatario.

## Propiedad de archivos y límites

- Evitar escrituras simultáneas en los mismos archivos. Ejecutar las etapas secuencialmente cuando compartan archivos o dependan de cambios anteriores.
- El implementador modifica producción y configuración compartida. El tester modifica pruebas y sus recursos; solicita cambios de producción al implementador.
- El reviewer solo lee y reporta. Es una instrucción de comportamiento: los permisos técnicos se heredan de la sesión principal.
- El coordinador resuelve discrepancias usando requisitos y evidencia. Si una decisión cambia el alcance aprobado, consultar al usuario.
- No eliminar ni sobrescribir trabajo ajeno. No cambiar modelos, requisitos, licencias o criterios de aceptación para eludir un bloqueo.

## Informes y comprobaciones

Cada entrega identifica cambios, comandos ejecutados, resultados y comprobaciones pendientes. Cada fallo incluye resultado esperado, resultado observado y reproducción. Cada hallazgo de revisión incluye prioridad P0-P3, ubicación, evidencia, impacto, una solución concreta con condición de aceptación y la prueba o comprobación que la valida.

El reviewer debe decirle al implementador cómo corregir cada hallazgo accionable, además de informar al coordinador. El implementador es quien modifica producción; el reviewer no aplica el cambio. Si la mensajería directa no está disponible, el coordinador reenvía los pasos y reactiva al implementador.

No inventar resultados. Las pruebas con dobles no acreditan calidad real de modelos. El emulador no acredita rendimiento en el teléfono objetivo. Validar latencia, memoria, batería y temperatura con dispositivo físico según PLAN.md.

Si un ciclo repite el mismo fallo sin progreso, detener los reintentos, documentar lo intentado y elevar el bloqueo al coordinador para decidir el siguiente paso.
