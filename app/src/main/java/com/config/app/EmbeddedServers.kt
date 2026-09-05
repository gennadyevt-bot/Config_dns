package com.config.app

import android.content.Context

// Встроенные (зашитые) read-only серверы: лежат в assets как обычные .conf,
// отображаются как "Profile 1/2/3". Доступны и в главном списке, и в App VPN.
object EmbeddedServers {

    private val builtin = listOf(
        "warp1.conf" to "Profile 1",
        "warp2.conf" to "Profile 2",
        "warp3.conf" to "Profile 3"
    )

    fun load(context: Context): List<ServerInfo> {
        return builtin.mapIndexed { index, (assetName, displayName) ->
            try {
                val text = context.assets.open(assetName).bufferedReader().use { it.readText() }
                WgConfigParser.parse(text)?.copy(id = "emb_$index", name = displayName)
            } catch (e: Exception) {
                null
            }
        }.filterNotNull()
    }

    // Полный список для выбора: встроенные + пользовательские
    fun all(context: Context): List<ServerInfo> = load(context) + ServerStorage(context).loadServers()
}
