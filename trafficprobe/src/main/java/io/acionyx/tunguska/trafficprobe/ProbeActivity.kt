package io.acionyx.tunguska.trafficprobe

import android.app.Activity
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class ProbeActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var endpointView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())
        updateEndpointText()
        if (intent.getBooleanExtra(EXTRA_AUTO_PROBE, false)) {
            startProbe()
        }
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateEndpointText()
        if (intent?.getBooleanExtra(EXTRA_AUTO_PROBE, false) == true) {
            startProbe()
        }
    }

    private fun buildContentView(): LinearLayout {
        val density = resources.displayMetrics.density
        val padding = (20 * density).toInt()
        val spacing = (12 * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, padding, padding, padding)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val title = TextView(this).apply {
            text = "Traffic Probe"
            textSize = 22f
        }
        endpointView = TextView(this).apply {
            textSize = 14f
            movementMethod = ScrollingMovementMethod()
        }
        val probeButton = Button(this).apply {
            text = "Fetch Public IP"
            setOnClickListener { startProbe() }
        }
        statusView = TextView(this).apply {
            text = "Status: idle"
            textSize = 18f
            movementMethod = ScrollingMovementMethod()
        }

        root.addView(title, layoutParams())
        root.addView(endpointView, layoutParams(topMargin = spacing))
        root.addView(probeButton, layoutParams(topMargin = spacing))
        root.addView(statusView, layoutParams(topMargin = spacing))
        return root
    }

    private fun layoutParams(topMargin: Int = 0): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        this.topMargin = topMargin
    }

    private fun updateEndpointText() {
        endpointView.text = "Endpoint: ${probeUrl()}"
    }

    private fun probeUrl(): String = intent.getStringExtra(EXTRA_PROBE_URL)?.trim().orEmpty().ifBlank {
        DEFAULT_PROBE_URL
    }

    private fun startProbe() {
        statusView.text = "Status: probing"
        val endpoint = probeUrl()
        thread(name = "traffic-probe", isDaemon = true) {
            val outcome = runCatching {
                fetchPublicIp(endpoint)
            }.fold(
                onSuccess = { ip -> "Status: success\nIP: $ip" },
                onFailure = { error -> "Status: error\nError: ${error.message ?: error.javaClass.simpleName}" },
            )
            runOnUiThread {
                statusView.text = outcome
            }
        }
    }

    private fun fetchPublicIp(endpoint: String): String {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 12_000
            requestMethod = "GET"
            instanceFollowRedirects = true
        }
        return connection.inputStream.bufferedReader().use { reader ->
            reader.readText().trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().ifBlank {
                error("Probe endpoint returned an empty response.")
            }
        }
    }

    companion object {
        const val EXTRA_PROBE_URL: String = "io.acionyx.tunguska.trafficprobe.extra.PROBE_URL"
        const val EXTRA_AUTO_PROBE: String = "io.acionyx.tunguska.trafficprobe.extra.AUTO_PROBE"
        const val DEFAULT_PROBE_URL: String = "https://api.ipify.org/"
    }
}
