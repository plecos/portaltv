package com.portal.tv.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

/**
 * Built-in profile avatars: one set for adults, one for kids. They are drawn
 * in code (no image files) on a 100x100 canvas and clipped to a circle.
 */
object AvatarArt {

    data class Avatar(val id: String, val kids: Boolean, val label: String)

    val adult = listOf(
        Avatar("sun", false, "Sun"), Avatar("moon", false, "Moon"),
        Avatar("mountain", false, "Mountain"), Avatar("wave", false, "Wave"),
        Avatar("leaf", false, "Leaf"), Avatar("star", false, "Star"),
        Avatar("planet", false, "Planet"), Avatar("bolt", false, "Bolt"),
        Avatar("cloud", false, "Cloud"), Avatar("flame", false, "Flame"),
        Avatar("gem", false, "Gem"), Avatar("pine", false, "Pine"),
        Avatar("rainbow", false, "Rainbow"), Avatar("heart", false, "Heart"),
        Avatar("rocket", false, "Rocket"), Avatar("sailboat", false, "Sailboat")
    )

    val kids = listOf(
        Avatar("cat", true, "Cat"), Avatar("dog", true, "Dog"),
        Avatar("bunny", true, "Bunny"), Avatar("bear", true, "Bear"),
        Avatar("fox", true, "Fox"), Avatar("panda", true, "Panda"),
        Avatar("owl", true, "Owl"), Avatar("frog", true, "Frog"),
        Avatar("pig", true, "Pig"), Avatar("lion", true, "Lion"),
        Avatar("penguin", true, "Penguin"), Avatar("duck", true, "Duck"),
        Avatar("mouse", true, "Mouse"), Avatar("koala", true, "Koala"),
        Avatar("turtle", true, "Turtle"), Avatar("monkey", true, "Monkey"),
        // Fantasy
        Avatar("unicorn", true, "Unicorn"), Avatar("dragon", true, "Dragon"),
        Avatar("wizard", true, "Wizard"), Avatar("fairy", true, "Fairy"),
        Avatar("knight", true, "Knight"), Avatar("castle", true, "Castle"),
        Avatar("crown", true, "Crown"), Avatar("ghost", true, "Ghost"),
        // Aliens and space
        Avatar("alien", true, "Alien"), Avatar("antenna", true, "Antenna alien"),
        Avatar("cyclops", true, "Monster"), Avatar("robot", true, "Robot"),
        Avatar("astronaut", true, "Astronaut"), Avatar("ufo", true, "UFO"),
        Avatar("comet", true, "Comet"), Avatar("earth", true, "Earth")
    )

    /** The avatars a profile of the given type can choose from. */
    fun options(isKids: Boolean): List<Avatar> = if (isKids) kids else adult

    fun find(id: String?): Avatar? = (adult + kids).firstOrNull { it.id == id }

    fun drawable(id: String): Drawable = AvatarDrawable(id)

    // ---------- Drawable ----------

    private class AvatarDrawable(private val id: String) : Drawable() {
        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            canvas.save()
            canvas.clipPath(Path().apply {
                addCircle(b.exactCenterX(), b.exactCenterY(), minOf(b.width(), b.height()) / 2f, Path.Direction.CW)
            })
            canvas.translate(b.left.toFloat(), b.top.toFloat())
            canvas.scale(b.width() / 100f, b.height() / 100f)
            art(canvas, id)
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    // ---------- Drawing helpers (100x100 units) ----------

    private fun fill(color: Long) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toInt()
    }

    private fun stroke(color: Long, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toInt()
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
    }

    private fun Canvas.bg(color: Long) = drawRect(0f, 0f, 100f, 100f, fill(color))

    private fun Canvas.dot(x: Float, y: Float, r: Float, color: Long) =
        drawCircle(x, y, r, fill(color))

    private fun Canvas.oval(l: Float, t: Float, r: Float, b: Float, color: Long) =
        drawOval(RectF(l, t, r, b), fill(color))

    private fun Canvas.rrect(l: Float, t: Float, r: Float, b: Float, rad: Float, color: Long) =
        drawRoundRect(RectF(l, t, r, b), rad, rad, fill(color))

    private fun Canvas.poly(color: Long, vararg pts: Float) {
        val p = Path().apply {
            moveTo(pts[0], pts[1])
            for (i in 2 until pts.size step 2) lineTo(pts[i], pts[i + 1])
            close()
        }
        drawPath(p, fill(color))
    }

    /** Left and right copies of a shape, mirrored around x = 50. */
    private fun mirrored(draw: (flip: Boolean) -> Unit) {
        draw(false)
        draw(true)
    }

    private fun mx(x: Float, flip: Boolean) = if (flip) 100f - x else x

    private fun Canvas.eyes(y: Float = 52f, dx: Float = 11f, r: Float = 4f, color: Long = 0xFF212121) {
        dot(50f - dx, y, r, color)
        dot(50f + dx, y, r, color)
    }

    // ---------- Art ----------

    private fun art(c: Canvas, id: String) {
        when (id) {
            // ----- Adult -----
            "sun" -> {
                c.bg(0xFFFF8F00)
                c.dot(50f, 50f, 18f, 0xFFFFF176)
                for (i in 0 until 8) {
                    c.save(); c.rotate(i * 45f, 50f, 50f)
                    c.drawLine(50f, 20f, 50f, 9f, stroke(0xFFFFF176, 5f))
                    c.restore()
                }
            }
            "moon" -> {
                c.bg(0xFF1A237E)
                val moon = Path().apply { addCircle(50f, 50f, 28f, Path.Direction.CW) }
                val bite = Path().apply { addCircle(63f, 42f, 24f, Path.Direction.CW) }
                moon.op(bite, Path.Op.DIFFERENCE)
                c.drawPath(moon, fill(0xFFFFF59D))
                c.dot(75f, 24f, 2f, 0xFFFFFFFF); c.dot(24f, 25f, 1.5f, 0xFFFFFFFF)
                c.dot(80f, 70f, 1.5f, 0xFFFFFFFF)
            }
            "mountain" -> {
                c.bg(0xFF4FC3F7)
                c.dot(75f, 28f, 9f, 0xFFFFEB3B)
                c.poly(0xFF546E7A, 8f, 82f, 44f, 34f, 82f, 82f)
                c.poly(0xFF37474F, 34f, 82f, 66f, 44f, 104f, 82f)
                c.drawRect(0f, 78f, 100f, 100f, fill(0xFF2E7D32))
            }
            "wave" -> {
                c.bg(0xFF0277BD)
                for ((i, y) in listOf(36f, 52f, 68f).withIndex()) {
                    val p = Path().apply {
                        moveTo(-5f, y)
                        var up = true
                        var x = -5f
                        while (x < 105f) {
                            quadTo(x + 12.5f, if (up) y - 12f else y + 12f, x + 25f, y)
                            up = !up; x += 25f
                        }
                    }
                    c.drawPath(p, stroke(if (i == 1) 0xFFFFFFFF else 0xFF81D4FA, 6f))
                }
            }
            "leaf" -> {
                c.bg(0xFF2E7D32)
                val leaf = Path().apply {
                    moveTo(24f, 76f); quadTo(18f, 24f, 76f, 22f); quadTo(82f, 72f, 24f, 76f)
                }
                c.drawPath(leaf, fill(0xFFA5D6A7))
                c.drawLine(24f, 76f, 64f, 34f, stroke(0xFF2E7D32, 3f))
            }
            "star" -> {
                c.bg(0xFF6A1B9A)
                val p = Path()
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) 32f else 14f
                    val a = Math.toRadians(-90.0 + i * 36.0)
                    val x = 50f + r * cos(a).toFloat()
                    val y = 53f + r * sin(a).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
                c.drawPath(p, fill(0xFFFFD54F))
            }
            "planet" -> {
                c.bg(0xFF0D1B2A)
                c.dot(22f, 24f, 1.5f, 0xFFFFFFFF); c.dot(80f, 20f, 1.5f, 0xFFFFFFFF)
                c.dot(82f, 78f, 1.5f, 0xFFFFFFFF)
                c.dot(50f, 50f, 20f, 0xFFFF7043)
                c.save(); c.rotate(-22f, 50f, 50f)
                c.drawOval(RectF(14f, 40f, 86f, 60f), stroke(0xFFFFCC80, 5f))
                c.restore()
            }
            "bolt" -> {
                c.bg(0xFF00695C)
                c.poly(0xFFFFEB3B, 56f, 10f, 30f, 54f, 48f, 54f, 42f, 90f, 72f, 42f, 53f, 42f)
            }

            "cloud" -> {
                c.bg(0xFF42A5F5)
                c.dot(38f, 56f, 16f, 0xFFFFFFFF); c.dot(56f, 47f, 20f, 0xFFFFFFFF)
                c.dot(74f, 58f, 14f, 0xFFFFFFFF)
                c.drawRect(38f, 56f, 74f, 72f, fill(0xFFFFFFFF))
                c.dot(38f, 56f, 16f, 0xFFFFFFFF)
            }
            "flame" -> {
                c.bg(0xFF263238)
                val outer = Path().apply {
                    moveTo(50f, 10f); quadTo(82f, 40f, 72f, 66f); quadTo(66f, 88f, 50f, 88f)
                    quadTo(32f, 88f, 28f, 66f); quadTo(24f, 50f, 40f, 34f)
                    quadTo(42f, 48f, 50f, 50f); quadTo(46f, 30f, 50f, 10f)
                }
                c.drawPath(outer, fill(0xFFFF7043))
                val inner = Path().apply {
                    moveTo(50f, 46f); quadTo(68f, 60f, 62f, 74f); quadTo(58f, 84f, 50f, 84f)
                    quadTo(40f, 84f, 38f, 72f); quadTo(36f, 62f, 50f, 46f)
                }
                c.drawPath(inner, fill(0xFFFFD54F))
            }
            "gem" -> {
                c.bg(0xFF283593)
                c.poly(0xFF4DD0E1, 30f, 34f, 70f, 34f, 86f, 50f, 50f, 88f, 14f, 50f)
                c.poly(0xFF80DEEA, 30f, 34f, 70f, 34f, 60f, 50f, 40f, 50f)
                c.poly(0xFF26C6DA, 14f, 50f, 30f, 34f, 40f, 50f)
                c.poly(0xFF26C6DA, 86f, 50f, 70f, 34f, 60f, 50f)
                c.poly(0xFF00ACC1, 14f, 50f, 40f, 50f, 50f, 88f)
                c.poly(0xFF0097A7, 86f, 50f, 60f, 50f, 50f, 88f)
                c.poly(0xFF00BCD4, 40f, 50f, 60f, 50f, 50f, 88f)
            }
            "pine" -> {
                c.bg(0xFF37474F)
                c.drawRect(46f, 70f, 54f, 90f, fill(0xFF6D4C41))
                c.poly(0xFF2E7D32, 50f, 10f, 30f, 40f, 70f, 40f)
                c.poly(0xFF388E3C, 50f, 26f, 24f, 58f, 76f, 58f)
                c.poly(0xFF43A047, 50f, 42f, 18f, 78f, 82f, 78f)
            }
            "rainbow" -> {
                c.bg(0xFFE1F5FE)
                val colors = listOf(0xFFEF5350, 0xFFFFA726, 0xFFFFEE58, 0xFF66BB6A, 0xFF42A5F5)
                for ((i, color) in colors.withIndex()) {
                    val r = 38f - i * 6.5f
                    c.drawArc(RectF(50f - r, 72f - r, 50f + r, 72f + r), 180f, 180f, false,
                        stroke(color, 6.5f).apply { strokeCap = Paint.Cap.BUTT })
                }
                c.dot(14f, 74f, 8f, 0xFFFFFFFF); c.dot(22f, 78f, 7f, 0xFFFFFFFF)
                c.dot(86f, 74f, 8f, 0xFFFFFFFF); c.dot(78f, 78f, 7f, 0xFFFFFFFF)
            }
            "heart" -> {
                c.bg(0xFFFCE4EC)
                val heart = Path().apply {
                    moveTo(50f, 82f); cubicTo(8f, 52f, 20f, 14f, 50f, 36f)
                    cubicTo(80f, 14f, 92f, 52f, 50f, 82f)
                }
                c.drawPath(heart, fill(0xFFE91E63))
                c.dot(36f, 36f, 4f, 0x66FFFFFF)
            }
            "rocket" -> {
                c.bg(0xFF1A237E)
                c.dot(20f, 24f, 1.5f, 0xFFFFFFFF); c.dot(82f, 30f, 1.5f, 0xFFFFFFFF)
                c.dot(24f, 74f, 1.5f, 0xFFFFFFFF)
                c.poly(0xFFEF5350, 36f, 54f, 22f, 74f, 38f, 68f)
                c.poly(0xFFEF5350, 64f, 54f, 78f, 74f, 62f, 68f)
                c.poly(0xFFFFA726, 42f, 66f, 58f, 66f, 50f, 90f)
                val body = Path().apply {
                    moveTo(50f, 8f); quadTo(70f, 32f, 64f, 68f); lineTo(36f, 68f)
                    quadTo(30f, 32f, 50f, 8f)
                }
                c.drawPath(body, fill(0xFFFFFFFF))
                c.dot(50f, 38f, 8f, 0xFF4FC3F7)
            }
            "sailboat" -> {
                c.bg(0xFF81D4FA)
                c.drawRect(0f, 70f, 100f, 100f, fill(0xFF0277BD))
                c.poly(0xFF6D4C41, 22f, 66f, 78f, 66f, 68f, 82f, 32f, 82f)
                c.drawLine(50f, 16f, 50f, 66f, stroke(0xFF5D4037, 3f))
                c.poly(0xFFFFFFFF, 48f, 20f, 48f, 60f, 24f, 60f)
                c.poly(0xFFFFCC80, 54f, 28f, 54f, 60f, 74f, 60f)
            }

            // ----- Kids -----
            "cat" -> {
                c.bg(0xFFFFE082)
                mirrored { f ->
                    c.poly(0xFFFFA726, mx(24f, f), 40f, mx(26f, f), 12f, mx(46f, f), 28f)
                    c.poly(0xFFF48FB1, mx(29f, f), 34f, mx(30f, f), 20f, mx(40f, f), 29f)
                }
                c.dot(50f, 56f, 30f, 0xFFFFA726)
                c.eyes()
                c.poly(0xFFF48FB1, 46f, 60f, 54f, 60f, 50f, 65f)
                for (dy in listOf(-3f, 3f)) {
                    c.drawLine(18f, 64f + dy, 36f, 63f + dy / 2, stroke(0xFF5D4037, 1.5f))
                    c.drawLine(82f, 64f + dy, 64f, 63f + dy / 2, stroke(0xFF5D4037, 1.5f))
                }
            }
            "dog" -> {
                c.bg(0xFF90CAF9)
                c.oval(12f, 26f, 34f, 68f, 0xFF6D4C41)
                c.oval(66f, 26f, 88f, 68f, 0xFF6D4C41)
                c.dot(50f, 56f, 29f, 0xFFD7A86E)
                c.dot(50f, 66f, 13f, 0xFFF5DEB3)
                c.eyes(y = 50f)
                c.dot(50f, 60f, 5f, 0xFF212121)
            }
            "bunny" -> {
                c.bg(0xFFF8BBD0)
                c.oval(31f, 2f, 47f, 46f, 0xFFFFFFFF); c.oval(53f, 2f, 69f, 46f, 0xFFFFFFFF)
                c.oval(36f, 10f, 42f, 38f, 0xFFF48FB1); c.oval(58f, 10f, 64f, 38f, 0xFFF48FB1)
                c.dot(50f, 63f, 27f, 0xFFFFFFFF)
                c.eyes(y = 58f)
                c.poly(0xFFF48FB1, 46f, 66f, 54f, 66f, 50f, 71f)
            }
            "bear" -> {
                c.bg(0xFFA5D6A7)
                c.dot(25f, 30f, 10f, 0xFF6D4C41); c.dot(75f, 30f, 10f, 0xFF6D4C41)
                c.dot(50f, 56f, 30f, 0xFF8D6E63)
                c.dot(50f, 66f, 13f, 0xFFD7B899)
                c.eyes(y = 50f)
                c.dot(50f, 61f, 5f, 0xFF212121)
            }
            "fox" -> {
                c.bg(0xFFFFCCBC)
                mirrored { f ->
                    c.poly(0xFFE65100, mx(22f, f), 42f, mx(24f, f), 10f, mx(46f, f), 30f)
                }
                c.dot(50f, 56f, 29f, 0xFFF57C00)
                c.poly(0xFFFFFFFF, 24f, 62f, 76f, 62f, 50f, 88f)
                c.eyes(y = 50f)
                c.dot(50f, 84f, 3.5f, 0xFF212121)
            }
            "panda" -> {
                c.bg(0xFFB2DFDB)
                c.dot(26f, 30f, 10f, 0xFF212121); c.dot(74f, 30f, 10f, 0xFF212121)
                c.dot(50f, 56f, 30f, 0xFFFFFFFF)
                c.oval(30f, 43f, 45f, 62f, 0xFF212121); c.oval(55f, 43f, 70f, 62f, 0xFF212121)
                c.dot(38f, 52f, 2.5f, 0xFFFFFFFF); c.dot(62f, 52f, 2.5f, 0xFFFFFFFF)
                c.dot(50f, 64f, 4f, 0xFF212121)
            }
            "owl" -> {
                c.bg(0xFFBCAAA4)
                mirrored { f ->
                    c.poly(0xFF6D4C41, mx(24f, f), 38f, mx(28f, f), 10f, mx(44f, f), 26f)
                }
                c.dot(50f, 58f, 32f, 0xFF8D6E63)
                c.dot(36f, 52f, 13f, 0xFFFFFFFF); c.dot(64f, 52f, 13f, 0xFFFFFFFF)
                c.dot(36f, 52f, 6f, 0xFF212121); c.dot(64f, 52f, 6f, 0xFF212121)
                c.poly(0xFFFFA000, 45f, 62f, 55f, 62f, 50f, 75f)
            }
            "frog" -> {
                c.bg(0xFFFFF59D)
                c.oval(12f, 40f, 88f, 90f, 0xFF66BB6A)
                c.dot(30f, 38f, 14f, 0xFF66BB6A); c.dot(70f, 38f, 14f, 0xFF66BB6A)
                c.dot(30f, 38f, 8f, 0xFFFFFFFF); c.dot(70f, 38f, 8f, 0xFFFFFFFF)
                c.dot(30f, 38f, 4f, 0xFF212121); c.dot(70f, 38f, 4f, 0xFF212121)
                val mouth = Path().apply { moveTo(28f, 66f); quadTo(50f, 84f, 72f, 66f) }
                c.drawPath(mouth, stroke(0xFF1B5E20, 3f))
                c.dot(44f, 56f, 1.8f, 0xFF1B5E20); c.dot(56f, 56f, 1.8f, 0xFF1B5E20)
            }
            "pig" -> {
                c.bg(0xFFFFF9C4)
                mirrored { f ->
                    c.poly(0xFFF48FB1, mx(24f, f), 40f, mx(26f, f), 14f, mx(46f, f), 28f)
                }
                c.dot(50f, 56f, 30f, 0xFFF8BBD0)
                c.eyes(y = 46f)
                c.oval(36f, 54f, 64f, 76f, 0xFFF48FB1)
                c.dot(45f, 65f, 2.6f, 0xFFAD1457); c.dot(55f, 65f, 2.6f, 0xFFAD1457)
            }
            "lion" -> {
                c.bg(0xFFFFECB3)
                c.dot(50f, 54f, 42f, 0xFF8D5524)
                c.dot(50f, 56f, 26f, 0xFFFFB74D)
                c.oval(38f, 60f, 62f, 78f, 0xFFFFE0B2)
                c.eyes(y = 50f)
                c.poly(0xFF4E342E, 45f, 58f, 55f, 58f, 50f, 64f)
            }
            "penguin" -> {
                c.bg(0xFF81D4FA)
                c.oval(20f, 14f, 80f, 94f, 0xFF263238)
                c.oval(32f, 42f, 68f, 90f, 0xFFFFFFFF)
                c.dot(40f, 34f, 7f, 0xFFFFFFFF); c.dot(60f, 34f, 7f, 0xFFFFFFFF)
                c.dot(41f, 35f, 3f, 0xFF212121); c.dot(59f, 35f, 3f, 0xFF212121)
                c.poly(0xFFFFA000, 43f, 44f, 57f, 44f, 50f, 56f)
            }
            "duck" -> {
                c.bg(0xFF80DEEA)
                c.dot(50f, 54f, 30f, 0xFFFFEE58)
                c.eyes(y = 46f, dx = 12f)
                c.oval(32f, 56f, 68f, 78f, 0xFFFF9800)
                c.dot(45f, 64f, 1.8f, 0xFFE65100); c.dot(55f, 64f, 1.8f, 0xFFE65100)
                val tuft = Path().apply { moveTo(46f, 26f); quadTo(50f, 10f, 56f, 18f); quadTo(52f, 20f, 54f, 26f) }
                c.drawPath(tuft, fill(0xFFFFEE58))
            }
            "mouse" -> {
                c.bg(0xFFD1C4E9)
                mirrored { f ->
                    c.dot(mx(26f, f), 30f, 16f, 0xFF9E9E9E)
                    c.dot(mx(26f, f), 30f, 9f, 0xFFF8BBD0)
                }
                c.dot(50f, 60f, 28f, 0xFFBDBDBD)
                c.eyes(y = 54f)
                c.dot(50f, 66f, 4f, 0xFFF48FB1)
                for (dy in listOf(-2f, 3f)) {
                    c.drawLine(16f, 66f + dy, 34f, 65f + dy / 2, stroke(0xFF616161, 1.5f))
                    c.drawLine(84f, 66f + dy, 66f, 65f + dy / 2, stroke(0xFF616161, 1.5f))
                }
            }
            "koala" -> {
                c.bg(0xFFC8E6C9)
                mirrored { f ->
                    c.dot(mx(22f, f), 38f, 15f, 0xFF9E9E9E)
                    c.dot(mx(22f, f), 38f, 8f, 0xFFEEEEEE)
                }
                c.dot(50f, 58f, 28f, 0xFFBDBDBD)
                c.oval(41f, 48f, 59f, 72f, 0xFF37474F)
                c.eyes(y = 48f, dx = 16f, r = 3.5f)
            }
            "turtle" -> {
                c.bg(0xFFB3E5FC)
                c.dot(22f, 78f, 9f, 0xFF9CCC65); c.dot(78f, 78f, 9f, 0xFF9CCC65)
                c.dot(50f, 24f, 13f, 0xFF9CCC65)
                c.dot(45f, 22f, 2f, 0xFF212121); c.dot(55f, 22f, 2f, 0xFF212121)
                c.oval(16f, 32f, 84f, 92f, 0xFF558B2F)
                c.oval(34f, 44f, 66f, 78f, 0xFF7CB342)
                c.drawLine(50f, 44f, 50f, 78f, stroke(0xFF558B2F, 2f))
                c.drawLine(34f, 61f, 66f, 61f, stroke(0xFF558B2F, 2f))
            }
            "monkey" -> {
                c.bg(0xFFFFF59D)
                mirrored { f ->
                    c.dot(mx(20f, f), 52f, 11f, 0xFF6D4C41)
                    c.dot(mx(20f, f), 52f, 6f, 0xFFD7B899)
                }
                c.dot(50f, 50f, 30f, 0xFF6D4C41)
                c.dot(50f, 62f, 22f, 0xFFFFE0B2)
                c.dot(40f, 54f, 10f, 0xFFFFE0B2); c.dot(60f, 54f, 10f, 0xFFFFE0B2)
                c.eyes(y = 52f, dx = 10f, r = 3.5f)
                c.dot(46f, 64f, 1.8f, 0xFF6D4C41); c.dot(54f, 64f, 1.8f, 0xFF6D4C41)
                val smile = Path().apply { moveTo(41f, 72f); quadTo(50f, 80f, 59f, 72f) }
                c.drawPath(smile, stroke(0xFF6D4C41, 2.5f))
            }
            // ----- Fantasy -----
            "unicorn" -> {
                c.bg(0xFFE1BEE7)
                c.dot(30f, 38f, 13f, 0xFFCE93D8); c.dot(24f, 54f, 11f, 0xFFF48FB1)
                c.dot(28f, 70f, 9f, 0xFF90CAF9)
                c.poly(0xFFFFFFFF, 38f, 40f, 40f, 22f, 54f, 34f)
                c.dot(54f, 60f, 26f, 0xFFFFFFFF)
                c.poly(0xFFFFD54F, 49f, 38f, 59f, 38f, 54f, 6f)
                c.dot(46f, 58f, 3.5f, 0xFF212121); c.dot(64f, 58f, 3.5f, 0xFF212121)
                c.dot(40f, 68f, 4f, 0x66F48FB1); c.dot(70f, 68f, 4f, 0x66F48FB1)
                c.dot(50f, 72f, 1.8f, 0xFFF48FB1); c.dot(58f, 72f, 1.8f, 0xFFF48FB1)
            }
            "dragon" -> {
                c.bg(0xFFFFE0B2)
                mirrored { f ->
                    c.poly(0xFF8D6E63, mx(32f, f), 40f, mx(26f, f), 12f, mx(46f, f), 30f)
                }
                c.dot(50f, 56f, 30f, 0xFF66BB6A)
                c.oval(34f, 60f, 66f, 84f, 0xFF81C784)
                c.dot(41f, 72f, 2f, 0xFF2E7D32); c.dot(59f, 72f, 2f, 0xFF2E7D32)
                for (dx in listOf(-12f, 12f)) {
                    c.dot(50f + dx, 48f, 7f, 0xFFFFEE58)
                    c.oval(48.5f + dx, 42f, 51.5f + dx, 54f, 0xFF212121)
                }
                c.poly(0xFFFFFFFF, 44f, 82f, 47f, 82f, 45.5f, 88f)
                c.poly(0xFFFFFFFF, 53f, 82f, 56f, 82f, 54.5f, 88f)
            }
            "wizard" -> {
                c.bg(0xFF283593)
                c.dot(18f, 24f, 1.5f, 0xFFFFFFFF); c.dot(84f, 30f, 1.5f, 0xFFFFFFFF)
                c.dot(50f, 66f, 22f, 0xFFFFCCBC)
                c.poly(0xFFFFFFFF, 32f, 68f, 68f, 68f, 50f, 98f)
                c.dot(40f, 62f, 2.8f, 0xFF212121); c.dot(60f, 62f, 2.8f, 0xFF212121)
                c.poly(0xFF5E35B1, 50f, 2f, 26f, 50f, 74f, 50f)
                c.oval(16f, 44f, 84f, 58f, 0xFF4527A0)
                c.poly(0xFFFFD54F, 50f, 22f, 53f, 30f, 61f, 30f, 55f, 35f, 57f, 43f, 50f, 38f, 43f, 43f, 45f, 35f, 39f, 30f, 47f, 30f)
            }
            "fairy" -> {
                c.bg(0xFFF8BBD0)
                c.oval(8f, 24f, 50f, 66f, 0xCCFFFFFF); c.oval(50f, 24f, 92f, 66f, 0xCCFFFFFF)
                c.oval(14f, 40f, 44f, 76f, 0xAAB3E5FC); c.oval(56f, 40f, 86f, 76f, 0xAAB3E5FC)
                c.dot(50f, 58f, 22f, 0xFFFFE0B2)
                c.drawArc(RectF(26f, 30f, 74f, 78f), 180f, 180f, true, fill(0xFFFF8F00))
                c.dot(50f, 36f, 6f, 0xFFFF8F00)
                c.eyes(y = 58f, dx = 8f, r = 3f)
                val smile = Path().apply { moveTo(43f, 68f); quadTo(50f, 74f, 57f, 68f) }
                c.drawPath(smile, stroke(0xFFE57373, 2f))
                c.dot(78f, 18f, 2.5f, 0xFFFFFFFF); c.dot(20f, 82f, 2f, 0xFFFFFFFF)
            }
            "knight" -> {
                c.bg(0xFF90A4AE)
                c.oval(44f, 4f, 56f, 32f, 0xFFE53935)
                c.dot(50f, 54f, 32f, 0xFFB0BEC5)
                c.rrect(26f, 44f, 74f, 60f, 4f, 0xFF37474F)
                c.dot(40f, 52f, 2.8f, 0xFFFFF176); c.dot(60f, 52f, 2.8f, 0xFFFFF176)
                for (x in listOf(38f, 50f, 62f)) c.drawLine(x, 66f, x, 82f, stroke(0xFF78909C, 2f))
            }
            "castle" -> {
                c.bg(0xFF81D4FA)
                c.dot(82f, 20f, 7f, 0xFFFFEB3B)
                c.drawRect(0f, 84f, 100f, 100f, fill(0xFF66BB6A))
                c.drawRect(24f, 48f, 76f, 88f, fill(0xFFB0BEC5))
                c.drawRect(18f, 32f, 36f, 88f, fill(0xFF90A4AE))
                c.drawRect(64f, 32f, 82f, 88f, fill(0xFF90A4AE))
                for (x in listOf(18f, 25f, 32f, 64f, 71f, 78f)) c.drawRect(x, 26f, x + 4f, 32f, fill(0xFF90A4AE))
                c.poly(0xFFE53935, 50f, 10f, 50f, 24f, 64f, 17f)
                c.drawLine(50f, 8f, 50f, 48f, stroke(0xFF5D4037, 2f))
                c.drawRect(42f, 66f, 58f, 88f, fill(0xFF5D4037))
                c.drawArc(RectF(42f, 58f, 58f, 74f), 180f, 180f, true, fill(0xFF5D4037))
                c.drawRect(24f, 48f, 76f, 54f, fill(0xFF78909C))
                c.drawRect(22f, 46f, 26f, 52f, fill(0xFF78909C))
            }
            "crown" -> {
                c.bg(0xFF7E57C2)
                c.dot(18f, 24f, 1.5f, 0xFFFFFFFF); c.dot(84f, 22f, 2f, 0xFFFFFFFF)
                c.poly(0xFFFFC107, 22f, 72f, 16f, 32f, 36f, 52f, 50f, 26f, 64f, 52f, 84f, 32f, 78f, 72f)
                c.drawRect(22f, 70f, 78f, 82f, fill(0xFFFFA000))
                c.dot(50f, 62f, 4f, 0xFFE53935); c.dot(32f, 64f, 3f, 0xFF42A5F5); c.dot(68f, 64f, 3f, 0xFF66BB6A)
                c.dot(16f, 32f, 3.5f, 0xFFFFE082); c.dot(84f, 32f, 3.5f, 0xFFFFE082); c.dot(50f, 26f, 3.5f, 0xFFFFE082)
            }
            "ghost" -> {
                c.bg(0xFF311B92)
                val body = Path().apply {
                    moveTo(24f, 84f); lineTo(24f, 46f); quadTo(24f, 12f, 50f, 12f)
                    quadTo(76f, 12f, 76f, 46f); lineTo(76f, 84f)
                    quadTo(69f, 74f, 63f, 84f); quadTo(56f, 74f, 50f, 84f)
                    quadTo(44f, 74f, 37f, 84f); quadTo(31f, 74f, 24f, 84f)
                }
                c.drawPath(body, fill(0xFFFFFFFF))
                c.oval(36f, 38f, 46f, 56f, 0xFF212121); c.oval(54f, 38f, 64f, 56f, 0xFF212121)
                c.oval(46f, 60f, 54f, 70f, 0xFF212121)
                c.dot(32f, 62f, 4f, 0x66F48FB1); c.dot(68f, 62f, 4f, 0x66F48FB1)
            }

            // ----- Aliens and space -----
            "alien" -> {
                c.bg(0xFF1A237E)
                c.dot(18f, 22f, 1.5f, 0xFFFFFFFF); c.dot(84f, 18f, 1.5f, 0xFFFFFFFF)
                c.dot(80f, 80f, 1.5f, 0xFFFFFFFF)
                c.oval(24f, 14f, 76f, 92f, 0xFF9CCC65)
                c.save(); c.rotate(-24f, 38f, 56f); c.oval(26f, 42f, 48f, 70f, 0xFF212121); c.restore()
                c.save(); c.rotate(24f, 62f, 56f); c.oval(52f, 42f, 74f, 70f, 0xFF212121); c.restore()
                c.dot(38f, 52f, 3f, 0xFFFFFFFF); c.dot(62f, 52f, 3f, 0xFFFFFFFF)
                c.drawLine(44f, 78f, 56f, 78f, stroke(0xFF558B2F, 2f))
            }
            "antenna" -> {
                c.bg(0xFF263238)
                c.dot(16f, 70f, 1.5f, 0xFFFFFFFF); c.dot(86f, 40f, 1.5f, 0xFFFFFFFF)
                mirrored { f ->
                    c.drawLine(mx(38f, f), 30f, mx(28f, f), 10f, stroke(0xFF7E57C2, 3f))
                    c.dot(mx(28f, f), 9f, 5f, 0xFFFFEE58)
                }
                c.dot(50f, 58f, 30f, 0xFFAB47BC)
                c.dot(39f, 54f, 10f, 0xFFFFFFFF); c.dot(61f, 54f, 10f, 0xFFFFFFFF)
                c.dot(40f, 55f, 5f, 0xFF212121); c.dot(60f, 55f, 5f, 0xFF212121)
                val smile = Path().apply { moveTo(40f, 72f); quadTo(50f, 82f, 60f, 72f) }
                c.drawPath(smile, stroke(0xFF4A148C, 3f))
            }
            "cyclops" -> {
                c.bg(0xFF004D40)
                mirrored { f ->
                    c.poly(0xFFFFE0B2, mx(32f, f), 34f, mx(34f, f), 12f, mx(46f, f), 26f)
                }
                c.dot(50f, 56f, 32f, 0xFFFF7043)
                c.dot(50f, 48f, 17f, 0xFFFFFFFF)
                c.dot(50f, 48f, 9f, 0xFF3F51B5); c.dot(50f, 48f, 4.5f, 0xFF212121)
                c.dot(46f, 44f, 2f, 0xFFFFFFFF)
                c.rrect(34f, 70f, 66f, 84f, 5f, 0xFF4E342E)
                c.poly(0xFFFFFFFF, 38f, 70f, 44f, 70f, 41f, 77f)
                c.poly(0xFFFFFFFF, 56f, 70f, 62f, 70f, 59f, 77f)
            }
            "robot" -> {
                c.bg(0xFF455A64)
                c.drawLine(50f, 12f, 50f, 28f, stroke(0xFFCFD8DC, 3f))
                c.dot(50f, 11f, 5f, 0xFFE53935)
                c.rrect(16f, 44f, 26f, 62f, 3f, 0xFF90A4AE); c.rrect(74f, 44f, 84f, 62f, 3f, 0xFF90A4AE)
                c.rrect(24f, 26f, 76f, 84f, 10f, 0xFFCFD8DC)
                c.rrect(32f, 38f, 68f, 58f, 5f, 0xFF263238)
                c.rrect(36f, 42f, 46f, 54f, 2f, 0xFF00E5FF); c.rrect(54f, 42f, 64f, 54f, 2f, 0xFF00E5FF)
                c.rrect(36f, 66f, 64f, 76f, 3f, 0xFF455A64)
                for (x in listOf(43f, 50f, 57f)) c.drawLine(x, 66f, x, 76f, stroke(0xFFCFD8DC, 1.5f))
            }
            "astronaut" -> {
                c.bg(0xFF0D1B2A)
                c.dot(16f, 20f, 1.5f, 0xFFFFFFFF); c.dot(86f, 28f, 1.5f, 0xFFFFFFFF)
                c.dot(82f, 80f, 1.5f, 0xFFFFFFFF); c.dot(18f, 78f, 1.5f, 0xFFFFFFFF)
                c.rrect(30f, 82f, 70f, 104f, 8f, 0xFFB0BEC5)
                c.dot(50f, 50f, 34f, 0xFFECEFF1)
                c.rrect(26f, 34f, 74f, 68f, 16f, 0xFF1565C0)
                c.oval(32f, 38f, 46f, 50f, 0x55FFFFFF)
                c.dot(18f, 52f, 5f, 0xFFB0BEC5); c.dot(82f, 52f, 5f, 0xFFB0BEC5)
            }
            "ufo" -> {
                c.bg(0xFF1A237E)
                c.dot(16f, 22f, 1.5f, 0xFFFFFFFF); c.dot(86f, 18f, 1.5f, 0xFFFFFFFF)
                c.dot(84f, 52f, 1.5f, 0xFFFFFFFF)
                c.poly(0x66FFF59D, 40f, 62f, 60f, 62f, 74f, 100f, 26f, 100f)
                c.drawArc(RectF(34f, 24f, 66f, 60f), 180f, 180f, true, fill(0xFF80DEEA))
                c.oval(14f, 46f, 86f, 68f, 0xFF90A4AE)
                c.dot(30f, 58f, 3f, 0xFFFFEB3B); c.dot(50f, 61f, 3f, 0xFFFFEB3B); c.dot(70f, 58f, 3f, 0xFFFFEB3B)
            }
            "comet" -> {
                c.bg(0xFF0D1B2A)
                c.dot(78f, 22f, 1.5f, 0xFFFFFFFF); c.dot(20f, 60f, 1.5f, 0xFFFFFFFF)
                c.dot(50f, 84f, 1.5f, 0xFFFFFFFF); c.dot(86f, 80f, 1.5f, 0xFFFFFFFF)
                c.poly(0xAAFF8A65, 74.5f, 55.5f, 57.5f, 72.5f, 10f, 10f)
                c.poly(0xFFFFCC80, 70f, 60f, 62f, 68f, 20f, 20f)
                c.dot(66f, 64f, 13f, 0xFFFFE0B2)
                c.dot(62f, 60f, 3f, 0xFFFFFFFF)
            }
            "earth" -> {
                c.bg(0xFF0D1B2A)
                c.dot(84f, 18f, 6f, 0xFFB0BEC5); c.dot(16f, 80f, 1.5f, 0xFFFFFFFF)
                c.dot(20f, 24f, 1.5f, 0xFFFFFFFF)
                c.dot(50f, 50f, 34f, 0xFF1E88E5)
                c.poly(0xFF66BB6A, 30f, 40f, 44f, 30f, 54f, 38f, 48f, 52f, 34f, 56f)
                c.poly(0xFF66BB6A, 56f, 56f, 70f, 50f, 74f, 64f, 62f, 74f)
                c.oval(54f, 28f, 72f, 34f, 0xAAFFFFFF); c.oval(28f, 62f, 44f, 68f, 0xAAFFFFFF)
            }
            else -> c.bg(0xFF607D8B)
        }
    }
}
