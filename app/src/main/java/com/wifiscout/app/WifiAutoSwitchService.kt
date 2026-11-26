package com.wifiscout.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
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
 * v1.5 深度修复版（针对 iQOO OriginOS 实测调优）：
 * 1. 服务自身常驻 NetworkCallback，彻底解决切网时旧 SSID 缓存卡死导致后续全部超时跳过的问题；
 * 2. 返回 App 改用 GLOBAL_ACTION_BACK（无需 vivo「后台弹出界面」权限），1 秒瞬回 App；
 * 3. 多窗口遍历 + 真手指手势点击兜底（canPerformGestures），解决 OriginOS 部分自定义行忽略 ACTION_CLICK 的问题；
 * 4. 内置全程诊断日志，任何异常一眼定位。
 */
class WifiAutoSwitchService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var activeJob: Job? = null
    private var liveNetCallback: ConnectivityManager.NetworkCallback? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val cb = NetworkInspector.createLiveWifiCallback { }
        liveNetCallback = cb
        NetworkInspector.registerWifiOnlyCallback(this, cb)
        log("无障碍服务已连接并启用实时 Wi-Fi 监听")
        notifyStatus(AutoTaskStatus(false, "自动切网引擎已就绪"))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 按需查询，平时零耗电
    }

    override fun onInterrupt() {
        cancelRunningTask()
    }

    override fun onDestroy() {
        super.onDestroy()
        liveNetCallback?.let { NetworkInspector.unregisterCallback(this, it) }
        liveNetCallback = null
        if (instance === this) instance = null
        serviceScope.cancel()
    }

    fun cancelRunningTask() {
        activeJob?.cancel()
        activeJob = null
        log("用户手动停止任务")
        notifyStatus(AutoTaskStatus(false, "已停止自动轮测"))
    }

    /** 点击列表单个 Wi-Fi：闪切 1 秒瞬回 App → 等待连上 → 实时测速 */
    fun switchAndTestSingle(targetSsid: String) {
        activeJob?.cancel()
        clearLogs()
        activeJob = serviceScope.launch {
            try {
                log("单网切测目标: $targetSsid")
                val alreadyOn = NetworkInspector.inspect(this@WifiAutoSwitchService).rawSsid == targetSsid
                if (!alreadyOn) {
                    notifyStatus(AutoTaskStatus(true, "正在闪切到「$targetSsid」…", livePhase = "正在切换 Wi-Fi…"))
                    val outcome = flashClickSsidAndReturnToApp(targetSsid)
                    log("点击结果($targetSsid): $outcome")
                    when (outcome) {
                        ClickOutcome.NEEDS_PASSWORD -> {
                            HistoryStore.markNoPasswordSsid(this@WifiAutoSwitchService, targetSsid)
                            notifyStatus(AutoTaskStatus(false, "「$targetSsid」未保存密码，无法自动连接"))
                            return@launch
                        }
                        ClickOutcome.NOT_FOUND -> {
                            notifyStatus(AutoTaskStatus(false, "未在系统列表找到「$targetSsid」，点「日志」查看详情"))
                            return@launch
                        }
                        ClickOutcome.CLICKED -> Unit
                    }

                    notifyStatus(AutoTaskStatus(true, "已返回 App，等待连上「$targetSsid」…", livePhase = "等待连接「$targetSsid」…"))
                    val connected = waitForWifiConnected(targetSsid, timeoutMs = 13_000L)
                    log("连接状态($targetSsid): $connected (当前=${NetworkInspector.inspect(this@WifiAutoSwitchService).rawSsid})")
                    if (!connected) {
                        notifyStatus(AutoTaskStatus(false, "连接「$targetSsid」超时，点「日志」查看原因"))
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("异常: ${e.message}")
                returnToAppSafely()
                notifyStatus(AutoTaskStatus(false, "切网/测速中断：${e.message ?: "请重试"}"))
            }
        }
    }

    /** 一键全自动轮测附近全部可用 Wi-Fi（跳过无密码，自动切回最快） */
    fun runBatchInspectSavedWifis(onlyStarred: Boolean = false) {
        activeJob?.cancel()
        clearLogs()
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

                val orderedQueue = LinkedHashSet<String>()
                if (!currentSsid.isNullOrBlank()) orderedQueue.add(currentSsid)

                val knownInRange = starred
                    .filter { it.isNotBlank() && (scannedMap.isEmpty() || scannedMap.containsKey(it)) }
                    .sortedByDescending { scannedMap[it]?.bestRssiDbm ?: -65 }
                orderedQueue.addAll(knownInRange)

                if (!onlyStarred) {
                    val otherStrong = scanned
                        .filter { it.bestRssiDbm >= -82 && !noPassSet.contains(it.ssid) }
                        .sortedByDescending { it.bestRssiDbm }
                        .map { it.ssid }
                    orderedQueue.addAll(otherStrong)
                }

                val queue = orderedQueue.toList()
                log("扫描到${scanned.size}个, 待测队列(${queue.size}): ${queue.joinToString(",")}")

                if (queue.isEmpty()) {
                    notifyStatus(AutoTaskStatus(false, "附近未扫到可用 Wi-Fi，请确认 GPS 已开启"))
                    return@launch
                }

                val results = mutableListOf<SpeedTestResult>()
                var skippedNoPassword = 0

                for ((index, ssid) in queue.withIndex()) {
                    val step = "(${index + 1}/${queue.size})"
                    val nowBefore = NetworkInspector.inspect(this@WifiAutoSwitchService).rawSsid
                    val alreadyOn = nowBefore == ssid

                    if (!alreadyOn) {
                        notifyStatus(
                            AutoTaskStatus(
                                running = true,
                                message = "$step 闪切至「$ssid」…（已测通 ${results.size} 个）",
                                livePhase = "$step 切换中：$ssid"
                            )
                        )
                        val outcome = flashClickSsidAndReturnToApp(ssid)
                        log("$step 点击[$ssid] -> $outcome")

                        if (outcome == ClickOutcome.NEEDS_PASSWORD) {
                            skippedNoPassword++
                            HistoryStore.markNoPasswordSsid(this@WifiAutoSwitchService, ssid)
                            notifyStatus(
                                AutoTaskStatus(
                                    running = true,
                                    message = "$step 「$ssid」未存密码，已跳过 → 继续下一个",
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
                                    message = "$step 列表未找到「$ssid」，跳过 → 继续下一个",
                                    livePhase = "未找到：$ssid"
                                )
                            )
                            delay(350)
                            continue
                        }

                        notifyStatus(
                            AutoTaskStatus(
                                running = true,
                                message = "$step 等待连上「$ssid」…（已测通 ${results.size} 个）",
                                livePhase = "$step 等待连接 $ssid…"
                            )
                        )
                        val connected = waitForWifiConnected(ssid, timeoutMs = 13_000L)
                        val nowAfter = NetworkInspector.inspect(this@WifiAutoSwitchService).rawSsid
                        log("$step 等待[$ssid] -> $connected (当前SSID=$nowAfter)")

                        if (!connected) {
                            notifyStatus(
                                AutoTaskStatus(
                                    running = true,
                                    message = "$step 「$ssid」连接超时，跳过 → 继续下一个",
                                    livePhase = "连接超时：$ssid"
                                )
                            )
                            delay(350)
                            continue
                        }
                    } else {
                        log("$step 已连接[$ssid]，直接测速")
                    }

                    HistoryStore.markStarredSsid(this@WifiAutoSwitchService, ssid)
                    HistoryStore.clearNoPasswordSsid(this@WifiAutoSwitchService, ssid)

                    val res = runSpeedTestWithLiveUpdates(ssid, prefix = "$step ")
                    if (res != null) {
                        log("$step 测速[$ssid] -> ↓${String.format(Locale.US, "%.1f", res.downloadMbps)}M ${res.verdict.score}分")
                        results.add(res)
                    } else {
                        log("$step 测速[$ssid] -> 外网不通")
                        notifyStatus(
                            AutoTaskStatus(
                                running = true,
                                message = "$step 「$ssid」无法访问外网，继续下一个…",
                                livePhase = "无外网：$ssid"
                            )
                        )
                        delay(350)
                    }
                }

                if (results.isEmpty()) {
                    returnToAppSafely()
                    notifyStatus(
                        AutoTaskStatus(
                            false,
                            "已检查 ${queue.size} 个 Wi-Fi（跳过无密码 $skippedNoPassword 个），均无法连外网"
                        )
                    )
                    return@launch
                }

                val best = results.maxWith(
                    compareBy<SpeedTestResult> { it.verdict.score }
                        .thenBy { it.downloadMbps }
                )
                val nowSsid = NetworkInspector.inspect(this@WifiAutoSwitchService).rawSsid
                if (nowSsid != best.networkName) {
                    log("切回最佳: ${best.networkName}")
                    notifyStatus(
                        AutoTaskStatus(
                            running = true,
                            message = "全部测完！正自动切回最快 Wi-Fi「${best.networkName}」…",
                            livePhase = "切回最快：${best.networkName}"
                        )
                    )
                    flashClickSsidAndReturnToApp(best.networkName)
                    waitForWifiConnected(best.networkName, timeoutMs = 8000L)
                }

                returnToAppSafely()
                WifiScoutWidgetProvider.refreshAllWidgets(this@WifiAutoSwitchService)

                val skipHint = if (skippedNoPassword > 0) "（跳过无密码 $skippedNoPassword 个）" else ""
                val summary = String.format(
                    Locale.US,
                    "🏆 共实测 %d 个 Wi-Fi%s！最快「%s」（↓%.1fM · %dms · %d分），已切好！",
                    results.size,
                    skipHint,
                    best.networkName,
                    best.downloadMbps,
                    best.pingMs.toInt(),
                    best.verdict.score
                )
                log("完成: $summary")
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("巡检异常: ${e.message}")
                returnToAppSafely()
                notifyStatus(AutoTaskStatus(false, "自动轮测已停止：${e.message ?: ""}"))
            }
        }
    }

    private suspend fun runSpeedTestWithLiveUpdates(ssid: String, prefix: String): SpeedTestResult? {
        notifyStatus(
            AutoTaskStatus(
                running = true,
                message = "${prefix}已连「$ssid」，开始极速测速…",
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
                            message = "${prefix}测「$ssid」${ev.phase}$sp",
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
        } catch (e: Exception) {
            log("测速失败($ssid): ${e.message}")
            null
        }
    }

    /** 优先用前台 MainActivity 启动系统 Wi-Fi 页，彻底避开 OriginOS「后台弹出界面」拦截 */
    private fun openSystemWifiSettings() {
        val host = hostActivityRef?.get()
        if (host != null && !host.isFinishing && !host.isDestroyed) {
            try {
                host.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                return
            } catch (_: Exception) {
            }
        }
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    /** 优先用 GLOBAL_ACTION_BACK 返回 App（OriginOS 绝不拦截！），再辅以 startActivity 兜底 */
    private suspend fun returnToAppSafely() {
        for (attempt in 0..3) {
            val topPkg = rootInActiveWindow?.packageName?.toString().orEmpty()
            if (topPkg == packageName) return
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(420)
        }
        val stillNotApp = rootInActiveWindow?.packageName?.toString().orEmpty() != packageName
        if (stillNotApp) {
            try {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
                }
                startActivity(intent)
            } catch (_: Exception) {
            }
        }
    }

    private fun getAllWindowRoots(): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        try {
            windows?.forEach { w -> w.root?.let { list.add(it) } }
        } catch (_: Exception) {
        }
        rootInActiveWindow?.let { r ->
            if (list.none { it == r }) list.add(0, r)
        }
        return list
    }

    private suspend fun flashClickSsidAndReturnToApp(targetSsid: String): ClickOutcome {
        openSystemWifiSettings()
        delay(1150)

        var clicked = false
        // 双向滚动查找：先向下找 5 屏，若仍未找到则向上回滚 6 屏（解决 OriginOS 记住上次滚动位置导致错过顶部 Wi-Fi）
        val directions = listOf(0, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1, -1)
        for (dir in directions) {
            val roots = getAllWindowRoots().filter {
                val pkg = it.packageName?.toString().orEmpty()
                pkg.isNotEmpty() && pkg != packageName && pkg != "com.android.systemui"
            }

            if (roots.isEmpty()) {
                openSystemWifiSettings()
                delay(900)
                continue
            }

            if (dir > 0) {
                roots.any { scrollVerticalList(it, forward = true) }
                delay(420)
            } else if (dir < 0) {
                roots.any { scrollVerticalList(it, forward = false) }
                delay(420)
            }

            val freshRoots = getAllWindowRoots().filter {
                val pkg = it.packageName?.toString().orEmpty()
                pkg.isNotEmpty() && pkg != packageName && pkg != "com.android.systemui"
            }
            if (dir == 0) {
                log("设置窗口: ${freshRoots.mapNotNull { it.packageName?.toString() }.distinct().joinToString(",")}")
            }
            for (root in freshRoots) {
                val targetNode = findExactTextNode(root, targetSsid)
                if (targetNode != null) {
                    clicked = if (isNodeInSafeViewport(targetNode)) {
                        clickNodeOrGestureTap(targetNode)
                    } else {
                        clickClickableParentOnly(targetNode)
                    }
                    if (clicked) break
                }
            }
            if (clicked) break
        }

        if (!clicked) {
            log("上下滚动均未找到节点: $targetSsid")
            returnToAppSafely()
            return ClickOutcome.NOT_FOUND
        }

        delay(750)
        val afterRoots = getAllWindowRoots()
        for (r in afterRoots) {
            if (containsPasswordPrompt(r)) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                delay(250)
                returnToAppSafely()
                return ClickOutcome.NEEDS_PASSWORD
            }
            val connectBtn = findButtonByTexts(r, listOf("连接", "Connect", "确定", "加入"))
            if (connectBtn != null) {
                clickNodeOrGestureTap(connectBtn)
                delay(250)
                break
            }
        }

        // 点完立刻通过 BACK 瞬回 App！
        returnToAppSafely()
        return ClickOutcome.CLICKED
    }

    private fun isNodeInSafeViewport(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val screenH = resources.displayMetrics.heightPixels
        val minY = (screenH * 0.08f).toInt()
        val maxY = (screenH * 0.91f).toInt()
        return rect.width() > 4 && rect.height() > 4 && rect.centerY() in minY..maxY
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
                    if (validated || (hasNet && System.currentTimeMillis() - ssidMatchedAt >= 1400L)) {
                        return@withContext true
                    }
                }
                delay(400)
            }
            NetworkInspector.inspect(this@WifiAutoSwitchService).rawSsid == targetSsid
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
        if (txt.contains("输入密码") || txt.contains("请输入密码")) {
            return true
        }
        for (i in 0 until node.childCount) {
            if (containsPasswordPrompt(node.getChild(i))) return true
        }
        return false
    }

    /** 优先模拟真实手指点在 SSID 文字中心（不会误触右侧 ⓘ 详情按钮），其次 ACTION_CLICK */
    private fun clickNodeOrGestureTap(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() > 4 && rect.height() > 4 && rect.centerX() > 0 && rect.centerY() > 0) {
            val path = Path().apply {
                moveTo(rect.centerX().toFloat(), rect.centerY().toFloat())
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
                .build()
            if (dispatchGesture(gesture, null, null)) {
                log("手势点击 (${rect.centerX()},${rect.centerY()})")
                return true
            }
        }
        return clickClickableParentOnly(node)
    }

    private fun clickClickableParentOnly(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable) {
                val ok = cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (ok) log("节点ACTION_CLICK成功(depth=$depth)")
                return ok
            }
            cur = cur.parent
            depth++
        }
        return false
    }

    /** 只滚动高度最大的纵向列表容器，避免误滚横向标签栏 */
    private fun scrollVerticalList(root: AccessibilityNodeInfo, forward: Boolean): Boolean {
        var bestNode: AccessibilityNodeInfo? = null
        var bestHeight = 0

        fun findTallestScrollable(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isScrollable) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.height() >= r.width() && r.height() > bestHeight) {
                    bestHeight = r.height()
                    bestNode = node
                }
            }
            for (i in 0 until node.childCount) {
                findTallestScrollable(node.getChild(i))
            }
        }

        findTallestScrollable(root)
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return bestNode?.performAction(action) == true
    }

    companion object {
        @Volatile
        var instance: WifiAutoSwitchService? = null
            private set

        @Volatile
        private var hostActivityRef: WeakReference<Activity>? = null

        @Volatile
        var statusListener: ((AutoTaskStatus) -> Unit)? = null

        @Volatile
        var lastStatus: AutoTaskStatus = AutoTaskStatus(false, "")
            private set

        private val logLock = Any()
        private val logs = ArrayList<String>()

        fun setHostActivity(activity: Activity) {
            hostActivityRef = WeakReference(activity)
        }

        fun clearHostActivity(activity: Activity) {
            if (hostActivityRef?.get() === activity) {
                hostActivityRef = null
            }
        }

        fun isEnabled(): Boolean = instance != null

        fun getLogsText(): String = synchronized(logLock) {
            if (logs.isEmpty()) "暂无运行日志" else logs.joinToString("\n")
        }

        private fun clearLogs() = synchronized(logLock) { logs.clear() }

        private fun log(msg: String) = synchronized(logLock) {
            val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            logs.add("[$ts] $msg")
            if (logs.size > 60) logs.removeAt(0)
        }

        private fun notifyStatus(status: AutoTaskStatus) {
            lastStatus = status
            statusListener?.invoke(status)
        }
    }
}
