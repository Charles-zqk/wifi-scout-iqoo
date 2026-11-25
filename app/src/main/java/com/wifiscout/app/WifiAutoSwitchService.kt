package com.wifiscout.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class AutoTaskStatus(
    val running: Boolean,
    val message: String,
    val finishedSummary: String? = null
)

/**
 * 基于 Android 官方无障碍服务（免 Root）实现：
 * 1. 点列表任意 Wi-Fi → 自动切过去并立即测速
 * 2. 一键自动巡检附近所有已保存密码的 Wi-Fi → 挨个切网测速 → 自动切回最快的那一个
 */
class WifiAutoSwitchService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var activeJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        notifyStatus(AutoTaskStatus(false, "自动切网引擎已就绪"))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 由协程按需主动读取当前窗口树，不在日常浏览时做任何消耗
    }

    override fun onInterrupt() {
        cancelRunningTask()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        serviceScope.cancel()
    }

    fun cancelRunningTask() {
        activeJob?.cancel()
        activeJob = null
        notifyStatus(AutoTaskStatus(false, "已停止自动切网任务"))
    }

    /** 直接切换到单个指定 Wi-Fi，连上后自动跳回 App 并测速 */
    fun switchAndTestSingle(targetSsid: String) {
        activeJob?.cancel()
        activeJob = serviceScope.launch {
            try {
                notifyStatus(AutoTaskStatus(true, "正在自动切换到「$targetSsid」…"))
                val connected = switchToSsidInternal(targetSsid)
                bringAppToFront()

                if (!connected) {
                    notifyStatus(
                        AutoTaskStatus(
                            false,
                            "未能自动连上「$targetSsid」（可能未保存密码或超出范围）"
                        )
                    )
                    return@launch
                }

                HistoryStore.markStarredSsid(this@WifiAutoSwitchService, targetSsid)
                notifyStatus(AutoTaskStatus(true, "已连上「$targetSsid」，正在极速测速…"))
                delay(700)

                val netInfo = NetworkInspector.inspect(this@WifiAutoSwitchService)
                val result = SpeedTestEngine.runTest(
                    networkInfo = netInfo,
                    mode = HistoryStore.getMode(this@WifiAutoSwitchService),
                    nodePref = HistoryStore.getNodePref(this@WifiAutoSwitchService),
                    onProgress = { ev ->
                        val speedTxt = if (ev.currentMbps > 0.1) {
                            String.format(Locale.US, " %.1f Mbps", ev.currentMbps)
                        } else ""
                        notifyStatus(AutoTaskStatus(true, "「$targetSsid」${ev.phase}$speedTxt"))
                    }
                )
                HistoryStore.saveResult(this@WifiAutoSwitchService, result)
                WifiScoutWidgetProvider.refreshAllWidgets(this@WifiAutoSwitchService)

                val summary = String.format(
                    Locale.US,
                    "✅ 「%s」测速完成：↓%.1fM · 延迟%dms · %d分（%s）",
                    result.networkName,
                    result.downloadMbps,
                    result.pingMs.toInt(),
                    result.verdict.score,
                    result.verdict.title
                )
                notifyStatus(AutoTaskStatus(false, summary, finishedSummary = summary))
            } catch (e: Exception) {
                bringAppToFront()
                notifyStatus(AutoTaskStatus(false, "切网/测速中断：${e.message ?: "请重试"}"))
            }
        }
    }

    /** 一键自动轮测附近所有已保存密码（或已标星）的 Wi-Fi，并自动切回最快的那一个 */
    fun runBatchInspectSavedWifis() {
        activeJob?.cancel()
        activeJob = serviceScope.launch {
            try {
                notifyStatus(AutoTaskStatus(true, "正在发现附近已保存密码的 Wi-Fi…"))

                // 1. 打开系统 Wi-Fi 页，自动识别屏幕上标注为「已保存 / 已连接」的 Wi-Fi
                openSystemWifiSettings()
                delay(1200)
                val discoveredFromSettings = discoverSavedSsidsOnScreen()
                for (s in discoveredFromSettings) {
                    HistoryStore.markStarredSsid(this@WifiAutoSwitchService, s)
                }

                // 2. 结合系统扫描结果 + 已标星/连过的列表，确定附近可测队列
                val scanned = NetworkInspector.getScannedWifis(this@WifiAutoSwitchService)
                val scannedMap = scanned.associateBy { it.ssid }
                val starred = HistoryStore.getStarredSsids(this@WifiAutoSwitchService)
                val currentSsid = NetworkInspector.inspect(this@WifiAutoSwitchService).rawSsid

                val targets = LinkedHashSet<String>()
                if (!currentSsid.isNullOrBlank()) targets.add(currentSsid)
                for (s in discoveredFromSettings) targets.add(s)
                for (s in starred) {
                    if (scannedMap.isEmpty() || scannedMap.containsKey(s)) {
                        targets.add(s)
                    }
                }

                // 按信号强度排序，最多连测前 6 个，避免耗时过长
                val queue = targets
                    .sortedByDescending { scannedMap[it]?.bestRssiDbm ?: -70 }
                    .take(6)

                if (queue.isEmpty()) {
                    bringAppToFront()
                    notifyStatus(
                        AutoTaskStatus(
                            false,
                            "未发现已保存的附近 Wi-Fi，请先在下方列表点 ☆ 标星或手动连过一次"
                        )
                    )
                    return@launch
                }

                val results = mutableListOf<SpeedTestResult>()

                for ((index, ssid) in queue.withIndex()) {
                    val stepTag = "(${index + 1}/${queue.size})"
                    notifyStatus(AutoTaskStatus(true, "$stepTag 正在切换并连接「$ssid」…"))

                    val alreadyOn = NetworkInspector.inspect(this@WifiAutoSwitchService).rawSsid == ssid
                    val connected = if (alreadyOn) true else switchToSsidInternal(ssid)
                    if (!connected) {
                        notifyStatus(AutoTaskStatus(true, "$stepTag 跳过「$ssid」（未保存密码或连接超时）"))
                        delay(500)
                        continue
                    }

                    HistoryStore.markStarredSsid(this@WifiAutoSwitchService, ssid)
                    notifyStatus(AutoTaskStatus(true, "$stepTag 已连「$ssid」，快速测速中…"))
                    delay(800)

                    try {
                        val netInfo = NetworkInspector.inspect(this@WifiAutoSwitchService)
                        val res = SpeedTestEngine.runTest(
                            networkInfo = netInfo,
                            mode = TestMode.LITE,
                            nodePref = HistoryStore.getNodePref(this@WifiAutoSwitchService),
                            onProgress = { ev ->
                                val sp = if (ev.currentMbps > 0.1) {
                                    String.format(Locale.US, " %.1fM", ev.currentMbps)
                                } else ""
                                notifyStatus(AutoTaskStatus(true, "$stepTag 测「$ssid」${ev.phase}$sp"))
                            }
                        )
                        HistoryStore.saveResult(this@WifiAutoSwitchService, res)
                        results.add(res)
                    } catch (_: Exception) {
                        // 某个 Wi-Fi 无外网权限时继续测下一个
                    }
                }

                if (results.isEmpty()) {
                    bringAppToFront()
                    notifyStatus(AutoTaskStatus(false, "巡检结束，但所有候选 Wi-Fi 均无法连外网"))
                    return@launch
                }

                // 3. 找出评分最高的 Wi-Fi，并自动切回它！
                val best = results.maxWith(
                    compareBy<SpeedTestResult> { it.verdict.score }
                        .thenBy { it.downloadMbps }
                )
                val nowSsid = NetworkInspector.inspect(this@WifiAutoSwitchService).rawSsid
                if (nowSsid != best.networkName) {
                    notifyStatus(AutoTaskStatus(true, "巡检完成！正在自动切回最快 Wi-Fi「${best.networkName}」…"))
                    switchToSsidInternal(best.networkName)
                }

                bringAppToFront()
                WifiScoutWidgetProvider.refreshAllWidgets(this@WifiAutoSwitchService)

                val summary = String.format(
                    Locale.US,
                    "🏆 已轮测 %d 个 Wi-Fi！最快是「%s」（↓%.1fM · 延迟%dms · %d分），已自动切好，电脑直接连它！",
                    results.size,
                    best.networkName,
                    best.downloadMbps,
                    best.pingMs.toInt(),
                    best.verdict.score
                )
                notifyStatus(AutoTaskStatus(false, summary, finishedSummary = summary))
            } catch (e: Exception) {
                bringAppToFront()
                notifyStatus(AutoTaskStatus(false, "自动巡检已停止：${e.message ?: ""}"))
            }
        }
    }

    private fun openSystemWifiSettings() {
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    private fun bringAppToFront() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }
        startActivity(intent)
    }

    /** 在 OriginOS / Android 系统 Wi-Fi 页面中自动查找目标 SSID 并点击连接 */
    private suspend fun switchToSsidInternal(targetSsid: String): Boolean {
        if (NetworkInspector.inspect(this).rawSsid == targetSsid) {
            return waitForWifiConnected(targetSsid, timeoutMs = 3000L)
        }

        openSystemWifiSettings()
        delay(1000)

        var clicked = false
        for (scrollAttempt in 0..4) {
            val root = rootInActiveWindow
            if (root != null) {
                val targetNode = findExactTextNode(root, targetSsid)
                if (targetNode != null) {
                    clicked = clickNodeOrParent(targetNode)
                    if (clicked) break
                }
                if (scrollAttempt < 4) {
                    scrollListForward(root)
                }
            }
            delay(550)
        }

        if (!clicked) return false

        // 等待 700ms：若弹出密码框说明没存密码，直接退出；若弹出「连接」确认按钮则自动点连接
        delay(750)
        val afterRoot = rootInActiveWindow
        if (afterRoot != null) {
            if (containsPasswordInput(afterRoot)) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                return false
            }
            val connectBtn = findButtonByTexts(afterRoot, listOf("连接", "Connect", "确定"))
            if (connectBtn != null) {
                clickNodeOrParent(connectBtn)
            }
        }

        return waitForWifiConnected(targetSsid, timeoutMs = 9500L)
    }

    private suspend fun waitForWifiConnected(targetSsid: String, timeoutMs: Long): Boolean =
        withContext(Dispatchers.IO) {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val deadline = System.currentTimeMillis() + timeoutMs
            var ssidMatchedAt = 0L
            while (System.currentTimeMillis() < deadline) {
                val info = NetworkInspector.inspect(this@WifiAutoSwitchService)
                if (info.kind == NetKind.WIFI && info.rawSsid == targetSsid) {
                    if (ssidMatchedAt == 0L) ssidMatchedAt = System.currentTimeMillis()
                    val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
                    val hasNet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                    val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                    // 一旦校验通过，或已连上目标 SSID 超过 1.6 秒且有网络能力，即可开始测速
                    if (validated || (hasNet && System.currentTimeMillis() - ssidMatchedAt >= 1600L)) {
                        return@withContext true
                    }
                }
                delay(450)
            }
            NetworkInspector.inspect(this@WifiAutoSwitchService).rawSsid == targetSsid
        }

    /** 扫描系统 Wi-Fi 设置界面里标有「已保存 / 已连接」的条目名称 */
    private fun discoverSavedSsidsOnScreen(): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val found = LinkedHashSet<String>()
        val keywords = listOf("已保存", "已连接", "Saved", "Connected", "自动连接")
        val ignoreLabels = setOf(
            "WLAN", "Wi-Fi", "网络和互联网", "已保存的网络", "可用网络",
            "添加网络", "高级设置", "WLAN助理", "更多设置", "刷新"
        )

        fun collectSavedRows(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val childCount = node.childCount
            if (childCount in 2..6) {
                val texts = mutableListOf<String>()
                for (i in 0 until childCount) {
                    gatherTexts(node.getChild(i), texts)
                }
                val hasSavedTag = texts.any { t -> keywords.any { kw -> t.contains(kw, ignoreCase = true) } }
                if (hasSavedTag) {
                    val candidate = texts.firstOrNull { t ->
                        t.length in 1..40 &&
                            !keywords.any { kw -> t.contains(kw, ignoreCase = true) } &&
                            !ignoreLabels.contains(t)
                    }
                    if (candidate != null) found.add(candidate)
                }
            }
            for (i in 0 until childCount) {
                collectSavedRows(node.getChild(i))
            }
        }

        collectSavedRows(root)
        return found.toList()
    }

    private fun gatherTexts(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (node == null) return
        val txt = node.text?.toString()?.trim()
        if (!txt.isNullOrEmpty()) out.add(txt)
        for (i in 0 until node.childCount) {
            gatherTexts(node.getChild(i), out)
        }
    }

    private fun findExactTextNode(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        val matches = root.findAccessibilityNodeInfosByText(target)
        for (n in matches) {
            if (n.text?.toString()?.trim() == target) return n
        }
        return matches.firstOrNull { n ->
            n.text?.toString()?.trim()?.startsWith(target) == true
        }
    }

    private fun findButtonByTexts(root: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        for (label in labels) {
            val matches = root.findAccessibilityNodeInfosByText(label)
            val exact = matches.firstOrNull { it.text?.toString()?.trim().equals(label, ignoreCase = true) }
            if (exact != null) return exact
        }
        return null
    }

    private fun containsPasswordInput(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isPassword || node.className?.toString()?.contains("EditText") == true) {
            return true
        }
        for (i in 0 until node.childCount) {
            if (containsPasswordInput(node.getChild(i))) return true
        }
        return false
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable) {
                return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            cur = cur.parent
            depth++
        }
        return false
    }

    private fun scrollListForward(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isScrollable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }
        for (i in 0 until node.childCount) {
            if (scrollListForward(node.getChild(i))) return true
        }
        return false
    }

    companion object {
        @Volatile
        var instance: WifiAutoSwitchService? = null
            private set

        @Volatile
        var statusListener: ((AutoTaskStatus) -> Unit)? = null

        @Volatile
        var lastStatus: AutoTaskStatus = AutoTaskStatus(false, "")
            private set

        fun isEnabled(): Boolean = instance != null

        private fun notifyStatus(status: AutoTaskStatus) {
            lastStatus = status
            statusListener?.invoke(status)
        }
    }
}
