package com.wifiscout.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WifiScoutWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateWidgetView(context, appWidgetManager, id, statusOverride = null)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_WIDGET_REFRESH -> {
                refreshAllWidgets(context, statusOverride = null)
            }
            ACTION_WIDGET_TEST -> {
                val pending = goAsync()
                refreshAllWidgets(context, statusOverride = "测速中…请稍候(约4秒)")
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val netInfo = NetworkInspector.inspect(context)
                        if (netInfo.kind == NetKind.OFFLINE) {
                            refreshAllWidgets(context, statusOverride = "无网络连接")
                            return@launch
                        }
                        val result = withTimeoutOrNull(9000L) {
                            SpeedTestEngine.runTest(
                                networkInfo = netInfo,
                                mode = TestMode.LITE,
                                nodePref = HistoryStore.getNodePref(context),
                                onProgress = { ev ->
                                    if (ev.currentMbps > 0.1) {
                                        val txt = "${ev.phase} ${String.format(Locale.US, "%.1f", ev.currentMbps)} Mbps"
                                        refreshAllWidgets(context, statusOverride = txt)
                                    }
                                }
                            )
                        }
                        if (result != null) {
                            HistoryStore.saveResult(context, result)
                            refreshAllWidgets(context, statusOverride = null)
                        } else {
                            refreshAllWidgets(context, statusOverride = "测速超时，点击打开App重试")
                        }
                    } catch (e: Exception) {
                        refreshAllWidgets(context, statusOverride = e.message ?: "测速失败")
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_WIDGET_TEST = "com.wifiscout.app.ACTION_WIDGET_TEST"
        const val ACTION_WIDGET_REFRESH = "com.wifiscout.app.ACTION_WIDGET_REFRESH"

        fun refreshAllWidgets(context: Context, statusOverride: String? = null) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, WifiScoutWidgetProvider::class.java))
            for (id in ids) {
                updateWidgetView(context, mgr, id, statusOverride)
            }
        }

        private fun updateWidgetView(
            context: Context,
            mgr: AppWidgetManager,
            widgetId: Int,
            statusOverride: String?
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_wifi_scout)
            val net = NetworkInspector.inspect(context)
            val prefix = when (net.kind) {
                NetKind.WIFI -> "📶 "
                NetKind.CELLULAR -> "📡 "
                NetKind.ETHERNET -> "🔌 "
                NetKind.OFFLINE -> "⚠️ "
            }
            views.setTextViewText(R.id.widgetNetworkName, prefix + net.displayName)
            views.setTextViewText(R.id.widgetNetworkSub, net.detailLine)

            // 优先匹配当前同名网络的测速记录，否则展示最近一次测速
            val history = HistoryStore.loadHistory(context)
            val matched = history.firstOrNull { it.networkName == net.displayName } ?: history.firstOrNull()

            if (matched != null) {
                views.setTextViewText(R.id.widgetDown, String.format(Locale.US, "%.1fM", matched.downloadMbps))
                views.setTextViewText(
                    R.id.widgetUp,
                    matched.uploadMbps?.let { String.format(Locale.US, "%.1fM", it) } ?: "--"
                )
                views.setTextViewText(R.id.widgetPing, "${matched.pingMs.toInt()}ms")

                val badgeText = "${matched.verdict.tier}级·${matched.verdict.score}分"
                views.setTextViewText(R.id.widgetVerdictBadge, badgeText)

                val badgeColor = when (matched.verdict.tier) {
                    "S" -> R.color.accent_green
                    "A" -> R.color.accent_sky
                    "B" -> R.color.accent_amber
                    else -> R.color.accent_red
                }
                views.setTextColor(R.id.widgetVerdictBadge, ContextCompat.getColor(context, badgeColor))

                val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(matched.timestamp))
                val isSameNet = matched.networkName == net.displayName
                val defaultStatus = if (isSameNet) {
                    "${matched.verdict.title} ($timeStr)"
                } else {
                    "上次测的是「${matched.networkName}」· 点测速测当前网"
                }
                views.setTextViewText(R.id.widgetStatus, statusOverride ?: defaultStatus)
            } else {
                views.setTextViewText(R.id.widgetDown, "--")
                views.setTextViewText(R.id.widgetUp, "--")
                views.setTextViewText(R.id.widgetPing, "--")
                views.setTextViewText(R.id.widgetVerdictBadge, "待测速")
                views.setTextColor(R.id.widgetVerdictBadge, ContextCompat.getColor(context, R.color.text_muted))
                views.setTextViewText(R.id.widgetStatus, statusOverride ?: "点右侧「⚡测速」判断电脑能否直连")
            }

            // 点击头部打开主 App
            val openAppIntent = Intent(context, MainActivity::class.java)
            val openAppPi = PendingIntent.getActivity(
                context, 10, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetHeader, openAppPi)

            // 点击「测速」按钮
            val testIntent = Intent(context, WifiScoutWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_TEST
            }
            val testPi = PendingIntent.getBroadcast(
                context, 11, testIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetBtnTest, testPi)

            // 点击「刷新」按钮
            val refreshIntent = Intent(context, WifiScoutWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_REFRESH
            }
            val refreshPi = PendingIntent.getBroadcast(
                context, 12, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetBtnRefresh, refreshPi)

            mgr.updateAppWidget(widgetId, views)
        }
    }
}
