package com.v2ray.ang.handler

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServerHealthRecord
import java.security.MessageDigest

/**
 * Builds a stable, private key for a connection and decides how long a remembered result is useful.
 * Remarks and subscription ids are deliberately excluded so health survives subscription refreshes.
 */
object ServerHealthMemory {
    const val SUCCESS_REUSE_MILLIS = 7L * 24L * 60L * 60L * 1000L
    const val FAILURE_REUSE_MILLIS = 12L * 60L * 60L * 1000L
    const val SKIP_PREFLIGHT_MILLIS = 6L * 60L * 60L * 1000L
    const val RETENTION_MILLIS = 45L * 24L * 60L * 60L * 1000L

    fun key(profile: ProfileItem): String {
        val identity = listOf(
            profile.configType.name,
            profile.server.normalizedLowercase(),
            profile.serverPort.normalized(),
            profile.network.normalizedLowercase(),
            profile.headerType.normalizedLowercase(),
            profile.host.normalizedLowercase(),
            profile.path.normalized(),
            profile.seed.normalized(),
            profile.kcpMtu?.toString().orEmpty(),
            profile.kcpTti?.toString().orEmpty(),
            profile.quicSecurity.normalizedLowercase(),
            profile.quicKey.normalized(),
            profile.mode.normalizedLowercase(),
            profile.serviceName.normalized(),
            profile.authority.normalizedLowercase(),
            profile.xhttpMode.normalizedLowercase(),
            profile.xhttpExtra.normalized(),
            profile.finalMask.normalized(),
            profile.security.normalizedLowercase(),
            profile.sni.normalizedLowercase(),
            profile.alpn.normalizedLowercase(),
            profile.fingerPrint.normalizedLowercase(),
            profile.insecure?.toString().orEmpty(),
            profile.echConfigList.normalized(),
            profile.verifyPeerCertByName.normalizedLowercase(),
            profile.pinnedCA256.normalized(),
            profile.manualMode?.name.orEmpty(),
            profile.username.normalized(),
            profile.password.normalized(),
            profile.method.normalizedLowercase(),
            profile.flow.normalizedLowercase(),
            profile.publicKey.normalized(),
            profile.shortId.normalized(),
            profile.mldsa65Verify.normalized(),
            profile.secretKey.normalized(),
            profile.preSharedKey.normalized(),
            profile.localAddress.normalizedLowercase(),
            profile.reserved.normalized(),
            profile.mtu?.toString().orEmpty(),
            profile.obfsPassword.normalized(),
            profile.portHopping.normalized(),
            profile.portHoppingInterval.normalized(),
            profile.pinSHA256.normalized(),
            profile.bandwidthDown.normalized(),
            profile.bandwidthUp.normalized(),
            profile.browserDialerMode.normalizedLowercase(),
        ).joinToString(separator = "\u001f")
        return MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    fun isReusable(record: ServerHealthRecord, nowMillis: Long): Boolean {
        val age = (nowMillis - record.testedAtMillis).coerceAtLeast(0L)
        val maximumAge = if (record.isWorking) SUCCESS_REUSE_MILLIS else FAILURE_REUSE_MILLIS
        return record.testedAtMillis > 0L && age <= maximumAge
    }

    fun canSkipSocketPreflight(record: ServerHealthRecord?, nowMillis: Long): Boolean =
        record?.isWorking == true &&
            record.testedAtMillis > 0L &&
            (nowMillis - record.testedAtMillis).coerceAtLeast(0L) <= SKIP_PREFLIGHT_MILLIS

    /** Stable rank: known-working first, unknown/stale next, recently failed last. */
    fun priority(record: ServerHealthRecord?, nowMillis: Long): Int = when {
        record == null || !isReusable(record, nowMillis) -> 1
        record.isWorking -> 0
        else -> 2
    }

    private fun String?.normalized(): String = this?.trim().orEmpty()

    private fun String?.normalizedLowercase(): String = normalized().lowercase()
}
