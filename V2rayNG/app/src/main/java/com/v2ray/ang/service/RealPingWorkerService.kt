package com.v2ray.ang.service

import android.content.Context
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.ManualConfigMode
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.ManualConfigModes
import com.v2ray.ang.handler.ManualVariantConfig
import com.v2ray.ang.handler.ServerHealthMemory
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Worker that runs a batch of real-ping tests independently.
 * Each batch owns its own CoroutineScope/dispatcher and can be cancelled separately.
 */
class RealPingWorkerService(
    private val context: Context,
    private val guids: List<String>,
    private val onEvent: (RealPingEvent) -> Unit = {}
) {
    private val job = SupervisorJob()
    private val concurrency = SettingsManager.getRealPingConcurrency()
    private val dispatcher = Executors.newFixedThreadPool(concurrency).asCoroutineDispatcher()
    private val scope = CoroutineScope(job + dispatcher + CoroutineName("RealPingBatchWorker"))

    private val runningCount = AtomicInteger(0)
    private val totalCount = AtomicInteger(0)

    fun start() {
        val jobs = guids.map { guid ->
            totalCount.incrementAndGet()
            scope.launch {
                runningCount.incrementAndGet()
                try {
                    val result = startRealPing(guid)
                    onEvent(RealPingEvent.Result(guid, result))
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    // Always publish a terminal result so an old cached success cannot survive
                    // an attempted probe that crashed before returning normally.
                    onEvent(RealPingEvent.Result(guid, -1L))
                } finally {
                    val count = totalCount.decrementAndGet()
                    val left = runningCount.decrementAndGet()
                    onEvent(RealPingEvent.Progress("$left / $count"))
                }
            }
        }

        scope.launch {
            try {
                joinAll(*jobs.toTypedArray())
                onEvent(RealPingEvent.Finish("0"))
            } catch (_: CancellationException) {
                onEvent(RealPingEvent.Finish("-1"))
            } finally {
                close()
            }
        }
    }

    fun cancel() {
        job.cancel()
    }

    private fun close() {
        try {
            dispatcher.close()
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun startRealPing(guid: String): Long {
        val retFailure = -1L

        val config = MmkvManager.decodeServerConfig(guid) ?: return retFailure
        if (!ManualConfigModes.hasMode(config)
            && !config.configType.isComplexType()
            && config.configType != EConfigType.HYSTERIA2
            && config.server.isNotNullEmpty()
            && config.serverPort?.toIntOrNull() != null
        ) {
            val remembered = MmkvManager.decodeRememberedServerHealth(guid)
            if (!ServerHealthMemory.canSkipSocketPreflight(remembered, System.currentTimeMillis())) {
                val url = config.server.orEmpty()
                val port = config.serverPort.orEmpty().toInt()
                val tcpTime = SpeedtestManager.socketConnectTime(url, port, SOCKET_PREFLIGHT_TIMEOUT_MS)
                if (tcpTime <= -1L) {
                    return retFailure
                }
            }
        }

        if (ManualConfigModes.usesFineFragment(config)) {
            val fineDefinition = ManualVariantConfig.current()
                .definition(ManualConfigMode.FINE_FRAGMENT)
            val hasFallback = !fineDefinition.fallbackFingerprint.isNullOrBlank()
            val rememberedFallback = MmkvManager.decodeServerAffiliationInfo(guid)
                ?.useFineFragmentFallback == true
            val attempts = when {
                !hasFallback -> listOf(false)
                rememberedFallback -> listOf(true, false)
                else -> listOf(false, true)
            }
            attempts.forEach { useFallback ->
                val result = measureRealDelay(
                    guid,
                    config,
                    useFineFragmentFallback = useFallback,
                )
                if (result >= 0L) {
                    MmkvManager.encodeFineFragmentFallback(guid, useFallback)
                    return result
                }
            }
            return retFailure
        }

        return measureRealDelay(guid, config)
    }

    private fun measureRealDelay(
        guid: String,
        config: ProfileItem,
        useFineFragmentFallback: Boolean? = null,
    ): Long {
        val configResult = CoreConfigManager.getV2rayConfig4Speedtest(
            context,
            guid,
            fineFragmentUseFallback = useFineFragmentFallback,
        )
        if (!configResult.status) {
            return -1L
        }
        return if (ManualConfigModes.usesGoogleDns(config)) {
            CoreNativeManager.measureDelayWithDns(configResult.content, SettingsManager.getDelayTestUrl())
        } else {
            CoreNativeManager.measureOutboundDelay(configResult.content, SettingsManager.getDelayTestUrl())
        }
    }

    companion object {
        private const val SOCKET_PREFLIGHT_TIMEOUT_MS = 700
    }
}
