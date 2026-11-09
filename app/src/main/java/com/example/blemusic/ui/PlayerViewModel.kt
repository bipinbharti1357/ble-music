package com.example.blemusic.ui

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.blemusic.ble.BleManager
import com.example.blemusic.ble.BleProtocol
import com.example.blemusic.ble.BleSpec
import com.example.blemusic.ble.ConnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val ble = BleManager(app)
    private val protocol = BleProtocol(ble)

    val connState = ble.connState
    val devices = ble.devices
    val connectedTo = ble.connectedTo
    /** Raw last notification line / connection status text from BleManager. */
    val message = ble.message

    /** Files reported by the device's LIST_FILES, refreshed on connect and on demand. */
    private val _files = MutableStateFlow<List<BleSpec.DeviceFile>>(emptyList())
    val files: StateFlow<List<BleSpec.DeviceFile>> = _files.asStateFlow()

    /** Rolling log of everything sent/received, newest last, capped so it can't grow forever. */
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private fun logLine(text: String) {
        _log.update { (it + text).takeLast(300) }
    }

    val bluetoothEnabled: Boolean get() = ble.isBluetoothOn

    // ------------------------------------------------------------- connect

    /** Optional manual BLE address, for connecting without scanning first. */
    var manualAddress by mutableStateOf("")
        private set
    fun updateManualAddress(v: String) { manualAddress = v }
    fun connectManual() {
        val addr = manualAddress.trim()
        if (addr.isNotEmpty()) connect(addr)
    }

    /** Bring-up aid: scan without the service-UUID filter to see every advertiser. */
    var showAllDevices by mutableStateOf(false)
        private set
    fun updateShowAllDevices(v: Boolean) { showAllDevices = v }

    fun scan() = ble.startScan(filterByService = !showAllDevices)
    fun stopScan() = ble.stopScan()
    fun connect(address: String) = ble.connect(address)
    fun disconnect() = ble.disconnect()

    init {
        viewModelScope.launch {
            connState.collect { state ->
                if (state == ConnState.READY) {
                    logLine("Connected.")
                    refreshFiles()
                }
            }
        }
    }

    // -------------------------------------------------------------- status

    fun getStatus() {
        viewModelScope.launch {
            runCatching { protocol.getStatus() }
                .onSuccess { logLine(it) }
                .onFailure { logLine("ERROR (status): ${it.message}") }
        }
    }

    fun setTimeNow() {
        viewModelScope.launch {
            runCatching { protocol.syncTime() }
                .onSuccess { logLine(it) }
                .onFailure { logLine("ERROR (set time): ${it.message}") }
        }
    }

    // ---------------------------------------------------------------- files

    fun refreshFiles() {
        viewModelScope.launch {
            runCatching { protocol.listFiles() }
                .onSuccess { list ->
                    _files.value = list
                    logLine("Files: ${list.map { it.name }}")
                    if (selectedTrack == null) selectedTrack = list.firstOrNull()?.name
                    if (alarmFile == null) alarmFile = list.firstOrNull()?.name
                }
                .onFailure { logLine("ERROR (list files): ${it.message}") }
        }
    }

    /** Reads the picked document and uploads it, then refreshes the file list. */
    fun uploadFile(uri: Uri) {
        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            val name = displayNameOf(resolver, uri) ?: "upload.bin"
            val bytes = withContext(Dispatchers.IO) {
                runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null) {
                logLine("ERROR (upload): could not read file")
                return@launch
            }
            logLine("Uploading $name (${bytes.size} bytes)...")
            var lastLoggedPct = -1
            runCatching {
                protocol.uploadFile(name, bytes) { sent, total ->
                    val pct = if (total > 0) sent * 100 / total else 100
                    if (pct / 20 != lastLoggedPct / 20) {
                        lastLoggedPct = pct
                        logLine("  $pct% ($sent/$total bytes)")
                    }
                }
            }.onSuccess { logLine(it) }
                .onFailure { logLine("ERROR (upload): ${it.message}") }
            refreshFiles()
        }
    }

    private fun displayNameOf(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }

    // -------------------------------------------------------------- volume

    /** Exact 0.0..1.0 value, edited as text like the desktop tool's "Volume:" field. */
    var volumeText by mutableStateOf("0.8")
        private set
    fun updateVolumeText(v: String) { volumeText = v }

    fun setVolume() {
        val level = volumeText.toFloatOrNull()?.coerceIn(0f, 1f) ?: run {
            logLine("ERROR (volume): '$volumeText' is not a number 0.0-1.0")
            return
        }
        viewModelScope.launch {
            runCatching { protocol.setVolume(level) }
                .onSuccess { logLine(it) }
                .onFailure { logLine("ERROR (volume): ${it.message}") }
        }
    }

    fun volUp() = nudgeVolume(+0.1f)
    fun volDown() = nudgeVolume(-0.1f)

    private fun nudgeVolume(delta: Float) {
        val current = volumeText.toFloatOrNull() ?: 0.8f
        volumeText = "%.1f".format((current + delta).coerceIn(0f, 1f))
        setVolume()
    }

    // --------------------------------------------------------- play / stop

    var selectedTrack by mutableStateOf<String?>(null)
        private set
    fun selectTrack(filename: String) { selectedTrack = filename }

    fun playNow() {
        val track = selectedTrack ?: run {
            logLine("ERROR (play): no file selected")
            return
        }
        viewModelScope.launch {
            logLine("Playing $track")
            withContextIgnored { protocol.play(track) }
                ?.let { logLine(it) }
        }
    }

    /** Small helper so a timed-out play (no STATUS:FINISHED) doesn't get logged as an error. */
    private suspend fun withContextIgnored(block: suspend () -> String?): String? =
        runCatching { block() }
            .onFailure { logLine("ERROR (play): ${it.message}") }
            .getOrNull()

    fun stop() {
        protocol.stop()
        logLine("Sent STOP")
    }

    // ------------------------------------------------------------- alarms

    var alarmHourText by mutableStateOf("7")
        private set
    var alarmMinuteText by mutableStateOf("30")
        private set
    var alarmRecurrence by mutableStateOf(BleSpec.Recurrence.DAILY)
        private set
    /** 0..6. Only used when recurrence is WEEKLY. Numbering follows whatever your
     *  firmware expects (e.g. 0 = Sunday) — check ble_full_test.py / your firmware
     *  source if alarms land on the wrong day. */
    var alarmWeekday by mutableStateOf<Int?>(null)
        private set
    var alarmFile by mutableStateOf<String?>(null)
        private set

    fun updateAlarmHour(v: String) { alarmHourText = v }
    fun updateAlarmMinute(v: String) { alarmMinuteText = v }
    fun updateAlarmRecurrence(r: BleSpec.Recurrence) { alarmRecurrence = r }
    fun updateAlarmWeekday(w: Int?) { alarmWeekday = w }
    fun selectAlarmFile(filename: String) { alarmFile = filename }

    fun addAlarm() {
        val hour = alarmHourText.toIntOrNull()?.coerceIn(0, 23)
        val minute = alarmMinuteText.toIntOrNull()?.coerceIn(0, 59)
        val file = alarmFile
        if (hour == null || minute == null || file == null) {
            logLine("ERROR (add alarm): need hour, minute and a file")
            return
        }
        viewModelScope.launch {
            runCatching {
                protocol.addAlarm(hour, minute, file, alarmRecurrence, alarmWeekday)
            }.onSuccess { logLine(it) }
                .onFailure { logLine("ERROR (add alarm): ${it.message}") }
        }
    }

    fun listAlarms() {
        viewModelScope.launch {
            runCatching { protocol.listAlarms() }
                .onSuccess { items -> if (items.isEmpty()) logLine("No alarms.") else items.forEach { logLine(it) } }
                .onFailure { logLine("ERROR (list alarms): ${it.message}") }
        }
    }

    var deleteAlarmIndexText by mutableStateOf("")
        private set
    fun updateDeleteAlarmIndex(v: String) { deleteAlarmIndexText = v }

    fun deleteAlarm() {
        val idx = deleteAlarmIndexText.toIntOrNull() ?: run {
            logLine("ERROR (delete alarm): enter an alarm index first")
            return
        }
        viewModelScope.launch {
            runCatching { protocol.deleteAlarm(idx) }
                .onSuccess { logLine(it) }
                .onFailure { logLine("ERROR (delete alarm): ${it.message}") }
        }
    }

    fun clearAlarms() {
        viewModelScope.launch {
            runCatching { protocol.clearAlarms() }
                .onSuccess { logLine(it) }
                .onFailure { logLine("ERROR (clear alarms): ${it.message}") }
        }
    }

    override fun onCleared() {
        ble.disconnect()
        super.onCleared()
    }
}
