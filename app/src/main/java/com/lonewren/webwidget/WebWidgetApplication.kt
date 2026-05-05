package com.lonewren.webwidget

import android.app.Application
import com.lonewren.webwidget.di.AppContainer

/**
 * Application entry point. Holds the [AppContainer] (manual DI, no Hilt) so
 * collaborators that don't fit cleanly into the Application lifecycle (Worker,
 * BroadcastReceiver) can reach the same DataStore handle.
 *
 * WorkManager is initialised by its default app-startup initialiser. We do
 * not implement [androidx.work.Configuration.Provider] here because we have
 * no need for a custom WorkerFactory — our worker has a (Context,
 * WorkerParameters) constructor and the default factory handles it.
 */
class WebWidgetApplication : Application() {

    val container: AppContainer by lazy { AppContainer(this) }
}
