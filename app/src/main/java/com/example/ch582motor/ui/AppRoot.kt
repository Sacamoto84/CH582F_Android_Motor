package com.example.ch582motor.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ch582motor.ble.ConnectionPhase
import com.example.ch582motor.vm.MotorViewModel

private val TABS = listOf("Наблюдение", "Настройки", "Мастер")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: MotorViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (state.connected) "Помпа" else "Помпа — не подключена") },
                actions = {
                    if (state.connected) {
                        TextButton(onClick = vm::disconnect) { Text("Отключить") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            StatusLine(state.phase)

            if (!state.connected) {
                if (state.deviceAddress != null && !state.phase.isBusy) {
                    Card(Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "Связь с ${state.deviceName ?: state.deviceAddress} потеряна",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                "Возможно, устройство ушло в сон. Нажмите кнопку на " +
                                    "корпусе и попробуйте снова.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            TextButton(onClick = vm::reconnect) { Text("Переподключиться") }
                        }
                    }
                }
                ScanScreen(
                    state = state,
                    onScan = vm::startScan,
                    onStopScan = vm::stopScan,
                    onConnect = vm::connect,
                    bluetoothEnabled = vm::bluetoothEnabled,
                )
                return@Column
            }

            PrimaryTabRow(selectedTabIndex = tab) {
                TABS.forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(title) },
                    )
                }
            }

            when (tab) {
                0 -> MonitorScreen(
                    state = state,
                    onStart = vm::start,
                    onStop = vm::stop,
                    onSave = vm::save,
                    onSleep = vm::sleep,
                    onDisconnect = vm::disconnect,
                )

                1 -> SettingsScreen(
                    state = state,
                    onEdit = vm::editParam,
                    onApply = vm::applyParam,
                    onRefresh = vm::refreshAll,
                    onSave = vm::save,
                    onFactoryReset = vm::factoryReset,
                    onCalibrate = { realMv, shownMv -> vm.calibrateVbat(realMv, shownMv) },
                )

                2 -> WizardScreen(
                    state = state,
                    onApply = vm::applyParam,
                    onTestRun = { vm.testRun() },
                    onStop = vm::stop,
                    onSave = vm::save,
                )
            }
        }
    }
}

@Composable
private fun StatusLine(phase: ConnectionPhase) {
    val text = when (phase) {
        is ConnectionPhase.Connecting -> "Подключение…"
        is ConnectionPhase.Initializing -> "Настройка соединения…"
        is ConnectionPhase.Disconnecting -> "Отключение…"
        is ConnectionPhase.Ready -> null
        is ConnectionPhase.Disconnected -> null
    }
    if (text != null) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
