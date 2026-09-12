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

    @Test fun parsesOneByteRouteWithoutCombiningOrDroppingFinalHop() {
        val result = PacketObservationRepository.parse(JSONObject("""
            {"observations":[
              {"path_json":"[\"7D\",\"19\",\"15\",\"CE\",\"5C\",\"B2\",\"F4\"]","observer_name":"F480"}
            ]}
        """))
        assertEquals(listOf("7D", "19", "15", "CE", "5C", "B2", "F4"), result.routes.single().path)
    }

    @Test fun preservesSamePathObservedByDifferentDevices() {
        val result = PacketObservationRepository.parse(JSONObject("""
            {"observations":[
              {"path_json":"[\"7D\",\"19\"]","observer_name":"other","observer_id":"AAAA"},
              {"path_json":"[\"7D\",\"19\"]","observer_name":"tracked","observer_id":"F480"}
            ]}
        """))
        assertEquals(2, result.routes.size)
        assertTrue(result.routes.any { it.observerPublicKey == "F480" })
    }

    @Test fun preservesResolvedFullKeysForExactRouteMatching() {
        val result = PacketObservationRepository.parse(JSONObject("""
            {"observations":[{
              "path_json":"[\"86E7\"]",
              "resolved_path":["86e771b0616afd5d0fb0a9c3c085ad7d1b2e9a2cba0f03d6b1c1572d5e232b17"],
              "observer_name":"F480",
              "observer_id":"F4809C7817BC777124F638D32008AF197415BA1240C36C186F6EC80619775C68"
            }]}
        """))
        assertEquals("86e771b0616afd5d0fb0a9c3c085ad7d1b2e9a2cba0f03d6b1c1572d5e232b17",
            result.routes.single().resolvedPath.single())
        assertTrue(result.loadSucceeded)
    }
}
