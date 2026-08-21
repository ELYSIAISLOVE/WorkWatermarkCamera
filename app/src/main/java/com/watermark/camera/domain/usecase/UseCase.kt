package com.watermark.camera.domain.usecase

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Base class for all UseCases.
 *
 * Enforces the single-responsibility principle: each UseCase does exactly one thing.
 * UseCases are pure Kotlin logic, independent of Android framework.
 *
 * @param Params Input parameters type.
 * @param Result Output result type.
 * @param dispatcher Coroutine dispatcher for execution (default: IO).
 */
abstract class UseCase<in Params, out Result>(
    private val dispatcher: CoroutineDispatcher
) {

    /**
     * Execute the use case.
     *
     * @param params Input parameters.
     * @return Result of the operation.
     */
    suspend operator fun invoke(params: Params): kotlin.Result<Result> {
        return withContext(dispatcher) {
            try {
                execute(params)
            } catch (e: Exception) {
                kotlin.Result.failure(e)
            }
        }
    }

    /**
     * The actual business logic implementation.
     *
     * @param params Input parameters.
     * @return Result of the operation.
     */
    protected abstract suspend fun execute(params: Params): kotlin.Result<Result>
}

/**
 * UseCase with no parameters.
 */
abstract class NoParamsUseCase<out Result>(
    dispatcher: CoroutineDispatcher
) : UseCase<Unit, Result>(dispatcher) {

    /**
     * Execute without parameters.
     */
    suspend operator fun invoke(): kotlin.Result<Result> = invoke(Unit)
}
