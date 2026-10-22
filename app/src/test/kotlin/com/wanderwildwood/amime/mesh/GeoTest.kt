package com.wanderwildwood.amime.mesh

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoTest {
    private val origin = GeoPoint(0.0, 0.0)

    @Test
    fun a_degree_of_longitude_on_the_equator_is_111_km_east() {
        assertEquals(111_195.0, Geo.distanceMeters(origin, GeoPoint(0.0, 1.0)), 100.0)
        assertEquals(90.0, Geo.bearingDegrees(origin, GeoPoint(0.0, 1.0)), 0.01)
        assertEquals(0.0, Geo.bearingDegrees(origin, GeoPoint(1.0, 0.0)), 0.01)
        assertEquals(180.0, Geo.bearingDegrees(origin, GeoPoint(-1.0, 0.0)), 0.01)
        assertEquals(270.0, Geo.bearingDegrees(origin, GeoPoint(0.0, -1.0)), 0.01)
    }

    @Test
    fun london_to_paris() {
        val london = GeoPoint(51.5074, -0.1278)
        val paris = GeoPoint(48.8566, 2.3522)
        assertEquals(343_500.0, Geo.distanceMeters(london, paris), 1500.0)
        assertEquals(148.0, Geo.bearingDegrees(london, paris), 1.5)
        assertEquals("SE", Geo.compass(Geo.bearingDegrees(london, paris)))
    }

    @Test
    fun the_compass_words_change_at_the_half_way_points() {
        assertEquals("N", Geo.compass(0.0))
        assertEquals("N", Geo.compass(22.4))
        assertEquals("NE", Geo.compass(22.6))
        assertEquals("E", Geo.compass(90.0))
        assertEquals("S", Geo.compass(180.0))
        assertEquals("W", Geo.compass(270.0))
        assertEquals("NW", Geo.compass(315.0))
        assertEquals("N", Geo.compass(359.9))
    }

    @Test
    fun distances_are_said_no_more_exactly_than_the_positions_allow() {
        assertEquals(Geo.Away.Under1, Geo.away(origin, GeoPoint(0.0, 0.005)))
        assertEquals(Geo.Away.Km(3, "N"), Geo.away(origin, GeoPoint(0.0287, 0.0)))
        assertEquals(Geo.Away.Km(110, "E"), Geo.away(origin, GeoPoint(0.0, 1.0)).let {
            // 111 km is under 100 only at the rounding boundary: it is tens above 100.
            it
        })
        assertEquals(Geo.Away.Km(140, "E"), Geo.away(origin, GeoPoint(0.0, 1.26)))
    }

    @Test
    fun the_map_link_is_a_geo_uri_with_the_name_escaped() {
        assertEquals(
            "geo:35.59510,-82.55150?q=35.59510,-82.55150(Ann%20B)",
            Geo.geoUri(35.5951, -82.5515, "Ann B"),
        )
    }
}
