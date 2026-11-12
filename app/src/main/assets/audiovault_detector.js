/*
 * AudioVault – detector de audio inyectado en cada documento (document-start).
 *
 * Qué hace (solo con APIs estándar de la web, sin romper DRM ni cifrado):
 *  - Observa elementos HTMLAudioElement / HTMLVideoElement (play, pause, ended…).
 *  - Recuerda los Blob pasados a URL.createObjectURL para poder recuperar
 *    el archivo original cuando un <audio> reproduce una URL blob:.
 *  - Copia los segmentos añadidos a SourceBuffer (Media Source Extensions)
 *    de pistas de audio para reconstruir el flujo cuando no hay archivo.
 *  - Detecta respuestas fetch/XHR con Content-Type de audio y conserva una copia.
 *  - Detecta Web Audio API (decodeAudioData, AudioBufferSourceNode, estado del contexto).
 *  - Detecta speechSynthesis (voz del sistema, normalmente no capturable).
 *  - Marca como protegido cualquier medio con EME/DRM y NO exporta su contenido.
 *
 * Comunicación: window.AudioVaultNative.post(json) y .chunk(xid, seq, base64).
 */
(function () {
  'use strict';
  if (window.__audiovault) return;

  var NATIVE_NAME = 'AudioVaultNative';
  var MAX_RETAIN_BYTES = 160 * 1024 * 1024;     // memoria máxima retenida por documento
  var MAX_TRACK_BYTES = 120 * 1024 * 1024;      // por pista MSE
  var MAX_FETCH_BYTES = 80 * 1024 * 1024;       // por respuesta de red
  var MAX_BLOBS = 40;
  var CHUNK = 192 * 1024;                        // bytes por trozo transferido a Android

  var frameId = Math.random().toString(36).slice(2, 8);
  var isTop = (function () { try { return window.top === window; } catch (e) { return false; } })();
  var counter = 0;
  var resources = {};      // resId -> resource
  var resOrder = [];
  var blobUrlToRes = {};   // 'blob:...' -> resId
  var retained = 0;
  var protectedPage = false;

  function nativeObj() { try { return window[NATIVE_NAME]; } catch (e) { return null; } }
  function post(o) {
    var n = nativeObj();
    if (!n) return;
    try {
      o.frame = frameId;
      o.top = isTop;
      o.page = location.href;
      o.title = isTop ? document.title : undefined;
      n.post(JSON.stringify(o));
    } catch (e) { /* nunca romper la página */ }
  }
  function newId(prefix) { counter += 1; return frameId + ':' + prefix + counter; }

  function lower(s) { return (s || '').toString().toLowerCase(); }
  function isAudioMime(m) { m = lower(m); return m.indexOf('audio/') === 0; }
  function isVideoMime(m) { m = lower(m); return m.indexOf('video/') === 0; }
  function isPlaylistMime(m) {
    m = lower(m);
    return m.indexOf('mpegurl') >= 0 || m.indexOf('dash+xml') >= 0;
  }
  function absUrl(u) { try { return new URL(u, location.href).href; } catch (e) { return String(u); } }

  /* ----------------------------------------------------------- sniff de bytes */
  function sniffBytes(u8) {
    function a(off, s) { for (var i = 0; i < s.length; i++) if (u8[off + i] !== s.charCodeAt(i)) return false; return true; }
    if (!u8 || u8.length < 4) return null;
    if (a(0, 'RIFF') && a(8, 'WAVE')) return 'audio/wav';
    if (a(0, 'ID3')) return 'audio/mpeg';
    if (a(0, 'OggS')) return 'audio/ogg';
    if (a(0, 'fLaC')) return 'audio/flac';
    if (a(4, 'ftyp')) { return (a(8, 'M4A') || a(8, 'M4B')) ? 'audio/mp4' : 'video/mp4'; }
    if (u8[0] === 0x1A && u8[1] === 0x45 && u8[2] === 0xDF && u8[3] === 0xA3) return 'video/webm';
    if (u8[0] === 0xFF && (u8[1] & 0xF6) === 0xF0) return 'audio/aac';
    if (u8[0] === 0xFF && (u8[1] & 0xE0) === 0xE0 && (u8[1] & 0x06) !== 0) return 'audio/mpeg';
    return null;
  }

  /* ------------------------------------------------------------- recursos */
  function addResource(r) {
    resources[r.id] = r;
    resOrder.push(r.id);
    trimResources();
    return r;
  }
  function trimResources() {
    var blobs = 0;
    for (var i = resOrder.length - 1; i >= 0; i--) {
      var r = resources[resOrder[i]];
      if (r && r.blob) blobs++;
    }
    while ((retained > MAX_RETAIN_BYTES || blobs > MAX_BLOBS) && resOrder.length > 1) {
      var id = resOrder.shift();
      var old = resources[id];
      if (!old) continue;
      if (old.blob) { blobs--; retained -= old.blob.size || 0; }
      if (old.tracks) old.tracks.forEach(function (t) { retained -= t.size; t.chunks = []; t.dropped = true; });
      old.evicted = true;
      post({ t: 'resource', res: id, kind: old.kind, evicted: true });
      delete resources[id];
    }
  }
  function describe(r) {
    var o = {
      t: 'resource', res: r.id, kind: r.kind, mime: r.mime, size: r.size, url: r.url,
      complete: !!r.complete, prot: !!r.protected, dur: r.duration
    };
    if (r.tracks) {
      o.tracks = r.tracks.map(function (t) { return { mime: t.mime, size: t.size, dropped: !!t.dropped }; });
      o.nonLinear = !!r.nonLinear;
    }
    return o;
  }
  function report(r) { post(describe(r)); }

  /* ----------------------------------------------------- URL.createObjectURL */
  var origCreate = URL.createObjectURL;
  var origRevoke = URL.revokeObjectURL;
  var MS = window.MediaSource || window.WebKitMediaSource;
  function msResource(ms) {
    if (ms.__avRes) return ms.__avRes;
    var r = addResource({ id: newId('mse'), kind: 'mse', mime: '', tracks: [], size: 0, complete: false });
    try { Object.defineProperty(ms, '__avRes', { value: r, enumerable: false }); } catch (e) { ms.__avRes = r; }
    try {
      ms.addEventListener('sourceended', function () { r.complete = true; report(r); });
    } catch (e) {}
    return r;
  }
  if (origCreate) {
    URL.createObjectURL = function (obj) {
      var url = origCreate.apply(this, arguments);
      try {
        if (MS && obj instanceof MS) {
          var r = msResource(obj);
          r.url = url;
          blobUrlToRes[url] = r.id;
          report(r);
        } else if (obj instanceof Blob) {
          var type = lower(obj.type);
          var keep = function (mime) {
            var br = addResource({ id: newId('blob'), kind: 'blob', mime: mime, blob: obj, size: obj.size, url: url, complete: true });
            retained += obj.size;
            blobUrlToRes[url] = br.id;
            trimResources();
            report(br);
          };
          if (isAudioMime(type) || isVideoMime(type)) keep(type);
          else if ((type === '' || type === 'application/octet-stream') && obj.size > 1024 && obj.slice) {
            obj.slice(0, 16).arrayBuffer().then(function (ab) {
              var m = sniffBytes(new Uint8Array(ab));
              if (m) keep(m);
            }).catch(function () {});
          }
        }
      } catch (e) {}
      return url;
    };
  }
  if (origRevoke) {
    URL.revokeObjectURL = function (url) {
      // Conservamos el Blob (ya lo referenciamos) para poder exportarlo después.
      return origRevoke.apply(this, arguments);
    };
  }

  /* --------------------------------------------------- Media Source Extensions */
  if (MS && MS.prototype && window.SourceBuffer) {
    var origAdd = MS.prototype.addSourceBuffer;
    MS.prototype.addSourceBuffer = function (mime) {
      var sb = origAdd.apply(this, arguments);
      try {
        var r = msResource(this);
        var track = { mime: lower(mime), chunks: [], size: 0, dropped: false };
        r.tracks.push(track);
        var audioTrack = r.tracks.filter(function (t) { return isAudioMime(t.mime); })[0];
        r.mime = audioTrack ? audioTrack.mime : r.tracks[0].mime;
        sb.__avTrack = track;
        sb.__avRes = r;
        report(r);
      } catch (e) {}
      return sb;
    };
    var origAppend = SourceBuffer.prototype.appendBuffer;
    SourceBuffer.prototype.appendBuffer = function (data) {
      try {
        var t = this.__avTrack, r = this.__avRes;
        if (t && r && !r.protected && !t.dropped) {
          var len = data.byteLength;
          if (t.size + len > MAX_TRACK_BYTES || retained + len > MAX_RETAIN_BYTES) {
            t.dropped = true;
          } else {
            var copy = (data instanceof ArrayBuffer) ? data.slice(0)
              : data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength);
            t.chunks.push(copy);
            t.size += len;
            r.size += len;
            retained += len;
            if (t.chunks.length === 1 || t.chunks.length % 20 === 0) report(r);
          }
        }
      } catch (e) {}
      return origAppend.apply(this, arguments);
    };
    var origRemove = SourceBuffer.prototype.remove;
    if (origRemove) SourceBuffer.prototype.remove = function () {
      try { if (this.__avRes) this.__avRes.nonLinear = true; } catch (e) {}
      return origRemove.apply(this, arguments);
    };
    var origEos = MS.prototype.endOfStream;
    MS.prototype.endOfStream = function () {
      try { var r = msResource(this); r.complete = true; report(r); } catch (e) {}
      return origEos.apply(this, arguments);
    };
  }

  /* ------------------------------------------------------------- DRM / EME */
  function markProtected(el) {
    protectedPage = true;
    try {
      if (el) {
        el.__avProtected = true;
        var rid = blobUrlToRes[el.currentSrc || el.src];
        if (rid && resources[rid]) {
          var r = resources[rid];
          r.protected = true;
          if (r.tracks) r.tracks.forEach(function (t) { retained -= t.size; t.chunks = []; t.dropped = true; });
          report(r);
        }
      }
    } catch (e) {}
    post({ t: 'eme' });
  }
  var HME = window.HTMLMediaElement;
  if (HME && HME.prototype.setMediaKeys) {
    var origSMK = HME.prototype.setMediaKeys;
    HME.prototype.setMediaKeys = function (keys) {
      if (keys) markProtected(this);
      return origSMK.apply(this, arguments);
    };
  }

  /* -------------------------------------------------------- elementos media */
  var EVENTS = ['play', 'playing', 'pause', 'ended', 'waiting', 'emptied', 'error', 'loadedmetadata', 'durationchange', 'encrypted'];
  var elCounter = 0;
  function srcInfo(el) {
    var src = '';
    try { src = el.currentSrc || el.src || ''; } catch (e) {}
    if (!src) {
      try {
        var s = el.querySelector && el.querySelector('source[src]');
        if (s) src = s.src;
      } catch (e) {}
    }
    var kind = 'http', res = null;
    if (el.srcObject) kind = 'stream';
    else if (src.indexOf('blob:') === 0) {
      res = blobUrlToRes[src] || null;
      kind = res ? (resources[res] ? resources[res].kind : 'blob') : 'blob-unknown';
    } else if (src.indexOf('data:') === 0) kind = 'data';
    else if (!src) kind = 'none';
    return { src: src, kind: kind, res: res };
  }
  function handleMediaEvent(ev) {
    if (ev.__avSeen) return;
    try { Object.defineProperty(ev, '__avSeen', { value: true }); } catch (e) { ev.__avSeen = true; }
    var el = ev.target;
    if (!el || !(HME && el instanceof HME)) return;
    attach(el);
    if (ev.type === 'encrypted') { markProtected(el); return; }
    var info = srcInfo(el);
    var dur = el.duration;
    post({
      t: 'media', ev: ev.type, el: el.__avId, tag: el.tagName, src: info.src.slice(0, 4096),
      srcKind: info.kind, res: info.res,
      dur: (isFinite(dur) ? dur : (dur === Infinity ? -1 : null)),
      cur: el.currentTime, paused: el.paused, muted: el.muted, vol: el.volume,
      prot: !!(el.__avProtected || el.mediaKeys),
      video: el.tagName === 'VIDEO' && (el.videoWidth > 0)
    });
  }
  function attach(el) {
    if (el.__avId) return;
    elCounter += 1;
    try { Object.defineProperty(el, '__avId', { value: 'e' + elCounter }); } catch (e) { el.__avId = 'e' + elCounter; }
    EVENTS.forEach(function (n) { try { el.addEventListener(n, handleMediaEvent, true); } catch (e) {} });
  }
  EVENTS.forEach(function (n) { document.addEventListener(n, handleMediaEvent, true); });
  if (HME) {
    var origPlay = HME.prototype.play;
    HME.prototype.play = function () {
      try { attach(this); } catch (e) {}
      return origPlay.apply(this, arguments);
    };
  }

  /* -------------------------------------------------------------- fetch/XHR */
  function retainBlobFromNetwork(kind, url, mime, blob, status) {
    var r = addResource({ id: newId(kind), kind: kind, mime: mime, blob: blob, size: blob.size, url: url, complete: status === 200 });
    retained += blob.size;
    trimResources();
    report(r);
  }
  var origFetch = window.fetch;
  if (origFetch) {
    window.fetch = function (input, init) {
      var p = origFetch.apply(this, arguments);
      try {
        var url = absUrl((input && input.url) ? input.url : input);
        p.then(function (resp) {
          try {
            var ct = lower(resp.headers.get('content-type'));
            if (!(isAudioMime(ct) || isPlaylistMime(ct) || isVideoMime(ct))) return;
            var len = parseInt(resp.headers.get('content-length') || '-1', 10);
            post({ t: 'net', via: 'fetch', url: url, mime: ct, size: len, status: resp.status });
            if (isAudioMime(ct) && resp.ok && resp.type !== 'opaque' && (len < 0 || len <= MAX_FETCH_BYTES)) {
              resp.clone().blob().then(function (b) {
                if (b.size > 0 && b.size <= MAX_FETCH_BYTES) retainBlobFromNetwork('fetch', url, ct, b, resp.status);
              }).catch(function () {});
            }
          } catch (e) {}
        }, function () {});
      } catch (e) {}
      return p;
    };
  }
  var XHR = window.XMLHttpRequest;
  if (XHR) {
    var origOpen = XHR.prototype.open;
    XHR.prototype.open = function (method, url) {
      try { this.__avUrl = absUrl(url); } catch (e) {}
      return origOpen.apply(this, arguments);
    };
    var origSend = XHR.prototype.send;
    XHR.prototype.send = function () {
      var xhr = this;
      try {
        xhr.addEventListener('load', function () {
          try {
            var ct = lower(xhr.getResponseHeader('content-type'));
            if (!(isAudioMime(ct) || isPlaylistMime(ct) || isVideoMime(ct))) return;
            var size = -1, blob = null;
            if (xhr.responseType === 'blob' && xhr.response) { blob = xhr.response; size = blob.size; }
            else if (xhr.responseType === 'arraybuffer' && xhr.response) { size = xhr.response.byteLength; blob = new Blob([xhr.response], { type: ct }); }
            post({ t: 'net', via: 'xhr', url: xhr.__avUrl, mime: ct, size: size, status: xhr.status });
            if (blob && isAudioMime(ct) && xhr.status === 200 && blob.size <= MAX_FETCH_BYTES) retainBlobFromNetwork('xhr', xhr.__avUrl, ct, blob, xhr.status);
          } catch (e) {}
        });
      } catch (e) {}
      return origSend.apply(this, arguments);
    };
  }

  /* -------------------------------------------------------------- Web Audio */
  var BAC = window.BaseAudioContext || window.AudioContext || window.webkitAudioContext;
  var ctxCounter = 0;
  function watchCtx(ctx) {
    if (!ctx || ctx.__avCtx) return ctx && ctx.__avCtx;
    ctxCounter += 1;
    var id = 'c' + ctxCounter;
    try { Object.defineProperty(ctx, '__avCtx', { value: id }); } catch (e) { ctx.__avCtx = id; }
    ctx.__avActive = 0;
    try {
      ctx.addEventListener('statechange', function () { post({ t: 'webaudio', ev: ctx.state, ctx: id }); });
    } catch (e) {}
    post({ t: 'webaudio', ev: ctx.state || 'created', ctx: id });
    return id;
  }
  if (BAC && BAC.prototype) {
    ['createBufferSource', 'createGain', 'createOscillator', 'createMediaElementSource',
      'createScriptProcessor', 'createAnalyser', 'createBiquadFilter', 'resume'].forEach(function (name) {
      var orig = BAC.prototype[name];
      if (typeof orig !== 'function') return;
      BAC.prototype[name] = function () {
        try { watchCtx(this); } catch (e) {}
        return orig.apply(this, arguments);
      };
    });
    var origDecode = BAC.prototype.decodeAudioData;
    if (origDecode) {
      BAC.prototype.decodeAudioData = function (buf) {
        try {
          watchCtx(this);
          if (buf && buf.byteLength > 2048 && buf.byteLength <= MAX_FETCH_BYTES) {
            var copy = buf.slice(0);
            var mime = sniffBytes(new Uint8Array(copy, 0, Math.min(16, copy.byteLength)));
            if (mime) {
              var b = new Blob([copy], { type: mime });
              var r = addResource({ id: newId('wae'), kind: 'webaudio-encoded', mime: mime, blob: b, size: b.size, complete: true });
              retained += b.size;
              trimResources();
              report(r);
            }
          }
        } catch (e) {}
        return origDecode.apply(this, arguments);
      };
    }
  }
  var ABSN = window.AudioBufferSourceNode;
  if (ABSN && ABSN.prototype.start) {
    var origStart = ABSN.prototype.start;
    ABSN.prototype.start = function () {
      try {
        var node = this, ctx = node.context, cid = watchCtx(ctx);
        var dur = node.buffer ? node.buffer.duration : null;
        if (node.buffer && dur >= 1.0 && node.buffer.length * node.buffer.numberOfChannels * 2 <= MAX_FETCH_BYTES) {
          var r = addResource({ id: newId('wab'), kind: 'webaudio-buffer', mime: 'audio/wav', audioBuffer: node.buffer,
            size: node.buffer.length * node.buffer.numberOfChannels * 2 + 44, duration: dur, complete: true });
          report(r);
        }
        ctx.__avActive = (ctx.__avActive || 0) + 1;
        post({ t: 'webaudio', ev: 'start', ctx: cid, dur: dur });
        node.addEventListener('ended', function () {
          ctx.__avActive = Math.max(0, (ctx.__avActive || 1) - 1);
          if (ctx.__avActive === 0) post({ t: 'webaudio', ev: 'end', ctx: cid });
        });
      } catch (e) {}
      return origStart.apply(this, arguments);
    };
  }

  /* ------------------------------------------------------- speechSynthesis */
  try {
    if (window.speechSynthesis && window.speechSynthesis.speak) {
      var synth = window.speechSynthesis;
      var origSpeak = synth.speak;
      synth.speak = function (u) {
        try {
          var len = (u && u.text) ? u.text.length : 0;
          post({ t: 'tts', ev: 'queued', len: len, lang: u && u.lang });
          if (u && u.addEventListener) {
            u.addEventListener('start', function () { post({ t: 'tts', ev: 'start', len: len }); });
            u.addEventListener('end', function () { post({ t: 'tts', ev: 'end', len: len }); });
          }
        } catch (e) {}
        return origSpeak.apply(this, arguments);
      };
    }
  } catch (e) {}

  /* --------------------------------------------- exportación hacia Android */
  function audioBufferToWav(buf) {
    var ch = buf.numberOfChannels, sr = buf.sampleRate, len = buf.length;
    var dataLen = len * ch * 2;
    var out = new ArrayBuffer(44 + dataLen);
    var v = new DataView(out);
    function s(o, str) { for (var i = 0; i < str.length; i++) v.setUint8(o + i, str.charCodeAt(i)); }
    s(0, 'RIFF'); v.setUint32(4, 36 + dataLen, true); s(8, 'WAVE'); s(12, 'fmt ');
    v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, ch, true);
    v.setUint32(24, sr, true); v.setUint32(28, sr * ch * 2, true); v.setUint16(32, ch * 2, true);
    v.setUint16(34, 16, true); s(36, 'data'); v.setUint32(40, dataLen, true);
    var chans = [];
    for (var c = 0; c < ch; c++) chans.push(buf.getChannelData(c));
    var off = 44;
    for (var i = 0; i < len; i++) {
      for (var k = 0; k < ch; k++) {
        var x = Math.max(-1, Math.min(1, chans[k][i]));
        v.setInt16(off, x < 0 ? x * 0x8000 : x * 0x7FFF, true);
        off += 2;
      }
    }
    return new Blob([out], { type: 'audio/wav' });
  }

  function blobForResource(r) {
    if (r.protected) return Promise.reject(new Error('Contenido protegido (DRM): no se exporta'));
    if (r.blob) return Promise.resolve({ blob: r.blob, mime: r.mime || r.blob.type });
    if (r.audioBuffer) return Promise.resolve({ blob: audioBufferToWav(r.audioBuffer), mime: 'audio/wav' });
    if (r.tracks) {
      var audio = r.tracks.filter(function (t) { return isAudioMime(t.mime) && t.chunks.length; });
      var pick = audio[0] || r.tracks.filter(function (t) { return t.chunks.length; })[0];
      if (!pick) return Promise.reject(new Error('No hay segmentos retenidos de Media Source'));
      if (pick.dropped) return Promise.reject(new Error('Flujo demasiado grande o liberado de memoria'));
      return Promise.resolve({ blob: new Blob(pick.chunks, { type: pick.mime }), mime: pick.mime, partial: !r.complete });
    }
    return Promise.reject(new Error('Recurso sin datos'));
  }

  function sendBlob(xid, blob, mime, meta) {
    var n = nativeObj();
    if (!n) return;
    post({ t: 'xfer-begin', xid: xid, size: blob.size, mime: mime, meta: meta || {} });
    var offset = 0, seq = 0;
    function next() {
      if (offset >= blob.size) { post({ t: 'xfer-end', xid: xid, ok: true }); return; }
      var slice = blob.slice(offset, Math.min(offset + CHUNK, blob.size));
      var fr = new FileReader();
      fr.onload = function () {
        try {
          var s = fr.result;
          var b64 = s.substring(s.indexOf(',') + 1);
          n.chunk(xid, seq, b64);
          seq += 1;
          offset += CHUNK;
          setTimeout(next, 0);
        } catch (e) { post({ t: 'xfer-end', xid: xid, ok: false, error: String(e) }); }
      };
      fr.onerror = function () { post({ t: 'xfer-end', xid: xid, ok: false, error: 'FileReader error' }); };
      fr.readAsDataURL(slice);
    }
    next();
  }

  function exportLocal(resId, xid) {
    var r = resources[resId];
    if (!r) return false;
    blobForResource(r).then(function (res) {
      sendBlob(xid, res.blob, res.mime, { kind: r.kind, url: r.url || null, partial: !!res.partial, nonLinear: !!r.nonLinear });
    }).catch(function (e) {
      post({ t: 'xfer-end', xid: xid, ok: false, error: String(e && e.message || e) });
    });
    return true;
  }

  function broadcast(msg) {
    try {
      for (var i = 0; i < window.frames.length; i++) {
        try { window.frames[i].postMessage(msg, '*'); } catch (e) {}
      }
    } catch (e) {}
  }

  window.addEventListener('message', function (ev) {
    var d = ev.data;
    if (!d || d.__av !== 'export') return;
    if (d.res && d.res.indexOf(frameId + ':') === 0) exportLocal(d.res, d.xid);
    else if (d.url && d.frame === frameId) exportUrl(d.url, d.xid);
    else broadcast(d);
  });

  function exportResource(resId, xid) {
    if (resId.indexOf(frameId + ':') === 0) {
      if (!exportLocal(resId, xid)) post({ t: 'xfer-end', xid: xid, ok: false, error: 'Recurso ya no disponible en memoria' });
    } else {
      broadcast({ __av: 'export', res: resId, xid: xid });
    }
    return true;
  }

  /** Exporta una URL accesible desde la propia página (blob:, data: o same-origin). */
  function exportUrl(url, xid, frame) {
    if (frame && frame !== frameId) { broadcast({ __av: 'export', url: url, xid: xid, frame: frame }); return true; }
    var rid = blobUrlToRes[url];
    if (rid && resources[rid]) return exportResource(rid, xid);
    fetch(url).then(function (r) {
      var ct = lower(r.headers.get('content-type'));
      return r.blob().then(function (b) { return { b: b, ct: ct || b.type }; });
    }).then(function (x) {
      if (x.ct.indexOf('text/') === 0 || x.ct.indexOf('html') >= 0) throw new Error('La URL devolvió ' + x.ct + ', no audio');
      sendBlob(xid, x.b, x.ct, { kind: 'url', url: url.indexOf('data:') === 0 ? null : url });
    }).catch(function (e) {
      post({ t: 'xfer-end', xid: xid, ok: false, error: String(e && e.message || e) });
    });
    return true;
  }

  /** Lista los medios presentes en el documento (para re-sincronizar). */
  function scan() {
    var els = document.querySelectorAll('audio,video');
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      attach(el);
      if (!el.paused) handleMediaEvent({ type: 'playing', target: el });
    }
    for (var id in resources) if (resources.hasOwnProperty(id)) report(resources[id]);
    broadcast({ __av: 'scan' });
  }
  window.addEventListener('message', function (ev) {
    if (ev.data && ev.data.__av === 'scan' && !isTop) scan();
  });

  window.__audiovault = {
    version: 1,
    frameId: frameId,
    exportResource: exportResource,
    exportUrl: exportUrl,
    scan: scan,
    isProtected: function () { return protectedPage; }
  };
  post({ t: 'hello', ua: navigator.userAgent });
})();
