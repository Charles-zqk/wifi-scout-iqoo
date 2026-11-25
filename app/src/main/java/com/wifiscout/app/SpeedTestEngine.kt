package com.wifiscout.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class TestMode { LITE, FULL }
enum class NodePref { AUTO, CLOUDFLARE, CN }

data class LaptopVerdict(
    val score: Int,
    val tier: String,       // S, A, B, C
    val tierLabel: String,
    val title: String,
    val desc: String,
    val capSummary: String
)

data class SpeedTestResult(
    val id: String,
    val timestamp: Long,
    val networkName: String,
    val kind: NetKind,
    val detailLine: String,
    val nodeName: String,
    val downloadMbps: Double,
    val uploadMbps: Double?,
    val pingMs: Double,
    val jitterMs: Double,
    val bytesUsed: Long,
    val verdict: LaptopVerdict
)

data class ProgressEvent(
    val phase: String,
    val currentMbps: Double,
    val bytesUsed: Long,
    val pingMs: Double? = null,
    val jitterMs: Double? = null,
    val nodeName: String? = null
)

private data class SpeedNode(
    val name: String,
    val supportsUpload: Boolean,
    val pingUrl: () -> String,
    val downloadUrl: (Int) -> String,
    val uploadUrl: (() -> String)? = null,
    val exactStream: Boolean = true
)

object SpeedTestEngine {

    private fun nonce(): String = "${System.currentTimeMillis()}_${(1000..9999).random()}"

    private val cloudflareNode = SpeedNode(
        name = "Cloudflare 全球",
        supportsUpload = true,
        pingUrl = { "https://speed.cloudflare.com/__down?bytes=0&r=${nonce()}" },
        downloadUrl = { bytes -> "https://speed.cloudflare.com/__down?bytes=$bytes&r=${nonce()}" },
        uploadUrl = { "https://speed.cloudflare.com/__up?r=${nonce()}" },
        exactStream = true
    )

    private val jsDelivrNode = SpeedNode(
        name = "jsDelivr 全球备用",
        supportsUpload = false,
        pingUrl = { "https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.3/package.json?r=${nonce()}" },
        downloadUrl = { "https://cdn.jsdelivr.net/npm/@fortawesome/fontawesome-free@6.5.2/webfonts/fa-solid-900.woff2?r=${nonce()}" },
        exactStream = false
    )

    private val cnNode = SpeedNode(
        name = "国内兼容 CDN",
        supportsUpload = false,
        pingUrl = { "https://s4.zstatic.net/ajax/libs/font-awesome/6.5.2/css/all.min.css?r=${nonce()}" },
        downloadUrl = { "https://s4.zstatic.net/ajax/libs/font-awesome/6.5.2/webfonts/fa-solid-900.woff2?r=${nonce()}" },
        exactStream = false
    )

    suspend fun runTest(
        networkInfo: CurrentNetworkInfo,
        mode: TestMode,
        nodePref: NodePref,
        onProgress: (ProgressEvent) -> Unit
    ): SpeedTestResult = withContext(Dispatchers.IO) {
        onProgress(ProgressEvent("正在优选测速节点…", 0.0, 0L))
        val node = resolveNode(nodePref)
        onProgress(ProgressEvent("已连节点：${node.name}", 0.0, 0L, nodeName = node.name))

        // 1. 延迟与抖动
        onProgress(ProgressEvent("测试延迟与抖动…", 0.0, 0L, nodeName = node.name))
        val (pingMs, jitterMs, lossRate) = measureLatency(node)
        onProgress(ProgressEvent("开始下载测速…", 0.0, 0L, pingMs = pingMs, jitterMs = jitterMs, nodeName = node.name))

        // 2. 下载测速
        val (downloadMbps, downBytes) = measureDownload(node, mode) { instMbps, used ->
            onProgress(ProgressEvent("正在测下载…", instMbps, used, pingMs = pingMs, jitterMs = jitterMs, nodeName = node.name))
        }

        // 3. 上传测速
        var totalBytes = downBytes
        val upUrlFn = node.uploadUrl
        val uploadMbps: Double? = if (node.supportsUpload && upUrlFn != null) {
            onProgress(ProgressEvent("正在测上传…", 0.0, totalBytes, pingMs = pingMs, jitterMs = jitterMs, nodeName = node.name))
            val (upMbps, upBytes) = measureUpload(upUrlFn, mode, downloadMbps) { instMbps, upUsed ->
                onProgress(ProgressEvent("正在测上传…", instMbps, totalBytes + upUsed, pingMs = pingMs, jitterMs = jitterMs, nodeName = node.name))
            }
            totalBytes += upBytes
            upMbps
        } else {
            null
        }

        val verdict = evaluateLaptopReadiness(downloadMbps, uploadMbps, pingMs, jitterMs, lossRate)
        SpeedTestResult(
            id = nonce(),
            timestamp = System.currentTimeMillis(),
            networkName = networkInfo.displayName,
            kind = networkInfo.kind,
            detailLine = networkInfo.detailLine,
            nodeName = node.name,
            downloadMbps = downloadMbps,
            uploadMbps = uploadMbps,
            pingMs = pingMs,
            jitterMs = jitterMs,
            bytesUsed = totalBytes,
            verdict = verdict
        )
    }

    private suspend fun resolveNode(pref: NodePref): SpeedNode {
        val candidates = when (pref) {
            NodePref.CLOUDFLARE -> listOf(cloudflareNode)
            NodePref.CN -> listOf(cnNode, jsDelivrNode)
            NodePref.AUTO -> listOf(cloudflareNode, jsDelivrNode, cnNode)
        }
        for (node in candidates) {
            coroutineContext.ensureActive()
            if (probeUrl(node.pingUrl(), 2500)) return node
        }
        throw IllegalStateException("测速节点连接超时，当前 Wi-Fi 可能无外网权限或需要网页登录认证")
    }

    private fun probeUrl(urlStr: String, timeoutMs: Int): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                useCaches = false
                requestMethod = "GET"
            }
            val code = conn.responseCode
            conn.inputStream?.use { it.readBytes() }
            code in 200..299
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    private suspend fun measureLatency(node: SpeedNode): Triple<Double, Double, Double> {
        val rtts = mutableListOf<Double>()
        val attempts = 5
        var failed = 0
        for (i in 0 until attempts) {
            coroutineContext.ensureActive()
            val t0 = System.nanoTime()
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(node.pingUrl()).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2800
                    readTimeout = 2800
                    useCaches = false
                    requestMethod = "GET"
                }
                if (conn.responseCode in 200..299) {
                    conn.inputStream?.use { it.readBytes() }
                    val ms = (System.nanoTime() - t0) / 1_000_000.0
                    rtts.add(ms)
                } else {
                    failed++
                }
            } catch (_: Exception) {
                failed++
            } finally {
                conn?.disconnect()
            }
        }
        if (rtts.isEmpty()) throw IllegalStateException("网络超时，无法连通外网")

        val steady = if (rtts.size >= 3) rtts.drop(1) else rtts
        val sorted = steady.sorted()
        val ping = sorted[sorted.size / 2]
        val jitter = if (steady.size >= 2) {
            (1 until steady.size).sumOf { abs(steady[it] - steady[it - 1]) } / (steady.size - 1)
        } else 0.0
        val lossRate = (failed.toDouble() / attempts) * 100.0
        return Triple(ping, jitter, lossRate)
    }

    private suspend fun measureDownload(
        node: SpeedNode,
        mode: TestMode,
        onTick: (Double, Long) -> Unit
    ): Pair<Double, Long> {
        val maxDurationMs = if (mode == TestMode.FULL) 5600L else 3400L
        val maxTotalBytes = if (mode == TestMode.FULL) 22L * 1024 * 1024 else 6L * 1024 * 1024
        val plan = if (node.exactStream) {
            if (mode == TestMode.FULL) listOf(1_500_000, 6_000_000, 14_000_000)
            else listOf(1_000_000, 4_500_000)
        } else {
            if (mode == TestMode.FULL) List(6) { 0 } else List(3) { 0 }
        }

        val startNs = System.nanoTime()
        var totalBytes = 0L
        val samples = mutableListOf<Double>()
        val buf = ByteArray(16 * 1024)

        for (chunkSize in plan) {
            coroutineContext.ensureActive()
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            if (elapsedMs >= maxDurationMs || totalBytes >= maxTotalBytes) break

            var conn: HttpURLConnection? = null
            try {
                conn = (URL(node.downloadUrl(chunkSize)).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3500
                    readTimeout = 4000
                    useCaches = false
                    requestMethod = "GET"
                    setRequestProperty("Accept-Encoding", "identity")
                }
                if (conn.responseCode !in 200..299) continue

                val chunkStartNs = System.nanoTime()
                var chunkBytes = 0L
                var winBytes = 0L
                var winStartNs = chunkStartNs

                val stream: InputStream = conn.inputStream
                stream.use { input ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buf)
                        if (read <= 0) break
                        totalBytes += read
                        chunkBytes += read
                        winBytes += read

                        val nowNs = System.nanoTime()
                        val winMs = (nowNs - winStartNs) / 1_000_000.0
                        if (winMs >= 160.0 && winBytes > 0) {
                            val instMbps = (winBytes * 8.0) / (winMs * 1000.0)
                            samples.add(instMbps)
                            onTick(instMbps, totalBytes)
                            winBytes = 0L
                            winStartNs = nowNs
                        }
                        if ((nowNs - startNs) / 1_000_000L >= maxDurationMs || totalBytes >= maxTotalBytes) {
                            break
                        }
                    }
                }

                val chunkSec = max(0.05, (System.nanoTime() - chunkStartNs) / 1_000_000_000.0)
                val chunkMbps = (chunkBytes * 8.0) / (chunkSec * 1_000_000.0)
                if (mode == TestMode.LITE && chunkMbps < 4.0 && totalBytes >= 800_000L) break
            } catch (_: Exception) {
                if (totalBytes > 0) break
            } finally {
                conn?.disconnect()
            }
        }

        if (totalBytes == 0L) throw IllegalStateException("未能下载测速数据，请检查网络")
        val totalSec = max(0.1, (System.nanoTime() - startNs) / 1_000_000_000.0)
        var finalMbps = (totalBytes * 8.0) / (totalSec * 1_000_000.0)

        if (samples.size >= 6) {
            val steady = samples.drop(samples.size / 4).sorted()
            val from = (steady.size * 0.15).toInt()
            val to = max(from + 1, (steady.size * 0.90).toInt())
            val trimmedAvg = steady.subList(from, to).average()
            finalMbps = trimmedAvg * 0.7 + finalMbps * 0.3
        }
        return Pair(finalMbps, totalBytes)
    }

    private suspend fun measureUpload(
        uploadUrlFn: () -> String,
        mode: TestMode,
        downloadHintMbps: Double,
        onTick: (Double, Long) -> Unit
    ): Pair<Double?, Long> {
        val uploadSize = when {
            mode == TestMode.FULL && downloadHintMbps > 40 -> 2_200_000
            mode == TestMode.FULL && downloadHintMbps > 10 -> 1_100_000
            mode == TestMode.FULL -> 512_000
            downloadHintMbps > 30 -> 900_000
            else -> 384_000
        }

        val chunk = ByteArray(16 * 1024) { i -> ((i * 31 + 7) and 0xFF).toByte() }
        var conn: HttpURLConnection? = null
        var sent = 0L
        val startNs = System.nanoTime()

        return try {
            conn = (URL(uploadUrlFn()).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3500
                readTimeout = 4500
                doOutput = true
                useCaches = false
                requestMethod = "POST"
                setFixedLengthStreamingMode(uploadSize)
                setRequestProperty("Content-Type", "application/octet-stream")
            }

            var winBytes = 0L
            var winStartNs = System.nanoTime()
            conn.outputStream.use { out ->
                while (sent < uploadSize) {
                    coroutineContext.ensureActive()
                    val toWrite = min(chunk.size.toLong(), uploadSize - sent).toInt()
                    out.write(chunk, 0, toWrite)
                    sent += toWrite
                    winBytes += toWrite
                    val nowNs = System.nanoTime()
                    val winMs = (nowNs - winStartNs) / 1_000_000.0
                    if (winMs >= 160.0) {
                        val instMbps = (winBytes * 8.0) / (winMs * 1000.0)
                        onTick(instMbps, sent)
                        winBytes = 0L
                        winStartNs = nowNs
                    }
                }
                out.flush()
            }

            val code = conn.responseCode
            conn.inputStream?.use { it.readBytes() }
            val sec = max(0.08, (System.nanoTime() - startNs) / 1_000_000_000.0)
            if (code in 200..299) Pair((sent * 8.0) / (sec * 1_000_000.0), sent) else Pair(null, sent)
        } catch (_: Exception) {
            Pair(null, sent)
        } finally {
            conn?.disconnect()
        }
    }

    fun evaluateLaptopReadiness(
        downloadMbps: Double,
        uploadMbps: Double?,
        pingMs: Double,
        jitterMs: Double,
        lossRate: Double = 0.0
    ): LaptopVerdict {
        val downScore = when {
            downloadMbps >= 100 -> 50.0
            downloadMbps >= 50 -> 42.0 + ((downloadMbps - 50.0) / 50.0) * 8.0
            downloadMbps >= 20 -> 32.0 + ((downloadMbps - 20.0) / 30.0) * 10.0
            downloadMbps >= 10 -> 22.0 + ((downloadMbps - 10.0) / 10.0) * 10.0
            downloadMbps >= 3 -> 10.0 + ((downloadMbps - 3.0) / 7.0) * 12.0
            else -> (downloadMbps / 3.0) * 10.0
        }

        val pingScore = when {
            pingMs <= 30 -> 20.0
            pingMs <= 60 -> 16.0 + ((60.0 - pingMs) / 30.0) * 4.0
            pingMs <= 120 -> 10.0 + ((120.0 - pingMs) / 60.0) * 6.0
            pingMs <= 250 -> 4.0 + ((250.0 - pingMs) / 130.0) * 6.0
            else -> max(0.0, 4.0 - ((pingMs - 250.0) / 250.0) * 4.0)
        }

        val jitterScore = when {
            jitterMs <= 10 -> 10.0
            jitterMs <= 30 -> 6.0 + ((30.0 - jitterMs) / 20.0) * 4.0
            jitterMs <= 80 -> 2.0 + ((80.0 - jitterMs) / 50.0) * 4.0
            else -> max(0.0, 2.0 - ((jitterMs - 80.0) / 100.0) * 2.0)
        }

        val rawScore = if (uploadMbps != null) {
            val upScore = when {
                uploadMbps >= 20 -> 20.0
                uploadMbps >= 10 -> 16.0 + ((uploadMbps - 10.0) / 10.0) * 4.0
                uploadMbps >= 5 -> 11.0 + ((uploadMbps - 5.0) / 5.0) * 5.0
                uploadMbps >= 2 -> 6.0 + ((uploadMbps - 2.0) / 3.0) * 5.0
                else -> (uploadMbps / 2.0) * 6.0
            }
            downScore + upScore + pingScore + jitterScore
        } else {
            ((downScore + pingScore + jitterScore) / 80.0) * 100.0
        }

        val score = max(0, min(100, (rawScore - lossRate * 0.4).roundToInt()))
        val up = uploadMbps ?: (downloadMbps * 0.25)

        val (tier, tierLabel, title, desc) = when {
            score >= 80 && downloadMbps >= 35 -> Quad(
                "S",
                "S 级 · 极速推荐",
                "强烈推荐电脑直接连接！",
                "带宽充沛且延迟低，电脑连接后高清视频会议、大文件同步与远程桌面都很丝滑。"
            )
            score >= 60 && downloadMbps >= 15 -> Quad(
                "A",
                "A 级 · 推荐连接",
                "推荐电脑连接，办公流畅",
                "速度稳定，足以流畅支撑电脑日常办公、文档协作与语音/视频会议。"
            )
            score >= 40 && downloadMbps >= 5 -> Quad(
                "B",
                "B 级 · 勉强可用",
                "能用但偏慢，建议对比其他网",
                "适合看网页和发消息，但大文件与会议偏吃力，建议再测测附近其他 Wi-Fi 或手机热点。"
            )
            else -> Quad(
                "C",
                "C 级 · 不建议连接",
                "网速过慢，别让电脑连这个！",
                "当前网络卡顿或延迟过高，建议立刻切换下一个 Wi-Fi，或直接开手机 5G/4G 热点。"
            )
        }

        val webOk = if (downloadMbps >= 4 && pingMs < 250) "流畅" else if (downloadMbps >= 1.5) "一般" else "卡顿"
        val meetOk = if (downloadMbps >= 10 && up >= 3 && pingMs < 120 && jitterMs < 35) "高清稳" else if (downloadMbps >= 4 && pingMs < 200) "标清" else "易卡"
        val fileOk = if (downloadMbps >= 35 && up >= 10) "极快" else if (downloadMbps >= 12) "中等" else "较慢"
        val remoteOk = if (downloadMbps >= 15 && pingMs < 75 && jitterMs < 22) "跟手" else if (downloadMbps >= 8 && pingMs < 140) "微延迟" else "迟滞"
        val capSummary = "🌐网页:$webOk  📹会议:$meetOk  📦传输:$fileOk  💻远程:$remoteOk"

        return LaptopVerdict(score, tier, tierLabel, title, desc, capSummary)
    }

    private data class Quad(val a: String, val b: String, val c: String, val d: String)
}
