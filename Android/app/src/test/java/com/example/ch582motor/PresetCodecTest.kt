package com.example.ch582motor

import com.example.ch582motor.ble.Params
import com.example.ch582motor.data.Preset
import com.example.ch582motor.data.PresetCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Разбор пресетов: то, что переживает перезапуск приложения и смену прошивки. */
class PresetCodecTest {

    private fun full(vararg over: Pair<Int, Int>) =
        Params.all.associate { it.id to it.default } + over.toMap()

    @Test
    fun `пресеты переживают круг туда-обратно`() {
        val list = listOf(
            Preset("рабочий", full(Params.BOOST_TIME to 250)),
            Preset("тихий", full(Params.PWM_FREQ_RUN to 20_000, Params.BOOST_EN to 0)),
        )
        assertEquals(list, PresetCodec.decode(PresetCodec.encode(list)))
    }

    @Test
    fun `имя с кавычками и переводом строки не рвёт JSON`() {
        val list = listOf(Preset("""кран "боковой"	 и\или второй""", full()))
        assertEquals(list, PresetCodec.decode(PresetCodec.encode(list)))
    }

    @Test
    fun `параметр, которого больше нет в таблице, пропускается`() {
        // id 200 в прошивке не существует: так выглядит пресет из будущей версии
        val raw = """[{"name":"старый","values":{"6":250,"200":42,"нехочу":1}}]"""
        val preset = PresetCodec.decode(raw).single()

        assertEquals("старый", preset.name)
        assertEquals(mapOf(Params.BOOST_TIME to 250), preset.values)
    }

    @Test
    fun `пресет без имени выбрасывается, соседи остаются`() {
        val raw = """[{"values":{"6":250}},{"name":"живой","values":{"6":251}}]"""
        assertEquals(listOf("живой"), PresetCodec.decode(raw).map { it.name })
    }

    @Test
    fun `мусор и пустота дают пустой список, а не падение`() {
        assertTrue(PresetCodec.decode(null).isEmpty())
        assertTrue(PresetCodec.decode("").isEmpty())
        assertTrue(PresetCodec.decode("не json").isEmpty())
        assertTrue(PresetCodec.decode("""{"name":"объект вместо массива"}""").isEmpty())
        assertTrue(PresetCodec.decode("""[{"name":"без значений"}]""").single().values.isEmpty())
    }

    @Test
    fun `пустой список кодируется в пустой массив`() {
        assertEquals("[]", PresetCodec.encode(emptyList()))
        assertTrue(PresetCodec.decode("[]").isEmpty())
    }
}
