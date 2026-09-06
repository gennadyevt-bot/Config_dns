package com.config.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wireguard.android.backend.Backend as WgBackend
import com.wireguard.android.backend.GoBackend as WgGoBackend
import com.wireguard.android.backend.Tunnel as WgBackendTunnel
import com.wireguard.config.Config as WgConfig
import org.amnezia.awg.backend.Backend as AwgBackend
import org.amnezia.awg.backend.GoBackend as AwgGoBackend
import org.amnezia.awg.backend.NoopTunnelActionHandler
import org.amnezia.awg.backend.Tunnel as AwgBackendTunnel
import org.amnezia.awg.config.Config as AwgConfig
import java.io.ByteArrayInputStream

class VpnManager private constructor(private val context: Context) {

    // WireGuard: серверы БЕЗ junk-параметров — здесь App VPN (IncludedApplications)
    // работает гарантированно (проверено).
    private val wgBackend: WgBackend = WgGoBackend(context.applicationContext)

    // AmneziaWG: серверы С junk-параметрами Jc/Jmin/Jmax/S1/S2/H1-H4 —
    // маскируют WireGuard от DPI РНК. App VPN для таких серверов не гарантируется.
    private val awgBackend: AwgBackend = AwgGoBackend(context.applicationContext, NoopTunnelActionHandler())

    private var currentWgConfig: WgConfig? = null
    private var currentAwgConfig: AwgConfig? = null
    private var usingAwg = false
    private val vpnStateStorage = VpnStateStorage(context)

    var onStatusChanged: ((VpnStatus) -> Unit)? = null
    var onServerChanged: ((ServerInfo?) -> Unit)? = null

    private var currentServer: ServerInfo? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        var globalStatus: VpnStatus = VpnStatus.DISCONNECTED

        @Volatile
        private var instance: VpnManager? = null

        fun getInstance(context: Context): VpnManager {
            return instance ?: synchronized(this) {
                instance ?: VpnManager(context).also { instance = it }
            }
        }

        fun destroyInstance() {
            instance = null
        }
    }

    fun getPrepareIntent(activity: Activity): android.content.Intent? {
        return VpnService.prepare(activity)
    }

    fun isPrepared(): Boolean {
        return VpnService.prepare(context) == null
    }

    fun connect(server: ServerInfo) {
        scope.launch {
            try {
                val prepareIntent = VpnService.prepare(context)
                if (prepareIntent != null) {
                    withContext(Dispatchers.Main) {
                        updateStatus(VpnStatus.ERROR)
                        showToast("Ошибка: разрешение VPN не дано. Откройте приложение и нажмите CONNECT.")
                    }
                    return@launch
                }

                currentServer = server
                withContext(Dispatchers.Main) {
                    onServerChanged?.invoke(server)
                    updateStatus(VpnStatus.CONNECTING)
                }

                vpnStateStorage.setWasConnected(true)
                vpnStateStorage.setLastServer(server.id)

                val serviceIntent = Intent(context, VpnKeepAliveService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }

                val includedApps = AppVpnStorage(context).getSelectedPackages().toList()

                // Конфиг с junk-параметрами (AmneziaWG) идёт через AWG-бэкенд —
                // он обходит DPI РНК. Обычные конфиги — через WireGuard с App VPN.
                val wantsAwg = server.jc.isNotEmpty() && server.jc != "0"
                if (wantsAwg) {
                    connectAwg(server, includedApps)
                } else {
                    connectWg(server, includedApps)
                }

                withContext(Dispatchers.Main) {
                    updateStatus(VpnStatus.CONNECTED)
                    StopVpnWidget.updateWidget(context, VpnStatus.CONNECTED)
                }
            } catch (e: Exception) {
                val err = e.message ?: e.toString()
                android.util.Log.e("ConfigVPN", "Connect failed", e)
                vpnStateStorage.setWasConnected(false)
                withContext(Dispatchers.Main) {
                    updateStatus(VpnStatus.ERROR)
                    StopVpnWidget.updateWidget(context, VpnStatus.ERROR)
                    showToast("Ошибка: $err")
                }
            }
        }
    }

    private suspend fun connectWg(server: ServerInfo, includedApps: List<String>) {
        val configString = buildConfigString(server, includedApps, withAwg = false)
        android.util.Log.d("ConfigVPN", "WG config: $configString")

        val config = WgConfig.parse(ByteArrayInputStream(configString.toByteArray()))
        currentWgConfig = config
        usingAwg = false

        val tunnel = WgTunnel.getInstance()
        try {
            val t0 = System.currentTimeMillis()
            wgBackend.setState(tunnel, WgBackendTunnel.State.UP, config)
            android.util.Log.d("ConfigVPN", "WG handshake: ${System.currentTimeMillis() - t0} ms")
        } catch (e: Exception) {
            if (includedApps.isNotEmpty()) {
                android.util.Log.w("ConfigVPN", "Backend failed with IncludedApplications, retrying without...", e)
                val fallbackConfig = WgConfig.parse(ByteArrayInputStream(buildConfigString(server).toByteArray()))
                wgBackend.setState(tunnel, WgBackendTunnel.State.UP, fallbackConfig)
                withContext(Dispatchers.Main) {
                    showToast("App VPN: приложение не найдено, VPN работает для всех")
                }
            } else {
                throw e
            }
        }

        warnIfNoTraffic {
            runCatching { wgBackend.getStatistics(tunnel).totalRx() }.getOrNull()
        }
    }

    private suspend fun connectAwg(server: ServerInfo, includedApps: List<String>) {
        try {
            val configString = buildConfigString(server, includedApps, withAwg = true)
            android.util.Log.d("ConfigVPN", "AWG config: $configString")

            val config = AwgConfig.parse(ByteArrayInputStream(configString.toByteArray()))
            currentAwgConfig = config
            usingAwg = true

            val t0 = System.currentTimeMillis()
            awgBackend.setState(AwgTunnel.getInstance(), AwgBackendTunnel.State.UP, config)
            android.util.Log.d("ConfigVPN", "AWG handshake: ${System.currentTimeMillis() - t0} ms")

            warnIfNoTraffic {
                runCatching { awgBackend.getStatistics(AwgTunnel.getInstance()).totalRx() }.getOrNull()
            }
        } catch (e: Exception) {
            // Фолбэк: сервер не принял junk-параметры — пробуем обычный WireGuard
            android.util.Log.w("ConfigVPN", "AWG failed, falling back to plain WireGuard", e)
            connectWg(server, includedApps)
        }
    }

    // Диагностика: VPN-интерфейс поднят, но сервер молчит (конфиг устарел
    // или IP заблокирован) — иначе получается «без ошибок, но интернета нет».
    private fun warnIfNoTraffic(rxProvider: () -> Long?) {
        scope.launch {
            delay(10000)
            val rx = rxProvider() ?: return@launch
            if (rx == 0L && globalStatus == VpnStatus.CONNECTED) {
                withContext(Dispatchers.Main) {
                    showToast("⚠️ Сервер не отвечает 10 сек: конфиг устарел или IP заблокирован. Попробуйте другой сервер.")
                }
            }
        }
    }

    fun disconnect() {
        scope.launch {
            try {
                withContext(Dispatchers.Main) {
                    updateStatus(VpnStatus.DISCONNECTING)
                }

                vpnStateStorage.setWasConnected(false)
                context.stopService(Intent(context, VpnKeepAliveService::class.java))

                if (usingAwg) {
                    awgBackend.setState(AwgTunnel.getInstance(), AwgBackendTunnel.State.DOWN, currentAwgConfig)
                } else {
                    wgBackend.setState(WgTunnel.getInstance(), WgBackendTunnel.State.DOWN, currentWgConfig)
                }
                withContext(Dispatchers.Main) {
                    updateStatus(VpnStatus.DISCONNECTED)
                    currentServer = null
                    onServerChanged?.invoke(null)
                    StopVpnWidget.updateWidget(context, VpnStatus.DISCONNECTED)
                }
            } catch (e: Exception) {
                val err = e.message ?: e.toString()
                android.util.Log.e("ConfigVPN", "Disconnect failed", e)
                withContext(Dispatchers.Main) {
                    updateStatus(VpnStatus.ERROR)
                    StopVpnWidget.updateWidget(context, VpnStatus.ERROR)
                    showToast("Ошибка: $err")
                }
            }
        }
    }

    fun getStatus(): VpnStatus = globalStatus
    fun getCurrentServer(): ServerInfo? = currentServer

    // IPv4-диапазоны датацентров Telegram. Telegram в РФ не заблокирован, а
    // канал WARP/серверов до этих сетей у многих провайдеров плохой: клиент
    // поочерёдно перебирает адреса DC, и каждая неудачная попытка — секунды
    // ожидания («Телеграм грузится минуту», пока YouTube летает). Поэтому при
    // полном туннеле (0.0.0.0/0) вычитаем эти диапазоны: DC идут напрямую
    // (мгновенно), остальной трафик остаётся в VPN.
    private val telegramDcV4 = listOf(
        "91.108.4.0/22", "91.108.8.0/22", "91.108.12.0/22",
        "91.108.16.0/22", "91.108.20.0/22", "91.108.56.0/22",
        "149.154.160.0/20", "185.76.151.0/24"
    )

    private fun ipv4ToLong(ip: String): Long {
        var r = 0L
        for (p in ip.split('.')) r = (r shl 8) or (p.toLong() and 0xff)
        return r
    }

    private fun longToIpv4(v: Long): String = listOf(
        (v shr 24) and 0xff, (v shr 16) and 0xff, (v shr 8) and 0xff, v and 0xff
    ).joinToString(".")

    // Раскладывает выровненный диапазон адресов в CIDR-префиксы
    private fun rangeToCidrs(start: Long, end: Long): List<String> {
        val res = mutableListOf<String>()
        var s = start
        while (s <= end) {
            var size = if (s == 0L) (1L shl 32) else s and (-s)
            while (s + size - 1 > end) size = size shr 1
            res.add(longToIpv4(s) + "/" + (32 - java.lang.Long.numberOfTrailingZeros(size)))
            s += size
        }
        return res
    }

    // Дополнение 0.0.0.0/0 за вычетом списка CIDR: «весь интернет КРОМЕ».
    // WireGuard AllowedIPs умеет только добавлять маршруты, поэтому
    // исключение выражаем как набор префиксов-дополнений.
    private fun v4ComplementExcluding(exclude: List<String>): List<String> {
        val ranges = mutableListOf(0L..0xFFFFFFFFL)
        for (cidr in exclude) {
            val (ip, bits) = cidr.split('/')
            val base = ipv4ToLong(ip)
            val size = 1L shl (32 - bits.toInt())
            val cut = base until (base + size)
            val it = ranges.listIterator()
            while (it.hasNext()) {
                val r = it.next()
                if (cut.last < r.first || cut.first > r.last) continue
                it.remove()
                if (r.first < cut.first) it.add(r.first until cut.first)
                if (cut.last < r.last) it.add(cut.last + 1..r.last)
            }
        }
        return ranges.flatMap { rangeToCidrs(it.first, it.last) }
    }

    private fun hasIPv6Address(address: String): Boolean {
        return address.split(',').any { it.trim().contains(':') }
    }

    private fun sanitizeDns(dns: String, interfaceAddress: String): String {
        val entries = dns.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val v6 = hasIPv6Address(interfaceAddress)
        val cleaned = entries.filter { !it.contains(':') || v6 }
        return if (cleaned.isEmpty()) "1.1.1.1" else cleaned.joinToString(", ")
    }

    // IPv6-диапазоны датацентров Telegram. Сценарий «без IPv6-адреса на
    // интерфейсе»: мы вырезаем все v6-маршруты, Telegram пробует свои v6-адреса
    // В ОБХОД туннеля и (если бы мы его не перехватили) упирался бы в блок
    // РНК — приложение висело бы на «Connecting...». Маршрутизируем только эти
    // префиксы в туннель: v6-пакеты мгновенно умирают (fast-fail), клиент
    // сразу падает на IPv4 и идёт через VPN.
    //
    // Сценарий «IPv6-адрес на интерфейсе есть» (встроенные WARP-конфиги!):
    // раньше здесь оставался ::/0 — весь v6-трафик шёл в туннель, включая
    // попытки Telegram достучаться до своих v6-DC. v6-канал WARP до DC
    // Telegram у многих провайдеров мёртвый/конgested: каждый запуск Telegram
    // висел до таймаута (~1 минута), потом падал на IPv4. YouTube/браузер при
    // этом «летали», т.к. ходили по IPv4. Поэтому ::/0 теперь вырезается
    // всегда (см. buildAllowedIPs): остальной IPv6 интернета идёт напрямую
    // мимо туннеля — это безопасно, Telegram в РФ не заблокирован (с 2020),
    // а Telegram-в-таннеле ходит строго по IPv4.
    private val telegramV6Blackhole = listOf(
        "2001:67c:4e8::/48",
        "2001:b28:f23d::/48",
        "2001:b28:f23f::/48",
        "2a0a:f280::/32"
    )

    // Итоговый набор маршрутов:
    // 1) IPv4: если туннель забирает всё (0.0.0.0/0) — вычитаем DC Telegram
    //    (см. telegramDcV4), иначе оставляем как в конфиге.
    // 2) IPv6: ::/0 вырезаем всегда (мёртвый v6-канал WARP до DC Telegram —
    //    см. комментарий выше), остальные v6-маршруты — только если на
    //    интерфейсе есть IPv6-адрес, иначе это чёрная дыра.
    // 3) Нет IPv6-адреса на интерфейсе — добавляем Telegram-v6-blackhole.
    private fun buildAllowedIPs(server: ServerInfo): String {
        val v6addr = hasIPv6Address(server.interfaceAddress)
        val entries = server.peerAllowedIPs.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val v4 = entries.filter { !it.contains(':') }
        val v6 = entries.filter { it.contains(':') && v6addr && it != "::/0" }
        val out = mutableListOf<String>()
        if (v4.any { it == "0.0.0.0/0" }) {
            out.addAll(v4ComplementExcluding(telegramDcV4))
        } else {
            out.addAll(v4)
        }
        out.addAll(v6)
        if (!v6addr) out.addAll(telegramV6Blackhole)
        if (out.isEmpty()) out.add("0.0.0.0/0")
        return out.joinToString(", ")
    }

    private fun buildConfigString(server: ServerInfo, includedApps: List<String> = emptyList(), withAwg: Boolean = false): String {
        val allowedIPs = buildAllowedIPs(server)
        val dns = sanitizeDns(server.interfaceDns, server.interfaceAddress)
        return buildString {
            appendLine("[Interface]")
            appendLine("Address = ${server.interfaceAddress}")
            appendLine("DNS = $dns")
            appendLine("PrivateKey = ${server.interfacePrivateKey}")

            if (withAwg) {
                // Junk-параметры AmneziaWG — маскируют WireGuard от DPI (обход блокировок РНК)
                if (server.jc.isNotEmpty() && server.jc != "0") appendLine("Jc = ${server.jc}")
                if (server.jmin.isNotEmpty() && server.jmin != "0") appendLine("Jmin = ${server.jmin}")
                if (server.jmax.isNotEmpty() && server.jmax != "0") appendLine("Jmax = ${server.jmax}")
                if (server.s1.isNotEmpty() && server.s1 != "0") appendLine("S1 = ${server.s1}")
                if (server.s2.isNotEmpty() && server.s2 != "0") appendLine("S2 = ${server.s2}")
                if (server.h1.isNotEmpty() && server.h1 != "0") appendLine("H1 = ${server.h1}")
                if (server.h2.isNotEmpty() && server.h2 != "0") appendLine("H2 = ${server.h2}")
                if (server.h3.isNotEmpty() && server.h3 != "0") appendLine("H3 = ${server.h3}")
                if (server.h4.isNotEmpty() && server.h4 != "0") appendLine("H4 = ${server.h4}")
            }

            // App VPN: парсеры ОБЕИХ библиотек (WireGuard и AmneziaWG 2.3.7)
            // поддерживают IncludedApplications — байткод GoBackend подтверждает.
            val pm = context.packageManager
            val validApps = includedApps.filter { pkg ->
                try { pm.getApplicationInfo(pkg, 0); true }
                catch (e: Exception) { android.util.Log.w("ConfigVPN", "App не установлено: $pkg"); false }
            }
            validApps.forEach { appendLine("IncludedApplications = $it") }

            appendLine("[Peer]")
            appendLine("PublicKey = ${server.peerPublicKey}")
            if (server.peerPresharedKey.isNotEmpty()) {
                appendLine("PresharedKey = ${server.peerPresharedKey}")
            }
            appendLine("AllowedIPs = $allowedIPs")
            appendLine("Endpoint = ${server.peerEndpoint}")
            appendLine("PersistentKeepalive = ${server.peerPersistentKeepalive}")
        }
    }

    private fun updateStatus(status: VpnStatus) {
        globalStatus = status
        onStatusChanged?.invoke(status)
    }

    private fun showToast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }
}