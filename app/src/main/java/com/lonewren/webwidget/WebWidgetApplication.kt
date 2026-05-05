package com.lonewren.webwidget

import android.app.Application
import androidx.work.Configuration
import com.lonewren.webwidget.di.AppContainer

/**
 * Application entry point.
 *
 * Two responsibilities:
 *  1. Hold the [AppContainer] (manual DI, no Hilt) so the same DataStore /
 *     WorkManager handles are reused across the process.
 *  2. Provide a [Configuration.Provider]-style WorkManager init. We use the
 *     on-demand initializer pattern (default WorkManager initializer) since we
 *     don't need a custom WorkerFactory yet — workers are constructible from
 *     just (Context, WorkerParameters).
 */
class WebWidgetApplication : Application(), Configuration.Provider {

    val container: AppContainer by lazy { AppContainer(this) }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
