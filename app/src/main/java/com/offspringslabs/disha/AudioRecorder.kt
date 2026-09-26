package com.offspringslabs.disha

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/** 16 kHz mono PCM16 recorder that returns a WAV byte array, for Gemini Flash audio input. */
class AudioRecorder {
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private val buf = ByteArrayOutputStream()
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 8192))
        buf.reset()
        record = r
        running = true
        r.startRecording()
        worker = thread(name = "disha-rec") {
            val chunk = ByteArray(4096)
            while (running) {
                val n = r.read(chunk, 0, chunk.size)
                if (n > 0) synchronized(buf) { buf.write(chunk, 0, n) }
            }
        }
    }

    fun stop(): ByteArray {
        running = false
        worker?.join(500)
        record?.runCatching { stop(); release() }
        record = null
        val pcm = synchronized(buf) { buf.toByteArray() }
        return wav(pcm)
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        val byteRate = SAMPLE_RATE * 2
        header.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(SAMPLE_RATE).putInt(byteRate).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(pcm.size)
        return header.array() + pcm
    }

    companion object { const val SAMPLE_RATE = 16000 }
}
