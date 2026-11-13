package com.portal.tv.extensions

/**
 * The name Portal uses for an extension's tile wherever it keeps a list of apps (hidden apps, the order of the grid,
 * Frequently Used, the apps a kid may open). It takes the place of a package name, so all of that works unchanged:
 * `portal.ext:<package>/<tile id>`.
 */
object ExtensionTileKey {

    const val PREFIX = "portal.ext:"

    fun of(packageName: String, tileId: String) = "$PREFIX$packageName/$tileId"

    fun isTile(key: String) = parse(key) != null

    /** The package and tile id in [key], or null if it isn't a tile's name. */
    fun parse(key: String): Pair<String, String>? {
        if (!key.startsWith(PREFIX)) return null
        val rest = key.removePrefix(PREFIX)
        val slash = rest.lastIndexOf('/')
        if (slash <= 0 || slash == rest.length - 1) return null
        return rest.substring(0, slash) to rest.substring(slash + 1)
    }
}

/** The words shown for where an extension stands. */
object ExtensionLabels {

    /** What each capability is called in a list; the longer description is [Capability.describe]. */
    fun shortName(capability: String): String = when (capability) {
        Capability.TILES -> "tiles"
        Capability.SETTINGS -> "settings page"
        Capability.REMINDERS -> "reminders"
        Capability.CARDS -> "cards"
        Capability.BACKGROUND -> "background"
        Capability.BAR -> "top bar items"
        Capability.CLOCK -> "items under the clock"
        else -> capability
    }

    /** How much of what an extension asks for has been allowed, for the icon in the list. */
    enum class PermissionLevel {
        /** It asks for things and none are allowed yet. */
        NONE,
        /** Some of what it asks for is allowed, not all. */
        PARTIAL,
        /** Everything it asks for is allowed. */
        ALL,
        /** Nothing to show: it asks for nothing, or it can't be used or has been withdrawn (the list says so itself). */
        NOT_APPLICABLE
    }

    /** [asked] is what it declares and this Portal knows; [granted] what is allowed now (a subset of it). */
    fun permissionLevel(state: ExtensionState, asked: Set<String>, granted: Set<String>): PermissionLevel = when {
        state == ExtensionState.REVOKED || state == ExtensionState.INVALID || state == ExtensionState.UNSUPPORTED -> PermissionLevel.NOT_APPLICABLE
        asked.isEmpty() -> PermissionLevel.NOT_APPLICABLE
        granted.none { it in asked } -> PermissionLevel.NONE
        asked.all { it in granted } -> PermissionLevel.ALL
        else -> PermissionLevel.PARTIAL
    }

    fun status(state: ExtensionState, granted: Set<String>): String = when (state) {
        ExtensionState.NOT_ALLOWED -> "Needs your permission"
        ExtensionState.ALLOWED -> "Allowed: " + granted.joinToString(", ") { shortName(it) }.ifEmpty { "nothing" }
        ExtensionState.SIGNER_CHANGED -> "Signed by someone else now. Needs your permission again"
        ExtensionState.UNSUPPORTED -> "Needs a newer Portal"
        ExtensionState.INVALID -> "Can't be used"
        ExtensionState.REVOKED -> "Withdrawn from the catalog. Switched off"
    }

    /** One line for the Settings category: how many are installed and how many allowed. */
    fun summary(found: Int, allowed: Int, updates: Int = 0): String {
        val base = when {
            found == 0 -> "None installed"
            allowed == 0 -> if (found == 1) "1 found, needs your permission" else "$found found, need your permission"
            else -> "$found found, $allowed allowed"
        }
        return if (updates > 0) "$base · " + (if (updates == 1) "1 update" else "$updates updates") else base
    }
}
