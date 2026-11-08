package net.getportaltv.scenic

import net.getportaltv.scenic.Colors.rgb

/**
 * The look of the small people in the scene. Every person gets a skin tone and a hair colour of their own, picked from a wide
 * range from very light to very dark skin, so the world is not full of one kind of person. The choice comes from a stable
 * random number, so someone keeps the same look for as long as they are on screen.
 */
object People {
    /** Eight skin tones, from very light to very dark, equally likely. */
    val SKIN = intArrayOf(
        rgb(250, 220, 192), rgb(238, 196, 160), rgb(222, 170, 126), rgb(196, 142, 98),
        rgb(166, 112, 72), rgb(132, 86, 54), rgb(104, 66, 44), rgb(78, 48, 34)
    )

    /** Hair colours, with how common each is. */
    private val HAIR = intArrayOf(rgb(28, 22, 20), rgb(58, 42, 34), rgb(98, 68, 46), rgb(132, 62, 38), rgb(214, 184, 112), rgb(176, 176, 172))
    private val HAIR_WEIGHT = floatArrayOf(0.36f, 0.28f, 0.16f, 0.05f, 0.09f, 0.06f)

    /** Which of the [SKIN] tones, for a random number [u] from 0 to 1. */
    fun skinIndex(u: Float): Int = (u * SKIN.size).toInt().coerceIn(0, SKIN.size - 1)

    /** Which of the hair colours, for a random number [u] from 0 to 1. */
    fun hairIndex(u: Float): Int {
        var acc = 0f
        for (i in HAIR.indices) { acc += HAIR_WEIGHT[i]; if (u < acc) return i }
        return 0
    }

    fun hair(index: Int): Int = HAIR[index.coerceIn(0, HAIR.size - 1)]
    const val HAIR_COUNT = 6

    /** Skin a little darker, as on somebody who has been lying in the sun. */
    fun tanned(skin: Int): Int = Colors.mul(skin, 0.88f, 0.86f, 0.84f)
}
