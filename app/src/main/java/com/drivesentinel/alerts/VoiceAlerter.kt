package com.drivesentinel.alerts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** What to say/play. [urgent] alerts interrupt whatever is currently speaking. */
data class VoiceAlert(
    val text: String?,
    val tone: List<BeepSynth.Tone> = BeepSynth.ROBOT_PROMPT,
    val urgent: Boolean = false,
)

/**
 * Robotic voice prompts: a synthesized beep (AudioTrack, static mode) followed by TTS with a
 * lowered pitch and slightly faster rate. Routed as navigation guidance so it ducks music and
 * goes to car Bluetooth like nav prompts. Transient audio focus is requested per prompt — on
 * Android 15 this only succeeds while the app is on top or running a foreground service,
 * which the DriveService guarantees.
 */
class VoiceAlerter(context: Context) : AutoCloseable {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { }
            .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = Channel<VoiceAlert>(capacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val tracks = ConcurrentHashMap<List<BeepSynth.Tone>, AudioTrack>()
    private val pendingUtterances = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val utteranceIds = AtomicInteger()

    @Volatile var voiceEnabled: Boolean = true

    @Volatile private var ttsReady = false
    private val tts: TextToSpeech = TextToSpeech(appContext) { status ->
        if (status == TextToSpeech.SUCCESS) configureTts() else Log.w(TAG, "TTS init failed: $status")
    }

    init {
        scope.launch {
            for (alert in queue) play(alert)
        }
    }

    fun announce(alert: VoiceAlert) {
        if (alert.urgent) {
            // Interrupt the current prompt; urgent alerts must not wait behind "Slow down".
            while (queue.tryReceive().isSuccess) Unit
            runCatching { tts.stop() }
            pendingUtterances.values.forEach { it.complete(Unit) }
        }
        queue.trySend(alert)
    }

    private fun configureTts() {
        val preferred = listOf(Locale.forLanguageTag("en-IN"), Locale.UK, Locale.US)
        val locale = preferred.firstOrNull { tts.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE } ?: Locale.US
        tts.setLanguage(locale)
        // Prefer an offline voice so prompts work without data.
        runCatching {
            tts.voices
                ?.filter { it.locale.language == "en" && !it.isNetworkConnectionRequired }
                ?.sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale == locale }.thenByDescending { it.quality })
                ?.firstOrNull()
                ?.let { tts.setVoice(it) }
        }
        tts.setPitch(0.6f)      // lower = more "robotic"
        tts.setSpeechRate(1.15f) // slightly faster, prompts stay short
        tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) {
                pendingUtterances.remove(utteranceId)?.complete(Unit)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                pendingUtterances.remove(utteranceId)?.complete(Unit)
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                pendingUtterances.remove(utteranceId)?.complete(Unit)
            }

            override fun onStop(utteranceId: String, interrupted: Boolean) {
                pendingUtterances.remove(utteranceId)?.complete(Unit)
            }
        })
        ttsReady = true
    }

    private suspend fun play(alert: VoiceAlert) {
        val focused = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!focused) Log.w(TAG, "Audio focus not granted; playing anyway")
        try {
            if (alert.tone.isNotEmpty()) {
                val track = tracks.getOrPut(alert.tone) { buildTrack(alert.tone) }
                runCatching {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
                    track.reloadStaticData()
                    track.play()
                }
                delay(alert.tone.sumOf { it.durationMs }.toLong() + 40)
            }
            val text = alert.text
            if (text != null && voiceEnabled && ttsReady) {
                val id = "ds-${utteranceIds.incrementAndGet()}"
                val done = CompletableDeferred<Unit>()
                pendingUtterances[id] = done
                val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
                withTimeoutOrNull(5_000) { done.await() }
                pendingUtterances.remove(id)
            }
        } finally {
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
    }

    private fun buildTrack(tones: List<BeepSynth.Tone>): AudioTrack {
        val pcm = BeepSynth.render(tones)
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(BeepSynth.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        track.write(pcm, 0, pcm.size)
        return track
    }

    override fun close() {
        scope.cancel()
        queue.close()
        runCatching { tts.stop(); tts.shutdown() }
        tracks.values.forEach { runCatching { it.release() } }
        tracks.clear()
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    private companion object {
        const val TAG = "VoiceAlerter"
    }
}
