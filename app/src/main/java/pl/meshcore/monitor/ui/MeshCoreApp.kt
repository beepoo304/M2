package pl.meshcore.monitor.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel

private data class AppTab(val label: String, val icon: ImageVector)
private val appTabs = listOf(
    AppTab("Live", Icons.AutoMirrored.Outlined.List), AppTab("My log", Icons.Outlined.Key),
    AppTab("Channels", Icons.Outlined.CellTower), AppTab("Settings", Icons.Outlined.Settings),
)

@Composable fun MeshCoreApp(onCloseApp: () -> Unit) {
    var selected by remember { mutableIntStateOf(0) }
    val settings: SettingsViewModel = viewModel()
    val devices by settings.devices.collectAsState()
    val names = devices.map { it.name.trim().lowercase() }.filterNot { it.startsWith("looking up") }.toSet()
    Scaffold(bottomBar = { NavigationBar { appTabs.forEachIndexed { index, tab ->
        NavigationBarItem(selected == index, { selected = index }, { Icon(tab.icon, tab.label) }, label = { Text(tab.label) })
    } } }) { padding -> when (selected) {
        0 -> LiveLogScreen(Modifier.padding(padding)); 1 -> OwnTrafficLogScreen(Modifier.padding(padding))
        2 -> ChannelsScreen(Modifier.padding(padding), names); else -> SettingsScreen(Modifier.padding(padding), settings, onCloseApp)
    } }
}
