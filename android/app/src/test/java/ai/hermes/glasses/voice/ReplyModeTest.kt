package ai.hermes.glasses.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyModeTest {
    @Test
    fun fastSystemRequestsTextOnlyThenUsesAndroidTts() {
        assertFalse(ReplyMode.FAST_SYSTEM.requestClonedAudio)
        assertTrue(ReplyMode.FAST_SYSTEM.useSystemTts)
    }

    @Test
    fun clonedModePreservesBridgeAudioFlow() {
        assertTrue(ReplyMode.CLONED.requestClonedAudio)
        assertFalse(ReplyMode.CLONED.useSystemTts)
    }

    @Test
    fun unknownStoredValueFallsBackToClonedVoice() {
        assertEquals(ReplyMode.CLONED, ReplyMode.fromStored("future-value"))
        assertEquals(ReplyMode.CLONED, ReplyMode.fromStored(null))
    }

    @Test
    fun storedFastValueRoundTrips() {
        assertEquals(ReplyMode.FAST_SYSTEM, ReplyMode.fromStored("fast_system"))
    }
}
