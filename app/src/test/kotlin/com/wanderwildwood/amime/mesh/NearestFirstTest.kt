package com.wanderwildwood.amime.mesh

import org.junit.Assert.assertEquals
import org.junit.Test

class NearestFirstTest {
    private val here = GeoPoint(35.0, -82.0)
    private fun person(name: String, lat: Double?, lon: Double?) =
        Person(prefix = listOf(name.first().code.toByte()), name = name, type = 1, pathKnown = true, lastHeard = 0, latitude = lat, longitude = lon)

    @Test
    fun the_nearest_come_first_and_those_without_a_position_keep_their_order_after_them() {
        val far = person("far", 36.0, -82.0)
        val none1 = person("none1", null, null)
        val near = person("near", 35.01, -82.0)
        val none2 = person("none2", null, null)
        val mid = person("mid", 35.3, -82.0)
        val sorted = Geo.nearestFirst(here, listOf(far, none1, near, none2, mid))
        assertEquals(listOf("near", "mid", "far", "none1", "none2"), sorted.map { it.name })
    }

    @Test
    fun the_same_distance_stays_in_the_order_it_came() {
        val a = person("a", 35.1, -82.0)
        val b = person("b", 35.1, -82.0)
        assertEquals(listOf("a", "b"), Geo.nearestFirst(here, listOf(a, b)).map { it.name })
        assertEquals(listOf("b", "a"), Geo.nearestFirst(here, listOf(b, a)).map { it.name })
    }

    @Test
    fun an_empty_list_is_empty() {
        assertEquals(emptyList<Person>(), Geo.nearestFirst(here, emptyList()))
    }
}
