package pl.meshcore.monitor.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.meshcore.monitor.data.LivePacket
import pl.meshcore.monitor.data.ConnectionConfigBus
import pl.meshcore.monitor.data.OwnLogTextExporter
import pl.meshcore.monitor.data.ExportLocationStore

@Composable
fun OwnTrafficLogScreen(
    modifier: Modifier = Modifier,
    viewModel: OwnTrafficLogViewModel = viewModel(),
) {
    val packets by viewModel.packets.collectAsState()
    val config by ConnectionConfigBus.config.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<LivePacket?>(null) }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Column {
                Text("MY DEVICES LOG", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Text("Newest first · ${packets.size} / 250", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                scope.launch {
                    val result = runCatching { withContext(Dispatchers.IO) { OwnLogTextExporter.save(context, packets) } }
                    Toast.makeText(context, result.fold(
                        onSuccess = { "$it saved in ${ExportLocationStore.label(context)}" },
                        onFailure = { "Cannot export log" },
                    ), Toast.LENGTH_LONG).show()
                }
            }, enabled = packets.isNotEmpty(), contentPadding = PaddingValues(horizontal = 7.dp)) { Text("Export", maxLines = 1) }
            TextButton(onClick = viewModel::clear, enabled = packets.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 7.dp)) { Text("Clear", maxLines = 1) }
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
                        TrackedNameText(packet.detail, config.ownNodeNames,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                }
            }
        }
    }
    selected?.let { PacketDetailsDialog(it) { selected = null } }
}
