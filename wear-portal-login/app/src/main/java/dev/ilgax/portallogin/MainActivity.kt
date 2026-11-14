package dev.ilgax.portallogin

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var cm: ConnectivityManager
    private val main = Handler(Looper.getMainLooper())

    private var network: Network? = null
    private var webView: WebView? = null
    private var probing = false
    private var online = false
    private var fullBleed = false
    private var lastUrlText = "http://neverssl.com"
    private val trustedHosts = mutableSetOf<String>()

    private lateinit var container: FrameLayout
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var overlay: LinearLayout
    private lateinit var overlayText: TextView
    private lateinit var overlayButton: Button
    private lateinit var overlayButton2: Button
    private lateinit var controls: HorizontalScrollView

    private val timeoutRunnable = Runnable {
        if (network == null) {
            setStatus(getString(R.string.no_wifi))
            showOverlay(getString(R.string.no_wifi), getString(R.string.wifi_settings), ::openWifiSettings)
        }
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) {
            main.post {
                network = n
                cm.bindProcessToNetwork(n)
                main.removeCallbacks(timeoutRunnable)
                probe(initial = true)
            }
        }

        override fun onLost(n: Network) {
            main.post {
                if (n != network) return@post
                network = null
                online = false
                cm.bindProcessToNetwork(null)
                showWaiting()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        cm = getSystemService(ConnectivityManager::class.java)
        buildUi()
        showWaiting()
        requestWifi()
    }

    // region UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        container = FrameLayout(this)
        root.addView(container, FrameLayout.LayoutParams(-1, -1))
        applyInset()

        webView = try {
            WebView(this).also { configure(it) }
        } catch (e: Exception) {
            null
        }
        webView?.let { container.addView(it, FrameLayout.LayoutParams(-1, -1)) }

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        container.addView(progress, FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP))

        overlayText = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
        }
        overlayButton = Button(this)
        overlayButton2 = Button(this)
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            isClickable = true
            addView(overlayText, LinearLayout.LayoutParams(-2, -2))
            addView(overlayButton, LinearLayout.LayoutParams(-2, dp(44)))
            addView(overlayButton2, LinearLayout.LayoutParams(-2, dp(44)))
            val p = dp(20)
            setPadding(p, p, p, p)
        }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        statusText = TextView(this).apply {
            setTextColor(Color.LTGRAY); textSize = 11f; gravity = Gravity.CENTER
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        val roundInset = if (resources.configuration.isScreenRound) dp(56) else dp(8)
        root.addView(
            statusText,
            FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(if (resources.configuration.isScreenRound) 10 else 2)
                marginStart = roundInset; marginEnd = roundInset
            },
        )

        controls = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            background = GradientDrawable().apply {
                setColor(0xAA202124.toInt()); cornerRadius = dp(22).toFloat()
            }
            val strip = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            fun btn(label: Int, action: () -> Unit) = Button(this@MainActivity).apply {
                setText(label); textSize = 11f; isAllCaps = false
                minWidth = dp(40); minimumWidth = dp(40); minHeight = dp(40); minimumHeight = dp(40)
                setPadding(dp(6), 0, dp(6), 0)
                background = null
                setTextColor(Color.WHITE)
                setOnClickListener { action() }
            }
            strip.addView(btn(R.string.btn_back) { goBackOrIgnore() })
            strip.addView(btn(R.string.btn_reload) { webView?.reload() })
            strip.addView(btn(R.string.btn_check) { probe() })
            strip.addView(btn(R.string.btn_fit) { fullBleed = !fullBleed; applyInset() })
            strip.addView(btn(R.string.btn_url) { showUrlDialog() })
            addView(strip)
        }
        root.addView(
            controls,
            FrameLayout.LayoutParams(-2, dp(44), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(if (resources.configuration.isScreenRound) 14 else 4)
                val side = if (resources.configuration.isScreenRound) dp(70) else dp(8)
                marginStart = side; marginEnd = side
            },
        )

        setContentView(root)
    }

    /** Inset the page to the square inscribed in the round display, unless full-bleed. */
    private fun applyInset() {
        val dm = resources.displayMetrics
        val pad = if (resources.configuration.isScreenRound && !fullBleed)
            (minOf(dm.widthPixels, dm.heightPixels) * 0.146f).roundToInt() else 0
        container.setPadding(pad, pad, pad, pad)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(wv: WebView) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            textZoom = 110
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        wv.setBackgroundColor(Color.WHITE)
        wv.setOnGenericMotionListener { v, e ->
            if (e.action == MotionEvent.ACTION_SCROLL &&
                e.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
            ) {
                val d = -e.getAxisValue(MotionEvent.AXIS_SCROLL) * 48f
                v.scrollBy(0, (d * resources.displayMetrics.density).roundToInt())
                true
            } else false
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 0..99) View.VISIBLE else View.GONE
            }
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase()
                return scheme != "http" && scheme != "https"
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                hideOverlay()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (!online) url?.let { setStatus(hostOf(it) ?: getString(R.string.portal)) }
                probe()
            }

            override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (req.isForMainFrame) {
                    showOverlay(
                        getString(R.string.page_error, err.description),
                        getString(R.string.retry),
                    ) { hideOverlay(); view.reload() }
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                val host = hostOf(error.url) ?: error.url
                if (host in trustedHosts) {
                    handler.proceed(); return
                }
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(getString(R.string.ssl_message, host))
                    .setPositiveButton(R.string.continue_label) { _, _ -> trustedHosts += host; handler.proceed() }
                    .setNegativeButton(R.string.cancel) { _, _ -> handler.cancel() }
                    .setOnCancelListener { handler.cancel() }
                    .show()
            }
        }
    }

    private fun hostOf(url: String) = runCatching { Uri.parse(url).host }.getOrNull()

    private fun setStatus(s: String) { statusText.text = s }

    private fun showOverlay(
        text: String,
        action: String? = null,
        onAction: (() -> Unit)? = null,
        action2: String? = null,
        onAction2: (() -> Unit)? = null,
    ) {
        overlayText.text = text
        fun bind(b: Button, label: String?, fn: (() -> Unit)?) {
            b.visibility = if (label != null && fn != null) View.VISIBLE else View.GONE
            b.text = label
            b.setOnClickListener { fn?.invoke() }
        }
        bind(overlayButton, action, onAction)
        bind(overlayButton2, action2, onAction2)
        overlay.visibility = View.VISIBLE
    }

    private fun hideOverlay() { overlay.visibility = View.GONE }

    private fun showWaiting() {
        setStatus(getString(R.string.no_wifi))
        if (webView == null) {
            showOverlay(getString(R.string.webview_missing)); return
        }
        showOverlay(getString(R.string.waiting_wifi), getString(R.string.wifi_settings), ::openWifiSettings)
    }

    private fun showUrlDialog() {
        val input = EditText(this).apply {
            setText(lastUrlText)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.url_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.open) { _, _ ->
                UrlNormalizer.normalize(input.text.toString())?.let {
                    lastUrlText = it
                    hideOverlay()
                    webView?.loadUrl(it)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openWifiSettings() {
        try {
            startActivity(Intent("com.google.android.clockwork.settings.connectivity.wifi.ADD_NETWORK_SETTINGS"))
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            } catch (e2: ActivityNotFoundException) {
                Toast.makeText(this, R.string.no_wifi, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // endregion

    // region Network

    private fun requestWifi() {
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.requestNetwork(req, callback)
        } catch (e: SecurityException) {
            setStatus(getString(R.string.no_wifi))
        }
        main.postDelayed(timeoutRunnable, 15_000)
    }

    private fun probe(initial: Boolean = false) {
        val n = network ?: return
        if (probing) return
        probing = true
        if (initial) setStatus(getString(R.string.checking))
        Thread {
            val result = runProbe(n)
            main.post {
                probing = false
                if (isDestroyed || n != network) return@post
                handle(result, n)
            }
        }.start()
    }

    private fun runProbe(n: Network): ProbeResult = try {
        val c = n.openConnection(URL(ProbeClassifier.PROBE_URL)) as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.connectTimeout = 5000
            c.readTimeout = 5000
            c.setRequestProperty("User-Agent", USER_AGENT)
            val code = c.responseCode
            val body = if (code == 200) {
                runCatching { c.inputStream.bufferedReader().use { it.readText().take(2048) } }.getOrNull()
            } else null
            ProbeClassifier.classify(code, c.getHeaderField("Location"), body)
        } finally {
            c.disconnect()
        }
    } catch (e: java.io.IOException) {
        ProbeResult.Error(e.message ?: e.javaClass.simpleName)
    }

    private fun handle(result: ProbeResult, n: Network) {
        when (result) {
            is ProbeResult.Online -> {
                val wasOnline = online
                online = true
                setStatus(getString(R.string.online))
                if (!wasOnline) {
                    cm.reportNetworkConnectivity(n, true)
                    Toast.makeText(this, R.string.online_banner, Toast.LENGTH_LONG).show()
                    vibrate()
                    showOverlay(
                        getString(R.string.online_banner),
                        getString(R.string.browse_anyway), ::hideOverlay,
                    )
                }
            }
            is ProbeResult.Portal -> {
                online = false
                setStatus(getString(R.string.portal))
                // Only load automatically if nothing is showing yet; later probes must not clobber the login flow.
                val wv = webView
                if (wv != null && wv.url.isNullOrEmpty()) {
                    hideOverlay()
                    wv.loadUrl(result.url)
                } else if (overlay.visibility == View.VISIBLE && wv != null) {
                    hideOverlay()
                }
            }
            is ProbeResult.Error -> {
                online = false
                setStatus(getString(R.string.no_wifi))
                showOverlay(
                    getString(R.string.probe_error, result.message),
                    getString(R.string.retry), { probe(initial = true) },
                    getString(R.string.wifi_settings), ::openWifiSettings,
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        try {
            val effect = VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE)
            if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(effect)
            } else {
                (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(effect)
            }
        } catch (_: Exception) {
        }
    }

    // endregion

    private fun goBackOrIgnore() {
        webView?.takeIf { it.canGoBack() }?.goBack()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else super.onBackPressed()
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val wv = webView
        if (wv != null && event.action == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
        ) {
            val d = -event.getAxisValue(MotionEvent.AXIS_SCROLL) * 48f
            wv.scrollBy(0, (d * resources.displayMetrics.density).roundToInt())
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (_: IllegalArgumentException) {
        }
        cm.bindProcessToNetwork(null)
        webView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun MotionEvent.isFromSource(src: Int) = (source and src) == src

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
