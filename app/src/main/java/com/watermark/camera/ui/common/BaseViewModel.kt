package com.watermark.camera.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Base ViewModel providing common functionality for all ViewModels.
 *
 * Features:
 * - UI state management via StateFlow
 * - One-time event emission via SharedFlow
 * - Coroutine scope tied to ViewModel lifecycle
 */
abstract class BaseViewModel<State : UiState, Event : UiEvent> : ViewModel() {

    /**
     * Mutable state flow holding the current UI state.
     * Subclasses must initialize this with their initial state.
     */
    protected abstract val _uiState: MutableStateFlow<State>

    /**
     * Public read-only state flow for UI observation.
     */
    val uiState: StateFlow<State> by lazy { _uiState.asStateFlow() }

    /**
     * Mutable shared flow for one-time UI events (toasts, navigation, etc.).
     */
    private val _uiEvent = MutableSharedFlow<Event>(extraBufferCapacity = 1)

    /**
     * Public read-only shared flow for UI event observation.
     */
    val uiEvent: SharedFlow<Event> = _uiEvent.asSharedFlow()

    /**
     * Updates the UI state using a reducer function.
     *
     * @param reducer Function that receives the current state and returns the new state.
     */
    protected fun updateState(reducer: State.() -> State) {
        val currentState = _uiState.value
        val newState = currentState.reducer()
        _uiState.value = newState
    }

    /**
     * Emits a one-time UI event.
     *
     * @param event The event to emit.
     */
    protected fun sendEvent(event: Event) {
        _uiEvent.tryEmit(event)
    }

    /**
     * Launches a coroutine in the ViewModel scope.
     *
     * @param block The coroutine block to execute.
     */
    protected fun launch(block: suspend CoroutineScope.() -> Unit) {
        viewModelScope.launch(block = block)
    }
}

/**
 * Marker interface for UI state classes.
 */
interface UiState

/**
 * Marker interface for UI event classes.
 */
interface UiEvent
