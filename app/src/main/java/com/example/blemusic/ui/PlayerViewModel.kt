package com.example.blemusic.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.example.blemusic.ble.BleManager
import com.example.blemusic.ble.BleSpec
import com.example.blemusic.schedule.PlaybackScheduler

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val ble = BleManager(app)

    val connState = ble.connState
    val devices = ble.devices
    val connectedTo = ble.connectedTo
    val message = ble.message

    /** Index into [BleSpec.TRACKS]. */
    var selectedTrack by mutableIntStateOf(0)
        private set

    /** How long to play, in minutes. 0 = play until stopped. */
    var durationMinutes by mutableIntStateOf(10)
        private set

    /** Minutes from now to start. 0 = start immediately. */
    var startDelayMinutes by mutableIntStateOf(0)
        private set

    var volume by mutableIntStateOf(20)
        private set

    val bluetoothEnabled: Boolean get() = ble.isBluetoothOn

    /** Bring-up aid: scan without the service-UUID filter to see every advertiser. */
    var showAllDevices by mutableStateOf(false)
        private set

    fun updateShowAllDevices(v: Boolean) { showAllDevices = v }

    // NOTE: these are named update*, not set*, on purpose. A Kotlin property `foo`
    // always emits a JVM setter `setFoo(T)` — even with `private set` — so a method
    // named `setFoo(T)` is a platform declaration clash.

    fun selectTrack(i: Int) { selectedTrack = i }

    fun updateDuration(min: Int) { durationMinutes = min.coerceIn(0, 120) }

    fun updateStartDelay(min: Int) { startDelayMinutes = min.coerceIn(0, 180) }

    fun updateVolume(v: Int) {
        volume = v.coerceIn(0, 30)
        ble.send(BleSpec.volume(volume))
    }

    fun scan() = ble.startScan(filterByService = !showAllDevices)
    fun stopScan() = ble.stopScan()
    fun connect(address: String) = ble.connect(address)
    fun disconnect() = ble.disconnect()

    /**
     * Sends the duration inside the PLAY packet so the device stops itself. Keeps
     * working if the phone goes out of range or the app is killed.
     */
    fun playNow() {
        val track = BleSpec.TRACKS[selectedTrack]
        ble.send(BleSpec.play(track.index, durationMinutes * 60))
    }

    fun stop() = ble.send(BleSpec.stop())

    fun pause() = ble.send(BleSpec.pause())

    /**
     * Schedules a reconnect-and-play in the future. Use for "start at 7 AM";
     * for "play for 20 minutes" use [playNow] instead.
     */
    fun playLater() {
        val address = connectedTo.value ?: return
        val track = BleSpec.TRACKS[selectedTrack]
        PlaybackScheduler.schedule(
            context = getApplication<Application>(),
            address = address,
            songIndex = track.index,
            durationSeconds = durationMinutes * 60,
            delayMinutes = startDelayMinutes
        )
    }

    fun cancelScheduled() = PlaybackScheduler.cancel(getApplication<Application>())

    override fun onCleared() {
        ble.disconnect()
        super.onCleared()
    }
}
