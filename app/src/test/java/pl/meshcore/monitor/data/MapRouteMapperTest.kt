package pl.meshcore.monitor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapRouteMapperTest {
    private val nodes = listOf(
        LocatedNode("06520000", 50.20, 19.00),
        LocatedNode("B2820000", 50.25, 19.05),
        LocatedNode("F4800000", 50.30, 19.10),
    )

    @Test fun mapsTwoByteRouteAsConfirmedEdges() {
        val event = MapRouteEvent("1", "hash", 4, "", 1, listOf("0652", "B282", "F480"))
        val edges = MapRouteMapper.edges(listOf(event), nodes)
        assertEquals(2, edges.size)
        assertTrue(edges.all { !it.uncertain })
    }

    @Test fun mapsOneByteRouteAsUncertainEdges() {
        val event = MapRouteEvent("1", "hash", 4, "", 1, listOf("06", "B2", "F4"))
        val edges = MapRouteMapper.edges(listOf(event), nodes)
        assertEquals(2, edges.size)
        assertTrue(edges.all { it.uncertain })
        assertFalse(edges.any { it.from.hash.length != 4 || it.to.hash.length != 4 })
    }

    @Test fun longestRouteIgnoresLongerBranchThatDoesNotBelongToSelectedKey() {
        val belonging = MapRouteEvent("packet", "hash", 4, "", 1,
            listOf("0652", "B282"), longestRouteEligible = true)
        val unrelatedLongerBranch = MapRouteEvent("packet", "hash", 4, "", 1,
            listOf("B282", "F480"), longestRouteEligible = false)

        val metrics = MapRouteMapper.metrics(listOf(belonging, unrelatedLongerBranch), nodes)

        assertEquals(listOf("0652", "B282"), metrics.longestRoute.map { it.hash })
    }

    @Test fun uncertainRouteNeverBecomesLongestRoute() {
        val uncertain = MapRouteEvent("packet", "hash", 2, "", 1,
            listOf("0652", "F480"), uncertainAttribution = true, longestRouteEligible = true)

        assertTrue(MapRouteMapper.metrics(listOf(uncertain), nodes).longestRoute.isEmpty())
    }
}
