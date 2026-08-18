package com.example.ch582motor.data

import android.content.Context
import com.example.ch582motor.ble.Params
import org.json.JSONArray
import org.json.JSONObject

/** Именованный набор значений параметров. Ключ — id параметра. */
data class Preset(val name: String, val values: Map<Int, Int>)

/**
 * Где лежат пресеты. Интерфейс нужен логике: в тестах его заменяет список
 * в памяти, и `MotorViewModel` перестаёт зависеть от SharedPreferences.
 */
interface PresetStorage {
    fun load(): List<Preset>
    fun save(presets: List<Preset>)
}

/**
 * Пресеты в виде одной строки JSON.
 *
 * Ни DataStore, ни сериализация сюда не тянутся намеренно: десяток пресетов
 * по тринадцать чисел — это единицы килобайт, а org.json есть в Android
 * из коробки.
 *
 * Разбор живёт отдельно от хранилища, потому что ошибаться он умеет,
 * а SharedPreferences в юнит-тест не затащить.
 */
object PresetCodec {

    fun encode(presets: List<Preset>): String {
        val array = JSONArray()
        presets.forEach { preset ->
            val values = JSONObject()
            preset.values.forEach { (id, v) -> values.put(id.toString(), v) }
            array.put(JSONObject().put(NAME, preset.name).put(VALUES, values))
        }
        return array.toString()
    }

    /**
     * Мусор и обрывки дают пустой список: терять пресеты обидно, но падать
     * при запуске из-за них — хуже.
     */
    fun decode(raw: String?): List<Preset> {
        if (raw == null) return emptyList()

        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val name = item.optString(NAME).takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val values = item.optJSONObject(VALUES) ?: JSONObject()

                Preset(
                    name = name,
                    values = values.keys().asSequence().mapNotNull { key ->
                        val id = key.toIntOrNull() ?: return@mapNotNull null
                        // Параметр мог исчезнуть из прошивки между версиями
                        if (Params.spec(id) == null) return@mapNotNull null
                        id to values.getInt(key)
                    }.toMap(),
                )
            }
        }.getOrDefault(emptyList())
    }

    private const val NAME = "name"
    private const val VALUES = "values"
}

/** Хранилище поверх SharedPreferences: вся его работа — достать и положить строку. */
class PresetStore(context: Context) : PresetStorage {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): List<Preset> = PresetCodec.decode(prefs.getString(KEY, null))

    override fun save(presets: List<Preset>) {
        prefs.edit().putString(KEY, PresetCodec.encode(presets)).apply()
    }

    private companion object {
        const val FILE = "presets"
        const val KEY = "list"
    }
}
