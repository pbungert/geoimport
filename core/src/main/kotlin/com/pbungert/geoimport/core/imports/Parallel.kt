package com.pbungert.geoimport.core.imports

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * [map] on up to [threads] threads at once, results in the original order.
 * An exception from [transform] is rethrown as itself, as [map] would.
 */
internal fun <T, R> List<T>.mapInParallel(threads: Int, transform: (T) -> R): List<R> {
    if (size < 2 || threads < 2) return map(transform)
    val pool = Executors.newFixedThreadPool(minOf(threads, size))
    try {
        return pool.invokeAll(map { Callable { transform(it) } }).map {
            try {
                it.get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
    } finally {
        pool.shutdownNow()
    }
}
