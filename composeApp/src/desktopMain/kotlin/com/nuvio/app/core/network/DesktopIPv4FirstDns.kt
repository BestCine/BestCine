package com.nuvio.app.core.network

import com.nuvio.app.bestcine.dns.BestCineDohDns
import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress

internal class DesktopIPv4FirstDns(private val delegate: Dns = BestCineDohDns) : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        delegate.lookup(hostname).sortedBy { if (it is Inet4Address) 0 else 1 }
}
