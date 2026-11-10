plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.agent.idcode"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.agent.idcode"
        minSdk = 31          // Android 12
        targetSdk = 35       // Android 15 平台；Android 16 设备直接向上兼容
        // vc301：① 快照收漏掉的淘宝在途（tbTransit）+ App 内刷新也走跃迁通知
        //        ② 淘宝详情页抠取件码（格口码优先、官方短码兜底）
        //        ③ 已到站的淘宝件进第 4 栏取件码列表
        // vc302：④ 首见到站/派件补报（判定 + 自检用例同步）
        // vc304：⑤ 直连查码链（fetchList HTTP + 直开详情页，退役盲点坐标链）
        // vc304b：⑥ 直开 assistant_pickup 取件页，真码 59-6-6012 上卡闭环
        // vc305：⑦ 批量抓码 —— 查码链多单化：一次取件页配回驿站全部「单号↔码」
        // vc306：⑧ 鲸鱼娘装扮 12→8 套（删原 1/9/11/12，重排为装扮一~八；默认套改 study）
        // vc307：⑨ 流体云左侧小图标换 App 图标（原系统日程本图标）；⑩ 设置页删左上角返回键
        // vc308：⑪ 淘宝侧查码桩 —— 校园「我的快递」页（last-mile-fe/m-end-school-tab）探针
        // vc309：⑫ 淘宝侧查码**生产通道**（TbSchoolChannel：queryMultisitePkgs + shelfNo）
        //        挂到批量链尾（⑤），只补拼多多看不到的件，不拖慢原有 4.3s 快路
        // vc310：⑬ 淘宝通道去掉固定 2.2s 等待，改 250ms 轮询 lib-mtop 就位即注入 + 毫秒自计时
        // vc311：⑭ 长按取件码卡片可手动打/撤「已取件」（SP 持久化，24h 后自动清）；
        //        debug_dump_cache / debug_tb_picked
        // vc312：⑮ 「全是终态 ⇒ 0 张卡」不再误判成中断（真机实锤：只有一单且已取走时
        //        整个收尾被 failed 挡住）
        // vc313：⑯ 取证开关：淘宝校园接口原始返回落盘（查「已取走的件在接口里还有没有记录」）
        // vc314：⑰ 卡里顺带存 bizOrderId（SP tb_oid_<运单号> 留档：详情页只认订单号）
        // vc316：⑱ ★ 删除「取件后自动打已取件标记」整条链（同志 10-18 定）：
        //        自动判据（拼多多状态表 / 在站快照 / 淘宝详情终态 / 静默复查）全部删除 ——
        //        「已取件」灰标从此**只由长按卡片手动打**，24h 后自动清。
        //        理由：状态文案会滞后或说谎（极兔「已签收」只是到代收点，vc315 真机实证），
        //        机器猜错一次就把还要用的码划掉；让用户点一下最可靠。
        //        保留的自动行为只有一件：某源本次抓取成功、且明确说这单已不在它那儿
        //        ⇒ 把取件码从「取件码」栏移除（不打标记）。
        // vc317：⑲ ★ 顺丰登录页「UI 拥挤」修复（同志 10-18 报，K70 Ultra 真机取证）：
        //        顺丰官网 rem 字号断点表在 375~376px 有缝（K70 Ultra 1220÷3.25=375.38 掉缝），
        //        根字号退回 16px ⇒ 正文 .16rem=2.56~8px ⇒ 整页缩小成"微生物"。
        //        修复：createWebView 对 loginMode=sf 的页面按顺丰自家断点表补设 html 根字号
        //        （onPageStarted + onPageFinished 双注入，幂等）。另加 --ez debug_webdev 调试开关。
        // vc318：⑳ ★ 取件码页底部快捷导航（同志 10-18 定）：淘/拼/菜 三个 48dp 圆角方块徽标
        //        （与主页三栏同款；同晚同志改口"只要单纯栏图标+按压动效"，立体球形版已弃），
        //        点击直达对应身份码页；dock 非 clickable ⇒ 空白处触摸穿透；列表底部加 96dp
        //        让卡能滚出图标区；动效只保留按压（按下 0.90 缩、松手 Overshoot 回弹）。
        // vc319：㉑ ★ 快捷导航深色样式（同志 10-18 深夜定："深色下不突兀、不刺眼抢眼"）：
        //        近黑画布上直铺满饱和品牌色像霓虹灯 ⇒ 「暗面徽章」：底=画布混 20% 品牌色、
        //        文字=品牌色混 45% 白、描边=品牌色 20% 透明 1dp；浅色模式维持原样。
        // vc320：㉒ ★ 快捷导航与页面同进同出 + 淡入淡出（同志 10-18 深夜报"退出时图标晚一拍
        //        消失有割裂感"）。根因：dock 挂 root，而预测性返回的跟手滑出只动页面层 ⇒
        //        页面滑走了图标还留在原地。修复：dock 改挂取件码层内部（层改 FrameLayout
        //        外壳+column 纵列），跟手滑出/取消弹回天然同步；closePickup 增加 180ms
        //        整层淡出（无手势路径的退场，图标随层一起淡出）；淡入继承 layerIn 220ms。
        // vc321：㉓ ★ 小米超级岛（HyperOS3 焦点通知）——照 Maling-Island 一比一复刻（同志 10-19 定）。
        //        IslandHelper（miui.focus.param JSON 模板+本地 Icon 图片+保温两连发+三查询）
        //        + IslandBypass（Shizuku/Stellar 掐网绕白名单：断 com.xiaomi.xmsf 网→发布→100ms 恢复）
        //        + IslandActionReceiver（岛上「已完成」撤岛）。
        //        设置页：「ColorOS 系统功能」改名「系统功能适配」，新增 超级岛主开关（硬闸：
        //        Shizuku 与 Stellar 都未授权开不了，弹自绘提示条）+ 绕过开关 + 支持状态行；
        //        到件链 TrackWorker/快照 5 处挂同步；debug 开关 --ez debug_island true。
        //        非澎湃OS 设备能力探测不过 ⇒ 一条不发（防 ColorOS 抽屉垃圾通知）。
        // vc322：㉔ ★ vc321 追改（同志 10-19 01:24 反馈）：① 超级岛行名改「超级岛（实验版）」；
        //        ② 删「超级岛支持状态」独立行 —— 授权提示只在点击开关那一刻弹（appToast）；
        //        ③ 闸门加取证日志（shizukuReady/stellarReady/engine），排查"取消 Stellar 授权
        //        后开关还能开"——1.0.3 客户端 checkSelfPermission 是现场远程调用（上游源码实锤），
        //        怀疑 Stellar 服务端/兼容通道仍认旧授权，真机拨一次看日志定责。
        // vc324：㉖ ★ 闸门口径终定（同志 10-19 03:48+拍板「简单一些」）：**完全不看授权**，
        //        只看设置里 Shizuku / Stellar 两个开关 —— 有一个开着 ⇒ 超级岛开关正常打开；
        //        两个都关着 ⇒ 弹回 + appToast 提示。删底层探测与取证日志（管理器"拒绝"与
        //        服务端授权实测两套账，无可读信号，不再纠缠）。
        // vc325：㉗ 开启提示文案定稿（同志 10-19 04:01）：「已开启。这台手机检测不到超级岛，仅澎湃系统可用」。
        // ★ vc316b（同志 2026-10-18 20:26 定）：显示版本号改为 **0.9.8.1**（同志自己的编号口径）。
        //   ⚠️ versionCode 仍必须是单调递增的整数（它才是安装器判定"新旧/能否覆盖安装"的依据）。
        // vc341：㉙ PDD 卡「有码没地址」统一补口子（PME110 复检）—— vc340 只挂在 AutoCode 链上，
        //        而码来自 native 兜底的单"缺码=0"，链根本不启动。vc341 加 ensurePddStationAddress：
        //        谁发现 PDD 卡有码但 stationText/stationName/nickName 三位全空，谁按订单号问一次
        //        cabinet 接口补地址（6h 限频）；渲染期全量扫描兜底，轨迹行族放宽 + 显示端认领
        //        stationName/nickName。
        // vc342：㉚ ★ 结构性优化 A+B（同志 10-20 定「先备份，然后把 A 和 B 做了」）：
        //        A 止血：① 补码改"一次刷新一批"（纯 HTTP 批量扫 → 取件页批量配对 → 最多 2 单逐单兜底），
        //        ② PddTraceChannel 由"忙就拒绝"改**优先级队列**（前台优先/后台让路、同键去重、硬兜底），
        //        ③ 第 5 栏完成计数统一结算（修 tbTransitDone/忙路径的漏账 ⇒ 转圈不停、缓存不落盘），
        //        ④ 每个源 20s 单源硬超时（不再一个源拖整栏 70s），⑤ 补码状态落盘（6h 衰减）。
        //        B 顺畅：⑥ 第 4/5 栏共享订单页 60s 缓存（同一批接口不重复拉），
        //        ⑦ 轨迹增强批次改"排队补跑"（不再整批丢掉；作废路径清零忙碌标记），
        //        ⑧ 90s 进页闸分级（手动刷新写时间戳、手动单号不受闸），⑨ 直连驿站失败重试一次。
        // vc343：㉛ 修同志 2026-10-20 报的两个取件码问题（日志 v1.0 / 小米 2407FRK8EC）：
        //        ① 淘宝件在取件码页显示「菜鸟」—— 菜鸟通道本就是淘宝登录态查的，
        //           srcName 一直叫「淘宝」，唯独卡片 chip 写死了 cainiao_tag；
        //           现在按包裹平台字段判断，无字段则按账号归属显示「淘宝」。
        //        ② 重复取件码 —— 缓存补卡判据只认 orderSn/waybillNo，而菜鸟卡的号在 mailNo，
        //           两个字段都空 ⇒ 每轮渲染都把缓存卡再补一遍；改为 pickKey + 同码兜底，
        //           并在写新键时清掉同源同码的旧键；另修 allDone 在 expect=0 时误判"完成"
        //           导致无端清缓存（键漂移的源头）。
        // vc344：㉜ 同志 2026-10-20 两份日志（20:41 / 21:48）修四件事；
        //        显示版本改回 1.0.x，**以后每版 +0.01**（1.0.2 → 1.0.3 → …）。
        //        ① 重复取件码：vc343 的缓存键/补卡判据修复，本版一起交付；
        //        ② 小米商城老单混进途中栏：补「已收货/已签收…」终态词 + 45 天年龄兜底；
        //        ③ 拼多多该进取件码却在途中：cabinet 接口交叉确认，有码即搬进取件码栏；
        //        ④ 手动单号该显示已签收却显示运输中：ischeck 漏报时用轨迹文案兜底。
        //        ⚠️ 「无平台不显示角标」「流体云/通知加地址」两项**按同志要求暂缓**，本版不含。
        // vc345：㉝ 同志 2026-10-20 恢复的两项（之前按他要求暂停过）：
        //        ① 认不出平台的菜鸟卡**不显示平台角标**（不再兜底写「淘宝」）；
        //        ② 流体云 + 常规通知加入**取件地址**：fromPkg 统一收
        //           stationText/stationName/nickName，流体云 body 写「取件地址：X」，
        //           通知展开态补一行。
        //        （vc344 的另一批：重复码、小米老单、PDD 到站搬栏、手动签收，保持不变）
        // vc346：㉞ 同志 2026-10-20 22:29 日志：途中两个 bug 仍在，复盘出两处"改了没生效"的路径：
        //        ① 小米：fetchMiChannel 是 if(pickup) else if(transit)，且 err 非空就跳过解析
        //           ⇒ 有品那一路失败一次，miTransit 永远停在旧缓存（2020 老单刷不掉）。
        //           改成两栏独立 + **err 非空也用已抓到的 captures 重算**，并打出丢弃原因。
        //        ② 手动单号：卡片右侧走 etaText()，它不管 status 是什么都兜成"在途中" ⇒
        //           已签收也显示在途中。改成终态优先返回"已签收"；Kd100 签收判定改为任意轨迹命中。
        // vc347：㉟ 同志 2026-10-20 报「拼多多有两个途中快递识别不出来」：
        //        日志里那两条是「已签收，待确认收货」—— 驿站代签、用户还没确认，
        //        旧逻辑按"已签收"当终态整条丢掉 ⇒ 两栏都看不见。
        //        修：PddChannel.isTransitTerminal 对「待确认/待收货」放行；
        //        inTransitScan 的驿站文案 / shipping_status 两道过滤对这类单也放行，
        //        交给 cabinet 交叉确认：有码进取件码栏，没码留途中；
        //        WAIT_HINTS 补「待确认」，批量取码链也会去问一次。
        // vc348：㊱ 同志实证：拼多多两个件显示"运输中，预计明天到达"，但订单状态字段
        //        写的是「已签收，待确认收货」—— **订单状态会说谎，物流文案才是真的**。
        //        旧逻辑按订单状态整条丢掉 ⇒ 途中栏少两个件。
        //        修：新增 MSG_IN_TRANSIT（运输中/派送/预计…），冲突时以物流为准；
        //        退款/取消/交易成功等"硬终态"仍然无条件丢弃；驿站文案与 shipping_status
        //        两道过滤只在"物流没说在途"时才生效。vc347 的待确认放行保留作兜底。
        // vc349：㊲ 同志 10-21 报「之前签收的快递在途中刷出来」：
        //        「已签收」+ 物流文案为空的单分不清真假签收（订单状态会滞后，物流又没给词）。
        //        修：这类**歧义单**不再进途中，交给 cabinet 接口裁决 ——
        //        有取件码 → 进取件码栏；无码 → 保持不显示。TransitReport 增加 ambiguous 通道，
        //        上限 5 单、仅 45 天内、每单 10 分钟限频。
        // vc350：㊳ 同志 10-22 截图实证：多多买菜单（PO-开头，无运单号/无物流直链/物流文案为空，
        //        订单状态恒「待收货」）被当成"在途快递"挂了 5 天。
        //        修：① inTransitScan / PddChannel.parseInTransit 对"三无"单（无运单号+无直链+
        //        物流文案空）不再进途中 —— 它们不是快递；② etaText 不再给这种卡编"在途中"，
        //        如实显示「待收货」。
        // vc351：㊴ **验证版（不改任何显示逻辑）**：新增 PddNative.trackStatuses +
        //        adb 调试入口 `--ez debug_enum_probe true`，把拼多多自己的
        //        express/track/status 枚举逐单打出来（含卡片当前状态），先核对语义再改分栏。
        // vc352：㊵ 拼多多判断改成"两层 + 补码链"（同志 2026-10-23 逐条拍板）：
        //        ① 枚举层：单批 10、总上限 25、首屏等枚举（≤1.5s）；
        //        ② 只信已证实的 OUT_CABINET/GOT=已取走；SIGN/未知/3001 一律走文案兜底；
        //        ③ 无码件补码链：拼多多驿站直连 → 淘宝按运单号（一次最多 4）→ 页面扣字兜底；
        //        ④ 无码拼多多卡占位文案「正在拉取取件码，10 秒后再刷新一下」。
        // vc353：㊶ 轨迹增强兜底：新版订单卡印「待收货」而不是「运输中」，旧锚点匹配不到 ⇒
        //        必然 no-card。补「待收货」锚点；直链/快递100 都失败时，最后再走一次
        //        订单列表页点「查看物流」（硬校验 realOrderSn，点错单也不会串数据）。
        // vc354：㊷ 淘宝也接入"枚举优先"（阿里/菜鸟标准物流状态表）：
        //        物流详情页 SSR 里抠 logisticsPackageStatus.statusCode，STA_INBOUND=已入柜/驿站
        //        → 取件码；STA_SIGN/SIGN/AGENT_SIGN=已签收/已取出 → 不显示；
        //        TRANSPORT/DELIVERING/ARRIVE_CITY… → 途中。未知值走文案兜底。
        //        拼多多补 DEPARTURE（已实证 2/2）→ 强制留途中，跳过文案误杀。
        //        每次抓到轨迹/枚举都打一行 TBEnum / PddEnum 日志，用于继续补全枚举样本。
        // vc355：㊸ 淘宝按阿里/菜鸟标准表补全枚举：INBOUND→取件码、OUTBOUND/SIGNED→已取走、
        //         SENT→途中；异常类（FAILED/RETURN/ISSUE/REJECTION/SIGNFAIL）不硬判，走文案层。
        //         拼多多维持既定映射（OUT_CABINET/GOT=已取走、DEPARTURE=在途、SIGN/未知=文案）。
        // vc356：㊹ 修「已到站但没码 → 两栏都消失」：
        //        ① cabinet 返回 place 但 code 空，也记入到站结果（以前直接丢）；
        //        ② 已到站无码 → 出占位卡（"正在拉取取件码…"）+ 触发淘宝按运单号补码；
        //        ③ 枚举 IN_CABINET（已入柜/驿站）同样按到站处理。
        //        典型场景：件在菜鸟驿站，码在菜鸟手里，拼多多只给地址不给码。
        // vc357：㊺ 重新加回「自动打已取标签」（同志 2026-10-23 拍板，推翻 vc316 的"只手动"）：
        //         开关在设置页「快递跟踪」分类，默认开。
        //         判据：拼多多 OUT_CABINET/GOT；淘宝 STA_SIGN/SIGN/SIGNED/AGENT_SIGN/OUTBOUND/RETURN_SIGN。
        //         命中时取件码卡打灰标（已取件 chip + 划线），保留 24h 自动消失；关掉开关则维持剔除行为。
        // vc358：㊻ ①「自动打已取标签」默认改为**关闭**（同志拍板，要用自己开）；
        //         ② 后台轮询也给取件码卡做已取判定：开关开时查枚举，出库(OUT_CABINET/GOT)
        //            打灰标；打标满 24h 的卡在轮询里顺手清掉（以前只有打开取件码页才清）。
        // vc360：㊻+1 身份码页诊断：打开后 4s 起每 6s 探一次（共 5 次），把
        //        最终网址/标题/正文字数/登录字样记入 IdDiag 日志——
        //        以后"身份码刷不出来"一条日志就能分清：没登录/账号没码/加载失败。
        // vc360：㊺+2 京东运单号探针（只读验证，不改生产行为）：--es debug_jd_wuliu <订单号>
        //        打开详情页 → 自动点「查看物流」→ 扫全部旁听响应+DOM，找各家运单号形态，
        //        落盘 jd_wuliu.txt。能拿到 ⇒ 京东轨迹（快递100）与取件码（淘宝按运单号）可接。
        // vc361：㊻ 拼多多分栏彻底按"枚举优先 + 文字兜底"两层：
        //        ① 打「已取」灰标只认"本轮之前在屏幕上"的单（历史单直接剔除，不再挂 10 多张旧码）；
        //        ② 终态词补「待评价」（待评价/已评价/交易成功 都算完结）；
        //        ③ 补码不再拿空地址覆盖缓存（修"底部地址几秒后变待取件+时间"）；
        //        ④ 已打灰标的卡不再参与补码作业。
        // vc646：账号换号/退出改为来源级清理；不再因淘宝变化清空拼多多、京东、小米数据。
        // vc647：修复 PDD 订单 JSON 中「申请退款」按钮文案误判为退款终态，恢复真实待取订单查码。
        // vc648：修复淘宝退出后身份码诊断读取旧字符串时间戳导致 ClassCastException 闪退。
        // vc649：副号扫描清理账号空订单缓存、扩大分页，并将副号取件码直接落持久缓存。
        // vc650：副号候选订单用副号 Cookie 走物流页补码，成功后写入取件码缓存并刷新第4栏。
        // vc651：副号补码优先使用订单列表提供的直链，避免页面点错相似订单。
        // vc652：副号详情补码改为逐单串行提交，避免共享 WebView 队列批量超时。
        // vc653：副号补码改为逐单独立等待并扩至10单，避免固定批次闩锁提前放弃后续订单。
        // vc654：副号失败订单各重试一次，并从物流页文本补齐取件地址。
        // ★★ vc690：同志定 —— 对外版本 **1.2**，内部 versionCode **690**（紧接着历史的 682 往上）。
        //   以后每版在 690 基础上 +1；任何 ≤689 的旧包都无法覆盖安装。
        // ★★ vc691/692 → 对外 **1.2.5**，内部 **691**（同志 2026-11-04 14:44 要求提版本）。
        // v1.4.5：dedupKey 补 validNo() 闸（optString 读到 JSON null 返回字符串 "null"、
        //         normNo 会把 "-"/"—"/"(无)" 滤成空串，两种退化都会让缺号的卡共用同一个键）；
        //         在途卡补 put("orderSn") 稳定锚点；TransitTerminal 豁免日志字段名修正。
        // v1.4.6：★★ 在途 9 件塌成 1 件的**真正根因**修复 ——
        //   整套补轨迹子系统（enhancePddTransitTraces 的 targets、applyTransitTrace 的匹配键、
        //   `firstOrNull { waybillNo == sn }` 的查找）都把 `waybillNo` 当**订单号**在用，
        //   而 applyTransitTrace 却把它就地改写成真运单号 ⇒ 身份被整体污染。
        //   真机日志实证（2026-11-10 10:09:43）：9 张卡 orderSn 各不相同，
        //   `waybillNo` 却全被涂成同一个 `79157444920500` ⇒ dedupKey 全等于 W:791... ⇒ 9 并成 1。
        //   ① applyTransitTrace：真运单号只写 `realWaybill`，**永不改写 `waybillNo`**；
        //      匹配键改「orderSn 优先，waybillNo 兜底」，让 firstOrNull 查找不再失效。
        //   ② dedupKey：pdd 卡先看 realWaybill（真号）、再退 orderSn，不再拿订单号当运单号比。
        //   ③ dedupPkgs：**同源、orderSn 不同 ⇒ 绝不合并**（一个源不会把同一件抓成两张），
        //      上游字段被写脏时不再等于"包裹消失"。
        // v1.4.7：收尾 1.4.6 留下的脏数据 —— 被写脏的轨迹缓存/卡片缓存会让**所有卡片显示同一段物流**
        //   （真机实证 2026-11-10 10:16：10 张卡运单号各不相同，文案逐字相同）。
        //   判据：两个不同包裹不可能有逐字相同的多行全程轨迹 ⇒ 同一段轨迹挂多单即为脏数据，
        //   整组丢弃，由下一轮按 orderSn 重补（1.4.6 起匹配 1:1，不会再串）。
        versionCode = 726
        versionName = "1.4.7"
    }

    buildTypes {
        release {
            // ★ vc282：APK 8MB → 唯一大头是 dex 7.18MB（kotlin/coroutines/androidx 全量未裁）。
            //   开 R8 裁剪 + 资源收缩；keep 规则见 proguard-rules.pro
            //   （Worker 反射实例化 / AIDL Binder 接口 / Shizuku 全保）。
            //   ★ vc286 A/B 实证：淘宝身份码白屏与 R8 无关（关 R8 依旧白屏，根因在淘宝
            //   服务端风控——菜鸟同 cookie 亮码正常），R8 恢复启用。
            isMinifyEnabled = true
            // ★★ v1.3.4：**关掉资源收缩**。1.3.3 装上后一启动就崩：
            //   `Resources$NotFoundException: String resource ID #0x7f0a0015`，
            //   同时桌面图标退回系统默认图标 —— 都是"资源被裁掉"的典型表现。
            //   资源收缩只省几十 KB，代价是不可预测的启动失败，不值。
            //   R8 代码裁剪（isMinifyEnabled）保持不变。
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    packaging {
        // dex 默认 STORED 不压缩；改 DEFLATED 换 1~2MB 体积，解压开销一次性（安装后 dex 已优化）
        dex { useLegacyPackaging = true }
    }
    // ★ AIDL：跨进程接口（Shizuku 看门人 IWatchDog）。新版 AGP 默认可能不生成，显式打开。
    buildFeatures {
        aidl = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// ★ Stellar-API 的传递依赖带了 androidx.core 1.17.0（要求 compileSdk 36），
//   而本机 SDK 只装到 35、AGP 8.9.2 —— 不为此升 SDK，把 core 压回 35 兼容的 1.15.0。
//   风险可控：Stellar-API 只用 Binder/Provider 基础能力，不依赖 core 1.17 的新 API。
configurations.all {
    resolutionStrategy {
        force("androidx.core:core:1.15.0")
        force("androidx.core:core-ktx:1.15.0")
    }
}

// 零 native 库 => 32/64 位设备通用，arm64 上自动以 64 位运行。
dependencies {
    // 只为 WebViewCompat.addDocumentStartJavaScript：必须在页面 JS 执行【之前】注入钩子，
    // 否则京东首屏的 JSONP（订单列表）会在钩子装上之前就发出去，数据永远抓不到。
    implementation("androidx.webkit:webkit:1.12.1")

    // 后台快递轮询：WorkManager 是系统级任务调度（不需要 root / Shizuku / 无障碍，
    // 也不用常驻前台服务），15 分钟为周期下限。TrackWorker / TrackScheduler 用它。
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // 预测性返回（侧滑跟手动画）。
    // ★ 为什么必须用它：系统 `android.window.OnBackInvokedCallback` 里
    //   onBackStarted/Progressed/Cancelled 是 **@SystemApi**，应用层 override 不了
    //   （实测 android-35 存根里只有 onBackInvoked，编译报 overrides nothing）。
    //   AndroidX 的 OnBackAnimationCallback 内部桥接系统能力，才是给应用用的口子。
    implementation("androidx.activity:activity-ktx:1.9.3")

    // ★ 下拉刷新（vc264m，同志要求第 4/5 栏支持"下拉松手刷新"）：
    //   SwipeRefreshLayout 自带跟手下拉 + 转圈 + 收起动画；配色接项目色板适配深浅模式
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // ★ Shizuku（vc264，可选增强）：用来拉起一个**独立特权进程**当"看门人"。
    //   主进程被系统回收后，看门人（shell/root 权限）能发一条带令牌的广播把服务叫回来。
    //   ⚠️ 前提是用户自己装了 Shizuku 并激活；**没装时全部逻辑静默降级**，
    //      绝不允许因为它不存在就崩溃或功能异常（KeepAliveService 的三层基础不依赖它）。
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // ★ Stellar（同志 10-15 要求）：Shizuku 的深度定制分支，API 高度同源。
    //   同志手机上只装了 Stellar（没装原版 Shizuku）⇒ 原生适配它，
    //   与 Shizuku 路径并存：装了哪个走哪个，都没装则全部静默降级。
    implementation("com.github.roro2239:Stellar-API:1.0.3")
    // ⚠️ userservice 模块**上游没发布到 Maven**（jitpack.yml 漏列模块，构建日志实锤）
    //   ⇒ 该模块 3 个必需源码文件已拷入本项目自编译（app/src/main/java/roro/stellar/userservice/）。
    //   别再试图加 `com.github.roro2239.Stellar-API:userservice:x` 依赖，Maven 上没有。
}
