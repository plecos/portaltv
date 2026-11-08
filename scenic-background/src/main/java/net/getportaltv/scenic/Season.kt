package net.getportaltv.scenic

import net.getportaltv.scenic.Colors.lerp
import net.getportaltv.scenic.Colors.rgb
import net.getportaltv.scenic.Colors.smoothstep

/**
 * Where the year has got to, as a few smooth amounts that the scenery reads: how much leaf the trees carry, how far autumn colour
 * has gone, the spring blossom, and how wintry it is. They come from the day of the year (turned round in the southern
 * hemisphere), and in the tropics they stay put, since the seasons there do not change the look of the land the way they do
 * further from the equator.
 */
class SeasonState {
    /** How much of its leaf a tree can carry now, 0 (bare) to 1 (full): each tree leafs out and drops its leaves at its own date. */
    var leaf = 1f
    /** How far autumn colour has come, 0 to 1: each tree turns at its own point. */
    var autumn = 0f
    /** Spring blossom, 0 to 1. */
    var bloom = 0f
    /** 0 in the warm months to 1 in deep winter. */
    var winter = 0f
    /** The two colours of the field's stripes (crops, stubble, ploughed earth, dormant grass). */
    var fieldA = rgb(92, 158, 70)
    var fieldB = rgb(80, 142, 62)
    /** What the hills' green is multiplied by: brighter in spring, golden in autumn, dull in winter. */
    var hillR = 1f
    var hillG = 1f
    var hillB = 1f
    /** The day of the year the picture is showing (1 to 366), or 0 in the tropics where it does not matter. */
    var day = 196f
}

object Seasons {
    const val CALENDAR = "Calendar"
    const val SPRING = "Spring"
    const val SUMMER = "Summer"
    const val AUTUMN = "Autumn"
    const val WINTER = "Winter"

    /** The choices in settings, the first being the default. */
    val CHOICES = listOf(CALENDAR, SPRING, SUMMER, AUTUMN, WINTER)

    /** A day of the year in the middle of each season (northern hemisphere), for the fixed choices. */
    fun dayFor(choice: String): Float = when (choice) {
        SPRING -> 112f
        SUMMER -> 196f
        AUTUMN -> 288f
        WINTER -> 20f
        else -> 196f
    }

    private class Key(val day: Float, val a: Int, val b: Int, val r: Float, val g: Float, val bl: Float)

    private val keys = listOf(
        Key(1f, rgb(122, 132, 94), rgb(110, 120, 86), 0.96f, 0.94f, 0.90f),
        Key(75f, rgb(122, 132, 94), rgb(110, 120, 86), 0.97f, 0.96f, 0.92f),
        Key(100f, rgb(100, 172, 76), rgb(86, 156, 66), 1.04f, 1.08f, 0.96f),
        Key(150f, rgb(90, 160, 66), rgb(78, 144, 58), 1.00f, 1.00f, 1.00f),
        Key(200f, rgb(150, 166, 64), rgb(190, 170, 70), 1.02f, 1.00f, 0.94f),
        Key(235f, rgb(190, 170, 72), rgb(214, 184, 80), 1.06f, 1.00f, 0.86f),
        Key(262f, rgb(176, 158, 98), rgb(150, 128, 84), 1.10f, 0.98f, 0.78f),
        Key(290f, rgb(128, 100, 70), rgb(108, 82, 58), 1.20f, 0.94f, 0.68f),
        Key(320f, rgb(122, 118, 86), rgb(108, 104, 78), 1.04f, 0.92f, 0.82f),
        Key(366f, rgb(122, 132, 94), rgb(110, 120, 86), 0.96f, 0.94f, 0.90f)
    )

    /**
     * Works out [out] for [dayOfYear] (1 to 366) at latitude [lat]. When [calendar] is false the day is a chosen season's and
     * is used as it is (no turning round in the south, no tropics).
     */
    fun update(out: SeasonState, dayOfYear: Float, lat: Double, calendar: Boolean) {
        if (calendar && kotlin.math.abs(lat) < TROPICS) {
            // Near the equator the year does not show in the trees and fields.
            out.leaf = 1f; out.autumn = 0f; out.bloom = 0f; out.winter = 0f
            out.fieldA = rgb(90, 160, 66); out.fieldB = rgb(78, 144, 58)
            out.hillR = 1f; out.hillG = 1f; out.hillB = 1f
            out.day = 0f
            return
        }
        var d = dayOfYear
        if (calendar && lat < 0) d = ((d - 1f + 182.5f) % 365f) + 1f
        out.day = d
        out.leaf = smoothstep(100f, 125f, d) * (1f - smoothstep(298f, 325f, d))
        out.autumn = smoothstep(262f, 292f, d)
        out.bloom = smoothstep(85f, 98f, d) * (1f - smoothstep(115f, 135f, d))
        out.winter = if (d < 200f) 1f - smoothstep(40f, 90f, d) else smoothstep(325f, 350f, d)
        var i = 0
        while (i < keys.size - 2 && d > keys[i + 1].day) i++
        val a = keys[i]
        val b = keys[i + 1]
        val t = ((d - a.day) / (b.day - a.day)).coerceIn(0f, 1f)
        out.fieldA = lerp(a.a, b.a, t)
        out.fieldB = lerp(a.b, b.b, t)
        out.hillR = a.r + (b.r - a.r) * t
        out.hillG = a.g + (b.g - a.g) * t
        out.hillB = a.bl + (b.bl - a.bl) * t
    }

    /** Closer to the equator than this the seasons do not change the look of the land. */
    const val TROPICS = 23.5
}
