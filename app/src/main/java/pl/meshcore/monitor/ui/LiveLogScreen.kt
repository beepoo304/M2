package pl.meshcore.monitor.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.meshcore.monitor.data.*
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun LiveLogScreen(modifier: Modifier, vm: LiveLogViewModel = viewModel()) {
    val state by vm.state.collectAsState(); val refreshing by vm.refreshing.collectAsState()
    var selected by remember { mutableStateOf<LivePacket?>(null) }; val listState = rememberLazyListState()
    LaunchedEffect(state.packets.firstOrNull()?.id) { if (state.packets.isNotEmpty()) listState.animateScrollToItem(0) }
    val rotation by rememberInfiniteTransition(label = "live").animateFloat(0f, 360f,
        infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "rotation")
    val connected = state.connection == ConnectionState.CONNECTED
    val statusColor = if (connected) MaterialTheme.colorScheme.primary else if (state.connection == ConnectionState.ERROR) MaterialTheme.colorScheme.error else Color(0xFFF0A84B)
    selected?.let { PacketDetailsDialog(it) { selected = null } }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
            Column { Text("LIVE LOG", fontWeight = FontWeight.Bold); Text("Newest first · ${state.packets.size} / 100",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.weight(1f)); Icon(Icons.Outlined.Refresh, "Listening", tint = statusColor,
                modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = if (connected) rotation else 0f })
            Text("  ${if (connected) "Connected" else state.connection.name.lowercase().replaceFirstChar(Char::uppercase)}", color = statusColor)
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
        PullToRefreshBox(refreshing, vm::refresh, Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) { items(state.packets, key = { it.id }) { packet ->
                val color = if (packet.ownTraffic) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Column(Modifier.fillMaxWidth().clickable { selected = packet }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row { Text(packet.time, color = color, fontFamily = FontFamily.Monospace); Spacer(Modifier.weight(1f)); Text(packet.typeLabel, color = color) }
                    Text(packet.nodeName ?: packet.observerName, color = color, fontWeight = FontWeight.Medium)
                    Text(packet.detail, color = color.copy(alpha = .78f), style = MaterialTheme.typography.bodySmall)
                }; HorizontalDivider()
            } }
        }
    }
}

@Composable internal fun PacketDetailsDialog(packet: LivePacket, onDismiss: () -> Unit) {
    val context = LocalContext.current; val key = packet.publicKey.orEmpty().ifBlank { packet.observerPublicKey }
    val config by ConnectionConfigBus.config.collectAsState()
    var showAllRoutes by remember(packet.id) { mutableStateOf(false) }
    val networkDetails by produceState<PacketObservationDetails?>(null, packet.id) {
        value = PacketObservationRepository.load(packet.id)
    }
    val decoded = remember(packet.decodedJson) {
        packet.decodedJson.takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(packet.nodeName ?: packet.observerName) }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("${packet.typeLabel} · ${packet.time}") }
            if (packet.payloadType == 5) {
                val channel = decoded?.optString("channel").orEmpty()
                val sender = decoded?.optString("sender").orEmpty()
                val message = decoded?.optString("text").orEmpty()
                item { Text("Channel: ${channel.ifBlank { "Private channel" }}", fontWeight = FontWeight.Medium) }
                if (sender.isNotBlank()) item { Text("Sender: $sender") }
                item { Text(if (message.isNotBlank()) message else "Message content is not available") }
            }
            val routes = networkDetails?.routes.orEmpty()
            val trackedRoutes = routes.filter { MeshPath.matchingKeys(it, config.ownPublicKeys).isNotEmpty() }
            val displayedRoutes = if (!showAllRoutes && trackedRoutes.isNotEmpty()) trackedRoutes else routes
            if (networkDetails == null) {
                item { Text("Route: ${packet.path.takeIf { it.isNotEmpty() }?.joinToString(" → ") ?: "Direct / unavailable"}") }
            } else if (routes.isEmpty()) {
                item { Text("Observed routes: Direct") }
            } else {
                item { Text(if (trackedRoutes.isNotEmpty() && !showAllRoutes) "Tracked routes (${trackedRoutes.size})" else "Observed routes (${routes.size})", fontWeight = FontWeight.Medium) }
                MeshPath.hashSizeBytes(routes)?.let { bytes -> item { Text("Path hashes: $bytes ${if (bytes == 1) "byte" else "bytes"} per hop", style = MaterialTheme.typography.bodySmall) } }
                items(displayedRoutes) { route ->
                    Column {
                        Text(route.joinToString(" → "), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        MeshPath.matchingKeys(route, config.ownPublicKeys).forEach { (hop, keys) ->
                            Text("$hop matches ${keys.joinToString { it.take(hop.length.coerceAtLeast(4)).uppercase() + "…" }}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (trackedRoutes.isNotEmpty() && trackedRoutes.size < routes.size) item {
                    TextButton(onClick = { showAllRoutes = !showAllRoutes }) { Text(if (showAllRoutes) "Show tracked routes" else "Show all ${routes.size} routes") }
                }
            }
            item { Text("Hops: ${routes.maxOfOrNull { it.size } ?: packet.path.size} · Seen: ${networkDetails?.observationCount ?: packet.observationCount}") }
            item { Text("Signal: ${packet.rssi?.let { "$it dBm" } ?: "—"} · SNR: ${packet.snr?.let { "$it dB" } ?: "—"}") }
            item { Text("Public key", fontWeight = FontWeight.Medium); Text(key.ifBlank { "Not carried by this packet" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            item { Text("Observer: ${packet.observerName}") }
            item { Text("Hash: ${packet.hash}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall) }
            item { Text("Raw: ${packet.rawHex}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, maxLines = 6) }
        }
    }, confirmButton = { Column {
        TextButton(enabled = packet.hash.isNotBlank(), onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("MeshCore packet hash", packet.hash))
        }) { Text("Copy Hash") }
        TextButton(enabled = key.isNotBlank(), onClick = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("MeshCore public key", key))
    }) { Text("Copy key") } } }, dismissButton = { TextButton(onDismiss) { Text("Close") } })
}
