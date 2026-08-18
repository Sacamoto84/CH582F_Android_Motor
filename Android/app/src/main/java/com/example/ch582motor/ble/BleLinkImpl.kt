package com.example.ch582motor.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Единственная реализация [BleLink]. Своей логики не имеет — сводит вместе
 * [MotorBleManager] и [MotorScanner], чтобы наружу торчал один интерфейс.
 */
class BleLinkImpl(context: Context) : BleLink {

    private val appContext = context.applicationContext
    private val manager = MotorBleManager(appContext)

    override val packets: SharedFlow<Packet> = manager.packets
    override val connection: StateFlow<ConnectionPhase> = manager.connection

    override var sleepRequested: Boolean
        get() = manager.sleepRequested
        set(value) {
            manager.sleepRequested = value
        }

    override fun bluetoothEnabled(): Boolean = adapter()?.isEnabled == true

    override fun scan(): Flow<List<FoundDevice>> = MotorScanner.scan()

    override suspend fun connectTo(address: String) {
        val device = adapter()?.getRemoteDevice(address) ?: error("Bluetooth недоступен")
        manager.connectTo(device)
    }

    override suspend fun disconnectFrom() = manager.disconnectFrom()

    override suspend fun abortConnect() = manager.abortConnect()

    override suspend fun sendCommand(code: Int) = manager.sendCommand(code)

    override suspend fun sendSetParam(id: Int, value: Int) = manager.sendSetParam(id, value)

    override suspend fun sendGetParam(id: Int) = manager.sendGetParam(id)

    override fun close() = manager.close()

    private fun adapter(): BluetoothAdapter? {
        val bm = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return bm?.adapter
    }
}
