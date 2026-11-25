package com.wifiscout.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object HistoryStore {
    private const val PREF_NAME = "wifi_scout_prefs"
    private const val KEY_HISTORY = "history_json"
    private const val KEY_MODE = "test_mode"
    private const val KEY_NODE = "node_pref"

    fun saveResult(context: Context, result: SpeedTestResult) {
        val list = loadHistory(context).toMutableList()
        // 同名网络保留最新记录，避免排行榜重复堆叠；不同网络并列对比
        list.removeAll { it.networkName == result.networkName && it.kind == result.kind }
        list.add(0, result)
        val trimmed = list.take(15)

        val arr = JSONArray()
        for (item in trimmed) {
            arr.put(toJson(item))
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HISTORY, arr.toString())
            .apply()
    }

    fun loadHistory(context: Context): List<SpeedTestResult> {
        val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<SpeedTestResult>()
            for (i in 0 until arr.length()) {
                fromJson(arr.getJSONObject(i))?.let { list.add(it) }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun latestResult(context: Context): SpeedTestResult? = loadHistory(context).firstOrNull()

    fun clearHistory(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_HISTORY)
            .apply()
    }

    fun getMode(context: Context): TestMode {
        val v = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_MODE, "LITE")
        return if (v == "FULL") TestMode.FULL else TestMode.LITE
    }

    fun setMode(context: Context, mode: TestMode) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode.name).apply()
    }

    fun getNodePref(context: Context): NodePref {
        val v = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_NODE, "AUTO")
        return try { NodePref.valueOf(v ?: "AUTO") } catch (_: Exception) { NodePref.AUTO }
    }

    fun setNodePref(context: Context, pref: NodePref) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_NODE, pref.name).apply()
    }

    private fun toJson(r: SpeedTestResult): JSONObject = JSONObject().apply {
        put("id", r.id)
        put("ts", r.timestamp)
        put("name", r.networkName)
        put("kind", r.kind.name)
        put("detail", r.detailLine)
        put("node", r.nodeName)
        put("down", r.downloadMbps)
        if (r.uploadMbps != null) put("up", r.uploadMbps) else put("up", JSONObject.NULL)
        put("ping", r.pingMs)
        put("jitter", r.jitterMs)
        put("bytes", r.bytesUsed)
        put("score", r.verdict.score)
        put("tier", r.verdict.tier)
        put("tierLabel", r.verdict.tierLabel)
        put("title", r.verdict.title)
        put("desc", r.verdict.desc)
        put("cap", r.verdict.capSummary)
    }

    private fun fromJson(o: JSONObject): SpeedTestResult? {
        return try {
            val up = if (o.isNull("up")) null else o.getDouble("up")
            SpeedTestResult(
                id = o.optString("id"),
                timestamp = o.optLong("ts"),
                networkName = o.optString("name", "未知网络"),
                kind = try { NetKind.valueOf(o.optString("kind", "WIFI")) } catch (_: Exception) { NetKind.WIFI },
                detailLine = o.optString("detail", ""),
                nodeName = o.optString("node", ""),
                downloadMbps = o.optDouble("down", 0.0),
                uploadMbps = up,
                pingMs = o.optDouble("ping", 0.0),
                jitterMs = o.optDouble("jitter", 0.0),
                bytesUsed = o.optLong("bytes", 0L),
                verdict = LaptopVerdict(
                    score = o.optInt("score", 0),
                    tier = o.optString("tier", "C"),
                    tierLabel = o.optString("tierLabel", ""),
                    title = o.optString("title", ""),
                    desc = o.optString("desc", ""),
                    capSummary = o.optString("cap", "")
                )
            )
        } catch (_: Exception) {
            null
        }
    }
}
