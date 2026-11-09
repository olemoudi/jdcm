package dev.jdcm.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * runCatching for suspending code: a cancellation is rethrown, never logged as a failure. Plain runCatching
 * around suspend calls swallows CancellationException and writes a bogus "failed" line to the log (znhr).
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
