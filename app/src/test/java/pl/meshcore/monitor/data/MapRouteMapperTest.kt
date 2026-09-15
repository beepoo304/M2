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

    @Test fun excludesOneByteRouteCompletely() {
        val event = MapRouteEvent("1", "hash", 4, "", 1, listOf("06", "B2", "F4"))
        val edges = MapRouteMapper.edges(listOf(event), nodes)
        assertTrue(edges.isEmpty())
    }

    @Test fun longestRouteIgnoresLongerBranchThatDoesNotBelongToSelectedKey() {
        val belonging = MapRouteEvent("packet", "hash", 4, "", 1,
            listOf("0652", "B282"), longestRouteEligible = true)
        val unrelatedLongerBranch = MapRouteEvent("packet", "hash", 4, "", 1,
            listOf("B282", "F480"), longestRouteEligible = false)

        val metrics = MapRouteMapper.metrics(listOf(belonging, unrelatedLongerBranch), nodes)

        assertEquals(listOf("0652", "B282"), metrics.longestRoute.map { it.hash })
    }

    @Test fun importedPacketTreeKeepsOnlyRoutesTouchingSelectedKey() {
        val selectedKey = "0652000000000000000000000000000000000000000000000000000000000000"
        val reachesKey = MapRouteEvent("same-packet", "hash", 4, "", 1,
            listOf("B282", "0652"))
        val otherBranch = MapRouteEvent("same-packet", "hash", 4, "", 1,
            listOf("B282", "F480"))
        val unobservedReply = MapRouteEvent("reply", "hash", 5, "", 1,
            listOf("B282", "F480"), replyToSelected = true)
        val observedReply = MapRouteEvent("reply", "hash", 5, "", 1,
            listOf("B282", "0652"), replyToSelected = true)

        val selected = listOf(reachesKey, otherBranch, unobservedReply, observedReply)
            .filter { MapRouteScope.includes(it, selectedKey) }

        assertEquals(listOf(reachesKey, observedReply), selected)
        assertEquals(listOf("B282", "0652"), MapRouteMapper.metrics(selected, nodes).longestRoute.map { it.hash })
    }

    @Test fun uncertainRouteNeverBecomesLongestRoute() {
        val uncertain = MapRouteEvent("packet", "hash", 2, "", 1,
            listOf("0652", "F480"), uncertainAttribution = true, longestRouteEligible = true)

        assertTrue(MapRouteMapper.metrics(listOf(uncertain), nodes).longestRoute.isEmpty())
    }

    @Test fun replyUsesOrangeEdgesButNeverDrawsInferredHopOrSetsLongestRoute() {
        val reply = MapRouteEvent("reply", "hash", 5, "", 1,
            listOf("0652", "B282"), replyToSelected = true, longestRouteEligible = false)
        val inferred = MapRouteEvent("reply", "hash", 5, "", 1,
            listOf("B282", "F480"), replyToSelected = true, inferredLastHop = true,
            longestRouteEligible = false)

        val edges = MapRouteMapper.edges(listOf(reply, inferred), nodes)
        val metrics = MapRouteMapper.metrics(listOf(reply, inferred), nodes)

        assertEquals(1, edges.size)
        assertTrue(edges.all { it.reply })
        assertEquals(MapRouteMapper.distanceKm(50.20, 19.00, 50.25, 19.05), metrics.totalUniqueKm, 0.001)
        assertTrue(metrics.longestRoute.isEmpty())
    }

    @Test fun missingGpsUsesUnmeasuredPurpleBridge() {
        val reply = MapRouteEvent("reply", "hash", 5, "", 1,
            listOf("0652", "B282", "F480"), replyToSelected = true)
        val nodesWithoutMiddleRepeater = nodes.filterNot { it.publicKey.startsWith("B282") }

        val edges = MapRouteMapper.edges(listOf(reply), nodesWithoutMiddleRepeater,
            listOf(NodeGpsInfo(nodes.first { it.publicKey.startsWith("B282") }.publicKey, false)))
        assertEquals(1, edges.size)
        assertTrue(edges.single().missingGps)
        assertEquals(0.0, MapRouteMapper.metrics(listOf(reply), nodesWithoutMiddleRepeater,
            listOf(NodeGpsInfo(nodes.first { it.publicKey.startsWith("B282") }.publicKey, false))).totalUniqueKm, 0.001)
    }

    @Test fun newerReplyObservationReplacesEarlierClosestRouteForSamePacket() {
        val first = MapRouteEvent("774516", "hash", 5, "", 1,
            listOf("7CCF", "8EB6"), replyToSelected = true, closestObservedReply = true)
        val second = first.copy(observedAt = 2, path = listOf("7CCF", "F480"))
        val final = first.copy(observedAt = 3, path = listOf("7CCF", "B282"))
        val unrelated = first.copy(packetId = "other", path = listOf("0652", "F480"))

        val afterFirst = MapReplySelection.replaceForPacket(listOf(unrelated), "774516", listOf(first))
        val afterSecond = MapReplySelection.replaceForPacket(afterFirst, "774516", listOf(second))
        val afterFinal = MapReplySelection.replaceForPacket(afterSecond, "774516", listOf(final))

        assertEquals(listOf(unrelated, final), afterFinal)
        assertEquals(1, afterFinal.count { it.packetId == "774516" })
        assertEquals(listOf(unrelated, final), MapReplySelection.keepLatestSaved(listOf(unrelated, first, second, final)))
    }

    @Test fun missingGpsKeepsOriginalHopNumbersAndOnlyMeasuresKnownLinks() {
        val event = MapRouteEvent("1", "hash", 4, "", 1, listOf("0652", "0012", "B282", "F480"))
        val metrics = MapRouteMapper.metrics(listOf(event), nodes, listOf(NodeGpsInfo("00120000", false)))
        assertEquals(listOf(0, 2, 3), metrics.longestRoute.map { it.hopIndex })
        assertEquals(listOf("0012"), metrics.longestRoute[1].missingBefore)
        assertEquals(MapRouteMapper.distanceKm(50.25,19.05,50.30,19.10), metrics.longestRouteKm, 0.001)
    }
    @Test fun distinctNearbyRepeatersAreNotLoops() {
        val near = nodes + LocatedNode("00120000",50.2001,19.0001)
        val event = MapRouteEvent("1","hash",4,"",1,listOf("0652","0012","B282"))
        assertEquals(3, MapRouteMapper.metrics(listOf(event), near).longestRoute.size)
    }
    @Test fun collidingHashCannotChooseNearestRepeater() {
        val collision = nodes + LocatedNode("B2821111",51.0,20.0)
        assertTrue(MapRouteMapper.resolve(listOf("0652","B282"),collision).isEmpty())
    }
}
