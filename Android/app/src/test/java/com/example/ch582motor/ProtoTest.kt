package com.example.ch582motor

import com.example.ch582motor.ble.Ack
import com.example.ch582motor.ble.Cmd
import com.example.ch582motor.ble.MotorState
import com.example.ch582motor.ble.ParamValue
import com.example.ch582motor.ble.Params
import com.example.ch582motor.ble.Proto
import com.example.ch582motor.ble.StopReason
import com.example.ch582motor.ble.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Байты сверены с разделом 9 задания — «сначала проверить руками». */
class ProtoTest {

    private fun ByteArray.hex() = joinToString(" ") { "%02X".format(it) }

    @Test
    fun `дамп всех параметров — 82 00 00`() {
        assertEquals("82 00 00", Proto.dumpAll().hex())
    }

    @Test
    fun `телеметрия вкл — 81 05 00`() {
        assertEquals("81 05 00", Proto.command(Cmd.TELEMETRY_ON).hex())
    }

    @Test
    fun `чтение BOOST_POWER — 80 05 00`() {
        assertEquals("80 05 00", Proto.getParam(Params.BOOST_POWER).hex())
    }

    @Test
    fun `запись BOOST_POWER = 300 — 05 2C 01`() {
        assertEquals("05 2C 01", Proto.setParam(Params.BOOST_POWER, 300).hex())
    }

    @Test
    fun `значение параметра little-endian`() {
        val packet = byteArrayOf(0x01, 0x05, 0x20, 0x03)
        val parsed = Proto.parse(packet) as ParamValue
        assertEquals(Params.BOOST_POWER, parsed.id)
        assertEquals(800, parsed.value)
    }

    @Test
    fun `квитанция ok`() {
        val parsed = Proto.parse(byteArrayOf(0x02, 0x05, 0x00)) as Ack
        assertEquals(Params.BOOST_POWER, parsed.code)
        assertTrue(parsed.ok)
    }

    @Test
    fun `квитанция отказ`() {
        val parsed = Proto.parse(byteArrayOf(0x02, 0x02, 0x01)) as Ack
        assertEquals(false, parsed.ok)
    }

    @Test
    fun `телеметрия разбирается целиком`() {
        // type=0, state=2, stop_reason=1, pct=95, pm=950, pot=4095, vbat=4100
        val packet = byteArrayOf(
            0x00, 0x02, 0x01, 95,
            0xB6.toByte(), 0x03,
            0xFF.toByte(), 0x0F,
            0x04, 0x10,
        )
        val t = Proto.parse(packet) as Telemetry
        assertEquals(MotorState.RUNNING, t.state)
        assertEquals(StopReason.USER, t.stopReason)
        assertEquals(95, t.pwmPct)
        assertEquals(950, t.pwmPermille)
        assertEquals(4095, t.potRaw)
        assertEquals(4100, t.vbatMv)
    }

    @Test
    fun `отказ пуска по разряду виден в телеметрии`() {
        // type=0, state=0 стоит, stop_reason=3, остальное неважно
        val packet = byteArrayOf(0x00, 0x00, 0x03, 0, 0, 0, 0, 0, 0x9C.toByte(), 0x0A)
        val t = Proto.parse(packet) as Telemetry
        assertEquals(MotorState.STOPPED, t.state)
        assertEquals(StopReason.LOW_BATTERY, t.stopReason)
        assertEquals(2716, t.vbatMv)
    }

    @Test
    fun `порог отсечки показывается вольтами`() {
        // Разделитель дробной части берётся из локали — для сравнения нормализуем
        fun fmt(v: Int) = Params.spec(Params.VBAT_MIN_MV)!!.format(v).replace(',', '.')
        assertEquals("3.00 В", fmt(3000))
        assertEquals("3.25 В", fmt(3250))
    }

    @Test
    fun `короткие и неизвестные пакеты не валят разбор`() {
        assertNull(Proto.parse(byteArrayOf()))
        assertNull(Proto.parse(byteArrayOf(0x00, 0x01)))
        assertNull(Proto.parse(byteArrayOf(0x01, 0x05)))
        assertNull(Proto.parse(byteArrayOf(0x7F, 0x00, 0x00)))
    }

    @Test
    fun `любой пакет CMD ровно 3 байта`() {
        assertEquals(3, Proto.setParam(Params.MAX_RUN_S, 65535).size)
        assertEquals(3, Proto.command(Cmd.SLEEP).size)
        assertEquals(3, Proto.getParam(0).size)
    }

    @Test
    fun `таблица параметров совпадает с прошивкой`() {
        // v8 убрал BOOT_GRACE_S, v9 занял тот же id 11 под VBAT_MIN_MV,
        // v10 добавил POT_RAW_MAX
        assertEquals(13, Params.COUNT)
        assertEquals(Params.POT_RAW_MAX, Params.all.last().id)
        assertEquals(3000, Params.spec(Params.VBAT_MIN_MV)!!.default)
        assertEquals(4200, Params.spec(Params.VBAT_MIN_MV)!!.max)
        assertEquals(4095, Params.spec(Params.POT_RAW_MAX)!!.default)
        assertEquals(100, Params.spec(Params.POT_RAW_MAX)!!.min)
        assertEquals(Params.COUNT, Params.all.size)
        assertEquals(Params.all.indices.toList(), Params.all.map { it.id })
        // Значения из Settings_Defaults
        assertEquals(300, Params.spec(Params.PWM_MIN)!!.default)
        assertEquals(950, Params.spec(Params.PWM_MAX)!!.default)
        assertEquals(16000, Params.spec(Params.PWM_FREQ_RUN)!!.default)
        assertEquals(4666, Params.spec(Params.VBAT_SCALE_Q12)!!.default)
        // Границы из Settings_Set
        assertEquals(919, Params.spec(Params.PWM_FREQ_RUN)!!.min)
        assertEquals(5000, Params.spec(Params.BOOST_TIME)!!.max)
        assertEquals(4, Params.spec(Params.VDROP_LEVEL)!!.max)
        assertEquals(1, Params.spec(Params.VBAT_SCALE_Q12)!!.min)
    }
}
