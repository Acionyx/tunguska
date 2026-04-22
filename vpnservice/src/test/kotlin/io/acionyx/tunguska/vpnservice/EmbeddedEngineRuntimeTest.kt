package io.acionyx.tunguska.vpnservice

import io.acionyx.tunguska.domain.SplitTunnelMode
import io.acionyx.tunguska.engine.api.CompiledEngineConfig
import io.acionyx.tunguska.engine.api.CompiledRuntimeAsset
import io.acionyx.tunguska.engine.api.VpnDirectives
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test

class EmbeddedEngineRuntimeTest {
    @After
    fun tearDown() {
        EmbeddedRuntimeStrategyPolicyStore.reset()
    }

    @Test
    fun `workspace factory writes redacted manifest`() {
        val workspaceFactory = EngineSessionWorkspaceFactory(
            rootDir = Files.createTempDirectory("tunguska-runtime").toFile(),
            clock = { 1234L },
        )
        val request = sampleRequest()

        val workspace = workspaceFactory.prepare(
            request = request,
            sessionLabel = "Tunguska abc123",
        )

        val manifest = workspace.manifestFile.readText()
        val configPayload = workspace.configFile.readText()
        assertTrue(manifest.contains("\"engine_id\": \"singbox\""))
        assertTrue(manifest.contains("\"config_hash\": \"abc123\""))
        assertTrue(manifest.contains("\"payload_bytes\": ${request.compiledConfig.payload.toByteArray().size}"))
        assertTrue(!manifest.contains("secret-value"))
        assertEquals(request.compiledConfig.payload, configPayload)
    }

    @Test
    fun `workspace factory records runtime assets in manifest`() {
        val workspaceFactory = EngineSessionWorkspaceFactory(
            rootDir = Files.createTempDirectory("tunguska-runtime").toFile(),
            clock = { 1234L },
        )
        val request = sampleRequestWithRuntimeAssets()

        val workspace = workspaceFactory.prepare(
            request = request,
            sessionLabel = "Tunguska abc123",
        )

        val manifest = workspace.manifestFile.readText()

        assertTrue(manifest.contains("\"runtime_asset_count\": 1"))
        assertTrue(manifest.contains("rule-set/geoip-ru.srs"))
    }

    @Test
    fun `registry returns unavailable for stub singbox host`() {
        val workspaceFactory = EngineSessionWorkspaceFactory(
            rootDir = Files.createTempDirectory("tunguska-runtime").toFile(),
            clock = { 1234L },
        )
        val registry = EmbeddedEngineHostRegistry(
            hosts = listOf(UnavailableEmbeddedEngineHost(clock = { 5678L })),
            strategyPolicy = EmbeddedRuntimeStrategyPolicy(
                activeStrategy = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
            ),
            clock = { 5678L },
        )

        val result = registry.prepare(
            request = sampleRequest(),
            workspaceFactory = workspaceFactory,
            sessionLabel = "Tunguska abc123",
        )

        assertEquals(EmbeddedEngineHostStatus.UNAVAILABLE, result.result.status)
        assertEquals(5678L, result.result.preparedAtEpochMs)
        assertTrue(result.result.summary.contains("unavailable"))
        assertTrue(File(requireNotNull(result.result.workspacePath)).exists())
        assertEquals(null, result.session)
    }

    @Test
    fun `registry exposes xray as the active strategy`() {
        val registry = EmbeddedEngineHostRegistry()

        assertEquals(EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS, registry.activeStrategyId())
    }

    @Test
    fun `registry prepares sing-box embedded session for sing-box strategy`() {
        val workspaceFactory = EngineSessionWorkspaceFactory(
            rootDir = Files.createTempDirectory("tunguska-runtime").toFile(),
            clock = { 1234L },
        )
        val registry = EmbeddedEngineHostRegistry(
            strategyPolicy = EmbeddedRuntimeStrategyPolicy(
                activeStrategy = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
            ),
            clock = { 5678L },
        )

        val result = registry.prepare(
            request = sampleRequest(),
            workspaceFactory = workspaceFactory,
            sessionLabel = "Tunguska abc123",
        )

        assertEquals(EmbeddedEngineHostStatus.READY, result.result.status)
        assertTrue(result.result.summary.contains("Prepared embedded sing-box workspace"))
        assertTrue(File(requireNotNull(result.result.workspacePath), "singbox-runtime.json").isFile)
        assertEquals("{\"uuid\":\"secret-value\"}", File(requireNotNull(result.result.workspacePath), "singbox-runtime.json").readText())
        assertTrue(result.session != null)
    }

    @Test
    fun `sing-box embedded session delegates lifecycle to runtime`() {
        val request = sampleRequest()
        val workspaceFactory = EngineSessionWorkspaceFactory(
            rootDir = Files.createTempDirectory("tunguska-runtime").toFile(),
            clock = { 1234L },
        )
        val workspace = workspaceFactory.prepare(
            request = request,
            sessionLabel = "Tunguska abc123",
        )
        val runtime = FakeSingboxRuntime()
        val session = SingboxEmbeddedEngineSession(
            request = request,
            workspace = workspace,
            runtimeConfigFile = File(workspace.rootDir, "singbox-runtime.json").apply {
                writeText(request.compiledConfig.payload)
            },
            runtime = runtime,
            clock = { 5678L },
        )
        val startResult = session.start()
        val healthResult = session.health()
        val stopResult = session.stop()

        assertEquals(EmbeddedEngineSessionStatus.STARTED, startResult.status)
        assertEquals(EmbeddedEngineSessionHealthStatus.HEALTHY, healthResult.status)
        assertEquals(EmbeddedEngineSessionStatus.STOPPED, stopResult.status)
        assertEquals(1, runtime.startCalls)
        assertEquals(1, runtime.healthCalls)
        assertEquals(1, runtime.stopCalls)
    }

    @Test
    fun `sing-box embedded session surfaces runtime startup failure`() {
        val request = sampleRequest()
        val workspaceFactory = EngineSessionWorkspaceFactory(
            rootDir = Files.createTempDirectory("tunguska-runtime").toFile(),
            clock = { 1234L },
        )
        val workspace = workspaceFactory.prepare(
            request = request,
            sessionLabel = "Tunguska abc123",
        )
        val runtime = FakeSingboxRuntime(startFailure = "native bridge failed")
        val session = SingboxEmbeddedEngineSession(
            request = request,
            workspace = workspace,
            runtimeConfigFile = File(workspace.rootDir, "singbox-runtime.json").apply {
                writeText(request.compiledConfig.payload)
            },
            runtime = runtime,
            clock = { 5678L },
        )

        val startResult = session.start()
        val healthResult = session.health()

        assertEquals(EmbeddedEngineSessionStatus.FAILED, startResult.status)
        assertTrue(startResult.summary.contains("native bridge failed"))
        assertEquals(EmbeddedEngineSessionHealthStatus.FAILED, healthResult.status)
        assertTrue(healthResult.summary.contains("native bridge failed"))
        assertEquals(1, runtime.stopCalls)
    }

    @Test
    fun `registry fails for unregistered engine ids`() {
        val request = sampleRequest().copy(
            compiledConfig = sampleRequest().compiledConfig.copy(engineId = "unknown-engine"),
        )
        val workspaceFactory = EngineSessionWorkspaceFactory(
            rootDir = Files.createTempDirectory("tunguska-runtime").toFile(),
            clock = { 1234L },
        )
        val registry = EmbeddedEngineHostRegistry(clock = { 5678L })

        val result = registry.prepare(
            request = request,
            workspaceFactory = workspaceFactory,
            sessionLabel = "Tunguska abc123",
        )

        assertEquals(EmbeddedEngineHostStatus.FAILED, result.result.status)
        assertTrue(result.result.summary.contains("No embedded engine host"))
    }

    @Test
    fun `workspace cleaner deletes prepared session directories`() {
        val workspaceFactory = EngineSessionWorkspaceFactory(
            rootDir = Files.createTempDirectory("tunguska-runtime").toFile(),
            clock = { 1234L },
        )
        val workspace = workspaceFactory.prepare(
            request = sampleRequest(),
            sessionLabel = "Tunguska abc123",
        )

        RuntimeSessionWorkspaceCleaner.delete(workspace.rootDir.absolutePath)

        assertTrue(!workspace.rootDir.exists())
    }

    private fun sampleRequest(): StagedRuntimeRequest {
        val compiled = CompiledEngineConfig(
            engineId = "singbox",
            format = "application/json",
            payload = """{"uuid":"secret-value"}""",
            configHash = "abc123",
            vpnDirectives = VpnDirectives(
                preserveLoopback = true,
                splitTunnelMode = SplitTunnelMode.FullTunnel,
                safeMode = true,
            ),
        )
        return StagedRuntimeRequest(
            plan = TunnelSessionPlanner.plan(compiled),
            compiledConfig = compiled,
        )
    }

    private fun sampleRequestWithRuntimeAssets(): StagedRuntimeRequest {
        val compiled = sampleRequest().compiledConfig.copy(
            runtimeAssets = listOf(CompiledRuntimeAsset(relativePath = "rule-set/geoip-ru.srs")),
        )
        return StagedRuntimeRequest(
            plan = TunnelSessionPlanner.plan(compiled),
            compiledConfig = compiled,
        )
    }
}

private class FakeSingboxRuntime(
    private val startFailure: String? = null,
    private val healthFailure: String? = null,
) : SingboxRuntime {
    var startCalls: Int = 0
    var stopCalls: Int = 0
    var healthCalls: Int = 0

    override fun start() {
        startCalls += 1
        startFailure?.let(::error)
    }

    override fun stop() {
        stopCalls += 1
    }

    override fun health(): SingboxRuntimeHealth {
        healthCalls += 1
        return healthFailure?.let {
            SingboxRuntimeHealth(healthy = false, summary = it)
        } ?: SingboxRuntimeHealth(
            healthy = true,
            summary = "Embedded sing-box runtime is healthy.",
        )
    }
}
