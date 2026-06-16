package com.roundearth.bikecomputer.data.diagnostics

import android.util.Log

/**
 * Drop-in replacement for [android.util.Log] that ALSO appends to [LogBus], so the in-app
 * Diagnostics view shows the same connection lifecycle the BLE source already logs to logcat
 * (connect/disconnect, status-133 flaps, CCCD writes, …) without anyone tailing `adb logcat`.
 *
 * Use exactly like Log: `Diag.i(TAG, "connecting to $addr")`. The throwable overloads append a
 * short summary to the buffered message (logcat still gets the full stack trace).
 */
object Diag {
    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        LogBus.add(LogBus.Source.APP, LogBus.Level.D, tag, msg)
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        LogBus.add(LogBus.Source.APP, LogBus.Level.I, tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        Log.w(tag, msg, tr)
        LogBus.add(LogBus.Source.APP, LogBus.Level.W, tag, msg + (tr?.let { " (${it.javaClass.simpleName})" } ?: ""))
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        Log.e(tag, msg, tr)
        LogBus.add(LogBus.Source.APP, LogBus.Level.E, tag, msg + (tr?.let { " (${it.javaClass.simpleName})" } ?: ""))
    }
}
