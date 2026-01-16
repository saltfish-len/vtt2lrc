# <img src="docs/images/cover.png" width="50" /> VTT to LRC Converter for Android

一个简单、高效、隐私安全的 Android 本地工具，用于将 WebVTT (`.vtt`) 字幕文件批量转换为 LRC (`.lrc`) 歌词文件。

专为 Android 现代系统设计，支持**文件夹批量操作**与**原地转换**。

可在release处下载：
https://github.com/saltfish-len/vtt2lrc/releases
选择Assets中的app-release.apk

# Experimental Branch

本分支（`experimental`）为实验性开发分支，用于验证基于 FFmpeg 的音频处理能力，不作为正式功能或发布版本使用。

## ✨ 功能特点 (Overview)

* **📂 批量原地转换**：利用 Android SAF (Storage Access Framework) 机制，用户授权整个文件夹后，App 会自动扫描并转换所有 VTT 文件，直接在**同一目录**下生成 LRC 文件，无需手动导入导出。
* **⚡ 极速处理**：基于 Kotlin 协程开发，文件读写与正则解析在后台线程瞬间完成，支持处理大量文件。
* **🧹 智能文件名整理**：支持去除嵌套扩展名。
    * 示例：`song.mp3.vtt` -> `song.lrc` (而非 `song.mp3.lrc`)。
* **🔒 隐私安全**：纯本地运行，不消耗流量，不上传任何文件至服务器。
* **📝 完美编码支持**：强制使用 UTF-8 编码读写，完美支持中文、日文及特殊符号，杜绝乱码。
* **🛠️ 高鲁棒性**：采用双侧锚定的正则匹配算法，精准提取时间轴与歌词文本。

## 🚀 使用方法 (Usage)

1.  **准备文件**：将你的 `.vtt` 字幕文件保存在手机的任意文件夹中（如 Download 或 Movies 下的子文件夹）。
2.  **选择模式**：打开 App，根据需要勾选或取消“去除嵌套扩展名”选项。
3.  **授权目录**：
    * 点击“选择文件夹并开始转换”按钮。
    * 在弹出的系统文件选择器中，找到你存放 VTT 的文件夹。
    * 点击底部的 **“使用此文件夹” (Use this folder)** 并点击 **“允许” (Allow)**。
4.  **等待完成**：App 会自动扫描并显示转换进度和日志。转换完成后，`.lrc` 文件将直接出现在源文件夹中。

## ⚠️ 注意事项 (Important Notes)

**关于数据覆盖（Overwrite Warning）：**

本应用采用**原地写入**策略，逻辑如下：
1.  扫描 `.vtt` 文件。
2.  计算目标 `.lrc` 文件名。
3.  **如果同名 `.lrc` 文件已存在，程序将直接覆盖旧文件**（实际上是先删除旧文件再写入新文件，以防止某些系统自动重命名为 `xxx (1).lrc`）。

> **建议**：在对重要数据进行批量操作前，请确保源文件夹中没有同名的重要 `.lrc` 文件，或已做好备份。

## 🤖 开发幕后：AI "对抗"生成 (Development Story)

本项目是一个有趣的 AI 协作实验，代码完全由 LLM 生成与审查：

* **👨‍💻 主程 (Architect & Coder): Google Gemini**
    * 负责项目架构设计、Android SAF 权限逻辑实现、Kotlin 核心代码编写以及 UI 构建 (Jetpack Compose)。
    * 提供了基于 Android 原生 API 的高性能实现方案。

* **🕵️ 质检与调试 (QA & Debugger): ChatGPT**
    * 负责代码审查 (Code Review)，指出了原始实现中的潜在 Bug（如 UTF-8 编码缺失、权限持久化遗漏、正则匹配鲁棒性问题）。
    * 扮演“对抗者”角色，模拟极端边界情况，迫使代码方案进行了三轮迭代优化。

通过这种“生成 -> 审查 -> 修正”的循环，最终产出了这套既符合 Android 现代开发规范，又具备极高稳定性的代码。

## 🔗 参考与致谢 (Credits)

本项目的处理流程参考了非常好用的网页版转换器开源项目（记得给它⭐️）：

* **[vtt-to-lrc-converter](https://github.com/meng0224/vtt-to-lrc-converter)** by @meng0224

Android 版本在此基础上针对移动端文件系统进行了深度适配。

---
## License

本分支引入并链接了 `ffmpeg-kit-full-gpl-6.0-2.LTS.aar`，该库封装了启用 GPL 组件的 FFmpeg。  
因此，本分支代码整体构成 GPL 派生作品，并受 **GNU General Public License（GPL）** 约束。

根据 GPL 许可条款：

- 允许自由使用、修改和再分发本分支代码  
- 任何再分发版本必须继续采用 GPL 许可证  
- 不得附加任何额外限制

## Distribution

- 本分支未发布任何 APK 或其他二进制文件  
- 所有正式发布版本均不包含 FFmpeg 或 GPL 依赖
