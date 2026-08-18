package com.example.ch582motor

import com.example.ch582motor.ble.Ack
import com.example.ch582motor.ble.BleLink
import com.example.ch582motor.ble.ConnectionPhase
import com.example.ch582motor.ble.FoundDevice
import com.example.ch582motor.ble.MotorState
import com.example.ch582motor.ble.Packet
import com.example.ch582motor.ble.ParamValue
import com.example.ch582motor.ble.Params
import com.example.ch582motor.ble.StopReason
import com.example.ch582motor.ble.Telemetry
import com.example.ch582motor.data.Preset
import com.example.ch582motor.data.PresetStorage
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.yield

/**
 * Что прошло через шов, по порядку. Очередь ожиданий квитанций держится
 * именно на порядке, поэтому лог упорядоченный, а не множество.
 */
sealed interface Op {
    data class Connect(val address: String) : Op
    data object Disconnect : Op
    data object AbortConnect : Op

    /** @param sleepRequested флаг на момент отправки, а не после неё */
    data class Command(val code: Int, val sleepRequested: Boolean = false) : Op

    data class SetParam(val id: Int, val value: Int) : Op

    /** @param answered сколько ответов фейк уже отдал — видно, читают ли поштучно */
    data class GetParam(val id: Int, val answered: Int = 0) : Op
}

/**
 * Поддельный BLE. Отвечает синхронно, не выходя из отправки, — так проверяется,
 * что подписка и ожидание встают в очередь раньше записи в характеристику.
 */
class FakeBleLink : BleLink {

    private val _packets = MutableSharedFlow<Packet>(extraBufferCapacity = 128)
    override val packets: SharedFlow<Packet> = _packets.asSharedFlow()

    private val _connection = MutableStateFlow<ConnectionPhase>(ConnectionPhase.Disconnected())
    override val connection: StateFlow<ConnectionPhase> = _connection.asStateFlow()

    override var sleepRequested = false

    val ops = mutableListOf<Op>()

    /** Что «лежит в устройстве»: этим фейк отвечает на запрос чтения. */
    val flash: MutableMap<Int, Int> = Params.all.associate { it.id to it.default }.toMutableMap()

    /** Параметры, на запрос которых фейк молчит — для проверки повторов. */
    var silent: Set<Int> = emptySet()

    /** Подключение зависает: устройство спит, отвечать нечем. */
    var connectHangs = false

    /** Отправка бросает это вместо работы. */
    var sendFails: Exception? = null

    /**
     * Чем прошивка отвечает на команду или запись, не дожидаясь возврата
     * из отправки: null — молчит, тест пришлёт квитанцию сам.
     *
     * Квитанция не просто кладётся в поток — ей дают дойти до разбора, пока
     * отправка ещё не вернула управление. На устройстве так и бывает: запись
     * в характеристику ждёт подтверждения стека, и уведомление успевает
     * прийти раньше. Ожидание, положенное в очередь после отправки, такую
     * квитанцию упустит.
     */
    var ackInSend: ((code: Int) -> Boolean?)? = null

    var bluetoothOn = true
    var scanResults: List<List<FoundDevice>> = emptyList()
    var closed = false

    private var answered = 0

    override fun bluetoothEnabled(): Boolean = bluetoothOn

    override fun scan(): Flow<List<FoundDevice>> = scanResults.asFlow()

    override suspend fun connectTo(address: String) {
        ops += Op.Connect(address)
        if (connectHangs) awaitCancellation()
        sendFails?.let { throw it }
        // Промежуточные фазы пропущены: логике между Connecting и Ready делать нечего.
        _connection.value = ConnectionPhase.Ready
    }

    override suspend fun disconnectFrom() {
        ops += Op.Disconnect
        _connection.value = ConnectionPhase.Disconnected()
    }

    override suspend fun abortConnect() {
        ops += Op.AbortConnect
        _connection.value = ConnectionPhase.Disconnected(cancelled = true)
    }

    override suspend fun sendCommand(code: Int) {
        ops += Op.Command(code, sleepRequested)
        sendFails?.let { throw it }
        ackInSend?.invoke(code)?.let { answerNow(Ack(code, it)) }
    }

    override suspend fun sendSetParam(id: Int, value: Int) {
        ops += Op.SetParam(id, value)
        sendFails?.let { throw it }
        ackInSend?.invoke(id)?.let { answerNow(Ack(id, it)) }
    }

    override suspend fun sendGetParam(id: Int) {
        ops += Op.GetParam(id, answered)
        sendFails?.let { throw it }
        if (id in silent) return
        flash[id]?.let {
            emit(ParamValue(id, it))
            answered++
        }
    }

    override fun close() {
        closed = true
    }

    // ------------------------------------------------------- управление извне

    suspend fun emit(packet: Packet) = _packets.emit(packet)

    /** Отдать пакет и уступить очередь, чтобы его разобрали до возврата отсюда. */
    private suspend fun answerNow(packet: Packet) {
        _packets.emit(packet)
        yield()
    }

    /** Квитанция «снаружи»: прошивка ответила уже после возврата из отправки. */
    suspend fun ack(code: Int, ok: Boolean) = emit(Ack(code, ok))

    suspend fun telemetry(pwmPct: Int = 50) = emit(
        Telemetry(
            state = MotorState.RUNNING,
            stopReason = StopReason.NONE,
            pwmPct = pwmPct,
            pwmPermille = pwmPct * 10,
            potRaw = 2000,
            vbatMv = 3900,
        ),
    )

    fun phase(phase: ConnectionPhase) {
        _connection.value = phase
    }

    /** Забыть предысторию, чтобы тест смотрел только на свои операции. */
    fun forget() {
        ops.clear()
        answered = 0
    }

    fun commands(): List<Int> = ops.filterIsInstance<Op.Command>().map { it.code }

    fun writes(): List<Op.SetParam> = ops.filterIsInstance<Op.SetParam>()

    fun reads(): List<Op.GetParam> = ops.filterIsInstance<Op.GetParam>()
}

/** Пресеты в памяти: тот же контракт, что у PresetStore, без SharedPreferences. */
class FakePresetStorage(initial: List<Preset> = emptyList()) : PresetStorage {

    var stored: List<Preset> = initial
        private set

    var saves = 0
        private set

    override fun load(): List<Preset> = stored

    override fun save(presets: List<Preset>) {
        stored = presets
        saves++
    }
}
