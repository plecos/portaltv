package net.getportaltv.scenic

import net.getportaltv.scenic.Colors.smoothstep
import kotlin.math.floor

/**
 * The places the journey goes through. Every place is an amount from 0 to 1 at each point along the road, and the amounts
 * run into each other slowly, so the land changes gradually: the trees thicken, the farms thin out, the ground darkens.
 *
 * Each piece of scenery asks for the amount at its own place on the road, so it is the same for it every frame and nothing
 * pops in or out as the change passes by.
 */
object Places {
    const val JOURNEY = "Journey"
    const val COUNTRYSIDE = "Countryside"
    const val FOREST = "Forest"

    /** The choices in settings, the first being the default. */
    val CHOICES = listOf(JOURNEY, COUNTRYSIDE, FOREST)

    /** How far one stretch of the journey goes before the next place, in pixels of travel (about two and a half minutes). */
    const val STRETCH = 4500.0
    /** How far the change from one place to the next takes (about a minute), centred on the boundary between them. */
    const val BLEND = 1500.0

    /** How much of the forest there is at [journey] pixels along the road, for the [mode] chosen. */
    fun forest(journey: Double, mode: String): Float = when (mode) {
        FOREST -> 1f
        COUNTRYSIDE -> 0f
        // The countryside and the forest take turns, starting in the countryside.
        else -> along(journey, STRETCH, BLEND) { stretch -> if (stretch > 0 && stretch % 2 != 0) 1f else 0f }
    }

    /** How tall the mountains are, from 0 (none: the hills run to the horizon) to 1, in stretches that come and go in any place. */
    fun mountains(journey: Double): Float =
        along(journey, 5200.0, 1800.0) { k -> if (k <= 0 || Noise.unit(k, 602) < 0.65f) 1f else 0f }

    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** [valueOf] each stretch, run into the next one near each boundary. */
    internal fun along(journey: Double, length: Double, blend: Double, valueOf: (Int) -> Float): Float {
        val stretch = floor(journey / length).toInt()
        val into = (journey - stretch * length)
        val here = valueOf(stretch)
        return when {
            into < blend / 2 -> mix(valueOf(stretch - 1), here, smoothstep(0f, 1f, (0.5 + into / blend).toFloat()))
            length - into < blend / 2 -> mix(here, valueOf(stretch + 1), smoothstep(0f, 1f, (0.5 - (length - into) / blend).toFloat()))
            else -> here
        }
    }
}

/** How much forest there is at a point of a layer that moves at [factor] of the journey, [x] pixels into that layer's world. */
/** How tall the mountains are now. The far ranges move so slowly that they cannot be placed by where they are, so it follows the journey as a whole: the mountains sink and rise together. */
internal fun SceneState.mountainsNow(): Float = Places.mountains(cam)

internal fun SceneState.forestAt(x: Double, factor: Double): Float = Places.forest(x / factor, placeMode)
