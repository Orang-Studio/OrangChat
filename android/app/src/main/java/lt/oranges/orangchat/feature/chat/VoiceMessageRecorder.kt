package lt.oranges.orangchat.feature.chat

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/** Small cache-backed recorder used by the message composer, not by LiveKit. */
class VoiceMessageRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var output: File? = null

    fun start(): Uri {
        check(recorder == null) { "A voice message is already recording" }
        val directory = File(context.cacheDir, "recordings").apply { mkdirs() }
        val file = File(directory, "voice-${UUID.randomUUID()}.m4a")
        val next = MediaRecorder(context).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(96_000)
            setAudioSamplingRate(44_100)
            setOutputFile(file)
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
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }

    /** Stops and returns the finished file, or null if the clip was invalid. */
    fun stop(): Uri? {
        val active = recorder ?: return null
        val file = output
        recorder = null
        output = null
        return try {
            active.stop()
            active.release()
            if (file != null && file.length() > 0L) {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
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
}
