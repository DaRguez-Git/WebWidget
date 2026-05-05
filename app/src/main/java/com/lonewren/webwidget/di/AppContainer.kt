package com.lonewren.webwidget.di

import android.content.Context
import com.lonewren.webwidget.data.WidgetPreferences

/**
 * Tiny manual DI container. Held by [com.lonewren.webwidget.WebWidgetApplication]
 * so collaborators are constructed once per process. We deliberately avoid Hilt
 * here: the graph is trivial (one DataStore wrapper + one factory) and a full
 * compiler-driven DI library would dominate APK size for no real benefit.
 *
 * Always pass [appContext] (Context.applicationContext) — never an Activity —
 * to avoid leaking Activity references through long-lived singletons.
 */
class AppContainer(appContext: Context) {

    private val applicationContext: Context = appContext.applicationContext

    val widgetPreferences: WidgetPreferences = WidgetPreferences(applicationContext)
}
