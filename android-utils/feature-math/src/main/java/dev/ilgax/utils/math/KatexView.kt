package dev.ilgax.utils.math

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AColor
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.ilgax.utils.math.recognition.Json
import dev.ilgax.utils.math.solver.Step

/** Everything the card's one WebView shows: the typed LaTeX, the answers, notes and (optionally) the steps. */
data class KatexContent(
    val preview: String?,
    val answers: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val steps: List<Step> = emptyList(),
) {
    fun toJson(fg: String, muted: String, accent: String): String {
        fun list(items: List<String>) = items.joinToString(",", "[", "]") { Json.quote(it) }
        return "{\"preview\":${preview?.let { Json.quote(it) } ?: "null"},\"answers\":${list(answers)},\"notes\":${list(notes)}," +
            "\"steps\":${steps.joinToString(",", "[", "]") { "{\"c\":${Json.quote(it.caption)},\"l\":${Json.quote(it.latex)}}" }}," +
            "\"fg\":${Json.quote(fg)},\"muted\":${Json.quote(muted)},\"accent\":${Json.quote(accent)}}"
    }
}

/** Renders [KatexContent]. Screenshot tests replace this because Robolectric cannot draw a WebView. */
val LocalKatexRenderer = staticCompositionLocalOf<@Composable (KatexContent, Modifier) -> Unit> { { content, modifier -> KatexView(content, modifier) } }

private fun Color.hex(): String = "#%06X".format(toArgb() and 0xFFFFFF)

/**
 * Shows [content] with KaTeX. The WebView comes from [KatexPool] (already loaded, so an update is just an
 * `evaluateJavascript` call). Until KaTeX has painted this content the plain LaTeX text is shown instead, so
 * the recognised text is on screen the moment OCR finishes; then it is swapped for the typeset version.
 */
@Composable
fun KatexView(content: KatexContent, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val json = remember(content, colors.onSurface, colors.onSurfaceVariant, colors.primary) {
        content.toJson(colors.onSurface.hex(), colors.onSurfaceVariant.hex(), colors.primary.hex())
    }
    var heightDp by remember { mutableIntStateOf(0) }
    var ready by remember { mutableStateOf(false) }
    val holder = remember { KatexPool.acquire(context) }
    DisposableEffect(holder) {
        holder.onHeight = { heightDp = it.coerceAtLeast(8) }
        onDispose { KatexPool.release(holder) }
    }
    val hasAnswers = content.answers.isNotEmpty()
    LaunchedEffect(json) {
        holder.render(json) {
            ready = true
            if (hasAnswers) SolveTimings.current.mark(Stage.KatexRendered)
        }
    }
    Box(modifier.fillMaxWidth()) {
        if (!ready) content.preview?.let { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant) }
        AndroidView(
            factory = { holder.attach() },
            modifier = Modifier.fillMaxWidth().height(if (ready) heightDp.dp else 0.dp),
        )
    }
}

/**
 * Pre-created, kept-alive KaTeX WebViews. [warm] creates one and loads the page while the Air Command panel is
 * still closing; a view is lent out by [acquire], returned by [release] and destroyed after [IDLE_MS] unused.
 * Main thread only.
 */
object KatexPool {
    private const val IDLE_MS = 5 * 60_000L
    private val main = Handler(Looper.getMainLooper())
    private val idle = ArrayList<KatexHolder>()
    private val trim = Runnable { idle.forEach { it.destroy() }; idle.clear() }

    /** Makes sure one loaded (or loading) page is ready. */
    fun warm(context: Context) {
        if (idle.isEmpty()) idle += KatexHolder(context.applicationContext)
        main.removeCallbacks(trim)
        main.postDelayed(trim, IDLE_MS)
    }

    fun acquire(context: Context): KatexHolder {
        main.removeCallbacks(trim)
        return if (idle.isNotEmpty()) idle.removeAt(idle.size - 1) else KatexHolder(context.applicationContext)
    }

    fun release(holder: KatexHolder) {
        holder.detach()
        idle += holder
        main.removeCallbacks(trim)
        main.postDelayed(trim, IDLE_MS)
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
class KatexHolder(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var loaded = false
    private var pending: String? = null
    private var destroyed = false
    private var seq = 0
    private var doneSeq = 0
    private var done: (() -> Unit)? = null

    /** Called with the page's content height in CSS px (= dp) whenever it changes. */
    var onHeight: ((Int) -> Unit)? = null

    val webView = WebView(context).apply {
        setBackgroundColor(AColor.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = WebView.OVER_SCROLL_NEVER
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = true // only file:///android_asset/katex/, packaged with the app
            allowContentAccess = false
            blockNetworkLoads = true
            setSupportZoom(false)
        }
        addJavascriptInterface(object {
            @JavascriptInterface
            fun height(px: Int, seq: Int) {
                main.post {
                    if (destroyed) return@post
                    onHeight?.invoke(px)
                    if (seq >= doneSeq) done?.let { done = null; it() }
                }
            }
        }, "Android")
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                loaded = true
                pending?.let { run(it) }
                pending = null
            }
        }
        loadUrl("file:///android_asset/katex/card.html")
    }

    /** [onRendered] runs once KaTeX has painted this [json] (the page reports its height after rendering). */
    fun render(json: String, onRendered: (() -> Unit)? = null) {
        seq++
        doneSeq = seq
        done = onRendered
        val call = "render($json, $seq)"
        if (loaded) run(call) else pending = call
    }

    /** The view, removed from any previous parent. */
    fun attach(): WebView {
        (webView.parent as? ViewGroup)?.removeView(webView)
        return webView
    }

    fun detach() {
        onHeight = null
        done = null
        (webView.parent as? ViewGroup)?.removeView(webView)
    }

    private fun run(call: String) {
        if (!destroyed) webView.evaluateJavascript(call, null)
    }

    fun destroy() {
        destroyed = true
        detach()
        webView.removeJavascriptInterface("Android")
        webView.destroy()
    }
}
