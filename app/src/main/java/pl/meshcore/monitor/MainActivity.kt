package pl.meshcore.monitor

import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import pl.meshcore.monitor.data.NetworkModule
import pl.meshcore.monitor.data.ConnectionConfig
import pl.meshcore.monitor.data.ConnectionConfigBus
import pl.meshcore.monitor.data.DEFAULT_OWN_PUBLIC_KEYS
import pl.meshcore.monitor.data.SharedLiveRepository
import pl.meshcore.monitor.data.SecureChannelStore
import pl.meshcore.monitor.data.ChannelStatisticsEngine
import pl.meshcore.monitor.data.TrafficRefreshPolicy
import pl.meshcore.monitor.data.AppPacketStatisticsEngine
import pl.meshcore.monitor.ui.MeshCoreApp
import pl.meshcore.monitor.ui.theme.MeshCoreTheme

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        TrafficRefreshPolicy.appVisible = true
        lifecycleScope.launch { SharedLiveRepository.refresh() }
    }

    override fun onStop() {
        TrafficRefreshPolicy.appVisible = false
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("connection_settings", MODE_PRIVATE)
        val keys = prefs.getStringSet("own_public_keys", DEFAULT_OWN_PUBLIC_KEYS) ?: DEFAULT_OWN_PUBLIC_KEYS
        ConnectionConfigBus.update(ConnectionConfig(
            coreScopeBaseUrl = prefs.getString("core_url", "https://live.meshcorekk.xyz")!!,
            ownPublicKeys = keys,
            ownNodeNames = keys.mapNotNull { prefs.getString("device_name_$it", null) }.map { it.trim().lowercase() }.toSet(),
            savedChannels = SecureChannelStore(this).load(),
        ))
        SharedLiveRepository.start()
        ChannelStatisticsEngine.start(this)
        AppPacketStatisticsEngine.start(this)
        ContextCompat.startForegroundService(this, Intent(this, LiveListenerService::class.java))
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        setContent {
            MeshCoreTheme {
                MeshCoreApp(onCloseApp = ::closeApp)
            }
        }
    }

    private fun closeApp() {
        startService(Intent(this, LiveListenerService::class.java).setAction(LiveListenerService.ACTION_CLOSE_APP))
        stopService(Intent(this, ScreenRecordingService::class.java))
        finishAndRemoveTask()
    }
}
