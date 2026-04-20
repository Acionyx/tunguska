package io.acionyx.tunguska.security.audit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ListenerAuditTest {
    @Test
    fun `proc parser extracts listening sockets`() {
        val raw = """
          sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
           0: 0100007F:2A38 00000000:0000 0A 00000000:00000000 00:00000000 00000000 1000 0 12345 1 0000000000000000 100 0 0 10 0
           1: 00000000:01BB 00000000:0000 0A 00000000:00000000 00:00000000 00000000 1000 0 67890 1 0000000000000000 100 0 0 10 0
        """.trimIndent()

        val sockets = ProcNetTcpParser.parse(protocol = "tcp", raw = raw)

        assertEquals(2, sockets.size)
        assertEquals("127.0.0.1", sockets.first().address)
        assertEquals(10808, sockets.first().port)
        assertEquals(1000, sockets.first().uid)
    }

    @Test
    fun `udp parser extracts bound sockets`() {
        val raw = """
          sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode ref pointer drops
           0: 0100007F:1F90 00000000:0000 07 00000000:00000000 00:00000000 00000000 1000 0 12345 2 0000000000000000 0
        """.trimIndent()

        val sockets = ProcNetUdpParser.parse(protocol = "udp", raw = raw)

        assertEquals(1, sockets.size)
        assertEquals("127.0.0.1", sockets.first().address)
        assertEquals(8080, sockets.first().port)
        assertEquals(1000, sockets.first().uid)
    }

    @Test
    fun `audit flags loopback and wildcard listeners`() {
        val findings = ListenerExposureAudit.audit(
            listOf(
                ListeningSocket(protocol = "tcp", address = "127.0.0.1", port = 10808),
                ListeningSocket(protocol = "tcp", address = "0.0.0.0", port = 443),
            ),
        )

        assertTrue(findings.any { it.code == "FORBIDDEN_PORT" })
        assertTrue(findings.any { it.code == "LOOPBACK_LISTENER" })
        assertTrue(findings.any { it.code == "WILDCARD_LISTENER" })
    }

    @Test
    fun `audit can scope findings to a single uid`() {
        val findings = ListenerExposureAudit.audit(
            sockets = listOf(
                ListeningSocket(protocol = "tcp", address = "127.0.0.1", port = 10808, uid = 1000),
                ListeningSocket(protocol = "tcp", address = "127.0.0.1", port = 10808, uid = 2000),
            ),
            subjectUid = 2000,
        )

        assertEquals(2, findings.size)
        assertTrue(findings.all { it.code in setOf("FORBIDDEN_PORT", "LOOPBACK_LISTENER") })
    }
}
