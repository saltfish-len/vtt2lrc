package com.saltfishlen.vtt2lrc.ui

import com.saltfishlen.vtt2lrc.MP3Utils

enum class JobPhase { Idle, Scanning, Running, Finished }

/**
 * 一条「哪里出问题了 + 该怎么办」的说明。
 * [fileName] 为 null 表示整个任务没能开始，而不是某个文件失败。
 */
data class JobFailure(
    val fileName: String?,
    val what: String,
    val how: String
)

data class JobState(
    val phase: JobPhase = JobPhase.Idle,
    val total: Int = 0,
    val done: Int = 0,
    val succeeded: Int = 0,
    val currentFile: String = "",
    val failures: List<JobFailure> = emptyList(),
    /** 整个任务没能开始的原因，比如文件夹选错了。 */
    val blocker: JobFailure? = null,
    /** 详细过程记录，默认折叠，只给需要排查的人看。 */
    val details: List<String> = emptyList()
) {
    val failedCount: Int get() = failures.size
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    val isBusy: Boolean get() = phase == JobPhase.Scanning || phase == JobPhase.Running
    val allSucceeded: Boolean
        get() = phase == JobPhase.Finished && blocker == null && failures.isEmpty() && succeeded > 0
}

private const val MAX_DETAIL_LINES = 400

/**
 * 业务逻辑通过它汇报进度，不再直接拼日志字符串。
 * 方法可能在 IO 线程调用，[onUpdate] 负责写回 Compose 状态。
 */
class JobReporter(private val onUpdate: (JobState) -> Unit) {

    private var state = JobState()

    private fun update(block: JobState.() -> JobState) {
        state = state.block()
        onUpdate(state)
    }

    fun start() = update { JobState(phase = JobPhase.Scanning) }

    fun detail(line: String) = update {
        val next = details + line
        copy(details = if (next.size > MAX_DETAIL_LINES) next.takeLast(MAX_DETAIL_LINES) else next)
    }

    fun found(count: Int) = update {
        copy(phase = JobPhase.Running, total = count, details = details + "共找到 $count 个文件")
    }

    fun startFile(name: String) = update { copy(currentFile = name) }

    fun fileSucceeded(name: String, outputName: String) = update {
        copy(
            done = done + 1,
            succeeded = succeeded + 1,
            details = details + "✅ $name → $outputName"
        )
    }

    fun fileFailed(failure: JobFailure, detail: String) = update {
        copy(
            done = done + 1,
            failures = failures + failure,
            details = details + "❌ $detail"
        )
    }

    /** 任务整体没能开始。 */
    fun blocked(failure: JobFailure) = update {
        copy(phase = JobPhase.Finished, blocker = failure, details = details + "❌ ${failure.what}")
    }

    fun finish() = update { copy(phase = JobPhase.Finished, currentFile = "") }
}

/**
 * 同一个原因的多个文件合并成一条提示。20 个文件失败不该刷出 20 张一模一样的卡片。
 */
fun List<JobFailure>.groupedByCause(): List<JobFailure> =
    groupBy { it.what to it.how }.map { (cause, list) ->
        val names = list.mapNotNull { it.fileName }
        JobFailure(
            fileName = when {
                names.isEmpty() -> null
                names.size == 1 -> names.first()
                else -> "${names.take(2).joinToString("、")} 等 ${names.size} 个文件"
            },
            what = cause.first,
            how = cause.second
        )
    }

/**
 * 把技术性的失败原因翻译成普通用户能照着做的一句话。
 * 措辞尽量避开「URI」「权限持久化」这类词。
 */
object Guidance {

    fun folderNotAccessible() = JobFailure(
        fileName = null,
        what = "无法访问该文件夹",
        how = "系统不允许应用访问「内部存储」根目录，以及「下载」和「Android」文件夹，" +
                "选中时上方会显示一行灰色提示。请改选其他文件夹；" +
                "如果没有合适的，可在文件管理器中新建一个，把文件放进去后再来选择。"
    )

    /** 只读位置、网盘同步目录等写不进去的情况，统一给这条建议。 */
    private const val MOVE_TO_OWN_FOLDER =
        "请将文件复制到手机内置存储的其他文件夹后重试。"

    fun noVttFound() = JobFailure(
        fileName = null,
        what = "该文件夹中没有 .vtt 文件",
        how = "仅处理 .vtt 格式的字幕，且只查找所选的这一层，不会进入子文件夹。" +
                "如果字幕位于下一级目录，请选择该目录。"
    )

    fun noVideoFound(selectedExts: Set<String>) = JobFailure(
        fileName = null,
        what = "该文件夹中没有已勾选的格式",
        how = "当前只处理 ${selectedExts.joinToString("、") { it.uppercase() }}。" +
                "请在「要处理的格式」中勾选对应格式，或改选其他文件夹。" +
                "另外只查找所选的这一层，不会进入子文件夹。"
    )

    fun noExtSelected() = JobFailure(
        fileName = null,
        what = "尚未勾选任何格式",
        how = "请在「要处理的格式」中至少勾选一项，不确定时选择 MP4。"
    )

    fun extNotSelected(fileName: String) = JobFailure(
        fileName = fileName,
        what = "该文件的格式未勾选",
        how = "请先在「要处理的格式」中勾选该格式，然后重新选择文件。"
    )

    fun outputDirUnknown(fileName: String) = JobFailure(
        fileName = fileName,
        what = "无法确定 MP3 的保存位置",
        how = "请改用「选择文件夹，批量提取」，直接选择视频所在的文件夹，MP3 将保存在该文件夹中。"
    )

    fun cannotCreateFile(fileName: String) = JobFailure(
        fileName = fileName,
        what = "无法在该文件夹中创建文件",
        how = "该位置可能是只读的，SD 卡和网盘同步目录较常出现这种情况。$MOVE_TO_OWN_FOLDER"
    )

    fun readFailed(fileName: String) = JobFailure(
        fileName = fileName,
        what = "无法读取该文件",
        how = "文件可能已被移动或删除。请在文件管理器中确认文件仍然存在，然后重新选择文件夹。"
    )

    fun forExtractError(fileName: String, error: MP3Utils.ExtractError?): JobFailure = when (error) {
        is MP3Utils.ExtractError.OutputDirMissing -> outputDirUnknown(fileName)

        is MP3Utils.ExtractError.Ffmpeg -> JobFailure(
            fileName = fileName,
            what = "该文件无法转换",
            how = "文件可能已损坏，也可能本身就不含声音，例如无声的录屏。" +
                    "可以先用播放器确认是否有声音。"
        )

        is MP3Utils.ExtractError.Io -> JobFailure(
            fileName = fileName,
            what = "读写文件时出错",
            how = "通常是存储空间不足，或文件正被其他应用占用。" +
                    "请清理空间并关闭占用该文件的应用后重试。如果仍然失败，$MOVE_TO_OWN_FOLDER"
        )

        is MP3Utils.ExtractError.InvalidInput -> JobFailure(
            fileName = fileName,
            what = "无法打开该文件",
            how = "文件可能不完整，例如尚未下载完成。请换一个文件重试。"
        )

        null -> JobFailure(
            fileName = fileName,
            what = "该文件未能转换成功",
            how = "其余文件不受影响。如果多个文件都失败，可能是该文件夹的问题，请改选其他文件夹。"
        )
    }

    fun unexpected(fileName: String) = JobFailure(
        fileName = fileName,
        what = "该文件在处理过程中出错",
        how = "其余文件不受影响。如果每次都在同一个文件上失败，请将该文件单独放到其他文件夹后重试。"
    )
}
