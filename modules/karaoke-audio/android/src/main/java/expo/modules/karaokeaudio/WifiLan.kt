package expo.modules.karaokeaudio

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import expo.modules.karaokeaudio.wifi.WifiMicPacket
import java.net.Inet4Address
import java.net.NetworkInterface

internal data class WifiPeer(
  val name: String,
  val host: String,
  val port: Int,
)

/**
 * mDNS advertisement and discovery for StageMic speaker phones on the same Wi-Fi.
 * The IP address on screen is the fallback when discovery does not resolve.
 */
internal object WifiLan {
  private val mainHandler = Handler(Looper.getMainLooper())
  private val peers = LinkedHashMap<String, WifiPeer>()
  private val pending = ArrayDeque<NsdServiceInfo>()
  private var resolving = false
  private var discoveryListener: NsdManager.DiscoveryListener? = null
  private var registrationListener: NsdManager.RegistrationListener? = null
  private var nsd: NsdManager? = null
  private var advertising = false
  private var discovering = false
  private var multicastUsers = 0
  private var multicastLock: WifiManager.MulticastLock? = null

  fun ipv4(): String {
    var fallback = ""
    val interfaces = try {
      NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
    } catch (_: Exception) {
      emptyList()
    }
    for (network in interfaces) {
      if (!network.isUp || network.isLoopback) {
        continue
      }
      for (address in network.inetAddresses) {
        if (address !is Inet4Address || address.isLoopbackAddress) {
          continue
        }
        val host = address.hostAddress ?: continue
        if (network.name.startsWith("wlan")) {
          return host
        }
        if (fallback.isEmpty()) {
          fallback = host
        }
      }
    }
    return fallback
  }

  @Synchronized
  fun peerList(): List<WifiPeer> = peers.values.toList()

  @Synchronized
  fun isAdvertising(): Boolean = advertising

  fun startDiscovery(context: Context) {
    val manager = nsd(context) ?: return
    synchronized(this) {
      if (discovering) {
        return
      }
      discovering = true
      acquireMulticast(context)
    }
    val listener = object : NsdManager.DiscoveryListener {
      override fun onDiscoveryStarted(serviceType: String) = Unit
      override fun onDiscoveryStopped(serviceType: String) = Unit
      override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
        synchronized(this@WifiLan) { discovering = false }
      }
      override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
      override fun onServiceFound(serviceInfo: NsdServiceInfo) {
        if (serviceInfo.serviceType?.contains("stagemic") != true) {
          return
        }
        mainHandler.post { enqueueResolve(manager, serviceInfo) }
      }
      override fun onServiceLost(serviceInfo: NsdServiceInfo) {
        synchronized(this@WifiLan) { peers.remove(serviceInfo.serviceName) }
      }
    }
    synchronized(this) { discoveryListener = listener }
    try {
      manager.discoverServices(WifiMicPacket.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    } catch (_: IllegalArgumentException) {
      try {
        manager.discoverServices("_stagemic._udp", NsdManager.PROTOCOL_DNS_SD, listener)
      } catch (_: Exception) {
        synchronized(this) { discovering = false }
      }
    }
  }

  fun stopDiscovery() {
    val manager = nsd
    val listener = synchronized(this) {
      val current = discoveryListener
      discovering = false
      pending.clear()
      resolving = false
      discoveryListener = null
      peers.clear()
      current
    }
    if (manager != null && listener != null) {
      try {
        manager.stopServiceDiscovery(listener)
      } catch (_: Exception) {
        // Already stopped.
      }
      releaseMulticast()
    }
  }

  fun startAdvertising(context: Context, port: Int) {
    val manager = nsd(context) ?: return
    stopAdvertising()
    synchronized(this) { acquireMulticast(context) }
    val info = NsdServiceInfo().apply {
      serviceName = "StageMic"
      serviceType = WifiMicPacket.SERVICE_TYPE
      this.port = port
    }
    val listener = object : NsdManager.RegistrationListener {
      override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
        synchronized(this@WifiLan) { advertising = true }
      }
      override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
        synchronized(this@WifiLan) { advertising = false }
      }
      override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
        synchronized(this@WifiLan) { advertising = false }
      }
      override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
    }
    synchronized(this) { registrationListener = listener }
    try {
      manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    } catch (_: Exception) {
      synchronized(this) { advertising = false }
    }
  }

  fun stopAdvertising() {
    val manager = nsd
    val listener = synchronized(this) {
      advertising = false
      val current = registrationListener
      registrationListener = null
      current
    }
    if (manager != null && listener != null) {
      try {
        manager.unregisterService(listener)
      } catch (_: Exception) {
        // Already gone.
      }
      releaseMulticast()
    }
  }

  private fun enqueueResolve(manager: NsdManager, serviceInfo: NsdServiceInfo) {
    synchronized(this) {
      pending.add(serviceInfo)
    }
    pumpResolve(manager)
  }

  private fun pumpResolve(manager: NsdManager) {
    val next = synchronized(this) {
      if (resolving) {
        return
      }
      val service = pending.removeFirstOrNull() ?: return
      resolving = true
      service
    }
    @Suppress("DEPRECATION")
    manager.resolveService(next, object : NsdManager.ResolveListener {
      override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
        val host = serviceInfo.host?.hostAddress.orEmpty()
        if (host.isNotEmpty() && host != ipv4()) {
          synchronized(this@WifiLan) {
            peers[serviceInfo.serviceName] = WifiPeer(
              name = serviceInfo.serviceName.ifBlank { "StageMic" },
              host = host,
              port = if (serviceInfo.port > 0) serviceInfo.port else WifiMicPacket.DEFAULT_PORT,
            )
          }
        }
        finishResolve(manager)
      }

      override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
        finishResolve(manager)
      }
    })
  }

  private fun finishResolve(manager: NsdManager) {
    mainHandler.post {
      synchronized(this) { resolving = false }
      pumpResolve(manager)
    }
  }

  private fun nsd(context: Context): NsdManager? {
    nsd?.let { return it }
    val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    nsd = manager
    return manager
  }

  private fun acquireMulticast(context: Context) {
    multicastUsers += 1
    if (multicastLock?.isHeld == true) {
      return
    }
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val lock = wifi.createMulticastLock("stagemic-mdns")
    lock.setReferenceCounted(false)
    lock.acquire()
    multicastLock = lock
  }

  private fun releaseMulticast() {
    synchronized(this) {
      multicastUsers = (multicastUsers - 1).coerceAtLeast(0)
      if (multicastUsers > 0) {
        return
      }
      val lock = multicastLock
      multicastLock = null
      if (lock != null && lock.isHeld) {
        lock.release()
      }
    }
  }
}
