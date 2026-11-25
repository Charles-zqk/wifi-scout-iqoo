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
    val finishedSummary: String? = null,
    val liveMbps: Double? = null,
    val livePhase: String? = null,
    val latestCompleted: SpeedTestResult? = null
)

private enum class ClickOutcome {
    CLICKED,
    NEEDS_PASSWORD,
    NOT_FOUND
}

/**
 * v1.4 升级版无障碍自动切网引擎（方案 B：闪切 1 秒瞬回 App + 全量轮测所有附近有密码 Wi-Fi）：
 * 1. 点完系统 Wi-Fi 项后 1 秒内立刻弹回 App，等待连接与实时测速全程停留在 App 页面展示；
 * 2. 一键轮测自动覆盖「当前连着 + 已标星 + 系统已保存 + 附近信号可用」的全部 Wi-Fi，
 *    遇到没存密码的自动识别密码框并 0.7 秒秒跳过、记住不再重复试；
 * 3. 全部测完后自动闪切回评分最高的那一个 Wi-Fi。
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
        // 按需主动查询窗口节点，平时零耗电
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
        notifyStatus(AutoTaskStatus(false, "已停止自动轮测"))
    }

    /** 点击列表单个 Wi-Fi：闪切 1 秒瞬回 App → 等待连上 → 实时测速 */
    fun switchAndTestSingle(targetSsid: String) {
        activeJob?.cancel()
        activeJob = serviceScope.launch {
            try {
                val alreadyOn = NetworkInspector.inspect(this@WifiAutoSwitchService).rawSsid == targetSsid
                if (!alreadyOn) {
                    notifyStatus(AutoTaskStatus(true, "正在闪切到「$targetSsid」…", livePhase = "正在切换 Wi-Fi…"))
                    val outcome = flashClickSsidAndReturnToApp(targetSsid)
                    when (outcome) {
                        ClickOutcome.NEEDS_PASSWORD -> {
                            HistoryStore.markNoPasswordSsid(this@WifiAutoSwitchService, targetSsid)
                            notifyStatus(AutoTaskStatus(false, "「$targetSsid」未保存密码，无法自动连接"))
                            return@launch
                        }
                        ClickOutcome.NOT_FOUND -> {
                            notifyStatus(AutoTaskStatus(false, "未在系统列表找到「$targetSsid」，请稍后重试"))
                            return@launch
                        }
                        ClickOutcome.CLICKED -> Unit
                    }

                    notifyStatus(AutoTaskStatus(true, "已返回 App，等待连上「$targetSsid」…", livePhase = "等待连接「$targetSsid」…"))
                    val connected = waitForWifiConnected(targetSsid, timeoutMs = 10_000L)
                    if (!connected) {
                        notifyStatus(AutoTaskStatus(false, "连接「$targetSsid」超时（可能密码失效或信号太弱）"))
                        return@launch
                    }
                }

                HistoryStore.markStarredSsid(this@WifiAutoSwitchService, targetSsid)
                HistoryStore.clearNoPasswordSsid(this@WifiAutoSwitchService, targetSsid)

                val res = runSpeedTestWithLiveUpdates(targetSsid, prefix = "")
                if (res != null) {
                    val summary = String.format(
                        Locale.US,
                        "✅ 「%s」测速完成：↓%.1fM · 延迟%dms · %d分（%s）",
                        res.networkName,
                        res.downloadMbps,
                        res.pingMs.toInt(),
                        res.verdict.score,
                        res.verdict.title
                    )
                    notifyStatus(
                        AutoTaskStatus(
                            running = false,
                            message = summary,
                            finishedSummary = summary,
                            liveMbps = res.downloadMbps,
                            livePhase = "${res.networkName} · ${res.verdict.tierLabel}",
                            latestCompleted = res
                        )
                    )
                } else {
                    notifyStatus(AutoTaskStatus(false, "「$targetSsid」已连上但无法访问外网"))
                }
            } catch (e: Exception) {
                bringAppToFront()
                notifyStatus(AutoTaskStatus(false, "切网/测速中断：${e.message ?: "请重试"}"))
            }
        }
    }

    /**
     * 一键全自动轮测所有附近可连接的 Wi-Fi：
     * - onlyStarred=false（默认）：测当前 + 已标星 + 系统已保存 + 所有附近信号 >= -80dBm 的 Wi-Fi（没存密码的自动 0.7 秒秒跳过并记住）
     * - onlyStarred=true：只轮测已标 ★ 的 Wi-Fi
     */
    fun runBatchInspectSavedWifis(onlyStarred: Boolean = false) {
        activeJob?.cancel()
        activeJob = serviceScope.launch {
            try {
                notifyStatus(AutoTaskStatus(true, "正在扫描附近全部 Wi-Fi…", livePhase = "准备全量轮测…"))
                NetworkInspector.triggerWifiScan(this@WifiAutoSwitchService)
                delay(400)

                val scanned = NetworkInspector.getScannedWifis(this@WifiAutoSwitchService)
                val scannedMap = scanned.associateBy { it.ssid }
                val currentSsid = NetworkInspector.inspect(this@WifiAutoSwitchService).rawSsid
                val starred = HistoryStore.getStarredSsids(this@WifiAutoSwitchService)
                val noPassSet = HistoryStore.getNoPasswordSsids(this@WifiAutoSwitchService)

                // 先闪进系统 Wi-Fi 页快速读取屏幕上的「已保存/已连接」列表，再立刻弹回 App
                val discoveredSaved = discoverSavedOnSystemWifiAndReturn()
                for (s in discoveredSaved) {
                    HistoryStore.markStarredSsid(this@WifiAutoSwitchService, s)
                    HistoryStore.clearNoPasswordSsid(this@WifiAutoSwitchService, s)
                }

                // 组装完整待测队列（去重、不设 2 个上限！）
                val orderedQueue = LinkedHashSet<String>()
                // 1. 当前已连的 Wi-Fi 排第 1（不用切网，直接在 App 页面先测！）
                if (!currentSsid.isNullOrBlank()) orderedQueue.add(currentSsid)

                // 2. 附近可扫到的所有已标 ★ 或系统标记已保存的 Wi-Fi
                val knownInRange = (starred + discoveredSaved)
                    .filter { it.isNotBlank() && (scannedMap.isEmpty() || scannedMap.containsKey(it)) }
                    .sortedByDescending { scannedMap[it]?.bestRssiDbm ?: -65 }
                orderedQueue.addAll(knownInRange)

                // 3. 若未勾选「仅测已标」，把附近信号可用 (>= -80dBm) 且未被标记过「无密码」的 Wi-Fi 也全部纳入探测
                if (!onlyStarred) {
                    val otherStrong = scanned
                        .filter { it.bestRssiDbm >= -80 && !noPassSet.contains(it.ssid) }
                        .sortedByDescending { it.bestRssiDbm }
                        .map { it.ssid }
                    orderedQueue.addAll(otherStrong)
                }

                val queue = orderedQueue.toList()
                if (queue.isEmpty()) {
                    notifyStatus(
                        AutoTaskStatus(
                            false,
                            "附近未扫到可测的 Wi-Fi，请确认 GPS 定位已打开并稍后重试"
                        )
                    )
                    return@launch
                }

                val results = mutableListOf<SpeedTestResult>()
                var skippedNoPassword = 0

                for ((index, ssid) in queue.withIndex()) {
                    val step = "(${index + 1}/${queue.size})"
                    val alreadyOn = NetworkInspector.inspect(this@WifiAutoSwitchService).rawSsid == ssid

                    if (!alreadyOn) {
                        notifyStatus(
                            AutoTaskStatus(
                                running = true,
                                message = "$step 闪切至「$ssid」…（已测通 ${results.size} 个）",
                                livePhase = "$step 切换中：$ssid"
                            )
                        )
                        val outcome = flashClickSsidAndReturnToApp(ssid)
                        if (outcome == ClickOutcome.NEEDS_PASSWORD) {
                            skippedNoPassword++
                            HistoryStore.markNoPasswordSsid(this@WifiAutoSwitchService, ssid)
                            notifyStatus(
                                AutoTaskStatus(
                                    running = true,
                                    message = "$step 「$ssid」未保存密码，已自动跳过 → 继续测下一个",
                                    livePhase = "跳过无密码：$ssid"
                                )
                            )
                            delay(350)
                            continue
                        }
                        if (outcome == ClickOutcome.NOT_FOUND) {
                            notifyStatus(
                                AutoTaskStatus(
                                    running = true,
                                    message = "$step 未找到「$ssid」，自动跳过 → 继续测下一个",
                                    livePhase = "跳过：$ssid"
                                )
                            )
                            delay(300)
                            continue
                        }

                        // 已经回到 App 页面！在 App 里展示等待连接进度
                        notifyStatus(
                            AutoTaskStatus(
                                running = true,
                                message = "$step 正在连接「$ssid」…（已测通 ${results.size} 个）",
                                livePhase = "$step 等待连上 $ssid…"
                            )
                        )
                        val connected = waitForWifiConnected(ssid, timeoutMs = 9500L)
                        if (!connected) {
                            notifyStatus(
                                AutoTaskStatus(
                                    running = true,
                                    message = "$step 「$ssid」连接超时，自动跳过 → 继续测下一个",
                                    livePhase = "连接超时：$ssid"
                                )
                            )
                            delay(350)
                            continue
                        }
                    }

                    // 连上成功！自动标星为「已存密码 Wi-Fi」，以后可一键只测这些
                    HistoryStore.markStarredSsid(this@WifiAutoSwitchService, ssid)
                    HistoryStore.clearNoPasswordSsid(this@WifiAutoSwitchService, ssid)

                    // 在 App 界面实时测速
                    val res = runSpeedTestWithLiveUpdates(ssid, prefix = "$step ")
                    if (res != null) {
                        results.add(res)
                    } else {
                        notifyStatus(
                            AutoTaskStatus(
                                running = true,
                                message = "$step 「$ssid」无法访问外网，自动继续下一个…",
                                livePhase = "无外网：$ssid"
                            )
                        )
                        delay(350)
                    }
                }

                if (results.isEmpty()) {
                    bringAppToFront()
                    notifyStatus(
                        AutoTaskStatus(
                            false,
                            "已检查 ${queue.size} 个 Wi-Fi（跳过无密码 $skippedNoPassword 个），但没有可连外网的 Wi-Fi"
                        )
                    )
                    return@launch
                }

                // 选出评分最高的 Wi-Fi，自动闪切回去并停留在 App 页面
                val best = results.maxWith(
                    compareBy<SpeedTestResult> { it.verdict.score }
                        .thenBy { it.downloadMbps }
                )
                val nowSsid = NetworkInspector.inspect(this@WifiAutoSwitchService).rawSsid
                if (nowSsid != best.networkName) {
                    notifyStatus(
                        AutoTaskStatus(
                            running = true,
                            message = "全部测完！正在自动切回最快 Wi-Fi「${best.networkName}」…",
                            livePhase = "切回最快：${best.networkName}"
                        )
                    )
                    flashClickSsidAndReturnToApp(best.networkName)
                    waitForWifiConnected(best.networkName, timeoutMs = 8000L)
                }

                bringAppToFront()
                WifiScoutWidgetProvider.refreshAllWidgets(this@WifiAutoSwitchService)

                val skipHint = if (skippedNoPassword > 0) "（自动跳过 $skippedNoPassword 个未存密码）" else ""
                val summary = String.format(
                    Locale.US,
                    "🏆 共实测 %d 个有密码 Wi-Fi%s！最快是「%s」（↓%.1fM · 延迟%dms · %d分），已切好，电脑直接连！",
                    results.size,
                    skipHint,
                    best.networkName,
                    best.downloadMbps,
                    best.pingMs.toInt(),
                    best.verdict.score
                )
                notifyStatus(
                    AutoTaskStatus(
                        running = false,
                        message = summary,
                        finishedSummary = summary,
                        liveMbps = best.downloadMbps,
                        livePhase = "最佳：${best.networkName} · ${best.verdict.tierLabel}",
                        latestCompleted = best
                    )
                )
            } catch (e: Exception) {
                bringAppToFront()
                notifyStatus(AutoTaskStatus(false, "自动轮测已停止：${e.message ?: ""}"))
            }
        }
    }

    private suspend fun runSpeedTestWithLiveUpdates(ssid: String, prefix: String): SpeedTestResult? {
        notifyStatus(
            AutoTaskStatus(
                running = true,
                message = "${prefix}已连上「$ssid」，正在快速测速…",
                liveMbps = 0.0,
                livePhase = "${prefix}测速中：$ssid"
            )
        )
        delay(650)
        return try {
            val netInfo = NetworkInspector.inspect(this@WifiAutoSwitchService)
            val res = SpeedTestEngine.runTest(
                networkInfo = netInfo,
                mode = TestMode.LITE,
                nodePref = HistoryStore.getNodePref(this@WifiAutoSwitchService),
                onProgress = { ev ->
                    val sp = if (ev.currentMbps > 0.1) {
                        String.format(Locale.US, " %.1f Mbps", ev.currentMbps)
                    } else ""
                    notifyStatus(
                        AutoTaskStatus(
                            running = true,
                            message = "${prefix}正在测「$ssid」${ev.phase}$sp",
                            liveMbps = ev.currentMbps,
                            livePhase = "${prefix}$ssid · ${ev.phase}"
                        )
                    )
                }
            )
            HistoryStore.saveResult(this@WifiAutoSwitchService, res)
            WifiScoutWidgetProvider.refreshAllWidgets(this@WifiAutoSwitchService)
            notifyStatus(
                AutoTaskStatus(
                    running = true,
                    message = String.format(
                        Locale.US,
                        "${prefix}「%s」完成：↓%.1fM (%d分)",
                        ssid,
                        res.downloadMbps,
                        res.verdict.score
                    ),
                    liveMbps = res.downloadMbps,
                    livePhase = "${prefix}$ssid 完成",
                    latestCompleted = res
                )
            )
            res
        } catch (_: Exception) {
            null
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

    /**
     * 闪切核心：打开系统 Wi-Fi 页 → 自动查找并点击目标 SSID → 检测密码框 → 1 秒内立刻弹回 App！
     */
    private suspend fun flashClickSsidAndReturnToApp(targetSsid: String): ClickOutcome {
        openSystemWifiSettings()
        delay(850)

        var clicked = false
        for (scrollAttempt in 0..5) {
            val root = rootInActiveWindow
            if (root != null) {
                // 如果不小心不在设置页，重新唤起系统 Wi-Fi 页
                val pkg = root.packageName?.toString().orEmpty()
                if (pkg == packageName) {
                    openSystemWifiSettings()
                    delay(750)
                    continue
                }
                val targetNode = findExactTextNode(root, targetSsid)
                if (targetNode != null) {
                    clicked = clickNodeOrParent(targetNode)
                    if (clicked) break
                }
                if (scrollAttempt < 5) {
                    scrollListForward(root)
                }
            }
            delay(450)
        }

        if (!clicked) {
            bringAppToFront()
            return ClickOutcome.NOT_FOUND
        }

        // 短暂等待 650ms 判断是否弹出密码输入框或「连接」确认按钮
        delay(650)
        val afterRoot = rootInActiveWindow
        if (afterRoot != null) {
            if (containsPasswordPrompt(afterRoot)) {
                // 关闭密码框并立刻返回 App
                performGlobalAction(GLOBAL_ACTION_BACK)
                delay(200)
                bringAppToFront()
                return ClickOutcome.NEEDS_PASSWORD
            }
            val connectBtn = findButtonByTexts(afterRoot, listOf("连接", "Connect", "确定"))
            if (connectBtn != null) {
                clickNodeOrParent(connectBtn)
                delay(250)
            }
        }

        // 点完立刻弹回 App 页面！后续等待连上和测速全部在 App 界面内完成
        bringAppToFront()
        return ClickOutcome.CLICKED
    }

    /** 闪扫系统 Wi-Fi 页上带「已保存/已连接」字样的网络，扫完立即返回 App */
    private suspend fun discoverSavedOnSystemWifiAndReturn(): List<String> {
        openSystemWifiSettings()
        delay(850)
        val found = LinkedHashSet<String>()
        for (scroll in 0..1) {
            val root = rootInActiveWindow
            if (root != null) {
                found.addAll(extractSavedSsidsFromNode(root))
                if (scroll == 0) scrollListForward(root)
            }
            delay(350)
        }
        bringAppToFront()
        return found.toList()
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
                    if (validated || (hasNet && System.currentTimeMillis() - ssidMatchedAt >= 1500L)) {
                        return@withContext true
                    }
                }
                delay(400)
            }
            NetworkInspector.inspect(this@WifiAutoSwitchService).rawSsid == targetSsid
        }

    private fun extractSavedSsidsFromNode(root: AccessibilityNodeInfo): List<String> {
        val found = LinkedHashSet<String>()
        val keywords = listOf("已保存", "已连接", "Saved", "Connected", "自动连接")
        val ignoreLabels = setOf(
            "WLAN", "Wi-Fi", "网络和互联网", "已保存的网络", "可用网络", "我的网络",
            "添加网络", "高级设置", "WLAN助理", "更多设置", "刷新", "已开启"
        )

        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val childCount = node.childCount
            if (childCount in 2..6) {
                val texts = mutableListOf<String>()
                for (i in 0 until childCount) gatherTexts(node.getChild(i), texts)
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
            for (i in 0 until childCount) walk(node.getChild(i))
        }

        walk(root)
        return found.toList()
    }

    private fun gatherTexts(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (node == null) return
        val txt = node.text?.toString()?.trim()
        if (!txt.isNullOrEmpty()) out.add(txt)
        for (i in 0 until node.childCount) gatherTexts(node.getChild(i), out)
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

    private fun containsPasswordPrompt(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isPassword || node.className?.toString()?.contains("EditText") == true) {
            return true
        }
        val txt = node.text?.toString().orEmpty()
        if (txt.contains("输入密码") || txt.contains("请输入密码") || txt.equals("密码", ignoreCase = true)) {
            return true
        }
        for (i in 0 until node.childCount) {
            if (containsPasswordPrompt(node.getChild(i))) return true
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
