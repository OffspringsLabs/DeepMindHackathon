package com.offspringslabs.disha

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Gemini TTS for online mode: one multilingual voice that reads Telugu, Hindi and English natively,
 * with a style prompt so it sounds like a local guide rather than a screen reader. PCM16 played via AudioTrack.
 */
class GeminiTts(private val apiKey: String = BuildConfig.GEMINI_API_KEY) {
    private val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
    @Volatile private var track: AudioTrack? = null

    data class Pcm(val bytes: ByteArray, val sampleRate: Int)

    /** Tries the fast model first, then the stable preview. */
    suspend fun synthesize(text: String, voice: String, style: String): Pcm = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (model in MODELS) {
            try { return@withContext call(model, "$style\n\n$text", voice) } catch (e: Exception) { last = e; Log.w(TAG, "$model failed: ${e.message}") }
        }
        throw last ?: IOException("TTS failed")
    }

    private fun call(model: String, prompt: String, voice: String): Pcm {
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().put("AUDIO"))
                put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))))
            })
        }
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("TTS HTTP ${resp.code}: ${txt.take(200)}")
            val parts = JSONObject(txt).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
            for (i in 0 until parts.length()) {
                val inline = parts.getJSONObject(i).optJSONObject("inlineData") ?: continue
                val mime = inline.optString("mimeType")
                val raw = Base64.decode(inline.getString("data"), Base64.DEFAULT)
                val pcm = if (isWav(raw)) unwrapWav(raw) else Pcm(raw, Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: 24000)
                Log.i(TAG, "tts ok model=$model mime=$mime bytes=${pcm.bytes.size} rate=${pcm.sampleRate}")
                return pcm
            }
            throw IOException("TTS returned no audio")
        }
    }

    private fun isWav(b: ByteArray) = b.size > 44 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte()

    /** Minimal RIFF/WAVE reader: sample rate from the fmt chunk, payload from the data chunk. */
    private fun unwrapWav(b: ByteArray): Pcm {
        fun le32(i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)
        var rate = 24000
        var pos = 12
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            val size = le32(pos + 4)
            if (id == "fmt ") rate = le32(pos + 12)
            if (id == "data") return Pcm(b.copyOfRange(pos + 8, minOf(b.size, pos + 8 + size)), rate)
            pos += 8 + size + (size and 1)
        }
        return Pcm(b.copyOfRange(44, b.size), rate)
    }

    /** Blocking playback on the caller's (IO) thread; returns when done or stopped. */
    suspend fun play(pcm: Pcm) = withContext(Dispatchers.IO) {
        stop()
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(pcm.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.bytes.size.coerceAtLeast(8192))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track = t
        try {
            t.write(pcm.bytes, 0, pcm.bytes.size)
            t.play()
            val durationMs = pcm.bytes.size * 1000L / (pcm.sampleRate * 2)
            val end = System.currentTimeMillis() + durationMs + 150
            while (System.currentTimeMillis() < end && track === t && t.playState == AudioTrack.PLAYSTATE_PLAYING) Thread.sleep(50)
        } finally {
            if (track === t) { runCatching { t.stop() }; t.release(); track = null }
        }
    }

    fun stop() {
        track?.let { runCatching { it.stop() }; runCatching { it.release() } }
        track = null
    }

    companion object {
        private const val TAG = "GeminiTts"
        val MODELS = listOf("gemini-3.8-flash-lite-tts", "gemini-2.5-flash-preview-tts")
        val VOICES = listOf("Kore", "Puck", "Zephyr", "Charon", "Aoede", "Leda")
    }
}

/** Reply language the traveller picked; Auto follows the language they spoke or typed in. */
enum class ReplyLang(val label: String, val instruction: String, val ttsHint: String) {
    AUTO("Auto", "Reply in the same language the traveller used (Telugu, Hindi or English). Keep numbers as digits and place names as they appear in the pack.", "in the language of the text"),
    ENGLISH("English", "Reply in Indian English.", "in Indian English"),
    TELUGU("తెలుగు", "Reply in Telugu (Telugu script). Keep numbers as digits; keep place names recognisable.", "in natural Telugu"),
    HINDI("हिन्दी", "Reply in Hindi (Devanagari). Keep numbers as digits; keep place names recognisable.", "in natural Hindi"),
}
