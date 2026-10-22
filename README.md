# VozTranslate

Prototipo Android nativo para transcripción y traducción locales. Antes de iniciar se descargan y verifican el modelo Moonshine Tiny Streaming del idioma de origen y el modelo M2M100 418M INT8 mediante trabajos persistentes de WorkManager. La captura PCM mono a 16 kHz se guarda progresivamente como WAV privado, se envía a Moonshine y sus segmentos/revisiones se guardan en Room. M2M100 traduce desde el mismo flujo de sesión: los parciales se coalescen con un límite de una inferencia cada dos segundos por segmento; los finales pendientes quedan almacenados en Room y un worker serial los procesa por lotes sin bloquear la captura. La pantalla activa muestra el original, la traducción disponible y el backlog final.

La dependencia Android está fijada en Moonshine Voice 0.1.5. La aplicación alimenta el flujo PCM de la única captura `AudioRecord` existente; Moonshine y M2M100 no abren micrófonos. M2M100 usa archivos privados ONNX INT8, SHA-256 pinneados y un motor JNI/C++ con SentencePiece nativo para ARM64. Como el runtime mínimo incluido por Moonshine rechaza grafos `.onnx`, el APK empaqueta un único `libonnxruntime.so` completo de ONNX Runtime Android 1.23.2; Gradle verifica su hash para cada variante. El APK se volvió a compilar, pero todavía no se ha instalado ni probado en el teléfono después del cambio. El smoke host no valida calidad de traducción. El dispositivo objetivo es Samsung Galaxy A56 5G SM-A566E con Android 16; falta confirmar la RAM del SKU para planificar la validación de rendimiento. La diarización sigue pendiente. Consulta `docs/MODEL_AND_RUNTIME_NOTES.md` para procedencia, tamaños, hashes, licencias y límites de validación.

El WAV es el formato interno de esta etapa. La exportación de audio M4A, texto bilingüe TXT y datos JSON pertenece a una fase posterior.

## Requisitos de desarrollo

- JDK 17 o posterior compatible con el wrapper.
- Android SDK Platform 37, Android NDK `29.0.14206865` y CMake `3.31.6`.
- Gradle se ejecuta con el wrapper incluido: `gradlew.bat assembleDebug` en Windows o `./gradlew assembleDebug` en macOS/Linux.

La versión del wrapper y su SHA-256 están fijados en `gradle/wrapper/gradle-wrapper.properties`. Las versiones de plugins y bibliotecas directas están centralizadas en `gradle/libs.versions.toml`. El APK de evaluación se configura solo para `arm64-v8a` en esta fase.

Consulta `PLAN.md` para alcance y criterios. Consulta `docs/MODEL_AND_RUNTIME_NOTES.md` para versiones, almacenamiento local, atribución y decisiones pendientes de runtime y verificación de modelos.
