# archive · 存档说明

这个目录放**不再参与日常使用、但要保留**的文件。

---

## studybuddy-迭代版-2026-09-22.html

| 项目 | 值 |
|---|---|
| 来源 | 微信收到：`D:\xwechat_files\wxid_gci10paghz4x22_6bcb\msg\file\2026-09\index(12).html` |
| 存档时间 | 2026-09-22 22:22 |
| 大小 | 119,539 字节 / 107,368 字符 / 2,682 行（内联脚本 75,505 字符） |
| SHA-256 | `58207bb7f84a7d0ab86d712c684640353d0930758f3ed20a12de7c5adb346756` |
| 与原文件关系 | **逐字节相同**（哈希一致），仅改了文件名 |

### 它是什么

同一款 StudyBuddy 的**后续迭代版**，比本工程根目录的 `index.html`（68KB / 1700 行）更靠后。

判定依据：localStorage 键与本工程完全一致 ——
`studybuddy.notes.v1` / `pomolog.v1` / `sessions.v1` / `subjects.v1` / `goals.v1` / `goallog.v1` /
`tasks.v1` / `exam.v1` / `lastbackup` / `lastsubject`，只在尾部多了 `sessions.arc.v1`（归档）。
也就是说：**两个版本的浏览器数据是互通的**，换文件不会丢记录。

### 它比本工程当前版本多出来的功能

1. **全屏倒计时 / 沉浸模式**：独立全屏层 `#pomoFull`（刻意不用 `<dialog>`，避开 top-layer 兼容差异）；
   深浅双主题（`--pf-*` 变量）；沉浸态=计时中只留大数字、点屏幕唤出按钮、6 秒无操作自动回沉浸；
   横屏布局（圆环在左、信息在右）；原生全屏 API + 屏幕方向锁定，**退出时解除方向锁**（安卓需要）；
   全屏期间保持屏幕常亮。
2. **跨零点自动刷新** `tickPomoCross()`：过零点自动把每日目标勾选归零并重绘进度条。
3. **学习日程自动归档** `archiveSessions()` + 新键 `studybuddy.sessions.arc.v1`：超过上限的最旧记录
   自动移入归档，避免 localStorage 被撑爆；导入时 `mergeSessions` 按 id 去重合并。
4. **删除可撤销** `toastUndo()`：删除后 5 秒内可点「撤销」。
5. **数据归一化** `normalizeNote()`：老数据 / 缺字段的数据兜底，避免一打开就崩。
6. **单文件化**：图标内联 SVG + `initManifest()` 用 Blob 动态生成 manifest，
   不再依赖外部 `manifest.webmanifest` 与 `icons/*.png`（单文件部署时那些会 404）。
7. 零碎：`pruneGoalLog()` 目标日志裁剪、`saveDraft()` / `flushDraft()` 草稿更可靠、
   `fillEditSubj()` 编辑记录时能改科目。

### 怎么用（如果哪天要切过去）

- 想直接看效果：双击这个 html 就行，数据与主版本共用同一套 localStorage 键。
- 想切换成主版本：把它复制成工程根目录的 `index.html` 覆盖即可（建议先 `git commit` 一次留底，
  切换后 `git diff` 能看到全部差异）。
- ⚠️ 它把图标与 manifest 内联了，所以**不要再同时外挂本工程的 `manifest.webmanifest` / `icons/`**，
  否则会出现「404 + 拿到旧图标」的混搭状态。

### 未做的事

- 没有把它合并进主版本，也没有替换 `index.html` —— 应他 2026-09-22 的要求，**只存档**。
- 只做了内联脚本的语法检查（`node --check` 通过），没跑真实浏览器冒烟测试。
