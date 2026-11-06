package com.portal.tv.extensions

import android.os.Handler
import android.os.Looper

/** Lets the home screen hear that an extension changed its items, so it can redraw while it is showing. */
object HostEvents {
    @Volatile var onItemsChanged: (() -> Unit)? = null
    @Volatile var onTilesChanged: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())

    fun tilesChanged() {
        handler.post { onTilesChanged?.invoke() }
    }

    fun itemsChanged() {
        handler.post { onItemsChanged?.invoke() }
    }
}
