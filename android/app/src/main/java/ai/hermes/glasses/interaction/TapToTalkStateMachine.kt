package ai.hermes.glasses.interaction

/** Idle/listening/busy states for the glasses action-button hands-free trigger. No Android or DAT types. */
enum class TapToTalkState {
    IDLE,
    LISTENING,
    BUSY,
}

sealed class TapToTalkEvent {
    /** A press of the physical action button, delivered as InputEvent.Button(ButtonType.ACTION). */
    object TriggerPressed : TapToTalkEvent()

    data class FinalTranscript(val text: String) : TapToTalkEvent()

    object ResponseFinished : TapToTalkEvent()

    object ResponseFailed : TapToTalkEvent()

    /** Glasses disconnected/folded, or the DeviceSession stopped. */
    object Disconnected : TapToTalkEvent()

    /** The phone button or typed-question fallback was used directly. */
    data class ManualSubmit(val text: String) : TapToTalkEvent()
}

sealed class TapToTalkEffect {
    object StartListening : TapToTalkEffect()
    object StopListening : TapToTalkEffect()
    data class Submit(val text: String) : TapToTalkEffect()
    object PlayReadyCue : TapToTalkEffect()
    object PlayCapturedCue : TapToTalkEffect()
    object PlayCancelCue : TapToTalkEffect()
}

/**
 * Pure state machine for the tap-to-talk interaction. Callers translate [TapToTalkEffect]s into
 * real Speech/Hermes/audio calls; this class holds no Android or DAT dependency so it is
 * unit-testable without instrumentation.
 */
class TapToTalkStateMachine {
    var state: TapToTalkState = TapToTalkState.IDLE
        private set

    fun on(event: TapToTalkEvent): List<TapToTalkEffect> {
        val (nextState, effects) = transition(state, event)
        state = nextState
        return effects
    }

    private fun transition(
        current: TapToTalkState,
        event: TapToTalkEvent,
    ): Pair<TapToTalkState, List<TapToTalkEffect>> = when (event) {
        is TapToTalkEvent.TriggerPressed -> when (current) {
            TapToTalkState.IDLE ->
                TapToTalkState.LISTENING to listOf(TapToTalkEffect.StartListening)
            TapToTalkState.LISTENING ->
                TapToTalkState.IDLE to listOf(TapToTalkEffect.StopListening, TapToTalkEffect.PlayCancelCue)
            TapToTalkState.BUSY ->
                current to emptyList()
        }

        is TapToTalkEvent.FinalTranscript -> when {
            current != TapToTalkState.LISTENING -> current to emptyList()
            event.text.isBlank() -> current to emptyList()
            else -> TapToTalkState.BUSY to
                listOf(
                    TapToTalkEffect.StopListening,
                    TapToTalkEffect.PlayCapturedCue,
                    TapToTalkEffect.Submit(event.text),
                )
        }

        is TapToTalkEvent.ResponseFinished, is TapToTalkEvent.ResponseFailed -> when (current) {
            TapToTalkState.BUSY -> TapToTalkState.IDLE to listOf(TapToTalkEffect.PlayReadyCue)
            else -> current to emptyList()
        }

        is TapToTalkEvent.Disconnected -> when (current) {
            TapToTalkState.LISTENING -> TapToTalkState.IDLE to listOf(TapToTalkEffect.StopListening)
            TapToTalkState.BUSY -> TapToTalkState.IDLE to emptyList()
            TapToTalkState.IDLE -> current to emptyList()
        }

        is TapToTalkEvent.ManualSubmit -> when (current) {
            TapToTalkState.BUSY -> current to emptyList()
            TapToTalkState.LISTENING ->
                TapToTalkState.BUSY to listOf(TapToTalkEffect.StopListening, TapToTalkEffect.Submit(event.text))
            TapToTalkState.IDLE ->
                TapToTalkState.BUSY to listOf(TapToTalkEffect.Submit(event.text))
        }
    }
}
