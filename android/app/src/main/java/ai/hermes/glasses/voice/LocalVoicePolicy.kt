package ai.hermes.glasses.voice

import java.util.Locale

/** Engine metadata only: no Android types, audio routing, or network calls. */
data class LocalVoiceOption(
    val name: String,
    val locale: Locale,
    val requiresNetwork: Boolean,
    val installed: Boolean,
    val latency: Int,
    val quality: Int,
)

/** Prefer the requested accent, then low engine-reported latency, using installed local voices. */
fun selectLocalVoice(voices: List<LocalVoiceOption>, locale: Locale): LocalVoiceOption? =
    voices.asSequence()
        .filter { it.installed && !it.requiresNetwork && it.locale.language == locale.language }
        .sortedWith(
            compareByDescending<LocalVoiceOption> { it.locale.country == locale.country }
                .thenBy { it.latency }
                .thenByDescending { it.quality }
                .thenBy { it.name },
        )
        .firstOrNull()
