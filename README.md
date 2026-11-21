# libepub

[![CI](https://github.com/TanakaLun/libepub/actions/workflows/ci.yml/badge.svg)](https://github.com/TanakaLun/libepub/actions/workflows/ci.yml)

纯原生 Android EPUB 阅读引擎：**零 WebView、零 JS**，解析、CSS 级联、排版、分页、绘制全部自研。
面向 Compose 阅读器，完整支持多看（Duokan）样书的特殊布局：`【注】` 脚注、单图/图集、
`DK-*` 字体回退、竖排 `vertical-rl`、ruby、着重号与文字装饰。

## 模块

| 模块 | 内容 | 依赖 |
| --- | --- | --- |
| `:core` | 纯 JVM：容器/OPF/NCX/nav、XHTML 解析、语义索引、CSS 解析与级联、块/内联/表格/浮动/ruby 布局、分页、LRU 缓存、`BookParser`/`LayoutSession` | kxml2 |
| `:render` | Compose：`ComposeGlyphMeasurer`、`PageDrawer`（逻辑轴→物理坐标）、`@font-face` 加载、图片下采样 LRU、`EpubPager`/`EpubScroll`/`EpubReaderState`、脚注弹窗、图集、缩放 | Compose UI + Foundation（**不含 Material**） |
| `:app` | 示例阅读器：书架、阅读页、目录、设置（字号/主题/横竖排/双模式）、`SharedPreferences` 进度 | 上述两者 |

## 设计要点

- **逻辑轴**：布局只使用 `main`（行内方向）与 `block`（行堆叠方向）；物理 x/y 只在
  `PageDrawer` 出现。横排与竖排共用同一套布局与分页代码。
- **单一布局输出**：翻页模式对 `ChapterLayout.lines` 做行边界切片；滚动模式直接绘制整章。
  切换模式不重排。分页渲染按 `page.startBlock - 章节首行位置` 平移绝对坐标，保证每页顶部留白一致；
  无文字的插画页在页内垂直居中。文字页不做整页居中（短页会漂浮，且阅读时视线跳动）。
- **书内链接**：`<a href>` 的运行片段携带解析后的 zip 路径与 fragment，渲染层为其生成命中区，
  阅读器据此跨章节/同章锚点跳转；外链一律忽略。
- **边到边 + 安全区**：`enableEdgeToEdge()`；阅读面全屏沉浸，顶栏/底栏浮在其上。宿主把
  chrome + 系统栏尺寸作为四边 `contentInset*` 交给 `EpubReaderState`：布局只在安全矩形内进行，
  翻页模式按同一矩形分页，滚动模式把上下预留作为章首/章末空白追加（不缩小可视区），
  因此文字不会被遮挡，也不会出现"半屏避让"。大图/图集/脚注仍是全屏覆盖层，
  按钮与页码分别用 `statusBarsPadding` / `navigationBarsPadding`；状态栏图标颜色随主题切换。
- **缓存键**：`href + 字号 + 视口宽高 + 书写方向 + 主题`，任一变化都会得到新布局；
  位置用**块坐标**恢复，而不是页码比例。
- **多看脚注原生等价**：`a.duokan-footnote` / `epub:type=noteref` 的 `<img>` 标记在盒树阶段
  替换为 `【注】` 文本并记录锚点；`aside[epub:type=footnote]` 在正文中隐藏，点击标记时
  用同一引擎、同一套样式表在小版面重排后弹窗显示。
- **CSS 容错**：未知 at-rule、非法选择器、非法声明一律跳过并记录警告，永不抛错；
  `@media screen/all` 扁平化、`print` 跳过；简写属性在级联前展开。
- **禁则与字素簇**：自研字素簇切分 + 禁则表（不用 `BreakIterator`），锁定 Unicode 区间。

## 支持矩阵

| 能力 | 状态 |
| --- | --- |
| EPUB 2 (OPF + NCX) / EPUB 3 (nav + landmarks + page-list) | ✅ |
| 流式 XHTML、脏标记修复、命名空间容忍 | ✅ |
| CSS 选择器/特异性/`!important`/内联/继承 | ✅ |
| 盒模型、margin 折叠、浮动绕排、列表、表格 | ✅ |
| 行内图片、块图片、`figure/figcaption`、图集 | ✅ |
| `@font-face`（容器内 TTF/OTF）、缺失族回退 | ✅ |
| ruby / `rp` 兜底、着重号、上下标、装饰线、`text-shadow` | ✅ |
| 横排 `horizontal-tb`、竖排 `vertical-rl` / `vertical-lr` | ✅ |
| 分页（行边界、强制断页、印刷页码锚点） | ✅ |
| 书内超链接（目录页、交叉引用、fragment 锚点） | ✅ |
| 纯图片页垂直居中 | ✅ |
| 滚动模式 | ✅ |
| 脚注弹窗 / 图集 / 双指缩放 | ✅ |
| 脚本（JS）识别 → 原生等价（不执行 JS） | ✅ 见 [`docs/script-support.md`](docs/script-support.md) |
| DRM、固定版式 FXL、media overlays、JS 执行、绝对定位 | ⛔ 明确不支持（优雅降级/告警） |

## 脚本（JS）

引擎不执行脚本，而是识别脚本**做什么**，再映射到原生能力：

```kotlin
val report = book.scriptReport()   // 会解析未缓存章节，放后台线程
report.libraries        // ["jQuery"]
report.unsupported      // [] 表示这本书的脚本全部有原生等价
```

真实样书里 17/24 章加载 92.7 KB 的 `notereplace.js`（jQuery 1.7.2 + 4 行
`$(".duokan-footnote").html("【注】")`），引擎已原生把脚注标记渲染为「【注】」，
所以这本书在 `unsupported` 里是空的。识别规则、Rhino 可行性实测与扩展方式见
[`docs/script-support.md`](docs/script-support.md)。

## 构建与测试

```bash
./gradlew build                     # core JVM + render Robolectric + app 打包
./gradlew :core:test                # 235 个单元测试
./gradlew :render:testDebugUnitTest # Robolectric 渲染/交互
./gradlew :app:testDebugUnitTest    # 进度/目录状态机
./gradlew :app:assembleDebug        # APK
```

工具链：Gradle 9.9.1 / AGP 9.5.1 / Kotlin 2.4.21 / JDK 21 / Compose BOM 2026.09.00 / Robolectric 4.17。

> **测试语料**：仓库不包含验收用的样书（受版权保护）。把任意 `.epub` 放进 `assets/`，
> 或用 `-Dlibepub.sample=/path/to/book.epub` 指定路径；两者都没有时，依赖真实语料的测试会被
> **跳过**而不是失败，合成语料（`SyntheticZips` / `TestBooks.syntheticFile()`）覆盖其余全部逻辑。
> 统计：`core 235 / render 27 / app 9`（本机带样书时）。

> Robolectric 在 Linux aarch64 上不支持 native graphics，测试使用 legacy 图形；
> `ComposeGlyphMeasurer` 在检测到平台字体度量为零时回退到 em 估算，真机始终走 `Paint`。

## 作为依赖使用（本地 Maven）

`:core` 与 `:render` 都接入了 `maven-publish`，可以发布到本地 Maven 仓库供其他工程消费
（`:app` 只是示例，不发布）。

```bash
./gradlew publishToMavenLocal                 # 两个库一起发布 → ~/.m2/repository/org/libepub/
./gradlew :core:publishToMavenLocal           # 只发布解析/排版层
./gradlew :render:publishToMavenLocal         # 只发布渲染层（需先发布 core）
./gradlew -PlibepubVersion=0.2.0 publishToMavenLocal   # 换版本号
```

产物（含 `-sources.jar`）：

| 坐标 | 类型 | 内容 |
| --- | --- | --- |
| `org.libepub:core:0.1.0` | `jar` | 纯 JVM 解析 + 排版，无 Android / Compose 依赖 |
| `org.libepub:render:0.1.0` | `aar` | Compose 渲染层，`api` 传递 `core` |

消费端：

```kotlin
repositories { mavenLocal() }          // 还需要 google() / mavenCentral() 解析传递依赖

dependencies {
    implementation("org.libepub:render:0.1.0")   // 要 Compose 阅读器
    // implementation("org.libepub:core:0.1.0")  // 只要解析/排版
}
```

要求与约定：

- 宿主 `compileSdk = 37`（AAR 元数据 `minCompileSdk=37`；Compose 1.12.1 自身也要求 37），`minSdk >= 24`。
- 库按 **Java 17 字节码**发布，D8 在 minSdk 24 上可正常脱糖。
- 宿主模块需启用 Compose（`org.jetbrains.kotlin.plugin.compose`）。
- Compose / coroutines / kxml2 全部由 POM 传递，无需重复声明；Compose BOM 以
  `<dependencyManagement><scope>import</scope>` 写入 POM，Gradle 与 Maven 都能解析版本。
- **不依赖任何 Material / 设计系统**：只用 `compose-ui` + `compose-foundation`，弹层里的文字用
  `BasicText`、按钮用 `Modifier.clickable`，按下反馈取自宿主的 `LocalIndication`。
  所以 Material 2、Material 3、Cupertino、或完全自研的 UI 库都能直接用，不需要排除依赖，
  也不会被强行套上别人的配色与形状。
- **配色可注入**：内置纸/复古/夜间三套 `ReaderTheme`，宿主可用
  `state.customTheme = ReaderTheme(key, background, foreground, accent)` 换成自己的色板；
  颜色不参与布局，切换不会重排。
- 公开 API 暴露 `@Composable` / `Modifier` / `ImageBitmap`，因此 `compose-ui`、`compose-foundation`
  以 `api` 发布。

最小用法：

```kotlin
@Composable
fun Reader(state: EpubReaderState) {
    EpubReader(state)              // 阅读面 + 脚注/图集/缩放覆盖层
    // 或自行排 chrome：
    // EpubReaderContent(state)
    // EpubReaderOverlays(state)
}
```

`EpubReaderState` 需要宿主装配 `LayoutSession`（含 `GlyphMeasurer`、`ImageSizeProvider`）与
`PageDrawer`；`app/src/main/kotlin/org/libepub/app/BookOpener.kt` 是完整示例。

## 性能（本容器，JVM）

| 指标 | 实测 | 阈值 |
| --- | --- | --- |
| 打开样书（容器+OPF+目录） | ~2 ms | < 1000 ms |
| 单章解析+级联+布局（中位数） | ~59 ms | < 500 ms |
| 全书 24 章布局 | ~833 ms（最慢 86 ms） | — |
| 翻 10 页 | 布局对象复用，缓存条目数不变 | 无 O(n) 增长 |

真机冷启动/帧率/内存见 [`docs/acceptance-device-checklist.md`](docs/acceptance-device-checklist.md)。

## 已知限制

- 不支持 DRM（`encryption.xml` 中未知密码算法会抛 `DrmProtectedException`；IDPF/Adobe
  字体混淆按规范支持）。
- 固定版式（FXL）、media overlays、JS 执行、`position:absolute/fixed` 不支持或降级；
  脚本本身会被识别并映射到原生等价（见 [`docs/script-support.md`](docs/script-support.md)）。
- 不访问网络：远程样式表/图片/字体一律忽略并告警。
- 文本选择、书签、搜索、高亮不在库内（可在 app 层基于 `ChapterLayout`/`Locator` 实现）。
