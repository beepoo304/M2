package pl.meshcore.monitor.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.common.InputImage
import pl.meshcore.monitor.data.ChannelMessageDetails
import pl.meshcore.monitor.data.ChannelSummary
import pl.meshcore.monitor.data.WarsawTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ChannelsScreen(modifier: Modifier, trackedNames: Set<String>, vm: ChannelsViewModel = viewModel()) {
    val context = LocalContext.current; val state by vm.state.collectAsState()
    var add by remember { mutableStateOf(false) }; var input by rememberSaveable { mutableStateOf("") }; var invalid by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ChannelSummary?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
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
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.selected != null) IconButton(vm::closeChannel) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text(state.selected?.name ?: "CHANNELS", fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f))
            Text(if (state.selected == null) "${state.myChannels.size} channels" else "${state.messages.size} messages",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
        if (state.selected != null) {
            PullToRefreshBox(state.loading, vm::refresh, Modifier.fillMaxSize()) { LazyColumn(Modifier.fillMaxSize()) {
                items(state.messages, key = { it.id }) { message ->
                    val tracked = message.sender.trim().lowercase() in trackedNames
                    Column(Modifier.fillMaxWidth().clickable { vm.showMessageDetails(message) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row { Text(message.sender, fontWeight = FontWeight.Medium,
                            color = if (tracked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.weight(1f)); Text(WarsawTimeFormatter.dateTime(message.timestamp), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
        else LazyColumn(Modifier.fillMaxSize()) { items(state.myChannels, key = { it.hash }) { channel ->
            Row(Modifier.fillMaxWidth().clickable { vm.open(channel) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(channel.name, fontWeight = FontWeight.Medium)
                    Text(if (channel.isPrivate) "Private channel" else "Saved public channel", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (channel.isPrivate) {
                    IconButton({ renameTarget = channel; renameText = channel.name }) {
                        Icon(Icons.Outlined.Edit, "Rename private channel")
                    }
                }
                IconButton({ vm.removeSaved(channel) }) { Icon(Icons.Outlined.Delete, "Remove channel") }
            }; HorizontalDivider()
        } }
    }
}

@Composable private fun MentionText(text: String, tracked: Set<String>) {
    val mention = Regex("@\\[([^]]+)]"); val styled = buildAnnotatedString { var position = 0
        mention.findAll(text).forEach { match -> append(text.substring(position, match.range.first))
            val own = match.groupValues[1].trim().lowercase() in tracked
            withStyle(SpanStyle(color = if (own) MaterialTheme.colorScheme.primary else Color(0xFF64B5F6), fontWeight = FontWeight.Medium)) { append(match.value) }
            position = match.range.last + 1 }
        append(text.substring(position)) }
    Text(styled, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun ChannelDetailsDialog(details: ChannelMessageDetails, close: () -> Unit) {
    val message = details.message
    AlertDialog(onDismissRequest = close, title = { Text(message.sender) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(WarsawTimeFormatter.dateTime(message.timestamp)); Text("Hops: ${details.path.size.takeIf { it > 0 } ?: message.hops}")
        Text("Route: ${details.path.takeIf { it.isNotEmpty() }?.joinToString(" → ") ?: "Unavailable"}")
        Text("Seen: ${details.observationCount} · Repeats: ${message.repeats}")
        Text("Observers: ${message.observers.joinToString().ifBlank { "Unavailable" }}")
        Text("Signal: ${details.rssi?.let { "$it dBm" } ?: "—"} · SNR: ${message.snr?.let { "$it dB" } ?: "—"}")
        Text("Packet: ${message.packetHash.ifBlank { message.id }}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
    } }, confirmButton = { TextButton(close) { Text("Close") } })
}
