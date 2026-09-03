package com.config.app

/**
 * Автоочистка WireGuard/AmneziaWG конфигов от llimonix и других генераторов.
 * Убирает IPv6, добавляет /32 к Address, чистит DNS и AllowedIPs.
 */
object ConfigSanitizer {

    fun sanitize(rawConfig: String): String {
        val lines = rawConfig.lines()
        val result = mutableListOf<String>()
        var inInterface = false
        var inPeer = false

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.equals("[Interface]", ignoreCase = true) -> {
                    inInterface = true
                    inPeer = false
                    result.add(line)
                }
                trimmed.equals("[Peer]", ignoreCase = true) -> {
                    inInterface = false
                    inPeer = true
                    result.add(line)
                }
                trimmed.startsWith("Address", ignoreCase = true) && inInterface -> {
                    result.add(sanitizeAddress(line))
                }
                trimmed.startsWith("DNS", ignoreCase = true) && inInterface -> {
                    result.add(sanitizeDns(line))
                }
                trimmed.startsWith("AllowedIPs", ignoreCase = true) && inPeer -> {
                    result.add(sanitizeAllowedIPs(line))
                }
                else -> result.add(line)
            }
        }

        return result.joinToString("\n")
    }

    private fun sanitizeAddress(line: String): String {
        val eqIndex = line.indexOf('=')
        if (eqIndex == -1) return line
        val value = line.substring(eqIndex + 1).trim()
        // Берём только первый IPv4-адрес
        val ipv4 = value.split(",").firstOrNull()?.trim() ?: return line
        // Добавляем /32 если нет маски
        val clean = if (ipv4.contains("/")) ipv4 else "$ipv4/32"
        return "${line.substring(0, eqIndex + 1)} $clean"
    }

    private fun sanitizeDns(line: String): String {
        val eqIndex = line.indexOf('=')
        if (eqIndex == -1) return line
        val value = line.substring(eqIndex + 1).trim()
        val dnsList = value.split(",").map { it.trim() }
            .filter { !it.contains(":") } // Убираем IPv6
        val clean = if (dnsList.isNotEmpty()) dnsList.joinToString(", ") else "1.1.1.1, 1.0.0.1"
        return "${line.substring(0, eqIndex + 1)} $clean"
    }

    private fun sanitizeAllowedIPs(line: String): String {
        val eqIndex = line.indexOf('=')
        if (eqIndex == -1) return line
        val value = line.substring(eqIndex + 1).trim()
        val ips = value.split(",").map { it.trim() }
            .filter { !it.contains(":") } // Убираем IPv6
        val clean = if (ips.isNotEmpty()) ips.joinToString(", ") else "0.0.0.0/0"
        return "${line.substring(0, eqIndex + 1)} $clean"
    }
}
