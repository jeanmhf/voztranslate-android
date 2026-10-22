# Prototipo Android para transcripción y traducción offline

## Objetivo y alcance

Demostrar transcripción, traducción y separación de hablantes en un teléfono Android de gama media, antes de construir el MVP comercial. Las cifras de rendimiento siguientes son objetivos por medir, no resultados comprobados.

- Android primero, funcionamiento offline después de descargar los modelos.
- Inglés, español y chino mandarín; salida en chino simplificado.
- Traducción en texto, sin síntesis de voz en esta etapa.
- Conferencias, conversaciones presenciales y captura de audio de otras aplicaciones cuando Android y la aplicación de origen lo permitan.
- Idioma de origen seleccionado manualmente. En conversación, botones para alternar entre los dos idiomas elegidos.
- Hablantes identificados como Speaker 1, Speaker 2, etc., dentro de cada grabación. Sin identificación biométrica persistente entre sesiones.
- Grabaciones e historial locales, con exportación.

## Stack y modelos

| Área | Elección |
| --- | --- |
| Aplicación | Kotlin, Jetpack Compose, ViewModel, Coroutines y Flow |
| Dispositivo objetivo | Android 10 o superior; ABI `arm64-v8a`, `armeabi-v7a` o `x86_64`; 6 GB de RAM recomendados |
| Captura | AudioRecord; MediaProjection y AudioPlaybackCapture para reproducción permitida |
| Transcripción | Moonshine Tiny Streaming para los tres idiomas |
| Hablantes | cpp-annote con modelos pyannote community-1 |
| Traducción | M2M100 418M exportado a ONNX y cuantizado a INT8 |
| Integración nativa | C++ mediante JNI y tokenizador SentencePiece compatible con el modelo |
| Datos | Room para sesiones y segmentos; archivos privados para audio; DataStore para preferencias |
| Descargas | WorkManager, reanudación y comprobación de integridad |
| Exportación | Audio M4A, texto bilingüe TXT y datos JSON |

M2M100 permite traducción directa entre los idiomas elegidos y tiene licencia MIT. Debe medirse el tamaño y consumo reales del paquete cuantizado. El archivo de pesos de precisión completa ronda 1,94 GB; no usar ese valor como estimación del paquete final.

Usar las variantes **Streaming MIT** de Moonshine seleccionadas; no sustituirlas por variantes antiguas con restricciones no comerciales. cpp-annote tiene código MIT y los modelos community-1 requieren atribución CC BY 4.0. Registrar versiones, fuentes, licencias y hashes de todos los modelos antes de distribuirlos.

Fijar versiones compatibles de dependencias y modelos. Resolver el runtime ONNX compartido mediante la integración nativa: evitar empaquetar versiones incompatibles de `libonnxruntime.so` para ASR y traducción.

El APK de evaluación incluye las tres ABI de la tabla para ampliar la compatibilidad con la mayoría de los teléfonos Android 10 o superior. Se recomiendan 6 GB de RAM para procesar los modelos locales; admitir más arquitecturas no garantiza que estos se ejecuten con fluidez en todos los teléfonos.

## Arquitectura y comportamiento

- Interfaces principales: `AudioSource`, `SpeechEngine`, `SpeakerTracker`, `TranslationEngine` y `SessionRepository`.
- Captura continua en un servicio en primer plano con notificación, pausa y parada. Guardar audio mientras se procesa, fuera del hilo de interfaz.
- Conferencia: idioma de origen fijo. Conversación: seleccionar dos idiomas y alternar origen/destino mediante botones.
- Mostrar transcripción parcial inmediatamente. Traducir texto parcial cambiado como máximo una vez cada dos segundos; al finalizar un segmento, reemplazar la traducción provisional por la definitiva.
- Descartar trabajos provisionales obsoletos y conservar los segmentos finales. Medir la cola de trabajo y comunicar retrasos.
- Mantener un seguimiento de hablantes independiente de los motores ASR durante toda la sesión. Cambiar el idioma no reinicia las identidades de hablantes.
- Permitir correcciones recientes de asignaciones de hablantes. No prometer separación perfecta cuando varias personas hablan a la vez.
- Cada segmento tiene identificador estable y revisión para actualizar texto, traducción y hablante sin duplicados en UI ni almacenamiento.
- Guardar progresivamente audio, tiempos, idiomas, texto original, traducción y hablantes. Recuperar las sesiones interrumpidas con el contenido ya persistido.
- Descargar modelos antes de la primera sesión offline, mostrando tamaño, espacio requerido y atribuciones.

### Captura de videollamadas y otras aplicaciones

Solicitar las autorizaciones de captura correspondientes. AudioPlaybackCapture no habilita universalmente el audio de comunicaciones: depende del uso de audio y de la política de la aplicación de origen. No intentar eludir estas restricciones.

Ante incompatibilidad confirmada, ofrecer captura por micrófono con aviso, solo si está disponible. Android también puede silenciar el micrófono durante una llamada. Detectar silenciamiento e interrupciones mediante las señales del sistema, informar al usuario y registrar la interrupción. El silencio por sí solo no demuestra que la captura esté bloqueada.

## Fases de implementación

1. Preparar herramientas Android y estructura nativa; fijar dependencias, modelos, licencias e integración del runtime compartido.
2. Construir captura, persistencia progresiva, descarga de modelos y recuperación de sesiones.
3. Integrar ASR, seguimiento de hablantes y traducción; conectar actualizaciones parciales/finales a la UI.
4. Añadir flujos de conferencia/conversación, captura de reproducción permitida e historial/exportación.
5. Validar el sistema combinado en un dispositivo físico y entregar APK de evaluación con informe de resultados.

## Criterios de validación

Con modelos ya cargados y audio claro:

- Primera transcripción parcial en ≤ 1,5 segundos en el 95 % de los turnos medidos.
- Traducción final en ≤ 3 segundos desde el final de frases de hasta 20 palabras o 40 caracteres chinos, en el 95 % de los casos.
- Sesión combinada de 60 minutos sin cierres, pérdida de audio ni crecimiento ilimitado de la cola.
- Pico de RAM del proceso inferior a 2,5 GB. Registrar también espacio de modelos, batería y temperatura.
- Evaluar las seis direcciones de traducción, nombres, números, acentos y mandarín. Comparar la versión cuantizada con la referencia y registrar errores ASR y de diarización.
- Evaluación bilingüe humana de calidad antes de un lanzamiento comercial.
- Probar dos y cuatro hablantes, regreso de un hablante, cambios de idioma y voces superpuestas.
- Probar bloqueo de pantalla, micrófono ocupado, revocación de captura, auriculares, disco lleno y recuperación tras cierre inesperado.

El informe distinguirá pruebas ejecutadas, resultados, limitaciones y comprobaciones pendientes. Si no se cumplen los objetivos, identificar el cuello de botella y revisar modelo o presupuesto de latencia antes de continuar al MVP.

## Supuestos y pendientes

- El teléfono objetivo informado es Samsung Galaxy A56 5G; faltan la variante exacta, RAM y versión de Android. El emulador permite validar flujos y almacenamiento, pero no acredita latencia, temperatura o batería en el dispositivo objetivo.
- En la inspección inicial no se encontraron Java, Gradle, adb ni Android Studio en los comandos/rutas habituales. Preparar el entorno antes de compilar; confirmar de nuevo su disponibilidad al empezar.
- Los modelos funcionan localmente tras la descarga inicial. No se ha elegido un servicio de inferencia de pago ni un backend de procesamiento.
- Esta entrega documental y de configuración no inicia la implementación de la aplicación.

## Referencias

- [Moonshine](https://github.com/moonshine-ai/moonshine)
- [cpp-annote](https://github.com/moonshine-ai/cpp-annote)
- [M2M100 418M](https://huggingface.co/facebook/m2m100_418M)
- [Optimum ONNX](https://huggingface.co/docs/optimum-onnx/en/onnxruntime/package_reference/modeling)
- [Android: compartir entrada de audio](https://developer.android.com/media/platform/sharing-audio-input)
