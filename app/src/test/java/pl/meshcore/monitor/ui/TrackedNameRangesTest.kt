package pl.meshcore.monitor.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackedNameRangesTest {
    @Test fun includesBracketedMentionMarkers() {
        val text = "Reply: @[Pawel SN9PJ 🛸] route"
        val ranges = TrackedNameRanges.find(text, setOf("pawel sn9pj 🛸"))
        assertEquals(listOf("@[Pawel SN9PJ 🛸]"), ranges.map { text.substring(it) })
    }

    @Test fun highlightsEveryTrackedNameIgnoringCase() {
        val text = "via K-ce Graniczna to @PAWEL SN9PJ 🛸"
        val ranges = TrackedNameRanges.find(text, setOf("K-ce Graniczna", "Pawel SN9PJ 🛸"))
        assertEquals(listOf("K-ce Graniczna", "@PAWEL SN9PJ 🛸"), ranges.map { text.substring(it) })
    }
}
