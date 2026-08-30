package com.photobooth.util

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/*
 * Factory helpers for the app's background work. Every thread produced here
 * is a daemon — a stray task must never keep the JVM alive — and named, so a
 * thread dump during a booth hang points straight at the culprit. Each
 * component owns its executor and shuts it down; nothing shares a global pool.
 */

/** A [ThreadFactory] making daemon threads named `<namePrefix>-<n>`. */
fun daemonThreadFactory(namePrefix: String): ThreadFactory {
    val counter = AtomicInteger(1)
    return ThreadFactory { runnable ->
        Thread(runnable, "$namePrefix-${counter.getAndIncrement()}").apply { isDaemon = true }
    }
}

/** Single-threaded executor for a component's serial background work. */
fun daemonExecutor(name: String): ExecutorService =
    Executors.newSingleThreadExecutor(daemonThreadFactory(name))

/** Single-threaded scheduler for delayed / fixed-delay work (timers, polling loops). */
fun daemonScheduler(name: String): ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor(daemonThreadFactory(name))

/** Fixed pool, for a component that needs a few concurrent blocking readers (e.g. a process's stdout + stderr). */
fun daemonPool(name: String, threads: Int): ExecutorService =
    Executors.newFixedThreadPool(threads, daemonThreadFactory(name))
