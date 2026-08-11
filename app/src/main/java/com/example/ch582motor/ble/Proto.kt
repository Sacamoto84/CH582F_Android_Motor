package com.example.ch582motor.ble

import java.util.UUID

/**
 * Протокол обмена с прошивкой CH582F.
 * Источник истины — APP/include/app_proto.h.
 *
 * Класс намеренно без зависимостей от Android: его можно гонять юнит-тестами.
 * Весь многобайтовый порядок — little-endian.
 */
object Proto {

    private fun sig(short: Int): UUID =
        UUID.fromString("0000%04X-0000-1000-8000-00805F9B34FB".format(short))

    /** Сервис помпы. В рекламе едет 16-битным (0xFFE0). */
    val SERVICE_UUID: UUID = sig(0xFFE0)

    /** Команды и запись параметров. Write / Write No Response. */
    val CHAR_CMD_UUID: UUID = sig(0xFFE1)

    /** Телеметрия, ответы, квитанции. Notify. */
    val CHAR_DATA_UUID: UUID = sig(0xFFE2)

    /** Имя устройства в scan response. */
    const val DEVICE_NAME = "MotorCtrl1"

    // ------------------------------------------------------------- коды op

    const val OP_GET_PARAM = 0x80
    const val OP_COMMAND = 0x81
    const val OP_DUMP_ALL = 0x82

    // ---------------------------------------------------------- типы notify

    const val NTF_TELEMETRY = 0x00
    const val NTF_PARAM = 0x01
    const val NTF_ACK = 0x02

    // --------------------------------------------------------- сборка пакетов

    /** Пакет в CMD — всегда ровно 3 байта, иначе ATT_ERR_INVALID_VALUE_SIZE. */
    private fun packet(op: Int, value: Int): ByteArray = byteArrayOf(
        (op and 0xFF).toByte(),
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
    )

    /** Записать параметр: op = id параметра (0x00..0x7F). */
    fun setParam(id: Int, value: Int): ByteArray {
        require(id in 0..0x7F) { "id параметра вне диапазона: $id" }
        require(value in 0..0xFFFF) { "значение не влезает в uint16: $value" }
        return packet(id, value)
    }

    /** Прочитать один параметр; ответ придёт в notify. */
    fun getParam(id: Int): ByteArray = packet(OP_GET_PARAM, id)

    /** Команда из [Cmd]. */
    fun command(code: Int): ByteArray = packet(OP_COMMAND, code)

    /** Выгрузить все параметры серией notify. */
    fun dumpAll(): ByteArray = packet(OP_DUMP_ALL, 0)

    // --------------------------------------------------------- разбор notify

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u8(b: ByteArray, off: Int): Int = b[off].toInt() and 0xFF

    /**
     * Разбирает входящий пакет. Возвращает null на мусоре и на пакетах
     * неизвестного типа — молча игнорировать безопаснее, чем падать.
     */
    fun parse(b: ByteArray): Packet? {
        if (b.isEmpty()) return null
        return when (u8(b, 0)) {
            NTF_TELEMETRY -> if (b.size < 10) null else Telemetry(
                state = MotorState.of(u8(b, 1)),
                stopReason = StopReason.of(u8(b, 2)),
                pwmPct = u8(b, 3),
                pwmPermille = u16(b, 4),
                potRaw = u16(b, 6),
                vbatMv = u16(b, 8),
            )

            NTF_PARAM -> if (b.size < 4) null else ParamValue(
                id = u8(b, 1),
                value = u16(b, 2),
            )

            NTF_ACK -> if (b.size < 3) null else Ack(
                code = u8(b, 1),
                ok = u8(b, 2) == 0,
            )

            else -> null
        }
    }
}

/** Команды: op = 0x81, код в младшем байте. */
object Cmd {
    const val MOTOR_STOP = 0
    const val MOTOR_START = 1
    const val SAVE = 2
    const val FACTORY_RESET = 3
    const val SLEEP = 4
    const val TELEMETRY_ON = 5
    const val TELEMETRY_OFF = 6

    fun name(code: Int): String = when (code) {
        MOTOR_STOP -> "Стоп"
        MOTOR_START -> "Пуск"
        SAVE -> "Сохранение"
        FACTORY_RESET -> "Сброс к заводским"
        SLEEP -> "Уход в сон"
        TELEMETRY_ON -> "Телеметрия вкл"
        TELEMETRY_OFF -> "Телеметрия выкл"
        else -> "Команда $code"
    }
}

/** Что приходит в DATA. */
sealed interface Packet

data class Telemetry(
    val state: MotorState,
    val stopReason: StopReason,
    val pwmPct: Int,
    val pwmPermille: Int,
    val potRaw: Int,
    val vbatMv: Int,
) : Packet

data class ParamValue(val id: Int, val value: Int) : Packet

/** [code] — код команды либо id параметра, смотря на что квитанция. */
data class Ack(val code: Int, val ok: Boolean) : Packet

enum class MotorState(val title: String) {
    STOPPED("Стоит"),
    BOOST("Стартовый рывок"),
    RUNNING("Работает"),
    UNKNOWN("—");

    companion object {
        fun of(v: Int) = when (v) {
            0 -> STOPPED
            1 -> BOOST
            2 -> RUNNING
            else -> UNKNOWN
        }
    }
}

enum class StopReason(val title: String) {
    NONE("—"),
    USER("Остановлено пользователем"),
    TIMEOUT("Сработал авто-стоп MAX_RUN_S"),
    UNKNOWN("—");

    companion object {
        fun of(v: Int) = when (v) {
            0 -> NONE
            1 -> USER
            2 -> TIMEOUT
            else -> UNKNOWN
        }
    }
}
