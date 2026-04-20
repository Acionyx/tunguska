package io.acionyx.tunguska.vpnservice

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class RuntimeListenerAuditTest {
    @Test
    fun `audit allows the configured authenticated loopback bridge`() {
        val auditor = RuntimeListenerAuditor(
            procNetReader = mapProcReader(
                "/proc/net/tcp" to """
                    sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
                     0: 0100007F:61A9 00000000:0000 0A 00000000:00000000 00:00000000 00000000 1000 0 12345 1 0000000000000000 100 0 0 10 0
                """.trimIndent(),
            ),
            clock = { 1111L },
            allowanceProvider = {
                setOf(
                    RuntimeAllowedLoopbackListener(
                        protocol = "tcp",
                        address = "127.0.0.1",
                        port = 25_001,
                    ),
                )
            },
        )

        val result = auditor.auditUid(uid = 1000)

        assertEquals(RuntimeAuditStatus.PASS, result.status)
        assertEquals(1, result.socketCount)
        assertTrue(result.summary.contains("Allowed authenticated loopback bridge"))
    }

    @Test
    fun `audit ignores forbidden listeners owned by other uids`() {
        val auditor = RuntimeListenerAuditor(
            procNetReader = mapProcReader(
                "/proc/net/tcp" to """
                    sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
                     0: 0100007F:2A38 00000000:0000 0A 00000000:00000000 00:00000000 00000000 2000 0 12345 1 0000000000000000 100 0 0 10 0
                """.trimIndent(),
            ),
            clock = { 1234L },
        )

        val result = auditor.auditUid(uid = 1000)

        assertEquals(RuntimeAuditStatus.PASS, result.status)
        assertEquals(0, result.findings.size)
        assertEquals(0, result.socketCount)
    }

    @Test
    fun `audit fails when current uid exposes a loopback listener`() {
        val auditor = RuntimeListenerAuditor(
            procNetReader = mapProcReader(
                "/proc/net/tcp" to """
                    sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
                     0: 0100007F:2A38 00000000:0000 0A 00000000:00000000 00:00000000 00000000 1000 0 12345 1 0000000000000000 100 0 0 10 0
                """.trimIndent(),
            ),
            clock = { 5678L },
        )

        val result = auditor.auditUid(uid = 1000)

        assertEquals(RuntimeAuditStatus.FAIL, result.status)
        assertEquals(1, result.socketCount)
        assertEquals(2, result.findings.size)
        assertTrue(result.summary.contains("loopback"))
    }

    @Test
    fun `audit reports unavailable when proc sources cannot be read`() {
        val auditor = RuntimeListenerAuditor(
            procNetReader = ProcNetReader { null },
            clock = { 9012L },
        )

        val result = auditor.auditUid(uid = 1000)

        assertEquals(RuntimeAuditStatus.UNAVAILABLE, result.status)
        assertTrue(result.summary.contains("unavailable"))
    }

    @Test
    fun `audit fails when current uid exposes a udp listener`() {
        val auditor = RuntimeListenerAuditor(
            procNetReader = mapProcReader(
                "/proc/net/udp" to """
                    sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode ref pointer drops
                     0: 0100007F:1F90 00000000:0000 07 00000000:00000000 00:00000000 00000000 1000 0 12345 2 0000000000000000 0
                """.trimIndent(),
            ),
            clock = { 3456L },
        )

        val result = auditor.auditUid(uid = 1000)

        assertEquals(RuntimeAuditStatus.FAIL, result.status)
        assertEquals(1, result.socketCount)
        assertTrue(result.findings.any { it.code == "LOOPBACK_LISTENER" })
    }

    private fun mapProcReader(vararg entries: Pair<String, String>): ProcNetReader {
        val values = entries.toMap()
        return ProcNetReader { path -> values[path] }
    }
}
