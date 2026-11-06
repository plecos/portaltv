package com.portal.tv.extensions

/** An attribute of an XML element: its text (resource references already looked up) and, if it was one, the resource id. */
data class XmlAttr(val text: String, val resId: Int = 0)

/** A small tree made from an XML file, so reading a manifest can be tested without Android. */
data class XmlNode(
    val name: String,
    val attrs: Map<String, XmlAttr> = emptyMap(),
    val children: List<XmlNode> = emptyList()
) {
    fun attr(key: String): String? = attrs[key]?.text?.trim()?.takeIf { it.isNotEmpty() }
}

/** What an extension can ask to be allowed to do. An adult turns each one on separately. */
object Capability {
    const val TILES = "tiles"
    const val SETTINGS = "settings"
    const val REMINDERS = "reminders"
    const val CARDS = "cards"

    /** The capabilities this version of Portal knows, in the order they are shown. */
    val known = listOf(TILES, SETTINGS, REMINDERS, CARDS)

    /** What turning it on means, in plain words. */
    fun describe(capability: String): String = when (capability) {
        TILES -> "Show tiles in your apps"
        SETTINGS -> "Add a settings page"
        REMINDERS -> "Add and change its own reminders"
        CARDS -> "Show cards over other apps"
        else -> "Something this version of Portal doesn't know ($capability)"
    }
}

/** One tile an extension adds to the app grid; [activity] is the full class name of the screen it opens. */
data class ExtensionTile(val id: String, val label: String, val activity: String, val iconRes: Int)

/** What an extension says about itself. */
data class ExtensionManifest(
    val id: String,
    val label: String,
    val description: String,
    val contract: Int,
    /** The capabilities it asks for that this Portal knows, including the ones tiles and a settings page imply. */
    val capabilities: Set<String>,
    /** Capabilities it asks for that this Portal doesn't know; they are never granted. */
    val unknownCapabilities: Set<String>,
    val tiles: List<ExtensionTile>,
    /** Full class name of its settings screen, if it has one. */
    val settingsActivity: String?
)

/**
 * Reads and checks the `portal-extension` XML an extension points to from its manifest. Everything it reads comes from
 * somebody else's app, so every value is checked and bounded, and anything wrong is reported rather than guessed at.
 */
object ManifestParser {

    /** The version of the extension contract this Portal speaks. An extension that needs a higher one can't be used. */
    const val CONTRACT_VERSION = 1

    const val MAX_LABEL = 40
    const val MAX_DESCRIPTION = 200
    const val MAX_TILES = 8
    const val MAX_TILE_LABEL = 24

    private val ID = Regex("[a-z0-9][a-z0-9-]{0,39}")

    sealed class Result {
        data class Ok(val manifest: ExtensionManifest) : Result()
        /** The file is wrong; [reason] says how, for the person looking at the extension. */
        data class Invalid(val reason: String) : Result()
        /** It was written for a newer Portal than this one. */
        data class NeedsNewerPortal(val contract: Int) : Result()
    }

    fun parse(root: XmlNode, packageName: String): Result {
        if (root.name != "portal-extension") return Result.Invalid("The extension file isn't a portal-extension.")
        val contract = root.attr("contract")?.toIntOrNull()?.takeIf { it >= 1 }
            ?: return Result.Invalid("It doesn't say which contract version it uses.")
        if (contract > CONTRACT_VERSION) return Result.NeedsNewerPortal(contract)

        val id = root.attr("id") ?: return Result.Invalid("It has no id.")
        if (!ID.matches(id)) return Result.Invalid("Its id \"$id\" isn't lowercase letters, digits and dashes.")
        val label = root.attr("label") ?: return Result.Invalid("It has no name.")
        if (label.length > MAX_LABEL) return Result.Invalid("Its name is longer than $MAX_LABEL characters.")
        val description = root.attr("description").orEmpty()
        if (description.length > MAX_DESCRIPTION) return Result.Invalid("Its description is longer than $MAX_DESCRIPTION characters.")

        val capabilities = linkedSetOf<String>()
        val unknown = linkedSetOf<String>()
        val tiles = mutableListOf<ExtensionTile>()
        var settings: String? = null

        for (child in root.children) {
            when (child.name) {
                "uses" -> {
                    val c = child.attr("capability") ?: return Result.Invalid("A <uses> line names no capability.")
                    if (c in Capability.known) capabilities += c else unknown += c
                }
                "tile" -> {
                    val tileId = child.attr("id") ?: return Result.Invalid("A tile has no id.")
                    if (!ID.matches(tileId)) return Result.Invalid("The tile id \"$tileId\" isn't lowercase letters, digits and dashes.")
                    if (tiles.any { it.id == tileId }) return Result.Invalid("Two tiles share the id \"$tileId\".")
                    if (tiles.size >= MAX_TILES) return Result.Invalid("It has more than $MAX_TILES tiles.")
                    val tileLabel = child.attr("label") ?: return Result.Invalid("The tile \"$tileId\" has no name.")
                    if (tileLabel.length > MAX_TILE_LABEL) return Result.Invalid("The tile \"$tileId\" has a name longer than $MAX_TILE_LABEL characters.")
                    val activity = child.attr("activity")?.let { className(it, packageName) }
                        ?: return Result.Invalid("The tile \"$tileId\" names no activity.")
                    tiles += ExtensionTile(tileId, tileLabel, activity, child.attrs["icon"]?.resId ?: 0)
                }
                "settings" -> {
                    if (settings != null) return Result.Invalid("It has more than one settings screen.")
                    settings = child.attr("activity")?.let { className(it, packageName) }
                        ?: return Result.Invalid("Its settings screen names no activity.")
                }
                // Anything else is for a newer contract revision or a tool; leave it.
            }
        }
        if (tiles.isNotEmpty()) capabilities += Capability.TILES
        if (settings != null) capabilities += Capability.SETTINGS
        val ordered = Capability.known.filter { it in capabilities }.toCollection(linkedSetOf())
        return Result.Ok(ExtensionManifest(id, label, description, contract, ordered, unknown, tiles, settings))
    }

    /** ".Foo" and "Foo" are inside the extension's own package; a name with a package in it is used as written. */
    internal fun className(name: String, packageName: String): String = when {
        name.startsWith(".") -> packageName + name
        '.' in name -> name
        else -> "$packageName.$name"
    }
}
