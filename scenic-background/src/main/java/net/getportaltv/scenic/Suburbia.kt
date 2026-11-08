package net.getportaltv.scenic

import net.getportaltv.scenic.Colors.lerp
import net.getportaltv.scenic.Colors.rgb
import net.getportaltv.scenic.Colors.smoothstep
import net.getportaltv.scenic.PixelScene.Companion.FIELD_TOP
import net.getportaltv.scenic.PixelScene.Companion.ROAD_TOP
import net.getportaltv.scenic.PixelScene.Companion.W
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/**
 * Suburbia: lawns and sidewalks along the road, streets of one and two storey houses with front yards, driveways and cars, and now
 * and then a commercial district: a grocery store, chain restaurants and a department store behind a parking lot. It comes right
 * before and right after the city, thinning out into countryside on the far side.
 */

/** Where houses and shops stand (their feet), and the sidewalk in front of them. */
private const val BASE = FIELD_TOP + 14
private const val SIDEWALK = ROAD_TOP - 3
private const val CELL = 56
private const val DISTRICT = 6 * CELL
private const val NEAR = 0.5

private val SIDING = intArrayOf(rgb(238, 232, 214), rgb(214, 196, 160), rgb(170, 196, 214), rgb(176, 200, 168), rgb(222, 176, 150), rgb(200, 204, 208), rgb(236, 212, 120), rgb(170, 100, 84))
private val ROOFS = intArrayOf(rgb(86, 82, 90), rgb(120, 78, 62), rgb(70, 84, 104), rgb(100, 96, 86), rgb(130, 60, 56))
private val DOORS = intArrayOf(rgb(196, 56, 52), rgb(52, 98, 170), rgb(46, 120, 84), rgb(110, 70, 44), rgb(226, 170, 50))
private val SHIRTS = intArrayOf(rgb(230, 90, 80), rgb(70, 150, 230), rgb(250, 210, 80), rgb(110, 200, 130), rgb(220, 120, 200), rgb(240, 240, 240))

private fun SceneState.dark() = (1f - look.day * 1.6f).coerceIn(0f, 1f)

private fun pick(seed: Int, k: Int, size: Int) = (Noise.unit(seed, k, 970) * size).toInt().coerceIn(0, size - 1)

/** Lawn, sidewalk and kerb along the road wherever there is suburbia (fading in and out with it at either end). */
internal fun PixelScene.drawSuburbGround(s: SceneState) {
    if (!s.anySuburb) return
    val haze = s.look.haze * 0.3f
    val lawn = lit(s, rgb(98, 164, 76), 0.06f + haze)
    val mown = lit(s, rgb(112, 178, 88), 0.06f + haze)
    val walk = lit(s, rgb(192, 190, 182), 0.04f)
    val joint = lit(s, rgb(150, 148, 142), 0.04f)
    val kerb = lit(s, rgb(210, 208, 200), 0.04f)
    val cam = floor(s.cam).toInt()
    for (x in 0 until W) {
        val a = s.suburbOnScreen(x)
        if (a <= 0.01f) continue
        fillAlpha(x, FIELD_TOP, x + 1, SIDEWALK, lawn, a)
        // Mown stripes across the lawn.
        var y = FIELD_TOP + 2
        while (y < SIDEWALK) { fillAlpha(x, y, x + 1, y + 1, mown, a * 0.7f); y += 4 }
        fillAlpha(x, SIDEWALK, x + 1, ROAD_TOP, walk, a)
        if (Math.floorMod(x + cam, 10) == 0) fillAlpha(x, SIDEWALK, x + 1, ROAD_TOP, joint, a)
        fillAlpha(x, ROAD_TOP - 1, x + 1, ROAD_TOP, kerb, a)
    }
}

/** Houses and commercial districts along the road, standing back behind their lawns and parking lots. */
internal fun PixelScene.drawSuburbBlocks(s: SceneState) {
    if (s.placeMode != Places.JOURNEY && s.placeMode != Places.SUBURBIA) return
    val offset = floor(s.cam * NEAR).toInt()
    val first = Math.floorDiv(offset, DISTRICT) - 1
    secondRow(s)
    waterTower(s)
    spritePaint.colorFilter = filter(s, 0.04f + s.look.haze * 0.3f)
    for (d in first..first + W / DISTRICT + 2) {
        val x0 = d * DISTRICT - offset
        val commercial = Noise.unit(d, 960) < 0.28f
        if (commercial) commercialDistrict(s, d, x0, offset) else for (k in 0 until 6) {
            val cellX = x0 + k * CELL
            if (cellX + CELL < -10 || cellX > W + 10) continue
            val centre = (d * DISTRICT + k * CELL + CELL / 2).toDouble()
            if (s.suburbAt(centre, NEAR) < 0.5f || s.cityAt(centre, NEAR) > 0.4f) continue
            house(s, d * 6 + k, cellX)
        }
    }
    spritePaint.colorFilter = null
}

// ---------- houses ----------

private fun PixelScene.house(s: SceneState, seed: Int, cellX: Int) {
    val night = s.dark()
    val haze = 0.04f + s.look.haze * 0.3f
    val stories = if (Noise.unit(seed, 1, 970) < 0.55f) 1 else 2
    val wm = 22 + pick(seed, 2, 6)
    val gw = 14
    val garageLeft = Noise.unit(seed, 3, 970) < 0.5f
    val x = cellX + 3
    val mx = if (garageLeft) x + gw else x
    val gx = if (garageLeft) x else x + wm
    val hm = if (stories == 1) 10 else 17
    val siding = solid(s, SIDING[pick(seed, 4, SIDING.size)], haze)
    val trim = lerp(siding, rgb(255, 255, 255), 0.4f)
    val shade = lerp(siding, rgb(10, 10, 30), 0.2f)
    // Snow settles on the roofs: they go white as it lies on them.
    val roof = lit(s, ROOFS[pick(seed, 5, ROOFS.size)], haze)
    val roofLight = lerp(roof, rgb(255, 255, 255), 0.14f)
    val glassDay = lerp(s.look.skyBottom, rgb(56, 76, 106), 0.55f)
    val dark = lerp(glassDay, rgb(10, 12, 24), 0.45f)
    // The walls and the roof over them (stepping in to a ridge).
    fill(mx, BASE - hm, mx + wm, BASE, siding)
    fill(mx + wm - 2, BASE - hm, mx + wm, BASE, shade)
    val rows = if (stories == 1) 6 else 7
    for (r in 0 until rows) {
        val inset = r * (wm / 2 + 2) / rows
        fill(mx - 2 + inset, BASE - hm - 1 - r, mx + wm + 2 - inset, BASE - hm - r, if (r == rows - 1) roofLight else roof)
    }
    // The garage: lower, with a panelled door.
    fill(gx, BASE - 8, gx + gw, BASE, siding)
    fill(gx - 1, BASE - 10, gx + gw + 1, BASE - 8, roof)
    fill(gx + 2, BASE - 7, gx + gw - 2, BASE, lerp(trim, shade, 0.25f))
    var py = BASE - 5
    while (py < BASE) { fill(gx + 2, py, gx + gw - 2, py + 1, shade); py += 2 }
    // A chimney, with smoke on a cold day.
    val chimneyX = mx + (if (garageLeft) wm - 6 else 3)
    fill(chimneyX, BASE - hm - rows - 3, chimneyX + 3, BASE - hm - 2, solid(s, rgb(150, 84, 70), haze))
    if (s.cond.tempC < 8f && Noise.unit(seed, 6, 970) < 0.6f) for (k in 0 until 4) {
        val a = (((s.time * 0.5) + k / 4.0) % 1.0).toFloat()
        fillAlpha(chimneyX + 1 + (sin(s.time + k) * 1.5f).toInt(), BASE - hm - rows - 5 - (a * 9).toInt(), chimneyX + 3 + (a * 2).toInt(), BASE - hm - rows - 3 - (a * 9).toInt(), rgb(214, 214, 220), (1f - a) * 0.55f)
    }
    // The front door with a step, and windows either side of it (more of them upstairs).
    val dx = mx + 3 + (Noise.unit(seed, 7, 970) * (wm - 10)).toInt()
    fill(dx, BASE - 7, dx + 4, BASE, solid(s, DOORS[pick(seed, 8, DOORS.size)], haze))
    fill(dx - 1, BASE - 1, dx + 5, BASE, trim)
    fun window(wx: Int, wy: Int, k: Int) {
        val on = Noise.unit(seed, 20 + k, 970) < 0.65f
        val glass = if (night > 0f) lerp(glassDay, if (on) WARM else dark, night) else glassDay
        fill(wx, wy, wx + 4, wy + 4, glass)
        fill(wx + 2, wy, wx + 3, wy + 4, trim)
        fill(wx - 1, wy, wx, wy + 4, shade)
        fill(wx + 4, wy, wx + 5, wy + 4, shade)
    }
    val lowY = BASE - 8
    for ((k, wx) in intArrayOf(mx + 3, mx + wm - 8).withIndex()) if (abs(wx - dx) > 6) window(wx, lowY, k)
    if (stories == 2) for ((k, wx) in intArrayOf(mx + 3, mx + wm / 2 - 2, mx + wm - 8).withIndex()) window(wx, BASE - 15, 5 + k)
    // Festive lights along the eaves.
    if (s.season.festive > 0.02f && Noise.unit(seed, 9, 970) < 0.7f) {
        var bx = mx; var k = 0
        while (bx < mx + wm) { bulb(s, bx, BASE - hm, seed * 7 + k, night); bx += 3; k++ }
    }
    // Decorations for the season (Christmas, then Halloween).
    decorate(s, seed, mx, wm, dx, gx, gw, garageLeft, night, haze)
    // The driveway widens toward the sidewalk, with a car on it now and then.
    val drive = solid(s, rgb(150, 150, 156), haze)
    for (y in BASE until ROAD_TOP) {
        val extra = (y - BASE) / 3
        fill(gx + 2 - extra, y, gx + gw - 2 + extra, y + 1, drive)
    }
    val hasCar = Noise.unit(seed, 10, 970) < 0.5f
    if (hasCar) sprite(cars[pick(seed, 11, cars.size)], gx + gw / 2 - 7, ROAD_TOP - 2)
    // The yard: a tree or bush beside the house, a mailbox at the kerb, and now and then a fence, a basketball hoop or bins.
    val spare = CELL - (gw + wm) - 3
    val treeX = if (garageLeft) x + gw + wm + 3 else cellX - 1
    val u = Noise.unit(seed, 12, 970)
    val warm = s.cond.tempC > 14f && s.snowCover < 0.1f && s.season.winter < 0.5f
    if (spare >= 12) when {
        u < 0.16f && warm -> pool(s, seed, treeX, night, haze)
        u < 0.28f && warm && s.cond.rain < 0.1f -> trampoline(s, seed, treeX, haze)
        s.season.halloween > 0.3f && u < 0.5f -> pumpkinPatch(s, seed, treeX, night, haze)
        u < 0.58f -> sprite(Sprites.roundTreeYear.at(s.season, seed * 3), treeX, BASE + 3)
        u < 0.82f -> {
            sprite(Sprites.pine, treeX, BASE + 3)
            // A lit tree at Christmas.
            treeLights(s, treeX, BASE + 3, Sprites.pine.width, Sprites.pine.height, seed, night)
        }
        else -> sprite(Sprites.bushYear.at(s.season, seed * 3 + 1), treeX + 1, BASE + 3)
    }
    val mbx = if (garageLeft) gx + gw + 3 else gx - 3
    fill(mbx, SIDEWALK - 4, mbx + 1, SIDEWALK, solid(s, rgb(90, 80, 70), haze))
    fill(mbx - 1, SIDEWALK - 6, mbx + 2, SIDEWALK - 4, solid(s, if (Noise.unit(seed, 13, 970) < 0.5f) rgb(60, 90, 150) else rgb(170, 60, 56), haze))
    val extra = Noise.unit(seed, 14, 970)
    when {
        extra < 0.22f -> {
            // A white picket fence along the front of the house.
            val white = solid(s, rgb(244, 244, 240), haze)
            fill(mx, SIDEWALK - 3, mx + wm, SIDEWALK - 2, white)
            var fx = mx
            while (fx < mx + wm) { fill(fx, SIDEWALK - 4, fx + 1, SIDEWALK - 1, white); fx += 3 }
        }
        extra < 0.34f -> {
            // A basketball hoop at the side of the drive.
            val px = if (garageLeft) gx + gw + 2 else gx - 2
            fill(px, ROAD_TOP - 13, px + 1, ROAD_TOP - 3, solid(s, rgb(90, 94, 104), haze))
            fill(px - 1, ROAD_TOP - 15, px + 3, ROAD_TOP - 12, solid(s, rgb(246, 246, 246), 0.0f))
            fill(px + (if (garageLeft) 3 else -2), ROAD_TOP - 12, px + (if (garageLeft) 5 else 0), ROAD_TOP - 11, solid(s, rgb(230, 110, 40), 0.0f))
        }
        extra < 0.46f -> {
            // Rubbish bins at the kerb.
            val bx = if (garageLeft) gx + gw + 5 else gx - 7
            fill(bx, SIDEWALK - 4, bx + 3, SIDEWALK, solid(s, rgb(60, 110, 76), haze))
            fill(bx + 4, SIDEWALK - 4, bx + 7, SIDEWALK, solid(s, rgb(96, 100, 110), haze))
        }
        extra < 0.58f && s.look.day > 0.5f && s.cond.rain < 0.1f && s.snowCover < 0.1f && s.cond.tempC > 12f -> {
            // A sprinkler throwing water over the lawn.
            val sx = mx + wm / 2
            for (k in 0 until 7) {
                val a = (((s.time * 1.8) + k / 7.0) % 1.0).toFloat()
                val dxs = ((k - 3) * 2f * (0.4f + a)).toInt()
                fillAlpha(sx + dxs, SIDEWALK - 3 - (sin(a * Math.PI).toFloat() * 4f).toInt(), sx + dxs + 1, SIDEWALK - 2 - (sin(a * Math.PI).toFloat() * 4f).toInt(), rgb(190, 225, 255), 0.85f)
            }
        }
    }
    // Now and then somebody mows the lawn.
    if (Noise.unit(seed, 15, 970) < 0.1f && s.look.day > 0.5f && s.cond.rain < 0.1f && s.snowCover < 0.1f && s.cond.tempC > 12f) {
        val run = wm - 6
        val px = mx + 2 + ((s.time * 4.0 + seed * 5) % (2.0 * run)).toInt().let { if (it > run) 2 * run - it else it }
        sprite(personSprite(pick(seed, 16, 6), People.skinIndex(Noise.unit(seed, 17, 970)), People.hairIndex(Noise.unit(seed, 18, 970)), (s.time * 4).toInt() % 2), px, SIDEWALK - 1)
        fill(px + 5, SIDEWALK - 3, px + 9, SIDEWALK - 1, solid(s, rgb(200, 60, 50), haze))
    }
}

// ---------- commercial districts ----------

private const val GROCERY = 0
private const val RESTAURANT = 1
private const val DEPARTMENT = 2
private const val GAS = 3
private val WIDTHS = intArrayOf(100, 46, 120, 66)

private fun PixelScene.commercialDistrict(s: SceneState, d: Int, x0: Int, offset: Int) {
    val centre = (d * DISTRICT + DISTRICT / 2).toDouble()
    if (s.suburbAt(centre, NEAR) < 0.5f || s.cityAt(centre, NEAR) > 0.4f) return
    if (x0 + DISTRICT < -20 || x0 > W + 20) return
    val night = s.dark()
    val haze = 0.04f + s.look.haze * 0.3f
    // The parking lot in front of everything: asphalt with painted lines, light poles and parked cars.
    val asphalt = solid(s, rgb(84, 86, 94), haze)
    val line = solid(s, rgb(214, 214, 206), haze)
    fill(x0, BASE, x0 + DISTRICT, SIDEWALK, asphalt)
    var lx = x0 + 6
    while (lx < x0 + DISTRICT - 6) { fill(lx, BASE + 1, lx + 1, SIDEWALK, line); lx += 17 }
    // Stores, left to right, as many as fit.
    var x = x0 + 8
    var k = 0
    // Where the drive-through queues are, so no parked car stands in them.
    val queues = ArrayList<IntArray>()
    while (k < 5) {
        val kind = pick(d * 10 + k, 1, 4)
        val w = WIDTHS[kind]
        if (x + w > x0 + DISTRICT - 4) break
        when (kind) {
            GROCERY -> grocery(s, d * 10 + k, x, w, night, haze)
            RESTAURANT -> { restaurant(s, d * 10 + k, x, w, night, haze); queues += intArrayOf(x - 80, x + w + 12) }
            DEPARTMENT -> department(s, d * 10 + k, x, w, night, haze)
            else -> gasStation(s, d * 10 + k, x, w, night, haze)
        }
        x += w + 10
        k++
    }
    // Cars in the bays, a light pole at each end.
    var cx = x0 + 8
    var i = 0
    while (cx < x0 + DISTRICT - 20) {
        if (Noise.unit(d, i, 971) < 0.45f && queues.none { cx > it[0] && cx < it[1] }) sprite(cars[pick(d * 40 + i, 2, cars.size)], cx, SIDEWALK + 1)
        cx += 17; i++
    }
    // The cars queueing at each drive-through, creeping up to the window.
    for (q in queues) driveThrough(s, d, q[0] + 80, q[1] - 12 - (q[0] + 80))
    for (end in 0..1) {
        val px = if (end == 0) x0 + 3 else x0 + DISTRICT - 3
        fill(px, BASE - 14, px + 1, SIDEWALK, solid(s, rgb(90, 94, 104), haze))
        fill(px - 2, BASE - 15, px + 3, BASE - 14, solid(s, rgb(90, 94, 104), haze))
        if (night > 0.2f) fillAlpha(px - 3, BASE - 14, px + 4, BASE - 8, rgb(255, 236, 170), night * 0.25f)
    }
}

/** Some pretend lettering: little blocks along a sign, since a few pixels can't spell anything. */
private fun PixelScene.lettering(x: Int, y: Int, w: Int, h: Int, seed: Int, color: Int) {
    var lx = x
    var k = 0
    while (lx < x + w - 2) {
        val lw = 2 + (Noise.unit(seed, k, 972) * 2).toInt()
        if (Noise.unit(seed, k + 50, 972) < 0.85f) fill(lx, y, lx + lw, y + h, color)
        lx += lw + 2
        k++
    }
}

private fun PixelScene.grocery(s: SceneState, seed: Int, x: Int, w: Int, night: Float, haze: Float) {
    val h = 18
    val body = solid(s, rgb(232, 226, 210), haze)
    val shade = lerp(body, rgb(10, 10, 30), 0.2f)
    val green = solid(s, rgb(46, 128, 76), haze)
    val glassDay = lerp(s.look.skyBottom, rgb(56, 76, 106), 0.55f)
    val glass = lerp(glassDay, lerp(WARM, body, 0.3f), night * 0.85f)
    fill(x, BASE - h, x + w, BASE, body)
    fill(x + w - 2, BASE - h, x + w, BASE, shade)
    fill(x - 1, BASE - h - 1, x + w + 1, BASE - h + 1, lerp(body, rgb(90, 90, 100), 0.5f))
    // The sign band with its lettering, and a leaf.
    fill(x + 3, BASE - h + 3, x + w - 3, BASE - h + 9, green)
    lettering(x + 12, BASE - h + 5, w - 24, 2, seed, rgb(244, 244, 240))
    fill(x + 5, BASE - h + 4, x + 8, BASE - h + 8, rgb(150, 220, 120))
    if (night > 0.2f) fillAlpha(x + 3, BASE - h + 3, x + w - 3, BASE - h + 9, rgb(255, 255, 220), night * 0.18f)
    // The glass front with its mullions, and a canopy over the doors.
    fill(x + 3, BASE - 9, x + w - 3, BASE, glass)
    var mx = x + 3
    while (mx < x + w - 3) { fill(mx, BASE - 9, mx + 1, BASE, shade); mx += 9 }
    fill(x + w / 2 - 9, BASE - 11, x + w / 2 + 9, BASE - 9, solid(s, rgb(196, 60, 52), haze))
    fill(x + w / 2 - 6, BASE - 9, x + w / 2 + 6, BASE, lerp(glass, rgb(20, 20, 30), 0.35f))
}

private fun PixelScene.restaurant(s: SceneState, seed: Int, x: Int, w: Int, night: Float, haze: Float) {
    val h = 12
    val body = solid(s, rgb(238, 206, 156), haze)
    val shade = lerp(body, rgb(10, 10, 30), 0.2f)
    val red = lit(s, rgb(200, 50, 44), haze)
    val glassDay = lerp(s.look.skyBottom, rgb(56, 76, 106), 0.55f)
    val glass = lerp(glassDay, WARM, night * 0.85f)
    fill(x, BASE - h, x + w, BASE, body)
    fill(x + w - 2, BASE - h, x + w, BASE, shade)
    // A red roof that slopes in.
    for (r in 0 until 4) fill(x - 1 + r * 2, BASE - h - 1 - r, x + w + 1 - r * 2, BASE - h - r, if (r == 3) lerp(red, rgb(255, 255, 255), 0.15f) else red)
    // Windows, the door, and the drive-through window at the end.
    for (wx in intArrayOf(x + 4, x + 14, x + 24)) { fill(wx, BASE - 9, wx + 6, BASE - 3, glass); fill(wx + 3, BASE - 9, wx + 4, BASE - 3, shade) }
    fill(x + 33, BASE - 8, x + 37, BASE, solid(s, rgb(226, 170, 50), haze))
    fill(x + w - 7, BASE - 8, x + w - 2, BASE - 4, glass)
    fill(x + w - 8, BASE - 4, x + w - 1, BASE - 3, red)
    // The sign on a tall pole beside it, lit at night.
    val px = x + w + 4
    fill(px, BASE - 22, px + 1, BASE, solid(s, rgb(96, 100, 110), haze))
    val sign = lerp(solid(s, rgb(240, 200, 60), haze), rgb(255, 255, 210), night * 0.5f)
    fill(px - 6, BASE - 29, px + 7, BASE - 21, sign)
    fill(px - 6, BASE - 29, px + 7, BASE - 28, red)
    fill(px - 6, BASE - 22, px + 7, BASE - 21, red)
    lettering(px - 4, BASE - 26, 9, 2, seed, red)
    if (night > 0.2f) fillAlpha(px - 8, BASE - 31, px + 9, BASE - 19, rgb(255, 236, 170), night * 0.2f)
}

private fun PixelScene.department(s: SceneState, seed: Int, x: Int, w: Int, night: Float, haze: Float) {
    val h = 22
    val body = solid(s, rgb(206, 198, 186), haze)
    val shade = lerp(body, rgb(10, 10, 30), 0.2f)
    val red = solid(s, rgb(196, 48, 52), haze)
    val glassDay = lerp(s.look.skyBottom, rgb(56, 76, 106), 0.55f)
    val glass = lerp(glassDay, lerp(WARM, body, 0.3f), night * 0.85f)
    fill(x, BASE - h, x + w, BASE, body)
    fill(x + w - 2, BASE - h, x + w, BASE, shade)
    fill(x - 1, BASE - h - 1, x + w + 1, BASE - h + 1, lerp(body, rgb(90, 90, 100), 0.5f))
    // Roof-top units.
    fill(x + 12, BASE - h - 4, x + 20, BASE - h - 1, lerp(body, rgb(150, 150, 158), 0.5f))
    fill(x + w - 28, BASE - h - 4, x + w - 18, BASE - h - 1, lerp(body, rgb(150, 150, 158), 0.5f))
    // A big sign in the middle and banners down the front.
    fill(x + w / 2 - 28, BASE - h + 3, x + w / 2 + 28, BASE - h + 10, red)
    lettering(x + w / 2 - 24, BASE - h + 5, 48, 3, seed, rgb(246, 246, 244))
    if (night > 0.2f) fillAlpha(x + w / 2 - 28, BASE - h + 3, x + w / 2 + 28, BASE - h + 10, rgb(255, 255, 220), night * 0.18f)
    var bx = x + 8
    while (bx < x + w - 10) { if (abs(bx - (x + w / 2)) > 22) fill(bx, BASE - h + 4, bx + 3, BASE - 11, if ((bx / 8) % 2 == 0) solid(s, rgb(52, 98, 170), haze) else red); bx += 14 }
    // The glass front in bays, and the entrance.
    fill(x + 6, BASE - 9, x + w - 6, BASE, glass)
    var gx = x + 6
    while (gx < x + w - 6) { fill(gx, BASE - 9, gx + 1, BASE, shade); gx += 12 }
    fill(x + w / 2 - 10, BASE - 12, x + w / 2 + 10, BASE - 9, shade)
    fill(x + w / 2 - 7, BASE - 9, x + w / 2 + 7, BASE, lerp(glass, rgb(20, 20, 30), 0.35f))
}

// ---------- people ----------

/** A few people on the sidewalks: walkers, joggers, dog walkers and children on bicycles; fewer than the city has. */
internal fun PixelScene.drawSuburbPeople(s: SceneState) {
    if (!s.anySuburb) return
    val l = s.look
    val storm = (s.cond.rain * 0.7f + s.cond.snow * 0.5f + s.cond.thunder).coerceIn(0f, 1f)
    if (storm > 0.7f) return
    val crowd = (0.12f + 0.88f * smoothstep(0.1f, 0.7f, l.day)) * (1f - 0.6f * storm)
    val travelV = if (s.travelling) s.speed else 0f
    val rainy = s.cond.rain > 0.2f || s.cond.snow > 0.3f
    spritePaint.colorFilter = filter(s, 0f, snowy = false)
    for (stream in 0..1) for (ev in LifeSchedule.events(s.time, 16.0, 90.0, 0.55f * crowd, 480 + stream * 4, s.boost)) {
        val dir = if (ev.unit(2) < 0.5f) 1 else -1
        val kind = ev.unit(3)
        // Mostly walkers; some run, some are children on bicycles.
        val speed = if (kind < 0.18f) 16f + ev.unit(4) * 6f else if (kind < 0.34f) 14f + ev.unit(4) * 6f else 4f + ev.unit(4) * 3.5f
        val v = dir * speed - travelV
        if (abs(v) < 0.5f) continue
        val age = (s.time - ev.spawn).toFloat()
        val x = if (v < 0) W + 24 + v * age else -24 + v * age
        if (x < -20 || x > W + 20) continue
        if (s.suburbAt(s.cam - travelV * age + (if (v < 0) W + 24 else -24), 1.0) < 0.6f) continue
        val frame = floor(age * (if (speed > 10f) 5f else 3f)).toInt() % 2
        val bottom = ROAD_TOP - 1
        val skin = People.skinIndex(ev.unit(10))
        val hair = People.hairIndex(ev.unit(11))
        if (kind < 0.18f) {
            // A child on a bicycle.
            sprite(personSprite((ev.unit(9) * 6).toInt().coerceIn(0, 5), skin, hair, frame, bike = true), x.toInt(), bottom, flip = v < 0)
        } else {
            val umbrella = if (rainy) (ev.unit(6) * 4).toInt().coerceIn(0, 3) else -1
            sprite(personSprite((ev.unit(9) * 6).toInt().coerceIn(0, 5), skin, hair, frame, umbrella), x.toInt(), bottom)
            // Some are walking a dog.
            if (kind in 0.34f..0.62f) {
                val dx = x.toInt() + (if (v > 0) 8 else -8)
                sprite(if (frame == 0) LifeSprites.dog else LifeSprites.dogStep, dx, bottom, flip = v < 0)
            }
        }
    }
    spritePaint.colorFilter = null
}

// ---------- the season ----------

internal fun PixelScene.pumpkin(s: SceneState, x: Int, bottom: Int, night: Float, haze: Float) {
    val orange = solid(s, rgb(238, 122, 30), haze)
    fill(x, bottom - 3, x + 4, bottom, orange)
    fill(x + 1, bottom - 4, x + 3, bottom - 3, orange)
    fill(x + 1, bottom - 5, x + 2, bottom - 4, solid(s, rgb(60, 120, 50), haze))
    // A face, glowing at night.
    val face = if (night > 0.2f) lerp(rgb(60, 30, 10), rgb(255, 220, 80), night) else rgb(60, 30, 10)
    fill(x, bottom - 3, x + 1, bottom - 2, face)
    fill(x + 3, bottom - 3, x + 4, bottom - 2, face)
    fill(x + 1, bottom - 1, x + 3, bottom, face)
}

private fun PixelScene.pumpkinPatch(s: SceneState, seed: Int, x: Int, night: Float, haze: Float) {
    for (k in 0 until 4) pumpkin(s, x + k * 3 + (if (k % 2 == 0) 0 else 1), SIDEWALK - 1 - (k % 2), night, haze)
}

private fun PixelScene.ghost(s: SceneState, x: Int, bottom: Int, haze: Float) {
    val white = solid(s, rgb(246, 246, 250), haze)
    val sway = (sin(s.time * 2.0 + x) * 1.0).toInt()
    fill(x + sway, bottom - 8, x + 5 + sway, bottom - 2, white)
    fill(x - 1 + sway, bottom - 7, x + 6 + sway, bottom - 3, white)
    fill(x + 1 + sway, bottom - 6, x + 2 + sway, bottom - 5, rgb(30, 30, 40))
    fill(x + 3 + sway, bottom - 6, x + 4 + sway, bottom - 5, rgb(30, 30, 40))
    fill(x + sway, bottom - 2, x + 1 + sway, bottom - 1, white); fill(x + 2 + sway, bottom - 2, x + 3 + sway, bottom - 1, white); fill(x + 4 + sway, bottom - 2, x + 5 + sway, bottom - 1, white)
}

private fun PixelScene.tombstone(s: SceneState, x: Int, bottom: Int, haze: Float) {
    val stone = solid(s, rgb(156, 160, 168), haze)
    fill(x, bottom - 5, x + 4, bottom, stone)
    fill(x + 1, bottom - 6, x + 3, bottom - 5, stone)
    fill(x + 1, bottom - 3, x + 3, bottom - 2, lerp(stone, rgb(40, 40, 50), 0.5f))
}

private fun PixelScene.snowman(s: SceneState, x: Int, bottom: Int) {
    val white = solid(s, rgb(248, 250, 252), 0.0f)
    fill(x, bottom - 4, x + 5, bottom, white)
    fill(x + 1, bottom - 7, x + 4, bottom - 4, white)
    fill(x + 1, bottom - 10, x + 4, bottom - 7, white)
    fill(x + 1, bottom - 12, x + 4, bottom - 10, rgb(40, 40, 50))
    fill(x + 2, bottom - 8, x + 3, bottom - 7, rgb(240, 120, 30))
    fill(x + 1, bottom - 9, x + 2, bottom - 8, rgb(30, 30, 36)); fill(x + 3, bottom - 9, x + 4, bottom - 8, rgb(30, 30, 36))
}

/** An inflatable Santa, glowing at night. */
private fun PixelScene.santa(s: SceneState, x: Int, bottom: Int, night: Float) {
    val red = lerp(rgb(200, 40, 44), rgb(255, 90, 80), night * 0.5f)
    fill(x, bottom - 6, x + 6, bottom, red)
    fill(x + 1, bottom - 9, x + 5, bottom - 6, rgb(240, 200, 170))
    fill(x + 1, bottom - 7, x + 5, bottom - 6, rgb(250, 250, 250))
    fill(x, bottom - 11, x + 5, bottom - 9, red)
    fill(x + 4, bottom - 12, x + 6, bottom - 11, rgb(250, 250, 250))
    fill(x, bottom - 3, x + 6, bottom - 2, rgb(30, 30, 36))
    if (night > 0.2f) fillAlpha(x - 2, bottom - 12, x + 8, bottom, rgb(255, 120, 100), night * 0.12f)
}

private fun PixelScene.reindeer(s: SceneState, x: Int, bottom: Int, night: Float) {
    val glow = lerp(solid(s, rgb(170, 130, 90), 0.04f), rgb(255, 250, 220), night * 0.7f)
    fill(x, bottom - 4, x + 6, bottom - 2, glow)
    fill(x + 1, bottom - 2, x + 2, bottom, glow); fill(x + 4, bottom - 2, x + 5, bottom, glow)
    fill(x + 5, bottom - 6, x + 7, bottom - 4, glow)
    fill(x + 5, bottom - 8, x + 6, bottom - 6, glow); fill(x + 7, bottom - 8, x + 8, bottom - 6, glow)
    if (night > 0.2f) fillAlpha(x - 1, bottom - 9, x + 9, bottom, rgb(255, 250, 220), night * 0.1f)
}

private fun PixelScene.candyCanes(s: SceneState, x: Int, bottom: Int) {
    for (k in 0 until 3) {
        val cx = x + k * 4
        fill(cx, bottom - 5, cx + 1, bottom, rgb(250, 250, 250))
        fill(cx, bottom - 4, cx + 1, bottom - 3, rgb(220, 40, 44))
        fill(cx, bottom - 2, cx + 1, bottom - 1, rgb(220, 40, 44))
        fill(cx + 1, bottom - 5, cx + 2, bottom - 4, rgb(220, 40, 44))
    }
}

/** Christmas and Halloween around a house: a wreath, a figure or two in the yard, pumpkins on the step, lights along the eaves. */
private fun PixelScene.decorate(s: SceneState, seed: Int, mx: Int, wm: Int, dx: Int, gx: Int, gw: Int, garageLeft: Boolean, night: Float, haze: Float) {
    val xmas = s.season.festive
    val hallo = s.season.halloween
    val front = SIDEWALK - 1
    if (xmas > 0.3f) {
        // A wreath on the door, and one figure in the front yard.
        if (Noise.unit(seed, 30, 970) < 0.6f) {
            fill(dx + 1, BASE - 6, dx + 3, BASE - 4, rgb(40, 130, 60))
            fill(dx + 1, BASE - 5, dx + 2, BASE - 4, rgb(220, 40, 44))
        }
        val yx = if (dx - mx > wm / 2) mx + 4 else mx + wm - 10
        val pickOne = Noise.unit(seed, 31, 970)
        when {
            pickOne < 0.28f -> santa(s, yx, front, night)
            pickOne < 0.52f -> snowman(s, yx, front)
            pickOne < 0.76f -> candyCanes(s, dx - 2, front)
            else -> reindeer(s, yx, front, night)
        }
    }
    if (hallo > 0.3f) {
        // Pumpkins on the step, and a ghost or gravestones on the lawn.
        pumpkin(s, dx - 5, BASE, night, haze)
        pumpkin(s, dx + 6, BASE, night, haze)
        if (Noise.unit(seed, 32, 970) < 0.5f) pumpkin(s, dx + 10, BASE - 1, night, haze)
        val yx = if (dx - mx > wm / 2) mx + 4 else mx + wm - 10
        val pickOne = Noise.unit(seed, 33, 970)
        when {
            pickOne < 0.3f -> ghost(s, yx, front, haze)
            pickOne < 0.6f -> { tombstone(s, yx, front, haze); tombstone(s, yx + 6, front - 1, haze) }
        }
        // Orange lights along the eaves of some.
        if (Noise.unit(seed, 34, 970) < 0.4f) {
            var bx = mx; var k = 0
            val hm = if (Noise.unit(seed, 1, 970) < 0.55f) 10 else 17
            while (bx < mx + wm) {
                fillAlpha(bx, BASE - hm, bx + 1, BASE - hm + 1, if (k % 2 == 0) rgb(255, 140, 30) else rgb(160, 70, 220), 0.6f + 0.4f * night)
                bx += 3; k++
            }
        }
    }
}

// ---------- the yards ----------

/** A swimming pool in the side yard behind a low fence, bright water in summer (and a lounger beside it). */
private fun PixelScene.pool(s: SceneState, seed: Int, x: Int, night: Float, haze: Float) {
    val deck = solid(s, rgb(214, 208, 196), haze)
    val water = lerp(solid(s, rgb(70, 170, 224), haze), rgb(20, 40, 90), night * 0.6f)
    val fence = solid(s, rgb(150, 112, 76), haze)
    fill(x - 1, SIDEWALK - 7, x + 15, SIDEWALK - 1, deck)
    fill(x + 1, SIDEWALK - 6, x + 13, SIDEWALK - 3, water)
    // Light ripples moving over the water.
    for (k in 0 until 3) {
        val rx = x + 2 + ((s.time * 3 + k * 4) % 10).toInt()
        fillAlpha(rx, SIDEWALK - 6 + k, rx + 2, SIDEWALK - 5 + k, rgb(220, 244, 255), 0.5f)
    }
    if (night > 0.3f) fillAlpha(x + 1, SIDEWALK - 6, x + 13, SIDEWALK - 3, rgb(120, 220, 255), night * 0.25f)
    fill(x - 1, SIDEWALK - 9, x + 15, SIDEWALK - 7, fence)
    // A lounger.
    fill(x + 4, SIDEWALK - 2, x + 11, SIDEWALK - 1, solid(s, rgb(250, 250, 246), haze))
}

/** A trampoline with a child bouncing on it by day. */
private fun PixelScene.trampoline(s: SceneState, seed: Int, x: Int, haze: Float) {
    val frame = solid(s, rgb(60, 66, 78), haze)
    val mat = solid(s, rgb(34, 36, 44), haze)
    fill(x, SIDEWALK - 5, x + 12, SIDEWALK - 4, mat)
    fill(x, SIDEWALK - 4, x + 1, SIDEWALK - 1, frame); fill(x + 11, SIDEWALK - 4, x + 12, SIDEWALK - 1, frame)
    // The safety net.
    var nx = x
    while (nx <= x + 11) { fillAlpha(nx, SIDEWALK - 11, nx + 1, SIDEWALK - 5, rgb(200, 204, 210), 0.35f); nx += 3 }
    fill(x, SIDEWALK - 11, x + 12, SIDEWALK - 10, frame)
    if (s.look.day > 0.5f) {
        val jump = (kotlin.math.abs(sin(s.time * 3.0 + seed)) * 5).toInt()
        val skin = solid(s, People.SKIN[People.skinIndex(Noise.unit(seed, 40, 970))], haze)
        fill(x + 5, SIDEWALK - 9 - jump, x + 7, SIDEWALK - 7 - jump, skin)
        fill(x + 5, SIDEWALK - 7 - jump, x + 7, SIDEWALK - 5 - jump, solid(s, SHIRTS[pick(seed, 41, SHIRTS.size)], haze))
    }
}

/** A second street of smaller houses behind the first, hazy with distance. */
private fun PixelScene.secondRow(s: SceneState) {
    val factor = 0.3
    val offset = floor(s.cam * factor).toInt()
    val cell = 30
    val first = Math.floorDiv(offset, cell) - 1
    val haze = 0.24f + s.look.haze * 0.3f
    val base = FIELD_TOP + 5
    for (i in first..first + W / cell + 3) {
        val wx = (i * cell + cell / 2).toDouble()
        if (s.suburbAt(wx, factor) < 0.5f || s.cityAt(wx, factor) > 0.4f) continue
        if (Noise.unit(i, 975) < 0.18f) continue
        val x = i * cell - offset + 3
        val w = 13 + (Noise.unit(i, 976) * 4).toInt()
        val siding = lit(s, SIDING[pick(i, 977, SIDING.size)], haze)
        val roof = lit(s, ROOFS[pick(i, 978, ROOFS.size)], haze)
        fill(x, base - 6, x + w, base, siding)
        for (r in 0 until 4) fill(x - 1 + r * (w / 4), base - 7 - r, x + w + 1 - r * (w / 4), base - 6 - r, roof)
        val glass = lerp(s.look.skyBottom, rgb(56, 76, 106), 0.55f)
        fill(x + 2, base - 4, x + 4, base - 2, glass)
        fill(x + w - 5, base - 4, x + w - 3, base - 2, glass)
        // A tree between some of them.
        if (Noise.unit(i, 979) < 0.5f) {
            val tx = x + w + 2
            fill(tx, base - 6, tx + 5, base - 2, lit(s, rgb(70, 130, 64), haze))
            fill(tx + 2, base - 2, tx + 3, base, lit(s, rgb(96, 70, 50), haze))
        }
    }
}

/** A water tower on a hill behind the houses, with a red light on top at night. */
private fun PixelScene.waterTower(s: SceneState) {
    val factor = 0.2
    val offset = floor(s.cam * factor).toInt()
    val cell = 600
    val first = Math.floorDiv(offset, cell) - 1
    val haze = 0.3f + s.look.haze * 0.3f
    for (i in first..first + W / cell + 2) {
        if (Noise.unit(i, 980) > 0.55f) continue
        val wx = i * cell + 120 + (Noise.unit(i, 981) * 300).toInt()
        if (s.suburbAt(wx.toDouble(), factor) < 0.6f || s.cityAt(wx.toDouble(), factor) > 0.3f) continue
        val x = wx - offset
        if (x < -20 || x > W + 20) continue
        val base = FIELD_TOP - 5
        val legs = lit(s, rgb(120, 124, 134), haze)
        val tank = lit(s, rgb(214, 220, 228), haze)
        val band = lit(s, rgb(196, 60, 56), haze)
        fill(x + 1, base - 12, x + 2, base, legs); fill(x + 9, base - 12, x + 10, base, legs)
        fill(x + 3, base - 12, x + 4, base, legs); fill(x + 7, base - 12, x + 8, base, legs)
        fill(x, base - 22, x + 11, base - 12, tank)
        fill(x, base - 18, x + 11, base - 16, band)
        fill(x + 2, base - 24, x + 9, base - 22, tank)
        fill(x + 4, base - 26, x + 7, base - 24, tank)
        fill(x + 5, base - 29, x + 6, base - 26, legs)
        if (s.dark() > 0.2f && (s.time * 0.9).toInt() % 2 == 0) fillAlpha(x + 4, base - 31, x + 7, base - 28, rgb(255, 60, 50), 0.9f)
    }
}

// ---------- more shops ----------

/** A gas station: pumps under a canopy, a little shop, and a tall price sign. */
private fun PixelScene.gasStation(s: SceneState, seed: Int, x: Int, w: Int, night: Float, haze: Float) {
    val red = lit(s, rgb(206, 52, 46), haze)
    val white = solid(s, rgb(240, 240, 236), haze)
    val post = solid(s, rgb(220, 220, 216), haze)
    val glassDay = lerp(s.look.skyBottom, rgb(56, 76, 106), 0.55f)
    val glass = lerp(glassDay, WARM, night * 0.85f)
    // The canopy on two posts, lit from underneath at night.
    fill(x + 4, BASE - 13, x + 4 + 1, BASE, post); fill(x + 38, BASE - 13, x + 39, BASE, post)
    fill(x, BASE - 17, x + 44, BASE - 13, white)
    fill(x, BASE - 17, x + 44, BASE - 15, red)
    if (s.snowCover > 0.03f) fillAlpha(x, BASE - 18, x + 44, BASE - 17, rgb(238, 242, 250), s.snowCover)
    if (night > 0.2f) fillAlpha(x + 2, BASE - 13, x + 42, BASE, rgb(255, 244, 190), night * 0.18f)
    // The pumps.
    for (px in intArrayOf(x + 12, x + 22, x + 31)) {
        fill(px, BASE - 7, px + 4, BASE, solid(s, rgb(150, 154, 164), haze))
        fill(px, BASE - 8, px + 4, BASE - 6, red)
        fill(px + 1, BASE - 6, px + 3, BASE - 4, glass)
    }
    // A cashier's shop at the end.
    val body = solid(s, rgb(232, 226, 210), haze)
    fill(x + 46, BASE - 11, x + w, BASE, body)
    fill(x + 45, BASE - 12, x + w + 1, BASE - 11, red)
    fill(x + 48, BASE - 8, x + w - 4, BASE - 2, glass)
    fill(x + w - 3, BASE - 8, x + w - 1, BASE, lerp(glass, rgb(20, 20, 30), 0.35f))
    // The price sign on a tall pole: three rows of numbers.
    val px = x - 3
    fill(px, BASE - 20, px + 1, BASE, solid(s, rgb(96, 100, 110), haze))
    fill(px - 5, BASE - 31, px + 6, BASE - 19, solid(s, rgb(34, 40, 52), haze))
    fill(px - 5, BASE - 31, px + 6, BASE - 29, red)
    for (row in 0 until 3) lettering(px - 3, BASE - 28 + row * 3, 8, 2, seed * 3 + row, if (night > 0.2f) rgb(255, 230, 120) else rgb(220, 200, 120))
    if (night > 0.2f) fillAlpha(px - 7, BASE - 33, px + 8, BASE - 17, rgb(255, 236, 170), night * 0.2f)
}

/** Cars queueing at a restaurant's drive-through (from [x] to its window at [x] + [w]): every so often they all move up one place and the first one drives off. */
private fun PixelScene.driveThrough(s: SceneState, seed: Int, x: Int, w: Int) {
    val tt = s.time / 9.0 + (seed % 7) * 0.31
    val c = floor(tt).toInt()
    val p = (tt - c).toFloat()
    val move = smoothstep(0.8f, 1f, p)
    val window = x + w - 16
    for (j in 0..4) {
        if (j == 4 && p < 0.8f) continue
        val cx = window - 17 * j + (17 * move).toInt()
        if (cx < x - 20) continue
        sprite(cars[pick((c + j) * 13 + seed, 3, cars.size)], cx, SIDEWALK + 1)
    }
}
