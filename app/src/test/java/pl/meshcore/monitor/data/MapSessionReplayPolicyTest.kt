package pl.meshcore.monitor.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MapSessionReplayPolicyTest {
    private val start = Instant.parse("2026-09-15T15:02:12Z").toEpochMilli()
    private fun packet(hash: String, timestamp: String) = LivePacket(hash, hash, "", 5, "Channel", "F480",
        nodeName = null, detail = "", rawHex = "", publicKey = null, timestamp = timestamp, observationCount = 29)

    @Test fun legacyRejectedPacketIsRecheckedWithoutNewObservations() {
        val reply = packet("reply", "2026-09-15T15:02:31Z")
        val session = MapSession("0a0b", running = true, startedAt = start,
            baseline = mapOf("reply" to 29), routingRevision = 0)
        val updated = MapSessionReplayPolicy.migrate(session, listOf(reply))
        assertEquals(setOf("reply"), updated.pendingRechecks)
        assertEquals(session.baseline, updated.baseline)
        assertTrue(updated.running)
    }
    @Test fun updateDoesNotImportPacketsBeforeStartOrUncapturedHistory() {
        val packets = listOf(packet("old", "2026-09-15T15:00:00Z"),
            packet("unknown", "2026-09-15T15:02:31Z"), packet("invalid", "bad timestamp"))
        val session = MapSession("0a0b", running = true, startedAt = start,
            baseline = mapOf("old" to 29, "invalid" to 29), routingRevision = 0)
        assertTrue(MapSessionReplayPolicy.migrate(session, packets).pendingRechecks.isEmpty())
    }
    @Test fun emptyInitialLiveStateDoesNotFinishLegacyMigration() {
        val session = MapSession("0a0b", running = true, startedAt = start,
            baseline = mapOf("reply" to 29), routingRevision = 0)
        assertEquals(session, MapSessionReplayPolicy.migrate(session, emptyList()))
    }
    @Test fun futureChangesUseCapturedIdsInsteadOfResumeBaseline() {
        val session = MapSession("0a0b", running = true, startedAt = start,
            baseline = mapOf("paused" to 29), routingRevision = 1, capturedPacketIds = setOf("captured"))
        val updated = MapSessionReplayPolicy.migrate(session, listOf(packet("paused", "2026-09-15T15:03:00Z")))
        assertEquals(setOf("captured"), updated.pendingRechecks)
    }
    @Test fun pendingRechecksSurviveSaveAndRestore() {
        val session = MapSession("0a0b", running = true, startedAt = start,
            capturedPacketIds = setOf("reply"), pendingRechecks = setOf("reply"))
        assertEquals(session, MapSessionJson.decode(MapSessionJson.encode(session), "0a0b"))
        assertEquals(session, MapSessionReplayPolicy.migrate(session, emptyList()))
    }
    @Test fun existingGeometryIsPreservedWhileItsPacketIsScheduled() {
        val event = MapRouteEvent("packet-id", "packet-hash", 5, "2026-09-15T15:02:31Z", start,
            listOf("88AB", "F480"), acceptedKeys = setOf("0a0b"))
        val session = MapSession("0a0b", events = listOf(event), routingRevision = 0)
        val updated = MapSessionReplayPolicy.migrate(session, emptyList())
        assertEquals(session.events, updated.events)
        assertEquals(setOf("packet-hash"), updated.pendingRechecks)
    }
}
