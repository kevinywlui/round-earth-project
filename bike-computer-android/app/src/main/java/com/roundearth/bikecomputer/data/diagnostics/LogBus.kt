package com.roundearth.bikecomputer.data.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A process-wide, bounded in-memory log buffer feeding the in-app Diagnostics view. It holds
 * two interleaved streams: the app's own BLE/state-machine logs (appended via [Diag], which
 * also forwards to logcat) and the firmware's debug lines (received over the BLE NUS channel
 * and tagged [Source.FW]). Keeping both in one timestamped buffer lets the log screen show the
 * phone's and the sensor's view of a connection side by side.
 *
 * Bounded to [CAPACITY] lines (oldest dropped) so a long session can't grow without limit.
 * Appends are synchronized and may come from binder threads (GATT callbacks); [lines] is a
 * StateFlow the UI collects.
 */
object LogBus {
    enum class Source { APP, FW }
    enum class Level { D, I, W, E }

    data class Line(
        val timestampMs: Long,
        val source: Source,
        val level: Level,
        val tag: String,
        val message: String,
    )

    /** Roughly a full screen-session of history; oldest lines are evicted past this. */
    const val CAPACITY = 2000

    private val lock = Any()
    private val buffer = ArrayDeque<Line>()
    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines

    /** Wall-clock millis of the most recent append, for testing the eviction boundary. */
    fun add(source: Source, level: Level, tag: String, message: String) {
        synchronized(lock) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(Line(System.currentTimeMillis(), source, level, tag, message))
            // Publish an immutable snapshot so collectors never see the mutating deque.
            _lines.value = buffer.toList()
        }
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _lines.value = emptyList()
        }
    }
}
