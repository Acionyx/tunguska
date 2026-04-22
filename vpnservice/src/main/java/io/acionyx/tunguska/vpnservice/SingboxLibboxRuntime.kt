package io.acionyx.tunguska.vpnservice

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.net.ProxyInfo
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import io.acionyx.tunguska.domain.SplitTunnelMode
import io.acionyx.tunguska.vpnservice.EmbeddedRuntimeDependencies
import io.acionyx.tunguska.vpnservice.EmbeddedRuntimeStrategyId
import io.acionyx.tunguska.vpnservice.IpSubnet
import io.acionyx.tunguska.vpnservice.StagedRuntimeRequest
import io.acionyx.tunguska.vpnservice.TunnelBuilderAdapter
import io.acionyx.tunguska.vpnservice.TunnelInterfacePlanner
import io.acionyx.tunguska.vpnservice.TunnelSessionPlan
import io.acionyx.tunguska.vpnservice.VpnRuntimeStore
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.RoutePrefix
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.StringBox
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.io.File
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.net.UnknownHostException
import java.security.KeyStore
import java.util.Base64
import java.util.Collections
import java.util.Locale
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.runBlocking

internal fun interface SingboxRuntimeFactory {
    fun create(
        request: StagedRuntimeRequest,
        runtimeConfigFile: File,
        runtimeDependencies: EmbeddedRuntimeDependencies,
    ): SingboxRuntime
}

internal interface SingboxRuntime {
    fun start()

    fun stop()

    fun health(): SingboxRuntimeHealth
}

internal data class SingboxRuntimeHealth(
    val healthy: Boolean,
    val summary: String,
)

internal object DefaultSingboxRuntimeFactory : SingboxRuntimeFactory {
    override fun create(
        request: StagedRuntimeRequest,
        runtimeConfigFile: File,
        runtimeDependencies: EmbeddedRuntimeDependencies,
    ): SingboxRuntime {
        val runtimeContext = runtimeDependencies.context ?: runtimeDependencies.vpnService?.applicationContext
        val vpnService = runtimeDependencies.vpnService
        if (runtimeContext == null || vpnService == null) {
            return MissingSingboxRuntime(
                message = "Embedded sing-box runtime requires an attached Android VpnService.",
            )
        }
        return LibboxSingboxRuntime(
            request = request,
            runtimeConfigFile = runtimeConfigFile,
            context = runtimeContext,
            vpnService = vpnService,
        )
    }
}

private class MissingSingboxRuntime(
    private val message: String,
) : SingboxRuntime {
    override fun start() {
        error(message)
    }

    override fun stop() = Unit

    override fun health(): SingboxRuntimeHealth = SingboxRuntimeHealth(
        healthy = false,
        summary = message,
    )
}

private class LibboxSingboxRuntime(
    private val request: StagedRuntimeRequest,
    private val runtimeConfigFile: File,
    context: Context,
    private val vpnService: VpnService,
) : SingboxRuntime, CommandServerHandler {
    private val appContext: Context = context.applicationContext
    private val lock = Any()
    private var activeState: ActiveLibboxState? = null

    override fun start() {
        synchronized(lock) {
            if (activeState != null) {
                error("Embedded sing-box runtime is already active.")
            }
        }
        val libboxBinary = SingboxBinaryLocator.locate(appContext)
        VpnRuntimeStore.recordRuntimeTelemetry(
            strategy = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
            nativeEvent = "Using packaged libbox runtime at ${libboxBinary.absolutePath}.",
        )
        SingboxLibboxEnvironment.ensureSetup(appContext)
        val configText = runtimeConfigFile.readText()
        Libbox.checkConfig(configText)
        val platform = SingboxPlatformInterface(
            context = appContext,
            vpnService = vpnService,
            sessionPlan = request.plan,
        )
        val overrideOptions = buildOverrideOptions(request.plan)
        val commandServer = Libbox.newCommandServer(this, platform)
        runCatching {
            commandServer.start()
            commandServer.startOrReloadService(configText, overrideOptions)
            if (commandServer.needWIFIState()) {
                runCatching { commandServer.updateWIFIState() }
            }
            synchronized(lock) {
                activeState = ActiveLibboxState(
                    commandServer = commandServer,
                    platform = platform,
                    configText = configText,
                    overrideOptions = overrideOptions,
                )
            }
        }.getOrElse { error ->
            runCatching { commandServer.closeService() }
            runCatching { commandServer.close() }
            platform.close()
            throw error
        }
    }

    override fun stop() {
        val state = synchronized(lock) {
            val snapshot = activeState
            activeState = null
            snapshot
        } ?: return
        runCatching { state.commandServer.closeService() }
        runCatching { state.commandServer.close() }
        state.platform.close()
    }

    override fun health(): SingboxRuntimeHealth {
        synchronized(lock) {
            if (activeState == null) {
                return SingboxRuntimeHealth(
                    healthy = false,
                    summary = "Embedded sing-box runtime is not active.",
                )
            }
        }
        val client = Libbox.newStandaloneCommandClient()
        return runCatching {
            val startedAt = client.startedAt
            if (startedAt <= 0L) {
                SingboxRuntimeHealth(
                    healthy = false,
                    summary = "Embedded sing-box runtime command socket is not serving traffic.",
                )
            } else {
                SingboxRuntimeHealth(
                    healthy = true,
                    summary = "Embedded sing-box runtime is healthy.",
                )
            }
        }.getOrElse { error ->
            SingboxRuntimeHealth(
                healthy = false,
                summary = error.message ?: error.javaClass.simpleName,
            )
        }.also {
            runCatching { client.disconnect() }
        }
    }

    override fun getSystemProxyStatus(): SystemProxyStatus {
        return synchronized(lock) { activeState?.platform?.currentSystemProxyStatus() }
            ?: SystemProxyStatus().apply {
                available = false
                enabled = false
            }
    }

    override fun serviceReload() {
        val state = synchronized(lock) { activeState } ?: return
        state.commandServer.startOrReloadService(state.configText, state.overrideOptions)
    }

    override fun serviceStop() {
        val state = synchronized(lock) { activeState } ?: return
        state.commandServer.closeService()
        state.platform.closeTun()
    }

    override fun setSystemProxyEnabled(enabled: Boolean) {
        synchronized(lock) {
            activeState?.platform?.setSystemProxyEnabled(enabled)
        }
    }

    override fun writeDebugMessage(message: String) {
        Log.i(TAG, message)
        VpnRuntimeStore.recordRuntimeTelemetry(
            strategy = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
            nativeEvent = "sing-box: $message",
        )
    }

    private fun buildOverrideOptions(sessionPlan: TunnelSessionPlan): OverrideOptions = OverrideOptions().apply {
        autoRedirect = false
        includePackage = SimpleStringIterator(emptyList())
        excludePackage = SimpleStringIterator(emptyList())
        when (val splitTunnel = sessionPlan.splitTunnelMode) {
            SplitTunnelMode.FullTunnel -> Unit
            is SplitTunnelMode.Allowlist -> {
                includePackage = SimpleStringIterator(splitTunnel.packageNames.sorted())
            }

            is SplitTunnelMode.Denylist -> {
                excludePackage = SimpleStringIterator(splitTunnel.packageNames.sorted())
            }
        }
    }

    private data class ActiveLibboxState(
        val commandServer: CommandServer,
        val platform: SingboxPlatformInterface,
        val configText: String,
        val overrideOptions: OverrideOptions,
    )

    private companion object {
        private const val TAG = "SingboxRuntime"
    }
}

private object SingboxLibboxEnvironment {
    private val lock = Any()
    private var configuredBasePath: String? = null

    fun ensureSetup(context: Context) {
        synchronized(lock) {
            val rootDir = File(context.noBackupFilesDir, "singbox")
            val baseDir = File(rootDir, "base")
            val workingDir = File(rootDir, "working")
            val tempDir = File(rootDir, "temp")
            listOf(rootDir, baseDir, workingDir, tempDir).forEach { dir ->
                require(dir.isDirectory || dir.mkdirs()) {
                    "Failed to create sing-box runtime directory ${dir.absolutePath}."
                }
            }
            if (configuredBasePath == baseDir.absolutePath) {
                return
            }
            runCatching {
                Libbox.setLocale(Locale.getDefault().toLanguageTag().replace("-", "_"))
            }
            Libbox.setup(
                SetupOptions().apply {
                    basePath = baseDir.absolutePath
                    workingPath = workingDir.absolutePath
                    tempPath = tempDir.absolutePath
                    fixAndroidStack = true
                    commandServerListenPort = 0
                    commandServerSecret = ""
                    logMaxLines = 4000L
                    debug = false
                },
            )
            configuredBasePath = baseDir.absolutePath
        }
    }
}

private class SingboxPlatformInterface(
    private val context: Context,
    private val vpnService: VpnService,
    private val sessionPlan: TunnelSessionPlan,
) : PlatformInterface {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val localDnsTransport = SingboxLocalDnsTransport(::requireDefaultNetwork)
    private val lock = Any()
    private var tunDescriptor: ParcelFileDescriptor? = null
    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var defaultInterfaceListener: InterfaceUpdateListener? = null
    private var systemProxyAvailable: Boolean = false
    private var systemProxyEnabled: Boolean = false

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        vpnService.protect(fd)
    }

    override fun openTun(options: TunOptions): Int {
        if (VpnService.prepare(vpnService) != null) {
            error("android: missing vpn permission")
        }
        closeTun()
        val builder = vpnService.Builder()
        val adapter = VpnBuilderAdapter(builder)
        val runtimePackageName = vpnService.packageName
        val sessionLabel = TunnelInterfacePlanner.plan(sessionPlan).sessionLabel
        builder.setSession(sessionLabel)
        builder.setMtu(options.mtu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val inet4Address = options.inet4Address
        while (inet4Address.hasNext()) {
            adapter.addAddress(inet4Address.next().toIpSubnet())
        }

        val inet6Address = options.inet6Address
        while (inet6Address.hasNext()) {
            adapter.addAddress(inet6Address.next().toIpSubnet())
        }

        if (options.autoRoute) {
            val dnsServer = runCatching { options.dnsServerAddress.unwrap() }.getOrDefault("")
            if (dnsServer.isNotBlank()) {
                builder.addDnsServer(dnsServer)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val inet4RouteAddress = options.inet4RouteAddress
                if (inet4RouteAddress.hasNext()) {
                    while (inet4RouteAddress.hasNext()) {
                        adapter.addRoute(inet4RouteAddress.next().toIpSubnet())
                    }
                } else if (options.inet4Address.hasNext()) {
                    adapter.addRoute(IpSubnet(address = "0.0.0.0", prefixLength = 0))
                }

                val inet6RouteAddress = options.inet6RouteAddress
                if (inet6RouteAddress.hasNext()) {
                    while (inet6RouteAddress.hasNext()) {
                        adapter.addRoute(inet6RouteAddress.next().toIpSubnet())
                    }
                } else if (options.inet6Address.hasNext()) {
                    adapter.addRoute(IpSubnet(address = "::", prefixLength = 0))
                }

                val inet4RouteExcludeAddress = options.inet4RouteExcludeAddress
                while (inet4RouteExcludeAddress.hasNext()) {
                    adapter.excludeRoute(inet4RouteExcludeAddress.next().toIpSubnet())
                }

                val inet6RouteExcludeAddress = options.inet6RouteExcludeAddress
                while (inet6RouteExcludeAddress.hasNext()) {
                    adapter.excludeRoute(inet6RouteExcludeAddress.next().toIpSubnet())
                }
            } else {
                val inet4RouteRange = options.inet4RouteRange
                while (inet4RouteRange.hasNext()) {
                    adapter.addRoute(inet4RouteRange.next().toIpSubnet())
                }

                val inet6RouteRange = options.inet6RouteRange
                while (inet6RouteRange.hasNext()) {
                    adapter.addRoute(inet6RouteRange.next().toIpSubnet())
                }
            }

            val includePackages = options.includePackage.toList().distinct()
            val excludePackages = options.excludePackage.toList().distinct()
            var runtimePackageBypassesVpn = false
            var effectiveAllowedApplications = 0

            includePackages.forEach { packageName ->
                if (packageName == runtimePackageName) {
                    runtimePackageBypassesVpn = true
                } else {
                    adapter.addAllowedApplication(packageName)
                    effectiveAllowedApplications += 1
                }
            }

            excludePackages.forEach { packageName ->
                adapter.addDisallowedApplication(packageName)
                if (packageName == runtimePackageName) {
                    runtimePackageBypassesVpn = true
                }
            }

            if (includePackages.isNotEmpty()) {
                // In allowlist mode the Tunguska runtime bypasses the VPN by staying out of the
                // routed package set entirely.
                runtimePackageBypassesVpn = true
                require(effectiveAllowedApplications > 0) {
                    "Allowlist mode has no routable applications after excluding the Tunguska runtime package."
                }
            } else if (!runtimePackageBypassesVpn) {
                adapter.addDisallowedApplication(runtimePackageName)
                runtimePackageBypassesVpn = true
            }

            check(runtimePackageBypassesVpn) {
                "Embedded sing-box runtime requires the Tunguska runtime package to bypass the VPN path."
            }
        }
        val proxyAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && options.isHTTPProxyEnabled
        if (proxyAvailable) {
            val server = options.httpProxyServer
            val port = options.httpProxyServerPort
            if (server.isNotBlank() && port > 0) {
                builder.setHttpProxy(
                    ProxyInfo.buildDirectProxy(
                        server,
                        port,
                        options.httpProxyBypassDomain.toList(),
                    ),
                )
                synchronized(lock) {
                    systemProxyAvailable = true
                    systemProxyEnabled = true
                }
            } else {
                synchronized(lock) {
                    systemProxyAvailable = true
                    systemProxyEnabled = false
                }
            }
        } else {
            synchronized(lock) {
                systemProxyAvailable = false
                systemProxyEnabled = false
            }
        }
        if (adapter.unsupportedExcludedRoutes.isNotEmpty()) {
            VpnRuntimeStore.recordRuntimeTelemetry(
                strategy = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
                nativeEvent = "Ignored ${adapter.unsupportedExcludedRoutes.size} excluded routes below Android 13.",
            )
        }
        val descriptor = builder.establish()
            ?: error("android: the application is not prepared or is revoked")
        synchronized(lock) {
            tunDescriptor = descriptor
        }
        return descriptor.fd
    }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            error("android: connection owner lookup requires Android 10+")
        }
        val uid = connectivityManager.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort),
        )
        if (uid == android.os.Process.INVALID_UID) {
            error("android: connection owner not found")
        }
        val packages = context.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
        return ConnectionOwner().apply {
            userId = uid
            userName = packages.firstOrNull() ?: uid.toString()
            processPath = ""
            setAndroidPackageNames(SimpleStringIterator(packages))
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        synchronized(lock) {
            defaultInterfaceListener = listener
            if (defaultNetworkCallback == null) {
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        refreshDefaultInterface()
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities,
                    ) {
                        refreshDefaultInterface()
                    }

                    override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
                        refreshDefaultInterface()
                    }

                    override fun onLost(network: Network) {
                        refreshDefaultInterface()
                    }
                }
                runCatching { connectivityManager.registerDefaultNetworkCallback(callback) }
                defaultNetworkCallback = callback
            }
        }
        refreshDefaultInterface()
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val callback = synchronized(lock) {
            if (defaultInterfaceListener !== listener && defaultInterfaceListener != null) {
                return@synchronized null
            }
            defaultInterfaceListener = null
            val snapshot = defaultNetworkCallback
            defaultNetworkCallback = null
            snapshot
        }
        callback?.let { registeredCallback ->
            runCatching { connectivityManager.unregisterNetworkCallback(registeredCallback) }
        }
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val networkInterfaces = runCatching {
            NetworkInterface.getNetworkInterfaces()?.let { Collections.list(it) }.orEmpty()
        }.getOrDefault(emptyList())
        val interfaces = mutableListOf<LibboxNetworkInterface>()
        allNetworks().forEach { network ->
            val linkProperties = runCatching { connectivityManager.getLinkProperties(network) }.getOrNull() ?: return@forEach
            val networkCapabilities = runCatching { connectivityManager.getNetworkCapabilities(network) }.getOrNull()
                ?: return@forEach
            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return@forEach
            }
            val interfaceName = linkProperties.interfaceName ?: return@forEach
            val networkInterface = networkInterfaces.firstOrNull { it.name == interfaceName } ?: return@forEach
            val boxInterface = LibboxNetworkInterface().apply {
                name = interfaceName
                dnsServer = SimpleStringIterator(linkProperties.dnsServers.mapNotNull { it.hostAddress })
                type = when {
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                index = networkInterface.index
                runCatching { mtu = networkInterface.mtu }
                addresses = SimpleStringIterator(
                    networkInterface.interfaceAddresses.mapNotNull { it.toPrefix() },
                )
                var dumpFlags = 0
                if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    dumpFlags = dumpFlags or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                }
                if (runCatching { networkInterface.isLoopback }.getOrDefault(false)) {
                    dumpFlags = dumpFlags or OsConstants.IFF_LOOPBACK
                }
                if (runCatching { networkInterface.isPointToPoint }.getOrDefault(false)) {
                    dumpFlags = dumpFlags or OsConstants.IFF_POINTOPOINT
                }
                if (runCatching { networkInterface.supportsMulticast() }.getOrDefault(false)) {
                    dumpFlags = dumpFlags or OsConstants.IFF_MULTICAST
                }
                flags = dumpFlags
                metered = !networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
            interfaces += boxInterface
        }
        return SimpleNetworkInterfaceIterator(interfaces.iterator())
    }

    override fun includeAllNetworks(): Boolean = false

    override fun localDNSTransport(): LocalDNSTransport = localDnsTransport

    override fun readWIFIState(): WIFIState? {
        val manager = wifiManager ?: return null
        return runCatching {
            @Suppress("DEPRECATION")
            val wifiInfo = manager.connectionInfo ?: return null
            var ssid = wifiInfo.ssid ?: ""
            if (ssid == "<unknown ssid>") {
                return Libbox.newWIFIState("", "")
            }
            if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid = ssid.substring(1, ssid.length - 1)
            }
            Libbox.newWIFIState(ssid, wifiInfo.bssid ?: "")
        }.getOrNull()
    }

    override fun sendNotification(notification: Notification) {
        val summary = listOf(notification.title, notification.subtitle, notification.body)
            .filter { it.isNotBlank() }
            .joinToString(" / ")
        if (summary.isNotBlank()) {
            VpnRuntimeStore.recordRuntimeTelemetry(
                strategy = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
                nativeEvent = "sing-box notification: $summary",
            )
        }
    }

    override fun systemCertificates(): StringIterator {
        val certificates = mutableListOf<String>()
        runCatching {
            val keyStore = KeyStore.getInstance("AndroidCAStore")
            keyStore.load(null, null)
            val encoder = Base64.getMimeEncoder(64, "\n".toByteArray())
            val aliases = keyStore.aliases()
            while (aliases.hasMoreElements()) {
                val certificate = keyStore.getCertificate(aliases.nextElement()) ?: continue
                certificates += buildString {
                    appendLine("-----BEGIN CERTIFICATE-----")
                    appendLine(encoder.encodeToString(certificate.encoded))
                    append("-----END CERTIFICATE-----")
                }
            }
        }
        return SimpleStringIterator(certificates)
    }

    override fun underNetworkExtension(): Boolean = false

    override fun clearDNSCache() = Unit

    fun close() {
        closeTun()
        val callback = synchronized(lock) {
            defaultInterfaceListener = null
            val snapshot = defaultNetworkCallback
            defaultNetworkCallback = null
            snapshot
        }
        callback?.let { registeredCallback ->
            runCatching { connectivityManager.unregisterNetworkCallback(registeredCallback) }
        }
    }

    fun closeTun() {
        val descriptor = synchronized(lock) {
            val snapshot = tunDescriptor
            tunDescriptor = null
            snapshot
        }
        runCatching { descriptor?.close() }
    }

    fun currentSystemProxyStatus(): SystemProxyStatus = synchronized(lock) {
        SystemProxyStatus().apply {
            available = systemProxyAvailable
            enabled = systemProxyEnabled
        }
    }

    fun setSystemProxyEnabled(enabled: Boolean) {
        synchronized(lock) {
            systemProxyEnabled = systemProxyAvailable && enabled
        }
    }

    private fun refreshDefaultInterface() {
        val listener = synchronized(lock) { defaultInterfaceListener } ?: return
        val snapshot = resolveDefaultNetworkSnapshot()
        if (snapshot == null) {
            listener.updateDefaultInterface("", -1, false, false)
            return
        }
        listener.updateDefaultInterface(snapshot.interfaceName, snapshot.interfaceIndex, false, false)
    }

    private fun requireDefaultNetwork(): Network = resolveDefaultNetworkSnapshot()?.network
        ?: error("missing default interface")

    private fun resolveDefaultNetworkSnapshot(): DefaultNetworkSnapshot? {
        val candidates = LinkedHashSet<Network>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            connectivityManager.activeNetwork?.let(candidates::add)
        }
        candidates += allNetworks()
        return candidates.firstNotNullOfOrNull(::defaultNetworkSnapshot)
    }

    private fun defaultNetworkSnapshot(network: Network): DefaultNetworkSnapshot? {
        val capabilities = runCatching { connectivityManager.getNetworkCapabilities(network) }.getOrNull() ?: return null
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return null
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return null
        }
        val linkProperties = runCatching { connectivityManager.getLinkProperties(network) }.getOrNull() ?: return null
        val interfaceName = linkProperties.interfaceName ?: return null
        val interfaceIndex = runCatching { NetworkInterface.getByName(interfaceName)?.index ?: -1 }.getOrDefault(-1)
        if (interfaceIndex < 0) {
            return null
        }
        return DefaultNetworkSnapshot(
            network = network,
            interfaceName = interfaceName,
            interfaceIndex = interfaceIndex,
        )
    }

    private fun allNetworks(): List<Network> = runCatching { connectivityManager.allNetworks.toList() }
        .getOrDefault(emptyList())

    private data class DefaultNetworkSnapshot(
        val network: Network,
        val interfaceName: String,
        val interfaceIndex: Int,
    )

}

private class SingboxLocalDnsTransport(
    private val defaultNetworkProvider: () -> Network,
) : LocalDNSTransport {
    override fun raw(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            error("android: raw DNS exchange requires Android 10+")
        }
        val defaultNetwork = defaultNetworkProvider()
        runBlocking {
            suspendCoroutine { continuation: Continuation<Unit> ->
                val signal = CancellationSignal()
                ctx.onCancel(signal::cancel)
                val callback = object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(answer: ByteArray, rcode: Int) {
                        if (rcode == 0) {
                            ctx.rawSuccess(answer)
                        } else {
                            ctx.errorCode(rcode)
                        }
                        resumeSuccess(continuation)
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        when (val cause = error.cause) {
                            is ErrnoException -> {
                                ctx.errnoCode(cause.errno)
                                resumeSuccess(continuation)
                                return
                            }
                        }
                        resumeFailure(continuation, error)
                    }
                }
                DnsResolver.getInstance().rawQuery(
                    defaultNetwork,
                    message,
                    DnsResolver.FLAG_NO_RETRY,
                    Dispatchers.IO.asExecutor(),
                    signal,
                    callback,
                )
            }
        }
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        val defaultNetwork = defaultNetworkProvider()
        runBlocking {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                suspendCoroutine { continuation: Continuation<Unit> ->
                    val signal = CancellationSignal()
                    ctx.onCancel(signal::cancel)
                    val callback = object : DnsResolver.Callback<Collection<java.net.InetAddress>> {
                        override fun onAnswer(answer: Collection<java.net.InetAddress>, rcode: Int) {
                            if (rcode == 0) {
                                ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n"))
                            } else {
                                ctx.errorCode(rcode)
                            }
                            resumeSuccess(continuation)
                        }

                        override fun onError(error: DnsResolver.DnsException) {
                            when (val cause = error.cause) {
                                is ErrnoException -> {
                                    ctx.errnoCode(cause.errno)
                                    resumeSuccess(continuation)
                                    return
                                }
                            }
                            resumeFailure(continuation, error)
                        }
                    }
                    val type = when {
                        network.endsWith("4") -> DnsResolver.TYPE_A
                        network.endsWith("6") -> DnsResolver.TYPE_AAAA
                        else -> null
                    }
                    if (type != null) {
                        DnsResolver.getInstance().query(
                            defaultNetwork,
                            domain,
                            type,
                            DnsResolver.FLAG_NO_RETRY,
                            Dispatchers.IO.asExecutor(),
                            signal,
                            callback,
                        )
                    } else {
                        DnsResolver.getInstance().query(
                            defaultNetwork,
                            domain,
                            DnsResolver.FLAG_NO_RETRY,
                            Dispatchers.IO.asExecutor(),
                            signal,
                            callback,
                        )
                    }
                }
            } else {
                val answer = try {
                    defaultNetwork.getAllByName(domain)
                } catch (_: UnknownHostException) {
                    ctx.errorCode(3)
                    return@runBlocking
                }
                ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n"))
            }
        }
    }

    private fun resumeSuccess(continuation: Continuation<Unit>) {
        runCatching { continuation.resume(Unit) }
    }

    private fun resumeFailure(continuation: Continuation<Unit>, error: Throwable) {
        runCatching { continuation.resumeWith(Result.failure(error)) }
    }
}

private class VpnBuilderAdapter(
    private val builder: VpnService.Builder,
) : TunnelBuilderAdapter {
    val unsupportedExcludedRoutes: MutableList<IpSubnet> = mutableListOf()
    private val disallowedPackages: MutableSet<String> = linkedSetOf()

    override fun setSession(label: String) {
        builder.setSession(label)
    }

    override fun setMtu(mtu: Int) {
        builder.setMtu(mtu)
    }

    override fun addAddress(subnet: IpSubnet) {
        builder.addAddress(subnet.address, subnet.prefixLength)
    }

    override fun addRoute(subnet: IpSubnet) {
        builder.addRoute(subnet.address, subnet.prefixLength)
    }

    override fun excludeRoute(subnet: IpSubnet) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            builder.excludeRoute(
                android.net.IpPrefix(java.net.InetAddress.getByName(subnet.address), subnet.prefixLength),
            )
        } else {
            unsupportedExcludedRoutes += subnet
        }
    }

    override fun addAllowedApplication(packageName: String) {
        try {
            builder.addAllowedApplication(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            throw IllegalArgumentException("Allowed application '$packageName' is not installed.")
        }
    }

    override fun addDisallowedApplication(packageName: String) {
        try {
            if (disallowedPackages.add(packageName)) {
                builder.addDisallowedApplication(packageName)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            throw IllegalArgumentException("Disallowed application '$packageName' is not installed.")
        }
    }
}

private class SimpleNetworkInterfaceIterator(
    private val iterator: Iterator<LibboxNetworkInterface>,
) : NetworkInterfaceIterator {
    override fun hasNext(): Boolean = iterator.hasNext()

    override fun next(): LibboxNetworkInterface = iterator.next()
}

private class SimpleStringIterator(values: Iterable<String>) : StringIterator {
    private val snapshot: List<String> = values.toList()
    private val iterator: Iterator<String> = snapshot.iterator()

    override fun len(): Int = snapshot.size

    override fun hasNext(): Boolean = iterator.hasNext()

    override fun next(): String = iterator.next()
}

private object SingboxBinaryLocator {
    fun locate(context: Context): File {
        val libDir = File(context.applicationInfo.nativeLibraryDir)
        val box = File(libDir, "libbox.so")
        require(box.isFile) {
            "Missing libbox.so under ${libDir.absolutePath}. Resolve libbox-android from GitHub Packages or publish a local override with tools/runtime/fetch-singbox-embedded.ps1 before building."
        }
        return box
    }
}

private fun StringBox?.unwrap(): String = this?.value ?: ""

private fun StringIterator.toList(): List<String> {
    val values = mutableListOf<String>()
    while (hasNext()) {
        values += next()
    }
    return values
}

private fun RoutePrefix.toIpSubnet(): IpSubnet = IpSubnet(
    address = address(),
    prefixLength = prefix(),
)

private fun InterfaceAddress.toPrefix(): String? {
    val rawAddress = address ?: return null
    val hostAddress = if (rawAddress is Inet6Address) {
        Inet6Address.getByAddress(rawAddress.address).hostAddress
    } else {
        rawAddress.hostAddress
    }
    return "$hostAddress/$networkPrefixLength"
}
