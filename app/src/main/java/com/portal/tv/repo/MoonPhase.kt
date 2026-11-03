package com.portal.tv.repo

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The moon's phase and rise and set times, worked out on the TV. Open-Meteo has no moon data.
 *
 * Positions come from the low-precision formulas in Paul Schlyter's "How to compute planetary
 * positions" (with the moon's main perturbations), good to about a degree. That puts phases within
 * an hour or two and rise and set within a few minutes, plenty for a weather page.
 * Free of Android types so it can be unit tested.
 */
object MoonPhase {

    const val SYNODIC_DAYS = 29.530588853

    /** The eight named phases, in order from new moon. */
    val NAMES = listOf(
        "New Moon", "Waxing Crescent", "First Quarter", "Waxing Gibbous",
        "Full Moon", "Waning Gibbous", "Last Quarter", "Waning Crescent"
    )

    /** What the moon looks like at one moment. */
    data class State(
        /** Degrees the moon is ahead of the sun along the ecliptic: 0 new, 90 first quarter, 180 full. */
        val elongation: Double,
        /** Fraction of the disc lit, 0 to 1. */
        val illumination: Double,
        /** Days since the last new moon (approximate, from [elongation]). */
        val ageDays: Double
    ) {
        /** 0 to 7, as an index into [NAMES]. */
        val bucket: Int get() = Math.floorMod(Math.round(elongation / 45.0).toInt(), 8)
        val name: String get() = NAMES[bucket]
        val waxing: Boolean get() = elongation < 180.0
    }

    fun stateAt(epochMillis: Long): State {
        val m = position(epochMillis)
        val elong = norm360(m.lon - sunLongitude(epochMillis))
        return State(elong, (1.0 - cos(Math.toRadians(elong))) / 2.0, elong / 360.0 * SYNODIC_DAYS)
    }

    /** The next moment after [from] when the moon is new (target 0) or full (target 180), in epoch millis. */
    fun nextPhase(from: Long, full: Boolean): Long {
        val target = if (full) 180.0 else 0.0
        fun diff(t: Long) = signedAngle(stateAt(t).elongation - target)
        val step = 3_600_000L
        var t = from
        // The elongation grows by about 12 degrees a day, so an hourly scan can't skip the crossing.
        var previous = diff(t)
        repeat(40 * 24) {
            val next = t + step
            val d = diff(next)
            if (previous < 0 && d >= 0) {
                var lo = t
                var hi = next
                repeat(20) {
                    val mid = (lo + hi) / 2
                    if (diff(mid) < 0) lo = mid else hi = mid
                }
                return hi
            }
            previous = d
            t = next
        }
        return from
    }

    /** Moonrise and moonset (epoch millis) on one local day; either may be null when the moon doesn't cross the horizon. */
    data class RiseSet(val rise: Long?, val set: Long?)

    /**
     * Rise and set within the 24 hours from [dayStart] (local midnight, as epoch millis) for a
     * place at [latitude] and [longitude] (degrees, east positive).
     */
    fun riseSet(dayStart: Long, latitude: Double, longitude: Double): RiseSet {
        fun altitudeAboveHorizon(t: Long): Double {
            val m = position(t)
            val parallax = Math.toDegrees(asin(1.0 / m.distance))
            // The horizon for the moon's upper limb, allowing for refraction and parallax.
            val horizon = 0.7275 * parallax - 0.5667
            return altitude(t, m, latitude, longitude) - horizon
        }
        var rise: Long? = null
        var set: Long? = null
        val step = 10 * 60_000L
        var t = dayStart
        var previous = altitudeAboveHorizon(t)
        while (t < dayStart + 24 * 3_600_000L) {
            val next = t + step
            val a = altitudeAboveHorizon(next)
            if (previous < 0 && a >= 0 && rise == null) rise = t + (step * (-previous / (a - previous))).toLong()
            if (previous >= 0 && a < 0 && set == null) set = t + (step * (previous / (previous - a))).toLong()
            previous = a
            t = next
        }
        return RiseSet(rise, set)
    }

    /** How the moon is turned in the sky for an observer, so the picture can be rotated to match. */
    data class Tilt(
        /** Degrees to turn a picture with north up so that the zenith is up: positive is clockwise. */
        val rotation: Double,
        /** Where the lit edge points, in degrees from celestial north through east (90 is east, 270 west). */
        val brightLimb: Double
    )

    /** The moon's tilt at [epochMillis] for a place at [latitude] and [longitude] (degrees, east positive). */
    fun tiltAt(epochMillis: Long, latitude: Double, longitude: Double): Tilt {
        val m = position(epochMillis)
        val (ra, dec) = equatorial(epochMillis, m.lon, m.lat)
        val (sunRa, sunDec) = equatorial(epochMillis, sunLongitude(epochMillis), 0.0)

        // Position angle of the bright limb, from the moon's and the sun's places on the sky.
        val dRa = sunRa - ra
        val brightLimb = norm360(Math.toDegrees(atan2(
            cos(sunDec) * sin(dRa),
            sin(sunDec) * cos(dec) - cos(sunDec) * sin(dec) * cos(dRa)
        )))

        // The parallactic angle: how far celestial north is turned from straight up at the moon.
        val hourAngle = siderealRadians(epochMillis, longitude) - ra
        val phi = rad(latitude)
        val q = Math.toDegrees(atan2(sin(hourAngle), tan(phi) * cos(dec) - sin(dec) * cos(hourAngle)))
        return Tilt(rotation = q, brightLimb = brightLimb)
    }

    // ---------- Astronomy ----------

    private class MoonPos(val lon: Double, val lat: Double, val distance: Double)

    /** Days since 1999 Dec 31 0:00 UT, the epoch of the formulas. */
    private fun days(epochMillis: Long): Double = epochMillis / 86_400_000.0 + 2440587.5 - 2451543.5

    private fun rad(deg: Double) = Math.toRadians(deg)
    private fun norm360(deg: Double) = ((deg % 360.0) + 360.0) % 360.0
    private fun signedAngle(deg: Double) = norm360(deg + 180.0) - 180.0

    private fun sunLongitude(epochMillis: Long): Double {
        val d = days(epochMillis)
        val w = 282.9404 + 4.70935e-5 * d
        val e = 0.016709 - 1.151e-9 * d
        val mean = norm360(356.0470 + 0.9856002585 * d)
        val ea = mean + Math.toDegrees(e) * sin(rad(mean)) * (1.0 + e * cos(rad(mean)))
        val xv = cos(rad(ea)) - e
        val yv = sqrt(1.0 - e * e) * sin(rad(ea))
        return norm360(Math.toDegrees(atan2(yv, xv)) + w)
    }

    private fun position(epochMillis: Long): MoonPos {
        val d = days(epochMillis)
        val n = norm360(125.1228 - 0.0529538083 * d)
        val i = 5.1454
        val w = norm360(318.0634 + 0.1643573223 * d)
        val a = 60.2666
        val e = 0.054900
        val mm = norm360(115.3654 + 13.0649929509 * d)

        var ea = mm + Math.toDegrees(e) * sin(rad(mm)) * (1.0 + e * cos(rad(mm)))
        repeat(5) {
            ea -= (ea - Math.toDegrees(e) * sin(rad(ea)) - mm) / (1.0 - e * cos(rad(ea)))
        }
        val xv = a * (cos(rad(ea)) - e)
        val yv = a * sqrt(1.0 - e * e) * sin(rad(ea))
        val v = Math.toDegrees(atan2(yv, xv))
        val r = sqrt(xv * xv + yv * yv)

        val vw = rad(v + w)
        val xh = r * (cos(rad(n)) * cos(vw) - sin(rad(n)) * sin(vw) * cos(rad(i)))
        val yh = r * (sin(rad(n)) * cos(vw) + cos(rad(n)) * sin(vw) * cos(rad(i)))
        val zh = r * sin(vw) * sin(rad(i))
        var lon = Math.toDegrees(atan2(yh, xh))
        var lat = Math.toDegrees(atan2(zh, sqrt(xh * xh + yh * yh)))

        // The moon's largest wobbles caused by the sun.
        val ms = norm360(356.0470 + 0.9856002585 * d)
        val ws = 282.9404 + 4.70935e-5 * d
        val lm = mm + w + n
        val ls = ms + ws
        val dd = lm - ls
        val f = lm - n
        fun s(x: Double) = sin(rad(x))
        fun c(x: Double) = cos(rad(x))
        lon += -1.274 * s(mm - 2 * dd) + 0.658 * s(2 * dd) - 0.186 * s(ms) - 0.059 * s(2 * mm - 2 * dd) -
            0.057 * s(mm - 2 * dd + ms) + 0.053 * s(mm + 2 * dd) + 0.046 * s(2 * dd - ms) +
            0.041 * s(mm - ms) - 0.035 * s(dd) - 0.031 * s(mm + ms) - 0.015 * s(2 * f - 2 * dd) +
            0.011 * s(mm - 4 * dd)
        lat += -0.173 * s(f - 2 * dd) - 0.055 * s(mm - f - 2 * dd) - 0.046 * s(mm + f - 2 * dd) +
            0.033 * s(f + 2 * dd) + 0.017 * s(2 * mm + f)
        val distance = r - 0.58 * c(mm - 2 * dd) - 0.46 * c(2 * dd)
        return MoonPos(norm360(lon), lat, distance)
    }

    /** Right ascension and declination, in radians, of a body at ecliptic [lonDeg] and [latDeg]. */
    private fun equatorial(epochMillis: Long, lonDeg: Double, latDeg: Double): Pair<Double, Double> {
        val ecl = rad(23.4393 - 3.563e-7 * days(epochMillis))
        val xg = cos(rad(lonDeg)) * cos(rad(latDeg))
        val yg = sin(rad(lonDeg)) * cos(rad(latDeg))
        val zg = sin(rad(latDeg))
        val ye = yg * cos(ecl) - zg * sin(ecl)
        val ze = yg * sin(ecl) + zg * cos(ecl)
        return atan2(ye, xg) to atan2(ze, sqrt(xg * xg + ye * ye))
    }

    /** The local sidereal time, in radians, at [lonDeg] east. */
    private fun siderealRadians(epochMillis: Long, lonDeg: Double): Double {
        val jd = epochMillis / 86_400_000.0 + 2440587.5
        val gmstHours = ((18.697374558 + 24.06570982441908 * (jd - 2451545.0)) % 24.0 + 24.0) % 24.0
        return rad(gmstHours * 15.0 + lonDeg)
    }

    /** The moon's height above the horizon in degrees, for an observer at [lat] and [lon]. */
    private fun altitude(epochMillis: Long, m: MoonPos, lat: Double, lon: Double): Double {
        val (ra, dec) = equatorial(epochMillis, m.lon, m.lat)
        val hourAngle = siderealRadians(epochMillis, lon) - ra
        val sinAlt = sin(rad(lat)) * sin(dec) + cos(rad(lat)) * cos(dec) * cos(hourAngle)
        return Math.toDegrees(asin(sinAlt.coerceIn(-1.0, 1.0)))
    }

    /** Whole days from [from] to [to], rounded down but never below 0, for "in 5 days". */
    fun daysUntil(from: Long, to: Long): Int = maxOf(0, floor((to - from) / 86_400_000.0).toInt())
}
