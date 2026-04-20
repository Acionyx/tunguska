package io.acionyx.tunguska.vpnservice

import io.acionyx.tunguska.security.audit.AuditFinding
import io.acionyx.tunguska.security.audit.ListenerAuditPolicy
import io.acionyx.tunguska.security.audit.ListenerExposureAudit
import io.acionyx.tunguska.security.audit.ListeningSocket
import io.acionyx.tunguska.security.audit.ProcNetTcpParser
import io.acionyx.tunguska.security.audit.ProcNetUdpParser
import java.io.File

enum class RuntimeAuditStatus {
    NOT_RUN,
    PASS,
    FAIL,
    UNAVAILABLE,
}

data class RuntimeListenerAuditResult(
    val status: RuntimeAuditStatus,
    val findings: List<AuditFinding>,
    val summary: String,
    val auditedAtEpochMs: Long,
    val socketCount: Int,
)

fun interface ProcNetReader {
    fun read(path: String): String?
}

class RuntimeListenerAuditor(
    private val procNetReader: ProcNetReader = FilesystemProcNetReader,
    private val clock: () -> Long = System::currentTimeMillis,
    private val policy: ListenerAuditPolicy = ListenerAuditPolicy.Default,
    private val allowanceProvider: () -> Set<RuntimeAllowedLoopbackListener> = RuntimeListenerAllowanceStore::snapshot,
) {
    fun auditUid(uid: Int): RuntimeListenerAuditResult {
        val inventories = PROC_NET_SOURCES.mapNotNull { source ->
            procNetReader.read(source.path)?.let { raw ->
                source.parser(source.protocol, raw)
            }
        }

        if (inventories.isEmpty()) {
            return RuntimeListenerAuditResult(
                status = RuntimeAuditStatus.UNAVAILABLE,
                findings = emptyList(),
                summary = "Listener audit unavailable because /proc/net socket sources could not be read.",
                auditedAtEpochMs = clock(),
                socketCount = 0,
            )
        }

        val sockets = inventories.flatten()
        val subjectSockets = sockets.filter { socket -> socket.uid == null || socket.uid == uid }
        val allowedListeners = allowanceProvider()
        val auditedSockets = subjectSockets.filterNot { socket ->
            socket.isLoopback && allowedListeners.any { allowed -> allowed.matches(socket) }
        }
        val findings = ListenerExposureAudit.audit(
            sockets = auditedSockets,
            policy = policy,
        )
        val status = if (findings.isEmpty()) RuntimeAuditStatus.PASS else RuntimeAuditStatus.FAIL
        return RuntimeListenerAuditResult(
            status = status,
            findings = findings,
            summary = buildSummary(
                status = status,
                findings = findings,
                uid = uid,
                sockets = subjectSockets,
                allowedCount = subjectSockets.size - auditedSockets.size,
            ),
            auditedAtEpochMs = clock(),
            socketCount = subjectSockets.size,
        )
    }

    private fun buildSummary(
        status: RuntimeAuditStatus,
        findings: List<AuditFinding>,
        uid: Int,
        sockets: List<ListeningSocket>,
        allowedCount: Int,
    ): String = when (status) {
        RuntimeAuditStatus.PASS -> {
            if (sockets.isEmpty() && allowedCount == 0) {
                "No listening sockets detected for uid $uid."
            } else {
                buildString {
                    append("No forbidden listeners detected for uid $uid across ${sockets.size} socket(s).")
                    if (allowedCount > 0) {
                        append(" Allowed authenticated loopback bridge(s): $allowedCount.")
                    }
                }
            }
        }

        RuntimeAuditStatus.FAIL -> findings
            .take(3)
            .joinToString(separator = " ") { it.message }
            .let { prefix ->
                if (findings.size > 3) {
                    "$prefix ${findings.size - 3} additional finding(s) omitted."
                } else {
                    prefix
                }
            }

        RuntimeAuditStatus.NOT_RUN -> "Listener audit has not been run."
        RuntimeAuditStatus.UNAVAILABLE -> "Listener audit unavailable."
    }

    private data class ProcNetSource(
        val path: String,
        val protocol: String,
        val parser: (String, String) -> List<ListeningSocket>,
    )

    private companion object {
        private val PROC_NET_SOURCES = listOf(
            ProcNetSource(path = "/proc/net/tcp", protocol = "tcp", parser = ProcNetTcpParser::parse),
            ProcNetSource(path = "/proc/net/tcp6", protocol = "tcp6", parser = ProcNetTcpParser::parse),
            ProcNetSource(path = "/proc/net/udp", protocol = "udp", parser = ProcNetUdpParser::parse),
            ProcNetSource(path = "/proc/net/udp6", protocol = "udp6", parser = ProcNetUdpParser::parse),
        )
    }
}

data class RuntimeAllowedLoopbackListener(
    val protocol: String = "tcp",
    val address: String = "127.0.0.1",
    val port: Int,
) {
    fun matches(socket: ListeningSocket): Boolean {
        return socket.protocol.equals(protocol, ignoreCase = true) &&
            socket.address == address &&
            socket.port == port
    }
}

object RuntimeListenerAllowanceStore {
    private val lock = Any()
    private var allowedListeners: Set<RuntimeAllowedLoopbackListener> = emptySet()

    fun replace(listeners: Set<RuntimeAllowedLoopbackListener>) {
        synchronized(lock) {
            allowedListeners = listeners.toSet()
        }
    }

    fun clear() {
        synchronized(lock) {
            allowedListeners = emptySet()
        }
    }

    fun snapshot(): Set<RuntimeAllowedLoopbackListener> = synchronized(lock) { allowedListeners.toSet() }
}

private object FilesystemProcNetReader : ProcNetReader {
    override fun read(path: String): String? = runCatching { File(path).readText() }.getOrNull()
}
