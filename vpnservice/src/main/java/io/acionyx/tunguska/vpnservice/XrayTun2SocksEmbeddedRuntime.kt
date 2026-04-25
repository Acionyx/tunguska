package io.acionyx.tunguska.vpnservice

import android.content.Context
import android.content.pm.PackageManager
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import io.acionyx.tunguska.domain.CanonicalJson
import io.acionyx.tunguska.domain.DnsMode
import io.acionyx.tunguska.domain.EffectiveRoutingPolicyResolver
import io.acionyx.tunguska.domain.EncryptedDnsKind
import io.acionyx.tunguska.domain.NetworkProtocol
import io.acionyx.tunguska.domain.OutboundProtocolId
import io.acionyx.tunguska.domain.ProfileIr
import io.acionyx.tunguska.domain.RouteAction
import io.acionyx.tunguska.domain.RouteRule
import io.acionyx.tunguska.domain.displayLabel
import io.acionyx.tunguska.domain.outboundShapeLabel
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object XrayCompatLaneMessaging {
    fun stagedShapeLabel(request: StagedRuntimeRequest): String? {
        val protocolId = request.profileProtocolId ?: return null
        val transportId = request.profileTransportId ?: return null
        return outboundShapeLabel(protocolId, transportId, request.profileSecurityId)
    }

    fun canonicalPayloadRequiredSummary(request: StagedRuntimeRequest): String {
        val stagedShape = stagedShapeLabel(request)
        return if (stagedShape != null) {
            "The xray+tun2socks runtime requires the canonical profile payload to rebuild the staged $stagedShape shape for fallback compilation."
        } else {
            "The xray+tun2socks runtime requires the canonical profile payload for fallback compilation."
        }
    }

    fun canonicalDecodeFailureSummary(
        request: StagedRuntimeRequest,
        error: Throwable,
    ): String {
        val detail = error.message ?: error.javaClass.simpleName
        val stagedShape = stagedShapeLabel(request)
        return if (stagedShape != null) {
            "The xray+tun2socks runtime could not decode the canonical profile payload for the staged $stagedShape shape: $detail"
        } else {
            "The xray+tun2socks runtime could not decode the canonical profile payload: $detail"
        }
    }

    fun validationFailureSummary(
        profile: ProfileIr,
        issuesSummary: String,
    ): String = "Fallback validation failed for the ${profile.outboundShapeLabel()} shape: $issuesSummary"

    fun compileFailureSummary(
        profile: ProfileIr,
        error: Throwable,
    ): String {
        val sectionDetail = (error as? XrayCompatCompileException)
            ?.let {
                " while compiling the ${it.section.displayLabel} section"
            }
            .orEmpty()
        return "The xray+tun2socks compatibility lane could not compile the ${profile.outboundShapeLabel()} shape$sectionDetail: ${error.message ?: error.javaClass.simpleName}"
    }

    fun preparedSummary(
        request: StagedRuntimeRequest,
        profile: ProfileIr,
    ): String = "Prepared xray+tun2socks runtime for ${profile.outboundShapeLabel()} (${request.compiledConfig.configHash.take(12)})."
}

internal enum class XrayCompatCompileSection(val displayLabel: String) {
    DNS("DNS"),
    ROUTING("Routing"),
}

internal class XrayCompatCompileException(
    val section: XrayCompatCompileSection,
    val fieldPath: String,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

class XrayTun2SocksEmbeddedHost(
    private val clock: () -> Long = System::currentTimeMillis,
) : EmbeddedEngineHost {
    // The staged request is still produced by the sing-box compiler contract.
    // This fallback host consumes the canonical profile payload and replaces
    // only the runtime strategy, not the app-facing compiled engine id.
    override val engineId: String = "singbox"
    override val strategyId: EmbeddedRuntimeStrategyId = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS

    override fun prepare(
        workspace: EngineSessionWorkspace,
        request: StagedRuntimeRequest,
        runtimeDependencies: EmbeddedRuntimeDependencies,
    ): EmbeddedEngineHostPreparation {
        val service = runtimeDependencies.vpnService
        val context = runtimeDependencies.context ?: service?.applicationContext
        if (service == null || context == null) {
            return failure(
                workspace = workspace,
                summary = "The xray+tun2socks runtime requires a live VpnService runtime context.",
            )
        }
        val profileCanonicalJson = request.profileCanonicalJson
            ?: return failure(
                workspace = workspace,
                summary = XrayCompatLaneMessaging.canonicalPayloadRequiredSummary(request),
            )
        val profile = runCatching {
            CanonicalJson.instance.decodeFromString<ProfileIr>(profileCanonicalJson)
        }.getOrElse { error ->
            return failure(
                workspace = workspace,
                summary = XrayCompatLaneMessaging.canonicalDecodeFailureSummary(request, error),
            )
        }
        val issues = profile.validate()
        if (issues.isNotEmpty()) {
            return failure(
                workspace = workspace,
                summary = XrayCompatLaneMessaging.validationFailureSummary(
                    profile = profile,
                    issuesSummary = issues.joinToString { issue -> "${issue.field}: ${issue.message}" },
                ),
            )
        }
        val binaries = runCatching {
            XrayBinaryLocator.locate(context)
        }.getOrElse { error ->
            return failure(
                workspace = workspace,
                summary = error.message ?: error.javaClass.simpleName,
            )
        }
        val nativeBridgeStatus = runCatching {
            VpnNativeProcessBridge.ensureLoaded()
            null
        }.getOrElse { error ->
            error.message ?: error.javaClass.simpleName
        }
        if (nativeBridgeStatus != null) {
            return failure(
                workspace = workspace,
                summary = "The xray+tun2socks runtime helper is unavailable: $nativeBridgeStatus",
            )
        }
        val geoAssetStatus = runCatching {
            XrayGeoAssetStager.stage(
                context = context,
                workspaceRoot = workspace.rootDir,
            )
            null
        }.getOrElse { error ->
            error.message ?: error.javaClass.simpleName
        }
        if (geoAssetStatus != null) {
            return failure(
                workspace = workspace,
                summary = "The xray+tun2socks geodata assets are unavailable: $geoAssetStatus",
            )
        }
        return EmbeddedEngineHostPreparation(
            result = EmbeddedEngineHostResult(
                status = EmbeddedEngineHostStatus.READY,
                summary = XrayCompatLaneMessaging.preparedSummary(request, profile),
                preparedAtEpochMs = clock(),
                workspacePath = workspace.rootDir.absolutePath,
            ),
            session = XrayTun2SocksEmbeddedEngineSession(
                service = service,
                request = request,
                profile = profile,
                workspace = workspace,
                binaries = binaries,
                clock = clock,
            ),
        )
    }

    private fun failure(
        workspace: EngineSessionWorkspace,
        summary: String,
        error: Throwable? = null,
    ): EmbeddedEngineHostPreparation = EmbeddedEngineHostPreparation(
        result = EmbeddedEngineHostResult(
            status = EmbeddedEngineHostStatus.FAILED,
            summary = summary,
            preparedAtEpochMs = clock(),
            workspacePath = workspace.rootDir.absolutePath,
            errorSection = (error as? XrayCompatCompileException)?.section?.displayLabel,
            errorFieldPath = (error as? XrayCompatCompileException)?.fieldPath,
        ),
    )
}

private class XrayTun2SocksEmbeddedEngineSession(
    private val service: VpnService,
    private val request: StagedRuntimeRequest,
    private val profile: ProfileIr,
    private val workspace: EngineSessionWorkspace,
    private val binaries: XrayBinarySet,
    private val clock: () -> Long,
) : EmbeddedEngineSession {
    private val lock = Any()
    private val bootstrapper = TunnelRuntimeBootstrapper(clock = clock)
    private var activeState: XrayTun2SocksActiveState? = null

    override fun start(): EmbeddedEngineSessionResult {
        synchronized(lock) {
            if (activeState != null) {
                return failure("xray+tun2socks runtime is already active.")
            }
        }
        RuntimeListenerAllowanceStore.clear()
        val bridge = AuthenticatedLocalBridge.generate()
        VpnRuntimeStore.recordRuntimeTelemetry(
            strategy = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
            bridgePort = bridge.port,
            nativeEvent = "Generated authenticated loopback bridge on 127.0.0.1:${bridge.port}.",
        )
        val compiled = runCatching {
            XrayCompatConfigCompiler.compile(profile = profile, bridge = bridge)
        }.getOrElse { error ->
            return failure(
                XrayCompatLaneMessaging.compileFailureSummary(profile, error),
                error,
            )
        }
        Log.i(
            TAG,
            "Preparing xray+tun2socks ${profile.outboundShapeLabel()} profile='${profile.name}' config='${request.compiledConfig.configHash.take(12)}'.",
        )
        val xrayConfigFile = File(workspace.rootDir, "xray-runtime.json").apply {
            writeText(compiled.json)
        }
        val tunnelBuilder = XrayTunnelRuntimeBuilder(
            service = service,
            sessionPlan = request.plan,
            vpnDnsServers = compiled.vpnDnsServers,
            appPackageName = service.packageName,
        )
        val leaseResult = bootstrapper.establishLease(
            builder = tunnelBuilder,
            sessionPlan = request.plan,
        )
        VpnRuntimeStore.recordBootstrap(leaseResult.result)
        val lease = leaseResult.lease ?: return failure(leaseResult.result.summary)
        val tunnelHandle = lease.handle as? ParcelFileDescriptorTunnelHandle ?: run {
            lease.handle.close()
            return failure("xray+tun2socks runtime requires a ParcelFileDescriptor-backed tunnel handle.")
        }
        var xrayProcess: Process? = null
        var tun2socksPid: Long = -1L
        var tunDupDescriptor: ParcelFileDescriptor? = null
        return runCatching {
            val rlimitResult = runCatching { VpnNativeProcessBridge.nativeSetMaxFds(MAX_RUNTIME_FDS) }.getOrDefault(0)
            if (rlimitResult != 0) {
                Log.w(TAG, "nativeSetMaxFds returned $rlimitResult; continuing with current limits.")
                VpnRuntimeStore.recordRuntimeTelemetry(
                    nativeEvent = "nativeSetMaxFds returned $rlimitResult; continuing with current limits.",
                )
            }

            xrayProcess = startXrayProcess(
                configFile = xrayConfigFile,
                workingDir = workspace.rootDir,
            )
            VpnRuntimeStore.recordRuntimeTelemetry(
                ownPackageBypassesVpn = tunnelBuilder.runtimePackageBypassesVpn,
                nativeEvent = "Started xray child process.",
            )
            consumeProcessLogs(xrayProcess, "xray")
            Thread.sleep(XRAY_BOOT_DELAY_MS)
            if (!isProcessAlive(xrayProcess)) {
                error("xray process exited during startup.")
            }
            runCatching { xrayConfigFile.delete() }

            tunDupDescriptor = ParcelFileDescriptor.dup(tunnelHandle.descriptor.fileDescriptor)
            val tunFd = tunDupDescriptor?.fd ?: error("Failed to duplicate the tunnel descriptor for tun2socks.")
            tun2socksPid = startTun2socks(
                tunnelFd = tunFd,
                bridge = bridge,
                mtu = lease.spec.mtu,
            )
            VpnRuntimeStore.recordRuntimeTelemetry(
                bridgePort = bridge.port,
                tun2socksPid = tun2socksPid.takeIf { it > 0L },
                ownPackageBypassesVpn = tunnelBuilder.runtimePackageBypassesVpn,
                nativeEvent = "Started tun2socks pid=$tun2socksPid on bridge port ${bridge.port}.",
            )
            if (tun2socksPid <= 0L) {
                error("nativeStartProcessWithFd failed: errno=${-tun2socksPid}.")
            }
            tunDupDescriptor.close()
            tunDupDescriptor = null
            Thread.sleep(TUN2SOCKS_BOOT_DELAY_MS)
            if (!isPidAlive(tun2socksPid)) {
                error("tun2socks process exited during startup.")
            }

            RuntimeListenerAllowanceStore.replace(
                setOf(
                    RuntimeAllowedLoopbackListener(
                        protocol = "tcp",
                        address = "127.0.0.1",
                        port = bridge.port,
                    ),
                ),
            )
            synchronized(lock) {
                activeState = XrayTun2SocksActiveState(
                    xrayProcess = xrayProcess,
                    tun2socksPid = tun2socksPid,
                    lease = lease,
                    bridge = bridge,
                    ownPackageBypassesVpn = tunnelBuilder.runtimePackageBypassesVpn,
                )
            }
            EmbeddedEngineSessionResult(
                status = EmbeddedEngineSessionStatus.STARTED,
                summary = "xray+tun2socks runtime started for ${request.compiledConfig.configHash.take(12)}.",
                observedAtEpochMs = clock(),
            )
        }.getOrElse { error ->
            RuntimeListenerAllowanceStore.clear()
            runCatching { tunDupDescriptor?.close() }
            runCatching { xrayConfigFile.delete() }
            stopInternal(
                state = XrayTun2SocksActiveState(
                    xrayProcess = xrayProcess,
                    tun2socksPid = tun2socksPid,
                    lease = lease,
                    bridge = bridge,
                    ownPackageBypassesVpn = tunnelBuilder.runtimePackageBypassesVpn,
                ),
            )
            failure(error.message ?: error.javaClass.simpleName)
        }
    }

    override fun stop(): EmbeddedEngineSessionResult {
        val state = synchronized(lock) {
            val snapshot = activeState
            activeState = null
            snapshot
        }
        if (state == null) {
            RuntimeListenerAllowanceStore.clear()
            return EmbeddedEngineSessionResult(
                status = EmbeddedEngineSessionStatus.STOPPED,
                summary = "xray+tun2socks runtime is already stopped.",
                observedAtEpochMs = clock(),
            )
        }
        val errors = stopInternal(state)
        RuntimeListenerAllowanceStore.clear()
        return if (errors.isEmpty()) {
            EmbeddedEngineSessionResult(
                status = EmbeddedEngineSessionStatus.STOPPED,
                summary = "xray+tun2socks runtime stopped.",
                observedAtEpochMs = clock(),
            )
        } else {
            failure(errors.first())
        }
    }

    override fun health(): EmbeddedEngineSessionHealthResult {
        val state = synchronized(lock) { activeState }
            ?: return EmbeddedEngineSessionHealthResult(
                status = EmbeddedEngineSessionHealthStatus.FAILED,
                summary = "xray+tun2socks runtime is not active.",
                observedAtEpochMs = clock(),
            )
        if (!isProcessAlive(state.xrayProcess)) {
            return EmbeddedEngineSessionHealthResult(
                status = EmbeddedEngineSessionHealthStatus.FAILED,
                summary = "xray process exited unexpectedly.",
                observedAtEpochMs = clock(),
            )
        }
        if (!isPidAlive(state.tun2socksPid)) {
            return EmbeddedEngineSessionHealthResult(
                status = EmbeddedEngineSessionHealthStatus.FAILED,
                summary = "tun2socks process exited unexpectedly.",
                observedAtEpochMs = clock(),
            )
        }
        return EmbeddedEngineSessionHealthResult(
            status = EmbeddedEngineSessionHealthStatus.HEALTHY,
            summary = "xray+tun2socks runtime is healthy on 127.0.0.1:${state.bridge.port}.",
            observedAtEpochMs = clock(),
        )
    }

    override fun observeEgressIp(endpoints: List<String>): RuntimeEgressIpObservation {
        val state = synchronized(lock) { activeState }
            ?: return RuntimeEgressIpProbe.unavailable("xray+tun2socks runtime is not active.")
        return RuntimeEgressIpProbe.observeViaSocksBridge(
            bridge = state.bridge,
            endpoints = endpoints,
        )
    }

    private fun startXrayProcess(
        configFile: File,
        workingDir: File,
    ): Process {
        val processBuilder = ProcessBuilder(
            binaries.xray.absolutePath,
            "run",
            "-c",
            configFile.absolutePath,
        )
        processBuilder.directory(workingDir)
        processBuilder.redirectErrorStream(true)
        processBuilder.environment()["XRAY_LOCATION_ASSET"] = workingDir.absolutePath
        return processBuilder.start()
    }

    private fun startTun2socks(
        tunnelFd: Int,
        bridge: AuthenticatedLocalBridge,
        mtu: Int,
    ): Long {
        val proxyUrl = "socks5://${bridge.user}:${bridge.password}@127.0.0.1:${bridge.port}"
        val args = arrayOf(
            binaries.tun2socks.absolutePath,
            "-device",
            "fd://$tunnelFd",
            "-proxy",
            proxyUrl,
            "-mtu",
            mtu.toString(),
            "-loglevel",
            "error",
        )
        return VpnNativeProcessBridge.nativeStartProcessWithFd(
            cmd = binaries.tun2socks.absolutePath,
            args = args,
            envKeys = emptyArray(),
            envVals = emptyArray(),
            keepFd = tunnelFd,
            maxFds = MAX_RUNTIME_FDS,
        )
    }

    private fun stopInternal(state: XrayTun2SocksActiveState): List<String> {
        val errors = mutableListOf<String>()
        if (state.tun2socksPid > 0L) {
            val killResult = runCatching {
                VpnNativeProcessBridge.nativeKillProcess(state.tun2socksPid)
            }.getOrElse { error ->
                errors += error.message ?: error.javaClass.simpleName
                Int.MIN_VALUE
            }
            if (killResult != 0 && killResult != Int.MIN_VALUE) {
                errors += "nativeKillProcess returned $killResult for tun2socks."
            }
            VpnRuntimeStore.recordRuntimeTelemetry(
                nativeEvent = "Stopped tun2socks pid=${state.tun2socksPid} with result=$killResult.",
            )
        }
        state.xrayProcess?.let { process ->
            runCatching {
                process.destroyForcibly()
                process.waitFor()
                VpnRuntimeStore.recordRuntimeTelemetry(
                    nativeEvent = "Destroyed xray child process.",
                )
            }.onFailure { error ->
                errors += error.message ?: error.javaClass.simpleName
            }
        }
        runCatching { state.lease.handle.close() }.onFailure { error ->
            errors += error.message ?: error.javaClass.simpleName
        }
        return errors
    }

    private fun consumeProcessLogs(process: Process, name: String) {
        Thread {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        when {
                            line.contains("accepted ", ignoreCase = true) -> {
                                VpnRuntimeStore.recordRuntimeTelemetry(
                                    routedTrafficObserved = true,
                                    lastRoutedTrafficAtEpochMs = clock(),
                                    xrayLogLine = "[$name] $line",
                                )
                            }

                            line.contains("dns", ignoreCase = true) &&
                                (line.contains("fail", ignoreCase = true) || line.contains("error", ignoreCase = true)) -> {
                                VpnRuntimeStore.recordRuntimeTelemetry(
                                    dnsFailureObserved = true,
                                    lastDnsFailureSummary = line.take(240),
                                    xrayLogLine = "[$name] $line",
                                )
                            }

                            else -> {
                                VpnRuntimeStore.recordRuntimeTelemetry(
                                    xrayLogLine = "[$name] $line",
                                )
                            }
                        }
                    }
                }
            }
        }.apply {
            isDaemon = true
            this.name = "tunguska-$name-log"
            start()
        }
    }

    private fun isProcessAlive(process: Process?): Boolean {
        process ?: return false
        return try {
            process.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    private fun isPidAlive(pid: Long): Boolean {
        if (pid <= 0L) return false
        return File("/proc/$pid/stat").exists()
    }

    private fun failure(
        message: String,
        error: Throwable? = null,
    ): EmbeddedEngineSessionResult = EmbeddedEngineSessionResult(
        status = EmbeddedEngineSessionStatus.FAILED,
        summary = message,
        observedAtEpochMs = clock(),
        errorSection = (error as? XrayCompatCompileException)?.section?.displayLabel,
        errorFieldPath = (error as? XrayCompatCompileException)?.fieldPath,
    )
}

private data class XrayTun2SocksActiveState(
    val xrayProcess: Process?,
    val tun2socksPid: Long,
    val lease: TunnelInterfaceLease,
    val bridge: AuthenticatedLocalBridge,
    val ownPackageBypassesVpn: Boolean,
)

private data class XrayBinarySet(
    val xray: File,
    val tun2socks: File,
)

private object XrayGeoAssetStager {
    fun stage(
        context: Context,
        workspaceRoot: File,
    ) {
        stageAsset(
            context = context,
            workspaceRoot = workspaceRoot,
            assetName = "geoip.dat",
        )
        stageAsset(
            context = context,
            workspaceRoot = workspaceRoot,
            assetName = "geosite.dat",
        )
    }

    private fun stageAsset(
        context: Context,
        workspaceRoot: File,
        assetName: String,
    ) {
        val destination = File(workspaceRoot, assetName)
        context.assets.open("xray/$assetName").use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        require(destination.isFile && destination.length() > 0L) {
            "Failed to stage $assetName into ${destination.absolutePath}."
        }
    }
}

private object XrayBinaryLocator {
    fun locate(context: Context): XrayBinarySet {
        val libDir = File(context.applicationInfo.nativeLibraryDir)
        val xray = File(libDir, "libxray.so")
        val tun2socks = File(libDir, "libtun2socks.so")
        require(xray.isFile) {
            "Missing libxray.so under ${libDir.absolutePath}. Run tools/runtime/fetch-xray-fallback.ps1 before building."
        }
        require(tun2socks.isFile) {
            "Missing libtun2socks.so under ${libDir.absolutePath}. Run tools/runtime/fetch-xray-fallback.ps1 before building."
        }
        return XrayBinarySet(
            xray = xray,
            tun2socks = tun2socks,
        )
    }
}

private class XrayTunnelRuntimeBuilder(
    service: VpnService,
    private val sessionPlan: TunnelSessionPlan,
    vpnDnsServers: List<String>,
    private val appPackageName: String,
) : TunnelRuntimeBuilder {
    private val builder = service.Builder()
    private val unsupportedExcludedRoutesMutable = mutableListOf<IpSubnet>()
    private val disallowedPackages = linkedSetOf<String>()
    private var hasAllowedApplications: Boolean = false
    private var effectiveAllowedApplications: Int = 0
    private var runtimePackageBypassesVpnMutable: Boolean = false

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        vpnDnsServers
            .distinct()
            .filter { it.isNotBlank() }
            .forEach { dnsServer ->
                runCatching { builder.addDnsServer(dnsServer) }.getOrElse { error ->
                    throw IllegalArgumentException("addDnsServer '$dnsServer' failed: ${error.message}", error)
                }
            }
        runCatching { builder.allowFamily(OsConstants.AF_INET) }.getOrElse { error ->
            throw IllegalArgumentException("allowFamily(AF_INET) failed: ${error.message}", error)
        }
        runCatching { builder.allowFamily(OsConstants.AF_INET6) }.getOrElse { error ->
            throw IllegalArgumentException("allowFamily(AF_INET6) failed: ${error.message}", error)
        }
    }

    override val unsupportedExcludedRoutes: List<IpSubnet>
        get() = unsupportedExcludedRoutesMutable.toList()

    val runtimePackageBypassesVpn: Boolean
        get() = runtimePackageBypassesVpnMutable

    override fun setSession(label: String) {
        builder.setSession(label)
    }

    override fun setMtu(mtu: Int) {
        builder.setMtu(mtu)
    }

    override fun addAddress(subnet: IpSubnet) {
        runCatching {
            builder.addAddress(subnet.address, subnet.prefixLength)
        }.getOrElse { error ->
            throw IllegalArgumentException(
                "addAddress ${subnet.address}/${subnet.prefixLength} failed: ${error.message}",
                error,
            )
        }
    }

    override fun addRoute(subnet: IpSubnet) {
        runCatching {
            builder.addRoute(subnet.address, subnet.prefixLength)
        }.getOrElse { error ->
            throw IllegalArgumentException(
                "addRoute ${subnet.address}/${subnet.prefixLength} failed: ${error.message}",
                error,
            )
        }
    }

    override fun excludeRoute(subnet: IpSubnet) {
        // The authenticated loopback bridge in the fallback lane does not rely on
        // Builder.excludeRoute(), and Android 16 emulator rejects 127.0.0.0/8 here.
        // The kernel loopback route remains local regardless.
        Unit
    }

    override fun addAllowedApplication(packageName: String) {
        if (packageName == appPackageName) {
            runtimePackageBypassesVpnMutable = true
            return
        }
        try {
            builder.addAllowedApplication(packageName)
            hasAllowedApplications = true
            effectiveAllowedApplications += 1
        } catch (_: PackageManager.NameNotFoundException) {
            throw IllegalArgumentException("Allowed application '$packageName' is not installed.")
        }
    }

    override fun addDisallowedApplication(packageName: String) {
        try {
            if (disallowedPackages.add(packageName)) {
                builder.addDisallowedApplication(packageName)
                if (packageName == appPackageName) {
                    runtimePackageBypassesVpnMutable = true
                }
            }
        } catch (_: PackageManager.NameNotFoundException) {
            throw IllegalArgumentException("Disallowed application '$packageName' is not installed.")
        }
    }

    override fun establish(): TunnelInterfaceHandle? {
        if (sessionPlan.splitTunnelMode is io.acionyx.tunguska.domain.SplitTunnelMode.Allowlist) {
            // In allowlist mode the Tunguska package bypasses the VPN by staying out of the
            // routed package set entirely. The active xray lane depends on this invariant for
            // its own outbound sockets, so treat it as an explicit runtime property.
            runtimePackageBypassesVpnMutable = true
        }
        if (sessionPlan.splitTunnelMode is io.acionyx.tunguska.domain.SplitTunnelMode.Allowlist && effectiveAllowedApplications == 0) {
            throw IllegalStateException(
                "Allowlist mode has no routable applications after excluding the Tunguska runtime package.",
            )
        }
        if (!hasAllowedApplications && sessionPlan.splitTunnelMode !is io.acionyx.tunguska.domain.SplitTunnelMode.Allowlist) {
            addDisallowedApplication(appPackageName)
        }
        check(runtimePackageBypassesVpnMutable) {
            "xray+tun2socks requires the Tunguska runtime package to bypass the VPN path."
        }
        return builder.establish()?.let(::RawParcelFileDescriptorTunnelHandle)
    }
}

private interface ParcelFileDescriptorTunnelHandle : TunnelInterfaceHandle {
    val descriptor: ParcelFileDescriptor
}

private class RawParcelFileDescriptorTunnelHandle(
    override val descriptor: ParcelFileDescriptor,
) : ParcelFileDescriptorTunnelHandle {
    override fun close() {
        descriptor.close()
    }
}

internal data class AuthenticatedLocalBridge(
    val port: Int,
    val user: String,
    val password: String,
) {
    companion object {
        private val secureRandom = SecureRandom()

        @OptIn(ExperimentalEncodingApi::class)
        fun generate(): AuthenticatedLocalBridge {
            return AuthenticatedLocalBridge(
                port = 20_000 + secureRandom.nextInt(30_000),
                user = Base64.UrlSafe.encode(randomBytes(12)).trimEnd('='),
                password = Base64.UrlSafe.encode(randomBytes(18)).trimEnd('='),
            )
        }

        private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(secureRandom::nextBytes)
    }
}

internal data class CompiledXrayRuntimeConfig(
    val json: String,
    val vpnDnsServers: List<String>,
)

internal object XrayCompatOutboundCompiler {
    fun compile(profile: ProfileIr): JsonObject = when (profile.outboundProtocolId) {
        OutboundProtocolId.VLESS_REALITY -> vlessRealityOutbound(profile)
    }

    private fun vlessRealityOutbound(profile: ProfileIr): JsonObject = buildJsonObject {
        put("tag", "proxy")
        put("protocol", "vless")
        put("settings", buildJsonObject {
            put("vnext", buildJsonArray {
                add(
                    buildJsonObject {
                        put("address", profile.outbound.address)
                        put("port", profile.outbound.port)
                        put("users", buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("id", profile.outbound.uuid)
                                    put("encryption", "none")
                                    profile.outbound.flow?.let { put("flow", it) }
                                },
                            )
                        })
                    },
                )
            })
        })
        put("streamSettings", buildJsonObject {
            put("network", "tcp")
            put("security", "reality")
            put("realitySettings", buildJsonObject {
                put("serverName", profile.outbound.serverName)
                put("fingerprint", profile.outbound.utlsFingerprint)
                put("publicKey", profile.outbound.realityPublicKey)
                put("shortId", profile.outbound.realityShortId)
                put("spiderX", profile.outbound.effectiveRealitySpiderX())
            })
        })
    }
}

internal object XrayCompatCompilerErrors {
    fun unsupportedRouteCriteria(
        rule: RouteRule,
        unsupportedCriteria: List<String>,
    ): XrayCompatCompileException = XrayCompatCompileException(
        section = XrayCompatCompileSection.ROUTING,
        fieldPath = "routing.rules",
        message = buildString {
            append("Routing rule '")
            append(rule.id)
            append("' uses only criteria unsupported by the xray+tun2socks compatibility lane")
            if (unsupportedCriteria.isNotEmpty()) {
                append(": ")
                append(unsupportedCriteria.joinToString())
            }
            append('.')
        },
    )

    fun invalidDotDnsEndpoint(
        endpoint: String,
        cause: Throwable? = null,
    ): XrayCompatCompileException = XrayCompatCompileException(
        section = XrayCompatCompileSection.DNS,
        fieldPath = "dns.endpoints",
        message = "DNS endpoint '$endpoint' is not a valid DoT address for the xray+tun2socks compatibility lane.",
        cause = cause,
    )
}

private object XrayCompatInfrastructureCompiler {
    fun socksInbound(bridge: AuthenticatedLocalBridge): JsonObject = buildJsonObject {
        put("tag", "socks-in")
        put("protocol", "socks")
        put("listen", "127.0.0.1")
        put("port", bridge.port)
        put("settings", buildJsonObject {
            put("auth", "password")
            put("ip", "127.0.0.1")
            put("udp", true)
            put("accounts", buildJsonArray {
                add(
                    buildJsonObject {
                        put("user", bridge.user)
                        put("pass", bridge.password)
                    },
                )
            })
        })
        put("sniffing", buildJsonObject {
            put("enabled", true)
            put("routeOnly", false)
            put("destOverride", buildJsonArray {
                add(JsonPrimitive("http"))
                add(JsonPrimitive("tls"))
            })
        })
    }

    fun staticOutbounds(): List<JsonObject> = listOf(
        taggedOutbound("direct", "freedom"),
        taggedOutbound("block", "blackhole"),
        taggedOutbound("dns-out", "dns"),
    )

    private fun taggedOutbound(tag: String, protocol: String): JsonObject = buildJsonObject {
        put("tag", tag)
        put("protocol", protocol)
    }
}

private object XrayCompatRuntimeDefaultsCompiler {
    fun logConfig(): JsonObject = buildJsonObject {
        put("loglevel", "warning")
    }

    fun policyConfig(): JsonObject = buildJsonObject {
        put("levels", buildJsonObject {
            put(
                "0",
                buildJsonObject {
                    put("handshake", 4)
                    put("connIdle", 120)
                    put("uplinkOnly", 5)
                    put("downlinkOnly", 30)
                },
            )
        })
    }
}

private object XrayCompatRoutingCompiler {
    fun compile(
        profile: ProfileIr,
        dnsPlan: XrayDnsPlan,
    ): JsonObject = buildJsonObject {
        val effectiveRouting = EffectiveRoutingPolicyResolver.resolve(profile)
        val requiresIpResolution = effectiveRouting.rules.any { rule ->
            rule.match.ipCidrs.isNotEmpty() || rule.match.geoIps.isNotEmpty()
        }
        put("domainStrategy", if (dnsPlan.proxyDns || requiresIpResolution) "IPIfNonMatch" else "AsIs")
        put("rules", buildJsonArray {
            if (dnsPlan.proxyDns) {
                add(
                    buildJsonObject {
                        put("type", "field")
                        put("port", "53")
                        put("network", "udp,tcp")
                        put("outboundTag", "dns-out")
                    },
                )
            }
            effectiveRouting.rules.forEach { rule ->
                add(routeRule(rule))
            }
            add(
                buildJsonObject {
                    put("type", "field")
                    put("inboundTag", buildJsonArray {
                        add(JsonPrimitive("socks-in"))
                    })
                    put("outboundTag", outboundTagFor(profile.routing.defaultAction))
                },
            )
        })
    }

    private fun routeRule(rule: RouteRule): JsonObject {
        val domains = buildList {
            addAll(rule.match.domainExact)
            addAll(rule.match.domainSuffix.map { "domain:$it" })
            addAll(rule.match.domainKeyword.map { "keyword:$it" })
            addAll(rule.match.geoSites.map { "geosite:$it" })
        }
        val ips = buildList {
            addAll(rule.match.ipCidrs)
            addAll(rule.match.geoIps.map { "geoip:$it" })
        }
        val unsupportedCriteria = buildList {
            if (rule.match.asns.isNotEmpty()) add("asn")
            if (rule.match.packageNames.isNotEmpty()) add("packageNames")
        }
        if (
            domains.isEmpty() &&
            ips.isEmpty() &&
            rule.match.ports.isEmpty() &&
            rule.match.protocols.isEmpty()
        ) {
            throw XrayCompatCompilerErrors.unsupportedRouteCriteria(
                rule = rule,
                unsupportedCriteria = unsupportedCriteria,
            )
        }
        return buildJsonObject {
            put("type", "field")
            if (domains.isNotEmpty()) {
                put("domain", domains.toJsonArray())
            }
            if (ips.isNotEmpty()) {
                put("ip", ips.toJsonArray())
            }
            if (rule.match.ports.isNotEmpty()) {
                put("port", rule.match.ports.sorted().joinToString(separator = ","))
            }
            if (rule.match.protocols.isNotEmpty()) {
                put(
                    "network",
                    rule.match.protocols
                        .map(NetworkProtocol::name)
                        .map(String::lowercase)
                        .distinct()
                        .joinToString(separator = ","),
                )
            }
            put("outboundTag", outboundTagFor(rule.action))
        }
    }

    private fun outboundTagFor(action: RouteAction): String = when (action) {
        RouteAction.PROXY -> "proxy"
        RouteAction.DIRECT -> "direct"
        RouteAction.BLOCK -> "block"
    }
}

internal object XrayCompatConfigCompiler {
    fun compile(
        profile: ProfileIr,
        bridge: AuthenticatedLocalBridge,
    ): CompiledXrayRuntimeConfig {
        val dnsPlan = XrayCompatDnsCompiler.compile(profile.dns)
        val root = buildJsonObject {
            put("log", XrayCompatRuntimeDefaultsCompiler.logConfig())
            put("dns", dnsPlan.xrayDns)
            put("inbounds", buildJsonArray {
                add(XrayCompatInfrastructureCompiler.socksInbound(bridge))
            })
            put("outbounds", buildJsonArray {
                add(XrayCompatOutboundCompiler.compile(profile))
                XrayCompatInfrastructureCompiler.staticOutbounds().forEach(::add)
            })
            put("routing", XrayCompatRoutingCompiler.compile(profile, dnsPlan))
            put("policy", XrayCompatRuntimeDefaultsCompiler.policyConfig())
        }
        return CompiledXrayRuntimeConfig(
            json = CanonicalJson.instance.encodeToString(JsonObject.serializer(), root),
            vpnDnsServers = dnsPlan.vpnDnsServers,
        )
    }
}

private data class XrayDnsPlan(
    val xrayDns: JsonObject,
    val vpnDnsServers: List<String>,
    val proxyDns: Boolean,
)

private object XrayCompatDnsCompiler {
    fun compile(mode: DnsMode): XrayDnsPlan = when (mode) {
        DnsMode.SystemDns -> {
            XrayDnsPlan(
                xrayDns = buildJsonObject {
                    put("hosts", buildJsonObject {})
                    put("servers", buildJsonArray {
                        add(JsonPrimitive("tcp://1.1.1.1:53"))
                        add(JsonPrimitive("tcp://1.0.0.1:53"))
                    })
                    put("queryStrategy", "UseIPv4")
                },
                vpnDnsServers = DEFAULT_VPN_DNS_SERVERS,
                proxyDns = true,
            )
        }

        is DnsMode.VpnDns -> {
            val servers = mode.servers.map { endpoint ->
                dnsServerEntry(rawEndpoint = endpoint)
            }
            XrayDnsPlan(
                xrayDns = buildJsonObject {
                    put("hosts", buildJsonObject {})
                    put("servers", JsonArray(servers))
                    put("queryStrategy", "UseIP")
                },
                vpnDnsServers = builderDnsServers(mode.servers),
                proxyDns = true,
            )
        }

        is DnsMode.CustomEncrypted -> {
            val servers = mode.endpoints.map { rawEndpoint ->
                dnsServerEntry(
                    rawEndpoint = rawEndpoint,
                    endpoint = when (mode.kind) {
                        EncryptedDnsKind.DOH -> ensurePrefixed(rawEndpoint, "https://")
                        EncryptedDnsKind.DOT -> ensurePrefixed(rawEndpoint, "tls://")
                    },
                )
            }
            XrayDnsPlan(
                xrayDns = buildJsonObject {
                    put("hosts", buildJsonObject {})
                    put("servers", JsonArray(servers))
                    put("queryStrategy", "UseIP")
                },
                vpnDnsServers = builderDnsServers(mode.endpoints),
                proxyDns = true,
            )
        }
    }

    private fun dnsServerEntry(
        rawEndpoint: String,
        endpoint: String = rawEndpoint,
    ): JsonObject {
        val normalized = endpoint.trim()
        return when {
            normalized.startsWith("https://", ignoreCase = true) -> buildJsonObject {
                put("address", normalized)
            }

            normalized.startsWith("tls://", ignoreCase = true) -> {
                val uri = runCatching { URI(normalized) }.getOrElse { error ->
                        throw XrayCompatCompilerErrors.invalidDotDnsEndpoint(rawEndpoint, error)
                }
                val host = uri.host
                        ?: throw XrayCompatCompilerErrors.invalidDotDnsEndpoint(rawEndpoint)
                val port = uri.port.takeIf { it > 0 } ?: 853
                buildJsonObject {
                    put("address", "tls+local://$host:$port")
                }
            }

            else -> error("Unsupported DNS endpoint format: $endpoint")
        }
    }

    private fun builderDnsServers(endpoints: List<String>): List<String> {
        val candidates = endpoints.mapNotNull { endpoint ->
            extractHost(endpoint)?.takeIf(::isIpLiteral)
        }.distinct()
        return if (candidates.isEmpty()) DEFAULT_VPN_DNS_SERVERS else candidates
    }

    private fun extractHost(endpoint: String): String? {
        val normalized = when {
            endpoint.startsWith("https://", ignoreCase = true) -> endpoint
            endpoint.startsWith("tls://", ignoreCase = true) -> endpoint
            endpoint.contains("://") -> endpoint
            else -> "tls://$endpoint"
        }
        return runCatching { URI(normalized).host }.getOrNull()
    }

    private fun ensurePrefixed(value: String, prefix: String): String =
        if (value.startsWith(prefix, ignoreCase = true)) value else "$prefix$value"

    private fun isIpLiteral(value: String): Boolean = runCatching {
        InetAddress.getByName(value)
        true
    }.getOrDefault(false)
}

private val DEFAULT_VPN_DNS_SERVERS = listOf("1.1.1.1", "1.0.0.1")

private fun List<String>.toJsonArray(): JsonArray = buildJsonArray {
    forEach { add(JsonPrimitive(it)) }
}

object VpnNativeProcessBridge {
    init {
        System.loadLibrary("vpnhelper")
    }

    @JvmStatic
    external fun nativeStartProcessWithFd(
        cmd: String,
        args: Array<String>,
        envKeys: Array<String>,
        envVals: Array<String>,
        keepFd: Int,
        maxFds: Int,
    ): Long

    @JvmStatic
    external fun nativeKillProcess(pid: Long): Int

    @JvmStatic
    external fun nativeSetMaxFds(maxFds: Int): Int

    fun ensureLoaded() = Unit
}

private const val TAG = "XrayTun2Socks"
private const val MAX_RUNTIME_FDS = 65_536
private const val XRAY_BOOT_DELAY_MS = 800L
private const val TUN2SOCKS_BOOT_DELAY_MS = 400L
