package com.example.blemusic.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "BleManager"

enum class ConnState { IDLE, SCANNING, CONNECTING, READY, DISCONNECTED, FAILED }

data class DiscoveredDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    /** Service UUIDs present in the advertisement packet, if any. */
    val advertisedServices: List<String> = emptyList()
)

/**
 * Wraps the Android BLE stack: scan -> connect -> discover services -> write.
 *
 * Permission checks live in the UI layer, which is why the class is annotated
 * MissingPermission. Every public method is safe to call from any thread: all GATT
 * work is funnelled onto the main looper and serialised through a queue, because the
 * Android stack silently drops any operation issued while another is still in flight.
 *
 * NOTE ON STYLE: every method that wraps `Handler.post` uses a block body with an
 * explicit Unit return. `Handler.post` returns Boolean, so an expression body
 * (`fun foo() = main.post { ... }`) would silently infer Boolean and break any
 * override or `() -> Unit` function reference that uses it.
 */
@SuppressLint("MissingPermission")
class BleManager(context: Context) {

    private val appContext = context.applicationContext
    private val btManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = btManager.adapter
    private val scanner: BluetoothLeScanner? get() = adapter?.bluetoothLeScanner

    private val main = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var controlChar: BluetoothGattCharacteristic? = null
    private var statusChar: BluetoothGattCharacteristic? = null

    /** Command captured while still connecting; flushed once the link is READY. */
    private var pendingOnReady: ByteArray? = null

    private var scanning = false
    private var lastScanWasFiltered = true

    // ----------------------------------------------------------------- state

    private val _connState = MutableStateFlow(ConnState.IDLE)
    val connState: StateFlow<ConnState> = _connState.asStateFlow()

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    private val _connectedTo = MutableStateFlow<String?>(null)
    val connectedTo: StateFlow<String?> = _connectedTo.asStateFlow()

    /** Last line received on the status characteristic, or the latest error text. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    // -------------------------------------------------------- operation queue

    private class Op(val label: String, val start: () -> Boolean)

    private val queue = ArrayDeque<Op>()
    private var busy = false

    private val watchdog = Runnable {
        Log.w(TAG, "GATT operation timed out; continuing")
        busy = false
        next()
    }

    private fun enqueue(label: String, start: () -> Boolean) {
        main.post {
            queue.addLast(Op(label, start))
            next()
        }
    }

    private fun next() {
        if (busy) return
        val op = queue.removeFirstOrNull() ?: return
        busy = true
        Log.d(TAG, "-> ${op.label}")
        if (!op.start()) {
            Log.w(TAG, "${op.label} could not be started")
            busy = false
            next()
        } else {
            main.postDelayed(watchdog, OP_TIMEOUT_MS)
        }
    }

    private fun opComplete() {
        main.post {
            main.removeCallbacks(watchdog)
            busy = false
            next()
        }
    }

    // -------------------------------------------------------------- scanning

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return

            // BUG FIX: this used to `return` when the name was null, which silently
            // hid every peripheral that omits its name from the advertisement, and
            // every peripheral the phone has never paired with (device.name is null
            // until then). Show them by address instead.
            val name = device.name
                ?: result.scanRecord?.deviceName
                ?: "(unnamed device)"

            val services = result.scanRecord?.serviceUuids
                ?.map { it.uuid.toString() }
                ?: emptyList()

            Log.d(
                TAG,
                "adv: $name / ${device.address} / ${result.rssi} dBm / " +
                    "services=${if (services.isEmpty()) "none in advertisement" else services}"
            )

            if (_devices.value.none { it.address == device.address }) {
                _devices.value = _devices.value +
                    DiscoveredDevice(name, device.address, result.rssi, services)
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            _message.value = "Scan failed (code $errorCode)"
            scanning = false
            _connState.value = ConnState.FAILED
        }
    }

    /**
     * Filters on the service UUID so only your hardware appears. If your firmware
     * does not put the service UUID in the advertisement packet, pass
     * filterByService = false and filter on [BleSpec.NAME_PREFIX] instead.
     */
    fun startScan(filterByService: Boolean = true, timeoutMs: Long = 15_000) {
        main.post {
            val s = scanner
            if (s == null || !isBluetoothOn) {
                _message.value = "Bluetooth is off"
                return@post
            }
            if (scanning) return@post

            _devices.value = emptyList()
            _connState.value = ConnState.SCANNING
            scanning = true
            lastScanWasFiltered = filterByService
            Log.d(TAG, "scan start, filtered=$filterByService")

            val filters = if (filterByService) {
                listOf(
                    ScanFilter.Builder()
                        .setServiceUuid(ParcelUuid(BleSpec.SERVICE_UUID))
                        .build()
                )
            } else {
                emptyList()
            }

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            s.startScan(filters, settings, scanCallback)
            main.postDelayed({ stopScan() }, timeoutMs)
        }
    }

    fun stopScan() {
        main.post {
            if (!scanning) return@post
            scanning = false
            runCatching { scanner?.stopScan(scanCallback) }
            if (_connState.value == ConnState.SCANNING) {
                _connState.value = ConnState.IDLE
                if (_devices.value.isEmpty()) {
                    _message.value = if (lastScanWasFiltered) {
                        "Nothing advertised service ${BleSpec.SERVICE_UUID}. " +
                            "Most firmware does not put its service UUID in the " +
                            "advertisement packet — turn on \"Show all nearby devices\"."
                    } else {
                        "No BLE advertisers at all. Check the device is powered and " +
                            "advertising, and that it is BLE — Classic modules like " +
                            "HC-05/HC-06 are invisible to BLE scans."
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ connecting

    fun connect(address: String) {
        main.post {
            // Scanning while connecting is a classic cause of status 133.
            if (scanning) {
                scanning = false
                runCatching { scanner?.stopScan(scanCallback) }
            }
            teardown(notify = false)

            val device: BluetoothDevice? =
                runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
            if (device == null) {
                _message.value = "Invalid device address"
                _connState.value = ConnState.FAILED
                return@post
            }

            _connState.value = ConnState.CONNECTING
            gatt = device.connectGatt(
                appContext,
                /* autoConnect = */ false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        }
    }

    fun disconnect() {
        main.post { teardown(notify = true) }
    }

    /** Must run on the main looper. */
    private fun teardown(notify: Boolean) {
        queue.clear()
        busy = false
        main.removeCallbacks(watchdog)
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }   // skipping close() leaks the link -> status 133
        }
        gatt = null
        controlChar = null
        statusChar = null
        pendingOnReady = null
        _connectedTo.value = null
        if (notify && _connState.value != ConnState.FAILED) {
            _connState.value = ConnState.DISCONNECTED
        }
    }

    // ---------------------------------------------------------------- sending

    /**
     * Queues a packet. If the link is not up yet the packet is held and sent as soon
     * as services are discovered, so the UI can fire a command straight after tapping
     * a device without waiting on the connection.
     */
    fun send(payload: ByteArray) {
        if (_connState.value != ConnState.READY) {
            pendingOnReady = payload
            return
        }
        writeQueued(payload)
    }

    private fun writeQueued(payload: ByteArray) {
        enqueue("write ${payload.toHex()}") {
            val g = gatt
            val c = controlChar
            if (g == null || c == null) return@enqueue false

            val writeType =
                if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, payload, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    c.writeType = writeType
                    c.value = payload
                    g.writeCharacteristic(c)
                }
            }
        }
    }

    // --------------------------------------------------------- GATT callbacks

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Connection error, status=$status")
                _message.value = "Connection failed (status $status)"
                _connState.value = ConnState.FAILED
                main.post { teardown(notify = false) }
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectedTo.value = g.device?.address
                    // The delay is deliberate: immediate discovery fails on many phones.
                    main.postDelayed(
                        { runCatching { g.discoverServices() } },
                        DISCOVERY_DELAY_MS
                    )
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connState.value = ConnState.DISCONNECTED
                    main.post { teardown(notify = false) }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(BleSpec.SERVICE_UUID)
            if (service == null) {
                fail("Service ${BleSpec.SERVICE_UUID} not found. Check BleSpec.kt.")
                return
            }
            controlChar = service.getCharacteristic(BleSpec.CONTROL_CHAR_UUID)
            statusChar = service.getCharacteristic(BleSpec.STATUS_CHAR_UUID)

            if (controlChar == null) {
                fail("Control characteristic not found. Check BleSpec.kt.")
                return
            }

            requestMtu(185)
            statusChar?.let { enableNotifications(it) }

            _connState.value = ConnState.READY
            _message.value = "Connected"

            pendingOnReady?.let {
                pendingOnReady = null
                writeQueued(it)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU = $mtu")
            opComplete()
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Write failed, status=$status")
                _message.value = "Write failed (status $status)"
            }
            opComplete()
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            d: BluetoothGattDescriptor,
            status: Int
        ) {
            opComplete()
        }

        // API 33+
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleStatus(value)
        }

        // API 32 and below
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic
        ) {
            handleStatus(c.value ?: return)
        }
    }

    private fun fail(reason: String) {
        _message.value = reason
        _connState.value = ConnState.FAILED
        main.post { teardown(notify = false) }
    }

    /** Device -> phone. Adapt to whatever your firmware notifies. */
    private fun handleStatus(bytes: ByteArray) {
        val printable = bytes.all { b ->
            val v = b.toInt() and 0xFF
            v in 0x20..0x7E || v == 0x0A || v == 0x0D
        }
        _message.value =
            if (printable) bytes.toString(Charsets.US_ASCII).trim() else bytes.toHex()
    }

    private fun requestMtu(mtu: Int) {
        enqueue("requestMtu($mtu)") {
            gatt?.requestMtu(mtu) ?: false
        }
    }

    private fun enableNotifications(c: BluetoothGattCharacteristic) {
        enqueue("enableNotify(${c.uuid})") {
            val g = gatt ?: return@enqueue false
            if (!g.setCharacteristicNotification(c, true)) return@enqueue false
            val cccd = c.getDescriptor(BleSpec.CCCD_UUID) ?: return@enqueue false
            val on = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, on) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = on
                    g.writeDescriptor(cccd)
                }
            }
        }
    }

    private fun ByteArray.toHex() = joinToString(" ") { "%02X".format(it) }

    private companion object {
        const val OP_TIMEOUT_MS = 5_000L
        const val DISCOVERY_DELAY_MS = 300L
    }
}
