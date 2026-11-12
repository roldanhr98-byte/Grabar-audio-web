# AudioVault Browser

Navegador Android nativo (Kotlin + Jetpack Compose + WebView Chromium) que **detecta los audios que se
reproducen en cualquier página**, **descarga el archivo original cuando existe** y, cuando no existe,
**graba la reproducción** con las APIs oficiales de Android (MediaProjection + AudioPlaybackCapture +
AudioRecord) en archivos WAV independientes, uno por lectura.

Pensado para conservar lecturas en voz alta de Claude, ChatGPT, ElevenLabs y cualquier reproductor web
que no ofrece botón de descarga. **Todo es local**: no hay servidor, no se envía nada fuera del teléfono.

> **Estado de verificación (honesto):** el código compila, las 33 pruebas unitarias y la prueba del
> detector JavaScript en Chromium pasan. **Las pruebas en un dispositivo Android real (Etapa 7) no las
> he podido ejecutar** porque este entorno de desarrollo no tiene dispositivo ni emulador con
> aceleración. Por eso la app incluye una **autoprueba de captura** (Ajustes → Diagnóstico) y el
> documento [docs/PRUEBAS.md](docs/PRUEBAS.md) con el protocolo a seguir. Ninguna captura se declara
> funcional sin haberla probado: consulta la tabla de estado al final.

## Descarga

Cada `push` compila la APK en GitHub Actions (workflow **Compilar APK**):

- **Releases** del repositorio → `AudioVault-Browser-1.0.N.apk` (release firmada, recomendada).
- **Actions** → ejecución → artefacto `AudioVault-Browser-1.0.N` (release + debug + SHA256SUMS).

Requisitos: Android 8.0+ (la captura de audio interno necesita **Android 10+**).

## Funciones

| Área | Implementado |
|---|---|
| Navegador | Barra de direcciones con búsqueda en Google, atrás/adelante, recargar/detener, página de inicio nativa, pestañas múltiples, pestañas privadas (perfil WebView separado si el WebView lo admite), historial, marcadores, descargas (DownloadManager), pantalla completa de vídeo, permisos web (micrófono, cámara, ubicación, DRM) con confirmación, selector de archivos, ventanas emergentes (OAuth) como pestañas, cookies persistentes, cookies de terceros configurables, borrar cookies por sitio o todas, modo escritorio. |
| Detector | Inyección JS al inicio de cada documento e iframe (`addDocumentStartJavaScript`) + observación de red (`shouldInterceptRequest`, sin modificar tráfico). Detecta MP3, WAV, M4A, AAC, OGG, OPUS, WebM, FLAC, HLS (.m3u8), DASH (.mpd), URLs blob:, Media Source Extensions, Web Audio API, `HTMLAudioElement`, `HTMLVideoElement`, respuestas `fetch`/XHR con MIME de audio, `speechSynthesis` y DRM (EME). Registra inicio, pausa, espera de búfer y fin, duración, MIME, tamaño y URL de origen cuando son accesibles. |
| Descarga directa | Descarga con las cookies, User-Agent y Referer de la sesión del usuario; conserva el formato original; valida los primeros bytes (rechaza HTML, JSON, vacíos); evita duplicados por SHA-256; registra página, fecha, duración y tamaño. HLS/DASH sin cifrar se ensamblan segmento a segmento; los cifrados/DRM se rechazan con explicación. |
| Extracción desde la página | Para URLs `blob:`, MSE y Web Audio el detector conserva los datos que la página ya tiene en memoria y los transfiere al almacenamiento local por trozos (Blob original, respuesta fetch original, segmentos MSE concatenados, buffer codificado de `decodeAudioData`, o PCM del `AudioBuffer` → WAV). |
| Captura interna | MediaProjection + `AudioPlaybackCaptureConfiguration` + `AudioRecord` PCM 16-bit, 16–48 kHz, mono/estéreo, servicio en primer plano con notificación persistente. Alcance «solo el navegador» (UID propio) u «otras apps que lo permitan» (usos MEDIA/GAME/UNKNOWN). Aviso automático de «no capturable» cuando solo llega silencio digital. |
| Micrófono | Modo alternativo independiente, desactivado por defecto; nunca se mezcla con la captura interna. |
| Grabación por sesiones | Máquina de estados que crea **un archivo por reproducción**: inicio por evento de la página (o por nivel), pausa/reanudación en el mismo archivo (ventana configurable), nueva lectura → nuevo archivo, fin por `ended` con cola de seguridad, silencio configurable que no corta pausas naturales mientras la página sigue reproduciendo, pre-roll para no perder el arranque, recorte de silencio final, inicio/fin manual. |
| Caché y recuperación | Cada captura se escribe al instante en `files/capture_cache` (no la borra el sistema) con cabecera WAV actualizada cada 2 s. Si la app muere, al reabrirla se repara la cabecera y la sesión queda recuperable (automática o manual). Espacio usado, vaciado de caché sin tocar la biblioteca. |
| Mi Biblioteca | Carpetas por sitio (`AudioVault/Claude/lectura_001.wav`, `ChatGPT/…`, `ElevenLabs/generacion_001.mp3`), nombre «CLAUDE - Lectura de texto - 12/11/2026», búsqueda, filtros (original/capturado/incompleto/sitio), reproductor Media3 (velocidad, ±10 s), forma de onda con búsqueda táctil, renombrar, compartir, exportar (SAF), ZIP múltiple con `metadata.json`, información técnica, eliminar (solo por acción del usuario). |
| Ajustes | Descarga automática, grabación automática, captura interna y alcance, micrófono, calidad, formato de salida (WAV o M4A/AAC), detección de silencio, ubicación de almacenamiento, copia pública en Música/AudioVault, permisos, cookies, caché web. |

## Arquitectura (resumen)

```
UI Compose (Inicio, Navegador, Panel de audio, Mi Biblioteca, Ajustes, Caché, Diagnóstico)
   │
TabManager ── WebView (una por pestaña, viven fuera de la UI para que el audio siga sonando)
   │   ├─ audiovault_detector.js (document-start, todos los frames)  ──► JsBridge
   │   └─ shouldInterceptRequest / DownloadListener (solo observación)
   ▼
MediaDetector ─► DetectionRepository (lista en memoria para el panel)
   │   ├─ HttpFetcher / StreamAssembler (HLS, DASH)      → archivo original
   │   ├─ transferencia JS→nativo (Blob, MSE, Web Audio) → archivo original
   │   └─ señales play/pause/ended ─► CaptureController
   ▼
CaptureController (hilo único) ─► SessionStateMachine (pura, con pruebas)
   │   ├─ CaptureService (FGS) ─► CaptureEngine (AudioRecord + AudioPlaybackCapture / MIC)
   │   └─ WavWriter (caché, recuperable) ─► AacEncoder (opcional)
   ▼
LibraryRepository (archivos + Room) ◄─ CacheManager (recuperación)
PlayerController (Media3) · WaveformExtractor · ExportManager (compartir/SAF/ZIP)
```

Detalle completo en [docs/ARQUITECTURA.md](docs/ARQUITECTURA.md).

## Limitaciones reales (investigadas)

Resumen; el análisis completo está en [docs/LIMITACIONES.md](docs/LIMITACIONES.md).

1. **AudioPlaybackCapture** solo existe desde Android 10, exige `RECORD_AUDIO` y la autorización de
   MediaProjection del usuario en cada activación, y solo entrega audio con uso `MEDIA`, `GAME` o
   `UNKNOWN` de apps cuya política lo permita. El audio del propio WebView se reproduce desde el
   proceso de AudioVault (UID propio, `targetSdk 35`, `allowAudioPlaybackCapture="true"` y
   `setAllowedCapturePolicy(ALLOW_CAPTURE_BY_ALL)`), por lo que **debería** ser capturable con
   `addMatchingUid(miUid)`; esto **se debe confirmar en cada dispositivo** con la autoprueba.
2. **`speechSynthesis`** (voz del sistema) no la reproduce la página sino el motor TTS de Android:
   no hay archivo y con alcance «solo el navegador» no es capturable. Se informa como tal.
3. **DRM/EME, HLS cifrado (AES/SAMPLE-AES) y DASH con ContentProtection**: no se descargan ni se
   descifran. Las apps/medios con política de captura restrictiva entregan silencio: la app lo detecta
   y muestra «no capturable» sin guardar archivos vacíos.
4. Una **URL blob:** no es descargable desde fuera de la página; AudioVault solo puede recuperar el
   contenido si el detector vio el Blob/MediaSource al crearse (inyección al inicio del documento).
   Si la página liberó la memoria, se indica.
5. **MSE**: la concatenación de segmentos reproduce el flujo tal como llegó; si la página hizo
   búsquedas (`SourceBuffer.remove`) se marca como posible reconstrucción imperfecta.
6. **Inicio de sesión con Google** dentro de WebViews está bloqueado por Google; en Claude/ChatGPT/
   ElevenLabs usa el acceso por correo electrónico. AudioVault no falsea el User-Agent para evitarlo.
7. Si dos pestañas suenan a la vez, la captura interna recibe la mezcla (es un único flujo de salida).

## Cómo verificar la captura en Claude / ChatGPT

1. Ajustes → **Diagnóstico** → «Activar captura interna» → «Ejecutar prueba». La prueba reproduce un
   tono de 1000 Hz (elemento `<audio>` con Blob) y uno de 660 Hz (Web Audio) en un WebView de la app y
   comprueba nivel y frecuencia recibidos por `AudioRecord`. Resultado ✔/✘ por cada vía.
2. En Claude o ChatGPT: activa la captura, pulsa «Leer en voz alta», abre el **Panel de audio** y
   comprueba que aparece el medio detectado y el estado «Grabando». Al terminar, verifica en
   **Mi Biblioteca → Información técnica** la duración según la página frente a la capturada (se marca
   «Incompleto» si difiere > 15 %) y escucha el archivo.

## Compilar localmente

```bash
# JDK 17 y Android SDK (platform 35, build-tools 35.0.0)
./gradlew testDebugUnitTest assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

La release se firma con el keystore incluido (`app/audiovault-debug.keystore`, solo para que todas
las compilaciones compartan firma y se puedan actualizar). Para firmar con un keystore propio define
los secretos `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` y
`ANDROID_KEY_PASSWORD` en GitHub.

## Estado de las pruebas

| Prueba | Estado |
|---|---|
| Compilación debug + release (R8) | ✔ Ejecutada (local y CI) |
| 33 pruebas unitarias JVM (sesiones, WAV/recuperación, formatos, HLS, DASH, nombres) | ✔ Ejecutadas |
| Detector JS en Chromium (Blob, fetch, MSE, Web Audio, iframes, exportación byte a byte) | ✔ Ejecutada |
| Casos 1–13 en dispositivo Android real | ✘ **No ejecutadas** (sin dispositivo en este entorno) – ver [docs/PRUEBAS.md](docs/PRUEBAS.md) |

## Privacidad

- Sin servidores ni analítica; la biblioteca permanece en el dispositivo.
- Notificación persistente y banner rojo mientras se graba; nunca se graba sin autorización de Android.
- No se interceptan conexiones HTTPS, no se usan certificados, APIs privadas ni se vulnera DRM.
