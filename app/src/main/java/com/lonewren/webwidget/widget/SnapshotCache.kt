package com.lonewren.webwidget.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

/**
 * Disk cache for the rendered widget bitmaps.
 *
 * We keep the snapshots on disk (cacheDir) instead of in memory because:
 *  - Workers are killable processes; an in-memory bitmap dies with them and
 *    the widget would briefly go blank between WorkManager runs.
 *  - The configuration Activity wants to display the same snapshot as a
 *    preview; reading it from a file is simpler than coordinating live state.
 *
 * One file per appWidgetId, overwritten on each successful run.
 */
class SnapshotCache(private val appContext: Context) {

    fun fileFor(appWidgetId: Int): File =
        File(appContext.cacheDir, "widget_${appWidgetId}.png")

    fun write(appWidgetId: Int, bitmap: Bitmap) {
        FileOutputStream(fileFor(appWidgetId)).use { out ->
            // PNG is lossless and reasonably small for screenshots of mostly
            // text/UI content. Quality is ignored for PNG but required by API.
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    fun read(appWidgetId: Int): Bitmap? {
        val file = fileFor(appWidgetId)
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    fun delete(appWidgetId: Int) {
        fileFor(appWidgetId).delete()
    }
}
