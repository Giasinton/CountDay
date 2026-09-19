# 设计与实现笔记

README 只讲怎么用；这里放实现细节、踩过的坑和验证记录。

## 目录结构

```
CountDay/
├── build.sh                     # 一键构建（不需要 Gradle / Android Studio）
├── app/src/main/
│   ├── AndroidManifest.xml      # 只有 1 个 receiver + 1 个配置 Activity，无 launcher 入口
│   ├── java/io/countday/
│   │   ├── CountDayWidgetProvider.java  # 唯一的广播接收器：更新/删除/改尺寸/跨天
│   │   ├── ConfigActivity.java          # 配置与编辑页（对话框样式）
│   │   ├── CropImageView.java           # 自绘的裁剪控件（拖动 + 四角缩放）
│   │   ├── WidgetUi.java                # 根据数据生成 RemoteViews
│   │   ├── WidgetText.java              # 天数的文案换算
│   │   ├── WidgetStore.java             # 每个实例一份 SharedPreferences 数据
│   │   ├── WidgetData.java              # 数据模型
│   │   ├── ImageStore.java              # 图片导入/裁剪/落盘 + 高质量缩放
│   │   ├── CardBackground.java          # 自定义背景色的圆角卡片位图
│   │   ├── MidnightAlarm.java           # 跨天刷新用的非精确闹钟
│   │   ├── DayMath.java                 # 日期计算（只以"本地日期"为单位）
│   │   └── BoxMath.java                 # 卡片比例 / 裁剪框约束的纯几何计算（可离线校验）
│   └── res/
│       ├── layout/widget_countday.xml   # 小组件布局（卡片 / 图片 / 文字三层）
│       ├── layout/activity_config.xml   # 配置页布局
│       ├── xml/countday_widget_info.xml # 小组件元数据（含 resizeMode）
│       ├── drawable/widget_card_default.xml  # 默认圆角背景（跟随深浅色）
│       └── values[-night]/              # 颜色随深色模式切换
├── build.gradle.kts / settings.gradle.kts / app/build.gradle.kts
├── docs/screenshots/            # README 里用的真机效果图
└── tools/                       # DayMath / BoxMath 的纯逻辑校验（在电脑上跑，不参与构建）
```

## 设计说明

**为什么不需要后台服务。**
小组件的内容只在几种时机需要重算：日期变了、用户改了配置、尺寸变了。

- 配置变化 / 添加 / 删除 / **拉伸尺寸**：`CountDayWidgetProvider` 收到广播后立刻更新。
- **跨天**：`MidnightAlarm` 用 `AlarmManager.set(...)`（非精确、无需任何权限）
  在下一个本地零点之后投递一次私有广播，receiver 收到后重算所有实例，并排好第二天的闹钟。
  没有 Service，广播处理完进程就可以被系统回收。
- 兜底：监听 `TIME_SET` / `TIMEZONE_CHANGED` / `DATE_CHANGED` / `LOCALE_CHANGED`，
  以及**每次点击小组件时都会重新计算一次**。

**图片怎么送到桌面（这是最容易踩坑的地方）。**

选图后立刻解码、等比缩小，**以 PNG 写进应用私有目录**（最长边 1440px），不依赖 SAF 的长期 URI 授权。

但"怎么把图交给桌面"有讲究：

- `setImageViewBitmap` 要把整张位图塞进 RemoteViews 的 Bundle 走 Binder（上限约 1MB），
  所以分辨率最多只能给到 ~384px，卡片大一点就糊 —— 这正是"没有加载完整分辨率"的原因。
- 所以改成 `setImageViewUri`：图片留在应用私有目录，桌面自己去解码，分辨率不受传输限制。
- **但系统不会自动把 RemoteViews 里的 URI 权限授予桌面**（实测：图片完全不显示）。
  最后是应用自己通过 HOME intent 找到桌面包名（清单里声明了对应 `queries`），
  用 `grantUriPermission` 显式授权；这个授权是持久的，桌面之后重新应用 RemoteViews 也还能读到图。
- URI 里带上文件的修改时间戳作为版本号：`ImageView.setImageURI` 对相同 URI 会直接跳过，
  不换 URI 的话重新裁剪后会一直显示旧图。

**高分辨率图片的缩放（两步走）。**

1. **入库**：一次性把 4000px 缩到 384px 会让双线性采样跨过大量像素、直接丢细节。
   所以先按 `inSampleSize` 粗降（内存可控），再**逐级折半**逼近目标 —— 每一步采样比都接近 1:1。
   原图存到 1440px，够大组件用，也不至于让桌面解码时吃太多内存。
2. **成品/预览**：按<b>目标分辨率</b>渲染 —— 也就是卡片实际会显示的像素尺寸（dp × 屏幕密度），
   用**双三次插值（Catmull-Rom）**做最后一次缩放。Android 框架只暴露双线性
   （`Bitmap.createScaledBitmap` / `Paint.setFilterBitmap`），所以 `BicubicScaler` 自己做了一遍
   分离式卷积（先水平再垂直，每轴 4 个抽样点，边缘夹取 + 权重归一化），
   并按<b>预乘 alpha</b> 插值，避免透明区域的颜色渗到边缘。
   这样桌面拿到的图正好是它要显示的尺寸，不用再缩放，也就不会糊。

成品图什么时候重算：卡片尺寸变了、或者**渲染器版本号**（`ImageStore.RENDER_REVISION`）对不上时。
把版本号记在实例数据里，渲染算法一升级（比如换成 `clipPath` 切圆角）老组件的成品图就会自动重绘，
不需要用户重新保存。

另外 `inScaled=false` 关掉 BitmapFactory 按图片自带 density 再缩一次的副作用；
全程 `ARGB_8888`，透明通道不会丢（用 `RGB_565` 正是"透明区变黑"的常见原因）。

**圆角。**
卡片圆角默认 20dp（`widget_card_default` 里写死的 shape）。一旦调成别的值，
就没法再用固定圆角的 shape drawable 了，于是改走 `CardBackground.create(...)`：
把「颜色 + 透明度 + 圆角 + 描边」画成一张按卡片长宽比生成的小位图，由 `ImageView` 拉伸铺满
（位图最长边 96px，圆角按比例画，拉伸后仍是正圆）。

**图片完全铺满卡片，圆角烘进位图。**
（这里的圆角用 `clipPath` 而不是 `PorterDuffXfermode(DST_IN)`：实测 DST_IN 在 Android 16 上是
**no-op** —— 掩码画上去什么都不切，表现为"只有上面两个角有圆角"。另外 `Canvas.drawBitmap`
会按「画布密度 / 位图密度」再缩放一次，新建位图和源图密度不一致会把图放大、右下角被推出可视区，
所以画布和目标位图的密度都要对齐源图；这两点都由 `ImageStore.roundCorners` 处理，
并且落盘前会检查四角 alpha 是否为 0。）
成品图一律按<b>卡片长宽比裁齐</b>：等比放大到盖住卡片，再居中裁掉多出来的部分（cover），
长宽正好等于卡片的像素尺寸，所以不会有留白（要"不裁剪"就把卡片尺寸设成「跟随图片」，
此时卡片比例 = 图片比例，放大后正好铺满）。另外 RemoteViews 没法给 `ImageView` 做圆角裁剪，
透明图片的直角会戳出卡片圆角，所以渲染的最后一步用 `PorterDuff.DST_IN` 把卡片圆角画成透明 ——
圆角大小就是设置里的那个值，和卡片用的是同一个，所以严丝合缝。
成品图是**落盘时就已经处理好圆角**的（桌面拿到的就是最终画面，不需要它再做任何裁剪）。

**背景色为什么用位图，以及怎么适配长宽比。**
RemoteViews 只能整块替换背景 drawable，**没法动态改 shape 的颜色**。所以：

- 默认配色 + 默认圆角 → 直接用 `@drawable/widget_card_default`：真实 shape，圆角原生、跟随深浅色、零开销；
  **放了图片时改用 `widget_card_plain`（同款但没有描边）** —— 图片边缘/圆角处的透明会把卡片描边
  透出来，看起来像"图片外面围了一圈暗线"；同理自定义颜色的位图在放了图片时也不画描边。
- 自定义配色**或自定义圆角** → 把「颜色 + 透明度 + 圆角 + 描边」画成一张小位图交给 `ImageView` 拉伸。
  位图**按小组件的长宽比生成**（最长边 96px，约 37KB）：如果固定用正方形位图，
  拉伸到 1x3 这种高组件时圆角会被拉成椭圆，按比例生成就始终是正圆。

**文字位置和字号怎么在 RemoteViews 里做到。**
- 位置：给文字容器和每个 TextView 设置 gravity —— `setInt(viewId, "setGravity", ...)`；
  容器负责整块竖直位置，TextView 负责各自的对齐。
- 微调：`setFloat(viewId, "setTranslationX/Y", px)`，偏移量按卡片尺寸的百分比换算成像素，
  所以组件拉伸后相对位置保持一致。这里特意用 translation 而不是 padding ——
  padding 会压缩文字可用宽度，偏移大了文字会提前变成省略号。
- 字号：`setTextViewTextSize(viewId, COMPLEX_UNIT_SP, size)`，四行文字按同一个百分比缩放。

**调色板（背景色 + 文字颜色）。**
`ColorPickerView` 是一个"饱和度 × 明度"方块，画法用三层叠加（纯色相底色 + 白→透明 +
透明→黑），不用逐像素生成位图，任意尺寸都清晰；色相由一条彩虹滑杆控制。
取色用系统的 `Color.colorToHSV` / `HSVToColor`。同一套控件服务两个目标 ——
顶部的「背景色 / 文字颜色」切换编辑对象，透明度滑杆也跟着切换，省掉一个重复的取色器。
文字颜色在 RemoteViews 侧用 `setTextColor` 显式设置（默认态也显式写主题色，避免上一次的自定义色残留）。

**按卡片比例裁剪 + 拖动/缩放图片。**
`CropImageView` 里裁剪框是**固定**的（比例 = 卡片比例），变化的是图片的变换矩阵：
单指拖动平移、双指捏合缩放（以两指中点为焦点）、按钮 `±` 按 1.25 倍步进。
约束逻辑：图片比框大时不允许拖出空白边，比框小时自动居中。
裁剪结果 = 把框的四角反映射回原图坐标，得到归一化矩形（可能超出 0~1，表示图片外留白）。
另外还有两个容易踩的坑，代码里都做了处理：
- 控件在 `GONE` 状态下宽高是 0，此时算出来的比例是错的 → 初始化推迟到布局完成
  （`onSizeChanged` / 布局后 `post`）；
- 首次布局的尺寸可能不是最终尺寸 → 只要用户还没手动拖过，尺寸一变就重新铺满。

**精确尺寸。**
`RemoteViews.setViewLayoutWidth/Height`（API 31+）可以直接指定 dp 尺寸，
所以「卡片尺寸」能做到严格的正方形；切回"填满格子"时显式写回 `MATCH_PARENT`，
否则上一次的固定尺寸会残留。这里还有个平台坑：`OPTION_APPWIDGET_MIN_*` 并不可靠
（实测 2x2 组件报 179x107，高度只按 1 行算），而 `OPTION_APPWIDGET_SIZES` 的第一项才是
当前真实尺寸，所以尺寸估算优先用它。

**存储。**
每个实例存两份图：`widget_<id>_src.png`（未裁剪原图，供反复裁剪）
和 `widget_<id>.png`（按裁剪框裁好的成品，小组件渲染用的就是它）。
配置按 `widget_<appWidgetId>` 存在 `SharedPreferences("countday_widgets")`。

**"连点三下"是怎么实现的。**
桌面小组件只有 `setOnClickPendingIntent`，没有多击检测，所以点击被接成一条**发给自己的广播**：
receiver 里记录"上一次点击时间 + 连击次数"（存在 SharedPreferences，因为两次广播之间进程可能被回收），
相邻两下不超过 600ms 才算连击，累计到 3 下才 `startActivity` 打开配置页。

这里有个平台坑：Android 10+ 限制后台启动 Activity，从广播里直接 `startActivity` 通常会被拦。
实测这条路径是放行的 —— 因为广播是通过 "别的可见应用（桌面）用 PendingIntent 发来的"，
命中系统的豁免；代码里也做了 `try/catch` 兜底，真被拦的话还有长按「设置」这条路。

> 代价：每次点击都会短暂拉起一下应用进程（只处理一条广播，几毫秒），换来连击能力。

**选择器里的示意图。**
`previewLayout`（API 31+）指向一个**专门做的示例布局** `widget_preview_sample.xml`：
结构和真实布局一致，只是文字写成了示例内容 —— 不加这一层的话，桌面渲染出来的是一张没有任何
文字的白卡片，用户根本看不出这个组件是干什么的。低版本桌面不支持 `previewLayout`，
则退回用 `previewImage`（一张画了"标题条 + 大数字块 + 日期条"的矢量示意图）。

**其他。**
- **只有 1 个 BroadcastReceiver、1 个 ContentProvider、1 个 Activity，没有 Service**，
  也没有 JobScheduler 任务、没有常驻进程；`dumpsys activity services` 查不到任何东西。
- 桌面刷新只有两个入口：**长按 →「设置」**（手动改），以及每天零点后的那一次闹钟（跨天）。
- `updatePeriodMillis="0"`：不使用系统周期性唤醒，避免耗电。
- `allowBackup="false"`：不参与云备份，避免恢复后实例 id 对不上。
- 配置页用对话框主题，默认 `RESULT_CANCELED`，按返回键即取消。
- **不能再依赖"离开即取消"**：早先版本在 `onStop()` 里 `finish()` 自己（想实现"离开就取消"），
  结果在部分 ROM（实测某国产 ROM）上，页面被系统短暂 stop 一下就会自杀、随后又被重新拉起 ——
  表现就是**每次改设置页面都跳回最上面**。现在改成：
  - 不用 `android:noHistory`（它会在打开选图器的瞬间销毁页面，导致"选完图闪退"）；
  - 也去掉 `onStop()` 里的 `finish()`；
  - 改为**完整保存/恢复编辑状态**（`onSaveInstanceState` 存下颜色/排版/尺寸/裁剪等全部字段），
    配合 `ScrollView` 的 id（系统会自动恢复滚动位置），所以即使 Activity 真的被重建，
    设置和滚动位置都原样接回来。
- 选图只放内存，只有点「保存」才落盘，所以取消不会留下半成品。

## 排查

```bash
adb logcat -c && adb logcat | grep -E "CountDay|AndroidRuntime|io.countday"
```

代码里所有可能失败的路径（读图、刷新小组件）都会带 `CountDay` 标签打印警告，
崩溃堆栈则来自 `AndroidRuntime`。

## 验证

```bash
bash tools/run-tests.sh   # DayMath：闰年、跨年、同日、零点闹钟时间点等 13 项断言
                          # BoxMath：卡片跟随图片比例的各种宽高比 / 异常比例，
                          #          以及"图片永远盖住裁剪框"的缩放与偏移不变量
```

**已在真机上验证过**（Android 17 / SDK 37）：

| 项目 | 结果 |
|---|---|
| 安装、无桌面图标 | ✅ `query-activities -c LAUNCHER` 查不到 |
| 配置页 | ✅ 色块/滑杆/文字位置/字号/裁剪/预览全部正常 |
| 点击小组件进入编辑 | ✅ 原有数据正确回填 |
| 多实例独立 | ✅ 桌面上两个实例各管各的 |
| 天数计算 | ✅ 96 天、239 天手算一致 |
| 背景色 + 透明度 | ✅ 白/蓝/黑、95%/低透明度都对 |
| PNG 透明通道 | ✅ 蓝色圆环 + 透明中心的测试图：透明处显示背景卡颜色，不透明处纹丝不动 |
| **手动裁剪** | ✅ 四象限测试图裁到只剩右下黄色象限，桌面同步生效 |
| **文字位置** | ✅ 上/中/下 + 左/居中/右 都对，像素级确认左对齐 |
| **文字大小** | ✅ 154% 实测放大 1.55 倍 |
| **长宽调节** | ✅ 2x2 → 3 行：内容自动重排居中、圆角按新长宽比重算 |
| 跨天闹钟 | ✅ `RTC` 非精确闹钟，触发时间换算为次日 00:00:03 |
| 单击/双击不响应 | ✅ 单击、双击后焦点都仍在桌面 |
| 连点三下进设置 | ✅ 三击后配置页打开，且是该实例的数据（显示「编辑纪念日」+ 该实例日期）；日志无拦截警告 |
| 长按进设置 | ✅ 长按 →「设置」同样能进配置页（来自 `widgetFeatures="reconfigurable"`） |
| 删掉相册原图无影响 | ✅ 删除相册文件 + 强制组件重绘后，桌面颜色数量逐项一致（红 2270／蓝 2318／黄 2237）——组件只用应用私有副本 |
| 选择器示意图 | ✅ 桌面「添加小组件」列表里显示 生日 / 128 / 目标 2026-12-24 的示例卡片（不是空白卡） |
| 文字微调 | ✅ 偏移 -23% → 文字整体上移 144px，正好是卡片高度 626px × 23% |
| 数字后的小号"天" | ✅ 层级实测：`239` 高 155px，紧跟其后的 `天` 高 **52px** —— 和日期小字 `起于 2026-01-23`（也是 52px）**完全一致**，水平间距 7px |
| 透明图边缘无描边 | ✅ 修复前卡片边缘有 20~37 的亮度凹陷（1dp 描边透出），修复后边缘逐像素平滑（214/179 无起伏） |
| 编辑状态不丢 / 不跳顶 | ✅ 改系统字号触发 Activity 重建后：偏移设置 +13% 与滚动位置都保留（旧版同样操作会跳回顶部） |
| 崩溃 | ✅ 全程 0 个 `FATAL EXCEPTION` |

> 说明：这台机器起不了 Android 模拟器，上面是用 USB/无线 adb 连真机跑出来的。
> 唯一没验证的是闹钟**真正在零点触发**那一下（改系统时间需要 root）。


```bash
```
