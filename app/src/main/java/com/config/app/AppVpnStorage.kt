package com.config.app

import android.content.Context
import android.content.SharedPreferences

class AppVpnStorage(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun setSelectedPackages(packages: Set<String>) {
        // HashSet() — обязательно, иначе putStringSet не видит изменения того же Set
        prefs.edit().putStringSet(SELECTED_KEY, HashSet(packages)).apply()
    }

    fun getSelectedPackages(): Set<String> {
        return HashSet(prefs.getStringSet(SELECTED_KEY, emptySet()) ?: emptySet())
    }

    fun setExcludedPackages(packages: Set<String>) {
        prefs.edit().putStringSet(EXCLUDED_KEY, HashSet(packages)).apply()
    }

    fun getExcludedPackages(): Set<String> {
        return HashSet(prefs.getStringSet(EXCLUDED_KEY, emptySet()) ?: emptySet())
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(ENABLED_KEY, enabled).apply()
    }

    fun isEnabled(): Boolean {
        return prefs.getBoolean(ENABLED_KEY, false)
    }

    fun cacheAppList(json: String) {
        prefs.edit().putString("app_list_cache", json).apply()
    }

    fun getCachedAppList(): String? {
        return prefs.getString("app_list_cache", null)
    }

    companion object {
        private const val PREFS_NAME = "app_vpn_prefs"
        private const val SELECTED_KEY = "selected_packages"
        private const val EXCLUDED_KEY = "excluded_packages"
        private const val ENABLED_KEY = "app_vpn_enabled"
    }
}