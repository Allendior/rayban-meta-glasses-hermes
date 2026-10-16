package ai.hermes.glasses

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import ai.hermes.glasses.interaction.TapToTalkEffect
import ai.hermes.glasses.interaction.TapToTalkEvent
import ai.hermes.glasses.interaction.TapToTalkState
import ai.hermes.glasses.interaction.TapToTalkStateMachine
import ai.hermes.glasses.net.BridgeEndpoint
import ai.hermes.glasses.net.HermesBridgeClient
import ai.hermes.glasses.storage.SecureSettings
import ai.hermes.glasses.voice.ReplyMode
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission as DatPermission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.inputs.Inputs
import com.meta.wearable.dat.inputs.addInputs
import com.meta.wearable.dat.inputs.removeInputs
import com.meta.wearable.dat.inputs.types.ButtonType
import com.meta.wearable.dat.inputs.types.InputEvent
import com.meta.wearable.dat.inputs.types.InputSource
import com.meta.wearable.dat.inputs.types.InputsConfiguration
import com.meta.wearable.dat.inputs.types.InputsState
import com.meta.wearable.dat.speech.Speech
import com.meta.wearable.dat.speech.addSpeech
import com.meta.wearable.dat.speech.types.SpeechState
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("SetTextI18n")
class MainActivity : ComponentActivity() {
    private lateinit var settings: SecureSettings
    private lateinit var bridgeUrl: EditText
    private lateinit var apiKey: EditText
    private lateinit var language: Spinner
    private lateinit var replyMode: Spinner
    private lateinit var prompt: EditText
    private lateinit var status: TextView
    private lateinit var interactionStatus: TextView
    private lateinit var transcript: TextView
    private lateinit var answer: TextView
    private lateinit var sendButton: Button
    private lateinit var listenButton: Button
    private lateinit var registrationButton: Button
    private lateinit var updateAppButton: Button

    private var datInitialized = false
    private var deviceSession: DeviceSession? = null
    private var speech: Speech? = null
    private var inputs: Inputs? = null
    private var sessionJob: Job? = null
    private var speechJobs = mutableListOf<Job>()
    private var player: MediaPlayer? = null
    private var toneGenerator: ToneGenerator? = null
    private var systemTts: TextToSpeech? = null
    private var systemTtsReady = false
    private var activeFastReply: FastReply? = null

    private data class FastReply(
        val utteranceId: String,
        val client: HermesBridgeClient,
        val text: String,
        val language: String,
    )

    /** Pure idle/listening/busy transitions live here; see ai.hermes.glasses.interaction. */
    private val tapToTalk = TapToTalkStateMachine()

    private val bluetoothPermission = registerForActivityResult(RequestPermission()) { granted ->
        if (granted) initializeDat() else showStatus("Bluetooth permission is required for the glasses")
    }

    private val datMicrophonePermission =
        registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            result.onSuccess { permission ->
                if (permission == PermissionStatus.Granted) startDeviceSession()
                else showStatus("Meta AI microphone permission was not granted")
            }.onFailure { error, _ -> showStatus("Permission error: ${error.description}") }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SecureSettings(this)
        setContentView(buildUi())
        populateSettings()
        wireActions()
        toneGenerator = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull()
        initializeSystemTts()
        updateInteractionUi()
        ensureBluetoothThenInitialize()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        toneGenerator?.release()
        toneGenerator = null
        activeFastReply = null
        systemTts?.stop()
        systemTts?.shutdown()
        systemTts = null
        speechJobs.forEach(Job::cancel)
        sessionJob?.cancel()
        if (isFinishing) {
            deviceSession?.let { session ->
                detachInputs(session)
                session.stop()
            }
        }
        super.onDestroy()
    }

    private fun ensureBluetoothThenInitialize() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            initializeDat()
        } else {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    private fun initializeDat() {
        if (datInitialized) return
        Wearables.initialize(applicationContext).fold(
            onSuccess = {
                datInitialized = true
                showStatus("Meta toolkit ready")
                lifecycleScope.launch {
                    Wearables.registrationState.collect { state ->
                        registrationButton.text = when (state) {
                            RegistrationState.REGISTERED -> "Meta AI connected"
                            RegistrationState.REGISTERING -> "Connecting to Meta AI…"
                            else -> "Connect Meta glasses"
                        }
                    }
                }
            },
            onFailure = { error, _ -> showStatus("Meta toolkit error: ${error.description}") },
        )
    }

    private fun connectGlasses() {
        if (!datInitialized) {
            ensureBluetoothThenInitialize()
            return
        }
        if (Wearables.registrationState.value == RegistrationState.REGISTERED) {
            requestSpeechPermission()
        } else {
            Wearables.startRegistration(this)
        }
    }

    private fun requestSpeechPermission() {
        lifecycleScope.launch {
            Wearables.checkPermissionStatus(DatPermission.MICROPHONE).fold(
                onSuccess = { state ->
                    if (state == PermissionStatus.Granted) startDeviceSession()
                    else datMicrophonePermission.launch(DatPermission.MICROPHONE)
                },
                onFailure = { error, _ -> showStatus("Permission check failed: ${error.description}") },
            )
        }
    }

    private fun startDeviceSession() {
        if (deviceSession != null) {
            return
        }
        Wearables.createSession(AutoDeviceSelector()).fold(
            onSuccess = { created ->
                deviceSession = created
                sessionJob = lifecycleScope.launch {
                    launch {
                        created.errors.collect { error -> handleGlassesError(error.description) }
                    }
                    launch {
                        created.state.collect { state ->
                            showStatus("Glasses session: ${state.name.lowercase()}")
                            when (state) {
                                DeviceSessionState.STARTED -> {
                                    attachSpeech(created)
                                    attachInputs(created)
                                }
                                DeviceSessionState.STOPPED -> {
                                    detachInputs(created)
                                    speech = null
                                    deviceSession = null
                                    dispatch(TapToTalkEvent.Disconnected)
                                }
                                else -> Unit
                            }
                        }
                    }
                }
                // `start()` returns Unit: it reports only that the request left the phone.
                // Everything the glasses have to say about hosting the session arrives
                // afterwards on `created.state`, which the collector above is watching.
                created.start()
            },
            onFailure = { error, _ -> showStatus("Could not create glasses session: ${error.description}") },
        )
    }

    private fun attachSpeech(session: DeviceSession) {
        if (speech != null) return
        session.addSpeech().fold(
            onSuccess = { attached ->
                speech = attached
                speechJobs += lifecycleScope.launch {
                    attached.errors.collect { error ->
                        if (error != null) handleGlassesError(error.description)
                    }
                }
                speechJobs += lifecycleScope.launch {
                    attached.transcriptions.collect { result ->
                        if (result == null) return@collect
                        transcript.text = if (result.isFinal) "You: ${result.text}" else "Hearing: ${result.text}"
                        if (result.isFinal) dispatch(TapToTalkEvent.FinalTranscript(result.text))
                    }
                }
            },
            onFailure = { error, _ -> showStatus("Speech is unavailable: ${error.description}") },
        )
    }

    /**
     * Only ACTION_BUTTON is requested: Allen wants the physical action button as the hands-free
     * trigger, not a temple swipe. Per the DAT 1.0 ButtonType docs the action button surfaces as
     * InputEvent.Button(ButtonType.ACTION); captouch nav/select arrive as Nav/Select and
     * capture-button presses as Capture, so subscribing to this one source keeps the other
     * surfaces out of the voice flow entirely.
     *
     * We deliberately do NOT subscribe to CAPTURE_BUTTON: it is not physically verified whether a
     * capture press also writes a photo to the wearer's camera roll on this model, and we will not
     * risk a hidden capture as a side effect of talking to Hermes.
     *
     * consumeBack = true is kept even though CAPTOUCH is not subscribed. It is cheap insurance:
     * an unconsumed back gesture tears down the entire DeviceSession, and the phone then sees only
     * a session that ended for no nameable reason.
     */
    private fun attachInputs(session: DeviceSession) {
        if (inputs != null) return
        session.addInputs(InputsConfiguration(sources = setOf(InputSource.ACTION_BUTTON), consumeBack = true)).fold(
            onSuccess = { attached ->
                inputs = attached
                var reachedActive = false
                speechJobs += lifecycleScope.launch {
                    attached.state.collect { state ->
                        when (state) {
                            InputsState.ACTIVE -> reachedActive = true
                            // INACTIVE is also the birth state and is replayed to a new
                            // collector; only treat it as a real drop once we know inputs had
                            // actually reached ACTIVE, otherwise we'd tear down a capability
                            // still coming up.
                            InputsState.INACTIVE -> if (reachedActive) {
                                showStatus("Action button is no longer active")
                            }
                            else -> Unit
                        }
                    }
                }
                speechJobs += lifecycleScope.launch {
                    attached.errors.collect { error ->
                        if (error != null) handleGlassesError(error.description)
                    }
                }
                speechJobs += lifecycleScope.launch {
                    attached.events.collect { event ->
                        // The action button surfaces as InputEvent.Button with
                        // ButtonType.ACTION (per the DAT 1.0 ButtonType docs: captouch nav/select
                        // arrive as Nav/Select, and capture-button presses as Capture, NOT as
                        // Button). We accept Select from ACTION_BUTTON too, because some firmware
                        // maps a confirm press onto the semantic select event instead.
                        when {
                            event is InputEvent.Button &&
                                event.button == ButtonType.ACTION -> dispatch(TapToTalkEvent.TriggerPressed)

                            event is InputEvent.Select &&
                                event.source == InputSource.ACTION_BUTTON -> dispatch(TapToTalkEvent.TriggerPressed)

                            else -> showStatus("Glasses input ignored: $event")
                        }
                    }
                }
            },
            onFailure = { error, _ -> showStatus("Action button is unavailable: ${error.description}") },
        )
    }

    private fun detachInputs(session: DeviceSession) {
        inputs = null
        runCatching { session.removeInputs() }
    }

    private fun startSpeechIfStopped() {
        val activeSpeech = speech ?: return
        if (activeSpeech.state.value != SpeechState.STOPPED) return
        activeSpeech.start().onFailure { error, _ -> showStatus("Could not start speech: ${error.description}") }
    }

    /** Applies the effects of a tap-to-talk transition, then refreshes the UI to match. */
    private fun dispatch(event: TapToTalkEvent) {
        applyTapToTalkEffects(tapToTalk.on(event))
        updateInteractionUi()
    }

    private fun applyTapToTalkEffects(effects: List<TapToTalkEffect>) {
        effects.forEach { effect ->
            when (effect) {
                TapToTalkEffect.StartListening -> startSpeechIfStopped()
                TapToTalkEffect.StopListening -> speech?.stop()
                is TapToTalkEffect.Submit -> handleSubmitEffect(effect.text)
                TapToTalkEffect.PlayReadyCue -> playCue(ToneGenerator.TONE_PROP_ACK)
                TapToTalkEffect.PlayCapturedCue -> playCue(ToneGenerator.TONE_PROP_BEEP)
                TapToTalkEffect.PlayCancelCue -> playCue(ToneGenerator.TONE_PROP_NACK)
            }
        }
    }

    private fun playCue(tone: Int) {
        runCatching { toneGenerator?.startTone(tone, 120) }
    }

    private fun updateInteractionUi() {
        val state = tapToTalk.state
        sendButton.isEnabled = state != TapToTalkState.BUSY
        listenButton.text = when (state) {
            TapToTalkState.IDLE -> "Start glasses listening"
            TapToTalkState.LISTENING -> "Listening on glasses (tap again to cancel)"
            TapToTalkState.BUSY -> "Asking Hermes…"
        }
        interactionStatus.text = "Glasses state: ${state.name.lowercase()}"
    }

    private fun handleGlassesError(description: String) {
        showStatus("Glasses error: $description")
        // Best-effort match: this build could not verify the SDK's real error-code type against
        // the reference implementation (sandboxed away from it), so it matches on the error
        // description text rather than a typed ErrorCode constant. Verify this against a real
        // DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED failure and tighten to a typed check if the SDK
        // exposes one.
        if (description.contains("UPDATE_REQUIRED", ignoreCase = true) ||
            description.contains("update required", ignoreCase = true)
        ) {
            updateAppButton.visibility = View.VISIBLE
        }
    }

    private fun handleSubmitEffect(text: String) {
        val question = text.trim()
        if (question.isEmpty()) {
            showStatus("Nothing to ask Hermes")
            dispatch(TapToTalkEvent.ResponseFailed)
            return
        }
        val normalizedUrl = try {
            BridgeEndpoint.normalize(bridgeUrl.text.toString())
        } catch (error: IllegalArgumentException) {
            showStatus(error.message ?: "Invalid bridge URL")
            dispatch(TapToTalkEvent.ResponseFailed)
            return
        }
        val key = apiKey.text.toString().trim()
        if (key.isEmpty()) {
            showStatus("Enter the bridge API key first")
            dispatch(TapToTalkEvent.ResponseFailed)
            return
        }
        saveSettings(normalizedUrl, key)
        val mode = selectedReplyMode()
        val turnLanguage = selectedLanguage()
        showStatus(
            if (mode == ReplyMode.FAST_SYSTEM) "Asking Hermes… fast voice is ready"
            else "Asking Hermes… cloned speech can take several seconds",
        )
        lifecycleScope.launch {
            try {
                val client = HermesBridgeClient(normalizedUrl, key)
                val response = withContext(Dispatchers.IO) {
                    client.turn(
                        question,
                        turnLanguage,
                        settings.sessionId,
                        speak = mode.requestClonedAudio,
                    )
                }
                settings.sessionId = response.sessionId
                answer.text = "Hermes: ${response.text}"
                if (response.audioPath != null) {
                    downloadAndPlay(client, response.audioPath, "Playing Hermes in the cloned voice")
                } else if (mode.useSystemTts) {
                    speakWithSystemVoice(client, response.text, turnLanguage)
                } else {
                    showStatus("Hermes answered without audio")
                    dispatch(TapToTalkEvent.ResponseFinished)
                }
            } catch (error: Exception) {
                showStatus(error.message ?: "Hermes request failed")
                dispatch(TapToTalkEvent.ResponseFailed)
            }
        }
    }

    private fun initializeSystemTts() {
        systemTts = TextToSpeech(applicationContext) { result ->
            systemTtsReady = result == TextToSpeech.SUCCESS
            if (!systemTtsReady) return@TextToSpeech
            systemTts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            systemTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    runOnUiThread {
                        val active = activeFastReply
                        if (active?.utteranceId != utteranceId) return@runOnUiThread
                        activeFastReply = null
                        showStatus("Fast Android voice finished")
                        dispatch(TapToTalkEvent.ResponseFinished)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    runOnUiThread { handleSystemTtsFailure(utteranceId) }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    runOnUiThread { handleSystemTtsFailure(utteranceId) }
                }
            })
        }
    }

    private fun speakWithSystemVoice(client: HermesBridgeClient, text: String, language: String) {
        val reply = FastReply(
            utteranceId = "hermes-fast-${System.currentTimeMillis()}",
            client = client,
            text = text,
            language = language,
        )
        activeFastReply = reply
        val tts = systemTts
        if (!systemTtsReady || tts == null) {
            fallbackToClonedVoice(reply, "Fast Android voice is not ready")
            return
        }
        val locale = when (language) {
            "hi" -> Locale.forLanguageTag("hi-IN")
            "fr" -> Locale.forLanguageTag("fr-FR")
            else -> Locale.forLanguageTag("en-US")
        }
        if (tts.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) {
            fallbackToClonedVoice(reply, "Fast Android voice has no $language language pack")
            return
        }
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, reply.utteranceId)
        if (result == TextToSpeech.ERROR) {
            fallbackToClonedVoice(reply, "Fast Android voice could not start")
        } else {
            showStatus("Speaking with fast Android voice over the active media route")
        }
    }

    private fun handleSystemTtsFailure(utteranceId: String?) {
        val reply = activeFastReply ?: return
        if (reply.utteranceId != utteranceId) return
        fallbackToClonedVoice(reply, "Fast Android voice failed")
    }

    private fun fallbackToClonedVoice(reply: FastReply, reason: String) {
        if (activeFastReply?.utteranceId != reply.utteranceId) return
        activeFastReply = null
        showStatus("$reason; generating cloned voice…")
        lifecycleScope.launch {
            try {
                val audioPath = withContext(Dispatchers.IO) {
                    reply.client.synthesize(reply.text, reply.language)
                }
                downloadAndPlay(reply.client, audioPath, "Playing cloned-voice fallback")
            } catch (error: Exception) {
                showStatus(error.message ?: "Both fast and cloned speech failed")
                dispatch(TapToTalkEvent.ResponseFailed)
            }
        }
    }

    private suspend fun downloadAndPlay(
        client: HermesBridgeClient,
        audioPath: String,
        playingMessage: String,
    ) {
        val file = File(cacheDir, "hermes-reply-${System.currentTimeMillis()}.wav")
        withContext(Dispatchers.IO) { client.downloadAudio(audioPath, file) }
        play(file)
        showStatus(playingMessage)
    }

    private fun testBridge() {
        val normalizedUrl = try {
            BridgeEndpoint.normalize(bridgeUrl.text.toString())
        } catch (error: IllegalArgumentException) {
            showStatus(error.message ?: "Invalid bridge URL")
            return
        }
        lifecycleScope.launch {
            showStatus("Checking bridge…")
            try {
                val result = withContext(Dispatchers.IO) {
                    HermesBridgeClient(normalizedUrl, apiKey.text.toString().trim()).health()
                }
                showStatus(result)
            } catch (error: Exception) {
                showStatus(error.message ?: "Bridge check failed")
            }
        }
    }

    private fun play(file: File) {
        player?.release()
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                it.release()
                if (player === it) player = null
                file.delete()
                dispatch(TapToTalkEvent.ResponseFinished)
            }
            setOnErrorListener { mediaPlayer, _, _ ->
                mediaPlayer.release()
                if (player === mediaPlayer) player = null
                file.delete()
                showStatus("Could not play the returned audio")
                dispatch(TapToTalkEvent.ResponseFailed)
                true
            }
            prepare()
            start()
        }
    }

    private fun selectedLanguage(): String = when (language.selectedItemPosition) {
        1 -> "hi"
        2 -> "fr"
        else -> "en"
    }

    private fun selectedReplyMode(): ReplyMode = when (replyMode.selectedItemPosition) {
        0 -> ReplyMode.FAST_SYSTEM
        else -> ReplyMode.CLONED
    }

    private fun saveSettings(url: String, key: String) {
        settings.bridgeUrl = url
        settings.language = selectedLanguage()
        settings.replyMode = selectedReplyMode()
        settings.saveApiKey(key)
    }

    private fun populateSettings() {
        bridgeUrl.setText(settings.bridgeUrl)
        apiKey.setText(settings.loadApiKey())
        language.setSelection(when (settings.language) { "hi" -> 1; "fr" -> 2; else -> 0 })
        replyMode.setSelection(if (settings.replyMode == ReplyMode.FAST_SYSTEM) 0 else 1)
    }

    private fun wireActions() {
        registrationButton.setOnClickListener { connectGlasses() }
        listenButton.setOnClickListener {
            if (speech == null) connectGlasses() else dispatch(TapToTalkEvent.TriggerPressed)
        }
        sendButton.setOnClickListener { dispatch(TapToTalkEvent.ManualSubmit(prompt.text.toString())) }
        updateAppButton.setOnClickListener { Wearables.openDATGlassesAppUpdate(this) }
        findViewById<Button>(R.id.test_bridge).setOnClickListener { testBridge() }
        findViewById<Button>(R.id.clear_session).setOnClickListener {
            settings.clearSession()
            answer.text = "Conversation cleared."
            showStatus("The next question starts a new Hermes session")
        }
    }

    private fun showStatus(message: String) {
        status.text = message
    }

    private fun buildUi(): View {
        val padding = dp(20)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(0xFFF4F1EA.toInt())
        }

        content.addView(TextView(this).apply {
            text = "Hermes Glasses"
            textSize = 30f
            setTextColor(0xFF17223B.toInt())
        })
        content.addView(label("Private voice assistant over your tailnet"))

        bridgeUrl = field("Bridge URL")
        apiKey = field("Bridge API key").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        content.addView(bridgeUrl)
        content.addView(apiKey)

        language = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("English", "हिन्दी", "Français"),
            )
        }
        content.addView(language, rowParams())

        replyMode = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    "Fast Android voice (active Bluetooth route)",
                    "Cloned voice (slower)",
                ),
            )
        }
        content.addView(replyMode, rowParams())

        content.addView(Button(this).apply { id = R.id.test_bridge; text = "Test Mac bridge" }, rowParams())
        registrationButton = Button(this).apply { text = "Connect Meta glasses" }
        listenButton = Button(this).apply { text = "Start glasses listening" }
        content.addView(registrationButton, rowParams())
        content.addView(listenButton, rowParams())

        updateAppButton = Button(this).apply {
            text = "Update Meta AI app on glasses"
            visibility = View.GONE
        }
        content.addView(updateAppButton, rowParams())

        transcript = label("Glasses transcript will appear here.")
        content.addView(transcript)

        interactionStatus = label("Glasses state: idle").apply { textSize = 14f }
        content.addView(interactionStatus)

        prompt = field("Or type a question")
        content.addView(prompt)
        sendButton = Button(this).apply { text = "Ask Hermes" }
        content.addView(sendButton, rowParams())
        content.addView(Button(this).apply { id = R.id.clear_session; text = "New conversation" }, rowParams())

        answer = label("Hermes's answer will appear here.").apply { textSize = 18f }
        content.addView(answer)
        status = label("Starting…").apply {
            setPadding(0, dp(20), 0, dp(24))
            setTextColor(0xFF275DAD.toInt())
        }
        content.addView(status)

        return ScrollView(this).apply { addView(content) }
    }

    private fun field(hintText: String) = EditText(this).apply {
        hint = hintText
        setSingleLine(true)
        layoutParams = rowParams()
    }

    private fun label(value: String) = TextView(this).apply {
        text = value
        textSize = 16f
        gravity = Gravity.START
        setTextColor(0xFF39445D.toInt())
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun rowParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(10) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
