plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("com.chaquo.python")
}

// Единственное место, где задаётся версия приложения (формат MAJOR.MINOR.PATCH).
val appVersionName = "0.0.6"
val appVersionCode = appVersionName.split(".").map { it.toInt() }.let { (major, minor, patch) ->
    major * 10000 + minor * 100 + patch
}

base {
    archivesName.set("runstef")
}

android {
    namespace = "com.example.runstef"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.runstef"
        minSdk = 28
        targetSdk = 36
        // Версия приложения ведётся в ОДНОМ месте — appVersionName ниже (вверху файла).
        // versionCode вычисляется из неё автоматически, а тег релиза на GitHub должен
        // совпадать с versionName (по тегу приложение понимает, что вышла новая версия, см.
        // network/ReleaseChecker.kt). Для релиза: поднять appVersionName, собрать, создать
        // релиз с тегом = appVersionName и приложить runstef-release.apk. Больше ничего
        // руками заполнять не нужно.
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // build_report.py (перенесённый десктопный отчёт, см. data/PythonReportBuilder.kt)
        // тянет numpy/pandas/matplotlib -- собираем только под 64-битные ABI, чтобы не
        // раздувать APK 32-битными версиями этих пакетов на современных телефонах (minSdk=28).
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // Нужно для BuildConfig.VERSION_CODE/VERSION_NAME (см. VersionCompare/ConfigRepository) -
        // AGP 8+ больше не генерирует BuildConfig по умолчанию.
        buildConfig = true
    }
}

chaquopy {
    defaultConfig {
        version = "3.13"
        buildPython("py", "-3.13")
        pip {
            // ИСПРАВЛЕНО (ревью п.16, таблица "pinned numpy/pandas/matplotlib versions"):
            // раньше версии не были зафиксированы - install("numpy") тянет ту версию, для
            // которой на момент СБОРКИ (не написания кода) есть прекомпилированное android-колесо
            // в репозитории Chaquopy (https://chaquo.com/pypi-13.1/), а не последнюю версию с
            // PyPI. Ключевая опасность: этот репозиторий со временем публикует НОВЫЕ версии
            // (в т.ч. с breaking changes в pandas/numpy API, которые использует build_report.py),
            // и пересборка через несколько месяцев без единой правки кода могла тихо подтянуть
            // другую мажорную версию и сломать отчёт. Версии ниже - единственные, для которых на
            // момент этой правки в репозитории Chaquopy есть колёса под cp313 (Python 3.13,
            // см. version выше) - при обновлении version/buildPython нужно заново свериться со
            // страницами https://chaquo.com/pypi-13.1/<пакет>/ и поднять пины осознанно, а не
            // молча ловить несовместимость на следующей сборке.
            install("numpy==1.26.2")
            install("pandas==2.1.3")
            install("matplotlib==3.8.4")
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.squareup.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.swiperefreshlayout)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
