package pl.meshcore.monitor.data

import org.junit.Assert.assertFalse
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
    fun oneByteEndingAloneIsOnlyAmbiguous() {
        val details = PacketObservationDetails(routes = listOf(
            ObservedRoute(
                path = listOf("7D", "19", "F4"),
                observerName = "Different F4 observer",
                observerPublicKey = "f4bb000000000000000000000000000000000000000000000000000000000000",
            ),
        ))

        assertFalse(OwnTrafficClassifier.matches(details, setOf(ownKey)))
        assertTrue(OwnTrafficClassifier.classify(details, setOf(ownKey)).possible)
    }

    @Test
    fun oneByteEndingIsConfirmedByFullObserverId() {
        val details = PacketObservationDetails(routes = listOf(
            ObservedRoute(
                path = listOf("7D", "19", "F4"),
                observerName = "Tracked observer",
                observerPublicKey = ownKey,
            ),
        ))

        assertTrue(OwnTrafficClassifier.matches(details, setOf(ownKey)))
        assertFalse(OwnTrafficClassifier.classify(details, setOf(ownKey)).possible)
    }
}
