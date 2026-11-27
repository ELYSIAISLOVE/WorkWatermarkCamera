package com.watermark.camera.util

import timber.log.Timber

/**
 * Unified logging utility.
 *
 * Wraps Timber to provide consistent log formatting and tag management.
 * All logs are automatically prefixed with "[WM]" for easy filtering.
 */
object Logger {

    private const val PREFIX = "[WM]"

    @JvmStatic
    fun d(tag: String, message: String) {
        Timber.d("$PREFIX [$tag] $message")
    }

    @JvmStatic
    fun d(message: String) {
        Timber.d("$PREFIX $message")
    }

    @JvmStatic
    fun i(tag: String, message: String) {
        Timber.i("$PREFIX [$tag] $message")
    }

    @JvmStatic
    fun i(message: String) {
        Timber.i("$PREFIX $message")
    }

    @JvmStatic
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Timber.w(throwable, "$PREFIX [$tag] $message")
        } else {
            Timber.w("$PREFIX [$tag] $message")
        }
    }

    @JvmStatic
    fun w(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Timber.w(throwable, "$PREFIX $message")
        } else {
            Timber.w("$PREFIX $message")
        }
    }

    @JvmStatic
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Timber.e(throwable, "$PREFIX [$tag] $message")
        } else {
            Timber.e("$PREFIX [$tag] $message")
        }
    }

    @JvmStatic
    fun e(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Timber.e(throwable, "$PREFIX $message")
        } else {
            Timber.e("$PREFIX $message")
        }
    }

    /**
     * Log a performance metric.
     */
    @JvmStatic
    fun perf(tag: String, operation: String, durationMs: Long) {
        Timber.i("$PREFIX [PERF] [$tag] $operation: ${durationMs}ms")
    }
}
