package io.acionyx.tunguska.app

import android.content.Context
import io.acionyx.tunguska.crypto.CipherBox
import io.acionyx.tunguska.domain.DnsMode
import io.acionyx.tunguska.domain.ProfileIr
import io.acionyx.tunguska.storage.EncryptedProfileStore
import io.acionyx.tunguska.storage.StoredProfile

private const val PROFILE_MASTER_KEY_ALIAS: String = "io.acionyx.tunguska.profile.master"
private const val PROFILE_STORE_RELATIVE_PATH: String = "profiles/default-profile.json.enc"

class SecureProfileRepository(
    context: Context,
    cipherBox: CipherBox = AndroidKeyStoreCipherBox(PROFILE_MASTER_KEY_ALIAS),
) {
    private val store = EncryptedProfileStore(
        path = context.filesDir.toPath().resolve(PROFILE_STORE_RELATIVE_PATH),
        cipherBox = cipherBox,
    )

    val storagePath: String = store.path.toString()
    val keyReference: String = "android-keystore:$PROFILE_MASTER_KEY_ALIAS"

    fun loadOrSeed(defaultProfile: ProfileIr): ProfileLoadResult {
        val storedProfile = store.load()
        return if (storedProfile != null) {
            val migrated = ProfileRuntimeMigrations.migrate(storedProfile.profile)
            val effectiveStoredProfile = if (migrated.profile != storedProfile.profile) {
                store.save(migrated.profile)
            } else {
                storedProfile
            }
            ProfileLoadResult(
                storedProfile = effectiveStoredProfile,
                seeded = false,
                status = migrated.status ?: "Loaded encrypted profile from app-private storage.",
            )
        } else {
            ProfileLoadResult(
                storedProfile = store.save(defaultProfile),
                seeded = true,
                status = "Seeded the bootstrap profile into encrypted app-private storage.",
            )
        }
    }

    fun reload(): StoredProfile {
        val storedProfile = checkNotNull(store.load()) {
            "No encrypted profile is stored at $storagePath."
        }
        val migrated = ProfileRuntimeMigrations.migrate(storedProfile.profile)
        return if (migrated.profile != storedProfile.profile) {
            store.save(migrated.profile)
        } else {
            storedProfile
        }
    }

    fun reseal(profile: ProfileIr): StoredProfile = store.save(profile)
}

data class ProfileLoadResult(
    val storedProfile: StoredProfile,
    val seeded: Boolean,
    val status: String,
)

internal data class ProfileMigrationResult(
    val profile: ProfileIr,
    val status: String? = null,
)

internal object ProfileRuntimeMigrations {
    private val legacyBrokenVpnDnsDefaults = setOf(
        "https://1.1.1.1/dns-query",
        "https://1.0.0.1/dns-query",
    )

    fun migrate(profile: ProfileIr): ProfileMigrationResult {
        val dns = profile.dns
        if (dns is DnsMode.VpnDns) {
            val normalizedServers = dns.servers
                .map(String::trim)
                .map(String::lowercase)
                .toSet()
            if (normalizedServers == legacyBrokenVpnDnsDefaults) {
                return ProfileMigrationResult(
                    profile = profile.copy(dns = DnsMode.SystemDns),
                    status = "Migrated legacy VPN DNS defaults to System DNS to avoid DoH-over-IP TLS failures.",
                )
            }
        }
        return ProfileMigrationResult(profile = profile)
    }
}
