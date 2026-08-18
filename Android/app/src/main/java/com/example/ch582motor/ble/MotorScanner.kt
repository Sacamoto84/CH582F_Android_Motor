package com.example.ch582motor.ble

import android.os.ParcelUuid
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat
import no.nordicsemi.android.support.v18.scanner.ScanCallback
import no.nordicsemi.android.support.v18.scanner.ScanFilter
import no.nordicsemi.android.support.v18.scanner.ScanResult
import no.nordicsemi.android.support.v18.scanner.ScanSettings

/**
 * Сканирование по UUID сервиса.
 *
 * Имя `MotorCtrl1` лежит в scan response, а не в рекламном пакете, поэтому
 * фильтруем по 0xFFE0, а имя достаём из scanRecord — там оно не требует
 * BLUETOOTH_CONNECT, в отличие от BluetoothDevice.name.
 */
object MotorScanner {

    /** Испустит наружу ошибку сканирования, чтобы UI мог её показать. */
    class ScanFailedException(val errorCode: Int) :
        RuntimeException("сканирование не запустилось, код $errorCode")

    fun scan(): Flow<List<FoundDevice>> = callbackFlow {
        val found = LinkedHashMap<String, FoundDevice>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (add(result)) trySend(found.values.toList())
            }

            override fun onBatchScanResults(results: List<ScanResult>) {
                var changed = false
                results.forEach { if (add(it)) changed = true }
                if (changed) trySend(found.values.toList())
            }

            override fun onScanFailed(errorCode: Int) {
                close(ScanFailedException(errorCode))
            }

            private fun add(result: ScanResult): Boolean {
                val address = result.device.address
                val entry = FoundDevice(
                    address = address,
                    name = result.scanRecord?.deviceName,
                    rssi = result.rssi,
                )
                val old = found.put(address, entry)
                return old == null || old.rssi != entry.rssi || old.name != entry.name
            }
        }

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(Proto.SERVICE_UUID))
                .build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .setUseHardwareFilteringIfSupported(true)
            .build()

        val scanner = BluetoothLeScannerCompat.getScanner()
        scanner.startScan(filters, settings, callback)

        awaitClose { runCatching { scanner.stopScan(callback) } }
    }
}
