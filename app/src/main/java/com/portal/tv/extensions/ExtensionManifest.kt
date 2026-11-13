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
    const val BAR = "bar"
    const val CLOCK = "clock"
    const val BACKGROUND = "background"
    const val WEATHER = "weather"
    const val WEATHER_SOURCE = "weather-source"
    const val WEATHER_PAGE = "weather-page"

    /** The capabilities this version of Portal knows, in the order they are shown. */
    val known = listOf(TILES, SETTINGS, BAR, CLOCK, BACKGROUND, REMINDERS, CARDS, WEATHER, WEATHER_SOURCE, WEATHER_PAGE)

    /** What turning it on means, in plain words. */
    fun describe(capability: String): String = when (capability) {
        TILES -> "Show tiles in your apps"
        SETTINGS -> "Add a settings page"
        BAR -> "Add buttons or text to the top bar, beside your profile, search and settings"
        CLOCK -> "Add buttons or text under the time and weather"
        BACKGROUND -> "Draw the background of your home screen, if you choose it in Settings, Background. It keeps running, and can use the network, while the home screen shows"
        REMINDERS -> "Add and change its own reminders"
        CARDS -> "Show cards over other apps"
        WEATHER -> "Read the current weather and where this TV roughly is (to within about 10 km)"
        WEATHER_SOURCE -> "Be where Portal gets its weather and weather alerts, if you choose it in Settings. Portal tells it where this TV roughly is (to within about 10 km), and which part of the map the radar is showing, and shows what it answers, including alerts"
        WEATHER_PAGE -> "Be the weather page that opens from the home screen, if you choose it in Settings. It can read the full forecast and alerts, and where this TV roughly is (to within about 10 km), including the city name, and which part of the map it looks at for radar"
        else -> "Something this version of Portal doesn't know ($capability)"
    }
}

/** One tile an extension adds to the app grid; [activity] is the full class name of the screen it opens. */
data class ExtensionTile(val id: String, val label: String, val activity: String, val iconRes: Int)

/** Where on the home screen an extension's items go. */
enum class ItemArea(val key: String, val title: String) {
    /** The panel at the top right, with the profile, search and settings buttons. */
    BAR("bar", "top bar"),
    /** Under the time and weather at the top left. */
    CLOCK("clock", "area under the time and weather");

    val capability: String get() = key
}

/**
 * A button or a line of text an extension puts on the home screen. Portal draws it; the extension supplies only the
 * words, an icon and, for a button, the screen it opens ([activity], a full class name; null for text).
 */
data class ExtensionItem(
    val id: String,
    val area: ItemArea,
    val text: String,
    val activity: String?,
    val iconRes: Int
) {
    val isButton: Boolean get() = activity != null
}

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
    val settingsActivity: String?,
    /** The buttons and text it puts on the home screen, in the order it lists them. */
    val items: List<ExtensionItem> = emptyList(),
    /** Full class name of the service that draws its home screen background, if it offers one. */
    val backgroundService: String? = null,
    /** A drawable in its own app that stands for it, or 0 to use the app's icon. */
    val iconRes: Int = 0,
    /** Whether its background asked to be sent left and right presses while it is the screen saver (`screenSaverKeys="true"`). */
    val backgroundKeys: Boolean = false,
    /** Full class name of the service Portal asks for the weather and alerts, if it offers to be a weather source. */
    val weatherService: String? = null,
    /** Full class name of the screen that opens in place of Portal's weather page, if it offers one. */
    val weatherPage: String? = null
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

    /** Most items one extension may put in each area, so a header can't be filled by one app. */
    const val MAX_ITEMS_PER_AREA = 2
    const val MAX_BUTTON_TEXT = 24
    const val MAX_LINE_TEXT = 40

    private val ID = Regex("[a-z0-9][a-z0-9-]{0,39}")

    fun isValidId(id: String) = ID.matches(id)

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
        if (!TextSafety.isPlain(label)) return Result.Invalid("Its name has characters that can't be shown.")
        // A package name that looks like Portal's own is already refused by the registry unless Portal's own key signed it, so a name
        // such as "Portal TV Weather" is only ever shown for one of ours.
        if (!Impersonation.isReservedPackage(packageName)) {
            Impersonation.reason(label)?.let { return Result.Invalid("Its name \"$label\" $it") }
        }
        val description = root.attr("description").orEmpty()
        if (description.length > MAX_DESCRIPTION) return Result.Invalid("Its description is longer than $MAX_DESCRIPTION characters.")
        if (!TextSafety.isPlain(description)) return Result.Invalid("Its description has characters that can't be shown.")

        val capabilities = linkedSetOf<String>()
        val unknown = linkedSetOf<String>()
        val tiles = mutableListOf<ExtensionTile>()
        var settings: String? = null
        val items = mutableListOf<ExtensionItem>()
        var background: String? = null
        var backgroundKeys = false
        var weatherService: String? = null
        var weatherPage: String? = null

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
                    if (!TextSafety.isPlain(tileLabel)) return Result.Invalid("The tile \"$tileId\" has characters in its name that can't be shown.")
                    Impersonation.reason(tileLabel)?.let { return Result.Invalid("The tile \"$tileId\" name \"$tileLabel\" $it") }
                    val activity = child.attr("activity")?.let { className(it, packageName) }
                        ?: return Result.Invalid("The tile \"$tileId\" names no activity.")
                    tiles += ExtensionTile(tileId, tileLabel, activity, child.attrs["icon"]?.resId ?: 0)
                }
                "settings" -> {
                    if (settings != null) return Result.Invalid("It has more than one settings screen.")
                    settings = child.attr("activity")?.let { className(it, packageName) }
                        ?: return Result.Invalid("Its settings screen names no activity.")
                }
                "background" -> {
                    if (background != null) return Result.Invalid("It has more than one background.")
                    background = child.attr("service")?.let { className(it, packageName) }
                        ?: return Result.Invalid("Its background names no service.")
                    backgroundKeys = child.attr("screenSaverKeys") == "true"
                }
                "weather-source" -> {
                    if (weatherService != null) return Result.Invalid("It has more than one weather source.")
                    weatherService = child.attr("service")?.let { className(it, packageName) }
                        ?: return Result.Invalid("Its weather source names no service.")
                }
                "weather-page" -> {
                    if (weatherPage != null) return Result.Invalid("It has more than one weather page.")
                    weatherPage = child.attr("activity")?.let { className(it, packageName) }
                        ?: return Result.Invalid("Its weather page names no activity.")
                }
                "bar-button", "bar-text", "clock-button", "clock-text" -> {
                    val area = if (child.name.startsWith("bar")) ItemArea.BAR else ItemArea.CLOCK
                    val button = child.name.endsWith("button")
                    val shown = "The ${child.name.replace('-', ' ')}"
                    val itemId = child.attr("id") ?: return Result.Invalid("$shown has no id.")
                    if (!ID.matches(itemId)) return Result.Invalid("$shown id \"$itemId\" isn't lowercase letters, digits and dashes.")
                    if (items.any { it.area == area && it.id == itemId }) return Result.Invalid("Two items in the ${area.title} share the id \"$itemId\".")
                    if (items.count { it.area == area } >= MAX_ITEMS_PER_AREA) return Result.Invalid("It has more than $MAX_ITEMS_PER_AREA items for the ${area.title}.")
                    val words = child.attr(if (button) "label" else "text")
                        ?: return Result.Invalid("$shown \"$itemId\" has no ${if (button) "label" else "text"}.")
                    val max = if (button) MAX_BUTTON_TEXT else MAX_LINE_TEXT
                    if (words.length > max) return Result.Invalid("$shown \"$itemId\" is longer than $max characters.")
                    if (!TextSafety.isPlain(words)) return Result.Invalid("$shown \"$itemId\" has characters that can't be shown.")
                    // A button sits among Portal's own, so its words can't pass for one of them.
                    if (button) Impersonation.reason(words)?.let { return Result.Invalid("$shown \"$itemId\" says \"$words\", which $it") }
                    val activity = if (button) {
                        child.attr("activity")?.let { className(it, packageName) }
                            ?: return Result.Invalid("$shown \"$itemId\" names no activity.")
                    } else null
                    items += ExtensionItem(itemId, area, words, activity, if (button) child.attrs["icon"]?.resId ?: 0 else 0)
                }
                // Anything else is for a newer contract revision or a tool; leave it.
            }
        }
        if (tiles.isNotEmpty()) capabilities += Capability.TILES
        if (settings != null) capabilities += Capability.SETTINGS
        items.forEach { capabilities += it.area.capability }
        if (background != null) capabilities += Capability.BACKGROUND
        if (weatherService != null) capabilities += Capability.WEATHER_SOURCE
        if (weatherPage != null) capabilities += Capability.WEATHER_PAGE
        val ordered = Capability.known.filter { it in capabilities }.toCollection(linkedSetOf())
        return Result.Ok(ExtensionManifest(id, label, description, contract, ordered, unknown, tiles, settings, items, background, root.attrs["icon"]?.resId ?: 0, backgroundKeys, weatherService, weatherPage))
    }

    /** ".Foo" and "Foo" are inside the extension's own package; a name with a package in it is used as written. */
    internal fun className(name: String, packageName: String): String = when {
        name.startsWith(".") -> packageName + name
        '.' in name -> name
        else -> "$packageName.$name"
    }
}
