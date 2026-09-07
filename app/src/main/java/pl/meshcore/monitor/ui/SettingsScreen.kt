package pl.meshcore.monitor.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import pl.meshcore.monitor.data.ConnectionConfig
import pl.meshcore.monitor.data.ConnectionConfigBus

@Composable internal fun SettingsScreen(modifier: Modifier, vm: SettingsViewModel, onCloseApp: () -> Unit) {
    val context = LocalContext.current
    val devices by vm.devices.collectAsState(); val active by ConnectionConfigBus.config.collectAsState()
    var url by rememberSaveable { mutableStateOf(vm.initialConfig.coreScopeBaseUrl) }
    var key by rememberSaveable { mutableStateOf("") }; var error by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Text("SETTINGS", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 16.dp, bottom = 12.dp))
            Text("LIVE API", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text("Active: ${active.coreScopeBaseUrl}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(url, { url = it }, label = { Text("API address") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            Text("M2 reads the public Live API. MQTT credentials are not required.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button({ vm.save(ConnectionConfig(url.trim(), devices.map { it.publicKey }.toSet())) },
                Modifier.padding(vertical = 8.dp)) { Text("Save connection") }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("TRACKED DEVICE KEYS", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            OutlinedTextField(key, { key = it; error = false }, label = { Text("64-character public key") },
                isError = error, singleLine = true,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton({ show = !show }) { Text(if (show) "Hide" else "Show") } },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Button({ if (vm.addDevice(key)) key = "" else error = true }, Modifier.padding(vertical = 8.dp)) { Text("Add device") }
        }
        items(devices, key = { it.publicKey }) { device ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.weight(1f)) { Text(device.name, fontWeight = FontWeight.Medium)
                    Text("${device.publicKey.take(8)}…${device.publicKey.takeLast(6)}", fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton({ vm.removeDevice(device.publicKey) }) { Icon(Icons.Outlined.Delete, "Remove device") }
            }; HorizontalDivider()
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
