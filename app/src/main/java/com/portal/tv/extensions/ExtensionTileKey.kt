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
        Capability.BAR -> "top bar items"
        Capability.CLOCK -> "items under the clock"
        else -> capability
    }

    fun status(state: ExtensionState, granted: Set<String>): String = when (state) {
        ExtensionState.NOT_ALLOWED -> "Not allowed"
        ExtensionState.ALLOWED -> "Allowed: " + granted.joinToString(", ") { shortName(it) }.ifEmpty { "nothing" }
        ExtensionState.SIGNER_CHANGED -> "Signed by someone else now. Not allowed"
        ExtensionState.UNSUPPORTED -> "Needs a newer Portal"
        ExtensionState.INVALID -> "Can't be used"
    }

    /** One line for the Settings category: how many are installed and how many allowed. */
    fun summary(found: Int, allowed: Int): String = when {
        found == 0 -> "None installed"
        allowed == 0 -> if (found == 1) "1 found, not allowed" else "$found found, none allowed"
        else -> "$found found, $allowed allowed"
    }
}
