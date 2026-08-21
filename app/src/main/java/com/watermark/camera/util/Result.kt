package com.watermark.camera.util

/**
 * Domain-specific result wrapper for operations that can fail.
 *
 * Provides a type-safe way to handle success and error cases
 * with additional context (error codes, user-friendly messages).
 */
sealed class AppResult<out T> {

    /**
     * Successful result.
     */
    data class Success<T>(val data: T) : AppResult<T>()

    /**
     * Error result.
     */
    data class Error(
        val code: ErrorCode,
        val message: String,
        val cause: Throwable? = null
    ) : AppResult<Nothing>()

    /**
     * Check if this result is a success.
     */
    fun isSuccess(): Boolean = this is Success

    /**
     * Check if this result is an error.
     */
    fun isError(): Boolean = this is Error

    /**
     * Get the success data, or null if error.
     */
    fun getOrNull(): T? = (this as? Success)?.data

    /**
     * Get the success data, or throw if error.
     */
    fun getOrThrow(): T {
        return when (this) {
            is Success -> data
            is Error -> throw cause ?: IllegalStateException(message)
        }
    }

    /**
     * Transform the success value.
     */
    inline fun <R> map(transform: (T) -> R): AppResult<R> {
        return when (this) {
            is Success -> Success(transform(data))
            is Error -> this
        }
    }

    /**
     * Execute action on success.
     */
    inline fun onSuccess(action: (T) -> Unit): AppResult<T> {
        if (this is Success) action(data)
        return this
    }

    /**
     * Execute action on error.
     */
    inline fun onError(action: (Error) -> Unit): AppResult<T> {
        if (this is Error) action(this)
        return this
    }
}

/**
 * Error codes for the application.
 */
enum class ErrorCode {
    // Camera errors
    CAMERA_INIT_FAILED,
    CAMERA_CAPTURE_FAILED,
    CAMERA_NOT_AVAILABLE,
    CAMERA_PERMISSION_DENIED,

    // Storage errors
    STORAGE_SAVE_FAILED,
    STORAGE_DELETE_FAILED,
    STORAGE_FULL,
    STORAGE_PERMISSION_DENIED,

    // Location errors
    LOCATION_UNAVAILABLE,
    LOCATION_TIMEOUT,
    LOCATION_PERMISSION_DENIED,

    // Processing errors
    IMAGE_PROCESSING_FAILED,
    WATERMARK_APPLY_FAILED,
    EXIF_WRITE_FAILED,

    // Network/General errors
    NETWORK_ERROR,
    UNKNOWN_ERROR
}

/**
 * Extension to convert Kotlin Result to AppResult.
 */
fun <T> kotlin.Result<T>.toAppResult(errorCode: ErrorCode = ErrorCode.UNKNOWN_ERROR): AppResult<T> {
    return fold(
        onSuccess = { AppResult.Success(it) },
        onFailure = {
            AppResult.Error(
                code = errorCode,
                message = it.message ?: "Unknown error",
                cause = it
            )
        }
    )
}
