package pl.meshcore.monitor.data

import org.junit.Assert.*
import org.junit.Test

class ApiHealthLogStoreTest {
    @Test fun outageStateSurvivesRoundTrip() {
        val entries = listOf(ApiHealthEntry(100, 200, "HTTP 503", 12, 3, true),
            ApiHealthEntry(10, 20, "Timeout", 15, 2, false))
        assertEquals(entries, ApiHealthLogStore.decode(ApiHealthLogStore.encode(entries)))
    }
    @Test fun keepsNewest250Outages() {
        val entries = (300 downTo 1).map { ApiHealthEntry(it.toLong(), it.toLong(), "HTTP 503", 1) }
        val restored = ApiHealthLogStore.decode(ApiHealthLogStore.encode(entries))
        assertEquals(250, restored.size)
        assertEquals(300L, restored.first().startedAtMs)
        assertEquals(51L, restored.last().startedAtMs)
    }
}
