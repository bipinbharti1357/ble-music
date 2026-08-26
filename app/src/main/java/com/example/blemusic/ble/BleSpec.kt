package com.example.blemusic.ble

import java.util.UUID

/**
 * ============================================================
 *  THE ONLY FILE YOU NEED TO EDIT FOR YOUR HARDWARE.
 * ============================================================
 *
 * Replace the UUIDs with the ones your firmware advertises, and make the packet
 * builders below produce exactly the bytes your firmware parses.
 *
 * Don't know your UUIDs? Install nRF Connect, connect to the device, and read them
 * off the service list.
 */
object BleSpec {

    /** Advertised service. Used as a scan filter, so the list only shows your devices. */
    val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")

    /** Phone -> device. Needs WRITE or WRITE_NO_RESPONSE. */
    val CONTROL_CHAR_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")

    /** Device -> phone. Needs NOTIFY. Optional: set to null usage is handled gracefully. */
    val STATUS_CHAR_UUID: UUID = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb")

    /** Standard Client Characteristic Configuration Descriptor. Never change this one. */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Fallback name match, for devices that don't advertise the service UUID. */
    const val NAME_PREFIX = "MUSIC"

    // ---------------------------------------------------------------------
    // Commands
    // ---------------------------------------------------------------------

    private const val CMD_PLAY: Byte = 0x01
    private const val CMD_STOP: Byte = 0x02
    private const val CMD_PAUSE: Byte = 0x03
    private const val CMD_VOLUME: Byte = 0x04

    /**
     * Play [songIndex] for [durationSeconds]. Pass 0 seconds to play until stopped.
     *
     * Layout: [cmd][song][duration_lo][duration_hi]   (little-endian uint16)
     * e.g. track 3 for 10 minutes -> 01 03 58 02
     */
    fun play(songIndex: Int, durationSeconds: Int): ByteArray {
        val d = durationSeconds.coerceIn(0, 65535)
        return byteArrayOf(
            CMD_PLAY,
            songIndex.coerceIn(0, 255).toByte(),
            (d and 0xFF).toByte(),
            ((d shr 8) and 0xFF).toByte()
        )
    }

    fun stop(): ByteArray = byteArrayOf(CMD_STOP, 0, 0, 0)

    fun pause(): ByteArray = byteArrayOf(CMD_PAUSE, 0, 0, 0)

    /** DFPlayer Mini and most modules use 0..30. */
    fun volume(level: Int): ByteArray =
        byteArrayOf(CMD_VOLUME, level.coerceIn(0, 30).toByte(), 0, 0)

    // ---------------------------------------------------------------------
    // ASCII alternative — delete whichever pair you don't use.
    // Common with HM-10 / HC-05 style transparent-serial modules where the
    // firmware does a simple String parse.
    // ---------------------------------------------------------------------

    fun playAscii(songIndex: Int, durationSeconds: Int): ByteArray =
        "PLAY:$songIndex,$durationSeconds\n".toByteArray(Charsets.US_ASCII)

    fun stopAscii(): ByteArray = "STOP\n".toByteArray(Charsets.US_ASCII)

    // ---------------------------------------------------------------------
    // Track list.
    //
    // Indexes must match the file order on the device's SD card. If your firmware
    // can report its own track list over the status characteristic, parse it in
    // BleManager.onStatus instead and delete this.
    // ---------------------------------------------------------------------

    data class Track(val index: Int, val title: String)

    val TRACKS = listOf(
        Track(1, "Track 1"),
        Track(2, "Track 2"),
        Track(3, "Track 3"),
        Track(4, "Track 4"),
        Track(5, "Track 5"),
    )
}
