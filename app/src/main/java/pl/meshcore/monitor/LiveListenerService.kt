package pl.meshcore.monitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import pl.meshcore.monitor.data.ConnectionConfig
import pl.meshcore.monitor.data.ConnectionConfigBus
import pl.meshcore.monitor.data.DEFAULT_OWN_PUBLIC_KEYS
import pl.meshcore.monitor.data.SharedLiveRepository
import pl.meshcore.monitor.data.SecureChannelStore
import pl.meshcore.monitor.data.ChannelMessage
import pl.meshcore.monitor.data.ChannelStatisticsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

class LiveListenerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cachedPacketIds = LinkedHashSet<String>()
    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("connection_settings", MODE_PRIVATE)
        val keys = prefs.getStringSet("own_public_keys", DEFAULT_OWN_PUBLIC_KEYS) ?: DEFAULT_OWN_PUBLIC_KEYS
        ConnectionConfigBus.update(ConnectionConfig(
            coreScopeBaseUrl = prefs.getString("core_url", "https://live.meshcorekk.xyz")!!,
            ownPublicKeys = keys,
            ownNodeNames = keys.mapNotNull { prefs.getString("device_name_$it", null) }.map { it.trim().lowercase() }.toSet(),
            savedChannels = SecureChannelStore(this).load(),
        ))
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "M2 live listener", NotificationManager.IMPORTANCE_LOW))
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val largeLogo = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888).also { bitmap ->
            ContextCompat.getDrawable(this, R.drawable.m2_logo)?.apply {
                setBounds(0, 0, bitmap.width, bitmap.height)
                draw(Canvas(bitmap))
            }
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(largeLogo)
            .setContentTitle("M2 is listening")
            .setContentText("Live packets are being updated in the background")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        SharedLiveRepository.start()
        ChannelStatisticsEngine.start(this)
        val channelStore = SecureChannelStore(this)
        scope.launch {
            SharedLiveRepository.state.collect { state ->
                val channels = channelStore.load()
                val pending = linkedMapOf<pl.meshcore.monitor.data.SavedChannel, MutableList<ChannelMessage>>()
                state.packets.filter { it.payloadType == 5 && it.decodedJson.isNotBlank() && it.id !in cachedPacketIds }.forEach { packet ->
                    val decoded = runCatching { JSONObject(packet.decodedJson) }.getOrNull() ?: return@forEach
                    val decodedName = decoded.optString("channel")
                    val exact = decodedName.takeIf(String::isNotBlank)?.let { name ->
                        channels.firstOrNull { it.name.equals(name, true) }
                    }
                    val channel = exact ?: channels.filter {
                        it.hash.equals(decoded.optString("channelHashHex"), true)
                    }.singleOrNull() ?: return@forEach
                    val sender = decoded.optString("sender").ifBlank { "Anonymous" }
                    val fullText = decoded.optString("text")
                    // Never persist an encrypted/hash-only envelope as an empty
                    // Anonymous message in a saved channel.
                    if (channel.secret.isNotBlank() &&
                        (sender == "Anonymous" || fullText.isBlank())) return@forEach
                    val text = fullText.removePrefix("$sender: ")
                    pending.getOrPut(channel) { mutableListOf() }.add(ChannelMessage(
                        id = packet.id, sender = sender, text = text, timestamp = packet.timestamp,
                        hops = packet.path.size, observers = listOf(packet.observerName),
                        repeats = packet.observationCount, snr = packet.snr, packetHash = packet.hash,
                    ))
                    cachedPacketIds += packet.id
                }
                pending.forEach(channelStore::mergeMessages)
                while (cachedPacketIds.size > SEEN_PACKET_LIMIT) cachedPacketIds.remove(cachedPacketIds.first())
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private companion object {
        const val CHANNEL_ID = "m2_live_listener"
        const val NOTIFICATION_ID = 2202
        const val SEEN_PACKET_LIMIT = 500
    }
}
