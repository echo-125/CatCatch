package com.catcatch.service

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

/**
 * 视频转码服务
 * 使用 Android 原生 MediaExtractor + MediaMuxer 实现 TS 转 MP4
 */
class FFmpegConverter {

    companion object {
        private const val TAG = "VideoConverter"
        private const val MIN_VALID_TS_SIZE = 1024L
        private const val MAX_BUFFER_SIZE = 1024 * 1024
        private const val TS_PACKET_SIZE = 188
        private const val TS_SYNC_BYTE = 0x47
    }

    fun isAvailable(): Boolean = true

    /**
     * 将 TS 文件转换为 MP4
     */
    suspend fun convertTsToMp4(
        inputTsFile: File,
        outputMp4File: File
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!inputTsFile.exists()) {
                throw Exception("输入文件不存在: ${inputTsFile.absolutePath}")
            }

            val fileSize = inputTsFile.length()
            if (fileSize < MIN_VALID_TS_SIZE) {
                throw Exception("输入文件过小 (${fileSize} bytes)，可能下载不完整")
            }

            Log.i(TAG, "开始转码: ${inputTsFile.name} (${fileSize} bytes)")

            if (outputMp4File.exists()) {
                outputMp4File.delete()
            }

            // 从 TS 流中解析 H.264 SPS/PPS 编解码器配置数据
            val (sps, pps) = parseH264CodecData(inputTsFile)
            if (sps != null && pps != null) {
                Log.i(TAG, "解析到 SPS: ${sps.size} bytes, PPS: ${pps.size} bytes")
            } else {
                Log.w(TAG, "未能解析到 SPS/PPS，转码可能失败")
            }

            val extractor = MediaExtractor()
            var muxer: MediaMuxer? = null

            try {
                extractor.setDataSource(inputTsFile.absolutePath)

                val trackCount = extractor.trackCount
                if (trackCount == 0) {
                    throw Exception("TS 文件中未找到媒体轨道")
                }

                Log.i(TAG, "检测到 $trackCount 个轨道")

                muxer = MediaMuxer(
                    outputMp4File.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )

                val trackMap = mutableMapOf<Int, Int>()
                for (i in 0 until trackCount) {
                    var format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: "unknown"
                    Log.i(TAG, "轨道 $i: $mime")

                    // 为视频轨道注入 SPS/PPS
                    if (mime.startsWith("video/") && sps != null && pps != null) {
                        format = injectCodecData(format, sps, pps)
                        Log.i(TAG, "已注入 H.264 codec data")
                    }

                    val muxerTrackIndex = muxer.addTrack(format)
                    trackMap[i] = muxerTrackIndex
                }

                muxer.start()

                val buffer = ByteBuffer.allocate(MAX_BUFFER_SIZE)
                val bufferInfo = MediaCodec.BufferInfo()

                for (trackIndex in 0 until trackCount) {
                    extractor.selectTrack(trackIndex)
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                    var frameCount = 0
                    while (true) {
                        coroutineContext.ensureActive()

                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) break

                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        bufferInfo.presentationTimeUs = extractor.sampleTime
                        bufferInfo.flags = extractor.sampleFlags

                        muxer.writeSampleData(trackMap[trackIndex]!!, buffer, bufferInfo)
                        frameCount++
                        extractor.advance()
                    }

                    extractor.unselectTrack(trackIndex)
                    Log.i(TAG, "轨道 $trackIndex 写入 $frameCount 帧")
                }

                Log.i(TAG, "转码完成: ${outputMp4File.name} (${outputMp4File.length()} bytes)")
            } catch (e: Exception) {
                if (outputMp4File.exists()) outputMp4File.delete()
                throw e
            } finally {
                try {
                    muxer?.stop()
                    muxer?.release()
                } catch (_: Exception) {}
                extractor.release()
            }

            Unit
        }
    }

    /**
     * 向 MediaFormat 注入 H.264 SPS/PPS 编解码器配置数据
     */
    private fun injectCodecData(format: MediaFormat, sps: ByteArray, pps: ByteArray): MediaFormat {
        // csd-0: SPS 带 start code
        val csd0 = ByteArray(4 + sps.size)
        csd0[0] = 0x00; csd0[1] = 0x00; csd0[2] = 0x00; csd0[3] = 0x01
        sps.copyInto(csd0, 4)

        // csd-1: PPS 带 start code
        val csd1 = ByteArray(4 + pps.size)
        csd1[0] = 0x00; csd1[1] = 0x00; csd1[2] = 0x00; csd1[3] = 0x01
        pps.copyInto(csd1, 4)

        format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
        format.setByteBuffer("csd-1", ByteBuffer.wrap(csd1))
        return format
    }

    /**
     * 解析 TS 文件，提取 H.264 SPS 和 PPS NAL 单元
     * 扫描所有非系统 PID 的 PES 数据，查找 H.264 NAL 单元
     */
    private fun parseH264CodecData(file: File): Pair<ByteArray?, ByteArray?> {
        var sps: ByteArray? = null
        var pps: ByteArray? = null

        // PID -> PES 数据缓冲区
        val pesBuffers = mutableMapOf<Int, MutableList<Byte>>()
        // 记录已确认包含 H.264 数据的 PID
        var videoPid = -1

        try {
            RandomAccessFile(file, "r").use { raf ->
                val packet = ByteArray(TS_PACKET_SIZE)
                var syncOffset = 0L

                while (raf.read(packet) == TS_PACKET_SIZE) {
                    // 验证同步字节
                    if (packet[0].toInt() and 0xFF != TS_SYNC_BYTE) {
                        // 尝试重新同步
                        syncOffset++
                        raf.seek(syncOffset)
                        continue
                    }
                    syncOffset += TS_PACKET_SIZE

                    val pid = ((packet[1].toInt() and 0x1F) shl 8) or (packet[2].toInt() and 0xFF)
                    val payloadUnitStart = (packet[1].toInt() and 0x40) != 0
                    val adaptationControl = (packet[3].toInt() and 0x30) shr 4

                    // 跳过系统 PID (PAT=0, CAT=1, TSDT=2, PMT 通常 0x1000+)
                    if (pid <= 2 || pid == 0x1FFF) continue

                    // 计算 payload 偏移
                    var payloadOffset = 4
                    if (adaptationControl == 0x02) continue
                    if (adaptationControl == 0x03) {
                        val adaptationLength = packet[4].toInt() and 0xFF
                        payloadOffset = 5 + adaptationLength
                    }
                    if (payloadOffset >= TS_PACKET_SIZE) continue

                    // 如果还没确定 videoPid，扫描所有 PID
                    if (videoPid == -1) {
                        // 收集每个 PID 的 PES 数据
                        if (payloadUnitStart) {
                            // 新 PES 包开始，检查之前积累的数据
                            for ((pidKey, buffer) in pesBuffers) {
                                if (buffer.size > 100) {
                                    val result = parsePesForH264(buffer.toByteArray())
                                    if (result.first != null) {
                                        videoPid = pidKey
                                        sps = result.first
                                        pps = result.second
                                        Log.d(TAG, "在 PID $pidKey 上找到 H.264 数据")
                                        break
                                    }
                                }
                            }
                            if (videoPid != -1) break
                        }

                        val buffer = pesBuffers.getOrPut(pid) { mutableListOf() }
                        for (i in payloadOffset until TS_PACKET_SIZE) {
                            buffer.add(packet[i])
                        }

                        // 限制每个 PID 的缓冲区大小
                        if (buffer.size > 2 * 1024 * 1024) {
                            buffer.clear()
                        }
                    } else if (pid == videoPid) {
                        // 已确定 videoPid，只收集该 PID 的数据
                        val buffer = pesBuffers.getOrPut(pid) { mutableListOf() }
                        if (payloadUnitStart && buffer.isNotEmpty()) {
                            val result = parsePesForH264(buffer.toByteArray())
                            if (result.first != null && sps == null) sps = result.first
                            if (result.second != null && pps == null) pps = result.second
                            buffer.clear()
                            if (sps != null && pps != null) return Pair(sps, pps)
                        }
                        for (i in payloadOffset until TS_PACKET_SIZE) {
                            buffer.add(packet[i])
                        }
                        if (buffer.size > 5 * 1024 * 1024) {
                            buffer.clear()
                        }
                    }
                }

                // 处理最后的缓冲区
                for ((_, buffer) in pesBuffers) {
                    if (buffer.size > 100 && (sps == null || pps == null)) {
                        val result = parsePesForH264(buffer.toByteArray())
                        if (result.first != null && sps == null) sps = result.first
                        if (result.second != null && pps == null) pps = result.second
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析 TS codec data 失败", e)
        }

        return Pair(sps, pps)
    }

    /**
     * 从 PES 数据中解析 H.264 NAL 单元，提取 SPS 和 PPS
     */
    private fun parsePesForH264(data: ByteArray): Pair<ByteArray?, ByteArray?> {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var i = 0

        while (i < data.size - 4) {
            // 寻找 NAL start code: 00 00 00 01 或 00 00 01
            val startCodeLen = when {
                i + 3 < data.size &&
                data[i].toInt() == 0 &&
                data[i + 1].toInt() == 0 &&
                data[i + 2].toInt() == 0 &&
                data[i + 3].toInt() == 1 -> 4
                i + 2 < data.size &&
                data[i].toInt() == 0 &&
                data[i + 1].toInt() == 0 &&
                data[i + 2].toInt() == 1 -> 3
                else -> {
                    i++
                    continue
                }
            }

            val nalStart = i + startCodeLen
            if (nalStart >= data.size) break

            val nalType = data[nalStart].toInt() and 0x1F

            // 寻找下一个 start code
            var nalEnd = data.size
            for (j in nalStart + 1 until data.size - 3) {
                if (data[j].toInt() == 0 && data[j + 1].toInt() == 0) {
                    if (data[j + 2].toInt() == 1 ||
                        (j + 3 < data.size && data[j + 2].toInt() == 0 && data[j + 3].toInt() == 1)
                    ) {
                        nalEnd = j
                        break
                    }
                }
            }

            val nalUnit = data.copyOfRange(nalStart, nalEnd)

            when (nalType) {
                7 -> { // SPS
                    if (sps == null) {
                        sps = nalUnit
                        Log.d(TAG, "找到 SPS: ${nalUnit.size} bytes")
                    }
                }
                8 -> { // PPS
                    if (pps == null) {
                        pps = nalUnit
                        Log.d(TAG, "找到 PPS: ${nalUnit.size} bytes")
                    }
                }
            }

            if (sps != null && pps != null) break

            i = nalEnd
        }

        return Pair(sps, pps)
    }
}
