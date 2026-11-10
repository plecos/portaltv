package com.portal.tv.data

import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val isSystem: Boolean,
    /** A short mark on the icon (a count, say) an extension put on its tile; null for anything else. */
    val badge: String? = null,
    /** A line under the name that an extension put on its tile. */
    val subtitle: String? = null
)
