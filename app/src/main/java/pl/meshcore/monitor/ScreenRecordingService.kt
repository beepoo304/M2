package pl.meshcore.monitor

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.io.File

class ScreenRecordingService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> stopRecording(true)
            ACTION_CANCEL -> stopRecording(false)
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent) {
        createChannel()
        startForeground(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher).setContentTitle("M² recording flight")
            .setContentText("Screen recording is active").setOngoing(true).build())
        val resultData = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        if (resultData == null) { stopSelf(); return }
        val width = intent.getIntExtra(EXTRA_WIDTH, 1080).coerceAtLeast(2) / 2 * 2
        val height = intent.getIntExtra(EXTRA_HEIGHT, 1920).coerceAtLeast(2) / 2 * 2
        val density = intent.getIntExtra(EXTRA_DENSITY, resources.displayMetrics.densityDpi)
        output = File(cacheDir, "m2-screen-${System.currentTimeMillis()}.mp4")
        val mediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        recorder = mediaRecorder.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(width, height)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(12_000_000)
            setOutputFile(output!!.absolutePath)
            prepare()
        }
        projection = (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
            .getMediaProjection(Activity.RESULT_OK, resultData).also { p ->
                p.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { if (!stopping) stopRecording(false) }
                }, Handler(Looper.getMainLooper()))
            }
        display = projection!!.createVirtualDisplay("M2 flight", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, mediaRecorder.surface, null, null)
        mediaRecorder.start()
        sendBroadcast(Intent(ACTION_STARTED).setPackage(packageName))
    }

    private fun stopRecording(save: Boolean) {
        if (stopping) return
        stopping = true
        val file = output
        runCatching { recorder?.stop() }
        recorder?.reset(); recorder?.release(); recorder = null
        display?.release(); display = null
        projection?.stop(); projection = null
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        if (file != null) {
            if (save && file.exists() && file.length() > 0) sendBroadcast(Intent(ACTION_READY).setPackage(packageName).putExtra(EXTRA_PATH, file.absolutePath))
            else file.delete()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) (getSystemService(NotificationManager::class.java)).createNotificationChannel(
            NotificationChannel(CHANNEL, "M² screen recording", NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        const val ACTION_START="pl.meshcore.monitor.RECORD_START"
        const val ACTION_STOP="pl.meshcore.monitor.RECORD_STOP"
        const val ACTION_CANCEL="pl.meshcore.monitor.RECORD_CANCEL"
        const val ACTION_READY="pl.meshcore.monitor.RECORD_READY"
        const val ACTION_STARTED="pl.meshcore.monitor.RECORD_STARTED"
        const val EXTRA_DATA="data"; const val EXTRA_WIDTH="width"; const val EXTRA_HEIGHT="height"
        const val EXTRA_DENSITY="density"; const val EXTRA_PATH="path"
        private const val CHANNEL="m2_screen_recording"; private const val NOTIFICATION_ID=2402
    }
}
