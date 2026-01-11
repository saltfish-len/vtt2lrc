package com.saltfishlen.vtt2lrc

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
    var tabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("VTT 转 LRC", "视频提取 MP3")

    Scaffold(
        topBar = {
            Surface(tonalElevation = 2.dp) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text(tabs[tabIndex], fontWeight = FontWeight.SemiBold)
                }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tabIndex == 0,
                    onClick = { tabIndex = 0 },
                    icon = { Text("V") },
                    label = { Text("VTT") }
                )
                NavigationBarItem(
                    selected = tabIndex == 1,
                    onClick = { tabIndex = 1 },
                    icon = { Text("M") },
                    label = { Text("MP3") }
                )
            }
        }
    ) { innerPadding ->
        when (tabIndex) {
            0 -> VttBatchConverterScreen(modifier = Modifier.padding(innerPadding))
            1 -> Mp3BatchExtractorScreen(modifier = Modifier.padding(innerPadding))
        }
    }
}

@Composable
fun VttBatchConverterScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // UI 状态
    var removeNestedExt by remember { mutableStateOf(true) }
    var isProcessing by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf(listOf<String>("准备就绪，请选择包含 VTT 的文件夹")) }
    var progress by remember { mutableStateOf(0f) }

    // 文件夹选择器
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                // 持久化权限，App 重启后依然有效
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                try {
                    context.contentResolver.takePersistableUriPermission(it, flags)
                } catch (e: Exception) {
                    logs = logs + "警告: 权限持久化失败，但这不影响本次操作"
                }

                isProcessing = true
                logs = listOf("正在扫描文件夹...")
                progress = 0f

                processFolderInPlace(context, it, removeNestedExt,
                    onLog = { msg -> logs = logs + msg },
                    onProgress = { p -> progress = p }
                )

                isProcessing = false
                logs = logs + "=== 全部完成 ==="
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 选项区域
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(12.dp)
            ) {
                Checkbox(
                    checked = removeNestedExt,
                    onCheckedChange = { removeNestedExt = it },
                    enabled = !isProcessing
                )
                Column {
                    Text("去除嵌套扩展名", fontWeight = FontWeight.Bold)
                    Text(
                        if (removeNestedExt) "例如: song.mp3.vtt -> song.lrc"
                        else "例如: song.mp3.vtt -> song.mp3.lrc",
                        fontSize = 12.sp, color = Color.Gray
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = { folderLauncher.launch(null) },
            enabled = !isProcessing,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
        ) {
            if (isProcessing) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text("正在处理...")
            } else {
                Text("选择文件夹并开始转换")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (isProcessing) {
            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text("运行日志:", fontWeight = FontWeight.Bold)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFEEEEEE))
                .padding(8.dp)
        ) {
            items(logs) { log ->
                Text(text = log, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

@Composable
fun Mp3BatchExtractorScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val allExts = listOf("mp4", "mkv", "mov", "avi", "flv", "webm", "m4v")

    var selectedExts by remember { mutableStateOf(setOf("mp4")) }
    var useVbr by remember { mutableStateOf(true) }
    var showFfmpegLogs by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf(listOf("准备就绪，请选择文件或文件夹")) }
    var progress by remember { mutableStateOf(0f) }

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                isProcessing = true
                logs = listOf("正在扫描文件夹...")
                progress = 0f
                persistWritePermission(context, it, logs) { newLogs -> logs = newLogs }

                val mode = if (useVbr) MP3Utils.Mp3Mode.Vbr() else MP3Utils.Mp3Mode.Cbr()
                extractMp3FromFolder(
                    context = context,
                    treeUri = it,
                    selectedExts = selectedExts,
                    mode = mode,
                    showFfmpegLogs = showFfmpegLogs,
                    onLog = { msg -> logs = logs + msg },
                    onProgress = { p -> progress = p }
                )

                isProcessing = false
                logs = logs + "=== 全部完成 ==="
            }
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                isProcessing = true
                logs = listOf("正在处理单个文件...")
                progress = 0f
                persistWritePermission(context, it, logs) { newLogs -> logs = newLogs }

                val mode = if (useVbr) MP3Utils.Mp3Mode.Vbr() else MP3Utils.Mp3Mode.Cbr()
                extractMp3FromFile(
                    context = context,
                    fileUri = it,
                    selectedExts = selectedExts,
                    mode = mode,
                    showFfmpegLogs = showFfmpegLogs,
                    onLog = { msg -> logs = logs + msg },
                    onProgress = { p -> progress = p }
                )

                isProcessing = false
                logs = logs + "=== 全部完成 ==="
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("输出模式", fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = useVbr, onClick = { useVbr = true }, enabled = !isProcessing)
                    Text("VBR (q=2)")
                    Spacer(modifier = Modifier.width(16.dp))
                    RadioButton(selected = !useVbr, onClick = { useVbr = false }, enabled = !isProcessing)
                    Text("CBR (192k)")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = showFfmpegLogs,
                        onCheckedChange = { showFfmpegLogs = it },
                        enabled = !isProcessing
                    )
                    Text("显示 FFmpeg 进度日志")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("选择视频扩展名", fontWeight = FontWeight.Bold)
                allExts.chunked(3).forEach { row ->
                    Row {
                        row.forEach { ext ->
                            val checked = selectedExts.contains(ext)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(end = 12.dp)
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        selectedExts = if (checked) {
                                            selectedExts - ext
                                        } else {
                                            selectedExts + ext
                                        }
                                    },
                                    enabled = !isProcessing
                                )
                                Text(ext)
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text("运行日志:", fontWeight = FontWeight.Bold)
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFFEEEEEE))
                .padding(8.dp)
        ) {
            items(logs) { log ->
                Text(text = log, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Surface(
            tonalElevation = 2.dp,
            shadowElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (isProcessing) {
                    LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(10.dp))
                }

                Button(
                    onClick = { folderLauncher.launch(null) },
                    enabled = !isProcessing,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00897B))
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("正在处理...")
                    } else {
                        Text("选择文件夹批量提取")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = { fileLauncher.launch(arrayOf("video/*")) },
                    enabled = !isProcessing,
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text("选择单个文件提取")
                }
            }
        }
    }
}

// --- 核心业务逻辑 ---
suspend fun processFolderInPlace(
    context: Context,
    treeUri: Uri,
    removeNestedExt: Boolean,
    onLog: (String) -> Unit,
    onProgress: (Float) -> Unit
) {
    withContext(Dispatchers.IO) {
        try {
            val rootDir = DocumentFile.fromTreeUri(context, treeUri)
            if (rootDir == null || !rootDir.isDirectory) {
                onLog("错误：无法访问文件夹。")
                return@withContext
            }

            // 目前只扫描当前层级（如需递归可后续扩展）
            val allFiles = rootDir.listFiles()
            val vttFiles = allFiles.filter { it.name?.lowercase()?.endsWith(".vtt") == true }

                val total = vttFiles.size
            if (total == 0) {
                onLog("未找到 .vtt 文件。")
                return@withContext
            }

            onLog("发现 $total 个 VTT 文件，开始转换...")

            var processed = 0

            for (file in vttFiles) {
                val originalName = file.name ?: "unknown.vtt"

                try {
                    // 读取内容（UTF-8）
                    val content = readTextFromUri(context, file.uri)

                    val baseName = VttUtils.getOutputFileName(originalName, removeNestedExt)
                    val lrcFileName = "$baseName.lrc"
                    val lrcContent = VttUtils.convertToLrc(content, baseName)

                    // 覆盖逻辑：避免自动重命名
                    val existingLrc = rootDir.findFile(lrcFileName)
                    if (existingLrc != null && existingLrc.exists()) {
                        // 尝试删除旧文件，防止自动重命名（如生成 xxx (1).lrc）
                        try {
                            existingLrc.delete()
                        } catch (e: Exception) {
                            // 如果删除失败，尝试直接覆盖写入
                        }
                    }

                    // 创建新文件
                    val newFile = rootDir.createFile("text/x-lrc", lrcFileName)

                    if (newFile != null) {
                        // 使用 "w" 模式，并强制 UTF-8 编码
                        context.contentResolver.openOutputStream(newFile.uri, "w")?.use { output ->
                            output.write(lrcContent.toByteArray(Charsets.UTF_8))
                        }
                        onLog("✅ $originalName -> $lrcFileName")
                    } else {
                        onLog("❌ 创建文件失败: $lrcFileName")
                    }

                } catch (e: Exception) {
                    onLog("❌ 转换失败 ($originalName): ${e.message}")
                }

                processed++
                onProgress(processed / total.toFloat())
            }

        } catch (e: Exception) {
            onLog("致命错误: ${e.message}")
            e.printStackTrace()
        }
    }
}

// 辅助读取
fun readTextFromUri(context: Context, uri: Uri): String {
    val sb = StringBuilder()
    context.contentResolver.openInputStream(uri)?.use { inputStream ->
        // InputStreamReader 使用 UTF-8
        BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
            var line: String? = reader.readLine()
            while (line != null) {
                sb.append(line).append("\n")
                line = reader.readLine()
            }
        }
    }
    return sb.toString()
}

private fun persistWritePermission(
    context: Context,
    uri: Uri,
    logs: List<String>,
    onLogsUpdate: (List<String>) -> Unit
) {
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    try {
        context.contentResolver.takePersistableUriPermission(uri, flags)
    } catch (e: Exception) {
        onLogsUpdate(logs + "警告: 权限持久化失败，但这不影响本次操作")
    }
}

private fun matchesExt(name: String, selectedExts: Set<String>): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext.isNotEmpty() && selectedExts.contains(ext)
}

suspend fun extractMp3FromFolder(
    context: Context,
    treeUri: Uri,
    selectedExts: Set<String>,
    mode: MP3Utils.Mp3Mode,
    showFfmpegLogs: Boolean,
    onLog: (String) -> Unit,
    onProgress: (Float) -> Unit
) {
    withContext(Dispatchers.IO) {
        if (selectedExts.isEmpty()) {
            onLog("请至少选择一个扩展名。")
            return@withContext
        }

        val rootDir = DocumentFile.fromTreeUri(context, treeUri)
        if (rootDir == null || !rootDir.isDirectory) {
            onLog("错误：无法访问文件夹。")
            return@withContext
        }

        val allFiles = rootDir.listFiles()
        val videoFiles = allFiles.filter { it.isFile && (it.name?.let { name -> matchesExt(name, selectedExts) } == true) }
        val total = videoFiles.size
        if (total == 0) {
            onLog("未找到匹配扩展名的视频文件。")
            return@withContext
        }

        onLog("发现 $total 个视频文件，开始提取...")
        var processed = 0

        for (file in videoFiles) {
            val result = MP3Utils.extractOneMp3(
                context = context,
                videoUri = file.uri,
                outputDirTreeUri = treeUri,
                mode = mode,
                cacheDir = context.cacheDir,
                onFfmpegLog = if (showFfmpegLogs) ({ msg -> onLog(msg) }) else null
            )
            onLog(result.message)
            processed++
            onProgress(processed / total.toFloat())
        }
    }
}

suspend fun extractMp3FromFile(
    context: Context,
    fileUri: Uri,
    selectedExts: Set<String>,
    mode: MP3Utils.Mp3Mode,
    showFfmpegLogs: Boolean,
    onLog: (String) -> Unit,
    onProgress: (Float) -> Unit
) {
    withContext(Dispatchers.IO) {
        if (selectedExts.isEmpty()) {
            onLog("请至少选择一个扩展名。")
            return@withContext
        }

        val displayName = MP3Utils.queryDisplayName(context.contentResolver, fileUri)
            ?: fileUri.lastPathSegment
            ?: "video"

        if (!matchesExt(displayName, selectedExts)) {
            onLog("文件扩展名不匹配: $displayName")
            return@withContext
        }

        val parentTreeUri = buildParentTreeUri(fileUri)
        if (parentTreeUri == null) {
            onLog("无法定位输出目录，请改用选择文件夹")
            return@withContext
        }

        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(parentTreeUri, flags)
        } catch (_: Exception) {
            onLog("警告: 无法持久化输出目录权限")
        }

        val result = MP3Utils.extractOneMp3(
            context = context,
            videoUri = fileUri,
            outputDirTreeUri = parentTreeUri,
            mode = mode,
            cacheDir = context.cacheDir,
            onFfmpegLog = if (showFfmpegLogs) ({ msg -> onLog(msg) }) else null
        )
        onLog(result.message)
        onProgress(1f)
    }
}

private fun buildParentTreeUri(fileUri: Uri): Uri? {
    val authority = fileUri.authority ?: return null
    val docId = try {
        DocumentsContract.getDocumentId(fileUri)
    } catch (e: IllegalArgumentException) {
        return null
    }
    val parts = docId.split(":")
    if (parts.size < 2) return null
    val volume = parts[0]
    val path = parts[1]
    val parentPath = if (path.contains("/")) path.substringBeforeLast("/") else ""
    val parentDocId = if (parentPath.isEmpty()) "$volume:" else "$volume:$parentPath"
    return DocumentsContract.buildTreeDocumentUri(authority, parentDocId)
}
