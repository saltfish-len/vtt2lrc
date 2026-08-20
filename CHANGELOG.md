# 更新日志（experimental 分支）

本分支不发布 APK 或其他二进制文件，因此没有版本号对应的发布记录，只按改动记录。

本分支链接了 `ffmpeg-kit-full-gpl`，整体构成 GPL 派生作品。一旦分发二进制，需同时提供对应源码，并去掉正式版那套「仅限非商业用途」的限制。

正式版（`com.saltfishlen.vtt2lrc`）的发布记录见 `main` 分支的 CHANGELOG。

## 未发布

`applicationId` `com.saltfishlen.vtt2lrc.exp` · `versionCode` 1 · `versionName` 1.0

### 合入 main

把 `main` 合了进来，本分支不再落后正式版。带进来的主要是网页版及其配套：

- `web/`：纯网页版字幕转换器，从 `main` 分支部署在 <https://saltfish-len.github.io/vtt2lrc/>
- `tests/vtt2lrc.test.mjs`：转换核心的单元测试，`node --test tests/vtt2lrc.test.mjs`
- `.github/workflows/pages.yml`：Pages 部署，只在推送到 `main` 时发布，本分支的改动不会触发
- `VttUtils.kt` 加了一条注释，指向网页版的对照实现

FFmpeg 相关的代码、双页签界面和 GPL 声明都未受影响。

`versionCode` 与 `versionName` 保持本分支的 1 / 1.0，没有跟随正式版的 3 / 1.2 —— `.exp` 是独立应用，两条版本线各自递增。

### 拆分 applicationId

此前本分支与正式版共用 `com.saltfishlen.vtt2lrc`，两者的 `versionCode` 因此必须全局单调，互相覆盖安装也说不通（9 MB vs 160 MB）。现在改为 `.exp` 后缀，两者可同时安装、版本号各自递增。启动器中显示为「VTT原地转换 实验版」。

`namespace` 仍为 `com.saltfishlen.vtt2lrc`，源码未受影响。

### 界面重做

与正式版同步。以前主界面是一块滚动的运行日志，现在换成：

- 处理中显示进度环、「正在处理第 N 个，共 M 个」和当前文件名
- 完成后显示成功数量与保存位置
- 出错时列出原因和对应的处理方法
- 详细过程记录收进折叠的「查看详细过程」，FFmpeg 的逐步日志开关也移入其中

配色补齐了 Material 3 的明暗两套主题。扩展名多选改为 filter chip，音质改为分段按钮，复选框改为整行可点的开关。

### 修正

文件选择的引导此前建议把文件放在 Download 文件夹，但较新的 Android 不允许 App 打开该目录。改为提示使用自建文件夹。

### 已知问题

单个文件模式依赖从文件路径反推输出目录，在部分设备上会失败，此时界面提示改用文件夹模式。

## 更早

- `wav -> mp3`
- `video -> audio`：引入 `ffmpeg-kit-full-gpl`，支持从视频提取 MP3
