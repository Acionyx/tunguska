package io.acionyx.tunguska.vpnservice

import io.acionyx.tunguska.engine.api.CompiledRuntimeAsset
import kotlin.test.assertEquals
import org.junit.Test

class VpnRuntimeContractTest {
    @Test
    fun `runtime asset encoding round trips relative paths`() {
        val runtimeAssets = listOf(
            CompiledRuntimeAsset("rule-set/geoip-ru.srs"),
            CompiledRuntimeAsset("rule-set/geoip-private.srs"),
        )

        val roundTrip = VpnRuntimeContract.decodeRuntimeAssets(
            VpnRuntimeContract.encodeRuntimeAssets(runtimeAssets),
        )

        assertEquals(runtimeAssets, roundTrip)
    }
}
