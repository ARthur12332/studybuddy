# studybuddy · 轻量日志笔记 PWA

给赵泽川自用的手机日志笔记 + 番茄钟应用。

## 技术约束（**最重要，改代码前必读**）

- **不许引入任何第三方库、框架或构建步骤**。整个应用就是 `index.html` 一个文件，
  CSS 与 JS 全部内联。这是它的核心卖点（零依赖、双击即用、可直接丢到任意静态托管）。
- **不加后端、不发任何网络请求**。数据一律存 `localStorage`，键名统一 `studybuddy.` 前缀：
  - `studybuddy.notes.v1` —— 日志记录
  - `studybuddy.pomodoro.v1` —— 番茄钟运行状态
  - `studybuddy.pomolog.v1` —— 按天的番茄统计
  - `studybuddy.theme` / `studybuddy.draft` —— 主题与草稿
- 统计图表也用**原生 DOM + CSS**做（柱高百分比），不要引图表库。
- 界面全中文；移动端优先（安全区适配、触控目标够大、输入框字号 ≥16px 防止 iOS 缩放）。

## 自检（每次改完必做）

1. 语法：把 `<script>` 里的内容抽出来交给 `node --check`；`sw.js` 同样检查。
2. 结构：所有 `$("xxx")` 引用的 id 必须在 HTML 中存在；HTML 中不能有重复 id。
3. 逻辑改动后，用浏览器实际点一遍相关流程再交付。

## 交付要求

- **每次新增功能都要同步更新 `README.md`**：功能表加一行、版本记录加一条。
- 图标配色改了要重跑 `python tools/make_icons.py` 重新生成图标。
- 版本号规则：小改动 +0.1，每次都要在 README 的「版本」一节留一行说明。

## 结构

```
index.html               主应用（HTML + CSS + JS 全内联）
manifest.webmanifest     PWA 清单
sw.js                    Service Worker（离线缓存）
icons/                   图标（由 tools/make_icons.py 生成）
tools/make_icons.py      图标生成脚本（纯标准库）
README.md                说明文档
```

## 现有功能（改动时注意别弄坏）

记录 / `#标签` 自动分类 / 搜索与标签筛选 / 编辑删除 / 按天分组 /
今日·总数·连续打卡 / 番茄钟（自定义时长、休息循环、结束提醒、完成自动记日志）/
专注统计（今日·本周·本月·累计 + 最近 7 天柱状图）/ 深浅色 / 草稿保留 / JSON 导入导出 / Markdown 导出。
