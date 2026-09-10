package com.config.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONArray
import java.net.URL

object CloudServers {
    private const val URL = "https://raw.githubusercontent.com/gennadyevt-bot/Config_dns/main/servers.json"

    fun update(context: Context, storage: ServerStorage, onDone: () -> Unit) {
        Thread {
            val result = try {
                val text = URL(URL).readText()
                val arr = JSONArray(text)
                var count = 0
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val name = o.optString("name", "Сервер " + (i + 1))
                    val config = o.optString("config", "")
                    if (config.isEmpty()) continue
                    val info = OwnServerSetup.parseConfigToInfo(config, name, "cloud")
                    if (info != null) {
                        storage.addServer(info)
                        count++
                    }
                }
                Result.success(count)
            } catch (e: Exception) {
                Result.failure(e)
            }
            Handler(Looper.getMainLooper()).post {
                result.onSuccess {
                    Toast.makeText(context, "Добавлено серверов: $it", Toast.LENGTH_LONG).show()
                    onDone()
                }
                result.onFailure {
                    Toast.makeText(context, "Ошибка обновления: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
