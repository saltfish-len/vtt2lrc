package com.saltfishlen.vtt2lrc

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object MP3Utils {

    data class ExtractResult(
        val inputName: String,
        val outputName: String,
        val outputUri: Uri?,
        val success: Boolean,
        val message: String,
        val error: ExtractError? = null
    )

    sealed class ExtractError {
        data class Io(val detail: String) : ExtractError()
        data class InvalidInput(val detail: String) : ExtractError()
        data class Ffmpeg(val code: Int, val detail: String?) : ExtractError()
        data class OutputDirMissing(val detail: String) : ExtractError()
    }

    data class Progress(
        val done: Int,
        val total: Int,
        val currentName: String
    )

    sealed class Mp3Mode {
        data class Vbr(val quality: Int = 2) : Mp3Mode()
        data class Cbr(val bitrate: String = "192k") : Mp3Mode()
    }

    fun queryDisplayName(cr: ContentResolver, uri: Uri): String? {
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return null
    }

    fun baseNameFromDisplayName(name: String): String {
        return name.substringBeforeLast('.', name)
    }

    fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    }

    fun copyUriToFile(cr: ContentResolver, uri: Uri, outFile: File) {
        cr.openInputStream(uri)?.use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("无法读取输入文件")
    }

    fun copyFileToUri(cr: ContentResolver, file: File, outUri: Uri) {
        cr.openOutputStream(outUri, "w")?.use { output ->
            FileInputStream(file).use { input ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("无法写入输出文件")
    }

    fun getTreeDir(context: Context, treeUri: Uri): DocumentFile? {
        val dir = DocumentFile.fromTreeUri(context, treeUri)
        return if (dir != null && dir.isDirectory) dir else null
    }

    fun createOrReplaceFile(dir: DocumentFile, mime: String, name: String): DocumentFile? {
        dir.findFile(name)?.let { existing ->
            if (existing.exists()) {
                existing.delete()
            }
        }
        return dir.createFile(mime, name)
    }

    fun buildFfmpegCmd(inputPath: String, outputPath: String, mode: Mp3Mode): String {
        val safeInput = "\"$inputPath\""
        val safeOutput = "\"$outputPath\""
        val audioArgs = when (mode) {
            is Mp3Mode.Vbr -> "-c:a libmp3lame -q:a ${mode.quality}"
            is Mp3Mode.Cbr -> "-c:a libmp3lame -b:a ${mode.bitrate}"
        }
        return "-y -i $safeInput -vn $audioArgs $safeOutput"
    }

    suspend fun extractOneMp3(
        context: Context,
        videoUri: Uri,
        outputDirTreeUri: Uri,
        mode: Mp3Mode,
        cacheDir: File
    ): ExtractResult = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val displayName = queryDisplayName(cr, videoUri) ?: videoUri.lastPathSegment ?: "video"
        val safeName = sanitizeFileName(displayName)
        val baseName = baseNameFromDisplayName(safeName)
        val outputName = "$baseName.mp3"

        val outputDir = getTreeDir(context, outputDirTreeUri)
            ?: return@withContext ExtractResult(
                inputName = safeName,
                outputName = outputName,
                outputUri = null,
                success = false,
                message = "无法访问输出目录",
                error = ExtractError.OutputDirMissing("output tree missing")
            )

        val inputExt = safeName.substringAfterLast('.', "mp4")
        val inputCache = File(cacheDir, "ffmpeg_in_${System.nanoTime()}.$inputExt")
        val outputCache = File(cacheDir, "ffmpeg_out_${System.nanoTime()}.mp3")

        try {
            copyUriToFile(cr, videoUri, inputCache)
        } catch (e: Exception) {
            inputCache.delete()
            return@withContext ExtractResult(
                inputName = safeName,
                outputName = outputName,
                outputUri = null,
                success = false,
                message = "拷贝输入失败: ${e.message}",
                error = ExtractError.Io(e.message ?: "copy input failed")
            )
        }

        val session = FFmpegKit.execute(buildFfmpegCmd(inputCache.absolutePath, outputCache.absolutePath, mode))
        if (!ReturnCode.isSuccess(session.returnCode)) {
            inputCache.delete()
            outputCache.delete()
            return@withContext ExtractResult(
                inputName = safeName,
                outputName = outputName,
                outputUri = null,
                success = false,
                message = "转码失败: ${session.returnCode}",
                error = ExtractError.Ffmpeg(session.returnCode?.value ?: -1, session.failStackTrace)
            )
        }

        val outDoc = createOrReplaceFile(outputDir, "audio/mpeg", outputName)
            ?: return@withContext ExtractResult(
                inputName = safeName,
                outputName = outputName,
                outputUri = null,
                success = false,
                message = "创建输出文件失败",
                error = ExtractError.Io("create output failed")
            )

        return@withContext try {
            copyFileToUri(cr, outputCache, outDoc.uri)
            ExtractResult(
                inputName = safeName,
                outputName = outputName,
                outputUri = outDoc.uri,
                success = true,
                message = "✅ $safeName -> $outputName"
            )
        } catch (e: Exception) {
            ExtractResult(
                inputName = safeName,
                outputName = outputName,
                outputUri = null,
                success = false,
                message = "写入输出失败: ${e.message}",
                error = ExtractError.Io(e.message ?: "copy output failed")
            )
        } finally {
            inputCache.delete()
            outputCache.delete()
        }
    }
}
