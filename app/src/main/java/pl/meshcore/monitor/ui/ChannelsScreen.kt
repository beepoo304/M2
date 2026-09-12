package pl.meshcore.monitor.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.common.InputImage
import pl.meshcore.monitor.data.ChannelMessageDetails
import pl.meshcore.monitor.data.ChannelSummary
import pl.meshcore.monitor.data.TrackedMention
import pl.meshcore.monitor.data.WarsawTimeFormatter
import pl.meshcore.monitor.data.ChannelStatisticsEngine
import pl.meshcore.monitor.data.ChannelStatEvent
import pl.meshcore.monitor.data.AppPacketStatisticsEngine
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ChannelsScreen(
    modifier: Modifier,
    trackedNames: Set<String>,
    devices: List<SettingsViewModel.DeviceEntry>,
    vm: ChannelsViewModel = viewModel(),
) {
    val context = LocalContext.current; val state by vm.state.collectAsState()
    var add by remember { mutableStateOf(false) }; var input by rememberSaveable { mutableStateOf("") }; var invalid by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ChannelSummary?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var statistics by remember { mutableStateOf(false) }
    var appStatistics by remember { mutableStateOf(false) }
    if (appStatistics) {
        AppPacketStatisticsScreen(modifier) { appStatistics = false }
        return
    }
    if (statistics) {
        ChannelStatisticsScreen(modifier, devices) { statistics = false }
        return
    }
    state.selectedMessage?.let { ChannelDetailsDialog(it, vm::closeMessageDetails) }
    LaunchedEffect(state.selected) {
        if (state.selected == null && state.error != null) vm.closeChannel()
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let {
        runCatching { InputImage.fromFilePath(context, it) }.onSuccess { image ->
            BarcodeScanning.getClient().process(image).addOnSuccessListener { codes ->
                val value = codes.firstNotNullOfOrNull { code -> code.rawValue }
                if (value != null && vm.addCustom(value)) { input = ""; add = false } else invalid = true
            }.addOnFailureListener { invalid = true }
        }
    } }
    if (add) AlertDialog(onDismissRequest = { add = false }, title = { Text("Add channel") }, text = {
        Column { Text("Enter public, #name or a 16/32-byte channel key.")
            OutlinedTextField(input, { input = it; invalid = false }, label = { Text("public, #name or key") }, isError = invalid, singleLine = true)
            Row { TextButton(onClick = {
                val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()
                GmsBarcodeScanning.getClient(context, options).startScan().addOnSuccessListener { code ->
                    if (code.rawValue?.let(vm::addCustom) == true) { input = ""; add = false } else invalid = true
                }.addOnFailureListener { invalid = true }
            }) { Icon(Icons.Outlined.CameraAlt, null); Text(" Scan QR") }
                TextButton({ gallery.launch("image/*") }) { Icon(Icons.Outlined.Image, null); Text(" Open image") } }
        }
    }, confirmButton = { TextButton({ if (vm.addCustom(input)) { input = ""; add = false } else invalid = true }) { Text("Add") } },
        dismissButton = { TextButton({ add = false }) { Text("Cancel") } })
    renameTarget?.let { channel ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Channel name") },
            text = { OutlinedTextField(renameText, { renameText = it }, label = { Text("Local name") }, singleLine = true) },
            confirmButton = { TextButton(onClick = {
                if (vm.renameSaved(channel, renameText)) renameTarget = null
            }, enabled = renameText.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton({ renameTarget = null }) { Text("Cancel") } },
        )
    }

    Column(modifier.fillMaxSize()) {
        if (state.selected != null) {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(vm::closeChannel) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                    Text(state.selected!!.name, fontWeight = FontWeight.Bold, maxLines = 1, fontSize = 13.sp,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(vm::clearSelectedMessages, enabled = state.messages.isNotEmpty()) {
                        Text("Delete all", color = MaterialTheme.colorScheme.primary, maxLines = 1)
                    }
                    Text("${state.messages.size} messages", maxLines = 1,
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("CHANNELS", fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f))
                Text("${state.myChannels.size} channels", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
        if (state.selected != null) {
            PullToRefreshBox(state.loading, vm::refresh, Modifier.fillMaxSize()) { LazyColumn(Modifier.fillMaxSize()) {
                items(state.messages, key = { it.id }) { message ->
                    val tracked = message.sender.trim().lowercase() in trackedNames
                    Column(Modifier.fillMaxWidth().clickable { vm.showMessageDetails(message) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(message.sender, fontWeight = FontWeight.Medium, fontSize = 12.sp,
                            color = if (tracked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text(WarsawTimeFormatter.dateTime(message.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        MentionText(message.text, trackedNames)
                    }; HorizontalDivider()
                }
            } }; return
        }
        Row(Modifier.fillMaxWidth().clickable { add = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Add, "Add channel", tint = MaterialTheme.colorScheme.primary); Text("  Add by #name or key", color = MaterialTheme.colorScheme.primary)
        }; HorizontalDivider()
        if (state.loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else if (state.myChannels.isEmpty()) Box(Modifier.fillMaxSize().padding(24.dp)) { Text("Channels you add will appear here") }
        else {
            val listState = rememberLazyListState()
            val orderedChannels = state.myChannels.sortedWith(
                compareByDescending<ChannelSummary> { channel ->
                    channel.recentMessages.any { it.isTrackedMessage(trackedNames) }
                }.thenByDescending { channel ->
                    channel.recentMessages.firstOrNull { it.isTrackedMessage(trackedNames) }?.timestamp.orEmpty()
                }.thenByDescending { it.latestMessage?.timestamp.orEmpty() }
            )
            PullToRefreshBox(state.refreshingChannels, vm::refreshAll, Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) { items(orderedChannels, key = { it.hash }) { channel ->
            val unread = channel.recentMessages.filter { it.epochMillis() > channel.lastReadAtMs }
            val trackedUnread = unread.count { it.isTrackedMessage(trackedNames) }
            val otherUnread = unread.size - trackedUnread
            Row(Modifier.fillMaxWidth().clickable { vm.open(channel) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(channel.name, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.weight(1f))
                if (trackedUnread > 0) Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Text(trackedUnread.toString(), modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
                }
                if (otherUnread > 0) {
                    Spacer(Modifier.width(5.dp))
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.extraLarge) {
                        Text(otherUnread.toString(), modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (channel.isPrivate) {
                    IconButton({ renameTarget = channel; renameText = channel.name }) {
                        Icon(Icons.Outlined.Edit, "Rename private channel")
                    }
                }
                IconButton({ vm.removeSaved(channel) }) { Icon(Icons.Outlined.Delete, "Remove channel") }
            }; HorizontalDivider()
        }
                item {
                    Row(Modifier.fillMaxWidth().clickable { statistics = true }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.QueryStats, "Channel statistics", tint = MaterialTheme.colorScheme.primary)
                        Text("  Channel statistics", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                    }
                    HorizontalDivider()
                }
                item {
                    Row(Modifier.fillMaxWidth().clickable { appStatistics = true }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Analytics, "App packet statistics", tint = MaterialTheme.colorScheme.primary)
                        Text("  App packet statistics", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                    }
                    HorizontalDivider()
                }
            } }
        }
    }
}

@Composable
private fun AppPacketStatisticsScreen(modifier: Modifier, close: () -> Unit) {
    val statistics by AppPacketStatisticsEngine.state.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Reset app packet statistics?") },
        text = { Text("The packet counter, start date and accumulated running time will start again from zero.") },
        confirmButton = { TextButton({ AppPacketStatisticsEngine.reset(); confirmReset = false }) { Text("Reset") } },
        dismissButton = { TextButton({ confirmReset = false }) { Text("Cancel") } },
    )
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text("APP PACKET STATISTICS", fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton({ confirmReset = true }) { Text("Reset") }
        }
        HorizontalDivider()
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Started", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(WarsawTimeFormatter.dateTime(Instant.ofEpochMilli(statistics.startedAtMs).toString()), fontWeight = FontWeight.Medium)
            Text("Packets received", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("%,d".format(java.util.Locale.US, statistics.packetCount),
                style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text("Total app running time", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatRunningTime(statistics.accumulatedRunningMs), fontWeight = FontWeight.Medium)
        }
    }
}

private fun formatRunningTime(milliseconds: Long): String {
    val totalMinutes = milliseconds.coerceAtLeast(0L) / 60_000L
    val days = totalMinutes / (24 * 60)
    val hours = totalMinutes / 60 % 24
    val minutes = totalMinutes % 60
    return buildString {
        if (days > 0) append("$days d ")
        append("$hours h $minutes min")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelStatisticsScreen(
    modifier: Modifier,
    devices: List<SettingsViewModel.DeviceEntry>,
    close: () -> Unit,
) {
    val events by ChannelStatisticsEngine.events.collectAsState()
    val refreshing by ChannelStatisticsEngine.refreshing.collectAsState()
    var selectedKey by rememberSaveable { mutableStateOf(devices.firstOrNull()?.publicKey.orEmpty()) }
    LaunchedEffect(devices.map { it.publicKey }) {
        if (devices.none { it.publicKey == selectedKey }) selectedKey = devices.firstOrNull()?.publicKey.orEmpty()
    }
    var menu by remember { mutableStateOf(false) }
    val selected = devices.firstOrNull { it.publicKey == selectedKey }
    val selectedEvents = events.filter { it.publicKey.equals(selectedKey, true) }
    val grouped = selectedEvents.groupBy { it.channel }.entries.sortedByDescending { entry ->
        entry.value.maxOfOrNull { it.timestamp }.orEmpty()
    }
    PullToRefreshBox(refreshing, ChannelStatisticsEngine::refreshNow, modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text("CHANNEL STATISTICS", fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { if (selectedKey.isNotBlank()) ChannelStatisticsEngine.reset(selectedKey) },
                enabled = selectedEvents.isNotEmpty()) { Text("Reset") }
        }
        ExposedDropdownMenuBox(menu, { menu = !menu }, Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = selected?.let { "${it.publicKey.take(4).uppercase()} · ${it.name}" }.orEmpty(),
                onValueChange = {}, readOnly = true, label = { Text("Tracked key") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menu) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            ExposedDropdownMenu(menu, { menu = false }) {
                devices.forEach { device -> DropdownMenuItem(
                    text = { Text("${device.publicKey.take(4).uppercase()} · ${device.name}") },
                    onClick = { selectedKey = device.publicKey; menu = false },
                ) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            StatisticValue("Sent", selectedEvents.count { it.sent })
            StatisticValue("Mentions", selectedEvents.count { it.mention })
            StatisticValue("Channels", grouped.size)
        }
        HorizontalDivider()
        if (grouped.isEmpty()) Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("No related channel traffic in the last 24 hours", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else LazyColumn(Modifier.fillMaxSize()) {
            items(grouped, key = { it.key }) { (channel, values) ->
                val ordered = values.sortedBy { it.timestamp }
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row { Text(channel, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                        Spacer(Modifier.weight(1f)); Text("${values.size} packets", fontSize = 12.sp) }
                    Text("First: ${WarsawTimeFormatter.dateTime(ordered.first().timestamp)}",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp))
                    Text("Last: ${WarsawTimeFormatter.dateTime(ordered.last().timestamp)}",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp))
                    Text("Sent ${values.count { it.sent }} · Mentions ${values.count { it.mention }}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
        }
    } }
}

@Composable private fun StatisticValue(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable private fun MentionText(text: String, tracked: Set<String>) =
    TrackedNameText(text, tracked, color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp))

private fun pl.meshcore.monitor.data.ChannelMessage.isTrackedMessage(tracked: Set<String>): Boolean =
    tracked.any { name -> sender.trim().equals(name.trim(), true) || sender.trim().startsWith("${name.trim()} ", true) } ||
        TrackedMention.contains(text, tracked)

private fun pl.meshcore.monitor.data.ChannelMessage.epochMillis(): Long =
    runCatching { Instant.parse(timestamp).toEpochMilli() }.getOrDefault(0L)

@Composable private fun ChannelDetailsDialog(details: ChannelMessageDetails, close: () -> Unit) {
    val message = details.message
    AlertDialog(onDismissRequest = close, title = { Text(message.sender, fontSize = 16.sp) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(WarsawTimeFormatter.dateTime(message.timestamp), fontSize = 11.sp); Text("Hops: ${details.path.size.takeIf { it > 0 } ?: message.hops}", fontSize = 11.sp)
        Text("Route: ${details.path.takeIf { it.isNotEmpty() }?.joinToString(" → ") ?: "Unavailable"}", fontSize = 11.sp)
        Text("Seen: ${details.observationCount} · Repeats: ${message.repeats}", fontSize = 11.sp)
        Text("Observers: ${message.observers.joinToString().ifBlank { "Unavailable" }}", fontSize = 11.sp)
        Text("Signal: ${details.rssi?.let { "$it dBm" } ?: "—"} · SNR: ${message.snr?.let { "$it dB" } ?: "—"}", fontSize = 11.sp)
        Text("Packet: ${message.packetHash.ifBlank { message.id }}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
    } }, confirmButton = { TextButton(close) { Text("Close") } })
}
