/**
 * 页面逻辑。转换本身在 vtt2lrc.js 里，这里只负责选文件、报进度、给结果。
 * 全程在浏览器本地完成，没有任何网络请求。
 */
import { convertToLrc, getOutputFileName, countTimedLines } from './vtt2lrc.js';

const $ = (id) => document.getElementById(id);

const dom = {
  statusIdle: $('statusIdle'),
  statusBusy: $('statusBusy'),
  statusDone: $('statusDone'),
  ring: $('ring'),
  ringFill: $('ringFill'),
  ringLabel: $('ringLabel'),
  busyTitle: $('busyTitle'),
  busyFile: $('busyFile'),
  doneTitle: $('doneTitle'),
  doneHint: $('doneHint'),
  guidance: $('guidance'),
  resultsCard: $('resultsCard'),
  resultList: $('resultList'),
  downloadAllBtn: $('downloadAllBtn'),
  downloadAllHint: $('downloadAllHint'),
  detailsCard: $('detailsCard'),
  detailLog: $('detailLog'),
  cleanNameToggle: $('cleanNameToggle'),
  cleanNameHint: $('cleanNameHint'),
  pickFilesBtn: $('pickFilesBtn'),
  pickFolderBtn: $('pickFolderBtn'),
  fileInput: $('fileInput'),
  folderInput: $('folderInput'),
  dropzone: $('dropzone'),
};

const RING_CIRCUMFERENCE = 2 * Math.PI * 20;
const MAX_DETAIL_LINES = 400;
const STORAGE_KEY = 'vtt2lrc.cleanName';
const REPLACEMENT_CHAR = '�';

const ua = navigator.userAgent || '';

/** iPadOS 13 起默认发桌面 UA，靠触点数把它认出来。 */
const isIOS = /iPad|iPhone|iPod/.test(ua) ||
  (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
const isHarmony = /HarmonyOS|OpenHarmony/i.test(ua);

/** 返回 [主版本, 次版本]，认不出来就返回 null。 */
function iosVersion() {
  if (!isIOS) return null;
  const os = /OS (\d+)[._](\d+)/.exec(ua);          // iPhone OS 18_4 like Mac OS X
  if (os) return [Number(os[1]), Number(os[2])];
  const ver = /Version\/(\d+)\.(\d+)/.exec(ua);     // iPad 桌面 UA 时退回 Safari 版本号
  if (ver) return [Number(ver[1]), Number(ver[2])];
  return null;
}

/**
 * 能不能用「选文件夹」。iOS 要到 Safari 18.4 才支持，之前的版本属性在但点了没反应，
 * 所以这里按版本号挡掉，宁可不给按钮也不给一个死按钮。
 */
function supportsDirectoryPick() {
  if (!('webkitdirectory' in HTMLInputElement.prototype)) return false;
  if (/SamsungBrowser/i.test(ua)) return false;     // 三星浏览器至今不支持
  const ios = iosVersion();
  if (ios && (ios[0] < 18 || (ios[0] === 18 && ios[1] < 4))) return false;
  return true;
}

const state = {
  phase: 'idle',        // idle | busy | done
  total: 0,
  done: 0,
  currentFile: '',
  results: [],          // { sourceName, outputName, content, timedLines }
  failures: [],         // { fileName, what, how, level }
  blocker: null,
  details: [],
  lastFiles: [],        // 记着上次选的文件，改了设置可以直接重转
};

/* ---------------- 「哪里出问题了 + 该怎么办」 ---------------- */
/* 思路沿用 Android 版 ui/JobState.kt 里的 Guidance：避开技术词，给一句能照着做的话。 */
const Guidance = {
  noVttSelected: (pickedCount) => ({
    fileName: null,
    level: 'error',
    what: pickedCount > 0 ? '选中的文件里没有 .vtt' : '没有选中文件',
    how: '本页只处理 WebVTT 字幕，也就是扩展名为 .vtt 的文件。' +
      '如果手里是 .srt、.ass 等其他格式，需要先转成 .vtt 再来。',
  }),
  skippedNotVtt: (fileName) => ({
    fileName,
    level: 'warn',
    what: '跳过了非 .vtt 文件',
    how: '本页只处理 .vtt，其余文件原样留着，不受影响。',
  }),
  readFailed: (fileName) => ({
    fileName,
    level: 'error',
    what: '无法读取该文件',
    how: '文件可能已被移动或删除。请重新选择一次；' +
      '如果是从聊天软件里直接打开的，先存到「文件」里再选。',
  }),
  notUtf8: (fileName) => ({
    fileName,
    level: 'warn',
    what: '该文件可能不是 UTF-8 编码，歌词里会出现乱码',
    how: '按 UTF-8 读取是为了和 Android 版保持一致。' +
      '请在电脑上用记事本或 VS Code 把字幕另存为 UTF-8，再回来转换。',
  }),
  noCue: (fileName) => ({
    fileName,
    level: 'warn',
    what: '该文件里没有找到时间轴',
    how: '生成的 .lrc 只有一行标题。请确认它确实是 WebVTT 字幕，内容里应当有这样的时间行：',
    example: '00:00:01.000 --> 00:00:04.000',
  }),
  duplicateName: (fileName) => ({
    fileName,
    level: 'warn',
    what: '有多个文件会生成同名歌词，已自动加编号区分',
    how: '通常是 song.mp3.vtt 和 song.mp4.vtt 这样同名不同源导致的。' +
      '如果不想要编号，可以关掉「自动整理文件名」再转一次。',
  }),
  unexpected: (fileName) => ({
    fileName,
    level: 'error',
    what: '该文件在转换过程中出错',
    how: '其余文件不受影响。如果每次都卡在同一个文件上，请把它单独选出来再试一次。',
  }),
};

/** 同一个原因的多个文件合并成一条，20 个文件失败不该刷出 20 张一模一样的卡片。 */
function groupedByCause(failures) {
  const groups = new Map();
  for (const f of failures) {
    const key = JSON.stringify([f.level, f.what, f.how]);
    if (!groups.has(key)) {
      groups.set(key, { level: f.level, what: f.what, how: f.how, example: f.example, names: [] });
    }
    if (f.fileName) groups.get(key).names.push(f.fileName);
  }
  return Array.from(groups.values()).map((g) => ({
    level: g.level,
    what: g.what,
    how: g.how,
    example: g.example,
    fileName:
      g.names.length === 0 ? null
        : g.names.length === 1 ? g.names[0]
          : g.names.slice(0, 2).join('、') + ' 等 ' + g.names.length + ' 个文件',
  }));
}

function detail(line) {
  state.details.push(line);
  if (state.details.length > MAX_DETAIL_LINES) {
    state.details = state.details.slice(-MAX_DETAIL_LINES);
  }
}

/* ---------------- 转换流程 ---------------- */

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** file.text() 在老一点的 WebView 上没有，退回 FileReader。 */
function readText(file) {
  if (typeof file.text === 'function') return file.text();
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error || new Error('read failed'));
    reader.readAsText(file, 'UTF-8');
  });
}

async function handleFiles(fileList) {
  const files = Array.from(fileList || []);
  if (files.length === 0) return;
  state.lastFiles = files;
  await convertFiles(files);
}

async function convertFiles(files) {
  state.phase = 'busy';
  state.results = [];
  state.failures = [];
  state.blocker = null;
  state.details = [];
  state.done = 0;
  state.currentFile = '';

  const vttFiles = [];
  for (const f of files) {
    if (f.name.toLowerCase().endsWith('.vtt')) {
      vttFiles.push(f);
    } else {
      state.failures.push(Guidance.skippedNotVtt(f.name));
      detail('跳过 ' + f.name + '（不是 .vtt）');
    }
  }

  if (vttFiles.length === 0) {
    state.phase = 'done';
    state.blocker = Guidance.noVttSelected(files.length);
    detail(state.blocker.what);
    render();
    return;
  }

  state.total = vttFiles.length;
  detail('共找到 ' + vttFiles.length + ' 个 .vtt 文件');
  render();

  const removeNested = dom.cleanNameToggle.checked;
  const usedNames = new Map();

  for (let i = 0; i < vttFiles.length; i += 1) {
    const file = vttFiles[i];
    state.currentFile = file.name;
    render();

    let content;
    try {
      content = await readText(file);
    } catch (err) {
      state.done += 1;
      state.failures.push(Guidance.readFailed(file.name));
      detail('× ' + file.name + ' 读取失败：' + (err && err.message));
      continue;
    }

    try {
      // 去掉 UTF-8 BOM。带 BOM 时首行本来也不会被认成时间轴，转换结果一样，
      // 去掉只是让后续判断干净些。
      if (content.charCodeAt(0) === 0xfeff) content = content.slice(1);

      const baseName = getOutputFileName(file.name, removeNested);
      const lrcContent = convertToLrc(content, baseName);
      const timedLines = countTimedLines(lrcContent);

      // 同名冲突：Android 版是原地覆盖，网页版覆盖不了，改成加编号并提示。
      let outputName = baseName + '.lrc';
      const key = outputName.toLowerCase();
      const seen = usedNames.get(key);
      if (seen) {
        const next = seen + 1;
        usedNames.set(key, next);
        outputName = baseName + ' (' + next + ').lrc';
        state.failures.push(Guidance.duplicateName(file.name));
        detail('! ' + file.name + ' 与前面的文件重名，输出为 ' + outputName);
      } else {
        usedNames.set(key, 1);
      }

      if (content.indexOf(REPLACEMENT_CHAR) !== -1) {
        state.failures.push(Guidance.notUtf8(file.name));
        detail('! ' + file.name + ' 含无法解码的字符，可能不是 UTF-8');
      }
      if (timedLines === 0) {
        state.failures.push(Guidance.noCue(file.name));
        detail('! ' + file.name + ' 没有找到时间轴');
      }

      state.results.push({
        sourceName: file.name,
        outputName: outputName,
        content: lrcContent,
        timedLines: timedLines,
      });
      state.done += 1;
      detail('√ ' + file.name + ' → ' + outputName + '（' + timedLines + ' 行）');
    } catch (err) {
      state.done += 1;
      state.failures.push(Guidance.unexpected(file.name));
      detail('× ' + file.name + ' 转换失败：' + (err && err.message));
    }

    // 每处理几个让出一次主线程，文件多的时候进度环才动得起来
    if (i % 5 === 4) {
      render();
      await sleep(0);
    }
  }

  state.phase = 'done';
  state.currentFile = '';
  render();
}

/* ---------------- 保存 ---------------- */

function saveAs(name, text) {
  // 用 octet-stream 而不是 text/plain：部分浏览器会把纯文本直接在标签页里打开，
  // 而不是存成文件。内容按 UTF-8 编码、不写 BOM，与 Android 版一致。
  const blob = new Blob([text], { type: 'application/octet-stream' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.rel = 'noopener';
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 20000);
}

/**
 * 「全部下载」的提示语按平台说实话：
 * 安卓同意一次就全下来；iOS 的 download 是异步的，循环触发常常只成第一个；
 * 鸿蒙每个文件都会弹一次保存位置选择器。
 */
function downloadAllHintText() {
  if (isIOS) {
    return 'iOS 会逐个询问，而且经常只保存下第一个。如果没有全部拿到，请用每条结果自己的「下载」。';
  }
  if (isHarmony) {
    return '每个文件都会弹一次「保存到哪」，需要逐个确认。也可以用每条结果自己的「下载」。';
  }
  return '浏览器可能会问一句「是否允许下载多个文件」，同意即可。也可以在下面逐个下载。';
}

/* ---------------- 渲染 ---------------- */

function render() {
  const phase = state.phase;

  dom.statusIdle.hidden = phase !== 'idle';
  dom.statusBusy.hidden = phase !== 'busy';
  dom.statusDone.hidden = phase !== 'done';

  if (phase === 'busy') {
    const fraction = state.total > 0 ? Math.min(state.done / state.total, 1) : 0;
    const percent = Math.round(fraction * 100);
    dom.ringFill.style.strokeDasharray = String(RING_CIRCUMFERENCE);
    dom.ringFill.style.strokeDashoffset = String(RING_CIRCUMFERENCE * (1 - fraction));
    dom.ringLabel.textContent = percent + '%';
    dom.ring.setAttribute('aria-valuenow', String(percent));
    dom.busyTitle.textContent =
      '正在转换第 ' + Math.min(state.done + 1, state.total) + ' 个，共 ' + state.total + ' 个';
    dom.busyFile.textContent = state.currentFile;
  }

  if (phase === 'done') {
    const ok = state.results.length;
    const bad = state.failures.filter((f) => f.level === 'error').length;
    dom.statusDone.classList.toggle('has-failure', ok === 0 || bad > 0);
    if (ok === 0) {
      dom.doneTitle.textContent = '没有转换出歌词';
      dom.doneHint.textContent = '看下面的说明，处理后再试一次。';
    } else {
      dom.doneTitle.textContent = '已转换 ' + ok + ' 个文件';
      dom.doneHint.textContent = bad > 0
        ? '另有 ' + bad + ' 个文件没能转换，原因见下方。'
        : '在下面把歌词保存到设备。';
    }
  }

  // 转换中不重建结果列表、提示卡和日志：每处理一个文件都全量重建的话，
  // N 个文件就是 N 次重建，实测 400 个文件要十几秒。转换中只更新上面的进度环，
  // 列表等结束后一次性渲染；results 为空时仍渲染一次，用来清掉上一轮的内容。
  if (phase !== 'busy' || state.results.length === 0) {
    renderGuidance();
    renderResults();
    renderDetails();
  }

  const busy = phase === 'busy';
  dom.pickFilesBtn.disabled = busy;
  dom.pickFolderBtn.disabled = busy;
  dom.cleanNameToggle.disabled = busy;
  dom.pickFilesBtn.textContent = busy ? '正在转换…' : '选择 .vtt 文件';
}

function guidanceCard(failure) {
  const box = document.createElement('div');
  box.className = 'guidance' + (failure.level === 'warn' ? ' is-warn' : '');

  const title = document.createElement('h3');
  title.textContent = failure.what;
  box.appendChild(title);

  const how = document.createElement('p');
  how.textContent = failure.how;
  box.appendChild(how);

  // 时间轴示例单独一行：混在正文里的话 --> 会在折行处断成「- ->」，看着像打错字
  if (failure.example) {
    const example = document.createElement('p');
    example.className = 'example';
    const code = document.createElement('code');
    code.textContent = failure.example;
    example.appendChild(code);
    box.appendChild(example);
  }

  if (failure.fileName) {
    const who = document.createElement('p');
    who.className = 'who';
    who.textContent = failure.fileName;
    box.appendChild(who);
  }
  return box;
}

function renderGuidance() {
  dom.guidance.textContent = '';
  if (state.blocker) dom.guidance.appendChild(guidanceCard(state.blocker));
  for (const f of groupedByCause(state.failures)) {
    dom.guidance.appendChild(guidanceCard(f));
  }
}

function renderResults() {
  const results = state.results;
  dom.resultsCard.hidden = results.length === 0;
  dom.resultList.textContent = '';
  if (results.length === 0) return;

  const many = results.length > 1;
  dom.downloadAllBtn.hidden = !many;
  dom.downloadAllHint.hidden = !many;
  if (many) dom.downloadAllHint.textContent = downloadAllHintText();

  const frag = document.createDocumentFragment();
  for (const result of results) frag.appendChild(resultItem(result));
  dom.resultList.appendChild(frag);
}

function resultItem(result) {
  const li = document.createElement('li');
  li.className = 'result-item';

  const head = document.createElement('div');
  head.className = 'result-head';

  const text = document.createElement('div');
  text.className = 'result-text';

  const name = document.createElement('div');
  name.className = 'result-name';
  name.textContent = result.outputName;
  text.appendChild(name);

  const from = document.createElement('div');
  from.className = 'muted small result-from';
  from.textContent = '来自 ' + result.sourceName + ' · ' + result.timedLines + ' 行歌词';
  text.appendChild(from);

  head.appendChild(text);

  const downloadBtn = document.createElement('button');
  downloadBtn.type = 'button';
  downloadBtn.className = 'btn btn-small is-primary';
  downloadBtn.textContent = '下载';
  downloadBtn.addEventListener('click', () => saveAs(result.outputName, result.content));
  head.appendChild(downloadBtn);

  li.appendChild(head);

  const preview = document.createElement('details');
  preview.className = 'preview';
  const summary = document.createElement('summary');
  summary.textContent = '预览';
  preview.appendChild(summary);
  const pre = document.createElement('pre');
  pre.className = 'lyrics';
  pre.textContent = result.content;
  preview.appendChild(pre);
  li.appendChild(preview);

  return li;
}

function renderDetails() {
  dom.detailsCard.hidden = state.details.length === 0;
  dom.detailLog.textContent = state.details.join('\n');
}

/* ---------------- 事件绑定 ---------------- */

function updateCleanNameHint() {
  dom.cleanNameHint.textContent = dom.cleanNameToggle.checked
    ? 'song.mp3.vtt 转换为 song.lrc（推荐）'
    : 'song.mp3.vtt 转换为 song.mp3.lrc';
}

function loadPreference() {
  try {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (saved !== null) dom.cleanNameToggle.checked = saved === '1';
  } catch (err) {
    // 隐私模式下 localStorage 可能直接抛错，用默认值就行
  }
  updateCleanNameHint();
}

function savePreference() {
  try {
    localStorage.setItem(STORAGE_KEY, dom.cleanNameToggle.checked ? '1' : '0');
  } catch (err) {
    // 存不下就算了
  }
}

dom.cleanNameToggle.addEventListener('change', () => {
  updateCleanNameHint();
  savePreference();
  // 改了设置就按新设置重转一遍，省得用户再选一次文件
  if (state.lastFiles.length > 0 && state.phase !== 'busy') {
    convertFiles(state.lastFiles);
  }
});

dom.pickFilesBtn.addEventListener('click', () => dom.fileInput.click());
dom.pickFolderBtn.addEventListener('click', () => dom.folderInput.click());

for (const input of [dom.fileInput, dom.folderInput]) {
  input.addEventListener('change', () => {
    const picked = Array.from(input.files || []);
    // 先清空再处理，这样连着选同一批文件也能触发 change
    input.value = '';
    handleFiles(picked);
  });
}

dom.downloadAllBtn.addEventListener('click', async () => {
  const results = state.results.slice();
  dom.downloadAllBtn.disabled = true;
  const original = dom.downloadAllBtn.textContent;
  for (let i = 0; i < results.length; i += 1) {
    dom.downloadAllBtn.textContent = '下载中 ' + (i + 1) + '/' + results.length;
    saveAs(results[i].outputName, results[i].content);
    // 连着触发会被浏览器合并或拦掉，隔一下更稳；iOS 更慢，给足时间
    await sleep(isIOS ? 900 : 300);
  }
  dom.downloadAllBtn.textContent = original;
  dom.downloadAllBtn.disabled = false;
});

/* 桌面端拖放。手机上没有拖放，这几段不会被触发。 */
let dragDepth = 0;
window.addEventListener('dragenter', (e) => {
  if (!e.dataTransfer || Array.prototype.indexOf.call(e.dataTransfer.types, 'Files') === -1) return;
  dragDepth += 1;
  dom.dropzone.hidden = false;
});
window.addEventListener('dragover', (e) => {
  if (!dom.dropzone.hidden) e.preventDefault();
});
window.addEventListener('dragleave', () => {
  dragDepth = Math.max(0, dragDepth - 1);
  if (dragDepth === 0) dom.dropzone.hidden = true;
});
window.addEventListener('drop', (e) => {
  if (!e.dataTransfer) return;
  e.preventDefault();
  dragDepth = 0;
  dom.dropzone.hidden = true;
  handleFiles(e.dataTransfer.files);
});

if (supportsDirectoryPick()) dom.pickFolderBtn.hidden = false;

loadPreference();
render();
