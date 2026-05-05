package com.lonewren.webwidget.widget

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

/**
 * Disk cache for the rendered widget tiles.
 *
 * Each widget owns a folder `cacheDir/widget_<id>/` containing one PNG per
 * tile (`tile_0000.png`, `tile_0001.png`, ...). The four-digit zero-padded
 * index keeps lexicographic order matching numeric order, which lets the
 * RemoteViewsFactory simply `listFiles().sorted()` without a custom
 * comparator.
 *
 * On error the cache is wiped and a single tile is written that is the
 * pre-rendered error placeholder; the widget shows that one row instead of
 * a blank ListView.
 */
class SnapshotCache(private val appContext: Context) {

    private fun widgetDir(appWidgetId: Int): File =
        File(appContext.cacheDir, "widget_$appWidgetId")

    private fun tileFile(appWidgetId: Int, index: Int): File =
        File(widgetDir(appWidgetId), "tile_${"%04d".format(index)}.png")

    /** Replaces all tiles for the given widget atomically-ish. */
    fun writeTiles(appWidgetId: Int, tiles: List<Bitmap>) {
        val dir = widgetDir(appWidgetId).apply {
            // Wipe-then-write: simpler than diffing, and the launcher will
            // call onDataSetChanged afterwards regardless.
            deleteRecursively()
            mkdirs()
        }
        tiles.forEachIndexed { index, bitmap ->
            FileOutputStream(File(dir, "tile_${"%04d".format(index)}.png")).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
    }

    /** Convenience for the error path: a single tile holding the placeholder. */
    fun writeSingleTile(appWidgetId: Int, bitmap: Bitmap) {
        writeTiles(appWidgetId, listOf(bitmap))
    }

    /** Files in numeric order. Empty if no snapshot has landed yet. */
    fun listTiles(appWidgetId: Int): List<File> {
        val dir = widgetDir(appWidgetId)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { _, name -> name.startsWith("tile_") && name.endsWith(".png") }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    fun delete(appWidgetId: Int) {
        widgetDir(appWidgetId).deleteRecursively()
    }
}
