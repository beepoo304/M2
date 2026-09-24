package pl.meshcore.monitor.data

import org.junit.Assert.*
import org.junit.Test

class RouteTrackingPolicyTest {
    private val key = "0652" + "0".repeat(60)
    private val other = "B282" + "0".repeat(60)
    private fun packet(r: TrackedKeyRelations) = LivePacket("api-id", "hash", "", 5, "Channel", "Other",
        nodeName = null, detail = "", rawHex = "", publicKey = null, trackedRelations = r)

    @Test fun unrelatedBranchOfRelatedPacketIsRejected() {
        val packet = packet(TrackedKeyRelations(routeKeys = setOf(key)))
        val branch = ObservedRoute(listOf("B282", "F480"), observerName = "Other")
        assertNull(RouteTrackingPolicy.event(packet, branch, setOf(key), key, MapTrackingMode.ALL_FOR_SELECTED_KEY))
    }
    @Test fun threeByteRouteRetainsAcceptanceProof() {
        val packet = packet(TrackedKeyRelations())
        val branch = ObservedRoute(listOf("B28200", "065200"), observerName = "Other")
        val event = RouteTrackingPolicy.event(packet, branch, setOf(key), key, MapTrackingMode.ENDS_AT_KEY)!!
        assertTrue(MapRouteScope.includes(event, key))
        assertEquals("065200", event.path.last())
    }
    @Test fun replyWithoutPhysicalEvidenceIsNotMapped() {
        val packet = packet(TrackedKeyRelations(replyKeys = setOf(key)))
        val branch = ObservedRoute(listOf("B282", "F480"), observerName = "Other")
        assertNull(RouteTrackingPolicy.event(packet, branch, setOf(key), key, MapTrackingMode.ALL_FOR_SELECTED_KEY))
    }
    @Test fun fullObserverProvesReplyButNeverLongest() {
        val packet = packet(TrackedKeyRelations(replyKeys = setOf(key)))
        val branch = ObservedRoute(listOf("B282", "F480"), observerName = "Saved", observerPublicKey = key)
        val event = RouteTrackingPolicy.event(packet, branch, setOf(key), key, MapTrackingMode.ALL_FOR_SELECTED_KEY)!!
        assertTrue(event.replyToSelected)
        assertFalse(event.longestRouteEligible)
        assertEquals("0652", event.path.last())
    }
    @Test fun replyToCompanionReportedByAnotherSavedKeyUsesOnlyItsRecordedRoute() {
        val companion = "0A0B" + "0".repeat(60)
        val observer = "F480" + "0".repeat(60)
        val packet = packet(TrackedKeyRelations(replyKeys = setOf(companion)))
        val branch = ObservedRoute(listOf("88AB", "B200", "8585", "86E7", "F200", "FEED", "F480"),
            observerName = "Saved F480", observerPublicKey = observer)
        val keys = setOf(companion, observer)
        listOf(MapTrackingMode.RELATED_TO_KEY, MapTrackingMode.ALL_FOR_SELECTED_KEY).forEach { mode ->
            val event = RouteTrackingPolicy.event(packet, branch, keys, companion, mode)!!
            assertEquals(branch.path, event.path)
            assertTrue(event.replyToSelected)
            assertFalse(event.longestRouteEligible)
            assertTrue(MapRouteScope.includes(event, companion))
            assertFalse(event.path.any { it.startsWith("0A0B", true) })
        }
        listOf(MapTrackingMode.STARTS_AT_KEY, MapTrackingMode.ENDS_AT_KEY, MapTrackingMode.REPORTED_BY_KEY).forEach { mode ->
            assertNull(RouteTrackingPolicy.event(packet, branch, keys, companion, mode))
        }
    }
    @Test fun replyDoesNotPullInOtherObservationsOfTheSamePacket() {
        val companion = "0A0B" + "0".repeat(60)
        val observer = "F480" + "0".repeat(60)
        val packet = packet(TrackedKeyRelations(replyKeys = setOf(companion), observerKeys = setOf(observer)))
        val branch = ObservedRoute(listOf("88AB", "B200"), observerName = "Untracked",
            observerPublicKey = "0707" + "0".repeat(60))
        assertNull(RouteTrackingPolicy.event(packet, branch, setOf(companion, observer), companion,
            MapTrackingMode.ALL_FOR_SELECTED_KEY))
    }
    @Test fun anotherSavedObserverCannotRescueAOneByteReplyRoute() {
        val companion = "0A0B" + "0".repeat(60)
        val observer = "F480" + "0".repeat(60)
        val packet = packet(TrackedKeyRelations(replyKeys = setOf(companion)))
        val branch = ObservedRoute(listOf("88", "F4"), observerName = "Saved", observerPublicKey = observer)
        MapTrackingMode.entries.forEach { mode ->
            assertNull(RouteTrackingPolicy.event(packet, branch, setOf(companion, observer), companion, mode))
        }
    }
    @Test fun oneByteCannotBeRescuedBySourceReplyOrObserver() {
        val packet = packet(TrackedKeyRelations(sourceKeys = setOf(key), replyKeys = setOf(key)))
        val branch = ObservedRoute(listOf("B2", "06"), observerName = "Saved", observerPublicKey = key)
        MapTrackingMode.entries.forEach { assertNull(RouteTrackingPolicy.event(packet, branch, setOf(key), key, it)) }
    }
    @Test fun conflictingResolvedIdentityDoesNotFallBackToSavedPrefix() {
        val conflict = "0652" + "1".repeat(60)
        val r = TrackedKeyMatcher.resolvedRoute(listOf("B282", "0652"), listOf(other, conflict), setOf(key))
        assertTrue(r.confirmedKeys.isEmpty())
    }
    @Test fun sourceIsIncludedBeforeFirstRecordedRepeater() {
        val packet = packet(TrackedKeyRelations(sourceKeys = setOf(key)))
        val branch = ObservedRoute(listOf("B282", "F480"), observerName = "Other")
        val event = RouteTrackingPolicy.event(packet, branch, setOf(key), key, MapTrackingMode.STARTS_AT_KEY)!!
        assertEquals(key, event.resolvedPath.first())
        assertEquals("0652", event.path.first())
    }

    @Test fun sourceAdvertWithEmptyPathUsesExplicitObserverAsDirectEndpoint() {
        val observer = "F480" + "0".repeat(60)
        val packet = packet(TrackedKeyRelations(sourceKeys = setOf(key)))
        val direct = ObservedRoute(emptyList(), observerName = "Observer", observerPublicKey = observer)
        val event = RouteTrackingPolicy.event(packet, direct, setOf(key), key, MapTrackingMode.STARTS_AT_KEY)!!
        assertEquals(listOf("0652", "F480"), event.path)
        assertEquals(listOf(key, observer), event.resolvedPath)
        assertTrue(MapRouteScope.includes(event, key))
    }
}
