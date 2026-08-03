package com.example.runstef.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Хранит скачанные из калькулятора HTML-планы (кнопка «Скачать HTML плана») в files/plans/
 * приложения и отдаёт их как список для экрана «Мои планы». HTML выбран вместо plan.json,
 * потому что его проще показать пользователю (это готовая, оформленная страница плана),
 * а машиночитаемый план (для экспорта в Garmin/intervals.icu) извлекается из него же —
 * см. PlanHtmlParser.
 */
class PlanRepository(private val context: Context) {

    private val plansDir: File
        get() = File(context.filesDir, "plans").apply { mkdirs() }

    // Пользовательский порядок планов на вкладке «Мои планы» (задаётся долгим удержанием + перетаскиванием).
    // Хранится как список имён файлов через перевод строки в обычных (не зашифрованных) SharedPreferences —
    // это не секретные данные, только порядок отображения.
    private val orderPrefs get() = context.getSharedPreferences("plan_order", Context.MODE_PRIVATE)
    private val keyOrder = "order"

    /** Сохраняет HTML-файл плана, извлекая из него встроенный план. Возвращает сохранённый файл. */
    fun savePlan(html: String, suggestedName: String? = null): SavedPlan {
        val plan = PlanHtmlParser.extractPlan(html)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val safeName = (suggestedName ?: plan.meta.name)
            .replace(Regex("[^A-Za-zА-Яа-я0-9_ -]"), "_")
            .take(40)
            .ifBlank { "plan" }
        var file = File(plansDir, "${stamp}_$safeName.html")
        var i = 1
        while (file.exists()) {
            file = File(plansDir, "${stamp}_${safeName}_$i.html")
            i++
        }
        file.writeText(html)
        return SavedPlan(
            fileName = file.name,
            filePath = file.absolutePath,
            savedAtMillis = file.lastModified(),
            plan = plan
        )
    }

    fun listPlans(): List<SavedPlan> {
        val dir = plansDir
        val files = dir.listFiles { f -> f.isFile && f.extension == "html" } ?: emptyArray()
        val byDate = files.mapNotNull { f ->
            try {
                val plan = PlanHtmlParser.extractPlan(f.readText())
                SavedPlan(f.name, f.absolutePath, f.lastModified(), plan)
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.savedAtMillis }

        val order = getOrder()
        if (order.isEmpty()) return byDate

        val byName = byDate.associateBy { it.fileName }
        val ordered = order.mapNotNull { byName[it] }
        // Новые планы, ещё не попавшие в сохранённый порядок, показываем первыми (как раньше — по дате).
        val extra = byDate.filter { it.fileName !in order }
        return extra + ordered
    }

    /** Сохранённый пользователем порядок планов (имена файлов), либо пустой список, если порядок не задавался. */
    fun getOrder(): List<String> =
        orderPrefs.getString(keyOrder, null)?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()

    /** Сохраняет порядок планов (после перетаскивания на вкладке «Мои планы»). */
    fun saveOrder(fileNames: List<String>) {
        orderPrefs.edit().putString(keyOrder, fileNames.joinToString("\n")).apply()
    }

    fun deletePlan(filePath: String): Boolean = File(filePath).delete()

    /** Полный HTML сохранённого плана — для просмотра в WebView. */
    fun readRawHtml(filePath: String): String = File(filePath).readText()

    /**
     * Переименовывает план: обновляет meta.name во встроенном window.__PLAN__ и перезаписывает
     * тот же файл (имя файла на диске не меняется, только отображаемое название в «Моих планах»).
     */
    fun renamePlan(filePath: String, newName: String): SavedPlan {
        val file = File(filePath)
        val html = file.readText()
        val renamed = PlanHtmlParser.withPlanName(html, newName)
        file.writeText(renamed)
        val plan = PlanHtmlParser.extractPlan(renamed)
        return SavedPlan(file.name, file.absolutePath, file.lastModified(), plan)
    }

    /**
     * content://-URI для отправки файла плана через системный шаринг (Intent.ACTION_SEND) —
     * использует уже настроенный FileProvider (см. AndroidManifest.xml, res/xml/file_paths.xml:
     * files-path "plans" указывает на тот же context.filesDir/plans/, где лежат файлы планов).
     */
    fun getShareUri(filePath: String): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(filePath))
}
