package com.config.app

import android.content.Context

class AutoConnectManager(context: Context) {
    private val storage = AutoConnectStorage(context)

    fun shouldAutoConnect(): Boolean = storage.isEnabled()
}
