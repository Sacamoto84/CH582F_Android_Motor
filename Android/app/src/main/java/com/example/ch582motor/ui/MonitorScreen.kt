package com.example.ch582motor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ch582motor.ble.Params
import com.example.ch582motor.ble.StopReason
import com.example.ch582motor.vm.UiState

@Composable
fun MonitorScreen(
    state: UiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSave: () -> Unit,
    onSleep: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmSleep by remember { mutableStateOf(false) }
    val telemetry = state.telemetry

    val cutoffMv = state.value(Params.VBAT_MIN_MV) ?: 0
    val belowCutoff = cutoffMv > 0 && telemetry != null && telemetry.vbatMv < cutoffMv

    // Проценты и полоска считаются от калиброванного верха, а не от 4095:
    // ручка до полной шкалы АЦП не достаёт. Сырое число показываем как есть —
    // именно его переписывают в POT_RAW_MAX при калибровке.
    val potMax = (state.value(Params.POT_RAW_MAX) ?: Params.ADC_FULL_SCALE)
        .coerceAtLeast(1)

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {

                Text(
                    telemetry?.state?.title ?: "Ждём телеметрию…",
                    style = MaterialTheme.typography.headlineSmall,
                )

                // Причина отказа живёт в ОЗУ контроллера и стирается сбросом,
                // в том числе после ухода в сон. Поэтому основной признак
                // выводим сами из напряжения и порога — он верен всегда,
                // даже если stop_reason уже потерян.
                if (belowCutoff) {
                    Text(
                        "Банка разряжена: %.2f В при пороге %.2f В. Пуск заблокирован — "
                            .format(telemetry.vbatMv / 1000.0, cutoffMv / 1000.0) +
                            "зарядите аккумулятор или понизьте отсечку в настройках.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else if (telemetry?.stopReason == StopReason.LOW_BATTERY) {
                    // Напряжение уже поднялось, но последний пуск отклоняли
                    Text(
                        "Последний пуск был заблокирован отсечкой по разряду. " +
                            "Сейчас напряжение выше порога — можно пробовать снова.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                GaugeRow(
                    label = "Скважность",
                    value = telemetry?.let { "%.1f %%".format(it.pwmPermille / 10.0) } ?: "—",
                    fraction = (telemetry?.pwmPermille ?: 0) / 1000f,
                )
                GaugeRow(
                    label = "Ручка",
                    value = telemetry?.let {
                        "%d  (%.0f %%)".format(it.potRaw, it.potRaw * 100.0 / potMax)
                    } ?: "—",
                    fraction = (telemetry?.potRaw ?: 0) / potMax.toFloat(),
                )
                StatRow(
                    label = "Напряжение банки",
                    value = telemetry?.let { "%.2f В".format(it.vbatMv / 1000.0) } ?: "—",
                )
                StatRow(
                    label = "Скважность, сырое значение",
                    value = telemetry?.let { "${it.pwmPermille} ‰ / ${it.pwmPct} %" } ?: "—",
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onStart,
                enabled = state.connected,
                modifier = Modifier.weight(1f),
            ) { Text("Пуск") }
            Button(
                onClick = onStop,
                enabled = state.connected,
                modifier = Modifier.weight(1f),
            ) { Text("Стоп") }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onSave,
                enabled = state.connected,
                modifier = Modifier.weight(1f),
            ) { Text("Сохранить") }
            OutlinedButton(
                onClick = { confirmSleep = true },
                enabled = state.connected,
                modifier = Modifier.weight(1f),
            ) { Text("Усыпить") }
        }

        OutlinedButton(
            onClick = onDisconnect,
            enabled = state.connected,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Отключиться") }

        Text(
            "Настройки применяются сразу, но живут в памяти. Во flash они попадают " +
                "только по кнопке «Сохранить» — и только при остановленной помпе.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }

    if (confirmSleep) {
        AlertDialog(
            onDismissRequest = { confirmSleep = false },
            title = { Text("Усыпить устройство?") },
            text = {
                Text(
                    "Помпа остановится, чип уйдёт в глубокий сон и пропадёт из эфира. " +
                        "Разбудить можно только кнопкой на корпусе." +
                        if (state.dirty) {
                            "\n\nЕсть несохранённые изменения — после сна они пропадут. " +
                                "Сначала нажмите «Сохранить»."
                        } else {
                            ""
                        },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSleep = false
                    onSleep()
                }) { Text("Усыпить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSleep = false }) { Text("Отмена") }
            },
        )
    }
}
