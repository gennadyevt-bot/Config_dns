package com.config.app

data class ServerInfo(
    val id: String,
    val name: String,
    val country: String = "",
    val flagEmoji: String = "",
    val interfaceAddress: String = "",
    val interfaceDns: String = "1.1.1.1, 8.8.8.8",
    val interfacePrivateKey: String = "",
    val peerPublicKey: String = "",
    val peerPresharedKey: String = "",
    val peerAllowedIPs: String = "0.0.0.0/0",
    val peerEndpoint: String = "",
    val peerPersistentKeepalive: String = "25",
    val jc: String = "5",
    val jmin: String = "50",
    val jmax: String = "1000",
    val s1: String = "50",
    val s2: String = "100",
    val h1: String = "1",
    val h2: String = "2",
    val h3: String = "3",
    val h4: String = "4"
)