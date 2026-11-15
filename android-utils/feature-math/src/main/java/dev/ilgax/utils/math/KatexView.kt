package dev.ilgax.utils.math

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AColor
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
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

/** One WebView per call site, reused for every update; sized from the height the page reports. */
@Composable
fun KatexView(content: KatexContent, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val json = remember(content, colors.onSurface, colors.onSurfaceVariant, colors.primary) {
        content.toJson(colors.onSurface.hex(), colors.onSurfaceVariant.hex(), colors.primary.hex())
    }
    var heightDp by remember { mutableIntStateOf(40) }
    val holder = remember { KatexHolder(context) { heightDp = it.coerceAtLeast(8) } }
    DisposableEffect(holder) { onDispose { holder.destroy() } }
    LaunchedEffect(json) { holder.render(json) }
    AndroidView(factory = { holder.webView }, modifier = modifier.fillMaxWidth().height(heightDp.dp))
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
private class KatexHolder(context: Context, private val onHeight: (Int) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var loaded = false
    private var pending: String? = null
    private var destroyed = false

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
            fun height(px: Int) { main.post { if (!destroyed) onHeight(px) } }
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

    fun render(json: String) {
        if (loaded) run(json) else pending = json
    }

    private fun run(json: String) {
        if (!destroyed) webView.evaluateJavascript("render($json)", null)
    }

    fun destroy() {
        destroyed = true
        webView.removeJavascriptInterface("Android")
        webView.destroy()
    }
}
