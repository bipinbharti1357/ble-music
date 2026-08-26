package com.example.blemusic.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.blemusic.ble.BleSpec
import com.example.blemusic.ble.ConnState
import com.example.blemusic.ble.DiscoveredDevice

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BleMusicTheme {
                PlayerScreen()
            }
        }
    }
}

// ---------------------------------------------------------------- pre-flight

/** The permission set differs before and after Android 12. */
private fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

private fun Context.hasAllBlePermissions(): Boolean =
    requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

/**
 * Below Android 12 the OS returns zero scan results — with no error — unless
 * Location Services is switched on system-wide. This is the single most common
 * reason a scan "works" but finds nothing.
 */
private fun Context.locationServicesBlockingScan(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return false
    val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
    return !LocationManagerCompat.isLocationEnabled(lm)
}

/** Returns a human-readable blocker, or null if everything needed is in place. */
private fun Context.scanBlocker(bluetoothOn: Boolean): String? = when {
    !hasAllBlePermissions() -> "Permission not granted yet — tap Search to grant it."
    !bluetoothOn -> "Bluetooth is off."
    locationServicesBlockingScan() ->
        "Location Services is off. Android ${Build.VERSION.RELEASE} returns zero " +
            "scan results without it, even though this app never uses your location."
    else -> null
}

// -------------------------------------------------------------------- screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(vm: PlayerViewModel = viewModel()) {

    val context = LocalContext.current
    val connState by vm.connState.collectAsState()
    val devices by vm.devices.collectAsState()
    val connectedTo by vm.connectedTo.collectAsState()
    val message by vm.message.collectAsState()

    val enableBluetooth = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { vm.scan() }

    val askPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            if (vm.bluetoothEnabled) {
                vm.scan()
            } else {
                enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
        }
    }

    // Explicitly typed: without it, the `when` infers the union of its branch types
    // and this becomes () -> Any, which won't match `onScan: () -> Unit`.
    val onScanTapped: () -> Unit = {
        when {
            !context.hasAllBlePermissions() -> askPermissions.launch(requiredPermissions())
            !vm.bluetoothEnabled ->
                enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            else -> vm.scan()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Music controller") }) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            ConnectionCard(
                connState = connState,
                connectedTo = connectedTo,
                message = message,
                blocker = context.scanBlocker(vm.bluetoothEnabled),
                showAll = vm.showAllDevices,
                onShowAllChange = vm::updateShowAllDevices,
                onScan = onScanTapped,
                onDisconnect = vm::disconnect
            )

            if (connState != ConnState.READY) {
                DeviceList(devices = devices, onPick = vm::connect)
            } else {
                TrackPicker(
                    selected = vm.selectedTrack,
                    onSelect = vm::selectTrack,
                    modifier = Modifier.weight(1f)
                )

                LabelledSlider(
                    label = if (vm.durationMinutes == 0) "Play until stopped"
                            else "Play for ${vm.durationMinutes} min",
                    value = vm.durationMinutes,
                    range = 0..120,
                    onChange = vm::updateDuration
                )

                LabelledSlider(
                    label = if (vm.startDelayMinutes == 0) "Start now"
                            else "Start in ${vm.startDelayMinutes} min",
                    value = vm.startDelayMinutes,
                    range = 0..180,
                    onChange = vm::updateStartDelay
                )

                LabelledSlider(
                    label = "Volume ${vm.volume}",
                    value = vm.volume,
                    range = 0..30,
                    onChange = vm::updateVolume
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (vm.startDelayMinutes == 0) vm.playNow() else vm.playLater()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (vm.startDelayMinutes == 0) "Play" else "Schedule")
                    }
                    FilledTonalButton(onClick = vm::pause, modifier = Modifier.weight(1f)) {
                        Text("Pause")
                    }
                    OutlinedButton(onClick = vm::stop, modifier = Modifier.weight(1f)) {
                        Text("Stop")
                    }
                }

                if (vm.startDelayMinutes > 0) {
                    OutlinedButton(
                        onClick = vm::cancelScheduled,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Cancel scheduled play") }
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    connState: ConnState,
    connectedTo: String?,
    message: String?,
    blocker: String?,
    showAll: Boolean,
    onShowAllChange: (Boolean) -> Unit,
    onScan: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = when (connState) {
                    ConnState.IDLE -> "Not connected"
                    ConnState.SCANNING -> "Searching\u2026"
                    ConnState.CONNECTING -> "Connecting\u2026"
                    ConnState.READY -> "Connected to ${connectedTo ?: "device"}"
                    ConnState.DISCONNECTED -> "Disconnected"
                    ConnState.FAILED -> "Connection failed"
                },
                style = MaterialTheme.typography.titleMedium
            )

            blocker?.let {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        it,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            if (connState != ConnState.READY) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Show all nearby devices",
                            style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Ignores the service-UUID filter. Turn this on if your " +
                                "device does not advertise its service UUID.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(checked = showAll, onCheckedChange = onShowAllChange)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onScan, enabled = connState != ConnState.SCANNING) {
                    Text("Search for device")
                }
                if (connState == ConnState.READY) {
                    OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                }
            }
        }
    }
}

@Composable
private fun DeviceList(devices: List<DiscoveredDevice>, onPick: (String) -> Unit) {
    if (devices.isEmpty()) {
        Text(
            "No devices yet. Power on the speaker, then tap Search.",
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(devices, key = { it.address }) { d ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(d.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${d.address}   ${d.rssi} dBm",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (d.advertisedServices.isEmpty()) {
                        Text(
                            "No service UUID in advertisement",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text(
                            "Advertises: ${d.advertisedServices.joinToString()}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { onPick(d.address) }) { Text("Connect") }
                }
            }
        }
    }
}

@Composable
private fun TrackPicker(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Text("Song", style = MaterialTheme.typography.titleSmall)
        LazyColumn {
            items(BleSpec.TRACKS) { track ->
                val index = BleSpec.TRACKS.indexOf(track)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = index == selected,
                            onClick = { onSelect(index) }
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = index == selected,
                        onClick = { onSelect(index) }
                    )
                    Text(track.title)
                }
            }
        }
    }
}

@Composable
private fun LabelledSlider(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit
) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat()
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DeviceListPreview() {
    BleMusicTheme {
        DeviceList(
            devices = listOf(
                DiscoveredDevice(
                    "MUSIC-BOX", "AA:BB:CC:DD:EE:FF", -58,
                    listOf("0000ffe0-0000-1000-8000-00805f9b34fb")
                ),
                DiscoveredDevice("(unnamed device)", "11:22:33:44:55:66", -81)
            ),
            onPick = {}
        )
    }
}
