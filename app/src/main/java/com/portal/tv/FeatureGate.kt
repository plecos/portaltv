package com.portal.tv

import android.content.Context
import com.portal.tv.repo.ProfileManager

object FeatureGate {

    /** True when the active Portal profile is a kid profile. */
    fun isChild(context: Context): Boolean = ProfileManager.isActiveKids(context)

    /** True when the active profile may chat with the AI and the AI server is set up. */
    fun isAiEnabled(context: Context): Boolean {
        val profile = ProfileManager.getActive(context) ?: return false
        return com.portal.tv.search.SearchSettings.level(context, profile).usesAi &&
            com.portal.tv.search.SearchSettings.isAiConfigured(context)
    }
}
