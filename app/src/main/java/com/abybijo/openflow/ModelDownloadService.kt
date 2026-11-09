package com.abybijo.openflow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Download state of one model, as Settings shows it. */
sealed class ModelDownloadState {
    object NotDownloaded : ModelDownloadState()
    data class Downloading(val fraction: Float) : ModelDownloadState()
    data class Paused(val partialBytes: Long) : ModelDownloadState()
    object Downloaded : ModelDownloadState()
    /** A transient failure (HTTP error, dropped connection): retrying automatically, partial kept. */
    data class Retrying(val attempt: Int, val maxAttempts: Int, val message: String) : ModelDownloadState()
    data class Failed(val message: String) : ModelDownloadState()
}

/**
 * Live download states, keyed by model id. The service writes here;
 * the ViewModel merges it over the on-disk state, so Settings always
 * shows the truth whether a download is running or not.
 */
object ModelDownloadStates {
    private val _flow = MutableStateFlow<Map<String, ModelDownloadState>>(emptyMap())
    val flow: StateFlow<Map<String, ModelDownloadState>> = _flow

    fun update(modelId: String, state: ModelDownloadState) {
        _flow.value = _flow.value + (modelId to state)
    }
}

/**
 * Downloads Whisper models in the foreground, so a 466 MB model keeps
 * downloading while the user does other things. One download at a
 * time. Pause keeps the .tmp file so Resume continues where it left
 * off (Range request); Cancel deletes the .tmp file. The service stops
 * itself when the download finishes, pauses, or is cancelled.
 */
class ModelDownloadService : Service() {

    companion object {
        const val ACTION_DOWNLOAD = "com.abybijo.openflow.DOWNLOAD_MODEL"
        const val ACTION_PAUSE = "com.abybijo.openflow.PAUSE_DOWNLOAD"
        const val ACTION_CANCEL = "com.abybijo.openflow.CANCEL_DOWNLOAD"
        const val EXTRA_MODEL_ID = "model_id"
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "openflow_downloads"

        fun downloadIntent(context: Context, modelId: String): Intent =
            Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra(EXTRA_MODEL_ID, modelId)
            }

        fun pauseIntent(context: Context): Intent =
            Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_PAUSE
            }

        fun cancelIntent(context: Context): Intent =
            Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_CANCEL
            }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null
    private var currentModelId: String? = null
    @Volatile private var stopRequested = false
    @Volatile private var cancelRequested = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DOWNLOAD -> {
                val id = intent.getStringExtra(EXTRA_MODEL_ID) ?: return START_NOT_STICKY
                startDownload(id)
            }
            ACTION_PAUSE -> pauseDownload()
            ACTION_CANCEL -> cancelDownload()
        }
        return START_NOT_STICKY
    }

    private fun startDownload(modelId: String) {
        if (downloadJob?.isActive == true) return // one download at a time
        val model = ModelCatalog.byId(modelId)
        if (ModelDownloader.isDownloaded(this, model)) {
            ModelDownloadStates.update(modelId, ModelDownloadState.Downloaded)
            return
        }
        if (!ModelDownloader.hasEnoughSpace(this, model)) {
            ModelDownloadStates.update(
                modelId,
                ModelDownloadState.Failed(
                    "Not enough storage for ${model.name} (${model.sizeMb} MB). Free some space and try again."
                )
            )
            return
        }
        currentModelId = modelId
        stopRequested = false
        cancelRequested = false
        startForegroundNotification(model)
        ModelDownloadStates.update(modelId, ModelDownloadState.Downloading(0f))
        downloadJob = scope.launch {
            try {
                ModelDownloader.download(
                    this@ModelDownloadService,
                    model,
                    onProgress = { progress ->
                        ModelDownloadStates.update(
                            modelId,
                            ModelDownloadState.Downloading(progress.fraction)
                        )
                        updateNotification(model, progress.fraction)
                    },
                    isCancelled = { stopRequested },
                    onRetry = { attempt, maxAttempts, message ->
                        ModelDownloadStates.update(
                            modelId,
                            ModelDownloadState.Retrying(attempt, maxAttempts, message)
                        )
                        updateNotificationRetrying(model, attempt, maxAttempts)
                    }
                )
                ModelDownloadStates.update(modelId, ModelDownloadState.Downloaded)
                updateNotificationDone(model)
            } catch (e: InterruptedException) {
                if (cancelRequested) {
                    ModelDownloader.deletePartial(this@ModelDownloadService, model)
                    ModelDownloadStates.update(modelId, ModelDownloadState.NotDownloaded)
                } else {
                    // Paused: the .tmp file stays, so Resume continues it.
                    val partial = ModelDownloader.partialSize(this@ModelDownloadService, model)
                    ModelDownloadStates.update(modelId, ModelDownloadState.Paused(partial))
                }
            } catch (e: Exception) {
                ModelDownloadStates.update(
                    modelId,
                    ModelDownloadState.Failed(e.message ?: "Download failed.")
                )
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun pauseDownload() {
        if (downloadJob?.isActive != true) return
        stopRequested = true
        downloadJob?.cancel()
    }

    private fun cancelDownload() {
        if (downloadJob?.isActive != true) return
        stopRequested = true
        cancelRequested = true
        downloadJob?.cancel()
    }

    private fun startForegroundNotification(model: WhisperModel) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        val notification = downloadNotification(model, 0f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(model: WhisperModel, fraction: Float) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, downloadNotification(model, fraction))
    }

    private fun updateNotificationDone(model: WhisperModel) {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("${model.name} downloaded")
            .setContentText("Ready to use on-device in Settings.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(false)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification)
    }

    private fun updateNotificationRetrying(model: WhisperModel, attempt: Int, maxAttempts: Int) {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloading ${model.name}")
            .setContentText("Connection dropped — retrying ($attempt of $maxAttempts)…")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification)
    }

    private fun downloadNotification(model: WhisperModel, fraction: Float): Notification {
        val percent = (fraction * 100).toInt()
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloading ${model.name}")
            .setContentText("$percent% of ${model.sizeMb} MB")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
