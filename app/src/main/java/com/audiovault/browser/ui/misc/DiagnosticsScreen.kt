package com.audiovault.browser.ui.misc

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.audiovault.browser.capture.AudioLevel
import com.audiovault.browser.capture.EngineState
import com.audiovault.browser.data.db.CaptureSource
import com.audiovault.browser.ui.components.CaptureMode
import com.audiovault.browser.ui.components.LevelMeter
import com.audiovault.browser.ui.components.SectionTitle
import com.audiovault.browser.ui.components.container
import com.audiovault.browser.ui.components.rememberCaptureStarter
import com.audiovault.browser.ui.theme.AvColors
import kotlin.math.abs

/**
 * Autoprueba en el dispositivo real: reproduce tonos conocidos dentro de un WebView de la
 * propia app (mismo proceso y UID que las pestañas) y comprueba si llegan al AudioRecord
 * de AudioPlaybackCapture con el nivel y la frecuencia esperados.
 */
private const val TEST_HTML = """<!doctype html><html><head><meta name="viewport" content="width=device-width">
<style>body{background:#161B22;color:#E6EDF3;font:14px sans-serif;margin:8px}</style></head><body>
<div id="s">Prueba de captura lista</div><script>
function wav(freq, sec, sr){var n=Math.floor(sec*sr),b=new ArrayBuffer(44+n*2),v=new DataView(b);
function s(o,t){for(var i=0;i<t.length;i++)v.setUint8(o+i,t.charCodeAt(i));}
s(0,'RIFF');v.setUint32(4,36+n*2,true);s(8,'WAVE');s(12,'fmt ');v.setUint32(16,16,true);v.setUint16(20,1,true);
v.setUint16(22,1,true);v.setUint32(24,sr,true);v.setUint32(28,sr*2,true);v.setUint16(32,2,true);v.setUint16(34,16,true);
s(36,'data');v.setUint32(40,n*2,true);for(var i=0;i<n;i++)v.setInt16(44+i*2,Math.sin(2*Math.PI*freq*i/sr)*16000,true);
return new Blob([b],{type:'audio/wav'});}
function phase(p,ev){AudioVaultTest.phase(p,ev);document.getElementById('s').textContent=p+': '+ev;}
async function runTest(){
 try{ var a=new Audio(URL.createObjectURL(wav(1000,3,44100))); phase('element','start'); await a.play();
  await new Promise(function(r){a.onended=r;}); phase('element','end'); }catch(e){ phase('element','error:'+e); }
 await new Promise(function(r){setTimeout(r,600);});
 try{ var ctx=new AudioContext(); await ctx.resume(); var o=ctx.createOscillator(); var g=ctx.createGain(); g.gain.value=0.5;
  o.frequency.value=660; o.connect(g); g.connect(ctx.destination); phase('webaudio','start'); o.start();
  await new Promise(function(r){setTimeout(r,3000);}); o.stop(); await ctx.close(); phase('webaudio','end'); }catch(e){ phase('webaudio','error:'+e); }
 phase('all','done');
}
</script></body></html>"""

private class PhaseStat(val target: Float) {
    var matchedMs = 0L
    var maxDb = -120f
    var hz = 0f
    var chunks = 0
    var digitalSilence = 0
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DiagnosticsScreen(nav: NavHostController, showMessage: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val engine by c.capture.engine.collectAsStateWithLifecycle()
    val level by c.capture.levelDb.collectAsStateWithLifecycle()
    val starter = rememberCaptureStarter(showMessage)
    val log = remember { mutableStateListOf<String>() }
    var running by remember { mutableStateOf(false) }
    val stats = remember { java.util.concurrent.ConcurrentHashMap<String, PhaseStat>() }
    var activePhase by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    val listener = remember {
        { l: AudioLevel ->
            val p = activePhase
            val st = p?.let { stats[it] }
            if (st != null) {
                st.chunks++
                if (l.isDigitalSilence) st.digitalSilence++
                if (l.rmsDb > st.maxDb) { st.maxDb = l.rmsDb; st.hz = l.zeroCrossHz }
                if (l.rmsDb > -45f && abs(l.zeroCrossHz - st.target) / st.target < 0.2f) st.matchedMs += l.durationMs
            }
        }
    }
    DisposableEffect(Unit) {
        c.capture.addLevelListener(listener)
        onDispose {
            c.capture.removeLevelListener(listener)
            c.capture.suppressSessions = false
            webView?.destroy()
        }
    }

    fun finish() {
        running = false
        c.capture.suppressSessions = false
        for ((name, st) in stats) {
            val label = if (name == "element") "Elemento <audio> en WebView (1000 Hz)" else "Web Audio API en WebView (660 Hz)"
            val ok = st.matchedMs >= 1000
            log += if (ok) "✔ $label: CAPTURABLE · ${st.matchedMs} ms detectados · pico %.0f dBFS · %.0f Hz".format(st.maxDb, st.hz)
            else if (st.chunks > 0 && st.digitalSilence == st.chunks) "✘ $label: NO capturable · solo silencio digital (Android no entrega este audio)"
            else "✘ $label: NO verificado · ${st.matchedMs} ms coincidentes · pico %.0f dBFS · %.0f Hz (¿volumen 0 o audio por otra salida?)".format(st.maxDb, st.hz)
        }
        log += "Resultado guardado solo en esta pantalla. Repite la prueba tras cambiar el alcance de captura si falla."
    }

    SubScreen("Diagnóstico de captura", nav) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle("Dispositivo")
            val wvp = runCatching { WebViewCompat.getCurrentWebViewPackage(ctx) }.getOrNull()
            Mono("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            Mono("WebView: ${wvp?.packageName ?: "?"} ${wvp?.versionName ?: ""}")
            Mono("AudioPlaybackCapture (API 29+): ${if (Build.VERSION.SDK_INT >= 29) "disponible" else "NO disponible"}")
            Mono("Inyección al inicio del documento: ${yesNo(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))}")
            Mono("Perfiles separados (pestaña privada): ${yesNo(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))}")
            Mono("Permiso RECORD_AUDIO: ${yesNo(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)}")
            Mono("Motor de captura: " + when (val e = engine) {
                is EngineState.Active -> "${e.source} · ${e.scope.label} · ${e.sampleRate} Hz · ${e.channels} ch"
                is EngineState.Error -> "error: ${e.message}"
                EngineState.Starting -> "iniciando"
                EngineState.Off -> "apagado"
            })

            SectionTitle("Prueba de captura del WebView")
            Text("Reproduce un tono de 1000 Hz con un elemento <audio> (Blob WAV) y otro de 660 Hz con Web Audio dentro de un WebView de esta app, " +
                "y comprueba si el AudioRecord de AudioPlaybackCapture los recibe. Es la misma arquitectura que usan las pestañas, por lo que indica " +
                "si las lecturas de Claude o ChatGPT serán capturables en este dispositivo. Sube el volumen multimedia.", fontSize = 13.sp)
            LevelMeter(level, Modifier.fillMaxWidth())
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(70.dp),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun phase(p: String, ev: String) {
                                post {
                                    when {
                                        ev == "start" -> { stats[p] = PhaseStat(if (p == "element") 1000f else 660f); activePhase = p; log += "▶ fase $p" }
                                        ev == "end" -> { activePhase = null }
                                        ev.startsWith("error") -> { activePhase = null; log += "✘ $p: $ev" }
                                        p == "all" -> finish()
                                    }
                                }
                            }
                        }, "AudioVaultTest")
                        loadDataWithBaseURL("https://audiovault.local/", TEST_HTML, "text/html", "utf-8", null)
                        webView = this
                    }
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val active = engine as? EngineState.Active
                if (active == null || active.source != CaptureSource.INTERNAL) {
                    Button(onClick = { starter.start(CaptureMode.INTERNAL) }) { Text("1. Activar captura interna") }
                } else {
                    Button(enabled = !running, onClick = {
                        log.clear(); stats.clear(); running = true
                        c.capture.suppressSessions = true
                        log += "Alcance: ${active.scope.label}"
                        webView?.evaluateJavascript("runTest()", null)
                    }) { Text(if (running) "Probando…" else "2. Ejecutar prueba") }
                }
            }
            Card { Column(Modifier.fillMaxWidth().padding(10.dp)) { if (log.isEmpty()) Text("Sin resultados todavía.", color = AvColors.Muted) else log.forEach { Text(it, fontSize = 13.sp) } } }

            SectionTitle("Protocolo de verificación en Claude / ChatGPT / ElevenLabs")
            Text(
                "1. Activa la captura interna (alcance «Solo el navegador»).\n" +
                    "2. Inicia sesión en el sitio y pulsa «Leer en voz alta» / reproducir.\n" +
                    "3. Abre el Panel de audio: debe aparecer el medio detectado (tipo, MIME, duración) y el estado «Grabando».\n" +
                    "4. Al terminar la lectura se guarda un archivo independiente (Claude/lectura_00N.wav). En Información técnica compara la " +
                    "«Duración según la página» con la capturada; si difiere más de un 15 % se marca como Incompleto.\n" +
                    "5. Reproduce el archivo en Mi Biblioteca: si contiene la voz, la captura funciona en ese sitio. Si solo hay silencio, la app " +
                    "avisa «no capturable» y no guarda el archivo.\n" +
                    "6. Si además se detectó un original (Blob, fetch, MSE), aparecerá como «Original» con su formato sin convertir.",
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun yesNo(b: Boolean) = if (b) "sí" else "no"

@Composable
private fun Mono(t: String) = Text(t, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Normal)
