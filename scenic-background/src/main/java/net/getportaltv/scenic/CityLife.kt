package net.getportaltv.scenic

import net.getportaltv.scenic.Colors.rgb
import net.getportaltv.scenic.Colors.smoothstep
import net.getportaltv.scenic.PixelScene.Companion.ROAD_TOP
import net.getportaltv.scenic.PixelScene.Companion.W
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/** People on the pavements of the city: walkers, couples, shoppers with bags, and people standing at the shop windows. */
internal fun PixelScene.drawCityPeople(s: SceneState) {
    val city = s.cityNow()
    if (city < 0.05f) return
    val l = s.look
    val storm = (s.cond.rain * 0.7f + s.cond.snow * 0.5f + s.cond.thunder).coerceIn(0f, 1f)
    if (storm > 0.85f) return
    // Busy by day, quiet at night, except in December when the shops stay open and the streets fill in the evening.
    var crowd = 0.25f + 0.75f * smoothstep(0.1f, 0.7f, l.day)
    crowd = maxOf(crowd, 0.75f * s.season.festive)
    crowd *= 1f - 0.5f * storm
    val rainy = s.cond.rain > 0.2f || s.cond.snow > 0.3f
    val travelV = if (s.travelling) s.speed else 0f
    val night = (1f - l.day * 1.6f).coerceIn(0f, 1f)
    spritePaint.colorFilter = filter(s, 0f, snowy = false)

    // Shoppers and walkers, three streams so the pavement is never empty.
    for (stream in 0..2) for (ev in LifeSchedule.events(s.time, 7.0, 80.0, 0.5f * crowd, 450 + stream * 4, s.boost)) {
        val dir = if (ev.unit(2) < 0.5f) 1 else -1
        val speed = 4.5f + ev.unit(3) * 4f
        val v = dir * speed - travelV
        if (abs(v) < 0.5f) continue
        val age = (s.time - ev.spawn).toFloat()
        val x = if (v < 0) W + 24 + v * age else -24 + v * age
        if (x < -20 || x > W + 20) continue
        val amount = smoothstep(0.3f, 0.9f, s.cityAt(x + s.cam, 1.0))
        if (amount <= 0f) continue
        spritePaint.alpha = (amount * 255).toInt()
        val frame = floor(age * 3.2f).toInt() % 2
        val bottom = ROAD_TOP - (ev.unit(5) * 2).toInt()
        val umbrella = if (rainy) (ev.unit(6) * 4).toInt().coerceIn(0, 3) else -1
        val bag = if (ev.unit(7) < 0.35f) (ev.unit(8) * 4).toInt().coerceIn(0, 3) else -1
        sprite(personSprite((ev.unit(9) * 6).toInt().coerceIn(0, 5), People.skinIndex(ev.unit(10)), People.hairIndex(ev.unit(11)), frame, umbrella, bag = bag), x.toInt(), bottom)
        // Somebody walking with them.
        if (ev.unit(12) < 0.25f) {
            val bag2 = if (ev.unit(13) < 0.3f) (ev.unit(14) * 4).toInt().coerceIn(0, 3) else -1
            sprite(personSprite((ev.unit(15) * 6).toInt().coerceIn(0, 5), People.skinIndex(ev.unit(16)), People.hairIndex(ev.unit(17)), (frame + 1) % 2, umbrella, bag = bag2), x.toInt() + (if (dir > 0) -7 else 7), bottom)
        }
    }

    // People standing at the shop windows or chatting, fixed to the pavement so they move with the street.
    val cell = 76
    val offset = floor(s.cam).toInt()
    val first = Math.floorDiv(offset, cell) - 1
    for (i in first..first + W / cell + 2) {
        if (Noise.unit(i, 860) > 0.3f * crowd) continue
        val wx = i * cell + 6 + (Noise.unit(i, 861) * (cell - 22)).toInt()
        val amount = smoothstep(0.3f, 0.9f, s.cityAt(wx.toDouble(), 1.0))
        if (amount <= 0f) continue
        spritePaint.alpha = (amount * 255).toInt()
        val x = wx - offset
        val bottom = ROAD_TOP - (Noise.unit(i, 862) * 2).toInt()
        val bag = if (Noise.unit(i, 863) < 0.5f) (Noise.unit(i, 864) * 4).toInt().coerceIn(0, 3) else -1
        sprite(personSprite((Noise.unit(i, 865) * 6).toInt().coerceIn(0, 5), People.skinIndex(Noise.unit(i, 866)), People.hairIndex(Noise.unit(i, 867)), 0, if (rainy) (Noise.unit(i, 868) * 4).toInt().coerceIn(0, 3) else -1, bag = bag), x, bottom)
        if (Noise.unit(i, 869) < 0.4f) {
            sprite(personSprite((Noise.unit(i, 870) * 6).toInt().coerceIn(0, 5), People.skinIndex(Noise.unit(i, 871)), People.hairIndex(Noise.unit(i, 872)), 0, if (rainy) (Noise.unit(i, 873) * 4).toInt().coerceIn(0, 3) else -1), x + 7, bottom)
        }
    }
    spritePaint.alpha = 255
    spritePaint.colorFilter = null
}
