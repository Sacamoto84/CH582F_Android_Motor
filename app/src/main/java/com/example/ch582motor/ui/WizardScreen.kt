package com.example.ch582motor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ch582motor.ble.MotorState
import com.example.ch582motor.ble.Params
import com.example.ch582motor.vm.UiState

/**
 * Мастер подбора стартового рывка — ради него всё и затевалось.
 *
 * Шаги ровно по заданию: сначала минимальная скважность без рывка, потом
 * мощность рывка, потом его длительность.
 */
@Composable
fun WizardScreen(
    state: UiState,
    onApply: (Int, Int) -> Unit,
    onTestRun: () -> Unit,
    onStop: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by remember { mutableIntStateOf(0) }

    // Рывок мешает искать порог удержания, поэтому на первом шаге он выключен.
    LaunchedEffect(step, state.connected) {
        if (!state.connected) return@LaunchedEffect
        when (step) {
            0 -> onApply(Params.BOOST_EN, 0)
            1, 2 -> onApply(Params.BOOST_EN, 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Шаг ${step + 1} из 3", style = MaterialTheme.typography.labelLarge)

        LiveStrip(state)

        when (step) {
            0 -> StepCard(
                title = "Порог удержания",
                text = "Рывок выключен. Выкрутите ручку в минимум и поднимайте " +
                    "PWM_MIN, пока помпа не станет уверенно держать вращение " +
                    "после пуска.",
                paramId = Params.PWM_MIN,
                stepSize = 10,
                state = state,
                onApply = onApply,
            )

            1 -> StepCard(
                title = "Мощность рывка",
                text = "Рывок включён. Ручка по-прежнему в минимуме. Поднимайте " +
                    "BOOST_POWER, пока помпа не начнёт уверенно трогаться с места.",
                paramId = Params.BOOST_POWER,
                stepSize = 25,
                state = state,
                onApply = onApply,
            )

            2 -> StepCard(
                title = "Длительность рывка",
                text = "Убавляйте BOOST_TIME до минимума, при котором помпа ещё " +
                    "успевает раскрутиться. Обычно 100–200 мс.",
                paramId = Params.BOOST_TIME,
                stepSize = 10,
                state = state,
                onApply = onApply,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onTestRun,
                enabled = state.connected,
                modifier = Modifier.weight(1f),
            ) { Text("Пробный пуск") }
            OutlinedButton(
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
                onClick = { if (step > 0) step-- },
                enabled = step > 0,
                modifier = Modifier.weight(1f),
            ) { Text("Назад") }
            if (step < 2) {
                Button(
                    onClick = { step++ },
                    modifier = Modifier.weight(1f),
                ) { Text("Дальше") }
            } else {
                Button(
                    onClick = onSave,
                    enabled = state.connected,
                    modifier = Modifier.weight(1f),
                ) { Text("Сохранить") }
            }
        }
    }
}

@Composable
private fun LiveStrip(state: UiState) {
    val telemetry = state.telemetry
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            StatRow(
                "Состояние",
                telemetry?.state?.title ?: "—",
            )
            StatRow(
                "Ручка",
                telemetry?.let {
                    "%d / 4095".format(it.potRaw) +
                        if (it.potRaw > POT_MIN_TOLERANCE) "  ← не в минимуме" else ""
                } ?: "—",
            )
            StatRow(
                "Скважность",
                telemetry?.let { "%.1f %%".format(it.pwmPermille / 10.0) } ?: "—",
            )
            if (telemetry?.state == MotorState.BOOST) {
                Text(
                    "Идёт рывок",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun StepCard(
    title: String,
    text: String,
    paramId: Int,
    stepSize: Int,
    state: UiState,
    onApply: (Int, Int) -> Unit,
) {
    val spec = Params.spec(paramId) ?: return
    val value = state.value(paramId)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { value?.let { onApply(paramId, spec.clamp(it - stepSize)) } },
                    enabled = state.connected && value != null,
                    modifier = Modifier.weight(1f),
                ) { Text("−$stepSize") }
                Text(
                    value?.let(spec::format) ?: "—",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1.2f).padding(top = 8.dp),
                )
                OutlinedButton(
                    onClick = { value?.let { onApply(paramId, spec.clamp(it + stepSize)) } },
                    enabled = state.connected && value != null,
                    modifier = Modifier.weight(1f),
                ) { Text("+$stepSize") }
            }
            Text(
                "${spec.key}   ·   диапазон ${spec.min}…${spec.max}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Ручка редко садится в ноль ровно — считаем минимумом всё, что ниже. */
private const val POT_MIN_TOLERANCE = 200
