package com.zevclip.sender

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import com.zevclip.sender.airplay.AirPlayIdentityStore
import com.zevclip.sender.airplay.AirPlayMirrorSessionController
import com.zevclip.sender.airplay.AirPlayMirrorStreamClient
import com.zevclip.sender.airplay.AirPlayScreenEncoder
import com.zevclip.sender.airplay.AirPlayTarget
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AirPlayScreenMirrorService : Service() {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var encoderWorker: Thread? = null
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var encoder: AirPlayScreenEncoder? = null
    private var mirrorClient: AirPlayMirrorStreamClient? = null
    private var mirrorSession: AirPlayMirrorSessionController? = null
    @Volatile
    private var mirrorFailure: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMirror()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> startMirror(intent)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopMirror()
        super.onDestroy()
    }

    private fun startMirror(intent: Intent) {
        if (running.get()) return
        cleanupLegacyNotification()
        ZevClipStatusNotification.ensureChannel(this)
        startForeground(
            ZevClipStatusNotification.NOTIFICATION_ID,
            ZevClipStatusNotification.build(this)
        )

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode == 0 || resultData == null) {
            finishWithStatus(getString(R.string.airplay_capture_permission_missing))
            return
        }

        val endpoint = ZevClipPreferences.endpoint(this)
        val pairingCode = intent.getStringExtra(EXTRA_SCREEN_CODE).orEmpty().trim()
        if (endpoint == null || pairingCode.isBlank()) {
            finishWithStatus(getString(R.string.airplay_screen_code_missing))
            return
        }

        running.set(true)
        mirrorFailure = null
        ZevClipPreferences.setAirPlayScreenMirroring(this, true)
        updateStatus(getString(R.string.airplay_screen_mirror_connecting))

        worker = thread(name = "zevclip-airplay-screen", isDaemon = true) {
            runCatching {
                val projectionManager = getSystemService(MediaProjectionManager::class.java)
                val projection = projectionManager.getMediaProjection(resultCode, resultData)
                    ?: error(getString(R.string.airplay_capture_permission_missing))
                mediaProjection = projection
                projectionCallback = object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopMirror()
                    }
                }.also { callback ->
                    projection.registerCallback(callback, Handler(Looper.getMainLooper()))
                }

                val identity = AirPlayIdentityStore.getOrCreate(this)
                val target = AirPlayTarget(
                    host = endpoint.ipAddress,
                    port = AirPlayTarget.DEFAULT_RTSP_PORT,
                    name = "Paired Mac AirPlay"
                )

                val (width, height, dpi) = captureSize()
                val nextMirrorSession = AirPlayMirrorSessionController(target, identity, pairingCode)
                val preparedMirror = nextMirrorSession.prepare()
                mirrorSession = nextMirrorSession

                val nextMirrorClient = AirPlayMirrorStreamClient(
                    target = target,
                    identity = identity,
                    width = width,
                    height = height,
                    running = running,
                    streamPort = preparedMirror.dataPort,
                    dataStreamKey = preparedMirror.dataStreamKey
                ).also { it.connect() }
                mirrorClient = nextMirrorClient
                nextMirrorSession.recordBestEffort()

                updateStatus(getString(R.string.airplay_screen_mirror_preparing))
                val screenEncoder = AirPlayScreenEncoder(width, height, dpi, nextMirrorClient, running)
                encoder = screenEncoder
                encoderWorker = thread(name = "zevclip-airplay-screen-encoder", isDaemon = true) {
                    runCatching { screenEncoder.start(projection) }
                        .onFailure { error ->
                            if (running.get()) {
                                val message = error.message ?: error.javaClass.simpleName
                                mirrorFailure = message
                                Log.w(TAG, "AirPlay screen encoder failed", error)
                                running.set(false)
                                updateStatus(getString(R.string.airplay_screen_mirror_failed, message))
                            }
                        }
                }

                updateStatus(getString(R.string.airplay_screen_mirror_live))
                while (running.get()) {
                    Thread.sleep(1_000)
                }
            }.onSuccess {
                mirrorFailure?.let { failure ->
                    updateStatus(getString(R.string.airplay_screen_mirror_failed, failure))
                } ?: updateStatus(getString(R.string.airplay_screen_mirror_stopped))
            }.onFailure { error ->
                val message = error.message ?: error.javaClass.simpleName
                mirrorFailure = message
                Log.w(TAG, "AirPlay screen mirror failed", error)
                updateStatus(getString(R.string.airplay_screen_mirror_failed, message))
            }

            stopMirror()
            stopSelf()
        }
    }

    private fun captureSize(): Triple<Int, Int, Int> {
        val metrics = resources.displayMetrics
        val width = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.width()
        } else {
            @Suppress("DEPRECATION")
            metrics.widthPixels
        }
        val height = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.height()
        } else {
            @Suppress("DEPRECATION")
            metrics.heightPixels
        }
        val (scaledWidth, scaledHeight) = AirPlayScreenEncoder.scaledSize(width, height)
        return Triple(scaledWidth, scaledHeight, metrics.densityDpi)
    }

    private fun stopMirror() {
        if (!running.getAndSet(false)) {
            cleanupMirrorState()
            return
        }
        cleanupMirrorState()
        ZevClipPreferences.setAirPlayScreenMirroring(this, false)
        stopForegroundKeepingStatus()
    }

    private fun cleanupMirrorState() {
        runCatching { mirrorClient?.close() }
        mirrorClient = null
        runCatching { mirrorSession?.close() }
        mirrorSession = null
        runCatching { encoder?.close() }
        encoder = null
        projectionCallback?.let { callback ->
            runCatching { mediaProjection?.unregisterCallback(callback) }
        }
        projectionCallback = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        ZevClipPreferences.setAirPlayScreenMirroring(this, false)
    }

    private fun finishWithStatus(status: String) {
        updateStatus(status)
        ZevClipPreferences.setAirPlayScreenMirroring(this, false)
        stopForegroundKeepingStatus()
        stopSelf()
    }

    private fun updateStatus(status: String) {
        ZevClipPreferences.setAirPlayTestStatus(this, status)
        ZevClipStatusNotification.update(this)
    }

    private fun stopForegroundKeepingStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
        ZevClipStatusNotification.update(this)
    }

    private fun cleanupLegacyNotification() {
        getSystemService(NotificationManager::class.java).cancel(LEGACY_AIRPLAY_NOTIFICATION_ID)
    }

    companion object {
        private const val ACTION_START = "com.zevclip.sender.airplay.screen.START"
        private const val ACTION_STOP = "com.zevclip.sender.airplay.screen.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_SCREEN_CODE = "screen_code"
        private const val LEGACY_AIRPLAY_NOTIFICATION_ID = 2042
        private const val TAG = "ZevClipAirPlayScreen"

        fun start(context: Context, resultCode: Int, resultData: Intent, screenCode: String) {
            val intent = Intent(context, AirPlayScreenMirrorService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
                .putExtra(EXTRA_SCREEN_CODE, screenCode.trim())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(stopIntent(context))
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, AirPlayScreenMirrorService::class.java).setAction(ACTION_STOP)
        }
    }
}
