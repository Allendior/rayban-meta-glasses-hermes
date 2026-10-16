package ai.hermes.glasses.voice

/**
 * How Hermes's completed text reaches the wearer.
 *
 * FAST_SYSTEM uses Android TextToSpeech over the active media route. It is not the private
 * on-glasses TTS protocol; Visor owns that experimental path. CLONED preserves the bridge's
 * generated-audio flow.
 */
enum class ReplyMode(
    val storedValue: String,
    val requestClonedAudio: Boolean,
    val useSystemTts: Boolean,
) {
    FAST_SYSTEM("fast_system", requestClonedAudio = false, useSystemTts = true),
    CLONED("cloned", requestClonedAudio = true, useSystemTts = false),
    ;

    companion object {
        fun fromStored(value: String?): ReplyMode =
            entries.firstOrNull { it.storedValue == value } ?: CLONED
    }
}
