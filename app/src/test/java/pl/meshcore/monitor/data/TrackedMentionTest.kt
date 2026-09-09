package pl.meshcore.monitor.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackedMentionTest {
    private val names = setOf("pawel sn9pj 🛸")

    @Test fun bracketedReplyToTrackedDeviceIsDetected() {
        assertTrue(TrackedMention.contains("OST UjazdBot: @[Pawel SN9PJ 🛸] route", names))
    }

    @Test fun plainMentionIsDetectedIgnoringCase() {
        assertTrue(TrackedMention.contains("ack @PAWEL SN9PJ 🛸", names))
    }

    @Test fun unrelatedMessageIsNotDetected() {
        assertFalse(TrackedMention.contains("reply for another station", names))
    }
}
