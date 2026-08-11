package com.example.ch582motor.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.ch582motor.ble.FoundDevice
import com.example.ch582motor.util.BlePermissions
import com.example.ch582motor.vm.UiState

/**
 * Точка входа: разрешения → включённый Bluetooth → скан по UUID 0xFFE0 →
 * подключение.
 */
@Composable
fun ScanScreen(
    state: UiState,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (FoundDevice) -> Unit,
    bluetoothEnabled: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var permissionsGranted by remember { mutableStateOf(BlePermissions.granted(context)) }
    var btOn by remember { mutableStateOf(bluetoothEnabled()) }
    var locationOn by remember { mutableStateOf(BlePermissions.locationServiceEnabled(context)) }
    // Пересчитать состояние после возврата из системных настроек.
    var recheck by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        permissionsGranted = result.values.all { it }
    }

    val enableBtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { recheck++ }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) recheck++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(recheck) {
        permissionsGranted = BlePermissions.granted(context)
        btOn = bluetoothEnabled()
        locationOn = BlePermissions.locationServiceEnabled(context)
    }

    val ready = permissionsGranted && btOn && locationOn

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!permissionsGranted) {
            Blocker(
                title = "Нужны разрешения",
                text = "Без доступа к Bluetooth приложение не сможет найти помпу.",
                action = "Выдать",
                onClick = { permissionLauncher.launch(BlePermissions.required.toTypedArray()) },
            )
        }
        if (permissionsGranted && !btOn) {
            Blocker(
                title = "Bluetooth выключен",
                text = "Включите Bluetooth, чтобы искать устройство.",
                action = "Включить",
                onClick = {
                    enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                },
            )
        }
        if (permissionsGranted && btOn && !locationOn) {
            Blocker(
                title = "Выключена геолокация",
                text = "На Android 8–11 сканирование BLE не работает без включённой " +
                    "службы определения местоположения.",
                action = "Открыть настройки",
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onScan, enabled = ready && !state.scanning) {
                Text(if (state.scanning) "Идёт поиск…" else "Искать помпу")
            }
            if (state.scanning) {
                OutlinedButton(onClick = onStopScan) { Text("Стоп") }
                CircularProgressIndicator(modifier = Modifier.padding(start = 4.dp))
            }
        }

        if (state.devices.isEmpty()) {
            Text(
                if (state.scanning) {
                    "Ищем устройства с сервисом 0xFFE0…"
                } else {
                    "Устройство засыпает через 60 секунд простоя и пропадает из эфира. " +
                        "Если не находится — нажмите кнопку на корпусе и повторите поиск."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.devices, key = { it.address }) { device ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                device.name ?: "Без имени",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "${device.address}   ·   ${device.rssi} dBm",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        Button(
                            onClick = { onConnect(device) },
                            enabled = !state.busy && !state.phase.isBusy,
                        ) {
                            Text("Подключить")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Blocker(title: String, text: String, action: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = onClick, modifier = Modifier.padding(top = 4.dp)) {
                Text(action)
            }
        }
    }
}
