package com.saltfishlen.vtt2lrc

import java.util.regex.Pattern

object VttUtils {

    // 扩展名列表：包含常见音视频与字幕格式
    private val KNOWN_EXTENSIONS = listOf(
        // Audio
        ".mp3", ".wav", ".aac", ".flac", ".ogg", ".wma", ".m4a", ".opus",
        ".aiff", ".au", ".ra", ".ac3", ".dts", ".amr", ".awb",
        // Video
        ".mp4", ".avi", ".mkv", ".flv", ".mov", ".wmv", ".webm", ".m4v",
        ".3gp", ".asf", ".rm", ".rmvb", ".vob", ".ogv", ".dv", ".ts",
        // Subtitle (新增)
        ".vtt", ".srt", ".sub", ".sbv", ".ass", ".ssa",
        ".webvtt", ".ttml", ".dfxp", ".smi", ".sami"
    )

    // 时间戳行，匹配 "开始 --> 结束"
    private val TIME_PATTERN = Pattern.compile(
        "(?:(\\d{1,2}):)?(\\d{2}):(\\d{2})\\.(\\d{3})\\s+-->\\s+(?:(\\d{1,2}):)?(\\d{2}):(\\d{2})\\.(\\d{3})"
    )

    /**
     * 核心转换函数
     */
    fun convertToLrc(vttContent: String, fileName: String): String {
        val lines = vttContent.lines()
        val lrcLines = StringBuilder()

        // 头部元数据
        lrcLines.append("[ti:${fileName}]\n")

        var currentStartTime: String? = null
        val currentSubtitle = StringBuilder()

        for (line in lines) {
            val trimLine = line.trim()
            // 跳过空行、WEBVTT头、NOTE注释
            if (trimLine.isEmpty() || trimLine == "WEBVTT" || trimLine.startsWith("NOTE")) continue

            // 跳过纯数字索引行
            if (trimLine.matches(Regex("^\\d+$"))) continue

            val matcher = TIME_PATTERN.matcher(trimLine)
            if (matcher.find()) {
                // 如果之前有缓存的字幕，先写入上一段
                if (currentStartTime != null && currentSubtitle.isNotEmpty()) {
                    lrcLines.append(currentStartTime).append(currentSubtitle.toString()).append("\n")
                }

                // 重置当前段落
                currentSubtitle.clear()

                // 解析开始时间 (Group 1-4)
                val hoursStr = matcher.group(1)
                val minStr = matcher.group(2)
                val secStr = matcher.group(3)
                val msStr = matcher.group(4)

                val minutes = (hoursStr?.toInt() ?: 0) * 60 + (minStr?.toInt() ?: 0)
                val seconds = secStr?.toInt() ?: 0

                // 毫秒处理：取前两位（centiseconds）
                val centiSeconds = (msStr?.toInt() ?: 0) / 10

                // 格式化为 LRC 标准 [MM:SS.xx]
                currentStartTime = "[%02d:%02d.%02d]".format(minutes, seconds, centiSeconds)

            } else if (currentStartTime != null) {
                // 处理字幕文本：去除 HTML 标签
                val cleanLine = trimLine.replace(Regex("<[^>]+>"), "")
                if (cleanLine.isNotEmpty()) {
                    // 如果是多行字幕，用空格连接
                    if (currentSubtitle.isNotEmpty()) currentSubtitle.append(" ")
                    currentSubtitle.append(cleanLine)
                }
            }
        }

        // 循环结束后，写入最后一段
        if (currentStartTime != null && currentSubtitle.isNotEmpty()) {
            lrcLines.append(currentStartTime).append(currentSubtitle.toString()).append("\n")
        }

        return lrcLines.toString()
    }

    /**
     * 获取输出文件名
     */
    fun getOutputFileName(originalName: String, removeNested: Boolean): String {
        val lowerName = originalName.lowercase()

        // 1. 先去掉 .vtt 后缀
        var baseName = if (lowerName.endsWith(".vtt")) {
            originalName.substring(0, originalName.length - 4)
        } else {
            originalName
        }

        if (!removeNested) return baseName

        // 2. 循环移除已知的后缀
        var lowerBase = baseName.lowercase()
        var foundExtension = true

        while (foundExtension) {
            foundExtension = false
            for (ext in KNOWN_EXTENSIONS) {
                if (lowerBase.endsWith(ext)) {
                    baseName = baseName.substring(0, baseName.length - ext.length)
                    lowerBase = lowerBase.substring(0, lowerBase.length - ext.length)
                    foundExtension = true
                    break
                }
            }
            // 防死循环：如果没有点了，停止
            if (!lowerBase.contains(".")) break
        }

        return baseName
    }
}
