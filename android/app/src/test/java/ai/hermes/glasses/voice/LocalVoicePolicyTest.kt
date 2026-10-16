package ai.hermes.glasses.voice

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalVoicePolicyTest {
    private fun voice(
        name: String,
        tag: String = "en-US",
        network: Boolean = false,
        installed: Boolean = true,
        latency: Int = 100,
        quality: Int = 300,
    ) = LocalVoiceOption(name, Locale.forLanguageTag(tag), network, installed, latency, quality)

    @Test
    fun networkAndUninstalledVoicesCannotEnterFastPath() {
        val options = listOf(
            voice("network", network = true, latency = 1),
            voice("missing", installed = false, latency = 1),
            voice("local"),
        )
        assertEquals("local", selectLocalVoice(options, Locale.US)?.name)
    }

    @Test
    fun requestedAccentWinsBeforeLatency() {
        val options = listOf(voice("british", "en-GB", latency = 1), voice("american"))
        assertEquals("american", selectLocalVoice(options, Locale.US)?.name)
    }

    @Test
    fun fastestLocalVoiceWinsAndQualityBreaksTies() {
        val options = listOf(
            voice("slow", latency = 500, quality = 500),
            voice("fast", latency = 100, quality = 300),
            voice("fast-clear", latency = 100, quality = 400),
        )
        assertEquals("fast-clear", selectLocalVoice(options, Locale.US)?.name)
    }

    @Test
    fun anotherAccentIsAllowedButAnotherLanguageIsNot() {
        val options = listOf(voice("english"), voice("french", "fr-CA"))
        assertEquals("french", selectLocalVoice(options, Locale.FRANCE)?.name)
        assertNull(selectLocalVoice(options, Locale.forLanguageTag("hi-IN")))
    }

    @Test
    fun missingLocalLanguageTriggersFallbackEvenIfNetworkVoiceExists() {
        assertNull(selectLocalVoice(listOf(voice("cloud", "hi-IN", network = true)), Locale.forLanguageTag("hi-IN")))
        assertNull(selectLocalVoice(emptyList(), Locale.US))
    }
}
