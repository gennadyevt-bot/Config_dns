package com.config.app

import android.content.Context
import android.content.SharedPreferences

class AutoConnectStorage(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("config_auto", Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean("auto_connect", false)
    fun setEnabled(enabled: Boolean) = prefs.edit().putBoolean("auto_connect", enabled).apply()
}
