package me.ash.reader.infrastructure.coroutine

import kotlinx.coroutines.CancellationException

/** Cancellation belongs to the caller's lifecycle, rather than an operation's error state. */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
