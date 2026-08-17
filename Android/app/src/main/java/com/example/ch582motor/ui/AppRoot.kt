package com.example.ch582motor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ch582motor.ble.ConnectionPhase
import com.example.ch582motor.ble.Params
import com.example.ch582motor.vm.MotorViewModel
import com.example.ch582motor.vm.ParamChange

private val TABS = listOf("Наблюдение", "Настройки", "Пресеты")

/** Сколько расхождений перечислять в баннере, прежде чем свернуть в счётчик. */
private const val CHANGES_SHOWN = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: MotorViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val presets by vm.presets.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by remember { mutableIntStateOf(0) }
    var confirmDisconnect by remember { mutableStateOf(false) }

    // Прошивка ничего не сохраняет сама: уйти со связи с несохранёнными
    // изменениями значит потерять их при следующем сбросе или сне.
    val leaveOrConfirm = {
        if (state.dirty) confirmDisconnect = true else vm.disconnect()
    }

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
                        TextButton(onClick = leaveOrConfirm) { Text("Отключить") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            StatusLine(state.phase, state.busy, vm::cancelConnect)

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

            val changes = state.changes
            if (changes.isNotEmpty()) UnsavedBanner(changes, onSave = vm::save)

            when (tab) {
                0 -> MonitorScreen(
                    state = state,
                    onStart = vm::start,
                    onStop = vm::stop,
                    onSave = vm::save,
                    onSleep = vm::sleep,
                    onDisconnect = leaveOrConfirm,
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

                2 -> PresetsScreen(
                    state = state,
                    presets = presets,
                    diffCount = vm::presetDiffCount,
                    onSave = vm::savePreset,
                    onApply = vm::applyPreset,
                    onDelete = vm::deletePreset,
                )
            }
        }

        if (confirmDisconnect) {
            AlertDialog(
                onDismissRequest = { confirmDisconnect = false },
                title = { Text("Отключиться без сохранения?") },
                text = {
                    Text(
                        "Изменения уже действуют, но во flash не записаны — они " +
                            "пропадут при сбросе питания или уходе в сон.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDisconnect = false
                        vm.save()
                        vm.disconnect()
                    }) { Text("Сохранить и выйти") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        confirmDisconnect = false
                        vm.disconnect()
                    }) { Text("Выйти без сохранения") }
                },
            )
        }
    }
}

/**
 * Прошивка не сохраняет сама, поэтому напоминаем — и сразу перечисляем, что
 * именно разошлось с flash. «Есть несохранённые изменения» без списка
 * заставляет гадать, какие.
 */
@Composable
private fun UnsavedBanner(changes: List<ParamChange>, onSave: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, bottom = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Не сохранено во flash: ${changes.size}",
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton(onClick = onSave) { Text("Сохранить") }
            }

            changes.take(CHANGES_SHOWN).forEach { change ->
                val spec = Params.spec(change.id)
                Text(
                    "%s: %s → %s".format(
                        spec?.title ?: "Параметр ${change.id}",
                        spec?.format(change.from) ?: change.from.toString(),
                        spec?.format(change.to) ?: change.to.toString(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (changes.size > CHANGES_SHOWN) {
                Text(
                    "и ещё ${changes.size - CHANGES_SHOWN}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            Text(
                "Уже действуют, но пропадут при сбросе или уходе в сон",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * Полоса состояния подключения с отменой. Устройство засыпает и пропадает
 * из эфира — попытка соединиться со спящим уходит в ретраи почти на минуту,
 * и ждать её до конца бессмысленно.
 */
@Composable
private fun StatusLine(phase: ConnectionPhase, busy: Boolean, onCancel: () -> Unit) {
    val text = when {
        phase is ConnectionPhase.Ready -> null
        phase is ConnectionPhase.Connecting -> "Подключение…"
        phase is ConnectionPhase.Initializing -> "Настройка соединения…"
        phase is ConnectionPhase.Disconnecting -> "Отключение…"
        // Между нажатием и первым колбэком стека фазы ещё нет
        busy -> "Подключение…"
        else -> null
    } ?: return

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium)
        // Отключение отменять нечего — оно и так завершится
        if (phase !is ConnectionPhase.Disconnecting) {
            TextButton(onClick = onCancel) { Text("Отмена") }
        }
    }
    LinearProgressIndicator(Modifier.fillMaxWidth())
}
