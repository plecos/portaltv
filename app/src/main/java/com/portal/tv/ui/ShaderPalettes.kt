package com.portal.tv.ui

/** Color schemes for the animated shader background. */
object ShaderPalettes {

    /**
     * @param base1 and [base2] form the dark base gradient; [aurora1]..[aurora3] are the moving bands.
     * @param swatch the bright color shown in the picker.
     */
    class Palette(
        val id: String,
        val label: String,
        val swatch: Int,
        val base1: FloatArray,
        val base2: FloatArray,
        val aurora1: FloatArray,
        val aurora2: FloatArray,
        val aurora3: FloatArray
    )

    private fun c(r: Float, g: Float, b: Float) = floatArrayOf(r, g, b)

    const val DEFAULT_ID = "aurora"

    val all = listOf(
        // The original deep navy and purple look.
        Palette("aurora", "Aurora", 0xFF7E57C2.toInt(),
            c(0.04f, 0.04f, 0.10f), c(0.06f, 0.04f, 0.14f),
            c(0.08f, 0.05f, 0.20f), c(0.03f, 0.08f, 0.15f), c(0.05f, 0.04f, 0.18f)),
        Palette("ocean", "Ocean", 0xFF1E88E5.toInt(),
            c(0.02f, 0.05f, 0.10f), c(0.03f, 0.07f, 0.14f),
            c(0.02f, 0.10f, 0.20f), c(0.02f, 0.12f, 0.15f), c(0.03f, 0.08f, 0.18f)),
        Palette("forest", "Forest", 0xFF43A047.toInt(),
            c(0.02f, 0.07f, 0.05f), c(0.03f, 0.09f, 0.06f),
            c(0.03f, 0.14f, 0.08f), c(0.05f, 0.12f, 0.06f), c(0.02f, 0.10f, 0.10f)),
        Palette("ember", "Ember", 0xFFE64A19.toInt(),
            c(0.10f, 0.03f, 0.03f), c(0.14f, 0.04f, 0.03f),
            c(0.20f, 0.06f, 0.03f), c(0.16f, 0.08f, 0.02f), c(0.18f, 0.04f, 0.06f)),
        Palette("rose", "Rose", 0xFFD81B60.toInt(),
            c(0.10f, 0.03f, 0.07f), c(0.14f, 0.04f, 0.09f),
            c(0.20f, 0.05f, 0.12f), c(0.15f, 0.04f, 0.16f), c(0.18f, 0.05f, 0.10f)),
        Palette("gold", "Gold", 0xFFFFB300.toInt(),
            c(0.09f, 0.07f, 0.02f), c(0.12f, 0.09f, 0.03f),
            c(0.18f, 0.13f, 0.03f), c(0.15f, 0.10f, 0.02f), c(0.17f, 0.12f, 0.04f)),
        Palette("slate", "Slate", 0xFF78909C.toInt(),
            c(0.05f, 0.05f, 0.06f), c(0.07f, 0.07f, 0.09f),
            c(0.10f, 0.10f, 0.13f), c(0.08f, 0.09f, 0.11f), c(0.09f, 0.09f, 0.12f)),
        Palette("indigo", "Indigo", 0xFF3949AB.toInt(),
            c(0.03f, 0.03f, 0.10f), c(0.04f, 0.04f, 0.14f),
            c(0.06f, 0.07f, 0.22f), c(0.04f, 0.06f, 0.17f), c(0.08f, 0.05f, 0.20f)),
        Palette("violet", "Violet", 0xFF8E24AA.toInt(),
            c(0.07f, 0.03f, 0.10f), c(0.09f, 0.04f, 0.13f),
            c(0.14f, 0.05f, 0.20f), c(0.10f, 0.04f, 0.17f), c(0.16f, 0.05f, 0.14f)),
        Palette("teal", "Teal", 0xFF00ACC1.toInt(),
            c(0.02f, 0.07f, 0.08f), c(0.03f, 0.09f, 0.10f),
            c(0.02f, 0.13f, 0.14f), c(0.03f, 0.10f, 0.12f), c(0.02f, 0.12f, 0.09f)),
        Palette("arctic", "Arctic", 0xFF80DEEA.toInt(),
            c(0.04f, 0.06f, 0.09f), c(0.05f, 0.08f, 0.12f),
            c(0.08f, 0.14f, 0.20f), c(0.06f, 0.12f, 0.18f), c(0.10f, 0.11f, 0.20f)),
        Palette("lime", "Lime", 0xFFC0CA33.toInt(),
            c(0.05f, 0.07f, 0.02f), c(0.07f, 0.09f, 0.03f),
            c(0.12f, 0.15f, 0.03f), c(0.09f, 0.13f, 0.04f), c(0.10f, 0.12f, 0.06f)),
        Palette("sunset", "Sunset", 0xFFFF7043.toInt(),
            c(0.09f, 0.04f, 0.07f), c(0.12f, 0.05f, 0.08f),
            c(0.20f, 0.07f, 0.05f), c(0.18f, 0.05f, 0.12f), c(0.14f, 0.06f, 0.16f)),
        Palette("copper", "Copper", 0xFFBF7B45.toInt(),
            c(0.08f, 0.05f, 0.03f), c(0.11f, 0.07f, 0.04f),
            c(0.18f, 0.10f, 0.05f), c(0.15f, 0.09f, 0.04f), c(0.13f, 0.08f, 0.06f)),
        Palette("crimson", "Crimson", 0xFFC62828.toInt(),
            c(0.09f, 0.02f, 0.03f), c(0.12f, 0.03f, 0.04f),
            c(0.19f, 0.04f, 0.05f), c(0.15f, 0.03f, 0.07f), c(0.17f, 0.05f, 0.04f))
    )

    fun find(id: String?): Palette = all.firstOrNull { it.id == id } ?: all.first()
}
