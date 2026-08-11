package lt.oranges.orangchat.feature.chat

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

class VoiceMessageRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var output: File? = null

    val isRecording: Boolean get() = recorder != null

    fun start(maxDuration: Duration? = null, onMaxDuration: () -> Unit = {}): Uri {
        check(recorder == null) { "A voice message is already recording" }
        pruneStaleRecordings()
        val directory = recordingsDir().apply { mkdirs() }
        val file = File(directory, "voice-${UUID.randomUUID()}.m4a")
        val next = MediaRecorder(context).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(96_000)
            setAudioSamplingRate(44_100)
            setOutputFile(file)
            if (maxDuration != null) {
                setMaxDuration(maxDuration.inWholeMilliseconds.toInt())
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        onMaxDuration()
                    }
                }
            }
        }
        try {
            next.prepare()
            next.start()
        } catch (error: Throwable) {
            next.release()
            file.delete()
            throw error
        }
        recorder = next
        output = file
        return uriFor(file)
    }

    fun amplitude(): Float {
        val active = recorder ?: return 0f
        val peak = runCatching { active.maxAmplitude }.getOrDefault(0)
        return (peak / MAX_AMPLITUDE).coerceIn(0f, 1f)
    }

    fun stop(): Uri? {
        val active = recorder ?: return null
        val file = output
        recorder = null
        output = null
        return try {
            active.stop()
            active.release()
            if (file != null && file.length() > 0L) {
                uriFor(file)
            } else {
                file?.delete()
                null
            }
        } catch (_: RuntimeException) {
            active.release()
            file?.delete()
            null
        }
    }

    fun cancel() {
        recorder?.let { active ->
            recorder = null
            val file = output
            output = null
            runCatching { active.stop() }
            active.release()
            file?.delete()
        }
    }

    private fun recordingsDir() = File(context.cacheDir, "recordings")

    private fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun pruneStaleRecordings() {
        val cutoff = System.currentTimeMillis() - STALE_AFTER.inWholeMilliseconds
        runCatching {
            recordingsDir().listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoff) file.delete()
            }
        }
    }

    private companion object {
        const val MAX_AMPLITUDE = 32_767f
        val STALE_AFTER: Duration = 24.hours
    }
}
