package com.wifiscout.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var testJob: Job? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var currentNetInfo: CurrentNetworkInfo? = null
    private var lastResult: SpeedTestResult? = null
    private var onlyStarredFilter = false

    private lateinit var tvNetworkTitle: TextView
    private lateinit var tvNetworkDetail: TextView
    private lateinit var tvAutoEngineBadge: TextView
    private lateinit var permissionBanner: LinearLayout
    private lateinit var tvPermissionReason: TextView
    private lateinit var btnFixPermission: Button
    private lateinit var autoProgressBanner: LinearLayout
    private lateinit var tvAutoProgress: TextView
    private lateinit var btnViewAutoLog: TextView
    private lateinit var btnStopAuto: TextView

    private lateinit var tvPhase: TextView
    private lateinit var tvLiveSpeed: TextView
    private lateinit var tvDown: TextView
    private lateinit var tvDownSub: TextView
    private lateinit var tvUp: TextView
    private lateinit var tvUpSub: TextView
    private lateinit var tvPing: TextView
    private lateinit var tvJitter: TextView
    private lateinit var btnAutoBatchTest: Button
    private lateinit var btnStartTest: Button
    private lateinit var btnModeToggle: TextView
    private lateinit var btnNodeToggle: TextView
    private lateinit var tvMetaInfo: TextView

    private lateinit var verdictCard: LinearLayout
    private lateinit var tvVerdictTier: TextView
    private lateinit var tvVerdictScore: TextView
    private lateinit var tvVerdictTitle: TextView
    private lateinit var tvVerdictDesc: TextView
    private lateinit var tvCapSummary: TextView
    private lateinit var btnCopyReport: Button

    private lateinit var btnScanWifis: TextView
    private lateinit var btnFilterStarred: TextView
    private lateinit var tvScanStatus: TextView
    private lateinit var scanListContainer: LinearLayout

    private lateinit var tvBestCallout: TextView
    private lateinit var btnClearHistory: TextView
    private lateinit var historyContainer: LinearLayout
    private lateinit var tvEmptyHistory: TextView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshNetworkStatus()
        renderScannedWifis(fromLiveScan = false)
    }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            renderScannedWifis(fromLiveScan = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupListeners()
        updateSettingsChips()
        renderHistory()

        if (!NetworkInspector.hasFineLocationPermission(this)) {
            requestWifiPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshNetworkStatus()
        updateAutoEngineBadge()

        val cb = NetworkInspector.createLiveWifiCallback {
            runOnUiThread {
                refreshNetworkStatus()
                renderScannedWifis(fromLiveScan = false)
            }
        }
        netCallback = cb
        NetworkInspector.registerCallback(this, cb)

        try {
            ContextCompat.registerReceiver(
                this,
                scanReceiver,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Exception) {
        }

        WifiAutoSwitchService.setHostActivity(this)
        WifiAutoSwitchService.statusListener = { status ->
            runOnUiThread { handleAutoStatus(status) }
        }
        handleAutoStatus(WifiAutoSwitchService.lastStatus)

        // 自动轮测期间不触发主动扫描，避免抢占 Wi-Fi 射频干扰连接与测速
        if (!WifiAutoSwitchService.lastStatus.running) {
            NetworkInspector.triggerWifiScan(this)
        }
        renderScannedWifis(fromLiveScan = false)
        renderHistory()
    }

    override fun onPause() {
        super.onPause()
        netCallback?.let { NetworkInspector.unregisterCallback(this, it) }
        netCallback = null
        try {
            unregisterReceiver(scanReceiver)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        WifiAutoSwitchService.clearHostActivity(this)
        WifiAutoSwitchService.statusListener = null
        uiScope.cancel()
    }

    private fun bindViews() {
        tvNetworkTitle = findViewById(R.id.tvNetworkTitle)
        tvNetworkDetail = findViewById(R.id.tvNetworkDetail)
        tvAutoEngineBadge = findViewById(R.id.tvAutoEngineBadge)
        permissionBanner = findViewById(R.id.permissionBanner)
        tvPermissionReason = findViewById(R.id.tvPermissionReason)
        btnFixPermission = findViewById(R.id.btnFixPermission)
        autoProgressBanner = findViewById(R.id.autoProgressBanner)
        tvAutoProgress = findViewById(R.id.tvAutoProgress)
        btnViewAutoLog = findViewById(R.id.btnViewAutoLog)
        btnStopAuto = findViewById(R.id.btnStopAuto)

        tvPhase = findViewById(R.id.tvPhase)
        tvLiveSpeed = findViewById(R.id.tvLiveSpeed)
        tvDown = findViewById(R.id.tvDown)
        tvDownSub = findViewById(R.id.tvDownSub)
        tvUp = findViewById(R.id.tvUp)
        tvUpSub = findViewById(R.id.tvUpSub)
        tvPing = findViewById(R.id.tvPing)
        tvJitter = findViewById(R.id.tvJitter)
        btnAutoBatchTest = findViewById(R.id.btnAutoBatchTest)
        btnStartTest = findViewById(R.id.btnStartTest)
        btnModeToggle = findViewById(R.id.btnModeToggle)
        btnNodeToggle = findViewById(R.id.btnNodeToggle)
        tvMetaInfo = findViewById(R.id.tvMetaInfo)

        verdictCard = findViewById(R.id.verdictCard)
        tvVerdictTier = findViewById(R.id.tvVerdictTier)
        tvVerdictScore = findViewById(R.id.tvVerdictScore)
        tvVerdictTitle = findViewById(R.id.tvVerdictTitle)
        tvVerdictDesc = findViewById(R.id.tvVerdictDesc)
        tvCapSummary = findViewById(R.id.tvCapSummary)
        btnCopyReport = findViewById(R.id.btnCopyReport)

        btnScanWifis = findViewById(R.id.btnScanWifis)
        btnFilterStarred = findViewById(R.id.btnFilterStarred)
        tvScanStatus = findViewById(R.id.tvScanStatus)
        scanListContainer = findViewById(R.id.scanListContainer)

        tvBestCallout = findViewById(R.id.tvBestCallout)
        btnClearHistory = findViewById(R.id.btnClearHistory)
        historyContainer = findViewById(R.id.historyContainer)
        tvEmptyHistory = findViewById(R.id.tvEmptyHistory)
    }

    private fun setupListeners() {
        tvAutoEngineBadge.setOnClickListener {
            if (WifiAutoSwitchService.isEnabled()) {
                toast("自动切网引擎已开启！可直接点任意 Wi-Fi 切换，或点一键轮测")
            } else {
                showAccessibilitySetupDialog()
            }
        }

        btnFixPermission.setOnClickListener {
            val info = currentNetInfo
            if (info != null && info.needsLocationToggle) {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } else {
                requestWifiPermissions()
            }
        }

        btnViewAutoLog.setOnClickListener {
            val logText = WifiAutoSwitchService.getLogsText()
            AlertDialog.Builder(this)
                .setTitle("自动切网运行日志")
                .setMessage(logText)
                .setPositiveButton("复制日志") { _, _ ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("wifi_scout_log", logText))
                    toast("已复制运行日志")
                }
                .setNegativeButton("关闭", null)
                .show()
        }

        btnStopAuto.setOnClickListener {
            WifiAutoSwitchService.instance?.cancelRunningTask()
            autoProgressBanner.visibility = View.GONE
        }

        // 主按钮 1：一键全量自动轮测附近全部可连 Wi-Fi
        btnAutoBatchTest.setOnClickListener {
            if (!ensureLocationReady()) return@setOnClickListener
            val svc = WifiAutoSwitchService.instance
            if (svc == null) {
                showAccessibilitySetupDialog()
                return@setOnClickListener
            }
            if (WifiAutoSwitchService.lastStatus.running) {
                svc.cancelRunningTask()
            } else {
                svc.runBatchInspectSavedWifis(onlyStarred = onlyStarredFilter)
            }
        }

        // 次按钮 2：仅测当前网络
        btnStartTest.setOnClickListener {
            if (testJob?.isActive == true) {
                testJob?.cancel()
                testJob = null
                setRunningState(false)
                tvPhase.text = "已停止测速"
            } else {
                startSpeedTest()
            }
        }

        btnModeToggle.setOnClickListener {
            val next = if (HistoryStore.getMode(this) == TestMode.LITE) TestMode.FULL else TestMode.LITE
            HistoryStore.setMode(this, next)
            updateSettingsChips()
            toast(if (next == TestMode.LITE) "省流模式 (~2-5MB)" else "精准模式 (~10-22MB)")
        }

        btnNodeToggle.setOnClickListener {
            val next = when (HistoryStore.getNodePref(this)) {
                NodePref.AUTO -> NodePref.CLOUDFLARE
                NodePref.CLOUDFLARE -> NodePref.CN
                NodePref.CN -> NodePref.AUTO
            }
            HistoryStore.setNodePref(this, next)
            updateSettingsChips()
        }

        btnCopyReport.setOnClickListener { copyVerdictReport() }

        btnScanWifis.setOnClickListener {
            if (!ensureLocationReady()) return@setOnClickListener
            val ok = NetworkInspector.triggerWifiScan(this)
            renderScannedWifis(fromLiveScan = false)
            toast(if (ok) "正在刷新附近 Wi-Fi 列表…" else "已展示最新信号强度（系统限频中）")
        }

        btnFilterStarred.setOnClickListener {
            onlyStarredFilter = !onlyStarredFilter
            btnFilterStarred.text = if (onlyStarredFilter) "★ 仅已标" else "★ 全部"
            renderScannedWifis(fromLiveScan = false)
        }

        btnClearHistory.setOnClickListener {
            HistoryStore.clearHistory(this)
            HistoryStore.clearAllNoPasswordSsids(this)
            renderHistory()
            renderScannedWifis(fromLiveScan = false)
            WifiScoutWidgetProvider.refreshAllWidgets(this)
            toast("已清空实测排行榜并重置跳过记录")
        }
    }

    private fun ensureLocationReady(): Boolean {
        if (!NetworkInspector.hasFineLocationPermission(this)) {
            requestWifiPermissions()
            return false
        }
        if (!NetworkInspector.isLocationServiceEnabled(this)) {
            toast("请先开启手机的定位服务(GPS)开关")
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return false
        }
        return true
    }

    private fun updateAutoEngineBadge() {
        if (WifiAutoSwitchService.isEnabled()) {
            tvAutoEngineBadge.text = "🤖 自动切网已开"
            tvAutoEngineBadge.setBackgroundResource(R.drawable.bg_badge_green)
            tvAutoEngineBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
        } else {
            tvAutoEngineBadge.text = "开启自动切网"
            tvAutoEngineBadge.setBackgroundResource(R.drawable.bg_badge_amber)
            tvAutoEngineBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
        }
    }

    /** iQOO / OriginOS 开启无障碍两步指引弹窗 */
    private fun showAccessibilitySetupDialog() {
        val msg = "开启「无障碍服务」一次后，即可实现：\n" +
            "• 点下方列表里的任意 Wi-Fi 直接自动切过去并测速\n" +
            "• 一键自动轮流测试附近所有已存密码的 Wi-Fi 并自动切回最快那一个\n\n" +
            "【iQOO OriginOS 开启步骤】\n" +
            "第1步（若无障碍开关灰掉）：点「去应用信息」→ 右上角三个点 ⋮ → 点击「允许受限设置」。\n" +
            "第2步：点「去开无障碍」→ 找到「已下载的应用 / WiFi侦察兵」→ 打开开关即可。"

        AlertDialog.Builder(this)
            .setTitle("开启全自动切网测速引擎")
            .setMessage(msg)
            .setPositiveButton("第2步：去开无障碍") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (_: Exception) {
                    toast("请在系统设置 → 快捷与辅助 → 无障碍中开启 WiFi侦察兵")
                }
            }
            .setNeutralButton("第1步：应用信息(解锁受限)") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                }
            }
            .setNegativeButton("暂不开启", null)
            .show()
    }

    private fun handleAutoStatus(status: AutoTaskStatus) {
        if (status.livePhase != null) {
            tvPhase.text = status.livePhase
        }
        if (status.liveMbps != null && status.liveMbps > 0.0) {
            tvLiveSpeed.text = String.format(
                Locale.US,
                if (status.liveMbps >= 100) "%.0f" else "%.1f",
                status.liveMbps
            )
        }
        if (status.latestCompleted != null) {
            syncMainResultUI(status.latestCompleted)
            renderHistory()
            renderScannedWifis(fromLiveScan = false)
        }

        if (status.running) {
            autoProgressBanner.visibility = View.VISIBLE
            tvAutoProgress.text = status.message
            btnAutoBatchTest.text = "■ 正在自动轮测中（点击停止）"
        } else {
            btnAutoBatchTest.text = "🤖 一键轮测全部 Wi-Fi（闪切瞬回找最快）"
            if (!status.finishedSummary.isNullOrEmpty()) {
                autoProgressBanner.visibility = View.VISIBLE
                tvAutoProgress.text = status.finishedSummary
                renderHistory()
                renderScannedWifis(fromLiveScan = false)
                HistoryStore.latestResult(this)?.let { syncMainResultUI(it) }
            } else if (status.message.isNotEmpty() && status.message != "自动切网引擎已就绪") {
                autoProgressBanner.visibility = View.VISIBLE
                tvAutoProgress.text = status.message
            } else {
                autoProgressBanner.visibility = View.GONE
            }
        }
    }

    private fun requestWifiPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun refreshNetworkStatus() {
        val info = NetworkInspector.inspect(this)
        currentNetInfo = info
        if (info.kind == NetKind.WIFI && !info.rawSsid.isNullOrBlank()) {
            HistoryStore.markStarredSsid(this, info.rawSsid)
        }

        val icon = when (info.kind) {
            NetKind.WIFI -> "📶 "
            NetKind.CELLULAR -> "📡 "
            NetKind.ETHERNET -> "🔌 "
            NetKind.OFFLINE -> "⚠️ "
        }
        tvNetworkTitle.text = icon + info.displayName
        tvNetworkDetail.text = info.detailLine

        when {
            info.needsLocationPermission -> {
                permissionBanner.visibility = View.VISIBLE
                tvPermissionReason.text = "授予「位置信息」权限以显示真实 Wi-Fi 名称和附近 Wi-Fi 列表"
                btnFixPermission.text = "一键授权显示 Wi-Fi 名称"
            }
            info.needsLocationToggle -> {
                permissionBanner.visibility = View.VISIBLE
                tvPermissionReason.text = "请打开下拉栏的「定位服务(GPS)」开关，系统才会返回 Wi-Fi 名称"
                btnFixPermission.text = "去开启定位开关"
            }
            else -> {
                permissionBanner.visibility = View.GONE
            }
        }

        WifiScoutWidgetProvider.refreshAllWidgets(this)
    }

    private fun updateSettingsChips() {
        val mode = HistoryStore.getMode(this)
        btnModeToggle.text = if (mode == TestMode.LITE) "省流" else "精准"
        btnNodeToggle.text = when (HistoryStore.getNodePref(this)) {
            NodePref.AUTO -> "自动节点"
            NodePref.CLOUDFLARE -> "全球节点"
            NodePref.CN -> "国内节点"
        }
    }

    private fun setRunningState(running: Boolean) {
        btnStartTest.text = if (running) "■ 停止测速" else "⚡ 仅测当前网络"
    }

    private fun startSpeedTest() {
        refreshNetworkStatus()
        val netInfo = currentNetInfo ?: NetworkInspector.inspect(this)
        if (netInfo.kind == NetKind.OFFLINE) {
            toast("当前没有网络连接")
            return
        }

        setRunningState(true)
        tvDown.text = "--"
        tvDownSub.text = "MB/s"
        tvUp.text = "--"
        tvUpSub.text = "MB/s"
        tvPing.text = "--"
        tvJitter.text = "--"
        tvLiveSpeed.text = "0.0"
        tvPhase.text = "正在测速…"

        val mode = HistoryStore.getMode(this)
        val nodePref = HistoryStore.getNodePref(this)

        testJob = uiScope.launch {
            try {
                val result = SpeedTestEngine.runTest(netInfo, mode, nodePref) { ev ->
                    runOnUiThread {
                        tvPhase.text = ev.phase
                        if (ev.currentMbps > 0) {
                            tvLiveSpeed.text = String.format(
                                Locale.US,
                                if (ev.currentMbps >= 100) "%.0f" else "%.1f",
                                ev.currentMbps
                            )
                        }
                        if (ev.pingMs != null) tvPing.text = ev.pingMs.toInt().toString()
                        if (ev.jitterMs != null) tvJitter.text = ev.jitterMs.toInt().toString()
                        val mb = ev.bytesUsed / (1024.0 * 1024.0)
                        tvMetaInfo.text = String.format(Locale.US, "节点：%s · 耗流：%.2f MB", ev.nodeName ?: "优选", mb)
                    }
                }

                lastResult = result
                HistoryStore.saveResult(this@MainActivity, result)
                syncMainResultUI(result)
                renderHistory()
                renderScannedWifis(fromLiveScan = false)
                WifiScoutWidgetProvider.refreshAllWidgets(this@MainActivity)
            } catch (e: Exception) {
                tvPhase.text = "测速受阻"
                toast(e.message ?: "测速失败")
            } finally {
                setRunningState(false)
            }
        }
    }

    private fun syncMainResultUI(result: SpeedTestResult) {
        lastResult = result
        tvLiveSpeed.text = String.format(
            Locale.US,
            if (result.downloadMbps >= 100) "%.0f" else "%.1f",
            result.downloadMbps
        )
        tvPhase.text = "${result.networkName} · ${result.verdict.tierLabel}"
        tvDown.text = String.format(Locale.US, "%.1f", result.downloadMbps)
        tvDownSub.text = String.format(Locale.US, "%.1f MB/s", result.downloadMbps / 8.0)
        if (result.uploadMbps != null) {
            tvUp.text = String.format(Locale.US, "%.1f", result.uploadMbps)
            tvUpSub.text = String.format(Locale.US, "%.1f MB/s", result.uploadMbps / 8.0)
        } else {
            tvUp.text = "N/A"
            tvUpSub.text = "仅测下载"
        }
        tvPing.text = result.pingMs.toInt().toString()
        tvJitter.text = result.jitterMs.toInt().toString()
        tvMetaInfo.text = String.format(
            Locale.US,
            "节点：%s · 耗流：%.2f MB",
            result.nodeName,
            result.bytesUsed / (1024.0 * 1024.0)
        )
        renderVerdict(result)
    }

    private fun renderVerdict(result: SpeedTestResult) {
        val v = result.verdict
        verdictCard.visibility = View.VISIBLE
        tvVerdictTier.text = v.tierLabel
        tvVerdictScore.text = "${v.score}分"
        tvVerdictTitle.text = v.title
        tvVerdictDesc.text = v.desc
        tvCapSummary.text = v.capSummary

        val (bgRes, colorRes) = when (v.tier) {
            "S" -> Pair(R.drawable.bg_badge_green, R.color.accent_green)
            "A" -> Pair(R.drawable.bg_badge_blue, R.color.accent_sky)
            "B" -> Pair(R.drawable.bg_badge_amber, R.color.accent_amber)
            else -> Pair(R.drawable.bg_badge_red, R.color.accent_red)
        }
        tvVerdictTier.setBackgroundResource(bgRes)
        tvVerdictTier.setTextColor(ContextCompat.getColor(this, colorRes))
        tvVerdictScore.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun renderScannedWifis(fromLiveScan: Boolean) {
        scanListContainer.removeAllViews()
        if (!NetworkInspector.hasFineLocationPermission(this) || !NetworkInspector.isLocationServiceEnabled(this)) {
            tvScanStatus.text = "开启定位权限与 GPS 后，自动显示附近 Wi-Fi 列表"
            return
        }

        val allScanned = NetworkInspector.getScannedWifis(this)
        val starred = HistoryStore.getStarredSsids(this)
        val currentSsid = currentNetInfo?.rawSsid
        val historyByName = HistoryStore.loadHistory(this).associateBy { it.networkName }

        if (allScanned.isEmpty()) {
            tvScanStatus.text = "暂未发现周边 Wi-Fi，请点右上角「刷新」"
            return
        }

        val filtered = if (onlyStarredFilter) {
            allScanned.filter { starred.contains(it.ssid) || it.ssid == currentSsid }
        } else {
            allScanned
        }

        val sorted = filtered.sortedWith(
            compareByDescending<ScannedWifi> { it.ssid == currentSsid }
                .thenByDescending { starred.contains(it.ssid) }
                .thenByDescending { it.bestRssiDbm }
        )

        val autoReady = WifiAutoSwitchService.isEnabled()
        val timeTag = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        tvScanStatus.text = if (autoReady) {
            "附近共 ${allScanned.size} 个 Wi-Fi ($timeTag) · 直接点击任意一个即可自动切过去并测速"
        } else {
            "附近共 ${allScanned.size} 个 Wi-Fi ($timeTag) · 开启右上角「自动切网」后点谁连谁"
        }

        val inflater = LayoutInflater.from(this)
        for (wifi in sorted.take(18)) {
            val row = inflater.inflate(R.layout.item_scanned_wifi, scanListContainer, false)
            val btnStar = row.findViewById<TextView>(R.id.btnScanStar)
            val tvSsid = row.findViewById<TextView>(R.id.tvScanSsid)
            val tvBand = row.findViewById<TextView>(R.id.tvScanBand)
            val tvSub = row.findViewById<TextView>(R.id.tvScanSub)
            val tvAction = row.findViewById<TextView>(R.id.tvScanAction)

            val isCurrent = wifi.ssid == currentSsid
            val isStarred = starred.contains(wifi.ssid) || isCurrent

            btnStar.text = if (isStarred) "★" else "☆"
            btnStar.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (isStarred) R.color.accent_amber else R.color.text_muted
                )
            )
            btnStar.setOnClickListener {
                val now = HistoryStore.toggleStarredSsid(this, wifi.ssid)
                toast(if (now) "已标记「${wifi.ssid}」加入一键轮测列表" else "已移出轮测标记")
                renderScannedWifis(fromLiveScan = false)
            }

            tvSsid.text = wifi.ssid
            tvBand.text = wifi.band

            val hist = historyByName[wifi.ssid]
            val subParts = mutableListOf("${wifi.bestRssiDbm}dBm ${wifi.signalLabel}")
            if (hist != null) {
                subParts.add(String.format(Locale.US, "实测↓%.1fM(%d分)", hist.downloadMbps, hist.verdict.score))
            } else if (isStarred) {
                subParts.add("已标常用")
            }
            tvSub.text = subParts.joinToString(" · ")

            if (isCurrent) {
                tvAction.text = "当前·测速"
                tvAction.setBackgroundResource(R.drawable.bg_badge_green)
                tvAction.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            } else {
                tvAction.text = "切换测速"
                tvAction.setBackgroundResource(R.drawable.bg_badge_blue)
                tvAction.setTextColor(ContextCompat.getColor(this, R.color.accent_sky))
            }

            row.setOnClickListener {
                if (isCurrent) {
                    startSpeedTest()
                    return@setOnClickListener
                }
                val svc = WifiAutoSwitchService.instance
                if (svc != null) {
                    svc.switchAndTestSingle(wifi.ssid)
                } else {
                    showAccessibilitySetupDialog()
                }
            }

            scanListContainer.addView(row)
        }
    }

    private fun renderHistory() {
        val list = HistoryStore.loadHistory(this)
        historyContainer.removeAllViews()

        if (list.isEmpty()) {
            tvEmptyHistory.visibility = View.VISIBLE
            tvBestCallout.visibility = View.GONE
            btnClearHistory.visibility = View.GONE
            return
        }

        tvEmptyHistory.visibility = View.GONE
        btnClearHistory.visibility = View.VISIBLE

        val sorted = list.sortedWith(
            compareByDescending<SpeedTestResult> { it.verdict.score }
                .thenByDescending { it.downloadMbps }
        )
        val best = sorted.first()
        val bestWifi = sorted.firstOrNull { it.kind == NetKind.WIFI }
        val bestCell = sorted.firstOrNull { it.kind == NetKind.CELLULAR }

        var compareNote = ""
        if (bestWifi != null && bestCell != null) {
            if (bestCell.verdict.score > bestWifi.verdict.score + 6) {
                compareNote = "\n💡 手机流量比当前所有 Wi-Fi 都快，建议直接开手机热点给电脑！"
            } else if (bestWifi.downloadMbps >= bestCell.downloadMbps * 1.2) {
                val ratio = bestWifi.downloadMbps / maxOf(0.1, bestCell.downloadMbps)
                compareNote = String.format(Locale.US, "\n💡 该 Wi-Fi 比手机流量快约 %.1fx，电脑直接连它！", ratio)
            }
        }

        tvBestCallout.visibility = View.VISIBLE
        tvBestCallout.text = String.format(
            Locale.US,
            "🏆 附近最佳：%s（↓%.1fM · 延迟%dms · %d分）%s",
            best.networkName,
            best.downloadMbps,
            best.pingMs.toInt(),
            best.verdict.score,
            compareNote
        )

        val inflater = LayoutInflater.from(this)
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())

        sorted.forEachIndexed { idx, item ->
            val row = inflater.inflate(R.layout.item_history, historyContainer, false)
            val tvRank = row.findViewById<TextView>(R.id.tvRank)
            val tvName = row.findViewById<TextView>(R.id.tvItemName)
            val tvType = row.findViewById<TextView>(R.id.tvItemType)
            val tvMetrics = row.findViewById<TextView>(R.id.tvItemMetrics)
            val tvScore = row.findViewById<TextView>(R.id.tvItemScore)
            val tvTime = row.findViewById<TextView>(R.id.tvItemTime)

            tvRank.text = if (idx == 0) "最佳" else "#${idx + 1}"
            if (idx == 0) {
                tvRank.setBackgroundResource(R.drawable.bg_badge_green)
                tvRank.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            }

            tvName.text = item.networkName
            tvType.text = if (item.kind == NetKind.CELLULAR) "流量" else "Wi-Fi"
            val upStr = item.uploadMbps?.let { String.format(Locale.US, "↑%.1fM", it) } ?: "↑--"
            tvMetrics.text = String.format(
                Locale.US,
                "↓%.1fM  %s  ⚡%dms",
                item.downloadMbps,
                upStr,
                item.pingMs.toInt()
            )
            tvScore.text = "${item.verdict.score}分"
            val scoreColor = when (item.verdict.tier) {
                "S" -> R.color.accent_green
                "A" -> R.color.accent_sky
                "B" -> R.color.accent_amber
                else -> R.color.accent_red
            }
            tvScore.setTextColor(ContextCompat.getColor(this, scoreColor))
            tvTime.text = fmt.format(Date(item.timestamp))

            // 点击排行榜里的任意 Wi-Fi，也可以直接切过去！
            row.setOnClickListener {
                if (item.kind == NetKind.WIFI && item.networkName != currentNetInfo?.rawSsid) {
                    val svc = WifiAutoSwitchService.instance
                    if (svc != null) svc.switchAndTestSingle(item.networkName)
                    else showAccessibilitySetupDialog()
                }
            }

            historyContainer.addView(row)
        }
    }

    private fun copyVerdictReport() {
        val r = lastResult ?: HistoryStore.latestResult(this) ?: return
        val upTxt = r.uploadMbps?.let { String.format(Locale.US, " ↑%.1fMbps", it) } ?: ""
        val text = String.format(
            Locale.US,
            "【WiFi侦察兵】%s：↓%.1fMbps (%.1fMB/s)%s，延迟 %dms，评分 %d/100 → %s",
            r.networkName,
            r.downloadMbps,
            r.downloadMbps / 8.0,
            upTxt,
            r.pingMs.toInt(),
            r.verdict.score,
            r.verdict.title
        )
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("wifi_scout_report", text))
        toast("已复制测速结论，可直接发给电脑")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
