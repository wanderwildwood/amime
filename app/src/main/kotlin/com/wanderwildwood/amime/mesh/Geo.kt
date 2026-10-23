package com.wanderwildwood.amime.mesh

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Distance and direction between two places, and how to say them. */
object Geo {
    private const val EARTH_M = 6_371_000.0

    /** Great-circle distance in metres. */
    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val p1 = Math.toRadians(a.latitude)
        val p2 = Math.toRadians(b.latitude)
        val dp = p2 - p1
        val dl = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Initial bearing from [a] to [b], 0..360 degrees clockwise from north. */
    fun bearingDegrees(a: GeoPoint, b: GeoPoint): Double {
        val p1 = Math.toRadians(a.latitude)
        val p2 = Math.toRadians(b.latitude)
        val dl = Math.toRadians(b.longitude - a.longitude)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private val POINTS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    /** The compass word for a bearing: N, NE, E and so on. */
    fun compass(degrees: Double): String = POINTS[((degrees + 22.5) / 45.0).toInt() % 8]

    /**
     * Distance rounded to what the positions can honestly support. A node on the default
     * channel shares its position to about 3 km, so metres would be a claim nobody can
     * back: under a kilometre says so, below ten it is whole kilometres, above that tens.
     */
    sealed interface Away {
        data object Under1 : Away
        data class Km(val km: Int, val direction: String) : Away
    }

    fun away(from: GeoPoint, to: GeoPoint): Away {
        val m = distanceMeters(from, to)
        if (m < 1000.0) return Away.Under1
        val km = m / 1000.0
        val rounded = if (km < 100.0) km.roundToInt() else (km / 10.0).roundToInt() * 10
        return Away.Km(rounded, compass(bearingDegrees(from, to)))
    }

    /**
     * [people] with those whose position is known first, nearest to [here] at the top. The
     * rest keep the order they came in, after them. The sort is stable, so two at the same
     * distance stay as they were.
     */
    fun nearestFirst(here: GeoPoint, people: List<Person>): List<Person> {
        val (placed, unplaced) = people.partition { it.latitude != null && it.longitude != null }
        return placed.sortedBy { distanceMeters(here, GeoPoint(it.latitude!!, it.longitude!!)) } + unplaced
    }

    /** The system's map chooser takes this. */
    fun geoUri(lat: Double, lon: Double, label: String): String =
        "geo:%.5f,%.5f?q=%.5f,%.5f(%s)".format(java.util.Locale.US, lat, lon, lat, lon, java.net.URLEncoder.encode(label, "UTF-8").replace("+", "%20"))
}
