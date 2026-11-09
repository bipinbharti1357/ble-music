package com.example.blemusic.ble

import android.util.Base64
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.zip.CRC32

/**
 * High-level ESP32_Audio_Clock operations, ported 1:1 from ble_full_test.py's
 * BLETester class. BleManager owns the GATT connection and the raw write queue;
 * this class owns request/response pairing over BleManager.statusLines, the
 * Kotlin analogue of the python script's asyncio.Queue-based wait_for pattern.
 *
 * All functions assume the link is already ConnState.READY.
 */
class BleProtocol(private val ble: BleManager) {

    class BleProtocolException(message: String) : Exception(message)

    // --------------------------------------------------------------- helpers

    /** Sends nothing; just waits for the next line starting with [prefix]. */
    private suspend fun awaitLine(prefix: String, timeoutMs: Long = 5_000): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw BleProtocolException("Timeout waiting for '$prefix'")
            val line = try {
                withTimeout(remaining) { ble.statusLines.receive() }
            } catch (e: TimeoutCancellationException) {
                throw BleProtocolException("Timeout waiting for '$prefix'")
            }
            if (line.startsWith(prefix)) return line
            // not the reply we wanted — discard and keep waiting, as in send_cmd()
        }
    }

    /** Collects lines matching [collect] until [isEnd] or the idle gap / overall deadline expires. */
    private suspend fun collectUntil(
        collect: (String) -> Boolean,
        isEnd: (String) -> Boolean,
        overallTimeoutMs: Long,
        idleTimeoutMs: Long
    ): List<String> {
        val out = mutableListOf<String>()
        val deadline = System.currentTimeMillis() + overallTimeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val line = withTimeoutOrNull(minOf(idleTimeoutMs, remaining)) {
                ble.statusLines.receive()
            } ?: break
            if (collect(line)) out.add(line)
            if (isEnd(line)) break
        }
        return out
    }

    // -------------------------------------------------------------- commands

    suspend fun getStatus(): String {
        ble.sendText(BleSpec.cmdGetStatus())
        return awaitLine(BleSpec.Reply.INFO)
    }

    suspend fun syncTime(): String {
        ble.sendText(BleSpec.cmdSetTime())
        return awaitLine(BleSpec.Reply.INFO)
    }

    suspend fun listFiles(
        overallTimeoutMs: Long = 5_000,
        idleTimeoutMs: Long = 2_000
    ): List<BleSpec.DeviceFile> {
        ble.sendText(BleSpec.cmdListFiles())
        return collectUntil(
            collect = { it.startsWith(BleSpec.Reply.FILE) },
            isEnd = { it.contains(BleSpec.Reply.LIST_END) },
            overallTimeoutMs = overallTimeoutMs,
            idleTimeoutMs = idleTimeoutMs
        ).map { BleSpec.DeviceFile(it.removePrefix(BleSpec.Reply.FILE)) }
    }

    /**
     * Uploads [data] as [filename], resuming from whatever offset the device reports
     * it already has (FILE_START/STATUS:FILE_UPLOAD_STARTED:<have_bytes>), same as
     * upload_file() in the python script. [onProgress] is invoked with (sent, total).
     */
    suspend fun uploadFile(
        filename: String,
        data: ByteArray,
        onProgress: ((sent: Int, total: Int) -> Unit)? = null
    ): String {
        val size = data.size
        val crc = CRC32().apply { update(data) }.value
        val crcHex = "%08X".format(crc)

        ble.sendText(BleSpec.cmdFileStart(filename, size, crcHex))
        val startedLine = awaitLine(BleSpec.Reply.FILE_UPLOAD_STARTED, timeoutMs = 5_000)
        val have = startedLine.split(":").getOrNull(2)?.toIntOrNull() ?: 0
        onProgress?.invoke(have, size)

        var sent = have
        while (sent < size) {
            val end = minOf(sent + ble.fileChunkSize, size)
            val chunk = data.copyOfRange(sent, end)
            val b64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
            ble.sendText(BleSpec.cmdFileData(b64))
            sent = end
            onProgress?.invoke(sent, size)
            delay(50) // matches the python script's pacing between chunks
        }

        ble.sendText(BleSpec.cmdFileEnd())
        val result = try {
            withTimeout(10_000) {
                var line: String
                do {
                    line = ble.statusLines.receive()
                } while (
                    !line.startsWith(BleSpec.Reply.FILE_UPLOAD_SUCCESS) &&
                    !line.startsWith(BleSpec.Reply.FILE_UPLOAD_FAILED)
                )
                line
            }
        } catch (e: TimeoutCancellationException) {
            throw BleProtocolException("No final response after FILE_END")
        }
        if (result.startsWith(BleSpec.Reply.FILE_UPLOAD_FAILED)) {
            throw BleProtocolException(result)
        }
        return result
    }

    /** Volume as 0.0..1.0. */
    suspend fun setVolume(level: Float): String {
        ble.sendText(BleSpec.cmdSetVolume(level))
        return awaitLine(BleSpec.Reply.VOL)
    }

    /**
     * Starts playback and waits (up to [waitMs]) for STATUS:FINISHED.
     * Returns null on timeout without treating it as an error — same as
     * play_file() in the python script.
     */
    suspend fun play(filename: String, waitMs: Long = 10_000): String? {
        ble.sendText(BleSpec.cmdPlay(filename))
        return withTimeoutOrNull(waitMs) {
            var line: String
            do {
                line = ble.statusLines.receive()
            } while (!line.contains(BleSpec.Reply.FINISHED))
            line
        }
    }

    /** Fire-and-forget, same as the python script's stop_audio(). */
    fun stop() {
        ble.sendText(BleSpec.cmdStop())
    }

    suspend fun addAlarm(
        hour: Int,
        minute: Int,
        filename: String,
        recurrence: BleSpec.Recurrence = BleSpec.Recurrence.DAILY,
        weekday: Int? = null
    ): String {
        ble.sendText(BleSpec.cmdAddAlarm(hour, minute, filename, recurrence, weekday))
        return awaitLine(BleSpec.Reply.ALARM_ADDED)
    }

    suspend fun listAlarms(
        overallTimeoutMs: Long = 5_000,
        idleTimeoutMs: Long = 2_000
    ): List<String> {
        ble.sendText(BleSpec.cmdListAlarms())
        return collectUntil(
            collect = { it.startsWith(BleSpec.Reply.ALARM_ITEM) },
            isEnd = { it.contains(BleSpec.Reply.ALARM_LIST_END) },
            overallTimeoutMs = overallTimeoutMs,
            idleTimeoutMs = idleTimeoutMs
        )
    }

    suspend fun deleteAlarm(index: Int): String {
        ble.sendText(BleSpec.cmdDeleteAlarm(index))
        return awaitLine(BleSpec.Reply.ALARM_DELETED)
    }

    suspend fun clearAlarms(): String {
        ble.sendText(BleSpec.cmdClearAlarms())
        return awaitLine(BleSpec.Reply.ALL_ALARMS_CLEARED)
    }

    // ------------------------------------------------------------- sequences

    /**
     * The rough equivalent of ble_full_test.py's main(): status -> time sync ->
     * list files -> optional upload -> volume -> play -> three test alarms ->
     * list -> delete one -> clear. Handy for a "run full self-test" button.
     */
    suspend fun runFullTest(
        uploadFilename: String? = null,
        uploadData: ByteArray? = null,
        onProgress: ((sent: Int, total: Int) -> Unit)? = null,
        log: (String) -> Unit = {}
    ) {
        log(getStatus())
        syncTime()
        val files = listFiles()
        log("Files: ${files.map { it.name }}")

        val playFilename = if (uploadFilename != null && uploadData != null) {
            log("Uploading $uploadFilename...")
            log(uploadFile(uploadFilename, uploadData, onProgress))
            uploadFilename
        } else {
            files.firstOrNull()?.name ?: "/alarm.mp3"
        }

        setVolume(0.8f)
        log("Playing $playFilename")
        play(playFilename, waitMs = 15_000)

        log("Adding three test alarms")
        val r1 = addAlarm(7, 30, "/alarm.mp3", BleSpec.Recurrence.DAILY)
        log(r1)
        val r2 = addAlarm(9, 0, "/alarm.mp3", BleSpec.Recurrence.ONCE)
        log(r2)
        val r3 = addAlarm(8, 0, "/alarm.mp3", BleSpec.Recurrence.WEEKLY, weekday = 1)
        log(r3)

        listAlarms().forEach { log(it) }

        BleSpec.parseAddedAlarmIndex(r2)?.let { idx -> log(deleteAlarm(idx)) }
        log(clearAlarms())
    }
}
