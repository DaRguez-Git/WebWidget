package com.lonewren.webwidget.widget

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.lonewren.webwidget.data.RefreshInterval
import com.lonewren.webwidget.worker.WebSnapshotWorker
import java.util.concurrent.TimeUnit

/**
 * Schedules the per-widget snapshot work.
 *
 * Two work requests per widget:
 *  - A one-time "kick" request enqueued right after configuration so the user
 *    sees a snapshot within seconds, not after the periodic window.
 *  - A periodic request that handles ongoing refreshes. Periodic work has a
 *    15-minute floor enforced by the framework; anything shorter is silently
 *    rounded up.
 *
 * Both share a unique work name keyed by appWidgetId so re-configuring a
 * widget cleanly replaces its previous schedule via REPLACE policy.
 */
object WidgetScheduler {

    fun schedule(context: Context, appWidgetId: Int, interval: RefreshInterval) {
        val wm = WorkManager.getInstance(context)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val data = workDataOf(WebSnapshotWorker.KEY_WIDGET_ID to appWidgetId)

        val periodic = PeriodicWorkRequestBuilder<WebSnapshotWorker>(
            interval.minutes,
            TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .setInputData(data)
            .build()

        wm.enqueueUniquePeriodicWork(
            periodicWorkName(appWidgetId),
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic,
        )

        val immediate = OneTimeWorkRequestBuilder<WebSnapshotWorker>()
            .setConstraints(constraints)
            .setInputData(data)
            .build()

        wm.enqueueUniqueWork(
            oneShotWorkName(appWidgetId),
            ExistingWorkPolicy.REPLACE,
            immediate,
        )
    }

    fun cancel(context: Context, appWidgetId: Int) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(periodicWorkName(appWidgetId))
        wm.cancelUniqueWork(oneShotWorkName(appWidgetId))
    }

    /**
     * Enqueues an immediate one-shot render. If [urlIndex] is non-negative
     * the worker renders only that URL; otherwise it renders all URLs in
     * the widget. Called from the manual-refresh button on the widget.
     *
     * REPLACE policy: tapping refresh repeatedly cancels in-flight work
     * for the same name and starts the latest one — the user almost
     * always wants the freshest run, not a queue of pending refreshes.
     */
    fun refreshNow(context: Context, appWidgetId: Int, urlIndex: Int = WebSnapshotWorker.ALL_URLS) {
        val wm = WorkManager.getInstance(context)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val data = workDataOf(
            WebSnapshotWorker.KEY_WIDGET_ID to appWidgetId,
            WebSnapshotWorker.KEY_URL_INDEX to urlIndex,
        )
        val request = OneTimeWorkRequestBuilder<WebSnapshotWorker>()
            .setConstraints(constraints)
            .setInputData(data)
            .build()
        wm.enqueueUniqueWork(
            oneShotWorkName(appWidgetId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun periodicWorkName(id: Int) = "web-widget-periodic-$id"
    private fun oneShotWorkName(id: Int) = "web-widget-oneshot-$id"
}
