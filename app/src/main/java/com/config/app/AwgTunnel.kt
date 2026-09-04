package com.config.app

import org.amnezia.awg.backend.Tunnel

class AwgTunnel private constructor() : Tunnel {

    override fun getName(): String = "ConfigVPN"

    override fun onStateChange(newState: Tunnel.State) {
        android.util.Log.d("AwgTunnel", "State changed to: $newState")
    }

    override fun isMetered() = false
    override fun isIpv4ResolutionPreferred() = false

    companion object {
        private var instance: AwgTunnel? = null

        fun getInstance(): AwgTunnel {
            return instance ?: synchronized(this) {
                instance ?: AwgTunnel().also { instance = it }
            }
        }
    }
}
