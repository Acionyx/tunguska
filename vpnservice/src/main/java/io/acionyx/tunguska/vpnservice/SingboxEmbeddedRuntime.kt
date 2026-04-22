package io.acionyx.tunguska.vpnservice

import java.io.File

class SingboxEmbeddedHost(
    private val clock: () -> Long = System::currentTimeMillis,
    private val sessionFactory: SingboxEmbeddedSessionFactory = DefaultSingboxEmbeddedSessionFactory,
) : EmbeddedEngineHost {
    override val engineId: String = "singbox"
    override val strategyId: EmbeddedRuntimeStrategyId = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED

    override fun prepare(
        workspace: EngineSessionWorkspace,
        request: StagedRuntimeRequest,
        runtimeDependencies: EmbeddedRuntimeDependencies,
    ): EmbeddedEngineHostPreparation {
        if (request.compiledConfig.payload.isBlank()) {
            return failure(
                workspace = workspace,
                summary = "Embedded sing-box runtime requires a non-empty compiled config payload.",
            )
        }

        val runtimeConfigFile = runCatching {
            val runtimePayload = materializeRuntimePayload(
                workspace = workspace,
                request = request,
                runtimeDependencies = runtimeDependencies,
            )
            stageRuntimeConfig(
                workspace = workspace,
                payload = runtimePayload,
            )
        }.getOrElse { error ->
            return failure(
                workspace = workspace,
                summary = error.message ?: error.javaClass.simpleName,
            )
        }

        return EmbeddedEngineHostPreparation(
            result = EmbeddedEngineHostResult(
                status = EmbeddedEngineHostStatus.READY,
                summary = "Prepared embedded sing-box workspace for ${request.compiledConfig.configHash.take(12)}.",
                preparedAtEpochMs = clock(),
                workspacePath = workspace.rootDir.absolutePath,
            ),
            session = sessionFactory.create(
                request = request,
                workspace = workspace,
                runtimeConfigFile = runtimeConfigFile,
                runtimeDependencies = runtimeDependencies,
                clock = clock,
            ),
        )
    }

    private fun stageRuntimeConfig(
        workspace: EngineSessionWorkspace,
        payload: String,
    ): File = File(workspace.rootDir, "singbox-runtime.json").apply {
        writeText(payload)
        require(isFile && length() > 0L) {
            "Failed to stage sing-box runtime config into ${absolutePath}."
        }
    }

    private fun materializeRuntimePayload(
        workspace: EngineSessionWorkspace,
        request: StagedRuntimeRequest,
        runtimeDependencies: EmbeddedRuntimeDependencies,
    ): String {
        val runtimeAssets = request.compiledConfig.runtimeAssets
        if (runtimeAssets.isEmpty()) {
            return request.compiledConfig.payload
        }
        val runtimeContext = runtimeDependencies.context ?: runtimeDependencies.vpnService?.applicationContext
            ?: error("Embedded sing-box runtime requires an Android context to stage runtime assets.")
        val stagedAssetPaths = runtimeAssets.associate { asset ->
            asset.relativePath to stageRuntimeAsset(
                context = runtimeContext,
                workspace = workspace,
                relativePath = asset.relativePath,
            )
        }
        var rewrittenPayload = request.compiledConfig.payload
        stagedAssetPaths.forEach { (logicalPath, stagedFile) ->
            rewrittenPayload = rewrittenPayload.replace(
                oldValue = logicalPath.jsonStringLiteral(),
                newValue = stagedFile.absolutePath.jsonStringLiteral(),
            )
        }
        return rewrittenPayload
    }

    private fun stageRuntimeAsset(
        context: android.content.Context,
        workspace: EngineSessionWorkspace,
        relativePath: String,
    ): File {
        val normalizedRelativePath = relativePath.replace('\\', '/')
        val destination = File(workspace.rootDir, normalizedRelativePath)
        destination.parentFile?.let { parent ->
            require(parent.isDirectory || parent.mkdirs()) {
                "Failed to create sing-box runtime asset directory ${parent.absolutePath}."
            }
        }
        context.assets.open("singbox/$normalizedRelativePath").use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        require(destination.isFile && destination.length() > 0L) {
            "Failed to stage sing-box runtime asset $normalizedRelativePath into ${destination.absolutePath}."
        }
        return destination
    }

    private fun failure(
        workspace: EngineSessionWorkspace,
        summary: String,
    ): EmbeddedEngineHostPreparation = EmbeddedEngineHostPreparation(
        result = EmbeddedEngineHostResult(
            status = EmbeddedEngineHostStatus.FAILED,
            summary = summary,
            preparedAtEpochMs = clock(),
            workspacePath = workspace.rootDir.absolutePath,
        ),
    )
}

private fun String.jsonStringLiteral(): String = "\"${buildString(length) {
    this@jsonStringLiteral.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
}}\""

fun interface SingboxEmbeddedSessionFactory {
    fun create(
        request: StagedRuntimeRequest,
        workspace: EngineSessionWorkspace,
        runtimeConfigFile: File,
        runtimeDependencies: EmbeddedRuntimeDependencies,
        clock: () -> Long,
    ): EmbeddedEngineSession
}

object DefaultSingboxEmbeddedSessionFactory : SingboxEmbeddedSessionFactory {
    override fun create(
        request: StagedRuntimeRequest,
        workspace: EngineSessionWorkspace,
        runtimeConfigFile: File,
        runtimeDependencies: EmbeddedRuntimeDependencies,
        clock: () -> Long,
    ): EmbeddedEngineSession = SingboxEmbeddedEngineSession(
        request = request,
        workspace = workspace,
        runtimeConfigFile = runtimeConfigFile,
        runtime = DefaultSingboxRuntimeFactory.create(
            request = request,
            runtimeConfigFile = runtimeConfigFile,
            runtimeDependencies = runtimeDependencies,
        ),
        clock = clock,
    )
}

internal class SingboxEmbeddedEngineSession(
    private val request: StagedRuntimeRequest,
    private val workspace: EngineSessionWorkspace,
    private val runtimeConfigFile: File,
    private val runtime: SingboxRuntime,
    private val clock: () -> Long,
) : EmbeddedEngineSession {
    private val lock = Any()
    private var started: Boolean = false
    private var lastStatusSummary: String? = null

    override fun start(): EmbeddedEngineSessionResult {
        synchronized(lock) {
            if (started) {
                return failure("Embedded sing-box runtime is already active.")
            }
        }
        RuntimeListenerAllowanceStore.clear()
        return runCatching {
            runtime.start()
            synchronized(lock) {
                started = true
                lastStatusSummary = null
            }
            VpnRuntimeStore.recordRuntimeTelemetry(
                strategy = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
                nativeEvent = "Started embedded sing-box runtime from ${runtimeConfigFile.name} in ${workspace.rootDir.name}.",
            )
            EmbeddedEngineSessionResult(
                status = EmbeddedEngineSessionStatus.STARTED,
                summary = "Embedded sing-box runtime started for ${request.compiledConfig.configHash.take(12)}.",
                observedAtEpochMs = clock(),
            )
        }.getOrElse { error ->
            val failureSummary = error.message ?: error.javaClass.simpleName
            rememberFailure(failureSummary)
            runCatching { runtime.stop() }
            failure(failureSummary)
        }
    }

    override fun stop(): EmbeddedEngineSessionResult {
        val wasStarted = synchronized(lock) {
            val snapshot = started
            started = false
            lastStatusSummary = null
            snapshot
        }
        RuntimeListenerAllowanceStore.clear()
        if (!wasStarted) {
            return EmbeddedEngineSessionResult(
                status = EmbeddedEngineSessionStatus.STOPPED,
                summary = "Embedded sing-box runtime is already stopped.",
                observedAtEpochMs = clock(),
            )
        }
        return runCatching {
            runtime.stop()
            EmbeddedEngineSessionResult(
                status = EmbeddedEngineSessionStatus.STOPPED,
                summary = "Embedded sing-box runtime stopped.",
                observedAtEpochMs = clock(),
            )
        }.getOrElse { error ->
            val failureSummary = error.message ?: error.javaClass.simpleName
            rememberFailure(failureSummary)
            failure(failureSummary)
        }
    }

    override fun health(): EmbeddedEngineSessionHealthResult {
        val isStarted = synchronized(lock) { started }
        if (!isStarted) {
            return healthResult(
                status = EmbeddedEngineSessionHealthStatus.FAILED,
                summary = synchronized(lock) { lastStatusSummary } ?: "Embedded sing-box runtime is not active.",
            )
        }
        val health = runCatching { runtime.health() }.getOrElse { error ->
            SingboxRuntimeHealth(
                healthy = false,
                summary = error.message ?: error.javaClass.simpleName,
            )
        }
        synchronized(lock) {
            lastStatusSummary = if (health.healthy) null else health.summary
        }
        return healthResult(
            status = if (health.healthy) {
                EmbeddedEngineSessionHealthStatus.HEALTHY
            } else {
                EmbeddedEngineSessionHealthStatus.FAILED
            },
            summary = health.summary,
        )
    }

    private fun rememberFailure(summary: String) {
        synchronized(lock) {
            started = false
            lastStatusSummary = summary
        }
        RuntimeListenerAllowanceStore.clear()
        VpnRuntimeStore.recordRuntimeTelemetry(
            strategy = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
            nativeEvent = summary,
        )
    }

    private fun failure(message: String): EmbeddedEngineSessionResult = EmbeddedEngineSessionResult(
        status = EmbeddedEngineSessionStatus.FAILED,
        summary = message,
        observedAtEpochMs = clock(),
    )

    private fun healthResult(
        status: EmbeddedEngineSessionHealthStatus,
        summary: String,
    ): EmbeddedEngineSessionHealthResult = EmbeddedEngineSessionHealthResult(
        status = status,
        summary = summary,
        observedAtEpochMs = clock(),
    )
}
