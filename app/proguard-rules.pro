# Preserve usable release stack traces while still shrinking and obfuscating app code.
-keepattributes SourceFile,LineNumberTable

# Keep manifest entry points stable across release obfuscation.
-keep class io.acionyx.tunguska.app.MainActivity { *; }
-keep class io.acionyx.tunguska.app.AutomationRelayActivity { *; }
-keep class io.acionyx.tunguska.app.AutomationRelayService { *; }
-keep class io.acionyx.tunguska.vpnservice.TunguskaVpnService { *; }
-keep class io.acionyx.tunguska.vpnservice.VpnRuntimeControlService { *; }

