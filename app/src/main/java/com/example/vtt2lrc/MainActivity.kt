package com.example.vtt2lrc // 记得替换你的包名

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
                VttBatchConverterScreen()
            }
        }
    }
}

@Composable
fun VttBatchConverterScreen() {
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
                // 1. 【关键修复】持久化权限
                // 即使 App 重启，只要不卸载，该文件夹的权限依然有效
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
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("VTT 原地批量转换器", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
        Spacer(modifier = Modifier.height(8.dp))
        Text("优化版：支持中文、权限修正", fontSize = 12.sp, color = Color.Gray)

        Divider(modifier = Modifier.padding(vertical = 16.dp))

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
                    // 读取内容 (自动处理编码，BufferedReader 默认探测或UTF-8)
                    val content = readTextFromUri(context, file.uri)

                    val baseName = VttUtils.getOutputFileName(originalName, removeNestedExt)
                    val lrcFileName = "$baseName.lrc"
                    val lrcContent = VttUtils.convertToLrc(content, baseName)

                    // 【优化】覆盖逻辑
                    val existingLrc = rootDir.findFile(lrcFileName)
                    if (existingLrc != null && existingLrc.exists()) {
                        // 尝试删除旧文件，防止 DocumentProvider 自动重命名 (如生成 xxx (1).lrc)
                        try {
                            existingLrc.delete()
                        } catch (e: Exception) {
                            // 如果删除失败，尝试直接覆盖写入
                        }
                    }

                    // 创建新文件
                    val newFile = rootDir.createFile("text/x-lrc", lrcFileName)

                    if (newFile != null) {
                        // 【关键修复】使用 "w" 模式，并强制 UTF-8 编码
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
        // 这里的 InputStreamReader 默认通常是 UTF-8，
        // 如果你的 VTT 文件确定是 UTF-8，这里是安全的。
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