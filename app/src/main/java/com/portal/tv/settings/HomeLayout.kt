package com.portal.tv.settings

/**
 * What a person can change about the home screen: how big the app icons are, whether the clock and weather show, which
 * corner the clock and weather and the profile, search and settings panel sit in, and whether that panel folds down to
 * one avatar. Pure values and rules, so they can be tested without a screen.
 */
object HomeLayout {

    enum class Corner(val key: String, val label: String, val top: Boolean, val start: Boolean) {
        TOP_LEFT("top_left", "Top left", top = true, start = true),
        TOP_RIGHT("top_right", "Top right", top = true, start = false),
        BOTTOM_LEFT("bottom_left", "Bottom left", top = false, start = true),
        BOTTOM_RIGHT("bottom_right", "Bottom right", top = false, start = false);

        companion object {
            fun fromKey(key: String?, default: Corner): Corner = values().firstOrNull { it.key == key } ?: default
        }
    }

    /** The two groups that can be placed: the time and weather, and the profile, search and settings buttons. */
    enum class Group { CLOCK, ACTIONS }

    val DEFAULT_CLOCK_CORNER = Corner.TOP_LEFT
    val DEFAULT_ACTIONS_CORNER = Corner.TOP_RIGHT

    enum class IconSize(val key: String, val label: String, val scale: Float) {
        SMALL("small", "Small", 0.75f),
        MEDIUM("medium", "Medium", 1f),
        LARGE("large", "Large", 1.25f);

        companion object {
            fun fromKey(key: String?): IconSize = values().firstOrNull { it.key == key } ?: MEDIUM
        }
    }

    /**
     * The groups in [corner], top to bottom. When both are in the same corner they stack, the clock above the buttons, so
     * neither covers the other.
     */
    fun groupsIn(corner: Corner, clockCorner: Corner, actionsCorner: Corner): List<Group> = buildList {
        if (clockCorner == corner) add(Group.CLOCK)
        if (actionsCorner == corner) add(Group.ACTIONS)
    }

    /** How many whole columns of [itemWidthPx] fit in [availablePx]; at least one. */
    fun columns(availablePx: Int, itemWidthPx: Int): Int = maxOf(1, availablePx / maxOf(1, itemWidthPx))

    /** [baseDp] scaled for an icon size, rounded to a whole dp. */
    fun scaled(baseDp: Int, size: IconSize): Int = Math.round(baseDp * size.scale)
}
