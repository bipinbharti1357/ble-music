package com.example.blemusic.ble

import java.util.UUID

/**
 * ============================================================
 *  Protocol definition for ESP32_Audio_Clock.
 * ============================================================
 *
 * This matches the text-based protocol used by ble_full_test.py: the firmware
 * speaks ASCII commands over Nordic UART (RX = write, TX = notify), not the
 * binary CMD_PLAY/CMD_STOP scheme from the old template.
 */
object BleSpec {

    val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")

    /** Phone -> device. Needs WRITE or WRITE_NO_RESPONSE. Called RX_UUID in the python script. */
    val CONTROL_CHAR_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")

    /** Device -> phone. Needs NOTIFY. Called TX_UUID in the python script. */
    val STATUS_CHAR_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

    /**
     * Standard Client Characteristic Configuration Descriptor.
     * BUG FIX: this was previously set to CONTROL_CHAR_UUID by mistake, so
     * getDescriptor(CCCD_UUID) never found anything on the status characteristic
     * and notifications were never actually enabled on the GATT side. This is the
     * real, spec-fixed CCCD UUID; do not change it.
     */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val NAME_PREFIX = "ESP32_Audio_Clock"

    /** Conservative default; BleManager narrows this once it knows the negotiated MTU. */
    const val DEFAULT_CHUNK_SIZE = 220

    // ---------------------------------------------------------------------
    // Model types
    // ---------------------------------------------------------------------

    enum class Recurrence { ONCE, DAILY, WEEKLY }

    data class Alarm(
        val index: Int,
        val hour: Int,
        val minute: Int,
        val filename: String,
        val recurrence: Recurrence,
        val weekday: Int? = null
    )

    /** Raw device file entry. Firmware only sends a name today; size is optional. */
    data class DeviceFile(val name: String)

    // ---------------------------------------------------------------------
    // Command builders — one text line per command, ASCII-encoded by the caller.
    // ---------------------------------------------------------------------

    fun cmdGetStatus(): String = "GET_STATUS"

    fun cmdSetTime(epochSeconds: Long = System.currentTimeMillis() / 1000): String =
        "SET_TIME:$epochSeconds"

    fun cmdListFiles(): String = "LIST_FILES"

    fun cmdFileStart(filename: String, sizeBytes: Int, crc32Hex: String): String =
        "FILE_START:$filename:$sizeBytes:$crc32Hex"

    /** [base64Chunk] must already be base64-encoded raw file bytes. */
    fun cmdFileData(base64Chunk: String): String = "FILE_DATA:$base64Chunk"

    fun cmdFileEnd(): String = "FILE_END"

    /** Firmware expects 0.0..1.0, matching the python script's set_volume(). */
    fun cmdSetVolume(level: Float): String =
        "SET_VOL:${level.coerceIn(0f, 1f)}"

    fun cmdPlay(filename: String): String = "PLAY:$filename"

    fun cmdStop(): String = "STOP"

    fun cmdAddAlarm(
        hour: Int,
        minute: Int,
        filename: String,
        recurrence: Recurrence = Recurrence.DAILY,
        weekday: Int? = null
    ): String {
        val base = "ADD_ALARM:$hour:$minute:$filename:${recurrence.name}"
        return if (recurrence == Recurrence.WEEKLY && weekday != null) "$base:$weekday" else base
    }

    fun cmdListAlarms(): String = "LIST_ALARMS"

    fun cmdDeleteAlarm(index: Int): String = "DEL_ALARM:$index"

    fun cmdClearAlarms(): String = "CLEAR_ALARMS"

    // ---------------------------------------------------------------------
    // Response line prefixes, so BleProtocol doesn't scatter magic strings.
    // ---------------------------------------------------------------------

    object Reply {
        const val INFO = "INFO:"
        const val FILE = "FILE:"
        const val LIST_END = "STATUS:LIST_END"
        const val FILE_UPLOAD_STARTED = "STATUS:FILE_UPLOAD_STARTED"
        const val FILE_UPLOAD_SUCCESS = "STATUS:FILE_UPLOAD_SUCCESS"
        const val FILE_UPLOAD_FAILED = "STATUS:FILE_UPLOAD_FAILED"
        const val VOL = "STATUS:VOL"
        const val FINISHED = "STATUS:FINISHED"
        const val ALARM_ADDED = "STATUS:ALARM_ADDED"
        const val ALARM_ITEM = "ALARM_ITEM:"
        const val ALARM_LIST_END = "ALARM_LIST_END"
        const val ALARM_DELETED = "STATUS:ALARM_DELETED"
        const val ALL_ALARMS_CLEARED = "STATUS:ALL_ALARMS_CLEARED"
    }

    /** Parses "STATUS:ALARM_ADDED:<idx>" -> idx, mirroring the python script's r2 parsing. */
    fun parseAddedAlarmIndex(reply: String): Int? =
        reply.substringAfterLast(':', missingDelimiterValue = "").toIntOrNull()
}
