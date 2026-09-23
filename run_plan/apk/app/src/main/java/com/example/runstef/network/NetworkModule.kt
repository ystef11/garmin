package com.example.runstef.network

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Общий OkHttpClient для всего приложения.
 *
 * Раньше каждый сетевой класс (GarminAuth, IntervalsApi, ConfigRepository, ApkUpdater,
 * OfflineCacheWebViewClient, PlanUrlLoader) создавал собственный OkHttpClient.Builder().build() —
 * у каждого свой пул соединений и свой пул потоков диспетчера, из-за чего keep-alive/connection
 * pooling между последовательными вызовами разных классов не работал, а потоки создавались и
 * уничтожались отдельно для каждого клиента.
 *
 * [baseClient] хранит один общий Dispatcher и ConnectionPool на всё приложение. Классам,
 * которым нужны нестандартные таймауты, следует брать `NetworkModule.baseClient.newBuilder()
 * .connectTimeout(...).readTimeout(...).build()` — клиент, полученный через newBuilder(),
 * переиспользует пул соединений и диспетчер родителя, так что общий пул сохраняется даже при
 * разных таймаутах у разных клиентов.
 */
object NetworkModule {
    val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .dispatcher(Dispatcher())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
}
