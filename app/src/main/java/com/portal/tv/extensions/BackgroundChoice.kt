package com.portal.tv.extensions

/** The words an extension and Portal use to talk about a home screen background (see docs/EXTENSIONS.md). */
object BackgroundProtocol {
    /** The action Portal binds with; the extension's service answers on a Messenger. */
    const val ACTION = "net.getportaltv.BACKGROUND"

    /** Portal to the extension: draw. [KEY_WIDTH], [KEY_HEIGHT], [KEY_HOST_TOKEN], [KEY_DISPLAY_ID], [KEY_MAX_FPS]. `replyTo` is set. */
    const val MSG_START = 1
    /** Extension to Portal: ready. Carries [KEY_PACKAGE], the picture to show. */
    const val MSG_READY = 2
    /** Portal to the extension: stop and let go of everything. */
    const val MSG_STOP = 3

    const val KEY_WIDTH = "width"
    const val KEY_HEIGHT = "height"
    const val KEY_HOST_TOKEN = "host_token"
    const val KEY_DISPLAY_ID = "display_id"
    const val KEY_MAX_FPS = "max_fps"
    const val KEY_PACKAGE = "surface_package"

    /** Largest picture Portal asks for, in pixels, whatever the screen is. */
    const val MAX_WIDTH = 1920
    const val MAX_HEIGHT = 1080
    /** The most frames a second Portal allows an extension to aim for (an extension picks its own rate, usually 30 or less). It is a request: Portal can't count an extension's frames. */
    const val MAX_FPS = 120
    /** How long Portal waits for an extension to be ready before it gives up and shows its own background. */
    const val READY_TIMEOUT_MS = 5_000L
}

/** What the home screen shows as its background, given what was chosen and what is allowed. */
object BackgroundChoice {

    sealed class Use {
        /** Show [packageName]'s drawing. */
        data class Extension(val packageName: String) : Use()
        /** Show Portal's own background; [reason] says why the extension isn't used. */
        data class Own(val reason: String) : Use()
    }

    const val MODE = "extension"

    /**
     * Decides whether to use an extension's background. [extension] is the one chosen (null if none); [granted] is what it
     * may do right now and [hasService] whether it offers a background that Portal can bind to. A kid profile uses one only if
     * a parent has allowed it for that kid ([kidAllowed]).
     */
    fun resolve(extension: String?, granted: Set<String>, hasService: Boolean, kid: Boolean, kidAllowed: Boolean = false): Use = when {
        kid && !kidAllowed -> Use.Own("A parent hasn't allowed an extension background for this kid.")
        extension == null -> Use.Own("No extension is chosen.")
        Capability.BACKGROUND !in granted -> Use.Own("It isn't allowed to draw the background.")
        !hasService -> Use.Own("It has no background Portal can open.")
        else -> Use.Extension(extension)
    }

    /** The width and height to ask for: the screen's, but never more than [BackgroundProtocol.MAX_WIDTH] by [BackgroundProtocol.MAX_HEIGHT]. */
    fun size(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return BackgroundProtocol.MAX_WIDTH to BackgroundProtocol.MAX_HEIGHT
        val scale = minOf(1f, BackgroundProtocol.MAX_WIDTH.toFloat() / width, BackgroundProtocol.MAX_HEIGHT.toFloat() / height)
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }
}
