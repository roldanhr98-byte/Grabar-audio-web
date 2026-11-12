# Análisis previo: APIs de Android/WebView y sus limitaciones

Este documento recoge la investigación hecha **antes** de implementar, y las decisiones derivadas.

## 1. Captura de audio interno (AudioPlaybackCapture)

Fuente: documentación oficial de Android «Capture video and audio playback» y referencia de
`AudioPlaybackCaptureConfiguration`.

Requisitos para capturar:

- Android 10 (API 29) o superior.
- Permiso `RECORD_AUDIO` concedido.
- Diálogo de `MediaProjectionManager.createScreenCaptureIntent()` aprobado por el usuario. En
  Android 14+ el token es de un solo uso por sesión y el servicio en primer plano de tipo
  `mediaProjection` debe iniciarse **después** del consentimiento y **antes** de
  `getMediaProjection()`. AudioVault sigue ese orden (`CaptureService.startInternal`). En Android 14+
  se pide la configuración de pantalla completa (`createConfigForDefaultDisplay`) para que la
  elección «una sola app» no limite la captura.
- Hay que registrar `MediaProjection.Callback.onStop` y dejar de grabar si el usuario revoca el
  permiso; si no, se grabaría silencio. AudioVault lo hace y cierra la lectura en curso.

Qué audio se puede capturar:

- Solo reproductores con uso `USAGE_MEDIA`, `USAGE_GAME` o `USAGE_UNKNOWN`.
- La política efectiva del reproductor debe ser `ALLOW_CAPTURE_BY_ALL` (manifiesto + `AudioManager`
  + `AudioAttributes`; se aplica la más restrictiva).
- Apps con `targetSdk ≥ 29` permiten la captura por defecto; con `targetSdk ≤ 28` no.
- Capturadora y app de origen deben estar en el mismo perfil de usuario.
- No se pueden combinar `addMatchingUid` con `excludeUid`, ni `addMatchingUsage` con `excludeUsage`.

### ¿Se puede capturar el audio del propio WebView?

Arquitectura de Chromium en Android WebView: el renderizador corre en un proceso aislado, pero la
**salida de audio** la realiza el proceso del navegador, es decir, el proceso de la app que contiene
el WebView (AudioVault, con su UID). Chromium usa AAudio/AudioTrack con uso de medios. Por tanto,
con `addMatchingUid(Process.myUid())`, el audio de las pestañas **debería** llegar al `AudioRecord`.
Para no depender de la suposición:

- el manifiesto declara `android:allowAudioPlaybackCapture="true"` y la app llama a
  `AudioManager.setAllowedCapturePolicy(ALLOW_CAPTURE_BY_ALL)` al arrancar;
- la pantalla **Diagnóstico** reproduce tonos conocidos en un WebView del mismo proceso (vía `<audio>`
  y vía Web Audio) y comprueba nivel y frecuencia recibidos; el resultado se muestra como
  CAPTURABLE / NO capturable;
- durante cada sesión, si la página está reproduciendo y solo llegan muestras a cero durante 4 s,
  se avisa «no capturable», y una sesión que solo contiene silencio digital **no se guarda**.

**No se declara funcional la captura hasta ejecutar esa prueba en un dispositivo real.**

### Casos no capturables conocidos

| Caso | Motivo | Comportamiento de AudioVault |
|---|---|---|
| `speechSynthesis` (voz del sistema) | lo reproduce el motor TTS (otro proceso/UID, uso a menudo de accesibilidad) | se muestra «Voz del sistema: no hay archivo»; con alcance «otras apps» se intenta, y si llega silencio se avisa |
| Medios con DRM (EME/Widevine) | contenido protegido | no se descarga ni descifra; marcado como protegido |
| Apps con `allowAudioPlaybackCapture=false` o política SYSTEM/NONE | política del origen | silencio → aviso «no capturable» |
| Llamadas, notificaciones, alarmas | usos no capturables | no se capturan |
| Android 8–9 | API inexistente | solo modo micrófono |

## 2. WebView y detección

- `WebViewCompat.addDocumentStartJavaScript` (androidx.webkit, función `DOCUMENT_START_SCRIPT`)
  inyecta el detector **antes** que cualquier script de la página y en todos los iframes. Sin esa
  función (WebView muy antiguo) se inyecta en `onPageStarted`/`onPageFinished`, más tarde: se
  pueden perder los Blob creados antes.
- `shouldInterceptRequest` permite **observar** URLs (no respuestas) de cualquier petición; se usa solo
  para listar medios por extensión, devolviendo siempre `null` (no se modifica tráfico).
- `addJavascriptInterface` está disponible en todos los frames: el puente solo recibe datos y solo
  acepta trozos de transferencias que la app pidió.

### URLs blob: y Media Source

Una URL `blob:` es una referencia en memoria del documento. No se puede descargar desde Android.
Formas legítimas de recuperar el contenido (implementadas):

1. Envolver `URL.createObjectURL`: si el objeto es un `Blob` de audio (o sin tipo pero con bytes de
   audio), se conserva la referencia; cuando un `<audio>` reproduce esa URL se sabe exactamente qué
   archivo es y se puede exportar el **original** (bytes idénticos).
2. Si es un `MediaSource`, se envuelven `addSourceBuffer`/`appendBuffer` para copiar los segmentos
   de las pistas de audio; la concatenación (segmento de inicialización + segmentos) reproduce el
   flujo. Con `SourceBuffer.remove` (búsquedas) se marca como reconstrucción imperfecta.
3. `fetch`/XHR con `Content-Type: audio/*`: se clona la respuesta (`Response.clone()`) y se conserva;
   así se obtiene el archivo que sirvió el servidor aunque la URL sea de un solo uso.
4. Web Audio: `decodeAudioData` recibe el archivo codificado → se copia antes de decodificar;
   `AudioBufferSourceNode.start` → se puede exportar el PCM como WAV. El audio sintetizado en tiempo
   real (osciladores, AudioWorklet) no tiene archivo: solo captura.

Límites: memoria retenida por documento (160 MB), por pista MSE (120 MB) y por respuesta (80 MB).

## 3. Descargas con sesión

`HttpFetcher` reenvía las cookies del `CookieManager` del WebView (o del perfil privado), el
User-Agent y el Referer de la página, siguiendo redirecciones manualmente. No hay proxy, ni
certificados propios, ni APIs privadas. Las URLs firmadas de un solo uso pueden fallar; en ese caso se
intenta la copia retenida por la página.

## 4. Identificación y almacenamiento

- Sitio por dominio (`claude.ai`, `chatgpt.com`, `elevenlabs.io`, …) → carpeta y prefijo.
- Biblioteca en `Android/data/com.audiovault.browser/files/AudioVault/` (o interno privado) y copia
  opcional en `Música/AudioVault` vía MediaStore. Exportación por SAF y ZIP.
- Caché de capturas en `files/capture_cache` (no la vacía el sistema, a diferencia de `cacheDir`).
