package io.acionyx.tunguska.vpnservice

import android.content.Context
import android.net.VpnService
import io.acionyx.tunguska.engine.api.CompiledEngineConfig
import java.io.File

data class StagedRuntimeRequest(
    val plan: TunnelSessionPlan,
    val compiledConfig: CompiledEngineConfig,
    val profileCanonicalJson: String? = null,
)

data class EmbeddedRuntimeDependencies(
    val context: Context? = null,
    val vpnService: VpnService? = null,
)

enum class EmbeddedRuntimeStrategyId {
    XRAY_TUN2SOCKS,
}

data class EmbeddedRuntimeStrategyPolicy(
    val activeStrategy: EmbeddedRuntimeStrategyId = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
)

enum class EmbeddedEngineHostStatus {
    NOT_PREPARED,
    READY,
    UNAVAILABLE,
    FAILED,
}

data class EmbeddedEngineHostResult(
    val status: EmbeddedEngineHostStatus,
    val summary: String,
    val preparedAtEpochMs: Long,
    val workspacePath: String? = null,
)

data class EngineSessionWorkspace(
    val rootDir: File,
    val manifestFile: File,
    val configFile: File,
)

enum class EmbeddedEngineSessionStatus {
    NOT_STARTED,
    STARTED,
    STOPPED,
    FAILED,
}

enum class EmbeddedEngineSessionHealthStatus {
    UNKNOWN,
    HEALTHY,
    DEGRADED,
    FAILED,
}

data class EmbeddedEngineSessionResult(
    val status: EmbeddedEngineSessionStatus,
    val summary: String,
    val observedAtEpochMs: Long,
)

data class EmbeddedEngineSessionHealthResult(
    val status: EmbeddedEngineSessionHealthStatus,
    val summary: String,
    val observedAtEpochMs: Long,
)

data class EmbeddedEngineHostPreparation(
    val result: EmbeddedEngineHostResult,
    val session: EmbeddedEngineSession? = null,
)

interface EmbeddedEngineSession {
    fun start(): EmbeddedEngineSessionResult

    fun stop(): EmbeddedEngineSessionResult

    fun health(): EmbeddedEngineSessionHealthResult
}

interface EmbeddedEngineHost {
    val engineId: String
    val strategyId: EmbeddedRuntimeStrategyId

    fun prepare(
        workspace: EngineSessionWorkspace,
        request: StagedRuntimeRequest,
        runtimeDependencies: EmbeddedRuntimeDependencies,
    ): EmbeddedEngineHostPreparation
}

class EmbeddedEngineHostRegistry(
    private val hosts: List<EmbeddedEngineHost> = listOf(
        XrayTun2SocksEmbeddedHost(),
    ),
    private val strategyPolicy: EmbeddedRuntimeStrategyPolicy = EmbeddedRuntimeStrategyPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun activeStrategyId(): EmbeddedRuntimeStrategyId = strategyPolicy.activeStrategy

    fun prepare(
        request: StagedRuntimeRequest,
        workspaceFactory: EngineSessionWorkspaceFactory,
        sessionLabel: String,
        runtimeDependencies: EmbeddedRuntimeDependencies = EmbeddedRuntimeDependencies(),
    ): EmbeddedEngineHostPreparation {
        val workspace = workspaceFactory.prepare(
            request = request,
            sessionLabel = sessionLabel,
        )
        val host = hosts.firstOrNull {
            it.engineId == request.compiledConfig.engineId &&
                it.strategyId == strategyPolicy.activeStrategy
        }
        if (host == null) {
            val availableStrategies = hosts
                .filter { it.engineId == request.compiledConfig.engineId }
                .map(EmbeddedEngineHost::strategyId)
                .distinct()
                .joinToString()
                .ifBlank { "none" }
            return EmbeddedEngineHostPreparation(
                result = EmbeddedEngineHostResult(
                    status = EmbeddedEngineHostStatus.FAILED,
                    summary = buildString {
                        append("No embedded engine host is registered for '${request.compiledConfig.engineId}' ")
                        append("with active strategy ${strategyPolicy.activeStrategy}. ")
                        append("Available strategies: $availableStrategies.")
                    },
                    preparedAtEpochMs = clock(),
                    workspacePath = workspace.rootDir.absolutePath,
                ),
            )
        }
        return host.prepare(
            workspace = workspace,
            request = request,
            runtimeDependencies = runtimeDependencies,
        )
    }
}

class EngineSessionWorkspaceFactory(
    private val rootDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun prepare(
        request: StagedRuntimeRequest,
        sessionLabel: String,
    ): EngineSessionWorkspace {
        rootDir.mkdirs()
        val workspaceDir = File(
            rootDir,
            "${request.compiledConfig.engineId}-${request.compiledConfig.configHash.take(12)}-${clock()}",
        )
        require(workspaceDir.mkdirs()) {
            "Unable to create engine workspace '${workspaceDir.absolutePath}'."
        }
        val manifestFile = File(workspaceDir, "session-manifest.json")
        val configFile = File(workspaceDir, "engine-config.json")
        manifestFile.writeText(
            renderManifest(
                request = request,
                sessionLabel = sessionLabel,
                createdAtEpochMs = clock(),
            ),
        )
        configFile.writeText(request.compiledConfig.payload)
        return EngineSessionWorkspace(
            rootDir = workspaceDir,
            manifestFile = manifestFile,
            configFile = configFile,
        )
    }

    companion object {
        fun fromContext(
            cacheDir: File,
            clock: () -> Long = System::currentTimeMillis,
        ): EngineSessionWorkspaceFactory = EngineSessionWorkspaceFactory(
            rootDir = File(cacheDir, "runtime"),
            clock = clock,
        )
    }

    private fun renderManifest(
        request: StagedRuntimeRequest,
        sessionLabel: String,
        createdAtEpochMs: Long,
    ): String = buildString {
        appendLine("{")
        appendLine("""  "engine_id": "${request.compiledConfig.engineId.jsonEscape()}",""")
        appendLine("""  "config_format": "${request.compiledConfig.format.jsonEscape()}",""")
        appendLine("""  "config_hash": "${request.compiledConfig.configHash.jsonEscape()}",""")
        appendLine("""  "payload_bytes": ${request.compiledConfig.payload.byteSize()},""")
        request.profileCanonicalJson?.let { profileJson ->
            appendLine("""  "profile_payload_bytes": ${profileJson.byteSize()},""")
        }
        appendLine("""  "session_label": "${sessionLabel.jsonEscape()}",""")
        appendLine("""  "process_suffix": "${request.plan.processNameSuffix.jsonEscape()}",""")
        appendLine("""  "created_at_epoch_ms": $createdAtEpochMs""")
        append('}')
    }
}

class UnavailableEmbeddedEngineHost(
    override val engineId: String = "singbox",
    private val clock: () -> Long = System::currentTimeMillis,
) : EmbeddedEngineHost {
    override val strategyId: EmbeddedRuntimeStrategyId = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS

    override fun prepare(
        workspace: EngineSessionWorkspace,
        request: StagedRuntimeRequest,
        runtimeDependencies: EmbeddedRuntimeDependencies,
    ): EmbeddedEngineHostPreparation = EmbeddedEngineHostPreparation(
        result = EmbeddedEngineHostResult(
            status = EmbeddedEngineHostStatus.UNAVAILABLE,
            summary = "Embedded engine host is unavailable; prepared a redacted session manifest for ${request.compiledConfig.configHash.take(12)}.",
            preparedAtEpochMs = clock(),
            workspacePath = workspace.rootDir.absolutePath,
        ),
    )
}

class ActiveRuntimeSession(
    private val engineSession: EmbeddedEngineSession,
    private val workspacePath: String?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun health(): EmbeddedEngineSessionHealthResult {
        return runCatching { engineSession.health() }.getOrElse { error ->
            EmbeddedEngineSessionHealthResult(
                status = EmbeddedEngineSessionHealthStatus.FAILED,
                summary = error.message ?: error.javaClass.simpleName,
                observedAtEpochMs = clock(),
            )
        }
    }

    fun stop(): EmbeddedEngineSessionResult {
        val engineStopResult = runCatching { engineSession.stop() }.getOrElse { error ->
            EmbeddedEngineSessionResult(
                status = EmbeddedEngineSessionStatus.FAILED,
                summary = error.message ?: error.javaClass.simpleName,
                observedAtEpochMs = clock(),
            )
        }
        RuntimeSessionWorkspaceCleaner.delete(workspacePath)
        return if (engineStopResult.status == EmbeddedEngineSessionStatus.FAILED) {
            engineStopResult
        } else {
            EmbeddedEngineSessionResult(
                status = EmbeddedEngineSessionStatus.STOPPED,
                summary = engineStopResult.summary,
                observedAtEpochMs = engineStopResult.observedAtEpochMs,
            )
        }
    }
}

object ActiveRuntimeSessionStore {
    private val lock = Any()
    private var activeSession: ActiveRuntimeSession? = null

    fun isActive(): Boolean = synchronized(lock) { activeSession != null }

    fun health(): EmbeddedEngineSessionHealthResult? {
        val session = synchronized(lock) { activeSession }
        return session?.health()
    }

    fun activate(session: ActiveRuntimeSession): EmbeddedEngineSessionResult? {
        val previous = synchronized(lock) {
            val existing = activeSession
            activeSession = session
            existing
        }
        return previous?.stop()
    }

    fun stop(): EmbeddedEngineSessionResult? {
        val session = synchronized(lock) {
            val existing = activeSession
            activeSession = null
            existing
        }
        return session?.stop()
    }
}

object RuntimeSessionWorkspaceCleaner {
    fun delete(workspacePath: String?) {
        if (workspacePath.isNullOrBlank()) return
        runCatching { File(workspacePath).deleteRecursively() }
    }
}

private fun String.byteSize(): Int = toByteArray(Charsets.UTF_8).size

private fun String.jsonEscape(): String = buildString(length) {
    this@jsonEscape.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
}
