package com.example.runstef.data

import android.content.Context

/**
 * Локальный профиль пользователя (возраст/пол/рост/вес) — вводится один раз вручную и хранится
 * на устройстве. НУЖЕН отдельно от аналитики Гармин: в базе AnalyticsDb (см. класс) этих полей
 * нет вообще — Гармин не отдаёт ни возраст, ни пол, ни рост, ни текущий вес через тот API,
 * которым пользуется приложение (только тренировки/самочувствие/пульс). Используется, чтобы
 * подставлять эти поля в калькуляторы (см. ToolUrlBuilder.kt) вместо того, чтобы вводить их
 * заново в каждом инструменте — сами калькуляторы остаются полностью редактируемыми.
 *
 * Обычная (не зашифрованная) SharedPreferences — в отличие от SettingsStore, здесь нет токенов
 * или API-ключей, только бытовые данные профиля.
 */
class UserProfileStore(context: Context) {

    private val prefs = context.getSharedPreferences("user_profile", Context.MODE_PRIVATE)

    private val keyAge = "age"
    private val keySex = "sex" // "m" | "f"
    private val keyHeightCm = "height_cm"
    private val keyWeightKg = "weight_kg"

    fun getAge(): Int? = prefs.getInt(keyAge, -1).takeIf { it > 0 }
    fun getSex(): String? = prefs.getString(keySex, null)?.takeIf { it == "m" || it == "f" }
    fun getHeightCm(): Int? = prefs.getInt(keyHeightCm, -1).takeIf { it > 0 }
    fun getWeightKg(): Double? = prefs.getFloat(keyWeightKg, -1f).takeIf { it > 0f }?.toDouble()

    fun isEmpty(): Boolean = getAge() == null && getSex() == null && getHeightCm() == null && getWeightKg() == null

    fun save(age: Int?, sex: String?, heightCm: Int?, weightKg: Double?) {
        prefs.edit().apply {
            if (age != null && age > 0) putInt(keyAge, age) else remove(keyAge)
            if (sex == "m" || sex == "f") putString(keySex, sex) else remove(keySex)
            if (heightCm != null && heightCm > 0) putInt(keyHeightCm, heightCm) else remove(keyHeightCm)
            if (weightKg != null && weightKg > 0) putFloat(keyWeightKg, weightKg.toFloat()) else remove(keyWeightKg)
        }.apply()
    }
}
