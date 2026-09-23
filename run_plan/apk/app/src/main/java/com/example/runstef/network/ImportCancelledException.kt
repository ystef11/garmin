package com.example.runstef.network

/**
 * Общее исключение отмены для всех долгих сетевых операций приложения (импорт аналитики из
 * Garmin Connect, авто-догрузка, отправка плана в Garmin Connect/intervals.icu) — бросается,
 * когда пользователь нажал «Стоп» (см. AnalyticsImportBus.cancelRequested / ExportViewModel.
 * cancelRequested), и ловится вызывающим кодом отдельно от обычных ошибок сети, чтобы
 * залогировать "отменено пользователем", а не "ОШИБКА: ...". Лежит в этом, самом верхнем
 * network-пакете (а не в network.garmin, где раньше жил только garmin-код), т.к. используется
 * и IntervalsApi (network.IntervalsApi), и всем garmin-кодом (network.garmin.*).
 */
class ImportCancelledException : RuntimeException("Отменено пользователем")
