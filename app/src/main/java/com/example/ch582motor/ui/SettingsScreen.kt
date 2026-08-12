package com.example.ch582motor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ch582motor.ble.Params
import com.example.ch582motor.vm.UiState

@Composable
fun SettingsScreen(
    state: UiState,
    onEdit: (Int, Int) -> Unit,
    onApply: (Int, Int) -> Unit,
    onRefresh: () -> Unit,
    onSave: () -> Unit,
    onFactoryReset: () -> Unit,
    onCalibrate: (realMv: Int, shownMv: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmReset by remember { mutableStateOf(false) }
    var calibrating by remember { mutableStateOf(false) }
    var frozenMv by remember { mutableIntStateOf(0) }

    LazyColumn(
        // imePadding обязателен: enableEdgeToEdge() снимает decorFitsSystemWindows,
        // окно под клавиатуру больше не ужимается, и adjustResize из манифеста
        // ничего не делает. Без него список не знает, что низ экрана занят,
        // и не может подтянуть редактируемое поле в видимую часть.
        modifier = modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = state.connected,
                    modifier = Modifier.weight(1f),
                ) { Text("Перечитать") }
                OutlinedButton(
                    onClick = onSave,
                    enabled = state.connected,
                    modifier = Modifier.weight(1f),
                ) { Text("Сохранить") }
            }
        }

        if (!state.paramsLoaded) {
            item {
                Text(
                    "Параметры ещё не выгружены.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        items(Params.all, key = { it.id }) { spec ->
            ParamEditor(
                spec = spec,
                value = state.value(spec.id),
                enabled = state.connected,
                pending = spec.id in state.pending,
                onChange = { onEdit(spec.id, it) },
                onCommit = { onApply(spec.id, it) },
            )
            if (spec.id == Params.VBAT_SCALE_Q12) {
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Мастер калибровки", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Приложение показывает напряжение банки приблизительно. " +
                                "Померьте её мультиметром, введите настоящее значение — " +
                                "масштаб пересчитается сам, и показания сойдутся.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        OutlinedButton(
                            onClick = {
                                // Напряжение скачет — фиксируем то, что видим сейчас,
                                // иначе пересчёт пойдёт по другому числу.
                                frozenMv = state.telemetry?.vbatMv ?: 0
                                calibrating = true
                            },
                            enabled = state.connected && state.telemetry != null,
                            modifier = Modifier.padding(top = 8.dp),
                        ) { Text("Откалибровать") }
                    }
                }
            }
        }

        item {
            OutlinedButton(
                onClick = { confirmReset = true },
                enabled = state.connected,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            ) { Text("Сброс к заводским") }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Сбросить настройки?") },
            text = { Text("Все 12 параметров вернутся к заводским значениям.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onFactoryReset()
                }) { Text("Сбросить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Отмена") }
            },
        )
    }

    if (calibrating) {
        CalibrationDialog(
            shownMv = frozenMv,
            currentScale = state.value(Params.VBAT_SCALE_Q12),
            onDismiss = { calibrating = false },
            onConfirm = { realMv ->
                calibrating = false
                onCalibrate(realMv, frozenMv)
            },
        )
    }
}

/**
 * Свой Dialog вместо AlertDialog: у AlertDialog содержимое не прокручивается и
 * не ужимается под клавиатуру, поэтому кнопки уезжали за нижний край экрана,
 * как только открывалась цифровая раскладка.
 */
@Composable
private fun CalibrationDialog(
    shownMv: Int,
    currentScale: Int?,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val realMv = parseMillivolts(text)
    val preview = if (realMv != null && shownMv > 0 && currentScale != null) {
        Math.round(currentScale.toDouble() * realMv / shownMv).toInt().coerceIn(1, 65535)
    } else {
        null
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().imePadding().padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                ) {
                    Text(
                        "Калибровка вольтметра",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        "1. Померьте напряжение прямо на клеммах банки мультиметром " +
                            "в режиме постоянного напряжения — получится примерно " +
                            "3.0…4.2 В.\n" +
                            "2. Введите измеренное значение ниже.\n" +
                            "3. Нажмите «Записать» — масштаб пересчитается, и показания " +
                            "приложения сойдутся с мультиметром.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 8.dp),
                    )

                    Text(
                        "Приложение показывает: %.2f В  ($shownMv мВ)".format(shownMv / 1000.0),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(
                        "Текущий масштаб: ${currentScale ?: "—"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    OutlinedTextField(
                        value = text,
                        onValueChange = { new -> text = filterVoltageInput(new) },
                        label = { Text("Реальное напряжение, В") },
                        placeholder = { Text("например 4.05") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )

                    Text(
                        when {
                            text.isBlank() -> "Введите значение с мультиметра"
                            realMv == null -> "Не похоже на напряжение банки"
                            shownMv <= 0 -> "Нет телеметрии — напряжение неизвестно"
                            currentScale == null -> "Текущий масштаб ещё не прочитан"
                            else -> "Понято как $realMv мВ · новый масштаб: $preview"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (preview != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) { Text("Отмена") }
                        TextButton(
                            onClick = { realMv?.let(onConfirm) },
                            enabled = preview != null,
                        ) { Text("Записать") }
                    }
                }
            }
        }
    }
}

/** Оставляем цифры и одну десятичную точку; запятую приводим к точке. */
private fun filterVoltageInput(raw: String): String {
    val normalized = raw.replace(',', '.')
    val sb = StringBuilder()
    var dotUsed = false
    for (ch in normalized) {
        when {
            ch.isDigit() -> sb.append(ch)
            ch == '.' && !dotUsed && sb.isNotEmpty() -> {
                dotUsed = true
                sb.append(ch)
            }
        }
    }
    return sb.toString().take(6)
}

/**
 * Мультиметр показывает вольты, но кто-то введёт и милливольты. Банка 1S — это
 * единицы вольт, поэтому всё, что не больше 100, считаем вольтами.
 * Результат в любом случае показываем пользователю в мВ.
 */
private fun parseMillivolts(raw: String): Int? {
    val v = raw.toDoubleOrNull() ?: return null
    if (v <= 0) return null
    val mv = if (v <= 100) Math.round(v * 1000).toInt() else Math.round(v).toInt()
    return if (mv in 1..20_000) mv else null
}
