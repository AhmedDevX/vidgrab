package com.vidgrab.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

private const val SERVICE_TYPE = "_vidgrab._tcp."

data class DiscoveredDevice(
    val serviceName: String,
    val host: String,
    val port: Int,
    val deviceId: String,
    val platform: String,
    val fingerprint: String, // public identity; verified again over TLS anyway
)

/**
 * mDNS/NSD discovery. Advertised data (name, host, port, fingerprint) is
 * UNTRUSTED reachability information: any LAN peer can publish it. Trust
 * comes only from the TLS fingerprint comparison in SendClient.
 */
class Nsd(context: Context) {

    private val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val found = mutableMapOf<String, DiscoveredDevice>()
    var onDevicesChanged: ((List<DiscoveredDevice>) -> Unit)? = null

    fun advertise(name: String, port: Int, deviceId: String, fingerprint: String) {
        stopAdvertising()
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("v", "2")
            setAttribute("id", deviceId)
            setAttribute("platform", "android")
            setAttribute("fp", fingerprint)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(info: NsdServiceInfo?, errorCode: Int) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo?, errorCode: Int) {}
            override fun onServiceRegistered(info: NsdServiceInfo?) {}
            override fun onServiceUnregistered(info: NsdServiceInfo?) {}
        }
        registrationListener = listener
        manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stopAdvertising() {
        registrationListener?.let {
            try { manager.unregisterService(it) } catch (_: Exception) {}
        }
        registrationListener = null
    }

    fun startDiscovery() {
        stopDiscovery()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onServiceFound(info: NsdServiceInfo?) {
                info ?: return
                manager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {}
                    override fun onServiceResolved(resolved: NsdServiceInfo?) {
                        resolved ?: return
                        val host = resolved.host?.hostAddress ?: return
                        val attrs = resolved.attributes
                        val device = DiscoveredDevice(
                            serviceName = resolved.serviceName,
                            host = host,
                            port = resolved.port,
                            deviceId = attrs["id"]?.let { String(it) } ?: "",
                            platform = attrs["platform"]?.let { String(it) } ?: "",
                            fingerprint = attrs["fp"]?.let { String(it) } ?: "",
                        )
                        synchronized(found) { found[resolved.serviceName] = device }
                        onDevicesChanged?.invoke(devices())
                    }
                })
            }
            override fun onServiceLost(info: NsdServiceInfo?) {
                info ?: return
                synchronized(found) { found.remove(info.serviceName) }
                onDevicesChanged?.invoke(devices())
            }
        }
        discoveryListener = listener
        manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            try { manager.stopServiceDiscovery(it) } catch (_: Exception) {}
        }
        discoveryListener = null
    }

    fun devices(): List<DiscoveredDevice> = synchronized(found) { found.values.toList() }

    fun close() {
        stopAdvertising()
        stopDiscovery()
    }
}
