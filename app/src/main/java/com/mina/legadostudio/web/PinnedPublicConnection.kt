package com.mina.legadostudio.web

import com.mina.legadostudio.domain.LogFilterUtils
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Resolve once, validate every answer, and connect using that exact address (never a hostname). */
internal class PinnedPublicConnection(
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val dial: (InetSocketAddress) -> Socket = { endpoint ->
        Socket().apply {
            try { connect(endpoint, 15_000); soTimeout = 60_000 }
            catch (error: Exception) { close(); throw error }
        }
    },
) {
    fun connect(host: String, port: Int): Socket {
        require(port in 1..65535) { "Invalid destination port" }
        require(host.isNotBlank() && host != "appassets.androidplatform.net" && !LogFilterUtils.isLoopbackOrPrivate(host)) { "Non-public destination" }
        val addresses = resolve(host)
        if (addresses.isEmpty() || addresses.any { !isPublic(it) }) throw IOException("Non-public DNS answer")
        var failure: Exception? = null
        for (address in addresses) {
            try { return dial(InetSocketAddress(address, port)) }
            catch (error: IOException) { failure = error }
        }
        throw IOException("Public destination unavailable", failure)
    }

    companion object {
        internal fun isPublic(address: InetAddress): Boolean =
            !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isSiteLocalAddress &&
                !address.isLinkLocalAddress && !address.isMulticastAddress &&
                !LogFilterUtils.isLoopbackOrPrivate(address.hostAddress.orEmpty())
    }
}
