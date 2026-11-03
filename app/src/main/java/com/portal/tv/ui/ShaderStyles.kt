package com.portal.tv.ui

/**
 * The animated shader backgrounds. Every style is a GLSL fragment shader that is drawn in the
 * colors of the chosen [ShaderPalettes.Palette], so any style can be any color. A style only has
 * to define `vec3 scene(vec2 uv, vec2 p, float t)`: [uv] runs 0 to 1 across the screen, [p] is the
 * same stretched to the screen's shape, and [t] is seconds. The shared parts, such as noise and
 * the palette helpers, are in [PRELUDE].
 */
object ShaderStyles {

    class Style(val id: String, val label: String, val scene: String)

    const val DEFAULT_ID = "aurora"

    /**
     * Shared by every style. The palette's colors are dark, made to be added to a dark base, so
     * [bright] lifts them into colors a style can paint with.
     */
    const val PRELUDE = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform float uTime;
uniform vec2 uRes;
uniform vec3 uBase1;
uniform vec3 uBase2;
uniform vec3 uAurora1;
uniform vec3 uAurora2;
uniform vec3 uAurora3;

const float BOOST = 3.6;
vec3 bright(vec3 c) { return c * BOOST; }

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

float fbm(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        v += a * noise(p);
        p = p * 2.0 + vec2(17.0, 9.0);
        a *= 0.5;
    }
    return v;
}
"""

    private const val MAIN = """
void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    float aspect = uRes.x / uRes.y;
    vec2 p = vec2(uv.x * aspect, uv.y);
    vec3 color = scene(uv, p, uTime);
    // A soft vignette keeps the edges dark, so text on top stays readable.
    float d = length(uv - 0.5) * 1.4;
    color *= 1.0 - d * d * 0.5;
    gl_FragColor = vec4(color, 1.0);
}
"""

    val all: List<Style> = listOf(
        // The original: slow bands of color drifting over a dark gradient.
        Style("aurora", "Aurora", """
vec3 scene(vec2 uv, vec2 p, float t) {
    vec3 base = mix(uBase1, uBase2, dot(uv, vec2(0.7, 0.3)));
    float s = t * 0.3;
    float b1 = sin(uv.x * 2.0 + s + uv.y * 1.5) * 0.5 + 0.5;
    float b2 = sin(uv.y * 3.0 - s * 0.7 + uv.x) * 0.5 + 0.5;
    float b3 = sin((uv.x + uv.y) * 1.5 + s * 0.4) * 0.5 + 0.5;
    return base + uAurora1 * b1 + uAurora2 * b2 + uAurora3 * b3;
}
"""),
        // Colors that swirl and fold into each other, like ink in water.
        Style("flow", "Flow", """
vec3 scene(vec2 uv, vec2 p, float t) {
    vec2 q = p * 1.6;
    float s = t * 0.07;
    vec2 w = vec2(fbm(q + vec2(0.0, s)), fbm(q + vec2(5.2, 1.3) - vec2(s, 0.0)));
    float f = fbm(q + 3.2 * w + vec2(s, -s));
    vec3 c = mix(uBase1, uBase2, f);
    c += bright(uAurora1) * smoothstep(0.25, 0.85, f) * 0.9;
    c += bright(uAurora2) * w.x * w.x * 0.9;
    c += bright(uAurora3) * w.y * 0.45;
    return c;
}
"""),
        // Rolling bands, front to back, like layers of hills or swell.
        Style("waves", "Waves", """
vec3 scene(vec2 uv, vec2 p, float t) {
    vec3 c = mix(uBase2, uBase1, uv.y);
    for (int i = 0; i < 5; i++) {
        float fi = float(i);
        float y = 0.62 - fi * 0.1
            + 0.05 * sin(uv.x * (2.4 + fi * 0.9) + t * (0.35 + fi * 0.09) + fi * 1.7)
            + 0.025 * sin(uv.x * (6.0 + fi) - t * (0.5 + fi * 0.07));
        float m = smoothstep(y + 0.006, y - 0.006, uv.y);
        vec3 layer = bright(mod(fi, 3.0) < 0.5 ? uAurora1 : (mod(fi, 3.0) < 1.5 ? uAurora2 : uAurora3));
        layer *= 0.35 + 0.16 * fi;
        // Lighter at the crest, deeper below it.
        layer *= 0.7 + 0.6 * smoothstep(y - 0.25, y, uv.y);
        c = mix(c, layer, m);
    }
    return c;
}
"""),
        // Slow clouds of gas with stars that twinkle.
        Style("nebula", "Nebula", """
vec3 scene(vec2 uv, vec2 p, float t) {
    float s = t * 0.025;
    float a = fbm(p * 2.2 + vec2(s, -s * 0.6));
    float b = fbm(p * 3.4 + a * 1.8 - vec2(s * 1.3, 0.0));
    vec3 c = mix(uBase1, uBase2, uv.y);
    c += bright(uAurora1) * smoothstep(0.35, 0.95, a) * 0.85;
    c += bright(uAurora2) * smoothstep(0.4, 1.0, b) * 0.75;
    c += bright(uAurora3) * a * b * 0.6;
    // Stars: a grid of cells, each with a star in some of them.
    vec2 g = p * 70.0;
    vec2 id = floor(g);
    float h = hash(id);
    float star = step(0.985, h) * smoothstep(0.35, 0.0, length(fract(g) - 0.5));
    star *= 0.6 + 0.4 * sin(t * (1.0 + h * 3.0) + h * 40.0);
    return c + vec3(star);
}
"""),
        // Big soft glows drifting around, slowly passing in front of one another.
        Style("orbs", "Orbs", """
vec3 scene(vec2 uv, vec2 p, float t) {
    float aspect = uRes.x / uRes.y;
    vec3 c = mix(uBase1, uBase2, dot(uv, vec2(0.6, 0.4)));
    for (int i = 0; i < 6; i++) {
        float fi = float(i);
        vec2 centre = vec2(
            aspect * (0.5 + 0.42 * sin(t * (0.07 + fi * 0.021) + fi * 1.9)),
            0.5 + 0.38 * cos(t * (0.06 + fi * 0.017) + fi * 2.7));
        float r = 0.2 + 0.07 * mod(fi, 3.0);
        float d = length(p - centre) / r;
        float glow = exp(-d * d);
        vec3 col = mod(fi, 3.0) < 0.5 ? uAurora1 : (mod(fi, 3.0) < 1.5 ? uAurora2 : uAurora3);
        c += bright(col) * glow * 0.8;
    }
    return c;
}
"""),
        // Classic plasma: sine waves added together and turned into color.
        Style("plasma", "Plasma", """
vec3 scene(vec2 uv, vec2 p, float t) {
    float s = t * 0.18;
    vec2 q = p * 3.0;
    float v = sin(q.x + s) + sin(q.y * 1.3 - s * 0.8) + sin((q.x + q.y) * 0.8 + s * 0.6)
        + sin(length(q - vec2(3.0, 1.5)) * 1.2 - s);
    v = v * 0.25 + 0.5;
    vec3 c = mix(uBase1, uBase2, v);
    c += bright(uAurora1) * smoothstep(0.2, 0.8, v) * 0.5;
    c += bright(uAurora2) * (1.0 - smoothstep(0.1, 0.6, v)) * 0.5;
    c += bright(uAurora3) * sin(v * 6.2832) * sin(v * 6.2832) * 0.3;
    return c;
}
"""),
        // Soft blobs that merge and pull apart, like a lava lamp.
        Style("lava", "Lava", """
vec3 scene(vec2 uv, vec2 p, float t) {
    float aspect = uRes.x / uRes.y;
    float f = 0.0;
    for (int i = 0; i < 6; i++) {
        float fi = float(i);
        vec2 c = vec2(aspect * (0.5 + 0.32 * sin(t * (0.11 + fi * 0.037) + fi * 2.1)),
                      0.5 + 0.42 * sin(t * (0.09 + fi * 0.029) + fi * 1.3 + 1.0));
        float r = 0.11 + 0.03 * mod(fi, 3.0);
        float d = length(p - c);
        f += (r * r) / (d * d + 0.0008);
    }
    vec3 col = mix(uBase1, uBase2, uv.y);
    float body = smoothstep(0.9, 1.3, f);
    float rim = smoothstep(0.6, 0.9, f) * (1.0 - body);
    col += bright(uAurora1) * body * 0.9 + bright(uAurora2) * rim * 0.5;
    col += bright(uAurora3) * smoothstep(1.3, 3.0, f) * 0.5;
    return col;
}
"""),
        // Rings spreading out across the screen, as if from raindrops on still water.
        Style("ripples", "Ripples", """
vec3 scene(vec2 uv, vec2 p, float t) {
    float aspect = uRes.x / uRes.y;
    vec3 col = mix(uBase1, uBase2, uv.y);
    for (int i = 0; i < 5; i++) {
        float fi = float(i);
        float k = t / (7.0 + fi * 1.3) + fi * 0.37;
        float cyc = floor(k);
        float ph = fract(k);
        vec2 centre = vec2(aspect * hash(vec2(cyc, fi)), hash(vec2(fi, cyc + 3.0)));
        float x = (length(p - centre) - ph * 0.45) * 26.0;
        float ring = exp(-x * x) * (1.0 - ph);
        vec3 tint = mod(fi, 3.0) < 0.5 ? uAurora1 : (mod(fi, 3.0) < 1.5 ? uAurora2 : uAurora3);
        col += bright(tint) * ring * 0.9;
    }
    return col;
}
"""),
        // Tall curtains of light that sway, like the northern lights.
        Style("curtains", "Curtains", """
vec3 scene(vec2 uv, vec2 p, float t) {
    vec3 col = mix(uBase2, uBase1, uv.y);
    float s = t * 0.12;
    for (int i = 0; i < 3; i++) {
        float fi = float(i);
        float x = uv.x * (3.0 + fi) + sin(uv.y * 2.0 + s * (1.0 + fi * 0.4) + fi * 2.0) * 0.6
            + fbm(vec2(uv.x * 2.0 + fi * 7.0, s)) * 1.2;
        float s1 = abs(sin(x * 3.14159));
        float band = s1 * s1 * s1 * s1 * s1 * s1;
        float fade = smoothstep(0.15, 0.65, uv.y) * smoothstep(1.05, 0.55, uv.y);
        vec3 tint = i == 0 ? uAurora1 : (i == 1 ? uAurora2 : uAurora3);
        col += bright(tint) * band * fade * 0.55;
    }
    return col;
}
"""),
        // Out-of-focus lights floating slowly upward.
        Style("bokeh", "Bokeh", """
vec3 scene(vec2 uv, vec2 p, float t) {
    float aspect = uRes.x / uRes.y;
    vec3 col = mix(uBase1, uBase2, uv.y);
    for (int i = 0; i < 14; i++) {
        float fi = float(i);
        float h = hash(vec2(fi, 7.3));
        float h2 = hash(vec2(fi, 2.1));
        vec2 centre = vec2(aspect * fract(h + 0.03 * sin(t * 0.1 + fi)), fract(h2 + t * (0.012 + 0.02 * h)));
        float r = 0.04 + 0.07 * hash(vec2(fi, 5.5));
        float d = length(p - centre) / r;
        float disc = smoothstep(1.0, 0.85, d) * 0.3 + smoothstep(1.0, 0.92, d) * smoothstep(0.7, 1.0, d) * 0.35;
        float m = mod(fi, 3.0);
        vec3 tint = m < 0.5 ? uAurora1 : (m < 1.5 ? uAurora2 : uAurora3);
        col += bright(tint) * disc * (0.5 + 0.5 * hash(vec2(fi, 9.9)));
    }
    return col;
}
"""),
        // Rings of light rushing toward you down a tunnel.
        Style("tunnel", "Tunnel", """
vec3 scene(vec2 uv, vec2 p, float t) {
    float aspect = uRes.x / uRes.y;
    vec2 q = p - vec2(aspect * 0.5, 0.5);
    float r = length(q);
    float a = atan(q.y, q.x);
    float v = 0.3 / (r + 0.05) - t * 0.35;
    float rings = 0.5 + 0.5 * sin(v * 6.0 + sin(a * 3.0 + t * 0.2) * 1.5);
    vec3 col = mix(uBase1, uBase2, smoothstep(0.0, 0.8, r));
    col += bright(uAurora1) * rings * rings * smoothstep(0.02, 0.4, r) * 0.7;
    col += bright(uAurora2) * (0.5 + 0.5 * sin(a * 4.0 + v * 2.0 + t * 0.15)) * 0.25 * smoothstep(0.0, 0.5, r);
    col += bright(uAurora3) * exp(-r * 5.0) * 0.4;
    return col;
}
""")
    )

    fun find(id: String?): Style = all.firstOrNull { it.id == id } ?: all.first()

    /** The full fragment shader for [style]. */
    fun fragmentSource(style: Style): String = PRELUDE + style.scene + MAIN
}
