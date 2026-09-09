package pl.meshcore.monitor.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketObservationRepositoryTest {
    @Test fun preservesEveryDistinctObservedRoute() {
        val result = PacketObservationRepository.parse(JSONObject("""
            {"observation_count":4,"observations":[
              {"path_json":"[\"B200\",\"86E7\"]","observer_name":"first","rssi":-115,"snr":0.0},
              {"path_json":"[\"B200\",\"86E7\",\"0652\"]","observer_name":"F480","rssi":-119,"snr":-5.0},
              {"path_json":"[\"B200\",\"86E7\",\"F480\"]","observer_name":"F480","rssi":-119,"snr":-5.0},
              {"path_json":"[\"B200\",\"86E7\",\"F480\"]","observer_name":"duplicate"}
            ]}
        """))
        assertEquals(4, result.observationCount)
        assertEquals(3, result.routes.size)
        assertTrue(result.routes.any { it.path == listOf("B200", "86E7", "F480") && it.path.size == 3 && it.rssi == -119 })
        assertTrue(result.routes.any { it.path == listOf("B200", "86E7", "0652") && it.snr == -5.0 })
    }
}
