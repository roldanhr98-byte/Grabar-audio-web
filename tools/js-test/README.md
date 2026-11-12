# Prueba del detector JavaScript

Ejecuta `app/src/main/assets/audiovault_detector.js` dentro de Chromium (el mismo motor que Android WebView)
con un puente `AudioVaultNative` simulado y comprueba:

- eventos de `<audio>` desconectado del DOM (play/playing/pause/ended) con duración;
- Blob sin tipo reconocido por sus bytes y exportado byte a byte idéntico;
- respuesta `fetch` con `Content-Type: audio/wav` detectada (MIME, tamaño) y retenida;
- segmentos de Media Source Extensions copiados;
- `decodeAudioData` (archivo original) y `AudioBufferSourceNode` (PCM → WAV);
- recursos dentro de un `<iframe>` exportados mediante `postMessage`;
- errores controlados para recursos inexistentes.

```bash
npm install --no-save --prefix tools/js-test playwright
npx --prefix tools/js-test playwright install chromium
NODE_PATH=tools/js-test/node_modules node tools/js-test/detector.test.js
```
