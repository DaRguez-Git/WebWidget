package com.lonewren.webwidget.widget

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

/**
 * Disk cache for the rendered URL snapshots.
 *
 * Each widget owns a folder `cacheDir/widget_<id>/` containing one PNG per
 * configured URL (`url_0000.png`, `url_0001.png`, ...). The four-digit
 * zero-padded index keeps lexicographic order matching the URL order,
 * which lets the RemoteViewsFactory decode them in card order without
 * any extra metadata.
 *
 * On a successful refresh the worker rewrites the whole folder, so old
 * snapshots from removed URLs are not orphaned.
 */
class SnapshotCache(private val appContext: Context) {

    private fun widgetDir(appWidgetId: Int): File =
        File(appContext.cacheDir, "widget_$appWidgetId")

    private fun snapshotFile(appWidgetId: Int, index: Int): File =
        File(widgetDir(appWidgetId), "url_${"%04d".format(index)}.png")

    /**
     * Rewrites every snapshot atomically-ish: the folder is wiped and then
     * the new bitmaps are written in order. The launcher will be told to
     * call [android.widget.RemoteViewsService.RemoteViewsFactory.onDataSetChanged]
     * after this returns.
     */
    fun writeSnapshots(appWidgetId: Int, bitmaps: List<Bitmap>) {
        val dir = widgetDir(appWidgetId).apply {
            deleteRecursively()
            mkdirs()
        }
        bitmaps.forEachIndexed { index, bitmap ->
            FileOutputStream(File(dir, "url_${"%04d".format(index)}.png")).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
    }

    /** Files in numeric order. Empty if no snapshot has landed yet. */
    fun listSnapshots(appWidgetId: Int): List<File> {
        val dir = widgetDir(appWidgetId)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { _, name -> name.startsWith("url_") && name.endsWith(".png") }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    fun delete(appWidgetId: Int) {
        widgetDir(appWidgetId).deleteRecursively()
    }
}
