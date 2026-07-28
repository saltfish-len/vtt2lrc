<div align="center">
  <img src="docs/images/cover.png" width="128" height="128" alt="App Icon"style="border-radius: 10px;"/>

  <br>

  # VTT to LRC Converter for Android

  **简单 • 高效 • 隐私安全**
  
  <p>
    专为 Android 设计的 WebVTT (.vtt) 转 LRC (.lrc) 歌词工具。<br>
    不支持联网，本地极速转换，完美还原时间轴。
  </p>
  
  <a href="https://github.com/saltfish-len/vtt2lrc/releases">
    <img src="https://img.shields.io/github/v/release/saltfish-len/vtt2lrc?include_prereleases&style=for-the-badge&color=25D366&logo=android" alt="Download APK" />
  </a>
  
  <br><br>
  <img src="docs/images/TITLE.png" width="100%" alt="Teaser Image" style="border-radius: 10px;"/>
</div>

---

## ✨ 功能特点 (Features)

*   **📂 批量原地转换**
    *   利用 Android SAF (Storage Access Framework) 机制，授权整个文件夹后，自动扫描并批量转换。
    *   直接在**同一目录**下生成 LRC 文件，无需繁琐导入导出。

*   **⚡ 极速处理**
    *   基于 Kotlin 协程开发，文件读写与正则解析在后台线程瞬间完成。
    *   轻松处理数千个字幕文件。

*   **🧹 智能文件名清洗**
    *   支持自动整理文件名，去除嵌套扩展名。
    *   示例：`song.mp3.vtt` -> `song.lrc` (而非 `song.mp3.lrc`)。

*   **📊 看得懂的进度**
    *   处理中显示进度环与「正在处理第 N 个，共 M 个」，不必盯着日志。
    *   出错时直接给出原因和对应的处理方法，而不是一行报错。
    *   详细过程记录默认折叠，排查问题时再展开。

*   **🌗 跟随系统深色模式**
    *   Material 3 配色，明暗两套主题。

*   **🔒 零隐私风险**
    *   纯本地运行，**无网络权限**，不上传任何文件。

*   **📝 完美编码支持**
    *   强制使用 UTF-8 编码读写，完美支持中文、日文及特殊符号，彻底告别乱码。

*   **🛠️ 高鲁棒性**
    *   采用双侧锚定的正则匹配算法，精确提取时间轴与歌词文本。

## 🚀 使用方法 (Usage)

1.  **准备文件**
    *   将 `.vtt` 字幕文件放进手机上任意一个**你自己建的文件夹**里。
    *   系统不允许 App 访问「内部存储」根目录，以及「下载」和「Android」文件夹，选中它们时文件选择器上方会显示一行灰色提示。

2.  **设置选项**
    *   打开 App，根据需要开启或关闭「自动整理文件名」。

3.  **授权并开始**
    *   点击 **「选择文件夹，开始转换」**。
    *   在系统文件选择器中，找到存放 VTT 的文件夹。
    *   点击底部 **「使用此文件夹」(Use this folder)** -> **「允许」(Allow)**。

4.  **完成**
    *   转换过程中显示进度与当前文件名，完成后 `.lrc` 文件将直接生成在原文件夹中。
    *   如果有文件没转成功，界面会列出原因和处理方法。

## ⚠️ 注意事项 (Important Notes)

> [!WARNING]
> **关于数据覆盖 (Overwrite Warning)**
>
> 本应用采用**原地写入**策略：
> 1. 如果检测到同名 `.lrc` 文件，程序将**直接覆盖旧文件**。
> 2. 此操作不可撤销。
> 3. **建议**：在批量操作前，请确保目标文件夹中没有需要保留的同名 `.lrc` 文件，或已做好备份。

> [!NOTE]
> **只处理所选的这一层**
>
> 扫描不会进入子文件夹。如果字幕分散在多级目录里，需要逐个目录选择。

## 🤖 开发幕后 (Development Story)

本项目是一个 **AI 协作实验**，代码完全由 LLM 生成与审查：

*   **👨‍💻 主程 (Architect & Coder): Google Gemini**
    *   负责项目架构设计、Android SAF 权限逻辑、Kotlin 核心代码及 Jetpack Compose UI 构建。
    *   提供了基于 Android 原生 API 的高性能实现方案。

*   **🕵️ 质检与调试 (QA & Debugger): ChatGPT**
    *   负责代码审查 (Code Review)，指出了原始实现中的潜在 Bug（如 UTF-8 编码、权限持久化、正则鲁棒性）。
    *   扮演“对抗者”角色，模拟极端边界情况，迫使代码方案进行了三轮迭代优化。

## 🔗 致谢与协议 (Credits & License)

核心处理流程参考了优秀的开源项目：
*   **[vtt-to-lrc-converter](https://github.com/meng0224/vtt-to-lrc-converter)** by @meng0224

**License**
*   This project is provided for **non-commercial use only**.
*   本项目仅供非商业用途使用。
