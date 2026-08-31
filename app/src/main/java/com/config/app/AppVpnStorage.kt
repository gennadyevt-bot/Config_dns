package com.config.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

class AppVpnStorage(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("app_vpn", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PACKAGES = "selected_packages"
        private const val KEY_ENABLED = "app_vpn_enabled"
    }

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)
    fun setEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()

    fun getSelectedPackages(): Set<String> {
        val json = prefs.getString(KEY_PACKAGES, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            val set = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                set.add(arr.getString(i))
            }
            set
        } catch (e: Exception) {
            emptySet()
        }
    }

    fun setSelectedPackages(packages: Set<String>) {
        val arr = JSONArray()
        packages.forEach { arr.put(it) }
        prefs.edit().putString(KEY_PACKAGES, arr.toString()).apply()
    }

    fun addPackage(pkg: String) {
        val set = getSelectedPackages().toMutableSet()
        set.add(pkg)
        setSelectedPackages(set)
    }

    fun removePackage(pkg: String) {
        val set = getSelectedPackages().toMutableSet()
        set.remove(pkg)
        setSelectedPackages(set)
    }

    fun isPackageSelected(pkg: String): Boolean = getSelectedPackages().contains(pkg)
}
