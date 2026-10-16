package eu.kanade.tachiyomi.ui.stream

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Keeps one WebView per [StreamSite] alive for the whole process, so switching tabs doesn't stop
 * playback or reload the page. The view is re-attached whenever its tab is composed again.
 */
class StreamWebState(private val site: StreamSite, private val appContext: Context) {

    private val ctx = android.content.MutableContextWrapper(appContext)

    var canGoBack by mutableStateOf(false)
        private set
    var inFullscreen by mutableStateOf(false)
        private set

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var fullscreenHost: FrameLayout? = null

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    val webView: WebView = WebView(ctx).apply {
        val self = this
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = StreamScripts.USER_AGENT
            setSupportMultipleWindows(false)
            builtInZoomControls = false
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(self, true)
        }
        addJavascriptInterface(Bridge(), StreamScripts.BRIDGE)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (site == StreamSite.YouTube && request.isForMainFrame) {
                    StreamPlayerLauncher.normalize(uri.toString())?.let {
                        openInPlayer(it)
                        return true
                    }
                }
                if (isAllowedHost(uri)) return false
                runCatching {
                    view.context.startActivity(
                        Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                view.evaluateJavascript(StreamScripts.MAIN, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(StreamScripts.MAIN, null)
                canGoBack = view.canGoBack()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                canGoBack = view.canGoBack()
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                showFullscreen(view, callback)
            }

            override fun onHideCustomView() {
                hideFullscreen()
            }
        }
        loadUrl(site.homeUrl)
    }

    fun attach(activityContext: Context) {
        ctx.baseContext = activityContext
        syncPlayback()
    }

    fun detach() {
        // Leave a fullscreen video visible state consistent when the tab goes away.
        if (customView != null) hideFullscreen()
        ctx.baseContext = appContext
    }

    fun goBack(): Boolean {
        if (customView != null) {
            hideFullscreen()
            return true
        }
        if (webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return false
    }

    private fun showFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        val activity = ctx.baseContext.findActivity() ?: return
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customCallback = callback
        val decor = activity.window.decorView as ViewGroup
        fullscreenHost = FrameLayout(activity).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            decor.addView(
                this,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        WindowInsetsControllerCompat(activity.window, decor).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        inFullscreen = true
    }

    private fun hideFullscreen() {
        val activity = ctx.baseContext.findActivity()
        fullscreenHost?.let { host ->
            (host.parent as? ViewGroup)?.removeView(host)
            host.removeAllViews()
        }
        fullscreenHost = null
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        if (activity != null) {
            WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        inFullscreen = false
    }

    // ---- background playback bookkeeping ----

    private fun syncPlayback() = StreamPlayback.update(appContext, site, playing[site] == true)

    private fun openInPlayer(url: String) {
        webView.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){v.pause()})", null)
        val context = ctx.baseContext
        scope.launch {
            runCatching { StreamPlayerLauncher.play(context, url) }.onFailure {
                android.widget.Toast.makeText(
                    appContext,
                    it.message ?: it.javaClass.simpleName,
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)

    private inner class Bridge {
        @JavascriptInterface
        fun openVideo(url: String) {
            webView.post { openInPlayer(url) }
        }

        @JavascriptInterface
        fun onPlaying(isPlaying: Boolean) {
            playing[site] = isPlaying
            webView.post { syncPlayback() }
        }
    }

    companion object {
        private val playing = mutableMapOf<StreamSite, Boolean>()
        private val states = mutableMapOf<StreamSite, StreamWebState>()

        fun get(site: StreamSite, context: Context): StreamWebState =
            states.getOrPut(site) { StreamWebState(site, context.applicationContext) }

        private fun isAllowedHost(uri: Uri): Boolean {
            val host = uri.host ?: return false
            val scheme = uri.scheme ?: return false
            if (scheme != "https" && scheme != "http" && scheme != "about") return false
            return host == "youtu.be" ||
                host.endsWith("youtube.com") ||
                host.endsWith("google.com") ||
                host.endsWith("googleusercontent.com") ||
                host.endsWith("gstatic.com") ||
                host.endsWith("ytimg.com") ||
                host.endsWith("googlevideo.com")
        }

        private tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
            is android.app.Activity -> this
            is ContextWrapper -> baseContext.findActivity()
            else -> null
        }
    }
}

/** Starts/stops the foreground service that keeps audio alive while the app is in the background. */
internal object StreamPlayback {
    private val active = mutableSetOf<StreamSite>()

    fun update(context: Context, site: StreamSite, isPlaying: Boolean) {
        val was = active.isNotEmpty()
        if (isPlaying) active.add(site) else active.remove(site)
        val now = active.isNotEmpty()
        if (was == now) return
        val intent = Intent(context, StreamPlaybackService::class.java)
        if (now) {
            runCatching { ContextCompat.startForegroundService(context, intent) }
        } else {
            context.stopService(intent)
        }
    }
}
