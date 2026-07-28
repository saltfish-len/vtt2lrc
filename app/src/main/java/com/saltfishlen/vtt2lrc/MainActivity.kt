package com.saltfishlen.vtt2lrc

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var tabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("字幕转歌词", "视频提取音乐")

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = tabs[tabIndex],
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                NavigationBarItem(
                    selected = tabIndex == 0,
                    onClick = { tabIndex = 0 },
                    icon = {
                        Icon(
                            imageVector = if (tabIndex == 0) {
                                Icons.AutoMirrored.Filled.List
                            } else {
                                Icons.AutoMirrored.Outlined.List
                            },
                            contentDescription = null
                        )
                    },
                    label = { Text("字幕") }
                )
                NavigationBarItem(
                    selected = tabIndex == 1,
                    onClick = { tabIndex = 1 },
                    icon = {
                        Icon(
                            imageVector = if (tabIndex == 1) {
                                Icons.Filled.PlayArrow
                            } else {
                                Icons.Outlined.PlayArrow
                            },
                            contentDescription = null
                        )
                    },
                    label = { Text("音频") }
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

/** 底部固定的操作区。 */
@Composable
private fun ActionBar(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

/** 主按钮内部内容：处理中时换成转圈 + 提示文案。 */
@Composable
private fun ButtonContent(text: String, isProcessing: Boolean) {
    if (isProcessing) {
        CircularProgressIndicator(
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text("正在处理…")
    } else {
        Text(text)
    }
}

/** 所有失败提示；同一原因的多个文件会合并成一条。 */
@Composable
private fun GuidanceList(job: JobState) {
    job.blocker?.let { GuidanceCard(failure = it) }
    job.failures.groupedByCause().forEach { GuidanceCard(failure = it) }
}

@Composable
fun VttBatchConverterScreen(modifier: Modifier = Modifier) {
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

    Column(
        modifier = modifier
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

            GuidanceList(job)

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

        ActionBar {
            Button(
                onClick = { folderLauncher.launch(null) },
                enabled = !isProcessing,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                ButtonContent("选择文件夹，开始转换", isProcessing)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun Mp3BatchExtractorScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val allExts = listOf("mp4", "mkv", "mov", "avi", "flv", "webm", "m4v", "wav")

    var job by remember { mutableStateOf(JobState()) }
    var selectedExts by remember { mutableStateOf(setOf("mp4")) }
    var useVbr by remember { mutableStateOf(true) }
    var showFfmpegLogs by remember { mutableStateOf(false) }
    var deleteSourceWav by remember { mutableStateOf(false) }
    val isProcessing = job.isBusy

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val reporter = JobReporter { newState -> job = newState }
                reporter.start()
                keepFolderAccess(context, it, reporter)

                val mode = if (useVbr) MP3Utils.Mp3Mode.Vbr() else MP3Utils.Mp3Mode.Cbr()
                extractMp3FromFolder(
                    context = context,
                    treeUri = it,
                    selectedExts = selectedExts,
                    mode = mode,
                    deleteSourceWav = deleteSourceWav,
                    showFfmpegLogs = showFfmpegLogs,
                    reporter = reporter
                )
                reporter.finish()
            }
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val reporter = JobReporter { newState -> job = newState }
                reporter.start()
                keepFolderAccess(context, it, reporter)

                val mode = if (useVbr) MP3Utils.Mp3Mode.Vbr() else MP3Utils.Mp3Mode.Cbr()
                extractMp3FromFile(
                    context = context,
                    fileUri = it,
                    selectedExts = selectedExts,
                    mode = mode,
                    deleteSourceWav = deleteSourceWav,
                    showFfmpegLogs = showFfmpegLogs,
                    reporter = reporter
                )
                reporter.finish()
            }
        }
    }

    Column(
        modifier = modifier
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
                idleTitle = "选择文件夹开始提取",
                idleHint = "文件夹中的视频会提取出 MP3，保存在同一位置"
            )

            GuidanceList(job)

            SectionCard(title = "音质") {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = useVbr,
                        onClick = { useVbr = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        enabled = !isProcessing
                    ) {
                        Text("高音质")
                    }
                    SegmentedButton(
                        selected = !useVbr,
                        onClick = { useVbr = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        enabled = !isProcessing
                    ) {
                        Text("省空间")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (useVbr) {
                        "文件较大，音质更好（推荐）"
                    } else {
                        "文件约小一半，日常收听足够"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionCard(title = "要处理的格式") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    allExts.forEach { ext ->
                        val checked = selectedExts.contains(ext)
                        FilterChip(
                            selected = checked,
                            onClick = {
                                selectedExts = if (checked) {
                                    selectedExts - ext
                                } else {
                                    selectedExts + ext
                                }
                            },
                            enabled = !isProcessing,
                            label = { Text(ext.uppercase()) },
                            leadingIcon = if (checked) {
                                {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(FilterChipDefaults.IconSize)
                                    )
                                }
                            } else {
                                null
                            }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (selectedExts.isEmpty()) {
                        "未勾选任何格式，不会处理文件"
                    } else {
                        "不确定时保持默认的 MP4 即可"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selectedExts.isEmpty()) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )

                if (selectedExts.contains("wav")) {
                    Spacer(Modifier.height(4.dp))
                    ToggleRow(
                        title = "转换后删除原 WAV 文件",
                        subtitle = "删除后无法恢复，不确定请勿开启",
                        checked = deleteSourceWav,
                        onCheckedChange = { deleteSourceWav = it },
                        enabled = !isProcessing
                    )
                }
            }

            DetailsSection(
                details = job.details,
                extraSettings = {
                    ToggleRow(
                        title = "记录每一步的转换细节",
                        subtitle = "仅排查问题时需要，平时无需开启",
                        checked = showFfmpegLogs,
                        onCheckedChange = { showFfmpegLogs = it },
                        enabled = !isProcessing
                    )
                }
            )
        }

        ActionBar {
            Button(
                onClick = { folderLauncher.launch(null) },
                enabled = !isProcessing,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                ButtonContent("选择文件夹，批量提取", isProcessing)
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = { fileLauncher.launch(arrayOf("video/*", "audio/*")) },
                enabled = !isProcessing,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text("只处理一个文件")
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

private fun matchesExt(name: String, selectedExts: Set<String>): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext.isNotEmpty() && selectedExts.contains(ext)
}

suspend fun extractMp3FromFolder(
    context: Context,
    treeUri: Uri,
    selectedExts: Set<String>,
    mode: MP3Utils.Mp3Mode,
    deleteSourceWav: Boolean,
    showFfmpegLogs: Boolean,
    reporter: JobReporter
) {
    withContext(Dispatchers.IO) {
        if (selectedExts.isEmpty()) {
            reporter.blocked(Guidance.noExtSelected())
            return@withContext
        }

        val rootDir = DocumentFile.fromTreeUri(context, treeUri)
        if (rootDir == null || !rootDir.isDirectory) {
            reporter.blocked(Guidance.folderNotAccessible())
            return@withContext
        }

        val allFiles = rootDir.listFiles()
        val videoFiles = allFiles.filter {
            it.isFile && (it.name?.let { name -> matchesExt(name, selectedExts) } == true)
        }
        val total = videoFiles.size
        if (total == 0) {
            reporter.blocked(Guidance.noVideoFound(selectedExts))
            return@withContext
        }

        reporter.found(total)

        for (file in videoFiles) {
            val name = file.name ?: "未命名文件"
            reporter.startFile(name)

            val result = MP3Utils.extractOneMp3(
                context = context,
                videoUri = file.uri,
                outputDirTreeUri = treeUri,
                mode = mode,
                cacheDir = context.cacheDir,
                onFfmpegLog = if (showFfmpegLogs) ({ msg -> reporter.detail(msg) }) else null
            )

            if (result.success) {
                reporter.fileSucceeded(result.inputName, result.outputName)
                if (deleteSourceWav && name.lowercase().endsWith(".wav")) {
                    val deleted = file.delete()
                    reporter.detail(
                        if (deleted) "已删除源文件: $name" else "⚠️ 删除源文件失败: $name"
                    )
                }
            } else {
                reporter.fileFailed(
                    Guidance.forExtractError(result.inputName, result.error),
                    "${result.inputName} ${result.message}"
                )
            }
        }
    }
}

suspend fun extractMp3FromFile(
    context: Context,
    fileUri: Uri,
    selectedExts: Set<String>,
    mode: MP3Utils.Mp3Mode,
    deleteSourceWav: Boolean,
    showFfmpegLogs: Boolean,
    reporter: JobReporter
) {
    withContext(Dispatchers.IO) {
        if (selectedExts.isEmpty()) {
            reporter.blocked(Guidance.noExtSelected())
            return@withContext
        }

        val displayName = MP3Utils.queryDisplayName(context.contentResolver, fileUri)
            ?: fileUri.lastPathSegment
            ?: "video"

        if (!matchesExt(displayName, selectedExts)) {
            reporter.blocked(Guidance.extNotSelected(displayName))
            return@withContext
        }

        val parentTreeUri = buildParentTreeUri(fileUri)
        if (parentTreeUri == null) {
            reporter.blocked(Guidance.outputDirUnknown(displayName))
            return@withContext
        }

        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(parentTreeUri, flags)
        } catch (e: Exception) {
            reporter.detail("⚠️ 保存位置的授权未能长期保存，不影响本次操作: ${e.message}")
        }

        reporter.found(1)
        reporter.startFile(displayName)

        val result = MP3Utils.extractOneMp3(
            context = context,
            videoUri = fileUri,
            outputDirTreeUri = parentTreeUri,
            mode = mode,
            cacheDir = context.cacheDir,
            onFfmpegLog = if (showFfmpegLogs) ({ msg -> reporter.detail(msg) }) else null
        )

        if (result.success) {
            reporter.fileSucceeded(result.inputName, result.outputName)
            if (deleteSourceWav && displayName.lowercase().endsWith(".wav")) {
                val doc = DocumentFile.fromSingleUri(context, fileUri)
                val deleted = doc?.delete() == true
                reporter.detail(
                    if (deleted) "已删除源文件: $displayName" else "⚠️ 删除源文件失败: $displayName"
                )
            }
        } else {
            reporter.fileFailed(
                Guidance.forExtractError(result.inputName, result.error),
                "${result.inputName} ${result.message}"
            )
        }
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
