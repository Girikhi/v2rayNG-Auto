package com.v2ray.ang.ui

import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.widget.ViewPager2
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.databinding.ItemQrcodeBinding
import com.v2ray.ang.dto.ServerHealthPhase
import com.v2ray.ang.dto.entities.PanelSubscriptionMetadata
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.Language
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.ManualConfigModes
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.util.AccountDataFormatter
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.QRCodeDecoder
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil

class MainActivity : HelperBaseActivity() {
    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    val mainViewModel: MainViewModel by viewModels()
    private lateinit var groupPagerAdapter: GroupPagerAdapter
    private lateinit var accountDashboardAdapter: AccountDashboardAdapter
    private var accountDrawerExpanded = false
    private var accountDrawerAnimator: ValueAnimator? = null
    private var connectionRequestInFlight = false
    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            val accountId = groupPagerAdapter.groups.getOrNull(position)?.id ?: return
            if (mainViewModel.subscriptionId != accountId) {
                mainViewModel.subscriptionIdChanged(accountId)
            }
            accountDashboardAdapter.selectAccount(accountId)
            binding.accountRecycler.smoothScrollToPosition(position)
            showSelectedAccount(accountId)
        }
    }

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        connectionRequestInFlight = false
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        } else if (mainViewModel.isRunning.value != true) {
            applyRunningState(isLoading = false, isRunning = false)
        }
    }
    private val requestActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) {
            restartV2Ray()
        }
        if (SettingsChangeManager.consumeSetupGroupTab()) {
            setupGroupTab()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.accountDrawerCard.layoutParams = binding.accountDrawerCard.layoutParams.apply {
            width = 0
            height = resources.displayMetrics.heightPixels / 3
        }
        setupTopBar()

        // The server pages stay lightweight; account controls live in a compact sliding drawer.
        groupPagerAdapter = GroupPagerAdapter(this, emptyList())
        binding.viewPager.adapter = groupPagerAdapter
        binding.viewPager.isUserInputEnabled = true
        binding.viewPager.registerOnPageChangeCallback(pageChangeCallback)

        accountDashboardAdapter = AccountDashboardAdapter(
            onAccountSelected = { position, accountId ->
                accountDashboardAdapter.selectAccount(accountId)
                if (binding.viewPager.currentItem == position) {
                    if (mainViewModel.subscriptionId != accountId) {
                        mainViewModel.subscriptionIdChanged(accountId)
                    }
                    showSelectedAccount(accountId)
                } else {
                    binding.viewPager.setCurrentItem(position, true)
                }
                collapseAccountDrawer()
            },
            onShareSelected = ::shareAccount,
            onDeleteSelected = ::confirmDeleteAccount,
        )
        binding.accountRecycler.layoutManager = LinearLayoutManager(
            this,
            LinearLayoutManager.VERTICAL,
            false,
        )
        binding.accountRecycler.setHasFixedSize(true)
        binding.accountRecycler.adapter = accountDashboardAdapter

        binding.fab.setOnClickListener { handleFabAction() }
        binding.buttonAdd.setOnClickListener { showAddMenu(it) }
        binding.buttonPing.setOnClickListener { mainViewModel.testAllRealPing() }
        binding.buttonRefresh.setOnClickListener { importConfigViaSub() }

        setupGroupTab()
        setupViewModel()
        SubscriptionUpdater.sync()
        mainViewModel.reloadServerList()
        lifecycleScope.launch {
            if (withContext(Dispatchers.IO) { AngConfigManager.ensureManualConfigModes() }) {
                setupGroupTab()
                mainViewModel.reloadServerList()
            }
            mainViewModel.startStartupHealthCheck()
        }

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {
        }
    }

    private fun setupTopBar() {
        setupLanguageToggle()
        binding.headerAccountsAction.contentDescription = getString(R.string.simple_open_accounts)
        binding.headerAccountsAction.setOnClickListener {
            setAccountDrawerExpanded(!accountDrawerExpanded)
        }
        binding.buttonSettings.setOnClickListener {
            requestActivityLauncher.launch(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun setupViewModel() {
        mainViewModel.updateTestResultAction.observe(this) { setTestState(it) }
        mainViewModel.quickConnectAction.observe(this) { guid ->
            if (mainViewModel.consumeQuickConnect(guid) &&
                mainViewModel.isRunning.value != true
            ) {
                beginV2RayConnection()
            }
        }
        mainViewModel.isRunning.observe(this) { isRunning ->
            applyRunningState(false, isRunning)
        }
        mainViewModel.isTesting.observe(this) { isTesting ->
            binding.buttonPing.isEnabled = !isTesting
            binding.buttonPing.text = getString(
                if (isTesting) R.string.simple_testing else R.string.simple_ping
            )
        }
        mainViewModel.serverHealthState.observe(this) { state ->
            if (state.subscriptionId != mainViewModel.subscriptionId) {
                return@observe
            }
            if (state.phase == ServerHealthPhase.READY ||
                state.phase == ServerHealthPhase.NO_WORKING_SERVERS
            ) {
                refreshGroupTabTitles(true)
            }
            if (mainViewModel.isRunning.value != true) {
                applyRunningState(isLoading = false, isRunning = false)
            }
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun setupGroupTab() {
        val groups = mainViewModel.getSubscriptions()
        groupPagerAdapter.update(groups)

        val targetIndex = if (groups.isEmpty()) {
            -1
        } else {
            groups.indexOfFirst { it.id == mainViewModel.subscriptionId }
                .takeIf { it >= 0 }
                ?: 0
        }
        if (targetIndex >= 0) {
            binding.viewPager.setCurrentItem(targetIndex, false)
            val accountId = groups[targetIndex].id
            if (mainViewModel.subscriptionId != accountId) {
                mainViewModel.subscriptionIdChanged(accountId)
            }
            accountDashboardAdapter.selectAccount(accountId)
        } else if (mainViewModel.subscriptionId.isNotEmpty()) {
            mainViewModel.subscriptionIdChanged("")
        }

        binding.accountRecycler.isVisible = groups.isNotEmpty()
        binding.accountDrawerEmpty.isVisible = groups.isEmpty()
        refreshGroupTabTitles(true)
        if (targetIndex >= 0) {
            binding.accountRecycler.scrollToPosition(targetIndex)
        }
    }

    private fun setupLanguageToggle() {
        val isPersian = SettingsManager.getLocale().language == Language.PERSIAN.code
        binding.buttonLanguage.text = if (isPersian) "EN" else "فا"
        binding.buttonLanguage.contentDescription = getString(
            if (isPersian) R.string.simple_switch_to_english
            else R.string.simple_switch_to_persian
        )
        binding.buttonLanguage.setOnClickListener {
            MmkvManager.encodeSettings(
                AppConfig.PREF_LANGUAGE,
                if (isPersian) Language.ENGLISH.code else Language.PERSIAN.code,
            )
            recreate()
        }
    }

    private fun accountDashboardItem(
        accountId: String,
        subscription: SubscriptionItem,
    ): AccountDashboardItem {
        val metadata = subscription.panelMetadata
        val workspace = metadata?.workspace
            ?.takeIf { it.isNotBlank() }
            ?: accountName(accountId, subscription)
        val user = metadata?.user
            ?.takeIf { it.isNotBlank() }
            ?: workspace

        return AccountDashboardItem(
            id = accountId,
            user = user,
            workspace = workspace,
        )
    }

    private fun accountName(accountId: String, subscription: SubscriptionItem): String =
        if (accountId == AppConfig.DEFAULT_SUBSCRIPTION_ID && subscription.url.isBlank()) {
            getString(R.string.simple_manual_configs)
        } else {
            subscription.remarks.ifBlank { getString(R.string.simple_default_account_name) }
        }

    private fun statusColor(status: String?): Int = when (status) {
        "active" -> R.color.colorPing
        "scheduled" -> R.color.color_fab_active
        "expired" -> R.color.colorPingRed
        else -> R.color.color_fab_inactive
    }

    @Suppress("UNUSED_PARAMETER")
    fun refreshGroupTabTitles(refreshAll: Boolean = false) {
        val subscriptions = MmkvManager.decodeSubscriptions()
        val selectedId = mainViewModel.subscriptionId.takeIf { selected ->
            subscriptions.any { it.guid == selected }
        } ?: subscriptions.firstOrNull()?.guid
        val accountItems = subscriptions.map {
            accountDashboardItem(it.guid, it.subscription)
        }

        accountDashboardAdapter.submitItems(accountItems, selectedId)
        binding.accountRecycler.isVisible = subscriptions.isNotEmpty()
        binding.accountDrawerEmpty.isVisible = subscriptions.isEmpty()
        showSelectedAccount(selectedId)
    }

    private fun showSelectedAccount(accountId: String?) {
        val subscription = accountId?.let(MmkvManager::decodeSubscription)
        val metadata = subscription?.panelMetadata
        val rawStatus = metadata?.status?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val status = when (rawStatus) {
            "active" -> getString(R.string.simple_status_active)
            "scheduled" -> getString(R.string.simple_status_scheduled)
            "disabled" -> getString(R.string.simple_status_disabled)
            "expired" -> getString(R.string.simple_status_expired)
            null -> getString(when {
                subscription == null -> R.string.simple_status_no_account
                subscription.url.isBlank() -> R.string.simple_status_local
                else -> R.string.simple_status_not_synced
            })
            else -> getString(R.string.simple_status_unknown)
        }
        val color = ContextCompat.getColor(this, statusColor(rawStatus))
        val workspace = metadata?.workspace?.takeIf { it.isNotBlank() }
        val accountFallback = subscription?.let { accountName(accountId.orEmpty(), it) }
        val user = metadata?.user
            ?.takeIf { it.isNotBlank() }
            ?: workspace
            ?: accountFallback
            ?: getString(R.string.simple_accounts)
        val telegramUrl = metadata?.telegramUrl?.takeIf { it.isNotBlank() }
        val hasStatus = rawStatus != null
        val hasDays = metadata?.let(::metadataExpiryMillis) != null
        val hasTotalDays = hasDays && metadata?.let(::metadataStartMillis) != null
        val centerRemainingDays = hasDays && !hasTotalDays
        val dataText = formatAccountData(metadata)
        val dataProgress = AccountDataFormatter.remainingPercent(
            metadata?.dataLimitBytes,
            metadata?.dataUsedBytes,
        )

        binding.tvAccountsTitle.text = user
        binding.accountStatusDot.backgroundTintList = ColorStateList.valueOf(color)
        binding.accountStatusRow.isVisible = hasStatus || hasDays
        binding.tvAccountStatus.isVisible = hasStatus && !centerRemainingDays
        binding.tvAccountStatus.text = getString(R.string.simple_status_value, status)
        binding.tvAccountRemaining.isVisible = hasDays
        binding.tvAccountRemaining.text = if (hasDays) formatAccountDays(subscription) else ""
        binding.tvAccountRemaining.layoutParams =
            (binding.tvAccountRemaining.layoutParams as LinearLayout.LayoutParams).apply {
                width = if (centerRemainingDays) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
                weight = if (centerRemainingDays) 1f else 0f
            }
        binding.tvAccountRemaining.gravity = if (centerRemainingDays) {
            Gravity.CENTER
        } else {
            Gravity.END or Gravity.CENTER_VERTICAL
        }
        binding.accountExpiryProgress.isVisible = hasTotalDays
        binding.accountExpiryProgress.setProgressCompat(
            if (hasTotalDays) accountExpiryProgress(subscription) else 0,
            true,
        )
        binding.tvAccountData.isVisible = dataText != null
        binding.tvAccountData.text = dataText.orEmpty()
        binding.accountDataProgress.isVisible = dataProgress != null
        binding.accountDataProgress.setProgressCompat(dataProgress ?: 0, true)
        binding.workspaceCard.isVisible = workspace != null
        binding.tvWorkspaceName.text = workspace.orEmpty()
        binding.buttonRefresh.isEnabled = !subscription?.url.isNullOrBlank()
        binding.buttonTelegramChannel.isVisible = telegramUrl != null
        binding.buttonTelegramChannel.text = telegramUrl?.let(::telegramLabel).orEmpty()
        binding.buttonTelegramChannel.setOnClickListener(
            telegramUrl?.let { url -> View.OnClickListener { Utils.openUri(this, url) } },
        )

        val count = accountId?.let(mainViewModel::getVisibleServerCount) ?: 0
        binding.tvSelectedServerCount.text = resources.getQuantityString(
            R.plurals.simple_server_count,
            count,
            count,
        )
        refreshActiveConnectionLabel()
    }

    fun refreshActiveConnectionLabel() {
        val selectedGuid = MmkvManager.getSelectServer()?.takeIf { it.isNotBlank() }
        val profile = selectedGuid?.let(MmkvManager::decodeServerConfig)
        if (selectedGuid == null || profile == null) {
            binding.tvActiveConnection.isVisible = false
            return
        }

        val accountId = profile.subscriptionId.ifBlank { AppConfig.DEFAULT_SUBSCRIPTION_ID }
        val subscription = MmkvManager.decodeSubscription(accountId)
        val account = subscription?.panelMetadata?.user
            ?.takeIf { it.isNotBlank() }
            ?: subscription?.panelMetadata?.workspace?.takeIf { it.isNotBlank() }
            ?: subscription?.let { accountName(accountId, it) }
            ?: getString(R.string.simple_default_account_name)

        val position = MmkvManager.decodeServerList(accountId).indexOf(selectedGuid)
        val server = if (ManualConfigModes.isManual(profile)) {
            profile.remarks.ifBlank {
                getString(R.string.simple_server_number, position.coerceAtLeast(0) + 1)
            }
        } else if (position >= 0) {
            getString(R.string.simple_server_number, position + 1)
        } else {
            profile.remarks.ifBlank { getString(R.string.simple_value_unavailable) }
        }

        binding.tvActiveConnection.text = getString(R.string.simple_active_connection, account, server)
        binding.tvActiveConnection.isVisible = true
    }

    private fun telegramLabel(url: String): String = url
        .removePrefix("https://")
        .removePrefix("http://")
        .removeSuffix("/")

    private fun setAccountDrawerExpanded(expanded: Boolean) {
        accountDrawerExpanded = expanded
        accountDrawerAnimator?.cancel()
        val horizontalMargins = (ACCOUNT_DRAWER_HORIZONTAL_MARGIN_DP * resources.displayMetrics.density).toInt()
        val targetWidth = if (expanded) {
            resources.displayMetrics.widthPixels - horizontalMargins
        } else {
            0
        }
        accountDrawerAnimator = ValueAnimator.ofInt(binding.accountDrawerCard.width, targetWidth).apply {
            duration = ACCOUNT_DRAWER_ANIMATION_MILLIS
            addUpdateListener { animation ->
                binding.accountDrawerCard.layoutParams = binding.accountDrawerCard.layoutParams.apply {
                    width = animation.animatedValue as Int
                }
            }
            start()
        }
        binding.accountExpandIcon.animate()
            .rotation(if (expanded) 90f else -90f)
            .setDuration(ACCOUNT_DRAWER_ANIMATION_MILLIS)
            .start()
        binding.headerAccountsAction.contentDescription = getString(
            if (expanded) R.string.simple_close_accounts else R.string.simple_open_accounts,
        )
    }

    private fun collapseAccountDrawer() {
        if (accountDrawerExpanded) setAccountDrawerExpanded(false)
    }

    private fun shareAccount(accountId: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.simple_share_account)
            .setItems(arrayOf(getString(R.string.simple_clipboard), getString(R.string.simple_qr_code))) { _, choice ->
                lifecycleScope.launch {
                    val content = withContext(Dispatchers.IO) {
                        runCatching { AngConfigManager.getAccountShareContent(accountId) }.getOrDefault("")
                    }
                    if (content.isBlank()) {
                        toastError(R.string.toast_failure)
                        return@launch
                    }
                    if (choice == 0) {
                        Utils.setClipboard(this@MainActivity, content)
                        toast(R.string.toast_success)
                    } else {
                        val bitmap = withContext(Dispatchers.Default) {
                            QRCodeDecoder.createQRCode(content)
                        }
                        if (bitmap == null) {
                            toastError(R.string.simple_qr_unavailable)
                            return@launch
                        }
                        val qrBinding = ItemQrcodeBinding.inflate(layoutInflater)
                        qrBinding.ivQcode.setImageBitmap(bitmap)
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(R.string.simple_qr_code)
                            .setView(qrBinding.root)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }
            .show()
    }

    private fun confirmDeleteAccount(accountId: String) {
        val subscription = MmkvManager.decodeSubscription(accountId) ?: return
        val accountName = subscription.panelMetadata?.user
            ?.takeIf { it.isNotBlank() }
            ?: accountName(accountId, subscription)
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.simple_delete_account_confirm, accountName))
            .setPositiveButton(R.string.simple_delete_account) { _, _ ->
                SettingsManager.removeSubscriptionWithDefault(accountId)
                setupGroupTab()
                SubscriptionUpdater.sync(forceReschedule = true)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun accountExpiryProgress(subscription: SubscriptionItem?): Int {
        val metadata = subscription?.panelMetadata ?: return 0
        val status = metadata.status?.lowercase()
        if (status == "expired" || status == "disabled" || status == "scheduled") return 0

        val expiry = metadataExpiryMillis(metadata) ?: return if (status == "active") 100 else 0
        val start = metadataStartMillis(metadata) ?: subscription.addedTime
        val duration = expiry - start
        if (duration <= 0L) return 0
        return (((expiry - System.currentTimeMillis()).toDouble() / duration) * 100)
            .toInt()
            .coerceIn(0, 100)
    }

    private fun formatAccountDays(subscription: SubscriptionItem?): String {
        val metadata = subscription?.panelMetadata
            ?: return getString(R.string.simple_days_ratio, DASH_VALUE, DASH_VALUE)
        val expiry = metadataExpiryMillis(metadata)
            ?: return getString(R.string.simple_days_ratio, DASH_VALUE, DASH_VALUE)
        val start = metadataStartMillis(metadata)
        val now = System.currentTimeMillis()
        val remainingFrom = start?.let { maxOf(now, it) } ?: now
        val remainingDays = durationDays(remainingFrom, expiry)
        if (start == null) {
            return getString(R.string.simple_days_only, formatLocalizedNumber(remainingDays))
        }
        val totalDays = durationDays(start, expiry)
        return getString(
            R.string.simple_days_ratio,
            formatLocalizedNumber(remainingDays),
            formatLocalizedNumber(totalDays),
        )
    }

    private fun formatAccountData(metadata: PanelSubscriptionMetadata?): String? {
        val total = metadata?.dataLimitBytes ?: return null
        if (total == 0L) return getString(R.string.simple_data_unlimited)
        if (total < 0L) return null
        val locale = SettingsManager.getLocale()
        val totalText = AccountDataFormatter.formatBytes(total, locale)
        val used = metadata.dataUsedBytes?.takeIf { it >= 0L }
            ?: return getString(R.string.simple_data_limit, totalText)
        val remaining = total - used.coerceAtMost(total)
        return getString(
            R.string.simple_data_remaining,
            AccountDataFormatter.formatBytes(remaining, locale),
            totalText,
        )
    }

    private fun formatLocalizedNumber(value: Long): String =
        NumberFormat.getIntegerInstance(SettingsManager.getLocale()).format(value)

    private fun durationDays(fromMillis: Long, toMillis: Long): Long {
        val duration = toMillis - fromMillis
        if (duration <= 0L) return 0L
        return ceil(duration / MILLIS_PER_DAY.toDouble()).toLong()
    }

    private fun metadataExpiryMillis(metadata: PanelSubscriptionMetadata): Long? {
        metadata.expireEpochSeconds?.let { return it * 1000L }
        return metadata.expiresAt?.let(::panelDateMillis)
    }

    private fun metadataStartMillis(metadata: PanelSubscriptionMetadata): Long? =
        metadata.startsOn?.let(::panelDateMillis)

    private fun panelDateMillis(value: String): Long? {
        value.toLongOrNull()?.let { numeric ->
            return if (numeric > EPOCH_MILLISECONDS_THRESHOLD) numeric else numeric * 1000L
        }
        return value.take(10)
            .takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?.atStartOfDay(ZoneId.systemDefault())
            ?.toInstant()
            ?.toEpochMilli()
    }

    private fun handleFabAction() {
        if (mainViewModel.isRunning.value == true) {
            applyRunningState(isLoading = true, isRunning = false)
            CoreServiceManager.stopVService(this)
        } else {
            beginV2RayConnection()
        }
    }

    private fun beginV2RayConnection() {
        if (connectionRequestInFlight ||
            mainViewModel.isRunning.value == true ||
            CoreServiceManager.isRunning()
        ) return
        connectionRequestInFlight = true
        applyRunningState(isLoading = true, isRunning = false)

        if (SettingsManager.isVpnMode()) {
            val intent = VpnService.prepare(this)
            if (intent == null) {
                connectionRequestInFlight = false
                startV2Ray()
            } else {
                requestVpnPermission.launch(intent)
            }
        } else {
            connectionRequestInFlight = false
            startV2Ray()
        }
    }

    private fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }
        CoreServiceManager.startVService(this)
    }

    fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            CoreServiceManager.stopVService(this)
        }
        lifecycleScope.launch {
            delay(500)
            startV2Ray()
        }
    }

    private fun setTestState(content: String?) {
        binding.tvTestState.text = content
    }

    private fun showAddMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(Menu.NONE, ADD_FROM_CLIPBOARD, Menu.NONE, R.string.simple_clipboard)
                .setIcon(R.drawable.ic_copy)
            menu.add(Menu.NONE, ADD_FROM_QR_CODE, Menu.NONE, R.string.simple_qr_code)
                .setIcon(R.drawable.ic_scan_24dp)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    ADD_FROM_CLIPBOARD -> addFromClipboard()
                    ADD_FROM_QR_CODE -> addFromQrCode()
                    else -> false
                }
            }
            show()
        }
    }

    private fun addFromClipboard(): Boolean {
        return try {
            val content = Utils.getClipboard(this).trim()
            if (content.isEmpty()) {
                toast(R.string.toast_none_data_clipboard)
                false
            } else {
                addFromContent(content)
                true
            }
        } catch (error: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to read import content from clipboard", error)
            toastError(R.string.toast_failure)
            false
        }
    }

    private fun addFromQrCode(): Boolean {
        launchQRCodeScanner { scanResult ->
            scanResult?.trim()?.takeIf { it.isNotEmpty() }?.let(::addFromContent)
        }
        return true
    }

    private fun addFromContent(content: String) {
        val input = content.trim().removePrefix("\uFEFF").trim()
        if (AngConfigManager.isSubscriptionInput(input)) {
            addSubscription(input)
            return
        }

        val manualAccountName = getString(R.string.simple_manual_configs)
        showLoading()
        lifecycleScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    AngConfigManager.importStandaloneConfigs(input, manualAccountName)
                }
                if (count > 0) {
                    mainViewModel.subscriptionIdChanged(AppConfig.DEFAULT_SUBSCRIPTION_ID)
                    setupGroupTab()
                    mainViewModel.testAllRealPing()
                    toast(getString(R.string.title_import_config_count, count))
                } else {
                    toastError(R.string.simple_invalid_import)
                }
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to import standalone config", error)
                toastError(R.string.simple_invalid_import)
            } finally {
                hideLoading()
            }
        }
    }

    private fun addSubscription(url: String) {
        val cleanUrl = url.trim()
        if (!Utils.isValidUrl(cleanUrl)) {
            toast(R.string.toast_invalid_url)
            return
        }
        if (!Utils.isValidSubUrl(cleanUrl)) {
            toast(R.string.toast_insecure_url_protocol)
            return
        }
        if (MmkvManager.decodeSubscriptions().any {
                it.subscription.url.trim().equals(cleanUrl, ignoreCase = true)
            }
        ) {
            toast(R.string.simple_subscription_exists)
            return
        }

        val remarks = subscriptionNameFromLink(cleanUrl)
        val subscription = SubscriptionItem(remarks = remarks, url = cleanUrl)
        val subscriptionId = Utils.getUuid()
        MmkvManager.encodeSubscription(subscriptionId, subscription)
        mainViewModel.subscriptionIdChanged(subscriptionId)
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                AngConfigManager.updateConfigViaSub(
                    SubscriptionCache(subscriptionId, subscription)
                )
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { updateResult ->
                    setupGroupTab()
                    mainViewModel.reloadServerList()
                    refreshGroupTabTitles()
                    SubscriptionUpdater.sync()
                    if (updateResult.successCount > 0) {
                        toast(
                            getString(
                                R.string.title_update_config_count,
                                updateResult.configCount
                            )
                        )
                        mainViewModel.startStartupHealthCheck(force = true)
                    } else {
                        toastError(R.string.simple_subscription_update_failed)
                    }
                }.onFailure { error ->
                    LogUtil.e(AppConfig.TAG, "Failed to add subscription", error)
                    setupGroupTab()
                    toastError(R.string.simple_subscription_update_failed)
                }
                hideLoading()
            }
        }
    }

    /** Uses the human-readable name carried after # in the subscription link. */
    private fun subscriptionNameFromLink(url: String): String {
        return runCatching {
            val uri = URI(url)
            uri.fragment
                ?.trim()
                ?.lineSequence()
                ?.firstOrNull()
                ?.take(MAX_SUBSCRIPTION_NAME_LENGTH)
                ?.takeIf { it.isNotEmpty() }
                ?: uri.host
                    ?.removePrefix("www.")
                    ?.take(MAX_SUBSCRIPTION_NAME_LENGTH)
                    .orEmpty()
        }.getOrDefault("").ifEmpty {
            getString(R.string.simple_default_account_name)
        }
    }

    private fun applyRunningState(isLoading: Boolean, isRunning: Boolean) {
        refreshActiveConnectionLabel()
        if (isLoading) {
            binding.fab.setImageResource(R.drawable.ic_fab_check)
            return
        }

        if (isRunning) {
            binding.fab.isEnabled = true
            binding.fab.setImageResource(R.drawable.ic_stop_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.simple_connect_button)
            )
            binding.fab.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.simple_on_connect_button)
            )
            binding.fab.contentDescription = getString(R.string.action_stop_service)
            setTestState(getString(R.string.simple_connected))
        } else {
            binding.fab.setImageResource(R.drawable.ic_play_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.simple_connect_idle)
            )
            binding.fab.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.simple_on_connect_idle)
            )
            binding.fab.contentDescription = getString(R.string.tasker_start_service)
            val healthState = mainViewModel.serverHealthState.value
                ?.takeIf { it.subscriptionId == mainViewModel.subscriptionId }
            when (healthState?.phase) {
                ServerHealthPhase.CHECKING -> {
                    val hasRememberedOrFreshServer = healthState.workingCount > 0
                    binding.fab.isEnabled = hasRememberedOrFreshServer
                    setTestState(
                        if (hasRememberedOrFreshServer) {
                            getString(R.string.simple_servers_ready, healthState.workingCount)
                        } else {
                            getString(R.string.simple_checking_servers)
                        }
                    )
                }
                ServerHealthPhase.REFRESHING -> {
                    binding.fab.isEnabled = false
                    setTestState(getString(R.string.simple_refreshing_account_configs))
                }
                ServerHealthPhase.READY -> {
                    binding.fab.isEnabled = true
                    setTestState(
                        getString(R.string.simple_servers_ready, healthState.workingCount)
                    )
                }
                ServerHealthPhase.NO_WORKING_SERVERS -> {
                    binding.fab.isEnabled = false
                    setTestState(getString(R.string.simple_no_working_servers))
                }
                null -> {
                    binding.fab.isEnabled = true
                    setTestState(getString(R.string.simple_tap_to_connect))
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        lifecycleScope.launch {
            delay(250L)
            mainViewModel.startStartupHealthCheck(force = true)
        }
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        return false
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.import_qrcode -> {
            importQRcode()
            true
        }

        R.id.import_clipboard -> {
            importClipboard()
            true
        }

        R.id.import_local -> {
            importConfigLocal()
            true
        }

        R.id.import_manually_policy_group -> {
            importManually(EConfigType.POLICYGROUP.value)
            true
        }

        R.id.import_manually_proxy_chain -> {
            importManually(EConfigType.PROXYCHAIN.value)
            true
        }

        R.id.import_manually_vmess -> {
            importManually(EConfigType.VMESS.value)
            true
        }

        R.id.import_manually_vless -> {
            importManually(EConfigType.VLESS.value)
            true
        }

        R.id.import_manually_ss -> {
            importManually(EConfigType.SHADOWSOCKS.value)
            true
        }

        R.id.import_manually_socks -> {
            importManually(EConfigType.SOCKS.value)
            true
        }

        R.id.import_manually_http -> {
            importManually(EConfigType.HTTP.value)
            true
        }

        R.id.import_manually_trojan -> {
            importManually(EConfigType.TROJAN.value)
            true
        }

        R.id.import_manually_wireguard -> {
            importManually(EConfigType.WIREGUARD.value)
            true
        }

        R.id.import_manually_hysteria2 -> {
            importManually(EConfigType.HYSTERIA2.value)
            true
        }

        R.id.export_all -> {
            exportAll()
            true
        }

        R.id.real_ping_all -> {
            toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
            mainViewModel.testAllRealPing()
            true
        }

        R.id.service_restart -> {
            restartV2Ray()
            true
        }

        R.id.del_all_config -> {
            delAllConfig()
            true
        }

        R.id.del_duplicate_config -> {
            delDuplicateConfig()
            true
        }

        R.id.del_invalid_config -> {
            delInvalidConfig()
            true
        }

        R.id.sort_by_test_results -> {
            sortByTestResults()
            true
        }

        R.id.sub_update -> {
            importConfigViaSub()
            true
        }

        R.id.locate_selected_config -> {
            locateSelectedServer()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun importManually(createConfigType: Int) {
        if (createConfigType == EConfigType.POLICYGROUP.value) {
            startActivity(
                Intent()
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerGroupActivity::class.java)
            )
        } else if (createConfigType == EConfigType.PROXYCHAIN.value) {
            startActivity(
                Intent()
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerProxyChainActivity::class.java)
            )
        } else {
            startActivity(
                Intent()
                    .putExtra("createConfigType", createConfigType)
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerActivity::class.java)
            )
        }
    }

    /**
     * import config from qrcode
     */
    private fun importQRcode(): Boolean {
        return addFromQrCode()
    }

    /**
     * import config from clipboard
     */
    private fun importClipboard()
            : Boolean {
        return addFromClipboard()
    }

    private fun importBatchConfig(server: String?) {
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                delay(500L)
                withContext(Dispatchers.Main) {
                    when {
                        count > 0 -> {
                            toast(getString(R.string.title_import_config_count, count))
                            mainViewModel.reloadServerList()
                            refreshGroupTabTitles()
                        }

                        countSub > 0 -> setupGroupTab()
                        else -> toastError(R.string.toast_failure)
                    }
                    hideLoading()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toastError(R.string.toast_failure)
                    hideLoading()
                }
                LogUtil.e(AppConfig.TAG, "Failed to import batch config", e)
            }
        }
    }

    /**
     * import config from local config file
     */
    private fun importConfigLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import config from local file", e)
            return false
        }
        return true
    }


    /**
     * import config from sub
     */
    fun importConfigViaSub(): Boolean {
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            val result = mainViewModel.updateConfigViaSubAll()
            delay(500L)
            launch(Dispatchers.Main) {
                if (result.successCount + result.failureCount + result.skipCount == 0) {
                    toast(R.string.title_update_subscription_no_subscription)
                } else if (result.successCount > 0 && result.failureCount + result.skipCount == 0) {
                    toast(getString(R.string.title_update_config_count, result.configCount))
                } else {
                    toast(
                        getString(
                            R.string.title_update_subscription_result,
                            result.configCount, result.successCount, result.failureCount, result.skipCount
                        )
                    )
                }
                if (result.configCount > 0) {
                    mainViewModel.reloadServerList()
                    refreshGroupTabTitles()
                }
                if (mainViewModel.subscriptionId.isNotEmpty()) {
                    SubscriptionUpdater.syncOne(subId = mainViewModel.subscriptionId)
                } else {
                    SubscriptionUpdater.sync(forceReschedule = true)
                }
                refreshGroupTabTitles(true)
                hideLoading()
            }
        }
        return true
    }

    private fun exportAll() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val ret = mainViewModel.exportAllServer()
            launch(Dispatchers.Main) {
                if (ret > 0)
                    toast(getString(R.string.title_export_config_count, ret))
                else
                    toastError(R.string.toast_failure)
                hideLoading()
            }
        }
    }

    private fun delAllConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeAllServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        refreshGroupTabTitles()
                        toast(getString(R.string.title_del_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delDuplicateConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeDuplicateServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        refreshGroupTabTitles()
                        toast(getString(R.string.title_del_duplicate_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delInvalidConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_invalid_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeInvalidServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        refreshGroupTabTitles()
                        toast(getString(R.string.title_del_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun sortByTestResults() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            mainViewModel.sortByTestResults()
            launch(Dispatchers.Main) {
                mainViewModel.reloadServerList()
                hideLoading()
            }
        }
    }

    /**
     * show file chooser
     */
    private fun showFileChooser() {
        launchFileChooser { uri ->
            if (uri == null) {
                return@launchFileChooser
            }

            readContentFromUri(uri)
        }
    }

    /**
     * read content from uri
     */
    private fun readContentFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri).use { input ->
                importBatchConfig(input?.bufferedReader()?.readText())
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to read content from URI", e)
        }
    }

    /**
     * Locates and scrolls to the currently selected server.
     * If the selected server is in a different group, automatically switches to that group first.
     */
    private fun locateSelectedServer() {
        val targetSubscriptionId = mainViewModel.findSubscriptionIdBySelect()
        if (targetSubscriptionId.isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }

        val targetGroupIndex = groupPagerAdapter.groups.indexOfFirst { it.id == targetSubscriptionId }
        if (targetGroupIndex < 0) {
            toast(R.string.toast_server_not_found_in_group)
            return
        }

        // Switch to target group if needed, then scroll to the server
        if (binding.viewPager.currentItem != targetGroupIndex) {
            binding.viewPager.setCurrentItem(targetGroupIndex, true)
            binding.viewPager.postDelayed({ scrollToSelectedServer(targetGroupIndex) }, 1000)
        } else {
            scrollToSelectedServer(targetGroupIndex)
        }
    }

    /**
     * Scrolls to the selected server in the specified fragment.
     * @param groupIndex The index of the group/fragment to scroll in
     */
    private fun scrollToSelectedServer(groupIndex: Int) {
        val itemId = groupPagerAdapter.getItemId(groupIndex)
        val fragment = supportFragmentManager.findFragmentByTag("f$itemId") as? GroupServerFragment

        if (fragment?.isAdded == true && fragment.view != null) {
            fragment.scrollToSelectedServer()
        } else {
            toast(R.string.toast_fragment_not_available)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }


    override fun onDestroy() {
        accountDrawerAnimator?.cancel()
        binding.viewPager.unregisterOnPageChangeCallback(pageChangeCallback)
        super.onDestroy()
    }

    companion object {
        private const val ADD_FROM_CLIPBOARD = 1
        private const val ADD_FROM_QR_CODE = 2
        private const val MAX_SUBSCRIPTION_NAME_LENGTH = 80
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
        private const val EPOCH_MILLISECONDS_THRESHOLD = 10_000_000_000L
        private const val ACCOUNT_DRAWER_ANIMATION_MILLIS = 220L
        private const val ACCOUNT_DRAWER_HORIZONTAL_MARGIN_DP = 28
        private const val DASH_VALUE = "—"
    }
}
