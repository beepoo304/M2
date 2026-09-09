package pl.meshcore.monitor.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketObservationRepositoryTest {
    @Test fun preservesEveryDistinctObservedRoute() {
        val result = PacketObservationRepository.parse(JSONObject("""
            {"observation_count":4,"observations":[
              {"path_json":"[\"B200\",\"86E7\"]"},
              {"path_json":"[\"B200\",\"86E7\",\"0652\"]"},
              {"path_json":"[\"B200\",\"86E7\",\"F480\"]"},
              {"path_json":"[\"B200\",\"86E7\",\"F480\"]"}
            ]}
        """))
        assertEquals(4, result.observationCount)
        assertEquals(3, result.routes.size)
        assertTrue(result.routes.contains(listOf("B200", "86E7", "F480")))
        assertTrue(result.routes.contains(listOf("B200", "86E7", "0652")))
    }
}
