package pl.meshcore.monitor.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnTrafficClassifierTest {
    private val ownKey = "f4809c7817bc777124f638d32008af197415ba1240c36c186f6ec80619775c68"

    @Test
    fun detectsOwnTrafficInAnyObservedRoute() {
        val details = PacketObservationDetails(
            routes = listOf(
                ObservedRoute(
                    path = listOf("BADF", "5261", "D900", "CA5E"),
                    observerName = "Other observer",
                ),
                ObservedRoute(
                    path = listOf("BADF", "5261", "D900", "86E7", "F480"),
                    observerName = "K-ce Paderewa TBS_OBSx2",
                    observerPublicKey = ownKey.uppercase(),
                ),
            ),
            observationCount = 23,
        )

        assertTrue(OwnTrafficClassifier.matches(details, setOf(ownKey)))
    }

    @Test
    fun ignoresPacketWithoutOwnObservation() {
        val details = PacketObservationDetails(
            routes = listOf(
                ObservedRoute(
                    path = listOf("BADF", "5261", "D900", "CA5E"),
                    observerName = "Other observer",
                ),
            ),
        )

        assertFalse(OwnTrafficClassifier.matches(details, setOf(ownKey)))
        assertFalse(OwnTrafficClassifier.classify(details, setOf(ownKey)).possible)
    }

    @Test
    fun oneByteEndingAloneIsExcluded() {
        val details = PacketObservationDetails(routes = listOf(
            ObservedRoute(
                path = listOf("7D", "19", "F4"),
                observerName = "Different F4 observer",
                observerPublicKey = "f4bb000000000000000000000000000000000000000000000000000000000000",
            ),
        ))

        assertFalse(OwnTrafficClassifier.matches(details, setOf(ownKey)))
        assertFalse(OwnTrafficClassifier.classify(details, setOf(ownKey)).possible)
    }

    @Test
    fun oneByteRouteIsExcludedEvenWithFullObserverId() {
        val details = PacketObservationDetails(routes = listOf(
            ObservedRoute(
                path = listOf("7D", "19", "F4"),
                observerName = "Tracked observer",
                observerPublicKey = ownKey,
            ),
        ))

        assertFalse(OwnTrafficClassifier.matches(details, setOf(ownKey)))
        assertFalse(OwnTrafficClassifier.classify(details, setOf(ownKey)).possible)
    }

    @Test
    fun trackedObserverDoesNotTurnFinalRouteNodeIntoTrackedNode() {
        val routeNode = "86e771b0616afd5d0fb0a9c3c085ad7d1b2e9a2cba0f03d6b1c1572d5e232b17"
        val details = PacketObservationDetails(routes = listOf(
            ObservedRoute(
                path = listOf("AAEF", "CABA", "5261", "6548", "BBFE", "3702", "BEBE", "86E7"),
                resolvedPath = listOf(
                    "aaef8dd979fc1e3dce3b304576574e1a8ce0564ec7e40b0b6f3d817c62ee4f80",
                    "caba5f6dbc382d4678819bbfa1972b21da512d4a1d63844b2e49b5535226d996",
                    "526124d65bbcea371b5e70d504445a3b74f50d999860f9490f25f4a0f3c79056",
                    "65482ff6d5f859d4eecb02c1dca254694387c9bacc7bbc3bbb1d437be7279a01",
                    "bbfe3c18cc23cad0fc2b7495b7bfef232e92a34adef40d3b697a54ebd6463894",
                    "3702c317bd74cc851848c10758697bbe23a46ad78baa3eb6f09ff19dfb5c3bd3",
                    "bebec3ee09a9f48791e22e2377d70ecacbd856ce461f325431240feb000908a0",
                    routeNode,
                ),
                observerName = "K-ce Paderewa TBS_OBSx2",
                observerPublicKey = ownKey,
            ),
        ))

        val relations = OwnTrafficClassifier.classify(details, setOf(ownKey)).relations
        assertEquals(setOf(ownKey), relations.observerKeys)
        assertTrue(relations.routeKeys.isEmpty())
    }
}
