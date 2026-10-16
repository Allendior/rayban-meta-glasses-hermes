package ai.hermes.glasses.interaction

import org.junit.Assert.assertEquals
import org.junit.Test

class TapToTalkStateMachineTest {
    @Test
    fun startsIdle() {
        val machine = TapToTalkStateMachine()
        assertEquals(TapToTalkState.IDLE, machine.state)
    }

    @Test
    fun tapWhileIdleStartsListening() {
        val machine = TapToTalkStateMachine()
        val effects = machine.on(TapToTalkEvent.TriggerPressed)
        assertEquals(TapToTalkState.LISTENING, machine.state)
        assertEquals(listOf(TapToTalkEffect.StartListening), effects)
    }

    @Test
    fun tapWhileListeningCancelsAndReturnsToIdle() {
        val machine = TapToTalkStateMachine()
        machine.on(TapToTalkEvent.TriggerPressed)
        val effects = machine.on(TapToTalkEvent.TriggerPressed)
        assertEquals(TapToTalkState.IDLE, machine.state)
        assertEquals(listOf(TapToTalkEffect.StopListening, TapToTalkEffect.PlayCancelCue), effects)
    }

    @Test
    fun tapWhileBusyIsIgnored() {
        val machine = TapToTalkStateMachine()
        machine.on(TapToTalkEvent.TriggerPressed) // -> LISTENING
        machine.on(TapToTalkEvent.FinalTranscript("what time is it")) // -> BUSY
        val effects = machine.on(TapToTalkEvent.TriggerPressed)
        assertEquals(TapToTalkState.BUSY, machine.state)
        assertEquals(emptyList<TapToTalkEffect>(), effects)
    }

    @Test
    fun finalTranscriptWhileListeningSubmitsAndGoesBusy() {
        val machine = TapToTalkStateMachine()
        machine.on(TapToTalkEvent.TriggerPressed)
        val effects = machine.on(TapToTalkEvent.FinalTranscript("hello there"))
        assertEquals(TapToTalkState.BUSY, machine.state)
        assertEquals(
            listOf(
                TapToTalkEffect.StopListening,
                TapToTalkEffect.PlayCapturedCue,
                TapToTalkEffect.Submit("hello there"),
            ),
            effects,
        )
    }

    @Test
    fun blankFinalTranscriptWhileListeningIsIgnored() {
        val machine = TapToTalkStateMachine()
        machine.on(TapToTalkEvent.TriggerPressed)
        val effects = machine.on(TapToTalkEvent.FinalTranscript("   "))
        assertEquals(TapToTalkState.LISTENING, machine.state)
        assertEquals(emptyList<TapToTalkEffect>(), effects)
    }

    @Test
    fun finalTranscriptWhileIdleIsIgnored() {
        val machine = TapToTalkStateMachine()
        val effects = machine.on(TapToTalkEvent.FinalTranscript("stray"))
        assertEquals(TapToTalkState.IDLE, machine.state)
        assertEquals(emptyList<TapToTalkEffect>(), effects)
    }

    @Test
    fun finalTranscriptWhileBusyDoesNotQueueSecondRequest() {
        val machine = TapToTalkStateMachine()
        machine.on(TapToTalkEvent.TriggerPressed)
        machine.on(TapToTalkEvent.FinalTranscript("first question"))
        val effects = machine.on(TapToTalkEvent.FinalTranscript("second question"))
        assertEquals(TapToTalkState.BUSY, machine.state)
        assertEquals(emptyList<TapToTalkEffect>(), effects)
    }

    @Test
    fun responseFinishedReturnsToIdleAndPlaysReadyCue() {
        val machine = TapToTalkStateMachine()
        machine.on(TapToTalkEvent.TriggerPressed)
        machine.on(TapToTalkEvent.FinalTranscript("question"))
        val effects = machine.on(TapToTalkEvent.ResponseFinished)
        assertEquals(TapToTalkState.IDLE, machine.state)
        assertEquals(listOf(TapToTalkEffect.PlayReadyCue), effects)
    }

    @Test
    fun responseFailedReturnsToIdleAndPlaysReadyCue() {
        val machine = TapToTalkStateMachine()
        machine.on(TapToTalkEvent.TriggerPressed)
        machine.on(TapToTalkEvent.FinalTranscript("question"))
        val effects = machine.on(TapToTalkEvent.ResponseFailed)
        assertEquals(TapToTalkState.IDLE, machine.state)
        assertEquals(listOf(TapToTalkEffect.PlayReadyCue), effects)
    }

    @Test
    fun disconnectedWhileListeningStopsListeningAndReturnsToIdle() {
        val machine = TapToTalkStateMachine()
        machine.on(TapToTalkEvent.TriggerPressed)
        val effects = machine.on(TapToTalkEvent.Disconnected)
        assertEquals(TapToTalkState.IDLE, machine.state)
        assertEquals(listOf(TapToTalkEffect.StopListening), effects)
    }

    @Test
    fun disconnectedWhileBusyReturnsToIdleWithoutStoppingListening() {
        val machine = TapToTalkStateMachine()
        machine.on(TapToTalkEvent.TriggerPressed)
        machine.on(TapToTalkEvent.FinalTranscript("question"))
        val effects = machine.on(TapToTalkEvent.Disconnected)
        assertEquals(TapToTalkState.IDLE, machine.state)
        assertEquals(emptyList<TapToTalkEffect>(), effects)
    }

    @Test
    fun disconnectedWhileIdleIsANoOp() {
        val machine = TapToTalkStateMachine()
        val effects = machine.on(TapToTalkEvent.Disconnected)
        assertEquals(TapToTalkState.IDLE, machine.state)
        assertEquals(emptyList<TapToTalkEffect>(), effects)
    }

    @Test
    fun manualSubmitWhileIdleGoesBusyAndSubmits() {
        val machine = TapToTalkStateMachine()
        val effects = machine.on(TapToTalkEvent.ManualSubmit("typed question"))
        assertEquals(TapToTalkState.BUSY, machine.state)
        assertEquals(listOf(TapToTalkEffect.Submit("typed question")), effects)
    }

    @Test
    fun manualSubmitWhileListeningStopsListeningThenSubmits() {
        val machine = TapToTalkStateMachine()
        machine.on(TapToTalkEvent.TriggerPressed)
        val effects = machine.on(TapToTalkEvent.ManualSubmit("typed question"))
        assertEquals(TapToTalkState.BUSY, machine.state)
        assertEquals(
            listOf(TapToTalkEffect.StopListening, TapToTalkEffect.Submit("typed question")),
            effects,
        )
    }

    @Test
    fun manualSubmitWhileBusyIsIgnoredAndDoesNotQueue() {
        val machine = TapToTalkStateMachine()
        machine.on(TapToTalkEvent.ManualSubmit("first"))
        val effects = machine.on(TapToTalkEvent.ManualSubmit("second"))
        assertEquals(TapToTalkState.BUSY, machine.state)
        assertEquals(emptyList<TapToTalkEffect>(), effects)
    }
}
