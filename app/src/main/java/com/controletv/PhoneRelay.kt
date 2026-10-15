package com.controletv

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs ADB shell commands through the phone app.
 *
 * Since Fire OS 7.6.6.9 / 8.1.0.3, Amazon refuses ADB connections that come from the TV
 * itself, but still accepts them from other devices. So the phone app keeps an ADB
 * connection to the TV and long-polls us for commands to run on it.
 */
object PhoneRelay {
    private class Job(val id: Long, val command: String) {
        val done = CountDownLatch(1)
        @Volatile var output: String? = null
        @Volatile var error: String? = null
    }

    private val ids = AtomicLong()
    private val queue = LinkedBlockingQueue<Job>()
    private val inFlight = ConcurrentHashMap<Long, Job>()

    @Volatile private var lastPoll = 0L

    /** ADB state as reported by the phone app on its last poll. */
    @Volatile var phoneState: Adb.State = Adb.State.OFF
        private set
    @Volatile var phoneError: String? = null
        private set

    val connected get() = System.currentTimeMillis() - lastPoll < POLL_TIMEOUT_MS + 10_000
    val ready get() = connected && phoneState == Adb.State.OK

    fun exec(command: String): String {
        val job = Job(ids.incrementAndGet(), command)
        inFlight[job.id] = job
        queue.put(job)
        try {
            if (!job.done.await(20, TimeUnit.SECONDS)) throw IllegalStateException("O celular não respondeu a tempo")
            job.error?.let { throw IllegalStateException(it) }
            return job.output ?: ""
        } finally {
            inFlight.remove(job.id)
            queue.remove(job)
        }
    }

    /** Called by the phone app's long poll: returns the next command, or null after a while. */
    fun next(state: Adb.State, error: String?): Pair<Long, String>? {
        lastPoll = System.currentTimeMillis()
        phoneState = state
        phoneError = error
        if (state != Adb.State.OK) return null
        val job = queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: return null
        lastPoll = System.currentTimeMillis()
        return job.id to job.command
    }

    fun result(id: Long, output: String?, error: String?) {
        lastPoll = System.currentTimeMillis()
        val job = inFlight[id] ?: return
        job.output = output
        job.error = error
        job.done.countDown()
    }

    private const val POLL_TIMEOUT_MS = 20_000L
}
