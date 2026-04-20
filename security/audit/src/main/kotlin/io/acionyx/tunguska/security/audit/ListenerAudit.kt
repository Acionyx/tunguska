package io.acionyx.tunguska.security.audit

import java.net.InetAddress

data class ListeningSocket(
    val protocol: String,
    val address: String,
    val port: Int,
    val uid: Int? = null,
) {
    val isLoopback: Boolean
        get() = runCatching { InetAddress.getByName(address).isLoopbackAddress }.getOrDefault(false)

    val isWildcard: Boolean
        get() = address == "0.0.0.0" || address == "::"
}

data class AuditFinding(
    val code: String,
    val message: String,
)

data class ListenerAuditPolicy(
    val forbidAnyLoopbackListener: Boolean = true,
    val forbidWildcardListener: Boolean = true,
    val forbiddenPorts: Set<Int> = setOf(1080, 10808),
) {
    companion object {
        val Default: ListenerAuditPolicy = ListenerAuditPolicy()
    }
}

object ListenerExposureAudit {
    fun audit(
        sockets: List<ListeningSocket>,
        policy: ListenerAuditPolicy = ListenerAuditPolicy.Default,
        subjectUid: Int? = null,
    ): List<AuditFinding> = buildList {
        sockets
            .asSequence()
            .filter { socket -> subjectUid == null || socket.uid == null || socket.uid == subjectUid }
            .forEach { socket ->
            if (socket.port in policy.forbiddenPorts) {
                add(
                    AuditFinding(
                        code = "FORBIDDEN_PORT",
                        message = "${socket.protocol} listener exposed on forbidden port ${socket.port} (${socket.address}).",
                    ),
                )
            }
            if (policy.forbidAnyLoopbackListener && socket.isLoopback) {
                add(
                    AuditFinding(
                        code = "LOOPBACK_LISTENER",
                        message = "${socket.protocol} listener exposed on loopback ${socket.address}:${socket.port}.",
                    ),
                )
            }
            if (policy.forbidWildcardListener && socket.isWildcard) {
                add(
                    AuditFinding(
                        code = "WILDCARD_LISTENER",
                        message = "${socket.protocol} listener exposed on wildcard address ${socket.address}:${socket.port}.",
                    ),
                )
            }
        }
    }
}

object ProcNetSocketParser {
    fun parse(protocol: String, raw: String, listeningStates: Set<String>): List<ListeningSocket> = raw
        .lineSequence()
        .drop(1)
        .mapNotNull { line -> parseLine(protocol, line, listeningStates) }
        .toList()

    private fun parseLine(protocol: String, line: String, listeningStates: Set<String>): ListeningSocket? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 8) return null
        if (parts[3] !in listeningStates) return null

        val local = parts[1].split(":")
        if (local.size != 2) return null

        val addressHex = local[0]
        val port = local[1].toIntOrNull(16) ?: return null
        val address = decodeAddress(addressHex) ?: return null
        val uid = parts[7].toIntOrNull()

        return ListeningSocket(protocol = protocol, address = address, port = port, uid = uid)
    }

    private fun decodeAddress(hex: String): String? = when (hex.length) {
        8 -> InetAddress.getByAddress(hex.chunked(2).reversed().map { it.toInt(16).toByte() }.toByteArray()).hostAddress
        32 -> InetAddress.getByAddress(decodeIpv6(hex)).hostAddress
        else -> null
    }

    private fun decodeIpv6(hex: String): ByteArray {
        val words = hex.chunked(8).flatMap { word -> word.chunked(2).reversed() }
        return words.map { it.toInt(16).toByte() }.toByteArray()
    }
}

object ProcNetTcpParser {
    fun parse(protocol: String, raw: String): List<ListeningSocket> = ProcNetSocketParser.parse(
        protocol = protocol,
        raw = raw,
        listeningStates = setOf("0A"),
    )
}

object ProcNetUdpParser {
    fun parse(protocol: String, raw: String): List<ListeningSocket> = ProcNetSocketParser.parse(
        protocol = protocol,
        raw = raw,
        listeningStates = setOf("07"),
    )
}
