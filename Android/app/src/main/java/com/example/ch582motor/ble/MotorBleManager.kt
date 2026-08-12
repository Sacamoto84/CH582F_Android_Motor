package com.example.ch582motor.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ktx.suspend
import no.nordicsemi.android.ble.observer.ConnectionObserver

/**
 * Обёртка над GATT. Nordic-библиотека сама держит очередь операций —
 * параллельно GATT дёргать нельзя, а тут это уже решено.
 *
 * Колбэки приходят не в главном потоке, поэтому наружу всё отдаётся потоками.
 */
class MotorBleManager(context: Context) : BleManager(context) {

    private var cmdChar: BluetoothGattCharacteristic? = null
    private var dataChar: BluetoothGattCharacteristic? = null

    /** Разобранные пакеты из DATA. */
    private val _packets = MutableSharedFlow<Packet>(
        replay = 0,
        extraBufferCapacity = 128,
    )
    val packets = _packets.asSharedFlow()

    private val _connection = MutableStateFlow<ConnectionPhase>(ConnectionPhase.Disconnected())
    val connection = _connection.asStateFlow()

    /** Помпу останавливали командой SLEEP — разрыв связи ожидаемый, не ошибка. */
    @Volatile
    var sleepRequested = false

    /** Пользователь нажал «Отмена» — неудача соединения не ошибка, а его решение. */
    @Volatile
    private var cancelRequested = false

    init {
        setConnectionObserver(object : ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) {
                _connection.value = ConnectionPhase.Connecting
            }

            override fun onDeviceConnected(device: BluetoothDevice) {
                _connection.value = ConnectionPhase.Initializing
            }

            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
                val cancelled = cancelRequested
                cancelRequested = false
                _connection.value = ConnectionPhase.Disconnected(
                    reason,
                    failedToConnect = true,
                    cancelled = cancelled,
                )
            }

            override fun onDeviceReady(device: BluetoothDevice) {
                _connection.value = ConnectionPhase.Ready(device)
            }

            override fun onDeviceDisconnecting(device: BluetoothDevice) {
                _connection.value = ConnectionPhase.Disconnecting
            }

            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                val expected = sleepRequested
                val cancelled = cancelRequested
                sleepRequested = false
                cancelRequested = false
                _connection.value = ConnectionPhase.Disconnected(
                    reason,
                    expected = expected,
                    cancelled = cancelled,
                )
            }
        })
    }

    override fun getMinLogPriority(): Int = Log.INFO

    override fun log(priority: Int, message: String) {
        Log.println(priority, TAG, message)
    }

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(Proto.SERVICE_UUID) ?: return false
        cmdChar = service.getCharacteristic(Proto.CHAR_CMD_UUID)
        dataChar = service.getCharacteristic(Proto.CHAR_DATA_UUID)

        val cmdWritable = cmdChar?.properties?.let {
            it and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        } == true
        val dataNotifiable = dataChar?.properties?.let {
            it and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        } == true

        return cmdWritable && dataNotifiable
    }

    override fun initialize() {
        setNotificationCallback(dataChar).with { _, data ->
            data.value?.let { raw -> Proto.parse(raw)?.let(_packets::tryEmit) }
        }
        // Без записи 0x0100 в CCCD устройство не пришлёт ничего.
        enableNotifications(dataChar).enqueue()
    }

    override fun onServicesInvalidated() {
        cmdChar = null
        dataChar = null
    }

    // ------------------------------------------------------------ операции

    suspend fun connectTo(device: BluetoothDevice) {
        connect(device)
            .useAutoConnect(false)
            .retry(3, 200)
            .timeout(20_000)
            .suspend()
    }

    suspend fun disconnectFrom() {
        disconnect().suspend()
    }

    /**
     * Прервать попытку соединения. Отмена корутины сама по себе не роняет
     * начатую операцию GATT — надо явно закрыть её через disconnect(),
     * иначе стек продолжит перебирать попытки в фоне.
     */
    suspend fun abortConnect() {
        cancelRequested = true
        runCatching { cancelQueue() }
        runCatching { disconnect().suspend() }
    }

    /** Любая запись в CMD — ровно 3 байта. */
    private suspend fun write(packet: ByteArray) {
        val target = cmdChar ?: error("характеристика CMD недоступна")
        writeCharacteristic(
            target,
            packet,
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        ).suspend()
    }

    suspend fun sendCommand(code: Int) = write(Proto.command(code))

    suspend fun sendSetParam(id: Int, value: Int) = write(Proto.setParam(id, value))

    suspend fun sendGetParam(id: Int) = write(Proto.getParam(id))

    suspend fun sendDumpAll() = write(Proto.dumpAll())

    private companion object {
        const val TAG = "MotorBle"
    }
}

/** Фаза соединения — то, что показываем в шапке. */
sealed interface ConnectionPhase {
    data object Connecting : ConnectionPhase
    data object Initializing : ConnectionPhase
    data class Ready(val device: BluetoothDevice) : ConnectionPhase
    data object Disconnecting : ConnectionPhase

    /**
     * @param expected разрыв после команды SLEEP — так и задумано
     * @param failedToConnect до соединения дело не дошло
     * @param cancelled пользователь сам прервал подключение
     */
    data class Disconnected(
        val reason: Int = ConnectionObserver.REASON_SUCCESS,
        val expected: Boolean = false,
        val failedToConnect: Boolean = false,
        val cancelled: Boolean = false,
    ) : ConnectionPhase

    val isReady: Boolean get() = this is Ready
    val isBusy: Boolean get() = this is Connecting || this is Initializing
}
