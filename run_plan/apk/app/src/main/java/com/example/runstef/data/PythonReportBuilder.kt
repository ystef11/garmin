package com.example.runstef.data

import android.content.Context
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

/**
 * Запускает десктопный build_report.py (см. garmin/run_plan/build_report.py, скопирован БЕЗ
 * ИЗМЕНЕНИЙ в app/src/main/python/build_report.py) прямо на устройстве через Chaquopy -- тот же
 * numpy/pandas/matplotlib расчёт и те же PNG-графики (base64 внутри HTML), что и в десктопном
 * отчёте, вместо частичного Kotlin-аналога, который раньше строил тот же отчёт прямо на
 * устройстве (AnalyticsReportBuilder.kt и связанные файлы — удалены как мёртвый код после
 * перехода на этот класс).
 *
 * Схема on-device БД (см. AnalyticsDb, DB_VERSION=6 "полный репаритет схемы с десктопом")
 * совместима с тем, что читает build_report.py -- отдельного экспорта/конвертации не нужно,
 * передаём путь к файлу БД аккаунта как есть.
 */
object PythonReportBuilder {

    @Volatile
    private var started = false

    private fun ensureStarted(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context.applicationContext))
            }
            started = true
        }
    }

    /**
     * Синхронно строит HTML-отчёт (build_report.main) из файла БД [dbFile] в [outFile].
     * Вызывать ТОЛЬКО из фонового потока (Dispatchers.IO) -- расчёт и рендер графиков занимают
     * заметное время (десктопная версия на полной истории тренировок идёт секунды-десятки
     * секунд, на телефоне может быть медленнее).
     */
    fun build(context: Context, dbFile: File, outFile: File) {
        require(dbFile.exists()) { "файл БД не найден: ${dbFile.absolutePath}" }
        ensureStarted(context)
        outFile.parentFile?.mkdirs()
        val mplConfigDir = File(context.cacheDir, "mplconfig").apply { mkdirs() }
        val py = Python.getInstance()
        try {
            py.getModule("report_runner").callAttr(
                "run",
                dbFile.absolutePath,
                outFile.absolutePath,
                null,
                mplConfigDir.absolutePath
            )
        } catch (e: PyException) {
            throw RuntimeException("build_report.py: ${e.message}", e)
        }
    }
}
