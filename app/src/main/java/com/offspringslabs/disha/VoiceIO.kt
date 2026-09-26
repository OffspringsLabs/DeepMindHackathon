package com.offspringslabs.disha

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** Offline-capable speech in (Android on-device recognizer) and speech out (TTS). */
class VoiceIO(private val context: Context) {
    var onPartial: (String) -> Unit = {}
    var onResult: (String) -> Unit = {}
    var onError: (String) -> Unit = {}
    var onListening: (Boolean) -> Unit = {}

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    /** Prefer the on-device recognizer; flips to false if its language pack is missing. */
    private var preferOnDevice = true
    private var lastIntent: Intent? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("en", "IN"))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts?.language = Locale.US
                tts?.setSpeechRate(1.05f)
            }
        }
    }

    val onDevice: Boolean get() = preferOnDevice && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    fun startListening() {
        val r = recognizer ?: (
            if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
            ).also { it.setRecognitionListener(listener); recognizer = it }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        lastIntent = intent
        r.startListening(intent)
    }

    /** Offline pack missing on the on-device recognizer: switch to the default (network) recognizer once and retry. */
    private fun fallbackToDefaultRecognizer(): Boolean {
        if (!preferOnDevice) return false
        preferOnDevice = false
        recognizer?.destroy(); recognizer = null
        onError("Offline speech pack missing, using network recognizer")
        startListening()
        return true
    }

    fun stopListening() { recognizer?.stopListening() }

    fun speak(text: String) {
        if (text.isBlank()) return
        tts?.speak(text.replace("₹", " rupees "), TextToSpeech.QUEUE_FLUSH, null, "disha")
    }

    /** Speaks and suspends until the utterance finishes (or 20 s). */
    suspend fun speakAndWait(text: String) {
        if (text.isBlank()) return
        val done = CompletableDeferred<Unit>()
        val id = "tf-" + System.nanoTime()
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == id) done.complete(Unit) }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { if (utteranceId == id) done.complete(Unit) }
            override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id) done.complete(Unit) }
        })
        tts?.speak(text.replace("₹", " rupees "), TextToSpeech.QUEUE_FLUSH, null, id)
        withTimeoutOrNull(20_000) { done.await() }
    }

    fun stopSpeaking() { tts?.stop() }

    fun release() {
        recognizer?.destroy(); recognizer = null
        tts?.shutdown(); tts = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { onListening(true) }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { onListening(false) }
        override fun onError(error: Int) {
            onListening(false)
            if ((error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) && fallbackToDefaultRecognizer()) return
            onError(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that, tap the mic and try again"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
                        "Offline English pack missing: Settings → System → Languages → Speech → download English (India)"
                    else -> "Speech error $error"
                }
            )
        }
        override fun onResults(results: Bundle?) {
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            onListening(false)
            if (t.isNotBlank()) onResult(t) else onError("Didn't catch that")
        }
        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
