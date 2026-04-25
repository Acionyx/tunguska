package io.acionyx.tunguska.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeErrorPresentationTest {
    @Test
    fun `returns null when no runtime error is present`() {
        assertNull(runtimeFailureDisplayText(null))
    }

    @Test
    fun `routing compile failures become editor guidance`() {
        val rendered = runtimeFailureDisplayText(
            rawError = "The xray+tun2socks compatibility lane could not compile the VLESS + REALITY over TCP shape while compiling the Routing section: Routing rule 'package-only' uses only criteria unsupported by the xray+tun2socks compatibility lane: packageNames.",
            fieldPath = "routing.rules",
        )

        requireNotNull(rendered)
        assertFalse(rendered.contains("(routing.rules)"))
        assertTrue(rendered.contains("Routing section"))
        assertTrue(rendered.contains("Review Routing in the profile editor."))
    }

    @Test
    fun `dns compile failures become editor guidance`() {
        val rendered = runtimeFailureDisplayText(
            rawError = "The xray+tun2socks compatibility lane could not compile the VLESS + REALITY over TCP shape while compiling the DNS section: DNS endpoint '' is not a valid DoT address for the xray+tun2socks compatibility lane.",
            fieldPath = "dns.endpoints",
        )

        assertEquals(
            "The xray+tun2socks compatibility lane could not compile the VLESS + REALITY over TCP shape while compiling the DNS section: DNS endpoint '' is not a valid DoT address for the xray+tun2socks compatibility lane. Review DNS handling in the profile editor.",
            rendered,
        )
    }

    @Test
    fun `section metadata becomes editor guidance when field path is missing`() {
        val rendered = runtimeFailureDisplayText(
            rawError = "The xray+tun2socks compatibility lane could not compile the VLESS + REALITY over TCP shape while compiling the Routing section: Routing rule 'package-only' uses only criteria unsupported by the xray+tun2socks compatibility lane: packageNames.",
            section = "Routing",
        )

        requireNotNull(rendered)
        assertTrue(rendered.contains("Routing section"))
        assertTrue(rendered.contains("Review Routing in the profile editor."))
    }

    @Test
    fun `falls back to legacy token parsing when field path is missing`() {
        val rendered = runtimeFailureDisplayText(
            "The xray+tun2socks compatibility lane could not compile the VLESS + REALITY over TCP shape while compiling the Routing section (routing.rules): Routing rule 'package-only' uses only criteria unsupported by the xray+tun2socks compatibility lane: packageNames.",
        )

        requireNotNull(rendered)
        assertFalse(rendered.contains("(routing.rules)"))
        assertTrue(rendered.contains("Review Routing in the profile editor."))
    }

    @Test
    fun `diagnostics rows include rendered guidance and structured metadata`() {
        val rows = runtimeFailureDiagnosticsRows(
            rawError = "The xray+tun2socks compatibility lane could not compile the VLESS + REALITY over TCP shape while compiling the Routing section: Routing rule 'package-only' uses only criteria unsupported by the xray+tun2socks compatibility lane: packageNames.",
            section = "Routing",
            fieldPath = "routing.rules",
        )

        assertEquals(
            listOf(
                "Failure guidance" to "The xray+tun2socks compatibility lane could not compile the VLESS + REALITY over TCP shape while compiling the Routing section: Routing rule 'package-only' uses only criteria unsupported by the xray+tun2socks compatibility lane: packageNames. Review Routing in the profile editor.",
                "Error section" to "Routing",
                "Error field path" to "routing.rules",
            ),
            rows,
        )
    }
}