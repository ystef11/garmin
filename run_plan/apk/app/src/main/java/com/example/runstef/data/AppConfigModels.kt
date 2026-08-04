package com.example.runstef.data

import kotlinx.serialization.Serializable

/** Один пункт экрана «Главная» — калькулятор/инструмент, открываемый в WebView. */
@Serializable
data class HomeItem(
    val id: String,
    val title: String,
    val description: String,
    val icon: String,
    val url: String
)

@Serializable
data class HomeConfig(
    val title: String = "Беговые инструменты",
    val subtitle: String = "",
    val items: List<HomeItem> = emptyList()
)

@Serializable
data class UpdateInfo(
    val latestVersion: String,
    val apkUrl: String
)

/**
 * Конфиг с бэка (https://ystef11.github.io/run/android/config.json) — авторитетный состав
 * «Главной» и данные для проверки обновлений. Редактируется вручную на бэке; при добавлении
 * нового инструмента приложение подхватит его без пересборки.
 */
@Serializable
data class RemoteConfig(
    val home: HomeConfig,
    val update: UpdateInfo? = null
)

/**
 * Конфиг, зашитый в приложение (assets/app_config.json) — единственный источник «своей» версии
 * приложения (поле [version], обновляется вручную разработчиком при новой сборке) и стартовый
 * fallback для «Главной» на случай самого первого запуска без интернета.
 */
@Serializable
data class BundledConfig(
    val version: String,
    val configUrl: String,
    val home: HomeConfig
)

/** Итоговая конфигурация, которой пользуется UI — результат [ConfigRepository.getEffectiveConfig]. */
data class EffectiveConfig(
    val ownVersion: String,
    val home: HomeConfig,
    val update: UpdateInfo?
)
