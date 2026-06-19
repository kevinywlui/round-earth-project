package com.roundearth.bikecomputer.data

/**
 * Pure (no-Android) parser for the firmware Backlog service wire format, so it can be unit-tested on
 * the JVM. Mirrors the little-endian packing in `speed.ino` (putLE32 / backlogFillInfo / backlogWrite).
 *
 * - Info block (READ): 20 bytes = bootId, currentUptimeSeconds, oldestIndex, newestIndex, overflow.
 * - Data record (NOTIFY): 16 bytes = bootId, recordIndex, uptimeSeconds, cumulativeRevolutions.
 *   A record with recordIndex == 0xFFFFFFFF is the end-of-stream terminator; an all-zero slot is empty.
 */
object BacklogRecordParser {
    const val RECORD_BYTES = 16
    const val INFO_BYTES = 20
    const val TERMINATOR_INDEX = 0xFFFFFFFFL

    data class Record(
        val bootId: Long,
        val recordIndex: Long,
        val uptimeSeconds: Long,
        val cumulativeRevolutions: Long,
    )

    data class Info(
        val bootId: Long,
        val currentUptimeSeconds: Long,
        val oldestIndex: Long,
        val newestIndex: Long,
        val overflow: Long,
    )

    fun parseInfo(b: ByteArray): Info? {
        if (b.size < INFO_BYTES) return null
        return Info(u32(b, 0), u32(b, 4), u32(b, 8), u32(b, 12), u32(b, 16))
    }

    fun isTerminator(b: ByteArray): Boolean = b.size >= RECORD_BYTES && u32(b, 4) == TERMINATOR_INDEX

    /** Parses a data record, or returns null for the terminator, an empty slot, or a short buffer. */
    fun parseRecord(b: ByteArray): Record? {
        if (b.size < RECORD_BYTES) return null
        val bootId = u32(b, 0)
        val index = u32(b, 4)
        if (index == TERMINATOR_INDEX) return null
        if (bootId == 0L) return null // empty ring slot (boot_id is never 0 for a real record)
        return Record(bootId, index, u32(b, 8), u32(b, 12))
    }

    /**
     * Back-computes a record's wall-clock from the connect-time anchor captured when the Info block was
     * read: [anchorWallClockMs] paired with [info].currentUptimeSeconds. PRECISE only for records from
     * the *current* boot (same bootId). Records from a PRIOR boot have an unknowable off-duration (the
     * sensor has no RTC), so they are placed at the current boot's start as a lower bound — keep this in
     * mind: their absolute time is an estimate, but distance (which keys on revolutions) is unaffected.
     */
    fun wallClockMillis(rec: Record, info: Info, anchorWallClockMs: Long): Long =
        if (rec.bootId == info.bootId) {
            anchorWallClockMs - (info.currentUptimeSeconds - rec.uptimeSeconds) * 1000L
        } else {
            anchorWallClockMs - info.currentUptimeSeconds * 1000L
        }

    private fun u32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or
            ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or
            ((b[o + 3].toLong() and 0xFF) shl 24)
}
