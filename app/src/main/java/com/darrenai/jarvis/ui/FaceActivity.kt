package com.darrenai.jarvis.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.darrenai.jarvis.SignalBus
import java.io.ByteArrayInputStream

/**
 * The face — the ACTUAL ai-visualizer faces (verbatim files under
 * assets/fsa), served to a WebView through request interception.
 *
 * https://fsa.local/… never hits the network: shouldInterceptRequest
 * serves face HTML/JS/CSS from assets and /state + /config from the
 * live SignalBus. The faces poll /state every ~120ms exactly like on
 * desktop, so they perform identically: idle, listening, thinking,
 * speaking in sync with the voice loop.
 */
class FaceActivity : Activity() {

    private lateinit var webView: WebView

    companion object {
        const val EXTRA_FACE = "face"
        private const val BASE = "https://fsa.local"

        fun show(ctx: Context, face: String = "board") {
            val intent = Intent(ctx, FaceActivity::class.java).apply {
                putExtra(EXTRA_FACE, face)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            ctx.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        val face = intent.getStringExtra(EXTRA_FACE)?.takeIf { it.isNotBlank() } ?: "board"

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = false
                displayZoomControls = false
                setSupportZoom(false)
                cacheMode = WebSettings.LOAD_NO_CACHE
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = true
                allowContentAccess = true
            }
            webChromeClient = WebChromeClient()
            webViewClient = FaceClient()
            addJavascriptInterface(FaceBridge(), "FsaBus")
            loadUrl("$BASE/faces/$face/index.html")
        }

        setContentView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        runCatching { webView.destroy() }
        super.onDestroy()
    }

    private inner class FaceClient : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            val url = request?.url?.toString() ?: return null
            if (!url.startsWith(BASE)) return null
            val path = url.removePrefix(BASE).substringBefore("?")
            return serve(path)
        }

        @Suppress("DEPRECATION")
        override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
            if (url == null || !url.startsWith(BASE)) return null
            return serve(url.removePrefix(BASE).substringBefore("?"))
        }

        private fun serve(path: String): WebResourceResponse? {
            return try {
                when (path) {
                    "/state" -> json(SignalBus.snapshotJson())
                    "/config" -> json(
                        SignalBus.configJson("JARVIS", facesJson())
                    )
                    "/", "" -> asset("fsa/index.html", "text/html")
                    else -> {
                        val name = path.removePrefix("/")
                        asset("fsa/$name", mimeFor(name))
                    }
                }
            } catch (e: Exception) {
                null
            }
        }

        private fun json(body: String): WebResourceResponse {
            return WebResourceResponse(
                "application/json", "UTF-8", 200, "OK",
                mapOf("Cache-Control" to "no-store"),
                ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
            )
        }

        private fun asset(assetPath: String, mime: String): WebResourceResponse? {
            return try {
                val stream = this@FaceActivity.assets.open(assetPath)
                WebResourceResponse(mime, "UTF-8", stream)
            } catch (e: Exception) {
                null
            }
        }

        private fun facesJson(): String {
            // Discovered the same way server.py scans faces/.
            val ids = listOf("board", "neural", "radial", "rain")
            val items = ids.mapNotNull { id ->
                try {
                    val meta = this@FaceActivity.assets
                        .open("fsa/faces/$id/face.json")
                        .bufferedReader().use { it.readText() }
                    val title = Regex("\"title\"\\s*:\\s*\"([^\"]+)\"")
                        .find(meta)?.groupValues?.get(1) ?: id.replaceFirstChar { it.uppercase() }
                    val tag = Regex("\"tagline\"\\s*:\\s*\"([^\"]*)\"")
                        .find(meta)?.groupValues?.get(1) ?: ""
                    "{\"id\":\"$id\",\"title\":\"${title.replace("\"", "")}\",\"tagline\":\"${tag.replace("\"", "")}\"}"
                } catch (e: Exception) {
                    "{\"id\":\"$id\",\"title\":\"$id\",\"tagline\":\"\"}"
                }
            }
            return "[" + items.joinToString(",") + "]"
        }

        private fun mimeFor(name: String): String {
            return when {
                name.endsWith(".html") -> "text/html"
                name.endsWith(".js") -> "application/javascript"
                name.endsWith(".css") -> "text/css"
                name.endsWith(".json") -> "application/json"
                name.endsWith(".png") -> "image/png"
                name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
                name.endsWith(".svg") -> "image/svg+xml"
                name.endsWith(".ttf") -> "font/ttf"
                name.endsWith(".wav") -> "audio/wav"
                else -> "application/octet-stream"
            }
        }
    }

    private inner class FaceBridge {
        @JavascriptInterface
        fun getState(): String = runCatching { SignalBus.snapshotJson() }.getOrDefault(
            "{\"state\":\"idle\",\"level\":0,\"samples\":[],\"alert\":false,\"loading\":false}"
        )
    }
}
