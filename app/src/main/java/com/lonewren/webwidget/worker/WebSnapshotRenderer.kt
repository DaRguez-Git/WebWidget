package com.lonewren.webwidget.worker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Renders a URL into a [Bitmap] by driving an offscreen [WebView].
 *
 * The widget shows one page snapshot per card; cards are full-widget-size
 * and the user swipes between them. So the only render mode we need is a
 * single thumbnail at the widget's pixel dimensions.
 *
 * **Gotchas this class works around** (the comments inline call them out):
 *  - WebView must run on the main thread.
 *  - It is not attached to a window, so we have to call measure() and
 *    layout() manually before draw().
 *  - Hardware-accelerated layers do not render to Bitmap-backed Canvases;
 *    we force LAYER_TYPE_SOFTWARE.
 *  - onPageFinished can fire before async paint settles; we add a short
 *    settle delay after the load signal.
 *  - Failure paths must still destroy() the WebView or chromium child
 *    processes leak.
 */
class WebSnapshotRenderer(private val appContext: Context) {

    sealed interface Result {
        data class Success(val bitmap: Bitmap) : Result
        data class Failure(val reason: String) : Result
    }

    /**
     * @param url               The page to load. Must include a scheme.
     * @param targetWidthPx     Final bitmap width (typically widget cell size).
     * @param targetHeightPx    Final bitmap height.
     * @param renderWidthPx     Logical viewport width used while rendering.
     *                          Larger than the final width on purpose: a desktop
     *                          width (~1024 px) keeps responsive sites from
     *                          serving the cramped mobile layout. The bitmap is
     *                          downscaled afterwards.
     * @param timeoutMillis     Hard cap before we give up waiting for onPageFinished.
     */
    suspend fun render(
        url: String,
        targetWidthPx: Int,
        targetHeightPx: Int,
        renderWidthPx: Int = DEFAULT_RENDER_WIDTH_PX,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
    ): Result {
        // Aspect-ratio preserving render dimensions: we render at a wide
        // logical width and downscale, so the page sees a desktop viewport
        // even when the final bitmap is small.
        val aspect = targetHeightPx.toFloat() / targetWidthPx.toFloat()
        val renderHeightPx = (renderWidthPx * aspect)
            .toInt()
            .coerceIn(MIN_RENDER_DIMENSION_PX, MAX_RENDER_DIMENSION_PX)
        val safeRenderWidth = renderWidthPx.coerceAtMost(MAX_RENDER_DIMENSION_PX)

        return withContext(Dispatchers.Main) {
            withWebView(url, timeoutMillis) { webView ->
                webView.measure(
                    View.MeasureSpec.makeMeasureSpec(safeRenderWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(renderHeightPx, View.MeasureSpec.EXACTLY),
                )
                webView.layout(0, 0, safeRenderWidth, renderHeightPx)

                val raw = Bitmap.createBitmap(
                    safeRenderWidth,
                    renderHeightPx,
                    Bitmap.Config.ARGB_8888,
                )
                webView.draw(Canvas(raw))

                withContext(Dispatchers.Default) {
                    Bitmap.createScaledBitmap(raw, targetWidthPx, targetHeightPx, true).also {
                        if (it !== raw) raw.recycle()
                    }
                }
            }
        }
    }

    /**
     * Common WebView lifecycle: build, configure, load, await, draw via
     * [draw], then mandatory cleanup. The [draw] lambda runs on the main
     * thread after page-ready + settle and is responsible for the measure /
     * layout / draw sequence appropriate to its rendering mode.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun withWebView(
        url: String,
        timeoutMillis: Long,
        draw: suspend (WebView) -> Bitmap,
    ): Result {
        // Application context: an Activity context here would leak the
        // Activity through the WebView's internal references.
        val webView = WebView(appContext)

        // Software layer is REQUIRED for view.draw(canvas) to produce a real
        // bitmap. With hardware acceleration the contents go through a GPU
        // texture and Canvas-on-Bitmap recording captures little to nothing.
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            databaseEnabled = false
            mediaPlaybackRequiresUserGesture = true
            userAgentString = DESKTOP_USER_AGENT
        }

        val pageReady = CompletableDeferred<Unit>()
        var loadFailureReason: String? = null

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, finishedUrl: String) {
                if (!pageReady.isCompleted && finishedUrl != "about:blank") {
                    pageReady.complete(Unit)
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    val description = error.description?.toString().orEmpty()
                    loadFailureReason = "WebView error ${error.errorCode}: $description"
                    Log.w(
                        TAG,
                        "main-frame error code=${error.errorCode} desc=$description url=${request.url}",
                    )
                    if (!pageReady.isCompleted) pageReady.complete(Unit)
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (newProgress >= 100 && !pageReady.isCompleted) {
                    pageReady.complete(Unit)
                }
            }
        }

        webView.loadUrl(url)

        try {
            val signal = withTimeoutOrNull(timeoutMillis) { pageReady.await() }
            if (signal == null) {
                return Result.Failure("Timed out after ${timeoutMillis / 1000}s")
            }
            if (loadFailureReason != null) {
                return Result.Failure(loadFailureReason!!)
            }

            // Settle delay: even after onPageFinished the page often inserts
            // content via requestAnimationFrame on the next frame (web fonts,
            // banners, lazy images). Drawing immediately can capture a pre-
            // paint state. 500ms is a pragmatic compromise.
            delay(SETTLE_DELAY_MS)

            return Result.Success(draw(webView))
        } catch (t: Throwable) {
            return Result.Failure(t.message ?: t.javaClass.simpleName)
        } finally {
            // Cleanup is mandatory. Without these calls Chromium leaves a
            // child renderer process around and the whole-process WebView
            // state machine can refuse to start a new instance later.
            webView.stopLoading()
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        }
    }

    companion object {
        const val DEFAULT_RENDER_WIDTH_PX = 1024
        const val DEFAULT_TIMEOUT_MS = 20_000L
        private const val SETTLE_DELAY_MS = 500L
        private const val MIN_RENDER_DIMENSION_PX = 256
        private const val MAX_RENDER_DIMENSION_PX = 2048

        private const val TAG = "WebSnapshotRenderer"

        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
