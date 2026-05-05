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
 * Two public modes:
 *  - [render]:        single bitmap at exact target dimensions. Used by the
 *                     configuration preview (one thumbnail).
 *  - [renderFullPage]: tall bitmap at target width × full content height.
 *                      Used by the worker to feed the scrollable ListView
 *                      tiles in the widget.
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
     * Single thumbnail at [targetWidthPx] × [targetHeightPx].
     *
     * Useful for previews where we want to see what the page looks like in a
     * single widget cell. The page is rendered at [renderWidthPx] (a desktop
     * width keeps responsive sites from serving the cramped mobile layout)
     * and downscaled with aspect ratio preserved.
     */
    suspend fun render(
        url: String,
        targetWidthPx: Int,
        targetHeightPx: Int,
        renderWidthPx: Int = DEFAULT_RENDER_WIDTH_PX,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
    ): Result {
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
     * Full-page bitmap at [targetWidthPx] wide; the height matches whatever
     * the page actually rendered to (capped at [MAX_FULL_PAGE_HEIGHT_PX] to
     * avoid OOM on infinitely scrolling sites).
     *
     * The trick: we measure the WebView with EXACTLY for width and
     * UNSPECIFIED for height, which lets the WebView grow to fit its
     * content. We then read the resulting measuredHeight and re-layout
     * accordingly before drawing.
     */
    suspend fun renderFullPage(
        url: String,
        targetWidthPx: Int,
        renderWidthPx: Int = DEFAULT_RENDER_WIDTH_PX,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
    ): Result {
        val safeRenderWidth = renderWidthPx.coerceAtMost(MAX_RENDER_DIMENSION_PX)

        return withContext(Dispatchers.Main) {
            withWebView(url, timeoutMillis) { webView ->
                // First measure: a generous initial height so the page has
                // somewhere to lay itself out before we ask for its real
                // content height. Without an initial layout some sites
                // never trigger their reflow.
                webView.measure(
                    View.MeasureSpec.makeMeasureSpec(safeRenderWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(
                        INITIAL_LAYOUT_HEIGHT_PX,
                        View.MeasureSpec.EXACTLY,
                    ),
                )
                webView.layout(0, 0, safeRenderWidth, INITIAL_LAYOUT_HEIGHT_PX)

                // Second measure with UNSPECIFIED on height. WebView responds
                // with its actual content height. contentHeight (in CSS px,
                // scaled by getScale()) is an alternative path but the
                // measure/UNSPECIFIED approach is more reliable across API
                // levels and respects CSS media queries we already triggered.
                webView.measure(
                    View.MeasureSpec.makeMeasureSpec(safeRenderWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                val fullHeight = webView.measuredHeight
                    .coerceAtLeast(INITIAL_LAYOUT_HEIGHT_PX)
                    .coerceAtMost(MAX_FULL_PAGE_HEIGHT_PX)
                webView.layout(0, 0, safeRenderWidth, fullHeight)

                val raw = Bitmap.createBitmap(
                    safeRenderWidth,
                    fullHeight,
                    Bitmap.Config.ARGB_8888,
                )
                webView.draw(Canvas(raw))

                // Scale to widget pixel width while preserving aspect.
                val targetHeightPx = (fullHeight.toLong() * targetWidthPx / safeRenderWidth)
                    .toInt()
                    .coerceAtLeast(1)
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
        // Use the application context — passing an Activity context to a long-
        // lived view leaks the Activity. Passing the worker's context is fine
        // because we destroy() the WebView before returning either way.
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

        // Bigger than a typical phone screen, smaller than infinity. Pages
        // taller than this are simply truncated; in practice it covers any
        // article-style content while keeping the bitmap under ~50 MB at
        // 1024 wide ARGB_8888.
        private const val MAX_FULL_PAGE_HEIGHT_PX = 12_288
        private const val INITIAL_LAYOUT_HEIGHT_PX = 2_048

        private const val TAG = "WebSnapshotRenderer"

        // Recent stable Chrome desktop UA. Worth refreshing periodically so
        // sites don't bucket us as an obsolete browser.
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
