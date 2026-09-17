package com.v2ray.ang.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.AssetManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.ServerHealthPhase
import com.v2ray.ang.dto.ServerHealthState
import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.matchesPattern
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.ManualConfigModes
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.ServerHealthMemory
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.regex.PatternSyntaxException

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private var serverList = mutableListOf<String>() // MmkvManager.decodeServerList()
    var subscriptionId: String = MmkvManager.decodeSettingsString(AppConfig.CACHE_SUBSCRIPTION_ID, "").orEmpty()
    var keywordFilter = ""
    val serversCache = mutableListOf<ServersCache>()
    private val serverPositions = mutableMapOf<String, Int>()
    val isRunning by lazy { MutableLiveData<Boolean>() }
    val isTesting by lazy { MutableLiveData<Boolean>(false) }
    val updateListAction by lazy { MutableLiveData<Int>() }
    val updateTestResultAction by lazy { MutableLiveData<String>() }
    val quickConnectAction by lazy { MutableLiveData<String>() }
    val serverHealthState by lazy { MutableLiveData<ServerHealthState>() }

    private var activePingBatch: PingBatch? = null
    private var startupHealthCheckStarted = false
    private var startupRefreshAttempted = false
    private var pendingQuickConnectGuid: String? = null

    private data class PingBatch(
        val subscriptionId: String,
        val serverGuids: List<String>,
        val allowAutomaticRefresh: Boolean,
        val isAfterRefresh: Boolean,
        val completedGuids: MutableSet<String> = linkedSetOf(),
        val workingGuids: MutableSet<String> = linkedSetOf(),
        var firstWorkingSelected: Boolean = false,
    )

    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     */
    fun startListenBroadcast() {
        isRunning.value = false
        val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY)
        ContextCompat.registerReceiver(getApplication(), mMsgReceiver, mFilter, Utils.receiverFlags())
        MessageUtil.sendMsg2Service(getApplication(), AppConfig.MSG_REGISTER_CLIENT, "")
    }

    /**
     * Called when the ViewModel is cleared.
     */
    override fun onCleared() {
        getApplication<AngApplication>().unregisterReceiver(mMsgReceiver)
        LogUtil.i(AppConfig.TAG, "Main ViewModel is cleared")
        super.onCleared()
    }

    /**
     * Reloads the server list based on current subscription filter.
     */
    fun reloadServerList() {
        serverList = if (subscriptionId.isEmpty()) {
            MmkvManager.decodeAllServerList()
        } else {
            MmkvManager.decodeServerList(subscriptionId)
        }

        if (subscriptionId == AppConfig.DEFAULT_SUBSCRIPTION_ID) {
            serverList = ManualConfigModes.failuresLast(serverList) {
                MmkvManager.decodeServerAffiliationInfo(it)?.testDelayMillis ?: 0L
            }.toMutableList()
        }
        updateCache()
        updateListAction.value = -1
    }

    /**
     * Removes a server by its GUID.
     * @param guid The GUID of the server to remove.
     */
    fun removeServer(guid: String) {
        serverList.remove(guid)
        MmkvManager.removeServer(guid)
        val index = getPosition(guid)
        if (index >= 0) {
            serversCache.removeAt(index)
        }
        rebuildServerPositions()
    }

    /**
     * Swaps the positions of two servers.
     * @param fromPosition The initial position of the server.
     * @param toPosition The target position of the server.
     */
    fun swapServer(fromPosition: Int, toPosition: Int) {
        if (subscriptionId.isEmpty()) {
            return
        }

        Collections.swap(serverList, fromPosition, toPosition)
        Collections.swap(serversCache, fromPosition, toPosition)
        rebuildServerPositions()

        MmkvManager.encodeServerList(serverList, subscriptionId)
    }

    /**
     * Updates the cache of servers.
     */
    @Synchronized
    fun updateCache() {
        serversCache.clear()
        val kw = keywordFilter.trim()
        val searchRegex = try {
            if (kw.isNotEmpty()) Regex(kw, setOf(RegexOption.IGNORE_CASE)) else null
        } catch (e: PatternSyntaxException) {
            null // Fallback to literal search if regex is invalid
        }
        for (guid in serverList) {
            val profile = MmkvManager.decodeServerConfig(guid) ?: continue
            if (kw.isEmpty()) {
                serversCache.add(ServersCache(guid, profile))
                continue
            }

            val remarks = profile.remarks
            val description = profile.description.orEmpty()
            val server = profile.server.orEmpty()
            val protocol = profile.configType.name
            if (remarks.matchesPattern(searchRegex, kw)
                || description.matchesPattern(searchRegex, kw)
                || server.matchesPattern(searchRegex, kw)
                || protocol.matchesPattern(searchRegex, kw)
            ) {
                serversCache.add(ServersCache(guid, profile))
            }
        }
        rebuildServerPositions()
    }

    /**
     * Updates the configuration via subscription for all servers.
     * @return Detailed result of the subscription update operation.
     */
    fun updateConfigViaSubAll(): SubscriptionUpdateResult {
        if (subscriptionId.isEmpty()) {
            return AngConfigManager.updateConfigViaSubAll()
        } else {
            val subItem = MmkvManager.decodeSubscription(subscriptionId) ?: return SubscriptionUpdateResult()
            return AngConfigManager.updateConfigViaSub(SubscriptionCache(subscriptionId, subItem))
        }
    }

    /**
     * Exports all servers.
     * @return The number of exported servers.
     */
    fun exportAllServer(): Int {
        val serverListCopy =
            if (subscriptionId.isEmpty() && keywordFilter.isEmpty()) {
                serverList
            } else {
                serversCache.map { it.guid }.toList()
            }

        val ret = AngConfigManager.shareNonCustomConfigsToClipboard(
            getApplication<AngApplication>(),
            serverListCopy
        )
        return ret
    }

    /**
     * Tests the real ping for all servers.
     */
    fun testAllRealPing() {
        startRealPing(
            targetSubscriptionId = subscriptionId,
            allowAutomaticRefresh = false,
            isAfterRefresh = false,
        )
    }

    /**
     * Runs one guarded health check when the app opens. A forced check is used when the
     * launcher brings the existing single-task activity to the foreground again.
     */
    fun startStartupHealthCheck(force: Boolean = false) {
        if (isTesting.value == true) {
            return
        }
        if (startupHealthCheckStarted && !force) {
            return
        }

        val targetSubscriptionId = subscriptionId
        if (targetSubscriptionId.isEmpty()) {
            startupHealthCheckStarted = true
            return
        }

        startupHealthCheckStarted = true
        startupRefreshAttempted = false
        MmkvManager.pruneServerHealthMemory()
        startRealPing(
            targetSubscriptionId = targetSubscriptionId,
            allowAutomaticRefresh = true,
            isAfterRefresh = false,
        )
    }

    private fun startRealPing(
        targetSubscriptionId: String,
        allowAutomaticRefresh: Boolean,
        isAfterRefresh: Boolean,
    ) {
        MessageUtil.sendMsg2TestService(
            getApplication(),
            TestServiceMessage(key = AppConfig.MSG_MEASURE_CONFIG_CANCEL)
        )

        val originalGuids = if (targetSubscriptionId.isEmpty()) {
            MmkvManager.decodeAllServerList()
        } else {
            MmkvManager.decodeServerList(targetSubscriptionId)
        }.distinct()

        val nowMillis = System.currentTimeMillis()
        val rememberedByGuid = originalGuids.associateWith { guid ->
            MmkvManager.applyRememberedServerHealth(guid, nowMillis)
        }
        // Kotlin's sort is stable, so known-good and unknown groups keep the user's order.
        val testGuids = originalGuids.sortedBy { guid ->
            ServerHealthMemory.priority(rememberedByGuid[guid], nowMillis)
        }
        val rememberedWorkingGuids = originalGuids.filter { guid ->
            rememberedByGuid[guid]?.isWorking == true
        }

        if (subscriptionId == targetSubscriptionId && rememberedWorkingGuids.isNotEmpty()) {
            MmkvManager.setSelectServer(rememberedWorkingGuids.first())
        }

        activePingBatch = PingBatch(
            subscriptionId = targetSubscriptionId,
            serverGuids = testGuids,
            allowAutomaticRefresh = allowAutomaticRefresh,
            isAfterRefresh = isAfterRefresh,
        )
        if (subscriptionId == targetSubscriptionId) {
            reloadServerList()
        }
        isTesting.value = true
        serverHealthState.value = ServerHealthState(
            subscriptionId = targetSubscriptionId,
            phase = ServerHealthPhase.CHECKING,
            workingCount = rememberedWorkingGuids.size,
            totalCount = testGuids.size,
        )

        viewModelScope.launch(Dispatchers.Default) {
            if (testGuids.isEmpty()) {
                onTestsFinished()
                return@launch
            }
            MessageUtil.sendMsg2TestService(
                getApplication(),
                TestServiceMessage(
                    key = AppConfig.MSG_MEASURE_CONFIG_START,
                    subscriptionId = targetSubscriptionId,
                    serverGuids = testGuids,
                )
            )
        }
    }

    /**
     * Tests the real ping for the current server.
     */
    fun testCurrentServerRealPing() {
        MessageUtil.sendMsg2Service(getApplication(), AppConfig.MSG_MEASURE_DELAY, "")
    }

    /**
     * Changes the subscription ID.
     * @param id The new subscription ID.
     */
    fun subscriptionIdChanged(id: String) {
        if (subscriptionId != id) {
            subscriptionId = id
            MmkvManager.encodeSettings(AppConfig.CACHE_SUBSCRIPTION_ID, subscriptionId)
        }
        reloadServerList()
    }

    /**
     * Gets the real account groups shown by the dashboard.
     */
    fun getSubscriptions(): List<GroupMapItem> {
        val subscriptions = MmkvManager.decodeSubscriptions()
        if (subscriptionId.isNotEmpty()
            && !subscriptions.map { it.guid }.contains(subscriptionId)
        ) {
            subscriptionIdChanged("")
        }

        return subscriptions.map { sub ->
            GroupMapItem(
                id = sub.guid,
                remarks = sub.subscription.remarks,
            )
        }
    }

    /** Consumes a one-shot request emitted when the first fresh working server is found. */
    @Synchronized
    fun consumeQuickConnect(guid: String): Boolean {
        if (pendingQuickConnectGuid != guid) return false
        pendingQuickConnectGuid = null
        return true
    }

    fun getVisibleServerCount(subscriptionId: String): Int {
        val serverGuids = MmkvManager.decodeServerList(subscriptionId)
        return serverGuids.size
    }

    /**
     * Gets the position of a server by its GUID.
     * @param guid The GUID of the server.
     * @return The position of the server.
     */
    fun getPosition(guid: String): Int {
        return serverPositions[guid] ?: -1
    }

    private fun rebuildServerPositions() {
        serverPositions.clear()
        serversCache.forEachIndexed { index, server ->
            serverPositions[server.guid] = index
        }
    }

    /**
     * Removes duplicate servers.
     * Excludes servers with complex types (Custom, PolicyGroup, or ProxyChain) from duplicate comparison.
     * @return The number of removed servers.
     */
    fun removeDuplicateServer(): Int {
        val serversCacheCopy = serversCache.toList().toMutableList()
        val deleteServer = mutableListOf<String>()

        serversCacheCopy.forEachIndexed { index, sc ->
            val profile = sc.profile
            // Skip if this profile has a complex config type
            if (profile.configType.isComplexType()) {
                return@forEachIndexed
            }

            serversCacheCopy.forEachIndexed { index2, sc2 ->
                if (index2 > index) {
                    val profile2 = sc2.profile
                    // Skip if the second profile has a complex config type
                    if (profile2.configType.isComplexType()) {
                        return@forEachIndexed
                    }

                    if (profile == profile2 && !deleteServer.contains(sc2.guid)) {
                        deleteServer.add(sc2.guid)
                    }
                }
            }
        }
        for (it in deleteServer) {
            MmkvManager.removeServer(it)
        }

        return deleteServer.count()
    }

    /**
     * Removes all servers.
     * @return The number of removed servers.
     */
    fun removeAllServer(): Int {
        val count =
            if (subscriptionId.isEmpty() && keywordFilter.isEmpty()) {
                MmkvManager.removeAllServer()
            } else {
                val serversCopy = serversCache.toList()
                for (item in serversCopy) {
                    MmkvManager.removeServer(item.guid)
                }
                serversCache.toList().count()
            }
        return count
    }

    /**
     * Removes invalid servers.
     * @return The number of removed servers.
     */
    fun removeInvalidServer(): Int {
        var count = 0
        if (subscriptionId.isEmpty() && keywordFilter.isEmpty()) {
            count += MmkvManager.removeInvalidServer("")
        } else {
            val serversCopy = serversCache.toList()
            for (item in serversCopy) {
                count += MmkvManager.removeInvalidServer(item.guid)
            }
        }
        return count
    }

    /**
     * Sorts servers by their test results.
     */
    fun sortByTestResults() {
        if (subscriptionId.isEmpty()) {
            MmkvManager.decodeSubsList().forEach { guid ->
                sortByTestResultsForSub(guid)
            }
        } else {
            sortByTestResultsForSub(subscriptionId)
        }
    }

    /**
     * Sorts servers by their test results for a specific subscription.
     * @param subId The subscription ID to sort servers for.
     */
    private fun sortByTestResultsForSub(subId: String) {
        val sorted = ManualConfigModes.failuresLast(MmkvManager.decodeServerList(subId)) {
            MmkvManager.decodeServerAffiliationInfo(it)?.testDelayMillis ?: 0L
        }
        MmkvManager.encodeServerList(sorted.toMutableList(), subId)
    }


    /**
     * Initializes assets.
     * @param assets The asset manager.
     */
    fun initAssets(assets: AssetManager) {
        viewModelScope.launch(Dispatchers.Default) {
            SettingsManager.initAssets(getApplication<AngApplication>(), assets)
        }
    }

    /**
     * Filters the configuration by a keyword.
     * @param keyword The keyword to filter by.
     */
    fun filterConfig(keyword: String) {
        if (keyword == keywordFilter) {
            return
        }
        keywordFilter = keyword
        reloadServerList()
    }

    fun findSubscriptionIdBySelect(): String? {
        // Get the selected server GUID
        val selectedGuid = MmkvManager.getSelectServer()
        if (selectedGuid.isNullOrEmpty()) {
            return null
        }

        val config = MmkvManager.decodeServerConfig(selectedGuid)
        return config?.subscriptionId
    }

    fun onTestsFinished() {
        val batch = activePingBatch
        activePingBatch = null

        viewModelScope.launch(Dispatchers.Default) {
            if (batch == null) {
                withContext(Dispatchers.Main) {
                    reloadServerList()
                    isTesting.value = false
                }
                return@launch
            }

            // Cancellation or a killed worker must not leave an unverified cached success behind.
            batch.serverGuids.filterNot(batch.completedGuids::contains).forEach { guid ->
                MmkvManager.encodeServerTestDelayMillis(guid, -1L)
            }

            if (batch.subscriptionId.isNotEmpty()) {
                sortByTestResultsForSub(batch.subscriptionId)
            } else {
                sortByTestResults()
            }

            val orderedGuids = if (batch.subscriptionId.isEmpty()) batch.serverGuids
                else MmkvManager.decodeServerList(batch.subscriptionId)
            val workingGuids = orderedGuids.filter(batch.workingGuids::contains)
            if (workingGuids.isNotEmpty()) {
                if (subscriptionId == batch.subscriptionId) {
                    val selectedGuid = MmkvManager.getSelectServer()
                    if (selectedGuid == null || !workingGuids.contains(selectedGuid)) {
                        MmkvManager.setSelectServer(workingGuids.first())
                    }
                }
                withContext(Dispatchers.Main) {
                    if (subscriptionId == batch.subscriptionId) {
                        reloadServerList()
                    }
                    serverHealthState.value = ServerHealthState(
                        subscriptionId = batch.subscriptionId,
                        phase = ServerHealthPhase.READY,
                        workingCount = workingGuids.size,
                        totalCount = batch.serverGuids.size,
                    )
                    isTesting.value = false
                }
                return@launch
            }

            val shouldRefresh = batch.allowAutomaticRefresh &&
                !batch.isAfterRefresh &&
                !startupRefreshAttempted &&
                isActiveUnexpiredAccount(batch.subscriptionId) &&
                hasInternetNetwork()

            if (shouldRefresh) {
                startupRefreshAttempted = true
                serverHealthState.postValue(
                    ServerHealthState(
                        subscriptionId = batch.subscriptionId,
                        phase = ServerHealthPhase.REFRESHING,
                        totalCount = batch.serverGuids.size,
                    )
                )
                val refreshed = refreshSubscription(batch.subscriptionId)
                if (refreshed) {
                    withContext(Dispatchers.Main) {
                        if (subscriptionId == batch.subscriptionId) {
                            reloadServerList()
                        }
                        startRealPing(
                            targetSubscriptionId = batch.subscriptionId,
                            allowAutomaticRefresh = false,
                            isAfterRefresh = true,
                        )
                    }
                    return@launch
                }
            }

            withContext(Dispatchers.Main) {
                if (subscriptionId == batch.subscriptionId) {
                    MmkvManager.setSelectServer("")
                    reloadServerList()
                }
                serverHealthState.value = ServerHealthState(
                    subscriptionId = batch.subscriptionId,
                    phase = ServerHealthPhase.NO_WORKING_SERVERS,
                    totalCount = batch.serverGuids.size,
                )
                isTesting.value = false
            }
        }
    }

    private fun isActiveUnexpiredAccount(subscriptionId: String): Boolean {
        val metadata = MmkvManager.decodeSubscription(subscriptionId)?.panelMetadata ?: return false
        if (!metadata.status.equals("active", ignoreCase = true)) {
            return false
        }
        val expiresAtMillis = metadata.expireEpochSeconds?.times(1000L) ?: return false
        return expiresAtMillis > System.currentTimeMillis()
    }

    /**
     * Checks the device network directly without relying on Google's validation endpoint,
     * which can report false negatives on restricted networks.
     */
    private fun hasInternetNetwork(): Boolean {
        val connectivityManager = getApplication<AngApplication>()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private suspend fun refreshSubscription(subscriptionId: String): Boolean {
        val subscription = MmkvManager.decodeSubscription(subscriptionId) ?: return false
        val result = withContext(Dispatchers.IO) {
            AngConfigManager.updateConfigViaSub(SubscriptionCache(subscriptionId, subscription))
        }
        if (result.successCount <= 0 || result.configCount <= 0) {
            return false
        }
        SubscriptionUpdater.syncOne(subId = subscriptionId)
        return true
    }

    private val mMsgReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING -> {
                    isRunning.value = true
                }

                AppConfig.MSG_STATE_NOT_RUNNING -> {
                    isRunning.value = false
                }

                AppConfig.MSG_STATE_START_SUCCESS -> {
                    getApplication<AngApplication>().toastSuccess(R.string.toast_services_success)
                    isRunning.value = true
                }

                AppConfig.MSG_STATE_START_FAILURE -> {
                    val errorMessage = intent.getStringExtra("content")
                    if (!errorMessage.isNullOrBlank()) {
                        getApplication<AngApplication>().toastError(errorMessage)
                    } else {
                        getApplication<AngApplication>().toastError(R.string.toast_services_failure)
                    }
                    isRunning.value = false
                }

                AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    isRunning.value = false
                }

                AppConfig.MSG_MEASURE_DELAY_SUCCESS -> {
                    updateTestResultAction.value = intent.getStringExtra("content")
                }

                AppConfig.MSG_MEASURE_CONFIG_SUCCESS -> {
                    val content = intent.getStringExtra("content")
                    content?.let(::onRealPingResult)
                    updateListAction.value = getPosition(content ?: "")
                }

                AppConfig.MSG_MEASURE_CONFIG_NOTIFY -> Unit

                AppConfig.MSG_MEASURE_CONFIG_FINISH -> {
                    val content = intent.getStringExtra("content")
                    if (content == "0") {
                        onTestsFinished()
                    } else {
                        activePingBatch = null
                        isTesting.value = false
                    }
                }
            }
        }
    }

    private fun onRealPingResult(guid: String) {
        val batch = activePingBatch ?: return
        if (guid !in batch.serverGuids || !batch.completedGuids.add(guid)) return

        val delay = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: -1L
        if (delay > 0L) {
            batch.workingGuids.add(guid)
            if (!batch.firstWorkingSelected && subscriptionId == batch.subscriptionId) {
                batch.firstWorkingSelected = true
                MmkvManager.setSelectServer(guid)
                serverHealthState.value = ServerHealthState(
                    subscriptionId = batch.subscriptionId,
                    phase = ServerHealthPhase.CHECKING,
                    workingCount = batch.workingGuids.size,
                    totalCount = batch.serverGuids.size,
                )
                synchronized(this@MainViewModel) {
                    pendingQuickConnectGuid = guid
                }
                quickConnectAction.value = guid
            }
        }
    }
}
