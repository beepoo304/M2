package pl.meshcore.monitor.ui

import pl.meshcore.monitor.data.ApiHealthEntry
import android.content.Intent
import android.net.Uri
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.text.NumberFormat
import pl.meshcore.monitor.BuildConfig
import pl.meshcore.monitor.data.ConnectionConfig
import pl.meshcore.monitor.data.ConnectionConfigBus

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SettingsScreen(modifier: Modifier, vm: SettingsViewModel, onCloseApp: () -> Unit) {
    val context = LocalContext.current
    val devices by vm.devices.collectAsState(); val active by ConnectionConfigBus.config.collectAsState()
    val savedApis by vm.savedApis.collectAsState()
    var url by rememberSaveable { mutableStateOf(vm.initialConfig.coreScopeBaseUrl) }
    LaunchedEffect(active.coreScopeBaseUrl) { url = active.coreScopeBaseUrl }
    var key by rememberSaveable { mutableStateOf("") }; var error by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(false) }
    var showApiLog by remember { mutableStateOf(false) }
    var apiMenu by remember { mutableStateOf(false) }
    var addApi by remember { mutableStateOf(false) }
    var newApi by rememberSaveable { mutableStateOf("") }
    var apiError by remember { mutableStateOf(false) }
    val apiLog by vm.apiHealthLog.collectAsState()
    val apiOnline by vm.apiOnline.collectAsState()
    val exportLocation by vm.exportLocation.collectAsState()
    val neighbours by vm.neighbours.collectAsState()
    var pendingExportLocation by rememberSaveable { mutableStateOf(exportLocation) }
    val chooseExportFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            runCatching { context.contentResolver.takePersistableUriPermission(
                it, FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION) }
            pendingExportLocation = it.toString()
        }
    }
    if (showApiLog) ApiLogDialog(apiOnline, apiLog, vm::resetApiLog) { showApiLog = false }
    neighbours?.let { DeviceNeighboursDialog(it, vm::refreshNeighbours, vm::closeNeighbours) }
    if (addApi) AlertDialog(
        onDismissRequest = { addApi = false },
        title = { Text("Add LIVE API") },
        text = { OutlinedTextField(newApi, { newApi = it; apiError = false }, label = { Text("API address") },
            singleLine = true, isError = apiError) },
        confirmButton = { TextButton({
            vm.addApi(newApi)?.let { url = it; newApi = ""; addApi = false } ?: run { apiError = true }
        }) { Text("Add") } },
        dismissButton = { TextButton({ addApi = false }) { Text("Cancel") } },
    )
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Text("SETTINGS", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 16.dp, bottom = 12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("LIVE API", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton({ showApiLog = true }) { Text("Log") }
            }
            Text("Active: ${active.coreScopeBaseUrl}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            ExposedDropdownMenuBox(expanded = apiMenu, onExpandedChange = { apiMenu = it }) {
                OutlinedTextField(
                    value = url,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Saved API") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(apiMenu) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth().padding(top = 8.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                ExposedDropdownMenu(expanded = apiMenu, onDismissRequest = { apiMenu = false }) {
                    savedApis.forEach { address ->
                        val protected = address.trimEnd('/') == active.coreScopeBaseUrl.trimEnd('/') ||
                            address.trimEnd('/') == "https://live.meshcorekk.xyz"
                        DropdownMenuItem(text = { Text(address, style = MaterialTheme.typography.bodySmall,
                            maxLines = 1) }, onClick = {
                            url = address; apiMenu = false
                        }, trailingIcon = {
                            IconButton(onClick = {
                                if (vm.removeApi(address) && url == address) url = active.coreScopeBaseUrl
                            }, enabled = !protected) {
                                Icon(Icons.Outlined.Delete, if (protected) "Protected API" else "Delete API")
                            }
                        })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Add API…", color = MaterialTheme.colorScheme.primary) }, onClick = {
                        apiMenu = false; addApi = true
                    })
                }
            }
            Text("M2 reads the public Live API. MQTT credentials are not required.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button({ vm.save(ConnectionConfig(url.trim(), devices.map { it.publicKey }.toSet())) },
                Modifier.padding(vertical = 8.dp)) { Text("Save connection") }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("TRACKED DEVICE KEYS", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            OutlinedTextField(key, { key = it; error = false }, label = { Text("64-character public key") },
                isError = error, singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton({ show = !show }) { Text(if (show) "Hide" else "Show") } },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            Button({ if (vm.addDevice(key)) key = "" else error = true }, Modifier.padding(vertical = 6.dp)) { Text("Add device") }
        }
        items(devices, key = { it.publicKey }) { device ->
            Row(Modifier.fillMaxWidth().clickable { vm.openNeighbours(device) }.padding(vertical = 5.dp)) {
                Column(Modifier.weight(1f)) { Text(device.name, fontWeight = FontWeight.Medium)
                    Text("${device.publicKey.take(8)}…${device.publicKey.takeLast(6)}", fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("START ${deviceCounterStart(device.counterStartedAt)}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text("PACKETS ${devicePacketCount(device.packetCount)}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                IconButton({ vm.removeDevice(device.publicKey) }) { Icon(Icons.Outlined.Delete, "Remove device") }
            }; HorizontalDivider()
        }
        item {
            HorizontalDivider(Modifier.padding(top = 16.dp, bottom = 12.dp))
            Text("EXPORT LOCATION", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text("DEFAULT: Download/M2", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!pendingExportLocation.isNullOrBlank()) Text(
                "SELECTED: ${vm.exportLocationLabel(pendingExportLocation)}",
                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    vm.saveExportLocation(pendingExportLocation)
                    Toast.makeText(context, "Export location saved", Toast.LENGTH_SHORT).show()
                }) { Text("Save") }
                OutlinedButton(onClick = { chooseExportFolder.launch(null) }) { Text("Choose location") }
                if (!pendingExportLocation.isNullOrBlank()) TextButton(onClick = { pendingExportLocation = null }) {
                    Text("Default")
                }
            }
        }
        item {
            HorizontalDivider(Modifier.padding(top = 16.dp))
            Column(
                Modifier.fillMaxWidth().clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ko-fi.com/beepoo304")))
                }.padding(vertical = 18.dp),
            ) {
                Text("Support M2 on Ko-fi", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                Text("ko-fi.com/beepoo304", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            HorizontalDivider()
            Text(
                "M² version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
            Text("Open source on GitHub",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            Text("github.com/beepoo304/M2",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/beepoo304/M2")))
                }.padding(top = 2.dp, bottom = 2.dp))
            var closing by remember { mutableStateOf(false) }
            Button(
                onClick = { closing = true; onCloseApp() },
                enabled = !closing,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text(if (closing) "Closing…" else "Close application") }
            Text("Stops background listening and closes M2.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
        }
    }
}

@Composable
private fun DeviceNeighboursDialog(state: DeviceNeighboursState, refresh: () -> Unit, close: () -> Unit) {
    AlertDialog(
        modifier = Modifier.fillMaxWidth(0.96f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = close,
        title = { Column {
            Text(state.deviceName, maxLines = 1)
            Text(state.deviceKey.take(4).uppercase(), fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        } },
        text = { LazyColumn(Modifier.heightIn(max = 560.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("RPT INFO", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(refresh, enabled = !state.loading) { Icon(Icons.Outlined.Refresh, "Refresh RPT information") }
                }
                Text("Source: ${state.sourceApi}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                if (state.updatedAtMs > 0L) Text("Updated: ${apiLogTime(state.updatedAtMs)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                state.rptInfo?.let { RptInfoBlock(it) }
                if (state.rptInfo == null && !state.loading && state.error == null) {
                    Text("RPT information unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Text("NEIGHBOURS (${state.neighbours.size})", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
            }
            if (state.neighbours.isEmpty() && !state.loading) item { Text("No neighbours found") }
            else {
                items(state.neighbours, key = { it.hash }) { neighbour ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text("${neighbour.hash} · ${neighbour.name}", modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall)
                        Text("${neighbour.count}×", fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        } },
        confirmButton = { TextButton(close) { Text("Close") } },
    )
}

@Composable
private fun RptInfoBlock(info: DeviceRptInfo) {
    val context = LocalContext.current
    val language = remember {
        context.getSharedPreferences("guide_settings", android.content.Context.MODE_PRIVATE)
            .getString("language", "PL").orEmpty()
    }
    val help = rptMetricHelp[language] ?: rptMetricHelp.getValue("EN")
    var explanation by remember { mutableStateOf<Pair<String, String>?>(null) }
    explanation?.let { (title, text) ->
        AlertDialog(
            onDismissRequest = { explanation = null },
            title = { Text(title) },
            text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton({ explanation = null }) { Text(help.close) } },
        )
    }
    val active = rptIsActive(info.lastHeard)
    OutlinedCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            RptInfoRow("Status", if (active) "● Active  ·  ${rptRelativeAge(info.lastHeard)}" else "● Inactive  ·  ${rptRelativeAge(info.lastHeard)}",
                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            RptInfoDivider()
            RptInfoRow("Last heard", rptRelativeAge(info.lastHeard))
            RptInfoDivider()
            RptMetricRow("Usefulness", info.usefulness,
                info.usefulnessGrade?.let { "$it  ${rptPercent(info.usefulness)}" } ?: rptPercent(info.usefulness),
                help.usefulness) { explanation = "Usefulness" to it }
            RptMetricRow("Traffic share", info.trafficShare,
                rptPercent(info.trafficShare) + rptMetricLabel(info.trafficShare, "traffic"),
                help.trafficShare) { explanation = "Traffic share" to it }
            RptMetricRow("Bridge score", info.bridgeScore,
                rptPercent(info.bridgeScore) + rptMetricLabel(info.bridgeScore, "bridge"),
                help.bridgeScore) { explanation = "Bridge score" to it }
            RptMetricRow("Coverage", info.coverage,
                rptPercent(info.coverage) + rptMetricLabel(info.coverage, "coverage"),
                help.coverage) { explanation = "Coverage" to it }
            RptMetricRow("Redundancy", info.redundancy,
                rptPercent(info.redundancy) + rptMetricLabel(info.redundancy, "redundancy"),
                help.redundancy) { explanation = "Redundancy" to it }
            RptInfoDivider()
            if (info.firstSeen.isNotBlank()) RptInfoRow("First seen", rptDate(info.firstSeen))
            info.totalPackets?.let { total ->
                val seen = info.totalObservations?.let { " (seen ${it}×)" }.orEmpty()
                RptInfoRow("Total packets", "$total$seen")
            }
            info.packetsToday?.let { RptInfoRow("Packets today", it.toString()) }
            info.averageSnr?.let { RptInfoRow("Avg. SNR", "%.1f dB".format(Locale.US, it)) }
            info.averageHops?.let { RptInfoRow("Avg. hops", "%.1f".format(Locale.US, it).removeSuffix(".0")) }
        }
    }
}

@Composable
private fun RptInfoDivider() {
    HorizontalDivider(
        Modifier.padding(vertical = 3.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
    )
}

@Composable
private fun RptInfoRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp), maxLines = 1)
        Text(value, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
            color = valueColor, textAlign = TextAlign.End, maxLines = 1,
            modifier = Modifier.weight(1f).padding(start = 6.dp))
    }
}

@Composable
private fun RptMetricRow(label: String, value: Double?, text: String, explanation: String, showExplanation: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                modifier = Modifier.width(94.dp))
            Box(
                Modifier.width(28.dp).clickable { showExplanation(explanation) },
                contentAlignment = Alignment.Center,
            ) { Text("ⓘ", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp) }
            Text(text.ifBlank { "—" }, fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End,
                modifier = Modifier.weight(1f), maxLines = 1)
        }
        LinearProgressIndicator(
            progress = { rptNormalized(value).toFloat() },
            modifier = Modifier.fillMaxWidth().height(5.dp).padding(top = 2.dp),
        )
    }
}

private data class RptMetricHelp(
    val usefulness: String,
    val trafficShare: String,
    val bridgeScore: String,
    val coverage: String,
    val redundancy: String,
    val close: String,
)

private val rptMetricHelp = mapOf(
    "PL" to RptMetricHelp(
        "Ogólna użyteczność RPT wyliczana na podstawie pozostałych wskaźników sieci.",
        "Jak często RPT występuje w zarejestrowanych trasach pakietów.",
        "W jakim stopniu RPT łączy części sieci, które bez niego byłyby od siebie oddzielone.",
        "Jak szeroki zasięg sieci i obszaru pomaga zapewnić ten RPT.",
        "Jak łatwo inne trasy mogą zastąpić ten RPT. Niska wartość oznacza dużą zastępowalność.", "Zamknij"),
    "EN" to RptMetricHelp(
        "Overall RPT usefulness calculated from the remaining network indicators.",
        "How often the RPT appears in recorded packet routes.",
        "How strongly the RPT connects parts of the mesh that would otherwise be separated.",
        "How widely this RPT helps the mesh reach nodes and areas.",
        "How easily alternative routes can replace this RPT. A low value means it is highly replaceable.", "Close"),
    "DE" to RptMetricHelp(
        "Gesamtnutzen des RPT, berechnet aus den übrigen Netzwerkindikatoren.",
        "Wie häufig der RPT in aufgezeichneten Paketrouten vorkommt.",
        "Wie stark der RPT sonst getrennte Teile des Mesh-Netzes verbindet.",
        "Wie groß der vom RPT unterstützte Netz- und Gebietsbereich ist.",
        "Wie leicht andere Routen diesen RPT ersetzen können. Ein niedriger Wert bedeutet hohe Ersetzbarkeit.", "Schließen"),
    "FR" to RptMetricHelp(
        "Utilité globale du RPT calculée à partir des autres indicateurs du réseau.",
        "Fréquence à laquelle le RPT apparaît dans les routes de paquets enregistrées.",
        "Capacité du RPT à relier des parties du mesh qui seraient autrement séparées.",
        "Étendue du réseau et des zones que ce RPT aide à couvrir.",
        "Facilité avec laquelle d’autres routes peuvent remplacer ce RPT. Une valeur faible indique qu’il est facilement remplaçable.", "Fermer"),
    "RU" to RptMetricHelp(
        "Общая полезность RPT, рассчитанная по остальным показателям сети.",
        "Как часто RPT встречается в зарегистрированных маршрутах пакетов.",
        "Насколько сильно RPT соединяет части mesh-сети, которые иначе были бы разделены.",
        "Насколько широкую область сети помогает охватить этот RPT.",
        "Насколько легко другие маршруты могут заменить этот RPT. Низкое значение означает высокую заменяемость.", "Закрыть"),
    "CZ" to RptMetricHelp(
        "Celková užitečnost RPT vypočtená z ostatních síťových ukazatelů.",
        "Jak často se RPT objevuje v zaznamenaných trasách paketů.",
        "Jak výrazně RPT propojuje části mesh sítě, které by jinak byly oddělené.",
        "Jak široký dosah sítě a území tento RPT pomáhá zajistit.",
        "Jak snadno mohou jiné trasy tento RPT nahradit. Nízká hodnota znamená vysokou nahraditelnost.", "Zavřít"),
    "SK" to RptMetricHelp(
        "Celková užitočnosť RPT vypočítaná z ostatných sieťových ukazovateľov.",
        "Ako často sa RPT objavuje v zaznamenaných trasách paketov.",
        "Ako výrazne RPT prepája časti mesh siete, ktoré by inak boli oddelené.",
        "Aký široký dosah siete a územia tento RPT pomáha zabezpečiť.",
        "Ako ľahko môžu iné trasy tento RPT nahradiť. Nízka hodnota znamená vysokú nahraditeľnosť.", "Zavrieť"),
)

private fun rptNormalized(value: Double?): Double = when {
    value == null -> 0.0
    value <= 1.0 -> value.coerceIn(0.0, 1.0)
    else -> (value / 100.0).coerceIn(0.0, 1.0)
}

private fun rptPercent(value: Double?): String = value?.let { "%.1f%%".format(Locale.US, rptNormalized(it) * 100.0) }.orEmpty()

private fun rptMetricLabel(value: Double?, type: String): String {
    val percent = value?.let { rptNormalized(it) * 100.0 } ?: return ""
    val label = when (type) {
        "traffic" -> if (percent < 15) "Redundant" else if (percent < 35) "Marginal" else "Significant"
        "bridge" -> if (percent < 5) "Marginal" else if (percent < 20) "Some role" else "Key bridge"
        "coverage" -> if (percent < 40) "Local reach" else if (percent < 70) "Good reach" else "Wide reach"
        else -> if (percent < 15) "Replaceable" else if (percent < 40) "Useful" else "Critical"
    }
    return "  $label"
}

private fun rptIsActive(timestamp: String): Boolean = runCatching {
    System.currentTimeMillis() - java.time.Instant.parse(timestamp).toEpochMilli() <= 24 * 60 * 60_000L
}.getOrDefault(false)

private fun rptRelativeAge(timestamp: String): String = runCatching {
    val seconds = ((System.currentTimeMillis() - java.time.Instant.parse(timestamp).toEpochMilli()) / 1_000L).coerceAtLeast(0L)
    when {
        seconds < 60 -> "${seconds}s ago"
        seconds < 3_600 -> "${seconds / 60}m ago"
        seconds < 86_400 -> "${seconds / 3_600}h ago"
        else -> "${seconds / 86_400}d ago"
    }
}.getOrDefault("—")

private fun rptDate(timestamp: String): String = runCatching {
    SimpleDateFormat("dd.MM.yyyy, HH:mm:ss", Locale.getDefault()).format(Date(java.time.Instant.parse(timestamp).toEpochMilli()))
}.getOrDefault(timestamp)

@Composable
private fun ApiLogDialog(online: Boolean?, entries: List<ApiHealthEntry>, reset: () -> Unit, close: () -> Unit) {
    AlertDialog(
        onDismissRequest = close,
        title = { Column { Text("LIVE API LOG"); Text(pl.meshcore.monitor.data.ConnectionConfigBus.config.value.coreScopeBaseUrl, style = MaterialTheme.typography.labelSmall) } },
        text = {
            Column {
                Text(
                    when (online) { true -> "API ONLINE"; false -> "API DOWN"; null -> "CHECKING API…" },
                    color = when (online) {
                        true -> MaterialTheme.colorScheme.primary
                        false -> MaterialTheme.colorScheme.error
                        null -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = FontWeight.Bold,
                )
                Text("Checked every 60 seconds · every 15 seconds during an outage", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                if (entries.isEmpty()) {
                    Text("No API outages recorded", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(Modifier.heightIn(max = 480.dp)) {
                        items(entries) { entry ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Text("API DOWN  ${apiLogTime(entry.startedAtMs)}",
                                    color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
                                Text(
                                    if (entry.ongoing) "Still down" else "API RESTORED  ${apiLogTime(entry.lastCheckedAtMs)}",
                                    color = if (entry.ongoing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                )
                                Text("Duration: ${apiDuration(entry.lastCheckedAtMs - entry.startedAtMs)}",
                                    style = MaterialTheme.typography.bodySmall)
                                Text(entry.status, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        dismissButton = { TextButton(reset) { Text("RESET LOG", color = MaterialTheme.colorScheme.error) } },
        confirmButton = { TextButton(close) { Text("Close") } },
    )
}

private fun apiLogTime(value: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(value))

private fun apiDuration(value: Long): String {
    val seconds = (value.coerceAtLeast(0L) / 1_000L)
    val hours = seconds / 3_600
    val minutes = seconds % 3_600 / 60
    val rest = seconds % 60
    return if (hours > 0) "%dh %02dm %02ds".format(hours, minutes, rest)
    else "%dm %02ds".format(minutes, rest)
}

private fun deviceCounterStart(value: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(value))

private fun devicePacketCount(value: Long): String =
    NumberFormat.getIntegerInstance(Locale.getDefault()).format(value).replace('\u00a0', ' ')
