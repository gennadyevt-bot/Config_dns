package com.config.app

import com.wireguard.android.backend.Tunnel

class WgTunnel private constructor() : Tunnel {

    override fun getName(): String = "ConfigVPN"

    override fun onStateChange(newState: Tunnel.State) {
        android.util.Log.d("WgTunnel", "State changed to: $newState")
    }

    companion object {
        private var instance: WgTunnel? = null

        fun getInstance(): WgTunnel {
            return instance ?: synchronized(this) {
                instance ?: WgTunnel().also { instance = it }
            }
        }
    }
}