package com.config.app

import org.amnezia.awg.backend.Tunnel

class WgTunnel(private val name: String) : Tunnel {
    override fun getName() = name
    override fun onStateChange(state: Tunnel.State) {}
    override fun isMetered() = false
    override fun isIpv4ResolutionPreferred() = false
}
