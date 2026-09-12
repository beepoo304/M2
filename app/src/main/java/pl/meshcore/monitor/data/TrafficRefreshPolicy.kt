package pl.meshcore.monitor.data

import android.content.Context

object TrafficRefreshPolicy {
    @Volatile var appVisible: Boolean = false
    @Volatile var screenInteractive: Boolean = true
    @Volatile var mapTrackingActive: Boolean = false

    fun liveIntervalMs(): Long = when {
        mapTrackingActive -> TRACKING_INTERVAL_MS
        !screenInteractive -> SCREEN_OFF_INTERVAL_MS
        !appVisible -> BACKGROUND_INTERVAL_MS
        else -> FOREGROUND_INTERVAL_MS
    }

    fun restoreMapTrackingState(context: Context) {
        val prefs = context.getSharedPreferences("map_tracking", Context.MODE_PRIVATE)
        val key = prefs.getString("last_selected_key", "").orEmpty()
        mapTrackingActive = key.isNotBlank() && MapSessionJson.decode(
            prefs.getString("session_${key.lowercase()}", "").orEmpty(), key
        ).running
    }

    const val FOREGROUND_INTERVAL_MS = 3_000L
    const val TRACKING_INTERVAL_MS = 3_000L
    const val BACKGROUND_INTERVAL_MS = 30_000L
    const val SCREEN_OFF_INTERVAL_MS = 60_000L
}
