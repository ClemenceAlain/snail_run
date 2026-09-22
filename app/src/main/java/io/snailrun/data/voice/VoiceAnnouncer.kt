package io.snailrun.data.voice

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import io.snailrun.domain.voice.Announcement
import io.snailrun.domain.voice.PaceSpeechFormatter
import io.snailrun.domain.coach.StrengthCue
import io.snailrun.domain.coach.WorkoutCue
import io.snailrun.domain.voice.RunNotice
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface TtsState {
    data object Initialising : TtsState

    data class Ready(
        val engineLabel: String,
        val voiceName: String?,
        /** True when the engine package does not hold INTERNET: an OS-enforced fact. */
        val engineHasNoNetworkAccess: Boolean,
    ) : TtsState

    sealed interface Unavailable : TtsState {
        /** A de-Googled build may genuinely ship with no speech engine at all. */
        data object NoEngineInstalled : Unavailable
        data object InitFailed : Unavailable
        data class LanguageMissing(val locale: Locale) : Unavailable
        data class LanguageUnsupported(val locale: Locale) : Unavailable
        /** Every voice for this language would synthesise server-side. */
        data class OnlyNetworkVoices(val locale: Locale) : Unavailable
    }
}

interface VoiceAnnouncer {
    val state: StateFlow<TtsState>
    fun speak(announcement: Announcement)
    fun speak(notice: RunNotice)
    fun speak(cue: WorkoutCue)
    fun speak(cue: StrengthCue)
    fun speakSample()
    fun setSpeechRate(rate: Float)
    fun shutdown()
}

/**
 * Speaks the pace.
 *
 * Two constraints shape this class. Synthesis happens in the engine's process, so this
 * app's missing INTERNET permission does not bind it — the strongest available check is
 * whether the *engine* holds the permission, which the OS does enforce. And a LineageOS
 * build without Google apps may have no engine at all, so every failure has to degrade
 * to silence without ever disturbing the recording.
 */
class AndroidVoiceAnnouncer(
    private val context: Context,
    private val formatter: PaceSpeechFormatter,
    /**
     * English, not the phone's language. See [SPEECH_LOCALE]: the words being read are
     * English, so the voice reading them has to be too.
     */
    private val locale: Locale = SPEECH_LOCALE,
) : VoiceAnnouncer {

    private val _state = MutableStateFlow<TtsState>(TtsState.Initialising)
    override val state: StateFlow<TtsState> = _state.asStateFlow()

    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val attributes = AudioAttributes.Builder()
        // Routes to the active media output, so a Bluetooth headset hears it, and ducks
        // music rather than stopping it. The usage every navigation app picks.
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { }
            .build()

    private var tts: TextToSpeech? = null

    init {
        start()
    }

    private fun start() {
        val probe = try {
            TextToSpeech(context) { }
        } catch (error: Exception) {
            _state.value = TtsState.Unavailable.InitFailed
            null
        }
        // getEngines() needs the TTS_SERVICE <queries> entry in the manifest, or it is
        // empty on Android 11 and up regardless of what is installed.
        val engines = probe?.engines.orEmpty()
        probe?.shutdown()
        if (engines.isEmpty()) {
            _state.value = TtsState.Unavailable.NoEngineInstalled
            return
        }

        tts = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) {
                _state.value = TtsState.Unavailable.InitFailed
                return@TextToSpeech
            }
            configure()
        }
    }

    private fun configure() {
        val engine = tts ?: return
        engine.setAudioAttributes(attributes)

        when (engine.setLanguage(locale)) {
            TextToSpeech.LANG_MISSING_DATA -> {
                _state.value = TtsState.Unavailable.LanguageMissing(locale)
                return
            }
            TextToSpeech.LANG_NOT_SUPPORTED -> {
                _state.value = TtsState.Unavailable.LanguageUnsupported(locale)
                return
            }
        }

        val offline = pickOfflineVoice(engine)
        if (offline == null) {
            _state.value = TtsState.Unavailable.OnlyNetworkVoices(locale)
            return
        }
        engine.voice = offline

        val enginePackage = engine.defaultEngine
        _state.value = TtsState.Ready(
            engineLabel = enginePackage ?: "",
            voiceName = offline.name,
            engineHasNoNetworkAccess = enginePackage?.let { !holdsInternetPermission(it) } ?: false,
        )
    }

    /**
     * Prefers a voice the engine says works offline.
     *
     * `isNetworkConnectionRequired` is self-reported and the framework does not verify
     * it, which is why the engine's own INTERNET permission is reported alongside: that
     * one the OS does enforce.
     */
    private fun pickOfflineVoice(engine: TextToSpeech): Voice? =
        runCatching {
            engine.voices
                ?.asSequence()
                ?.filter { it.locale.language == locale.language }
                ?.filterNot { it.isNetworkConnectionRequired }
                ?.filterNot { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features }
                ?.sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.latency })
                ?.firstOrNull()
        }.getOrNull()

    private fun holdsInternetPermission(packageName: String): Boolean = runCatching {
        val info = context.packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        info.requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true
    }.getOrDefault(true)

    override fun speak(announcement: Announcement) {
        say(formatter.format(announcement))
    }

    override fun speak(cue: WorkoutCue) {
        say(formatter.format(cue))
    }

    override fun speak(cue: StrengthCue) {
        say(formatter.format(cue))
    }

    override fun speak(notice: RunNotice) {
        say(formatter.format(notice))
    }

    override fun speakSample() {
        say(formatter.format(SAMPLE))
    }

    override fun setSpeechRate(rate: Float) {
        runCatching { tts?.setSpeechRate(rate) }
    }

    private fun say(text: String) {
        val engine = tts ?: return
        if (_state.value !is TtsState.Ready) return

        // A phone call holds exclusive focus. Skip the announcement rather than queue
        // it: by the time the call ends the number would be wrong anyway.
        if (audioManager?.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            return
        }

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = abandonFocus()
            @Deprecated("Required by the base class", ReplaceWith(""))
            override fun onError(utteranceId: String?) = abandonFocus()
            override fun onError(utteranceId: String?, errorCode: Int) = abandonFocus()
            override fun onStop(utteranceId: String?, interrupted: Boolean) = abandonFocus()
        })

        val params = Bundle().apply {
            // Deprecated in favour of setVoice, which is applied above, but still
            // honoured: a second signal to the engine to stay off the network.
            putBoolean(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS, true)
        }
        // One announcement, one utterance: several queued utterances would mean several
        // ducking cycles and audible thrash.
        runCatching {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID)
        }.onFailure { abandonFocus() }
    }

    private fun abandonFocus() {
        runCatching { audioManager?.abandonAudioFocusRequest(focusRequest) }
    }

    override fun shutdown() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        abandonFocus()
    }

    private companion object {
        const val UTTERANCE_ID = "snail-run-announcement"

        val SAMPLE = Announcement(
            milestone = io.snailrun.domain.voice.Milestone.Distance(1000.0),
            activeDurationMs = 312_000,
            distanceMeters = 1000.0,
            averagePaceSecPerKm = 312.0,
            lastSplitPaceSecPerKm = null,
        )
    }
}
