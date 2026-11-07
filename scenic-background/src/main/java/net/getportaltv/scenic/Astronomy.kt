package net.getportaltv.scenic

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the sun and moon are, and how the moon looks, for a place and a moment. Low-precision formulas (a fraction of a
 * degree), which is plenty for a picture on a wall. Positions are as seen from the ground: altitude above the horizon and
 * azimuth from north, clockwise.
 */
object Astronomy {

    class AltAz(val altDeg: Double, val azDeg: Double)

    /** The moon as drawn: where it is, how much is lit, and the way the lit side points on screen. */
    class MoonView(
        val altDeg: Double,
        val azDeg: Double,
        /** Fraction of the disc lit, 0 to 1. */
        val illumination: Double,
        /** Degrees: 0 at full moon, 180 at new moon. */
        val phaseAngleDeg: Double,
        /** Where the lit side points on screen: x right and y up, a unit vector. */
        val litX: Double,
        val litY: Double,
        /** Days since the new moon, roughly. */
        val ageDays: Double
    )

    private const val J2000_MS = 946728000000L   // 2000-01-01 12:00 UTC
    private const val SYNODIC = 29.530588853

    private fun rad(deg: Double) = deg * PI / 180.0
    private fun deg(rad: Double) = rad * 180.0 / PI
    private fun wrap360(d: Double) = ((d % 360.0) + 360.0) % 360.0

    fun daysSinceJ2000(ms: Long): Double = (ms - J2000_MS) / 86_400_000.0

    /** Ecliptic longitude of the sun, degrees. */
    private fun sunLongitude(d: Double): Double {
        val l = 280.460 + 0.9856474 * d
        val g = rad(357.528 + 0.9856003 * d)
        return wrap360(l + 1.915 * sin(g) + 0.020 * sin(2 * g))
    }

    private class Ecliptic(val lonDeg: Double, val latDeg: Double)

    private fun moonEcliptic(d: Double): Ecliptic {
        val l = 218.316 + 13.176396 * d
        val m = rad(134.963 + 13.064993 * d)
        val f = rad(93.272 + 13.229350 * d)
        return Ecliptic(wrap360(l + 6.289 * sin(m)), 5.128 * sin(f))
    }

    private fun obliquity(d: Double) = rad(23.439 - 0.0000004 * d)

    /** Horizontal position of a point given by ecliptic longitude and latitude. */
    private fun altAz(d: Double, lonDeg: Double, latDeg: Double, placeLat: Double, placeLon: Double): AltAz {
        val eps = obliquity(d)
        val lam = rad(lonDeg)
        val beta = rad(latDeg)
        val ra = atan2(sin(lam) * cos(eps) - kotlin.math.tan(beta) * sin(eps), cos(lam))
        val dec = asin(sin(beta) * cos(eps) + cos(beta) * sin(eps) * sin(lam))
        val lst = rad(wrap360(280.46061837 + 360.98564736629 * d + placeLon))
        val h = lst - ra
        val phi = rad(placeLat)
        val alt = asin(sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(h))
        val az = atan2(sin(h), cos(h) * sin(phi) - kotlin.math.tan(dec) * cos(phi)) + PI
        return AltAz(deg(alt), wrap360(deg(az)))
    }

    fun sun(ms: Long, lat: Double, lon: Double): AltAz {
        val d = daysSinceJ2000(ms)
        return altAz(d, sunLongitude(d), 0.0, lat, lon)
    }

    /** How far the moon is ahead of the sun along the ecliptic: 0 new, 90 first quarter, 180 full. */
    fun elongationDeg(ms: Long): Double {
        val d = daysSinceJ2000(ms)
        return wrap360(moonEcliptic(d).lonDeg - sunLongitude(d))
    }

    fun moon(ms: Long, lat: Double, lon: Double): MoonView {
        val d = daysSinceJ2000(ms)
        val mEc = moonEcliptic(d)
        val m = altAz(d, mEc.lonDeg, mEc.latDeg, lat, lon)
        val s = altAz(d, sunLongitude(d), 0.0, lat, lon)
        val elongation = wrap360(mEc.lonDeg - sunLongitude(d))
        // The angle between the sun and moon as seen from Earth is the elongation (folded to 0..180); the phase angle is what's left.
        val folded = if (elongation > 180) 360 - elongation else elongation
        val phaseAngle = 180.0 - folded
        val illumination = (1 + cos(rad(phaseAngle))) / 2

        // The lit side points toward the sun along the sky: the bearing from the moon to the sun, taking altitude as
        // latitude and azimuth as longitude, so "up" on screen is toward the zenith and right is increasing azimuth.
        val a1 = rad(m.altDeg)
        val a2 = rad(s.altDeg)
        val dAz = rad(s.azDeg - m.azDeg)
        val bx = sin(dAz) * cos(a2)
        val by = cos(a1) * sin(a2) - sin(a1) * cos(a2) * cos(dAz)
        val len = sqrt(bx * bx + by * by)
        val litX = if (len < 1e-9) 0.0 else bx / len
        val litY = if (len < 1e-9) 1.0 else by / len
        return MoonView(m.altDeg, m.azDeg, illumination, phaseAngle, litX, litY, elongation / 360.0 * SYNODIC)
    }
}
