package com.self.zai.agent

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface RunActivityEvent {
    data class PhoneOperationStep(val targetAppLabel: String = "") : RunActivityEvent
    data class NonPhoneToolActivity(val label: String) : RunActivityEvent
}

sealed interface RunControlEvent {
    data object StopActiveRun : RunControlEvent
    data object FocusRunningSession : RunControlEvent
}

/**
 * Decoupled in-process event bus breaking constructor cycles between [AgentCoordinator],
 * [com.self.zai.phone.PhoneController], [AgentToolDispatcher], [ModelSelectionManager],
 * [CapabilitiesManager], and [com.self.zai.service.FloatingCapsuleController].
 */
class RunEventBus {

    private val _activityEvents = MutableSharedFlow<RunActivityEvent>(extraBufferCapacity = 32)
    val activityEvents: SharedFlow<RunActivityEvent> = _activityEvents.asSharedFlow()

    private val _controlEvents = MutableSharedFlow<RunControlEvent>(extraBufferCapacity = 16)
    val controlEvents: SharedFlow<RunControlEvent> = _controlEvents.asSharedFlow()

    private val _configInvalidations = MutableSharedFlow<String?>(extraBufferCapacity = 16)
    val configInvalidations: SharedFlow<String?> = _configInvalidations.asSharedFlow()

    fun emitPhoneOperationStep(targetAppLabel: String = "") {
        _activityEvents.tryEmit(RunActivityEvent.PhoneOperationStep(targetAppLabel))
    }

    fun emitNonPhoneToolActivity(label: String) {
        _activityEvents.tryEmit(RunActivityEvent.NonPhoneToolActivity(label))
    }

    fun requestStopActiveRun() {
        _controlEvents.tryEmit(RunControlEvent.StopActiveRun)
    }

    fun requestFocusRunningSession() {
        _controlEvents.tryEmit(RunControlEvent.FocusRunningSession)
    }

    /** Pass `null` to invalidate all configured Pi sessions, or a specific `sessionId`. */
    fun invalidateSessionConfig(sessionId: String? = null) {
        _configInvalidations.tryEmit(sessionId)
    }
}
