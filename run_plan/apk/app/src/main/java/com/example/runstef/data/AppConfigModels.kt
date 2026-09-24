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

/** Информация о доступном обновлении. Берётся из GitHub Releases (см. network/ReleaseChecker);
 * блок "update" в config.json читается только как запасной вариант для совместимости. */
@Serializable
data class UpdateInfo(
    val latestVersion: String,
    val apkUrl: String,
    // SHA-256 apk в hex (из поля digest ассета релиза) — для проверки целостности перед
    // установкой (см. ApkUpdater.downloadApk). null — проверка по хэшу пропускается, остаётся
    // проверка подписи и версии самого apk (ApkUpdater.verifyApk).
    val sha256: String? = null
) {
    /** Ключ для «Пропустить это обновление»: хэш файла (он и определяет, что это за сборка),
     * а если хэша нет — номер версии. */
    val skipKey: String get() = sha256?.takeIf { it.isNotBlank() }?.lowercase() ?: latestVersion
}

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
    // Устарело: своя версия теперь берётся из BuildConfig.VERSION_NAME (app/build.gradle.kts).
    // Поле оставлено необязательным, чтобы старый app_config.json продолжал разбираться.
    val version: String = "",
    val configUrl: String,
    val home: HomeConfig
)

/** Итоговая конфигурация, которой пользуется UI — результат [ConfigRepository.getEffectiveConfig]. */
data class EffectiveConfig(
    val ownVersion: String,
    val home: HomeConfig,
    val update: UpdateInfo?,
    /** Есть что предложить: хэш опубликованного apk отличается от хэша установленного. */
    val updateAvailable: Boolean = false
)
