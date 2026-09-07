package pl.meshcore.monitor.ui

import android.app.ActivityManager
import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import pl.meshcore.monitor.LiveListenerService
import pl.meshcore.monitor.MainActivity
import pl.meshcore.monitor.data.ConnectionState
import pl.meshcore.monitor.data.SharedLiveRepository

class CloseApplicationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Suppress("DEPRECATION")
    @Test fun closeStopsServiceAndLiveListenerAndFinishesActivity() {
        val activity = compose.activity
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        fun listenerRunning() = manager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == LiveListenerService::class.java.name
        }
        compose.waitUntil(10_000) { listenerRunning() }
        compose.onNodeWithText("Settings").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Close application"))
        compose.onNodeWithText("Close application").performClick()
        compose.waitUntil(15_000) { activity.isDestroyed && !listenerRunning() }
        assertEquals(ConnectionState.DISCONNECTED, SharedLiveRepository.state.value.connection)
    }
}
