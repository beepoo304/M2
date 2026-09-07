package pl.meshcore.monitor.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import pl.meshcore.monitor.data.LivePacket
import pl.meshcore.monitor.ui.theme.MeshCoreTheme

class PacketDetailsDialogTest {
    @get:Rule val compose = createComposeRule()

    private fun packet(hash: String) = LivePacket(
        id = "test-packet", hash = hash, time = "12:34:56", payloadType = 4,
        typeLabel = "ADVERT", observerName = "Test observer", nodeName = "Test node",
        detail = "", rawHex = "", publicKey = "a".repeat(64),
    )

    @Test fun copyHashAndKeyUseTheirOwnValues() {
        val packet = packet("0123456789ABCDEF")
        compose.setContent { MeshCoreTheme { PacketDetailsDialog(packet) {} } }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        compose.onNodeWithText("Copy Hash").performClick()
        compose.runOnIdle { assertEquals(packet.hash, clipboard.primaryClip?.getItemAt(0)?.text?.toString()) }
        compose.onNodeWithText("Copy key").performClick()
        compose.runOnIdle { assertEquals(packet.publicKey, clipboard.primaryClip?.getItemAt(0)?.text?.toString()) }
    }

    @Test fun missingHashCannotBeCopied() {
        compose.setContent { MeshCoreTheme { PacketDetailsDialog(packet("")) {} } }
        compose.onNodeWithText("Copy Hash").assertIsNotEnabled()
    }
}
