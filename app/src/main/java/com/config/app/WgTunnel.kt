package com.config.app

import org.amnezia.awg.backend.Tunnel

class WgTunnel(
    private val tunnelName: String,
    private val onStateChanged: ((Tunnel.State) -> Unit)? = null
) : Tunnel {

    private var currentState: Tunnel.State = Tunnel.State.DOWN

    override fun getName(): String = tunnelName

    override fun onStateChange(newState: Tunnel.State) {
        currentState = newState
        onStateChanged?.invoke(newState)
    }

    override fun isMetered(): Boolean = false

    override fun isIpv4ResolutionPreferred(): Boolean = true

    fun getState(): Tunnel.State = currentState
}
