package com.example.ch582motor.data

import android.content.Context
import com.example.ch582motor.ble.Params
import org.json.JSONArray
import org.json.JSONObject

/** Именованный набор значений параметров. Ключ — id параметра. */
data class Preset(val name: String, val values: Map<Int, Int>)

/**
 * Пресеты живут в SharedPreferences одной строкой JSON.
 *
 * Ни DataStore, ни сериализация сюда не тянутся намеренно: десяток пресетов
 * по тринадцать чисел — это единицы килобайт, а org.json есть в Android
 * из коробки.
 */
class PresetStore(context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): List<Preset> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()

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

    fun save(presets: List<Preset>) {
        val array = JSONArray()
        presets.forEach { preset ->
            val values = JSONObject()
            preset.values.forEach { (id, v) -> values.put(id.toString(), v) }
            array.put(JSONObject().put(NAME, preset.name).put(VALUES, values))
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val FILE = "presets"
        const val KEY = "list"
        const val NAME = "name"
        const val VALUES = "values"
    }
}
