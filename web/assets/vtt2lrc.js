/**
 * 转换核心。与 Android 版的 app/src/main/java/com/saltfishlen/vtt2lrc/VttUtils.kt 一一对应，
 * 同一份 .vtt 在两端必须得到完全相同的 .lrc。改这里时请同步改 Kotlin 侧。
 *
 * 这个模块不碰 DOM，浏览器和 Node 都能直接 import（tests/vtt2lrc.test.mjs 用的就是它）。
 */

// 扩展名列表：包含常见音视频与字幕格式。顺序与 Kotlin 侧一致，剥离时按此顺序匹配。
const KNOWN_EXTENSIONS = [
  // Audio
  '.mp3', '.wav', '.aac', '.flac', '.ogg', '.wma', '.m4a', '.opus',
  '.aiff', '.au', '.ra', '.ac3', '.dts', '.amr', '.awb',
  // Video
  '.mp4', '.avi', '.mkv', '.flv', '.mov', '.wmv', '.webm', '.m4v',
  '.3gp', '.asf', '.rm', '.rmvb', '.vob', '.ogv', '.dv', '.ts',
  // Subtitle
  '.vtt', '.srt', '.sub', '.sbv', '.ass', '.ssa',
  '.webvtt', '.ttml', '.dfxp', '.smi', '.sami'
];

// 时间戳行，匹配 "开始 --> 结束"。不加 g 标志，避免 lastIndex 在多次调用间残留。
// 注意 --> 两侧写成显式的 ASCII 空白类，而不是 \s：Java 的 \s 只认 ASCII 空白，
// JS 的 \s 还包含全角空格等 Unicode 空白，用 \s 会让网页版比 Android 版多认一些行。
const TIME_PATTERN =
  /(?:(\d{1,2}):)?(\d{2}):(\d{2})\.(\d{3})[ \t\n\v\f\r]+-->[ \t\n\v\f\r]+(?:(\d{1,2}):)?(\d{2}):(\d{2})\.(\d{3})/;

// 纯数字索引行（SRT 风格的序号，VTT 里也常见）
const INDEX_PATTERN = /^\d+$/;

// HTML / VTT 标签，如 <v Bob>、<i>、<00:00:01.000>
const TAG_PATTERN = /<[^>]+>/g;

/** Kotlin 的 String.lines()：\r\n、\r、\n 都算换行。 */
function splitLines(text) {
  return text.split(/\r\n|\r|\n/);
}

function toInt(value) {
  // 组未命中时 Kotlin 侧是 null -> 0；"09" 必须按十进制解析，不能落到八进制。
  return value === undefined || value === null ? 0 : parseInt(value, 10);
}

function pad2(value) {
  // 对应 Kotlin 的 "%02d"：不足两位补零，超过两位原样输出（超过 100 分钟的长音频会用到）。
  return String(value).padStart(2, '0');
}

/**
 * 核心转换函数。
 *
 * @param {string} vttContent .vtt 文件的全文
 * @param {string} fileName   写进 [ti:] 的名字，调用方传的是清洗后的主文件名
 * @returns {string} .lrc 全文，行尾统一为 \n
 */
export function convertToLrc(vttContent, fileName) {
  const lines = splitLines(vttContent);
  const lrcLines = [];

  // 头部元数据
  lrcLines.push(`[ti:${fileName}]\n`);

  let currentStartTime = null;
  let currentSubtitle = '';

  for (const line of lines) {
    const trimLine = line.trim();

    // 跳过空行、WEBVTT 头、NOTE 注释
    if (trimLine === '' || trimLine === 'WEBVTT' || trimLine.startsWith('NOTE')) continue;

    // 跳过纯数字索引行
    if (INDEX_PATTERN.test(trimLine)) continue;

    const match = TIME_PATTERN.exec(trimLine);
    if (match) {
      // 如果之前有缓存的字幕，先写入上一段
      if (currentStartTime !== null && currentSubtitle !== '') {
        lrcLines.push(currentStartTime + currentSubtitle + '\n');
      }

      // 重置当前段落
      currentSubtitle = '';

      // 解析开始时间（组 1-4，结束时间用不上）
      const minutes = toInt(match[1]) * 60 + toInt(match[2]);
      const seconds = toInt(match[3]);
      // 毫秒处理：取前两位（厘秒）
      const centiSeconds = Math.floor(toInt(match[4]) / 10);

      // 格式化为 LRC 标准 [MM:SS.xx]
      currentStartTime = `[${pad2(minutes)}:${pad2(seconds)}.${pad2(centiSeconds)}]`;
    } else if (currentStartTime !== null) {
      // 处理字幕文本：去除标签。注意去标签后不再 trim，与 Kotlin 侧保持一致。
      const cleanLine = trimLine.replace(TAG_PATTERN, '');
      if (cleanLine !== '') {
        // 如果是多行字幕，用空格连接
        if (currentSubtitle !== '') currentSubtitle += ' ';
        currentSubtitle += cleanLine;
      }
    }
  }

  // 循环结束后，写入最后一段
  if (currentStartTime !== null && currentSubtitle !== '') {
    lrcLines.push(currentStartTime + currentSubtitle + '\n');
  }

  return lrcLines.join('');
}

/**
 * 获取输出文件名（不含 .lrc 后缀）。
 *
 * @param {string} originalName 原始文件名，如 song.mp3.vtt
 * @param {boolean} removeNested 是否继续剥离嵌套的已知扩展名
 */
export function getOutputFileName(originalName, removeNested) {
  const lowerName = originalName.toLowerCase();

  // 1. 先去掉 .vtt 后缀
  let baseName = lowerName.endsWith('.vtt')
    ? originalName.slice(0, originalName.length - 4)
    : originalName;

  if (!removeNested) return baseName;

  // 2. 循环移除已知的后缀
  let lowerBase = baseName.toLowerCase();
  let foundExtension = true;

  while (foundExtension) {
    foundExtension = false;
    for (const ext of KNOWN_EXTENSIONS) {
      if (lowerBase.endsWith(ext)) {
        baseName = baseName.slice(0, baseName.length - ext.length);
        lowerBase = lowerBase.slice(0, lowerBase.length - ext.length);
        foundExtension = true;
        break;
      }
    }
    // 防死循环：如果没有点了，停止
    if (!lowerBase.includes('.')) break;
  }

  return baseName;
}

/** 数一数转换结果里有多少条带时间轴的歌词，用来判断这份字幕是不是空转了。 */
export function countTimedLines(lrcContent) {
  let count = 0;
  for (const line of lrcContent.split('\n')) {
    if (/^\[\d/.test(line)) count += 1;
  }
  return count;
}
