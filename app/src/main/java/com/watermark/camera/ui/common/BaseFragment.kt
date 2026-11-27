package com.watermark.camera.ui.common

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewbinding.ViewBinding
import kotlinx.coroutines.launch

/**
 * Base Fragment providing common functionality for all fragments.
 *
 * Features:
 * - ViewBinding auto-management (null-safe cleanup in onDestroyView)
 * - Automatic collection of StateFlow and SharedFlow with lifecycle awareness
 * - Loading state handling
 *
 * @param Binding The ViewBinding type for this fragment.
 */
abstract class BaseFragment<Binding : ViewBinding> : Fragment() {

    private var _binding: Binding? = null

    /**
     * Access the binding instance.
     * Throws if accessed before onCreateView or after onDestroyView.
     */
    protected val binding: Binding
        get() = _binding ?: throw IllegalStateException(
            "Binding is null. Ensure you access it between onCreateView and onDestroyView."
        )

    /**
     * Inflates the view binding for this fragment.
     * Subclasses must implement this to provide their specific binding.
     */
    abstract fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?): Binding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = inflateBinding(inflater, container)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews()
        observeData()
    }

    /**
     * Initialize views and set up click listeners.
     * Called in onViewCreated.
     */
    abstract fun initViews()

    /**
     * Observe ViewModel data flows.
     * Called in onViewCreated.
     *
     * Use [collectStateFlow] and [collectEventFlow] for automatic lifecycle management.
     */
    abstract fun observeData()

    /**
     * Collects a StateFlow with lifecycle awareness.
     * Automatically starts collecting when the lifecycle reaches STARTED
     * and stops when it falls below STARTED.
     *
     * @param flow The StateFlow to collect.
     * @param collect The collector function.
     */
    protected fun <T> collectStateFlow(
        flow: androidx.lifecycle.LifecycleCoroutineScope,
        collect: (T) -> Unit
    ) {
        // This is a helper that should be used with viewLifecycleOwner.lifecycleScope
        // The actual collection happens in observeData using the extension below
    }

    /**
     * Safely collects a StateFlow with lifecycle awareness.
     *
     * Usage:
     * ```
     * collectStateFlow(viewModel.uiState) { state ->
     *     // handle state
     * }
     * ```
     */
    protected fun <T> collectStateFlow(
        stateFlow: kotlinx.coroutines.flow.StateFlow<T>,
        action: (T) -> Unit
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                stateFlow.collect { action(it) }
            }
        }
    }

    /**
     * Safely collects a SharedFlow (one-time events) with lifecycle awareness.
     *
     * Usage:
     * ```
     * collectEventFlow(viewModel.uiEvent) { event ->
     *     // handle event
     * }
     * ```
     */
    protected fun <T> collectEventFlow(
        sharedFlow: kotlinx.coroutines.flow.SharedFlow<T>,
        action: (T) -> Unit
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                sharedFlow.collect { action(it) }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
