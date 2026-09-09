package com.lynko.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log
import org.json.JSONObject

/**
 * Captures audio playing on the phone via MediaProjection playback capture
 * (API 29+), downsamples to 16 kHz mono 16-bit PCM, then streams LF1 chunks:
 *   b"LF1" + u16 LE sample-rate + u16 LE channels + u32 LE sample-count + PCM bytes
 */
class AudioSession(
    private val projection: MediaProjection,
    private val sendBinary: (ByteArray) -> Unit,
) {
    companion object {
        const val TAG = "lynko-audio"
        const val CHUNK_MAGIC_0 = 0x4C // L
        const val CHUNK_MAGIC_1 = 0x46 // F
        const val CHUNK_MAGIC_2 = 0x31 // 1
    }

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile private var capturing = false

    fun start() {
        if (capturing) return
        val rate = 16000
        val chans = AudioFormat.CHANNEL_IN_MONO
        val fmt = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(rate, chans, fmt).coerceAtLeast(4096)

        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(rate)
            .setEncoding(fmt)
            .setChannelMask(chans)
            .build()

        record = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(minBuf * 4)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        val state = record?.state
        if (state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed: $state")
            record?.release()
            record = null
            return
        }

        capturing = true
        record?.startRecording()
        thread = Thread {
            val buf = ShortArray(1600) // 100 ms @ 16 kHz
            while (capturing) {
                val n = record?.read(buf, 0, buf.size) ?: break
                if (n <= 0) continue
                val pcm = ByteArray(n * 2)
                java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer().put(buf, 0, n)
                sendChunk(rate, 1, n, pcm)
            }
            Log.i(TAG, "capture loop ended")
        }.also { it.start() }
        Log.i(TAG, "capture started @ ${rate}Hz mono")
    }

    private fun sendChunk(sampleRate: Int, channels: Int, sampleCount: Int, pcm: ByteArray) {
        val header = ByteArray(3 + 2 + 2 + 4)
        header[0] = CHUNK_MAGIC_0.toByte()
        header[1] = CHUNK_MAGIC_1.toByte()
        header[2] = CHUNK_MAGIC_2.toByte()
        // u16 LE sample rate
        header[3] = (sampleRate and 0xFF).toByte()
        header[4] = ((sampleRate shr 8) and 0xFF).toByte()
        // u16 LE channels
        header[5] = (channels and 0xFF).toByte()
        header[6] = 0
        // u32 LE sample count
        header[7] = (sampleCount and 0xFF).toByte()
        header[8] = ((sampleCount shr 8) and 0xFF).toByte()
        header[9] = ((sampleCount shr 16) and 0xFF).toByte()
        header[10] = ((sampleCount shr 24) and 0xFF).toByte()
        val frame = header + pcm
        sendBinary(frame) // locked funnel in LinkService — never concurrent ws.send
    }

    fun stop() {
        capturing = false
        thread?.interrupt()
        thread = null
        record?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        record = null
        Log.i(TAG, "capture stopped")
    }
}
