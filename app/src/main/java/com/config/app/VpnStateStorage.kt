package com.config.app

import android.content.Context
import android.content.SharedPreferences

class VpnStateStorage(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("vpn_state", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_WAS_CONNECTED = "was_connected"
        private const val KEY_LAST_SERVER = "last_server_id"
    }

    fun setWasConnected(connected: Boolean) {
        prefs.edit().putBoolean(KEY_WAS_CONNECTED, connected).apply()
    }

    fun wasConnected(): Boolean = prefs.getBoolean(KEY_WAS_CONNECTED, false)

    fun setLastServer(serverId: String) {
        prefs.edit().putString(KEY_LAST_SERVER, serverId).apply()
    }

    fun getLastServer(): String? = prefs.getString(KEY_LAST_SERVER, null)

    fun clear() {
        prefs.edit().clear().apply()
    }
}
