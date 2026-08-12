package com.example.ch582motor.ble

/**
 * Таблица параметров. Диапазоны сняты с прошивки (APP/settings.c, Settings_Set),
 * дефолты — с Settings_Defaults.
 *
 * Про частоты: прошивка проверяет только нижнюю границу (919 Гц, аппаратный
 * предел делителя ШИМ), но значение едет как uint16, поэтому потолок — 65535.
 * Верхний предел железа (~234 кГц) недостижим через протокол.
 *
 * BOOT_GRACE_S убран вместе с версией настроек 8: окно держали ради отладчика,
 * а проект заливается через ISP. Освободившийся id 11 занял VBAT_MIN_MV
 * из версии 9 — отсечка по разряду.
 */
enum class ParamKind { NUMBER, SWITCH, ENUM }

data class ParamSpec(
    val id: Int,
    val key: String,
    val title: String,
    val unit: String,
    val min: Int,
    val max: Int,
    val default: Int,
    val kind: ParamKind,
    val hint: String,
    /** Подписи для [ParamKind.ENUM], индекс = значение. */
    val options: List<String> = emptyList(),
) {
    fun clamp(v: Int): Int = v.coerceIn(min, max)

    /** Как показать значение пользователю. */
    fun format(v: Int): String = when {
        kind == ParamKind.SWITCH -> if (v != 0) "включён" else "выключен"
        kind == ParamKind.ENUM -> options.getOrElse(v) { v.toString() }
        unit == "‰" -> "%.1f %%".format(v / 10.0)
        // Мультиметр меряет в вольтах, человеку понятнее «3.00 В», чем «3000»
        unit == "мВ" -> "%.2f В".format(v / 1000.0)
        else -> "$v $unit"
    }
}

object Params {

    const val PWM_MIN = 0
    const val PWM_MAX = 1
    const val PWM_FREQ_RUN = 2
    const val PWM_FREQ_BOOST = 3
    const val BOOST_EN = 4
    const val BOOST_POWER = 5
    const val BOOST_TIME = 6
    const val MAX_RUN_S = 7
    const val VBAT_SCALE_Q12 = 8
    const val VDROP_LEVEL = 9
    const val SLEEP_TOUT_S = 10
    const val VBAT_MIN_MV = 11

    const val COUNT = 12

    /** Нижняя граница частоты ШИМ — аппаратное ограничение, меньше чип не умеет. */
    const val PWM_HZ_MIN = 919

    val all: List<ParamSpec> = listOf(
        ParamSpec(
            id = PWM_MIN, key = "PWM_MIN", title = "Минимальная скважность",
            unit = "‰", min = 0, max = 1000, default = 300, kind = ParamKind.NUMBER,
            hint = "Скважность, когда ручка выкручена в минимум. Ниже порога мотор " +
                "гудит, но не крутится — поднимай, пока вращение не станет уверенным.",
        ),
        ParamSpec(
            id = PWM_MAX, key = "PWM_MAX", title = "Максимальная скважность",
            unit = "‰", min = 0, max = 1000, default = 950, kind = ParamKind.NUMBER,
            hint = "Скважность при ручке в максимуме. Прошивка сама поменяет местами " +
                "с минимумом, если перепутать.",
        ),
        ParamSpec(
            id = PWM_FREQ_RUN, key = "PWM_FREQ_RUN", title = "Рабочая частота ШИМ",
            unit = "Гц", min = PWM_HZ_MIN, max = 65535, default = 16000, kind = ParamKind.NUMBER,
            hint = "16 кГц выше слышимого — мотор не пищит. Применяется сразу, слышно " +
                "на ходу.",
        ),
        ParamSpec(
            id = PWM_FREQ_BOOST, key = "PWM_FREQ_BOOST", title = "Частота на время рывка",
            unit = "Гц", min = PWM_HZ_MIN, max = 65535, default = 1000, kind = ParamKind.NUMBER,
            hint = "Низкая частота на старте даёт больший момент, но мотор слышно.",
        ),
        ParamSpec(
            id = BOOST_EN, key = "BOOST_EN", title = "Стартовый рывок",
            unit = "", min = 0, max = 1, default = 1, kind = ParamKind.SWITCH,
            hint = "Кратковременный импульс повышенной мощности, чтобы сорвать помпу " +
                "с места и продавить столб воды.",
        ),
        ParamSpec(
            id = BOOST_POWER, key = "BOOST_POWER", title = "Мощность рывка",
            unit = "‰", min = 0, max = 1000, default = 800, kind = ParamKind.NUMBER,
            hint = "Абсолютная скважность на время рывка — не прибавка к текущей, " +
                "а именно значение.",
        ),
        ParamSpec(
            id = BOOST_TIME, key = "BOOST_TIME", title = "Длительность рывка",
            unit = "мс", min = 0, max = 5000, default = 150, kind = ParamKind.NUMBER,
            hint = "Минимальное время, за которое помпа успевает раскрутиться. " +
                "Обычно 100–200 мс.",
        ),
        ParamSpec(
            id = MAX_RUN_S, key = "MAX_RUN_S", title = "Авто-стоп",
            unit = "с", min = 0, max = 65535, default = 120, kind = ParamKind.NUMBER,
            hint = "Защита от сухого хода: помпа сама встанет через это время. " +
                "0 — без ограничения.",
        ),
        ParamSpec(
            id = VBAT_SCALE_Q12, key = "VBAT_SCALE_Q12", title = "Калибровка вольтметра",
            unit = "Q12", min = 1, max = 10000, default = 4666, kind = ParamKind.NUMBER,
            hint = "vbat_mv = ADC × scale / 4096. Заводское значение приблизительное — " +
                "лучше подобрать мастером калибровки.",
        ),
        ParamSpec(
            id = VDROP_LEVEL, key = "VDROP_LEVEL", title = "Сброс по просадке питания",
            unit = "", min = 0, max = 4, default = 4, kind = ParamKind.ENUM,
            hint = "Порог VDD33, при котором чип делает аварийный сброс.",
            options = listOf("выключено", "1.9 В", "2.1 В", "2.3 В", "2.5 В"),
        ),
        ParamSpec(
            id = SLEEP_TOUT_S, key = "SLEEP_TOUT_S", title = "Простой до ухода в сон",
            unit = "с", min = 0, max = 600, default = 60, kind = ParamKind.NUMBER,
            hint = "Считается, только когда помпа стоит и никто не подключён. " +
                "0 — не спать вообще.",
        ),
        ParamSpec(
            id = VBAT_MIN_MV, key = "VBAT_MIN_MV", title = "Отсечка по разряду",
            unit = "мВ", min = 0, max = 4200, default = 3000, kind = ParamKind.NUMBER,
            hint = "Ниже этого напряжения помпа не запустится — устройство ответит " +
                "сигналом ошибки. Сравнение идёт с напряжением покоя, уже " +
                "работающую помпу порог не глушит. 0 — защита выключена.",
        ),
    )

    private val byId = all.associateBy { it.id }

    fun spec(id: Int): ParamSpec? = byId[id]

    /** Имя для квитанции: у команд свой словарь, у параметров — таблица. */
    fun title(id: Int): String = byId[id]?.title ?: "Параметр $id"
}
