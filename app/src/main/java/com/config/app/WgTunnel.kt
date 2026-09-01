package com.config.app

import org.amnezia.awg.backend.Tunnel

class WgTunnel private constructor() : Tunnel {
    override fun getName() = "config_vpn"
    override fun onStateChange(state: Tunnel.State) {}
    override fun isMetered() = false
    override fun isIpv4ResolutionPreferred() = false

    companion object {
        @Volatile
        private var instance: WgTunnel? = null

        fun getInstance(): WgTunnel {
            return instance ?: synchronized(this) {
                instance ?: WgTunnel().also { instance = it }
            }
        }
    }
}