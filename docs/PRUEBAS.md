# Pruebas

## Ejecutadas (automáticas)

| Conjunto | Dónde | Resultado |
|---|---|---|
| `SessionStateMachineTest` (13): lectura completa, pausa/reanudación en el mismo archivo, dos lecturas → dos archivos, silencio natural sin corte, fin por silencio, ventana de pausa, descarte de fragmentos cortos, sesión manual, aviso de silencio digital, disparo por nivel, captura detenida, audio progresivo (waiting) | JVM (`./gradlew testDebugUnitTest`) | ✔ |
| `WavWriterTest` (6): cabecera, **recuperación tras cierre inesperado**, recorte, anexado, pre-roll, analizador de nivel/frecuencia | JVM | ✔ |
| `LibraryLogicTest` (5): sitios, nombres «CLAUDE - Lectura de texto - fecha», numeración `lectura_00N`, rechazo de HTML/JSON, reconocimiento de MP3/AAC/WAV/OGG/OPUS/M4A/WebM | JVM | ✔ |
| `StreamParsersTest` (9): HLS maestra/medios/cifrado/byte-range, DASH plantilla+timeline / número / DRM, clasificador de URLs | JVM | ✔ |
| `tools/js-test/detector.test.js`: detector JS en Chromium real (eventos, Blob, fetch, MSE, Web Audio, iframe, exportación byte a byte) | Chromium headless (Playwright) | ✔ |
| Compilación debug y release (R8) | Local + GitHub Actions | ✔ |

## Pendientes en dispositivo Android real (NO ejecutadas)

No se dispuso de dispositivo ni emulador acelerado en el entorno de desarrollo. **Ninguno de estos
casos se ha marcado como superado.** Rellena la columna «Resultado» al probar:

| # | Caso | Cómo comprobarlo | Esperado | Resultado |
|---|---|---|---|---|
| 0 | Autoprueba de captura | Ajustes → Diagnóstico → Activar → Ejecutar prueba | ✔ en `<audio>` y Web Audio | pendiente |
| 1 | MP3 normal | Abrir una URL .mp3 pública | Panel: «Archivo de audio», guardado ORIGINAL .mp3 idéntico | pendiente |
| 2 | WAV | URL .wav | ORIGINAL .wav | pendiente |
| 3 | Audio HTML5 | Página con `<audio controls>` | Detectado con duración; original + captura | pendiente |
| 4 | Claude «Leer en voz alta» | ver README | `Claude/lectura_001.wav` con la voz; original si existe | pendiente |
| 5 | ChatGPT «Leer en voz alta» | ídem | `ChatGPT/lectura_001.wav` | pendiente |
| 6 | ElevenLabs | Generar voz en Speech Synthesis | `ElevenLabs/generacion_001.*` (original mp3) | pendiente |
| 7 | Audio progresivo | ChatGPT/ElevenLabs con respuesta larga | Un solo archivo sin cortes durante «waiting» | pendiente |
| 8 | Pausar y reanudar | Pausa < ventana y continuar | Mismo archivo, sin silencio de la pausa | pendiente |
| 9 | Cambiar de pestaña | Lectura sonando, abrir otra pestaña | La grabación continúa | pendiente |
| 10 | Cerrar el navegador grabando | Deslizar la app en recientes | Lectura cerrada y guardada (alcance navegador) | pendiente |
| 11 | Recuperación de caché | Forzar detención (Ajustes Android) durante una grabación y reabrir | Sesión «Recuperado» en biblioteca o en Caché | pendiente |
| 12 | Lecturas separadas | Leer 3 respuestas seguidas | `lectura_001..003` | pendiente |
| 13 | Exportación | ZIP de varios + abrir en otro reproductor | Se reproducen; `metadata.json` correcto | pendiente |

Clasificación que debe verse en la app: **ORIGINAL** (descargado/extraído), **CAPTURADO**,
**INCOMPLETO** (badge ámbar) y **NO CAPTURABLE / NO DESCARGABLE** (DRM, TTS del sistema, silencio digital).
