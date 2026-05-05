package com.lonewren.webwidget.di

import android.content.Context
import com.lonewren.webwidget.WebWidgetApplication

/**
 * Convenience accessor used by code that only has a [Context] (Workers,
 * Receivers). Cast through the application instance so we don't have to
 * thread the container through every constructor.
 */
val Context.appContainer: AppContainer
    get() = (applicationContext as WebWidgetApplication).container
