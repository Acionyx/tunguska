package io.acionyx.tunguska.vpnservice

import io.acionyx.tunguska.domain.SplitTunnelMode
import io.acionyx.tunguska.engine.api.CompiledEngineConfig
import io.acionyx.tunguska.engine.api.VpnDirectives
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class EmbeddedEngineRuntimeTest {
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
}
