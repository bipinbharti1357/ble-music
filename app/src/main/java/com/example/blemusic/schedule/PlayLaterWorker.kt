package com.example.blemusic.schedule

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.blemusic.ble.BleManager
import com.example.blemusic.ble.BleSpec
import com.example.blemusic.ble.ConnState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Wakes up later, reconnects to the device, sends PLAY, disconnects.
 *
 * Use this only for "start playing at a future time." If the timer just means
 * "play for N minutes," put the duration in the PLAY packet instead — the device
 * handles it without the phone being involved.
 *
 * Doze mode can delay this by several minutes. For alarm-clock accuracy, swap
 * WorkManager for AlarmManager.setExactAndAllowWhileIdle() and declare
 * SCHEDULE_EXACT_ALARM.
 */
class PlayLaterWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val address = inputData.getString(KEY_ADDRESS) ?: return Result.failure()
        val songIndex = inputData.getInt(KEY_SONG, 1)
        val durationSeconds = inputData.getInt(KEY_DURATION, 0)

        if (!hasConnectPermission()) return Result.failure()

        val ble = BleManager(applicationContext)
        ble.connect(address)

        val connected = withTimeoutOrNull(25_000) {
            ble.connState.first { it == ConnState.READY || it == ConnState.FAILED }
        }

        if (connected != ConnState.READY) {
            ble.disconnect()
            return if (runAttemptCount < 3) Result.retry() else Result.failure()
        }

        ble.send(BleSpec.play(songIndex, durationSeconds))
        delay(1_500)          // let the write land before tearing down the link
        ble.disconnect()
        return Result.success()
    }

    private fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                applicationContext, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    companion object {
        const val KEY_ADDRESS = "address"
        const val KEY_SONG = "song"
        const val KEY_DURATION = "duration"
    }
}

object PlaybackScheduler {

    private const val WORK_NAME = "scheduled_playback"

    fun schedule(
        context: Context,
        address: String,
        songIndex: Int,
        durationSeconds: Int,
        delayMinutes: Int
    ) {
        val data = Data.Builder()
            .putString(PlayLaterWorker.KEY_ADDRESS, address)
            .putInt(PlayLaterWorker.KEY_SONG, songIndex)
            .putInt(PlayLaterWorker.KEY_DURATION, durationSeconds)
            .build()

        val request = OneTimeWorkRequestBuilder<PlayLaterWorker>()
            .setInputData(data)
            .setInitialDelay(delayMinutes.toLong(), TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
