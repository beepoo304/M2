package pl.meshcore.monitor.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MeshPathTest {
    @Test fun preservesOneByteHopsAndFinalOddHop() {
        assertEquals(
            listOf("7D", "19", "15", "CE", "5C", "B2", "F4"),
            MeshPath.normalize(listOf("7d", "19", "15", "ce", "5c", "b2", "f4")),
        )
    }

    @Test fun preservesTwoByteHops() {
        assertEquals(listOf("B200", "86E7", "F480"), MeshPath.normalize(listOf("b200", "86e7", "f480")))
    }

    @Test fun preservesThreeByteHops() {
        assertEquals(listOf("C77777", "515151", "F4809C"), MeshPath.normalize(listOf("c77777", "515151", "f4809c")))
    }

    @Test fun matchesSavedKeyAtEverySupportedHashWidth() {
        val key = "f4809c7817bc777124f638d32008af197415ba1240c36c186f6ec80619775c68"
        assertEquals(setOf("F4"), MeshPath.matchingKeys(listOf("7D", "F4"), setOf(key)).keys)
        assertEquals(setOf("F480"), MeshPath.matchingKeys(listOf("B200", "F480"), setOf(key)).keys)
        assertEquals(setOf("F4809C"), MeshPath.matchingKeys(listOf("C77777", "F4809C"), setOf(key)).keys)
    }

    @Test fun reportsHashWidthWithoutChangingRoute() {
        assertEquals(1, MeshPath.hashSizeBytes(listOf(listOf("7D", "19", "F4"))))
        assertEquals(2, MeshPath.hashSizeBytes(listOf(listOf("B200", "F480"))))
        assertEquals(3, MeshPath.hashSizeBytes(listOf(listOf("C77777", "F4809C"))))
    }

    @Test fun trackedRouteMustEndAtSavedKey() {
        val b282 = "b282f47b00000000000000000000000000000000000000000000000000000000"
        assertEquals(emptyList<String>(), MeshPath.endingKeys(listOf("7D", "B2", "BE"), setOf(b282)))
        assertEquals(listOf(b282), MeshPath.endingKeys(listOf("7D", "19", "B2"), setOf(b282)))
    }

    @Test fun oneByteEndingIsNotReliableWithoutObserverConfirmation() {
        val key = "f4809c7817bc777124f638d32008af197415ba1240c36c186f6ec80619775c68"
        assertEquals(false, MeshPath.hasReliableEnding(listOf("7D", "F4"), setOf(key)))
        assertEquals(true, MeshPath.hasReliableEnding(listOf("B200", "F480"), setOf(key)))
    }
}
