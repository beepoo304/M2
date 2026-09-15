package pl.meshcore.monitor.data

import org.junit.Assert.*
import org.junit.Test

class NodeGpsPolicyTest {
    private val key = "0652" + "0".repeat(60)
    @Test fun knownRepeaterWithoutGpsIsPurple() {
        assertTrue(NodeGpsPolicy.confirmedMissingGps("0652", listOf(NodeGpsInfo(key, false))))
    }
    @Test fun knownRepeaterWithGpsIsNotPurple() {
        assertFalse(NodeGpsPolicy.confirmedMissingGps("0652", listOf(NodeGpsInfo(key, true))))
    }
    @Test fun hashAbsentFromDirectoryIsNotNoGps() {
        assertFalse(NodeGpsPolicy.confirmedMissingGps("D358", listOf(NodeGpsInfo(key, false))))
        assertFalse(NodeGpsPolicy.confirmedMissingGps("0652", emptyList()))
    }
    @Test fun companionWithoutGpsIsNotReportedAsNoGpsRepeater() {
        assertFalse(NodeGpsPolicy.confirmedMissingGps("0652", listOf(NodeGpsInfo(key, false, false))))
    }
    @Test fun collisionNeedsResolvedIdentityBeforeReportingMissingGps() {
        val other = "0652" + "1".repeat(60)
        val directory = listOf(NodeGpsInfo(key, false), NodeGpsInfo(other, true))
        assertFalse(NodeGpsPolicy.confirmedMissingGps("0652", directory))
        assertTrue(NodeGpsPolicy.confirmedMissingGps("0652", directory, key))
        assertFalse(NodeGpsPolicy.confirmedMissingGps("0652", directory, other))
    }
    @Test fun unknownIntermediateHashDoesNotProducePurpleOrInventedLink() {
        val nodes = listOf(LocatedNode(key, 50.0, 19.0), LocatedNode("B282" + "0".repeat(60), 50.1, 19.1),
            LocatedNode("F480" + "0".repeat(60), 50.2, 19.2))
        val event = MapRouteEvent("id", "hash", 4, "", 0, listOf("0652", "D358", "B282", "F480"))
        val edges = MapRouteMapper.edges(listOf(event), nodes, emptyList())
        assertEquals(1, edges.size)
        assertFalse(edges.single().missingGps)
        assertEquals("B282", edges.single().from.hash)
        val metrics = MapRouteMapper.metrics(listOf(event), nodes, emptyList())
        assertEquals(listOf("B282", "F480"), metrics.longestRoute.map { it.hash })
        assertEquals(MapRouteMapper.distanceKm(50.1, 19.1, 50.2, 19.2), metrics.totalUniqueKm, .001)
    }
}
