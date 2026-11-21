# 脚本（JS）处理

libepub **不执行** EPUB 里的 JavaScript。这不是能力缺失，而是取舍：脚本在阅读器里
真正做的事情高度集中在少数几种「改写静态标记」的操作上，引擎直接把这些效果做成了
原生能力，比内嵌一个 JS 引擎更小、更快、更安全。

本文说明：识别什么、映射到什么、以及为什么不做通用 JS。

## 1. 为什么不做通用 JS

引入 Rhino 技术上可行（已实测：1.9.2 / 1.7.15.1 都能在 Android 上跑，沙箱可阻断
`java`/`Packages`/`getClass` 逃逸），但成本不在引擎：

| 项 | 实测 |
| --- | --- |
| Rhino 1.9.2 dex | 1.5 MB，最低 minSdk 26（`MethodHandle.invoke`） |
| Rhino 1.7.15.1 dex | 1.3 MB，可 minSdk 24 |
| 小脚本（20 行 + 事件注册） | 中位 3.6 ms / 章 |
| jQuery 1.7.2 加载（预编译后） | 中位 88 ms / 文档（桌面 JVM，ART 上约 ×2–4） |

要让 jQuery 这类脚本真正跑起来，还需要 DOM（活集合、`innerHTML` 解析、`createElement`）、
CSSOM（`getComputedStyle`、`classList` 改样式触发级联重算）、事件模型（命中测试回
`LaidOutBox.node`）、生命周期与重排/重新分页。这是一整套 Web 平台，而真实电子书里
的脚本几乎从不产生新的阅读内容。

## 2. 识别规则

`ScriptAuditor` 按「载荷字面量」而不是方法名匹配——压缩后的 jQuery 自身就含有
`.hide()`、`.show()`、`scrollTo`，按方法名匹配会误判。识别到内置库（jQuery / Zepto /
MooTools / Prototype / Dojo）时，只分析 IIFE 结束之后的应用代码。

| pattern id | 触发证据 | 结论 | 原生等价 |
| --- | --- | --- | --- |
| `duokan-footnote-marker` | `duokan-footnote` 选择器、`【注】` 字面量 | ✅ HANDLED | 脚注标记渲染为 `【注】`（`SemanticIndex`） |
| `duokan-image-group` | `duokan-image` | ✅ HANDLED | 图集/单图块（`ImageGroup`） |
| `document-write` | `document.write` | ⛔ UNSUPPORTED | 生成的标记不在源文件里，无法排版 |
| `navigation` | `scrollTo(` / `location.href` / `location.hash` | ⛔ UNSUPPORTED | 阅读位置由 `Locator` 管理 |
| `image-resize` | `img` + `.width(` / `.height(` / `css('width'` | ⛔ UNSUPPORTED | 图片按容器宽度自适应 |
| `visibility-toggle` | `.toggle(` / `.hide(` / `.show(` / `display` + `none` | ⛔ UNSUPPORTED | 静态 `display:none` 仍然生效 |
| `unknown` | 其余非空脚本 | ⛔ UNSUPPORTED | 展示静态标记 |

非 JavaScript 的 `type`（如 `text/template`）、远程 `src`、缺失文件不会进入识别，
分别记入 `skipped` 并告警。

## 3. 使用

单章：

```kotlin
val document = book.chapter("OEBPS/Text/Section004.xhtml")
document.scriptAudit.patterns.forEach { println("${it.id} -> ${it.support}") }
```

全书：

```kotlin
// 会解析所有未缓存的章节，请在后台线程调用
val report = book.scriptReport()
println(report.libraries)          // ["jQuery"]
println(report.unsupported)        // [] —— 这本书不需要 JS
report.chaptersUsing("duokan-footnote-marker")  // 18 个章节
```

告警会写进 `book.warnings`：

```
script OEBPS/Misc/notereplace.js: duokan-footnote-marker handled natively (JS is not executed)
script inline uses unsupported navigation; JS is not executed and the static markup is shown
```

## 4. 扩展

新增一种原生等价时：

1. 在 `ScriptAuditor.RULES` 里加一条 `Rule`（用载荷字面量做证据，避免方法名误判）；
2. 在 `ScriptSupport` 上标 `HANDLED`，并把效果做进 `SemanticIndex` / 布局；
3. 在 `ScriptAuditorTest` 补一个「识别」用例，在真实/合成书测试里补一个「效果」用例。

如果某本书的脚本确实产生了静态标记里没有的内容，`unknown` 会把它标出来——那是引入
`:script` 模块（Rhino + DOM facade）的触发条件，而不是默认路径。
