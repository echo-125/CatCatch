package com.catcatch.service

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 视频转码服务
 * 支持三种转码后端：
 * - FFmpeg-kit: 使用 ffmpeg 命令行，兼容性最好
 * - MediaExtractor + MediaMuxer: Android 原生，速度快
 * - 手写 muxer: 纯 Java 实现，不依赖系统服务，支持 PTS/DTS 重写
 *
 * 转码模式 (transcodeMode):
 * - 0 = 自动: 系统原生优先，降级至 FFmpeg-kit
 * - 1 = FFmpeg-kit: 仅使用 FFmpeg-kit
 * - 2 = 系统原生: 仅使用 MediaExtractor + 手写 muxer
 */
class FFmpegConverter {

    companion object {
        private const val TAG = "VideoConverter"
        private const val MIN_VALID_TS_SIZE = 1024L
        private const val MAX_BUFFER_SIZE = 1024 * 1024
        private const val EXTRACTOR_MAX_RETRIES = 2
        private const val EXTRACTOR_RETRY_DELAY_MS = 1000L
        private const val DEFAULT_TIMESCALE = 90000L
        private const val DEFAULT_FRAME_DURATION = 3000L // 30fps: 90000/30
    }

    /**
     * 检查指定模式的转码是否可用
     */
    fun isAvailable(transcodeMode: Int = 0): Boolean {
        return when (transcodeMode) {
            1 -> isFFmpegKitAvailable()
            2 -> isMediaExtractorAvailable()
            else -> isMediaExtractorAvailable() || isFFmpegKitAvailable()
        }
    }

    private fun isMediaExtractorAvailable(): Boolean {
        return try {
            Class.forName("android.media.MediaExtractor")
            Class.forName("android.media.MediaMuxer")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    private fun isFFmpegKitAvailable(): Boolean {
        return try {
            Class.forName("com.arthenica.ffmpegkit.FFmpegKit")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    /**
     * 视频元信息
     */
    data class VideoInfo(
        val width: Int = 0,
        val height: Int = 0,
        val durationUs: Long = 0
    ) {
        val resolution: String
            get() = if (width > 0 && height > 0) "${width}x${height}" else ""
    }

    /**
     * 提取视频文件的元信息（分辨率、时长）
     * 失败时返回空信息，不阻塞主流程
     */
    fun extractVideoInfo(file: File): VideoInfo {
        return try {
            if (!file.exists()) return VideoInfo()
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/")) {
                        val width = format.getIntegerOrDefault(MediaFormat.KEY_WIDTH, 0)
                        val height = format.getIntegerOrDefault(MediaFormat.KEY_HEIGHT, 0)
                        val durationUs = format.getLongOrDefault(MediaFormat.KEY_DURATION, 0L)
                        Log.i(TAG, "视频元信息: ${width}x${height}, 时长=${durationUs}us")
                        return VideoInfo(width, height, durationUs)
                    }
                }
                VideoInfo()
            } finally {
                extractor.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "提取视频元信息失败: ${e.message}")
            VideoInfo()
        }
    }

    private fun MediaFormat.getIntegerOrDefault(name: String, default: Int): Int {
        return try { if (containsKey(name)) getInteger(name) else default } catch (_: Exception) { default }
    }

    private fun MediaFormat.getLongOrDefault(name: String, default: Long): Long {
        return try { if (containsKey(name)) getLong(name) else default } catch (_: Exception) { default }
    }

    /**
     * 将 TS 文件转换为 MP4
     * @param transcodeMode 转码模式: 0=自动, 1=FFmpeg-kit, 2=系统原生
     */
    suspend fun convertTsToMp4(
        inputTsFile: File,
        outputMp4File: File,
        externalFilesDir: File? = null,
        transcodeMode: Int = 0
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!inputTsFile.exists()) throw Exception("输入文件不存在")
            if (inputTsFile.length() < MIN_VALID_TS_SIZE) throw Exception("输入文件过小")

            Log.i(TAG, "开始转码: ${inputTsFile.name} (${inputTsFile.length()} bytes), 模式=$transcodeMode")
            if (outputMp4File.exists()) outputMp4File.delete()

            val startTime = System.currentTimeMillis()

            when (transcodeMode) {
                1 -> {
                    convertWithFFmpegKit(inputTsFile, outputMp4File)
                }
                2 -> {
                    convertWithMediaExtractorPipeline(inputTsFile, outputMp4File, externalFilesDir)
                }
                else -> {
                    // 自动: 系统原生优先，降级至 FFmpeg-kit
                    convertWithAutoFallback(inputTsFile, outputMp4File, externalFilesDir)
                }
            }

            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "转码完成: ${outputMp4File.length()} bytes, 耗时=${elapsed}ms (${String.format("%.1f", elapsed / 1000.0)}s)")
        }.map { Unit }
    }

    /**
     * 自动模式: 系统原生 → FFmpeg-kit
     */
    private suspend fun convertWithAutoFallback(
        inputTsFile: File,
        outputMp4File: File,
        externalFilesDir: File?
    ) {
        // 方案 1: 系统原生（MediaExtractor + 手写 muxer）
        val nativeStart = System.currentTimeMillis()
        val nativeResult = runCatching {
            convertWithMediaExtractorPipeline(inputTsFile, outputMp4File, externalFilesDir)
        }
        if (nativeResult.isSuccess) {
            val nativeElapsed = System.currentTimeMillis() - nativeStart
            Log.i(TAG, "系统原生转码成功, 耗时=${nativeElapsed}ms")
            return
        }
        val nativeElapsed = System.currentTimeMillis() - nativeStart
        Log.w(TAG, "系统原生转码失败(${nativeElapsed}ms): ${nativeResult.exceptionOrNull()?.message}")
        if (outputMp4File.exists()) outputMp4File.delete()

        // 方案 2: FFmpeg-kit
        convertWithFFmpegKit(inputTsFile, outputMp4File)
    }

    /**
     * FFmpeg-kit 转码: -c copy 直接复制流，速度快兼容性好
     */
    private fun convertWithFFmpegKit(inputTsFile: File, outputMp4File: File) {
        Log.i(TAG, "使用 FFmpeg-kit 转码...")
        val startTime = System.currentTimeMillis()
        val cmd = "-y -i \"${inputTsFile.absolutePath}\" -c copy -movflags +faststart \"${outputMp4File.absolutePath}\""
        val session = FFmpegKit.execute(cmd)
        val elapsed = System.currentTimeMillis() - startTime

        if (!ReturnCode.isSuccess(session.returnCode)) {
            val logs = session.allLogsAsString
            Log.e(TAG, "FFmpeg-kit 失败(${elapsed}ms): code=${session.returnCode}, logs=$logs")
            throw Exception("FFmpeg-kit 转码失败: ${session.returnCode}")
        }

        if (!outputMp4File.exists() || outputMp4File.length() < 1024) {
            throw Exception("FFmpeg-kit 输出文件无效")
        }
        Log.i(TAG, "FFmpeg-kit 转码完成: ${outputMp4File.length()} bytes, 耗时=${elapsed}ms")
    }

    /**
     * 系统原生转码管线: MediaExtractor → 外部目录重试 → 手写 muxer（支持 PTS 重写）
     */
    private fun convertWithMediaExtractorPipeline(
        inputTsFile: File,
        outputMp4File: File,
        externalFilesDir: File?
    ) {
        // 方案 1: MediaExtractor
        val extractor = createInitializedExtractor(inputTsFile)
        if (extractor != null) {
            try {
                val t = System.currentTimeMillis()
                convertWithExtractor(extractor, inputTsFile, outputMp4File)
                Log.i(TAG, "MediaExtractor 转码成功: ${outputMp4File.length()} bytes, 耗时=${System.currentTimeMillis() - t}ms")
                return
            } catch (e: Exception) {
                Log.e(TAG, "MediaExtractor 转码失败: ${e.message}")
                if (outputMp4File.exists()) outputMp4File.delete()
            } finally {
                extractor.release()
            }
        }

        // 方案 2: 复制到 getExternalFilesDir 再试
        if (externalFilesDir != null && !inputTsFile.absolutePath.startsWith(externalFilesDir.absolutePath)) {
            Log.i(TAG, "尝试复制到 getExternalFilesDir 再转码...")
            val altFile = File(externalFilesDir, inputTsFile.name)
            try {
                if (altFile.parentFile?.exists() != true) altFile.parentFile?.mkdirs()
                inputTsFile.copyTo(altFile, overwrite = true)
                val altExtractor = createInitializedExtractor(altFile)
                if (altExtractor != null) {
                    try {
                        val t = System.currentTimeMillis()
                        convertWithExtractor(altExtractor, altFile, outputMp4File)
                        Log.i(TAG, "外部目录 MediaExtractor 转码成功: ${outputMp4File.length()} bytes, 耗时=${System.currentTimeMillis() - t}ms")
                        return
                    } catch (e: Exception) {
                        Log.e(TAG, "外部目录 MediaExtractor 也失败: ${e.message}")
                        if (outputMp4File.exists()) outputMp4File.delete()
                    } finally {
                        altExtractor.release()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "复制到外部目录失败: ${e.message}")
            } finally {
                altFile.delete()
            }
        }

        // 方案 3: 纯手写 TS→MP4 muxer（支持 PTS 重写）
        Log.i(TAG, "尝试纯手写 TS→MP4 muxer（PTS 重写模式）...")
        val t = System.currentTimeMillis()
        convertWithManualMuxer(inputTsFile, outputMp4File)
        Log.i(TAG, "手写 muxer 转码完成: ${outputMp4File.length()} bytes, 耗时=${System.currentTimeMillis() - t}ms")
    }

    private fun createInitializedExtractor(file: File): MediaExtractor? {
        for (attempt in 1..EXTRACTOR_MAX_RETRIES) {
            var e1: MediaExtractor? = null
            try {
                e1 = MediaExtractor()
                e1.setDataSource(file.absolutePath)
                return e1
            } catch (e: Exception) {
                Log.w(TAG, "MediaExtractor 第 $attempt 次失败: ${e.message}")
                try { e1?.release() } catch (_: Exception) {}
                if (attempt < EXTRACTOR_MAX_RETRIES) Thread.sleep(EXTRACTOR_RETRY_DELAY_MS)
            }
            var e2: MediaExtractor? = null
            try {
                e2 = MediaExtractor()
                FileInputStream(file).use { fis -> e2.setDataSource(fis.fd) }
                return e2
            } catch (e: Exception) {
                Log.w(TAG, "MediaExtractor fd 第 $attempt 次失败: ${e.message}")
                try { e2?.release() } catch (_: Exception) {}
                if (attempt < EXTRACTOR_MAX_RETRIES) Thread.sleep(EXTRACTOR_RETRY_DELAY_MS)
            }
        }
        return null
    }

    private fun convertWithExtractor(extractor: MediaExtractor, inputTsFile: File, outputMp4File: File) {
        var muxer: MediaMuxer? = null
        try {
            val trackCount = extractor.trackCount
            if (trackCount == 0) throw Exception("未找到媒体轨道")

            muxer = MediaMuxer(outputMp4File.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackMap = mutableMapOf<Int, Int>()
            var hasVideo = false

            for (i in 0 until trackCount) {
                var format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                Log.d(TAG, "轨道 $i: mime=$mime")
                if (mime.startsWith("video/")) {
                    hasVideo = true
                    val csd0 = format.getByteBuffer("csd-0")
                    val csd1 = format.getByteBuffer("csd-1")
                    val csd0Size = csd0?.remaining() ?: 0
                    val csd1Size = csd1?.remaining() ?: 0
                    Log.d(TAG, "视频轨道 csd-0: ${csd0Size} bytes, csd-1: ${csd1Size} bytes")
                    if (csd0Size < 4 || csd1Size < 1) {
                        Log.w(TAG, "csd 无效（csd-0=${csd0Size}, csd-1=${csd1Size}），尝试从 TS 文件提取 SPS/PPS")
                        // 优先用 TS 包解析
                        var (sps, pps) = parseH264CodecDataLimited(inputTsFile)
                        // 兜底：原始字节扫描
                        if (sps == null || pps == null) {
                            Log.w(TAG, "TS 包解析未找到 SPS/PPS，尝试原始字节扫描")
                            val raw = scanRawForSpsPps(inputTsFile)
                            sps = sps ?: raw.first
                            pps = pps ?: raw.second
                        }
                        if (sps != null && pps != null) {
                            Log.i(TAG, "提取到 SPS=${sps.size} bytes, PPS=${pps.size} bytes，注入 codec data")
                            format = injectCodecData(format, sps, pps)
                        } else {
                            Log.e(TAG, "未能从 TS 文件提取 SPS/PPS")
                        }
                    }
                }
                trackMap[i] = muxer.addTrack(format)
            }
            if (!hasVideo) throw Exception("未找到视频轨道")

            muxer.start()
            val buf = ByteBuffer.allocate(MAX_BUFFER_SIZE)
            val info = MediaCodec.BufferInfo()

            for (ti in 0 until trackCount) {
                extractor.selectTrack(ti)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                while (true) {
                    val sz = extractor.readSampleData(buf, 0)
                    if (sz < 0) break
                    info.offset = 0; info.size = sz
                    info.presentationTimeUs = extractor.sampleTime
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(trackMap[ti]!!, buf, info)
                    extractor.advance()
                }
                extractor.unselectTrack(ti)
            }
        } finally {
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    // ===== 方案 3: 纯手写 TS→MP4 muxer（支持 PTS 重写）=====

    /**
     * 手写 TS→MP4 muxer：直接扫描原始字节找 H.264 NAL，手动构建 MP4 容器
     * 完全不依赖 MediaExtractor / mediaserver
     * 支持从 TS 提取 PTS/DTS 时间戳，重写为连续的 MP4 时间线
     */
    private fun convertWithManualMuxer(inputTsFile: File, outputMp4File: File) {
        // 第一遍：扫描原始字节提取 SPS/PPS
        val (sps, pps) = scanRawForSpsPps(inputTsFile)
        if (sps == null || pps == null) {
            throw Exception("手写 muxer: 未能提取 SPS/PPS")
        }
        Log.i(TAG, "手写 muxer: SPS=${sps.size} bytes, PPS=${pps.size} bytes")

        // 从 SPS 解析分辨率
        val (width, height) = parseSpsResolution(sps)
        Log.i(TAG, "手写 muxer: 解析分辨率 ${width}x${height}")

        // 第二遍：扫描原始字节提取视频切片 NAL 和 PTS 到临时文件
        val tempFile = File.createTempFile("annexb_", ".h264")
        try {
            val (frames, idrIndices) = extractRawNalWithPts(inputTsFile, tempFile)
            if (frames.isEmpty()) throw Exception("手写 muxer: 未能提取视频帧")
            Log.i(TAG, "手写 muxer: ${frames.size} 帧, ${idrIndices.size} 个 IDR, Annex B: ${tempFile.length()} bytes")

            writeMp4(tempFile, frames, idrIndices, sps, pps, outputMp4File, width, height)
        } finally {
            tempFile.delete()
        }
    }

    /**
     * 从 SPS 解析视频分辨率
     * 解析 pic_width_in_mbs_minus1 和 pic_height_in_map_units_minus1
     * 考虑帧裁剪（crop_* 字段）
     */
    private fun parseSpsResolution(sps: ByteArray): Pair<Int, Int> {
        try {
            val reader = BitReader(sps)

            // 跳过 forbidden_zero_bit (1) + nal_ref_idc (2) + nal_unit_type (5)
            reader.readBits(8)

            // profile_idc (8)
            val profileIdc = reader.readBits(8)

            // constraint_set0-5_flag (6) + reserved_zero_2bits (2)
            reader.readBits(8)

            // level_idc (8)
            reader.readBits(8)

            // seq_parameter_set_id (ue(v))
            reader.readExpGolomb()

            // 高 profile 额外解析
            if (profileIdc == 100 || profileIdc == 110 || profileIdc == 122 ||
                profileIdc == 244 || profileIdc == 44 || profileIdc == 83 ||
                profileIdc == 86 || profileIdc == 118 || profileIdc == 128) {
                val chromaFormatIdc = reader.readExpGolomb()
                if (chromaFormatIdc == 3) {
                    reader.readBits(1) // separate_colour_plane_flag
                }
                reader.readExpGolomb() // bit_depth_luma_minus8
                reader.readExpGolomb() // bit_depth_chroma_minus8
                reader.readBits(1) // qpprime_y_zero_transform_bypass_flag
                val seqScalingMatrixPresent = reader.readBits(1)
                if (seqScalingMatrixPresent != 0) {
                    val limit = if (chromaFormatIdc != 3) 8 else 12
                    for (i in 0 until limit) {
                        if (reader.readBits(1) != 0) {
                            skipScalingList(reader, if (i < 6) 16 else 64)
                        }
                    }
                }
            }

            // log2_max_frame_num_minus4 (ue(v))
            reader.readExpGolomb()

            // pic_order_cnt_type (ue(v))
            val picOrderCntType = reader.readExpGolomb()
            if (picOrderCntType == 0) {
                reader.readExpGolomb() // log2_max_pic_order_cnt_lsb_minus4
            } else if (picOrderCntType == 1) {
                reader.readBits(1) // delta_pic_order_always_zero_flag
                reader.readSignedExpGolomb() // offset_for_non_ref_pic
                reader.readSignedExpGolomb() // offset_for_top_to_bottom_field
                val numRefFrames = reader.readExpGolomb()
                for (i in 0 until numRefFrames) {
                    reader.readSignedExpGolomb()
                }
            }

            // max_num_ref_frames (ue(v))
            reader.readExpGolomb()

            // gaps_in_frame_num_value_allowed_flag (1)
            reader.readBits(1)

            // pic_width_in_mbs_minus1 (ue(v))
            val picWidthInMbs = reader.readExpGolomb() + 1

            // pic_height_in_map_units_minus1 (ue(v))
            val picHeightInMapUnits = reader.readExpGolomb() + 1

            // frame_mbs_only_flag (1)
            val frameMbsOnly = reader.readBits(1)

            val width = picWidthInMbs * 16
            val height = picHeightInMapUnits * 16 * (2 - frameMbsOnly)

            // 帧裁剪
            var cropLeft = 0; var cropRight = 0; var cropTop = 0; var cropBottom = 0
            val frameCroppingFlag = reader.readBits(1)
            if (frameCroppingFlag != 0) {
                cropLeft = reader.readExpGolomb() * 2
                cropRight = reader.readExpGolomb() * 2
                cropTop = reader.readExpGolomb() * 2
                cropBottom = reader.readExpGolomb() * 2
            }

            val finalWidth = width - cropLeft - cropRight
            val finalHeight = height - cropTop - cropBottom

            return Pair(finalWidth, finalHeight)
        } catch (e: Exception) {
            Log.w(TAG, "解析 SPS 分辨率失败: ${e.message}，使用默认 1920x1080")
            return Pair(1920, 1080)
        }
    }

    private fun skipScalingList(reader: BitReader, size: Int) {
        var lastScale = 8
        var nextScale = 8
        for (i in 0 until size) {
            if (nextScale != 0) {
                val deltaScale = reader.readSignedExpGolomb()
                nextScale = (lastScale + deltaScale + 256) % 256
            }
            lastScale = if (nextScale == 0) lastScale else nextScale
        }
    }

    /**
     * 简单的位读取器，用于解析 H.264 SPS
     */
    private class BitReader(private val data: ByteArray) {
        private var bitOffset = 0

        fun readBits(n: Int): Int {
            var result = 0
            for (i in 0 until n) {
                val byteIndex = bitOffset / 8
                val bitIndex = 7 - (bitOffset % 8)
                if (byteIndex < data.size) {
                    result = (result shl 1) or ((data[byteIndex].toInt() shr bitIndex) and 1)
                }
                bitOffset++
            }
            return result
        }

        fun readExpGolomb(): Int {
            var leadingZeros = 0
            while (readBits(1) == 0 && leadingZeros < 32) {
                leadingZeros++
            }
            if (leadingZeros == 0) return 0
            return (1 shl leadingZeros) - 1 + readBits(leadingZeros)
        }

        fun readSignedExpGolomb(): Int {
            val code = readExpGolomb()
            return if ((code and 1) != 0) (code + 1) / 2 else -(code / 2)
        }
    }

    /**
     * 直接扫描原始字节找 H.264 NAL start code，提取 SPS/PPS
     * 绕过 TS 包解析，适用于任何包含 H.264 数据的文件
     */
    private fun scanRawForSpsPps(file: File): Pair<ByteArray?, ByteArray?> {
        // 诊断：先分析 TS 文件结构
        diagnoseTsFile(file)

        val scanSize = minOf(file.length(), 50 * 1024 * 1024L) // 扫描前 50MB
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var startCodeCount = 0
        var firstStartCodeOffset = -1L
        val nalTypeCounts = mutableMapOf<Int, Int>()

        RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(scanSize.toInt())
            raf.readFully(buf)

            var i = 0
            while (i < buf.size - 4) {
                val startCodeLen = when {
                    buf[i].toInt() == 0 && buf[i+1].toInt() == 0 && buf[i+2].toInt() == 0 &&
                    i + 3 < buf.size && buf[i+3].toInt() == 1 -> 4
                    buf[i].toInt() == 0 && buf[i+1].toInt() == 0 && buf[i+2].toInt() == 1 -> 3
                    else -> { i++; continue }
                }

                val nalStart = i + startCodeLen
                if (nalStart >= buf.size) break

                val nalType = buf[nalStart].toInt() and 0x1F

                if (startCodeCount == 0) {
                    firstStartCodeOffset = i.toLong()
                    Log.d(TAG, "raw scan: 首个 start code at offset $i, NAL type=$nalType")
                }
                startCodeCount++
                nalTypeCounts[nalType] = (nalTypeCounts[nalType] ?: 0) + 1

                var nalEnd = buf.size
                for (j in nalStart + 1 until buf.size - 3) {
                    if (buf[j].toInt() == 0 && buf[j+1].toInt() == 0) {
                        if (buf[j+2].toInt() == 1 ||
                            (j + 3 < buf.size && buf[j+2].toInt() == 0 && buf[j+3].toInt() == 1)) {
                            nalEnd = j
                            break
                        }
                    }
                }

                when (nalType) {
                    7 -> if (sps == null) {
                        sps = buf.copyOfRange(nalStart, nalEnd)
                        Log.d(TAG, "raw scan: 找到 SPS ${sps!!.size} bytes at offset $nalStart")
                    }
                    8 -> if (pps == null) {
                        pps = buf.copyOfRange(nalStart, nalEnd)
                        Log.d(TAG, "raw scan: 找到 PPS ${pps!!.size} bytes at offset $nalStart")
                    }
                }

                if (sps != null && pps != null) break
                i = nalEnd
            }
        }

        Log.i(TAG, "raw scan 完成: 扫描 ${scanSize} bytes, 找到 $startCodeCount 个 start code")
        Log.i(TAG, "raw scan: NAL 类型分布 = $nalTypeCounts")
        if (firstStartCodeOffset >= 0) {
            Log.i(TAG, "raw scan: 首个 start code at offset $firstStartCodeOffset")
        } else {
            Log.w(TAG, "raw scan: 在前 ${scanSize} bytes 中未找到任何 start code!")
        }

        return Pair(sps, pps)
    }

    /**
     * 诊断 TS 文件结构：检查同步字节、PID 分布、可能的视频编码
     */
    private fun diagnoseTsFile(file: File) {
        Log.i(TAG, "===== TS 文件诊断 =====")
        Log.i(TAG, "文件大小: ${file.length()} bytes (${file.length() / 1024 / 1024} MB)")

        RandomAccessFile(file, "r").use { raf ->
            // 检查前 1000 个字节的前几个字节
            val header = ByteArray(376) // 2 个 TS 包
            raf.readFully(header)

            // 检查 TS 同步字节
            val hasSyncByte = header[0].toInt() and 0xFF == 0x47
            Log.d(TAG, "首字节: 0x${(header[0].toInt() and 0xFF).toString(16)}, TS同步字节: $hasSyncByte")

            if (hasSyncByte) {
                // 检查连续 TS 包的同步
                var syncOk = 0
                var syncFail = 0
                val checkCount = 100
                val packet = ByteArray(188)
                raf.seek(0)
                for (p in 0 until checkCount) {
                    if (raf.read(packet) != 188) break
                    if (packet[0].toInt() and 0xFF == 0x47) syncOk++ else syncFail++
                }
                Log.d(TAG, "TS 同步检查 (前 $checkCount 包): 同步=$syncOk, 失败=$syncFail")

                // 分析前 10000 个 TS 包的 PID 分布
                val pidCounts = mutableMapOf<Int, Int>()
                val pesPids = mutableSetOf<Int>()
                raf.seek(0)
                for (p in 0 until 10000) {
                    if (raf.read(packet) != 188) break
                    if (packet[0].toInt() and 0xFF != 0x47) continue
                    val pid = ((packet[1].toInt() and 0x1F) shl 8) or (packet[2].toInt() and 0xFF)
                    pidCounts[pid] = (pidCounts[pid] ?: 0) + 1

                    val pusi = (packet[1].toInt() and 0x40) != 0
                    if (pusi && pid > 2 && pid != 0x1FFF) pesPids.add(pid)
                }
                Log.d(TAG, "PID 分布 (前 10000 包): $pidCounts")
                Log.d(TAG, "有 PUSI 的 PID: $pesPids")

                // 检查 PAT/PMT 来确定节目信息
                raf.seek(0)
                var pmtPid = -1
                for (p in 0 until 100) {
                    if (raf.read(packet) != 188) break
                    if (packet[0].toInt() and 0xFF != 0x47) continue
                    val pid = ((packet[1].toInt() and 0x1F) shl 8) or (packet[2].toInt() and 0xFF)
                    if (pid == 0) {
                        // PAT
                        val payloadStart = if ((packet[1].toInt() and 0x40) != 0) {
                            4 + 1 + (packet[4].toInt() and 0xFF)
                        } else continue
                        if (payloadStart + 11 < 188) {
                            // 简单解析 PAT 找 PMT PID
                            val sectionLen = ((packet[payloadStart + 1].toInt() and 0x0F) shl 8) or
                                    (packet[payloadStart + 2].toInt() and 0xFF)
                            val entries = (sectionLen - 9) / 4
                            for (e in 0 until entries) {
                                val off = payloadStart + 8 + e * 4
                                if (off + 3 < 188) {
                                    val progNum = ((packet[off].toInt() and 0xFF) shl 8) or
                                            (packet[off + 1].toInt() and 0xFF)
                                    val pmPid = ((packet[off + 2].toInt() and 0x1F) shl 8) or
                                            (packet[off + 3].toInt() and 0xFF)
                                    if (progNum != 0) {
                                        pmtPid = pmPid
                                        Log.d(TAG, "PAT: 节目 $progNum, PMT PID=$pmtPid")
                                    }
                                }
                            }
                        }
                    }
                    if (pmtPid > 0 && pid == pmtPid) {
                        // PMT - 找视频流类型
                        val payloadStart = if ((packet[1].toInt() and 0x40) != 0) {
                            4 + 1 + (packet[4].toInt() and 0xFF)
                        } else continue
                        if (payloadStart + 12 < 188) {
                            val sectionLen = ((packet[payloadStart + 1].toInt() and 0x0F) shl 8) or
                                    (packet[payloadStart + 2].toInt() and 0xFF)
                            val progInfoLen = ((packet[payloadStart + 10].toInt() and 0x0F) shl 8) or
                                    (packet[payloadStart + 11].toInt() and 0xFF)
                            var off = payloadStart + 12 + progInfoLen
                            while (off + 5 < payloadStart + 3 + sectionLen && off + 5 < 188) {
                                val streamType = packet[off].toInt() and 0xFF
                                val esPid = ((packet[off + 1].toInt() and 0x1F) shl 8) or
                                        (packet[off + 2].toInt() and 0xFF)
                                val esInfoLen = ((packet[off + 3].toInt() and 0x0F) shl 8) or
                                        (packet[off + 4].toInt() and 0xFF)
                                val typeName = when (streamType) {
                                    0x01 -> "MPEG-1 Video"
                                    0x02 -> "MPEG-2 Video"
                                    0x03 -> "MPEG-1 Audio"
                                    0x04 -> "MPEG-2 Audio"
                                    0x0F -> "AAC Audio"
                                    0x10 -> "MPEG-4 Video"
                                    0x1B -> "H.264 Video"
                                    0x24 -> "H.265/HEVC Video"
                                    0x81 -> "AC-3 Audio"
                                    0x87 -> "E-AC-3 Audio"
                                    else -> "未知(0x${streamType.toString(16)})"
                                }
                                Log.d(TAG, "PMT: stream_type=0x${streamType.toString(16)} ($typeName), PID=$esPid")
                                off += 5 + esInfoLen
                            }
                        }
                        break
                    }
                }
            } else {
                // 不是标准 TS，检查是否是其他格式
                Log.w(TAG, "文件不以 TS 同步字节 0x47 开头")
                Log.d(TAG, "前 32 字节 hex: ${header.take(32).joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }}")

                // 检查是否是 ftyp (MP4)
                if (header[4].toInt().toChar() == 'f' && header[5].toInt().toChar() == 't' &&
                    header[6].toInt().toChar() == 'y' && header[7].toInt().toChar() == 'p') {
                    Log.w(TAG, "文件看起来是 MP4 容器，不是 TS!")
                }
                // 检查是否是 FLV
                if (header[0].toInt().toChar() == 'F' && header[1].toInt().toChar() == 'L' &&
                    header[2].toInt().toChar() == 'V') {
                    Log.w(TAG, "文件看起来是 FLV 容器!")
                }
            }
        }
        Log.i(TAG, "===== TS 诊断结束 =====")
    }

    /**
     * 两遍扫描提取 H.264 视频切片 NAL 单元并写入 Annex B 文件
     * 同时从 TS 包中提取 PTS 时间戳
     * 只提取 IDR 帧 (type 5) 和非 IDR 帧 (type 1)，跳过 SPS/PPS/SEI 等
     * 返回 Pair<frames, idrIndices>，idrIndices 记录哪些帧是 IDR（用于构建 stss）
     */
    private fun extractRawNalWithPts(inputFile: File, outputFile: File): Pair<List<FrameInfo>, List<Int>> {
        // 第一遍：扫描 TS 包，提取 PES 中的 PTS 和 NAL 位置
        val nalEntries = mutableListOf<NalEntry>()
        val videoPidPtsMap = mutableMapOf<Int, Long>() // PID -> 最近的 PTS
        var videoPid = -1

        RandomAccessFile(inputFile, "r").use { raf ->
            val packet = ByteArray(188)
            val fileSize = inputFile.length()

            while (raf.filePointer < fileSize) {
                if (raf.read(packet) != 188) break
                if (packet[0].toInt() and 0xFF != 0x47) continue

                val pid = ((packet[1].toInt() and 0x1F) shl 8) or (packet[2].toInt() and 0xFF)
                if (pid <= 2 || pid == 0x1FFF) continue

                val pusi = (packet[1].toInt() and 0x40) != 0
                val afc = (packet[3].toInt() and 0x30) shr 4

                var payloadOffset = 4
                if (afc == 0x02) continue
                if (afc == 0x03) {
                    payloadOffset = 5 + (packet[4].toInt() and 0xFF)
                }
                if (payloadOffset >= 188) continue

                // 有 PUSI 标记的包，检查是否是 PES 头
                if (pusi && payloadOffset + 9 < 188) {
                    val pts = extractPtsFromPes(packet, payloadOffset)
                    if (pts != null) {
                        videoPidPtsMap[pid] = pts
                        if (videoPid == -1) videoPid = pid
                    }
                }

                // 提取 NAL start code（视频 PID 的数据）
                if (videoPid != -1 && pid == videoPid) {
                    val currentPts = videoPidPtsMap[pid]
                    scanNalInPayload(packet, payloadOffset, 188, currentPts, raf.filePointer - 188, nalEntries)
                }
            }
        }

        // 只保留视频切片 NAL（type 1 = non-IDR, type 5 = IDR）
        val videoNals = nalEntries.filter { it.type == 1 || it.type == 5 }
        if (videoNals.isEmpty()) return Pair(emptyList(), emptyList())

        // 第二遍：逐个读取视频 NAL 写入文件
        val frames = mutableListOf<FrameInfo>()
        val idrIndices = mutableListOf<Int>()
        val startCode = byteArrayOf(0x00, 0x00, 0x00, 0x01)

        RandomAccessFile(inputFile, "r").use { raf ->
            outputFile.outputStream().use { output ->
                for (idx in videoNals.indices) {
                    val nalStart = videoNals[idx].offset
                    // 找下一个 NAL 的起始位置
                    val nextNalOffset = findNextNalOffset(nalEntries, nalStart, inputFile.length())

                    val nalSize = nextNalOffset - nalStart
                    if (nalSize > 0 && nalSize < 10 * 1024 * 1024) {
                        output.write(startCode)
                        raf.seek(nalStart)
                        val nalData = ByteArray(nalSize.toInt())
                        raf.readFully(nalData)
                        output.write(nalData)

                        val pts = videoNals[idx].pts
                        frames.add(FrameInfo(startCode.size + nalSize, pts))
                        if (videoNals[idx].type == 5) {
                            idrIndices.add(frames.size) // 1-based index for stss
                        }
                    }
                }
            }
        }

        return Pair(frames, idrIndices)
    }

    /**
     * 从 TS 包的有效负载中扫描 NAL start code
     */
    private fun scanNalInPayload(
        packet: ByteArray, start: Int, end: Int,
        pts: Long?, packetFileOffset: Long, nalEntries: MutableList<NalEntry>
    ) {
        var i = start
        while (i < end - 3) {
            val isStartCode = when {
                packet[i].toInt() == 0 && packet[i+1].toInt() == 0 && packet[i+2].toInt() == 0 &&
                i + 3 < end && packet[i+3].toInt() == 1 -> true
                packet[i].toInt() == 0 && packet[i+1].toInt() == 0 && packet[i+2].toInt() == 1 -> true
                else -> false
            }

            if (isStartCode) {
                val scLen = if (i + 3 < end && packet[i+2].toInt() == 0) 4 else 3
                val nalStart = i + scLen
                if (nalStart < end) {
                    val nalType = packet[nalStart].toInt() and 0x1F
                    // 记录 NAL 数据起始的全局文件偏移
                    nalEntries.add(NalEntry(packetFileOffset + nalStart, nalType, pts))
                }
                i += scLen
            } else {
                i++
            }
        }
    }

    /**
     * 从 PES 头部提取 PTS
     * PES 头部格式：0x000001 + stream_id + packet_length + flags + PTS
     */
    private fun extractPtsFromPes(packet: ByteArray, payloadOffset: Int): Long? {
        if (payloadOffset + 9 >= packet.size) return null

        // 检查 PES start code: 0x000001
        if (packet[payloadOffset].toInt() != 0 || packet[payloadOffset+1].toInt() != 0 ||
            packet[payloadOffset+2].toInt() != 1) return null

        val streamId = packet[payloadOffset + 3].toInt() and 0xFF
        // 只处理视频流 (0xE0-0xEF)
        if (streamId < 0xE0 || streamId > 0xEF) return null

        // PTS_DTS_flags: 第 7 字节的高 2 位
        val ptsDtsFlags = (packet[payloadOffset + 7].toInt() and 0xC0) shr 6
        if (ptsDtsFlags == 0) return null // 无 PTS

        val ptsStart = payloadOffset + 9
        if (ptsStart + 5 > packet.size) return null

        // 提取 33 位 PTS（跨 5 字节）
        val pts = ((packet[ptsStart].toLong() and 0x0E) shl 29) or
                  ((packet[ptsStart + 1].toLong() and 0xFF) shl 22) or
                  ((packet[ptsStart + 2].toLong() and 0xFE) shl 14) or
                  ((packet[ptsStart + 3].toLong() and 0xFF) shl 7) or
                  ((packet[ptsStart + 4].toLong() and 0xFE) shr 1)
        return pts
    }

    /**
     * 在 nalEntries 中找到给定 offset 之后的下一个 NAL 起始位置
     * 用于确定当前 NAL 的数据范围
     */
    private fun findNextNalOffset(nalEntries: List<NalEntry>, currentOffset: Long, fileSize: Long): Long {
        for (entry in nalEntries) {
            if (entry.offset > currentOffset) return entry.offset
        }
        return fileSize
    }

    /**
     * 手写 MP4 文件：ftyp + moov + mdat
     * 使用从 TS 提取的真实 PTS 时间戳
     */
    private fun writeMp4(
        annexBFile: File, frames: List<FrameInfo>, idrIndices: List<Int>,
        sps: ByteArray, pps: ByteArray, outputMp4File: File,
        width: Int = 1920, height: Int = 1080
    ) {
        val timescale = DEFAULT_TIMESCALE

        // 计算帧数据总大小
        val mdatDataSize = frames.sumOf { it.size }
        val mdatSize = 8 + mdatDataSize

        // 构建 moov 数据（使用 PTS 时间戳）
        val moovData = buildMoov(frames, idrIndices, sps, pps, timescale, mdatSize, width, height)

        // 构建 ftyp 数据
        val ftypData = buildFtyp()

        // 写入文件
        RandomAccessFile(outputMp4File, "rw").use { raf ->
            // ftyp
            raf.write(ftypData)

            // moov
            raf.write(moovData)

            // mdat
            if (mdatSize > Int.MAX_VALUE) {
                // 扩展大小：size=1, 后跟 64 位实际大小
                raf.write(intToBytes(1))
                raf.write("mdat".toByteArray())
                raf.write(longToBytes(mdatSize))
            } else {
                raf.write(intToBytes(mdatSize.toInt()))
                raf.write("mdat".toByteArray())
            }

            // 写入帧数据
            val buffer = ByteArray(64 * 1024)
            FileInputStream(annexBFile).use { input ->
                var remaining = mdatDataSize
                while (remaining > 0) {
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = input.read(buffer, 0, toRead)
                    if (read <= 0) break
                    raf.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
    }

    private fun buildFtyp(): ByteArray {
        val brand = "isom"
        val compatibleBrands = listOf("isom", "iso2", "avc1", "mp41")
        val size = 8 + 4 + 4 + compatibleBrands.size * 4
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(size)
        buf.put("ftyp".toByteArray())
        buf.put(brand.toByteArray())
        buf.putInt(0x200) // minor version
        for (cb in compatibleBrands) buf.put(cb.toByteArray())
        return buf.array()
    }

    private fun buildMoov(
        frames: List<FrameInfo>, idrIndices: List<Int>, sps: ByteArray, pps: ByteArray,
        timescale: Long, mdatOffset: Long, width: Int, height: Int
    ): ByteArray {
        val stts = buildSttsFromPts(frames, timescale)
        val stsz = buildStsz(frames)
        val stco = buildStco(frames, mdatOffset)
        val stss = buildStss(idrIndices)
        val stsc = buildStsc(frames.size)
        val stsd = buildStsd(sps, pps, width, height)

        val stblData = buildBox("stbl", concat(stsd, stts, stss, stsc, stsz, stco))
        val dinfData = buildBox("dinf", buildDinf())
        val vmhdData = buildVmhd()
        val minfData = buildBox("minf", concat(vmhdData, dinfData, stblData))

        val hdlrData = buildHdlr()
        val totalDuration = calculateTotalDuration(frames, timescale)
        val mdhdData = buildMdhd(totalDuration, timescale)
        val mdiaData = buildBox("mdia", concat(mdhdData, hdlrData, minfData))

        val tkhdData = buildTkhd(totalDuration, width, height)
        val trakData = buildBox("trak", concat(tkhdData, mdiaData))

        val mvhdData = buildMvhd(totalDuration, timescale)
        return buildBox("moov", concat(mvhdData, trakData))
    }

    /**
     * 使用 PTS 计算总时长
     */
    private fun calculateTotalDuration(frames: List<FrameInfo>, timescale: Long): Long {
        if (frames.isEmpty()) return 0L
        val ptsList = frames.mapNotNull { it.pts }
        if (ptsList.isEmpty()) {
            // 无 PTS 信息，使用默认帧时长
            return frames.size * DEFAULT_FRAME_DURATION
        }
        // 总时长 = 最后一个 PTS - 第一个 PTS + 最后一帧的估算时长
        val lastFrameDuration = if (frames.size >= 2) {
            val lastPts = frames.last().pts ?: DEFAULT_FRAME_DURATION
            val prevPts = frames[frames.size - 2].pts ?: 0L
            if (lastPts > prevPts) lastPts - prevPts else DEFAULT_FRAME_DURATION
        } else {
            DEFAULT_FRAME_DURATION
        }
        return (ptsList.last() - ptsList.first()) + lastFrameDuration
    }

    private fun buildMvhd(duration: Long, timescale: Long): ByteArray {
        val buf = ByteBuffer.allocate(108).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(108); buf.put("mvhd".toByteArray())
        buf.put(0) // version
        buf.put(byteArrayOf(0, 0, 0)) // flags
        buf.putInt(0) // creation time
        buf.putInt(0) // modification time
        buf.putInt(timescale.toInt())
        buf.putInt(duration.toInt())
        buf.putInt(0x00010000) // rate (1.0)
        buf.putShort(0x0100) // volume (1.0)
        buf.put(ByteArray(10)) // reserved
        // matrix (identity)
        buf.putInt(0x00010000); buf.putInt(0); buf.putInt(0)
        buf.putInt(0); buf.putInt(0x00010000); buf.putInt(0)
        buf.putInt(0); buf.putInt(0); buf.putInt(0x40000000)
        buf.put(ByteArray(24)) // pre-defined
        buf.putInt(0xFFFFFFFF.toInt()) // next track ID
        return buf.array()
    }

    private fun buildTkhd(duration: Long, width: Int, height: Int): ByteArray {
        val buf = ByteBuffer.allocate(92).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(92); buf.put("tkhd".toByteArray())
        buf.put(0) // version
        buf.put(byteArrayOf(0, 0, 3)) // flags (enabled + in movie)
        buf.putInt(0); buf.putInt(0) // creation/modification time
        buf.putInt(1) // track ID
        buf.putInt(0) // reserved
        buf.putInt(duration.toInt())
        buf.put(ByteArray(8)) // reserved
        buf.putShort(0) // layer
        buf.putShort(0) // alternate group
        buf.putShort(0x0100) // volume
        buf.putShort(0) // reserved
        // matrix (identity)
        buf.putInt(0x00010000); buf.putInt(0); buf.putInt(0)
        buf.putInt(0); buf.putInt(0x00010000); buf.putInt(0)
        buf.putInt(0); buf.putInt(0); buf.putInt(0x40000000)
        buf.putInt(width shl 16) // width (16.16 fixed point)
        buf.putInt(height shl 16) // height (16.16 fixed point)
        return buf.array()
    }

    private fun buildMdhd(duration: Long, timescale: Long): ByteArray {
        val buf = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(32); buf.put("mdhd".toByteArray())
        buf.put(0); buf.put(byteArrayOf(0, 0, 0))
        buf.putInt(0); buf.putInt(0)
        buf.putInt(timescale.toInt())
        buf.putInt(duration.toInt())
        buf.putShort(0x55C4) // language (und)
        buf.putShort(0) // quality
        return buf.array()
    }

    private fun buildHdlr(): ByteArray {
        val name = "VideoHandler"
        val size = 8 + 4 + 4 + 4 + 12 + name.length + 1
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(size); buf.put("hdlr".toByteArray())
        buf.put(0); buf.put(byteArrayOf(0, 0, 0))
        buf.put(ByteArray(4)) // pre-defined
        buf.put("vide".toByteArray()) // handler type
        buf.put(ByteArray(12)) // reserved
        buf.put(name.toByteArray())
        buf.put(0) // null terminator
        return buf.array()
    }

    private fun buildVmhd(): ByteArray {
        val buf = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(20); buf.put("vmhd".toByteArray())
        buf.put(0); buf.put(byteArrayOf(0, 0, 1)) // version + flags
        buf.putShort(0) // graphics mode
        buf.put(ByteArray(6)) // opcolor
        return buf.array()
    }

    private fun buildDinf(): ByteArray {
        val urlData = buildBox("url ", byteArrayOf(0, 0, 0, 1)) // self-contained flag
        val drefData = buildBox("dref", concat(
            byteArrayOf(0, 0, 0, 0), // version + flags
            intToBytes(1), // entry count
            urlData
        ))
        return drefData
    }

    private fun buildStsd(sps: ByteArray, pps: ByteArray, width: Int, height: Int): ByteArray {
        // avc1 sample entry
        val avcCData = buildAvcC(sps, pps)
        val sampleEntrySize = 8 + 78 + avcCData.size
        val sampleEntry = ByteBuffer.allocate(sampleEntrySize).order(ByteOrder.BIG_ENDIAN)
        sampleEntry.putInt(sampleEntrySize)
        sampleEntry.put("avc1".toByteArray())
        sampleEntry.put(ByteArray(6)) // reserved
        sampleEntry.putShort(1) // data reference index
        sampleEntry.put(ByteArray(16)) // pre-defined + reserved
        sampleEntry.putShort(width.toShort()) // width
        sampleEntry.putShort(height.toShort()) // height
        sampleEntry.putInt(0x00480000) // horiz resolution (72.0)
        sampleEntry.putInt(0x00480000) // vert resolution (72.0)
        sampleEntry.putInt(0) // reserved
        sampleEntry.putShort(1) // frame count
        sampleEntry.put(ByteArray(32)) // compressor name
        sampleEntry.putShort(0x0018) // depth
        sampleEntry.putShort((-1).toShort()) // pre-defined
        sampleEntry.put(avcCData)

        val stsdSize = 8 + 4 + sampleEntrySize
        val buf = ByteBuffer.allocate(stsdSize).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(stsdSize); buf.put("stsd".toByteArray())
        buf.put(0); buf.put(byteArrayOf(0, 0, 0))
        buf.putInt(1) // entry count
        buf.put(sampleEntry.array())
        return buf.array()
    }

    private fun buildAvcC(sps: ByteArray, pps: ByteArray): ByteArray {
        val size = 11 + sps.size + pps.size
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(size)
        buf.put("avcC".toByteArray())
        buf.put(1) // configurationVersion
        buf.put(sps[1]) // profile
        buf.put(sps[2]) // compatibility
        buf.put(sps[3]) // level
        buf.put(0xFF.toByte()) // lengthSizeMinus1 = 3
        buf.put(0xE1.toByte()) // numOfSPS = 1
        buf.putShort(sps.size.toShort())
        buf.put(sps)
        buf.put(1) // numOfPPS
        buf.putShort(pps.size.toShort())
        buf.put(pps)
        return buf.array()
    }

    /**
     * 使用 PTS 差值构建可变帧时长的 stts 表
     * 支持帧率变化和 PTS 不连续的情况
     */
    private fun buildSttsFromPts(frames: List<FrameInfo>, timescale: Long): ByteArray {
        // 提取有效 PTS
        val ptsList = frames.mapNotNull { it.pts }

        if (ptsList.size < 2) {
            // PTS 信息不足，回退到默认帧时长
            Log.w(TAG, "PTS 信息不足（${ptsList.size} 个），使用默认 30fps")
            return buildStts(frames.size, DEFAULT_FRAME_DURATION)
        }

        // 计算相邻帧的 PTS 差值作为帧时长
        val durations = mutableListOf<Long>()
        for (i in 1 until ptsList.size) {
            var diff = ptsList[i] - ptsList[i - 1]
            // 过滤异常值（负值或过大的跳跃）
            if (diff <= 0 || diff > timescale) { // 单帧不应超过 1 秒
                diff = DEFAULT_FRAME_DURATION
            }
            durations.add(diff)
        }

        // 统计连续相同 duration 的 run（压缩 stts 表）
        val entries = mutableListOf<Pair<Long, Int>>() // (duration, count)
        var currentDuration = durations.first()
        var currentCount = 1
        for (i in 1 until durations.size) {
            if (durations[i] == currentDuration) {
                currentCount++
            } else {
                entries.add(Pair(currentDuration, currentCount))
                currentDuration = durations[i]
                currentCount = 1
            }
        }
        entries.add(Pair(currentDuration, currentCount))

        Log.i(TAG, "stts 表: ${entries.size} 个条目, 帧数=${frames.size}, " +
                "PTS 范围=[${ptsList.first()}, ${ptsList.last()}]")

        // 构建 stts box
        val size = 16 + entries.size * 8
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(size); buf.put("stts".toByteArray())
        buf.put(0); buf.put(byteArrayOf(0, 0, 0))
        buf.putInt(entries.size)
        for ((duration, count) in entries) {
            buf.putInt(count)
            buf.putInt(duration.toInt())
        }
        return buf.array()
    }

    /**
     * 兼容旧接口：使用固定帧时长构建 stts 表
     */
    private fun buildStts(frameCount: Int, frameDuration: Long): ByteArray {
        val buf = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(16); buf.put("stts".toByteArray())
        buf.put(0); buf.put(byteArrayOf(0, 0, 0))
        buf.putInt(1) // entry count
        buf.putInt(frameCount)
        buf.putInt(frameDuration.toInt())
        return buf.array()
    }

    private fun buildStsz(frames: List<FrameInfo>): ByteArray {
        val size = 12 + 4 + 4 + frames.size * 4
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(size); buf.put("stsz".toByteArray())
        buf.put(0); buf.put(byteArrayOf(0, 0, 0))
        buf.putInt(0) // sample size (0 = individual sizes)
        buf.putInt(frames.size)
        for (f in frames) buf.putInt(f.size.toInt())
        return buf.array()
    }

    private fun buildStco(frames: List<FrameInfo>, mdatOffset: Long): ByteArray {
        val size = 12 + 4 + frames.size * 4
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(size); buf.put("stco".toByteArray())
        buf.put(0); buf.put(byteArrayOf(0, 0, 0))
        buf.putInt(frames.size)
        var offset = mdatOffset + 8 // skip mdat header
        for (f in frames) {
            buf.putInt(offset.toInt())
            offset += f.size
        }
        return buf.array()
    }

    private fun buildStss(idrIndices: List<Int>): ByteArray {
        // idrIndices 是 1-based 的 IDR 帧索引
        val size = 12 + 4 + idrIndices.size * 4
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(size); buf.put("stss".toByteArray())
        buf.put(0); buf.put(byteArrayOf(0, 0, 0))
        buf.putInt(idrIndices.size)
        for (idx in idrIndices) buf.putInt(idx)
        return buf.array()
    }

    private fun buildStsc(frameCount: Int): ByteArray {
        val buf = ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(28); buf.put("stsc".toByteArray())
        buf.put(0); buf.put(byteArrayOf(0, 0, 0))
        buf.putInt(1) // entry count
        buf.putInt(1) // first chunk
        buf.putInt(frameCount) // samples per chunk
        buf.putInt(1) // sample description index
        return buf.array()
    }

    private fun buildBox(type: String, data: ByteArray): ByteArray {
        val size = 8 + data.size
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(size)
        buf.put(type.toByteArray())
        buf.put(data)
        return buf.array()
    }

    private fun concat(vararg arrays: ByteArray): ByteArray {
        val totalSize = arrays.sumOf { it.size }
        val result = ByteArray(totalSize)
        var offset = 0
        for (arr in arrays) {
            arr.copyInto(result, offset)
            offset += arr.size
        }
        return result
    }

    private fun intToBytes(v: Int): ByteArray {
        return byteArrayOf(
            (v shr 24).toByte(), (v shr 16).toByte(),
            (v shr 8).toByte(), v.toByte()
        )
    }

    private fun longToBytes(v: Long): ByteArray {
        return byteArrayOf(
            (v shr 56).toByte(), (v shr 48).toByte(),
            (v shr 40).toByte(), (v shr 32).toByte(),
            (v shr 24).toByte(), (v shr 16).toByte(),
            (v shr 8).toByte(), v.toByte()
        )
    }

    private fun injectCodecData(format: MediaFormat, sps: ByteArray, pps: ByteArray): MediaFormat {
        val csd0 = ByteArray(4 + sps.size)
        csd0[0] = 0x00; csd0[1] = 0x00; csd0[2] = 0x00; csd0[3] = 0x01
        sps.copyInto(csd0, 4)
        val csd1 = ByteArray(4 + pps.size)
        csd1[0] = 0x00; csd1[1] = 0x00; csd1[2] = 0x00; csd1[3] = 0x01
        pps.copyInto(csd1, 4)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
        format.setByteBuffer("csd-1", ByteBuffer.wrap(csd1))
        Log.i(TAG, "injectCodecData: csd-0=${csd0.size} bytes, csd-1=${csd1.size} bytes, profile=${sps[1].toInt() and 0xFF}, level=${sps[3].toInt() and 0xFF}")
        return format
    }

    private fun parseH264CodecDataLimited(file: File): Pair<ByteArray?, ByteArray?> {
        val maxScan = minOf(file.length(), 50 * 1024 * 1024L) // 扫描前 50MB
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        val pesBuffers = mutableMapOf<Int, java.io.ByteArrayOutputStream>()
        var videoPid = -1

        RandomAccessFile(file, "r").use { raf ->
            val packet = ByteArray(188)
            var syncOffset = 0L
            var bytesRead = 0L

            while (bytesRead < maxScan && raf.read(packet) == 188) {
                bytesRead += 188
                if (packet[0].toInt() and 0xFF != 0x47) {
                    syncOffset++; raf.seek(syncOffset); bytesRead = syncOffset; continue
                }
                syncOffset += 188

                val pid = ((packet[1].toInt() and 0x1F) shl 8) or (packet[2].toInt() and 0xFF)
                val pusi = (packet[1].toInt() and 0x40) != 0
                val ac = (packet[3].toInt() and 0x30) shr 4
                if (pid <= 2 || pid == 0x1FFF) continue
                var po = 4
                if (ac == 0x02) continue
                if (ac == 0x03) po = 5 + (packet[4].toInt() and 0xFF)
                if (po >= 188) continue

                if (videoPid == -1) {
                    if (pusi) {
                        for ((pk, buf) in pesBuffers) {
                            if (buf.size() > 100) {
                                val r = parsePesForH264(buf.toByteArray())
                                if (r.first != null) { videoPid = pk; sps = r.first; pps = r.second; break }
                            }
                        }
                        if (videoPid != -1) break
                    }
                    val buf = pesBuffers.getOrPut(pid) { java.io.ByteArrayOutputStream() }
                    buf.write(packet, po, 188 - po)
                    if (buf.size() > 2 * 1024 * 1024) buf.reset()
                } else if (pid == videoPid) {
                    val buf = pesBuffers.getOrPut(pid) { java.io.ByteArrayOutputStream() }
                    if (pusi && buf.size() > 0) {
                        val r = parsePesForH264(buf.toByteArray())
                        if (r.first != null && sps == null) sps = r.first
                        if (r.second != null && pps == null) pps = r.second
                        buf.reset()
                        if (sps != null && pps != null) return Pair(sps, pps)
                    }
                    buf.write(packet, po, 188 - po)
                    if (buf.size() > 5 * 1024 * 1024) buf.reset()
                }
            }
            for ((_, buf) in pesBuffers) {
                if (buf.size() > 100 && (sps == null || pps == null)) {
                    val r = parsePesForH264(buf.toByteArray())
                    if (r.first != null && sps == null) sps = r.first
                    if (r.second != null && pps == null) pps = r.second
                }
            }
        }
        return Pair(sps, pps)
    }

    private fun parsePesForH264(data: ByteArray): Pair<ByteArray?, ByteArray?> {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var i = 0
        while (i < data.size - 4) {
            val scl = when {
                i + 3 < data.size && data[i].toInt() == 0 && data[i+1].toInt() == 0 && data[i+2].toInt() == 0 && data[i+3].toInt() == 1 -> 4
                i + 2 < data.size && data[i].toInt() == 0 && data[i+1].toInt() == 0 && data[i+2].toInt() == 1 -> 3
                else -> { i++; continue }
            }
            val ns = i + scl
            if (ns >= data.size) break
            val nt = data[ns].toInt() and 0x1F
            var ne = data.size
            for (j in ns + 1 until data.size - 3) {
                if (data[j].toInt() == 0 && data[j+1].toInt() == 0) {
                    if (data[j+2].toInt() == 1 || (j+3 < data.size && data[j+2].toInt() == 0 && data[j+3].toInt() == 1)) {
                        ne = j; break
                    }
                }
            }
            val nal = data.copyOfRange(ns, ne)
            when (nt) {
                7 -> if (sps == null) sps = nal
                8 -> if (pps == null) pps = nal
            }
            if (sps != null && pps != null) break
            i = ne
        }
        return Pair(sps, pps)
    }
}

/**
 * 帧信息：大小 + PTS 时间戳
 */
private data class FrameInfo(val size: Long, val pts: Long? = null)

/**
 * NAL 条目：偏移 + 类型 + PTS
 */
private data class NalEntry(val offset: Long, val type: Int, val pts: Long? = null)
