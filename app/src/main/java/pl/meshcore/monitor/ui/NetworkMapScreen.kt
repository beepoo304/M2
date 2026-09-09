package pl.meshcore.monitor.ui

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import pl.meshcore.monitor.data.MapEdge
import pl.meshcore.monitor.data.MapPacketFilter
import pl.meshcore.monitor.data.MapFileStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkMapScreen(
    modifier: Modifier = Modifier,
    devices: List<SettingsViewModel.DeviceEntry>,
    vm: NetworkMapViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    var keyMenu by remember { mutableStateOf(false) }
    var filterMenu by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var fileProgress by remember { mutableIntStateOf(0) }
    var fileBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val loadMap = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        fileBusy = true; fileProgress = 20; exportMessage = "Loading map…"
        runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("Cannot read file") }
            .onSuccess { raw -> fileProgress = 70; runCatching { vm.importMap(raw) }.onSuccess { fileProgress = 100; exportMessage = "Map loaded" }.onFailure { exportMessage = "Cannot load map" } }
            .onFailure { exportMessage = "Cannot read map file" }
        fileBusy = false
    }
    LaunchedEffect(devices) {
        if (state.selectedKey.isBlank() && devices.isNotEmpty()) vm.select(devices.first().publicKey, devices.first().name)
    }
    val selectedDevice = devices.firstOrNull { it.publicKey == state.selectedKey }
    LaunchedEffect(exportMessage) {
        if (exportMessage != null) {
            kotlinx.coroutines.delay(3_000)
            exportMessage = null
        }
    }

    Column(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ExposedDropdownMenuBox(keyMenu, { keyMenu = !keyMenu }, Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = selectedDevice?.publicKey?.take(4)?.uppercase().orEmpty(),
                    onValueChange = {}, readOnly = true, label = { Text("Tracked key") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(keyMenu) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(), singleLine = true,
                )
                ExposedDropdownMenu(keyMenu, { keyMenu = false }, modifier = Modifier.fillMaxWidth()) {
                    devices.forEach { device -> DropdownMenuItem(
                        text = { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(device.publicKey.take(4).uppercase(), fontWeight = FontWeight.Bold)
                            Text(device.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        } },
                        onClick = { vm.select(device.publicKey, device.name); keyMenu = false },
                    ) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExposedDropdownMenuBox(filterMenu, { filterMenu = !filterMenu }, Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = state.session?.filter?.label.orEmpty(), onValueChange = {}, readOnly = true,
                        label = { Text("Packet type") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(filterMenu) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(), singleLine = true,
                    )
                    ExposedDropdownMenu(filterMenu, { filterMenu = false }) {
                        MapPacketFilter.entries.forEach { filter -> DropdownMenuItem(
                            text = { Text(filter.label) }, onClick = { vm.setFilter(filter); filterMenu = false },
                        ) }
                    }
                }
                Row(Modifier.width(150.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        val session = state.session
                        if (session == null) exportMessage = "No map to save"
                        else {
                            fileBusy = true; fileProgress = 0; exportMessage = "Exporting map…"
                            scope.launch(Dispatchers.IO) {
                            runCatching { MapFileStore.save(context, session, state.selectedName) { fileProgress = it } }
                                .onSuccess { withContext(Dispatchers.Main) { exportMessage = "Map exported to Download/M2"; fileBusy = false } }
                                .onFailure { withContext(Dispatchers.Main) { exportMessage = "Cannot save map"; fileBusy = false } }
                            }
                        }
                    }, Modifier.weight(1f).height(56.dp), enabled = !fileBusy, contentPadding = PaddingValues(0.dp)) { Text("Export") }
                    OutlinedButton(onClick = { loadMap.launch(arrayOf("application/json", "application/octet-stream", "*/*")) },
                        Modifier.weight(1f).height(56.dp), enabled = !fileBusy, contentPadding = PaddingValues(0.dp)) { Text("Load") }
                }
            }
        }

        val session = state.session
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (session?.running == true) Button(vm::stop, Modifier.weight(1f)) {
                Icon(Icons.Outlined.Stop, null); Spacer(Modifier.width(6.dp)); Text("Stop")
            } else Button(vm::start, Modifier.weight(1f), enabled = state.selectedKey.isNotBlank()) {
                Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Start")
            }
            OutlinedButton(vm::restart, Modifier.weight(1f), enabled = state.selectedKey.isNotBlank()) {
                Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Restart")
            }
        }

        val status = when {
            state.loadingNodes -> "Loading repeater locations…"
            session?.running == true -> "TRACKING · ${state.selectedKey.take(4).uppercase()} · ${session.filter.label}"
            else -> "STOPPED · ${state.selectedKey.take(4).uppercase()}"
        }
        Text(status, Modifier.padding(start = 14.dp, top = 8.dp), style = MaterialTheme.typography.labelMedium,
            color = if (session?.running == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "Packets ${session?.events?.distinctBy { it.packetId }?.size ?: 0} · Routes ${session?.events?.size ?: 0} · Links ${state.edges.size} · GPS nodes ${state.knownNodeCount}",
            Modifier.padding(start = 14.dp, bottom = 6.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 14.dp)) }
        if (fileBusy) LinearProgressIndicator({ fileProgress / 100f }, Modifier.fillMaxWidth().padding(horizontal = 14.dp))
        exportMessage?.let { Text(it, Modifier.padding(horizontal = 14.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall) }
        TrackingMap(state.edges, state.selectedNode, Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable
private fun TrackingMap(edges: List<MapEdge>, selectedNode: pl.meshcore.monitor.data.MapNodePoint?, modifier: Modifier) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    var pulseWide by remember { mutableStateOf(false) }
    LaunchedEffect(selectedNode) {
        while (true) {
            kotlinx.coroutines.delay(850)
            pulseWide = !pulseWide
        }
    }
    AndroidView(
        modifier = modifier.clipToBounds(),
        factory = {
            Configuration.getInstance().userAgentValue = context.packageName
            MapView(context).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(7.0); controller.setCenter(GeoPoint(50.15, 19.2))
                setBuiltInZoomControls(false)
            }
        },
        update = { map ->
            map.overlayManager.tilesOverlay.setColorFilter(mapColorFilter(dark))
            val selectedId = selectedNode?.let { "${it.hash}:${it.lat}:${it.lon}" }
            if (selectedId != null && map.tag != selectedId) {
                map.tag = selectedId
                map.controller.setZoom(12.0)
                map.controller.animateTo(GeoPoint(selectedNode.lat, selectedNode.lon))
            }
            map.overlays.removeAll { it is Polyline || it is Marker }
            edges.forEach { edge -> map.overlays += edgePolyline(edge) }
            (edges.flatMap { listOf(it.from, it.to) } + listOfNotNull(selectedNode))
                .distinctBy { "${it.hash}:${it.lat}:${it.lon}" }
                .forEach { point ->
                    val isSelected = selectedNode != null &&
                        point.hash.equals(selectedNode.hash, ignoreCase = true) &&
                        point.lat == selectedNode.lat && point.lon == selectedNode.lon
                    map.overlays += Marker(map).apply {
                        position = GeoPoint(point.lat, point.lon)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = point.hash
                        snippet = if (point.uncertain) "Possible 1-byte match" else "2-byte hash"
                        val dot = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(when {
                                isSelected -> 0xFF35E879.toInt()
                                point.uncertain -> 0xFFFFA726.toInt()
                                else -> 0xFF35E879.toInt()
                            })
                            setStroke(2, 0xE0101215.toInt())
                            val size = if (isSelected) 32 else 24
                            setSize(size, size)
                        }
                        val pulse = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(0x1835E879)
                            setStroke(2, 0x9935E879.toInt())
                            setSize(56, 56)
                        }
                        val outer = if (isSelected) 64 else 56
                        val touchArea = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(android.graphics.Color.TRANSPARENT)
                            setSize(outer, outer)
                        }
                        val layers = if (isSelected)
                            arrayOf(touchArea, pulse, dot)
                        else arrayOf(touchArea, dot)
                        icon = LayerDrawable(layers).apply {
                            val inner = if (isSelected) 32 else 24
                            val inset = (outer - inner) / 2
                            if (isSelected) {
                                val pulseInset = if (pulseWide) 4 else 10
                                setLayerInset(1, pulseInset, pulseInset, pulseInset, pulseInset)
                                setLayerInset(2, inset, inset, inset, inset)
                            } else setLayerInset(1, inset, inset, inset, inset)
                            setBounds(0, 0, outer, outer)
                        }
                    }
                }
            map.invalidate()
        },
    )
}

private fun edgePolyline(edge: MapEdge) = Polyline().apply {
    setPoints(listOf(GeoPoint(edge.from.lat, edge.from.lon), GeoPoint(edge.to.lat, edge.to.lon)))
    outlinePaint.color = if (edge.uncertain) 0xFFF0A84B.toInt() else 0xFF42D47B.toInt()
    outlinePaint.strokeWidth = (2.6f + minOf(edge.count, 8) * .22f)
    outlinePaint.alpha = minOf(175 + edge.count * 8, 255)
    if (edge.uncertain) outlinePaint.pathEffect = DashPathEffect(floatArrayOf(8f, 5f), 0f)
}

private fun mapColorFilter(dark: Boolean): ColorMatrixColorFilter {
    val matrix = if (dark) floatArrayOf(
        -.1063f, -.3576f, -.0361f, 0f, 190f,
        -.1063f, -.3576f, -.0361f, 0f, 190f,
        -.1063f, -.3576f, -.0361f, 0f, 190f,
        0f, 0f, 0f, 1f, 0f,
    ) else floatArrayOf(
        .2126f, .7152f, .0722f, 0f, 0f,
        .2126f, .7152f, .0722f, 0f, 0f,
        .2126f, .7152f, .0722f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
    return ColorMatrixColorFilter(ColorMatrix(matrix))
}
