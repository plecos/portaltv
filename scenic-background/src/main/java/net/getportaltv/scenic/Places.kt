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
    const val CITY = "City"
    const val BEACH = "Beach"

    /** The choices in settings, the first being the default. */
    val CHOICES = listOf(JOURNEY, COUNTRYSIDE, FOREST, CITY, BEACH)

    /** The places the journey passes through between stretches of countryside, in turn. */
    private val SPECIALS = listOf(FOREST, CITY, BEACH)

    /** How far one stretch of the journey goes before the next place, in pixels of travel (about two and a half minutes). */
    const val STRETCH = 4500.0
    /** How far the change from one place to the next takes (about a minute), centred on the boundary between them. */
    const val BLEND = 1500.0

    /** Which place the journey is in during [stretch]: countryside, then each of the others in turn with countryside between. */
    fun placeOf(stretch: Int): String =
        if (stretch <= 0 || stretch % 2 == 0) COUNTRYSIDE else SPECIALS[((stretch - 1) / 2) % SPECIALS.size]

    /** How much of [place] there is at [journey] pixels along the road, for the [mode] chosen: 0 to 1. */
    fun amount(place: String, journey: Double, mode: String): Float = when (mode) {
        JOURNEY -> along(journey, STRETCH, BLEND) { stretch -> if (placeOf(stretch) == place) 1f else 0f }
        place -> 1f
        else -> 0f
    }

    fun forest(journey: Double, mode: String): Float = amount(FOREST, journey, mode)
    fun city(journey: Double, mode: String): Float = amount(CITY, journey, mode)
    fun beach(journey: Double, mode: String): Float = amount(BEACH, journey, mode)

    /**
     * How tall the mountains are at [journey] (a position in the range's own world: its x divided by how fast it moves), from 0 (none:
     * the hills run to the horizon) to 1. A range begins with a few small peaks, then more and taller ones, and ends the same way
     * the other way round: the ramp is spread along the land, so it scrolls in from the right and out to the left like everything else.
     */
    fun mountains(journey: Double): Float =
        along(journey, 8000.0, 3500.0) { k -> if (k <= 0 || Noise.unit(k, 618) < 0.65f) 1f else 0f }

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

/** How tall the mountains are at [x] in the world of a range that moves at [factor] of the journey. */
internal fun mountainsAt(x: Double, factor: Double): Float = Places.mountains(x / factor)

/** How much forest there is at a point of a layer that moves at [factor] of the journey, [x] pixels into that layer's world. */
internal fun SceneState.forestAt(x: Double, factor: Double): Float = Places.forest(x / factor, placeMode)

/** The same for the city. */
internal fun SceneState.cityAt(x: Double, factor: Double): Float = Places.city(x / factor, placeMode)

/** How much city there is where the journey is now (for things that cannot be placed by where they are). */
internal fun SceneState.cityNow(): Float = Places.city(cam, placeMode)

/** The same for the beach. */
internal fun SceneState.beachAt(x: Double, factor: Double): Float = Places.beach(x / factor, placeMode)

/** How much beach there is where the journey is now. */
internal fun SceneState.beachNow(): Float = Places.beach(cam, placeMode)

/** How much of the land is built or sand rather than wild country (city or beach): the farms, fences, ponds and wildlife give way to it. */
internal fun SceneState.hardAt(x: Double, factor: Double): Float = maxOf(cityAt(x, factor), beachAt(x, factor))

internal fun SceneState.hardNow(): Float = maxOf(cityNow(), beachNow())
