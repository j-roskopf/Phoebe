package com.phoebe.app.player

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.util.concurrent.TimeUnit

/**
 * Media3 HTTP stack aligned with Phoebe's Ktor/OkHttp clients (artwork + Plex API).
 *
 * DefaultHttpDataSource (HttpURLConnection) fails under split-tunnel VPNs such as Tailscale
 * while OkHttp still reaches the LAN Plex server. Do **not** bind sockets to the underlying
 * Wi-Fi [android.net.Network]: Tailscale sets `bypassable=false`, so `Network.bindSocket`
 * throws EPERM for VPN-included UIDs.
 */
internal object AndroidPlaybackHttp {
    private val ipv4PreferredDns = Dns { hostname ->
        val all = Dns.SYSTEM.lookup(hostname)
        val ipv4 = all.filterIsInstance<Inet4Address>()
        if (ipv4.isEmpty()) all else ipv4 + all.filter { it !is Inet4Address }
    }

    private val sharedClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(ipv4PreferredDns)
            .fastFallback(true)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Suppress("UNUSED_PARAMETER")
    fun dataSourceFactory(context: Context): DataSource.Factory =
        OkHttpDataSource.Factory(sharedClient)
            .setUserAgent("Phoebe")
}
