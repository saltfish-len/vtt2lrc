/**
 * 转换核心的单元测试。跑法：node --test tests/
 *
 * 这里的每条断言都对着 app/src/main/java/com/saltfishlen/vtt2lrc/VttUtils.kt 写，
 * 网页版和 Android 版必须对同一份 .vtt 给出同一份 .lrc。
 * 改了任何一侧的转换逻辑，这些用例应该同时失败。
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  convertToLrc,
  getOutputFileName,
  countTimedLines,
} from '../web/assets/vtt2lrc.js';

test('最简单的一段字幕', () => {
  const vtt = 'WEBVTT\n\n00:00:01.000 --> 00:00:04.000\nHello\n';
  assert.equal(convertToLrc(vtt, 'song'), '[ti:song]\n[00:01.00]Hello\n');
});

test('毫秒截断成厘秒，不四舍五入', () => {
  const vtt = 'WEBVTT\n\n00:00:01.239 --> 00:00:04.000\nHello\n';
  // 239 / 10 = 23，不是 24
  assert.equal(convertToLrc(vtt, 'x'), '[ti:x]\n[00:01.23]Hello\n');
});

test('小时并进分钟，可以超过两位', () => {
  const vtt = 'WEBVTT\n\n02:03:04.500 --> 02:03:09.000\n很长的音频\n';
  // 2 小时 3 分 = 123 分
  assert.equal(convertToLrc(vtt, 'x'), '[ti:x]\n[123:04.50]很长的音频\n');
});

test('没有小时段的时间戳按 MM:SS 解析', () => {
  const vtt = 'WEBVTT\n\n09:08.070 --> 09:12.000\n只有分秒\n';
  assert.equal(convertToLrc(vtt, 'x'), '[ti:x]\n[09:08.07]只有分秒\n');
});

test('一条字幕的多行用空格连起来', () => {
  const vtt = 'WEBVTT\n\n00:00:01.000 --> 00:00:04.000\n第一行\n第二行\n';
  assert.equal(convertToLrc(vtt, 'x'), '[ti:x]\n[00:01.00]第一行 第二行\n');
});

test('去掉标签，且去掉后不再 trim（与 Kotlin 侧一致）', () => {
  const vtt = 'WEBVTT\n\n00:00:01.000 --> 00:00:04.000\n<v Bob> 你好\n';
  // "<v Bob> 你好" 去标签后是 " 你好"，前导空格保留
  assert.equal(convertToLrc(vtt, 'x'), '[ti:x]\n[00:01.00] 你好\n');
});

test('整行都是标签时该行被丢掉', () => {
  const vtt = 'WEBVTT\n\n00:00:01.000 --> 00:00:04.000\n<b></b>\n有内容\n';
  assert.equal(convertToLrc(vtt, 'x'), '[ti:x]\n[00:01.00]有内容\n');
});

test('WEBVTT 头、NOTE 注释、纯数字序号都跳过', () => {
  const vtt = [
    'WEBVTT',
    '',
    'NOTE 这是一条注释',
    '',
    '1',
    '00:00:01.000 --> 00:00:04.000',
    '正文',
    '',
    '2',
    '00:00:05.000 --> 00:00:08.000',
    '第二句',
    '',
  ].join('\n');
  assert.equal(convertToLrc(vtt, 'x'), '[ti:x]\n[00:01.00]正文\n[00:05.00]第二句\n');
});

test('时间戳行后面的样式参数被忽略', () => {
  const vtt = 'WEBVTT\n\n00:00:01.000 --> 00:00:04.000 align:start position:0%\n正文\n';
  assert.equal(convertToLrc(vtt, 'x'), '[ti:x]\n[00:01.00]正文\n');
});

test('没有文本的空段落不产出歌词行', () => {
  const vtt = [
    'WEBVTT',
    '',
    '00:00:01.000 --> 00:00:04.000',
    '',
    '00:00:05.000 --> 00:00:08.000',
    '有内容',
    '',
  ].join('\n');
  assert.equal(convertToLrc(vtt, 'x'), '[ti:x]\n[00:05.00]有内容\n');
});

test('最后一段字幕不会被漏掉', () => {
  const vtt = 'WEBVTT\n\n00:00:01.000 --> 00:00:04.000\n最后一句';
  assert.equal(convertToLrc(vtt, 'x'), '[ti:x]\n[00:01.00]最后一句\n');
});

test('CRLF 和纯 CR 换行都能正确切分', () => {
  const crlf = 'WEBVTT\r\n\r\n00:00:01.000 --> 00:00:04.000\r\n正文\r\n';
  const cr = 'WEBVTT\r\r00:00:01.000 --> 00:00:04.000\r正文\r';
  const expected = '[ti:x]\n[00:01.00]正文\n';
  assert.equal(convertToLrc(crlf, 'x'), expected);
  assert.equal(convertToLrc(cr, 'x'), expected);
});

test('时间轴之前的文本被忽略', () => {
  const vtt = 'WEBVTT - 带标题的头\n\nSTYLE\n\n00:00:01.000 --> 00:00:04.000\n正文\n';
  assert.equal(convertToLrc(vtt, 'x'), '[ti:x]\n[00:01.00]正文\n');
});

test('没有任何时间轴时只剩标题行', () => {
  assert.equal(convertToLrc('WEBVTT\n\n随便写点什么\n', 'x'), '[ti:x]\n');
  assert.equal(countTimedLines(convertToLrc('WEBVTT\n', 'x')), 0);
});

test('前导零按十进制解析，不会被当成八进制', () => {
  const vtt = 'WEBVTT\n\n00:08:09.080 --> 00:09:00.000\n正文\n';
  assert.equal(convertToLrc(vtt, 'x'), '[ti:x]\n[08:09.08]正文\n');
});

test('countTimedLines 数的是带时间轴的行，不含标题', () => {
  const lrc = convertToLrc(
    'WEBVTT\n\n00:00:01.000 --> 00:00:02.000\na\n\n00:00:03.000 --> 00:00:04.000\nb\n',
    'x'
  );
  assert.equal(countTimedLines(lrc), 2);
});

/* ---------------- 文件名清洗 ---------------- */

test('剥掉 .vtt 后缀', () => {
  assert.equal(getOutputFileName('song.vtt', false), 'song');
  assert.equal(getOutputFileName('song.VTT', false), 'song');
});

test('开启整理时继续剥掉嵌套的已知扩展名', () => {
  assert.equal(getOutputFileName('song.mp3.vtt', true), 'song');
  assert.equal(getOutputFileName('movie.mp4.mkv.vtt', true), 'movie');
});

test('关闭整理时只剥 .vtt', () => {
  assert.equal(getOutputFileName('song.mp3.vtt', false), 'song.mp3');
});

test('遇到不认识的后缀就停下，不会把名字吃光', () => {
  assert.equal(getOutputFileName('a.b.mp4.vtt', true), 'a.b');
  assert.equal(getOutputFileName('2024.01.01.vtt', true), '2024.01.01');
});

test('名字里没有点时不会误伤', () => {
  assert.equal(getOutputFileName('song.vtt', true), 'song');
  assert.equal(getOutputFileName('无扩展名', true), '无扩展名');
});

test('扩展名匹配忽略大小写但保留原名大小写', () => {
  assert.equal(getOutputFileName('Song.MP3.vtt', true), 'Song');
});

test('不是 .vtt 的名字原样返回（调用方已保证只传 .vtt）', () => {
  assert.equal(getOutputFileName('song.srt', false), 'song.srt');
});
