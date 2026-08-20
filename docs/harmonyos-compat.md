# 鸿蒙兼容性调研（已结案：不适配）

> **结论（2026-08-19）：放弃鸿蒙适配。** 本文档保留为调研记录，避免日后重复排查。
>
> 决策理由见第七节。**注意：第四节的 B / C / E / F 是与鸿蒙无关的真实缺陷，不随本决策作废**，已移入第六节作为普通待办。

> 调研日期：2026-07-28 · 分支：`feature/ui-redesign`（experimental 系）
> 触发原因：用户在鸿蒙设备上遇到「创建文件失败」。
> **2026-08-19 修订**：目标设备确认为华为 Pura 80 Pro / HarmonyOS 6.1，安装方式确认为「点 APK 直接安装」。
> 据此推翻了初版的核心判断，并确定运行环境为卓易通容器，见第二、三节。

## 一、现状

仓库对鸿蒙**没有任何针对性适配**。全仓库 `grep -ri "harmony|ohos|huawei|hms|鸿蒙"` 零命中。

这是一个纯 Android 工程：Jetpack Compose + SAF / `DocumentFile`，`minSdk 24` / `targetSdk 36`。

## 二、目标设备与运行环境（已定案）

### 设备档案

| 项 | 值 |
|---|---|
| 机型 | 华为 Pura 80 Pro（国行，2025-06 发布） |
| 系统 | HarmonyOS 6.1（Pura 80 系列已推送 6.1.0.135 SP8） |
| 出厂系统 | HarmonyOS 5.1，**不支持回退，国行无安卓版可选** |
| 底座 | 鸿蒙内核，**已移除 AOSP** |
| 原生装 APK | **不能** |
| 本 App 实际运行于 | **卓易通容器**（iSulad，共享内核 + 环境隔离） |

### 三种运行环境，本机已确认属于第三种

| | HarmonyOS ≤4 / EMUI（含海外机） | HarmonyOS 5 / 6（纯血，本机） | 第三方容器（卓易通 / 出境易） |
|---|---|---|---|
| 底座 | 基于 AOSP，保留 ART | 鸿蒙内核，无 AOSP | 跑在鸿蒙上的普通 App，内含精简安卓运行环境 |
| 装 APK | 能 | **不能** | 能，但在独立沙箱内 |
| SAF 实现 | AOSP `ExternalStorageProvider` + 华为改动 | 不存在 | 容器自己实现，无稳定契约 |

HarmonyOS 5 起移除 AOSP 代码，系统层面的安卓兼容层已不复存在；HarmonyOS 6 只是去掉了 NEXT 后缀，延续同一路线，**并无内置安卓兼容层**。

### 初版的错误判断

初版写道：「用户既然进到了『创建文件失败』这一步，说明 App 已经跑起来，所以设备一定是 HarmonyOS 4.x/3.x，不是 NEXT。」

**这条推理错了。** 它漏掉了第三种可能：APK 通过第三方容器运行。在 HarmonyOS 6.1 上，这是唯一可能的运行方式。

## 三、运行环境已确认：卓易通容器

用户报告「APK 是直接装的」。这与「跑在容器里」**并不矛盾**，恰恰是卓易通设计出来的观感：

- 在鸿蒙上点击 APK，系统会像以前一样拉起安装界面；应用市场里遇到无鸿蒙原生版的应用，会提示「由卓易通提供服务」；
- 点安装后，系统在后台自动配置好兼容环境，桌面出现一个正常的应用图标；
- 整个过程对用户透明，观感与直接安装无异。

加上 Pura 80 系列国行出厂即 HarmonyOS 5.1、不支持回退、无安卓版可选，可以排除「这台其实是 AOSP 变体」的可能。

**结论：本 App 运行在卓易通容器内，而非鸿蒙原生环境，也非 AOSP 系统。**

### 容器的技术特征及其影响

卓易通采用华为自研的 **iSulad 容器技术**，共享内核 + 环境隔离，而非传统虚拟机模拟。对本问题的影响：

| 特征 | 对「创建文件失败」的含义 |
|---|---|
| 环境隔离 | 容器内的存储是映射进去的沙箱视图，**不等于**用户在系统文件管理器里看到的目录树 |
| SAF 由容器侧实现 | `ACTION_OPEN_DOCUMENT_TREE` 与 `DocumentsProvider` 都是卓易通自己的实现，无对外契约，与 AOSP 的差异远大于「厂商偏差」 |
| 已知短板 | 文件传输受限、通知缺失、分辨率降级等 |

## 四、问题清单（依据容器环境重新评估）


初版把 A–E 建立在「AOSP + 厂商改动」的前提上。改到容器环境后，这些条目的价值发生了变化，**不是全部作废**：

| 条目 | 容器环境下的重新评估 |
|---|---|
| **F** 可诊断性 | **最高优先级不变**。且现在多了一个用途：`treeUri.authority` 能直接暴露卓易通的 provider 实现 |
| **A** 非标 MIME | **升值**。受限的自研 provider 比 AOSP **更**可能带 MIME 白名单，非标类型直接拒。成本极低，值得一搏 |
| **B** 删后立即重建 | **升值**。容器的文件索引同步比原生更可能滞后 |
| **C** `"w"` 未截断 | 与环境无关，任何情况下都该修 |
| **D** 凭空拼 tree URI | **基本作废**。它只影响单文件模式（非主路径），且容器里更没戏。降为最低优先级 |
| **E** 多余权限声明 | 与环境无关，任何情况下都该修 |
| **G** 存储视图错位 | **新增，见下**。容器环境特有 |

### G. 容器的存储视图可能与系统文件管理器错位（新增）

由于 iSulad 的环境隔离，用户在鸿蒙系统文件管理器里看到、并在选择器中选中的那个文件夹，映射进容器后可能是只读视图，或根本不是同一个目录。

这能非常自然地解释「能读到 .vtt、却建不出 .lrc」——读走的是映射进来的内容，写要落回真实存储时被容器拦下。

**这一条我方无法修复**，只能通过诊断确认，然后在失败文案里给出可行的绕行建议（例如改用容器内可写的目录）。



### F. 拿不到诊断信息 —— 唯一确定该做的事

**这是当前最重要的一条，而且是唯一不依赖前提假设的一条。** 现在连「App 跑在什么环境里」都靠猜，A–E 的排序更是纯推理。三处缺口：

1. `createFile` 返回 null 时只报一句「无法在该文件夹中创建文件」（`ui/JobState.kt:156`），不含任何可定位信息；
2. `createFile` 没有包 try/catch，它抛出的 `SecurityException` / `UnsupportedOperationException` 的具体 message 被外层笼统的 catch 吃掉（`MainActivity.kt:503`、`MainActivity.kt:518`）；
3. **详细过程带不出设备** —— `AppComponents.kt:376` 的 `LogPanel` 是纯展示的 `LazyColumn`，没有复制或分享入口。这一条不解决，前两条等于白做。

补齐后，环境信息（`Build.MANUFACTURER` / `Build.DISPLAY` / `treeUri.authority`）本身就能反过来确认第三节那个问题 —— 容器通常会伪造 `Build` 字段，但 `authority` 会露馅。

### A. `text/x-lrc` 不是注册 MIME 类型

`MainActivity.kt:503`

```kotlin
val newFile = rootDir.createFile("text/x-lrc", lrcFileName)
```

AOSP 的 `FileSystemProvider.splitFileName()` 在「未知 MIME + 未知扩展名」时会原样保留 `song.lrc`。厂商改过的 provider 常见两种偏差：校验 MIME 白名单，未知类型直接返回 null；或补充了扩展名映射表，导致追加二次扩展名变成 `song.lrc.txt`。

**判据**：MP3 路径用的是标准 MIME `audio/mpeg`（`MP3Utils.kt:182`）。若同机器上「提取 MP3」正常而「转 LRC」失败，即锁定此条。

修法：改用 `application/octet-stream`（AOSP 在扩展名未知时的默认推导值，能走「保持原名」分支）。**不要**换 `text/plain`，那反而可能被改名为 `song.lrc.txt`。

### B. 先删后建的竞态，以及一处失效的错误提示

`MainActivity.kt:493-501`、`MP3Utils.kt:85-92`

`delete()` 后紧接着同名 `createFile()`，provider 索引未刷新时可能返回 null 或生成 `xxx (1).lrc` —— 恰是当初加删除逻辑想避免的结果。

另有确凿 bug：`DocumentFile.delete()` 失败时**返回 false 而非抛异常**，因此 `catch` 块里那句「⚠️ 旧文件无法删除，改为直接覆盖」永远打印不出来，返回值被丢弃。

修法：不删不建，已存在就直接对原 uri 截断写入。

### C. 写入用了 `"w"` 而非 `"wt"`（与鸿蒙无关，应修）

`MainActivity.kt:513`、`MP3Utils.kt:73`

Android 官方明确说明 `"w"` **不保证截断**。覆盖更长的旧文件时尾部残留会保留 —— LRC 多出旧歌词，MP3 尾部损坏。

### D. 单文件模式的 `buildParentTreeUri`

`MainActivity.kt:696-710`

假设 docId 形如 `volume:path` 并凭空拼一个 tree URI。**凭空拼出的 tree URI 从未被系统 grant 过**，`takePersistableUriPermission`（`MainActivity.kt:661`）必然抛异常且被吞掉，随后 `createFile` 因 `SecurityException` 返回 null。CHANGELOG 记录的「已知问题」即此条。

附带：该处文案「授权未能长期保存，不影响本次操作」是错的，这种情况下恰恰影响本次操作。

### E. 清单里的 `READ_EXTERNAL_STORAGE` 是多余的（与鸿蒙无关，应修）

`app/src/main/AndroidManifest.xml` 声明了它，但代码从未申请运行时权限，SAF 也不需要。部分定制系统上「声明了存储权限却未授予」反而会触发存储受限提示。

## 五、原改进计划（已作废，仅存档）

> 以下计划基于「要在鸿蒙上把问题修好」的前提制定，该前提已放弃。保留以说明当时的思路。

### 第零阶段：确认运行环境 —— ✅ 已完成

结论见第三节：卓易通容器。**这意味着「创建文件失败」大概率不是本仓库的 bug，而是容器 SAF 实现的限制（问题 G / A）。** 后续工作应按此定位，不要再假设自己面对的是一个标准 Android 环境。

### 第一阶段：零成本验证（不写代码，先做）

在改任何东西之前，这三个观察能大幅收敛范围：

- [ ] 同一台机器、同一个文件夹，**「音频」页提取 MP3 是否正常**
      → 正常则锁定 **A**（MIME 白名单）；同样失败则指向 **G**（整个目录不可写）
- [ ] 换一个文件夹重试（尤其是容器内自建的目录 vs 系统文件管理器里的目录）
      → 换目录后成功即坐实 **G**
- [ ] 「文件夹模式」与「单文件模式」分别试
      → 只有单文件模式失败则是 **D**，与容器无关

### 第二阶段：可诊断性（F）—— 无论如何都该做

- [ ] 给 `createFile` 包 try/catch，把真实异常类型与 message 写进「详细过程」（`MainActivity.kt:503`、`MP3Utils.kt:91`）
- [ ] 任务开始时写入一行环境信息：`treeUri.authority`、docId 前缀、`Build.MANUFACTURER`、`Build.DISPLAY`
      → 容器通常会伪造 `Build` 字段，但 **`authority` 会暴露卓易通的 provider 实现**
- [ ] **给 `DetailsSection` 加「复制详细过程」按钮**（`AppComponents.kt:337`）。没有它，前两项拿不出设备

验收：在 Pura 80 Pro 上复现一次，能拿到一段可直接粘贴回来的文本，据此判定卡在 A 还是 G。

### 第三阶段：低成本试探（A、B）

两条在容器环境下反而比在 AOSP 上更可能命中，且改动都很小：

- [ ] **A** — MIME 由 `text/x-lrc` 改为 `application/octet-stream`（`MainActivity.kt:503`）
- [ ] **B** — 覆盖策略改为「已存在则复用原 uri 截断写入，不存在才 createFile」，同时删掉那段拿不到返回值的 delete 逻辑（`MainActivity.kt:493-515`、`MP3Utils.createOrReplaceFile`）

### 第四阶段：无条件清理（与鸿蒙无关，可随时并入）

- [ ] **C** — 所有 `openOutputStream(uri, "w")` 改为 `"wt"`（`MainActivity.kt:513`、`MP3Utils.kt:73`）
- [ ] **E** — 删除 `AndroidManifest.xml` 中的 `READ_EXTERNAL_STORAGE`

### 第五阶段：兜底与降级

- [ ] **G** — 若诊断坐实存储视图错位：在 `Guidance.cannotCreateFile` 中补一条针对容器的绕行建议（`ui/JobState.kt:156`）。**这是我方唯一能做的事，属于改善提示而非修复**
- [ ] **D** — 优先级最低。修正 `MainActivity.kt:661` 附近的误导文案（该文案称「不影响本次操作」，实为影响）；单文件模式的输出目录推断可择机改为「先弹文件夹选择器拿授权再选文件」

## 六、从本次调研中捞出的真实缺陷（与鸿蒙无关，仍需处理）

这四条是排查鸿蒙时顺带发现的，**在所有 Android 设备上都成立**，不随「放弃鸿蒙」作废。建议转为普通待办：

- [ ] **C** — `openOutputStream(uri, "w")` 不保证截断，覆盖更长的旧文件会残留尾部：LRC 多出旧歌词，MP3 尾部损坏。改为 `"wt"`（`MainActivity.kt:513`、`MP3Utils.kt:73`）
- [ ] **B** — `DocumentFile.delete()` 失败时返回 false 而非抛异常，因此「⚠️ 旧文件无法删除」那句提示**永远打印不出来**，返回值被丢弃（`MainActivity.kt:493-501`）。顺带可把覆盖策略改为「已存在则复用原 uri 截断写入」，同时消化掉 C
- [ ] **E** — `AndroidManifest.xml` 声明了 `READ_EXTERNAL_STORAGE`，但代码从未申请运行时权限，SAF 也不需要。部分定制系统上反而会触发存储受限提示，应删除
- [ ] **F** — 「详细过程」没有复制入口（`AppComponents.kt:337`），用户无法把日志带出设备。任何设备上的远程排查都卡在这里，值得单独修

**D**（单文件模式凭空拼 tree URI）是既有的已知问题，CHANGELOG 已记录，优先级低，此处不重复。

## 七、决策记录：为什么放弃

技术上可行，成本卡在分发。

**可行的方案是**：把字幕转换单独做成鸿蒙原生 HAP。核心体验保得住——`DocumentViewPicker` 支持 `DocumentSelectMode.FOLDER` 加持久化授权，「授权文件夹 → 批量原地转换」能原样实现。移植量也小——`VttUtils.kt` 共 125 行，唯一 import 是 `java.util.regex.Pattern`，零 Android 依赖，翻到 ArkTS 几乎逐行；要重写的只是 picker + 遍历 + `@ohos.file.fs` 写入 + ArkUI 界面。

**否决它的是分发**：

| | 现在（Android） | 鸿蒙 |
|---|---|---|
| 分发 | GitHub Release 挂 APK，用户自取 | 做不到 |
| 安装 | 点开就装 | HAP 不能点击直装，**必须过华为应用市场审核** |
| 门槛 | 无 | 实名认证 + 软件著作权 + 隐私政策合规 |

代码几天能写完，上架才是长期成本。对一个 GitHub 上的开源小工具来说，这是运营方式的改变，与项目定位不匹配。

**同时否决的两个替代方案**：

- **靠卓易通容器打补丁** —— 容器 SAF 无对外契约，今天绕过明天可能又变，天花板低且不可控
- **改做网页版** —— 移动端浏览器不支持 `showDirectoryPicker`，「批量原地转换」会退化成逐个选文件、逐个下载，核心卖点丧失

**音频提取无论如何都不做鸿蒙版**：FFmpeg 需自行用 OHOS NDK 交叉编译（现有 aar 只含 Android ABI），近乎另起项目，且 GPL 上架另有麻烦。

### 对用户的影响

鸿蒙 5/6（纯血）用户只能通过卓易通容器运行本 App，文件读写可能失败，**不受支持**。HarmonyOS ≤4 / EMUI（含海外机）保留 AOSP，不受此影响，正常可用。

## 八、参考

- [华为 Pura 80 系列率先升级 HarmonyOS 6.1.0.135 SP8（IT之家）](https://www.ithome.com/0/978/918.htm)
- [HUAWEI Pura 80 Pro 官网](https://consumer.huawei.com/cn/phones/pura80-pro/)
- [Huawei Pura 80 (Wikipedia)](https://en.wikipedia.org/wiki/Huawei_Pura_80)
- [HarmonyOS 5 (Wikipedia)](https://en.wikipedia.org/wiki/HarmonyOS_5)
- [Huawei launches a new HarmonyOS 6 update for many devices (Huawei Central)](https://www.huaweicentral.com/huawei-launches-a-new-harmonyos-6-update-for-many-devices/)
- [鸿蒙与安卓的「兼容之桥」：HarmonyOS 5.x/6.x 运行 Android APK 的方案与原理](https://zhuanlan.zhihu.com/p/2006822955129254456)
- [鸿蒙 next 出境易/卓易通研究（知乎）](https://zhuanlan.zhihu.com/p/10576812652)
- [鸿蒙删掉安卓兼容层，卓易通却还能装 APK（搜狐）](https://www.sohu.com/a/1042001116_121784105)
- [Access documents and other files from shared storage (Android Developers)](https://developer.android.com/training/data-storage/shared/documents-files)
- [DocumentFile.createFile returns null (nextcloud/android#8028)](https://github.com/nextcloud/android/issues/8028)
- [华为 Pura 80 系列出厂标配 HarmonyOS 5.1：不支持回退（快科技）](https://news.mydrivers.com/1/1053/1053919.htm)
- [纯血鸿蒙时代，APK 还能装吗？从安装教程到「卓易通」底层原理深度解析](https://blog.soarli.top/archives/996.html)
- [安卓 app 如何兼容鸿蒙手机解决方案（CSDN）](https://blog.csdn.net/qq_35365160/article/details/148233854)
