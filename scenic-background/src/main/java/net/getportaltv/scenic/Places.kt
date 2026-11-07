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

    /** How far one stretch of the journey goes before the next place, in pixels of travel (about three and a half minutes). */
    const val STRETCH = 6000.0
    /** How far the change from one place to the next takes (about a minute), centred on the boundary between them. */
    const val BLEND = 1800.0

    /** How much of the forest there is at [journey] pixels along the road, for the [mode] chosen. */
    fun forest(journey: Double, mode: String): Float = when (mode) {
        FOREST -> 1f
        COUNTRYSIDE -> 0f
        else -> along(journey) { stretch -> if (stretch > 0 && Noise.unit(stretch, 601) < 0.5f) 1f else 0f }
    }

    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** [valueOf] each stretch, run into the next one near each boundary. */
    internal fun along(journey: Double, valueOf: (Int) -> Float): Float {
        val stretch = floor(journey / STRETCH).toInt()
        val into = (journey - stretch * STRETCH)
        val here = valueOf(stretch)
        return when {
            into < BLEND / 2 -> mix(valueOf(stretch - 1), here, smoothstep(0f, 1f, (0.5 + into / BLEND).toFloat()))
            STRETCH - into < BLEND / 2 -> mix(here, valueOf(stretch + 1), smoothstep(0f, 1f, (0.5 - (STRETCH - into) / BLEND).toFloat()))
            else -> here
        }
    }
}

/** How much forest there is at a point of a layer that moves at [factor] of the journey, [x] pixels into that layer's world. */
internal fun SceneState.forestAt(x: Double, factor: Double): Float = Places.forest(x / factor, placeMode)
