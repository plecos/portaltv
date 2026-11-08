package net.getportaltv.scenic

import net.getportaltv.scenic.Colors.lerp
import net.getportaltv.scenic.Colors.rgb
import net.getportaltv.scenic.Colors.smoothstep

/**
 * What the light is like right now: sky colours, the tint the scenery takes, the colour of clouds and fog. All of it follows
 * from how high the sun is and what the weather is doing, so sunrise, sunset, dusk, overcast and fog come out of the same
 * numbers and change smoothly together. Reused each frame (nothing is created while drawing).
 */
class Look {
    var skyTop = 0
    var skyBottom = 0
    /** The colour of the glow around a low sun, and how strong it is (0 to 1). */
    var glow = 0
    var glowStrength = 0f
    /** 0 at night, 1 in full day. */
    var day = 0f
    /** How near the sun is to the horizon, 0 to 1 (1 at the horizon): the time of warm light. */
    var twilight = 0f
    var tintR = 1f
    var tintG = 1f
    var tintB = 1f
    var fogColor = 0
    /** Haze over the distant layers, 0 to 1. */
    var haze = 0f
    var cloudLit = 0
    var cloudShade = 0
    var starVisibility = 0f
    /** How dark the weather is, 0 to 1: cloud, rain and snow. */
    var gloom = 0f
    /** How complete the grey overcast is, 0 to 1: rain, snow and storms close the sky over entirely (no blue, sun, moon or stars). */
    var deck = 0f

    private class Key(val alt: Float, val top: Int, val bottom: Int)

    private val keys = listOf(
        Key(-18f, rgb(5, 6, 20), rgb(8, 10, 28)),
        Key(-12f, rgb(10, 14, 40), rgb(26, 30, 64)),
        Key(-6f, rgb(26, 34, 86), rgb(96, 72, 116)),
        Key(-1f, rgb(52, 74, 146), rgb(242, 132, 92)),
        Key(3f, rgb(82, 122, 204), rgb(250, 172, 112)),
        Key(10f, rgb(92, 152, 226), rgb(192, 216, 240)),
        Key(30f, rgb(72, 142, 232), rgb(162, 206, 246)),
        Key(60f, rgb(52, 122, 226), rgb(150, 196, 246))
    )

    fun update(sunAltDeg: Float, c: Conditions, snowCover: Float, flash: Float) {
        // Clear-sky colours from the sun's height.
        var i = 0
        while (i < keys.size - 2 && sunAltDeg > keys[i + 1].alt) i++
        val a = keys[i]
        val b = keys[i + 1]
        val t = ((sunAltDeg - a.alt) / (b.alt - a.alt)).coerceIn(0f, 1f)
        var top = lerp(a.top, b.top, t)
        var bottom = lerp(a.bottom, b.bottom, t)

        day = smoothstep(-7f, 9f, sunAltDeg)
        twilight = (1f - kotlin.math.abs(sunAltDeg + 1f) / 11f).coerceIn(0f, 1f)
        val overcast = (c.cloud * c.cloud).coerceIn(0f, 1f)
        val storm = (c.rain * 0.8f + c.snow * 0.55f + c.thunder * 0.4f).coerceIn(0f, 1f)
        gloom = (overcast * 0.6f + storm * 0.5f).coerceIn(0f, 1f)

        // Cloud and rain turn the sky grey, darker in a storm, and it follows day and night.
        deck = (c.rain * 2f + c.snow * 2f + c.thunder).coerceIn(0f, 1f)
        val grayNight = rgb(46, 48, 54)
        val grayDay = lerp(rgb(166, 174, 186), rgb(92, 98, 110), storm)
        val gray = lerp(grayNight, grayDay, day)
        // Near the horizon the overcast keeps a hint of the sunrise colour.
        top = lerp(top, Colors.scale(gray, 0.9f), maxOf(overcast * 0.9f, deck))
        bottom = lerp(bottom, lerp(gray, bottom, twilight * 0.35f * (1f - deck)), maxOf(overcast * 0.85f, deck))

        // Fog: the sky fades into one flat colour.
        fogColor = lerp(rgb(30, 34, 48), lerp(rgb(196, 204, 214), rgb(240, 170, 130), twilight * 0.5f), day.coerceAtLeast(twilight * 0.6f))
        haze = c.fog.coerceIn(0f, 1f)
        top = lerp(top, fogColor, haze * 0.85f)
        bottom = lerp(bottom, fogColor, haze * 0.95f)

        // Lightning lights the whole sky for a moment.
        if (flash > 0f) {
            top = lerp(top, rgb(236, 240, 255), flash * 0.8f)
            bottom = lerp(bottom, rgb(250, 250, 255), flash * 0.8f)
        }
        skyTop = top
        skyBottom = bottom

        glow = lerp(rgb(255, 120, 70), rgb(255, 190, 120), 1f - twilight)
        glowStrength = (twilight * (1f - overcast * 0.85f) * (1f - haze * 0.5f) * (1f - deck)).coerceIn(0f, 1f)

        // The scenery's tint: dark and blue at night, warm at sunrise and sunset, flatter and darker in overcast.
        val nightR = 0.36f; val nightG = 0.42f; val nightB = 0.68f
        var r = nightR + (1f - nightR) * day
        var g = nightG + (1f - nightG) * day
        var bl = nightB + (1f - nightB) * day
        val warm = twilight * (1f - maxOf(overcast * 0.6f, deck))
        r *= 1f + 0.05f * warm
        g *= 1f - 0.12f * warm
        bl *= 1f - 0.30f * warm
        val dim = 1f - 0.20f * overcast - 0.12f * storm
        tintR = (r * dim).coerceIn(0f, 1.2f)
        tintG = (g * dim).coerceIn(0f, 1.2f)
        tintB = (bl * dim).coerceIn(0f, 1.2f)

        // Clouds are white lit from above by day, orange-pink at sunrise and sunset, blue-grey at night, dark in a storm.
        val grim = (storm * 1.6f + overcast * 0.2f).coerceIn(0f, 1f)
        val litDay = lerp(rgb(255, 255, 255), rgb(104, 110, 126), grim)
        val litTwilight = lerp(litDay, rgb(255, 176, 140), twilight * 0.8f)
        cloudLit = lerp(rgb(70, 78, 112), litTwilight, day.coerceAtLeast(twilight * 0.5f))
        cloudShade = Colors.scale(lerp(cloudLit, rgb(120, 128, 150), 0.5f), 0.72f - 0.12f * grim)

        starVisibility = ((1f - smoothstep(-14f, -3f, sunAltDeg)) * (1f - c.cloud * 0.9f) * (1f - c.fog) * (1f - deck)).coerceIn(0f, 1f)
    }
}
