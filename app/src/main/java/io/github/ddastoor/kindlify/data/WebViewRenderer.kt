package io.github.ddastoor.kindlify.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * Loads a page in a hidden WebView (Android's built-in browser engine) so its JavaScript
 * runs, then hands back the resulting HTML. Used by Fetch contents for pages that build
 * their text in the browser. Nothing is shown, no JavaScript interface is exposed to the
 * page, file access is off, and the WebView is destroyed afterwards.
 */
class WebViewRenderer(private val context: Context) : PageRenderer {

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun render(url: String): RenderedPage? = withContext(Dispatchers.Main) {
        val webView = WebView(context.applicationContext)
        val handler = Handler(Looper.getMainLooper())
        try {
            withTimeoutOrNull(TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        blockNetworkImage = true // only the text structure is needed; pictures are downloaded separately
                        allowFileAccess = false
                        allowContentAccess = false
                    }
                    // A phone-sized layout, so pages render as they would on screen.
                    webView.measure(
                        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY),
                    )
                    webView.layout(0, 0, 1080, 2400)

                    val capture = Runnable {
                        webView.evaluateJavascript("document.documentElement.outerHTML") { result ->
                            val html = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()
                            if (continuation.isActive) {
                                continuation.resume(html?.let { RenderedPage(webView.url ?: url, it) })
                            }
                        }
                    }
                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false

                        override fun onPageFinished(view: WebView, finishedUrl: String) {
                            // Give the page's scripts a moment to build the content; restart the wait
                            // if another load finishes (redirects, client-side navigation).
                            handler.removeCallbacks(capture)
                            handler.postDelayed(capture, SETTLE_MS)
                        }
                    }
                    continuation.invokeOnCancellation { handler.removeCallbacks(capture) }
                    webView.loadUrl(url)
                }
            }
        } finally {
            webView.stopLoading()
            webView.destroy()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 25_000L
        const val SETTLE_MS = 2_500L
    }
}
