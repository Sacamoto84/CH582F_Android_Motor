package com.example.ch582motor.vm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.ch582motor.ble.Ack
import com.example.ch582motor.ble.BleLink
import com.example.ch582motor.ble.BleLinkImpl
import com.example.ch582motor.ble.Cmd
import com.example.ch582motor.ble.ConnectionPhase
import com.example.ch582motor.ble.FoundDevice
import com.example.ch582motor.ble.ParamValue
import com.example.ch582motor.ble.Params
import com.example.ch582motor.ble.Telemetry
import com.example.ch582motor.data.Preset
import com.example.ch582motor.data.PresetStorage
import com.example.ch582motor.data.PresetStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class UiState(
    val phase: ConnectionPhase = ConnectionPhase.Disconnected(),
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val scanning: Boolean = false,
    val devices: List<FoundDevice> = emptyList(),
    val telemetry: Telemetry? = null,
    /** Что реально лежит в устройстве. */
    val params: Map<Int, Int> = emptyMap(),
    /** Что показывает UI: может опережать устройство, пока идёт дебаунс. */
    val edited: Map<Int, Int> = emptyMap(),
    val pending: Set<Int> = emptySet(),
    val paramsLoaded: Boolean = false,
    val busy: Boolean = false,
    /**
     * Снимок значений на момент последнего чтения или записи во flash.
     * Всё, что от него отличается, и есть несохранённые изменения —
     * прошивка сама ничего не сохраняет, только по команде SAVE.
     */
    val saved: Map<Int, Int> = emptyMap(),
) {
    fun value(id: Int): Int? = edited[id] ?: params[id]
    val connected: Boolean get() = phase.isReady

    /** Что именно разошлось с flash. Порядок как в таблице параметров. */
    val changes: List<ParamChange>
        get() = Params.all.mapNotNull { spec ->
            val now = params[spec.id] ?: return@mapNotNull null
            val was = saved[spec.id] ?: return@mapNotNull null
            if (was == now) null else ParamChange(spec.id, was, now)
        }

    val dirty: Boolean get() = changes.isNotEmpty()
}

/** Одно расхождение с flash: было `from`, стало `to`. */
data class ParamChange(val id: Int, val from: Int, val to: Int)

/** Чего ждём от следующей квитанции — иначе id параметра и код команды не различить. */
private sealed interface Expect {
    data class Param(val id: Int, val newValue: Int, val oldValue: Int?) : Expect
    data class Command(val code: Int) : Expect
}

class MotorViewModel(
    private val ble: BleLink,
    private val presetStore: PresetStorage,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private val _presets = MutableStateFlow(presetStore.load())
    val presets = _presets.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val expectations = ArrayDeque<Expect>()
    private val debounce = mutableMapOf<Int, Job>()
    private var scanJob: Job? = null
    private var connectJob: Job? = null

    init {
        viewModelScope.launch {
            ble.packets.collect { packet ->
                when (packet) {
                    is Telemetry -> _state.update { it.copy(telemetry = packet) }
                    is ParamValue -> onParamValue(packet)
                    is Ack -> onAck(packet)
                }
            }
        }
        viewModelScope.launch {
            ble.connection.collect { phase -> onPhase(phase) }
        }
    }

    // ------------------------------------------------------------ соединение

    private fun onPhase(phase: ConnectionPhase) {
        _state.update { st ->
            when (phase) {
                is ConnectionPhase.Ready -> st.copy(phase = phase)
                is ConnectionPhase.Disconnected -> st.copy(
                    phase = phase,
                    telemetry = null,
                    paramsLoaded = false,
                    pending = emptySet(),
                    saved = emptyMap(),
                )

                else -> st.copy(phase = phase)
            }
        }
        if (phase is ConnectionPhase.Disconnected) {
            expectations.clear()
            debounce.values.forEach { it.cancel() }
            debounce.clear()
            when {
                phase.cancelled -> notify("Подключение отменено")

                phase.expected ->
                    notify("Устройство ушло в сон — связь разорвана, это нормально")

                phase.failedToConnect ->
                    notify("Не удалось подключиться. Устройство могло уснуть — " +
                        "нажмите кнопку на корпусе и повторите")

                phase.reason != 0 -> notify("Связь потеряна")
            }
        }
    }

    fun bluetoothEnabled(): Boolean = ble.bluetoothEnabled()

    fun startScan() {
        if (_state.value.scanning) return
        scanJob?.cancel()
        _state.update { it.copy(scanning = true, devices = emptyList()) }
        scanJob = viewModelScope.launch {
            withTimeoutOrNull(SCAN_DURATION_MS) {
                ble.scan()
                    .catch { e ->
                        notify(e.message ?: "Ошибка сканирования")
                    }
                    .collect { list -> _state.update { it.copy(devices = list) } }
            }
            _state.update { it.copy(scanning = false) }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _state.update { it.copy(scanning = false) }
    }

    fun connect(found: FoundDevice) {
        stopScan()
        _state.update {
            it.copy(
                deviceName = found.name ?: found.address,
                deviceAddress = found.address,
                busy = true,
            )
        }
        connectJob = viewModelScope.launch {
            try {
                ble.connectTo(found.address)
                // Сначала параметры, потом телеметрия: пока она молчит, у стека
                // свободны буферы уведомлений.
                loadAllParams()
                // Ожидание кладём до отправки: квитанция может прилететь раньше,
                // чем вернётся управление из записи.
                expectations.addLast(Expect.Command(Cmd.TELEMETRY_ON))
                ble.sendCommand(Cmd.TELEMETRY_ON)
            } catch (e: CancellationException) {
                // Отмена — не ошибка, сообщение выдаст cancelConnect()
                throw e
            } catch (e: Exception) {
                notify(e.message ?: "Не удалось подключиться")
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /**
     * Прервать подключение. Устройство спит и пропало из эфира — ждать
     * до конца ретраев (до минуты) незачем.
     */
    fun cancelConnect() {
        val job = connectJob ?: return
        connectJob = null
        // Соединение уже состоялось — рвать его отменой нельзя
        if (!job.isActive) return
        job.cancel()
        viewModelScope.launch {
            ble.abortConnect()
            _state.update { it.copy(busy = false) }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            runCatching { ble.disconnectFrom() }
        }
    }

    fun reconnect() {
        val addr = _state.value.deviceAddress
        val known = _state.value.devices.firstOrNull { it.address == addr }
        if (known != null) connect(known) else startScan()
    }

    // ------------------------------------------------------------- параметры

    private fun onParamValue(p: ParamValue) {
        _state.update { st ->
            // Значение, которое пользователь крутит прямо сейчас, не перетираем.
            val edited = if (p.id in st.pending) st.edited else st.edited + (p.id to p.value)
            val params = st.params + (p.id to p.value)
            st.copy(
                params = params,
                edited = edited,
                paramsLoaded = params.size >= Params.COUNT,
            )
        }
    }

    private fun onAck(ack: Ack) {
        when (val expect = expectations.removeFirstOrNull()) {
            is Expect.Param -> {
                if (ack.ok) {
                    _state.update { st ->
                        st.copy(
                            params = st.params + (expect.id to expect.newValue),
                            pending = st.pending - expect.id,
                        )
                    }
                    // PWM_MIN и PWM_MAX прошивка может поменять местами — перечитать.
                    if (expect.id == Params.PWM_MIN || expect.id == Params.PWM_MAX) {
                        viewModelScope.launch { rereadPwmBounds() }
                    }
                } else {
                    _state.update { st ->
                        val back = expect.oldValue ?: st.params[expect.id]
                        st.copy(
                            edited = if (back != null) st.edited + (expect.id to back)
                            else st.edited - expect.id,
                            pending = st.pending - expect.id,
                        )
                    }
                    notify("${Params.title(expect.id)}: значение отвергнуто прошивкой")
                }
            }

            is Expect.Command -> {
                if (!ack.ok) {
                    // Прошивка отказывает в записи на ходу: стирание страницы
                    // заморозило бы выход ШИМ на десятки миллисекунд.
                    notify(
                        when (expect.code) {
                            Cmd.SAVE -> "Сохранение отклонено: сначала остановите помпу"
                            Cmd.MOTOR_START ->
                                "Пуск заблокирован: напряжение банки ниже порога отсечки"

                            else -> "${Cmd.name(expect.code)}: команда отвергнута"
                        },
                    )
                    return
                }
                when (expect.code) {
                    Cmd.SAVE -> {
                        _state.update { it.copy(saved = it.params) }
                        notify("Настройки записаны во flash")
                    }

                    Cmd.FACTORY_RESET -> {
                        notify("Заводские значения восстановлены")
                        viewModelScope.launch { runCatching { loadAllParams() } }
                    }
                }
            }

            null -> Unit // квитанция без ожидания — игнорируем
        }
    }

    private suspend fun rereadPwmBounds() {
        readParam(Params.PWM_MIN)
        readParam(Params.PWM_MAX)
    }

    /**
     * Прошивка на OP_DUMP_ALL выгружает все 12 параметров одним циклом
     * (Profile/motor_service.c), но буферов уведомлений у стека всего
     * BLE_BUFF_NUM, и цикл обрывается на первом отказе — до телефона доезжает
     * только начало серии. Поэтому читаем поштучно и ждём каждый ответ.
     */
    private suspend fun loadAllParams() {
        val missing = mutableListOf<Int>()
        for (spec in Params.all) {
            if (!readParam(spec.id)) missing += spec.id
        }
        // Прочитанное и есть то, что лежит во flash: точка отсчёта расхождений.
        _state.update { it.copy(saved = it.params) }
        if (missing.isNotEmpty()) {
            notify("Не прочитаны параметры: " + missing.joinToString { Params.title(it) })
        }
    }

    /**
     * Подписку на ответ открываем до отправки запроса — [ble] раздаёт пакеты
     * через SharedFlow без реплея, опоздавший подписчик ответ не увидит.
     */
    private suspend fun readParam(id: Int): Boolean {
        repeat(PARAM_READ_ATTEMPTS) {
            val got = withTimeoutOrNull(PARAM_READ_TIMEOUT_MS) {
                ble.packets
                    .onSubscription { runCatching { ble.sendGetParam(id) } }
                    .filterIsInstance<ParamValue>()
                    .first { it.id == id }
            }
            if (got != null) return true
        }
        return false
    }

    /**
     * Пользователь двигает ползунок: показываем сразу, пишем с задержкой.
     * Ресурс flash 100 000 циклов — на каждое движение слать нельзя.
     */
    fun editParam(id: Int, value: Int) {
        val spec = Params.spec(id) ?: return
        val clamped = spec.clamp(value)
        _state.update { it.copy(edited = it.edited + (id to clamped)) }

        debounce[id]?.cancel()
        debounce[id] = viewModelScope.launch {
            delay(WRITE_DEBOUNCE_MS)
            writeParam(id, clamped)
        }
    }

    /** Записать немедленно, без дебаунса (мастера, кнопка «Применить»). */
    fun applyParam(id: Int, value: Int) {
        val spec = Params.spec(id) ?: return
        val clamped = spec.clamp(value)
        debounce[id]?.cancel()
        _state.update { it.copy(edited = it.edited + (id to clamped)) }
        viewModelScope.launch { writeParam(id, clamped) }
    }

    private suspend fun writeParam(id: Int, value: Int) {
        if (!_state.value.connected) return
        val old = _state.value.params[id]
        if (old == value) return
        _state.update { it.copy(pending = it.pending + id) }
        expectations.addLast(Expect.Param(id, value, old))
        try {
            ble.sendSetParam(id, value)
        } catch (e: Exception) {
            expectations.removeLastOrNull()
            _state.update { it.copy(pending = it.pending - id) }
            notify(e.message ?: "Запись не прошла")
        }
    }

    fun refreshAll() {
        if (!_state.value.connected) return
        viewModelScope.launch {
            runCatching { loadAllParams() }
                .onFailure { notify(it.message ?: "Не удалось прочитать параметры") }
        }
    }

    // -------------------------------------------------------------- команды

    fun sendCommand(code: Int) {
        if (!_state.value.connected) return
        viewModelScope.launch {
            if (code == Cmd.SLEEP) ble.sleepRequested = true
            expectations.addLast(Expect.Command(code))
            try {
                ble.sendCommand(code)
            } catch (e: Exception) {
                expectations.removeLastOrNull()
                ble.sleepRequested = false
                notify(e.message ?: "Команда не прошла")
            }
        }
    }

    fun start() = sendCommand(Cmd.MOTOR_START)
    fun stop() = sendCommand(Cmd.MOTOR_STOP)
    fun save() = sendCommand(Cmd.SAVE)
    fun factoryReset() = sendCommand(Cmd.FACTORY_RESET)
    fun sleep() = sendCommand(Cmd.SLEEP)

    // -------------------------------------------------------------- пресеты

    /**
     * Снимок того, что сейчас показано на экране настроек. Пресет хранит
     * все параметры, включая калибровочные VBAT_SCALE_Q12 и POT_RAW_MAX —
     * так он годится и как резервная копия перед экспериментами.
     */
    fun savePreset(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return

        val st = _state.value
        val values = Params.all.mapNotNull { spec ->
            st.value(spec.id)?.let { spec.id to it }
        }.toMap()

        if (values.size < Params.COUNT) {
            notify("Параметры ещё не прочитаны целиком")
            return
        }

        val replaced = _presets.value.any { it.name == trimmed }
        val list = _presets.value.filterNot { it.name == trimmed } + Preset(trimmed, values)

        _presets.value = list
        presetStore.save(list)
        notify(if (replaced) "Пресет «$trimmed» перезаписан" else "Пресет «$trimmed» сохранён")
    }

    fun deletePreset(name: String) {
        val list = _presets.value.filterNot { it.name == name }
        _presets.value = list
        presetStore.save(list)
        notify("Пресет «$name» удалён")
    }

    /**
     * Заливаем последовательно, а не пачкой корутин: очередь ожиданий
     * квитанций должна совпадать с порядком записей, иначе отказ по одному
     * параметру откатит другой.
     */
    fun applyPreset(preset: Preset) {
        if (!_state.value.connected) return

        val values = Params.all.mapNotNull { spec ->
            preset.values[spec.id]?.let { spec.id to spec.clamp(it) }
        }
        if (values.isEmpty()) return

        // Показываем сразу, чтобы форма не отставала от заливки
        _state.update { it.copy(edited = it.edited + values) }
        values.forEach { (id, _) -> debounce.remove(id)?.cancel() }

        viewModelScope.launch {
            for ((id, v) in values) writeParam(id, v)
            notify("Пресет «${preset.name}» применён")
        }
    }

    /** Сколько параметров пресета отличается от текущих значений. */
    fun presetDiffCount(preset: Preset): Int {
        val st = _state.value
        return preset.values.count { (id, v) -> st.value(id) != v }
    }

    // ----------------------------------------------------------- калибровка

    /**
     * vbat_mv = ADC × scale / 4096, значит новый масштаб — старый, домноженный
     * на отношение реального напряжения к показанному.
     */
    /**
     * @param shownMv показание, зафиксированное в момент открытия мастера —
     *   напряжение плавает, и пересчитывать надо ровно по тому числу, которое
     *   пользователь видел, когда мерил мультиметром.
     */
    fun calibrateVbat(realMv: Int, shownMv: Int): Boolean {
        val old = _state.value.value(Params.VBAT_SCALE_Q12) ?: return false
        if (shownMv <= 0 || realMv <= 0) return false
        val spec = Params.spec(Params.VBAT_SCALE_Q12) ?: return false
        val new = spec.clamp(Math.round(old.toDouble() * realMv / shownMv).toInt())
        applyParam(Params.VBAT_SCALE_Q12, new)
        notify("Масштаб $old → $new. Нажмите «Сохранить», чтобы записать во flash")
        return true
    }

    // ---------------------------------------------------------------- прочее

    private fun notify(text: String) {
        _messages.trySend(text)
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { ble.close() }
    }

    companion object {
        private const val WRITE_DEBOUNCE_MS = 400L
        private const val SCAN_DURATION_MS = 30_000L
        private const val PARAM_READ_TIMEOUT_MS = 2_000L
        private const val PARAM_READ_ATTEMPTS = 3

        /** Единственное место, где логика встречается с платформой. */
        fun factory(context: Context): ViewModelProvider.Factory {
            val app = context.applicationContext
            return viewModelFactory {
                initializer { MotorViewModel(BleLinkImpl(app), PresetStore(app)) }
            }
        }
    }
}
