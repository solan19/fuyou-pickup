# 蜉蝣·取件通

把散落在淘宝 / 菜鸟 / 拼多多 / 京东 / 小米的**取件码**和**在途物流**，聚到一屏看完的 Android App。

作者：**酷安@灰太狼来啦**
最初参考了 GitHub 上的开源项目 **ExpressAssistant** 的思路，之后按自己的用法重写并长期真机迭代。

| 项目 | 说明 |
| --- | --- |
| 版本 | 1.4.28（内部 versionCode 747） |
| 包名 | `com.agent.idcode` |
| 系统要求 | Android 12（API 31）及以上 |
| 语言 | Kotlin（JVM Target 17） |
| 编译 / 目标 SDK | 35 |
| **构建 JDK** | **JDK 21**（Gradle 8.13 需要；JDK 17 编译会失败） |
| 构建工具 | Gradle 8.13（已带 Wrapper）+ Build-Tools 35.0.0 |
| 许可证 | MIT（见 [LICENSE](LICENSE)） |

## 能做什么

- **取件码聚合**：拼多多、淘宝 / 菜鸟、京东、小米、手动单号；拼多多支持主号 + 多个副号。
- **真码反查**：平台只给「运单号后五位」时，自动按运单号去淘宝校园驿站等通道换真实货架码。
- **途中快递**：在途包裹卡片、物流轨迹、广告文案清洗、快递公司标签。
- **通知抓码**：从拼多多 / 抖音 / 淘宝 / 京东 / 小红书 / 菜鸟等 10 个白名单平台的通知里解析取件码，写入取件码页（需系统「通知使用权」；总开关与平台开关默认全开）。
- **定时取件提醒**：自己选允许提醒的时间段 + 段内逐个小时刻，每天最多 6 次；到点优先推流体云 / 超级岛，设备不支持或推送失败才回退普通通知。
- **身份码**：App 内直开淘宝 / 菜鸟 / 拼多多身份码页。
- **系统展示**：通知、ColorOS 流体云（Android 16 提升型通知）、小米超级岛（实验）、桌面小组件、快捷磁贴。
- **应用内更新**：设置 → 关于应用 → 检查更新。清单与安装包**多源自动回退**（镜像页 → gcore.jsDelivr → ghproxy → GitHub raw），某一条被网络重置也能更新；**不弹窗、不自动下载、不自动安装**，要不要更新由用户点。
- **黑名单**：长按卡片删除；「全部删除」= 永久隐藏（只能清 App 数据恢复）、「全部移除」= 正常移除。
- **其它**：凝光视效主题、深浅主题、多套装扮、下拉刷新、离线缓存、手动标记「已取件」。

## 数据是怎么来的

**App 没有任何服务器，也不上传用户数据。**

1. 用户在 App 内置的 WebView 里**自己登录**各平台，登录态（Cookie）存在本机；
2. 之后由 App 在**本机**直接请求这些平台的 H5 接口，或用隐藏 WebView 打开官方页面读取内容；
3. 抓到的取件码、驿站名、物流文案全部写进**本机缓存**，供首屏秒显、通知、小组件复用。

> 这些接口都是各平台**未公开**的 H5 / 移动端接口，改版、风控随时可能让某块功能失效 —— 这是这类工具的通病，不是写错了。
> 本项目与淘宝、菜鸟、拼多多、京东、小米、快递 100 等**没有任何隶属或合作关系**，商标归各自所有者。

## 怎么打开 / 编译

1. 装 Android Studio（Ladybug 或更新），**JDK 21**，SDK Platform 35 + Build-Tools 35.0.0；
2. 直接用 Android Studio 打开**这个文件夹**（`build.gradle.kts` 所在目录）；
3. 首次打开时 Studio 会提示缺 `local.properties`，它会自动生成并填入本机 SDK 路径；命令行编译则自己建一个：

```properties
# local.properties（每台机器不同，不要提交）
sdk.dir=C\:\\你的\\Android\\Sdk
```

4. 命令行：

```bash
gradlew.bat assembleDebug      # Windows
./gradlew assembleDebug        # macOS / Linux
```

国内拉依赖慢的话，`settings.gradle.kts` 里已经配好了阿里云镜像和 JitPack。

## 签名

**这个目录里不含任何签名密钥，也不该含。** 发布版请用你自己的 keystore：

```bash
zipalign -p -f 4 app-release-unsigned.apk aligned.apk
apksigner sign --ks 你的.jks --ks-key-alias 你的别名 --out 成品.apk aligned.apk
```

> 包名固定 `com.agent.idcode`。**换了签名密钥，旧版本就无法覆盖安装**，只能卸载重装（数据会清空）。

CI（`.github/workflows/release.yml`）从 **GitHub Secrets** 读签名材料：`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。没有配置这些 secret 时工作流会直接失败并提示，不会偷偷生成新密钥。

## 更新分发

App 的「检查更新」只读一份清单：**仓库根 `update.json`**（公开仓库 raw，匿名可读）。
安装包放在 `update/` 目录，`apkUrl` 指向它。

**分发闸门**：代码提交 ≠ 分发。只有明确确认要发这个版本，才执行

```bash
python tools/publish_update.py --confirm \
  --version-code 745 --version-name 1.4.26 \
  --apk update/fuyou-1.4.26.apk \
  --apk-url "https://raw.githubusercontent.com/solan19/fuyou-pickup/main/update/fuyou-1.4.26.apk" \
  --notes "本版改了什么"
```

- 不带 `--confirm` 会 `REFUSED` 退出（闸门）；
- `force=false` 时 App 只做展示，**不弹窗、不自动下载、不自动安装**；
- `update/*.apk` 已在 `.gitignore` 里放行（`!update/*.apk`），换版本号的文件名照样能提交。

## 目录结构

```
app/src/main/java/com/agent/idcode/
├── MainActivity.kt            主界面、四栏渲染、卡片与设置页
├── PddNative.kt               拼多多纯 HTTP 通道（订单 / 柜机 / 状态枚举）
├── PddChannel.kt              拼多多 WebView 旁听与登录态
├── PddAccounts.kt             拼多多主号 / 副号管理
├── PddTraceChannel.kt         物流详情页补码与轨迹
├── TbChannel.kt / TbSchoolChannel.kt / TbTransitProbe.kt    淘宝 / 菜鸟 / 校园驿站
├── JdChannel.kt / MiChannel.kt / MiShopChannel.kt / Kd100*.kt   其它平台
├── TrackFetcher.kt / TrackWorker.kt / TrackScheduler.kt     聚合、后台轮询
├── TransitRules.kt            在途件裁决（唯一入口）
├── PddCodeStore.kt / MuteStore.kt / PickedStore.kt / ExpressStore.kt   本机持久化
├── PickupGate.kt              「哪些取件码算待取」唯一裁决点（码 + 未取 + 未屏蔽）
├── PickupPublish.kt           一次发布到小组件 / 流体云 / 超级岛 / 通知的唯一出口
├── NotifyRules.kt / NotifyCodeSink.kt / IdNotifyListener.kt   通知抓码（规则 / 写入 / 监听）
├── PickupReminder.kt          定时提醒（配置 / 定时 tick / 排程 / 渠道分流）
├── UpdateChecker.kt           检查更新（清单 / 版本比较 / 下载校验）
├── IslandHelper.kt / LiveUpdate.kt / *Widget*.kt            通知 / 流体云 / 小组件
├── UiTokens.kt                设计令牌（间距 / 圆角 / 字号 / 动效）
├── SquircleDrawable.kt / NingguangGlass.kt / AmbientGlowDrawable.kt / FollowGlow.kt   凝光视效
└── ...
```

## 已知限制

- 依赖平台未公开接口，平台改版就得跟着改解析逻辑。
- 登录态会过期，需要重新登录；本项目只在本机使用登录态，不提供账号共享或代登录。
- 流体云要 Android 16 的提升型通知 + 系统授权；超级岛要 HyperOS 3。不支持时自动降级为普通通知（设置页会写明降级原因）。
- 定时提醒的「到点用闹钟通道」会在状态栏常驻一个闹钟图标（系统 `setAlarmClock` 的固有代价）；关掉它就不会有图标，但提醒可能被省电策略推迟。
- 代码里有不少历史遗留的诊断开关和兼容分支，是长期真机调出来的，看着乱但有原因。

## 素材说明

App 里的鲸鱼娘立绘、图标、收款码等美术素材**不在 MIT 授权范围内**，版权归原作者 / 画师所有；代码可以随便用，图请自己换。

## 免责

本项目按「现状」提供，仅供个人学习与自用。作者不对数据准确性、可用性、平台规则变更或由此产生的任何损失负责。访问第三方平台的行为由**使用者本人**发起并承担相应责任。

## 许可证

[MIT](LICENSE) © 2026 酷安@灰太狼来啦
