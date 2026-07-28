package com.saltfishlen.vtt2lrc

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.saltfishlen.vtt2lrc.ui.DetailsSection
import com.saltfishlen.vtt2lrc.ui.Guidance
import com.saltfishlen.vtt2lrc.ui.GuidanceCard
import com.saltfishlen.vtt2lrc.ui.JobReporter
import com.saltfishlen.vtt2lrc.ui.JobState
import com.saltfishlen.vtt2lrc.ui.SectionCard
import com.saltfishlen.vtt2lrc.ui.StatusCard
import com.saltfishlen.vtt2lrc.ui.ToggleRow
import com.saltfishlen.vtt2lrc.ui.groupedByCause
import com.saltfishlen.vtt2lrc.ui.theme.Vtt2lrcTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Vtt2lrcTheme {
                VttBatchConverterScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VttBatchConverterScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var job by remember { mutableStateOf(JobState()) }
    var removeNestedExt by remember { mutableStateOf(true) }
    val isProcessing = job.isBusy

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val reporter = JobReporter { newState -> job = newState }
                reporter.start()
                keepFolderAccess(context, it, reporter)
                processFolderInPlace(context, it, removeNestedExt, reporter)
                reporter.finish()
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "字幕转歌词",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                StatusCard(
                    state = job,
                    idleTitle = "选择文件夹开始转换",
                    idleHint = "文件夹中的 .vtt 字幕会转换为 .lrc 歌词，保存在同一位置"
                )

                job.blocker?.let { GuidanceCard(failure = it) }
                job.failures.groupedByCause().forEach { GuidanceCard(failure = it) }

                SectionCard(title = "转换设置") {
                    ToggleRow(
                        title = "自动整理文件名",
                        subtitle = if (removeNestedExt) {
                            "song.mp3.vtt 转换为 song.lrc（推荐）"
                        } else {
                            "song.mp3.vtt 转换为 song.mp3.lrc"
                        },
                        checked = removeNestedExt,
                        onCheckedChange = { removeNestedExt = it },
                        enabled = !isProcessing
                    )
                }

                DetailsSection(details = job.details)
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = { folderLauncher.launch(null) },
                    enabled = !isProcessing,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("正在处理…")
                    } else {
                        Text("选择文件夹，开始转换")
                    }
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
    reporter: JobReporter
) {
    withContext(Dispatchers.IO) {
        try {
            val rootDir = DocumentFile.fromTreeUri(context, treeUri)
            if (rootDir == null || !rootDir.isDirectory) {
                reporter.blocked(Guidance.folderNotAccessible())
                return@withContext
            }

            // 目前只扫描当前层级（如需递归可后续扩展）
            val allFiles = rootDir.listFiles()
            val vttFiles = allFiles.filter { it.name?.lowercase()?.endsWith(".vtt") == true }

            val total = vttFiles.size
            if (total == 0) {
                reporter.blocked(Guidance.noVttFound())
                return@withContext
            }

            reporter.found(total)

            for (file in vttFiles) {
                val originalName = file.name ?: "unknown.vtt"
                reporter.startFile(originalName)

                val content = try {
                    readTextFromUri(context, file.uri)
                } catch (e: Exception) {
                    reporter.fileFailed(
                        Guidance.readFailed(originalName),
                        "$originalName 读取失败: ${e.message}"
                    )
                    continue
                }

                try {
                    val baseName = VttUtils.getOutputFileName(originalName, removeNestedExt)
                    val lrcFileName = "$baseName.lrc"
                    val lrcContent = VttUtils.convertToLrc(content, baseName)

                    // 覆盖逻辑：避免自动重命名（如生成 xxx (1).lrc）
                    val existingLrc = rootDir.findFile(lrcFileName)
                    if (existingLrc != null && existingLrc.exists()) {
                        try {
                            existingLrc.delete()
                        } catch (e: Exception) {
                            // 删除失败就直接覆盖写入
                            reporter.detail("⚠️ 旧文件无法删除，改为直接覆盖: $lrcFileName")
                        }
                    }

                    val newFile = rootDir.createFile("text/x-lrc", lrcFileName)
                    if (newFile == null) {
                        reporter.fileFailed(
                            Guidance.cannotCreateFile(originalName),
                            "$originalName 创建输出文件失败"
                        )
                        continue
                    }

                    // 使用 "w" 模式，并强制 UTF-8 编码
                    context.contentResolver.openOutputStream(newFile.uri, "w")?.use { output ->
                        output.write(lrcContent.toByteArray(Charsets.UTF_8))
                    }
                    reporter.fileSucceeded(originalName, lrcFileName)

                } catch (e: Exception) {
                    reporter.fileFailed(
                        Guidance.unexpected(originalName),
                        "$originalName 转换失败: ${e.message}"
                    )
                }
            }

        } catch (e: Exception) {
            reporter.detail("❌ 致命错误: ${e.message}")
            reporter.blocked(Guidance.folderNotAccessible())
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

/** 记住这次授权，App 重启后依然能访问；失败不影响本次操作，只写进详细记录。 */
private fun keepFolderAccess(context: Context, uri: Uri, reporter: JobReporter) {
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    try {
        context.contentResolver.takePersistableUriPermission(uri, flags)
    } catch (e: Exception) {
        reporter.detail("⚠️ 访问授权未能长期保存，不影响本次操作: ${e.message}")
    }
}
