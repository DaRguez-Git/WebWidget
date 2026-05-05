package com.lonewren.webwidget.data

import java.util.concurrent.TimeUnit

/**
 * Allowed refresh cadences for a widget.
 *
 * Android's WorkManager has a hard floor of 15 minutes for periodic work, so
 * that's our shortest option. We expose a fixed enum (rather than a free-form
 * minute count) because the configuration UI is a list of presets and storing
 * an enum name keeps DataStore migrations trivial.
 */
enum class RefreshInterval(val minutes: Long) {
    EVERY_15_MINUTES(15),
    EVERY_30_MINUTES(30),
    EVERY_HOUR(60),
    EVERY_6_HOURS(60 * 6),
    EVERY_24_HOURS(60 * 24);

    fun toMillis(): Long = TimeUnit.MINUTES.toMillis(minutes)

    companion object {
        val DEFAULT: RefreshInterval = EVERY_HOUR

        fun fromName(name: String?): RefreshInterval =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
