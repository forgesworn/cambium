package dev.forgesworn.cambium.nip55

import java.util.concurrent.ConcurrentHashMap

/**
 * Small per-key warning aggregator for bursty provider failures. The first occurrence is reported
 * immediately; repeats are counted without producing a log line, and the first occurrence after
 * [intervalMillis] reports the whole count accumulated since the previous warning.
 */
internal class BurstLogLimiter(
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val elapsedRealtimeMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val states = ConcurrentHashMap<String, State>()

    fun record(key: String): Report? {
        val state = states.computeIfAbsent(key) { State() }
        val now = elapsedRealtimeMillis()
        return synchronized(state) {
            state.pending += 1
            val last = state.lastReportAtMillis
            if (last != null && now - last < intervalMillis) {
                null
            } else {
                Report(state.pending).also {
                    state.pending = 0
                    state.lastReportAtMillis = now
                }
            }
        }
    }

    data class Report(val occurrences: Int)

    private class State(
        var pending: Int = 0,
        var lastReportAtMillis: Long? = null,
    )

    companion object {
        const val DEFAULT_INTERVAL_MILLIS = 60_000L
    }
}
