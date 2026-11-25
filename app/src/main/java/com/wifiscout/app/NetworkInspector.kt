package com.wifiscout.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

enum class NetKind { WIFI, CELLULAR, ETHERNET, OFFLINE }

data class CurrentNetworkInfo(
    val kind: NetKind,
    val displayName: String,
    val rawSsid: String?,
    val detailLine: String,
    val rssiDbm: Int?,
    val frequencyMhz: Int?,
    val linkSpeedMbps: Int?,
    val needsLocationPermission: Boolean,
    val needsLocationToggle: Boolean
)

object NetworkInspector {

    @Volatile
    private var cachedWifiInfo: WifiInfo? = null

    fun createLiveWifiCallback(onChanged: () -> Unit): ConnectivityManager.NetworkCallback {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(
                ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
            ) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val info = caps.transportInfo as? WifiInfo
                    if (info != null) {
                        cachedWifiInfo = info
                    }
                    onChanged()
                }

                override fun onAvailable(network: Network) {
                    onChanged()
                }

                override fun onLost(network: Network) {
                    cachedWifiInfo = null
                    onChanged()
                }
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    onChanged()
                }

                override fun onAvailable(network: Network) {
                    onChanged()
                }

                override fun onLost(network: Network) {
                    cachedWifiInfo = null
                    onChanged()
                }
            }
        }
    }

    fun registerCallback(context: Context, callback: ConnectivityManager.NetworkCallback) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(request, callback)
        } catch (_: Exception) {
        }
    }

    fun unregisterCallback(context: Context, callback: ConnectivityManager.NetworkCallback) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
    }

    fun hasFineLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isLocationServiceEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    @Suppress("DEPRECATION")
    fun inspect(context: Context): CurrentNetworkInfo {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork
        val caps = if (activeNetwork != null) cm.getNetworkCapabilities(activeNetwork) else null

        if (caps == null) {
            return CurrentNetworkInfo(
                kind = NetKind.OFFLINE,
                displayName = "未连接网络",
                rawSsid = null,
                detailLine = "请连接 Wi-Fi 或开启移动数据",
                rssiDbm = null,
                frequencyMhz = null,
                linkSpeedMbps = null,
                needsLocationPermission = false,
                needsLocationToggle = false
            )
        }

        val hasPerm = hasFineLocationPermission(context)
        val locOn = isLocationServiceEnabled(context)

        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo: WifiInfo? = cachedWifiInfo ?: try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    (caps.transportInfo as? WifiInfo) ?: wm.connectionInfo
                } else {
                    wm.connectionInfo
                }
            } catch (_: Exception) {
                null
            }

            val raw = wifiInfo?.ssid?.trim()?.removePrefix("\"")?.removeSuffix("\"")
            val validSsid = raw != null &&
                raw.isNotEmpty() &&
                !raw.equals("<unknown ssid>", ignoreCase = true) &&
                raw != "0x"

            val rssi = wifiInfo?.rssi?.takeIf { it in -120..-1 }
            val freq = wifiInfo?.frequency?.takeIf { it > 0 }
            val linkSpeed = wifiInfo?.linkSpeed?.takeIf { it > 0 }

            val band = when {
                freq == null -> null
                freq >= 5900 -> "6GHz"
                freq >= 4900 -> "5GHz"
                else -> "2.4GHz"
            }

            val signalLabel = when {
                rssi == null -> null
                rssi >= -55 -> "信号极强"
                rssi >= -67 -> "信号良好"
                rssi >= -76 -> "信号一般"
                else -> "信号偏弱"
            }

            val detailParts = mutableListOf<String>()
            if (signalLabel != null && rssi != null) detailParts.add("$signalLabel (${rssi}dBm)")
            if (band != null) detailParts.add(band)
            if (linkSpeed != null) detailParts.add("协商 ${linkSpeed}Mbps")
            if (detailParts.isEmpty()) detailParts.add("Wi-Fi 已连接")

            val name = if (validSsid) raw!! else "当前 Wi-Fi（未获取名称）"

            return CurrentNetworkInfo(
                kind = NetKind.WIFI,
                displayName = name,
                rawSsid = if (validSsid) raw else null,
                detailLine = detailParts.joinToString(" · "),
                rssiDbm = rssi,
                frequencyMhz = freq,
                linkSpeedMbps = linkSpeed,
                needsLocationPermission = !validSsid && !hasPerm,
                needsLocationToggle = !validSsid && hasPerm && !locOn
            )
        }

        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val carrier = try {
                tm.networkOperatorName?.trim().orEmpty()
            } catch (_: Exception) {
                ""
            }
            val title = if (carrier.isNotEmpty()) "手机流量 ($carrier)" else "手机蜂窝流量"
            val downEst = caps.linkDownstreamBandwidthKbps / 1000
            val detail = if (downEst > 0) "蜂窝数据 · 链路估速约 ${downEst}Mbps" else "蜂窝数据已连接（适合做热点对比基准）"
            return CurrentNetworkInfo(
                kind = NetKind.CELLULAR,
                displayName = title,
                rawSsid = title,
                detailLine = detail,
                rssiDbm = null,
                frequencyMhz = null,
                linkSpeedMbps = downEst.takeIf { it > 0 },
                needsLocationPermission = false,
                needsLocationToggle = false
            )
        }

        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return CurrentNetworkInfo(
                kind = NetKind.ETHERNET,
                displayName = "有线网络",
                rawSsid = "有线网络",
                detailLine = "以太网已连接",
                rssiDbm = null,
                frequencyMhz = null,
                linkSpeedMbps = null,
                needsLocationPermission = false,
                needsLocationToggle = false
            )
        }

        return CurrentNetworkInfo(
            kind = NetKind.WIFI,
            displayName = "当前网络",
            rawSsid = null,
            detailLine = "网络已连接",
            rssiDbm = null,
            frequencyMhz = null,
            linkSpeedMbps = null,
            needsLocationPermission = !hasPerm,
            needsLocationToggle = hasPerm && !locOn
        )
    }
}
