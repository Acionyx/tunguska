package io.acionyx.tunguska.vpnservice

import io.acionyx.tunguska.domain.SplitTunnelMode
import io.acionyx.tunguska.engine.api.CompiledEngineConfig
import io.acionyx.tunguska.engine.api.VpnDirectives
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test

class RuntimeSessionControllerTest {
    @After
    fun tearDown() {
        EmbeddedRuntimeStrategyPolicyStore.reset()
        ActiveRuntimeSessionStore.stop()
        VpnRuntimeStore.stop()
    }

    @Test
    fun `controller activates runtime session when host prepares and starts`() {
        val session = FakeEmbeddedEngineSession(clock = { 3333L })
        val controller = RuntimeSessionController(
            engineHostRegistry = EmbeddedEngineHostRegistry(
                hosts = listOf(
                    ReadyEmbeddedEngineHost(
                        clock = { 2222L },
                        session = session,
                    ),
                ),
                clock = { 2222L },
            ),
            workspaceFactoryProvider = {
                EngineSessionWorkspaceFactory(
                    rootDir = Files.createTempDirectory("tunguska-runtime").toFile(),
                    clock = { 4444L },
                )
            },
            clock = { 5555L },
        )
        val request = sampleRequest()
        VpnRuntimeStore.stage(request)
        VpnRuntimeStore.markStartRequested()

        val outcome = controller.start(
            request = request,
            sessionLabel = "Tunguska abc123",
        )

        assertNull(outcome.failureReason)
        assertNotNull(outcome.activeSession)
        assertTrue(ActiveRuntimeSessionStore.isActive())
        assertEquals(VpnRuntimePhase.RUNNING, VpnRuntimeStore.snapshot().phase)
        assertEquals(TunnelBootstrapStatus.NOT_ATTEMPTED, VpnRuntimeStore.snapshot().bootstrapStatus)
        assertEquals(EmbeddedEngineHostStatus.READY, VpnRuntimeStore.snapshot().engineHostStatus)
        assertEquals(EmbeddedEngineSessionStatus.STARTED, VpnRuntimeStore.snapshot().engineSessionStatus)

        val stopResult = ActiveRuntimeSessionStore.stop()

        assertNotNull(stopResult)
        assertEquals(EmbeddedEngineSessionStatus.STOPPED, stopResult.status)
        assertTrue(session.stopCalled)
        assertFalse(ActiveRuntimeSessionStore.isActive())
        assertTrue(session.lastWorkspacePath?.let { !java.io.File(it).exists() } ?: false)
    }

    @Test
    fun `controller closes lease and workspace when host is unavailable`() {
        val workspaceRoot = Files.createTempDirectory("tunguska-runtime").toFile()
        val controller = RuntimeSessionController(
            engineHostRegistry = EmbeddedEngineHostRegistry(
                hosts = listOf(UnavailableEmbeddedEngineHost(clock = { 2222L })),
                strategyPolicy = EmbeddedRuntimeStrategyPolicy(
                    activeStrategy = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
                ),
                clock = { 2222L },
            ),
            workspaceFactoryProvider = {
                EngineSessionWorkspaceFactory(
                    rootDir = workspaceRoot,
                    clock = { 3333L },
                )
            },
            clock = { 4444L },
        )
        val request = sampleRequest()
        VpnRuntimeStore.stage(request)
        VpnRuntimeStore.markStartRequested()

        val outcome = controller.start(
            request = request,
            sessionLabel = "Tunguska abc123",
        )

        assertTrue(outcome.failureReason?.contains("unavailable") == true)
        assertNull(outcome.activeSession)
        assertFalse(ActiveRuntimeSessionStore.isActive())
        assertEquals(TunnelBootstrapStatus.NOT_ATTEMPTED, VpnRuntimeStore.snapshot().bootstrapStatus)
        assertEquals(EmbeddedEngineHostStatus.UNAVAILABLE, VpnRuntimeStore.snapshot().engineHostStatus)
        assertEquals(EmbeddedEngineSessionStatus.NOT_STARTED, VpnRuntimeStore.snapshot().engineSessionStatus)
        assertTrue(workspaceRoot.listFiles().isNullOrEmpty())
    }

    @Test
    fun `controller cleans up when engine session fails to start`() {
        val session = FakeEmbeddedEngineSession(
            startResult = EmbeddedEngineSessionResult(
                status = EmbeddedEngineSessionStatus.FAILED,
                summary = "Engine rejected the tunnel handle.",
                observedAtEpochMs = 3333L,
            ),
            clock = { 3333L },
        )
        val workspaceRoot = Files.createTempDirectory("tunguska-runtime").toFile()
        val controller = RuntimeSessionController(
            engineHostRegistry = EmbeddedEngineHostRegistry(
                hosts = listOf(
                    ReadyEmbeddedEngineHost(
                        clock = { 2222L },
                        session = session,
                    ),
                ),
                clock = { 2222L },
            ),
            workspaceFactoryProvider = {
                EngineSessionWorkspaceFactory(
                    rootDir = workspaceRoot,
                    clock = { 4444L },
                )
            },
            clock = { 5555L },
        )
        val request = sampleRequest()
        VpnRuntimeStore.stage(request)
        VpnRuntimeStore.markStartRequested()

        val outcome = controller.start(
            request = request,
            sessionLabel = "Tunguska abc123",
        )

        assertEquals("Engine rejected the tunnel handle.", outcome.failureReason)
        assertNull(outcome.activeSession)
        assertFalse(ActiveRuntimeSessionStore.isActive())
        assertTrue(session.stopCalled)
        assertEquals(EmbeddedEngineSessionStatus.FAILED, VpnRuntimeStore.snapshot().engineSessionStatus)
        assertTrue(workspaceRoot.listFiles().isNullOrEmpty())
    }

    @Test
    fun `controller starts host selected by staged runtime strategy`() {
        val session = FakeEmbeddedEngineSession(clock = { 3333L })
        val controller = RuntimeSessionController(
            engineHostRegistry = EmbeddedEngineHostRegistry(
                hosts = listOf(
                    ReadyEmbeddedEngineHost(
                        clock = { 2222L },
                        session = session,
                        strategyIdValue = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
                    ),
                ),
                strategyPolicy = EmbeddedRuntimeStrategyPolicy(
                    activeStrategy = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
                ),
                clock = { 2222L },
            ),
            workspaceFactoryProvider = {
                EngineSessionWorkspaceFactory(
                    rootDir = Files.createTempDirectory("tunguska-runtime").toFile(),
                    clock = { 4444L },
                )
            },
            clock = { 5555L },
        )
        val request = sampleRequest().copy(
            runtimeStrategy = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
        )
        VpnRuntimeStore.stage(request)
        VpnRuntimeStore.markStartRequested()

        val outcome = controller.start(
            request = request,
            sessionLabel = "Tunguska abc123",
        )

        assertNull(outcome.failureReason)
        assertNotNull(outcome.activeSession)
        assertEquals(EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED, VpnRuntimeStore.snapshot().activeStrategy)
    }

    @Test
    fun `controller starts default sing-box host with fake runtime`() {
        val runtime = ControllerFakeSingboxRuntime()
        val workspaceRoot = Files.createTempDirectory("tunguska-runtime").toFile()
        val controller = RuntimeSessionController(
            engineHostRegistry = EmbeddedEngineHostRegistry(
                hosts = listOf(
                    SingboxEmbeddedHost(
                        clock = { 2222L },
                        sessionFactory = SingboxEmbeddedSessionFactory { request, workspace, runtimeConfigFile, _, clock ->
                            SingboxEmbeddedEngineSession(
                                request = request,
                                workspace = workspace,
                                runtimeConfigFile = runtimeConfigFile,
                                runtime = runtime,
                                clock = clock,
                            )
                        },
                    ),
                ),
                strategyPolicy = EmbeddedRuntimeStrategyPolicy(
                    activeStrategy = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
                ),
                clock = { 2222L },
            ),
            workspaceFactoryProvider = {
                EngineSessionWorkspaceFactory(
                    rootDir = workspaceRoot,
                    clock = { 3333L },
                )
            },
            clock = { 4444L },
        )
        val request = sampleRequest().copy(
            runtimeStrategy = EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED,
        )
        VpnRuntimeStore.stage(request)
        VpnRuntimeStore.markStartRequested()

        val outcome = controller.start(
            request = request,
            sessionLabel = "Tunguska abc123",
        )

        assertNull(outcome.failureReason)
        assertNotNull(outcome.activeSession)
        assertTrue(ActiveRuntimeSessionStore.isActive())
        assertEquals(EmbeddedEngineHostStatus.READY, VpnRuntimeStore.snapshot().engineHostStatus)
        assertEquals(EmbeddedEngineSessionStatus.STARTED, VpnRuntimeStore.snapshot().engineSessionStatus)
        assertEquals(EmbeddedRuntimeStrategyId.SINGBOX_EMBEDDED, VpnRuntimeStore.snapshot().activeStrategy)
        assertEquals(1, runtime.startCalls)

        val stopResult = ActiveRuntimeSessionStore.stop()

        assertNotNull(stopResult)
        assertEquals(EmbeddedEngineSessionStatus.STOPPED, stopResult.status)
        assertEquals(1, runtime.stopCalls)
        assertTrue(workspaceRoot.listFiles().isNullOrEmpty())
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

private class ReadyEmbeddedEngineHost(
    private val clock: () -> Long,
    private val session: EmbeddedEngineSession,
    private val strategyIdValue: EmbeddedRuntimeStrategyId = EmbeddedRuntimeStrategyId.XRAY_TUN2SOCKS,
) : EmbeddedEngineHost {
    override val engineId: String = "singbox"
    override val strategyId: EmbeddedRuntimeStrategyId = strategyIdValue

    override fun prepare(
        workspace: EngineSessionWorkspace,
        request: StagedRuntimeRequest,
        runtimeDependencies: EmbeddedRuntimeDependencies,
    ): EmbeddedEngineHostPreparation {
        if (session is FakeEmbeddedEngineSession) {
            session.lastWorkspacePath = workspace.rootDir.absolutePath
        }
        return EmbeddedEngineHostPreparation(
            result = EmbeddedEngineHostResult(
                status = EmbeddedEngineHostStatus.READY,
                summary = "Prepared embedded sing-box session for ${request.compiledConfig.configHash}.",
                preparedAtEpochMs = clock(),
                workspacePath = workspace.rootDir.absolutePath,
            ),
            session = session,
        )
    }
}

private class FakeEmbeddedEngineSession(
    private val startResult: EmbeddedEngineSessionResult = EmbeddedEngineSessionResult(
        status = EmbeddedEngineSessionStatus.STARTED,
        summary = "Embedded engine session started.",
        observedAtEpochMs = 3333L,
    ),
    private val healthResult: EmbeddedEngineSessionHealthResult = EmbeddedEngineSessionHealthResult(
        status = EmbeddedEngineSessionHealthStatus.HEALTHY,
        summary = "Embedded engine session is healthy.",
        observedAtEpochMs = 3334L,
    ),
    private val clock: () -> Long,
) : EmbeddedEngineSession {
    var stopCalled: Boolean = false
    var lastWorkspacePath: String? = null

    override fun start(): EmbeddedEngineSessionResult = startResult

    override fun health(): EmbeddedEngineSessionHealthResult = healthResult

    override fun stop(): EmbeddedEngineSessionResult {
        stopCalled = true
        return EmbeddedEngineSessionResult(
            status = EmbeddedEngineSessionStatus.STOPPED,
            summary = "Embedded engine session stopped.",
            observedAtEpochMs = clock(),
        )
    }
}

private class ControllerFakeSingboxRuntime : SingboxRuntime {
    var startCalls: Int = 0
    var stopCalls: Int = 0

    override fun start() {
        startCalls += 1
    }

    override fun stop() {
        stopCalls += 1
    }

    override fun health(): SingboxRuntimeHealth = SingboxRuntimeHealth(
        healthy = true,
        summary = "Embedded sing-box runtime is healthy.",
    )
}
