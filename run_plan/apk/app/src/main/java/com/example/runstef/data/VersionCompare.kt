package com.example.runstef.data

/**
 * Сравнение версий вида "0.0.1" (произвольное число числовых компонентов через точку).
 * Нечисловые/отсутствующие компоненты считаются нулём.
 */
object VersionCompare {

    /** true, если [remoteVersion] строго новее [currentVersion]. */
    fun isNewer(remoteVersion: String, currentVersion: String): Boolean {
        val remote = parts(remoteVersion)
        val current = parts(currentVersion)
        val size = maxOf(remote.size, current.size)
        for (i in 0 until size) {
            val r = remote.getOrElse(i) { 0 }
            val c = current.getOrElse(i) { 0 }
            if (r != c) return r > c
        }
        return false
    }

    private fun parts(version: String): List<Int> =
        version.split(".").map { it.toIntOrNull() ?: 0 }
}
