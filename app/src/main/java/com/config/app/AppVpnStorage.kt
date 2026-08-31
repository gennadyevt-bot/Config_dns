package com.config.app

import android.content.Context
import android.content.SharedPreferences

class AppVpnStorage(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun setSelectedPackages(packages: Set<String>) {
        prefs.edit().putStringSet(SELECTED_KEY, packages).apply()
    }

    fun getSelectedPackages(): Set<String> {
        return prefs.getStringSet(SELECTED_KEY, emptySet()) ?: emptySet()
    }

    fun setExcludedPackages(packages: Set<String>) {
        prefs.edit().putStringSet(EXCLUDED_KEY, packages).apply()
    }

    fun getExcludedPackages(): Set<String> {
        return prefs.getStringSet(EXCLUDED_KEY, emptySet()) ?: emptySet()
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(ENABLED_KEY, enabled).apply()
    }

    fun isEnabled(): Boolean {
        return prefs.getBoolean(ENABLED_KEY, false)
    }

    companion object {
        private const val PREFS_NAME = "app_vpn_prefs"
        private const val SELECTED_KEY = "selected_packages"
        private const val EXCLUDED_KEY = "excluded_packages"
        private const val ENABLED_KEY = "app_vpn_enabled"
    }
}