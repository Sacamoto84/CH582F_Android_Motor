package com.example.ch582motor.ble

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Всё платформенное, что нужно логике: GATT, сканер, состояние адаптера.
 *
 * Шов существует ради тестов: за интерфейсом логика не отличает настоящее
 * устройство от подделки, поэтому её можно гонять на JVM без Android
 * и без Robolectric.
 */
interface BleLink {

    /**
     * Разобранные пакеты из DATA.
     *
     * Именно [SharedFlow], а не [Flow]: [kotlinx.coroutines.flow.onSubscription]
     * объявлен только на нём, а без него ответ, пришедший синхронно внутри
     * отправки запроса, теряется — подписчик ещё не встал.
     */
    val packets: SharedFlow<Packet>

    val connection: StateFlow<ConnectionPhase>

    /** Помпу останавливали командой SLEEP — разрыв связи ожидаемый, не ошибка. */
    var sleepRequested: Boolean

    fun bluetoothEnabled(): Boolean

    fun scan(): Flow<List<FoundDevice>>

    /**
     * Адрес строкой, а не `BluetoothDevice`: этот класс в юнит-тесте не создать,
     * а реализации адреса достаточно — соединение всё равно идёт по нему.
     */
    suspend fun connectTo(address: String)

    suspend fun disconnectFrom()

    suspend fun abortConnect()

    suspend fun sendCommand(code: Int)

    suspend fun sendSetParam(id: Int, value: Int)

    suspend fun sendGetParam(id: Int)

    fun close()
}

/** Найденное сканером устройство. Чистые данные — ни одного класса Android. */
data class FoundDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
)

/** Фаза соединения — то, что показываем в шапке. */
sealed interface ConnectionPhase {
    data object Connecting : ConnectionPhase
    data object Initializing : ConnectionPhase
    data object Ready : ConnectionPhase
    data object Disconnecting : ConnectionPhase

    /**
     * @param reason код причины от Nordic; 0 (REASON_SUCCESS) — разрыв по своей воле
     * @param expected разрыв после команды SLEEP — так и задумано
     * @param failedToConnect до соединения дело не дошло
     * @param cancelled пользователь сам прервал подключение
     */
    data class Disconnected(
        val reason: Int = 0,
        val expected: Boolean = false,
        val failedToConnect: Boolean = false,
        val cancelled: Boolean = false,
    ) : ConnectionPhase

    val isReady: Boolean get() = this is Ready
    val isBusy: Boolean get() = this is Connecting || this is Initializing
}
