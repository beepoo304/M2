package pl.meshcore.monitor.data

import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BrokerApiTest {
    @Test fun liveSuccessClosesOutageWithoutWaitingForMonitor() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503).setBody("Service unavailable"))
        server.enqueue(MockResponse().setBody("{\"packets\":[]}"))
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        try {
            assertTrue(runCatching { BrokerApi.packets(base, 1) }.isFailure)
            assertEquals(false, ApiHealthMonitor.state.value.getValue(base).online)
            assertEquals(0, BrokerApi.packets(base, 250).length())
            val state = ApiHealthMonitor.state.value.getValue(base)
            assertEquals(true, state.online)
            assertFalse(state.entries.first().ongoing)
            ApiHealthMonitor.record(base, false, System.currentTimeMillis() - 1000)
            assertEquals(true, ApiHealthMonitor.state.value.getValue(base).online)
        } finally { server.shutdown() }
    }

    @Test fun successfulHttpWithoutPacketArrayIsNotHealthy() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{}"))
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        try {
            assertTrue(runCatching { BrokerApi.packets(base, 1) }.isFailure)
            assertEquals(false, ApiHealthMonitor.state.value.getValue(base).online)
        } finally { server.shutdown() }
    }

    @Test fun cancellationDoesNotWaitForServerOrMarkBrokerDown() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        try {
            ApiHealthMonitor.record(base, true, System.currentTimeMillis() - 100)
            val request = launch(Dispatchers.IO) { BrokerApi.packets(base, 1) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
            withTimeout(2_000) { request.cancelAndJoin() }
            assertEquals(true, ApiHealthMonitor.state.value.getValue(base).online)
        } finally {
            server.shutdown()
        }
    }
}
