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
    drawAirport(s)
    // The tower is farther off than the second row of houses, so it is drawn first and they stand in front of its feet.
    waterTower(s)
    secondRow(s)
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
    sidingTexture(mx + 1, BASE - hm + 1, mx + wm - 2, BASE)
    wallFalloff(mx + 1, BASE - hm, mx + wm - 2, BASE)
    val rows = if (stories == 1) 6 else 7
    for (r in 0 until rows) {
        val inset = r * (wm / 2 + 2) / rows
        fill(mx - 2 + inset, BASE - hm - 1 - r, mx + wm + 2 - inset, BASE - hm - r, if (r == rows - 1) roofLight else roof)
        shingleRow(mx - 2 + inset, mx + wm + 2 - inset, BASE - hm - 1 - r, seed + r)
    }
    eaveShadow(mx, mx + wm, BASE - hm)
    // The garage: lower, with a panelled door.
    fill(gx, BASE - 8, gx + gw, BASE, siding)
    fill(gx - 1, BASE - 10, gx + gw + 1, BASE - 8, roof)
    fill(gx + 2, BASE - 7, gx + gw - 2, BASE, lerp(trim, shade, 0.25f))
    var py = BASE - 5
    while (py < BASE) { fill(gx + 2, py, gx + gw - 2, py + 1, shade); py += 2 }
    if (hiRes) {
        // The garage door's row of little windows, and its handle.
        val sc = scale
        var wxp = (gx + 3) * sc
        while (wxp < (gx + gw - 3) * sc - 2) { fillAlphaPhys(wxp, (BASE - 7) * sc + 1, wxp + 3, (BASE - 7) * sc + 3, glassDay, 0.75f); wxp += 5 }
        fillAlphaPhys((gx + gw / 2) * sc, (BASE - 2) * sc, (gx + gw / 2) * sc + 3, (BASE - 2) * sc + 1, rgb(255, 255, 255), 0.5f)
    }
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
        windowDetail(wx, wy, 4, 4, shade, if (night > 0f && on) night else 0f, WARM, s.look.day)
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
    if (hiRes) {
        // A mailbox on the finer grid: a wooden post with a brace, a rounded box with a door seam and handle, a flag, and a shadow.
        val sc = scale
        val boxC = solid(s, if (Noise.unit(seed, 13, 970) < 0.5f) rgb(60, 90, 150) else rgb(170, 60, 56), haze)
        val post = solid(s, rgb(110, 90, 70), haze)
        val white = rgb(255, 255, 255); val ink = rgb(8, 10, 24)
        fillOval(mbx - 1.4f, SIDEWALK - 0.7f, mbx + 2.6f, SIDEWALK + 0.6f, ink, 0.22f)
        fillPhys(mbx * sc, (SIDEWALK - 4) * sc, mbx * sc + 3, SIDEWALK * sc, post)
        fillAlphaPhys(mbx * sc, (SIDEWALK - 4) * sc, mbx * sc + 1, SIDEWALK * sc, white, 0.2f)
        line(mbx + 0.8f, SIDEWALK - 1f, mbx + 2.4f, SIDEWALK - 3.8f, post, 255)
        fillOval(mbx - 1.4f, SIDEWALK - 6.3f, mbx + 2.9f, SIDEWALK - 3.8f, boxC, 1f)
        fillPhys((mbx - 1) * sc, (SIDEWALK - 5) * sc, (mbx + 2) * sc + 2, (SIDEWALK - 4) * sc + 1, boxC)
        fillAlphaPhys((mbx - 1) * sc + 1, (SIDEWALK - 6) * sc, (mbx + 2) * sc, (SIDEWALK - 6) * sc + 1, white, 0.35f)
        fillAlphaPhys((mbx + 2) * sc + 1, (SIDEWALK - 6) * sc, (mbx + 2) * sc + 2, (SIDEWALK - 4) * sc, ink, 0.3f)
        fillPhys((mbx + 2) * sc, (SIDEWALK - 5) * sc, (mbx + 2) * sc + 1, (SIDEWALK - 5) * sc + 1, rgb(230, 226, 214))
        fillPhys((mbx - 1) * sc + 1, (SIDEWALK - 8) * sc, (mbx - 1) * sc + 2, (SIDEWALK - 5) * sc, rgb(214, 60, 52)); fillPhys((mbx - 1) * sc + 1, (SIDEWALK - 8) * sc, (mbx - 1) * sc + 5, (SIDEWALK - 8) * sc + 2, rgb(214, 60, 52))
    } else {
    fill(mbx, SIDEWALK - 4, mbx + 1, SIDEWALK, solid(s, rgb(90, 80, 70), haze))
    fill(mbx - 1, SIDEWALK - 6, mbx + 2, SIDEWALK - 4, solid(s, if (Noise.unit(seed, 13, 970) < 0.5f) rgb(60, 90, 150) else rgb(170, 60, 56), haze))
    }
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
            if (hiRes) {
                // A sprinkler head on a damp patch of lawn, throwing arcs of drops that fall in a spray, with a fine mist and glints.
                val head = rgb(150, 154, 164)
                fillOval(sx - 7f, SIDEWALK - 2.4f, sx + 7f, SIDEWALK - 0.8f, rgb(20, 60, 24), 0.20f)
                fillPhys(sx * scale, (SIDEWALK - 2) * scale, sx * scale + 3, (SIDEWALK - 1) * scale, head)
                fillOval(sx - 0.2f, SIDEWALK - 2.6f, sx + 1.8f, SIDEWALK - 1.8f, Colors.mul(head, 1.2f, 1.2f, 1.2f), 1f)
                for (k in 0 until 7) for (d in 0 until 7) {
                    val a = ((((s.time * 1.8) + k / 7.0) % 1.0) - d * 0.03).toFloat().let { if (it < 0f) it + 1f else it }
                    val dxs = (k - 3) * 2f * (0.4f + a)
                    val yy = SIDEWALK - 3f - sin(a * Math.PI).toFloat() * 4f
                    fillOval(sx + dxs, yy, sx + dxs + 0.9f, yy + 0.9f, rgb(190, 225, 255), (0.85f - d * 0.11f).coerceAtLeast(0.1f))
                }
                fillOval(sx - 7f, SIDEWALK - 5.5f, sx + 8f, SIDEWALK - 1.2f, rgb(210, 235, 255), 0.07f)
            } else
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
internal fun PixelScene.lettering(x: Int, y: Int, w: Int, h: Int, seed: Int, color: Int) {
    if (hiRes) {
        // Little glyphs of strokes on the finer grid (three real pixels wide, a gap between) that read as writing, and spaces between words.
        val sc = scale
        val top = y * sc
        val hp = (h * sc).coerceAtLeast(4)
        var gx = x * sc
        var g = 0
        while (gx < (x + w) * sc - 3) {
            if (Noise.unit(seed, g, 975) < 0.18f) { gx += 4; g++; continue }
            val bits = Noise.hash(seed, g, 311)
            val mid = top + hp / 2
            if (bits and 1 != 0) fillPhys(gx, top, gx + 3, top + 1, color)
            if (bits and 2 != 0) fillPhys(gx, top + hp - 1, gx + 3, top + hp, color)
            if (bits and 4 != 0) fillPhys(gx, top, gx + 1, top + hp, color)
            if (bits and 8 != 0) fillPhys(gx + 2, top, gx + 3, top + hp, color)
            if (bits and 16 != 0) fillPhys(gx, mid, gx + 3, mid + 1, color)
            if (bits and 31 == 0) fillPhys(gx + 1, top, gx + 2, top + hp, color)
            gx += 5
            g++
        }
        return
    }
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
    storefrontGlints(x + 3, BASE - 9, x + w - 3, BASE, s.look.day * (1f - night))
    wallFalloff(x + 1, BASE - h + 1, x + w - 2, BASE)
    eaveShadow(x + 3, x + w - 3, BASE - h + 9)
    var mx = x + 3
    while (mx < x + w - 3) { fill(mx, BASE - 9, mx + 1, BASE, shade); mx += 9 }
    fill(x + w / 2 - 9, BASE - 11, x + w / 2 + 9, BASE - 9, solid(s, rgb(196, 60, 52), haze))
    fill(x + w / 2 - 6, BASE - 9, x + w / 2 + 6, BASE, lerp(glass, rgb(20, 20, 30), 0.35f))
    if (hiRes) {
        val sc = scale
        val white = rgb(255, 255, 255); val ink = rgb(8, 10, 24)
        // The sign band framed in white, a proper leaf with a vein, and the roof edge lit with a shadow below.
        fillAlphaPhys((x + 3) * sc, (BASE - h + 3) * sc, (x + w - 3) * sc, (BASE - h + 3) * sc + 1, white, 0.45f)
        fillAlphaPhys((x + 3) * sc, (BASE - h + 9) * sc - 1, (x + w - 3) * sc, (BASE - h + 9) * sc, ink, 0.35f)
        fillOval(x + 5f, BASE - h + 4f, x + 8.6f, BASE - h + 8f, rgb(150, 220, 120), 1f)
        line(x + 5.4f, BASE - h + 7.6f, x + 8.2f, BASE - h + 4.4f, green, 255)
        fillAlphaPhys((x - 1) * sc, (BASE - h - 1) * sc, (x + w + 1) * sc, (BASE - h - 1) * sc + 1, white, 0.3f)
        eaveShadow(x + 1, x + w - 2, BASE - h + 1)
        // The canopy over the doors with a scalloped edge, and double doors with handles.
        val red = solid(s, rgb(196, 60, 52), haze)
        var cx = x + w / 2 - 9
        while (cx < x + w / 2 + 9) { fillOval(cx.toFloat(), BASE - 9.7f, cx + 3f, BASE - 8.3f, red, 1f); cx += 3 }
        eaveShadow(x + w / 2 - 9, x + w / 2 + 9, BASE - 8)
        val doorX = (x + w / 2) * sc
        fillAlphaPhys(doorX - 1, (BASE - 9) * sc + 2, doorX + 1, BASE * sc, white, 0.35f)
        fillPhys(doorX - 4, (BASE - 4) * sc, doorX - 3, (BASE - 2) * sc, rgb(230, 230, 236)); fillPhys(doorX + 3, (BASE - 4) * sc, doorX + 4, (BASE - 2) * sc, rgb(230, 230, 236))
    }
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
    for (wx in intArrayOf(x + 4, x + 14, x + 24)) { fill(wx, BASE - 9, wx + 6, BASE - 3, glass); fill(wx + 3, BASE - 9, wx + 4, BASE - 3, shade); windowDetail(wx, BASE - 9, 6, 6, shade, night, WARM, s.look.day) }
    wallFalloff(x + 1, BASE - h, x + w - 2, BASE)
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
    if (hiRes) {
        val sc = scale
        val white = rgb(255, 255, 255); val ink = rgb(8, 10, 24)
        // Shingles on the red roof, a door with a window and handle, and bulbs chasing round the sign.
        for (r in 0 until 4) shingleRow(x - 1 + r * 2, x + w + 1 - r * 2, BASE - h - 1 - r, seed + r)
        eaveShadow(x, x + w, BASE - h)
        wallFalloff(x + 1, BASE - h, x + w - 2, BASE)
        fillAlphaPhys((x + 34) * sc, (BASE - 7) * sc, (x + 36) * sc, (BASE - 4) * sc, glass, 0.85f)
        fillPhys((x + 36) * sc - 2, (BASE - 3) * sc, (x + 36) * sc - 1, (BASE - 2) * sc, rgb(255, 244, 190))
        sidingTexture(x + 1, BASE - 3, x + w - 2, BASE)
        var bx = px - 6
        while (bx <= px + 6) {
            val on = ((s.time * 3).toInt() + bx) % 2 == 0
            val c = if (night > 0.2f && on) rgb(255, 236, 160) else rgb(236, 226, 196)
            fillOval(bx.toFloat(), BASE - 29.6f, bx + 1.2f, BASE - 28.4f, c, 1f); fillOval(bx.toFloat(), BASE - 21.6f, bx + 1.2f, BASE - 20.4f, c, 1f)
            bx += 2
        }
        fillAlphaPhys((px - 6) * sc, (BASE - 28) * sc, (px - 6) * sc + 1, (BASE - 21) * sc, white, 0.3f)
        fillAlphaPhys((px + 7) * sc - 1, (BASE - 28) * sc, (px + 7) * sc, (BASE - 21) * sc, ink, 0.3f)
    }
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
    storefrontGlints(x + 6, BASE - 9, x + w - 6, BASE, s.look.day * (1f - night))
    wallFalloff(x + 1, BASE - h + 1, x + w - 2, BASE)
    eaveShadow(x + 6, x + w - 6, BASE - 12)
    var gx = x + 6
    while (gx < x + w - 6) { fill(gx, BASE - 9, gx + 1, BASE, shade); gx += 12 }
    fill(x + w / 2 - 10, BASE - 12, x + w / 2 + 10, BASE - 9, shade)
    fill(x + w / 2 - 7, BASE - 9, x + w / 2 + 7, BASE, lerp(glass, rgb(20, 20, 30), 0.35f))
    if (hiRes) {
        val sc = scale
        val white = rgb(255, 255, 255); val ink = rgb(8, 10, 24)
        // Texture and light on the wall, a framed sign with a bulb border, banners with a pattern, vents on the roof units, revolving doors.
        wallFalloff(x + 1, BASE - h + 1, x + w - 2, BASE)
        eaveShadow(x, x + w, BASE - h + 1)
        sidingTexture(x + 1, BASE - h + 10, x + w - 2, BASE - 10)
        fillAlphaPhys((x + w / 2 - 28) * sc, (BASE - h + 3) * sc, (x + w / 2 + 28) * sc, (BASE - h + 3) * sc + 1, white, 0.45f)
        fillAlphaPhys((x + w / 2 - 28) * sc, (BASE - h + 10) * sc - 1, (x + w / 2 + 28) * sc, (BASE - h + 10) * sc, ink, 0.35f)
        var bb = x + w / 2 - 27
        while (bb < x + w / 2 + 27) {
            val on = ((s.time * 2).toInt() + bb) % 2 == 0
            val c = if (night > 0.2f && on) rgb(255, 236, 160) else rgb(244, 232, 196)
            fillOval(bb.toFloat(), BASE - h + 3.2f, bb + 1.2f, BASE - h + 4.4f, c, 1f); fillOval(bb.toFloat(), BASE - h + 8.6f, bb + 1.2f, BASE - h + 9.8f, c, 1f)
            bb += 3
        }
        var bx = x + 8
        while (bx < x + w - 10) {
            if (abs(bx - (x + w / 2)) > 22) {
                for (yy in (BASE - h + 4) * sc until (BASE - 11) * sc step 6) fillAlphaPhys(bx * sc + 1, yy, bx * sc + 5, yy + 2, white, 0.30f)
                fillAlphaPhys(bx * sc, (BASE - h + 4) * sc, bx * sc + 1, (BASE - 11) * sc, white, 0.25f)
            }
            bx += 14
        }
        for (ux in intArrayOf(x + 12, x + w - 28)) for (vx in 0 until 8 step 2) fillAlphaPhys((ux + vx) * sc, (BASE - h - 3) * sc, (ux + vx) * sc + 1, (BASE - h - 1) * sc, ink, 0.4f)
        val dx = (x + w / 2) * sc
        fillAlphaPhys(dx - 1, (BASE - 9) * sc, dx + 1, BASE * sc, white, 0.35f)
        fillAlphaPhys(dx - 7 * sc, (BASE - 9) * sc, dx - 7 * sc + 1, BASE * sc, white, 0.25f)
        fillAlphaPhys(dx + 7 * sc - 1, (BASE - 9) * sc, dx + 7 * sc, BASE * sc, white, 0.25f)
    }
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
    if (hiRes) {
        // A pool on the finer grid: a tiled deck with a pale coping, water that is deeper and darker toward the far side, caustic
        // lines and glints that drift over it, a chrome ladder, a lounger with a cushion, and a picket fence behind.
        val sc = scale
        val white = rgb(255, 255, 255); val ink = rgb(8, 10, 24)
        fillPhys((x - 1) * sc, (SIDEWALK - 7) * sc, (x + 15) * sc, (SIDEWALK - 1) * sc, deck)
        var ty = (SIDEWALK - 7) * sc
        while (ty < (SIDEWALK - 1) * sc) { fillAlphaPhys((x - 1) * sc, ty, (x + 15) * sc, ty + 1, ink, 0.10f); ty += 4 }
        var tx = (x - 1) * sc
        while (tx < (x + 15) * sc) { fillAlphaPhys(tx, (SIDEWALK - 7) * sc, tx + 1, (SIDEWALK - 1) * sc, ink, 0.07f); tx += 6 }
        val shallow = lerp(solid(s, rgb(96, 200, 236), haze), rgb(30, 60, 110), night * 0.6f)
        val wx0 = (x + 1) * sc; val wx1 = (x + 13) * sc; val wy0 = (SIDEWALK - 6) * sc; val wy1 = (SIDEWALK - 3) * sc
        for (r in 0 until wy1 - wy0) fillPhys(wx0, wy0 + r, wx1, wy0 + r + 1, lerp(water, shallow, r / (wy1 - wy0 - 1f)))
        fillAlphaPhys(wx0 - 1, wy0 - 1, wx1 + 1, wy0, white, 0.55f)
        fillAlphaPhys(wx0 - 1, wy1, wx1 + 1, wy1 + 1, white, 0.35f)
        fillAlphaPhys(wx0, wy0, wx1, wy0 + 2, ink, 0.22f)
        for (k in 0 until 6) {
            val rx = wx0 + 3 + (((s.time * 5 + k * 5) % 20).toInt())
            val ry = wy0 + 1 + (k % 3) * 2 + (sin(s.time * 2 + k).toFloat() * 0.8f).toInt()
            if (rx + 4 < wx1) { fillAlphaPhys(rx, ry, rx + 4, ry + 1, rgb(230, 248, 255), 0.5f); fillAlphaPhys(rx + 1, ry + 1, rx + 3, ry + 2, rgb(230, 248, 255), 0.25f) }
        }
        for (g in 0 until 5) if (Noise.unit(g, (s.time * 4).toInt(), seed + 977) < 0.5f) { val gx = wx0 + 2 + (Noise.unit(g, seed, 978) * (wx1 - wx0 - 4)).toInt(); val gy = wy0 + 1 + (Noise.unit(g, seed, 979) * (wy1 - wy0 - 2)).toInt(); fillAlphaPhys(gx, gy, gx + 1, gy + 1, white, 0.8f) }
        if (night > 0.3f) fillOval(x + 1f, SIDEWALK - 6.4f, x + 13f, SIDEWALK - 2.6f, rgb(120, 220, 255), night * 0.30f)
        line(x + 12.2f, SIDEWALK - 6.4f, x + 12.2f, SIDEWALK - 3.4f, rgb(214, 218, 226)); line(x + 11.2f, SIDEWALK - 6.4f, x + 11.2f, SIDEWALK - 3.4f, rgb(214, 218, 226)); line(x + 11.2f, SIDEWALK - 6.4f, x + 12.2f, SIDEWALK - 6.4f, rgb(214, 218, 226))
        // The picket fence behind.
        fillPhys((x - 1) * sc, (SIDEWALK - 8) * sc, (x + 15) * sc, (SIDEWALK - 8) * sc + 2, fence)
        var pk = (x - 1) * sc
        while (pk < (x + 15) * sc) { fillPhys(pk, (SIDEWALK - 9) * sc, pk + 2, (SIDEWALK - 7) * sc, fence); fillAlphaPhys(pk, (SIDEWALK - 9) * sc, pk + 1, (SIDEWALK - 7) * sc, white, 0.2f); fillAlphaPhys(pk, (SIDEWALK - 9) * sc, pk + 2, (SIDEWALK - 9) * sc + 1, white, 0.3f); pk += 4 }
        // A lounger: a frame, a cushion with a stripe, and a raised back.
        val cush = solid(s, rgb(250, 250, 246), haze)
        fillPhys((x + 4) * sc, (SIDEWALK - 2) * sc, (x + 11) * sc, (SIDEWALK - 2) * sc + 3, cush)
        fillPhys((x + 4) * sc, (SIDEWALK - 2) * sc + 1, (x + 11) * sc, (SIDEWALK - 2) * sc + 2, solid(s, rgb(80, 150, 220), haze))
        line(x + 4f, SIDEWALK - 2f, x + 5.4f, SIDEWALK - 3.8f, cush)
        fillPhys((x + 4) * sc, (SIDEWALK - 1) * sc, (x + 4) * sc + 1, SIDEWALK * sc, fence); fillPhys((x + 11) * sc - 1, (SIDEWALK - 1) * sc, (x + 11) * sc, SIDEWALK * sc, fence)
        return
    }
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
    if (hiRes) {
        // A trampoline on the finer grid: a round mat seen at a slant, a padded rim with springs, splayed legs, a safety net of poles and
        // mesh, and a child bouncing with arms up and legs tucked.
        val sc = scale
        val white = rgb(255, 255, 255); val ink = rgb(8, 10, 24)
        val pad = solid(s, rgb(60, 110, 200), haze)
        fillOval(x - 1f, SIDEWALK - 0.8f, x + 13f, SIDEWALK + 0.8f, ink, 0.25f)
        line(x + 1.5f, SIDEWALK - 4f, x + 0.3f, SIDEWALK - 0.2f, frame); line(x + 10.5f, SIDEWALK - 4f, x + 11.7f, SIDEWALK - 0.2f, frame); line(x + 4f, SIDEWALK - 4.2f, x + 3.6f, SIDEWALK - 0.4f, frame, 200); line(x + 8f, SIDEWALK - 4.2f, x + 8.4f, SIDEWALK - 0.4f, frame, 200)
        fillOval(x - 0.2f, SIDEWALK - 5.6f, x + 12.2f, SIDEWALK - 3.4f, pad, 1f)
        fillOval(x + 0.5f, SIDEWALK - 5.3f, x + 11.5f, SIDEWALK - 3.8f, mat, 1f)
        fillAlphaPhys((x + 1) * sc, (SIDEWALK - 5) * sc, (x + 6) * sc, (SIDEWALK - 5) * sc + 1, white, 0.18f)
        for (k in 0 until 9) { val a = k * Math.PI / 8; fillAlphaPhys(((x + 6f + kotlin.math.cos(a).toFloat() * 5.4f) * sc).toInt(), ((SIDEWALK - 4.5f + sin(a).toFloat() * 0.7f) * sc).toInt(), ((x + 6f + kotlin.math.cos(a).toFloat() * 5.4f) * sc).toInt() + 1, ((SIDEWALK - 4.5f + sin(a).toFloat() * 0.7f) * sc).toInt() + 1, white, 0.3f) }
        if (s.look.day > 0.5f) {
            val jump = kotlin.math.abs(sin(s.time * 3.0 + seed)).toFloat() * 5f
            val skin = solid(s, People.SKIN[People.skinIndex(Noise.unit(seed, 40, 970))], haze)
            val shirt = solid(s, SHIRTS[pick(seed, 41, SHIRTS.size)], haze)
            val by = SIDEWALK - 5.4f - jump
            line(x + 5.2f, by - 2.4f, x + 3.8f, by - 4.6f, skin); line(x + 6.8f, by - 2.4f, x + 8.2f, by - 4.6f, skin)
            fillOval(x + 4.9f, by - 4.9f, x + 7.1f, by - 2.7f, skin, 1f)
            fillOval(x + 4.7f, by - 5.3f, x + 7.3f, by - 3.7f, solid(s, rgb(70, 46, 34), haze), 1f)
            fillOval(x + 4.7f, by - 2.8f, x + 7.3f, by - 0.2f, shirt, 1f)
            line(x + 5.4f, by - 0.2f, x + 4.6f, by + 1.2f, solid(s, rgb(60, 70, 110), haze)); line(x + 6.6f, by - 0.2f, x + 7.4f, by + 1.2f, solid(s, rgb(60, 70, 110), haze))
        }
        var nx2 = x
        while (nx2 <= x + 12) { line(nx2 + 0.5f, SIDEWALK - 11f, nx2 + 0.5f, SIDEWALK - 5f, rgb(200, 204, 210), 90); nx2 += 3 }
        for (r in 0 until 4) fillAlphaPhys(x * sc, (SIDEWALK - 11) * sc + r * 3 + 2, (x + 12) * sc, (SIDEWALK - 11) * sc + r * 3 + 3, rgb(200, 204, 210), 0.22f)
        line(x + 0.2f, SIDEWALK - 11f, x + 0.2f, SIDEWALK - 4.5f, frame); line(x + 11.8f, SIDEWALK - 11f, x + 11.8f, SIDEWALK - 4.5f, frame)
        fillPhys(x * sc, (SIDEWALK - 11) * sc, (x + 12) * sc, (SIDEWALK - 11) * sc + 2, frame)
        return
    }
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
        val glass = lerp(s.look.skyBottom, rgb(56, 76, 106), 0.55f)
        if (hiRes) {
            // The far houses on the finer grid: boards along the walls, a tiled roof with a lit ridge and a shadow under its eaves,
            // a chimney, framed windows with a sill, a door; and rounded trees of two shades with a trunk, and a shadow on the ground.
            val sc = scale
            val white = rgb(255, 255, 255); val ink = rgb(8, 10, 24)
            val shade = lerp(siding, rgb(10, 10, 30), 0.2f)
            fillOval(x - 2f, base - 0.8f, x + w + 3f, base + 1.2f, ink, 0.22f)
            fillPhys(x * sc, (base - 6) * sc, (x + w) * sc, base * sc, siding)
            fillAlphaPhys((x + w) * sc - 3, (base - 6) * sc, (x + w) * sc, base * sc, ink, 0.2f)
            sidingTexture(x, base - 6, x + w, base)
            for (r in 0 until 4) {
                val x0 = x - 1 + r * (w / 4); val x1 = x + w + 1 - r * (w / 4)
                fill(x0, base - 7 - r, x1, base - 6 - r, roof)
                shingleRow(x0, x1, base - 7 - r, i + r)
            }
            fillAlphaPhys((x + (w / 4) * 3) * sc - 2, (base - 10) * sc, (x + w + 1 - 3 * (w / 4)) * sc + 2, (base - 10) * sc + 1, white, 0.4f)
            eaveShadow(x - 1, x + w + 1, base - 6)
            // A chimney on the roof.
            val chX = x + 2 + (Noise.unit(i, 981) * (w - 8)).toInt()
            fillPhys(chX * sc, (base - 11) * sc, chX * sc + 3, (base - 8) * sc, lit(s, rgb(150, 84, 70), haze))
            fillAlphaPhys(chX * sc, (base - 11) * sc, chX * sc + 3, (base - 11) * sc + 1, white, 0.35f)
            for (wx in intArrayOf(x + 2, x + w - 5)) {
                fillPhys(wx * sc, (base - 4) * sc, (wx + 2) * sc, (base - 2) * sc, glass)
                windowDetail(wx, base - 4, 2, 2, shade, 0f, WARM, s.look.day)
            }
            fillPhys((x + w / 2) * sc - 1, (base - 3) * sc, (x + w / 2) * sc + 3, base * sc, lerp(siding, rgb(60, 40, 30), 0.5f))
            fillPhys((x + w / 2) * sc + 1, (base - 2) * sc, (x + w / 2) * sc + 2, (base - 2) * sc + 1, rgb(236, 226, 190))
            if (Noise.unit(i, 979) < 0.5f) {
                val tx = x + w + 2
                val dark = lit(s, rgb(54, 106, 52), haze); val mid = lit(s, rgb(70, 130, 64), haze); val high = lit(s, rgb(96, 158, 80), haze)
                fillPhys(tx * sc + 4, (base - 3) * sc, tx * sc + 6, base * sc, lit(s, rgb(96, 70, 50), haze))
                fillOval(tx - 0.2f, base - 6.4f, tx + 5.4f, base - 1.2f, dark, 1f)
                fillOval(tx - 0.2f, base - 6.4f, tx + 4.4f, base - 2.0f, mid, 1f)
                fillOval(tx + 0.4f, base - 6.0f, tx + 2.6f, base - 4.2f, high, 1f)
                fillOval(tx + 3.2f, base - 4.2f, tx + 4.8f, base - 2.8f, dark, 0.7f)
            }
            continue
        }
        fill(x, base - 6, x + w, base, siding)
        for (r in 0 until 4) fill(x - 1 + r * (w / 4), base - 7 - r, x + w + 1 - r * (w / 4), base - 6 - r, roof)
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
        // Never in front of the airport.
        if (airportNear(s, wx / factor, 2600.0)) continue
        // Standing on the ground: the field begins at FIELD_TOP, so the feet are set just inside it (they used to stop short of it and
        // leave the tower hanging in the air).
        val base = FIELD_TOP + 3
        val legs = lit(s, rgb(120, 124, 134), haze)
        val tank = lit(s, rgb(214, 220, 228), haze)
        val band = lit(s, rgb(196, 60, 56), haze)
        if (hiRes) {
            // The tower on the finer grid: splayed legs braced with crosses, footings, a ribbed cylindrical tank lit on the left and
            // shaded on the right with a red band and rivets, a catwalk with a rail and ladder, a conical roof and a blinking beacon.
            val sc = scale
            val ink = rgb(8, 10, 24); val white = rgb(255, 255, 255)
            val brace = Colors.scale(legs, 0.8f)
            fillOval(x - 4f, base - 0.8f, x + 15f, base + 1.6f, ink, 0.30f)
            val feet = floatArrayOf(x - 0.8f, x + 3.6f, x + 7.4f, x + 11.8f)
            val tops = floatArrayOf(x + 1.5f, x + 4.2f, x + 6.8f, x + 9.5f)
            for (k in 0 until 4) {
                line(tops[k], base - 12f, feet[k], base.toFloat(), legs)
                fillPhys((feet[k] * sc).toInt() - 1, base * sc - 1, (feet[k] * sc).toInt() + 4, base * sc + 2, Colors.mul(legs, 1.15f, 1.15f, 1.15f))
            }
            // Cross braces between the legs at two heights, and a ring beam.
            for (hy in floatArrayOf(base - 9f, base - 4.5f)) {
                val u0 = (base - 12f - hy) / -12f
                for (k in 0 until 3) {
                    val xa = tops[k] + (feet[k] - tops[k]) * (hy - (base - 12f)) / 12f
                    val xb = tops[k + 1] + (feet[k + 1] - tops[k + 1]) * (hy - (base - 12f)) / 12f
                    val h2 = hy + 4.5f
                    val xa2 = tops[k] + (feet[k] - tops[k]) * (h2 - (base - 12f)) / 12f
                    val xb2 = tops[k + 1] + (feet[k + 1] - tops[k + 1]) * (h2 - (base - 12f)) / 12f
                    if (h2 <= base) { line(xa, hy, xb2, h2, brace, 200); line(xb, hy, xa2, h2, brace, 200) }
                }
            }
            fillPhys(x * sc, (base - 7) * sc, (x + 11) * sc, (base - 7) * sc + 2, brace)
            // The tank: a ribbed cylinder, light on the left and dark on the right, with a red band round the middle and rivets below.
            fillPhys(x * sc, (base - 22) * sc, (x + 11) * sc, (base - 12) * sc, tank)
            fillAlphaPhys(x * sc, (base - 22) * sc, x * sc + 4, (base - 12) * sc, white, 0.30f)
            fillAlphaPhys((x + 11) * sc - 6, (base - 22) * sc, (x + 11) * sc, (base - 12) * sc, ink, 0.30f)
            fillAlphaPhys((x + 11) * sc - 2, (base - 22) * sc, (x + 11) * sc, (base - 12) * sc, ink, 0.18f)
            var rb = (base - 22) * sc + 3
            while (rb < (base - 12) * sc) { fillAlphaPhys(x * sc, rb, (x + 11) * sc, rb + 1, ink, 0.13f); rb += 4 }
            fillPhys(x * sc, (base - 18) * sc, (x + 11) * sc, (base - 16) * sc, band)
            fillAlphaPhys(x * sc, (base - 18) * sc, (x + 11) * sc, (base - 18) * sc + 1, white, 0.25f)
            fillAlphaPhys(x * sc, (base - 16) * sc - 1, (x + 11) * sc, (base - 16) * sc, ink, 0.25f)
            var rv = x * sc + 2
            while (rv < (x + 11) * sc - 1) { fillAlphaPhys(rv, (base - 13) * sc, rv + 1, (base - 13) * sc + 1, ink, 0.4f); rv += 3 }
            // The catwalk under the tank with a rail, and a ladder down the side.
            fillPhys((x - 2) * sc, (base - 12) * sc, (x + 13) * sc, (base - 12) * sc + 2, brace)
            fillAlphaPhys((x - 2) * sc, (base - 14) * sc, (x + 13) * sc, (base - 14) * sc + 1, legs, 0.9f)
            var rp = (x - 2) * sc
            while (rp <= (x + 13) * sc) { fillAlphaPhys(rp, (base - 14) * sc, rp + 1, (base - 12) * sc, legs, 0.9f); rp += 5 }
            line(x + 10.6f, base - 12f, x + 11.6f, base.toFloat(), brace, 255)
            var lr = (base - 11) * sc
            while (lr < base * sc) { fillAlphaPhys((x + 10) * sc + 1 + (lr - (base - 11) * sc) / 14, lr, (x + 12) * sc + 1 + (lr - (base - 11) * sc) / 14, lr + 1, brace, 0.9f); lr += 4 }
            // A conical roof, lit on its left half, with a finial and the beacon.
            val roofTop = (base - 28) * sc
            val roofBase = (base - 22) * sc
            for (ry in 0 until roofBase - roofTop) {
                val half = (ry * (6.2f * sc) / (roofBase - roofTop)).toInt() + 1
                val mid = (x * sc) + (11 * sc) / 2
                fillPhys(mid - half, roofTop + ry, mid, roofTop + ry + 1, Colors.mul(tank, 1.05f, 1.05f, 1.08f))
                fillPhys(mid, roofTop + ry, mid + half, roofTop + ry + 1, Colors.scale(tank, 0.82f))
            }
            fillPhys(x * sc + (11 * sc) / 2 - 1, (base - 31) * sc, x * sc + (11 * sc) / 2 + 1, roofTop, legs)
            if (s.dark() > 0.2f && (s.time * 0.9).toInt() % 2 == 0) {
                fillGlow(x + 5.5f, base - 31.5f, 6f, rgb(255, 60, 50), 0.6f)
                fillOval(x + 4.6f, base - 32.3f, x + 6.4f, base - 30.7f, rgb(255, 90, 80), 1f)
            } else fillOval(x + 4.8f, base - 32.1f, x + 6.2f, base - 30.9f, Colors.scale(rgb(200, 70, 66), 0.7f), 1f)
            continue
        }
        // (The footings and a pad on the ground for the 8-bit style.)
        fill(x - 1, base, x + 12, base + 1, legs)
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
