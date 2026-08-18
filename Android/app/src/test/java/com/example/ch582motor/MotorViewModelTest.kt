package com.example.ch582motor

import com.example.ch582motor.ble.Cmd
import com.example.ch582motor.ble.ConnectionPhase
import com.example.ch582motor.ble.FoundDevice
import com.example.ch582motor.ble.Params
import com.example.ch582motor.data.Preset
import com.example.ch582motor.vm.MotorViewModel
import com.example.ch582motor.vm.ParamChange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Логика [MotorViewModel] на JVM: за [FakeBleLink] нет ни Android, ни BLE.
 *
 * Дисциплина времени — [StandardTestDispatcher]: корутины не бегут сами,
 * их двигает `advanceUntilIdle()`. Иначе дебаунс и таймауты чтения ждали бы
 * по-настоящему.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MotorViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var ble: FakeBleLink
    private lateinit var storage: FakePresetStorage
    private lateinit var vm: MotorViewModel

    @Before
    fun setUp() {
        // viewModelScope сидит на Dispatchers.Main, которого на JVM нет
        Dispatchers.setMain(dispatcher)
        ble = FakeBleLink()
        storage = FakePresetStorage()
        vm = MotorViewModel(ble, storage)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Сообщения уходят в Channel, забирать их надо самим. Сборщик сидит на
     * [UnconfinedTestDispatcher]: на общем StandardTestDispatcher он бы отставал
     * на такт, и проверка сообщения шла бы раньше его доставки.
     */
    private fun vmTest(body: suspend TestScope.(MutableList<String>) -> Unit) =
        runTest(dispatcher) {
            val messages = mutableListOf<String>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.messages.collect { messages += it }
            }
            body(messages)
        }

    private fun found() = FoundDevice(ADDRESS, "MotorCtrl1", -50)

    /**
     * Довести до «подключено, все параметры прочитаны, расхождений нет».
     * Квитанцию на TELEMETRY_ON фейк выдаёт сам — иначе ожидание осталось бы
     * в очереди и съело первую квитанцию теста.
     */
    private fun TestScope.bringUp() {
        ble.ackInSend = { true }
        vm.connect(found())
        advanceUntilIdle()
        ble.ackInSend = null
        ble.forget()
    }

    private val state get() = vm.state.value

    // ------------------------------------------ квитанции и очередь ожиданий

    @Test
    fun `две квитанции подряд ложатся каждая на свой параметр`() = vmTest {
        bringUp()
        vm.applyParam(Params.BOOST_TIME, 200)
        vm.applyParam(Params.MAX_RUN_S, 300)
        advanceUntilIdle()

        assertEquals(
            listOf(Op.SetParam(Params.BOOST_TIME, 200), Op.SetParam(Params.MAX_RUN_S, 300)),
            ble.writes(),
        )

        // Только первая квитанция: при перевёрнутой очереди применилась бы вторая запись
        ble.ack(Params.BOOST_TIME, ok = true)
        advanceUntilIdle()
        assertEquals(200, state.params[Params.BOOST_TIME])
        assertEquals(120, state.params[Params.MAX_RUN_S])
        assertEquals(setOf(Params.MAX_RUN_S), state.pending)

        ble.ack(Params.MAX_RUN_S, ok = true)
        advanceUntilIdle()
        assertEquals(300, state.params[Params.MAX_RUN_S])
        assertTrue(state.pending.isEmpty())
    }

    @Test
    fun `отказ откатывает своё значение и не трогает соседнее`() = vmTest { messages ->
        bringUp()
        vm.applyParam(Params.BOOST_TIME, 200)
        vm.applyParam(Params.MAX_RUN_S, 300)
        advanceUntilIdle()

        ble.ack(Params.BOOST_TIME, ok = false)
        advanceUntilIdle()

        assertEquals(150, state.edited[Params.BOOST_TIME])
        assertEquals(150, state.params[Params.BOOST_TIME])
        assertEquals(300, state.edited[Params.MAX_RUN_S])
        assertEquals(setOf(Params.MAX_RUN_S), state.pending)
        assertTrue(messages.single().contains("Длительность рывка"))
    }

    @Test
    fun `квитанции на параметр 2 и на команду с кодом 2 не путаются`() = vmTest { messages ->
        bringUp()
        // id параметра PWM_FREQ_RUN и код команды SAVE — оба 2, различить их
        // можно только позицией в очереди
        assertEquals(Params.PWM_FREQ_RUN, Cmd.SAVE)

        vm.applyParam(Params.PWM_FREQ_RUN, 20_000)
        advanceUntilIdle()
        vm.save()
        advanceUntilIdle()

        ble.ack(2, ok = true)
        ble.ack(2, ok = true)
        advanceUntilIdle()

        assertEquals(20_000, state.params[Params.PWM_FREQ_RUN])
        // Разойдись квитанции наоборот — снимок saved взяли бы до записи
        assertEquals(state.params, state.saved)
        assertTrue(state.changes.isEmpty())
        assertTrue(messages.contains("Настройки записаны во flash"))
    }

    @Test
    fun `квитанция изнутри отправки команды не теряется`() = vmTest { messages ->
        bringUp()
        ble.ackInSend = { true }

        vm.save()
        advanceUntilIdle()

        // Ожидание встало в очередь до отправки, иначе квитанция пришла бы
        // в пустую очередь, а само ожидание осталось бы съедать следующую
        assertTrue(messages.contains("Настройки записаны во flash"))
        assertEquals(state.params, state.saved)
    }

    @Test
    fun `квитанция изнутри отправки записи не теряется`() = vmTest {
        bringUp()
        ble.ackInSend = { true }

        vm.applyParam(Params.BOOST_TIME, 200)
        advanceUntilIdle()

        assertEquals(200, state.params[Params.BOOST_TIME])
        assertTrue(state.pending.isEmpty())
    }

    @Test
    fun `подключение ставит ожидание TELEMETRY_ON раньше отправки`() = vmTest {
        ble.ackInSend = { true }
        vm.connect(found())
        advanceUntilIdle()
        ble.ackInSend = null

        assertEquals(listOf(Op.Connect(ADDRESS)), ble.ops.filterIsInstance<Op.Connect>())
        assertEquals(listOf(Cmd.TELEMETRY_ON), ble.commands())

        // Очередь пуста: квитанция на TELEMETRY_ON уже снята. Останься она там —
        // съела бы квитанцию следующей записи
        ble.forget()
        vm.applyParam(Params.BOOST_TIME, 200)
        advanceUntilIdle()
        ble.ack(Params.BOOST_TIME, ok = true)
        advanceUntilIdle()

        assertEquals(200, state.params[Params.BOOST_TIME])
        assertTrue(state.pending.isEmpty())
    }

    @Test
    fun `квитанция без ожидания ничего не ломает`() = vmTest { messages ->
        bringUp()
        val before = state

        ble.ack(Params.MAX_RUN_S, ok = false)
        ble.ack(Cmd.SAVE, ok = true)
        advanceUntilIdle()

        assertEquals(before, state)
        assertTrue(messages.isEmpty())
    }

    // ------------------------------------------------------- отмена и ошибки

    @Test
    fun `отмена подключения не выглядит ошибкой`() = vmTest { messages ->
        ble.connectHangs = true
        vm.connect(found())
        advanceUntilIdle()
        assertTrue(state.busy)

        vm.cancelConnect()
        advanceUntilIdle()

        assertTrue(ble.ops.contains(Op.AbortConnect))
        assertFalse(state.busy)
        // Ровно одно сообщение и то не про ошибку: CancellationException
        // не должен доезжать до catch (e: Exception)
        assertEquals(listOf("Подключение отменено"), messages)
    }

    @Test
    fun `отказ пуска объясняет отсечку по разряду`() = vmTest { messages ->
        bringUp()
        vm.start()
        advanceUntilIdle()
        ble.ack(Cmd.MOTOR_START, ok = false)
        advanceUntilIdle()

        assertTrue(messages.single().contains("отсечки"))
        assertFalse(messages.single().contains("отвергнута"))
    }

    @Test
    fun `отказ сохранения велит сначала остановить помпу`() = vmTest { messages ->
        bringUp()
        vm.save()
        advanceUntilIdle()
        ble.ack(Cmd.SAVE, ok = false)
        advanceUntilIdle()
        assertTrue(messages.single().contains("остановите помпу"))

        // У прочих команд текста своего нет — остаётся общий
        vm.stop()
        advanceUntilIdle()
        ble.ack(Cmd.MOTOR_STOP, ok = false)
        advanceUntilIdle()
        assertEquals("Стоп: команда отвергнута", messages.last())
    }

    @Test
    fun `SLEEP взводит флаг до отправки`() = vmTest {
        bringUp()
        ble.ackInSend = { true }

        vm.sleep()
        advanceUntilIdle()

        // Флаг нужен на момент отправки: разрыв может прийти раньше возврата
        val sent = ble.ops.filterIsInstance<Op.Command>().single { it.code == Cmd.SLEEP }
        assertTrue(sent.sleepRequested)
        assertTrue(ble.sleepRequested)
    }

    @Test
    fun `упавшая отправка снимает флаг SLEEP и чистит очередь`() = vmTest { messages ->
        bringUp()
        ble.sendFails = IllegalStateException("характеристика CMD недоступна")

        vm.sleep()
        advanceUntilIdle()

        assertFalse(ble.sleepRequested)
        assertEquals(listOf("характеристика CMD недоступна"), messages)

        // Ожидание снято: пришедшая позже квитанция не находит, к чему приклеиться
        ble.sendFails = null
        ble.ack(Cmd.SLEEP, ok = false)
        advanceUntilIdle()
        assertEquals(1, messages.size)
    }

    // ---------------------------------------------------- расхождения с flash

    @Test
    fun `после чтения всех параметров расхождений нет`() = vmTest {
        bringUp()
        assertTrue(state.paramsLoaded)
        assertEquals(Params.COUNT, state.params.size)
        // Снимок берётся из своих же ответов: сборщик пакетов — отдельная
        // корутина и к этому моменту может ещё не разгрести очередь
        assertEquals(Params.COUNT, state.saved.size)
        assertTrue(state.changes.isEmpty())
        assertFalse(state.dirty)
    }

    @Test
    fun `запись даёт ровно одно расхождение`() = vmTest {
        bringUp()
        vm.applyParam(Params.BOOST_TIME, 200)
        advanceUntilIdle()
        ble.ack(Params.BOOST_TIME, ok = true)
        advanceUntilIdle()

        assertEquals(listOf(ParamChange(Params.BOOST_TIME, 150, 200)), state.changes)
    }

    @Test
    fun `успешный SAVE очищает расхождения`() = vmTest {
        bringUp()
        vm.applyParam(Params.BOOST_TIME, 200)
        advanceUntilIdle()
        ble.ack(Params.BOOST_TIME, ok = true)
        advanceUntilIdle()
        assertEquals(1, state.changes.size)

        vm.save()
        advanceUntilIdle()
        ble.ack(Cmd.SAVE, ok = true)
        advanceUntilIdle()

        assertTrue(state.changes.isEmpty())
    }

    @Test
    fun `возврат значения к прежнему убирает расхождение`() = vmTest {
        bringUp()
        vm.applyParam(Params.BOOST_TIME, 200)
        advanceUntilIdle()
        ble.ack(Params.BOOST_TIME, ok = true)
        advanceUntilIdle()
        assertEquals(1, state.changes.size)

        // Старый флаг dirty так не умел: взводился на записи и не опускался
        vm.applyParam(Params.BOOST_TIME, 150)
        advanceUntilIdle()
        ble.ack(Params.BOOST_TIME, ok = true)
        advanceUntilIdle()

        assertTrue(state.changes.isEmpty())
        assertFalse(state.dirty)
    }

    // ----------------------------------------------------- чтение параметров

    @Test
    fun `параметры читаются поштучно, каждый после ответа на предыдущий`() = vmTest {
        bringUp()
        vm.refreshAll()
        advanceUntilIdle()

        val reads = ble.reads()
        assertEquals(Params.all.map { it.id }, reads.map { it.id })
        // К моменту n-го запроса пришло ровно n ответов — значит серией не шлём
        assertEquals(reads.indices.toList(), reads.map { it.answered })
    }

    @Test
    fun `молчание на запрос даёт три попытки и жалобу`() = vmTest { messages ->
        ble.silent = setOf(Params.MAX_RUN_S)
        ble.ackInSend = { true }

        vm.connect(found())
        advanceUntilIdle()

        assertEquals(3, ble.reads().count { it.id == Params.MAX_RUN_S })
        assertEquals(listOf("Не прочитаны параметры: Авто-стоп"), messages)
        // Остальные всё равно прочитаны, и снимок собран из них
        assertEquals(Params.COUNT - 1, state.params.size)
        assertEquals(Params.COUNT - 1, state.saved.size)
        assertFalse(state.paramsLoaded)
    }

    @Test
    fun `ответ, пришедший изнутри запроса, не теряется`() = vmTest { messages ->
        // Фейк отвечает, не выходя из sendGetParam. Открывайся подписка после
        // отправки — ответ ушёл бы в никуда и чтение упёрлось бы в таймаут
        vm.connect(found())
        advanceUntilIdle()

        assertTrue(state.paramsLoaded)
        assertEquals(Params.COUNT, state.params.size)
        assertTrue(messages.none { it.startsWith("Не прочитаны") })
    }

    // ------------------------------------------------------ дебаунс и запись

    @Test
    fun `правка ползунка шлётся один раз и последним значением`() = vmTest {
        bringUp()

        vm.editParam(Params.BOOST_TIME, 160)
        advanceTimeBy(100)
        vm.editParam(Params.BOOST_TIME, 170)
        advanceTimeBy(100)
        vm.editParam(Params.BOOST_TIME, 180)

        // Показано сразу, в устройство пока ничего
        assertEquals(180, state.edited[Params.BOOST_TIME])
        assertTrue(ble.writes().isEmpty())

        advanceTimeBy(399)
        assertTrue(ble.writes().isEmpty())

        advanceTimeBy(2)
        assertEquals(listOf(Op.SetParam(Params.BOOST_TIME, 180)), ble.writes())
    }

    @Test
    fun `applyParam пишет сразу и отменяет отложенную запись`() = vmTest {
        bringUp()
        vm.editParam(Params.BOOST_TIME, 160)
        advanceTimeBy(100)

        vm.applyParam(Params.BOOST_TIME, 250)
        advanceUntilIdle()

        // Дожил бы отложенный дебаунс — прилетела бы вторая запись со 160
        assertEquals(listOf(Op.SetParam(Params.BOOST_TIME, 250)), ble.writes())
    }

    @Test
    fun `запись значения, уже лежащего в устройстве, не отправляется`() = vmTest {
        bringUp()
        vm.applyParam(Params.BOOST_TIME, 150)
        advanceUntilIdle()

        assertTrue(ble.writes().isEmpty())
        assertTrue(state.pending.isEmpty())
    }

    // -------------------------------------------------------------- прочее

    @Test
    fun `разрыв связи чистит состояние, но оставляет параметры`() = vmTest { messages ->
        bringUp()
        ble.telemetry()
        vm.applyParam(Params.BOOST_TIME, 200)
        advanceUntilIdle()
        assertNotNull(state.telemetry)
        assertEquals(setOf(Params.BOOST_TIME), state.pending)
        assertEquals(Params.COUNT, state.saved.size)

        ble.phase(ConnectionPhase.Disconnected(reason = 8))
        advanceUntilIdle()

        assertNull(state.telemetry)
        assertTrue(state.pending.isEmpty())
        assertTrue(state.saved.isEmpty())
        assertFalse(state.paramsLoaded)
        // Форма не должна опустеть
        assertEquals(Params.COUNT, state.params.size)
        assertTrue(messages.contains("Связь потеряна"))
    }

    @Test
    fun `после записи границы ШИМ перечитываются обе`() = vmTest {
        bringUp()
        // Прошивка меняет границы местами, если min оказался больше max
        ble.flash[Params.PWM_MIN] = 950
        ble.flash[Params.PWM_MAX] = 990

        vm.applyParam(Params.PWM_MIN, 990)
        advanceUntilIdle()
        ble.forget()
        ble.ack(Params.PWM_MIN, ok = true)
        advanceUntilIdle()

        assertEquals(listOf(Params.PWM_MIN, Params.PWM_MAX), ble.reads().map { it.id })
        assertEquals(950, state.params[Params.PWM_MIN])
        assertEquals(990, state.params[Params.PWM_MAX])
    }

    @Test
    fun `запись верхней границы ШИМ тоже перечитывает обе`() = vmTest {
        bringUp()
        vm.applyParam(Params.PWM_MAX, 900)
        advanceUntilIdle()
        ble.forget()
        ble.ack(Params.PWM_MAX, ok = true)
        advanceUntilIdle()

        assertEquals(listOf(Params.PWM_MIN, Params.PWM_MAX), ble.reads().map { it.id })
    }

    @Test
    fun `разрыв связи снимает отложенные записи`() = vmTest {
        bringUp()
        vm.editParam(Params.BOOST_TIME, 200)
        advanceTimeBy(100)

        ble.phase(ConnectionPhase.Disconnected())
        advanceUntilIdle()

        assertTrue(ble.writes().isEmpty())
    }

    // -------------------------------------------------------------- пресеты

    @Test
    fun `пресет сохраняет все параметры и уходит в хранилище`() = vmTest {
        bringUp()
        vm.savePreset("  рабочий  ")
        advanceUntilIdle()

        val saved = storage.stored.single()
        assertEquals("рабочий", saved.name)
        assertEquals(Params.COUNT, saved.values.size)
        assertEquals(150, saved.values[Params.BOOST_TIME])
        assertEquals(storage.stored, vm.presets.value)
    }

    @Test
    fun `одноимённый пресет перезаписывается, а не дублируется`() = vmTest { messages ->
        bringUp()
        vm.savePreset("A")
        advanceUntilIdle()

        vm.applyParam(Params.BOOST_TIME, 300)
        advanceUntilIdle()
        ble.ack(Params.BOOST_TIME, ok = true)
        advanceUntilIdle()

        vm.savePreset("A")
        advanceUntilIdle()

        assertEquals(1, storage.stored.size)
        assertEquals(300, storage.stored.single().values[Params.BOOST_TIME])
        assertTrue(messages.any { it.contains("перезаписан") })
    }

    @Test
    fun `неполный набор параметров в пресет не пишется`() = vmTest { messages ->
        vm.savePreset("A")
        advanceUntilIdle()

        assertTrue(storage.stored.isEmpty())
        assertEquals(0, storage.saves)
        assertTrue(messages.single().contains("не прочитаны целиком"))
    }

    @Test
    fun `пресет заливается по одному в порядке таблицы параметров`() = vmTest {
        bringUp()
        // Порядок задан не пресетом, а таблицей: очередь ожиданий должна
        // совпадать с порядком записей
        val preset = Preset(
            "A",
            mapOf(Params.MAX_RUN_S to 300, Params.BOOST_TIME to 250),
        )

        vm.applyPreset(preset)
        advanceUntilIdle()

        assertEquals(
            listOf(Op.SetParam(Params.BOOST_TIME, 250), Op.SetParam(Params.MAX_RUN_S, 300)),
            ble.writes(),
        )
        assertEquals(250, state.edited[Params.BOOST_TIME])
    }

    @Test
    fun `удаление пресета уходит в хранилище`() = vmTest {
        bringUp()
        vm.savePreset("A")
        vm.savePreset("B")
        advanceUntilIdle()

        vm.deletePreset("A")
        advanceUntilIdle()

        assertEquals(listOf("B"), storage.stored.map { it.name })
        assertEquals(listOf("B"), vm.presets.value.map { it.name })
    }

    @Test
    fun `пресеты читаются из хранилища при создании`() = vmTest {
        val existing = Preset("старый", mapOf(Params.BOOST_TIME to 250))
        val vm2 = MotorViewModel(FakeBleLink(), FakePresetStorage(listOf(existing)))
        assertEquals(listOf(existing), vm2.presets.value)
    }

    @Test
    fun `счётчик отличий пресета считает только расходящиеся значения`() = vmTest {
        bringUp()
        val preset = Preset(
            "A",
            mapOf(Params.BOOST_TIME to 150, Params.MAX_RUN_S to 999),
        )
        assertEquals(1, vm.presetDiffCount(preset))
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}
