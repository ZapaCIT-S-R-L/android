package ro.zapakid.chat.ui

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import java.io.File

/** Voice notes: AAC in an M4A file in the app's no-backup cache; the caller encrypts and deletes it. */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    fun start() {
        val out = File.createTempFile("voice", ".m4a", context.noBackupFilesDir).also { file = it }
        recorder =
            newRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(BITRATE)
                setAudioSamplingRate(SAMPLE_RATE)
                setMaxDuration(MAX_MILLIS)
                setOutputFile(out)
                prepare()
                start()
            }
        startedAt = System.currentTimeMillis()
    }

    @Suppress("DEPRECATION") // the no-argument constructor is the only one before Android 12
    private fun newRecorder(): MediaRecorder =
        if (android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.S
        ) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }

    /** Stops; returns the recording and its length, or null when it was cancelled or too short. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount")
    fun stop(keep: Boolean): Pair<ByteArray, Long>? {
        val duration = System.currentTimeMillis() - startedAt
        val out = file
        try {
            recorder?.stop()
        } catch (e: RuntimeException) {
            // stop() right after start() throws: nothing was recorded.
            out?.delete()
            return null
        } finally {
            recorder?.release()
            recorder = null
            file = null
        }
        if (out == null) return null
        val bytes = if (keep && duration >= MIN_MILLIS) out.readBytes() else null
        out.delete()
        return bytes?.let { it to duration }
    }

    /** Plays a decrypted voice note. */
    fun play(bytes: ByteArray) = play(context, bytes)

    companion object {
        private const val BITRATE = 32_000
        private const val SAMPLE_RATE = 16_000
        private const val MAX_MILLIS = 120_000
        private const val MIN_MILLIS = 600L

        /** Plays a decrypted voice note from a temporary file, deleted after playback. */
        fun play(context: Context, bytes: ByteArray) {
            val tmp = File.createTempFile("play", ".m4a", context.noBackupFilesDir).apply { writeBytes(bytes) }
            MediaPlayer().apply {
                setDataSource(tmp.absolutePath)
                setOnCompletionListener {
                    it.release()
                    tmp.delete()
                }
                prepare()
                start()
            }
        }
    }
}
