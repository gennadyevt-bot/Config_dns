package com.config.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

class DomainVpnStorage(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("domain_vpn", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_DOMAINS = "domains"
        private const val KEY_ENABLED = "domain_vpn_enabled"
    }

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)
    fun setEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()

    fun getDomains(): Set<String> {
        val json = prefs.getString(KEY_DOMAINS, "[]") ?: "[]"
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

    fun setDomains(domains: Set<String>) {
        val arr = JSONArray()
        domains.forEach { arr.put(it) }
        prefs.edit().putString(KEY_DOMAINS, arr.toString()).apply()
    }

    fun addDomain(domain: String) {
        val set = getDomains().toMutableSet()
        set.add(domain.lowercase().trim())
        setDomains(set)
    }

    fun removeDomain(domain: String) {
        val set = getDomains().toMutableSet()
        set.remove(domain.lowercase().trim())
        setDomains(set)
    }

    fun containsDomain(text: String): Boolean {
        val lower = text.lowercase()
        return getDomains().any { lower.contains(it) }
    }
}
