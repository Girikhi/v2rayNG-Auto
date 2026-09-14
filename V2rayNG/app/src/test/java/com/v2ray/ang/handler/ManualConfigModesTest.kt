package com.v2ray.ang.handler

import com.google.gson.JsonObject
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.ManualConfigMode
import com.v2ray.ang.util.JsonUtil
import org.junit.Assert.*
import org.junit.Test

/** Storage-free regression coverage for migration, ordering and actual runtime settings. */
class ManualConfigModesTest {
    private fun profile(mode: ManualConfigMode? = null) = ProfileItem(
        configType = EConfigType.VLESS,
        subscriptionId = AppConfig.DEFAULT_SUBSCRIPTION_ID,
        remarks = "My server — سرور من",
        server = "proxy.example.com", serverPort = "443",
        password = "00000000-0000-4000-8000-000000000001",
        network = "ws", path = "/tunnel", security = AppConfig.TLS,
        sni = "cdn.example.com", manualMode = mode,
    )

    private fun config(): V2rayConfig = V2rayConfig(
        log = V2rayConfig.LogBean(loglevel = "warning"),
        inbounds = arrayListOf(V2rayConfig.InboundBean(
            tag = "socks", port = 10808, protocol = "socks",
            sniffing = V2rayConfig.InboundBean.SniffingBean(true, arrayListOf("http", "tls", "fakedns")),
        )),
        outbounds = arrayListOf(V2rayConfig.OutboundBean(
            protocol = "vless", tag = AppConfig.TAG_PROXY,
            streamSettings = V2rayConfig.OutboundBean.StreamSettingsBean(),
        )),
        routing = V2rayConfig.RoutingBean(domainStrategy = "AsIs", rules = arrayListOf(
            V2rayConfig.RoutingBean.RulesBean(domain = listOf("geosite:cn"), outboundTag = AppConfig.TAG_DIRECT),
        )),
        dns = V2rayConfig.DnsBean(servers = arrayListOf("1.1.1.1", "fakedns"),
            hosts = mapOf("proxy.example.com" to "192.0.2.1")),
        fakedns = listOf(mapOf("ipPool" to "198.18.0.0/15")),
        stats = emptyMap<String, String>(),
    )

    @Test
    fun createsSeparateModesWithoutChangingNamesOrConnectionFields() {
        val source = profile()
        val variants = ManualConfigModes.variants(source, "source-id")
        assertEquals(ManualConfigMode.entries, variants.map { it.manualMode })
        variants.forEach {
            assertNotSame(source, it)
            assertEquals("source-id", it.manualSourceId)
            assertEquals(source.remarks, it.remarks)
            assertEquals(JsonUtil.toJson(source), JsonUtil.toJson(it.copy(manualMode = null, manualSourceId = null)))
        }
        assertNull(source.manualMode)
        assertNull(source.manualSourceId)
        assertFalse(variants[0] == variants[1])
        assertFalse(variants[1] == variants[2])
    }

    @Test
    fun simpleSubscriptionProfilesReceiveAllModesInsideTheirAccount() {
        val subscribed = profile().copy(subscriptionId = "panel-account")
        val composite = subscribed.copy(configType = EConfigType.CUSTOM)

        val expanded = ManualConfigModes.expandSubscriptionProfiles(listOf(subscribed, composite))

        val modeCount = ManualConfigMode.entries.size
        assertEquals(modeCount + 1, expanded.size)
        assertEquals(ManualConfigMode.entries, expanded.take(modeCount).map { it.manualMode })
        assertTrue(expanded.take(modeCount).all { it.subscriptionId == "panel-account" })
        assertTrue(expanded.take(modeCount).all { it.remarks == subscribed.remarks })
        assertEquals(1, expanded.take(modeCount).map { it.manualSourceId }.distinct().size)
        assertEquals(composite, expanded.last())
        assertNull(expanded.last().manualMode)
    }

    @Test
    fun migrationKeepsOriginalGuidAndIsIdempotent() {
        val original = ServersCache("selected-guid", profile())
        val completed = ManualConfigModes.completeModes(listOf(original))
        assertEquals(ManualConfigMode.entries.size, completed.size)
        assertEquals("selected-guid", completed.first().guid)
        assertEquals(ManualConfigMode.entries, completed.map { it.profile.manualMode })
        assertEquals(ManualConfigMode.entries.size, completed.map { it.guid }.distinct().size)
        assertTrue(completed.all { it.profile.manualSourceId == "selected-guid" })
        assertTrue(completed.all { it.profile.remarks == original.profile.remarks })
        assertNull(original.profile.manualMode)
        assertEquals(JsonUtil.toJson(completed), JsonUtil.toJson(ManualConfigModes.completeModes(completed)))
    }

    @Test
    fun migrationRepairsMissingVariantWithoutDuplicatingOtherModes() {
        val completed = ManualConfigModes.completeModes(listOf(ServersCache("one", profile())))
        val partial = completed.filter { it.profile.manualMode != ManualConfigMode.GOOGLE_DOH }
        val repaired = ManualConfigModes.completeModes(partial)
        assertEquals(ManualConfigMode.entries.size, repaired.size)
        assertTrue(repaired.map { it.guid }.containsAll(partial.map { it.guid }))
        assertEquals(ManualConfigMode.entries.toSet(), repaired.map { it.profile.manualMode }.toSet())
        assertEquals(JsonUtil.toJson(repaired), JsonUtil.toJson(ManualConfigModes.completeModes(repaired)))
    }

    @Test
    fun migrationAddsFineFragmentToExistingSubscriptionVariantSets() {
        val sourceId = "subscription-source"
        val oldVariants = ManualConfigModes.variants(
            profile().copy(subscriptionId = "panel-account"),
            sourceId,
        ).filter { it.manualMode != ManualConfigMode.FINE_FRAGMENT }
            .mapIndexed { index, item -> ServersCache("sub-$index", item) }

        val completed = ManualConfigModes.completeModes(oldVariants)

        assertEquals(ManualConfigMode.entries.size, completed.size)
        assertEquals(ManualConfigMode.entries.toSet(), completed.map { it.profile.manualMode }.toSet())
        assertTrue(completed.all { it.profile.subscriptionId == "panel-account" })
        assertTrue(completed.all { it.profile.manualSourceId == sourceId })
        assertTrue(completed.map { it.guid }.containsAll(oldVariants.map { it.guid }))
        assertEquals(JsonUtil.toJson(completed), JsonUtil.toJson(ManualConfigModes.completeModes(completed)))
    }

    @Test
    fun migrationRepairsMissingSourceIdOnlyOnce() {
        val completed = ManualConfigModes.completeModes(listOf(ServersCache("one", profile(ManualConfigMode.ORIGINAL))))
        assertEquals(ManualConfigMode.entries.size, completed.size)
        assertTrue(completed.all { it.profile.manualSourceId == "one" })
        assertEquals(JsonUtil.toJson(completed), JsonUtil.toJson(ManualConfigModes.completeModes(completed)))
    }

    @Test
    fun identicalNamesDoNotMergeIndependentImports() {
        val completed = ManualConfigModes.completeModes(listOf(
            ServersCache("one", profile()), ServersCache("two", profile()),
        ))
        assertEquals(ManualConfigMode.entries.size * 2, completed.size)
        assertEquals(
            mapOf("one" to ManualConfigMode.entries.size, "two" to ManualConfigMode.entries.size),
            completed.groupingBy { it.profile.manualSourceId }.eachCount(),
        )
    }

    @Test
    fun migrationDoesNotTouchSubscriptionsOrRawAndCompositeProfiles() {
        val untouched = listOf(
            ServersCache("sub", profile().copy(subscriptionId = "panel-account")),
            ServersCache("raw", profile().copy(configType = EConfigType.CUSTOM)),
            ServersCache("chain", profile().copy(configType = EConfigType.PROXYCHAIN)),
        )
        assertEquals(JsonUtil.toJson(untouched), JsonUtil.toJson(ManualConfigModes.completeModes(untouched)))
    }

    @Test
    fun modeAndSourceIdSurviveStorageSerialization() {
        ManualConfigModes.variants(profile(), "source").forEach { original ->
            val restored = requireNotNull(JsonUtil.fromJson(JsonUtil.toJson(original), ProfileItem::class.java))
            assertEquals(original.manualMode, restored.manualMode)
            assertEquals(original.manualSourceId, restored.manualSourceId)
            assertEquals(original.remarks, restored.remarks)
        }
    }

    @Test
    fun failedEntriesMoveToBottomWithoutHidingOrLatencySorting() {
        val items = listOf("failed1" to -1L, "slow" to 600L, "pending" to 0L,
            "failed2" to -3L, "fast" to 30L)
        val sorted = ManualConfigModes.failuresLast(items) { it.second }
        assertEquals(listOf("slow", "pending", "fast", "failed1", "failed2"), sorted.map { it.first })
        assertEquals(items.toSet(), sorted.toSet())
        val allFailed = items.filter { it.second < 0L }
        assertEquals(allFailed, ManualConfigModes.failuresLast(allFailed) { it.second })
    }

    @Test
    fun editedModesKeepTheirFullNamesAndSettingsAfterReloadAndMigration() {
        val fullName = "🇩🇪 My complete server name — نام کامل کانفیگ برای نمایش بدون کوتاه شدن — 1234567890"
        val entries = ManualConfigModes.completeModes(listOf(ServersCache("original", profile())))
        ManualConfigMode.entries.forEach { mode ->
            val editedEntries = entries.map { item ->
                if (item.profile.manualMode != mode) item else {
                    // The original editor loads the stored profile and changes only editable fields.
                    val edited = requireNotNull(JsonUtil.fromJson(JsonUtil.toJson(item.profile), ProfileItem::class.java))
                    edited.remarks = fullName
                    edited.server = "edited.example.com"
                    edited.serverPort = "8443"
                    edited.path = "/new-tunnel"
                    item.copy(profile = edited)
                }
            }
            val reloaded = ManualConfigModes.completeModes(editedEntries)
            assertEquals(ManualConfigMode.entries.size, reloaded.size)
            assertEquals(entries.map { it.guid }, reloaded.map { it.guid })
            val edited = reloaded.single { it.profile.manualMode == mode }.profile
            assertEquals(fullName, edited.remarks)
            assertEquals("edited.example.com", edited.server)
            assertEquals("8443", edited.serverPort)
            assertEquals("/new-tunnel", edited.path)
            assertEquals(mode, edited.manualMode)
            assertEquals("original", edited.manualSourceId)
            entries.filter { it.profile.manualMode != mode }.forEach { untouched ->
                assertEquals(JsonUtil.toJson(untouched), JsonUtil.toJson(reloaded.single { it.guid == untouched.guid }))
            }
        }
    }

    @Test
    fun tlsFragmentUsesTlsHelloAndLeavesProfileUnchanged() {
        val source = profile(ManualConfigMode.FRAGMENT)
        val before = JsonUtil.toJson(source)
        val outbound = config().outbounds.first()
        ManualConfigModes.applyFragment(source, outbound)
        val settings = (outbound.streamSettings!!.finalmask as JsonObject)
            .getAsJsonArray("tcp").single().asJsonObject.getAsJsonObject("settings")
        assertEquals("tlshello", settings.get("packets").asString)
        assertEquals("50-100", settings.get("length").asString)
        assertEquals("10-20", settings.get("delay").asString)
        assertEquals(before, JsonUtil.toJson(source))
    }

    @Test
    fun fineFragmentUsesTestedValuesAndRuntimeOnlyFingerprints() {
        val source = profile(ManualConfigMode.FINE_FRAGMENT).copy(fingerPrint = "chrome")
        val before = JsonUtil.toJson(source)
        val definitions = ManualVariantConfig.defaults()
        val outbound = config().outbounds.first()

        ManualConfigModes.applyFragment(source, outbound, definitions)

        val settings = (outbound.streamSettings!!.finalmask as JsonObject)
            .getAsJsonArray("tcp").single().asJsonObject.getAsJsonObject("settings")
        assertEquals("tlshello", settings.get("packets").asString)
        assertEquals("10-20", settings.get("length").asString)
        assertEquals("1-5", settings.get("delay").asString)
        assertEquals("edge", ManualConfigModes.runtimeProfile(source, false, definitions).fingerPrint)
        assertEquals("unsafe", ManualConfigModes.runtimeProfile(source, true, definitions).fingerPrint)
        assertEquals(before, JsonUtil.toJson(source))
    }

    @Test
    fun editableVariantJsonIsValidatedAndChangesRuntimeValues() {
        val customJson = ManualVariantConfig.DEFAULT_JSON
            .replace("\"length\": \"10-20\"", "\"length\": \"7-11\"")
            .replace("\"delay\": \"1-5\"", "\"delay\": \"2-4\"")
            .replace("\"fingerprint\": \"edge\"", "\"fingerprint\": \"ios\"")
            .replace("https://dns.google/dns-query", "https://cloudflare-dns.com/dns-query")
        assertNull(ManualVariantConfig.validationError(customJson))
        val definitions = ManualVariantConfig.parse(customJson)
        val fine = definitions.definition(ManualConfigMode.FINE_FRAGMENT)
        assertEquals("7-11", fine.length)
        assertEquals("2-4", fine.delay)
        assertEquals("ios", fine.fingerprint)
        assertEquals(
            "https://cloudflare-dns.com/dns-query",
            definitions.definition(ManualConfigMode.GOOGLE_DOH).url,
        )

        assertNotNull(ManualVariantConfig.validationError("{}"))
        assertNotNull(ManualVariantConfig.validationError(
            ManualVariantConfig.DEFAULT_JSON.replace("\"1-5\"", "\"9-2\"")
        ))
        assertNotNull(ManualVariantConfig.validationError(
            ManualVariantConfig.DEFAULT_JSON.replace("\"edge\"", "\"not-a-real-fingerprint\"")
        ))
    }

    @Test
    fun plainTcpAndRealityFragmentFirstWrites() {
        listOf(null, "", "none", "reality").forEach { security ->
            val outbound = config().outbounds.first()
            ManualConfigModes.applyFragment(profile(ManualConfigMode.FRAGMENT).copy(security = security), outbound)
            val settings = (outbound.streamSettings!!.finalmask as JsonObject)
                .getAsJsonArray("tcp").single().asJsonObject.getAsJsonObject("settings")
            assertEquals("1-3", settings.get("packets").asString)
        }
    }

    @Test
    fun fragmentPreservesOtherMasksAndDoesNotAccumulate() {
        val outbound = config().outbounds.first()
        outbound.streamSettings!!.finalmask = JsonUtil.parseString(
            """{"tcp":[{"type":"fragment","settings":{"packets":"tlshello"}},{"type":"header-custom"}],"udp":[{"type":"noise"}],"quicParams":{"congestion":"bbr"}}"""
        )
        repeat(2) { ManualConfigModes.applyFragment(profile(ManualConfigMode.FRAGMENT), outbound) }
        val masks = outbound.streamSettings!!.finalmask as JsonObject
        assertEquals(listOf("fragment", "header-custom"), masks.getAsJsonArray("tcp").map { it.asJsonObject.get("type").asString })
        assertEquals("noise", masks.getAsJsonArray("udp").single().asJsonObject.get("type").asString)
        assertEquals("bbr", masks.getAsJsonObject("quicParams").get("congestion").asString)
    }

    @Test
    fun originalAndDohModesDoNotAddFragmentButSubscriptionFragmentDoes() {
        listOf(profile(), profile(ManualConfigMode.ORIGINAL), profile(ManualConfigMode.GOOGLE_DOH)).forEach { source ->
            val outbound = config().outbounds.first()
            val before = JsonUtil.toJson(outbound)
            ManualConfigModes.applyFragment(source, outbound)
            assertEquals(before, JsonUtil.toJson(outbound))
        }
        val subscribedFragment = profile(ManualConfigMode.FRAGMENT).copy(subscriptionId = "panel-account")
        val subscriptionOutbound = config().outbounds.first()
        ManualConfigModes.applyFragment(subscribedFragment, subscriptionOutbound)
        assertNotNull(subscriptionOutbound.streamSettings!!.finalmask)

        assertTrue(ManualConfigModes.usesGoogleDns(profile(ManualConfigMode.GOOGLE_DOH)))
        assertTrue(ManualConfigModes.usesGoogleDns(
            profile(ManualConfigMode.GOOGLE_DOH).copy(subscriptionId = "panel-account")
        ))
        assertFalse(ManualConfigModes.usesGoogleDns(profile(ManualConfigMode.ORIGINAL)))
    }

    @Test
    fun udpOnlyFragmentIsExplicitlyUnsupportedRatherThanANoop() {
        val source = profile(ManualConfigMode.FRAGMENT)
        listOf(source.copy(configType = EConfigType.WIREGUARD), source.copy(configType = EConfigType.HYSTERIA2),
            source.copy(network = "quic"), source.copy(network = "kcp"), source.copy(alpn = "h3")).forEach {
            assertFalse(ManualConfigModes.supportsFragment(it))
            val outbound = config().outbounds.first()
            assertThrows(IllegalArgumentException::class.java) { ManualConfigModes.applyFragment(it, outbound) }
            assertNull(outbound.streamSettings!!.finalmask)
        }
    }

    @Test
    fun googleDohReplacesFallbackDnsPreservesBootstrapAndRoutesQueriesThroughProxy() {
        val runtime = config()
        ManualConfigModes.applyGoogleDns(runtime)
        assertEquals(listOf("https://dns.google/dns-query"), runtime.dns!!.servers)
        assertEquals(AppConfig.TAG_DNS, runtime.dns!!.tag)
        assertEquals("192.0.2.1", runtime.dns!!.hosts!!["proxy.example.com"])
        assertEquals(AppConfig.DNS_GOOGLE_ADDRESSES, runtime.dns!!.hosts!![AppConfig.DNS_GOOGLE_DOMAIN])
        assertNull(runtime.fakedns)
        assertFalse(runtime.inbounds.first().sniffing!!.destOverride.contains("fakedns"))
        val intercept = runtime.routing.rules[0]
        assertEquals(listOf("socks", "tun"), intercept.inboundTag)
        assertEquals("53", intercept.port)
        assertEquals("dns-out", intercept.outboundTag)
        val dnsRoute = runtime.routing.rules[1]
        assertEquals(listOf(AppConfig.TAG_DNS), dnsRoute.inboundTag)
        assertEquals(AppConfig.TAG_PROXY, dnsRoute.outboundTag)
        assertEquals(1, runtime.outbounds.count { it.tag == "dns-out" && it.protocol == "dns" })
    }

    @Test
    fun googleDohReusesExistingDnsOutbound() {
        val runtime = config()
        runtime.outbounds.add(V2rayConfig.OutboundBean(protocol = "dns", tag = "dns-out"))
        ManualConfigModes.applyGoogleDns(runtime)
        assertEquals(1, runtime.outbounds.count { it.tag == "dns-out" })
    }

    @Test
    fun speedtestRetainsGoogleDohSoEachVariantTestsItsOwnDns() {
        val runtime = config()
        ManualConfigModes.applyGoogleDns(runtime)
        CoreConfigManager.postProcessForSpeedtest(runtime, keepDns = true)
        assertEquals(listOf("https://dns.google/dns-query"), runtime.dns!!.servers)
        assertTrue(runtime.inbounds.isEmpty())
        assertNull(runtime.stats)
        assertNull(runtime.fakedns)
        assertEquals(1, runtime.routing.rules.size)
        assertEquals(listOf(AppConfig.TAG_DNS), runtime.routing.rules.single().inboundTag)
        assertEquals(AppConfig.TAG_PROXY, runtime.routing.rules.single().outboundTag)
    }

    @Test
    fun originalSpeedtestKeepsExistingLightweightBehavior() {
        val runtime = config()
        CoreConfigManager.postProcessForSpeedtest(runtime)
        assertNull(runtime.dns)
        assertTrue(runtime.inbounds.isEmpty())
        assertTrue(runtime.routing.rules.isEmpty())
    }
}
