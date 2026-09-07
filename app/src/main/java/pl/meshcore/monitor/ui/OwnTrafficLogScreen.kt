package pl.meshcore.monitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.meshcore.monitor.data.LivePacket

@Composable
fun OwnTrafficLogScreen(
    modifier: Modifier = Modifier,
    viewModel: OwnTrafficLogViewModel = viewModel(),
) {
    val packets by viewModel.packets.collectAsState()
    var selected by remember { mutableStateOf<LivePacket?>(null) }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Column {
                Text("MY DEVICES LOG", fontWeight = FontWeight.Bold)
                Text("Newest first · ${packets.size} / 100", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = viewModel::clear, enabled = packets.isNotEmpty()) { Text("Clear") }
        }
        HorizontalDivider()
        if (packets.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("No activity from saved device keys", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(packets, key = { it.id }) { packet ->
                    Column(Modifier.fillMaxWidth().clickable { selected = packet }
                        .padding(horizontal = 16.dp, vertical = 9.dp)) {
                        Row {
                            Text(packet.typeLabel, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text(packet.time, style = MaterialTheme.typography.labelSmall)
                        }
                        Text(packet.nodeName ?: packet.observerName, fontWeight = FontWeight.Medium)
                        Text(packet.detail, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                }
            }
        }
    }
    selected?.let { PacketDetailsDialog(it) { selected = null } }
}
