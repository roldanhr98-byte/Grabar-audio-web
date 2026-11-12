// Prueba del detector JavaScript de AudioVault en Chromium (Playwright).
// Uso: node tools/js-test/detector.test.js
const { chromium } = require('playwright');
const http = require('http');
const fs = require('fs');
const path = require('path');
const assert = require('assert');

const DETECTOR = fs.readFileSync(path.join(__dirname, '../../app/src/main/assets/audiovault_detector.js'), 'utf8');

function makeWav(seconds, freq, sr = 8000) {
  const n = Math.floor(seconds * sr);
  const buf = Buffer.alloc(44 + n * 2);
  buf.write('RIFF', 0); buf.writeUInt32LE(36 + n * 2, 4); buf.write('WAVE', 8);
  buf.write('fmt ', 12); buf.writeUInt32LE(16, 16); buf.writeUInt16LE(1, 20); buf.writeUInt16LE(1, 22);
  buf.writeUInt32LE(sr, 24); buf.writeUInt32LE(sr * 2, 28); buf.writeUInt16LE(2, 32); buf.writeUInt16LE(16, 34);
  buf.write('data', 36); buf.writeUInt32LE(n * 2, 40);
  for (let i = 0; i < n; i++) buf.writeInt16LE(Math.round(Math.sin(2 * Math.PI * freq * i / sr) * 12000), 44 + i * 2);
  return buf;
}
const WAV = makeWav(1.5, 440);

const PAGES = {
  '/': `<!doctype html><html><head><title>Prueba AudioVault</title></head><body>
    <iframe src="/frame"></iframe>
    <script>
      window.results = {};
      async function runAll() {
        // 1. Blob + <audio>
        const wavBytes = await (await fetch('/tone.wav')).arrayBuffer();     // también prueba fetch
        const blob = new Blob([wavBytes]);                                   // sin tipo → sniff
        const url = URL.createObjectURL(blob);
        await new Promise(r => setTimeout(r, 100));
        const a = new Audio();          // elemento desconectado del DOM
        a.muted = true;
        a.src = url;
        await a.play();
        await new Promise(r => a.addEventListener('ended', r));
        // 2. MSE con bytes arbitrarios
        if (window.MediaSource && MediaSource.isTypeSupported('audio/webm; codecs="opus"')) {
          const ms = new MediaSource();
          const v = document.createElement('audio');
          document.body.appendChild(v);
          v.src = URL.createObjectURL(ms);
          await new Promise(r => ms.addEventListener('sourceopen', r));
          const sb = ms.addSourceBuffer('audio/webm; codecs="opus"');
          try { sb.appendBuffer(new Uint8Array([0x1A,0x45,0xDF,0xA3,1,2,3,4,5,6,7,8])); } catch (e) {}
        }
        // 3. Web Audio
        const ctx = new AudioContext();
        const decoded = await ctx.decodeAudioData(wavBytes.slice(0));
        const src = ctx.createBufferSource();
        src.buffer = decoded; src.connect(ctx.destination); src.start();
        window.results.done = true;
      }
    </script></body></html>`,
  '/frame': `<!doctype html><html><body><script>
      const b = new Blob([new Uint8Array([0x49,0x44,0x33,4,0,0,0,0,0,0].concat(Array(3000).fill(7)))], {type:'audio/mpeg'});
      window.frameUrl = URL.createObjectURL(b);
    </script></body></html>`,
};

const server = http.createServer((req, res) => {
  if (req.url === '/tone.wav') { res.writeHead(200, { 'content-type': 'audio/wav', 'content-length': WAV.length }); res.end(WAV); return; }
  const body = PAGES[req.url];
  if (!body) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { 'content-type': 'text/html' }); res.end(body);
});

(async () => {
  await new Promise(r => server.listen(0, r));
  const port = server.address().port;
  const browser = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
  const page = await browser.newPage();
  const msgs = [];
  const chunks = {};
  await page.exposeFunction('__avPost', s => msgs.push(JSON.parse(s)));
  await page.exposeFunction('__avChunk', (x, seq, b64) => { (chunks[x] = chunks[x] || [])[seq] = b64; });
  await page.addInitScript(() => {
    window.AudioVaultNative = { post: s => window.__avPost(s), chunk: (x, q, b) => window.__avChunk(x, q, b) };
  });
  await page.addInitScript(DETECTOR);
  page.on('pageerror', e => console.error('pageerror', e));
  await page.goto(`http://127.0.0.1:${port}/`);
  await page.evaluate(() => runAll());
  await page.waitForTimeout(800);

  const by = t => msgs.filter(m => m.t === t);
  const kinds = by('resource').map(r => r.kind);
  console.log('tipos de mensaje:', [...new Set(msgs.map(m => m.t))].join(', '));
  console.log('recursos:', [...new Set(kinds)].join(', '));
  console.log('eventos media:', by('media').map(m => m.ev).join(','));

  // Comprobaciones
  assert(by('hello').length >= 2, 'hello en documento e iframe');
  const net = by('net').find(m => m.url.endsWith('/tone.wav'));
  assert(net && net.mime === 'audio/wav' && net.size === WAV.length, 'fetch de audio detectado con MIME y tamaño');
  assert(kinds.includes('fetch'), 'respuesta fetch retenida');
  const blobRes = by('resource').find(r => r.kind === 'blob' && r.mime === 'audio/wav' && r.top);
  assert(blobRes, 'Blob sin tipo reconocido como audio/wav por sus bytes');
  const evs = by('media').filter(m => m.srcKind === 'blob' && m.res === blobRes.res).map(m => m.ev);
  for (const e of ['play', 'playing', 'ended']) assert(evs.includes(e), 'evento ' + e + ' de <audio> desconectado');
  const endMsg = by('media').find(m => m.ev === 'ended');
  assert(Math.abs(endMsg.dur - 1.5) < 0.05, 'duración reportada ' + endMsg.dur);
  assert(kinds.includes('webaudio-encoded'), 'decodeAudioData retenido');
  assert(kinds.includes('webaudio-buffer'), 'AudioBufferSourceNode retenido');
  assert(by('webaudio').some(m => m.ev === 'start'), 'inicio Web Audio');
  const mse = by('resource').filter(r => r.kind === 'mse');
  if (mse.length) assert(mse.some(r => r.size === 12 && r.tracks[0].mime.startsWith('audio/webm')), 'segmentos MSE copiados');
  else console.log('AVISO: este Chromium no soporta audio/webm opus en MSE; prueba MSE omitida');

  async function exportRes(res, xid, fn = 'exportResource') {
    await page.evaluate(([r, x, f]) => window.__audiovault[f](r, x), [res, xid, fn]);
    for (let i = 0; i < 50; i++) {
      const end = msgs.find(m => m.t === 'xfer-end' && m.xid === xid);
      if (end) return { end, data: end.ok ? Buffer.concat((chunks[xid] || []).map(b => Buffer.from(b, 'base64'))) : null };
      await page.waitForTimeout(100);
    }
    throw new Error('timeout export ' + xid);
  }
  // Exportar Blob → bytes idénticos al WAV original
  let r = await exportRes(blobRes.res, 'x1');
  assert(r.end.ok && r.data.equals(WAV), 'exportación Blob idéntica al original');
  // Exportar respuesta fetch
  const fetchRes = by('resource').find(x => x.kind === 'fetch');
  r = await exportRes(fetchRes.res, 'x2');
  assert(r.end.ok && r.data.equals(WAV), 'exportación fetch idéntica');
  // decodeAudioData → bytes codificados originales
  r = await exportRes(by('resource').find(x => x.kind === 'webaudio-encoded').res, 'x3');
  assert(r.end.ok && r.data.equals(WAV), 'decodeAudioData: archivo original');
  // AudioBuffer → WAV válido
  r = await exportRes(by('resource').find(x => x.kind === 'webaudio-buffer').res, 'x4');
  assert(r.end.ok && r.data.slice(0, 4).toString() === 'RIFF' && r.data.length > 1000, 'AudioBuffer → WAV');
  // Recurso en iframe (relay por postMessage)
  const frameRes = by('resource').find(x => x.kind === 'blob' && !x.top);
  assert(frameRes, 'Blob del iframe detectado');
  r = await exportRes(frameRes.res, 'x5');
  assert(r.end.ok && r.data.length === 3010 && r.data.slice(0, 3).toString() === 'ID3', 'exportación desde iframe');
  // Recurso inexistente → error controlado
  r = await exportRes(blobRes.res.split(':')[0] + ':nope', 'x6');
  assert(!r.end.ok, 'error controlado');
  // exportUrl de URL blob conocida
  r = await exportRes(blobRes.url, 'x7', 'exportUrl');
  assert(r.end.ok && r.data.equals(WAV), 'exportUrl blob');

  console.log('OK: todas las comprobaciones del detector JS pasaron');
  await browser.close();
  server.close();
})().catch(e => { console.error('FALLO:', e.message); process.exit(1); });
