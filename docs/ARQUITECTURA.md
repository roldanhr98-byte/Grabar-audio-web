# Arquitectura técnica

Paquete `com.audiovault.browser`. Sin servidores, sin frameworks de inyección: `core/AppContainer`
crea y conecta los componentes.

| Paquete | Clases | Responsabilidad |
|---|---|---|
| `browser` | `TabManager`, `BrowserTab`, `JsBridge`, `GeneralDownloads` | WebViews por pestaña (fuera de la UI), clientes WebView/Chrome, perfil privado, cookies, descargas no-audio con DownloadManager |
| `detector` | `MediaDetector`, `DetectionRepository`, `DetectedMedia`, `MediaUrlClassifier` | Recibe mensajes del JS y de la red, mantiene la lista del panel, decide y ejecuta la obtención, envía señales al grabador |
| `assets/audiovault_detector.js` | — | Ganchos web estándar: elementos media, Blob, MSE, fetch/XHR, Web Audio, speechSynthesis, EME; exportación por trozos |
| `download` | `HttpFetcher`, `HlsParser`, `DashParser`, `StreamAssembler` | Descargas con sesión del usuario; ensamblado HLS/DASH sin cifrar |
| `capture` | `CaptureService`, `CaptureEngine`, `CaptureController`, `SessionStateMachine`, `WavWriter`, `LevelAnalyzer`, `PreRollBuffer`, `AacEncoder` | Captura interna / micrófono, segmentación por lecturas, escritura WAV recuperable, conversión AAC opcional |
| `cache` | `CacheManager` | Recuperación tras cierre, espacio usado, vaciado |
| `library` | `LibraryRepository`, `SiteIdentifier`, `AudioFormatSniffer`, `MediaInfoReader` | Validación por bytes, deduplicado SHA-256, carpetas/nombres, metadatos Room, copia pública |
| `player` | `PlayerController`, `WaveformExtractor` | Media3 ExoPlayer + MediaSession; forma de onda (WAV directo o MediaCodec) |
| `export` | `ExportManager` | Compartir (FileProvider), exportar (SAF), ZIP con metadata.json |
| `data` | `AppDatabase` (Room), `SettingsRepository` (DataStore) | Persistencia |
| `ui` | Compose: `AppRoot`, `HomeScreen`, `BrowserScreen`, `AudioPanelSheet`, `LibraryScreen`, `SettingsScreen`, `CacheScreen`, `DiagnosticsScreen`, … | Interfaz nativa oscura |

## Flujo de una lectura de Claude

1. El usuario pulsa «Leer en voz alta». La página crea un `Audio`/`MediaSource`/Blob: el detector
   (inyectado al inicio del documento) lo registra y envía `resource` y `media/play`.
2. `MediaDetector` crea la entrada en el panel y reenvía `PLAY` a `CaptureController`.
3. Si la captura está activa, `SessionStateMachine` emite `Start`: se crea
   `capture_cache/<uuid>.wav.part`, se escribe el pre-roll y se registra la sesión en Room.
4. Cada bloque PCM de 100 ms se analiza (nivel, frecuencia) y se escribe; la cabecera WAV se
   actualiza cada 2 s.
5. `pause` → tras la cola se pasa a pausa (no se escribe). `play` del mismo medio → se continúa en el
   mismo archivo. Otro medio → se cierra el archivo y empieza otro.
6. `ended` (o silencio largo sin reproducción) → tras la cola, `Finalize`: recorte de silencio
   final, validación (no silencio digital), conversión opcional a M4A, importación a
   `AudioVault/Claude/lectura_00N.wav` con estado Completo/Incompleto y notas (duración esperada vs.
   capturada).
7. En paralelo, si la descarga automática está activa y existe archivo (Blob/fetch/MSE), se exporta
   el **original** y se guarda como otro elemento con origen «Original».

## Modelo de datos (Room)

- `audio_items`: nombre, ruta, carpeta, sitio, página, título, URL del medio, fecha, duración,
  tamaño, formato, MIME, origen (`AudioOrigin`), estado (`CaptureStatus`), frecuencia, canales,
  tasa de bits, SHA-256, notas.
- `capture_sessions`: sesión en caché (RECORDING/PAUSED/INTERRUPTED…), bytes escritos, formato.
- `history`, `bookmarks`, `downloads`.

## Hilos

- JS → `JsBridge` (hilo JavaBridge) → `MediaDetector` (rápido, sin bloquear).
- Captura: hilo `AudioVault-Capture` (lectura de AudioRecord) → hilo único `AudioVault-Session`
  (máquina de estados + escritor) → corrutinas IO para Room y la importación.
- UI: Compose en el hilo principal; WebView y ExoPlayer en el hilo principal.
