package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.enums.ManualConfigMode
import com.v2ray.ang.util.JsonUtil
import java.net.URI

data class ManualVariantDefinition(
    val type: String = "",
    val packetsTls: String? = null,
    val packetsOther: String? = null,
    val length: String? = null,
    val delay: String? = null,
    val fingerprint: String? = null,
    val fallbackFingerprint: String? = null,
    val url: String? = null,
)

data class ManualVariantFile(
    val version: Int = 1,
    val variants: Map<String, ManualVariantDefinition> = emptyMap(),
) {
    fun definition(mode: ManualConfigMode): ManualVariantDefinition =
        requireNotNull(variants[mode.variantKey]) { "Missing '${mode.variantKey}' variant" }
}

/**
 * User-editable definitions for the fixed, storage-compatible manual variant IDs.
 * Invalid stored JSON never reaches Xray: runtime use falls back to this safe default.
 */
object ManualVariantConfig {
    val DEFAULT_JSON: String = """
        {
          "version": 1,
          "variants": {
            "original": {
              "type": "original"
            },
            "fragment": {
              "type": "fragment",
              "packetsTls": "tlshello",
              "packetsOther": "1-3",
              "length": "50-100",
              "delay": "10-20"
            },
            "fine_fragment": {
              "type": "fragment",
              "packetsTls": "tlshello",
              "packetsOther": "1-3",
              "length": "10-20",
              "delay": "1-5",
              "fingerprint": "edge",
              "fallbackFingerprint": "unsafe"
            },
            "google_doh": {
              "type": "doh",
              "url": "https://dns.google/dns-query"
            }
          }
        }
    """.trimIndent()

    private val rangePattern = Regex("^(\\d+)-(\\d+)$")
    private val allowedFingerprints = setOf(
        "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq",
        "random", "randomized", "unsafe",
    )

    @Volatile
    private var cachedRaw: String? = null

    @Volatile
    private var cachedValue: ManualVariantFile? = null

    fun current(): ManualVariantFile {
        val raw = runCatching {
            MmkvManager.decodeSettingsString(AppConfig.PREF_MANUAL_VARIANTS_JSON)
        }.getOrNull().orEmpty().ifBlank { DEFAULT_JSON }
        cachedValue?.takeIf { cachedRaw == raw }?.let { return it }
        return synchronized(this) {
            cachedValue?.takeIf { cachedRaw == raw } ?: run {
                val parsed = runCatching { parse(raw) }.getOrElse { defaults() }
                cachedRaw = raw
                cachedValue = parsed
                parsed
            }
        }
    }

    fun defaults(): ManualVariantFile = parse(DEFAULT_JSON)

    fun validationError(raw: String): String? = try {
        parse(raw)
        null
    } catch (error: Exception) {
        error.message ?: "Invalid variant JSON"
    }

    fun invalidate() {
        cachedRaw = null
        cachedValue = null
    }

    internal fun parse(raw: String): ManualVariantFile {
        require(raw.isNotBlank()) { "JSON is empty" }
        require(raw.length <= 32_768) { "JSON is too large" }
        val parsed = JsonUtil.fromJsonSafe(raw, ManualVariantFile::class.java)
            ?: throw IllegalArgumentException("Invalid JSON")
        require(parsed.version == 1) { "Unsupported version" }

        val original = parsed.definition(ManualConfigMode.ORIGINAL)
        require(original.type == "original") { "The original variant must use type 'original'" }

        validateFragment(parsed.definition(ManualConfigMode.FRAGMENT), requireFingerprints = false)
        validateFragment(parsed.definition(ManualConfigMode.FINE_FRAGMENT), requireFingerprints = true)

        val doh = parsed.definition(ManualConfigMode.GOOGLE_DOH)
        require(doh.type == "doh") { "The google_doh variant must use type 'doh'" }
        val uri = runCatching { URI(doh.url.orEmpty()) }.getOrNull()
        require(uri?.scheme.equals("https", ignoreCase = true) && !uri?.host.isNullOrBlank()) {
            "The DoH URL must be a valid HTTPS URL"
        }
        return parsed
    }

    private fun validateFragment(definition: ManualVariantDefinition, requireFingerprints: Boolean) {
        require(definition.type == "fragment") { "Fragment variants must use type 'fragment'" }
        validatePackets("packetsTls", definition.packetsTls)
        validatePackets("packetsOther", definition.packetsOther)
        validateRange("length", definition.length, minimum = 1, maximum = 65_535)
        validateRange("delay", definition.delay, minimum = 0, maximum = 60_000)
        if (requireFingerprints) {
            val primary = definition.fingerprint?.lowercase()
            val fallback = definition.fallbackFingerprint?.lowercase()
            require(primary != null && primary in allowedFingerprints) { "Invalid fingerprint" }
            require(fallback != null && fallback in allowedFingerprints) {
                "Invalid fallbackFingerprint"
            }
            require(!definition.fingerprint.equals(definition.fallbackFingerprint, ignoreCase = true)) {
                "Primary and fallback fingerprints must differ"
            }
        }
    }

    private fun validatePackets(name: String, value: String?) {
        if (value.equals("tlshello", ignoreCase = true)) return
        validateRange(name, value, minimum = 1, maximum = 65_535)
    }

    private fun validateRange(name: String, value: String?, minimum: Int, maximum: Int) {
        val match = value?.let(rangePattern::matchEntire)
            ?: throw IllegalArgumentException("Invalid $name range")
        val low = match.groupValues[1].toIntOrNull()
            ?: throw IllegalArgumentException("Invalid $name range")
        val high = match.groupValues[2].toIntOrNull()
            ?: throw IllegalArgumentException("Invalid $name range")
        require(low in minimum..maximum && high in minimum..maximum && low <= high) {
            "Invalid $name range"
        }
    }
}
