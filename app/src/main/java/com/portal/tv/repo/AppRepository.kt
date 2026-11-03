package com.portal.tv.repo

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import com.portal.tv.data.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppRepository(private val context: Context) {

    private companion object {
        /** The system settings apps are reached from Portal's own Settings screen instead. */
        val SYSTEM_SETTINGS_PACKAGES = setOf("com.android.tv.settings", "com.android.settings")
    }

    /**
     * Returns all launchable apps (phone and TV launchers, excluding Portal itself and the system settings app), sorted by label.
     * Runs off the main thread.
     */
    suspend fun getLaunchableApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        // TV apps (Disney+, Pluto TV, Netflix...) often declare only the leanback
        // category, so look for both and keep one entry per package.
        val resolveInfos = listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)
            .flatMap { category ->
                pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(category), 0
                )
            }
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != context.packageName }
            .filter { it.activityInfo.packageName !in SYSTEM_SETTINGS_PACKAGES }

        resolveInfos.mapNotNull { info ->
            val label = info.loadLabel(pm)?.toString() ?: return@mapNotNull null
            val packageName = info.activityInfo.packageName
            return@mapNotNull AppInfo(
                packageName = packageName,
                label = label,
                icon = info.loadIcon(pm),
                isSystem = (info.activityInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            )
        }.sortedBy { it.label.lowercase() }
    }
}
