package com.wifiscout.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
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

    private lateinit var tvNetworkTitle: TextView
    private lateinit var tvNetworkDetail: TextView
    private lateinit var btnRefreshNet: TextView
    private lateinit var permissionBanner: LinearLayout
    private lateinit var tvPermissionReason: TextView
    private lateinit var btnFixPermission: Button

    private lateinit var tvPhase: TextView
    private lateinit var tvLiveSpeed: TextView
    private lateinit var tvDown: TextView
    private lateinit var tvDownSub: TextView
    private lateinit var tvUp: TextView
    private lateinit var tvUpSub: TextView
    private lateinit var tvPing: TextView
    private lateinit var tvJitter: TextView
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

    private lateinit var tvBestCallout: TextView
    private lateinit var btnClearHistory: TextView
    private lateinit var historyContainer: LinearLayout
    private lateinit var tvEmptyHistory: TextView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshNetworkStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupListeners()
        updateSettingsChips()
        renderHistory()

        // 首次启动时若缺少位置权限，主动请求以便直接读取 Wi-Fi SSID 名称
        if (!NetworkInspector.hasFineLocationPermission(this)) {
            requestWifiPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshNetworkStatus()
        val cb = NetworkInspector.createLiveWifiCallback {
            runOnUiThread { refreshNetworkStatus() }
        }
        netCallback = cb
        NetworkInspector.registerCallback(this, cb)
    }

    override fun onPause() {
        super.onPause()
        netCallback?.let { NetworkInspector.unregisterCallback(this, it) }
        netCallback = null
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }

    private fun bindViews() {
        tvNetworkTitle = findViewById(R.id.tvNetworkTitle)
        tvNetworkDetail = findViewById(R.id.tvNetworkDetail)
        btnRefreshNet = findViewById(R.id.btnRefreshNet)
        permissionBanner = findViewById(R.id.permissionBanner)
        tvPermissionReason = findViewById(R.id.tvPermissionReason)
        btnFixPermission = findViewById(R.id.btnFixPermission)

        tvPhase = findViewById(R.id.tvPhase)
        tvLiveSpeed = findViewById(R.id.tvLiveSpeed)
        tvDown = findViewById(R.id.tvDown)
        tvDownSub = findViewById(R.id.tvDownSub)
        tvUp = findViewById(R.id.tvUp)
        tvUpSub = findViewById(R.id.tvUpSub)
        tvPing = findViewById(R.id.tvPing)
        tvJitter = findViewById(R.id.tvJitter)
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

        tvBestCallout = findViewById(R.id.tvBestCallout)
        btnClearHistory = findViewById(R.id.btnClearHistory)
        historyContainer = findViewById(R.id.historyContainer)
        tvEmptyHistory = findViewById(R.id.tvEmptyHistory)
    }

    private fun setupListeners() {
        btnRefreshNet.setOnClickListener {
            refreshNetworkStatus()
            toast("已刷新当前网络信息")
        }

        btnFixPermission.setOnClickListener {
            val info = currentNetInfo
            if (info != null && info.needsLocationToggle) {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } else {
                requestWifiPermissions()
            }
        }

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
            toast(if (next == TestMode.LITE) "已切换：省流模式 (~2-5MB)" else "已切换：精准满载模式 (~10-22MB)")
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

        btnClearHistory.setOnClickListener {
            HistoryStore.clearHistory(this)
            renderHistory()
            WifiScoutWidgetProvider.refreshAllWidgets(this)
            toast("已清空附近网络对比记录")
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
                tvPermissionReason.text = "安卓系统要求授予「位置信息」权限才能读取当前 Wi-Fi 名称（不会上传定位）"
                btnFixPermission.text = "一键授权显示 Wi-Fi 名称"
            }
            info.needsLocationToggle -> {
                permissionBanner.visibility = View.VISIBLE
                tvPermissionReason.text = "请打开手机下拉栏的「定位服务(GPS)」开关，系统才会返回真实 Wi-Fi 名称"
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
        btnModeToggle.text = if (mode == TestMode.LITE) "⚡ 省流模式" else "🎯 精准模式"

        val nodeLabel = when (HistoryStore.getNodePref(this)) {
            NodePref.AUTO -> "🌐 节点:自动优选"
            NodePref.CLOUDFLARE -> "🌐 节点:Cloudflare"
            NodePref.CN -> "🌐 节点:国内兼容"
        }
        btnNodeToggle.text = nodeLabel
    }

    private fun setRunningState(running: Boolean) {
        btnStartTest.text = if (running) "■ 停止测速" else "▶ 一键测速 · 检查是否适合电脑连"
    }

    private fun startSpeedTest() {
        refreshNetworkStatus()
        val netInfo = currentNetInfo ?: NetworkInspector.inspect(this)
        if (netInfo.kind == NetKind.OFFLINE) {
            toast("当前没有网络连接，请先连接 Wi-Fi 或开启流量")
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
        tvPhase.text = "准备测速…"

        val mode = HistoryStore.getMode(this)
        val nodePref = HistoryStore.getNodePref(this)

        testJob = uiScope.launch {
            try {
                val result = SpeedTestEngine.runTest(netInfo, mode, nodePref) { ev ->
                    runOnUiThread {
                        tvPhase.text = ev.phase
                        if (ev.currentMbps > 0) {
                            tvLiveSpeed.text = String.format(Locale.US, if (ev.currentMbps >= 100) "%.0f" else "%.1f", ev.currentMbps)
                        }
                        if (ev.pingMs != null) tvPing.text = ev.pingMs.toInt().toString()
                        if (ev.jitterMs != null) tvJitter.text = ev.jitterMs.toInt().toString()
                        val mb = ev.bytesUsed / (1024.0 * 1024.0)
                        val nodeTxt = ev.nodeName ?: "探测中"
                        tvMetaInfo.text = String.format(Locale.US, "节点：%s · 本次耗流：%.2f MB", nodeTxt, mb)
                    }
                }

                lastResult = result
                HistoryStore.saveResult(this@MainActivity, result)

                tvLiveSpeed.text = String.format(Locale.US, if (result.downloadMbps >= 100) "%.0f" else "%.1f", result.downloadMbps)
                tvPhase.text = "测速完成 · ${result.verdict.tierLabel}"
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
                    "节点：%s · 本次耗流：%.2f MB",
                    result.nodeName,
                    result.bytesUsed / (1024.0 * 1024.0)
                )

                renderVerdict(result)
                renderHistory()
                WifiScoutWidgetProvider.refreshAllWidgets(this@MainActivity)
            } catch (e: Exception) {
                tvPhase.text = "测速受阻"
                toast(e.message ?: "测速失败，请检查网络连接")
            } finally {
                setRunningState(false)
            }
        }
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
