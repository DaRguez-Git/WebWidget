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
 * Why not just embed a WebView in the widget? RemoteViews (the only thing
 * AppWidgetProvider can publish) supports a small whitelist of view types,
 * and WebView is not on it. The workaround is to build a WebView in our own
 * process, paint it to a Canvas-backed Bitmap, and ship that bitmap to the
 * launcher via setImageViewBitmap.
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
        // Aspect-ratio preserving: the WebView is tall enough to hold the
        // widget aspect at the wide rendering width. Without this the page
        // would be squashed when downscaled.
        val aspect = targetHeightPx.toFloat() / targetWidthPx.toFloat()
        val renderHeightPx = (renderWidthPx * aspect)
            .toInt()
            .coerceIn(MIN_RENDER_DIMENSION_PX, MAX_RENDER_DIMENSION_PX)
        val safeRenderWidth = renderWidthPx.coerceAtMost(MAX_RENDER_DIMENSION_PX)

        // All WebView interaction is forced onto Dispatchers.Main. The Canvas
        // that backs the bitmap *can* be touched from any thread, but only
        // after the WebView's draw() call has completed on the main thread.
        return withContext(Dispatchers.Main) {
            renderOnMain(
                url = url,
                renderWidthPx = safeRenderWidth,
                renderHeightPx = renderHeightPx,
                targetWidthPx = targetWidthPx,
                targetHeightPx = targetHeightPx,
                timeoutMillis = timeoutMillis,
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun renderOnMain(
        url: String,
        renderWidthPx: Int,
        renderHeightPx: Int,
        targetWidthPx: Int,
        targetHeightPx: Int,
        timeoutMillis: Long,
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
            // Without these the wide-viewport trick doesn't work and we get
            // the mobile layout zoomed in.
            useWideViewPort = true
            loadWithOverviewMode = true
            // We don't want to keep cookies/files/db across runs; the worker
            // is supposed to be stateless.
            databaseEnabled = false
            mediaPlaybackRequiresUserGesture = true
            // A desktop UA pushes more sites to send the desktop variant. Some
            // mobile-only sites still detect us via touch/JS, but this gets a
            // better hit rate for visual snapshots.
            userAgentString = DESKTOP_USER_AGENT
        }

        val pageReady = CompletableDeferred<Unit>()
        var loadFailureReason: String? = null

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, finishedUrl: String) {
                // Multiple onPageFinished can fire (about:blank cleanup, then
                // the real URL). Only the deferred completion matters; we
                // gate on whether the URL still matches what we intended.
                if (!pageReady.isCompleted && finishedUrl != "about:blank") {
                    pageReady.complete(Unit)
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                // Only treat the *main frame* error as fatal. Sub-resource
                // failures (favicons, third-party trackers) are common and
                // shouldn't fail the whole snapshot.
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
                // Defensive fallback: some sites (SPAs with client-side
                // routing) never produce an onPageFinished for the final
                // route. If progress hits 100 and we still haven't seen the
                // signal, complete after the settle delay below.
                if (newProgress >= 100 && !pageReady.isCompleted) {
                    pageReady.complete(Unit)
                }
            }
        }

        // Manual measure + layout. WebView is detached, so its width/height
        // are 0 until we tell it the exact size to paint at.
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(renderWidthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(renderHeightPx, View.MeasureSpec.EXACTLY),
        )
        webView.layout(0, 0, renderWidthPx, renderHeightPx)

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

            // Draw on main thread. createBitmap on a 1024x N image is fine
            // memory-wise; we cap dimensions above to avoid OOM on huge widgets.
            val raw = Bitmap.createBitmap(renderWidthPx, renderHeightPx, Bitmap.Config.ARGB_8888)
            webView.draw(Canvas(raw))

            // Downscaling can be expensive; do it off the main thread.
            val scaled = withContext(Dispatchers.Default) {
                Bitmap.createScaledBitmap(raw, targetWidthPx, targetHeightPx, true).also {
                    if (it !== raw) raw.recycle()
                }
            }
            return Result.Success(scaled)
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

        // Recent stable Chrome desktop UA. Worth refreshing periodically so
        // sites don't bucket us as an obsolete browser.
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
