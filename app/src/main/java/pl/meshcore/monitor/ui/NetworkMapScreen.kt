package pl.meshcore.monitor.ui

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.drawable.BitmapDrawable
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.zIndex
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
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
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import pl.meshcore.monitor.data.MapEdge
import pl.meshcore.monitor.data.MapPacketFilter
import pl.meshcore.monitor.data.MapFileStore
import pl.meshcore.monitor.data.ExportLocationStore
import pl.meshcore.monitor.data.MapNodePoint
import pl.meshcore.monitor.data.MapRouteMapper
import pl.meshcore.monitor.R
import pl.meshcore.monitor.ScreenRecordingService
import kotlin.math.*
import androidx.core.view.WindowCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkMapScreen(
    modifier: Modifier = Modifier,
    devices: List<SettingsViewModel.DeviceEntry>,
    vm: NetworkMapViewModel = viewModel(),
    onFullscreenChanged: (Boolean) -> Unit = {},
) {
    val state by vm.state.collectAsState()
    var keyMenu by remember { mutableStateOf(false) }
    var filterMenu by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var fileProgress by remember { mutableIntStateOf(0) }
    var fileBusy by remember { mutableStateOf(false) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var animationToken by remember { mutableIntStateOf(0) }
    var prepareFlightToken by remember { mutableIntStateOf(0) }
    var focusToken by remember { mutableIntStateOf(0) }
    var animationRunning by remember { mutableStateOf(false) }
    var recordedVideoPath by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val view = LocalView.current
    val capturePermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val metrics = context.resources.displayMetrics
            fullscreen = true
            prepareFlightToken++
            scope.launch {
                kotlinx.coroutines.delay(1_000)
                ContextCompat.startForegroundService(context, Intent(context, ScreenRecordingService::class.java).apply {
                    action = ScreenRecordingService.ACTION_START
                    putExtra(ScreenRecordingService.EXTRA_DATA, data)
                    putExtra(ScreenRecordingService.EXTRA_WIDTH, metrics.widthPixels)
                    putExtra(ScreenRecordingService.EXTRA_HEIGHT, metrics.heightPixels)
                    putExtra(ScreenRecordingService.EXTRA_DENSITY, metrics.densityDpi)
                })
            }
        } else exportMessage = "Screen recording permission denied"
    }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                when (intent?.action) {
                    ScreenRecordingService.ACTION_STARTED -> scope.launch { kotlinx.coroutines.delay(250); animationToken++ }
                    ScreenRecordingService.ACTION_READY -> recordedVideoPath = intent.getStringExtra(ScreenRecordingService.EXTRA_PATH)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ScreenRecordingService.ACTION_STARTED)
            addAction(ScreenRecordingService.ACTION_READY)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
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
    LaunchedEffect(fullscreen) { onFullscreenChanged(fullscreen) }
    BackHandler(enabled = fullscreen) { fullscreen = false }
    DisposableEffect(fullscreen, view) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (fullscreen) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else controller?.show(WindowInsetsCompat.Type.systemBars())
        onDispose { if (!fullscreen) controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    recordedVideoPath?.let { capturePath ->
        AlertDialog(onDismissRequest = { java.io.File(capturePath).delete(); recordedVideoPath = null },
            title = { Text("Save animation as MP4?") },
            text = { Text("The animated longest route will be saved in ${ExportLocationStore.label(context)}.") },
            confirmButton = { TextButton(onClick = {
                recordedVideoPath = null; fileBusy = true; fileProgress = 20; exportMessage = "Saving screen recording…"
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        val source = java.io.File(capturePath)
                        val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                        val name = "M2-${state.selectedKey.take(4).uppercase()}-$stamp-flight.mp4"
                        fileProgress = 60
                        ExportLocationStore.writeBytes(context, name, "video/mp4", source.readBytes())
                        source.delete(); fileProgress = 100; name
                    }
                        .onSuccess { name -> withContext(Dispatchers.Main) { fileBusy = false; exportMessage = "Saved $name" } }
                        .onFailure { withContext(Dispatchers.Main) { fileBusy = false; exportMessage = "Cannot save MP4" } }
                }
            }) { Text("YES") } },
            dismissButton = { TextButton({ java.io.File(capturePath).delete(); recordedVideoPath = null }) { Text("NO") } })
    }

    if (fullscreen) {
        Box(Modifier.fillMaxSize().background(Color(0xFF111315))) {
            TrackingMap(state.edges, state.selectedNode, state.longestRoute, animationToken,
                focusToken, prepareFlightToken, Modifier.fillMaxSize(), onAnimationStateChanged = { animationRunning = it },
                onAnimationFinished = {
                    context.startService(Intent(context, ScreenRecordingService::class.java).setAction(ScreenRecordingService.ACTION_STOP))
                })
            IconButton({ fullscreen = false }, Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(12.dp)
                .size(58.dp).zIndex(20f).background(Color(0xE0202327), RoundedCornerShape(14.dp))) {
                Icon(Icons.Outlined.FullscreenExit, "Exit full screen", tint = Color.White)
            }
            Image(painterResource(R.drawable.m2_logo), "M²",
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(57.dp).zIndex(20f))
        }
        return
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
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
                                .onSuccess { withContext(Dispatchers.Main) { exportMessage = "Map exported to ${ExportLocationStore.label(context)}"; fileBusy = false } }
                                .onFailure { withContext(Dispatchers.Main) { exportMessage = "Cannot save map"; fileBusy = false } }
                            }
                        }
                    }, Modifier.weight(1f).height(48.dp), enabled = !fileBusy, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(0.dp)) { Text("Export") }
                    OutlinedButton(onClick = { loadMap.launch(arrayOf("application/json", "application/octet-stream", "*/*")) },
                        Modifier.weight(1f).height(48.dp), enabled = !fileBusy, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(0.dp)) { Text("Load") }
                }
            }
        }

        val session = state.session
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (session?.running == true || animationRunning) Button({
                if (animationRunning) animationToken = 0 else vm.stop()
            }, Modifier.weight(1f)) {
                Icon(Icons.Outlined.Stop, null); Spacer(Modifier.width(6.dp)); Text("Stop")
            } else Button(vm::start, Modifier.weight(1f), enabled = state.selectedKey.isNotBlank()) {
                Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Start")
            }
            OutlinedButton({ animationToken = 0; focusToken++; vm.restart() }, Modifier.weight(1f), enabled = state.selectedKey.isNotBlank()) {
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
            "Packets ${session?.events?.distinctBy { it.packetId }?.size ?: 0} · Routes ${session?.events?.size ?: 0} · Links ${state.edges.size} · MAX HOPS ${session?.events?.maxOfOrNull { it.path.size } ?: 0}",
            Modifier.padding(start = 14.dp, bottom = 6.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.padding(start = 14.dp, bottom = 4.dp)) {
            Text("Distance %.1f km · ".format(java.util.Locale.US, state.totalDistanceKm),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = {
                vm.stop()
                val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                capturePermission.launch(manager.createScreenCaptureIntent())
            }, enabled = state.longestRoute.size > 1,
                modifier = Modifier.height(30.dp), shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF2196F3)),
                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp)) {
                Text("Longest route %.1f km".format(java.util.Locale.US, state.longestRouteKm),
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 14.dp)) }
        if (fileBusy) LinearProgressIndicator({ fileProgress / 100f }, Modifier.fillMaxWidth().padding(horizontal = 14.dp))
        exportMessage?.let { Text(it, Modifier.padding(horizontal = 14.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall) }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            TrackingMap(state.edges, state.selectedNode, state.longestRoute, animationToken, focusToken, prepareFlightToken, Modifier.fillMaxSize(),
                onAnimationStateChanged = { animationRunning = it },
                onAnimationFinished = {})
            IconButton({ fullscreen = true }, Modifier.align(Alignment.TopEnd).padding(8.dp)
                .background(Color(0xA0202327), RoundedCornerShape(9.dp))) {
                Icon(Icons.Outlined.Fullscreen, "Full screen", tint = Color.White)
            }
        }
    }
}

@Composable
private fun TrackingMap(edges: List<MapEdge>, selectedNode: MapNodePoint?, longestRoute: List<MapNodePoint>,
    animationToken: Int, focusToken: Int, prepareFlightToken: Int, modifier: Modifier, onAnimationStateChanged: (Boolean) -> Unit,
    onAnimationFinished: () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    var pulseWide by remember { mutableStateOf(false) }
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var animationStep by remember { mutableIntStateOf(-1) }
    var flying by remember { mutableStateOf(false) }
    var compassBearing by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(flying) { onAnimationStateChanged(flying) }
    LaunchedEffect(selectedNode) {
        while (true) {
            kotlinx.coroutines.delay(850)
            pulseWide = !pulseWide
        }
    }
    LaunchedEffect(focusToken) {
        if (focusToken <= 0) return@LaunchedEffect
        mapView?.let { map ->
            map.mapOrientation = 0f
            selectedNode?.let { point ->
                map.controller.setZoom(12.0)
                map.controller.animateTo(GeoPoint(point.lat, point.lon))
            }
        }
    }
    LaunchedEffect(prepareFlightToken, longestRoute) {
        if (prepareFlightToken <= 0 || longestRoute.isEmpty()) return@LaunchedEffect
        mapView?.let { map ->
            map.mapOrientation = 0f
            map.controller.setZoom(13.0)
            map.controller.setCenter(GeoPoint(longestRoute.first().lat, longestRoute.first().lon))
            map.invalidate()
        }
    }
    LaunchedEffect(animationToken, longestRoute) {
        if (animationToken <= 0 || longestRoute.size < 2) return@LaunchedEffect
        flying = true
        animationStep = 0
        kotlinx.coroutines.delay(180)
        val map = mapView ?: run { flying = false; return@LaunchedEffect }
        var completed = false
        try {
        val nodeMarkers = map.overlays.filterIsInstance<Marker>()
        map.overlays.removeAll { it is Polyline || it is Marker }
        // Keep the complete green network visible for the whole flight. The blue
        // route is progressively painted over it and must never create a gap.
        edges.forEach { edgePolylines(it.copy(longestRoute = false)).forEach(map.overlays::add) }
        val flightLine = Polyline().apply {
            outlinePaint.color = 0xFF2196F3.toInt(); outlinePaint.strokeWidth = 16.875f; outlinePaint.alpha = 255
        }
        val revealed = mutableListOf(GeoPoint(longestRoute.first().lat, longestRoute.first().lon))
        val totalRouteKm = longestRoute.zipWithNext().sumOf { (a, b) ->
            MapRouteMapper.distanceKm(a.lat, a.lon, b.lat, b.lon)
        }
        map.overlays += flightLine
        // Node dots must stay above every route line during the complete flight.
        nodeMarkers.forEach(map.overlays::add)
        flightLine.setPoints(revealed)
        map.invalidate()
        map.controller.setZoom(13.0)
        map.controller.setCenter(GeoPoint(longestRoute.first().lat, longestRoute.first().lon))
        addFlightLabel(map, longestRoute.first(), "START · ${longestRoute.first().hash}")
        kotlinx.coroutines.delay(900)
        for (index in 1 until longestRoute.size) {
            val from = longestRoute[index - 1]; val to = longestRoute[index]
            val distance = MapRouteMapper.distanceKm(from.lat, from.lon, to.lat, to.lon)
            val targetZoom = minOf(flightZoom(distance), fitSegmentZoom(from, to, map.width, map.height))
            val startZoom = map.zoomLevelDouble
            val bearing = routeBearing(from, to)
            val duration = ((3200.0 + distance.coerceAtMost(150.0) * 16.0) * 1.30).toLong().coerceAtMost(7300L)
            val frames = (duration / 33L).toInt().coerceAtLeast(1)
            val middle = MapNodePoint("", (from.lat + to.lat) / 2.0, (from.lon + to.lon) / 2.0)
            addFlightLabel(map, middle, "↔ %.1f km".format(java.util.Locale.US, distance))
            var targetLabelShown = false
            repeat(frames + 1) { frame ->
                val raw = frame.toDouble() / frames
                val t = raw * raw * (3.0 - 2.0 * raw)
                val lat = from.lat + (to.lat - from.lat) * t
                val lon = from.lon + (to.lon - from.lon) * t
                map.controller.setCenter(GeoPoint(lat, lon))
                map.controller.setZoom(startZoom + (targetZoom - startZoom) * t)
                map.mapOrientation = 0f
                compassBearing = ((bearing + 360f) % 360f)
                flightLine.setPoints(revealed + GeoPoint(lat, lon))
                if (!targetLabelShown && raw >= .80) {
                    val label = if (index == longestRoute.lastIndex) "FINISH → HOP $index · ${to.hash}" else "HOP $index · ${to.hash}"
                    addFlightLabel(map, to, label)
                    if (index == longestRoute.lastIndex) addFlightLabel(map, to,
                        "LONGEST ROUTE · %.2f km".format(java.util.Locale.US, totalRouteKm), anchor = 2.65f)
                    targetLabelShown = true
                }
                map.invalidate()
                kotlinx.coroutines.delay(33)
            }
            revealed += GeoPoint(to.lat, to.lon)
            flightLine.setPoints(revealed)
            animationStep = index
            if (!targetLabelShown) {
                val label = if (index == longestRoute.lastIndex) "FINISH → HOP $index · ${to.hash}" else "HOP $index · ${to.hash}"
                addFlightLabel(map, to, label)
                if (index == longestRoute.lastIndex) addFlightLabel(map, to,
                    "LONGEST ROUTE · %.2f km".format(java.util.Locale.US, totalRouteKm), anchor = 2.65f)
            }
            kotlinx.coroutines.delay(1150)
        }
        kotlinx.coroutines.delay(800)
        map.mapOrientation = 0f
        map.zoomToBoundingBox(BoundingBox.fromGeoPoints(longestRoute.map { GeoPoint(it.lat, it.lon) }), true, 90)
        kotlinx.coroutines.delay(1500)
        kotlinx.coroutines.delay(2_500)
        animationStep = -1
        completed = true
        flying = false
        kotlinx.coroutines.delay(250)
        onAnimationFinished()
        } finally {
            flying = false
            map.mapOrientation = 0f
            if (!completed) animationStep = -1
        }
    }
    Box(modifier.clipToBounds()) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            Configuration.getInstance().userAgentValue = context.packageName
            MapView(context).apply {
                mapView = this
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(7.0); controller.setCenter(GeoPoint(50.15, 19.2))
                setBuiltInZoomControls(false)
            }
        },
        update = { map ->
            map.overlayManager.tilesOverlay.setColorFilter(if (flying) flightMapColorFilter(dark) else mapColorFilter(dark))
            if (flying) { map.invalidate(); return@AndroidView }
            val selectedId = selectedNode?.let { "${it.hash}:${it.lat}:${it.lon}" }
            if (selectedId != null && map.tag != selectedId) {
                map.tag = selectedId
                map.controller.setZoom(12.0)
                map.controller.animateTo(GeoPoint(selectedNode.lat, selectedNode.lon))
            }
            map.overlays.removeAll { it is Polyline || it is Marker }
            edges.filterNot { it.longestRoute }.forEach { edge -> edgePolylines(edge).forEach(map.overlays::add) }
            if (longestRoute.size > 1) map.overlays += Polyline().apply {
                setPoints(longestRoute.map { GeoPoint(it.lat, it.lon) })
                outlinePaint.color = 0xFF2196F3.toInt(); outlinePaint.strokeWidth = 4.2f; outlinePaint.alpha = 255
            }
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
            if (animationStep >= 0 && longestRoute.size > 1) {
                longestRoute.take(animationStep + 1).forEachIndexed { index, point ->
                    map.overlays += Marker(map).apply {
                        position = GeoPoint(point.lat, point.lon); setAnchor(Marker.ANCHOR_CENTER, 1.35f)
                        icon = textBadge(context, if (index == 0) "START · ${point.hash}" else "HOP $index · ${point.hash}")
                    }
                }
                longestRoute.zipWithNext().take(animationStep).forEach { (from, to) ->
                    val km = MapRouteMapper.distanceKm(from.lat, from.lon, to.lat, to.lon)
                    map.overlays += Marker(map).apply {
                        position = GeoPoint((from.lat + to.lat) / 2.0, (from.lon + to.lon) / 2.0)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        icon = textBadge(context, "%.1f km".format(java.util.Locale.US, km), compact = true)
                    }
                }
            }
            map.invalidate()
        },
    )
    if (flying) Surface(Modifier.align(Alignment.TopStart).padding(12.dp).zIndex(50f), color = Color(0xEE15181C),
        shape = RoundedCornerShape(10.dp)) {
        Text("▲ N  %03d°".format(compassBearing.roundToInt()), color = Color.White,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
    }
}

private fun flightZoom(distanceKm: Double): Double = when {
    distanceKm < 1 -> 18.5
    distanceKm < 3 -> 17.5
    distanceKm < 5 -> 16.7
    distanceKm < 8 -> 15.5
    distanceKm < 20 -> 14.0
    distanceKm < 50 -> 12.7
    distanceKm < 100 -> 11.4
    else -> 10.0
}

private fun fitSegmentZoom(from: MapNodePoint, to: MapNodePoint, width: Int, height: Int): Double {
    if (width <= 0 || height <= 0) return flightZoom(MapRouteMapper.distanceKm(from.lat, from.lon, to.lat, to.lon))
    fun mercator(lat: Double): Double {
        val value = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return (1.0 - ln(tan(value) + 1.0 / cos(value)) / Math.PI) / 2.0
    }
    val lonFraction = abs(to.lon - from.lon) / 360.0
    val latFraction = abs(mercator(to.lat) - mercator(from.lat))
    val usableWidth = (width - 260).coerceAtLeast(160).toDouble()
    val usableHeight = (height - 360).coerceAtLeast(240).toDouble()
    val lonZoom = if (lonFraction > 0) log2(usableWidth / 256.0 / lonFraction) else 19.0
    val latZoom = if (latFraction > 0) log2(usableHeight / 256.0 / latFraction) else 19.0
    return minOf(lonZoom, latZoom).coerceIn(4.0, 18.5)
}

private fun routeBearing(from: MapNodePoint, to: MapNodePoint): Float {
    val a = Math.toRadians(from.lat); val b = Math.toRadians(to.lat)
    val dLon = Math.toRadians(to.lon - from.lon)
    return Math.toDegrees(atan2(sin(dLon) * cos(b), cos(a) * sin(b) - sin(a) * cos(b) * cos(dLon))).toFloat()
}

private fun addFlightLabel(map: MapView, point: MapNodePoint, label: String, compact: Boolean = false, anchor: Float? = null) {
    map.overlays += Marker(map).apply {
        position = GeoPoint(point.lat, point.lon); setAnchor(Marker.ANCHOR_CENTER, anchor ?: if (compact) .5f else 1.35f)
        icon = textBadge(map.context, label, compact)
    }
}

private fun edgePolyline(edge: MapEdge) = Polyline().apply {
    setPoints(listOf(GeoPoint(edge.from.lat, edge.from.lon), GeoPoint(edge.to.lat, edge.to.lon)))
    outlinePaint.color = when {
        edge.longestRoute -> 0xFF2196F3.toInt()
        edge.uncertain -> 0xFFF0A84B.toInt()
        else -> 0xFF42D47B.toInt()
    }
    val baseWidth = 2.6f + minOf(edge.count, 8) * .22f
    outlinePaint.strokeWidth = if (!edge.uncertain && !edge.longestRoute) baseWidth * 1.4f else baseWidth
    outlinePaint.alpha = if (!edge.uncertain && !edge.longestRoute) 255 else minOf(175 + edge.count * 8, 255)
    if (edge.uncertain && !edge.longestRoute) outlinePaint.pathEffect = DashPathEffect(floatArrayOf(8f, 5f), 0f)
}

private fun edgePolylines(edge: MapEdge): List<Polyline> {
    val core = edgePolyline(edge)
    if (edge.uncertain || edge.longestRoute) return listOf(core)
    val outline = Polyline().apply {
        setPoints(listOf(GeoPoint(edge.from.lat, edge.from.lon), GeoPoint(edge.to.lat, edge.to.lon)))
        outlinePaint.color = 0xD8191D1B.toInt()
        outlinePaint.strokeWidth = core.outlinePaint.strokeWidth + 4f
        outlinePaint.alpha = 220
    }
    return listOf(outline, core)
}

private fun textBadge(context: android.content.Context, text: String, compact: Boolean = false): BitmapDrawable {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE; textSize = if (compact) 34f else 44f; typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    val padding = if (compact) 14 else 19
    val width = paint.measureText(text).toInt() + padding * 2
    val height = (paint.fontMetrics.bottom - paint.fontMetrics.top).toInt() + padding * 2
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9181B20.toInt() }
    canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 10f, 10f, background)
    canvas.drawText(text, padding.toFloat(), padding - paint.fontMetrics.top, paint)
    return BitmapDrawable(context.resources, bitmap)
}

private fun mapColorFilter(dark: Boolean): ColorMatrixColorFilter {
    val matrix = ColorMatrix().apply { setSaturation(0.20f) }
    val level = if (dark) 0.78f else 0.90f
    val lift = if (dark) 24f else 12f
    matrix.postConcat(ColorMatrix(floatArrayOf(
        level, 0f, 0f, 0f, lift,
        0f, level, 0f, 0f, lift,
        0f, 0f, level, 0f, lift,
        0f, 0f, 0f, 1f, 0f,
    )))
    return ColorMatrixColorFilter(matrix)
}

private fun flightMapColorFilter(dark: Boolean) = mapColorFilter(dark)
