package com.whalechan.protocol

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * 伴侣端的 mDNS 发现 + 解析。
 * 发现到服务后再 resolve 一次拿 host/port，因为 NsdServiceInfo 在发现阶段通常只有名字。
 */
class NsdDiscovery(
    private val context: Context,
    private val onFound: (name: String, host: String, port: Int) -> Unit,
    private val onLost: (name: String) -> Unit
) {

    private val nsdManager: NsdManager? = context.getSystemService(NsdManager::class.java)
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (discoveryListener != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Plog.i("NSD 开始搜索：$serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != SERVICE_TYPE) return
                resolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                onLost(serviceInfo.serviceName)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Plog.i("NSD 停止搜索")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Plog.w("NSD 搜索失败：$errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Plog.w("NSD 停止失败：$errorCode")
            }
        }
        discoveryListener = listener

        runCatching {
            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            Plog.w("启动 NSD 搜索异常", it)
            discoveryListener = null
        }
    }

    fun stop() {
        runCatching { discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) } }
        discoveryListener = null
    }

    private fun resolve(serviceInfo: NsdServiceInfo) {
        runCatching {
            nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Plog.w("NSD 解析失败：${info.serviceName} $errorCode")
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress ?: return
                    onFound(info.serviceName, host, info.port)
                }
            })
        }.onFailure { Plog.w("NSD 解析异常", it) }
    }

    companion object {
        const val SERVICE_TYPE = "_whalechan._tcp."
    }
}
