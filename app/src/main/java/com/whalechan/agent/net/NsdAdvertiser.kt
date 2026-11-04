package com.whalechan.agent.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.whalechan.agent.core.WhaleLog

/** 把手机端 WebSocket 服务注册到局域网 mDNS，伴侣端可以自动发现。 */
class NsdAdvertiser(
    private val context: Context,
    private val serviceName: String,
    private val port: Int
) {

    private val nsdManager: NsdManager? = context.getSystemService(NsdManager::class.java)
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        acquireMulticastLock()

        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = SERVICE_TYPE
            this.port = this@NsdAdvertiser.port
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                WhaleLog.i("NSD 已注册：${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                WhaleLog.w("NSD 注册失败：$errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                WhaleLog.i("NSD 已注销：${info.serviceName}")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                WhaleLog.w("NSD 注销失败：$errorCode")
            }
        }
        registrationListener = listener
        runCatching {
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { WhaleLog.w("NSD 注册异常", it) }
    }

    fun stop() {
        runCatching { registrationListener?.let { nsdManager?.unregisterService(it) } }
        registrationListener = null
        runCatching {
            multicastLock?.takeIf { it.isHeld }?.release()
        }
        multicastLock = null
    }

    private fun acquireMulticastLock() {
        runCatching {
            val wifi = context.getSystemService(WifiManager::class.java) ?: return
            multicastLock = wifi.createMulticastLock("whale-nsd").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { WhaleLog.w("获取组播锁失败（不影响 NSD，只影响部分设备发现）", it) }
    }

    companion object {
        const val SERVICE_TYPE = "_whalechan._tcp."
    }
}
