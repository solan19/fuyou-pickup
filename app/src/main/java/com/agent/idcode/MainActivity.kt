package com.agent.idcode

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * 身份码 —— 三栏 WebView 壳。
 *
 * 机理（为什么这样能亮动态码）：淘宝官方存在身份码 H5 页面（末端集配平台 identity-code），
 * 浏览器上下文里登录后即可实时渲染。本 App 只做容器：WebView 保持 cookie 登录态，
 * 码由官方页面自己生成，我们不触碰、不解析、不存任何账号数据。
 */
class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var homeView: LinearLayout
    /**
     * 网页层（WebView 容器）。
     *
     * ★★ 刻意写成「属性 + 自定义 setter」而不是裸字段：
     *    **"有没有层"是"有没有东西可返回"的唯一真相**，而它只由这四个字段的赋值决定。
     *    把同步挂在 setter 上 ⇒ 二十多个开/关点一个都不用改、也永远不会漏。
     *    漏一处的后果很具体：**返回回调的开关直接决定系统播不播「窗口缩回桌面图标」的动画**。
     */
    private var webLayerField: ViewGroup? = null
    private var webLayer: ViewGroup?
        get() = webLayerField
        set(value) {
            webLayerField = value
            syncBackEnabled()
        }

    private var webView: WebView? = null
    private var progressBar: ProgressBar? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    /** 「导出日志」待写入的临时文件（点导出 → 收集日志落临时文件 → 另存为对话框选中后从这里搬过去） */
    private var pendingLogExport: java.io.File? = null

    // 取件码数据通道（隐形 WebView + 原生渲染层）
    private var dataWebView: WebView? = null
    private var dataPageReady = false
    private var dataFetchPending = false
    /**
     * 取件码层（第 4 栏）。
     *
     * ★★ 刻意写成「属性 + 自定义 setter」而不是裸字段：
     *    **"有没有层"是"有没有东西可返回"的唯一真相**，而它只由这四个字段的赋值决定。
     *    把同步挂在 setter 上 ⇒ 二十多个开/关点一个都不用改、也永远不会漏。
     *    漏一处的后果很具体：**返回回调的开关直接决定系统播不播「窗口缩回桌面图标」的动画**。
     */
    private var pickupLayerField: ViewGroup? = null
    private var pickupLayer: ViewGroup?
        get() = pickupLayerField
        set(value) {
            pickupLayerField = value
            syncBackEnabled()
        }

    /** ★ vc423：当前 web 层是不是「淘宝身份码」（用于自动跳回身份码页） */
    private var identityLayerActive = false

    private var pickupListContainer: LinearLayout? = null

    /**
     * ★★ vc673：取件码页里**改过数据**（标记已取件 / 取消标记 / 删除）。
     *
     * 首页那张「N 件待取」主卡是 [buildHome] 一次性建好的快照，不会自己刷新；
     * 以前在取件码页打上「已取件」后返回首页，**卡片和数字还是旧的**，
     * 必须杀进程重进才更新（同志 2026-11-03 报的）。关层时按这个标记重建首页即可。
     */
    private var pickupDirty = false

    /** ★ vc368：按取件地址分组时的吸顶标题（null = 当前不是分组模式/页面没开） */
    private var pickupSticky: TextView? = null
    private var pickupStickyBox: LinearLayout? = null
    private var pickupScroll: android.widget.ScrollView? = null
    private val pickupGroupHeaders = ArrayList<Pair<View, String>>()

    /** ★ vc318：取件码页底部悬浮导航（淘/拼/菜 三个球形图标，点击直达对应身份码页） */
    private var pickupDock: ViewGroup? = null
    private val pickupDockAnims = ArrayList<android.animation.Animator>()

    /** ★ vc264m：第 4 栏的下拉刷新控件（null = 页面没开/已关） */
    private var pickupSwipe: androidx.swiperefreshlayout.widget.SwipeRefreshLayout? = null
    private var pickupStatusText: TextView? = null
    private var pickupTimeout: Runnable? = null
    /**
     * 设置层。
     *
     * ★★ 刻意写成「属性 + 自定义 setter」而不是裸字段：
     *    **"有没有层"是"有没有东西可返回"的唯一真相**，而它只由这四个字段的赋值决定。
     *    把同步挂在 setter 上 ⇒ 二十多个开/关点一个都不用改、也永远不会漏。
     *    漏一处的后果很具体：**返回回调的开关直接决定系统播不播「窗口缩回桌面图标」的动画**。
     */
    private var settingsLayerField: ViewGroup? = null
    private var settingsLayer: ViewGroup?
        get() = settingsLayerField
        set(value) {
            settingsLayerField = value
            // ★★ v1.4.9：层没了就必须把"正在关闭"一起清掉 —— 否则 8 个子页面/主题切换点
            //   那种「removeView + 置 null + openSettings()」的路径会把 closing 留在 true，
            //   下一次关闭时 closeSettings 直接 return ⇒ 设置页关不掉。
            if (value == null) settingsClosing = false
            syncBackEnabled()
        }

    /** 退出动画正在播 —— 防连点返回导致重复 removeView */
    private var settingsClosing = false
    /**
     * 设置键的按下反馈正在播。
     * ⚠️ 原来是拿 `v.scaleX < 1f` 当这个标志 —— **拿动画中间态当状态**：
     *   反馈动画一旦被打断，scaleX 就永久停在 0.9，按钮**从此再也点不动**。
     *   改成布尔标志 + 两条自愈路径（动画结束 / 400ms 兜底）。
     */
    /**
     * ★★ v1.4.9：**布尔标志不可靠，改时间戳**。
     * 同志 2026-11-10 实测「设置页进去一次再退出来就再也进不去了」：真机日志里点齿轮
     * 连一行日志都没有 —— 因为 Log 当年写在 busy 判断**之后**，busy 卡住就静默返回。
     * 布尔值是类级字段、按钮又是单例，两个收尾回调（动画结束 / 400ms 兜底）只要有一个
     * 没跑到（视图重建、动画被取消）就永久为 true ⇒ 永久锁死。
     * 时间戳只挡 450ms 内的连点，不存在"永久锁死"这种状态。
     */
    @Volatile
    private var settingsBtnLastTapMs = 0L
    /** 预测性返回：AndroidX 的 dispatcher（内部桥接系统的预测性返回能力） */
    private val backDispatcher = androidx.activity.OnBackPressedDispatcher()
    private val mainHandler = Handler(Looper.getMainLooper())

    // ---------- 拼多多原生通道运行时状态 ----------
    // 拼多多登录态轮询（页面内登录成功后自动抓 token）
    private var pddPoll: Runnable? = null
    // 网络 IO 单线程池：PddNative 的所有请求都丢这里，绝不占主线程
    /**
     * ★ vc508：**从单线程改成 2 条**。
     * 原来小米登录/换票、快递100 查询、日志自检全排同一条线程 ——
     * 一个快递100 查询要 8~20 秒，期间小米换票只能干等（反之亦然）。
     * 两条线程足够（这些任务本身频率很低），又不会失控。
     */
    private val pddIo = java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
        Thread(r, "pdd-bg").apply { isDaemon = true }
    }

    /**
     * ★ vc508：**拼多多纯 HTTP 任务专用池**（3 条）。
     *
     * 以前驿站直连、cabinet 补地址、淘宝直连、调试 POST 全都挤在 `waybillIo`（4 条，
     * 还要跟补码批次、手动单号抢）—— 点一下刷新，几路同时在跑就排队。
     * 这些任务**都是纯 HTTP、不碰 WebView**，可以安全并行。
     */
    private val pddHttpPool = java.util.concurrent.Executors.newFixedThreadPool(3) { r ->
        Thread(r, "pdd-http").apply { isDaemon = true }
    }

    /** ★ vc508：日志打包单独一条线程 —— 导出大日志不再占用抓取线程 */
    private val exportIo = java.util.concurrent.Executors.newSingleThreadExecutor() { r ->
        Thread(r, "log-export").apply { isDaemon = true }
    }

    /**
     * ★ vc490：**副账号专用线程池**。
     *
     * 原来所有拼多多后台任务都跑 `pddIo`（newSingleThreadExecutor）——一条线程，
     * 谁排前面谁先跑。副账号一多、某个号网络慢，后面的号全堵在这条线上
     * （这正是同志说的"堵线"）。
     * 现在副账号各占一条线程（最多 3 个号），与主号/其它 pddIo 任务完全隔离。
     */
    private val pddAccPool = java.util.concurrent.Executors.newFixedThreadPool(3) { r ->
        Thread(r, "pdd-acc").apply { isDaemon = true }
    }
    /**
     * ★★ vc655：**同一个副号同时只允许跑一轮**。
     *
     * 事故（2026-11-01 20:38 真机日志）：40 秒内出现两次「最终带码 4 单」，
     * 两轮同时开详情页 ⇒ 后面的任务全在队列里排队，日志里一片
     * `已在队列 / 排队超时 / 超时(队列兜底)`，补码结果时多时少 ——
     * 这就是同志看到的"两个两个往外出、还不稳定"。
     */
    private val pddAccInFlight = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    /**
     * ★★ vc655：**补码失败的单，30 分钟内不再重开详情页**。
     *
     * 真机日志（2026-11-01 20:34~20:44）：9 个候选里有 5 个订单**根本没有取件码**
     * （多半已取走/未到站），但每一轮刷新都把它们重新开一遍详情 —— 每轮白等 25~30 秒，
     * 还把 WebView 队列占满，害得真正有码的那几单排不上队（`排队超时`）。
     * ⇒ 失败一次就记下时间，冷却期内直接跳过；过 30 分钟再试。
     *   成功则立即清掉记录（下次照常）。
     */
    private val pddDetailFailAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val PDD_DETAIL_FAIL_COOLDOWN_MS = 30L * 60_000L

    /**
     * ★★ vc659：拼多多「短码」（取件码 = 运单后五位）的反查队列。
     *
     * 同志 2026-11-01 的原话：「任何平台快递取件码为快递单号后 5 位回去淘宝拼多多查询」。
     * 这条链（淘宝校园驿站按运单号查）本来就有，但**只接在淘宝到站卡上**，
     * 拼多多的卡从来没进过 ⇒ 5 个菜鸟单只显示"后五位"，没有真实格口码。
     */
    private val pddShortWb = LinkedHashSet<String>()
    private val pddShortByWb = HashMap<String, String>()
    private var pddShortRunning = false
    /** ★ vc691：淘宝校园页被占用时的"稍后再来"闸，避免重复排定时器 */
    private var pddShortRetryScheduled = false
    /**
     * ★ vc254：手动单号专用并行池。
     * [pddIo] 是**单线程**的 —— 5 个手动单号在它上面串行查要 22.8 秒（2026-10-14 实测）。
     * 这些查询彼此独立，没必要排队 ⇒ 单独开一个 4 线程的池。
     */
    /**
     * ★ vc518：**网页层 WebView 池**。
     * 原来 closeWeb() 直接 destroy → 残留的定时器/回调仍往这个实例 evaluateJavascript，
     * 系统抛 `Application attempted to call on a destroyed WebView`，页面表现为"加载不出来/查询 0 条"
     * （用户 09:09:15 日志里连续几十条）。
     * 现在关页面只是"退场 + 放回池子"，实例一直活着，下次打开直接复用（还省掉重建的 1~2 秒）。
     */
    /**
     * ★ vc524：**到站待补码卡**（运单号 → 卡片）。
     * 不写 PddCodeStore（避免脏缓存），只活在内存里；查到码才落库并从途中删除。
     */
    private val tbPendingCards = LinkedHashMap<String, JSONObject>()

    private val webPool = ArrayDeque<android.webkit.WebView>()
    /** WebView 出厂 UA（复用实例时先复位，避免上一个页面的 UA 残留） */
    private var defaultUa: String? = null

    private val waybillIo = java.util.concurrent.Executors.newFixedThreadPool(4)
    // 取件码层的当前数据源："all"（菜鸟+拼多多）| "pdd"（仅拼多多）
    private var pickupMode = "all"
    private var pickupExpect = 0
    private var pickupGot = 0
    /**
     * vc274：取件码页的"轮次代数"——每开一轮 +1。
     * 闸门兜底计时器（onPickupGate）带代数，开新一轮后旧计时器作废，
     * 防止上一轮的兜底把新轮的 pending 提前排空。
     */
    private var pickupRoundGen = 0
    private var pddNeedLogin = false
    private var pddErr: String? = null
    private var jdErr: String? = null
    /** 还没返回的取件码数据源（加载中文案按它显示"正在获取X取件码"） */
    private val pendingSources = ArrayList<String>()

    // ---------- 在途快递层（第 5 栏） ----------

    private fun openInTransit() {
        if (transitLayer != null) return
        transitEnterMs = System.currentTimeMillis()
        val layer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // ★ v2 马卡龙：页面底铺环境光（四角柔光），白卡浮在上面才"有光可透"
            background = AmbientGlowDrawable(isDark())
        }
        // ★ v2 .bar：26px 大标题 + 12px 副行；顶栏透明（无大白边）
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22 * dp).toInt(), (6 * dp).toInt(), (18 * dp).toInt(), (10 * dp).toInt())
        }
        top.addView(TextView(this).apply {
            text = getString(R.string.bar_transit_title)
            textSize = UiTok.T26
            typeface = tfMedium
            letterSpacing = -0.015f
            setTextColor(cTextPrimary)
        })
        top.addView(TextView(this).apply {
            text = "按平台分开加载 · 下拉可刷新"
            textSize = UiTok.T12
            setTextColor(cTextSecondary)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (4 * dp).toInt() })
        // ★ vc264n（同志 04:16 要求）：删掉右上角刷新键 —— 手动刷新走**下拉手势**
        //   （vc264m 加的 SwipeRefreshLayout，同一入口 fetchInTransit(force=true)）
        attachTopBar(layer, top)

        val status = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(cTextSecondary)
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), 0)
        }
        layer.addView(status, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // ★ vc697：平台标签条 —— 在途快递按平台分开加载，左右滑动切换
        val tabScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            // ★ 四等分必须让子 LinearLayout 真正铺满视口，否则 weight 不生效
            isFillViewport = true
        }
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // ★ v2 .seg：薰衣草胶囊容器，四等分，选中项白底
            background = sq(UiTok.R999, UiTok.segBg(isDark()))
            setPadding((3 * dp).toInt(), (3 * dp).toInt(), (3 * dp).toInt(), (3 * dp).toInt())
        }
        tabScroll.addView(tabRow, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        layer.addView(tabScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = (4 * dp).toInt(); rightMargin = (4 * dp).toInt()
            topMargin = (2 * dp).toInt(); bottomMargin = (14 * dp).toInt()
        })
        transitTabRow = tabRow
        transitTabsScroll = tabScroll

        val scroll = ScrollView(this)
        // ★ P2 渐进遮罩：滚动内容在顶栏下 28dp 内淡出，观感等价 COUI 的渐进模糊边界；
        //   foreground 画在内容之上但不占布局，也不会产生"顶部大白边"。
        runCatching {
            scroll.foreground = ProgressiveFadeDrawable(cBg, fromTop = true, fadePx = 28f * dp)
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // ★ vc233：左右 16dp → 10dp。卡片两侧原本各空出 47px，同志嫌"卡片太细"，
            //   把这块留白还一部分给卡片本身（第 4 栏不动 —— 那栏没这个反馈）。
            setPadding((10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt(), (24 * dp).toInt())
        }
        scroll.addView(list)
        // ★ vc264m：下拉松手刷新（SwipeRefreshLayout 包住 ScrollView）。
        //   触发调 fetchInTransit(force=true)——与顶栏刷新按钮同一入口（用户明确意图，穿透 90 秒闸）
        val swipe = androidx.swiperefreshlayout.widget.SwipeRefreshLayout(this).apply {
            setColorSchemeColors(cAccent)   // 转圈弧色 = 项目主色（靛紫）
            setProgressBackgroundColorSchemeColor(if (isDark()) Color.parseColor("#1C1D1F") else Color.WHITE)
            setDistanceToTriggerSync((88 * dp).toInt())    // 拉多少距离触发（手感）
            setSlingshotDistance((140 * dp).toInt())       // 阻尼拉伸距离（拖拽更跟手）
            setOnRefreshListener {
                fetchInTransit(force = true)
                // 兜底：60 秒还没停就强制收起（防异常路径卡转圈）
                postDelayed({ if (isRefreshing) isRefreshing = false }, 60_000L)
            }
            addView(scroll)
        }
        layer.addView(swipe, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        root.addView(layer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        transitLayer = layer
        layerIn(layer)
        transitList = list
        transitSwipe = swipe
        transitStatus = status
        // ★ vc697：**按平台分开加载** —— 进页只定标签、不抓全部平台。
        //   首屏只抓当前标签这一路；其它平台等切到它时再抓（切回已抓过的不重复发请求）。
        transitRoundLoaded.clear()
        runCatching {
            val avail0 = availableTransitSources()
            transitSrc = preferTransitSrc(avail0)
            refreshTransitTabs()
            attachTransitTabSwipe(scroll)
        }.onFailure { SafeRun.w("TransitTab", it) }
        // ★ vc234：和取件码栏同一套 —— 进页先把上次的在途件画出来，抓取在后台跑。
        //   拼多多在途要走一遍 WebView，常常 20~30 秒，那段时间页面不该是空的。
        //   ★ 顺序有讲究：**先** fetchInTransit()（它会写"正在获取…"），**再**用缓存画
        //     （把文案改成"刷新中 · 上次 xx:xx"）—— 反过来的话，缓存文案会被抓取那份盖掉。
        // ★ vc692：先把落盘的卡**按源灌回内存字段**。
        //   原来内存字段只在"本轮抓取回来"时才填 —— 中途退出会让这一轮白跑，
        //   下次进页既没有内存数据、缓存又没更新过 ⇒ 必须重新等 20 多秒（用户反馈的"没有缓存"）。
        hydrateTransitSources()
        val cached = loadTransitCache()
        // ★ vc255：轨迹缓存也一起读回来。pddTransitTraceCache 原来只是进程内 Map，
        //   App 一杀就丢（ColorOS 上滑划掉 = 全面清场）⇒ 每次冷启动后进第 5 栏都必然
        //   重开 3 个物流页。落盘后冷启动可直接命中，一个页面都不开。
        val traceHit = loadTransitTraceCache()
        if (traceHit.isNotEmpty()) pddTransitTraceCache.putAll(traceHit)
        Log.i("TransitCache", "进页：缓存条数=" + cached.size + " 轨迹缓存=" + traceHit.size)
        if (autoRefreshOnOpen()) {
            fetchInTransit()
        } else {
            Log.i("AutoRefresh", "途中：自动刷新关闭，只显示缓存")
            setStatusText(
                transitStatus,
                if (cached.isNotEmpty()) "自动刷新已关闭 · 下拉可刷新" else "自动刷新已关闭 · 下拉刷新"
            )
        }
        if (cached.isNotEmpty()) renderInTransit(cached)
    }

    private fun closeTransit() {
        // ★ vc692：退出时立刻把这一轮已经拿到的卡（含缓存回灌的部分）落盘。
        //   原来只在"全部源都回来 + 页面还开着"时才写 ⇒ 中途退出 = 这一轮白跑。
        if (transitLayer != null) {
            try { saveTransitCache() } catch (t: Throwable) { SafeRun.w("TransitCache", t) }
        }
        transitTimeout?.let { mainHandler.removeCallbacks(it) }
        transitTimeout = null
        transitLayer?.let { root.removeView(it) }
        transitLayer = null
        transitList = null
        // ★ vc708：离页复位 —— 下次进"在途快递"仍播一次入场动画
        transitPainted = false
        expandedTransitKey = null
        transitSwipe = null
        transitStatus = null
        // ★ vc697：标签条随层一起消失（transitSrc 留着 —— 下次进来还停在上次看的平台）
        transitTabRow = null
        transitTabsScroll = null
        transitRoundLoaded.clear()
    }

    /** 途中栏抓取：复用拼多多/京东两条通道，回调里对原始响应做宽口径解析（非终态订单） */
    private fun fetchInTransit(force: Boolean = false) {
        // ★ vc392：同上，在途栏也按账号范围清
        enforceAccountScopes()
        transitTimeout?.let { mainHandler.removeCallbacks(it) }
        // ★ vc255：进页间隔闸。只有顶栏刷新按钮（force=true）算用户的明确意图可穿透；
        //   普通进出第 5 栏在 90 秒内只用缓存画 + 状态行如实说明还剩多少秒。
        //   ⚠ 闸门命中时**任何请求都不发**（含手动单号/快递100）—— 既省接口也省快递100 额度。
        // ★ vc342：手动刷新也算"刚更新过" —— 旧代码 force 既不检查闸门、也不写时间戳，
        //   于是"刚下拉完再进页面"和"刚进页面再下拉"两种顺序的行为不一致。
        if (force) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong(KEY_LAST_ORDER_LIST, System.currentTimeMillis()).apply()
        } else {
            val last = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getLong(KEY_LAST_ORDER_LIST, 0L)
            val gap = System.currentTimeMillis() - last
            if (last != 0L && gap < ORDER_LIST_MIN_INTERVAL_MS) {
                val leftSec = (ORDER_LIST_MIN_INTERVAL_MS - gap) / 1000
                Log.i("TransitGate", "进页间隔闸命中（${gap / 1000}s < " +
                        "${ORDER_LIST_MIN_INTERVAL_MS / 1000}s），平台源本轮不发请求")
                transitGot = 0
                transitExpect = 0
                transitPendings.clear()
                // ★ vc270c：各源字段**不再清空** —— 按源缓存口径，失败/中断保留上一趟的卡
                transitList?.removeAllViews()
                val cached = loadTransitCache()
                if (cached.isNotEmpty()) {
                    renderInTransit(cached)
                } else {
                    showStatusText(transitStatus)
                    setStatusText(transitStatus, "刚刚更新过，${leftSec} 秒后可再刷新")
                }
                // ★ vc342：闸门只限"平台源" —— 手动单号走快递100，与平台额度无关，照查
                if (manualWaybills(this).isNotEmpty()) fetchManualChannel()
                return
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong(KEY_LAST_ORDER_LIST, System.currentTimeMillis()).apply()
        }
        transitGot = 0
        // vc274：新轮次代数 +1 —— 旧轮的闸门兜底计时器（onTransitGate）全部作废
        transitRoundGen++
        // ★ vc270c：各源字段**不再清空**（按源缓存口径）——刷新期间旧卡保留，
        //   源成功才替换它那份；失败/中断自然保留（对齐取件码页"失败绝不动缓存"）
        // ★ vc697：**按平台分开加载** —— 本轮只抓当前标签这一个源。
        //   起因（2026-11-09 真机日志实测）：四个源并行时，整栏「更新于」要等**最慢**的那个 ——
        //   拼多多原生接口 0.585s、淘宝网页旁听 2.9s，而京东那一路要 16.6s（这台机器每次都是
        //   0 张卡），于是进页后状态行一直挂着「正在获取京东物流」十几秒 —— 用户说的"加载慢"。
        //   分开之后：进哪个平台就抓哪个平台，互不拖累。
        val tabSrc = if (transitSrc.isEmpty()) "pdd" else transitSrc
        if (tabSrc.isNotEmpty()) transitRoundLoaded.add(tabSrc)
        val wantPdd = PddChannel.isLoggedIn(this) && tabSrc == "pdd"
        val wantJd = JdChannel.isLoggedIn(this) && tabSrc == "jd"
        val wantMi = miEnabled() && MiChannel.isLoggedIn(this) && tabSrc == "mi"
        // ★ vc270：淘宝在途源 —— 淘宝登录态（jar cookie）即可，走网页层旁听链路
        val wantTb = TbChannel.isLoggedIn() && tabSrc == "tb"
        // ★ 修真 bug：原来 `(if (wantMi) 1 else 0)` 写了**两次**（末尾本该是 wantSf/wantEms 的位），
        //   期望数因此永远比实际回调数多 1 —— 而 renderInTransit 开头是
        //   `if (transitGot < transitExpect) return` ⇒ 小米开着的时候**一张卡都渲染不出来**，
        //   只能等 50 秒超时报"超时"。
        // ★ vc697：手动单号现在是**独立标签** —— 轮到它时占一个期待位（否则状态行会永远
        //   停在"正在获取手动添加的单号…"，不会结算成"更新于 HH:mm"）。
        val hasManual = manualWaybills(this).isNotEmpty() && tabSrc == "manual"
        transitExpect = (if (wantPdd) 1 else 0) + (if (wantJd) 1 else 0) +
            (if (wantMi) 1 else 0) + (if (wantTb) 1 else 0) +
            (if (hasManual) 1 else 0)
        // ★ 加载文案要显示"正在获取哪个平台的物流"（同志 2026-09-21 要求，跟取件码那栏一致）。
        transitPendings.clear()
        if (wantPdd) transitPendings.add("pdd")
        if (wantJd) transitPendings.add("jd")
        if (wantMi) transitPendings.add("mi")
        if (wantTb) transitPendings.add("tb")
        if (hasManual) transitPendings.add("manual")
        showStatusText(transitStatus)
        setStatusText(
            transitStatus,
            when {
                transitExpect == 0 && !hasManual -> getString(R.string.transit_no_source)
                transitExpect == 0 -> "正在获取手动添加的单号…"
                else -> transitLoadingText()
            }
        )
        transitList?.removeAllViews()
        if (transitExpect == 0 && !hasManual) {
            // ★ vc343：没有平台源就不会走 allDone，转圈在这里收
            transitSwipe?.let { if (it.isRefreshing) it.isRefreshing = false }
            return
        }
        // 只有手动单号时不占 expect ⇒ 永远等不到 allDone，转圈也要提前收
        if (transitExpect == 0) transitSwipe?.let { if (it.isRefreshing) it.isRefreshing = false }

        transitTimeout = Runnable {
            // ★ vc343：总超时同样收转圈（有源卡住时缓存不会落盘/收尾）
            transitSwipe?.let { if (it.isRefreshing) it.isRefreshing = false }
            if (transitGot < transitExpect) {
                setStatusText(transitStatus, getString(R.string.pickup_error, "超时"))
            }
        }.also { mainHandler.postDelayed(it, 50_000) }

        // ★ vc212：拼多多改走 PddNative 原生真首页（SSR 首屏旁听 0 次 XHR，老路对最新单
        //   结构性失明 —— 见 文档\在途快递_第5栏_pdd在途不显示_修复_2026-09-27.md §六）。
        //   原生接口层失败才回退 WebView 老路。
        if (wantPdd) fetchPddTransitNative()
        if (wantJd) JdChannel.fetch(this) { _, err ->
            if (transitLayer == null) return@fetch
            // ★ vc274：闸门占用 ≠ 失败 —— 与第 4 栏同款，快速重开第 5 栏时撞上会闪
            //   "获取失败：正在抓取中"。改走 onTransitGate：不显错、不计数，20s 兜底。
            if (err == PICKUP_GATE_MSG) { onTransitGate("jd"); return@fetch }
            // ★ vc270c：失败保留旧卡（按源缓存口径）
            if (err.isEmpty()) {
                jdTransit = JdChannel.parseInTransit(JdChannel.lastCaptures)
            }
            transitPendings.remove("jd")
            transitGot++
            if (err.isNotEmpty()) setStatusText(transitStatus, getString(R.string.pickup_error, errShort(err)))
            renderInTransit()
        }
        if (wantMi) fetchMiChannel()
        // ★ vc270：淘宝在途（网页层旁听链路，串行且最慢 —— 放最后发起）
        if (wantTb) fetchTaobaoTransit()
        // 手动单号：不占 expect（增强性质），有就并行查
        // ★ vc259：expect==0 时也要查（上面早退条件已带 !hasManual），见 fetchInTransit 开头
        if (hasManual) fetchManualChannel()

        // ★ vc342（P0-5）：给**每个源**挂单源硬超时 —— 以前只有整栏 50s 总超时，
        //   一个源卡死就拖着整栏"正在获取"；现在 20s 到点按完成收尾，旧卡不受影响。
        for (s in ArrayList(transitPendings)) scheduleTransitSettle(s)
    }

    /** ★ vc342：单源硬超时结算（第 5 栏）—— 只对"还在等"的源记账，不重复计数 */
    private fun scheduleTransitSettle(src: String) {
        val gen = transitRoundGen
        mainHandler.postDelayed({
            if (gen != transitRoundGen) return@postDelayed          // 已开新一轮，旧轮作废
            // ★ vc643：这里是"单源硬超时"的最后防线，不再因为当前空栏而放弃结算。
            //   正常空结果会先走源回调并移除 pending；20s 仍没有回调才说明该源卡死，
            //   必须只结算这一源，不能让整栏继续等到 50s。
            if (transitPendings.remove(src)) {
                transitGot++
                Log.w("TransitTimeout", "$src 单源超时（${PER_SOURCE_TIMEOUT_MS / 1000}s）—— 按完成收尾，旧卡保留")
                if (transitLayer != null) renderInTransit()
            }
        }, PER_SOURCE_TIMEOUT_MS)
    }

    /**
     * ★★ vc212：途中栏拼多多走原生真首页（PddNative.inTransitScan）。
     *
     * 结果分流：
     *  · 接口层成功（ok=true）⇒ 用 native 结果组装卡片；再按 track/status 交叉过滤
     *    （"在驿站待取/已取走"的件第 4 栏已有卡，别在第 5 栏重复 —— 与 parseInTransit 同口径）；
     *  · needLogin（40001）⇒ markLoginDead（与 TrackFetcher 后台同款，UI 不再谎报"已登录"），
     *    状态栏如实报"登录已过期"，**不开** WebView 空跑；
     *  · 接口层失败（网络/HTTP 错）⇒ 回退 vc211 老路（WebView 旁听 + parseInTransit），
     *    保证"native 修不好时也不比原来差"。
     */
    private fun fetchPddTransitNative() {
        Thread {
            val rep: PddNative.TransitReport? = try {
                val ck = PddChannel.cookies(this)
                if (ck.isEmpty()) null
                else {
                    val uid = PddChannel.uid(this).ifEmpty { PddChannel.uidFromCookie(ck) }
                    val token = Regex("PDDAccessToken=([^;]+)")
                        .find(ck)?.groupValues?.get(1)?.trim().orEmpty()
                    val ses = PddNative.Session(token = token, uid = uid, cookie = ck)
                    if (!ses.usable) null
                    else PddNative.inTransitScan(ses, maxPages = 2)
                }
            } catch (_: Throwable) {
                null
            }
            // ★ vc345：到站交叉确认 —— 拼多多的订单状态常年停在「待收货」，
            //   一个件其实已到驿站、有取件码，却仍被算成"途中"（同志 2026-10-20 反馈：
            //   PO-261017-… 明明该进取件码栏，却只在在途快递里）。
            //   `express/cabinet/{order_sn}` 是权威判据：有码 ⇒ 已到站。
            //   每单 10 分钟限频、最多查 8 单、3 线程并行 —— 不给接口添负担。
            val arrived = HashMap<String, Pair<String, String>>()
            if (rep != null && rep.ok) {
                try {
                    val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
                    val nowChk = System.currentTimeMillis()
                    val todo = (rep.items + rep.ambiguous).filter {
                        it.orderSn.isNotEmpty() &&
                                nowChk - sp.getLong("arrchk_${it.orderSn}", 0L) > 10L * 60_000L
                    }.take(8)
                    if (todo.isNotEmpty()) {
                        sp.edit().apply {
                            for (t in todo) putLong("arrchk_${t.orderSn}", nowChk)
                        }.apply()
                        val idx = java.util.concurrent.atomic.AtomicInteger(0)
                        val workers = minOf(3, todo.size)
                        val ts = ArrayList<Thread>()
                        for (w in 0 until workers) ts.add(Thread {
                            while (true) {
                                val i = idx.getAndIncrement()
                                if (i >= todo.size) return@Thread
                                val sn = todo[i].orderSn
                                val cp = try {
                                    PddNative.cabinetInfo(this@MainActivity, sn)
                                } catch (_: Throwable) {
                                    "" to ""
                                }
                                if (cp.first.isNotEmpty() || cp.second.isNotEmpty()) {
                                    synchronized(arrived) { arrived[sn] = cp }
                                }
                            }
                        })
                        ts.forEach { it.start() }
                        ts.forEach { try { it.join() } catch (t: Throwable) { SafeRun.w("MainActivity", t) } }
                        Log.i(
                            "PddNative",
                            "到站交叉确认：查 ${todo.size} 单 → 命中 ${arrived.size}（有码即已到站）"
                        )
                    }
                } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
            }
            // ★ vc352：第一层判定 —— 拼多多自己的枚举（只信已证实的"已取走"两值）
            val enumMap = try {
                val sns = (rep?.items.orEmpty() + rep?.ambiguous.orEmpty()).map { it.orderSn }
                if (sns.isEmpty()) emptyMap<String, String>()
                else PddNative.trackStatusesForCtx(this@MainActivity, sns)
            } catch (_: Throwable) {
                emptyMap()
            }
            // ★★ vc627：**枚举必须让渲染层看得见** —— 否则 PickupVerdict 查不到枚举，
            //   会把"运输中出现驿站字样"的件判成已到站（今天数量错的根因）。
            if (enumMap.isNotEmpty()) {
                PddChannel.mergeTrackStatus(enumMap)
                Log.i("PddEnum", "枚举已并入全局表：${enumMap.size} 条")
                // ★★ vc631：**枚举一到就重判一次**。
                //   实测存在 1.1 秒的竞态：渲染先跑（全局表 size=0）⇒ 全部退回文案判定 ⇒
                //   "运输中"的件被"驿站"字样判成已到站（在途凭空少 2 条）。
                //   并入之后必须重画，否则这一轮永远按错误结论上屏。
                mainHandler.post {
                    try { if (transitLayer != null) renderInTransit() } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
                    try { if (pickupLayer != null) renderMerged() } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
                }
            }
            mainHandler.post {
                if (transitLayer == null) return@post
                when {
                    rep == null || (!rep.ok && !rep.needLogin) -> {
                        // 原生路走不通：回退 WebView 老路（行为与 vc211 完全一致）
                        PddChannel.fetch(this) { _, err ->
                            if (transitLayer == null) return@fetch
                            // ★ vc274：闸门占用 ≠ 失败（与第 4 栏同款）——
                            //   不显错、不计数，20s 兜底按完成收尾。
                            if (err == PICKUP_GATE_MSG) { onTransitGate("pdd"); return@fetch }
                            // ★ vc257：这条回退路同样会旁听到 track 状态，一并落盘
                            PddChannel.persistTrackStatus(this)
                            // ★ vc270c：失败保留旧卡（按源缓存口径）
                            if (err.isEmpty()) {
                                pddTransit = PddChannel.parseInTransit(PddChannel.lastCaptures)
                            }
                            transitPendings.remove("pdd")
                            transitGot++
                            if (err.isNotEmpty()) {
                                // ★ vc558：已经有卡片时**不覆盖**状态行（别让一个源的登录问题把整页写成报错）
                        if ((transitList?.childCount ?: 0) == 0) {
                            setStatusText(transitStatus, getString(R.string.pickup_error, errShort(err)))
                        } else {
                            Log.i("MiShopChannel", "小米失败但有其它卡片，状态行保持不动：${errShort(err)}")
                        }
                            }
                            renderInTransit()
                            // ★ vc217：回退路组出的卡同样要补轨迹——vc214~216 只挂在 native
                            //   成功分支，native 一抖（cookie/网络波动）第 5 栏就永远没轨迹。
                            if (pddTransit.isNotEmpty()) enhancePddTransitTraces()
                        }
                    }
                    rep.needLogin -> {
                        PddChannel.markLoginDead(this)
                        transitPendings.remove("pdd")
                        transitGot++
                        setStatusText(
                            transitStatus,
                            getString(R.string.pickup_error, "拼多多登录已过期")
                        )
                        renderInTransit()
                    }
                    else -> {
                        // ★ vc261：扫描入选后还有两道交叉过滤（track 状态 / 第4栏取件码卡），
                        //   它们同样会静默丢单 ⇒ 逐条记原因。10-14 同志报"在途时第5栏也不显示"，
                        //   当时无法还原是哪道拦的，今后日志直接给答案。
                        val kept = ArrayList<JSONObject>()
                        var promoted = false
                        for (t in rep.items) {
                            // ★ vc352：枚举说已取走 ⇒ 不进途中（已证实语义）
                            val en = enumMap[t.orderSn]
                            // ★ vc608：OUT_CABINET / SIGN **不一定是已取走** ——
                            //   文案含「待确认 / 待收货 / 待取」时，件其实还在驿站（vc347 真机实证）。
                            val pendingConfirm = t.status.contains("待确认") || t.status.contains("待收货") ||
                                    t.status.contains("待取") ||
                                    t.latest.contains("待确认") || t.latest.contains("待收货")
                            val takenForSure = en == "GOT" ||
                                    ((en == "OUT_CABINET" || en == "SIGN") && !pendingConfirm)
                            if (takenForSure) {
                                // ★ vc357：开关开着时给取件码卡打灰标（卡还在，途中不再显示）
                                if (autoPickedTagOn()) {
                                    val card = pddPkgs.firstOrNull {
                                        it.optString("orderSn") == t.orderSn ||
                                                it.optString("waybillNo") == t.orderSn
                                    }
                                    if (card != null && card.optLong("pickedAt", 0L) == 0L) {
                                        val now = System.currentTimeMillis()
                                        card.put("pickedAt", now)
                                        val c = PddCodeStore.get(this, "pdd:" + t.orderSn)
                                        if (c != null && c.pickedAt == 0L) {
                                            PddCodeStore.put(
                                                this, "pdd:" + t.orderSn, c.copy(pickedAt = now)
                                            )
                                        }
                                        Log.i("PddEnum", "自动打已取标签：${t.orderSn}（$en）")
                                    }
                                }
                                Log.i("PddEnum", "途中剔除 ${t.orderSn}：$en（已取走）")
                                continue
                            }
                            if ((en == "OUT_CABINET" || en == "SIGN") && pendingConfirm) {
                                Log.i("PddEnum", "途中保留 ${t.orderSn}：枚举 $en 但文案「${t.status}」= 待确认，不按已取走剔除")
                            }
                            // ★ vc354：枚举说 DEPARTURE（已实证 2/2=在途）⇒ 跳过后续文案过滤，
                            //   避免"订单状态写已签收、实际还在路上"的单被误杀
                            val enumTransit = en == "DEPARTURE"
                            if (enumTransit) {
                                Log.i("PddEnum", "途中保留 ${t.orderSn}：枚举 DEPARTURE（在途，跳过文案过滤）")
                            }
                            // ★ vc345：cabinet 接口确认这个件**已到站有码** ⇒ 转进取件码栏，
                            //   不再当"在途"（同志 10-20 报：PO-261017-… 该进取件码却只在途中）。
                            val hit = arrived[t.orderSn]
                            if (hit != null && hit.first.isNotEmpty()) {
                                promotePddTransitToPickup(t, hit.first, hit.second)
                                promoted = true
                                continue
                            }
                            // ★ vc356：已到站但码没给（典型：菜鸟驿站，码在菜鸟手里）
                            // ★★ v1.4.10：**但到了这一步不能搬走** —— 留在在途栏，等码补到再搬。
                            //   事故（同志 2026-11-10 12:20「皇帝柑还没到，但在途快递看不到」）：
                            //   皇帝柑（261105-380203210271958 / 极兔 JT5544361757046）枚举 SIGN、
                            //   cabinet 没给码、只给驿站名 ⇒ 走这里被 promote 到「取件码」栏当无码卡。
                            //   而「取件码」栏的价值就是**给出码**，一张无码卡摆在那儿用户根本不会看，
                            //   于是两栏观感都是"没有这件"。真机日志：pdd 入参 7 张 → 下一次回调变 6 张。
                            //   口径：**有码才进取件栏**；无码一律留在在途栏（后台照常补码，
                            //   补到码后这条链下一轮自然把它搬过去）。
                            if (hit != null && hit.second.isNotEmpty()) {
                                Log.i(
                                    "PddEnum",
                                    "到站但 cabinet 没给码，留在在途栏：${t.orderSn} @${hit.second}"
                                )
                            }
                            // ★ vc356：枚举明确"已入柜/驿站" ⇒ 按到站处理（即使 cabinet 没给地址）
                            // ★★ v1.4.10：同上 —— 入柜但无码也留在在途栏，不再搬成无码卡。
                            if (en == "IN_CABINET") {
                                Log.i("PddEnum", "入柜但无码，留在在途栏：${t.orderSn}")
                            }
                            if (!enumTransit && PddChannel.trackSaysNotInTransit(t.orderSn)) {
                                Log.i("PddNative", "native 在途丢弃 ${t.orderSn}：track/status 判定不在途中")
                                continue
                            }
                            // ★ vc218：已到驿站的单（凉席类）status 恒「待收货」、shipping_message 又
                            //   没有驿站/取件码文本 ⇒ 文本判据全漏。旧做法是"第4栏有缓存就丢"。
                            // ★★ v1.4.10：**这条丢弃规则撤掉，只留日志**。
                            //   事故（同志 2026-11-10 12:20「皇帝柑还没到，但在途快递看不到」）：
                            //   皇帝柑 cabinet 明确回 `code=无 place=无`，可缓存里躺着一枚**借来的码**
                            //   （同驿站另一件的 73-3-5888，被轨迹文本抠串了）⇒ 旧规则据此认定
                            //   "第4栏已有取件码卡"把它丢掉；而取件栏那张卡的码正被"摘掉借来的码"清掉，
                            //   于是**两栏都没有这件**。真机日志：
                            //   `cabinet(...)→ code=无` / `native 在途丢弃 ...：第4栏已有取件码卡`。
                            //   口径：**平台说待收货就留在在途栏**。重复显示顶多烦，丢件是事故。
                            run {
                                val cached = PddCodeStore.get(this, "pdd:" + t.orderSn)
                                if (!enumTransit && cached?.code?.isNotEmpty() == true) {
                                    Log.i(
                                        "PddNative",
                                        "第4栏有码（${cached.code}），在途栏仍保留：${t.orderSn}"
                                    )
                                }
                            }
                            kept.add(JSONObject().apply {
                                put("src", "pdd")
                                put("goodsName", t.goods)
                                put("statusText", t.status)
                                put("latest", t.latest)
                                put("trackLink", "")
                                put("waybillNo", t.orderSn)
                                // ★★ v1.4.5：**把订单号单独留一份**。
                                //   以前只在 waybillNo 里塞订单号当占位：补完轨迹 waybillNo 被改成真运单号后，
                                //   这张卡就再没有稳定锚点（pickKey/parcelIds 的 pdd 分支读 orderSn 恒空），
                                //   主键一漂，去重就可能把别件认成同一件。orderSn 写死在这里，永不改写。
                                put("orderSn", t.orderSn)
                                put("phone", t.phone)
                                // ★ vc252：订单列表接口自带的真运单号 + 物流页直链
                                //   （有它们就不用开订单详情页去"点进去抠"了，见 enhancePddTransitTraces）
                                put("realWaybill", t.waybill)
                                put("expressUrl", t.expressUrl)
                                put("etaHint", t.etaHint)   // ★ vc696：订单列表自带的预计送达
                            })
                        }
                        Log.i("PddEnum", "本轮 kept=${kept.size}：" + kept.joinToString(",") { it.optString("waybillNo") })
        // ★ vc349：歧义单裁决 —— cabinet 有码 → 取件码栏；无码 → 保持不显示
                        //   （订单状态写"已签收"、物流文案为空，分不清真假签收，只能问接口）
                        for (t in rep.ambiguous) {
                            // ★ vc352：枚举优先 —— 已取走的歧义单直接丢弃
                            val en2 = enumMap[t.orderSn]
                            if (en2 == "OUT_CABINET" || en2 == "GOT") {
                                Log.i("PddEnum", "歧义单 ${t.orderSn}：枚举 $en2（已取走）→ 丢弃")
                                continue
                            }
                            if (en2 == "DEPARTURE") {
                                Log.i("PddEnum", "歧义单 ${t.orderSn}：枚举 DEPARTURE → 留途中")
                                kept.add(JSONObject().apply {
                                    put("src", "pdd")
                                    put("goodsName", t.goods)
                                    put("statusText", t.status)
                                    put("latest", t.latest)
                                    put("trackLink", "")
                                    put("waybillNo", t.orderSn)
                                    put("orderSn", t.orderSn)   // ★★ v1.4.5：稳定锚点，永不改写
                                    put("phone", t.phone)
                                    put("realWaybill", t.waybill)
                                    put("expressUrl", t.expressUrl)
                                    put("etaHint", t.etaHint)   // ★ vc696
                                })
                                continue
                            }
                            // ★ vc356：枚举/地址说已到站 ⇒ 出占位卡（菜鸟驿站常常没码）
                            if (en2 == "IN_CABINET" || arrived[t.orderSn]?.second?.isNotEmpty() == true) {
                                promotePddTransitToPickupNoCode(
                                    t, arrived[t.orderSn]?.second.orEmpty()
                                )
                                promoted = true
                                continue
                            }
                            val hit = arrived[t.orderSn]
                            if (hit != null && hit.first.isNotEmpty()) {
                                promotePddTransitToPickup(t, hit.first, hit.second)
                                promoted = true
                            } else {
                                Log.i(
                                    "PddNative",
                                    "歧义单 ${t.orderSn}：cabinet 无码 → 不进途中（按已签收处理）"
                                )
                            }
                        }
                        pddTransit = kept
                        transitPendings.remove("pdd")
                        transitGot++
                        renderInTransit()
                        // ★ vc345：有件被搬到取件码栏 ⇒ 第 4 栏也重画（层没开时会自己 return，
                        //   但码已落 PddCodeStore，下次打开就有）
                        if (promoted && pickupLayer != null) renderMerged()
                        // ★ vc214：首屏照旧，随后异步补真实运单号+物流轨迹（同志 02:14 指令）
                        if (pddTransit.isNotEmpty()) enhancePddTransitTraces()
                    }
                }
            }
        }.start()
    }

    /**
     * ★★ vc214：第 5 栏拼多多在途卡补物流轨迹（同志 02:14 指令：向快递100 要物流补进第 5 栏）。
     *
     * 为什么不能拿卡上"单号"直接查快递100：那是**订单号**（PDD 订单列表不带运单号，
     * tracking_number 恒空），快递100 只认**运单号**。运单号只藏在订单详情的物流页（DOM）里。
     * ⇒ 复用第 4 栏同款链路 [PddTraceChannel.fetch]（开物流页、从页 URL 抠真实运单号），
     *   再用运单号走快递100 [Kd100Channel.queryTraceAuto]（与手动单号同款；前缀优先认公司、只查一家不盲试，vc260）。
     *
     * 成本控制：每单开一次详情页（10~20s）⇒ 每轮最多 3 单、**串行**（PddTraceChannel 本身
     * 单例串行，占线时回调 err）、15 分钟内存缓存。**纯增强不占 transitExpect**：
     * 首屏照常显示订单状态，轨迹回来静默刷对应卡；一单都没有成功就什么都不发生（保持原样）。
     * 代际令牌 [transitEnhanceGen]：刷新/关层 ⇒ 旧回调全部作废，防串台。
     */
    private fun enhancePddTransitTraces() {
        // ★ vc342：上一批没跑完 ⇒ 不再"整批丢掉"，记一笔，等它跑完自动补一轮
        val nowMs = System.currentTimeMillis()
        if (transitEnhanceBusyStart != 0L && nowMs - transitEnhanceBusyStart < 120_000L) {
            transitEnhanceAgain = true
            Log.i("TransitEnhance", "上一批次仍在进行(${(nowMs - transitEnhanceBusyStart) / 1000}s)，本轮排队，批次结束后自动补跑")
            return
        }
        transitEnhanceBusyStart = nowMs
        val gen = ++transitEnhanceGen
        // ★ vc507：原来只补前 3 单（`pddTransit.take(3)`）—— 在途件一多，后面几单永远补不上轨迹。
        //   现在全部处理，只留防失控上限 12；超限时明确打日志，不再静默丢。
        val allWb = pddTransit.mapNotNull { it.optString("waybillNo").ifEmpty { null } }.distinct()
        val capWb = 12
        if (allWb.size > capWb) {
            Log.w("TransitEnhance", "在途 ${allWb.size} 单，超出单轮上限 $capWb，本轮先补前 $capWb 单")
        }
        val targets = if (allWb.size > capWb) allWb.take(capWb) else allWb
        if (targets.isEmpty()) { transitEnhanceBusyStart = 0L; return }
        var enhanced = 0
        val batchStart = System.currentTimeMillis()
        fun next(idx: Int) {
            if (gen != transitEnhanceGen || transitLayer == null) {
                // ★ vc342：作废路径必须**清零忙碌标记** —— 旧代码不清零，随后 2 分钟内的
                //   所有批次都被当成"上一批还在跑"直接丢掉（在途轨迹补不上的元凶之一）。
                transitEnhanceBusyStart = 0L
                return
            }
            if (idx >= targets.size) {
                transitEnhanceBusyStart = 0L
                if (enhanced > 0) {
                    appToast(this, "已补充 $enhanced 单物流轨迹")
                }
                if (transitEnhanceAgain) {
                    transitEnhanceAgain = false
                    mainHandler.postDelayed({ enhancePddTransitTraces() }, 500)
                }
                return
            }
            val sn = targets[idx]
            // ★ vc704：**每批限时 20 秒**。一张物流页 3~5s + 1.2s 间隔，原来 10 单要一口气
            //   跑 30~60 秒（冷缓存），期间整栏一直在"正在获取"；现在到点就收尾，
            //   剩下的排到下一批（transitEnhanceAgain 已有），用户先看到已补好的那几张。
            if (batchStart != 0L && System.currentTimeMillis() - batchStart > 20_000L) {
                Log.i("TransitEnhance", "本批已跑 20s，剩余 ${targets.size - idx} 单排到下一批")
                transitEnhanceBusyStart = 0L
                transitEnhanceAgain = true
                mainHandler.postDelayed({ enhancePddTransitTraces() }, 8_000L)
                return
            }
        val cached = pddTransitTraceCache[sn]
        // ★ vc255：15 分钟 → 6 小时。物流节点更新本来就不快，6h 与列表缓存口径一致；
        //   且落盘后这就是"冷启动要不要重开物流页"的唯一判据，放宽才省得到页面。
        if (cached != null && System.currentTimeMillis() - cached.at < TRANSIT_CACHE_KEEP_MS) {
            if (applyTransitTrace(gen, sn, cached.waybillNo, cached.latest, cached.lines)) enhanced++
            next(idx + 1)
            return
        }
        val pkg0 = pddTransit.firstOrNull { it.optString("waybillNo") == sn }
        // 收件人手机号（订单数据挖的，可信；快递100 校验字段选填）
        val phone = pkg0?.optString("phone").orEmpty()
        // ★ vc252：订单列表接口**自带**运单号与物流页直链（见 PddNative.waybillOf 的抓包实证）
        val wbKnown = pkg0?.optString("realWaybill").orEmpty()
        val expressUrl = pkg0?.optString("expressUrl").orEmpty()
        Log.i("TransitEnhance", "$sn 已知运单号=${wbKnown.ifEmpty { "(无)" }} 直链=" +
                (if (expressUrl.isEmpty()) "(无)" else "有"))

        /** 结果落缓存 + 写回卡片 + 推进下一单 */
        fun commit(waybill: String, lines: List<String>) {
            val latest = lines.firstOrNull().orEmpty()
            pddTransitTraceCache[sn] = TransitTrace(
                waybillNo = waybill, latest = latest, at = System.currentTimeMillis(),
                lines = lines.joinToString("\n")
            )
            saveTransitTraces()
            mainHandler.post {
                if (applyTransitTrace(gen, sn, waybill, latest,
                        lines.joinToString("\n"))) enhanced++
                // ★ vc255：两单物流页之间留 1.2s，别连开（降风控，也让人看得过来）
                mainHandler.postDelayed({ next(idx + 1) }, TRACE_PAGE_GAP_MS)
            }
        }

        /** ★ vc353：直链、快递100 都失败 ⇒ 最后再走一次订单列表页点「查看物流」。
         *  新版订单卡上印的是「待收货」而不是「运输中」—— 旧锚点词匹配不到，必然 no-card。 */
        fun tryDomFallback(sn: String, idx: Int) {
            PddTraceChannel.fetch(
                this, sn,
                anchorWords = listOf("待收货", "运输中", "派送", "已发货")
            ) { r ->
                if (gen != transitEnhanceGen || transitLayer == null) return@fetch
                val lines = PddTraceChannel.parseTraceLines(r.text)
                if (r.err == null && r.waybillNo.isNotEmpty() && lines.isNotEmpty()) {
                    Log.i("TransitEnhance", "$sn DOM兜底 ${r.waybillNo} ${lines.size} 段轨迹")
                    commit(r.waybillNo, lines)
                } else {
                    Log.w(
                        "TransitEnhance",
                        "$sn DOM兜底也没拿到（err=${r.err} wb=${r.waybillNo} lines=${lines.size}）"
                    )
                    next(idx + 1)
                }
            }
        }

        /** 快递100 补全程（有真运单号就能直接查；[pddLines] 是 PDD 页文本兜底） */
        fun goKd100(waybill: String, pddLines: List<String>) {
            val pddLatest = pddLines.firstOrNull().orEmpty()
            pddIo.execute {
                var latest = pddLatest
                var lines = pddLines
                try {
                    val tr = Kd100Channel.queryTraceAuto(this@MainActivity, phone, waybill).trace
                    val kdLast = tr?.points?.lastOrNull()
                    if (!kdLast.isNullOrEmpty()) {
                        // ★ vc216 时间硬校验：autonumber 认不出 15 位韵达号 ⇒ 兜底 12 公司逐个试
                        //   （表里有 ems/youzhengguonei）⇒ 邮政接口对「单号+手机号」返回**别人的轨迹**
                        //   （02:59 实测：韵达单被填 8 月 4 日邮政揽收轨迹，比下单日 09-26 早 53 天）。
                        //   轨迹时间早于下单日 00:00 ⇒ 物理上不可能是本单的，丢弃（保 PDD 页文本兜底）。
                        val orderDay0 = PddNative.snToDateMs(sn)?.let { it - 12 * 3600_000L }
                        val traceMs = traceTimeMs(kdLast)
                        if (orderDay0 != null && traceMs != null && traceMs < orderDay0) {
                            Log.w("TransitEnhance",
                                "$sn 快递100轨迹早于下单日(${kdLast.take(10)})，疑似别人的脏数据，丢弃")
                        } else {
                            latest = kdLast
                            // ★ vc219：快递100 有数据 ⇒ 全部节点替换 PDD 页行（它的轨迹更全）
                            val pts = tr?.points
                            if (!pts.isNullOrEmpty()) lines = pts
                        }
                    }
                } catch (e: Throwable) {
                    Log.w("TransitEnhance", "$sn 快递100 异常: ${e.message}（用 PDD 物流页文本兜底）")
                }
                if (latest.isNotEmpty()) {
                    Log.i("TransitEnhance", "$sn 全程轨迹 ${lines.size} 条（来源=" +
                            (if (lines !== pddLines) "快递100" else "PDD页") + "）")
                    pddTransitTraceCache[sn] = TransitTrace(
                        waybillNo = waybill, latest = latest, at = System.currentTimeMillis(),
                        lines = lines.joinToString("\n")
                    )
                    saveTransitTraces()
                    mainHandler.post {
                        if (applyTransitTrace(gen, sn, waybill, latest,
                                lines.joinToString("\n"))) enhanced++
                        mainHandler.postDelayed({ next(idx + 1) }, TRACE_PAGE_GAP_MS)
                    }
                } else {
                    Log.w("TransitEnhance", "$sn 两路都没抠到轨迹文本，跳过")
                    mainHandler.post { tryDomFallback(sn, idx) }
                }
            }
        }

        when {
            // ① 有「查看物流」直链 ⇒ **直接开物流页**（不借道订单列表页：更快，且不可能点错单）
            expressUrl.isNotEmpty() -> PddTraceChannel.fetchByUrl(this, expressUrl, wbKnown) { r ->
                if (gen != transitEnhanceGen || transitLayer == null) return@fetchByUrl
                val lines = PddTraceChannel.parseTraceLines(r.text)
                val wb = r.waybillNo.ifEmpty { wbKnown }
                // ★ 硬校验：物流页 URL 里的订单号必须等于目标单（防张冠李戴）
                val wrong = r.realOrderSn.isNotEmpty() && r.realOrderSn != sn
                if (!wrong && wb.isNotEmpty() && lines.isNotEmpty()) {
                    Log.i("TransitEnhance", "$sn 直链物流页 $wb ${lines.size} 段轨迹")
                    commit(wb, lines)
                    return@fetchByUrl
                }
                Log.w("TransitEnhance",
                    "$sn 直链没读到(err=${r.err} lines=${lines.size} wrong=$wrong) ⇒ 转快递100")
                if (wbKnown.isNotEmpty()) goKd100(wbKnown, lines) else tryDomFallback(sn, idx)
            }
            // ② 只有运单号（没直链）⇒ 直接问快递100，一个页面都不开
            wbKnown.isNotEmpty() -> goKd100(wbKnown, emptyList())
            // ③ 都没有 ⇒ vc214 老路：开订单列表页，按锚点词点「查看物流」
            else -> PddTraceChannel.fetch(
                this, sn,
                // ★ vc215：锚点词传「运输中/派送/已发货」—— 第 5 栏在途卡上印的是这些摘要词
                //   （默认「待取件/驿站」只服务第 4 栏，会点到别的待取件卡）
                anchorWords = listOf("待收货", "运输中", "派送", "已发货")
            ) { r ->
                if (gen != transitEnhanceGen || transitLayer == null) return@fetch
                if (r.err != null || r.waybillNo.isEmpty()) {
                    Log.w("TransitEnhance", "$sn 轨迹增强失败 err=${r.err} waybill=${r.waybillNo}")
                    next(idx + 1)
                    return@fetch
                }
                // ★ vc215 硬校验：物流页 URL 里的订单号必须等于目标单 —— 防张冠李戴
                if (r.realOrderSn != sn) {
                    Log.w("TransitEnhance", "$sn 定位到别的单(${r.realOrderSn})，丢弃防张冠李戴")
                    next(idx + 1)
                    return@fetch
                }
                goKd100(r.waybillNo, PddTraceChannel.parseTraceLines(r.text))
            }
        }
    }
        next(0)
    }

    /** 增强结果写回对应卡（realWaybill=真实运单号、latest=轨迹、lines=vc219 全程轨迹行）并重刷；主线程调用 */
    private fun applyTransitTrace(
        gen: Int, orderSn: String, waybill: String, latest: String, lines: String = ""
    ): Boolean {
        if (gen != transitEnhanceGen || transitLayer == null) return false
        // ★★ v1.4.6：**再也不把真运单号写进 `waybillNo`** —— 这是「在途 9 件塌成 1 件」的真正根因。
        //
        //   这套子系统（含 [enhancePddTransitTraces] 的 targets、[applyTransitTrace] 的匹配键、
        //   `pddTransit.firstOrNull { it.optString("waybillNo") == sn }` 的查找）**都把
        //   `waybillNo` 当成订单号在用**（建卡时 put 的就是 t.orderSn）。
        //   v1.4.5 及以前这里无条件 `pkg.put("waybillNo", waybill)` ⇒
        //     · 卡片的身份键被就地改写成"某一次"解析到的真运单号；
        //     · 真机日志实证：9 张卡 orderSn 各不相同，`waybillNo` 却被涂成同一个
        //       `79157444920500` ⇒ dedupKey 全等于 `W:79157444920500` ⇒ 整批并成 1 张；
        //     · 而 `waybillNo` 一旦不等订单号，`firstOrNull { waybillNo == sn }` 就再也找不到卡，
        //       下一轮拿到空的 phone/realWaybill，于是越补越歪。
        //   真运单号本来就该住在 `realWaybill` 里 —— 展示侧普遍是
        //   `optString("realWaybill").ifEmpty { optString("waybillNo") }`，改写后照样显示真号。
        val wb = waybill.trim()
        val okWb = validNo(wb)
        if (!okWb && wb.isNotEmpty()) {
            Log.w("TransitEnhance", "$orderSn 运单号无效(\"$wb\")，只补轨迹")
        }
        var hit = false
        for (pkg in pddTransit) {
            val match = pkg.optString("orderSn").ifEmpty { pkg.optString("waybillNo") } == orderSn
            if (match) {
                // 订单号锚点：身份只认它，永不改写
                if (!validNo(pkg.optString("orderSn"))) pkg.put("orderSn", orderSn)
                // 真运单号写进 realWaybill（已有值就不覆盖，订单列表接口给的更可信）
                if (okWb && pkg.optString("realWaybill").isEmpty()) pkg.put("realWaybill", wb)
                pkg.put("latest", latest)
                if (lines.isNotEmpty()) pkg.put("traceLines", lines)
                hit = true
            }
        }
        if (hit) renderInTransitThrottled()
        return hit
    }

    /**
     * ★ vc704：**渲染节流** —— 轨迹增强是"补一单重画一次"，10 单就是 10 次整栏重建
     *  （当天实测 845 次渲染 / 6829 条裁决日志）。补码期间人眼看不出这十几次差别，
     *  却要付整栏 View 重建的代价 ⇒ 合并到最多 1.5 秒一次；批量结束时强制补一次。
     */
    private var transitRenderAt = 0L
    private var transitRenderPending = false

    /**
     * ★ vc708：**消闪**两个状态位。
     *  · [transitPainted]：本页是否已经播过入场动画 —— 动画只在"从空到有"播一次，
     *    以前每次刷新都重播 ⇒ 一列卡片"刷"地重新淡入，这是"刷新就闪"的主因。
     *  · [expandedTransitKey]：哪张卡是展开的（身份键 `src:单号`）——
     *    以前重画时直接清掉展开态，用户展开的卡每刷一次就被合上。
     */
    private var transitPainted = false
    private var expandedTransitKey: String? = null

    /** ★ vc708：取件码页上一次渲染的内容指纹 —— 一致就整列不重画（消闪第二道闸） */
    private var pickupRenderFp = ""

    private fun renderInTransitThrottled() {
        if (transitLayer == null) return
        val now = System.currentTimeMillis()
        val gap = now - transitRenderAt
        if (gap >= 1_500L) {
            transitRenderAt = now
            renderInTransit()
        } else if (!transitRenderPending) {
            transitRenderPending = true
            mainHandler.postDelayed({
                transitRenderPending = false
                transitRenderAt = System.currentTimeMillis()
                if (transitLayer != null) renderInTransit()
            }, 1_500L - gap)
        }
    }

    /**
     * ★ vc216：轨迹点开头的时间（"yyyy-MM-dd HH:mm:ss …"）→ ms；解析不了返回 null（调用方放行）。
     * 只用于第 5 栏增强的快递100 轨迹（该格式稳定）；PDD 页文本兜底走 realOrderSn 校验，不需此函数。
     */
    private fun traceTimeMs(point: String): Long? {
        val m = Regex("(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{2}):(\\d{2}):(\\d{2})").find(point)
            ?: return null
        return try {
            val g = m.groupValues
            val cal = java.util.Calendar.getInstance()
            cal.clear()
            cal.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(),
                g[4].toInt(), g[5].toInt(), g[6].toInt())
            cal.timeInMillis
        } catch (e: Throwable) {
            null
        }
    }

    /** 在途栏加载文案：显示第一个还没回来的源（与第 4 栏的 loadingText 同款） */
    private fun transitLoadingText(): String {
        val next = transitPendings.firstOrNull() ?: return getString(R.string.transit_loading)
        return "正在获取${srcName(next)}物流"
    }

    /**
     * ★ vc558：把源错误压成**一行短文案**（去掉 URL、限制长度）。
     * 真机事故：小米未登录时，整页状态变成
     * 「获取失败：商城 需要登录（页面跳到了 https://account.xiaomi.com/...）」一屏大字报。
     */
    private fun errShort(s: String): String {
        var t = s
        t = t.replace(Regex("""[(（]?[^)）]*https?://\S+[)）]?"""), "")
        t = t.replace(Regex("""https?://\S+"""), "")
        t = t.trim().trim('（', '）', '(', ')', '，', ',', '：', ':')
        if (t.length > 26) t = t.take(26) + "…"
        return t.ifEmpty { "加载失败" }
    }

    /** 预计剩余天数：只从轨迹文本里抠"预计X日/今天/明天"，抠不到就老实显示"在途中"，不编数字 */
    /**
     * ★ vc556：卡片上只显示**物流状态文案**，不要时间前缀。
     * 各源给的 latest 格式不一：`2026-10-26 11:14 …` / `10-26 11:14 …` / `11:14 …`，
     * 这里按"行首时间"逐个剥掉，剥不到就原样返回。
     */
    private fun stripTraceTime(s: String): String {
        val t = s.trim()
        val parts = t.split(" ").filter { it.isNotEmpty() }
        if (parts.size >= 2) {
            val p0 = parts[0]
            val p1 = parts[1]
            val isDate = Regex("""^\d{4}[-/.]\d{1,2}[-/.]\d{1,2}$""").matches(p0) ||
                    Regex("""^\d{1,2}[-/.]\d{1,2}$""").matches(p0) ||
                    Regex("""^\[?\d{4}[-/.]\d{1,2}[-/.]\d{1,2}\]?$""").matches(p0)
            val isTime = Regex("""^\d{1,2}:\d{2}(:\d{2})?$""").matches(p1)
            if (isDate && isTime) return parts.drop(2).joinToString(" ").trim()
            if (Regex("""^\d{1,2}:\d{2}(:\d{2})?$""").matches(p0)) return parts.drop(1).joinToString(" ").trim()
        }
        return t
    }

    private fun etaText(latest: String, status: String): String {
        // ★ vc346（同志 2026-10-20 报「手动单号该显示已签收」）：**终态优先**。
        //   旧实现不管 status 是什么，最后都兜成「在途中」——已签收的手动单也显示"在途中"。
        val terminal = listOf(
            "已签收", "已收货", "已取件", "已取货", "已完成", "已退款",
            "已取消", "交易关闭", "查无结果", "查不到轨迹"
        )
        terminal.firstOrNull { status.contains(it) }?.let { return it }
        // ★ vc350：只有「待收货」一个词、没有任何物流内容时，别编"在途中"——
        //   如实显示状态本身（多多买菜/未发货类单的卡就是这个形态，2026-10-22 截图实证）。
        if (status == "待收货" && (latest.isBlank() || latest == "待收货")) return "待收货"
        val m = Regex("预计\\s*(\\d{1,2})\\s*日").find(latest)
        if (m != null) {
            try {
                val now = java.util.Calendar.getInstance()
                val target = (now.clone() as java.util.Calendar).apply {
                    set(java.util.Calendar.MONTH, m.groupValues[1].toInt() - 1)
                    set(java.util.Calendar.DAY_OF_MONTH, m.groupValues[2].toInt())
                    set(java.util.Calendar.HOUR_OF_DAY, 23)
                }
                val days = ((target.timeInMillis - now.timeInMillis) / 86_400_000L).toInt() + 1
                if (days in 0..30) return "预计${days}天内"
            } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        }
        Regex("预计\\s*(今天|明天|后天)").find(latest)?.let { return "预计${it.groupValues[1]}送达" }
        if (status.contains("派送") || status.contains("配送")) return "即将送达"
        return "在途中"
    }

    /**
     * ★ vc692：途中卡「预计到达」文案 —— **只在真有依据时返回非空**。
     *
     * 与 [etaText] 的分工：etaText 兜底永远给一句话（"在途中"），适合当状态词；
     * 卡片信息行只要"预计"这类有价值的结论，认不出就返回空串（调用方不显示），
     * 免得每张卡都挂一句废话。
     *
     * 认这些形态：`预计11月5日` / `预计5日` / `预计5号` / `预计今天|明天|后天` /
     * `预计24小时`；都没有但已在派送 ⇒「今日派送中」。
     */
    private fun etaForCard(
        latest: String, traceLines: String, status: String, hint: String = ""
    ): String {
        // ★ vc696：拼多多订单列表自带的「预计X送达」（每单都有）优先 —— 轨迹文本只有部分承运商写。
        //   实测形态：预计今天/明天/后天送达、预计最晚后天送达（多一个「最晚」，老正则认不出）。
        if (hint.isNotEmpty()) {
            Regex("预计\\s*(?:最晚|最早|大概|可能|约)?\\s*(今天|明天|后天)").find(hint)?.let {
                return "预计${it.groupValues[1]}送达"
            }
            Regex("预计\\s*[【\\[（(]?\\s*(\\d{1,2})\\s*[日号]").find(hint)?.let { m ->
                val dy = m.groupValues[1].toIntOrNull()
                if (dy != null) dayText(daysUntil(null, dy))?.let { return it }
            }
        }
        val pool = listOf(latest, status, traceLines, hint).joinToString(" ")
        Regex("预计\\s*[【\\[（(]?\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]").find(pool)?.let { m ->
            val mo = m.groupValues[1].toIntOrNull()
            val dy = m.groupValues[2].toIntOrNull()
            if (mo != null && dy != null) dayText(daysUntil(mo, dy))?.let { return it }
        }
        Regex("预计\\s*[【\\[（(]?\\s*(\\d{1,2})\\s*[日号]").find(pool)?.let { m ->
            val dy = m.groupValues[1].toIntOrNull()
            if (dy != null) dayText(daysUntil(null, dy))?.let { return it }
        }
        Regex("预计\\s*(?:最晚|最早|大概|可能|约)?\\s*[【\\[（(]?\\s*(今天|明天|后天)").find(pool)?.let {
            return "预计${it.groupValues[1]}送达"
        }
        Regex("预计\\s*(\\d{1,3})\\s*小时").find(pool)?.let { return "预计${it.groupValues[1]}小时内送达" }
        if (status.contains("派送") || status.contains("派件") || status.contains("投递")) return "今日派送中"
        return ""
    }

    /** 天数 → 人话；超出 0..30 天视为不可信，返回 null */
    private fun dayText(days: Int?): String? = when {
        days == null || days !in 0..30 -> null
        days == 0 -> "预计今天送达"
        days == 1 -> "预计明天送达"
        else -> "预计${days}天后送达"
    }

    /**
     * 目标月/日距今天的天数。`month = null` 表示原文只给了日号：
     * 先按本月算，结果为负则视为跨月，加一个月重算。
     */
    private fun daysUntil(month: Int?, day: Int): Int? = try {
        val now = java.util.Calendar.getInstance()
        val target = (now.clone() as java.util.Calendar).apply {
            if (month != null) set(java.util.Calendar.MONTH, month - 1)
            set(java.util.Calendar.DAY_OF_MONTH, day)
            set(java.util.Calendar.HOUR_OF_DAY, 23)
            set(java.util.Calendar.MINUTE, 59)
        }
        var days = ((target.timeInMillis - now.timeInMillis) / 86_400_000L).toInt()
        if (month == null && days < 0) {
            target.add(java.util.Calendar.MONTH, 1)
            days = ((target.timeInMillis - now.timeInMillis) / 86_400_000L).toInt()
        }
        days
    } catch (t: Throwable) {
        SafeRun.w("MainActivity", t)
        null
    }

    /**
     * 轨迹行首的时间戳：`2026-10-11 18:39:52` ⇒ 只取到分钟（`2026-10-11 18:39`）。
     * 有的源本身就不带秒，所以秒那一段写成可选。
     */
    private val TRACE_TIME_HEAD = Regex("^\\s*(\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2})(?::\\d{2})?\\s*")

    /**
     * ★★ vc680：**卡片那行只留"日期之后"的物流描述**（同志 2026-11-03 要求）。
     *
     * 原因：拼多多的轨迹行是「状态 + 日期时间 + 描述」拼起来的，例如
     *   `运输中2026-11-02 19:25:57 您的快件已离开【西安转运中心】…`
     * 卡片只有一行、宽度有限，前面这串"运输中+日期时间"既占地方又没有信息量
     * （时间在展开的详情里还在，没丢）。所以卡片上把**日期及它前面的一切**砍掉，
     * 只留描述本身；没有日期就原样返回，避免误伤本来就只有描述的源（京东/手动单号等）。
     *
     * ⚠️ 只作用于**卡片那一行**，展开详情里的时间轴不受影响。
     */
    private val TRACE_DATE_ANY = Regex("""\d{4}[-/.]\d{1,2}[-/.]\d{1,2}(?:[ T]\d{1,2}:\d{2}(?::\d{2})?)?""")

    private fun afterTraceDate(s: String): String {
        val raw = s.trim()
        if (raw.isEmpty()) return raw
        val m = TRACE_DATE_ANY.find(raw) ?: return raw
        val body = raw.substring(m.range.last + 1).trim()
        return body.ifEmpty { raw }
    }

    /**
     * ★ vc231：快递公司在轨迹里夹带的客服电话 / 广告尾巴。
     *   这些字常常比真正的物流信息还长（同志原话："太占空间了"），一律删掉。
     *   ⚠️ 只删**明确是联系/营销**的片段，绝不动地点、驿站名、动作本身 ——
     *      取件码就是从轨迹文案里抠出来的（见 pickupCodeOf），误删会直接丢功能。
     */
    private val TRACE_ADS = listOf(
        // 【物流问题请致电（专属热线:95554）更快解决】 / 【95126为韵达快递员外呼专属号码，请放心接听】
        Regex("【[^】]*(?:物流问题|专属热线|专属电话|专属号码|外呼|请放心接听|投诉|致电|联系客服|温馨提示|有事呼叫|无需找商家|客服|热线)[^】]*】"),
        // （物流问题无需找商家或平台，请致电官方客服热线956025为您解决 ）
        // ★ vc682：关键词补「客服 / 热线 / 服务」，拼多多这版广告就是栽在这里；
        //   顺带容忍括号里尾随空格（原文是「…为您解决 ）」）。
        Regex("[（(][^）)]*(?:物流问题|专属热线|专属电话|拨打专属|客服|热线|致电|投诉|服务热线)[^）)]*[)）]"),
        // 「，如有取件码问题或找不到包裹等问题，请电联站点：…，投诉电话：…（…）」整条尾巴
        Regex("[,，][^。]*(?:如有疑问|如有取件码|如您未收到|有问题请|请电联|请联系|网点电话|投诉电话)[^。]*。?$"),
        // 网点电话 / 快递员手机号：(0794-2105145) (13800138000)
        Regex("[（(]\\d{7,12}[)）]")
    )

    /**
     * ★★ vc682：**广告/客服句的判定词**（同志 2026-11-03 要求按句过滤）。
     *
     * 为什么改成"按句"：以前的 [TRACE_ADS] 是逐条列举固定措辞，拼多多换个说法就漏
     * （实测「极兔速递全程守护，让每一份期待安心抵达。」整句都留下来了）。
     * 现在先删括号里的营销段，再按句号/分号切句，**含这些词的整句丢掉**。
     *
     * ⚠️ 词表刻意只收"联络/营销"的字眼，不收"驿站/柜/派送/取件"这类物流动作 ——
     *    取件码是从文案里抠的，误删会直接丢功能。
     */
    private val AD_SENTENCE_MARKERS = listOf(
        "致电", "热线", "客服", "投诉", "专属", "请放心接听", "无需找商家",
        "全程守护", "期待", "承蒙", "为您服务", "外呼", "拨打", "电联", "物流问题"
    )

    /**
     * ★★ vc682：**卡片那行与展开详情共用**的文案清洗。
     * 顺序：先删括号广告段 → 再按句丢营销句 → 收尾压空格；全被删空则回退原文（宁可啰嗦不留空行）。
     */
    private fun cleanTraceText(src: String): String {
        if (src.isBlank()) return src
        var body = src
        for (ad in TRACE_ADS) body = ad.replace(body, "")
        val kept = body.split(Regex("(?<=[。！；;])"))
            .filter { p -> p.isNotBlank() && AD_SENTENCE_MARKERS.none { p.contains(it) } }
        var out = kept.joinToString("").replace(Regex("\\s{2,}"), " ").trim()
        out = out.trimEnd('，', ',', '、', ' ', '；', ';')
        return out.ifEmpty { src.trim() }
    }

    /**
     * ★ vc231 单条轨迹的排版：时间一行（小字灰）+ 内容一行（正文），左侧时间轴串起来。
     *
     * @param isHead 最新一条 ⇒ 圆点点亮 + 文字用主色（一眼看出"现在在哪一步"）
     * @param isTail 最老一条 ⇒ 不再往下画连接线
     */
    /**
     * ★ vc588：**物流流程时间线**按令牌重做。
     *
     * 改前是"7dp 灰点 + 硬编码 #3A3A38 的 1.5dp 竖线 + 11sp 时间 + 13sp 正文"，
     * 问题有三个：① 颜色是写死的深灰，浅色模式下脏；② 点太小、和正文没有对齐关系；
     * ③ 最新一条和历史一条长得一样，扫不出"现在到哪了"。
     *
     * 现在：
     *  · 轨道宽 20dp：最新节点 8dp 实心 cAccent + 16dp 20% 淡光晕（一眼看出当前进度）；
     *    历史节点 8dp 实心 cTextTertiary；
     *  · 竖线 1dp，用全局分割线色（`UiTok.dividerColor`，深浅自适应），从圆点下方 2dp 起、贯穿到本行底部；
     *  · 时间 12sp cTextTertiary 在上，正文 13sp：最新 cTextPrimary + Medium，历史 cTextSecondary，行距 1.35；
     *  · 每条底部留 12dp，最后一条不留 —— 节点之间的节奏靠这条留白，而不是靠空 View。
     */
    private fun traceStep(raw: String, isHead: Boolean, isTail: Boolean): View {
        val m = TRACE_TIME_HEAD.find(raw)
        val time = if (m != null) m.groupValues[1].replace('T', ' ') else ""
        var body = cleanTraceText(if (m != null) raw.substring(m.range.last + 1) else raw)
        // 真被删空了（整行就是一句广告）⇒ 保留原文，宁可啰嗦也不能显示空行
        if (body.isEmpty()) body = raw.trim()

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        // ---------- 左：时间轴 ----------
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val dotBox = FrameLayout(this).apply {
            if (isHead) {
                // 当前节点的淡光晕（20% 强调色）
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(UiTok.brandWithAlpha(cAccent, if (isDark()) 0x33 else 0x22))
                }
            }
        }
        dotBox.addView(View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isHead) cAccent else cTextTertiary)
            }
        }, FrameLayout.LayoutParams((UiTok.S8 * dp).toInt(), (UiTok.S8 * dp).toInt()).apply {
            gravity = Gravity.CENTER
        })
        rail.addView(dotBox, LinearLayout.LayoutParams(
            (UiTok.S16 * dp).toInt(), (UiTok.S16 * dp).toInt()
        ).apply { topMargin = (UiTok.S4 * dp).toInt() })

        if (!isTail) {
            rail.addView(View(this).apply {
                setBackgroundColor(UiTok.dividerColor(isDark()))
            }, LinearLayout.LayoutParams((UiTok.HAIR * dp).toInt().coerceAtLeast(1), 0, 1f).apply {
                topMargin = (4 * dp).toInt()
            })
        }
        row.addView(rail, LinearLayout.LayoutParams(
            (UiTok.S20 * dp).toInt(), ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // ---------- 右：时间 + 正文 ----------
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((UiTok.S8 * dp).toInt(), 0, 0, if (isTail) 0 else (UiTok.S12 * dp).toInt())
        }
        if (time.isNotEmpty()) {
            col.addView(TextView(this).apply {
                text = time
                textSize = UiTok.T12
                setTextColor(cTextTertiary)
            })
        }
        col.addView(TextView(this).apply {
            text = body
            textSize = UiTok.T13
            // 字重只用 Regular / Medium（项目里没有 tfRegular，用系统默认）
            typeface = if (isHead) tfMedium else android.graphics.Typeface.DEFAULT
            setTextColor(if (isHead) cTextPrimary else cTextSecondary)
            setLineSpacing((2 * dp).toFloat(), 1.35f)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (2 * dp).toInt() })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    /**
     * 第 5 栏**当前展开的那一张**的详情区。null = 全都收着。
     * 只在第 5 栏列表存活期间有效 —— 列表每次重建都清空（`renderInTransit` 里），
     * 否则会攥着一个已经被丢掉的旧 View，造成看不见的内存泄漏。
     */
    private var expandedTransitDetail: View? = null

    /**
     * ★ vc232 带动画地展开 / 收起一个容器（改的是**高度**，透明度同步）。
     *
     * 为什么手写高度动画，不开 `animateLayoutChanges`：
     *   那个开关会把「收起」拆成两段串行——先把内容淡出，淡完了才改布局、下面的卡才开始挪，
     *   总计接近 600ms，观感是"点了半天才动"；而"一次只展开一张"要同时处理"收旧的 + 开新的 + 移动其它"，
     *   串行触发得更明显。这里让**高度本身就是动画对象**，LinearLayout 天然把下面的卡推下去，
     *   整排只用一条时间轴，没有排队等待。
     *
     * @param toShow true=展开，false=收起
     */
    private fun animateHeight(v: View, toShow: Boolean) {
        v.animate().cancel()
        // ★ 连点保护：上一次的高度动画还在跑就掐掉，否则两个 ValueAnimator 同时写
        //   layoutParams.height，卡片会抖（你追我赶地互相覆盖）。
        (v.tag as? ValueAnimator)?.cancel()
        val lp = v.layoutParams
        val from: Int
        val to: Int
        if (toShow) {
            if (v.visibility == View.VISIBLE && v.height > 0) return   // 已经开着
            v.visibility = View.VISIBLE
            from = 0
            to = measureWrapContentHeight(v)
        } else {
            if (v.visibility != View.VISIBLE) return                   // 已经是关的
            from = v.height
            to = 0
        }
        val dur = if (toShow) 260L else 220L
        val dec = android.view.animation.DecelerateInterpolator()
        // 内容跟着渐显 / 渐隐，纯粹的高度变化会显得有点"抽"
        v.alpha = if (toShow) 0.35f else 1f
        v.animate().alpha(if (toShow) 1f else 0f).setDuration(dur).setInterpolator(dec).start()
        ValueAnimator.ofInt(from, to).apply {
            duration = dur
            interpolator = dec
            v.tag = this
            addUpdateListener { va ->
                lp.height = va.animatedValue as Int
                v.layoutParams = lp
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    // ★ 必须把高度还给 WRAP_CONTENT：写死数字的话，以后这张卡的轨迹
                    //   多出几行（快递走到新站点）就会被截断。收起时留 0，下次从 0 起跳。
                    lp.height = if (toShow) ViewGroup.LayoutParams.WRAP_CONTENT else 0
                    v.layoutParams = lp
                    v.alpha = 1f
                    if (!toShow) v.visibility = View.GONE
                }
            })
            start()
        }
    }

    /** 量一个 WRAP_CONTENT 容器「放开了应该多高」—— 拿它当高度动画的目标值。 */
    private fun measureWrapContentHeight(v: View): Int {
        val p = v.parent as? ViewGroup ?: return 0
        // 减去父容器的左右内边距，否则宽度算大了 ⇒ 行数少算一行 ⇒ 展开完跳一下
        val w = p.width - p.paddingLeft - p.paddingRight
        if (w <= 0) return 0
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return v.measuredHeight
    }

    // ---------- 第 5 栏缓存（vc234） ----------

    /** 在途状态变得比取件码快（取件码几天不动，物流一天几变），隔夜的缓存不如不显示。 */
    private val TRANSIT_CACHE_KEEP_MS = TimeGates.TRANSIT_CACHE_KEEP_MS
    /** vc270c：在途**列表**缓存保留时长 —— 对齐取件码页口径（常驻直到被新数据替换），72h 防严重陈旧 */
    private val TRANSIT_LIST_KEEP_MS = TimeGates.TRANSIT_LIST_KEEP_MS

    /** ★ vc255：订单列表接口最短进页间隔（90 秒）。
     *
     * 反复进出第 5 栏是最常见的操作，而 [fetchInTransit] 原来每进一次就打 1~2 次
     * `aristotle/order_list_v4`（"秒显"只是先画缓存，请求照发）⇒ 进出 10 次 =
     * 10~20 次全量订单 POST。这是整条链路里最像爬虫的行为，必须加闸。
     * 间隔内只渲染缓存 + 状态行如实说明剩余秒数，**任何请求都不发**。
     */
    /**
     * ★ vc594：进页自动刷新的**防抖**间隔，90s → **10s**。
     *
     * 历史：vc255/vc264 设 90s 是为了省平台额度（拼多多每轮要开一次 WebView，淘宝/京东有频控），
     * 但同志的实际感受是"刚刷新完退出去再进来，就被提示要等 90 秒"，像被锁住。
     * 现在只保留 10 秒：足够挡"返回→立刻再进"的连点，正常的进出/下拉都不会被拦。
     * （下拉刷新、顶栏刷新按钮本来就是 force=true，任何间隔都穿透。）
     */
    private val ORDER_LIST_MIN_INTERVAL_MS = TimeGates.ORDER_LIST_MIN_INTERVAL_MS

    /**
     * ★ vc342（P0-5）：**单源**硬超时。
     *
     * 以前只有"整栏 70s/50s"的总超时：一个源卡住，整栏陪着干等，而且界面一直显示
     * "正在获取X"。现在每个源各挂 20s 定时器 —— 到点就把这一路按"完成"结算，
     * 旧卡照常显示，其它源照常上屏。
     */
    private val PER_SOURCE_TIMEOUT_MS = TimeGates.PER_SOURCE_TIMEOUT_MS

    /** ★ vc255：两个拼多多物流页之间的间隔。串行已是既成事实（PddTraceChannel 单例），
     *  再加 1.2 秒让节奏更像人，也降设备行为风控的概率。 */
    private val TRACE_PAGE_GAP_MS = 1_200L

    private fun transitCacheFile() = java.io.File(filesDir, "transit_cache.json")

    /** 把本次**抓到的**在途列表落盘（下一趟进页就能秒显）。只在数据全回来时才调。 */
    /**
     * vc270c：在途快递缓存改**按源保存**（对齐取件码页口径：对应源抓取失败绝不动它的缓存）。
     * 旧版整表覆盖的毛病：一轮刷新里只要有一个源失败/中断，它上一趟的卡就从缓存里消失。
     * 现在每源各存各的（pddTransit/jdTransit/miTransit/tbTransit/manualTransit 字段），
     * 源回调"成功才赋值"⇒ 失败时字段保持旧卡 ⇒ 落盘自然保留。
     * clearAll=true 仅供测试数据清理用（连轨迹缓存一起清）。
     */
    /**
     * ★ vc692：把落盘的在途卡按源灌回内存字段（pddTransit/jdTransit/miTransit/tbTransit/manualTransit）。
     *
     * 为什么要这一步：内存字段原本只在「本轮该源抓取成功」时才赋值 ⇒
     *   ① 用户中途退出（源还没回来）⇒ 这一轮没有任何字段被填 ⇒
     *      退出时无从落盘、下次进来缓存也没有新东西，只能重新等一遍；
     *   ② 冷启动后内存为空，而缓存文件里其实躺着上一轮的卡。
     * 先灌回内存，后续「退出即落盘」才是安全的（不会拿空数组覆盖好缓存）。
     * 只灌**当前为空**的源，不覆盖本轮已经抓到的数据。
     */
    private fun hydrateTransitSources() {
        try {
            synchronized(UiCacheSync.FILE_LOCK) {
                val f = transitCacheFile()
                if (!f.exists()) return
                val root = JSONObject(f.readText())
                if (System.currentTimeMillis() - root.optLong("ts") > TRANSIT_LIST_KEEP_MS) return
                val src = root.optJSONObject("sources") ?: return
                fun read(key: String): ArrayList<JSONObject> {
                    val out = ArrayList<JSONObject>()
                    val arr = src.optJSONArray(key) ?: return out
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
                    return out
                }
                var n = 0
                fun hydrate(field: ArrayList<JSONObject>, key: String) {
                    if (field.isNotEmpty()) return
                    val got = read(key)
                    if (got.isNotEmpty()) { field.addAll(got); n += got.size }
                }
                hydrate(pddTransit, "pdd")
                hydrate(jdTransit, "jd")
                hydrate(miTransit, "mi")
                hydrate(tbTransit, "tb")
                hydrate(manualTransit, "manual")
                if (n > 0) Log.i("TransitCache", "缓存回灌：$n 张卡进内存（退出即落盘的前提）")
            }
        } catch (t: Throwable) {
            SafeRun.w("TransitCache", t)
        }
    }

    private fun saveTransitCache(clearAll: Boolean = false) {
        // ★ vc281：后台 UiCacheSync 也会写这个文件（WorkManager 线程）⇒ 读改写全程持锁
        try {
            synchronized(UiCacheSync.FILE_LOCK) {
                val src = JSONObject()
                if (!clearAll) {
                    fun putArr(k: String, list: List<JSONObject>) {
                        val a = JSONArray()
                        for (o in list) a.put(o)
                        src.put(k, a)
                    }
                    putArr("pdd", pddTransit)
                    putArr("jd", jdTransit)
                    putArr("mi", miTransit)
                            putArr("tb", tbTransit)
                    putArr("manual", manualTransit)
                }
                // ★ vc255：轨迹一并落盘（键=orderSn）。原来只在内存 Map 里，见 loadTransitTraceCache。
                val tr = JSONObject()
                synchronized(transitCacheLock) {
                    for ((k, v) in pddTransitTraceCache) {
                        tr.put(k, JSONObject()
                            .put("waybillNo", v.waybillNo)
                            .put("latest", v.latest)
                            .put("at", v.at)
                            .put("lines", v.lines))
                    }
                }
                transitCacheFile().writeText(JSONObject()
                    .put("ts", System.currentTimeMillis())
                    .put("sources", src)
                    .put("traces", tr).toString())
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(
                    KEY_LAST_TRANSIT_TIME,
                    java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date())
                ).apply()
            }
        } catch (e: Throwable) {
            Log.w("TransitCache", "存在途快递缓存失败: ${e.message}")
        }
    }

    /**
     * 判断一张途中卡是否已经明确结束。
     * 缓存首显也必须走这道闸，不能因为服务端本轮暂时没返回就把旧的已取件卡复活。
     * 「已签收，待确认收货」以及仍有明确运输文案的卡继续保留，避免误伤代收/状态滞后。
     */
    private fun isTransitClearlyTerminal(p: JSONObject): Boolean {
        val status = p.optString("statusText")
        val latest = p.optString("latest")
        val pendingConfirm = PickupVerdict.PENDING_CONFIRM.any {
            status.contains(it) || latest.contains(it)
        }
        val inTransit = PickupVerdict.MSG_IN_TRANSIT.any {
            status.contains(it) || latest.contains(it)
        }
        return !pendingConfirm && !inTransit &&
            PickupVerdict.SOFT_TERMINAL.any { status.contains(it) }
    }

    /** 读上一趟的在途列表（vc270c：按源结构，合并全部源）；过期 / 损坏都当"没有缓存"处理（静默）。 */
    private fun loadTransitCache(): ArrayList<JSONObject> {
        val out = ArrayList<JSONObject>()
        val removed = ArrayList<JSONObject>()
        try {
            // ★ vc281：与后台 UiCacheSync 互斥（它可能在 WorkManager 线程重写此文件）
            synchronized(UiCacheSync.FILE_LOCK) {
                val f = transitCacheFile()
                if (!f.exists()) return out
                val root = JSONObject(f.readText())
                if (System.currentTimeMillis() - root.optLong("ts") > TRANSIT_LIST_KEEP_MS) return out
                // vc270c：新结构 {"sources":{pdd:[...],tb:[...],...}}；旧格式（整表 "list"）直接当无缓存，
                // 反正新一轮抓取会按源重建。
                val src = root.optJSONObject("sources") ?: return out
                var changed = false
                for (key in src.keys()) {
                    val arr = src.optJSONArray(key) ?: continue
                    val kept = JSONArray()
                    var sourceChanged = false
                    for (i in 0 until arr.length()) {
                        val p = arr.optJSONObject(i) ?: continue
                        if (isTransitClearlyTerminal(p)) {
                            removed.add(p)
                            sourceChanged = true
                            changed = true
                        } else {
                            kept.put(p)
                            out.add(p)
                        }
                    }
                    if (sourceChanged) src.put(key, kept)
                }
                if (changed) {
                    root.put("sources", src)
                    root.put("ts", System.currentTimeMillis())
                    f.writeText(root.toString())
                }
            }
        } catch (e: Throwable) {
            Log.w("TransitCache", "读在途快递缓存失败: ${e.message}")
        }
        if (removed.isNotEmpty()) {
            Log.i("TransitTerminal", "读缓存时清除已完成旧卡 ${removed.size} 张：" +
                removed.joinToString(" | ") {
                    "${it.optString("src")}:${it.optString("realWaybill").ifEmpty { it.optString("waybillNo") }}=" +
                        it.optString("goodsName")
                })
        }
        // ★★ v1.4.7：缓存里的卡也可能带着被写脏的 `traceLines`（同一段轨迹挂多单）。
        //   判据同 loadTransitTraceCache：逐字相同的多行轨迹不可能属于两个不同包裹 ⇒ 一并剥掉，
        //   让卡片退回显示自己的 `latest`，并由下一轮按 orderSn 重补轨迹。
        run {
            val byLines = HashMap<String, MutableList<JSONObject>>()
            for (p in out) {
                val lines = p.optString("traceLines").trim()
                if (!lines.contains('\n')) continue
                byLines.getOrPut(lines) { ArrayList() }.add(p)
            }
            var stripped = 0
            for ((_, group) in byLines) {
                if (group.size < 2) continue
                for (p in group) { p.remove("traceLines"); stripped++ }
            }
            if (stripped > 0) {
                Log.w("TransitCache", "清掉缓存卡上被污染的重复轨迹 $stripped 张（同一段轨迹挂了多单）")
            }
        }
        return out
    }

    /**
     * ★ vc255：读落盘的轨迹缓存（键 = orderSn）。
     *
     * 与 [loadTransitCache] 的关键差别：**不按文件的 ts 判过期** —— 每条轨迹自带 `at`，
     * TTL 由调用方（enhancePddTransitTraces）按 at 判断。否则"列表刚过期但轨迹还新鲜"
     * 会被整体误杀，白丢一次能省下的物流页。
     */
    private fun loadTransitTraceCache(): HashMap<String, TransitTrace> {
        val out = HashMap<String, TransitTrace>()
        try {
            // ★ vc281：与后台 UiCacheSync 互斥
            synchronized(UiCacheSync.FILE_LOCK) {
                val f = transitCacheFile()
                if (!f.exists()) return out
                val tr = JSONObject(f.readText()).optJSONObject("traces") ?: return out
                for (k in tr.keys()) {
                    val o = tr.optJSONObject(k) ?: continue
                    out[k] = TransitTrace(
                        waybillNo = o.optString("waybillNo"),
                        latest = o.optString("latest"),
                        at = o.optLong("at"),
                        lines = o.optString("lines")
                    )
                }
            }
        } catch (e: Throwable) {
            Log.w("TransitCache", "读途中轨迹缓存失败: ${e.message}")
        }
        // ★★ v1.4.7：**清掉被污染的轨迹** —— 这是「所有卡片都是同一个物流」的收尾。
        //   历史 bug（≤ v1.4.5）：`applyTransitTrace` 拿 `waybillNo` 当订单号匹配，而它自己又把
        //   `waybillNo` 改写成真运单号 ⇒ 一批卡同时命中同一个键 ⇒ **同一段轨迹被写给了所有卡**，
        //   并原样落进这份缓存。真机实证：10 张卡（运单号各不相同）显示逐字相同的
        //   「快件正在长途运输中…距离下一站约898KM」。
        //   判据：两个不同包裹不可能有**逐字相同的全程轨迹**。同一段多行 lines 挂在两个以上
        //   订单号下 ⇒ 必是写脏的。无法判断它本该属于谁，所以整组丢弃，由下一轮按订单号重补
        //   （1.4.6 起匹配键改成 orderSn，重补是 1:1 的，不会再串）。
        run {
            val byLines = HashMap<String, MutableList<String>>()
            for ((k, v) in out) {
                val lines = v.lines.trim()
                if (!lines.contains('\n')) continue    // 单行不算证据，不动
                byLines.getOrPut(lines) { ArrayList() }.add(k)
            }
            val bad = ArrayList<String>()
            for ((_, keys) in byLines) if (keys.size >= 2) bad.addAll(keys)
            if (bad.isNotEmpty()) {
                for (k in bad) out.remove(k)
                Log.w("TransitCache", "清理被污染的重复轨迹 ${bad.size} 条（同一段轨迹挂了多单）：$bad")
            }
        }
        return out
    }

    /**
     * ★ vc255：只把轨迹部分写回文件，**不动 list / ts / "上次更新时间"**。
     *
     * 轨迹是抓取回来后异步补的，那时候列表早就落过盘了；若直接调 [saveTransitCache]，
     * 会把"合并去重后的全量卡片"重写成只剩拼多多的那份 ⇒ 只能单独改 traces 字段。
     */
    private fun saveTransitTraces() {
        try {
            // ★ vc281：与后台 UiCacheSync 互斥 —— 它读改写整个文件（含 traces），不能交错
            synchronized(UiCacheSync.FILE_LOCK) {
                val f = transitCacheFile()
                val root = if (f.exists()) {
                    try {
                        JSONObject(f.readText())
                    } catch (t: Throwable) {
                        JSONObject()
                    }
                } else JSONObject()
                val tr = JSONObject()
                synchronized(transitCacheLock) {
                    for ((k, v) in pddTransitTraceCache) {
                        tr.put(k, JSONObject()
                            .put("waybillNo", v.waybillNo)
                            .put("latest", v.latest)
                            .put("at", v.at)
                            .put("lines", v.lines))
                    }
                }
                root.put("traces", tr)
                if (!root.has("ts")) root.put("ts", System.currentTimeMillis())
                f.writeText(root.toString())
                Log.i("TransitCache", "轨迹缓存落盘 ${tr.length()} 条")
            }
        } catch (e: Throwable) {
            Log.w("TransitCache", "存途中轨迹缓存失败: ${e.message}")
        }
    }

    // ---------- vc264p 测试物流数据（第 5 栏用） ----------

    /**
     * 灌 3 条测试物流进第 5 栏，**每条 10 个轨迹节点**（同志 04:52 要求）。
     *
     * 用法（调试参数只在 onCreate 读 ⇒ **必须先 force-stop**）：
     * ```
     * adb shell am force-stop com.agent.idcode
     * adb shell am start -n com.agent.idcode/.MainActivity --ez debug_seed_transit3 true
     * ```
     * 三条分别挂 sf / jd / pdd 三个源（chip 颜色不同，好区分），
     * 数据**落盘** ⇒ 杀进程后重新进第 5 栏还在（走 loadTransitCache 秒显那条路）。
     * 清除：[clearTransitTestData]（--ez debug_seed_transit_clear true）。
     */
    /** 测试物流的持久化（SP 存 JSON 数组；默认空串 ⇒ 不影响生产） */
    private fun saveTestTransit(list: List<JSONObject>) {
        try {
            val arr = JSONArray()
            list.forEach { arr.put(it) }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("test_transit_json", arr.toString()).apply()
        } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
    }

    private fun loadTestTransit(): ArrayList<JSONObject> {
        val out = ArrayList<JSONObject>()
        try {
            val s = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("test_transit_json", "[]") ?: "[]"
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
        } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        return out
    }

    private fun seedTransitTestData() {
        val now = System.currentTimeMillis()
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
        // 单号 / 公司 / 商品 / 源
        val plans = listOf(
            arrayOf("MI20261030001", "小米商城", "无线蓝牙耳机", "mi"),
            arrayOf("JD0987654321002", "京东物流", "厨房收纳置物架", "jd"),
            arrayOf("YT8877665544003", "圆通速递", "纯棉短袖T恤", "pdd")
        )
        val list = ArrayList<JSONObject>()
        plans.forEachIndexed { idx, p ->
            val no = p[0]; val company = p[1]; val goods = p[2]; val src = p[3]
            val lines = ArrayList<String>()
            // 10 个节点：从"已下单"一路到"派送中"，时间从早到晚（最新在第一行）
            val steps = listOf(
                "【商家】订单已提交，等待发货",
                "【商家】商品已打包完成",
                "【$company】快递员已揽收",
                "【深圳集散中心】已发出，下一站【武汉转运中心】",
                "【武汉转运中心】已到达",
                "【武汉转运中心】已发出，下一站【长沙分拨中心】",
                "【长沙分拨中心】已到达",
                "【长沙分拨中心】已发出，下一站【岳麓区网点】",
                "【岳麓区网点】已到达，正在分拣",
                "【岳麓区网点】快递员已取件，正在派送中"
            )
            for (i in steps.indices) {
                // 最新一条排最前：倒序时间（第 10 步 = 现在，第 1 步 = 90 小时前）
                val minsAgo = (steps.size - 1 - i) * 9 * 60L
                lines.add("${sdf.format(java.util.Date(now - minsAgo * 60_000))} ${steps[i]}")
            }
            val o = JSONObject().apply {
                put("src", src)
                put("waybillNo", no)
                put("realWaybill", no)     // 首屏直接显示真运单号
                put("goodsName", goods)
                put("statusText", "运输中")
                put("latest", lines.first())   // 卡上一行显示最新节点
                put("tip", lines.first())
                // ★ 展开后显示的完整物流流程 —— renderInTransit 展开区读的是**这个字段**
                //   （不是 pddTransitTraceCache；那个是"补轨迹"机制用的，别混淆）
                put("traceLines", lines.joinToString("\n"))   // 10 行 = 10 个节点
                put("nickName", company)
                put("phone", "")
            }
            list.add(o)
            synchronized(transitCacheLock) {
                pddTransitTraceCache[no] = TransitTrace(
                    waybillNo = no,
                    latest = lines.first(),
                    at = now,
                    lines = lines.joinToString("\n")   // ★ 10 行 = 展开后 10 个节点
                )
            }
        }
        // ★ 进 testTransit（renderInTransit 会合并它）——缓存只负责"秒显"那一趟，
        //   抓取完成后的渲染不合并缓存，光写缓存会被冲掉（04:57 实测）
        testTransit.clear()
        testTransit.addAll(list)
        saveTestTransit(list)
        saveTransitCache()       // 秒显用（vc270c：按源落盘；测试卡经 SP 独立恢复）
        saveTransitTraces()      // 轨迹落盘
        Log.i("TestSeed", "已灌 ${list.size} 条测试物流（每条 10 个节点）：" +
                list.joinToString(" | ") { it.optString("waybillNo") })
    }

    /** 清掉 [seedTransitTestData] 灌的测试数据，恢复真实抓取 */
    private fun clearTransitTestData() {
        synchronized(transitCacheLock) {
            pddTransitTraceCache.clear()
        }
        testTransit.clear()
        saveTestTransit(ArrayList())
        saveTransitCache(clearAll = true)
        saveTransitTraces()
        Log.i("TestSeed", "测试物流已清除，恢复真实抓取")
    }

    /**
     * 途中栏渲染：一行 = 平台标注 + 商品名 + 预计天数；单击展开精确物流，挤压下方内容
     *
     * @param cache 非 null ⇒ 这一趟是**拿缓存秒显**的（进页时先画出来，抓取还在后台跑）。
     *               为 null ⇒ 正常渲染，并把结果落盘供下一趟秒显。
     */
    private fun renderInTransit(cache: ArrayList<JSONObject>? = null) {
        val list = transitList ?: return
        val useCache = cache != null
        // ★ vc270d：增量渲染 —— **不再等全部源回来才画**。旧逻辑源没齐就 return，
        //   下拉刷新瞬间 removeAllViews 清屏 + 淘宝链路 20~35s 才回来 ⇒ 屏幕长时间空白，
        //   观感="卡片丢失"（21:58 日志实锤：下拉后 21 秒零卡片）。
        //   现在字段里始终保留旧卡（vc270c 按源缓存）⇒ 重画时旧卡照常上屏，
        //   哪个源回来就更新它那份，状态行显示"正在获取X物流"。
        val allDone = !useCache && transitExpect > 0 && transitGot >= transitExpect
        if (allDone) {
            transitSwipe?.let { if (it.isRefreshing) it.isRefreshing = false }
        }
        // ★ vc232：列表要重画了，旧卡片会被丢掉 ⇒ 别再攥着它们的引用（否则收起一个已被回收的 view）
        // ★ vc708：**只丢 View 引用、保留身份键** —— 重画后按 [expandedTransitKey] 把那张卡重新展开，
        //   用户不会再看到"展开的卡自己合上"。
        expandedTransitDetail = null
        // 状态行：没全回来就显示"正在获取X物流"（但**继续画卡片**，不再 return）
        if (!useCache && transitGot < transitExpect) {
            setStatusText(transitStatus, transitLoadingText())
        }
        list.removeAllViews()
        // ★ 合并后立刻跨源去重：同一件被两个平台抓到时只渲染一张卡（见 dedupPkgs）
        // ★ vc259 修回归（09-22 vc182「移除 EMS」时把 manualTransit 一起带出了合并列表）：
        //   手动单号从那天起"抓了也不上屏"—— fetchManualChannel 照跑、后台跟踪快照照收
        //   （buildSnapshot 里还有它），就是第 5 栏列表里没有 ⇒ 观感="加了单号什么都没显示"。
        //   cache 分支（秒显/90 秒闸命中）也合并 manualTransit：闸命中时手动单号不重查，
        //   展示上一轮的结果与"刷新中·上次 HH:mm"的口径一致。
        val mergedTransit = dedupPkgs(ArrayList<JSONObject>().apply {
            cache?.let { addAll(it) }
            addAll(pddTransit); addAll(jdTransit); addAll(miTransit)
            // ★ vc302：已到站的淘宝卡不进「在途快递」（改收进第 4 栏取件码）—— 两栏分流
            // ★ vc354：枚举说已取走/已签收的卡也不进（两栏都不显示）
            addAll(tbTransit.filterNot {
                tbCardArrived(it) || tbEnumVerdict(
                    it.optString("statusCode"), it.optString("enumDesc"), it.optString("enumSeq")
                ) == TB_EN_TAKEN
            })
            addAll(manualTransit)
            addAll(testTransit)   // ★ vc264p：测试物流（默认空，生产零影响）
        })
        // ★★ v1.4.10 诊断：**在途件到底在哪一步少的**。
        //   12:15 真机：拼多多说"待收货 11 条 ⇒ 入选 11 条"，可页面只画 6 张，
        //   中间 dedup / 终态 / 黑名单三道闸都没有日志说剔了谁 ⇒ 只能靠这两行定位。
        Log.i(
            "TransitBuild",
            "入参 pdd=${pddTransit.size} tb=${tbTransit.size} cache=${cache?.size ?: 0} " +
                    "pddSn=" + pddTransit.joinToString(",") { it.optString("orderSn") }
        )
        Log.i(
            "TransitBuild",
            "去重后 ${mergedTransit.size} 张：" + mergedTransit.joinToString(",") {
                it.optString("orderSn") + "/" + it.optString("waybillNo")
            }
        )

        // ★★ v1.4.13：**一次裁决**（同志 13:26：「重新编写在途快递的抓取和过滤规则，可以参考 1.2.5」）。
        //
        //   这里原来是 4 段串联的 removeAll：终态文案 → 已有取件码(TransitMove) → 黑名单(TransitMuted)
        //   → isTransitClearlyTerminal → 小米陈旧单，渲染循环里还要再判一次黑名单。
        //   每段都带自己的豁免（"运输中优先""待确认豁免""宁多显示"）和自己的词表，互相对冲
        //   ⇒ 皇帝柑 / 化妆棉 / 润唇膏三轮事故全部出在这一段。
        //
        //   现在只留**一次** [TransitRules.judge]：一张卡判一次、判据顺序固定、丢弃必带原因。
        //   各源列表（pddTransit/tbTransit/…）一律不动 —— 规则改了下一轮抓取立刻恢复显示。
        val blacklistNow = pickupMutedAll()
        val all = ArrayList<JSONObject>()
        val mutedStillRunning = ArrayList<String>()
        val dropLog = ArrayList<String>()
        // ★★ v1.4.13：**一枚码被几个订单占用** —— 同码多单 = 抽取污染（格口码被页面重复抠走），
        //   这种码**不能**当作"这单已经到站"的依据。
        //   13:41 真机：`73-3-5888` 同时挂在 3 个订单上（其中两个的轨迹还是
        //   「已取件 2026-11-09 19:11:45 您的快件已被领取」的旧码），结果 2 件在途货
        //   被"已有取件码"整条剔掉，在途从 7 件变 5 件。
        //   ⇒ 只有**唯一归属**的码才算到站；多单共用的留给补码链去纠正。
        val codeOwners = HashMap<String, Int>()
        for ((_, c) in PddCodeStore.all(this)) {
            val cd = c.code.trim()
            if (cd.isNotEmpty()) codeOwners[cd] = (codeOwners[cd] ?: 0) + 1
        }
        for (q in mergedTransit) {
            val src = q.optString("src")
            val key = pickKey(src, q)
            // 判"是否已进驿站链"看**持久缓存**里的码，不看卡面 —— 卡面那个可能是上一轮遗留的借来码
            val cached = if (key != null) PddCodeStore.get(this, "$src:$key") else null
            val cachedCode = cached?.code.orEmpty().trim()
            val owners = if (cachedCode.isEmpty()) 0 else (codeOwners[cachedCode] ?: 0)
            val codeOk = cachedCode.isNotEmpty() && owners <= 1
            if (cachedCode.isNotEmpty() && owners > 1) {
                Log.i("TransitRules", "码 $cachedCode 被 $owners 单共用，不作为 $key 的到站依据")
            }
            // ★★ v1.4.14：取命中记录本身（不只是布尔）—— 后面要按 id 前缀区分「已取件」型
            val muteHit = pickupMuteHit(src, q)
            val card = TransitRules.Card(
                src = src,
                orderStatus = q.optString("statusText"),
                latest = q.optString("latest"),
                trace = q.optString("traceLines"),
                code = if (codeOk) cachedCode else "",
                waybill = q.optString("realWaybill")
                    .ifEmpty { q.optString("waybillNo") }
                    .ifEmpty { q.optString("mailNo") },
                hasLogisticsUrl = q.optString("expressUrl").isNotBlank(),
                pickedAt = maxOf(cached?.pickedAt ?: 0L, q.optLong("pickedAt", 0L)),
                muted = muteHit != null,
                mutedPicked = muteHit?.id?.startsWith("picked:") == true,
                // 拼多多订单号前 6 位 = YYMMDD 下单日期（15 天闸）
                bornAtMs = if (src == "pdd") (PddNative.snToDateMs(key.orEmpty()) ?: 0L) else 0L,
                staleMi = src == "mi" && MiShopChannel.isStaleForTransit(q)
            )
            val d = TransitRules.judge(card)
            if (d.keep) {
                all.add(q)
                // ★ v1.4.3：名单里但仍在运输中/待确认的件 —— 连续 3 轮还这样就自动移出名单（多半是误加）
                // ★★ v1.4.14：`picked:` 型记录**不参与**这条自动移出 —— 它是用户的明确标记，
                //   而拼多多侧常年停在「已签收，待确认收货」，一进这条链几轮就被打掉，
                //   用户看到的就是"删了又回来"。误删保护只对长按删除的记录成立。
                if (card.muted && !card.mutedPicked && key != null) mutedStillRunning.add(key)
            } else {
                dropLog.add("$src/${key.orEmpty()} → ${d.kind}：${d.reason}")
            }
        }
        Log.i("TransitRules", "裁决 ${mergedTransit.size} 张 ⇒ 在途 ${all.size} 张，剔除 ${dropLog.size} 张")
        for (line in dropLog) Log.i("TransitRules", "剔 $line")
        if (mutedStillRunning.isNotEmpty()) {
            val still = HashSet<String>()
            for (key in mutedStillRunning) {
                val n = (muteRunningRounds[key] ?: 0) + 1
                if (n >= 3) {
                    muteRunningRounds.remove(key)
                    try {
                        MuteStore.removeKey(this@MainActivity, key)
                    } catch (t: Throwable) {
                        SafeRun.w("MuteStore", t)
                    }
                    Log.i("MuteStore", "自动移出仍在途的项（连续多轮运输中）id=$key")
                } else {
                    muteRunningRounds[key] = n
                    still.add(key)
                }
            }
            for (k in muteRunningRounds.keys.toList()) {
                if (k !in still) muteRunningRounds.remove(k)
            }
        }
        // ★ v1.4.3：标签数字 = 过滤后实际显示的卡数（和眼睛看到的对得上）
        transitShownCount.clear()
        for (p in all) {
            val s = p.optString("src")
            transitShownCount[s] = (transitShownCount[s] ?: 0) + 1
        }
        // ★ vc697：只上屏当前平台标签的卡（合并/去重/裁决仍作用于全部来源，只在这里过一遍 src）
        val shown = ArrayList(all.filter { it.optString("src") == transitSrc })
        refreshTransitTabs()
        Log.i("TransitDebug", "testTransit=${testTransit.size} merged=${mergedTransit.size} all=${all.size} " +
                "pdd=${pddTransit.size} jd=${jdTransit.size} mi=${miTransit.size} " +
                "manual=${manualTransit.size} tb=${tbTransit.size} blacklist=${blacklistNow.size}")
        Log.i("TransitBuild", "最终 all=${all.size} 张：" + all.joinToString(",") {
            it.optString("orderSn") + "/" + it.optString("waybillNo")
        })
        if (shown.isEmpty()) {
            showStatusText(transitStatus)
            // ★ 秒显时空缓存（文件刚过期）别喊"暂无在途快递"—— 那是句结论，不是过程
            Log.i("TransitCache", "空列表：useCache=" + useCache + " 距进页=" +
                (System.currentTimeMillis() - transitEnterMs) + "ms")
            val stillFetching = !useCache && transitGot < transitExpect
            setStatusText(transitStatus,
                when {
                    stillFetching -> transitLoadingText()
                    useCache -> "正在获取…"
                    // ★ vc697：按平台分开后，"没有"要说清是哪个平台没有
                    else -> if (transitSrc.isEmpty()) getString(R.string.transit_empty)
                        else srcName(transitSrc) + "暂无在途快递"
                })
            return
        }
        // ★ vc234：真抓到的数据才落盘；vc270d：**全部源回来才算真完成**才落盘
        //   （增量渲染的中间态不覆盖缓存，免得把好缓存写成残缺的）
        if (allDone) {
            Log.i("TransitCache", "真实渲染：all=" + all.size + " 距进页=" +
                (System.currentTimeMillis() - transitEnterMs) + "ms")
            saveTransitCache()   // ★ vc270c：按源落盘（取件码口径）
        }
        if (useCache) {
            // 卡是旧数据，得说清楚正在刷新，别让用户以为这就是最新的。
            // ★ 这里**直接赋 text**，不走 setStatusText 的淡入淡出：进页这一瞬间要的是
            //   "立刻可读"，而那段动画的文字是在 withEndAction 里才写进去的，
            //   一旦紧接着有第二次 setStatusText 把它 cancel 掉，文案就永远不出现。
            Log.i("TransitCache", "秒显分支：all=" + all.size + " statusNull=" + (transitStatus == null))
            transitStatus?.let { tv ->
                tv.animate().cancel()
                tv.alpha = 1f
                tv.visibility = View.VISIBLE
                val t = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString(KEY_LAST_TRANSIT_TIME, "") ?: ""
                tv.text = if (t.isNotEmpty()) "刷新中 · 上次 $t" else "刷新中…"
            }
        } else if (allDone) {
            // ★ vc235：原来这里直接 GONE，结果秒显那行"刷新中 · 上次 xx:xx"只活不到 1 秒
            //   （抓取常常 0.5 秒就回来，见 2026-10-12 真机日志：进页 20.308 写字、
            //   20.761 就真实渲染了）—— 一闪而过等于看不见。
            //   取件码栏的口径是"更新于 HH:mm"常驻，这里对齐：刷新完换成这个，一直挂着。
            // ★ vc270d：只有**全部源回来**（allDone）才换"更新于"；
            //   增量渲染中间态保持上面写的"正在获取X物流"（不覆盖）。
            transitStatus?.let { tv ->
                showStatusText(tv)
                val t = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString(KEY_LAST_TRANSIT_TIME, "") ?: ""
                // ★ vc592：3 秒后自动淡出
                if (t.isNotEmpty()) showUpdateThenHide(tv, "更新于 $t")
            }
        }
        // ★ 错峰入场要在**全部条目建完**后统一启动，所以先收集再动画（边建边动会让后面的项错过起点）
        val cards = ArrayList<View>()
        // ★ vc607：一行总账 —— 出现"少一张卡"时，先看这行对不对得上
        Log.i("TransitRender", "准备渲染 tab=${transitSrc} shown=${shown.size} 张" +
                "（all=${all.size} blacklist=${blacklistNow.size}）")
        for (pkg in shown) {
            val src = pkg.optString("src")
            // ★★ v1.4.13：**黑名单与"该不该在在途"已在上面 [TransitRules.judge] 判过一次**，
            //   这里不再重复判。原来这里还有一道 isPickupMuted + 一次 PickupVerdict.decide
            //   （vc633 之后只打日志不生效），等于同一件事在三个地方判三遍 —— 每次改口径
            //   都要同时改三处，漏一处就是"改了这个那个又冒出来"。现在只有 TransitRules 一处。
            val goods = pkg.optString("goodsName")
                .ifEmpty { if (pkg.optBoolean("manualFailed")) "手动添加的单号" else "快递包裹" }
            val latest = pkg.optString("latest")
            val status = pkg.optString("statusText")
            // ★ vc253：订单列表接口自带的真运单号**首屏就能显示**（不必等物流页回来）。
            //   没有真运单号时（还没发货 / 老路Webview）才退回订单号。
            //   注意：卡片的身份键仍是 waybillNo（=订单号），别拿显示值去匹配。
            val no = pkg.optString("realWaybill").ifEmpty { pkg.optString("waybillNo") }

            // Linear 风格卡片：半透明白底 + 半透明白边框（深色下靠亮度阶梯表达层级，不用投影）
            val card = linearCard().apply {
                // ★ vc233：左右 14dp → 12dp（内容更宽），上下 12dp → 13dp（卡片稍微厚一点，
                //   不那么细长）。同志看的主要是展开后的大块，留白给得比第 4 栏大方些。
                // ★ vc586：留白统一到令牌（20/16）
            setPadding((UiTok.S20 * dp).toInt(), (UiTok.S16 * dp).toInt(),
                       (UiTok.S20 * dp).toInt(), (UiTok.S16 * dp).toInt())
            }
            // ★★ vc681（同志选 A 方案）：卡片头改成**两行**
            //     第一行 = 平台标签 + 商品名（横排，标签在前，商品名吃掉剩余宽度）
            //     第二行 = 最新物流状态
            //   上一版把标签单独占一行，右侧空一大截、还多占一行高度 ⇒ 合并掉。
            //   容器仍叫 head：点击展开 / 长按删除 / 按压反馈都挂在它上面，交互完全不变。
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
            }
            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topRow.addView(chip(srcName(src), srcColor(src)))
            // ★ vc699：商品长标题 → **小标签**（"皇帝柑" / "棉衣" / "鞋" / "柿子"…）。
            //   同志要求：卡上别再出现「【活动特价】加大加厚家用手提黑色」这种一长串描述标题。
            topRow.addView(TextView(this).apply {
                text = goodsTag(goods)
                textSize = UiTok.T13
                typeface = tfMedium
                setTextColor(cTextSecondary)
                gravity = Gravity.CENTER
                background = tagBg()
                setPadding((8 * dp).toInt(), (3 * dp).toInt(), (8 * dp).toInt(), (3 * dp).toInt())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = (6 * dp).toInt() })
            // 右侧弹性占位：标签靠左，不被拉伸成整行
            topRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            head.addView(topRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            // ★ vc591：卡片那行轨迹也取**时间最新**的一条（不再盲信源给的 latest 顺序）
            val traceRaw = pkg.optString("traceLines")
            val latestLine = if (traceRaw.isNotBlank()) {
                val rowsT = traceRaw.split("\n").map { it.trim() }.filter { it.isNotBlank() }
                val pick = rowsT.maxByOrNull { r ->
                    TRACE_TIME_HEAD.find(r)?.groupValues?.get(1) ?: ""
                } ?: rowsT.firstOrNull().orEmpty()
                // ★ vc680：卡片只留"日期之后"的描述（状态词和日期时间不占这行）
                // ★ vc682：再过一遍广告清洗（与展开详情同一条链）
                cleanTraceText(afterTraceDate(stripTraceTime(pick))).replace("\n", " ")
            } else {
                cleanTraceText(afterTraceDate(stripTraceTime(latest))).replace("\n", " ")
            }
            if (latestLine.isNotEmpty() && !pkg.optBoolean("manualFailed")) {
                // ★ vc681：状态行**独占整行、最多两行** —— 挂到 head 上（不随标签缩进），
                //   一行截断太狠、看不到关键动作（同志要求）。
                head.addView(TextView(this).apply {
                    text = latestLine
                    textSize = UiTok.T13
                    setTextColor(cTextSecondary)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (4 * dp).toInt() })
            } else if (pkg.optBoolean("manualFailed")) {
                // ★ vc679：右侧「在途中」删掉后，「查不到」落到状态那一行（同志要求保留这条信息）
                head.addView(TextView(this).apply {
                    text = "查不到"
                    textSize = UiTok.T13
                    setTextColor(cTextSecondary)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (4 * dp).toInt() })
            }

            // ★ vc692：信息行 —— 快递公司 · 物流单号 · 预计到达（不展开就能一眼看到）
            run {
                val bits = ArrayList<String>()
                courierTagOf(pkg)?.let { bits.add(it) }
                val wbReal = pkg.optString("realWaybill").trim()
                val wbNo = pkg.optString("waybillNo").trim()
                when {
                    wbReal.isNotEmpty() -> bits.add(wbReal)
                    wbNo.isNotEmpty() -> bits.add(wbNo)
                }
                etaForCard(latest, pkg.optString("traceLines"), status, pkg.optString("etaHint"))
                    .takeIf { it.isNotEmpty() }?.let { bits.add(it) }
                val waybillForCopy = wbReal.ifEmpty { wbNo }
                if (bits.isNotEmpty()) {
                    val infoTv = TextView(this).apply {
                        text = bits.joinToString(" · ")
                        textSize = UiTok.T12
                        setTextColor(cTextSecondary)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                    val infoRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(infoTv, LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                        ))
                        // ★ vc694：物流单号复制按钮（用户要求：单号旁边给个复制键）
                        if (waybillForCopy.isNotEmpty()) {
                            val cpBtn = chip("复制单号", ACCENT_HEX)
                            cpBtn.isClickable = true
                            cpBtn.setOnClickListener { copyWaybill(waybillForCopy) }
                            addView(cpBtn, LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { leftMargin = (8 * dp).toInt() })
                        }
                    }
                    head.addView(infoRow, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (4 * dp).toInt() })
                }
            }
            card.addView(head)

            // 展开区：精确物流（默认 GONE；展开时把下面的卡片往下挤 —— LinearLayout 天然行为）
            val detail = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(0, (10 * dp).toInt(), 0, 0)
            }
            fun dline(label: String, content: String) {
                if (content.isEmpty()) return
                detail.addView(TextView(this).apply {
                    text = "$label：$content"
                    textSize = UiTok.T13
                    setTextColor(cTextSecondary)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (4 * dp).toInt() })
            }
            // ★ vc219 完整物流流程：增强拿到的全部轨迹节点（快递100 或 PDD 物流页，时间倒序，
            //   首行=最新）。有全程块就不再重复显示单行「最新轨迹」，点开卡片即可看整个流程。
            val fullTrace = pkg.optString("traceLines")
            if (fullTrace.isNotEmpty()) {
                val steps = fullTrace.split("\n").map { it.trim() }.filter { it.isNotBlank() }
                detail.addView(TextView(this).apply {
                    text = "物流流程（${steps.size} 条）"
                    textSize = 13f
                    typeface = tfMedium
                    setTextColor(cTextPrimary)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (12 * dp).toInt() })
                // ★ vc590：**倒序呈现（最新在最上）**。
                //   各源给的行序并不统一（顺丰正序、菜鸟/拼多多多为倒序）⇒ 这里统一按时间戳**降序**排，
                //   再按"第一条=最新"渲染。这样无论源怎么给，用户看到的都是标准物流时间线。
                fun timeKeyOf(x: String): String {
                    val mm = TRACE_TIME_HEAD.find(x)
                    return mm?.groupValues?.get(1) ?: ""
                }
                val orderedSteps = if (steps.all { timeKeyOf(it).isNotEmpty() }) {
                    steps.sortedByDescending { timeKeyOf(it) }
                } else {
                    steps   // 有没有时间的行 ⇒ 保持原序，避免无时间的行被甩到最后
                }
                orderedSteps.forEachIndexed { idx, raw ->
                    detail.addView(
                        traceStep(raw, idx == 0, idx == orderedSteps.size - 1),
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = (9 * dp).toInt() })
                }
            } else {
                dline("最新轨迹", latest)
            }
            dline("状态", status)
            dline("单号", no)
            dline("快递公司", courierTagOf(pkg).orEmpty())
            dline("预计到达", etaForCard(latest, pkg.optString("traceLines"), status, pkg.optString("etaHint")))
            card.addView(detail)

            head.setOnClickListener {
                // ★ vc232 一次只展开一张：先把上一张收起来，再开这一张。
                //   一张展开就有 1400+px，两张同开会把彼此顶到很远的地方。
                val current = expandedTransitDetail
                val opening = !(detail.visibility == View.VISIBLE)
                if (current != null && current !== detail) animateHeight(current, false)
                animateHeight(detail, opening)
                expandedTransitDetail = if (opening) detail else null
                // ★ vc708：记住"展开的是哪一张"（按身份键，重画后据此恢复）
                expandedTransitKey = if (opening) "$src:$no" else null
            }
            // ★ 长按 = 是否删除（同志 2026-09-21 要求）。
            //   手动添加的单号**真删**（从 SP 列表移除，持久生效）；
            //   其它来源的件删不掉 —— 它们是平台数据，下次刷新照样回来，
            //   与其做个"删了又回来"的假删除，不如如实告诉用户去哪管。
            head.setOnLongClickListener {
                // ★ vc553：**删除 = 加入黑名单**（任何来源都可删）。
                //   删除后：从途中/取件码的所有渲染中消失，且后续抓取到也不显示；
                //   可在「设置 → 其他 → 黑名单」里移出。
                val isManual = src == "manual"
                val bkey = "$src:$no"
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle(if (isManual) "删除这个单号" else "删除并加入黑名单")
                    .setMessage(
                        "$goods\n单号：$no\n\n" +
                                "删除后不再出现在「在途快递」和「取件码」，\n" +
                                "以后即使平台又返回这个件也不会显示。\n" +
                                "可在「设置 → 其他 → 黑名单」里恢复。"
                    )
                    .setPositiveButton("删除") { _, _ ->
                        if (isManual) {
                            val kept = manualWaybills(this@MainActivity).filter { it.first != no }
                            saveManualWaybills(this@MainActivity, kept)
                            manualTransit.removeAll { it.optString("waybillNo") == no }
                        }
                        // ★★ v1.4.9：按这件包裹的**全部身份写一条记录**（订单号/运单号/取件码/驿站/标题），
                        //   不再往名单里塞三个互不相干的扁平键 —— 取件码会被复用，那样会误伤以后的新件。
                        mutePackage(src, pkg)
                        val mutedNow = pickupMutedAll()
                        fun drop(list: ArrayList<JSONObject>) {
                            list.removeAll { q -> isPickupMuted(q.optString("src"), q, mutedNow) }
                        }
                        drop(pddPkgs); drop(jdPkgs); drop(cainiaoPkgs)
                        drop(miPkgs); drop(manualPkgs)
                        tbTransit.removeAll { q -> isPickupMuted("tb", q, mutedNow) }
                        appToast(this@MainActivity, "已删除（不再显示）")
                        if (transitLayer != null) renderInTransit()
                        if (pickupLayer != null) renderMerged()
                    }
                    .setNegativeButton("取消", null)
                    .showAnimated()
                true     // ★ 吃掉长按，否则松手还会触发 click 展开详情
            }
            applyPress(head)
            list.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * dp).toInt() })
            cards.add(card)
            // ★ vc708：这张卡刷新前就是展开的 ⇒ 重画后自动展开回来（走与用户点击同一条路）
            if (expandedTransitKey != null && expandedTransitKey == "$src:$no") {
                head.post { head.performClick() }
            }
        }
        // 错峰入场：每项延迟 30ms、淡入 + 上移 12dp（Linear 的列表节奏）
        // ★★ vc708：**只在首次上屏播一次**。以前每次刷新都重播，一列卡片"刷"地重新淡入 ——
        //   这是"刷新就闪"观感最大的来源；后续刷新一律不动画，只让变化的卡片静默替换。
        if (!transitPainted) {
            transitPainted = true
            staggerIn(cards)
        }
    }

    /** 预计剩余天数：只从轨迹文本里抠"预计X日/今天/明天"，抠不到就老实显示"在途中"，不编数字 */
    /**
     * 在途快递层（第 5 栏）。
     *
     * ★★ 刻意写成「属性 + 自定义 setter」而不是裸字段：
     *    **"有没有层"是"有没有东西可返回"的唯一真相**，而它只由这四个字段的赋值决定。
     *    把同步挂在 setter 上 ⇒ 二十多个开/关点一个都不用改、也永远不会漏。
     *    漏一处的后果很具体：**返回回调的开关直接决定系统播不播「窗口缩回桌面图标」的动画**。
     */
    private var transitLayerField: LinearLayout? = null
    private var transitLayer: LinearLayout?
        get() = transitLayerField
        set(value) {
            transitLayerField = value
            syncBackEnabled()
        }

    private var transitList: LinearLayout? = null

    /** ★ vc264m：第 5 栏的下拉刷新控件（null = 页面没开/已关） */
    private var transitSwipe: androidx.swiperefreshlayout.widget.SwipeRefreshLayout? = null
    private var transitStatus: TextView? = null
    /** ★ vc697：平台标签条（在途快递按平台分开加载、左右滑动切换） */
    private var transitTabRow: LinearLayout? = null
    private var transitTabsScroll: android.widget.HorizontalScrollView? = null
    /** 当前选中的平台："pdd" \/ "jd" \/ "tb" \/ "mi" \/ "manual"（空串 = 还没有可用平台） */
    private var transitSrc: String = ""
    /** 本轮已经抓过的平台 —— 切回已抓过的标签就不重复发请求 */
    private val transitRoundLoaded = HashSet<String>()
    /** 进页时刻（诊断用：看真实抓取多久回来） */
    private var transitEnterMs: Long = 0L
    /** 各源在「过滤后实际显示」的卡数（标签数字用它，和看到的卡片数对得上） */
    private val transitShownCount = HashMap<String, Int>()
    /** 名单里每条「连续仍在运输中」的轮次（连续满 3 轮 ⇒ 自动移出，防误加永久卡死） */
    private val muteRunningRounds = HashMap<String, Int>()
    private var transitGot = 0
    /**
     * vc274：第 5 栏的"轮次代数"——每开一轮 +1。
     * 与第 4 栏的 pickupRoundGen 同款：闸门兜底计时器（onTransitGate）带代数，
     * 开新一轮后旧计时器作废，防止上一轮的兜底把新轮的 pending 提前排空。
     */
    private var transitRoundGen = 0
    private var transitExpect = 0
    /** 在途栏"还没回来的源"队列：加载文案按它显示「正在获取X物流」（与第 4 栏同款体验） */
    private val transitPendings = ArrayList<String>()
    private var transitTimeout: Runnable? = null
    private var pddTransit: ArrayList<JSONObject> = ArrayList()

    // ---------- vc270 淘宝在途源（第5栏） ----------
    /** 淘宝在途卡片（身份键 waybillNo=bizOrderId；真实运单号放 realWaybill） */
    private var tbTransit: ArrayList<JSONObject> = ArrayList()
    /** 整链互斥：订单页 16s + 详情页串行，跑完前不再叠加新一轮 */
    private var tbTransitBusy = false
    /** 当前 webLayer 是否为本链路打开（收尾时才允许 closeWeb，避免误关用户自己开的页面） */
    private var tbLayerOpen = false
    /** vc270o：正在加载中的淘宝详情页数量（并行补轨迹用；0 = 可以收尾） */
    private var tbDetailInflight = 0
    /** vc270o：详情页并发上限 —— 详情页是整个淘宝在途链路里最慢的一环，串行等于等两倍时间 */
    private val TB_DETAIL_PARALLEL = 2
    /**
     * ★ vc311：本轮淘宝详情里判为**终态**（已签收/已取件/已取走…）的单 → 状态文案。
     *
     * [buildTbCard] 对终态单一律 **return null 不出卡**（历史口径，第 5 栏不该显示签收件）
     * ⇒ 它们不进 [tbTransit]。把它们记在这里，供两件事用：
     *   · 收尾时区分「全取走了 ⇒ 0 张卡」与「抽不到数据 ⇒ 0 张卡」（配 [tbTerminalHits]）；
     *   · 第 5 栏与第 4 栏分流、以及 debug_tb_picked 模拟终态。
     * ★ vc316 起**不再**拿它去打「已取件」标记 —— 自动标记整条链已删，灰标只认长按手动。
     * 每轮淘宝在途开跑时清空（[fetchTaobaoTransit]）。
     */
    private val tbPickedNow = HashMap<String, String>()
    /**
     * ★ vc311：本轮**成功判出终态**的单数（一单只计一次）。
     * 用途：把「全取走了 ⇒ 0 张卡」与「页面抽不到数据 ⇒ 0 张卡」区分开 ——
     * 前者是成功（要照常收尾落缓存），后者才是中断（保留旧卡）。
     */
    private var tbTerminalHits = 0
    /**
     * vc275：便宜快路（刮页面数据 blob，见 TbTransitProbe.HARVEST_ULTRON_JS）的最大轮数。
     * 1.5s 起每 1s 一轮 ⇒ 覆盖 1.5~6.5s；之后转全量刮兜底（2s 步进，最多 2 轮）。
     */
    private val TB_ULTRON_ATTEMPTS = 6
    /**
     * vc273：渠道“上一轮还在跑”的闸门回复（PddChannel/JdChannel/MiShopChannel 都有）——
     * **这不是失败，是“正在获取中”**。快速开关取件码页时会撞上：旧代码把它当错误显示
     * “获取失败：正在抓取中”，数据到手后状态文字又没人更新 ⇒ 同志报的 bug。
     * 处理：不记错误、不计数、不用空列表覆盖已有数据，等上一轮的回调过来自然收尾。
     */
    private val PICKUP_GATE_MSG = "正在抓取中"
    /** 隐形旁听时盖在网页层上吃掉全部触摸的透明层（alpha 0.02 的页面不能挡用户操作） */
    private var tbTouchEater: View? = null
    /** vc270g：本会话见过的拼多多在途卡（手动刷新缺单时补回用；仅内存，48h 上限） */
    private val pddLastSeenBare = LinkedHashMap<String, JSONObject>()
    /** vc270j：驿站包裹探针激活中（mdkd 页叠加旁听钩子用） */
    private var mdkdPkgProbeActive = false

    // ---------- 途中栏拼多多轨迹增强（vc214） ----------
    /** 单个订单增强出的轨迹（真实运单号 + 最新轨迹文案 + vc219 全程轨迹行 \n 连接）。
     *  ★ vc255：TTL 6 小时，且落盘到 transit_cache.json 的 traces 字段（不再只是 15 分钟内存缓存） */
    private class TransitTrace(
        val waybillNo: String, val latest: String, val at: Long,
        val lines: String = ""
    )
    @Volatile private var pddTransitTraceCache = HashMap<String, TransitTrace>()

    /** ★ vc255：transit_cache.json 的读写锁（列表落盘与轨迹落盘会互相读改写同一文件） */
    private val transitCacheLock = Any()
    /** 增强任务代际令牌：第 5 栏刷新/关闭 ⇒ 旧任务的回调全部作废 */
    @Volatile private var transitEnhanceGen = 0

    // ★ vc218 批次互踩防线（03:17 真机日志：开层批次 WebView 未完 + 手动刷新批次并发 ⇒
    //   新批次全被 PddTraceChannel「正在抓取中」挡废、旧批次被代际令牌作废 ⇒ 谁都没跑完）。
    //   上一批次开始后 120s 内不再发新批次（单例通道一次全程 ~90s）；回调作废路径不清零 ⇒ 靠时限兜底。
    @Volatile private var transitEnhanceBusyStart = 0L

    /** ★ vc342：本轮淘宝在途属于哪一代 —— 迟到的旧代回调不许结算新一代的账 */
    @Volatile private var tbTransitRoundGen = -1

    /** ★ vc342：批次进行中又来了新请求 ⇒ 记一笔，本批结束后自动补跑（旧实现是整批丢掉） */
    @Volatile private var transitEnhanceAgain = false

    /**
     * ★ vc264p：**测试物流**列表（第 5 栏用）。
     * 默认恒为空 ⇒ 生产零影响；只有 `--ez debug_seed_transit3 true` 才会填充。
     * 为什么不直接塞进缓存：抓取完成后的那一趟渲染（cache=null）**不合并缓存**
     * ⇒ 灌进缓存的数据会被"抓取回来的空结果"冲掉（04:57 实测踩到）。
     */
    private val testTransit = ArrayList<JSONObject>()

    private var jdTransit: ArrayList<JSONObject> = ArrayList()
    private var miTransit: ArrayList<JSONObject> = ArrayList()
    private var miErr: String? = null
    private var cainiaoNeedLogin = false
    private var cainiaoErr: String? = null
    private var cainiaoPkgs: ArrayList<JSONObject> = ArrayList()
    private var pddPkgs: ArrayList<JSONObject> = ArrayList()
    private var jdPkgs: ArrayList<JSONObject> = ArrayList()
    /** 小米：走商城 H5，原始捕获留着让第 4/5 栏按各自口径解析 */
    private var miPkgs: ArrayList<JSONObject> = ArrayList()
    private var miCaptures: List<Pair<String, String>> = emptyList()
    /** 手动添加的运单号（快递100 增强）：用户自己粘单号，无登录依赖 */
    private var manualPkgs: ArrayList<JSONObject> = ArrayList()
    private var manualTransit: ArrayList<JSONObject> = ArrayList()

    // ── vc279：无码件「已到达<地址>」──
    /** 假的无码到达件单号（debug_fake_arrived 灌数据用，重启不带该参数即消失） */
    private val fakeArrivedNo = "TESTARRIVED"
    /** onCreate 读一次（调试参数只在 onCreate 读，铁律）；开着就每轮补一张假卡 */
    private var fakeArrived = false

    /** 平台标签配色（第 4/5 栏的 chip 共用） */
    private fun srcColor(src: String): String = when (src) {
        "jd" -> "#E1251B"
        "mi" -> "#FF6900"
        "manual" -> "#BA7517"
        "pdd" -> "#E02E24"
        "cainiao" -> "#0A9E7B"
        "tb" -> "#FF5000"
        "ntf" -> "#7A5AF8"
        else -> "#666666"
    }

    private var pickupIdentity = false
    private var statusBarTop = 0
    private var pddTopAction: TextView? = null
    /** 所有"顶栏"引用：状态栏 inset 变化时要一起补 padding（动态层收不到首次分发） */
    private val liveTopBars = ArrayList<View>()
    private val dp: Float get() = resources.displayMetrics.density

    companion object {
        // ★ vc354/vc669：淘宝/菜鸟标准物流状态枚举。
        //   枚举码不是完整业务结论：实测 AGENT_SIGN +「待取件」+ seq=900
        //   仍是待取件，必须让详情文案/序号覆盖通用的 AGENT_SIGN=已取走映射。
        private const val TB_EN_UNKNOWN = 0
        private const val TB_EN_PICKUP = 1
        private const val TB_EN_TAKEN = 2
        private const val TB_EN_TRANSIT = 3
        /**
         * 第 1 栏「淘宝身份码」。
         *
         * ★★ vc264o（2026-10-15 04:42 实测修）：**必须带 `x-ssr=true`**
         *   不带它 ⇒ 页面走前端渲染（SPA），身份码数据要靠页面里的 JS 自己请求，
         *   实测拿到的是"标题在、内容区只剩一串零宽空格"（= 数据没回来）⇒ 观感就是空白页。
         *   带上它 ⇒ 淘宝走**服务端直出（SSR）**，身份码直接塞进 HTML ⇒ 打开就有码。
         *   （这个地址是从同志分享的「菜鸟驿站频道」链接里点「身份码」跳出来的真实地址：
         *    短链 m.tb.cn/h.jcU5LBJ → 频道页 1100333/... → 本地址 1011717/...identity-code?...）
         */
        private const val TAOBAO_URL =
            "https://pages-fast.m.taobao.com/wow/z/uniapp/1011717/last-mile-fe/end-collect-platform/identity-code?spm=cn_end_pick_up.xiaoyuan.xiaoyuan.5&x-ssr=true"


        /** ★ vc383：在「菜鸟驿站」首页点底部「身份码」tab（照淘宝 App 的真实路径） */
        private val CLICK_IDENTITY_TAB_JS = """
            (function(){
              try{
                var out={click:'notfound',hrefs:[],inner:[],tabs:[]};
                var els=document.querySelectorAll('div,span,a,li,p');
                for(var i=0;i<els.length;i++){
                  var t=(els[i].innerText||'').trim();
                  if(t.length<=6 && t.indexOf('身份码')>=0){ els[i].click(); out.click='clicked'; break; }
                }
                if(out.click==='notfound'){
                  var anc=document.querySelector('a[href*=\x22identity-code\x22]');
                  if(anc){ anc.click(); out.click='clicked-anchor'; }
                }
                var as=document.querySelectorAll('a[href]');
                for(var j=0;j<as.length && out.hrefs.length<12;j++){
                  var h=as[j].href||'';
                  if(/identity/i.test(h)) out.hrefs.push(h.slice(0,300));
                }
                var html=document.documentElement?document.documentElement.innerHTML:'';
                var re=/identity-code[^"'<>\\s]{0,260}/g, mm;
                while((mm=re.exec(html))!==null && out.inner.length<6){ out.inner.push(mm[0]); }
                return JSON.stringify(out);
              }catch(e){ return 'err:'+e; }
            })()
        """.trimIndent().replace("\n", "")
        private const val CAINIAO_URL =
            "https://market.m.taobao.com/app/cn-yz/multi-activity/authCode.html?bizEntry=ALIPAY_GUOGUO"

        /**
         * ★ vc360：身份码页诊断探针 —— 这条链路以前一行日志都不打，
         *   出了"空白/刷不出"只能靠猜。加载后把「最终网址 / 标题 / 正文字数 /
         *   是否出现登录字样 / 正文前 60 字」记进日志，一条就能分清：
         *   没登录（login=true 且 n 很小）、账号没码（n 小但无登录字样）、还是加载失败（u 变了）。
         */
        private val IDENTITY_DIAG_JS = """
            (function(){
              try{
                var bd=document.body;
                var raw=bd?String(bd.innerText||''):'';
                var t=raw.replace(/\u200b/g,'').replace(/\s+/g,' ').trim();
                var m=t.replace(/\\u200b/g,'').replace(/\s+/g,'');
                var html=document.documentElement?document.documentElement.innerHTML:'';
                var cv=document.querySelectorAll('canvas').length;
                var ci=document.querySelectorAll('img[src^=\x22data:image\x22]').length;
                var cw=document.querySelectorAll('[class*=barcode],[class*=qr],[class*=codeImg],[class*=identity]').length;
                var f={};
                ['校园','驿站','绑定','开通','暂无','审核','认证','身份码','登录'].forEach(function(k){f[k]=(t.indexOf(k)>=0);});
                return JSON.stringify({u:location.href.slice(0,120),ti:(document.title||''),n:t.length,m:m.length,
                  txt:m.slice(0,80),cv:cv,ci:ci,cw:cw,code:/identityCode|qrCode|barcode/i.test(html),f:f});
              }catch(e){return JSON.stringify({e:String(e)});}
            })()
        """.trimIndent().replace("\n", "")
        /** 隐形数据 WebView 加载的驿站首页：自带 lib-mtop + 登录 cookie，是 mtop 查询的宿主页面 */
        private const val HOME_URL =
            "https://pages-fast.m.taobao.com/wow/z/uniapp/1011717/last-mile-fe/end-collect-platform/home-ssr-index"

        private const val PDD_PACKAGE = "com.xunmeng.pinduoduo"

        /**
         * 拼多多登录专用 UA：换成一个与用户真实机型不同的常见机型。
         * 目的见 [openWeb] 里 loginMode == "pdd" 分支的注释 —— 配合清 storage，把这次登录伪装成"另一台设备"。
         */
        /** 电脑版 Chrome 身份（拼多多登录页用：页面原生提供「扫码 + 手机号」两种登录；也是 --es debug_ua desktop 的载体） */
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        /** ★ vc266：拼多多登录页走【电脑端通道】（桌面 UA）——不再扮演"另一台手机"。
         *   2026-10-15 实测：清空全部登录态后纯扫码登录，身份码/订单/物流三关全过，且不顶掉拼多多 App。 */
        private const val PDD_LOGIN_UA = DESKTOP_UA

        /** 调试：--es debug_ua desktop ⇒ WebView 强制用电脑版 Chrome 身份（最后生效，覆盖所有 loginMode 的 UA） */
        @Volatile var DEBUG_DESKTOP_UA = false

    /** ★ vc497：录下页面自己发的 mtop 请求（api+data），用于纯 HTTP 重放（B1 参数固化） */
    @Volatile var DEBUG_TB_REC = false
    /** ★ vc455：EMS ump 站会按 UA 判断容器；这里准备微信/支付宝两种，用于试探渲染 */
    @Volatile var DEBUG_CONTAINER_UA: String? = null

    private const val UA_WECHAT =
        "Mozilla/5.0 (Linux; Android 14; PKX110 Build/UKQ1.230924.001; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/122.0.6261.120 Mobile Safari/537.36 " +
            "XWEB/1220099 MMWEBSDK/20240404 MicroMessenger/8.0.49.2600(0x28003133) " +
            "WeChat/arm64 Weixin NetType/WIFI Language/zh_CN ABI/arm64"

    private const val UA_ALIPAY =
        "Mozilla/5.0 (Linux; U; Android 14; zh-CN; PKX110 Build/UKQ1.230924.001) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/100.0.4896.58 UWS/4.0.0 Mobile Safari/537.36 " +
            "AliApp(AP/10.5.90.8000) AlipayClient/10.5.90.8000 Language/zh-Hans useStatusBar/true"

        /**
         * ★★ vc264r：拼多多登录**保持设备身份**（不再"伪装成另一台设备"）。
         *
         * 为什么改：拼多多顶号弹窗自己写明了 —— "在其他设备上**通过短信验证码登录**，
         * 当前设备已退出登录"（05:25 实测）。而我们**每次登录都换 UA + 清 cookie + 清 WebStorage**
         * = 每次都制造一台"新设备" ⇒ **每次登录都顶一次**。
         * 参考商业软件「多多开」的做法（每个账号固定一套独立设备指纹）⇒ 反过来做：
         * **保持 UA 与会话不变**，让服务端认为"还是上次那台设备"，
         * 顶号频率应从"每次都顶"降到"会话过期时偶发"。
         * ⚠️ 若实测无效/更糟 ⇒ 把这个常量改回 false 即可（一次构建的事）。
         */
        private const val PDD_KEEP_IDENTITY = true

        private const val PREFS = "idcode_prefs"
        // vc284：拼多多登录页强制提示弹窗「以后不再提示」的开关
        private const val KEY_PDD_LOGIN_NOTICE_OFF = "pdd_login_notice_off"
        private const val KEY_STATION_CODE = "pdd_station_code"
        private const val KEY_LAST_PKG_COUNT = "last_pkg_count"
        private const val KEY_LAST_PKG_TIME = "last_pkg_time"
        private const val KEY_LAST_TRANSIT_TIME = "last_transit_time"
        /** ★ vc255：上一次真正打 order_list_v4 的时刻（进页间隔闸用，见 ORDER_LIST_MIN_INTERVAL_MS） */
        private const val KEY_LAST_ORDER_LIST = "last_order_list_ms"
    /** 用户在取件码页长按删除过的单（键 = src:单号）—— 不再自动补回 */
    private const val KEY_PICKUP_MUTED = "pickup_muted"
        private const val KEY_THEME_MODE = "theme_mode"
    /** 侧滑跟手动画开关（预测性返回时让设置页跟着手指走），默认开 */
    private const val KEY_PREDICTIVE = "predictive_back"
        private const val KEY_DARK_START = "dark_start"
        private const val KEY_DARK_END = "dark_end"
        private const val KEY_REOPEN_SETTINGS = "reopen_settings"
        private const val REQ_FILE_CHOOSER = 1001

    /** 通知权限申请（Android 13+ 的 POST_NOTIFICATIONS） */
    private const val REQ_NOTIF = 1002

    /** 导出日志的「另存为」对话框（vc268，同志 10-15 要求） */
    private const val REQ_EXPORT_LOGS = 1003

    /** 组件自定义图片：系统相册选图（vc294，同志 10-16 要求） */
    private const val REQ_WIDGET_IMG = 1004

        /**
         * mtop 查询脚本 —— ★ vc667：**统一到 [CaiNiaoChannel.QUERY_JS]** 这一份。
         *
         * 以前这里和 `CaiNiaoChannel` 各存一份一模一样的字符串，改一边忘另一边就会
         * "前台能查后台查不到"。现在只有一份实现：先按老参数查，空则按手机号跨驿站再查
         * （外部用户 2026-11-01 报"淘宝取件码抓不到"，根因就是只带 source/traceId，
         *   服务端没有"谁的件"这个上下文，永远回 allPkgSize:0）。
         */
        private val QUERY_JS: String get() = CaiNiaoChannel.QUERY_JS

        /** 快递公司代号 → 中文名（接口返回的 upCpCode 是代号） */
        private val CP_NAMES = mapOf(
            "SF" to "顺丰", "ZTO" to "中通", "STO" to "申通", "YTO" to "圆通",
            "YUNDA" to "韵达", "JD" to "京东", "HTKY" to "百世", "EMS" to "EMS",
            "POSTB" to "邮政", "CN7000001" to "菜鸟速递", "CN7000002" to "菜鸟速递",
            "JT" to "极兔", "J&T" to "极兔", "DBL" to "德邦", "ZNML" to "智能闪送"
        )
    }

    // ---------- 主题 ----------

    private fun isDark(): Boolean {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getString(KEY_THEME_MODE, "system") != "scheduled") {
            return (resources.configuration.uiMode and 0x30) == 0x20
        }
        return inDarkWindow(
            prefs.getString(KEY_DARK_START, "22:00") ?: "22:00",
            prefs.getString(KEY_DARK_END, "07:00") ?: "07:00"
        )
    }

    /**
     * ★ 系统栏图标颜色必须跟着 **App 自己的主题**走（同志 2026-09-22 反馈：
     *   浅色模式下主页/设置页顶到状态栏，状态栏的时间看不清）。
     *
     * ## 根因
     * 主题 AppTheme 里写的是 windowLightStatusBar=false（**白色图标**，为深色背景准备的），
     * 而根容器 root 会刷成 cBg（浅色模式是近白 #f7f8f8）并**顶到状态栏**
     * ⇒ 白色图标压在近白背景上，等于隐形。
     *
     * ## 修法（两件一起做才稳）
     * ① 状态栏背景设成页面背景色 cBg —— 因为不确定状态栏区域最终显示的是
     *    系统画的背景还是内容顶上来的顶栏，统一成「页面自己的颜色」后两者结果一致；
     * ② 浅色背景用**深色图标**（isAppearanceLightStatusBars = true），深色背景反之。
     *    导航栏同理（它的 navigationBarColor 也是白的，浅色模式下一样会糊）。
     *
     * minSdk 31 ⇒ WindowCompat 这套 API 直接可用，不需要 SYSTEM_UI_FLAG 老办法兜底。
     */
    private fun applySystemBarIcons() {
        // ① 状态栏底色交给页面自己（见上面注释）
        window.statusBarColor = cBg
        // ② 图标颜色跟随主题
        val lightBars = !isDark()      // 背景浅 ⇒ 用深色图标
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)?.apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }
    }

    /** "22:30" → 1350（分钟数） */
    private fun mins(hhmm: String): Int {
        val p = hhmm.split(":")
        return (p.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    private fun inDarkWindow(start: String, end: String): Boolean {
        val cal = java.util.Calendar.getInstance()
        val cur = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val s = mins(start)
        val e = mins(end)
        if (s == e) return false
        // 跨零点（如 22:00~07:00）要分两段判断
        return if (s < e) cur in s until e else (cur >= s || cur < e)
    }

    /** 凝光蓝的十六进制写法（[chip] / [pill] 这类只收 String 的老接口用） */
    private val ACCENT_HEX: String get() = if (isDark()) "#1A8CFF" else "#0080FF"

    // ---------- 主题：ColorOS 17「凝光」色板（v1.4.19） ----------
    // 规格取自 ColorOS 17 的 Compose 复刻库 COUI（CouiPresets.Light / Dark），就是这台手机自己的那套色：
    //  · 昼：底 #F0F1F2 + 纯白卡 + 强调 #0080FF；文字 90% / 54% / 38% 黑，分割线 12%
    //  · 夜：底**纯黑** + 10% 白卡（叠上去正好 #1A1A1A）+ 强调 #1A8CFF；文字 90/54/38% 白，分割线 20%
    //  · 圆角一律超椭圆（[SquircleDrawable]），不是四分之一圆弧
    // 旧 Linear 色板（靛紫 #5e6ad2 + 近黑画布）已整体退役，别再往回改。
    private val cBg: Int
        get() = UiTok.pageBg(isDark())
    /** 卡片填充顶部（v2 半融入：92% 白） */
    private val cCardTop: Int
        get() = UiTok.cardBgTop(isDark())
    /** 卡片填充底部（v2 半融入：84% 白，环境光透出来） */
    private val cCardBottom: Int
        get() = UiTok.cardBgBottom(isDark())
    /** 兼容：需要单一色值时给顶部色 */
    private val cCard: Int
        get() = cCardTop

    /** 面板层（顶栏 / 抽屉底）：夜景 #1E1E1E，比页面纯黑亮一档 */
    private val cPanel: Int
        get() = UiTok.panelBg(isDark())
    private val cTextPrimary: Int
        get() = if (isDark()) 0xE6FFFFFF.toInt() else 0xE6000000.toInt()
    private val cTextSecondary: Int
        get() = if (isDark()) 0x8AFFFFFF.toInt() else 0x8A000000.toInt()
    private val cTextTertiary: Int
        get() = if (isDark()) 0x70FFFFFF else 0x70000000
    private val cTextFaint: Int
        get() = if (isDark()) 0x45FFFFFF else 0x45000000

    /** 链接蓝：夜里用 accentOnSurface 的浅一档 #5C9DFF，白天用深一档 #0070E0 */
    private val cLink: Int
        get() = Color.parseColor(if (isDark()) "#5C9DFF" else "#0070E0")
    private val cRipple: Int
        get() = Color.parseColor(if (isDark()) "#33FFFFFF" else "#1F000000")

    /** 卡片/输入框边框：凝光的边几乎看不见，只负责"有个边"，别再加深 */
    private val cBorder: Int
        get() = UiTok.cardStroke(isDark())

    /** 分隔线：昼 12% 黑 / 夜 20% 白 */
    private val cDivider: Int
        get() = UiTok.dividerColor(isDark())

    /** 强调色（凝光蓝）：只用于 CTA / 选中 / 链接，不做装饰 */
    private val cAccent: Int
        get() = Color.parseColor(if (isDark()) "#1A8CFF" else "#0080FF")

    /**
     * 超椭圆背景（全 App 唯一入口）。
     * [radiusDp] 用 UiTok.R*；[fill] 纯色，或再给 [fillBottom] 做垂直凝光（只有主按钮这么干）。
     * 深色下 [contour] 打开 → 顶部一条亮线，纯黑底上全靠它把卡片边"提"起来。
     */
    private fun sq(
        radiusDp: Int,
        fill: Int,
        fillBottom: Int = fill,
        stroke: Int = Color.TRANSPARENT,
        strokeDp: Float = 0f,
        contour: Boolean = false,
    ): SquircleDrawable = SquircleDrawable(
        radiusDp * dp, fill, fillBottom, stroke, strokeDp * dp, contour
    )

    /**
     * ★ v2 软阴影：框架 elevation + 超椭圆 outline —— 投影画在 view 边界外，不会被自身裁掉。
     * 昼 6dp（v2 box-shadow 0 8px 26px -18px 的观感）、夜 2dp（纯黑上投影几乎看不见，少用）。
     * 阴影色走 [UiTok.cardShadow]（昼偏蓝的墨色 rgba(15,26,44,.42)，不是纯黑）。
     */
    private fun cardElevation(v: android.view.View, radiusDp: Int) {
        val dark = isDark()
        val r = radiusDp * dp
        v.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, o: android.graphics.Outline) {
                val w = view.width.toFloat()
                val h = view.height.toFloat()
                if (w <= 0f || h <= 0f) return
                val p = android.graphics.Path()
                SquirclePath.build(p, w, h, r)
                try {
                    o.setPath(p)
                    o.alpha = 1f
                } catch (_: Throwable) {
                    o.setRoundRect(0, 0, w.toInt(), h.toInt(), r)
                }
            }
        }
        v.elevation = if (dark) 2f * dp else 6f * dp
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            v.outlineAmbientShadowColor = UiTok.cardShadow(dark)
            v.outlineSpotShadowColor = UiTok.cardShadow(dark)
        }
    }

    /** 主按钮的凝光蓝底：上浅下深 + 顶部亮线（昼 #2E9BFF→#0080FF / 夜 #3D9DFF→#1478E0） */
    private fun ctasBg(radiusDp: Int = UiTok.R999): SquircleDrawable = sq(
        radiusDp, UiTok.accentTop(isDark()), UiTok.accentBottom(isDark()), contour = true
    )

    /**
     * 凝光卡片容器：白卡（夜 10% 白）+ 超椭圆 + 极细描边。
     * ★ 深色下**不用投影**表达层级 —— 投影在纯黑上根本看不见，靠卡片自身的半透明白 + 轮廓光。
     */
    private fun linearCard(radius: Int = UiTok.R20): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = linearCardBg(radius)
        // ★ v2 软阴影：把卡从环境光上"托"起来 —— 白卡全白不可见的问题就靠这一步 + 环境光
        cardElevation(this, radius)
    }

    /**
     * 与 [linearCard] 同一口径的**可绘制**背景（需要 RippleDrawable / drawable 而非 View 时用）。
     * ★ v1.4.19：改成**超椭圆 + 轮廓光**（[SquircleDrawable]）——
     *   圆弧换超椭圆、1px 边框换 0.6dp 细线、深色下加顶部亮线，卡片立刻像系统自带那张。
     * ★ 名字刻意不叫 `cardBg` —— `makeIdentityCard` 里有同名局部变量，会自己遮蔽自己。
     */
    private fun linearCardBg(radius: Int = UiTok.R20): android.graphics.drawable.Drawable =
        // ★ v2「半融入」：顶 92% 白 → 底 84% 白，环境光从卡后透出来，卡片不再死白突兀
        sq(radius, cCardTop, cCardBottom, stroke = cBorder, strokeDp = 0.6f, contour = isDark())

    /**
     * 卡片按下微缩（Linear 不用涟漪，用极短的缩放反馈）。
     *
     * ★ 三条硬约束（2026-09-20 踩过）：
     * 1. **只能挂"卡片/行"这一级，绝不能挂页面容器** —— 挂在首页整页容器上时，
     *    手指按下整页就缩到 0.98，滑动时手指一直按着 ⇒ 四边漏出 12px 底层背景
     *    （用户报的"首页任意滑动四周出现白边"就是这么来的）。
     * 2. **手势一旦变成"滑动"就必须立刻复原** —— 否则在可滚动列表里，从卡片上起手的滑动
     *    会让这张卡整段拖动期间一直缩着。
     * 3. 返回 false ⇒ 不影响 `setOnClickListener`，也不抢 ScrollView 的手势。
     */
    private fun applyPress(vararg views: android.view.View) {
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        val spring = android.view.animation.OvershootInterpolator(1.1f)
        for (v in views) {
            var downX = 0f
            var downY = 0f
            var swiped = false
            v.setOnTouchListener { view, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        downY = e.rawY
                        swiped = false
                        view.animate().scaleX(0.98f).scaleY(0.98f).setDuration(UiTok.D100).start()
                        // ★ P2 跟手光：按下即起光，中心=触点（不改既有 listener 链）
                        val (lx, ly) = FollowGlow.local(view, e)
                        FollowGlow.down(view, lx, ly, UiTok.R20.toFloat(), isDark())
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (kotlin.math.abs(e.rawX - downX) > slop ||
                            kotlin.math.abs(e.rawY - downY) > slop
                        ) {
                            swiped = true
                            view.animate().scaleX(1f).scaleY(1f).setDuration(UiTok.D100).start()
                            FollowGlow.up(view)
                        } else if (!swiped) {
                            val (lx, ly) = FollowGlow.local(view, e)
                            FollowGlow.move(view, lx, ly)
                        }
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        // ★ P2 动效回弹：松手用轻 overshoot 弹回，不是硬切回 1f
                        view.animate().scaleX(1f).scaleY(1f)
                            .setDuration(UiTok.D240).setInterpolator(spring).start()
                        FollowGlow.up(view)
                    }
                }
                false
            }
        }
    }

    /** 列表错峰入场：淡入 + 上移 12dp，每项延迟 30ms（Linear 的入场节奏） */
    private fun staggerIn(views: List<android.view.View>) {
        views.forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = 12 * dp
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay(i * 30L).setDuration(UiTok.D190).start()
        }
    }

    /**
     * 页面/图层进入动画：淡入 + 上移 12dp（Linear 的节奏，220ms、减速曲线、不弹跳）。
     * 用 post 确保视图已 attach 到窗口再动，否则动画会丢。
     */
    private fun layerIn(v: android.view.View) {
        v.alpha = 0f
        v.translationY = 12 * dp
        v.post {
            v.animate().alpha(1f).translationY(0f).setDuration(UiTok.D240)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
    }

    // ---------------- ★★ 预测性返回手势（侧滑跟手动画） ----------------

    /**
     * 当前**最上层** —— 就是"按返回该关掉的那一层"。
     * 顺序与 [handleBack] 完全一致，改一边记得改另一边。
     * 全都没开 ⇒ null，表示**在主页**。
     */
    private fun topLayer(): View? = labelLayer ?: whaleStyleLayer ?: widgetStyleLayer ?: settingsLayer ?: webLayer ?: transitLayer ?: pickupLayer

    /** 本次手势的跟手目标（开始时就定好，中途不换）。null = 这次返回"不关层"，不该跟手 */
    @Volatile
    private var gestureTarget: View? = null

    /** 预测性返回回调本体。留引用是为了能**动态开关**它（见 [syncBackEnabled]） */
    private var backCallback: androidx.activity.OnBackPressedCallback? = null

    /**
     * 这次返回**会不会真的关掉一层**。
     *
     * ★ 只有一处例外：**网页层里 WebView 自己还有浏览历史**时，返回是"网页后退"而不是关层
     *   （见 [handleBack] 的第一个分支）。那种情况下**绝不能**让这一层跟手滑出去 ——
     *   滑出去了又不关，页面就永久停在屏幕外了。
     */
    private fun backClosesTopLayer(): Boolean = when {
        settingsLayer != null -> true
        webLayer != null -> webView?.canGoBack() != true
        transitLayer != null -> true
        pickupLayer != null -> true
        else -> false
    }

    /**
     * ★★ 主页 / 内层用**两套完全不同的返回动画** —— 2026-09-22 看过酷安录屏后定的。
     *
     * | | 谁在动 | 长什么样 |
     * |---|---|---|
     * | **内层**（网页/取件码/途中/设置） | **我们自己跟手** | 这一层跟着手指往右滑出去 |
     * | **主页**（没层可关） | **交给系统** | 整个窗口跟手缩小、滑向桌面图标（酷安那样） |
     *
     * 主页那套**只能交给系统**：它是"关闭 App"的跨任务动画，由 `OnBackInvokedDispatcher`
     * 在**没有任何应用回调注册**时才接管。所以做法是：在主页把回调 `isEnabled = false`，
     * AndroidX 会自动向系统**注销**我们的回调 ⇒ 系统走默认路径 ⇒ 播"窗口缩回桌面图标"。
     *
     * ⚠️ 以前这里是错的（就是同志说的"主页返回好奇怪"）：以前在主页面
     *   `homeView.translationX = progress * width` —— 那是在**满屏窗口内部把内容往右推**，
     *   左边露出的是 root 的底色；松手还要先弹回、再退出，看着就是
     *   「滑一下 → 弹回来 → 才关」。系统动画是**整个窗口**在动、**桌面从后面露出来**，
     *   两者根本不是一回事。
     */
    fun syncBackEnabled() {
        val cb = backCallback ?: return
        val hasLayer = topLayer() != null
        if (cb.isEnabled == hasLayer) return
        cb.isEnabled = hasLayer
        Log.i(
            "Predictive",
            if (hasLayer) "回调开启 ⇒ 内层由我们跟手"
            else "回调关闭 ⇒ 交给系统播「窗口缩回桌面图标」"
        )
    }

    /**
     * 侧滑跟手 —— 就是酷安那种：手指拖动时页面跟着走，松手才决定去留。
     *
     * ★ 用 AndroidX 的 `OnBackPressedCallback`（AndroidX **没有** OnBackAnimationCallback
     *   这个类型），它把预测性返回的四个动作做成 handleOnBackXxx 方法。
     *   系统的 `android.window.OnBackInvokedCallback.onBackStarted/Progressed/Cancelled`
     *   是 @SystemApi，应用层 override 会直接编译失败（实测）。
     * ★ 必须 `setOnBackInvokedDispatcher(...)` 才算真正接上预测性返回。
     * ★ 本机 Android 16，配合 Manifest 的 `enableOnBackInvokedCallback` 生效。
     */
    private fun setupPredictiveBack() {
        backCallback = object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackStarted(backEvent: androidx.activity.BackEventCompat) {
                // ★ 手势一开始就把"跟谁走"定下来 —— 中途层被关掉也不换目标，
                //   免得出现"拖到一半换了个东西动"
                gestureTarget = if (backClosesTopLayer()) topLayer() else null
                Log.i("Predictive", "start p=${backEvent.progress} 跟手目标=${gestureTarget != null}")
            }

            override fun handleOnBackProgressed(backEvent: androidx.activity.BackEventCompat) {
                // ★ 跟手：拖到哪这一层就走到哪。开关关掉就完全不动 ——
                //   松手后照样正常关闭（走 handleOnBackPressed）
                if (!getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_PREDICTIVE, true)) return
                val t = gestureTarget ?: return
                t.translationX = root.width.toFloat() * backEvent.progress.coerceIn(0f, 1f)
            }

            override fun handleOnBackCancelled() {
                Log.i("Predictive", "cancelled")
                // 没滑到底就松手 ⇒ 弹回原位
                gestureTarget?.animate()?.translationX(0f)?.setDuration(UiTok.D190)
                    ?.setInterpolator(android.view.animation.DecelerateInterpolator())?.start()
                gestureTarget = null
            }

            override fun handleOnBackPressed() {
                val t = gestureTarget
                gestureTarget = null
                Log.i("Predictive", "invoked 跟手目标=${t != null}")
                if (t == null) {
                    // 两种可能：① 网页里"网页后退"（层不关）② 兜底
                    //（主页时回调是关的，系统自己接管，走不到这里）
                    if (!handleBack()) finishAfterTransition()
                    return
                }
                // 有层：把它**滑出屏幕**再真正关闭 —— 跟手已经走了一半，
                // 从这里接着滑，视觉上是连贯的一整段
                t.animate().translationX(root.width.toFloat()).setDuration(UiTok.D190)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .withEndAction { handleBack() }
                    .start()
            }
        }
        backDispatcher.addCallback(backCallback!!)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                backDispatcher.setOnBackInvokedDispatcher(onBackInvokedDispatcher)
                Log.i("Predictive", "attached")
            } catch (e: Throwable) {
                Log.w("Predictive", "attach failed: ${e.message}")
            }
        }
        syncBackEnabled()
    }

    // ---------------- ★★ 设置页：侧滑进出 ----------------

    /**
     * 打开：设置页**从右往左**滑入。
     *
     * ⚠️ 必须**先摆到屏幕右外侧**（translationX = root 宽度）再 post 播动画：
     *   视图 attach 时 translationX 默认是 0，直接 animate 的话会在屏幕里先闪一帧再滑，
     *   看起来就是"闪一下才动"。用 post 也保证布局完成、宽度已知。
     */
    private fun slideIn(v: android.view.View) {
        v.translationX = root.width.toFloat()
        v.post {
            Log.i("SetPerf", "slideIn start")
            v.animate().translationX(0f)
                .setDuration(240)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .withEndAction { Log.i("SetPerf", "slideIn end") }
                .start()
        }
    }

    /** 退出：设置页**从左往右**滑出，滑完再真正移除这一层（否则会"啪"地消失） */
    private fun slideOut(v: android.view.View, onEnd: () -> Unit) {
        v.animate().translationX(root.width.toFloat())
            .setDuration(UiTok.D240)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction { onEnd() }
            .start()
    }

    // ---------- Step 6：全局动效统一（弹窗 / 开关 / 加载文案 / 刷新按钮） ----------
    //   统一口径：**全部 ≤ 600ms、只用 Decelerate，不弹跳**（Linear 的动效是"到位即止"，没有回弹）。

    /**
     * 弹窗进入动画：淡入 + 缩放 0.96 → 1.0（160ms、减速）。
     *
     * ★ 系统 `AlertDialog` / `TimePickerDialog` 默认是"啪"地出现、没有任何过渡，这一层把它补上。
     * ★ 动作必须挂在 **decorView** 而不是 contentView：dialog 的圆角底板与投影都画在 decor 上，
     *   只缩 content 会露出背后的直角色块（比不做动画还难看）。
     * ★ 监听要在 `show()` 之前挂 —— `setOnShowListener` 只在 show 时回调一次。
     */
    private fun dialogIn(d: android.app.Dialog) {
        // ★★★ vc264e（2026-10-15 同志截图反馈"弹窗是纯白直角"）：
        //   系统弹窗默认白底直角，深色模式下与整个 App 风格严重冲突。
        //   全部 15 个 AlertDialog + 1 个 TimePickerDialog 都从本函数进入
        //   ⇒ **改这一处即全局生效**（别在各自的 Builder 上逐个改）。
        val dark = isDark()
        // ① 外壳：window 换圆角背景 + 深浅色 + 半透明细边框
        try {
            d.window?.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 20 * dp
                setColor(if (dark) Color.parseColor("#1C1D1F") else Color.WHITE)
                setStroke((1 * dp).toInt(), if (dark) 0x1FFFFFFF else 0x14000000)
            })
        } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        d.setOnShowListener {
            // ② 文字上色（vc264f 修正）：
            //    ⚠️ 原实现只处理"已知 id"（标题/正文/按钮），**漏了列表项** ——
            //    单选/多选列表（setSingleChoiceItems 等）是系统动态创建的 TextView
            //    （每项 id 都是 text1，findViewById 只能找到第一个），
            //    同志实测「跟踪范围」的选项文字灰暗看不清，根因就是它。
            //    ⇒ 策略改为：**先全量覆盖**（弹窗内所有 TextView → 主文字色），
            //       再用更精确的颜色**覆盖回来**（正文用次要色、按钮用靛紫）——
            //       这里"看得清"优先于"层级细节"（被覆盖最多损失一点层次感，不会读不了）。
            try {
                tintAllTextViews(d.window?.decorView)
            } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
            try {
                // 标题 id 是系统私有资源（com.android.internal），应用层没有公开常量
                // ⇒ 用资源名反射拿（社区通行做法，AOSP/ColorOS 都有）
                val titleId = resources.getIdentifier("alertTitle", "id", "android")
                if (titleId != 0) {
                    (d.findViewById<View>(titleId) as? TextView)?.setTextColor(cTextPrimary)
                }
                (d.findViewById<View>(android.R.id.message) as? TextView)
                    ?.setTextColor(cTextSecondary)
                for (id in intArrayOf(
                    android.R.id.button1, android.R.id.button2, android.R.id.button3
                )) {
                    (d.findViewById<View>(id) as? TextView)?.setTextColor(cAccent)
                }
                // 单选圆圈：系统默认蓝与项目主色不符 ⇒ 靛紫
                tintRadioButtons(d.window?.decorView)
            } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
            // ③ 输入框：系统默认"黑字 + 灰下划线"，深色底上根本看不清 ⇒ 统一换成项目风格
            try {
                tintDialogInputs(d.window?.decorView)
            } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
            // ④ 原有入场动画
            val decor = d.window?.decorView ?: return@setOnShowListener
            decor.alpha = 0f
            decor.scaleX = 0.96f
            decor.scaleY = 0.96f
            decor.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(UiTok.D190)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    /**
     * ★★ vc695（同志 2026-11-04 15:09 定）：**危险操作确认弹窗（项目自绘，不走系统 AlertDialog）**。
     *
     * 为什么要自绘：系统弹窗的按钮固定在右下角一排、且样式跟着 ROM 变；同志要求
     * **左下「否」/ 右下「是」** 的布局 + 圆角 + 文字与底色反色（深色下白字、浅色下墨字）。
     *
     * 视觉口径（与全局弹窗一致，见 [dialogIn]）：
     * · 20dp 圆角 + 1px 半透明描边；浅色白底 / 深色 #1C1D1F，**文字色随主题反色**；
     * · 入场：淡入 + 0.96→1.0 缩放，190ms 减速（与 `dialogIn` 同一套）；
     * · 「是」用危险红 #D93025 并加粗 —— 这是不可逆操作，不能跟「否」长得一样。
     */
    private fun showDangerDialog(title: String, message: String, onConfirm: () -> Unit) {
        val dark = isDark()
        val radiusPx = 20 * dp
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22 * dp).toInt(), (22 * dp).toInt(), (22 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radiusPx
                setColor(if (dark) Color.parseColor("#1C1D1F") else Color.WHITE)
                setStroke((1 * dp).toInt(), if (dark) 0x1FFFFFFF else 0x14000000)
            }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: android.view.View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
                }
            }
        }
        card.addView(TextView(this).apply {
            text = title
            textSize = UiTok.T17
            typeface = tfMedium
            setTextColor(cTextPrimary)
            includeFontPadding = false
        })
        card.addView(TextView(this).apply {
            text = message
            textSize = UiTok.T15
            setTextColor(cTextSecondary)
            setLineSpacing((6 * dp).toFloat(), 1f)
            setPadding(0, (14 * dp).toInt(), 0, (14 * dp).toInt())
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun actionBtn(text: String, color: Int, bold: Boolean, click: () -> Unit) = TextView(this).apply {
            this.text = text
            textSize = UiTok.T15
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(color)
            if (bold) setTypeface(tfMedium)
            // ★ vc696：**水平 padding 归零并给 minWidth** —— 否则文字自己居中，
            //   "否"会飘到半个屏幕的中间（同志 11-04 15:18 截图指出）。
            minWidth = (44 * dp).toInt()
            minHeight = (44 * dp).toInt()
            setPadding(0, (14 * dp).toInt(), 0, (14 * dp).toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { click() }
        }
        val noBtn = actionBtn("否", cTextSecondary, false) { }
        val yesBtn = actionBtn("是", Color.parseColor("#D93025"), true) { }
        // ★ vc696：**贴边布局** —— 否贴左、是贴右，中间用弹性空白顶开。
        //   （旧写法给"否"weight=1 + gravity CENTER ⇒ 它坐在左半边正中，看着像"放中间"。）
        row.addView(noBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(yesBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        applyPress(noBtn, yesBtn)
        card.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val d = android.app.Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        d.setContentView(card)
        val widthPx = (resources.displayMetrics.widthPixels * 0.86f).toInt()
        d.window?.let { w ->
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.55f)
            w.setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        d.setCanceledOnTouchOutside(true)
        noBtn.setOnClickListener { d.dismiss() }
        yesBtn.setOnClickListener {
            d.dismiss()
            onConfirm()
        }
        d.setOnShowListener {
            val decor = d.window?.decorView ?: return@setOnShowListener
            decor.alpha = 0f
            decor.scaleX = 0.96f
            decor.scaleY = 0.96f
            decor.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(UiTok.D190)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
        d.show()
    }

    /**
     * 递归把弹窗里的 EditText 换成项目风格：主文字色 + 淡提示色 + 圆角输入框底。
     * 只碰 EditText，不动其它控件。
     */
    private fun tintDialogInputs(v: View?) {
        if (v == null) return
        if (v is android.widget.EditText) {
            v.setTextColor(cTextPrimary)
            v.setHintTextColor(cTextFaint)
            val dark = isDark()
            v.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 10 * dp
                setColor(if (dark) Color.parseColor("#101114") else Color.parseColor("#F2F3F5"))
                setStroke((1 * dp).toInt(), if (dark) 0x1FFFFFFF else 0x14000000)
            }
            return
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) tintDialogInputs(v.getChildAt(i))
        }
    }

    /**
     * 递归把所有 TextView 统一上主文字色。
     * 用在 dialogIn 的"先全量覆盖"一步：弹窗里系统动态创建的控件
     * （列表项 text1、TimePicker 数字…）没有公开 id 可定位，只能遍历。
     */
    private fun tintAllTextViews(v: View?) {
        if (v == null) return
        if (v is TextView) {
            v.setTextColor(cTextPrimary)
            return
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) tintAllTextViews(v.getChildAt(i))
        }
    }

    /** 递归把弹窗里的单选圆圈换成靛紫（系统默认 accent 蓝与项目主色不符） */
    private fun tintRadioButtons(v: View?) {
        if (v == null) return
        if (v is android.widget.RadioButton) {
            try {
                v.buttonTintList = android.content.res.ColorStateList.valueOf(cAccent)
            } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
            return
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) tintRadioButtons(v.getChildAt(i))
        }
    }

    /** 带入场动画的弹窗入口：**以后新建弹窗一律走这里**，别再直接 `.show()` */
    private fun android.app.AlertDialog.Builder.showAnimated(): android.app.AlertDialog {
        val d = create()
        dialogIn(d)
        d.show()
        return d
    }

    /**
     * 顶栏刷新按钮：「⟳」点击时自转一圈（600ms、减速），同时立刻发起刷新。
     *
     * ★ 每次先把 `rotation` 归零再动：`animate()` 是从**当前值**补到目标值，
     *   不归零的话第二次点击是 360°→720°，两圈并成一段，视觉上第二下"不动"。
     */
    private fun refreshBtn(onClick: () -> Unit): android.widget.ImageView {
        // ★★★ 用**矢量图形**，别用文字（"⟳"）：
        //     那个字形在字体 em box 里**天生偏下** —— 实测字形包围盒中心比 View 中心低 9.5px，
        //     而 View.rotation 绕 View 中心转 ⇒ 看着就是"绕一个偏下的点打圈"。
        //     关 includeFontPadding 只挪回 1px，治不了根（字形位置是字体决定的）。
        //     矢量图在 24×24 viewport 里对称，FIT_CENTER 摆正后旋转中心严格落在正中。
        // ★ 另外不要复用 `topBarBtn`：它自带左右各 14dp padding（那是给「完成/登录」
        //   这类文字按钮留点击余量的），塞进固定 48dp 宽的槽位会把内容挤歪。
        // ★★ 用**矢量图形**而不是文字 "⟳"：
        //   那个字形在字体的 em box 里天生偏下 —— 实测字形包围盒中心比 View 中心低 9.5px，
        //   而 View.rotation 是绕 View 中心转的，于是看起来就是"绕一个偏下的点打圈"。
        //   关 includeFontPadding 只能挪回 1px，治不了根。
        //   换成图形后，FIT_CENTER 把它摆正，旋转中心严格落在 View 中心。
        val tv = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_refresh)
            setColorFilter(cTextPrimary)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            val pad = (12 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            isClickable = true
        }
        tv.setOnClickListener { v ->
            v.animate().cancel()
            // ★ 显式把旋转轴心钉在 View 正中（本来就是默认值，写出来防止以后被改坏）
            v.pivotX = v.width / 2f
            v.pivotY = v.height / 2f
            v.rotation = 0f
            v.animate().rotation(360f).setDuration(UiTok.D380)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .withEndAction { v.rotation = 0f }
                .start()
            onClick()
        }
        return tv
    }

    /**
     * 加载/状态文案交叉淡入（180ms = 90ms 淡出 → 换字 → 90ms 淡入）。
     *
     * ★ 前提是调用方先 `showStatusText()` 把它顶成 VISIBLE + alpha 1 —— 只有"肉眼正看着它"才值得
     *   交叉淡入。这个 alpha 守卫防的是另一件事：上一次淡出还没跑完又来新文案时，
     *   直接换字（此时它本来就是半透明/不可见，再做一次淡出只会看到"文字闪没"）。
     */
    /** ★ vc592：「更新于 HH:mm」只保留 3 秒就淡出（同志要求，不再常驻占一行） */
    private var statusFadeJob: Runnable? = null

    private fun showUpdateThenHide(tv: TextView?, text: CharSequence) {
        if (tv == null) return
        statusFadeJob?.let { mainHandler.removeCallbacks(it) }
        tv.animate().cancel()
        tv.visibility = View.VISIBLE
        tv.alpha = 1f
        tv.text = text
        val job = Runnable {
            tv.animate().alpha(0f).setDuration(UiTok.D240).withEndAction {
                tv.visibility = View.GONE
                tv.alpha = 1f
                statusFadeJob = null
            }.start()
        }
        statusFadeJob = job
        mainHandler.postDelayed(job, 3000L)
    }

    private fun setStatusText(tv: TextView?, text: CharSequence) {
        if (tv == null || tv.text == text) return
        tv.animate().cancel()
        if (tv.visibility != View.VISIBLE || tv.alpha < 1f) {
            tv.alpha = 1f
            tv.text = text
            return
        }
        tv.animate().alpha(0f).setDuration(UiTok.D100).withEndAction {
            tv.text = text
            tv.animate().alpha(1f).setDuration(UiTok.D100).start()
        }.start()
    }

    /** 让状态文案现身：先掐掉可能还在跑的淡出、并把 alpha 顶满（否则可能停在 0，变成"隐形"） */
    private fun showStatusText(tv: TextView?) {
        if (tv == null) return
        tv.animate().cancel()
        tv.alpha = 1f
        tv.visibility = View.VISIBLE
    }

    /** 面板内显隐过渡：出现 = 淡入 + 上移 4dp（160ms）；消失 = 先淡出 120ms 再 GONE */
    private fun fadeToggle(v: View, visible: Boolean) {
        v.animate().cancel()
        if (visible) {
            if (v.visibility == View.VISIBLE && v.alpha == 1f) return
            v.visibility = View.VISIBLE
            v.alpha = 0f
            v.translationY = 4 * dp
            v.animate().alpha(1f).translationY(0f).setDuration(UiTok.D190)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        } else {
            if (v.visibility != View.VISIBLE) return
            v.animate().alpha(0f).setDuration(UiTok.D100).withEndAction {
                v.visibility = View.GONE
                // 复位，保证下次 fadeToggle(true) 是从 0 起淡入而不是"已经 0 再淡入"
                v.alpha = 1f
                v.translationY = 0f
            }.start()
        }
    }

    /**
     * 开关过渡：轨道 + 滑块的颜色用 `ArgbEvaluator` 沿 120ms 走完，而不是让系统硬切。
     *
     * ★ 两条实测纠错（2026-09-20 真机取色）：
     * 1. **滑块与轨道都要「从旧色补到新色」**。写成 `evaluate(t, to, from)` 会让滑块
     *    停在**切换前**的颜色上 —— 真机取样：关掉开关后滑块仍是 `#8088D0`（靛紫），一眼假。
     * 2. **轨道必须带不透明度**。滑块与轨道同色的话，滑块压在轨道上等于消失；
     *    轨道用基础色 38% 不透明度，就是 Material Switch 的观感（实心滑块 + 淡色轨道）。
     */
    private fun animateSwitchTint(sw: android.widget.Switch, on: Boolean) {
        val fromBase = if (on) cTextFaint else cAccent
        val toBase = if (on) cAccent else cTextFaint
        val ev = android.animation.ArgbEvaluator()
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 120
            addUpdateListener { a ->
                val base = ev.evaluate(a.animatedValue as Float, fromBase, toBase) as Int
                sw.thumbTintList = android.content.res.ColorStateList.valueOf(base)
                sw.trackTintList =
                    android.content.res.ColorStateList.valueOf(withAlpha(base, 0.38f))
            }
        }.start()
    }

    /** 给颜色套一个不透明度（0f~1f） */
    private fun withAlpha(color: Int, a: Float): Int =
        Color.argb((255 * a).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    /** Linear 中等字重（规范禁用 700，最大 590；Android 用 sans-serif-medium 近似） */
    private val tfMedium: Typeface
        get() = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    // ---------- 生命周期 ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        // ★ 根容器必须自己刷画布色：主题是 `Theme.Material.Light`，windowBackground 是近白 #FAFAFA，
        //   而 Android 12+ 的 ScrollView 边缘拉伸（overscroll stretch）会在边界处露出底层背景
        //   ⇒ 不刷的话，在深色首页顶部/底部一拉就是一道白边。
        root.background = AmbientGlowDrawable(isDark())
        // ★ vc582：测试物流要先加载，首页「在途快递」那行才能显示件数
        //   （原来 loadTestTransit 在 buildHome 之后，首页永远显示 0 件）
        testTransit.clear()
        testTransit.addAll(loadTestTransit())
        // ★ vc571：测试取件码注入（5 条）/ 清除 —— 必须在 buildHome 之前
        if (intent?.getBooleanExtra("debug_seed_codes", false) == true) seedTestCodes()
        if (intent?.getBooleanExtra("debug_seed_codes_clear", false) == true) clearTestCodes()
        // ★ vc676：调试用 —— 按逗号分隔的键把指定项移出黑名单（清测试残留，不动用户自己删的）
        intent?.getStringExtra("debug_unmute")?.let { raw ->
            val keys = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            for (k in keys) MuteStore.removeKey(this, k)
            Log.i("MuteStore", "调试移出黑名单 ${keys.size} 条：$keys 余 ${MuteStore.entries(this).size}")
        }
        // ★ vc583：把**整份**取件码缓存清空（含真实抓取残留；只给调试用）
        // ★★ vc614：单一裁决器自测（纯本地，不联网）：--ez debug_verdict_selftest true
        if (intent?.getBooleanExtra("debug_verdict_selftest", false) == true) {
            val r = PickupVerdict.selfTest()
            Log.i("Verdict", r)
            try {
                java.io.File(getExternalFilesDir(null), "verdict_selftest.txt").writeText(r)
            } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        }
        // ★★ v1.4.13：清掉旧版「自动打已取标签」留下的伪标记（只跑一次）
        cleanAutoPickedOnce()
        // ★★ v1.4.15：**一码多单 ⇒ 该码整批作废**（同志 14:09：「73-3-5888 是沐浴露的取件码，
        //   我已经取件了，但他还在缓存，所以会造成重复出现，并且没有匹配的快递」）。
        //   1.4.13 的 cleanSharedCodesOnce 只做"保早删晚"且仅跑一次，抓取链下一轮又贴回来。
        //   现在改成持久作废表（[PddCodeStore.KEY_DEAD]），put/putForce 直接拒写。
        runCatching { PddCodeStore.sweepShared(this) }
            .onSuccess { if (it > 0) Log.i("PddCodeMatch", "启动清扫：作废 $it 个一码多单的码") }
        // ★★ v1.4.13：在途裁决器自测（纯本地，不联网）：--ez debug_transit_selftest true
        //   14 条用例把「运输中优先 / 待确认豁免 / 名单豁免 / 已有码不重复」这些护栏钉住，
        //   以后谁改了判据顺序，跑一次就知道有没有踩到历史事故。
        if (intent?.getBooleanExtra("debug_transit_selftest", false) == true) {
            val r = TransitRules.selfTest()
            Log.i("TransitRules", r)
            try {
                java.io.File(getExternalFilesDir(null), "transit_selftest.txt").writeText(r)
            } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        }
        // ★★ v1.4.15：**一码多单作废机制**的回归自测（纯本地，不联网）：
        //   `--ez debug_codesweep true`
        //   造两条同码缓存 → sweepShared → 应作废该码、两条全删、且事后拒写。
        if (intent?.getBooleanExtra("debug_codesweep", false) == true) {
            run {
                val dup = "99-9-9999"
                val now = System.currentTimeMillis()
                PddCodeStore.putForce(this, "pdd:debugA", PddCodeStore.Cached(
                    src = "pdd", code = dup, station = "调试驿站", waybill = "DBG1",
                    latest = "调试", ts = now
                ))
                PddCodeStore.putForce(this, "pdd:debugB", PddCodeStore.Cached(
                    src = "pdd", code = dup, station = "调试驿站", waybill = "DBG2",
                    latest = "调试", ts = now
                ))
                val before = PddCodeStore.allRaw(this).count { it.value.code == dup }
                val killed = PddCodeStore.sweepShared(this)
                val after = PddCodeStore.allRaw(this).count { it.value.code == dup }
                PddCodeStore.putForce(this, "pdd:debugC", PddCodeStore.Cached(
                    src = "pdd", code = dup, station = "调试驿站", waybill = "DBG3",
                    latest = "调试", ts = now
                ))
                val rejected = PddCodeStore.allRaw(this).none { it.key == "pdd:debugC" }
                val r = "一码多单作废自测：\n" +
                        "  作废前同码条目=$before（期望 2）\n" +
                        "  本轮作废码数=$killed（期望 1）\n" +
                        "  作废后同码条目=$after（期望 0）\n" +
                        "  作废码拒写=" + (if (rejected) "是" else "否") + "（期望 是）\n" +
                        "结果：" + (if (before == 2 && killed >= 1 && after == 0 && rejected) "PASS" else "FAIL")
                Log.i("PddCodeMatch", r)
                try {
                    java.io.File(getExternalFilesDir(null), "codesweep_selftest.txt").writeText(r)
                } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
            }
        }
        // ★ vc647：回归验证 PDD「申请退款」按钮不再误杀真实待收货订单。
        if (intent?.getBooleanExtra("debug_pdd_filter_selftest", false) == true) {
            val r = PddNative.pickupFilterSelfTest()
            Log.i("PddNative", r)
            try {
                java.io.File(getExternalFilesDir(null), "pdd_filter_selftest.txt").writeText(r)
            } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        }
        // ★ vc637：跨源同码去重回归自测，覆盖“一个源缺站点”的真实异常形态。
        if (intent?.getBooleanExtra("debug_dedup_selftest", false) == true) selfTestDedup()
        // ★ vc693：一键自检（也可从「设置」页点「运行自检」）
        if (intent?.getBooleanExtra("debug_self_test", false) == true) showSelfTestDialog()
        // ★★ vc604：**一条命令清掉所有调试残留**（testTransit / 轨迹缓存 / 取件码 / 桌面组件 / 在途缓存）
        //   交付前必跑；同志报"用户到手还带假数据"，根因就是以前只能一条条清、容易漏。
        if (intent?.getBooleanExtra("debug_purge_all", false) == true) {
            try {
                testTransit.clear()
                saveTestTransit(testTransit)
                pddTransitTraceCache.clear()
                saveTransitTraces()
                val ks = PddCodeStore.all(this).keys.toList()
                ks.forEach { PddCodeStore.remove(this, it) }
                HomeWidgets.clearCodes(this)
                val f = transitCacheFile()
                if (f.exists()) f.delete()
                Log.i("TestSeed", "PURGE 完成：测试物流/轨迹/取件码(${ks.size})/组件/在途缓存 已全部清空")
            } catch (t: Throwable) {
                Log.w("TestSeed", "PURGE 失败: ${t.message}")
            }
        }
        if (intent?.getBooleanExtra("debug_clear_all_codes", false) == true) {
            val keys = PddCodeStore.all(this).keys.toList()
            keys.forEach { PddCodeStore.remove(this, it) }
            // ★ vc603：桌面小组件是**第二份存储**（widget_codes），必须一起清并重画
            HomeWidgets.clearCodes(this)
            Log.i("TestSeed", "已清空取件码缓存 ${keys.size} 条（含桌面小组件）")
        }
        homeView = buildHome()
        root.addView(homeView, matchParent())
        // ★ 主页的返回动画交给系统（整窗缩回桌面图标），所以设置键不需要单独留引用来跟手
        root.addView(buildSettingsButton())
        setContentView(root)
        applySystemBarIcons()
        // ★ vc342：恢复上次的补码状态（哪些单已封顶 / 试过几次），6h 自动衰减
        loadEnrichState()
        // 预测性返回（侧滑跟手）+ 返回逻辑总入口：必须常驻 ——
        // 开了 enableOnBackInvokedCallback 后，API 33+ 不再走 onBackPressed，
        // 所有层的返回都得由它兜住
        setupPredictiveBack()
        // 状态栏 inset：缓存到 statusBarTop，动态建的层（web/pickup/settings）收不到首次分发，
        // 只能靠这个缓存值自己补 padding，否则顶栏文字会压进状态栏
        root.setOnApplyWindowInsetsListener { _, insets ->
            val t = insets.getInsets(WindowInsets.Type.statusBars()).top
            if (t > 0) {
                statusBarTop = t
                for (bar in liveTopBars) {
                    if (bar.isAttachedToWindow) bar.setPadding((10 * dp).toInt(), t, (10 * dp).toInt(), 0)
                }
            }
            insets
        }
        root.post { root.requestApplyInsets() }
        // ★ vc698：**主动申请**权限（通知 \/ 电池优化白名单）—— 用户要求别只给入口。
        //   放到 insets 之后（界面已就绪）再弹，免得对话框先于界面出现；
        //   3 天内弹过就不再打扰，设置页里始终有手动入口。
        mainHandler.postDelayed({ maybeAutoRequestPermissions() }, 1_200L)

        // ★ 流体云：启动时**先从持久化快照补推一次**。
        //   这样 App 一打开实况位上就有码，不用等抓取；也免得某个源临时失效
        //   （实测拼多多被判过登录失效）时，已经抓到手的取件码从实况位掉下去。
        mainHandler.post {
            try {
                LiveUpdate.ensureChannel(this)
                LiveUpdate.syncFromSnapshot(this)
                IslandHelper.syncFromSnapshot(this)
            } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        }

        // 调试通道：adb am start --es debug_url <网址> 可直接打开任意页面做探测
        when (intent?.getStringExtra("debug_ua")) {
            "desktop" -> DEBUG_DESKTOP_UA = true
            "wx", "wechat" -> DEBUG_CONTAINER_UA = UA_WECHAT
            "alipay" -> DEBUG_CONTAINER_UA = UA_ALIPAY
        }
        // 调试：--ez debug_webdev true ⇒ 开启 WebView 远程调试（DevTools 可连），
        //   排查「顺丰登录页在部分机型上按电脑版布局缩小渲染」用（看真实 URL / 视口宽 / 缩放）
        if (intent?.getBooleanExtra("debug_webdev", false) == true) {
            try {
                WebView.setWebContentsDebuggingEnabled(true)
                Log.i("SfDebug", "debug_webdev: WebView 远程调试已开启")
            } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        }
        // 调试：--ez debug_wipe_pdd true ⇒ 清空拼多多全部登录痕迹（SP 存档 + cookie 库），
        //   做「纯扫码会话能否独立撑起全部」实验用（清完只靠扫码重新登录）
        if (intent?.getBooleanExtra("debug_wipe_pdd", false) == true) {
            try {
                PddChannel.clearLogin(this)
                Log.i("PddDebug", "debug_wipe_pdd: 已清空拼多多登录痕迹")
            } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        }
        // 调试：--ez debug_pdd_dlg true ⇒ 走与生产完全相同的弹窗闸门（vc284 验证用，
        //   已登录机上点入口走身份码页，摸不到弹窗；这里复用同一份"开关判断+弹窗"）
        if (intent?.getBooleanExtra("debug_pdd_dlg", false) == true) {
            // 与生产完全同路：打开登录页 + 弹窗浮层覆盖其上
            mainHandler.postDelayed({ openPddLogin() }, 800)
        }
        // 调试：--ez debug_pdd_dlg_reset true ⇒ 清掉「以后不再提示」勾选（验证复位用）
        if (intent?.getBooleanExtra("debug_pdd_dlg_reset", false) == true) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .remove(KEY_PDD_LOGIN_NOTICE_OFF).apply()
            Log.i("PddNotice", "已复位：不再提示勾选已清除")
        }
        // 调试：--ez debug_dump_tokens true ⇒ 把「jar 令牌 vs SP 存档令牌」写外部文件（比对会话血统，只读不动）
        if (intent?.getBooleanExtra("debug_dump_tokens", false) == true) {
            try {
                val jar = try {
                    android.webkit.CookieManager.getInstance()
                        .getCookie("https://mobile.yangkeduo.com/").orEmpty()
                } catch (_: Throwable) {
                    ""
                }
                val jarTok = Regex("(?:^|;\\s*)PDDAccessToken=([^;]+)").find(jar)?.groupValues?.get(1).orEmpty()
                val spTok = Regex("(?:^|;\\s*)PDDAccessToken=([^;]+)")
                    .find(PddChannel.cookies(this))?.groupValues?.get(1).orEmpty()
                val info = "jar=${jarTok.take(16)}(len=${jarTok.length})\n" +
                    "sp =${spTok.take(16)}(len=${spTok.length})\n" +
                    "same=${jarTok == spTok && jarTok.isNotEmpty()}\n" +
                    "ts=${System.currentTimeMillis()}\n"
                java.io.File(getExternalFilesDir(null), "pdd_tokens.txt").writeText(info)
            } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        }
        intent?.getStringExtra("debug_url")?.let { openWeb(it, "debug") }
        // ★ 白屏诊断（--es debug_url 时顺带跑）：区分「页面没渲染」和「WebView 尺寸/可见性有问题」。
        //   典型坑：WebView 没挂到视图树或尺寸为 0 ⇒ window.innerWidth=0 ⇒ 布局不发生
        //   ⇒ DOM 有内容（`innerHTML` 非空）但 `innerText` 为空 ⇒ 用户看到的就是一片底色。
        if (intent?.getStringExtra("debug_url") != null) {
            mainHandler.postDelayed({
                val w = webView
                if (w == null) {
                    Log.i("WebDiag", "webView == null")
                } else {
                    val vs = "view=%dx%d vis=%s alpha=%s parent=%s".format(
                        w.width, w.height, w.visibility == View.VISIBLE,
                        w.alpha, w.parent?.javaClass?.simpleName
                    )
                    Log.i("WebDiag", vs)
                    val js = "(function(){var bd=document.body;var app=document.getElementById('app');" +
                        "return JSON.stringify({l:location.href,t:document.title," +
                        "vw:window.innerWidth,vh:window.innerHeight," +
                        "appLen:(app?app.innerHTML.length:-1)," +
                        "bodyKids:(bd?bd.children.length:-1)," +
                        "htmlLen:(document.documentElement?document.documentElement.outerHTML.length:0)," +
                        "txt:(bd?bd.innerText:'').replace(/\\s+/g,' ').slice(0,150)," +
                        "forms:(document.querySelectorAll?document.querySelectorAll('input').length:-1)});})()"
                    w.evaluateJavascript(js) { v ->
                        Log.i("WebDiag", "DOM: " + WebHook.unquoteJsString(v))
                    }
                }
            }, 14_000)
        }
        // 后台快递跟踪：建通知渠道 + 按设置排程（间隔 0 = 不排）。
        // 放在这里而不是 Application：本项目没有 Application 类，且排程依赖用户先开过 App。
        TrackNotifier.ensureChannel(this)
        // ★★★ vc264：调度权交给**常驻前台服务**（同志 10-15 拍板）
        //   背景：10-14 实测 ColorOS 会把不活跃 App 的闹钟触发时间改写到约 +72 小时后，
        //   非精确闹钟 / 精确闹钟 / setAlarmClock 全被推迟，AOSP 电池白名单也无效
        //   ⇒ "到点叫醒"这条路在 ColorOS 上走不通，改成服务常驻、服务内部自己定时查。
        //   服务内按 pollIntervalMin 间隔跑（复用 TrackScheduler.runNow，与 Worker 同一份逻辑）。
        //   ★ fromUser=true：用户主动打开 App 是明确意图，不受"曾经被强制停止"的影响
        //     （后台自愈才尊重强制停止，见 KeepAliveService.start 的注释）。
        KeepAliveService.start(this, fromUser = true)
        // 周期任务保留（服务万一不在时的额外兜底）；兜底闹钟**撤销** —— 它已被证实会被推迟
        TrackScheduler.apply(this)
        KeepAlive.disarm(this)
        //   --ei debug_alarm_min 2   兜底闹钟改成 2 分钟后触发（验证"划掉后闹钟还在不在"）
        // 调试通道（后台跟踪专用，验证时用）：
        //   --es debug_notify "文案"   走一遍通知链路
        //   --ez debug_track_run true  立刻触发一次 TrackWorker（不影响周期排程）
        //   --ez debug_track_dump true 把快照/跟踪名单/设置落盘，adb pull 出来看
        intent?.getStringExtra("debug_notify")?.let { TrackNotifier.notifyTest(this, it) }
        if (intent?.getBooleanExtra("debug_track_run", false) == true) TrackScheduler.runNow(this)

        if (intent?.getBooleanExtra("debug_track_dump", false) == true) dumpTrackState()
        if (intent?.getBooleanExtra("debug_dump_tb", false) == true) debugDumpTb = true
        // vc268d：A 路探测开关（⚠️ 调试参数只在 onCreate 读 ⇒ 必须 force-stop 后再 am start）
        intent?.getStringExtra("debug_tb_api")?.let { debugTbApi = it }
        intent?.getStringExtra("debug_tb_data")?.let { debugTbData = it }
        // vc269：淘宝 H5 链路探测器（olist 接口名 / h5-detail 数据形状）
        if (intent?.getBooleanExtra("debug_tb_olist", false) == true) TbTransitProbe.probeOlist(this, root)
        intent?.getStringExtra("debug_tb_detail")?.let { TbTransitProbe.probeDetail(this, it) }
        // vc270j：多多驿站包裹页探针（找批量取件码接口）——开 mdkd/package + 旁听钩子，
        // 20s/40s 两轮落盘捕获；层保持打开（页面可能需要停留触发加载）
        if (intent?.getBooleanExtra("debug_mdkd_pkg", false) == true) {
            mdkdPkgProbeActive = true
            PddMdkd.probeActive = true
            mainHandler.postDelayed({
                openWeb("https://m.pinduoduo.net/mdkd/package", "驿站包裹探针", loginMode = "mdkd")
            }, 400)
            fun dumpMdkdCaptures() {
                val wv = webView ?: return
                WebHook.readCaptures(wv) { caps ->
                    try {
                        val f = java.io.File(getExternalFilesDir(null), "mdkd_pkg_capture.txt")
                        for ((u, b) in caps) {
                            f.appendText("URL: $u\nBODY: ${b.take(30000)}\n----\n")
                        }
                        Log.i("MdkdProbe", "落盘 ${caps.size} 段 → mdkd_pkg_capture.txt")
                    } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
                }
                // vc270j：页面网络请求全清单（resource entries）——列表接口就算返回空也会留名
                wv.evaluateJavascript(
                    "(function(){try{return JSON.stringify({res:performance.getEntriesByType('resource').map(function(r){return r.name})})}catch(e){return '{}'}})()"
                ) { res ->
                    try {
                        val f = java.io.File(getExternalFilesDir(null), "mdkd_res_dump.txt")
                        f.writeText(WebHook.unquoteJsString(res))
                        Log.i("MdkdProbe", "资源清单已落盘 → mdkd_res_dump.txt")
                    } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
                }
            }
            mainHandler.postDelayed({ dumpMdkdCaptures() }, 20_000)
            mainHandler.postDelayed({ dumpMdkdCaptures() }, 40_000)
        }
        // vc270k：express_trackbox 家族原生直发试探（免签名接口，只要 Referer+AccessToken）
        intent?.getStringExtra("debug_pdd_post")?.let { path ->
            val bodyJson = intent.getStringExtra("debug_pdd_body") ?: "{}"
            PddMdkd.probeActive = true
            pddHttpPool.execute pddPost@{
                val t0 = System.currentTimeMillis()
                val out = try {
                    val conn = java.net.URL("https://api.pinduoduo.com/api/express_trackbox/$path")
                        .openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.doOutput = true
                    conn.setRequestProperty("Referer", "https://m.pinduoduo.net/mdkd/package")
                    conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8")
                    conn.setRequestProperty("User-Agent", PddChannel.BROWSER_UA)
                    val ck = try {
                        CookieManager.getInstance().getCookie("https://mobile.yangkeduo.com/").orEmpty()
                    } catch (_: Throwable) { "" }
                    if (ck.isNotEmpty()) conn.setRequestProperty("Cookie", ck)
                    val tok = Regex("(?:^|;\\s*)PDDAccessToken=([^;]+)").find(ck)?.groupValues?.get(1)
                    tok?.let { conn.setRequestProperty("AccessToken", it.trim()) }
                    conn.outputStream.use { it.write(bodyJson.toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    "HTTP $code\n" + (stream?.readBytes()?.toString(Charsets.UTF_8) ?: "")
                } catch (t: Throwable) {
                    "EXC ${t.message}"
                }
                Log.i("PddPost", "POST $path → ${out.take(600)}（${System.currentTimeMillis() - t0}ms）")
                try {
                    java.io.File(getExternalFilesDir(null), "pdd_post_dump.txt")
                        .appendText("== POST $path body=$bodyJson\n$out\n----\n")
                } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
            }
        }
        // ★ vc351：**枚举验证（只读）** —— 不改任何显示逻辑，只把拼多多自己的
        //   `express/track/status` 结果打出来，用来核对枚举语义是否和观察一致。
        //   adb：--ez debug_enum_probe true [--es debug_enum_sn "单号1,单号2"]
        if (intent?.getBooleanExtra("debug_enum_probe", false) == true) {
            val extraSns = intent.getStringExtra("debug_enum_sn").orEmpty()
                .split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
            val caps = PddChannel.lastCaptures
            val capSns = ArrayList<String>()
            for ((_, body) in caps) {
                Regex("\"order_sn\"\\s*:\\s*\"([^\"]+)\"").findAll(body).forEach {
                    val sn = it.groupValues[1]
                    if (sn.isNotEmpty() && sn !in capSns) capSns.add(sn)
                }
            }
            val sns = (extraSns + capSns).distinct()
            val pkgsSnap = ArrayList(pddPkgs)
            val transSnap = ArrayList(pddTransit)
            Log.i("EnumProbe", "触发：候选 ${sns.size} 单（显式 ${extraSns.size} / 旁听 ${capSns.size}）")
            // 用独立线程（不走 waybillIo）：冷启动时上一实例可能在销毁，共享线程池会被拒
            Thread enumProbe@{
                val ck = PddChannel.cookies(this@MainActivity)
                val uid = PddChannel.uid(this@MainActivity).ifEmpty { PddChannel.uidFromCookie(ck) }
                val token = Regex("PDDAccessToken=([^;]+)")
                    .find(ck)?.groupValues?.get(1)?.trim().orEmpty()
                val ses = PddNative.Session(token = token, uid = uid, cookie = ck)
                Log.i(
                    "EnumProbe",
                    "开始：候选 ${sns.size} 单（显式 ${extraSns.size} / 旁听 ${capSns.size}）usable=${ses.usable}"
                )
                val map = PddNative.trackStatuses(ses, sns)
                val sb = StringBuilder()
                for (sn in sns) {
                    val en = map[sn] ?: "（无记录）"
                    val card = pkgsSnap.firstOrNull {
                        it.optString("orderSn") == sn || it.optString("waybillNo") == sn
                    }
                    val tr = transSnap.firstOrNull { it.optString("waybillNo") == sn }
                    val cur = card?.optString("statusText").orEmpty()
                        .ifEmpty { tr?.optString("statusText").orEmpty() }
                    val line = "ENUMPROBE $sn → $en  卡片状态=「${cur.ifEmpty { "不在列表" }}」"
                    Log.i("EnumProbe", line)
                    sb.append(line).append('\n')
                }
                if (map.isEmpty()) Log.w("EnumProbe", "接口没返回任何枚举（HTTP/登录态问题）")
                Log.i("EnumProbe", "完成：${sns.size} 单 → 命中 ${map.size}")
                try {
                    java.io.File(getExternalFilesDir(null), "pdd_enum_dump.txt")
                        .writeText("候选=${sns.size} 命中=${map.size}\n" + sb)
                } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
            }.start()
        }
        // vc269b：走**正式可视化网页层**刮订单 DOM（隐形探测层页面不渲染订单，这是被实测
        //   证明能出订单的唯一路径）——开 olist → 等 18s → 刮 → 自动关窗
        if (intent?.getBooleanExtra("debug_tb_scrape", false) == true) {
            mainHandler.postDelayed({ openWeb("https://h5.m.taobao.com/mlapp/olist.html", "debug") }, 400)
            // vc270c：整份 currentUltronData 落盘（定位商品名字段用）
            mainHandler.postDelayed({
                webView?.evaluateJavascript(
                    "(function(){try{return JSON.stringify(window.currentUltronData||null)}catch(e){return 'null'}})()"
                ) { res ->
                    try {
                        val f = java.io.File(getExternalFilesDir(null), "tb_ultron_dump.txt")
                        f.writeText(WebHook.unquoteJsString(res))
                        Log.i("TbProbe", "currentUltronData 已落盘 → tb_ultron_dump.txt")
                    } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
                }
            }, 17500)
            mainHandler.postDelayed({
                webView?.evaluateJavascript(TbTransitProbe.DOM_SCRAPE_PUBLIC) { result ->
                    try {
                        val f = java.io.File(getExternalFilesDir(null), "tb_olist_dom.txt")
                        f.appendText((result ?: "<null>") + "\n----\n")
                        Log.i("TbProbe", "正式层刮取完成 → tb_olist_dom.txt")
                    } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
                    closeWeb()
                }
            }, 18500)
        }
        // ★ vc664 一次性诊断：把淘宝校园驿站的**原始返回**落盘 + 强制对已缓存的拼多多单重查一遍，
        //   用来确认站点名到底在哪个字段里（只跑一次，看清字段就改代码，之后不用再跑）。
        //   adb shell am start -n com.agent.idcode/.MainActivity --ez debug_pdd_station_probe true
        if (intent?.getBooleanExtra("debug_pdd_station_probe", false) == true) {
            TbSchoolChannel.dumpRaw = true
            mainHandler.postDelayed({
                var n = 0
                synchronized(pddShortWb) {
                    for ((k, c) in PddCodeStore.all(this)) {
                        if (c.src != "pdd") continue
                        val sn = k.substringAfter(':', k)
                        val wb = c.waybill
                        if (wb.isBlank() || wb == sn) continue
                        pddShortWb.add(wb)
                        pddShortByWb[wb] = sn
                        n++
                    }
                }
                Log.i("CodeBridge", "站点探针：排队 $n 单，dumpRaw=true")
                maybeResolvePddShortCodes()
            }, 8000)
        }
        // ★ vc262 伪造数据源（端到端测试用，见 FakeTrack.kt 头注）：
        //   --es debug_fake_track '{"no":"TEST262","company":"圆通速递","pickupCode":"3-1-2345","station":"测试菜鸟驿站"}'
        //     → 种"运输中"基线；之后每轮轮询前进一步：派送中 → 已到驿站+取件码 → 稳态
        //   --ez debug_fake_clear true → 清除，恢复真实抓取
        intent?.getStringExtra("debug_fake_track")?.let { raw ->
            try {
                val o = org.json.JSONObject(raw)
                FakeTrack.install(
                    this,
                    FakeTrack.Plan(
                        no = o.optString("no"),
                        company = o.optString("company"),
                        pickupCode = o.optString("pickupCode"),
                        station = o.optString("station"),
                        cursor = 0
                    )
                )
            } catch (e: Throwable) {
                Log.w("FakeTrack", "debug_fake_track 解析失败: ${e.message}")
            }
        }
        if (intent?.getBooleanExtra("debug_fake_clear", false) == true) FakeTrack.clear(this)
        // ★ vc264p（同志 04:52 要求）：灌 3 条**测试物流**进第 5 栏，每条带 10 个轨迹节点
        //   --ez debug_seed_transit3 true      灌数据（落盘 ⇒ 杀进程后仍在）
        //   --ez debug_seed_transit_clear true 清掉，恢复真实抓取
        // 恢复测试物流（如果有；默认空 ⇒ 生产零影响）
        testTransit.clear()
        testTransit.addAll(loadTestTransit())
        if (intent?.getBooleanExtra("debug_seed_transit3", false) == true) seedTransitTestData()
        if (intent?.getBooleanExtra("debug_seed_transit_clear", false) == true) clearTransitTestData()
        // vc263 A/B 实验：setAlarmClock 特权通道是否扛得住 ColorOS 的 72 小时推迟
        // （实测结论：排上时正常，几分钟后仍被推迟 ⇒ 仅作开关保留，默认关）
        if (intent.hasExtra("debug_alarm_clock")) {
            getSharedPreferences("idcode_prefs", android.content.Context.MODE_PRIVATE).edit()
                .putBoolean("debug_alarm_clock", intent.getBooleanExtra("debug_alarm_clock", false))
                .apply()
        }
        //   --ez debug_track_selftest true  跑后台跟踪的「闭环自检」（判定表 + 真发一条通知）
        if (intent?.getBooleanExtra("debug_track_selftest", false) == true) selfTestTrack()
        //   --ez debug_mark_picked true  给缓存里所有取件码打上「已取件」标记（验证 vc229 标记/渲染/计数）
        //   --ei debug_picked_age_h 25    标记成「25 小时前取的」⇒ 验证满 24h 自动清除
        //   --ez debug_lock_picked true   关掉「仍是待取就撤销标记」的自愈（不关的话一刷新标记就没了）
        //   --es debug_kd_num <单号>  用快递100 官方接口查一次并把原始响应打到日志
        //                             （排查"填了 key 还是查不到"，可选 --es debug_kd_com / debug_kd_phone）
        intent?.getStringExtra("debug_kd_num")?.let { n ->
            Kd100Channel.selfTest(
                this, n,
                intent.getStringExtra("debug_kd_com") ?: "",
                intent.getStringExtra("debug_kd_phone") ?: ""
            )
        }
        //   --es debug_kd_free <单号>  同一个单号把 官方/手机版/网页版 三条路各查一遍打日志
        intent?.getStringExtra("debug_kd_free")?.let { n ->
            Kd100Channel.selfTestFree(
                this, n,
                intent.getStringExtra("debug_kd_com") ?: "",
                intent.getStringExtra("debug_kd_phone") ?: ""
            )
        }
        debugLockPicked = intent?.getBooleanExtra("debug_lock_picked", false) == true
        // ★ vc258：把订单列表原始响应落盘（排查"某个件为什么拉不到任何信息"）
        if (intent?.getBooleanExtra("debug_dump_orders", false) == true) {
            val ck = PddChannel.cookies(this)
            if (ck.isEmpty()) {
                Log.w("PddNative", "debug_dump_orders：本地没有拼多多 cookie")
            } else {
                val uid = PddChannel.uid(this).ifEmpty { PddChannel.uidFromCookie(ck) }
                val token = Regex("PDDAccessToken=([^;]+)")
                    .find(ck)?.groupValues?.get(1)?.trim().orEmpty()
                PddNative.debugDumpOrders(
                    this,
                    PddNative.Session(token = token, uid = uid, cookie = ck)
                )
            }
        }
        // ★ vc255：往第 5 栏轨迹缓存灌一条假数据并落盘。
        //   用途：在"账号没登录 / 没有在途单"的环境下验证
        //   saveTransitTraces → loadTransitTraceCache 这一整条闭环（filesDir 是内部存储，
        //   无 root 推不进去，只能让 App 自己写）。默认关，不影响生产。
        //   验证法：先带 true 启动一次，再 force-stop 冷启动一次，看
        //   「进页：缓存条数=0 轨迹缓存=1」即通。
        if (intent?.getBooleanExtra("debug_seed_trace", false) == true) {
            pddTransitTraceCache["260822-157894612560590"] = TransitTrace(
                waybillNo = "SF-TEST-VC255", latest = "调试用假轨迹",
                at = System.currentTimeMillis(), lines = "调试用假轨迹\n第二行"
            )
            saveTransitTraces()
            Log.i("TransitCache", "debug_seed_trace：已灌 1 条假轨迹并落盘")
        }
        //   --es debug_add_waybill <单号>  往「手动添加单号」列表加一个（vc259 验证用）。
        //   为什么不走 UI：设置页→手动添加行→弹窗输入，adb 点六七步还容易点歪；
        //   而这个开关只是替用户把单号写进 SP，与 UI 添加落到同一个 saveManualWaybills。
        //   验证法：带它 + --ez debug_open_transit true 启动，看第 5 栏出不出卡
        //   （日志「手动单号 1/1 回来：<单号>」+「真实渲染：all=N」）。
        //   清理：第 5 栏长按那张卡 → 删除（走的是 manualTransit.removeAll 老路）。
        intent?.getStringExtra("debug_add_waybill")?.let { no ->
            val existing = manualWaybills(this)
            if (existing.none { it.first == no }) {
                saveManualWaybills(this, existing + (no to ""))
                Log.i("ManualWaybill", "debug_add_waybill：已添加 $no")
                appToast(this, "已添加手动单号：$no")
            }
        }
        //   --es debug_del_waybill <单号>  从「手动添加单号」列表删一个（vc276）。
        //   背景：同志选 A 方案（删掉与淘宝在途重复的手单 JT3180960043943），但该手单
        //   已被 dedup 并进淘宝卡、不在第 5 栏屏上 ⇒ 长按删除够不着；「清空全部」按钮
        //   2026-09-21 应同志要求已删 ⇒ 只能走这个开关。与 UI 长按删除落同一 SP 键
        //   （saveManualWaybills），删完冷启进第 5 栏看「开始并行查 N 个手动单号」不含它。
        intent?.getStringExtra("debug_del_waybill")?.let { no ->
            val existing = manualWaybills(this)
            if (existing.any { it.first == no }) {
                saveManualWaybills(this, existing.filter { it.first != no })
                Log.i("ManualWaybill", "debug_del_waybill：已删除 $no（剩 ${existing.size - 1} 个）")
                appToast(this, "已删除手动单号：$no")
            } else {
                Log.i("ManualWaybill", "debug_del_waybill：列表里没有 $no，无需删")
            }
        }
        //   --es debug_jd_detail <dealId>  打开京东订单详情页并落盘 jd_detail.txt（vc278）。
        //   用途：订单列表 JSON 无地址字段（2500 单样本实测），无取件码的件要在第 4 栏显示
        //   「已到达<地址>」⇒ 先用它看详情页的接口响应/DOM 里有没有收件地址。
        //   --es debug_clear_tb_login true  清掉淘宝/菜鸟登录残留（换号排查用）
        if (intent?.getBooleanExtra("debug_clear_tb_login", false) == true) {
            clearTaobaoCookies()
            appToast(this, "已清理淘宝/菜鸟登录残留，请重新登录", true)
        }
        //   --ez debug_clear_all_tb true  彻底清空 WebView 全部 cookie，再回注拼多多/京东
        //   （用于验证"身份码显示旧账号"是否是 cookie jar 里的旧域 cookie 作祟）
        if (intent?.getBooleanExtra("debug_clear_all_tb", false) == true) {
            val cmAll = CookieManager.getInstance()
            cmAll.removeAllCookies {
                try { PddChannel.injectCookies(PddChannel.cookies(this)) } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
                try { JdChannel.injectCookies(JdChannel.cookies(this)) } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
                Log.i("TbLogin", "removeAllCookies 完成，已回注拼多多/京东（⚠️ 淘宝需重新登录）")
            }
        }
        //   --ez debug_tb_jump true  开关「淘宝身份码跳转」（验证用）
        if (intent?.hasExtra("debug_tb_jump") == true) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean("tb_id_autojump", intent.getBooleanExtra("debug_tb_jump", false))
                .apply()
        }
        //   --es debug_forget_no <单号>  把某个件从快照里删掉（验证首见到站会不会通知）
        intent?.getStringExtra("debug_forget_no")?.takeIf { it.isNotBlank() }?.let { no ->
            try {
                val list = ExpressStore.load(this).filterNot { it.no == no }
                ExpressStore.save(this, list)
                Log.i("TrackDbg", "已从快照移除 $no，剩 ${list.size} 条")
            } catch (t: Throwable) {
                Log.w("TrackDbg", "移除失败: ${t.message}")
            }
        }
        //   --ez debug_header_dump true  打开 EMS 首页并列出"可点击/头部"元素（找我的EMS入口）
        if (intent?.getBooleanExtra("debug_header_dump", false) == true) {
            DEBUG_DESKTOP_UA = true
            mainHandler.postDelayed({ openWeb("https://www.ems.com.cn/", "调试页", accent = "#007A33") }, 600)
            val js = "(function(){var out=[];var es=document.querySelectorAll('*');" +
                "for(var i=0;i<es.length;i++){var e=es[i];" +
                "var t=(e.innerText||'').trim();var r=e.getBoundingClientRect();" +
                "if(t.length>0&&t.length<=14&&r.width>0&&r.height>0&&r.top<260){" +
                "var cs=getComputedStyle(e);" +
                "if(cs.cursor==='pointer'||e.tagName==='A'||e.tagName==='BUTTON'){" +
                "out.push(t+'|'+e.tagName+'|'+(e.className||'').toString().slice(0,50));}}}" +
                "return JSON.stringify(out.slice(0,50));})()"
            mainHandler.postDelayed({
                webView?.evaluateJavascript(js) { r -> Log.i("EmsHead", r ?: "<null>") }
            }, 12_000)
        }
        //   --es debug_len_url <url>  打开某页并报告 appLen/bodyLen/正文样本（EMS 白屏诊断用）
        intent?.getStringExtra("debug_len_url")?.takeIf { it.isNotBlank() }?.let { u ->
            mainHandler.postDelayed({ openWeb(u, "诊断页", accent = "#007A33") }, 600)
            val js = "(function(){var a=document.getElementById('app');" +
                "return JSON.stringify({url:location.href, appLen:a?a.innerHTML.length:-1," +
                "bodyLen:document.body?document.body.innerHTML.length:-1," +
                "ready:document.readyState, text:(document.body?document.body.innerText:'').slice(0,400)});})()"
            mainHandler.postDelayed({
                webView?.evaluateJavascript(js) { r -> Log.i("EmsLen", r ?: "<null>") }
            }, 12_000)
        }
        //   --es debug_url_dump <url>  桌面 UA 打开某页，10 秒后把正文前 4KB 打进日志
        intent?.getStringExtra("debug_url_dump")?.takeIf { it.isNotBlank() }?.let { u ->
            DEBUG_DESKTOP_UA = true
            mainHandler.postDelayed({ openWeb(u, "调试页", accent = "#FE2C55") }, 600)
            mainHandler.postDelayed({
                val dumpJs = "(function(){var a=[];var as=document.querySelectorAll('a');" +
                    "for(var i=0;i<as.length;i++){var h=as[i].href||'';var t=(as[i].innerText||'').trim().slice(0,20);" +
                    "if(/order|trade|item|personal|买到|交易|订单/.test(h+t)){a.push(t+' -> '+h);}}" +
                    "return JSON.stringify({links:a.slice(0,40), text:document.body.innerText.slice(0,600)});})()"
                webView?.evaluateJavascript(dumpJs) { r ->
                    Log.i("UrlDump", r ?: "<null>")
                }
            }, 12_000)
        }
        //   --ez tb_rec true  录下淘宝页面自己发的 mtop 请求（api+data），用于纯 HTTP 重放
        DEBUG_IDCODE_FORCE = intent?.getBooleanExtra("debug_idcode_force", false) == true

        if (intent?.getBooleanExtra("tb_rec", false) == true) {
            DEBUG_TB_REC = true
            mainHandler.postDelayed({
                openWeb(TAOBAO_URL, "录制页", accent = "#FF5000")
            }, 500)
            mainHandler.postDelayed({
                val w = webView
                if (w == null) {
                    Log.w("TbRec", "webView 已关，读不到捕获")
                } else {
                    WebHook.readCaptures(w) { caps ->
                        Log.i("TbRec", "捕获 ${caps.size} 条")
                        for ((u, body) in caps.take(12)) {
                            Log.i("TbRec", "REQ " + u.take(900))
                            FileLog.log(this, "TbRec", "REQ " + u.take(900))
                        }
                    }
                }
            }, 14_000)
        }
        // ★ vc500：文件日志（持续把本进程 logcat 落盘，7 天保留）
        try {
            LogStore.start(this)
            LogStore.installCrashHandler(this)
        } catch (t: Throwable) {
            Log.w("LogStore", "启动失败: ${t.message}")
        }
        //   --es tb_probe_api <api>  纯 HTTP 调 mtop（B 路线可行性实验）
        intent?.getStringExtra("tb_probe_api")?.takeIf { it.isNotBlank() }?.let { api ->
            val data = intent.getStringExtra("tb_probe_data") ?: "{}"
            Log.i("TbProbe", "探测开始 api=$api data=$data")
            Thread {
                TbNativeProbe.call(this, api, data)
            }.start()
        }
        //   --ez debug_collect_logs true  自检：只跑日志收集，不弹另存为
        if (intent?.getBooleanExtra("debug_collect_logs", false) == true) {
            pddIo.execute {
                val (b, e) = collectLogcat()
                Log.i("ExportLogs", "自检 bytes=" + (b?.size ?: -1) + " err=" + e)
            }
        }
        //   --es debug_js "<js>"  在当前网页里执行一段 JS（调试用，结果写日志）
        intent?.getStringExtra("debug_js")?.takeIf { it.isNotBlank() }?.let { code ->
            mainHandler.postDelayed({
                val w = webView
                if (w == null) {
                    Log.w("WebJs", "没有打开的网页层")
                } else {
                    w.evaluateJavascript(code) { r -> Log.i("WebJs", "结果 " + r) }
                }
            }, 2500)
        }
        //   --ez debug_run_worker true  立刻排一次真实的 TrackWorker（含通知链路）
        if (intent?.getBooleanExtra("debug_run_worker", false) == true) {
            try {
                val req = androidx.work.OneTimeWorkRequest.Builder(TrackWorker::class.java).build()
                androidx.work.WorkManager.getInstance(this).enqueue(req)
                Log.i("TrackDbg", "已排入一次性 TrackWorker")
            } catch (t: Throwable) {
                Log.w("TrackDbg", "排入失败: ${t.message}")
            }
        }
        //   --ei debug_track_delay <秒>  退到后台后再跑一次真实抓取（验证轮询）
        if (intent?.hasExtra("debug_track_delay") == true) {
            val delay = intent.getIntExtra("debug_track_delay", 15).coerceIn(3, 120)
            Log.i("TrackDbg", "将在 ${'$'}delay 秒后（后台）跑一次 TrackFetcher")
            val appCtx = applicationContext
            Thread {
                try {
                    Thread.sleep(delay * 1000L)
                    Log.i("TrackDbg", "开始后台抓取")
                    val items = kotlinx.coroutines.runBlocking {
                        TrackFetcher.fetch(appCtx, ExpressStore.trackedNos(appCtx))
                    }
                    Log.i("TrackDbg", "后台抓取结果 ${'$'}{items.size} 条")
                    for (it in items) Log.i("TrackDbg", "  · ${'$'}{it.src} ${'$'}{it.no} ${'$'}{it.statusText} ${'$'}{it.pickupCode}")
                } catch (t: Throwable) {
                    Log.w("TrackDbg", "后台抓取异常: ${'$'}{t.message}")
                }
            }.start()
        }
        //   --es debug_id_url <url>  直接打开指定地址（身份码入口排查用）
        intent?.getStringExtra("debug_id_url")?.let { u ->
            mainHandler.postDelayed({ openWeb(u, "身份码") }, 800)
        }
        //   --ez debug_wipe_tb_all true  彻底清淘宝：cookie + WebStorage/IndexedDB + 取件码 + 在途
        if (intent?.getBooleanExtra("debug_wipe_tb_all", false) == true) {
            val cmW = CookieManager.getInstance()
            cmW.removeAllCookies {
                try { PddChannel.injectCookies(PddChannel.cookies(this)) } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
                try { JdChannel.injectCookies(JdChannel.cookies(this)) } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
                try { android.webkit.WebStorage.getInstance().deleteAllData() } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
                try { clearSourceData("tb") } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
                try {
                    val w0 = WebView(this)
                    w0.clearCache(true)
                    w0.clearFormData()
                    w0.destroy()
                } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("scope_uid_tb", "")
                    .remove("tb_id_storage_dirty").apply()
                Log.i("AcctScope", "已彻底清淘宝：cookie + WebStorage + 取件码 + 在途")
                appToast(this, "淘宝数据已彻底清理（含本地存储），请重新登录", true)
            }
        }
        intent?.getStringExtra("debug_jd_detail")?.let { id ->
            appToast(this, "京东详情抓取：$id")
            JdChannel.debugFetchDetail(this, id)
        }
        // ★ vc360：运单号探针 —— 打开京东详情页并自动点「查看物流」，
        //   扫全部旁听响应 + DOM，看能不能抄到真实运单号（jd_wuliu.txt）。
        //   能拿到 ⇒ 京东轨迹（快递100）和取件码（淘宝按运单号）两条路都能接上。
        intent?.getStringExtra("debug_jd_wuliu")?.let { id ->
            appToast(this, "京东运单号探针：$id")
            JdChannel.debugFetchDetail(this, id, probeWaybill = true)
        }
        // ★ vc279：灌一张**假的无码到达件**进第4栏（京东物流、无码、地址=北京大学），
        //   验证「已到达<地址>」卡片的渲染与点击复制。同志名下暂无真实到达件 ⇒ 先看效果。
        //   --ez debug_fake_arrived true 开启；重启不带该参数即消失（只在 onCreate 读）。
        fakeArrived = intent?.getBooleanExtra("debug_fake_arrived", false) == true
        // ★ vc368：分组 UI 自检 —— 灌 5 张测试卡（A 驿站 2 件、B 快递柜 3 件）
        if (intent?.getBooleanExtra("debug_fake_groups", false) == true) {
            val nowSead = System.currentTimeMillis()
            val seeds = listOf(
                Triple("990001-000000000000001", "1-1-1001", "清华大学"),
                Triple("990001-000000000000002", "1-1-1002", "清华大学"),
                Triple("990001-000000000000003", "1-1-1003", "清华大学"),
                Triple("990002-000000000000001", "2-2-2001", "北京大学"),
                Triple("990002-000000000000002", "2-2-2002", "北京大学"),
                Triple("990002-000000000000003", "2-2-2003", "北京大学"),
                Triple("990003-000000000000001", "9-9-9001", ""),
                Triple("990003-000000000000002", "9-9-9002", "")
            )
            for ((sn, code, st) in seeds) {
                PddCodeStore.put(this, "pdd:$sn", PddCodeStore.Cached(
                    src = "pdd", code = code, station = st,
                    waybill = "", latest = "", ts = nowSead
                ))
            }
            Log.i("FakeGroups", "已灌 5 张测试卡（2 个取件地址）")
        }
        // ★ vc368：清掉上面那批测试卡（自检完用，不影响真实数据）
        if (intent?.getBooleanExtra("debug_clear_groups", false) == true) {
            for (sn in listOf(
                "990001-000000000000001", "990001-000000000000002", "990001-000000000000003",
                "990002-000000000000001", "990002-000000000000002", "990002-000000000000003",
                "990003-000000000000001", "990003-000000000000002",
                "261020-597017259562219", "261020-522127941162219"
            )) PddCodeStore.remove(this, "pdd:$sn")
            Log.i("FakeGroups", "测试卡已清除")
        }
        if (intent?.getBooleanExtra("debug_mark_picked", false) == true)
            debugMarkAllPicked(intent.getIntExtra("debug_picked_age_h", 0))
                // ★ EMS 页面内操作调试：打开首页 → 用 JS 点底部「查快递」tab → 读 URL + 文本
        //   （真机证明订单列表在这个 tab 里，而"只加载页面"不发 order/v3/list）
                // 流体云 / 提升型通知自检：把官方 10 条硬性条件逐条对着真通知验一遍
        if (intent?.getBooleanExtra("debug_live_selftest", false) == true) {
            val r = LiveUpdate.selfTest(this)
            Log.i("LiveTest", r)
            try {
                java.io.File(getExternalFilesDir(null), "live_selftest.txt").writeText(r)
            } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
            appToast(this, "流体云自检完成（结果已落盘 live_selftest.txt）", true)
        }
        //   --ez debug_island true  ★ vc321：超级岛实验通知
        //     发一条假的「待取件 88-8-8888」：HyperOS 上应变超级岛，其它机器上是普通常驻通知
        //     （实验不受开关/门禁限制）。结果落盘 island_selftest.txt：
        //     含设备三查询 + 绕过引擎 + JSON 字节数 + 判读说明。
        if (intent?.getBooleanExtra("debug_island", false) == true) {
            val r = IslandHelper.debugPublish(this)
            Log.i("Island", r)
            try {
                java.io.File(getExternalFilesDir(null), "island_selftest.txt").writeText(r)
            } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
            appToast(this, "超级岛实验通知已发（结果落盘 island_selftest.txt）", true)
        }
        //   --es debug_pdd_url <url>  用拼多多通道加载指定页面并落盘捕获
        //   用途：实测「取件码」到底由哪个页面/接口给出来（订单页 orders.html 的响应里没有取件码）
        intent?.getStringExtra("debug_pdd_url")?.let { u ->
            appToast(this, "拼多多探测：$u")
            PddChannel.fetch(this, u) { pkgs, err ->
                Log.i("PddUrlProbe", "url=$u 解析出 ${pkgs.size} 条 err=$err")
            }
        }
        //   --es debug_pdd_search <运单号>  打开「我的快递」页并**自动填单号触发搜索**，
        //   旁听搜索接口 —— 定位「跨平台取件码/驿站名」的数据载体（vc303 三级兜底链侦察）。
        //   结果看 pdd_capture.txt：拿取件码（如 59-6-6012）搜出接口名与请求体后，
        //   就能照 PddNative 模式做纯 HTTP 直连。
        //   --ez debug_autocode_reset true  清掉自动查码的限频时间戳（调试重跑用）
        if (intent?.getBooleanExtra("debug_autocode_reset", false) == true) {
            val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
            val ed = sp.edit()
            var n = 0
            for (k in sp.all.keys.toList()) if (k.startsWith("autoq_")) { ed.remove(k); n++ }
            ed.apply()
            Log.i("AutoCode", "已清除 $n 个自动查码限频标记")
            appToast(this, "已重置自动查码限频（$n 条）")
        }
        //   --ez debug_stellar_input true  验证「Stellar shell 权限注入 input」是否可用
        //   （格口码查询的关键能力：页面只认真实输入 ⇒ 借 shell 特权进程发 input 命令）
        if (intent?.getBooleanExtra("debug_stellar_input", false) == true) {
            Thread {
                try {
                    if (!StellarBridge.isWatchdogAlive()) StellarBridge.bindWatchdog(this, true)
                    Thread.sleep(3500)
                    val uid = StellarBridge.selfUid()
                    Log.i("StellarInput", "保护进程 UID=$uid（0=root / 2000=shell 才有权注入输入）")
                    val ok = StellarBridge.injectInput(arrayOf("tap", "500", "500"))
                    Log.i("StellarInput", "input tap 结果=$ok（0=注入成功；-1=保护进程未绑定；其它=执行失败）")
                    appToast(this, "注入探测完成：UID=$uid 结果=$ok")
                } catch (t: Throwable) {
                    Log.w("StellarInput", "探测失败: ${t.message}")
                }
            }.start()
        }
        //   --ez debug_pdd_trackbox true  纯 HTTP 直连「我的快递」列表接口
        //   （express_trackbox/list）。依据：PddNative 历史实测「H5 业务接口不校验 anti-content」
        //   ⇒ 只要有登录 cookie 就能直连，响应里若有跨平台件+驿站+取件码，
        //   三级兜底链的拼多多一级就做成纯 HTTP（<1.5 秒，满足同志 5 秒硬指标）。
        if (intent?.getBooleanExtra("debug_pdd_trackbox", false) == true) {
            Thread {
                try {
                    val ck = PddChannel.cookies(this)
                    val uid = PddChannel.uid(this)
                    val url = "https://mobile.yangkeduo.com/proxy/api/api/express_trackbox/list?pdduid=$uid"
                    val body = "{\"offset\":0,\"count\":20,\"channel\":\"express_box_my_express\"," +
                            "\"entry_source\":null,\"status_type\":0,\"query_type\":0,\"anti_content\":\"x\"}"
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Cookie", ck)
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("Referer", "https://mobile.yangkeduo.com/psnl_goods_help.html")
                    conn.setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14; PKX110) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    )
                    conn.doOutput = true
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.outputStream.use { it.write(body.toByteArray()) }
                    val code = conn.responseCode
                    val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.use { it.readText() } ?: ""
                    Log.i("PddTrackbox", "HTTP $code len=${resp.length}")
                    java.io.File(getExternalFilesDir(null), "pdd_trackbox.txt")
                        .writeText("HTTP $code\nurl=$url\n\n$resp")
                    Log.i("PddTrackbox", "已落盘 pdd_trackbox.txt")
                } catch (t: Throwable) {
                    Log.w("PddTrackbox", "失败: ${t.message}")
                }
            }.start()
        }
        //   --es debug_pdd_detail <url>  打开「我的快递」**详情页**（assistant_waybill）
        //   读页面文本 + 抓接口 —— 格口码（如 59-6-6012）的载体页面。
        //   URL 来源：列表接口 track_info_list 的 jump_url 字段（拼上域名前缀）。
        //   --ez debug_pdd_dom true  枚举「我的快递」页的可输入元素形态
        //   （input/contenteditable/iframe）—— 定位 JS 合成事件填不进单号的根因。
        if (intent?.getBooleanExtra("debug_pdd_dom", false) == true) {
            appToast(this, "DOM 诊断中…")
            PddExpressChannel.probeDom(this)
        }
        //   --ez debug_direct_probe true  路线B侦察：纯HTTP试探详情接口(双基座×真单数据)
        //   + WebView直开详情页钩子捕获真实流量，材料落盘 pdd_direct_probe*.txt
        if (intent?.getBooleanExtra("debug_direct_probe", false) == true) {
            appToast(this, "直连侦察中…")
            PddExpressChannel.directProbe(this)
        }
        //   --ez debug_tb_school true  ★ vc308：淘宝校园「我的快递」页侦察 ——
        //   直开 pages-fast 校园页 + 钩页内 XHR，落盘 tb_school_probe.txt（接口名/取件码实证用）。
        if (intent?.getBooleanExtra("debug_tb_school", false) == true) {
            appToast(this, "淘宝校园页侦察中…")
            TbPickupProbe.probeSchoolPage(this)
        }
        //   --ez debug_tb_school_query true --es waybill <单号>  ★ vc308 探针 v2：
        //   直开校园页 + 模拟输入单号触发查询，钩出「按单号查包裹」的接口（落盘 tb_school_query.txt）。
        if (intent?.getBooleanExtra("debug_tb_school_query", false) == true) {
            val wb = intent.getStringExtra("waybill")?.trim().orEmpty().ifEmpty { "JT3180960043943" }
            appToast(this, "淘宝校园查询探针：$wb")
            TbPickupProbe.probeSchoolQuery(this, wb)
        }
        //   --ez debug_tb_school_v4 true --es waybill <单号>  ★ vc308 探针 v4（决定性）：
        //   给 lib.mtop.request 装记录器（JSONP 拿不到请求参数，只能这么抄），
        //   再模拟输入单号触发搜索，落盘 tb_school_v4.txt（含请求 api/data 原文）。
        if (intent?.getBooleanExtra("debug_tb_school_v4", false) == true) {
            val wb = intent.getStringExtra("waybill")?.trim().orEmpty().ifEmpty { "JT3180960043943" }
            appToast(this, "淘宝校园矩阵探针：$wb")
            TbPickupProbe.probeSchoolV4(this, wb)
        }
        //   --ez debug_tb_school_v5 true --es waybill <单号> --es phone <手机号>
        //   ★ vc308 探针 v5（实测矩阵）：直接甩 4 个候选接口，看谁返回包裹+取件码。
        if (intent?.getBooleanExtra("debug_tb_school_v5", false) == true) {
            val wb = intent.getStringExtra("waybill")?.trim().orEmpty().ifEmpty { "JT3180960043943" }
            val ph = intent.getStringExtra("phone")?.trim().orEmpty().ifEmpty { "13800138000" }
            appToast(this, "淘宝校园实测矩阵：$wb / $ph")
            TbPickupProbe.probeSchoolV5(this, wb, ph)
        }
        //   --ez debug_tb_channel true --es waybills "单号1,单号2"
        //   ★ vc308 生产通道单测：直接跑淘宝校园查码（不经过拼多多链），结果弹 toast。
        if (intent?.getBooleanExtra("debug_tb_channel", false) == true) {
            val ws = intent.getStringExtra("waybills")?.split(',')
                ?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            // ★ vc313：--ez debug_tb_dump true 连原始返回一起落盘（tb_school_raw.txt）
            TbSchoolChannel.dumpRaw = intent.getBooleanExtra("debug_tb_dump", false)
            appToast(this, "淘宝校园查码：${ws.size} 单")
            TbSchoolChannel.queryBatch(this, ws) { map ->
                appToast(this, "淘宝校园命中 ${map.size}：" + map.entries.joinToString { "${it.key}→${it.value}" }.take(160))
            }
        }
        //   --ez debug_dump_cache true  ★ vc311：取件码缓存全量落盘（files/pickup_cache.txt）
        //   用途：查「这单到底 src 是什么、pickedAt 是几、有没有手动标记」。
        if (intent?.getBooleanExtra("debug_dump_cache", false) == true) dumpPickupCache()
        //   --ez debug_pdd_list true  ★ vc315：拉一次拼多多「我的快递」全量列表并落盘
        //   （这张表按**收件手机号**聚合，淘宝/京东/极兔的件都在，自带状态文案 ——
        //     ★ vc316 起只当**取证工具**用：查"某个件在各平台眼里到底是什么状态"，
        //     不再拿它去判「取没取走」）
        if (intent?.getBooleanExtra("debug_pdd_list", false) == true) {
            appToast(this, "拉「我的快递」列表…")
            PddExpressChannel.fetchList(this, force = true) { list ->
                PddExpressChannel.dumpList(this, list)
                appToast(this, "我的快递 ${list.size} 条（已落盘 pdd_list.txt）")
            }
        }
        //   --ez debug_tb_school_all true  ★ vc315：按手机号拉一次淘宝校园「在站全量」
        if (intent?.getBooleanExtra("debug_tb_school_all", false) == true) {
            appToast(this, "拉淘宝校园在站列表…")
            TbSchoolChannel.queryBatch(this, emptyList()) { map ->
                try {
                    val sb = StringBuilder("now=" + System.currentTimeMillis() + " size=" + map.size + "\n")
                    for ((k, v) in map) sb.append(k).append(" -> ").append(v).append('\n')
                    java.io.File(getExternalFilesDir(null), "tb_school_all.txt").writeText(sb.toString())
                } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
                appToast(this, "淘宝校园在站 ${map.size} 件（已落盘 tb_school_all.txt）")
            }
        }
        //   --es debug_tb_picked "单号1,单号2"  ★ vc311：把指定运单当「淘宝已报终态」
        //   直接走 saveTbArrivedCache。★★ vc316：它现在是**回归测试工具** ——
        //   跑完去看缓存，pickedAt 应该**仅为 0**（证明自动打标记确实没了）。
        intent?.getStringExtra("debug_tb_picked")?.let { ws ->
            var n = 0
            for (w in ws.split(',')) {
                val t = w.trim()
                if (t.isEmpty()) continue
                tbPickedNow[t] = "已签收（调试）"
                n++
            }
            if (n > 0) {
                saveTbArrivedCache()
                Log.i("TbTransit", "debug_tb_picked：$n 单已走 saveTbArrivedCache")
            }
        }
        //   --es debug_tb_oid "运单号:订单号"  ★ vc314：给已存在的缓存卡补订单号
        //   （老卡建卡时还没存订单号 ⇒ 静默复查查不了；补一条就能自查状态）
        intent?.getStringExtra("debug_tb_oid")?.let { s ->
            val p = s.split(':')
            if (p.size == 2 && p[0].isNotBlank() && p[1].isNotBlank()) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("tb_oid_${p[0].trim()}", p[1].trim()).apply()
                Log.i("TbSilent", "debug_tb_oid：${p[0].trim()} -> ${p[1].trim()}")
            }
        }
        //   --ez debug_autoq_force true  ★ vc305：清全部查码限频戳 + 把缓存里的淘宝到站卡
        //   灌回内存 tbTransit + 立即跑**批量**查码链 —— adb 一条命令端到端验证（不必等 6h 限频）。
        if (intent?.getBooleanExtra("debug_autoq_force", false) == true) {
            val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
            val ed = sp.edit()
            sp.all.keys.filter { it.startsWith("autoq_") }.forEach { ed.remove(it) }
            ed.apply()
            var injected = 0
            for ((_, c) in PddCodeStore.all(this)) {
                if (c.src != "tb") continue
                if (tbTransit.any { it.optString("waybillNo") == c.waybill }) continue
                val card = tbCachedCard(c)
                // 模拟「列表只有官方短码」的待查态（码=单号后5位），逼批量链真正开跑；
                // 查回的码走 fillCode 正常回填，缓存真码有 keepReal 保护不会被洗掉。
                card.put("pickupCode", c.waybill.takeLast(5))
                tbTransit.add(card)
                injected++
            }
            appToast(this, "批量查码：强制触发（灌 $injected 单）")
            mainHandler.post { maybeAutoQueryRealCodeBatch() }
        }
        //   --ez debug_pdd_trackbox_detail true  批量试探「我的快递」**详情接口**
        //   （格口码 59-6-6012 的载体）。策略：列表接口已通（express_trackbox/list），
        //   详情接口大概率同族 —— 遍历同族接口名 × 请求体变体，哪个响应含格口码即命中。
        if (intent?.getBooleanExtra("debug_pdd_trackbox_detail", false) == true) {
            Thread {
                try {
                    val ck = PddChannel.cookies(this)
                    val uid = PddChannel.uid(this)
                    val names = listOf(
                        "detail", "info", "track", "query", "get",
                        "waybill_detail", "express_detail", "detail_v2", "get_express_info"
                    )
                    val bodies = listOf(
                        "{\"shipping_id\":384}",
                        "{\"shipping_id\":384,\"tracking_number\":\"JT3180960043943\"}",
                        "{\"tracking_number\":\"JT3180960043943\"}",
                        "{\"shipping_id\":384,\"tracking_number\":\"JT3180960043943\",\"channel\":\"express_box_my_express\"}"
                    )
                    val sb = StringBuilder()
                    var hits = 0
                    for (n in names) {
                        for (b in bodies) {
                            val url = "https://mobile.yangkeduo.com/proxy/api/api/express_trackbox/$n?pdduid=$uid"
                            try {
                                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                                conn.requestMethod = "POST"
                                conn.setRequestProperty("Cookie", ck)
                                conn.setRequestProperty("Content-Type", "application/json")
                                conn.setRequestProperty(
                                    "Referer",
                                    "https://mobile.yangkeduo.com/psnl_goods_help.html?_t_module_name=assistant_waybill&shipping_id=384&tracking_number=JT3180960043943"
                                )
                                conn.setRequestProperty(
                                    "User-Agent",
                                    "Mozilla/5.0 (Linux; Android 14; PKX110) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                                )
                                conn.doOutput = true
                                conn.connectTimeout = 5000
                                conn.readTimeout = 5000
                                conn.outputStream.use { it.write(b.toByteArray()) }
                                val code = conn.responseCode
                                val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                                    ?.bufferedReader()?.use { it.readText() } ?: ""
                                val mark = when {
                                    resp.contains("59-6-6012") -> " ★★★格口码命中!"
                                    resp.contains("取件码") -> " ★含取件码"
                                    resp.length > 120 && resp.contains("success") -> " 有数据"
                                    else -> ""
                                }
                                if (mark.isNotEmpty()) hits++
                                Log.i("PddTbDetail", "$n body=${b.take(44)} HTTP $code len=${resp.length}$mark")
                                sb.append("=== $n\nbody: $b\nHTTP $code\n${resp.take(1800)}\n\n")
                                Thread.sleep(350)
                            } catch (t: Throwable) {
                                Log.w("PddTbDetail", "$n 失败: ${t.message}")
                            }
                        }
                    }
                    java.io.File(getExternalFilesDir(null), "pdd_trackbox_detail.txt").writeText(sb.toString())
                    Log.i("PddTbDetail", "全候选试探完成（命中标记 $hits 处），落盘 pdd_trackbox_detail.txt")
                } catch (t: Throwable) {
                    Log.w("PddTbDetail", "外层失败: ${t.message}")
                }
            }.start()
        }
        intent?.getStringExtra("debug_pdd_detail")?.let { u ->
            appToast(this, "详情页探针：$u")
            PddExpressChannel.fetchCode(
                this, "detail-probe",
                customUrl = u, autoSearch = false,
                onDone = { r ->
                Log.i("PddDetail", "⇒ 取件码=${r.code} 驿站=${r.station} url=${r.pageUrl.take(70)}")
                try {
                    java.io.File(getExternalFilesDir(null), "pdd_detail_probe.txt").writeText(
                        "url=${r.pageUrl}\ncode=${r.code}\nstation=${r.station}\n\n---- 页面文本 ----\n${r.pageText}"
                    )
                } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
                }
            )
        }
        intent?.getStringExtra("debug_pdd_search")?.let { code ->
            val u = "https://mobile.yangkeduo.com/psnl_goods_help.html?_wv=41729&_wvx=10" +
                    "_t_module_name=assistant_search&refer_scene=wechat_share" +
                    "&refer_share_id=probe&refer_share_uin=probe&refer_share_channel=message"
            // 页面级自动动作脚本：找搜索输入框（placeholder 含 单号/物流）→ React 受控注入 →
            // input/change 事件 → 回车 + 点「搜索/查询」按钮。自身带重试（SPA 渲染慢）。
            // ⚠️ 严禁 // 行注释 —— autoJs 会被 evaluateJavascript 原样执行（本条不会被压行，
            //    但保持与其它注入 JS 同一纪律）。
            val autoJs = """
                (function(){
                  var CODE='$code';
                  var tries=0;
                  function fill(){
                    tries++;
                    var inputs=document.querySelectorAll('input');
                    var target=null;
                    for(var i=0;i<inputs.length;i++){
                      var ph=(inputs[i].placeholder||'')+'';
                      if(ph.indexOf('单号')>-1||ph.indexOf('物流')>-1){target=inputs[i];break;}
                    }
                    if(!target&&inputs.length) target=inputs[0];
                    if(!target){ if(tries<50) setTimeout(fill,500); return; }
                    try{
                      var desc=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(target),'value');
                      if(desc&&desc.set){ desc.set.call(target,CODE); } else { target.value=CODE; }
                      target.dispatchEvent(new Event('input',{bubbles:true}));
                      target.dispatchEvent(new Event('change',{bubbles:true}));
                    }catch(e){}
                    setTimeout(function(){
                      try{
                        var ev=new KeyboardEvent('keydown',{key:'Enter',keyCode:13,which:13,bubbles:true});
                        target.dispatchEvent(ev);
                        var els=document.querySelectorAll('button,div,span,a');
                        for(var j=0;j<els.length;j++){
                          var t=(els[j].innerText||'').trim();
                          if(t==='搜索'||t==='查询'){ els[j].click(); break; }
                        }
                      }catch(e){}
                    },400);
                  }
                  if(document.readyState==='complete'||document.readyState==='interactive'){ setTimeout(fill,900); }
                  else{ document.addEventListener('DOMContentLoaded',function(){ setTimeout(fill,900); }); }
                })()
            """.trimIndent()
            appToast(this, "拼多多搜码探针：$code")
            // ★ vc303：改走 PddExpressChannel（页面文本读取）—— 隐形 XHR 旁听 4 轮全空，
            //   页面渲染出结果后直接读 innerText 抠码（不需要抓接口）。
            PddExpressChannel.fetchCode(this, code, onDone = { r ->
                Log.i("PddSearch", "code=$code ⇒ 取件码=${r.code} 驿站=${r.station} url=${r.pageUrl.take(60)}")
                try {
                    java.io.File(getExternalFilesDir(null), "pdd_express_probe.txt").writeText(
                        "waybill=$code\ncode=${r.code}\nstation=${r.station}\nurl=${r.pageUrl}\n\n---- 页面文本 ----\n${r.pageText}"
                    )
                } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
            })
        }
        //   --es debug_pdd_noauth <分享链接>  直接 HTTP 调拼多多「物流分享页」的**免登录**接口
        //   （detail_noauth），把请求头组合逐条试一遍并落盘 pdd_noauth.txt。见 PddNoAuthProbe.kt
        intent?.getStringExtra("debug_pdd_noauth")?.let { u ->
            PddNoAuthProbe.probe(this, u)
            appToast(this, "免登录接口探测中…")
        }
        //   --es debug_pdd_share_clean <分享链接>  用**独立 cookie 目录**加载分享页
        //   （不注入登录态 ⇒ 模拟"外人点开"），旁听 detail_noauth 并落盘 pdd_capture.txt
        intent?.getStringExtra("debug_pdd_share_clean")?.let { u ->
            PddChannel.fetch(this, u, clean = true) { pkgs, err ->
                Log.i("PddShareClean", "解析出 ${pkgs.size} 条 err=$err")
            }
            appToast(this, "干净会话探测中…")
        }
        //   --es debug_pdd_trace <order_sn>  测「详情页→物流页→抠取件码」这条新链路
        //   （取件码只写在详情页的轨迹文案里，列表接口没有 —— 见 PddTraceChannel 的说明）
        intent?.getStringExtra("debug_pdd_trace")?.let { sn ->
            appToast(this, "拼多多轨迹测试：$sn")
            PddTraceChannel.fetch(this, sn) { r ->
                Log.i(
                    "PddTraceProbe",
                    "url=${r.pageUrl.take(120)} textLen=${r.text.length} waybill=${r.waybillNo} " +
                            "pickup=${r.pickupCode} err=${r.err}"
                )
                try {
                    java.io.File(getExternalFilesDir(null), "pdd_trace_probe.txt").writeText(
                        "url=" + r.pageUrl + "\nwaybill=" + r.waybillNo +
                                "\npickup=" + r.pickupCode + "\nerr=" + r.err +
                                "\n\ntraceLines=\n" + PddTraceChannel.parseTraceLines(r.text).joinToString("\n") +
                                "\n\n---- raw ----\n" + r.text
                    )
                } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
            }
        }
        //   --es debug_pdd_express <url>  测 vc252「直开物流页」这条路。
        //   传完整的 goods_express URL（形如 goods_express.html?tracking_number=…&order_sn=…）。
        //   用途：验证「直链会不会被弹回推荐流 / 能读到几段轨迹」，结果落盘 pdd_express_probe.txt。
        //   ★ 不接订单号：原生在途扫描只覆盖「待收货」tab，历史单本来就不在里面，
        //     硬要拿直链又得再开一次订单列表页 —— 那就不是这条路要验的东西了。
        intent?.getStringExtra("debug_pdd_express")?.let { key ->
            if (!key.contains("goods_express") && !key.startsWith("http")) {
                appToast(this, "请传完整 goods_express URL（不是订单号）", true)
                return@let
            }
            appToast(this, "直开物流页测试…")
            PddTraceChannel.fetchByUrl(this, key) { r ->
                val lines = PddTraceChannel.parseTraceLines(r.text)
                Log.i("PddExpressProbe",
                    "url=${r.pageUrl.take(140)} waybill=${r.waybillNo} " +
                            "orderSn=${r.realOrderSn} pickup=${r.pickupCode} err=${r.err}")
                Log.i("PddExpressProbe", "轨迹 ${lines.size} 段：\n" + lines.joinToString("\n"))
                try {
                    java.io.File(getExternalFilesDir(null), "pdd_express_probe.txt").writeText(
                        "key=$key\nurl=" + r.pageUrl + "\nwaybill=" + r.waybillNo +
                                "\norderSn=" + r.realOrderSn + "\npickup=" + r.pickupCode +
                                "\nerr=" + r.err +
                                "\n\nlines(${lines.size})=\n" + lines.joinToString("\n") +
                                "\n\n---- raw ----\n" + r.text
                    )
                } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
            }
        }
        //   --ez debug_open_transit true  进 App 直接开第 5 栏（在途快递）—— adb 测速用，
        //   不用手点底部栏，省得坐标对不准白跑一趟。
        if (intent?.getBooleanExtra("debug_open_transit", false) == true) {
            mainHandler.postDelayed({
                Log.i("TransitTiming", "debug_open_transit 进第5栏 t=" +
                        (System.currentTimeMillis() - transitEnterMs) + "ms")
                openInTransit()
            }, 1200)
        }
        //   --ez debug_pdd_dump true   把拼多多 cookie（完整值）+ 各域 jar cookie 落盘。
        //   用途：排除"服务端 40001 到底是 token 过期还是 cookie 集不全"——
        //   拿真实 cookie 从 PC 复打一次即可判定。★ 含凭据，只走调试入口，发布前应随其它 dump 一起收紧。
        if (intent?.getBooleanExtra("debug_pdd_dump", false) == true) dumpPddState()
        //   --ei debug_poll_min 15   直接设置轮询间隔（分钟）并重排，省得为了验证去点 UI
        if (intent?.hasExtra("debug_poll_min") == true) {
            ExpressStore.savePollIntervalMin(this, intent.getIntExtra("debug_poll_min", 0))
            TrackScheduler.apply(this)
            if (ExpressStore.pollIntervalMin(this) > 0) KeepAlive.arm(this)
            else KeepAlive.disarm(this)
        }
        // 设置页改完配置会 recreate 重刷颜色；这个标记让重建后自动回到设置页
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_REOPEN_SETTINGS, false)) {
            prefs.edit().remove(KEY_REOPEN_SETTINGS).apply()
            openSettings()
        }

        // 从桌面小组件点进来：直接进第4栏（取件码）并刷新，刷新完桌面也跟着更新
        if (intent?.getBooleanExtra(HomeWidgets.EXTRA_OPEN_PICKUP, false) == true) {
            mainHandler.postDelayed({ openPickup("all") }, 350)
        }
        // 在途快递小组件点进来：直接进第5栏
        if (intent?.getBooleanExtra(HomeWidgets.EXTRA_OPEN_TRANSIT, false) == true) {
            mainHandler.postDelayed({ openInTransit() }, 350)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Activity 已经在运行时，小组件点击走这里（PendingIntent 带 CLEAR_TOP）
        if (intent.getBooleanExtra(HomeWidgets.EXTRA_OPEN_PICKUP, false) && pickupLayer == null) {
            mainHandler.postDelayed({ openPickup("all") }, 350)
        }
        if (intent.getBooleanExtra(HomeWidgets.EXTRA_OPEN_TRANSIT, false) && transitLayer == null) {
            mainHandler.postDelayed({ openInTransit() }, 350)
        }
    }

    // ---------- 主界面 ----------

    // ================= 首页组件（ColorOS 17 流体设计 · vc563） =================
    // 规矩见 UiTokens：间距 8 档 / 圆角 6 档 / 字号 8 级 / 无渐变 / 品牌色只在 36dp 方块里

    /**
     * 首页卡片底（v1.4.19 凝光）：超椭圆 + 昼白卡 / 夜 10% 白 + 0.6dp 细描边。
     * 夜间额外画顶部轮廓光 —— 纯黑底上全靠这道光把卡片的边"提"起来。
     */
    private fun homeCardBg(radiusDp: Int = UiTok.R20): android.graphics.drawable.Drawable =
        sq(radiusDp, cCardTop, cCardBottom, stroke = cBorder, strokeDp = 0.6f, contour = isDark())

    /**
     * 36dp 身份方块 —— ★ v2 马卡龙：淘=樱 / 拼=薰 / 菜=薄（首页与 dock 同一套），
     * 途/云 = 天青 / 薄荷绿；字母用墨色（v2 .id .ch = ink），身份靠**底色**区分。
     */
    private fun brandTile(badge: String, colorHex: String): TextView {
        val tileBg: Int
        val tileText: Int
        when (badge) {
            "淘" -> { tileBg = UiTok.macRose(isDark()); tileText = cTextPrimary }
            "拼" -> { tileBg = UiTok.macLilac(isDark()); tileText = cTextPrimary }
            "菜" -> { tileBg = UiTok.macMint(isDark()); tileText = cTextPrimary }
            "途" -> { tileBg = UiTok.iconBlueBg(isDark()); tileText = cAccent }
            "云" -> { tileBg = UiTok.iconGreenBg(isDark()); tileText = UiTok.iconGreenText(isDark()) }
            else -> {
                val c = Color.parseColor(colorHex)
                tileBg = UiTok.brandWithAlpha(c, UiTok.brandTileAlpha(isDark()))
                tileText = c
            }
        }
        return TextView(this).apply {
            text = badge
            textSize = UiTok.T19
            typeface = tfMedium
            gravity = Gravity.CENTER
            setTextColor(tileText)
            background = sq(UiTok.R14, tileBg)
        }
    }

    private fun homeDivider(): View = View(this).apply {
        setBackgroundColor(UiTok.dividerColor(isDark()))
    }

    /** 卡内列表行（64dp）：品牌方块 + 标题 + 右侧状态 + 箭头 */
    private fun homeRow(
        badge: String, colorHex: String, title: String, status: String,
        onClick: (View) -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((UiTok.S16 * dp).toInt(), 0, (UiTok.S16 * dp).toInt(), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { v -> onClick(v) }
        }
        row.addView(brandTile(badge, colorHex), LinearLayout.LayoutParams(
            (UiTok.BRAND_TILE * dp).toInt(), (UiTok.BRAND_TILE * dp).toInt()
        ))
        row.addView(TextView(this).apply {
            text = title
            // ★ v2 统一：行卡主标题 16px Medium（.rowcard .k .t）
            textSize = UiTok.T16
            typeface = tfMedium
            setTextColor(cTextPrimary)
            maxLines = 1
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = (UiTok.S12 * dp).toInt()
        })
        if (status.isNotBlank()) {
            row.addView(TextView(this).apply {
                text = status
                textSize = UiTok.T12
                setTextColor(cTextTertiary)
                maxLines = 1
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = (UiTok.S8 * dp).toInt() })
        }
        row.addView(TextView(this).apply {
            text = "\u203a"   // ›
            textSize = UiTok.T20
            setTextColor(cTextFaint)
        })
        applyPress(row)
        return row
    }

    /** 分组标题（只能看不能点） */
    private fun homeSectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = UiTok.T13
        setTextColor(cTextSecondary)
    }

    private fun buildHome(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // ★ v2 马卡龙：首页底铺环境光
            background = AmbientGlowDrawable(isDark())
            // ★★ vc674：**初始内边距就要带上状态栏高度**。
            //   以前只靠 setOnApplyWindowInsetsListener 补 top，而首页是动态 addView 的，
            //   重建时（标记已取件 → rebuildHome）**不会重新分发一次 inset** ⇒ top=0
            //   ⇒ 标题直接压到状态栏上（同志 2026-11-03 实测："打上已取件就重叠"）。
            setPadding(
                (UiTok.S20 * dp).toInt(), statusBarTop + (UiTok.S12 * dp).toInt(),
                (UiTok.S20 * dp).toInt(), (UiTok.S24 * dp).toInt()
            )
            setOnApplyWindowInsetsListener { v, insets ->
                val topInset = insets.getInsets(WindowInsets.Type.statusBars()).top
                if (topInset > 0) statusBarTop = topInset
                v.setPadding(
                    (UiTok.S20 * dp).toInt(), maxOf(topInset, statusBarTop) + (UiTok.S12 * dp).toInt(),
                    (UiTok.S20 * dp).toInt(), (UiTok.S24 * dp).toInt()
                )
                insets
            }
        }

        // ---------- 顶栏（v2 .bar）：26px 大标题 + 12px 副行；齿轮在右上角 36dp ----------
        box.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = UiTok.T26
            typeface = tfMedium
            letterSpacing = -0.015f
            setTextColor(cTextPrimary)
            maxLines = 1
        })
        box.addView(TextView(this).apply {
            val stamp = try {
                java.text.SimpleDateFormat("M月d日 E · HH:mm", java.util.Locale.CHINA)
                    .format(java.util.Date())
            } catch (_: Throwable) { "" }
            text = if (stamp.isEmpty()) "刚抓取" else "$stamp 刚抓取"
            textSize = UiTok.T12
            setTextColor(cTextSecondary)
            letterSpacing = 0.01f
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (4 * dp).toInt() })

        box.addView(makeSpacer(UiTok.S16))

        // ---------- 主卡：取件码（0 件=占位；≥1 件=大数字 + 2×2 网格，最多 4 条） ----------
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val pkgTime = prefs.getString(KEY_LAST_PKG_TIME, null)
        // ★★ v1.4.10：**首页网格与取件码页同源**（同志 2026-11-10 报「首页偶现有取件码但不显示，
        //   要点开查看全部才看得到」）。
        //   根因：首页读的是**持久缓存 [PddCodeStore]**，取件码页读的是**本轮实时合并出来的
        //   pending**。两套数据各自过滤（首页还多一道 looksTaken），任一步骤错位（缓存码被
        //   冲突清理搬走 / 文案被判已取走 / 补码只落到页面还没落盘）就会出现
        //   「页面上有码，首页 0 件」。⇒ 首页优先用 [homePickupCodes]（renderMerged 发布），
        //   只有它还没发布过（冷启动第一帧）才回落缓存。
        val feed = homePickupCodes
        val codeList: List<PddCodeStore.Cached>
        val feedRows: List<Pair<String, String>>
        if (feed != null) {
            codeList = emptyList()
            // ★★ v1.4.11：网格只画**还没取**的码（发布侧已过滤，这里再挡一层）。
            //   取件后首页与取件码页同步撤掉，不留"已取件"的格子。
            feedRows = feed.filter { !it.picked }.map { it.code to it.station.ifBlank { "驿站待取" } }
        } else {
            // ★★ v1.4.16：**冷启动第一帧也过黑名单**（同志 14:20：「首页会出现在黑名单中的
            //   已删除的取件码，显示待取」）。主路径靠 renderMerged 发布的 [homePickupCodes]，
            //   但冷启动首帧还没发布过、走的是这条回落路径 ⇒ 这里不挡就照样冒出已删的码。
            codeList = PddCodeStore.all(this).entries
                .filter { (k, c) ->
                    !PddCodeStore.isPicked(c) && c.code.isNotBlank() &&
                        !PddCodeStore.looksTaken(c) && !isCachedEntryMuted(k, c)
                }
                .sortedByDescending { it.value.ts }
                // ★ vc605：同一取件码可能被两个来源各存一条（截图中 1-3-4021 出现两次）
                //   ⇒ 按码去重，保留时间最新的那条
                .distinctBy { it.value.code }
                .map { it.value }
            feedRows = codeList.map { it.code to it.station.ifBlank { "驿站待取" } }
        }
        // ★ vc584：条数只认缓存（清空缓存就该显示 0）；旧写法回退 SP 历史计数 ⇒ 清了还显示旧数字
        val pkgCount = feedRows.size
        val primary = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = homeCardBg()
            if (!isDark()) elevation = 1.5f * dp
            setPadding(
                (UiTok.S20 * dp).toInt(), (UiTok.S16 * dp).toInt(),
                (UiTok.S20 * dp).toInt(), (UiTok.S16 * dp).toInt()
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { openPickup("all") }
        }
        // ★ v2 hero 顶行：左「待取」胶囊 + 右「驿站」印章（.chip / .stamp）
        val heroTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heroTop.addView(TextView(this).apply {
            text = "待取"
            textSize = UiTok.T12
            typeface = tfMedium
            setTextColor(cAccent)
            background = sq(UiTok.R999, UiTok.accentSoft(isDark()))
            setPadding(
                (UiTok.S12 * dp).toInt(), (UiTok.S4 * dp).toInt(),
                (UiTok.S12 * dp).toInt(), (UiTok.S4 * dp).toInt()
            )
        })
        heroTop.addView(TextView(this).apply {
            text = feedRows.firstOrNull()?.second?.take(10) ?: "驿站待取"
            textSize = UiTok.T115
            setTextColor(cTextTertiary)
            letterSpacing = 0.01f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = (UiTok.S8 * dp).toInt()
        })
        primary.addView(heroTop, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        // ★★ v1.4.19（凝光）：待取件数从"3 件待取"一句话改成**大数字 + 小单位**。
        //   凝光只允许两处大字：这里是 56sp 的数字，取件码页是 41sp 的等宽码；除此之外没有第三处。
        //   字号差拉到 3.7 倍，扫一眼先看见"几件"，再看见"哪件"。
        // ★ vc585 的老约束仍要守：0 件也得显示 0，且字号和 3 件时**同一档**。
        val countRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        countRow.addView(TextView(this).apply {
            text = pkgCount.toString()
            textSize = UiTok.T68          // ★ v2 .count .n = 68px
            typeface = tfMedium
            setTextColor(cTextPrimary)
            fontFeatureSettings = "tnum"
            // ★ 别关 includeFontPadding：关了大数字上下会被裁掉一截（真机实测）
        })
        countRow.addView(TextView(this).apply {
            text = " 件"
            textSize = UiTok.T15
            setTextColor(cTextSecondary)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (UiTok.S8 * dp).toInt() })
        primary.addView(countRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (UiTok.S8 * dp).toInt() })
        // ★ v2 .rule：计数与取件码之间一条极淡分割线
        primary.addView(View(this).apply {
            background = android.graphics.drawable.ColorDrawable(cDivider)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, (UiTok.HAIR * dp).toInt())
        ).apply { topMargin = (UiTok.S16 * dp).toInt() })

        // ★ v2 .codeblk：主码 41px 等宽（唯一大字之一）+ 绿点驿站行
        if (feedRows.isNotEmpty()) {
            primary.addView(TextView(this).apply {
                text = "取件码"
                textSize = UiTok.T12
                setTextColor(cTextTertiary)
                letterSpacing = 0.03f
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (UiTok.S16 * dp).toInt() })
            primary.addView(TextView(this).apply {
                text = feedRows.first().first
                textSize = UiTok.T41
                typeface = tfMedium
                fontFeatureSettings = "tnum"
                setTextColor(cTextPrimary)
                letterSpacing = -0.005f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            primary.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(View(this@MainActivity).apply {
                    background = sq(UiTok.R999, UiTok.greenDot(isDark()))
                }, LinearLayout.LayoutParams((6 * dp).toInt(), (6 * dp).toInt()))
                addView(TextView(this@MainActivity).apply {
                    text = feedRows.first().second
                    textSize = UiTok.T13
                    setTextColor(cTextSecondary)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = (7 * dp).toInt() })
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (UiTok.S8 * dp).toInt() })
        }

        // ★ v2：主卡不再重复「更新于 xx:xx」（顶栏副行已有"刚抓取"、印章已有驿站），
        //   0 件时才给一句占位，避免空卡。
        if (pkgCount == 0) {
            primary.addView(TextView(this).apply {
                text = "暂无待取件 · 点开查看全部"
                textSize = UiTok.T13
                setTextColor(cTextTertiary)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (UiTok.S4 * dp).toInt() })
        }

        // ★ v2 压缩：主码已 41px 独占一行，其余码只做**一行次要信息**，不再铺 2×2 网格。
        //   旧网格会把同一个码再画一遍（主卡 41px + 网格 15sp），且把首页撑过一屏导致
        //   底部身份码卡被裁 —— 这里改成「其余 N 件」的单行胶囊，高度从 ~120dp 降到 ~26dp。
        if (feedRows.size > 1) {
            val moreRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val rest = feedRows.drop(1)
            moreRow.addView(TextView(this).apply {
                text = "其余 ${rest.size} 件"
                textSize = UiTok.T115
                setTextColor(cTextTertiary)
            })
            for (c in rest.take(3)) {
                moreRow.addView(TextView(this).apply {
                    text = c.first
                    textSize = UiTok.T115
                    typeface = tfMedium
                    fontFeatureSettings = "tnum"
                    setTextColor(cTextSecondary)
                    background = sq(UiTok.R999, UiTok.gtagBg(isDark()))
                    setPadding((8 * dp).toInt(), (3 * dp).toInt(), (8 * dp).toInt(), (3 * dp).toInt())
                    maxLines = 1
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = (6 * dp).toInt() })
            }
            primary.addView(moreRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (UiTok.S12 * dp).toInt() })
        }

        val goBtn = TextView(this).apply {
            text = "查看全部"
            textSize = UiTok.T15
            typeface = tfMedium
            gravity = Gravity.CENTER
            // ★ v1.4.19（凝光）：昼夜同一个凝光蓝胶囊（上浅下深 + 顶部亮线）。
            //   夜间不再反白 —— 反白在纯黑上最"稳"，但和系统里的主按钮不是一套语言；
            //   凝光的 CTA 就该是那支蓝，深浅模式只换蓝的深浅。
            setTextColor(Color.WHITE)
            background = ctasBg()
            isClickable = true
            setOnClickListener { openPickup("all") }
        }
        // ★ v2 .herofoot：主按钮 + 一个纯文字按钮（ghost），胶囊高 40dp
        val heroFoot = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heroFoot.addView(goBtn, LinearLayout.LayoutParams(
            0, (UiTok.S40Dp * dp).toInt(), 1f
        ))
        heroFoot.addView(TextView(this).apply {
            text = "标记已取件"
            textSize = UiTok.T15
            gravity = Gravity.CENTER
            setTextColor(cTextSecondary)
            isClickable = true
            setOnClickListener { openPickup("all") }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, (UiTok.S40Dp * dp).toInt()
        ).apply { leftMargin = (UiTok.S16 * dp).toInt() })
        primary.addView(heroFoot, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (UiTok.S16 * dp).toInt() })
        applyPress(primary)
        box.addView(primary)

        box.addView(makeSpacer(UiTok.S12))

        // ---------- 「在途」分组（v2 .sect-t 在卡外） ----------
        val transitCount = pddTransit.size + jdTransit.size + miTransit.size +
                tbTransit.size + manualTransit.size + testTransit.size
        box.addView(homeSectionLabel("在途"), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (UiTok.S16 * dp).toInt(); bottomMargin = (UiTok.S8 * dp).toInt() })

        // ★ v2 .rowcard：41dp 图标 + 标题/摘要两行 + 右侧 19px 数字 + 「件 ›」
        val transitCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = homeCardBg()
            setPadding((13 * dp).toInt(), (13 * dp).toInt(), (17 * dp).toInt(), (13 * dp).toInt())
            minimumHeight = (64 * dp).toInt()
            if (!isDark()) elevation = 1.5f * dp
            isClickable = true
            setOnClickListener { openInTransit() }
        }
        transitCard.addView(TextView(this).apply {
            text = "途"
            textSize = UiTok.T15
            typeface = tfMedium
            gravity = Gravity.CENTER
            setTextColor(cAccent)
            background = sq(UiTok.R14, UiTok.iconBlueBg(isDark()))
        }, LinearLayout.LayoutParams((41 * dp).toInt(), (41 * dp).toInt()))
        val transitTextBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        transitTextBox.addView(TextView(this).apply {
            text = getString(R.string.bar_transit_title)
            textSize = UiTok.T16
            typeface = tfMedium
            setTextColor(cTextPrimary)
        })
        transitTextBox.addView(TextView(this).apply {
            text = if (transitCount > 0) "运输中的包裹 · 点击查看全部轨迹"
                   else "暂无在途 · 下拉取件码页可刷新"
            textSize = UiTok.T125
            setTextColor(cTextSecondary)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (4 * dp).toInt() })
        transitCard.addView(transitTextBox, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ).apply { leftMargin = (13 * dp).toInt() })
        transitCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = transitCount.toString()
                textSize = UiTok.T19
                typeface = tfMedium
                fontFeatureSettings = "tnum"
                setTextColor(cTextPrimary)
            })
            addView(TextView(this@MainActivity).apply {
                text = "件"
                textSize = UiTok.T14
                setTextColor(cTextSecondary)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = (2 * dp).toInt() })
            addView(TextView(this@MainActivity).apply {
                text = "›"
                textSize = UiTok.T15
                setTextColor(cTextFaint)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = (6 * dp).toInt() })
        })
        applyPress(transitCard)
        box.addView(transitCard)

        box.addView(makeSpacer(UiTok.S16))

        // ---------- 身份码：分组标题（卡外）+ 横排三格 ----------
        box.addView(homeSectionLabel("身份码"), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (UiTok.S8 * dp).toInt() })

        // ★ v2 .ids：一张卡内横排三格，每格 = 马卡龙色块 + 11.5px 标签
        val idCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = homeCardBg()
            setPadding((11 * dp).toInt(), (11 * dp).toInt(), (11 * dp).toInt(), (11 * dp).toInt())
            if (!isDark()) elevation = 1.5f * dp
        }
        fun idTile(ch: String, label: String, bg: Int, action: () -> Unit): View =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding((8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt())
                background = sq(UiTok.R14, bg)
                addView(TextView(this@MainActivity).apply {
                    text = ch
                    textSize = UiTok.T19
                    typeface = tfMedium
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setTextColor(cTextPrimary)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (28 * dp).toInt()
                ))
                addView(TextView(this@MainActivity).apply {
                    text = label
                    textSize = UiTok.T115
                    setTextColor(cTextTertiary)
                    letterSpacing = 0.02f
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (6 * dp).toInt() })
                isClickable = true
                setOnClickListener { action() }
                applyPress(this)
            }
        val tileLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        idCard.addView(idTile("淘", getString(R.string.bar_taobao), UiTok.macRose(isDark())) {
            if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("tb_id_autojump", false))
                openTaobaoIdentity()
            else openWeb(TAOBAO_URL, getString(R.string.web_title), accent = "#FF5000")
        }, tileLp.apply { rightMargin = (9 * dp).toInt() })
        idCard.addView(idTile("拼", getString(R.string.bar_pdd_code), UiTok.macLilac(isDark())) {
            if (PddChannel.isLoggedIn(this)) openPddIdentity() else openPddLogin()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = (4 * dp).toInt(); rightMargin = (4 * dp).toInt()
        })
        idCard.addView(idTile("菜", getString(R.string.bar_cainiao), UiTok.macMint(isDark())) {
            openWeb(TAOBAO_URL, getString(R.string.web_title_cainiao), cainiaoIdentity = true)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = (9 * dp).toInt()
        })
        box.addView(idCard)

        return box
    }

    // ---------- 小米登录（取件码数据源） ----------

    private var miLoginPoll: Runnable? = null

    /**
     * 小米登录两段式：
     * ① 后台线程先调 serviceLogin 拿真正的登录页地址 → WebView 打开
     * ② 轮询到 passToken/userId/deviceId 齐 → 后台 mint() 换 serviceToken 存档
     */
    /**
     * 顺丰登录：WebView 打开官方登录页（手机号 + 短信验证码），登录态留在 jar 里。
     * （顺丰数据源已移除，保留此注释仅为说明历史设计。）
     */
    /** 登录页顶部「完成」按模式分发（撤抖音时被误删，这里恢复） */
    private fun finishLogin(mode: String) {
        when (mode) {
            "pdd" -> finishPddLogin()
            "jd" -> finishJdLogin()
            "tb" -> finishTbLogin()
            "mi" -> finishMiLogin()
        }
    }

    /** 各平台自家 App 的 scheme：一律在 WebView 内拦掉，别扔给系统（撤抖音时被误删，这里恢复） */
    private fun ownAppSchemes(loginMode: String?): Set<String> = when (loginMode) {
        "pdd", "mdkd" -> setOf("pinduoduo", "xunmeng", "pddopen")
        "jd" -> setOf("openapp.jdmobile", "openapp.jdmoile", "jingdong")
        "mi" -> setOf("mishop", "mi", "miuistatus")
        else -> setOf("taobao", "tbopen")
    }

    /** 哪些域名留在 WebView 内打开（撤抖音时被误删，这里恢复） */
    private fun inAppHost(host: String, loginMode: String?): Boolean {
        val fixed = listOf(
            "taobao.com", "tmall.com", "tb.cn", "alipay.com", "cainiao.com", "cainiao.com.cn",
            "pinduoduo.net", "pinduoduo.com", "yangkeduo.com",
        )
        if (fixed.any { host.endsWith(it) }) return true
        return ownHosts(loginMode).any { host.endsWith(it) }
    }

    /** 退出某个数据源的登录（二次确认后执行） */
    private fun confirmLogoutSource(name: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("退出 $name 登录")
            .setMessage("退出会**删除该账号的全部数据**（取件码、在途、轨迹、后台快照、桌面组件），等同清除数据；" +
                    "App 的外观设置不受影响。\n\n退出后需要重新登录，才能继续抓取 $name 的快递。")
            .setNegativeButton("取消", null)
            .setPositiveButton("退出") { _, _ ->
                try { action() } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
                appToast(this, "已退出 $name 登录", true)
                refreshSettingsIfOpen()
            }
            .show()
    }

    private fun refreshSettingsIfOpen() {
        if (settingsLayer == null) return
        settingsLayer?.let { root.removeView(it) }
        settingsLayer = null
        openSettings()
    }

    /**
     * 小米"⋮"弹窗：接入说明 + **数据源开关**（在这里开/关，不用去登录/退出绕一圈）。
     * 关闭数据源只影响取件码/在途快递是否加载小米，**不动登录态**。
     */
    private fun openMiMoreDialog() {
        val on = miEnabled()
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.mi_more_title))
            .setMessage(
                getString(R.string.mi_capability_note) +
                    "\n\n当前状态：" + (if (on) "已启用" else "已关闭") +
                    "\n（启用后取件码和在途快递才会加载小米数据；小米走网页抓取，加载比其他源慢）"
            )
            .setPositiveButton(if (on) "关闭数据源" else "开启数据源") { _, _ ->
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("mi_enabled", !on).apply()
                appToast(this, if (on) "小米数据源已关闭" else "小米数据源已开启")
                // 刷新设置页行的显示
                settingsLayer?.let { root.removeView(it) }
                settingsLayer = null
                openSettings()
            }
            .setNegativeButton(android.R.string.ok, null)
            .showAnimated()
    }

    /** 小米数据源开关（默认关：小米走 H5 抓取最慢，由用户主动开启承担这段等待） */
    private fun miEnabled(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("mi_enabled", false)

    private fun openMiLogin() {        appToast(this, R.string.mi_fetching_page)
        pddIo.execute {
            try {
                val url = MiChannel.fetchLoginPageUrl()
                runOnUiThread {
                    if (webLayer != null) return@runOnUiThread
                    openWeb(
                        url, getString(R.string.mi_login_title),
                        accent = "#FF6900", loginMode = "mi"
                    )
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    appToast(this, getString(R.string.mi_page_fail, e.message), true)
                }
            }
        }
    }

    private fun startMiLoginPoll() {
        stopMiLoginPoll()
        val r = object : Runnable {
            var tries = 0
            override fun run() {
                tries++
                // 自动路径只认 STS 令牌（别一看到账号 cookie 就急着去换票 —— 那时令牌还没写进来）
                if (MiChannel.isLoginDone()) {
                    finishMiLogin(allowMint = false)
                    return
                }
                // 每 20 次（约 30 秒）打一次诊断，便于确认 STS 跳转到底有没有发生
                if (tries % 20 == 0) {
                    android.util.Log.i("MiLogin", "waiting STS: tries=$tries ${MiChannel.diagSts()}")
                }
                if (tries >= 80) return
                mainHandler.postDelayed(this, 1500)
            }
        }
        miLoginPoll = r
        mainHandler.postDelayed(r, 1500)
    }

    private fun stopMiLoginPoll() {
        miLoginPoll?.let { mainHandler.removeCallbacks(it) }
        miLoginPoll = null
    }

    /**
     * 登录收尾。
     * @param allowMint 是否允许走"自己换票"的兜底路。自动轮询只等 STS 令牌（false），
     *                  只有用户**主动点顶栏「完成」**才给它 true（用户自己确认已经登好了）。
     */
    private fun finishMiLogin(allowMint: Boolean = true) {
        stopMiLoginPoll()
        val pass = MiChannel.passCookies()
        val userId = pass["userId"].orEmpty()
        val cUserId = pass["cUserId"].orEmpty()

        // ★ 主路径：页面自己的 STS 跳转已经把令牌写进 jar（api.assistant.miui.com 域）。
        //   参考项目就是优先用这个，mint() 只是兜底 —— 只走 mint 会拿到"登录验证失败"。
        val jarToken = MiChannel.stsToken()
        if (jarToken.isNotEmpty()) {
            MiChannel.saveLogin(this, jarToken, cUserId, userId)
            try { CookieManager.getInstance().flush() } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
            appToast(this, R.string.mi_login_ok)
            closeWeb()
            if (settingsLayer != null) {
                settingsLayer?.let { root.removeView(it) }
                settingsLayer = null
                openSettings()
            }
            return
        }

        if (!allowMint) {
            // 自动轮询走到这里说明令牌还没到：继续等，别去碰那条会被拒的换票路
            return
        }
        if (!MiChannel.hasPassCookies()) {
            appToast(this, R.string.mi_login_not_yet)
            return
        }
        appToast(this, R.string.mi_mint_doing)
        pddIo.execute {
            try {
                val r = MiChannel.mint()
                MiChannel.saveLogin(this, r.token, r.cUserId.ifEmpty { cUserId }, userId)
                runOnUiThread {
                    appToast(this, R.string.mi_login_ok)
                    closeWeb()
                    if (settingsLayer != null) {
                        settingsLayer?.let { root.removeView(it) }
                        settingsLayer = null
                        openSettings()
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.w("MiLogin", "mint failed: ${e.message}")
                runOnUiThread {
                    appToast(this, getString(R.string.mi_mint_fail, e.message), true)
                }
            }
        }
    }

    // ---------- 淘宝 / 菜鸟登录（取件码数据源） ----------

    private var tbLoginPoll: Runnable? = null

    /**
     * 打开淘宝登录页。
     * **不清 cookie** —— 淘宝的登录态就活在 jar 里，清了会把现有登录态一起弄丢，
     * 而且用户可能只是进来看看。要换号就在页面上点「切换账号」。
     */
    private fun openTbLogin() {
        openWeb(
            TbChannel.LOGIN_URL, getString(R.string.tb_login_title),
            accent = "#FF5000", loginMode = "tb"
        )
    }

    /** 淘宝登录轮询：jar 里出现 `unb` 就算登上了 */
    private fun startTbLoginPoll() {
        stopTbLoginPoll()
        // ★ vc404：**必须识别"真的换了号"**。
        //   旧逻辑只要 isLoggedIn() 就 finish —— 旧 cookie 还在时一开始就是 true，
        //   登录页刚打开就被关掉，用户根本没机会输账号（这就是"换了号没换"的真凶）。
        val startUid = TbChannel.uid()
        Log.i("TbLogin", "开始登录轮询，起始 uid=")
        val r = object : Runnable {
            var tries = 0
            override fun run() {
                tries++
                val cur = TbChannel.uid()
                // ★ vc406：必须 cur 非空；startUid 为空时要求连续两次都非空（防抖动）
                val switched = cur.isNotEmpty() && cur != startUid
                if (switched) {
                    Log.i("TbLogin", "检测到账号切换： → ")
                    finishTbLogin()
                    return
                }
                if (tries >= 160) {
                    Log.i("TbLogin", "登录轮询超时（未检测到切换，当前 uid=）")
                    return
                }
                mainHandler.postDelayed(this, 1500)
            }
        }
        tbLoginPoll = r
        mainHandler.postDelayed(r, 1500)
    }

    private fun stopTbLoginPoll() {
        tbLoginPoll?.let { mainHandler.removeCallbacks(it) }
        tbLoginPoll = null
    }

    private fun finishTbLogin() {
        val uid = TbChannel.uid()
        if (uid.isEmpty()) {
            appToast(this, R.string.tb_login_not_yet)
            return
        }
        stopTbLoginPoll()
        // ★ vc403：确认是否真的换了号（同号登录时给个明确记录，免得又误判）
        val prevUid = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString("tb_prev_uid", "").orEmpty()
        Log.i("TbLogin", "登录完成 uid= 之前=" +
            if (prevUid.isNotEmpty() && prevUid == uid) "（仍是同一个账号！）" else "（账号已切换）")
        // ★ vc377：记下"什么时候登录成功的"——身份码刚登录要等约 30 分钟才生成，
        //   有这枚时间戳才能区分"正常等待"和"真出不来"。
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putLong("tb_login_at", System.currentTimeMillis()).apply()
        // cookie 落盘，保证下次冷启动登录态还在
        try { CookieManager.getInstance().flush() } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        appToast(this, R.string.tb_login_ok)
        closeWeb()
        if (settingsLayer != null) {
            settingsLayer?.let { root.removeView(it) }
            settingsLayer = null
            openSettings()
        }
        if (pickupLayer != null) fetchPickup()
    }

    /**
     * 拼多多栏点击：应用内直开官方身份码页（自建原生桥冒充拼多多容器，见 [PddMdkd]）。
     *
     * 2026-09-20 复盘（推翻 9-18 的"App 内无解"结论）：mdkd 页面循环跳登录的真相不是
     * "不认消费者 token"，而是**它的请求根本不走 XHR**，全走拼多多原生桥 `JSNetwork:request`
     * —— 桥不在 ⇒ 请求压根没发出去 ⇒ 看起来就像登录态不认。
     * 现在我们自建原生桥冒充拼多多容器（见 [PddMdkd]），身份码页可以原样在应用内渲染。
     * 注意：这里不要写出带斜杠加星号的路径字面量 —— Kotlin 块注释可嵌套，会吃掉后面的代码。
     *
     * （原 `pddSubtitle()` 已删：2026-09-20 同志要求去掉该栏副标题，
     *   登录态提示改由身份码页内顶栏的「登录」按钮承担。）
     */
    private fun openPddIdentity() = openPddMdkd()

    /** 应用内打开拼多多官方身份码页（m.pinduoduo.net 的 mdkd 页） */
    private fun openPddMdkd() {
        // ★ vc490：拼多多身份码**永远用主号**。
        //   副号登录会把 jar 里的 cookie 换成副号（WebView 只有一个 cookie 仓库），
        //   这里在打开前把主号 cookie 写回 jar，保证身份码/亮码始终是主号。
        PddAccounts.primary(this)?.let { p ->
            try {
                PddChannel.injectCookies(p.cookie)
                Log.i("PddAccount", "打开身份码前注入主号 cookie（uid=${p.uid.takeLast(6)}）")
            } catch (t: Throwable) {
                Log.w("PddAccount", "注入主号 cookie 失败: ${t.message}")
            }
        }
        openWeb(
            PddMdkd.IDENTITY_URL, getString(R.string.bar_pdd_code),
            accent = "#E02E24", loginMode = "mdkd",
            backClosesLayer = true   // ★ vc606：一次返回退出（页内会跳订单列表）
        )
    }

    private fun mdkdTopAction() {
        if (PddChannel.isLoggedIn(this)) {
            launchPddPickup()
        } else {
            // 关掉身份码层再去登录（openWeb 同时只允许一个网页层）
            closeWeb()
            openPddLogin()
        }
    }

    /**
     * 打开拼多多登录页。
     *
     * ★★ 2026-09-26：**这是全 App 唯一的拼多多登录入口**，别再另开第二个。
     *   两个入口（设置页拼多多行未登录时 / 身份码页顶栏未登录时）都只是调它。
     *
     * ★ vc266（2026-10-15）：登录页改走【电脑端通道】（桌面 UA，见 [PDD_LOGIN_UA]）。
     *   页面原生提供「扫码登录 + 手机号登录」两种方式：
     *   - **扫码**（推荐）：用手机上的拼多多 App 扫，建立的是电脑端会话 —— 实测**不会顶掉**拼多多 App，
     *     且登录后身份码/订单/物流三关全通（清空登录态从零验证过）。
     *   - 手机号（短信）：仍可用，但它与 App 的互斥行为未重新验证，文案里如实提示。
     *   登录完成前**绝不提示"已成功"** —— 登录是否真被服务端接受，只有后续业务调用才知道
     *   （见 [PddChannel.markLoginDead]）。
     */
    /** ★ vc489：添加副账号（走同一个登录页，只是把结果落到"新增"槽） */
    private fun addPddSecondary() {
        // ★ vc490：最多 3 个账号（1 主 + 2 副）—— 再多风控概率明显上升
        if (PddAccounts.all(this).size >= 3) {
            appToast(this, "最多 3 个账号（1 主 + 2 副）", false)
            return
        }
        PddAccounts.beginAddSecondary(this)
        // ★★ vc494：**先清掉拼多多域的 cookie**，否则登录页会带着主号会话直接显示"已登录"，
        //   点一下就把主号身份存成"副号"（日志实证：副号2/副号3 uid 全等于主号）。
        try {
            PddAccounts.backupPrimaryCookie(this)
            PddChannel.clearCookies()
            Log.i("PddAccount", "添加副号前已清拼多多 cookie（保证登录页是未登录态）")
        } catch (t: Throwable) {
            Log.w("PddAccount", "清 cookie 失败: ${t.message}")
        }
        doOpenPddLogin()
    }

    /**
     * ★ vc489：副账号拉取 —— **纯 HTTP**，不占 pickupExpect、不开 WebView。
     * 谁先回谁先渲染：每个账号独立线程 + 独立 Session，慢号不拖别的号。
     */
    private fun fetchPddSecondary() {
        if (pickupLayer == null) return
        val list = PddAccounts.secondaries(this)
        if (list.isEmpty()) return
        // ★ vc490：跳过 6 小时内已判失效的号（省一次必然失败的请求）
        val now = System.currentTimeMillis()
        val usable = list.filter { it.deadAt == 0L || now - it.deadAt > 6 * 3600_000L }
        if (usable.isEmpty()) return
        for ((idx, acc) in usable.withIndex()) {
            // ★ vc655：上一轮还没跑完就跳过本轮 —— 宁可这轮不补，也不许两轮抢同一个页面队列
            if (pddAccInFlight.putIfAbsent(acc.id, true) != null) {
                Log.i("PddAccount", "${acc.label} 上一轮还在跑，跳过本轮（防队列互相挤）")
                continue
            }
            pddAccPool.execute {
              try {
                // 错峰 250ms/号：避免三个号同一瞬间打同一批接口（也能让主号先跑）
                if (idx > 0) try { Thread.sleep(250L * idx) } catch (_: InterruptedException) {}
                val t0 = System.currentTimeMillis()
                val ses = PddAccounts.sessionOf(acc)
                if (!ses.usable) return@execute
                // 副号切换/淘宝退出后的主号迁移可能留下空页缓存；副号刷新必须重新问一次。
                PddNative.clearOrderPagesCache(ses.uid)
                val rep = try {
                    PddNative.scan(ses, maxPages = 3, cap = 40)
                } catch (t: Throwable) {
                    Log.w("PddAccount", "${acc.label} 扫描异常: ${t.message}")
                    null
                } ?: return@execute
                val costMs = System.currentTimeMillis() - t0
                Log.i("PddAccount", "${acc.label} 用时 ${costMs}ms 扫描=${rep.scanned} 带码=${rep.items.count { it.code.isNotEmpty() }}")
                LogEvents.account(this, acc.label, costMs, rep.scanned, rep.items.count { it.code.isNotEmpty() })
                if (rep.needLogin) {
                    PddAccounts.markDead(this, acc.id)
                    Log.w("PddAccount", "${acc.label} 登录失效（${rep.message}）")
                    return@execute
                }
                val directCards = rep.items.filter { it.code.isNotEmpty() && it.orderSn.isNotEmpty() }
                // ★★ vc655：**缓存里已经有码的单，不再开详情页**。
                //   旧写法每轮都把全部候选单重新开一遍详情（9 单 ≈ 30 秒、还反复触发风控），
                //   而取件码对一个订单是稳定的（没取走就不会变）⇒ 直接复用，只补真缺的。
                //   这同时修掉"两个两个往外出、每次都重算一遍"的观感：一轮只开 1~2 个页面。
                val cachedMap = PddCodeStore.all(this)
                val cachedCodeOf: (String) -> String = { sn ->
                    val c = cachedMap["pdd:$sn"]
                    if (c == null || c.code.isBlank() || PddCodeStore.looksTaken(c)) "" else c.code
                }
                val nowFail = System.currentTimeMillis()
                val cooling = rep.candidateOrderSns.count { sn ->
                    cachedCodeOf(sn).isEmpty() && directCards.none { it.orderSn == sn } &&
                        nowFail - (pddDetailFailAt[sn] ?: 0L) < PDD_DETAIL_FAIL_COOLDOWN_MS
                }
                val needDetail = rep.candidateOrderSns.filter { sn ->
                    directCards.none { it.orderSn == sn } && cachedCodeOf(sn).isEmpty() &&
                        nowFail - (pddDetailFailAt[sn] ?: 0L) >= PDD_DETAIL_FAIL_COOLDOWN_MS
                }.take(10)
                val reused = rep.candidateOrderSns.count { sn ->
                    cachedCodeOf(sn).isNotEmpty() && directCards.none { it.orderSn == sn }
                }
                Log.i(
                    "PddAccount",
                    "${acc.label} 扫到 ${rep.scanned} 单，直接带码 ${directCards.size} 单，缓存复用 $reused 单，冷却跳过 $cooling 单，候选 ${rep.candidateOrderSns.size} 单，需补码 ${needDetail.size} 单"
                )
                if (directCards.isEmpty() && needDetail.isEmpty()) return@execute
                // ★★ vc655：**复用的码要"续期"**。
                //   上面既然不再每轮重抓详情，缓存里的 `ts` 就不会刷新；而 `PddCodeStore.all()`
                //   和 `PickupVerdict` 都有"48 小时没更新就隐藏"的闸 —— 件在驿站放两天，
                //   码会自己消失。这里把"这一轮扫描里仍然看到这单"当成一次确认，重写 ts
                //   （6 小时内不重复写盘，省 IO）。只续期、不改码。
                run {
                    val nowTs = System.currentTimeMillis()
                    for (sn in rep.candidateOrderSns) {
                        val c = cachedMap["pdd:$sn"] ?: continue
                        if (c.code.isBlank() || PddCodeStore.looksTaken(c)) continue
                        if (nowTs - c.ts < 6 * 3600_000L) continue
                        PddCodeStore.put(this, "pdd:$sn", c.copy(ts = nowTs))
                    }
                }
                val cards = java.util.Collections.synchronizedList(ArrayList<PddNative.Pickup>())

                // ★★ vc655：**补出一个就上屏一个**（写缓存 → 进列表 → 重绘），不再攒到最后一起放。
                //   旧写法整批 post 一次，用户看到的是"等很久 → 突然蹦出 2 个 / 又少一个"。
                val pushOne: (PddNative.Pickup) -> Unit = { p ->
                    cards.add(p)
                    mainHandler.post {
                        val key = "pdd:${p.orderSn}"
                        // ★ vc658：**内容一模一样且在保鲜期内 ⇒ 不写盘、不重绘**。
                        //   pure-HTTP 通道现在一次能拿全部 9 单，若每单都 put+renderMerged，
                        //   一轮就是 9 次全表写 + 9 次整页重建（刷新一次肉眼可见地抖）。
                        val prev = PddCodeStore.get(this, key)
                        val pWb = p.waybill.ifEmpty { p.orderSn }
                        // ★★ vc662：**不许拿"后五位"把反查出来的真码覆盖回去**。
                        //   拼多多每轮都返回"单号后五位"，而真码是淘宝校园驿站按运单号查来的；
                        //   旧写法照写 ⇒ 刷新一次真码就丢、又变回后五位（同志 11-01 问的就是这个）。
                        val prevReal = prev != null && prev.code.isNotBlank() &&
                                !PickupCodeResolver.isShortCode(prev.waybill.ifEmpty { pWb }, prev.code)
                        val newCode =
                            if (prevReal && PickupCodeResolver.isShortCode(pWb, p.code)) prev!!.code else p.code
                        // ★★ vc666：**站点名同理，不许被"菜鸟驿站"这种公司名盖回去**。
                        //   拼多多对这 5 单只给 `companyName=菜鸟驿站`；真正的驿站名
                        //   （"抚州幼儿师范高等专科学校店"）是从淘宝校园驿站按运单号反查来的，
                        //   每轮扫描照写就会把好地址冲掉 —— 与码同一类"自己覆盖自己"的坑。
                        val pStation = p.place.trim()
                        val prevGoodStation = prev != null && prev.station.isNotBlank() &&
                                prev.station != "菜鸟驿站" && prev.station != "驿站" &&
                                prev.station != "快递柜" && prev.station != "代收点"
                        val newStation = if (prevGoodStation &&
                            (pStation.isBlank() || pStation == "菜鸟驿站" || pStation == "驿站" ||
                                    pStation == "快递柜" || pStation == "代收点")
                        ) prev!!.station else pStation.take(60)
                        // ★ vc663：**排队要放在"内容没变就返回"之前** ——
                        //   否则真码已齐、只差站点名的单永远轮不到补地址（一直 unchanged 直接 return）。
                        //   内部有 6 小时限频，重复调用只是读一次 SP，不会打接口。
                        queuePddShortCode(p.orderSn, pWb, newCode, newStation)
                        // ★ vc658：**内容一模一样且在保鲜期内 ⇒ 不写盘、不重绘**。
                        //   pure-HTTP 通道现在一次能拿全部 9 单，若每单都 put+renderMerged，
                        //   一轮就是 9 次全表写 + 9 次整页重建（刷新一次肉眼可见地抖）。
                        val unchanged = prev != null && prev.code == newCode &&
                                prev.station == newStation &&
                                System.currentTimeMillis() - prev.ts < 10 * 60_000L
                        if (unchanged) return@post
                        if (key !in pickupMutedAll()) {
                            PddCodeStore.put(
                                this,
                                key,
                                PddCodeStore.Cached(
                                    src = "pdd",
                                    code = newCode,
                                    station = newStation,
                                    // ★ vc659：存真运单号（以前错存成订单号）——短码反查要用它
                                    waybill = pWb,
                                    latest = p.status.take(220),
                                    ts = System.currentTimeMillis(),
                                    account = acc.label
                                )
                            )
                        }
                        val exist = pddPkgs.firstOrNull { it.optString("orderSn") == p.orderSn }
                        if (exist == null) {
                            pddPkgs.add(org.json.JSONObject().apply {
                                put("src", "pdd")
                                put("orderSn", p.orderSn)
                                put("pickupCode", newCode)
                                put("stationText", newStation)
                                put("nickName", newStation)
                                put("statusText", p.status.ifBlank { "待取件" })
                                put("account", acc.label)
                                put("waybill", p.waybill)
                            })
                        } else {
                            // ★ vc662：已有真码就别让后五位盖回去（同 prevReal 口径）
                            val curCode = exist.optString("pickupCode")
                            if (curCode.isEmpty() ||
                                (PickupCodeResolver.isShortCode(pWb, curCode) &&
                                        !PickupCodeResolver.isShortCode(pWb, newCode))
                            ) exist.put("pickupCode", newCode)
                            // 地址没补上的顺手补上（不能把已有的地址冲掉）
                            val curSt = exist.optString("stationText")
                            val curGeneric = curSt.isEmpty() || curSt == "菜鸟驿站" || curSt == "驿站"
                            if (curGeneric && newStation.isNotBlank()) {
                                exist.put("stationText", newStation)
                                exist.put("nickName", newStation)
                            }
                            exist.put("account", acc.label)
                            if (exist.optString("waybill").isEmpty()) exist.put("waybill", p.waybill)
                        }
                        if (pickupLayer != null) renderMerged()
                    }
                }
                directCards.forEach(pushOne)
                val stationOf: (String) -> String = { text ->
                    var found = ""
                    for (raw in text.split('\n')) {
                        val line = raw.trim()
                        if (line.isEmpty() || line.contains("转运中心") || line.contains("分拨中心")) continue
                        if (line.contains("驿站") || line.contains("快递柜") ||
                            line.contains("代收点") || line.contains("服务点")
                        ) {
                            val bracket = Regex("[【\\[]([^】\\]]{2,40})[】\\]]")
                                .find(line)?.groupValues?.get(1).orEmpty()
                            found = (bracket.ifEmpty {
                                line.replace(Regex("^.*?至"), "").trim()
                            }).take(60)
                            if (found.isNotBlank()) break
                        }
                    }
                    found
                }
                if (needDetail.isNotEmpty()) {
                    // The shared WebView queue is serialized; wait per order, not once for a fixed batch timeout.
                    for (sn in needDetail) {
                        val directUrl = rep.candidateExpressUrls[sn]
                        val acquired = java.util.concurrent.atomic.AtomicBoolean(false)
                        for (attempt in 1..2) {
                            val done = java.util.concurrent.CountDownLatch(1)
                            val onDone: (PddTraceChannel.Result) -> Unit = { r ->
                                val code = r.pickupCode.orEmpty()
                                if (code.isNotEmpty()) {
                                    acquired.set(true)
                                    pddDetailFailAt.remove(sn)
                                    pushOne(PddNative.Pickup(
                                        orderSn = sn, code = code, tip = r.text.take(220),
                                        place = stationOf(r.text), goods = "", status = "待取件"
                                    ))
                                    Log.i("PddAccount", "${acc.label} 详情补码成功 order=$sn code=${code.take(18)} attempt=$attempt")
                                } else {
                                    pddDetailFailAt[sn] = System.currentTimeMillis()
                                    // ★ vc655 诊断：把页面里所有"…码…"的上下文各抓 1 段，
                                    //   判断是页面根本没码，还是码换了格式（数字码/二维码/带空格）
                                    val snip = Regex(".{0,18}(取件码|取货码|提货码|自提码|取件|码)[:：]?.{0,26}")
                                        .findAll(r.text).take(3)
                                        .joinToString(" ／ ") { it.value.replace('\n', ' ') }
                                    Log.w(
                                        "PddAccount",
                                        "${acc.label} 详情补码失败 order=$sn err=${r.err} attempt=$attempt " +
                                            "textLen=${r.text.length} 片段=$snip"
                                    )
                                }
                                done.countDown()
                            }
                            if (!directUrl.isNullOrBlank()) {
                                PddTraceChannel.fetchByUrl(
                                    this, directUrl, sessionCookie = acc.cookie,
                                    priority = PddTraceChannel.PRI_BACKGROUND, onDone = onDone
                                )
                            } else {
                                PddTraceChannel.fetch(
                                    this, sn,
                                    anchorWords = listOf("待收货", "运输中", "派送", "已发货", "驿站", "待取件"),
                                    priority = PddTraceChannel.PRI_BACKGROUND,
                                    sessionCookie = acc.cookie, onDone = onDone
                                )
                            }
                            try { done.await(100, java.util.concurrent.TimeUnit.SECONDS) }
                            catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
                            if (acquired.get()) break
                            try { Thread.sleep(1500L) } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                                break
                            }
                        }
                    }
                }
                Log.i("PddAccount", "${acc.label} 最终带码 ${cards.size} 单（已逐个上屏，不再整批重绘）")
              } finally {
                  pddAccInFlight.remove(acc.id)
              }
            }
        }
    }

    /**
     * ★★ vc691（同志 2026-11-04 定 A 方案）：**短码反查的反向保护**。
     *
     * 背景：`vc662` 只在"账号扫描"那一条链做过"真码不许被后五位盖回去"；其余三个兜底出口
     * （在途转取件码栏 / 物流页兜底 / 逐单补码）**都没有这层保护**。一旦把反查接到这些出口，
     * 下一轮 cabinet 又返回「取件出示快递单号后五位」，反查来的真格口码当场被打回原形 ——
     * 等于白查。所以先统一成这一处口径：**旧码是真格口码、新来的是短码 ⇒ 保留旧码**。
     */
    private fun preferRealPddCode(prevCode: String, waybill: String, incoming: String): String {
        if (incoming.isBlank()) return prevCode
        if (prevCode.isBlank()) return incoming
        val prevReal = !PickupCodeResolver.isShortCode(waybill, prevCode)
        return if (prevReal && PickupCodeResolver.isShortCode(waybill, incoming)) prevCode else incoming
    }

    /** 同上（地址版）：新地址是「菜鸟驿站」这类泛称、旧地址更具体 ⇒ 保留旧地址（与 vc666 同口径） */
    private fun preferRealPddStation(prev: String, incoming: String): String {
        val generic = setOf("", "菜鸟驿站", "驿站", "快递柜", "代收点")
        val inc = incoming.trim()
        return if (inc in generic && prev.trim() !in generic) prev.trim() else inc
    }

    /**
     * ★ vc659：把「码 = 运单后五位」的拼多多单排进反查队列（6 小时限频，同一单只查一次）。
     * 主线程调用。
     *
     * ★★ vc691：**四个写码出口必须都调它**（同志 11-04 日志实证：同一天 261101 那单
     *   码=99100（= 运单 79155361999100 尾号）、站=菜鸟驿站，全程 0 条 `CodeBridge` ——
     *   因为它走的是"在途转取件码栏"，那条链以前从不排队，所以永远不去淘宝换真格口码）。
     */
    private fun queuePddShortCode(
        orderSn: String, waybill: String, code: String, station: String, force: Boolean = false
    ) {
        val wb = waybill.trim()
        if (wb.isEmpty()) return
        val needCode = PickupCodeResolver.isShortCode(wb, code)
        // ★ vc663：码已经反查到了、但地址还只有"菜鸟驿站"这种泛称 ⇒ 也值得再跑一次（拿站点名）
        val needStation = station.isBlank() || station == "菜鸟驿站" || station == "驿站"
        if (!needCode && !needStation) return
        if (!force) {
            val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
            val stampKey = if (needCode) "pddcode_try_$wb" else "pddst_try_$wb"
            if (System.currentTimeMillis() - sp.getLong(stampKey, 0L) < 6 * 3600_000L) return
        }
        synchronized(pddShortWb) {
            pddShortWb.add(wb)
            pddShortByWb[wb] = orderSn
        }
        maybeResolvePddShortCodes()
    }

    /**
     * ★ vc659：拼多多短码 → 淘宝校园驿站按运单号反查真实格口码。
     *
     * 复用既有能力（[TbSchoolChannel.queryBatch]，与淘宝到站卡同一条链），不新写接口。
     * 命中就替换缓存里的码并重绘；查不到就保持"后五位"（那本来就是有些驿站的凭证）。
     */
    private fun maybeResolvePddShortCodes() {
        if (pddShortRunning) return
        var wbs: List<String> = emptyList()
        synchronized(pddShortWb) {
            if (pddShortWb.isEmpty()) return
            // ★★ vc691：淘宝校园页正被别的链占着（`queryBatch` 忙时**直接回空表**）——
            //   照旧跑下去会把这个单的 6h 名额白白烧掉，以后再也补不上。3 秒后再来。
            if (TbSchoolChannel.isRunning()) {
                if (!pddShortRetryScheduled) {
                    pddShortRetryScheduled = true
                    mainHandler.postDelayed({
                        pddShortRetryScheduled = false
                        maybeResolvePddShortCodes()
                    }, 3000L)
                }
                Log.i("CodeBridge", "淘宝校园页忙 ⇒ 短码反查延后 3s（待查 ${pddShortWb.size} 单）")
                return
            }
            wbs = pddShortWb.toList()
            pddShortWb.clear()
        }
        pddShortRunning = true
        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        sp.edit().apply {
            val ts = System.currentTimeMillis()
            for (w in wbs) {
                putLong("pddcode_try_$w", ts)
                putLong("pddst_try_$w", ts)
            }
        }.apply()
        Log.i("CodeBridge", "拼多多短码反查（淘宝校园驿站）：$wbs")
        try {
            TbSchoolChannel.queryBatch(this, wbs) { map ->
                pddShortRunning = false
                val stMap = TbSchoolChannel.lastStations   // ★ vc663：同一次响应里的站点名
                var changed = false
                for (w in wbs) {
                    val c = map[w.uppercase()] ?: map[w]
                    val st = stMap[w.uppercase()].orEmpty().trim()
                    val sn = synchronized(pddShortByWb) { pddShortByWb[w] }.orEmpty()
                    if (sn.isEmpty()) continue
                    val key = "pdd:$sn"
                    val old = PddCodeStore.get(this, key) ?: continue
                    var nc = old
                    if (!c.isNullOrBlank() && !PickupCodeResolver.isShortCode(w, c) && old.code != c) {
                        nc = nc.copy(code = c)
                        pddPkgs.firstOrNull { it.optString("orderSn") == sn }?.put("pickupCode", c)
                        Log.i("CodeBridge", "★ 拼多多短码反查到真码：$sn waybill=$w ${old.code} → $c")
                    }
                    // 站点名同样是"更具体就替换"（当前只有"菜鸟驿站"这种泛称时才换）
                    val curSt = nc.station
                    val curGeneric = curSt.isBlank() || curSt == "菜鸟驿站" || curSt == "驿站"
                    if (st.isNotEmpty() && curGeneric && st != curSt) {
                        nc = nc.copy(station = st.take(60))
                        pddPkgs.firstOrNull { it.optString("orderSn") == sn }?.let {
                            it.put("stationText", st.take(60))
                            it.put("nickName", st.take(60))
                        }
                        Log.i("CodeBridge", "★ 拼多多短码反查补到站点：$sn waybill=$w 站=$st")
                    }
                    if (nc != old) {
                        PddCodeStore.put(this, key, nc.copy(ts = System.currentTimeMillis()))
                        changed = true
                    }
                }
                if (changed && pickupLayer != null) renderMerged()
                // 查完再清一次登记，避免长期占内存
                synchronized(pddShortByWb) {
                    for (w in wbs) pddShortByWb.remove(w)
                }
                if (pddShortWb.isNotEmpty()) maybeResolvePddShortCodes()
            }
        } catch (t: Throwable) {
            pddShortRunning = false
            SafeRun.w("CodeBridge", t)
        }
    }

    private fun openPddLogin() {
        // ★ vc284b（同志 19:45 指正）：弹窗要**盖在登录页上面**，不是先挡路再进页面。
        //   流程：先照常打开登录网页层 ⇒ 稍候弹窗浮层覆盖其上；没勾"不再提示"才弹。
        doOpenPddLogin()
        // 延 600ms：让网页层先完成首帧铺底，弹窗再盖上来（视觉上是"浮在登录页上"）
        mainHandler.postDelayed({ maybeShowPddLoginNotice { } }, 600)
    }

    /** 弹窗闸门（vc284）：SP 里"不再提示"没勾过 ⇒ 弹；勾过 ⇒ 直接放行。生产/调试共用。 */
    private fun maybeShowPddLoginNotice(onProceed: () -> Unit) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_PDD_LOGIN_NOTICE_OFF, false)) {
            Log.i("PddNotice", "已勾选不再提示，直接放行")
            onProceed()
        } else {
            showPddLoginNotice(onProceed)
        }
    }

    private fun doOpenPddLogin() {
        openWeb(
            PddChannel.LOGIN_URL, getString(R.string.pdd_login_title),
            accent = "#E02E24", loginMode = "pdd"
        )
    }

    /**
     * 拼多多登录须知弹窗（vc284，同志指定）：
     *  · 中部文案：扫码登录才能看身份码；手机号登录能看取件码/在途但不再维护；
     *  · 底部中间「我知道了」—— 前 5 秒禁用（强制阅读），带倒计时；点掉才进登录页；
     *  · 左下角勾选框「以后不再提示」—— 勾上并确认后落 SP，之后 openPddLogin 直接放行。
     * 可取消=false：按返回键/点外部都不关，必须等 5 秒后点按钮（"强制弹窗"的语义）。
     */
    private fun showPddLoginNotice(onProceed: () -> Unit) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(8))
        }
        val msg = TextView(this).apply {
            // ★ vc332：同志 10-19 定稿文案 —— 第一行推荐语，第二行讲为什么（避免与 App 互顶）
            text = "推荐扫码登录\n" +
                "扫码登录才可避免与拼多多APP账号互顶问题"
            textSize = 15f
            gravity = Gravity.CENTER
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextColor(cTextPrimary)   // 深浅模式自动反色
        }
        box.addView(msg, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, dp(12)) })
        // 左下角勾选框（布局顺序在文案之后、按钮之前，自然落在左下）
        val never = CheckBox(this).apply {
            text = "以后不再提示"
            textSize = 13f
            setTextColor(cTextPrimary)   // 深浅模式自动反色
            // ★ vc284c 勾选框图标本身也要反色：buttonTint 管的是方框/对勾 drawable，
            //   setTextColor 管不到它（黑框贴深色底就是同志截图里的样子）
            buttonTintList = ColorStateList.valueOf(cTextPrimary)
        }
        box.addView(never)
        val dlg = AlertDialog.Builder(this)
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("我知道了") { _, _ ->
                if (never.isChecked) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean(KEY_PDD_LOGIN_NOTICE_OFF, true).apply()
                }
                Log.i("PddNotice", "确认放行：never=" + never.isChecked)
                onProceed()
            }
            .create()
        // ★ vc284c：先挂全局弹窗美化（圆角 20dp + 深浅色底板 + 全量文字反色）再 show ——
        //   上一版漏了这一步才出现"白底直角"；15 个系统弹窗都从这里过，动效挂 decor 上。
        try {
            dialogIn(dlg)
        } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        dlg.show()
        Log.i("PddNotice", "弹窗已显示")
        // 5 秒强制阅读：确认按钮先禁用，逐秒倒计时后放开
        val btn = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
        btn.isEnabled = false
        fun tick(sec: Int) {
            if (!dlg.isShowing) return
            btn.text = if (sec > 0) "我知道了（${sec}s）" else "我知道了"
            btn.isEnabled = sec <= 0
            Log.i("PddNotice", "tick=$sec enabled=${btn.isEnabled}")
            if (sec > 0) mainHandler.postDelayed({ tick(sec - 1) }, 1000)
        }
        tick(5)
    }

    /**
     * 拼多多栏点击（旧）：深链直达拼多多 App 内的【官方身份码页】。
     *
     * 路由：`pinduoduo://com.xunmeng.pinduoduo/mdkd/identificationCode`
     * 对应 H5 `https://m.pinduoduo.net/mdkd/identificationCode`（拼多多驿站端 mdkd），
     * 由拼多多 App 的 MainFrameActivity 承载，App 容器自动注入登录态 ⇒ 免登录。
     *
     * ★ 推翻旧定论：早期在 dex 里逐字节搜「身份码」零命中便下了"拼多多无账号级身份码"的结论，
     *   实际是它藏在驿站端 H5（mdkd 域）—— 文案在 H5 bundle 而不在 dex 里。
     */
    private fun launchPddPickup() {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("pinduoduo://$PDD_PACKAGE/mdkd/identificationCode"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        val launch = packageManager.getLaunchIntentForPackage(PDD_PACKAGE)
        if (launch != null) {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            appToast(this, R.string.pdd_app_missing)
        }
    }

    /** ★ vc580：测试取件码 —— 注入 3 条（驿站各不相同） */
    private fun seedTestCodes() {
        val now = System.currentTimeMillis()
        // 先清掉上一次注入的测试键（否则旧数据会叠加，数字对不上）
        clearTestCodes()
        // ★ vc580：同志要求 —— 注入 **3 条、驿站地址各不相同**（验证取件码页多卡片观感）
        val rows = listOf(
            PddCodeStore.Cached("cainiao", "5-3-4003", "幸福里菜鸟驿站", "73733450534940", "已到达驿站", now, 0L),
            PddCodeStore.Cached("jd", "1-2-1108", "万科东门快递柜", "JD0987654321002", "已入柜", now - 60_000, 0L),
            // ★ vc683：这条带 JT 运单号 + 极兔文案 —— 专门验证「快递公司标签」识别
            PddCodeStore.Cached("pdd", "6-6-8899", "星城驿站 3 号架", "JT5542845585956",
                "您的快件已到驿站，凭取件码取件。极兔速递", now - 120_000, 0L)
        )
        rows.forEach { PddCodeStore.putForce(this, it.src + ":" + it.waybill, it) }
        Log.i("TestSeed", "已注入 3 条测试取件码：" + rows.joinToString(" | ") { it.code } +
                " ｜缓存实际条数=" + PddCodeStore.all(this).size)
    }

    private fun clearTestCodes() {
        val all = PddCodeStore.all(this)
        var n = 0
        all.keys.forEach { k ->
            if (k.endsWith("73733450534940") || k.endsWith("JD0987654321002") ||
                k.endsWith("PDD20261029001") || k.endsWith("SF1987654321001") ||
                k.endsWith("MI20261029002") || k.endsWith("JT5542845585956")
            ) { PddCodeStore.remove(this, k); n++ }
        }
        Log.i("TestSeed", "测试取件码已清除 $n 条")
    }

    /**
     * ★ vc605：把某个源的**陈旧缓存**清掉（只清没打灰标的）。
     * 触发时机：该源本轮抓取成功且返回 0 个带码包裹 —— 平台已经说了没有待取件，
     * 缓存再留着就是"假数据"。
     */
    private fun purgeStaleCodeCache(src: String) {
        try {
            var n = 0
            PddCodeStore.all(this).forEach { (k, c) ->
                if (c.src == src && c.pickedAt == 0L) {
                    PddCodeStore.remove(this, k)
                    n++
                }
            }
            if (n > 0) Log.i("PddCache", "清掉 $src 陈旧取件码缓存 $n 条（本轮平台返回 0 件）")
        } catch (x: Throwable) {
            Log.w("PddCache", "清缓存失败: ${x.message}")
        }
    }

    private fun pickupSubtitle(): String {
        // ★ v2 副行口径：「N 张 · 待取 M · HH:mm」—— 数字与卡片一一对应
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val count = prefs.getInt(KEY_LAST_PKG_COUNT, -1)
        val time = prefs.getString(KEY_LAST_PKG_TIME, null)
        val picked = if (pickupPickedVisible > 0) pickupPickedVisible else 0
        val total = if (count >= 0) count + picked else -1
        return when {
            total >= 0 && time != null -> "$total 张 · 待取 $count · $time"
            count >= 0 && time != null -> getString(R.string.bar_pickup_sub_count, count, time)
            else -> getString(R.string.bar_pickup_sub)
        }
    }

    private fun makeSpacer(h: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, (h * dp).toInt())
    }

    private fun makeBar(
        badge: String, colorHex: String, title: String, sub: String,
        enabled: Boolean, onClick: (() -> Unit)? = null,
        onLongClick: (() -> Unit)? = null
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((20 * dp).toInt(), 0, (20 * dp).toInt(), 0)
        }
        row.addView(TextView(this).apply {
            text = badge
            textSize = 20f
            typeface = tfMedium
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12 * dp
                setColor(Color.parseColor(colorHex))
            }
        }, LinearLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt()))

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), 0, 0, 0)
        }
        texts.addView(TextView(this).apply {
            text = title
            textSize = 17f
            typeface = tfMedium
            setTextColor(if (enabled) cTextPrimary else cTextTertiary)
        })
        // ★ 副标题为空就**不加这个 View**（不是塞空字符串）：
        //   空 TextView 仍占 2dp 上边距 + 一行高度，行高会莫名虚胖。
        if (sub.isNotBlank()) {
            texts.addView(TextView(this).apply {
                text = sub
                textSize = 13f
                setTextColor(if (enabled) cTextSecondary else cTextTertiary)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (2 * dp).toInt() })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(TextView(this).apply {
            text = if (enabled) "›" else "·"
            textSize = 24f
            setTextColor(if (enabled) cTextFaint else cTextTertiary)
        })

        // Linear 口径：半透明底 + 1px 半透明边框（与在途卡/设置卡同一套），圆角走 12dp 面板档
        val barBg = linearCardBg(12)
        val wrap = FrameLayout(this).apply {
            background = RippleDrawable(ColorStateList.valueOf(cRipple), barBg, barBg)
            // ★ 深色下不用投影表达层级 —— 投影在近黑画布上根本看不见（Linear 的 depth 全靠亮度阶梯）
            if (!isDark()) elevation = 2 * dp
            if (!enabled) alpha = 0.72f
            if (enabled && onClick != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
                if (onLongClick != null) {
                    isLongClickable = true
                    setOnLongClickListener { onLongClick(); true }
                }
            }
        }
        wrap.addView(row, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (92 * dp).toInt()
        ))
        wrap.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (92 * dp).toInt()
        )
        // 按下微缩挂在**卡片**上（Step 4 的原意；曾在容器上挂过，导致整页缩放出白边）
        if (enabled && onClick != null) applyPress(wrap)
        return wrap
    }

    /**
     * 给"顶栏"补状态栏内边距。
     * 这些层是动态 addView 上去的，收不到首次 inset 分发，必须先用缓存的 statusBarTop 撑一次，
     * 再挂监听等真实值回来。
     */
    private fun bindTopBarInset(top: View) {
        liveTopBars.removeAll { !it.isAttachedToWindow }
        liveTopBars.add(top)
        // ★ v2：v1 版写死 10dp 左右 padding，会把调用方给大标题顶栏设的 22dp 覆盖掉
        //   （取件码页/在途页 v2 顶栏因此左边距被压回 10dp）。改成只改 top，其余原样保留。
        val l = top.paddingLeft
        val r = top.paddingRight
        val b = top.paddingBottom
        val ownTop = top.paddingTop
        top.setPadding(l, statusBarTop + ownTop, r, b)
        top.setOnApplyWindowInsetsListener { v, insets ->
            val t = insets.getInsets(WindowInsets.Type.statusBars()).top
            if (t > 0) statusBarTop = t
            v.setPadding(l, maxOf(t, statusBarTop) + ownTop, r, b)
            insets
        }
        top.post { if (top.isAttachedToWindow) top.requestApplyInsets() }
    }

    // ---------- Linear 统一顶栏（改造 Step 3/5） ----------
    // ★ 以前每个层各刷一条平台色顶栏（淘宝橙 / 取件绿 / 途中紫 / WebView 平台色），
    //   五颜六色互不相干。统一成：面板色底 + 1px 底边 + 17f/590 中性标题，
    //   平台辨识度交给标题前的 7dp 小色点。
    // ⚠️ 左右 padding 最终由 bindTopBarInset 统一成 10dp，这里设的 padding 只在挂载前生效。

    private fun topBarTitle(t: String, size: Float = 17f): TextView = TextView(this).apply {
        text = t
        textSize = size
        typeface = tfMedium
        setTextColor(cTextPrimary)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun topBarBtn(label: String, size: Float = 15f, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = size
            setTextColor(cTextPrimary)
            gravity = Gravity.CENTER
            setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
            setOnClickListener { onClick() }
        }

    /** 平台色点：替代"整条顶栏刷平台色" */
    private fun accentDot(accent: String): View = View(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(accent))
        }
    }

    /**
     * 统一的顶栏容器（只建左边：可选前置视图 + 色点 + 标题）。
     * 右侧按钮由调用方 `top.addView(topBarBtn(...))` 自己加。
     */
    private fun makeTopBar(
        title: String, accent: String? = null, leading: View? = null,
        // ★ vc328：标题字号可调（默认 17f 不变）—— 设置页同志要求放大
        titleSize: Float = 17f
    ): LinearLayout {
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // ★ v2 马卡龙：顶栏**透明** —— 环境光从底下透出来，不再压白色实底
            //   （同志 18:36："顶部应该也是彩色的，相当于顶部没有白色的遮挡，目前每一页都是这样"）
            setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0)
        }
        if (leading != null) top.addView(leading)
        if (accent != null) {
            top.addView(accentDot(accent), LinearLayout.LayoutParams(
                (7 * dp).toInt(), (7 * dp).toInt()
            ).apply {
                leftMargin = (4 * dp).toInt()
                rightMargin = (8 * dp).toInt()
                gravity = Gravity.CENTER_VERTICAL
            })
        }
        top.addView(topBarTitle(title, titleSize), LinearLayout.LayoutParams(0, (52 * dp).toInt(), 1f).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        return top
    }

    /** 顶栏挂到层上：加 1px 底边分隔线 + 状态栏 inset */
    private fun attachTopBar(layer: LinearLayout, top: View) {
        layer.addView(top, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        // ★ v2：顶栏与内容之间不加任何实色条（.bar 无 border-bottom），环境光直接贯通。
        //   P2 的渐进遮罩挂在**滚动容器 foreground** 上（见 openInTransit / openPickup），
        //   不占布局高度、不会在顶栏下沿制造白边。
        bindTopBarInset(top)
    }

    /** 开始一次刷新：只拉「设置里登录过」的源，等齐对应路数 */
    /**
     * @param keepCards 为 true 时**不清空**列表里已有的卡。
     *   只用在「进页秒显」这一条路：缓存里已经有取件码，先上屏给用户看，再后台刷新。
     *   （手动点刷新按钮仍是 false —— 刷新就是该清空重画。）
     */
    /** ★ vc417：免责声明文案（同志 2026-10-25 定稿，勿改措辞） */
    private val DISCLAIMER_TEXT = """
        1. 非官方工具：本软件为个人开发的本地辅助工具，与淘宝、天猫、菜鸟、拼多多、京东、小米、顺丰等平台及其关联公司无任何隶属、合作或授权关系；相关名称与商标归各自权利人所有。

        2. 数据来源：软件仅在本机、使用用户本人已登录的账号，展示其本人可见的订单与物流信息；不使用破解、脱壳、伪造签名等手段，不侵入平台服务器、不修改平台数据。

        3. 不收集数据：无服务器、无账号体系，不上传、不存储、不转售任何账号、订单、取件码或身份码信息；登录凭据仅存在于用户本机。

        4. 免费提供：全部功能不设任何付费门槛，不捐赠同样可用。仅接受用户自愿、无对价的赞助，赞助与本软件功能、服务无关。

        5. 权利尊重：本软件尊重各方知识产权与商业利益。如任何权利人认为本软件或其内容存在不当，可联系底部作者本人，本人在核实后将立即停止分发并删除相关内容。

        6. 责任限制：本软件按“现状”提供，不作任何担保；取件码、身份码等信息由平台返回，可能延迟或错误，取件请以驿站/快递柜实际信息为准；使用风险由用户自行承担。
    """.trimIndent()

    /** ★ vc421：捐赠弹窗（收付款码图片；圆角 + 深浅色自适应） */
    private fun showDonate() {
        val dark = isDark()
        val bgColor = if (dark) Color.parseColor("#1C1D1F") else Color.WHITE
        val titleColor = if (dark) Color.parseColor("#F2F2F2") else Color.parseColor("#111111")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 20f * dp
                setColor(bgColor)
            }
            setPadding((12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt(), (4 * dp).toInt())
        }
        root.addView(TextView(this).apply {
            text = "捐赠支持"
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(titleColor)
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), (10 * dp).toInt())
        })
        val img = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.donate_qr)
            adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }
        root.addView(img, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(TextView(this).apply {
            text = "完全自愿 · 不影响任何功能"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(if (dark) Color.parseColor("#9A9A9A") else Color.parseColor("#999999"))
            setPadding(0, (8 * dp).toInt(), 0, (4 * dp).toInt())
        })
        val btn = TextView(this).apply {
            text = "关闭"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(cAccent)
            setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
            isClickable = true
        }
        root.addView(btn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val dlg = Dialog(this)
        dlg.setContentView(root)
        dlg.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout(
                (resources.displayMetrics.widthPixels * 0.90f).toInt(),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        btn.setOnClickListener { dlg.dismiss() }
        dlg.show()
    }

    /**
     * ★ vc424：跳到淘宝 App 的「菜鸟驿站 → 身份码」。
     * 淘宝的 H5 跳转协议是 tbopen://（老版本是 taobao://），把目标 H5 地址塞进 h5Url。
     * 依次尝试，全失败就提示（不静默）。
     */
    private fun openTaobaoIdentity() {
        val enc = android.net.Uri.encode(TAOBAO_URL)
        val candidates = listOf(
            "tbopen://m.taobao.com/tbopen/index.html?action=ali.open.nav&module=h5&bootImage=0&h5Url=" + enc,
            "taobao://m.taobao.com/tbopen/index.html?action=ali.open.nav&module=h5&bootImage=0&h5Url=" + enc,
            TAOBAO_URL
        )
        for ((idx, u) in candidates.withIndex()) {
            try {
                val it = Intent(Intent.ACTION_VIEW, Uri.parse(u))
                if (idx == candidates.lastIndex) it.setPackage("com.taobao.taobao")
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(it)
                Log.i("IdJump", "跳淘宝：$u")
                return
            } catch (t: Throwable) {
                Log.w("IdJump", "跳转失败($idx)：${t.message}")
            }
        }
        appToast(this, "没有找到淘宝 App，或跳转被拒绝", true)
    }

    private fun showDisclaimer() {
        // ★ vc418：自建圆角弹窗，深/浅色自适应，文字取"反色"（深底浅字、浅底深字）
        val dark = isDark()
        val bgColor = if (dark) Color.parseColor("#1C1D1F") else Color.WHITE
        val titleColor = if (dark) Color.parseColor("#F2F2F2") else Color.parseColor("#111111")
        val bodyColor = if (dark) Color.parseColor("#C9C9C9") else Color.parseColor("#555555")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 20f * dp
                setColor(bgColor)
            }
            setPadding((20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt(), (6 * dp).toInt())
        }
        root.addView(TextView(this).apply {
            text = "免责声明"
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(titleColor)
        })
        val body = TextView(this).apply {
            text = DISCLAIMER_TEXT
            textSize = 13f
            setTextColor(bodyColor)
            setLineSpacing(5f, 1.25f)
            setPadding(0, (12 * dp).toInt(), 0, (6 * dp).toInt())
        }
        val sv = android.widget.ScrollView(this).apply {
            // ★ vc419：滚动条画在文字外侧，并给右侧留出条宽，避免压住正文
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
            setPadding(0, 0, (8 * dp).toInt(), 0)
            addView(body)
        }
        root.addView(sv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        val btn = TextView(this).apply {
            text = "知道了"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(cAccent)
            setPadding(0, (16 * dp).toInt(), 0, (16 * dp).toInt())
            isClickable = true
        }
        root.addView(btn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val dlg = Dialog(this)
        dlg.setContentView(root)
        dlg.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            val w = (resources.displayMetrics.widthPixels * 0.86f).toInt()
            val h = (resources.displayMetrics.heightPixels * 0.64f).toInt()
            setLayout(w, h)
        }
        btn.setOnClickListener { dlg.dismiss() }
        dlg.show()
    }
    private fun fetchPickup(keepCards: Boolean = false) {
        // ★ vc392：抓取前先对齐账号范围（换号/退出 ⇒ 清上一个账号的数据）
        enforceAccountScopes()
        cancelPickupTimeout()
        pickupGot = 0
        // vc274：新轮次代数 +1 —— 旧轮的闸门兜底计时器（onPickupGate）全部作废
        pickupRoundGen++
        pddErr = null
        jdErr = null
        cainiaoErr = null
        // ★ 每个源都只在登录过才发起、才计数 ——
        //   以前淘宝那 1 路是无条件算进 pickupExpect 的，淘宝没登录时它永远不回来，
        //   整个界面干等 70 秒报"超时"（实测踩过）。
        val wantTb = TbChannel.isLoggedIn()
        val wantPdd = PddChannel.isLoggedIn(this)
        // ★ vc502：本轮有哪些源（事件日志）
        try {
            val srcs = ArrayList<String>()
            if (wantTb) srcs.add("cainiao")
            if (wantPdd) srcs.add("pdd")
            LogEvents.roundStart(this, "pickup", srcs)
        } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        val wantJd = JdChannel.isLoggedIn(this)
        val wantMi = miEnabled() && MiChannel.isLoggedIn(this)
        // 还没完成的源队列：加载中文案按它显示"正在获取X取件码"，每回来一路就推进一个
        pendingSources.clear()
        if (wantTb) pendingSources.add("cainiao")
        if (wantPdd) pendingSources.add("pdd")
        if (wantJd) pendingSources.add("jd")
        if (wantMi) pendingSources.add("mi")
        pickupExpect = pendingSources.size

        showStatusText(pickupStatusText)
        // ★ vc229：已经秒显出卡了，状态就别再写「正在获取…」那种"好像什么都没有"的文案，
        //   改成「刷新中 · 上次 HH:mm」——既说清在刷新，又保留上次结果的时效。
        setStatusText(pickupStatusText, if (keepCards) {
            val last = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_LAST_PKG_TIME, "") ?: ""
            if (last.isNotEmpty()) "刷新中 · 上次 $last" else loadingText()
        } else loadingText())
        if (!keepCards) pickupListContainer?.removeAllViews()

        if (pickupExpect == 0) {
            // 一个源都没登录：直接给引导，不发起抓取
            // ★ vc343：没有抓取轮次就不会走 allDone，转圈必须在这里收起（否则一直转）
            pickupSwipe?.let { if (it.isRefreshing) it.isRefreshing = false }
            setStatusText(pickupStatusText, getString(R.string.pickup_no_source))
            return
        }

        pickupTimeout = Runnable {
            // ★ vc343：总超时也收转圈 —— 只要有源没回来，缓存同步里就不会走"完成"分支
            pickupSwipe?.let { if (it.isRefreshing) it.isRefreshing = false }
            if (pickupGot < pickupExpect) {
                setStatusText(pickupStatusText, getString(R.string.pickup_error, "超时"))
            }
        }.also { mainHandler.postDelayed(it, 70_000) }

        if (wantTb) fetchTaobaoAsync()
        if (wantPdd) fetchPddChannel()
        // ★ vc489：副账号并行（纯 HTTP，不占 pickupExpect；谁先回谁先渲染）
        if (PddAccounts.secondaries(this).isNotEmpty()) fetchPddSecondary()
        if (wantJd) fetchJdChannel()
        if (wantMi) fetchMiChannel()
        // ★ vc270k：拼多多**直连驿站**（免签名接口，~0.5s 一次拿全部取件码）——
        //   不占 pickupExpect、不影响其它源；拿到就补卡片，拿不到也不拖后腿。
        if (wantPdd) fetchPddStationDirect()
        // ★ 手动导入的单号**不进第 4 栏**（2026-09-21 同志定的口径：
        //   「第 4 栏的取件码定义就是里面都是取件码」—— 而手动单号是快递100 查轨迹的，
        //   本身没有取件码）⇒ 它归**第 5 栏（在途快递）**，由 fetchInTransit 那条路去查。
        // ★ vc342（P0-5）：给每个源挂单源硬超时 —— 一个源卡住不再让整栏等到 70s
        for (s in ArrayList(pendingSources)) schedulePickupSettle(s)
        // 排查"明明登录了却判未登录"：把 jar 里淘宝 cookie 实况打到日志
        TbChannel.diag()
    }

    /** ★ vc342：单源硬超时结算（第 4 栏）—— 只对"还在等"的源记账，不重复计数 */
    private fun schedulePickupSettle(src: String) {
        val gen = pickupRoundGen
        mainHandler.postDelayed({
            if (gen != pickupRoundGen) return@postDelayed
            // ★ vc643：空栏也必须结算。正常空结果会先走源回调移除 pending；
            //   20s 仍未回调说明该源卡死，不能把整栏拖到 70s。
            if (pendingSources.remove(src)) {
                pickupGot++
                Log.w("PickupTimeout", "$src 单源超时（${PER_SOURCE_TIMEOUT_MS / 1000}s）—— 按完成收尾，旧卡保留")
                if (pickupLayer != null) renderMerged()
            }
        }, PER_SOURCE_TIMEOUT_MS)
    }

    /**
     * ★ vc270k：拼多多驿站直连取件码（不开页面、免签名接口，一次拿全部码）。
     * 与 fetchPddChannel 并行；不占 pickupExpect（拿了算赚，没拿到照走老路）。
     */
    /**
     * ★ vc352：拼多多无码件的「淘宝兜底」—— 按运单号（一次最多 4 个）去淘宝校园页查真码。
     * 顺序按同志定的：拼多多驿站直连先跑；这里只补剩下的。
     */
    private fun taobaoFillPddPickupCodes(round: Int = 0) {
        if (pickupLayer == null) return
        // ★ vc507：原来固定只查 4 个运单号；现在按 4 个一批、最多 3 批（12 单）依次补，
        //   仍有上限（校园页一次最多 4 个），但不会再"只补前 4 单、其余永远没码"。
        val allNeed = pddPkgs.filter {
            it.optString("pickupCode").isEmpty() && it.optString("waybillNo").isNotEmpty()
        }.map { it.optString("waybillNo") }.distinct()
        val start = round * 4
        if (start >= allNeed.size || round >= 3) {
            if (round >= 3 && allNeed.size > 12) {
                Log.w("PddEnum", "淘宝补码：待补 ${allNeed.size} 个运单号，超出单轮上限 12，本轮先补前 12 个")
            }
            return
        }
        val need = allNeed.drop(start).take(4)
        if (need.isEmpty()) return
        Log.i("PddEnum", "淘宝补码：${need.size} 个运单号 ${need.joinToString()}")
        TbSchoolChannel.queryBatch(this, need) { map ->
            if (map.isEmpty() || pickupLayer == null) return@queryBatch
            var changed = false
            for (p in pddPkgs) {
                if (p.optString("pickupCode").isNotEmpty()) continue
                val w = p.optString("waybillNo")
                if (w.isEmpty()) continue
                val c = map[w.uppercase()] ?: map[w] ?: continue
                if (c.isNotEmpty()) {
                    p.put("pickupCode", c)
                    changed = true
                    Log.i("PddEnum", "淘宝补到码：$w → $c")
                }
            }
            if (changed) renderMerged()
            // 还有下一批 ⇒ 隔 800ms 再查，避免连打校园页
            if (start + need.size < allNeed.size && round + 1 < 3) {
                mainHandler.postDelayed({ taobaoFillPddPickupCodes(round + 1) }, 800)
            }
        }
    }

    /**
     * ★ vc365：有码没地址的拼多多卡 —— 用「我的快递」列表直连（fetchList）按运单号/订单号补站名；
     * 列表里也没有的，问一次 cabinet（6h 限频）。列表查询本身 6h 限频，避免反复开页面。
     */
    private fun backfillPddStations() {
        if (pickupLayer == null) return
        val need = pddPkgs.filter {
            it.optString("src") == "pdd" &&
                it.optString("pickupCode").isNotEmpty() &&
                it.optString("stationText").isEmpty() &&
                it.optString("stationName").isEmpty() &&
                it.optString("nickName").isEmpty()
        }
        if (need.isEmpty()) return
        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - sp.getLong("addrlist_ts", 0L) < 6L * 3600_000L) {
            // ★ vc507：原来只补 3 单地址；改为全部（上限 10，超限打日志）
            if (need.size > 10) Log.w("PddAddr", "待补地址 ${need.size} 单，超出单轮上限 10")
            for (p in need.take(10)) ensurePddStationAddress(p.optString("orderSn"))
            return
        }
        sp.edit().putLong("addrlist_ts", now).apply()
        Log.i("PddAddr", "列表补地址：${need.size} 张卡缺地址")
        PddExpressChannel.fetchList(this, force = true) { list ->
            var changed = false
            for (p in need) {
                val wb = p.optString("waybillNo").ifEmpty { p.optString("realWaybill") }
                val sn = p.optString("orderSn")
                val hit = list.firstOrNull {
                    (wb.isNotEmpty() && it.trackingNumber.equals(wb, ignoreCase = true)) ||
                        (sn.isNotEmpty() && it.orderSn == sn)
                } ?: continue
                val st = hit.station
                if (st.isEmpty()) continue
                val station = st.take(60)
                p.put("stationText", station)
                if (p.optString("nickName").isEmpty()) p.put("nickName", station)
                if (sn.isNotEmpty()) {
                    val c = PddCodeStore.get(this, "pdd:$sn")
                    if (c != null && c.station != station) {
                        PddCodeStore.put(this, "pdd:$sn", c.copy(station = station))
                    }
                }
                changed = true
                Log.i("PddAddr", "列表补到地址并同步缓存：$sn → $station")
            }
            if (changed) renderMerged()
            // ★ vc507：同上，补地址不再只补 3 单（上限 10）
            val needAddr = need.filter { it.optString("stationText").isEmpty() }
            if (needAddr.size > 10) Log.w("PddAddr", "待补地址 ${needAddr.size} 单，超出单轮上限 10")
            for (p in needAddr.take(10)) {
                ensurePddStationAddress(p.optString("orderSn"))
            }
        }
    }

    /**
     * ★ vc366：拼多多账号切换 ⇒ 清掉上一个账号的取件码/轨迹缓存。
     *
     * 为什么会漏：PddCodeStore 的键是 `pdd:单号`，**不带账号**；换号后旧缓存条目
     * 仍然被"缓存补卡"路径渲染（源没返回 ⇒ 视为源失败，保留缓存），刷新多少次都在。
     * 判据：cookie 里的 uid 变了就等于换号；第一次运行本版本（没有记录 uid）也清一次，
     * 把历史遗留的跨账号缓存清干净。
     */
    private fun ensurePddAccountScope() {
        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        val ck = PddChannel.cookies(this)
        if (ck.isBlank()) return
        val cur = PddChannel.uid(this).ifEmpty { PddChannel.uidFromCookie(ck) }
        if (cur.isEmpty()) return
        val old = sp.getString("pdd_cache_uid", "").orEmpty()
        if (old == cur) return
        val store = PddCodeStore.all(this)
        var removed = 0
        for (k in store.keys) {
            if (k.startsWith("pdd:")) {
                PddCodeStore.remove(this, k)
                removed++
            }
        }
        pddPkgs.clear()
        try {
            pddTransitTraceCache.clear()
            saveTransitTraces()
        } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        sp.edit().putString("pdd_cache_uid", cur).remove("pdd_enrich_state").apply()
        Log.i(
            "PddAccount",
            "拼多多账号 ${if (old.isEmpty()) "首次记录" else old + " → "} $cur：清掉 $removed 个取件码缓存"
        )
        // ★ 清完立刻重绘，别让上一账号的卡停在屏幕上等下次刷新
        if (pickupLayer != null) renderMerged()
    }

    /**
     * ★ vc392：账号切换/退出 ⇒ 清掉该账号在**本机**的全部数据（取件码、在途、内存卡片）。
     *
     * 同志 2026-10-24 定的规矩：换号不能把上一个账号的数据留在界面上
     * （典型现象：换了淘宝账号，身份码页还显示上一个账号的手机号/码）。
     * 判据 = 各源自己的账号指纹（淘宝 unb / 拼多多 uid / 小米手机号），
     * 指纹变化（含退出变成空）就清；首次运行只记录指纹，不动老数据。
     */
    private fun enforceAccountScopes() {
        // ★ vc399：一次性迁移 —— 老版本换号没标记，这里补一次"清身份码页本地存储"，
        //   修"换了账号但身份码页还显示上一个账号手机号"的历史卡死状态。
        val sp0 = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!sp0.getBoolean("tb_scope_migrated_v399", false)) {
            sp0.edit()
                .putBoolean("tb_scope_migrated_v399", true)
                .putBoolean("tb_id_storage_dirty", true).apply()
        }
        scopeCheck("pdd", PddChannel.uid(this))
        scopeCheck("tb", TbChannel.uid())
        scopeCheck("mi", MiChannel.phones(this).joinToString(","))
        // ★ vc610：京东原先没纳入账号作用域 ⇒ 换京东号会串数据
        try { scopeCheck("jd", JdChannel.pin(this)) } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
    }

    /** 账号变化只清理该平台可识别的数据，避免影响其他平台。 */
    private fun scopeCheck(src: String, cur: String) {
        if (cur.isBlank()) return
        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        val key = "scope_uid_$src"
        val old = sp.getString(key, null)
        if (old.isNullOrBlank()) {
            sp.edit().putString(key, cur).apply()
            return
        }
        if (old == cur) return
        Log.i("AcctScope", "$src 账号变化「${old.takeLast(6)}」→「${cur.takeLast(6)}」⇒ 仅清理 $src 数据")
        clearSourceData(src)
        sp.edit().putString(key, cur).apply()
    }

    private fun clearSourceData(src: String) {
        val aliases = if (src == "tb") setOf("tb", "cainiao") else setOf(src)
        var codeCount = 0
        for (key in PddCodeStore.all(this).keys.toList()) {
            val keySource = key.substringBefore(':')
            if (keySource in aliases) {
                PddCodeStore.remove(this, key)
                codeCount++
            }
        }
        when (src) {
            "pdd" -> { pddPkgs.clear(); pddTransit.clear() }
            "tb" -> { cainiaoPkgs.clear(); tbTransit.clear() }
            "jd" -> { jdPkgs.clear(); jdTransit.clear() }
            "mi" -> { miPkgs.clear(); miTransit.clear() }
        }
        try {
            synchronized(transitCacheLock) {
                val file = transitCacheFile()
                if (file.exists()) {
                    val root = JSONObject(file.readText())
                    root.optJSONObject("sources")?.let { sources ->
                        for (name in aliases) sources.remove(name)
                        root.put("sources", sources)
                        file.writeText(root.toString())
                    }
                }
            }
        } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        try { ExpressStore.clearSource(this, src) } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        try { MuteStore.clearSource(this, src) } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        try { HomeWidgets.clearCodes(this, src) } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        if (src == "pdd") {
            try {
                pddTransitTraceCache.clear()
                saveTransitTraces()
                java.io.File(filesDir, "track_status.json").delete()
            } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        }
        val edit = getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("scope_uid_$src", "")
        when (src) {
            "tb" -> edit.putString("tb_prev_uid", "").remove("tb_login_at")
                .putBoolean("tb_id_storage_dirty", true)
            "pdd" -> edit.putString("pdd_cache_uid", "")
        }
        edit.apply()
        Log.i("AcctScope", "已清 $src：取件码 $codeCount 条 + 来源快照/组件/屏蔽项；其他来源保留")
        val uiRefresh = {
            try { rebuildHome() } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
            try { if (pickupLayer != null) renderMerged() } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
            try { if (transitLayer != null) renderInTransit(ArrayList()) } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) uiRefresh()
        else mainHandler.post(uiRefresh)
    }

    private fun fetchPddStationDirect() {
        pddHttpPool.execute {
            var res = try {
                PddStationDirect.query(this)
            } catch (_: Throwable) {
                Pair(null as String?, emptyList<PddStationDirect.StationPackage>())
            }
            // ★ vc342（P1-5）：一次网络抖动就整轮退出太亏 —— "没问成"（lastOk=false）重试一次。
            //   lastOk=true 的空表是**有效信息**（驿站此刻真没件），不重试。
            if (res.second.isEmpty() && !PddStationDirect.lastOk) {
                try { Thread.sleep(800) } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
                res = try { PddStationDirect.query(this) } catch (_: Throwable) { res }
                Log.i("PddStation", "直连首查未成，重试一次：${res.second.size} 个码 ok=${PddStationDirect.lastOk}")
            }
            val station = res.first
            val list = res.second
            if (list.isEmpty()) {
                // ★ vc270n：直连 0 个码 ⇒ 别空手回去，改用 **native 已拿到的物流直链**
                //   逐个开物流页补码（vc252 实测 ~6s/单；比走订单列表快，且不依赖列表页渲染）。
                //   10-16 那台小米机就是直连 count=0 + 补码链路 0 次启动 ⇒ 界面只剩老单的码。
                Log.i("PddStation", "直连 0 个码 ⇒ 转 native 直链补码")
                // ★ vc352：先走淘宝（按运单号，最多 4 个），再走页面扣字
                mainHandler.post { taobaoFillPddPickupCodes() }
                fallbackPddNativeCodes()
                return@execute
            }
            mainHandler.post {
                if (pickupLayer == null) return@post
                var added = 0
                for (p in list) {
                    val key = p.sn.ifEmpty { p.waybill.ifEmpty { p.code } }
                    if (key.isEmpty()) continue
                    // ① 写缓存（常态显示 + 之后不用重复问）
                    if (key !in pickupMutedAll()) {
                        PddCodeStore.put(
                            this, "pdd:$key", PddCodeStore.Cached(
                                src = "pdd", code = p.code,
                                station = station.orEmpty().take(60),
                                waybill = p.waybill, latest = "", ts = System.currentTimeMillis()
                            )
                        )
                    }
                    // ② 补卡片（本轮列表里没有就加一张，渲染层靠码去重/合并）
                    val exists = pddPkgs.any {
                        it.optString("orderSn") == key || it.optString("waybillNo") == p.waybill ||
                                it.optString("pickupCode") == p.code
                    }
                    if (!exists) {
                        pddPkgs.add(JSONObject().apply {
                            put("src", "pdd")
                            if (p.sn.isNotEmpty()) put("orderSn", p.sn)
                            put("pickupCode", p.code)
                            put("waybillNo", p.waybill)
                            put("upCpName", "拼多多")
                            put("statusText", "在驿站待取")
                            put("goodsName", p.goods)
                            put("stationName", station.orEmpty())
                            put("nickName", station.orEmpty())
                        })
                        added++
                    }
                }
                Log.i("PddStation", "直连补卡 $added 张（驿站=${station ?: "?"}）")
                renderMerged()
                // ★ vc352：直连之后仍有没码的卡 ⇒ 淘宝按运单号补（一次最多 4 个）
                taobaoFillPddPickupCodes()
                // ★ vc365：有码没地址的卡 ⇒ 列表直连/cabinet 补站名
                backfillPddStations()
            }
        }
    }

    /**
     * ★ vc270n：直连接口拿不到码时的**兜底补码** —— 用 [PddNative.inTransitScan] 已经
     * 拿到的「查看物流」直链，逐个开物流页抠取件码（[PddTraceChannel.fetchByUrl]）。
     *
     * 为什么需要它：10-16 真机日志（别人那台小米机）里直连 count=0（包裹不在
     * default_station 那个驿站），而老的 enrich 链路**一次都没启动** ⇒ 第 4 栏只剩
     * 缓存里一个 134 天前老单的码，观感就是"4 个取件码只出 1 个"。
     *
     * ⚠️ 必须**串行**：[PddTraceChannel.fetchByUrl] 有进程级 `running` 闸门，
     *    并发调会全部回「正在抓取中」——所以一单一单来。
     */
    private fun fallbackPddNativeCodes() {
        val gen = pickupRoundGen
        waybillIo.execute {
            val rep = try {
                val ck = PddChannel.cookies(this)
                if (ck.isEmpty()) null
                else {
                    val uid = PddChannel.uid(this).ifEmpty { PddChannel.uidFromCookie(ck) }
                    val token = Regex("PDDAccessToken=([^;]+)")
                        .find(ck)?.groupValues?.get(1)?.trim().orEmpty()
                    val ses = PddNative.Session(token = token, uid = uid, cookie = ck)
                    if (!ses.usable) null else PddNative.inTransitScan(ses, maxPages = 2)
                }
            } catch (_: Throwable) {
                null
            }
            if (rep == null || rep.items.isEmpty()) {
                Log.i("PddStation", "native 兜底：拿不到订单（${rep?.message ?: "会话不可用"}）")
                return@execute
            }
            // 只挑"看着已经到驿站/待取"且带直链的 —— 其余开了物流页也白开（在途/已完成）
            // ★★ vc623：**候选必须真的到了驿站**。
            //   旧判据里的 `status.contains("待收")`（几乎所有在途单都是"待收货"）
            //   + `latest.contains("驿站")`（运输文案到处是驿站）⇒ 9 单里 8 单都进候选，
            //   每单 3~4 秒串行 ⇒ 一轮 30 秒、刷几次就是 2 分钟（同志实测）。
            val arrived = rep.items.filter { t ->
                t.expressUrl.isNotEmpty() && (
                        t.status.contains("待取件") || t.status.contains("待自提") ||
                                t.latest.contains("已到站") || t.latest.contains("已入柜") ||
                                t.latest.contains("待取件") || t.latest.contains("已放入") ||
                                t.latest.contains("已签收，待确认") || t.latest.contains("请及时取件")
                        )
            }
            // 物流文案可能滞后；native 枚举若已明确是取出状态，不能先开物流页补出临时码。
            val statusMap = PddNative.trackStatusesForCtx(this, arrived.map { it.orderSn })
            PddChannel.mergeTrackStatus(statusMap)
            val cands = arrived.filterNot { statusMap[it.orderSn] == "OUT_CABINET" || statusMap[it.orderSn] == "GOT" }
            val enumRemoved = arrived.size - cands.size
            if (enumRemoved > 0) Log.i("PddStation", "native 兜底先按枚举剔除已取走 $enumRemoved 单")
            Log.i("PddStation", "native 兜底：订单 ${rep.items.size} 条 ⇒ 候选 ${cands.size} 条")
            if (cands.isEmpty()) return@execute
            val queue = ArrayList(cands)
            fun next() {
                if (queue.isEmpty()) {
                    Log.i("PddStation", "native 兜底补码结束")
                    return
                }
                val it = queue.removeAt(0)
                mainHandler.post {
                    PddTraceChannel.fetchByUrl(this, it.expressUrl, it.waybill) { r ->
                        val code = r.pickupCode.orEmpty()
                        // 回填一律以物流页 URL 里的真实订单号为准（见 Result.realOrderSn 的说明）。
                        // 但真实订单号若与本轮请求不一致，说明列表页点错了卡；不能把错单的码
                        // 新建成临时取件卡，等正式刷新回来再消失。
                        val own = r.realOrderSn.ifEmpty { it.orderSn }
                        val wrongOrder = r.realOrderSn.isNotEmpty() && r.realOrderSn != it.orderSn
                        val latestEnum = PddChannel.lastTrackStatus[own]
                        val alreadyTaken = latestEnum == "OUT_CABINET" || latestEnum == "GOT"
                        Log.i("PddStation", "native 兜底补码：请求=${it.orderSn} 页面=$own code=${code.ifEmpty { "（空）" }} err=${r.err} wrong=$wrongOrder enum=${latestEnum.orEmpty()}")
                        if (code.isNotEmpty() && !wrongOrder && !alreadyTaken && gen == pickupRoundGen) {
                            applyNativePddCode(own, r.waybillNo.ifEmpty { it.waybill }, code, r.text)
                        } else if (code.isNotEmpty() && alreadyTaken) {
                            Log.i("PddStation", "枚举已取走，丢弃临时补码：$own → $code ($latestEnum)")
                        } else if (code.isNotEmpty() && (wrongOrder || gen != pickupRoundGen)) {
                            Log.w("PddStation", "丢弃未确认的临时补码：请求=${it.orderSn} 页面=$own code=$code wrong=$wrongOrder oldGen=${gen != pickupRoundGen}")
                        }
                        next()
                    }
                }
            }
            next()
        }
    }

    /** ★ vc341：从轨迹行里挑「取件地址」行 —— 驿站/代收点/快递柜 三族 + 显式「取件地址:」句式
     *  （后者的整行是句子，先抠出地址段再上屏，别把整句贴卡上） */
    private fun stationLineOf(lines: List<String>): String? {
        val raw = lines.firstOrNull {
            it.contains("驿站") || it.contains("代收点") || it.contains("快递柜") ||
                    it.contains("取件地址") || it.contains("服务中心")
        } ?: return null
        val line = raw.trim()
        // ★★ vc623：**只取短名**，不要把整段物流文案当地址贴到卡上。
        //   同志 2026-10-31 实测：一张卡下面铺了「待取件…请及时取件，如有疑问请联系…投诉电话…」
        //   上百字，既占地方又没法导航。
        // ① 先认「取件地址: xxx」句式
        Regex("取件地址[:：]\\s*([^,，。\\n]{2,40})").find(line)?.let {
            return it.groupValues[1].trim()
        }
        // ② 先认「取件地址」之后的整段站名；站名里允许出现「-学校店」这类分店后缀。
        //   旧写法用非贪婪匹配，遇到「菜鸟驿站-抚州幼儿高等专科学校店」会在第一个
        //   「驿站」处提前收口，只留下「菜鸟驿站」。
        val stationRe = Regex("([\\u4e00-\\u9fa5A-Za-z0-9·（）()\\-]{2,40}(?:驿站|代收点|快递柜|服务中心)(?:[-－—][\\u4e00-\\u9fa5A-Za-z0-9·（）()\\-]{2,32})?)")
        stationRe.find(line)?.let { return it.groupValues[1].trim() }
        // ③ 兜底：截断，绝不整段上屏
        return line.take(24)
    }

    /**
     * ★ vc341（PME110 用户复检实证）：PDD 卡「有码、没地址」的**统一补口子**。
     *
     * ## 为什么 vc340 没修住
     * vc340 把柜名接进了 AutoCode 批量链（cabinetInfo），但那条链只为「缺真码」的单跑 ——
     * 这台机器的单码来自 native 兜底物流页（日志 `native 兜底补码 → 71334382`），
     * 码一到位就被判"码、址双全"，`PddEnrich: 缺码=0` ⇒ AutoCode 链根本不启动，
     * 柜名永远没人问。而 native 兜底/enrich 的站名只能从轨迹文案里抠三个词，抠不到就永远空。
     *
     * ## 补法
     * 谁发现"这张 PDD 卡有码但三个地址位（stationText/stationName/nickName）全空"，
     * 谁就按**订单号**问一次 cabinet 接口（express/cabinet/{order_sn}，~0.3s 免签名 HTTP）——
     * 那正是拼多多 App 里「丰巢快递柜丨XX」的数据源，柜名/站名是结构化字段。
     * 每单 6h 限频（addrs_&lt;sn&gt;），失败也记戳，下轮刷新自然再试。
     */
    private fun ensurePddStationAddress(sn: String) {
        if (sn.isEmpty()) return
        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (System.currentTimeMillis() - sp.getLong("addrs_$sn", 0L) < 6L * 3600_000L) return
        sp.edit().putLong("addrs_$sn", System.currentTimeMillis()).apply()
        pddHttpPool.execute {
            val place = try { PddNative.cabinetInfo(this, sn).second } catch (_: Throwable) { "" }
            if (place.isEmpty()) return@execute
            mainHandler.post {
                val card = pddPkgs.firstOrNull { it.optString("orderSn") == sn } ?: return@post
                if (card.optString("stationText").isNotEmpty()) return@post   // 别处已填，别洗掉
                card.put("stationText", place.take(60))
                if (card.optString("nickName").isEmpty()) card.put("nickName", place.take(60))
                PddCodeStore.get(this, "pdd:$sn")?.let { c ->
                    if (c.station.isEmpty())
                        PddCodeStore.put(this, "pdd:$sn", c.copy(station = place.take(60)))
                }
                Log.i("PddEnrich", "★ cabinet 接口补到取件地址 sn=$sn → $place")
                if (pickupLayer != null) renderMerged()
            }
        }
    }

    /** vc270n：把兜底抠到的码写进缓存 + 卡片 + 重绘（与直连补卡同一套落点） */
    private fun applyNativePddCode(sn: String, waybill: String, code: String, text: String) {
        if (pickupLayer == null) return
        // ★ vc639：native 兜底可能跨越一次刷新才回调；旧轮的码只能落缓存，
        //   不能把已经被新轮订单列表淘汰的临时卡重新画回当前页面。
        val roundAtCall = pickupRoundGen
        val key = "pdd:$sn"
        // ★ vc341：站名抠取提到 muted 判断外（下面补卡也要用）；行族放宽（stationLineOf）——
        //   快递柜单的轨迹常是「…已入柜/取件地址:XX」句式，旧表只认 驿站/代收点/快递柜 三个词，
        //   全漏 ⇒ PME110 码有了、地址永远空。
        // ★ vc365：地址先从**原文**抠（地址行常不带日期，旧逻辑会把它过滤掉）
        val station = (PddTraceChannel.stationFromText(text) ?: "").take(60)
        // ★ vc361：物流页里常抠不到地址 —— **别拿空值整条覆盖**，沿用缓存里原来的地址
        //   （同志报："底部原本是取件地址，几秒后变成待取件+时间"）
        val oldCached = PddCodeStore.get(this, key)
        val q = pddPkgs.firstOrNull { it.optString("orderSn") == sn }
        // ★★ vc691：反查出口②（物流页兜底）—— 真码优先、并且排队去换真格口码
        val effWb = waybill.ifEmpty { q?.optString("waybillNo").orEmpty() }
            .ifEmpty { oldCached?.waybill.orEmpty() }
        val effCode = preferRealPddCode(oldCached?.code.orEmpty(), effWb, code)
        val stationKept = preferRealPddStation(oldCached?.station.orEmpty(), station)
            .ifEmpty { oldCached?.station.orEmpty() }
        if (key !in pickupMutedAll()) {
            PddCodeStore.put(
                this, key, PddCodeStore.Cached(
                    src = "pdd", code = effCode, station = stationKept,
                    waybill = effWb, latest = "", ts = System.currentTimeMillis()
                )
            )
        }
        queuePddShortCode(sn, effWb, effCode, stationKept)
        if (q != null) {
            q.put("pickupCode", effCode)
            if (effWb.isNotEmpty() && q.optString("waybillNo").isEmpty()) q.put("waybillNo", effWb)
            // ★ vc341：卡上没地址才填（别把已有的更准站名洗掉）
            if (stationKept.isNotEmpty() && q.optString("stationText").isEmpty()) {
                q.put("stationText", stationKept)
                if (q.optString("nickName").isEmpty()) q.put("nickName", stationKept)
            }
        } else {
            pddPkgs.add(JSONObject().apply {
                put("src", "pdd"); put("orderSn", sn)
                put("pickupCode", effCode); put("waybillNo", effWb)
                put("upCpName", "拼多多"); put("statusText", "在驿站待取")
                put("goodsName", "拼多多包裹·" + sn.takeLast(6))
                put("stationText", stationKept)
                put("nickName", stationKept)
            })
        }
        if (roundAtCall == pickupRoundGen) {
            renderMerged()
        } else {
            Log.i("PddStation", "旧刷新轮补码只落缓存、不重绘：$sn → $code")
        }
        // ★ vc341：轨迹里抠不到地址 ⇒ 按订单号问一次 cabinet 接口（快递柜单的地址就在那儿）
        if (station.isEmpty()) {
            ensurePddStationAddress(sn)
            // ★ vc365：cabinet 只给泛称时，再用「我的快递」列表按运单号补真站名
            backfillPddStations()
        }
    }

    /**
     * 拼多多一路：隐藏 WebView 加载消费者订单页 + 旁听接口响应。
     * 结果直接写进 pddPkgs，再借 onPickupPart 走统一的重绘流程。
     */
    private fun fetchPddChannel() {
        // ★ vc366：换号先把上一个账号的缓存清掉（否则旧码会一直挂在页面上）
        ensurePddAccountScope()
        PddChannel.fetch(this) { pkgs, err ->
            // ★★ vc273（10-16）：闸门被上一轮占着 ≠ 失败。快速开关取件码页时会撞上——
            //   旧写法把它当错误显示"获取失败：正在抓取中"（数据明明几秒后就到）。
            //   ★ vc274：改走 onPickupGate —— 不记错误、不计数、不用空列表覆盖 pddPkgs；
            //   上一轮抓完的回调会过来 onPickupPart 收尾；若占闸门的是另一页（它的回调
            //   不会来排本页 pending），20s 兜底也会排空，页面不会吊死在"正在获取"。
            if (err == PICKUP_GATE_MSG) {
                onPickupGate("pdd")
                return@fetch
            }
            // ★★ vc270n（10-16，P0）：**数据先存字段，再判断层开没开**。
            //   旧写法 `if (pickupLayer == null) return@fetch` 挡在最前面 ⇒ 拼多多抓完的
            //   19 个包裹（其中 4 个"在驿站待取"、都有取件码）**整个被丢弃**，pddPkgs 永远
            //   停在旧值（甚至空）—— 真机日志实证：`pkgs=19` 但第 4 栏只剩缓存里那个
            //   134 天前的老单的码；补码链路 PddTraceChannel **0 次启动**、裸卡降级 0 次、
            //   45 天过滤 0 次 ⇒ 说明 pending 里一个拼多多卡都没有。
            //   （第 5 栏却能看到这 4 单，因为它读 PddChannel 的原始捕获，不依赖 pddPkgs。）
            //   ⇒ 观感就是同志报的「4 个取件码只出 1 个，且那 1 个还是老单的」。
            //   track 状态同样必须先落盘：它是"这单取没取"的唯一判据，丢了就只剩笨兜底。
            // ★ vc257：旁听回来的 track 状态立刻落盘（进程一杀就没有判据了）
            PddChannel.persistTrackStatus(this)
            // ★ vc361：本轮之前"已在屏幕上"的单号 —— 只有它们才配打「已取」灰标。
            val prevSns = pddPkgs.mapNotNull {
                it.optString("orderSn").ifEmpty { null }
            }.toHashSet()
            pddPkgs = pkgs
            pddErr = err.ifEmpty { null }
            if (pickupLayer == null) {
                // 层没开：不渲染，但数据已在 pddPkgs 里 —— 开页/下一轮渲染自然上屏
                Log.i("PddChannel", "第4栏未开：${pkgs.size} 条已存字段，等渲染上屏 err=$err")
                return@fetch
            }
            // ★ vc352：第一层判定 —— 先按拼多多自己的枚举把"已取走"剔掉再上屏。
            //   最多等约 1.5s（25 单 3 批），避免"已取走的卡先闪一下再消失"。
            val gen = pickupRoundGen
            Thread {
                val sns = pkgs.map { it.optString("orderSn") }.filter { it.isNotEmpty() }
                val enums = if (sns.isEmpty()) emptyMap() else
                    PddNative.trackStatusesForCtx(this, sns)
                val taken = enums.filterValues { it == "OUT_CABINET" || it == "GOT" }.keys
                // ★ vc362：没码的单要进取件栏，必须有"到站证据"：
                //   枚举 = IN_CABINET，或文案里出现 驿站/待取/入柜…；否则一律不进
                //   （10 多天前的"待确认/待收货"单就是这么冒出来的）
                val noCodeNotArrived = pkgs.filter { p ->
                    if (p.optString("pickupCode").isNotEmpty()) return@filter false
                    if (p.optLong("pickedAt", 0L) != 0L) return@filter false
                    if (enums[p.optString("orderSn")] == "IN_CABINET") return@filter false
                    // ★ vc363：枚举 SIGN = 已签收（但不保证已取出）⇒ 没码就别在取件栏占位
                    if (enums[p.optString("orderSn")] == "SIGN") return@filter true
                    val s = p.optString("statusText") + " " + p.optString("latest")
                    listOf("驿站", "待取", "入柜", "快递柜", "代收点", "已到")
                        .none { s.contains(it) }
                }.map { it.optString("orderSn") }.toHashSet()
                if (noCodeNotArrived.isNotEmpty()) {
                    Log.i("PddEnum", "无码无到站证据 → 不进取件栏 ${noCodeNotArrived.size} 单：${noCodeNotArrived.joinToString()}")
                }
                mainHandler.post {
                    if (gen != pickupRoundGen) return@post
                    if (taken.isNotEmpty()) {
                        // ★ vc361：只有"本轮之前已经在屏幕上"的单才配打灰标；
                        //   历史单（待评价/交易成功…）即使枚举说已取走，也只该消失。
                        val taggable = if (autoPickedTagOn()) taken.filter { it in prevSns } else emptyList()
                        if (taggable.isNotEmpty()) {
                            val now = System.currentTimeMillis()
                            for (p in pkgs) {
                                if (p.optString("orderSn") in taggable &&
                                    p.optLong("pickedAt", 0L) == 0L
                                ) {
                                    p.put("pickedAt", now)
                                    val key = "pdd:" + p.optString("orderSn")
                                    val c = PddCodeStore.get(this, key)
                                    if (c != null && c.pickedAt == 0L) {
                                        PddCodeStore.put(this, key, c.copy(pickedAt = now))
                                    }
                                }
                            }
                            // 打标的保留（灰卡 24h），没打标的一律剔除
                            pddPkgs = ArrayList(pkgs.filterNot {
                                (it.optString("orderSn") in taken && it.optString("orderSn") !in taggable) ||
                                        it.optString("orderSn") in noCodeNotArrived
                            })
                            Log.i(
                                "PddEnum",
                                "自动打已取标签 ${taggable.size} 单：${taggable.joinToString()}｜" +
                                        "历史单直接剔除 ${taken.size - taggable.size} 单"
                            )
                        } else {
                            pddPkgs = ArrayList(pkgs.filterNot { it.optString("orderSn") in taken })
                            Log.i(
                                "PddEnum",
                                "取件码剔除已取走 ${taken.size} 单：${taken.joinToString()}"
                            )
                        }
                    } 
                    // ★ vc362：无论有没有"已取走"单，都要清掉"无码且已签收"的占位卡
                    if (noCodeNotArrived.isNotEmpty()) {
                        pddPkgs = ArrayList(pddPkgs.filterNot {
                            it.optString("orderSn") in noCodeNotArrived
                        })
                    }
                    onPickupPart("pdd", null)
                }
            }.start()
        }
    }

    /**
     * 京东一路：隐藏 WebView 加载订单中心 + 改写 JSONP 回调旁听（h5st 由页面自己算）。
     */
    private fun fetchJdChannel() {
        JdChannel.fetch(this) { pkgs, err ->
            if (pickupLayer == null) return@fetch
            // ★ vc273：闸门占用（上一轮还在跑）≠ 失败 —— 快速重开页面时撞上会闪
            //   "获取失败：正在抓取中"。不记错不计数，等上一轮回调收尾。
            // ★ vc274：改走 onPickupGate（带 20s 兜底）—— 占闸门的可能是另一页，
            //   它的回调不会来排本页 pending，不兜底会干等到 70s 超时。
            if (err == PICKUP_GATE_MSG) { onPickupGate("jd"); return@fetch }
            jdPkgs = pkgs
            jdErr = err.ifEmpty { null }
            // ★ vc279：灌假的无码到达件（debug_fake_arrived）验证「已到达<地址>」卡片
            if (fakeArrived && pkgs.none { it.optString("waybillNo") == fakeArrivedNo }) {
                pkgs.add(JSONObject().apply {
                    put("src", "jd")
                    put("pickupCode", "")
                    put("waybillNo", fakeArrivedNo)
                    put("upCpName", "京东物流")
                    put("statusText", "待取件")
                    put("goodsName", "测试商品·京东物流无码件")
                    put("arrivedAddress", "北京大学")
                })
            }
            // ★ vc279：无码件（京东物流）补收货地址 —— 第4栏主信息位要显示「已到达<地址>」
            fillJdArrivedAddresses(pkgs)
            onPickupPart("jd", null)
        }
    }

    // ── vc279：京东无码件「已到达<地址>」─────────────────────────────
    /** 串行队列：一次只开一个详情页（隐藏 WebView 同时挂多个没必要，也别惹风控） */
    private val jdAddrQueue = ArrayDeque<String>()
    private var jdAddrBusy = false
    private var jdAddrCurrent: String? = null

    /**
     * 给京东**无取件码**的件补收货地址（同志 10-16 需求：京东物流不显示取件码，
     * 到达后进取件码页，卡片显示「已到达<地址>」，如"已到达北京大学"；
     * 能拿到取件码的（中通/韵达等）照常显示取件码，不走这条路）。
     *
     * 数据来源：订单详情页 `progressList[]` 里 `progressId=="address"` 的 `tip`
     * （订单列表 JSON 实测没有任何地址字段，见 JdChannel.openDetailPage 头注）。
     * 命中缓存的**同步**贴上（不闪一下「待取件」）；未命中的排队开页面，回来就地刷新。
     */
    private fun fillJdArrivedAddresses(pkgs: ArrayList<JSONObject>) {
        if (pickupLayer == null) return   // 只有第 4 栏开着才值得抓
        var queued = false
        for (p in pkgs) {
            if (p.optString("src") != "jd") continue
            if (p.optString("pickupCode").isNotEmpty()) continue      // 有码件不需要地址
            if (p.optString("arrivedAddress").isNotEmpty()) continue  // 已经有了
            val id = p.optString("waybillNo")
            if (id.isEmpty()) continue
            val cached = JdChannel.cachedAddress(this, id)
            if (cached != null) {
                p.put("arrivedAddress", cached)
                continue
            }
            if (jdAddrQueue.contains(id) || id == jdAddrCurrent) continue
            jdAddrQueue.add(id)
            queued = true
        }
        if (queued) pumpJdAddrQueue()
    }

    private fun pumpJdAddrQueue() {
        if (jdAddrBusy) return
        if (jdAddrQueue.isEmpty()) return
        val id = jdAddrQueue.removeFirst()
        jdAddrBusy = true
        jdAddrCurrent = id
        JdChannel.fetchDetailAddress(this, id) { addr ->
            jdAddrBusy = false
            jdAddrCurrent = null
            if (addr.isNotEmpty()) {
                jdPkgs.firstOrNull { it.optString("waybillNo") == id }
                    ?.put("arrivedAddress", addr)
                Log.i("JdAddr", "地址已贴上 deal=$id")
            } else {
                Log.w("JdAddr", "没拿到地址 deal=$id（降级显示状态文案）")
            }
            if (pickupLayer == null) {
                // 页面已经关了：剩下的不用抓了（缓存里已有的下轮照样同步贴）
                jdAddrQueue.clear()
                return@fetchDetailAddress
            }
            renderMerged()
            pumpJdAddrQueue()
        }
    }

    /**
     * 小米一路：走**小米商城 H5**（`m.mi.com/order/list`）旁听 + 主动调订单接口。
     * 不用智能助理那套原生接口 —— 它依赖 MIUI 系统类 `miui.util.CoderUtils`，非小米设备没有。
     * 原始捕获存下来，第 4 栏按"有自提码"、第 5 栏按"非终态"各自解析。
     */
    private fun fetchMiChannel() {
        MiShopChannel.fetch(this) { captures, err ->
            if (pickupLayer == null && transitLayer == null) return@fetch
            miCaptures = captures
            // ★ vc346：两栏**各自独立**处理 —— 旧写法是 `if (pickup) ... else if (transit)`，
            //   第 4 栏开着时第 5 栏永远拿不到新解析结果。
            if (pickupLayer != null) {
                if (err == PICKUP_GATE_MSG) {
                    onPickupGate("mi")
                } else {
                    miPkgs = MiShopChannel.parsePickup(captures)
                    if (err.isNotEmpty()) miErr = err
                    onPickupPart("mi", null)
                }
            }
            if (transitLayer != null) {
                if (err == PICKUP_GATE_MSG) {
                    onTransitGate("mi")
                } else {
                    // ★★ vc346（2026-10-20 复盘）：**err 非空也必须重算**。
                    //   旧逻辑 `if (err.isEmpty()) miTransit = parse...` —— 商城成功了、有品那路
                    //   失败一次，整段就跳过 ⇒ miTransit 永远停在旧缓存，2020 的老单怎么刷都不掉。
                    miTransit = MiShopChannel.parseInTransit(captures)
                    Log.i(
                        "MiShopChannel",
                        "解析 captures=${captures.size} → 途中 ${miTransit.size} 条（err=${err.ifEmpty { "无" }}）"
                    )
                    if (err.isNotEmpty() && captures.isEmpty()) {
                        // ★ vc558：已经有卡片时**不覆盖**状态行（别让一个源的登录问题把整页写成报错）
                        if ((transitList?.childCount ?: 0) == 0) {
                            setStatusText(transitStatus, getString(R.string.pickup_error, errShort(err)))
                        } else {
                            Log.i("MiShopChannel", "小米失败但有其它卡片，状态行保持不动：${errShort(err)}")
                        }
                    }
                    transitPendings.remove("mi")
                    transitGot++
                    renderInTransit()
                }
            }
        }
    }

    /**
 * （顺丰数据源已于 vc616 移除：此前只有变量与注释、没有抓取实现。）
         */
    private fun manualWaybills(ctx: android.content.Context): ArrayList<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try {
            val s = getSharedPreferences(PREFS, MODE_PRIVATE).getString("manual_waybills", "[]") ?: "[]"
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val no = o.optString("no")
                if (no.isNotEmpty()) out.add(no to o.optString("phone"))
            }
        } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        return out
    }

    private fun saveManualWaybills(ctx: android.content.Context, list: List<Pair<String, String>>) {
        val arr = JSONArray()
        for ((no, ph) in list) arr.put(JSONObject().put("no", no).put("phone", ph))
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("manual_waybills", arr.toString()).apply()
    }

    /** 手动添加单号弹窗：输入运单号（手机号自动带小米账号的），可清空全部 */
    private fun showAddWaybillDialog() {
        val existing = manualWaybills(this)
        val input = android.widget.EditText(this).apply {
            hint = "粘贴快递运单号，如 SF138266610092"
            setSingleLine(true)
            val p = (16 * dp).toInt()
            setPadding(p, p / 2, p, p / 2)
        }
        // ★ 手机尾号（2026-09-21）：韵达等快递公司查询时会校验**收件人手机号后 4 位**。
        //   以前这个值恒为空（来自"小米账号手机号"，而小米没登录）。
        //   ⚠️ 必须定义在 `box` **外面** —— setPositiveButton 里要用它（写在 apply 块里拿不到，
        //   编译会报 Unresolved reference 'tail'）。
        val tail = android.widget.EditText(this).apply {
            // ★ 措辞很讲究：实测（PC 复现）同一个真单号 ——
            //   尾号**留空**时网页版给过真实轨迹，而尾号**填错**时直接"查无结果"。
            //   ⇒ 它是"校验字段"，填错比不填更糟，必须让用户知道。
            // ★ 措辞用「**收件人**手机号」而不是含糊的"手机尾号" ——
            //   韵达校验的是**收件人**的号：代收 / 帮别人寄的件，收件人并不是你本人。
            // ★ 不再预填（2026-09-21 晚更正）：免费通道下填不填尾号结果一样
            //   （实测 7 次都是"查无结果"），预填一个猜来的号只会让人以为"必须填对"。
            hint = "收件人手机号后 4 位（选填）"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
            val p = (16 * dp).toInt()
            setPadding(p, p / 2, p, p / 2)
        }

        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val p = (20 * dp).toInt()
            setPadding(p, p / 2, p, 0)
            addView(input)
            addView(tail)
            addView(TextView(this@MainActivity).apply {
                text = "已添加 ${existing.size} 个单号 · 第二行是【收件人】手机尾号（选填）：" +
                        "免费通道填不填结果一样；填了官方 key/customer 后才靠它校验"
                textSize = 12f
                setTextColor(cTextTertiary)
                setPadding(0, (8 * dp).toInt(), 0, 0)
            })
        }
        AlertDialog.Builder(this)
            .setTitle("手动添加单号")
            .setView(box)
            .setPositiveButton("添加") { _, _ ->
                val no = input.text.toString().replace(" ", "").replace("\u00A0", "")
                if (no.length < 8) {
                    appToast(this, "单号太短，请检查")
                    return@setPositiveButton
                }
                // 优先用用户填的尾号；没填才回退到已知账号手机号
                val phone = tail.text.toString().trim()
                    .ifEmpty { MiChannel.phones(this).firstOrNull() ?: "" }
                saveManualWaybills(this, existing + (no to phone))
                appToast(this, "已添加，取件码页会自动查询轨迹")
                fetchManualChannel()
            }
            // ★ 原来这里是「清空全部」+「取消」两个按钮；同志 2026-09-21 要求**删掉清空全部**，
            //   让「取消」落在原来那个位置（对话框左边的 neutral 位）。单个删除走长按。
            .setNeutralButton(android.R.string.cancel, null)
            .showAnimated()
    }

    /** 手动单号走快递100 查轨迹（后台线程），前 5 个；结果进第 4/5 栏 */
    /**
     * 手动添加的单号 → 快递100 查轨迹（结果只进第 5 栏「在途快递」）。
     *
     * ★ vc254 提速：原来 5 个单号在 [pddIo]（**单线程**）里一个一个查，而 queryTraceAuto
     *   在公司码识别不出来时会**逐个常见公司码兜底重试**（每试一次一个 HTTP 往返），
     *   实测整轮 22.8 秒（2026-10-14 07:16 真机日志：首屏 369ms、收尾 22789ms）。
     *   ⇒ 改成 4 线程并行 + **谁先回来谁先上屏**，整轮≈最慢那一个单号的时间。
     */
    private fun fetchManualChannel() {
        val items = manualWaybills(this)
        // ★ vc275：一个单号都不剩了 ⇒ 清干净再走（不能留旧卡在列表里）
        if (items.isEmpty()) {
            if (manualTransit.isNotEmpty()) {
                manualTransit = ArrayList()
                if (transitLayer != null) renderInTransit()
            }
            return
        }
        // ★ vc507：原来只查前 5 个手动单号，第 6 个起永远查不到；改为全部（上限 20）
        if (items.size > 20) Log.w("ManualWaybill", "手动单号 ${items.size} 个，超出单轮上限 20，本轮先查前 20 个")
        val todo = items.take(20)
        // ★ 每轮开查前清空，否则并行回来的结果会和上一轮混在一起（越刷越多）
        manualPkgs = ArrayList()
        // ★★ vc275（10-16，同志报+截图）：「首次加载在途快递中会出现"手动添加的单号"，
        //   等加载完毕后它又会消失」—— 根因是**每轮开头无条件清空 manualTransit**：
        //   重查窗口期（快递100 查不到要 8s+，02:23 实测）里任何一次重绘（别的源回来 /
        //   下拉刷新 / 90 秒闸命中）都会让手动卡凭空消失，结果回来又蹦出来 ⇒ 闪 + 丢。
        //   另外旧写法 `if (transitLayer == null) return@post` 把快速开关页面时的查询
        //   结果**整个丢弃**（vc270n 同款教训），再进页面要等下一轮重查才见卡。
        //   ⇒ 现在只剔"不在本轮清单里"的（删了的/被 take(5) 挤掉的），清单里的旧卡留着
        //   占位；结果回来按单号**原位替换**（顺序不跳）；层关着也照常存字段。
        val keepNos = todo.map { it.first }.toSet()
        manualTransit.removeAll { it.optString("waybillNo") !in keepNos }
        Log.i("ManualWaybill", "开始并行查 ${todo.size} 个手动单号：" +
                todo.joinToString(",") { it.first })
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        for ((no, phone) in todo) {
            waybillIo.execute {
                val pkg = queryManualOne(no, phone)
                mainHandler.post {
                    // 层关着也要把结果存下（只跳过渲染）—— 数据不再丢在半路
                    val idx = manualTransit.indexOfFirst { it.optString("waybillNo") == no }
                    if (pkg != null) {
                        if (idx >= 0) manualTransit[idx] = pkg else manualTransit.add(pkg)
                    } else if (idx >= 0) {
                        manualTransit.removeAt(idx)
                    }
                    Log.i("ManualWaybill",
                        "手动单号 ${done.incrementAndGet()}/${todo.size} 回来：$no")
                    if (pickupLayer != null) onPickupPart("manual", null)
                    // ★ vc697：手动单号作为**独立标签**时占一个期待位 ⇒ 全部查完就结算，
                    //   状态行才会从"正在获取手动添加的单号…"换成"更新于 HH:mm"。
                    //   不是当前标签时 pendings 里没有 "manual"，这里什么都不做（与旧行为一致）。
                    if (transitPendings.contains("manual") && done.get() >= todo.size) {
                        transitPendings.remove("manual")
                        transitGot++
                    }
                    if (transitLayer != null) renderInTransit()
                }
            }
        }
    }

    /**
     * vc275：手动单号"查不到"卡（两条失败路共用：接口没轨迹 / 有轨迹但零节点）。
     * 查不到也照样出一张卡 —— 否则用户"加了单号却什么都不显示"，
     * 根本分不清是自己单号打错了还是软件坏了。
     */
    private fun manualFailCard(no: String, phone: String, why: String): JSONObject =
        JSONObject().apply {
            put("src", "manual")
            put("goodsName", "")
            put("shopName", "手动添加")
            put("statusText", "查不到轨迹")
            put("pickupCode", "")
            put("stationText", "")
            put("waybillNo", no)
            // ★ 直接显示 `why` —— 它能区分"接口挂了"和"接口正常但这单没轨迹"
            //   （以前一律"查无结果"，用户分不清是自己单号错了还是我们坏了）。
            put("latest", why)
            put("trackLink", "")
            put("phone", phone)
            put("upCpName", "手动添加")
            put("nickName", "")
            put("tip", why)
            put("shelfNo", "")
            put("manualFailed", true)
        }

    /**
     * 查**一个**手动单号（跑在 [waybillIo] 的线程里）。
     * 查不到也照样出一张卡 —— 否则用户"加了单号却什么都不显示"，
     * 根本分不清是自己单号打错了还是软件坏了。
     */
    private fun queryManualOne(no: String, phone: String): JSONObject? {
        try {
            // ★ 用 queryTraceAuto：字母前缀优先认公司，认不出只查 autonumber 那一家、
            //   **不跨公司盲试**（vc260：盲试会试出撞号的别人的轨迹——10-14 圆通单
            //   YT0714785611281 实证，界面上出现过梅州一件撞号包裹的假物流）。
            //   原来 `detectCompany(no)?.comCode ?: continue` 会在识别失败时**静默跳过** ——
            //   快递100 的 autonumber 对某些单号（实测韵达 46569114010791）返回空数组，
            //   于是"手动加了运单号却什么都不显示"，用户完全不知道原因。
            val r = Kd100Channel.queryTraceAuto(this@MainActivity, phone, no)
            val tr = r.trace
            if (tr == null) {
                // ★ 查不到**也要出一张卡**。
                //   （实测踩过：导入 `465691140107911` 多了 1 位，界面毫无反应）
                val why = r.why.ifEmpty { "快递100 查不到这个单号" }
                Log.w("ManualWaybill", "$no 查不到：$why")
                return manualFailCard(no, phone, why)
            }
            // ★ vc275：有 trace 但零节点也出"查不到"卡（旧写法 return null ⇒ 无卡）
            val last = tr.points.lastOrNull()
                ?: return manualFailCard(no, phone, "快递100 没有返回轨迹节点")
            val status = if (tr.isReceived) "已签收"
            else if (tr.eta.isNotEmpty()) "运输中" else "查询成功"
            // ★ vc346：把判定依据打出来 —— 下次"已签收却显示在途中"能一眼定位是字段还是文案
            Log.i(
                "ManualWaybill",
                "$no isReceived=${tr.isReceived} status=$status 节点=${tr.points.size} " +
                        "最后一条=${last.take(50)}"
            )
            return JSONObject().apply {
                put("src", "manual")
                put("goodsName", "")
                put("shopName", "手动添加")
                put("statusText", status)
                put("pickupCode", "")
                put("stationText", "")
                put("waybillNo", no)
                // ★ vc254 修真 bug：`$tr.eta` 在 Kotlin 里是「tr 对象 + 字面 .eta」，
                //   取字段必须写成 `${tr.eta}`。
                put("latest", listOfNotNull(
                    // ★ vc345：已签收就别再写"预计送达"了（自相矛盾）
                    if (!tr.isReceived && tr.eta.isNotEmpty()) "预计 ${tr.eta} 送达" else null, last
                ).joinToString(" · "))
                put("trackLink", "")
                put("phone", phone)
                put("upCpName", "手动添加")
                put("nickName", last)
                put("tip", last)
                put("shelfNo", "")
            }
        } catch (e: Throwable) {
            // 以前这里 `catch (t: Throwable) { SafeRun.w("MainActivity", t) }` 把异常全吞了 ⇒ 用户"加了单号没反应"
            // 而我们连日志都没有。改成记一笔，至少能查。
            Log.w("ManualWaybill", "$no 查询异常: ${e.message}")
            return null
        }
    }

    /** 一路数据回来了：写缓存 + 重绘（全部回来才算完整） */
    private fun onPickupPart(src: String, pkgs: ArrayList<JSONObject>?) {
        // ★ vc502：结构化事件 —— 每个源回来一行（排障时一眼看出哪个源慢/空/错）
        try {
            LogEvents.source(this, "pickup", src, pkgs?.size)
        } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        if (pkgs != null) {
            // ★ 原来写的是 `if (src == "cainiao") ... else pddPkgs = ...` ——
            //   缺省落到 pdd 上，哪天有人传个非 null 的 "manual" 就会**把拼多多的数据覆盖掉**。
            //   这里显式列举，其它源（manual/ems/sf/mi）各有自己的变量，不走这条路。
            when (src) {
                "cainiao" -> cainiaoPkgs = pkgs
                "pdd" -> {
                    // ★ vc342（P0-1 配套）：批量补码可能**比订单页先到** —— 换列表时把已有的
                    //   码/地址继承过来，否则 PddChannel 一回来就把刚补到的码"洗掉"
                    //   （码还在 PddCodeStore 里，但卡上没了 ⇒ 用户看到"码又消失了"）。
                    val old = pddPkgs.associateBy { it.optString("orderSn") }
                    val store = PddCodeStore.all(this)
                    for (p in pkgs) {
                        val sn = p.optString("orderSn")
                        if (sn.isEmpty()) continue
                        val prev = old[sn]
                        val cached = store["pdd:$sn"]
                        if (p.optString("pickupCode").isEmpty()) {
                            val c = prev?.optString("pickupCode").orEmpty()
                                .ifEmpty { cached?.code.orEmpty() }
                            if (c.isNotEmpty()) p.put("pickupCode", c)
                        }
                        if (p.optString("stationText").isEmpty()) {
                            val st = prev?.optString("stationText").orEmpty()
                                .ifEmpty { cached?.station.orEmpty() }
                            if (st.isNotEmpty()) {
                                p.put("stationText", st)
                                if (p.optString("nickName").isEmpty()) p.put("nickName", st)
                            }
                        }
                    }
                    pddPkgs = pkgs
                }
            }
        }
        // ★ 手动单号不占 expect ⇒ 也不能把 pickupGot 加 1，
        //   否则 "所有源都回来了"（pickupGot >= pickupExpect）会被它提前满足，
        //   缓存消除逻辑就会在别的源还没回来时开跑。
        // ★★ 幂等（同一源可能回来两次：先上屏、补齐后再刷）：
        //   只有**真的从 pending 队列里移除**时才计数 —— 第二次调用 remove 返回 false，
        //   不会把 pickupGot 多加，"全部完成"的判定不受影响。
        if (src != "manual") {
            if (pendingSources.remove(src)) pickupGot++
        }
        renderMerged()
    }

    /**
     * ★ vc274：取件码页撞上渠道闸门（"正在抓取中"回复）时的统一收尾。
     *
     * 为什么必须兜底：占着闸门的那一轮可能是**另一页**发起的（第 4/5 栏共用
     * pdd/jd/mi 三个渠道，每页的回调只认自己页的层）⇒ 它的回调不会来排本页的
     * pending，本页会干等到 70s 超时才报"超时"（快速开关页面时稳定复现）。
     * 20s 足够上一轮跑完（正常渠道回一趟 3~10s）；万一没跑完也按"完成"收尾——
     * 宁可少显示一行"正在获取X"，也绝不让页面吊死。
     */
    private fun onPickupGate(src: String) {
        val gen = pickupRoundGen
        Log.i("PickupGate", "$src 闸门占用中（上一轮还在跑），等它的回调收尾")
        mainHandler.postDelayed({
            if (gen != pickupRoundGen) return@postDelayed   // 已开新一轮，旧轮作废
            if (pendingSources.remove(src)) {
                pickupGot++
                Log.w("PickupGate", "$src 20s 还没排上（占闸门的可能是另一页），按完成收尾")
                renderMerged()
            }
        }, 20_000)
        // 立刻重绘一次：状态行继续显示"正在获取X"（pending 里还有这个源），不是错误
        renderMerged()
    }

    /**
     * ★ vc274：第 5 栏撞上渠道闸门（"正在抓取中"回复）时的统一收尾。
     * 与 onPickupGate 同款同机理：占闸门的可能是另一页，它的回调不会来排本页
     * pending ⇒ 20s 兜底按完成收尾，防止干等到 50s 超时报"超时"。
     */
    private fun onTransitGate(src: String) {
        val gen = transitRoundGen
        Log.i("TransitGate", "$src 闸门占用中（上一轮还在跑），等它的回调收尾")
        mainHandler.postDelayed({
            if (gen != transitRoundGen) return@postDelayed
            if (transitPendings.remove(src)) {
                transitGot++
                Log.w("TransitGate", "$src 20s 还没排上（占闸门的可能是另一页），按完成收尾")
                renderInTransit()
            }
        }, 20_000)
        // 立刻重绘一次：状态行继续显示"正在获取X物流"（pending 里还有这个源），不是错误
        renderInTransit()
    }

    /**
     * 数据源 → 显示名。以后接入新源（小米 / 快递100），在这里加一行即可，
     * 「正在获取X取件码」的文案自动跟上。
     */
    private fun srcName(src: String): String = when (src) {
        "cainiao" -> "淘宝"
        "tb" -> "淘宝"
        "pdd" -> "拼多多"
        "jd" -> "京东"
        "mi" -> "小米"
        "manual" -> "手动"
        "ntf" -> "通知"
        else -> src
    }

    /** 加载中文案：显示第一个还没完成的源（并发拉取，谁没回来就报谁） */
    private fun loadingText(): String {
        val next = pendingSources.firstOrNull() ?: return getString(R.string.pickup_loading)
        return getString(R.string.pickup_loading_src, srcName(next))
    }

    /**
     * ★ vc343：菜鸟通道的**平台角标**。
     *
     * 这个通道（`mtop.cainiao...queryMultiStationPackages`）是用**淘宝登录态**查的，
     * 返回的是该账号下的驿站包裹 ⇒ 默认归属「淘宝」（[srcName] 也一直这么叫，
     * 只有卡片 chip 写死成了「菜鸟」，造成"文案说淘宝、卡上写菜鸟"的自相矛盾 —— 2026-10-20 反馈）。
     *
     * 若包裹自带平台字段（同一个驿站可能混有其他平台的件），按字段显示对应平台，
     * 避免把拼多多/京东件一律标成淘宝。
     */
    private fun cainiaoLabelOf(pkg: JSONObject): Pair<String, String> {
        val raw = arrayOf(
            "platformName", "platform", "bizPlatform", "orderPlatform",
            "sourceName", "channelName", "tradePlatform", "platformType"
        ).map { pkg.optString(it) }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val t = raw.lowercase()
        return when {
            t.contains("淘宝") || t.contains("taobao") || t.contains("天猫") || t.contains("tmall") ->
                "淘宝" to "#FF5000"
            t.contains("拼多多") || t.contains("pdd") || t.contains("pinduoduo") ->
                "拼多多" to "#E02E24"
            t.contains("京东") || t.contains("jd") ->
                "京东" to "#E1251B"
            t.contains("抖音") || t.contains("douyin") ->
                "抖音" to "#111111"
            // ★ vc345（同志 2026-10-20 定）：**认不出平台就不显示平台** ——
            //   兜底写「淘宝」只是猜，宁可空着，也不要给错信息。
            else -> "" to ""
        }
    }

    /**
     * 跑一次取件码查询。
     *
     * 必须先探 lib-mtop 就绪：多账号每轮都会重载隐形页，页面里的 lib-mtop 是重新加载的，
     * onPageFinished 时往往还没初始化好。原来单账号是"一次预热、长期复用"所以看不出来，
     * 多账号下会立刻踩到 —— 页面返回 NO_LIB，而 NO_LIB 又被当成"登录失效"，误报未登录。
     */
    /**
     * ★ vc523：读旁听捕获，找页面自己那条取件接口响应；命中就直接按它渲染。
     */
    private fun readDataPageCapture() {
        val w = dataWebView ?: return
        WebHook.readCaptures(w) { caps ->
            var hit: String? = null
            var hitUrl = ""
            for ((u, b) in caps) {
                if (b.contains("stationPackageAssembleList")) {
                    hit = b
                    hitUrl = u
                    break
                }
            }
            if (hit == null) {
                Log.i("TaobaoFetch", "旁听未命中（captures=${caps.size}），先试 DOM/SSR 兜底")
                // ★ vc613：SSR 页面不发 XHR ⇒ 从 DOM 里抠数据（与 CaiNiaoChannel 同一套）
                mainHandler.postDelayed({ grabDataFromDom() }, 300)
                return@readCaptures
            }
            Log.i("TaobaoFetch", "旁听命中 $hitUrl")
            Log.i("TaobaoFetch", "旁听回包: " + hit.take(600))
            try {
                val o = JSONObject(hit)
                val wrap = JSONObject()
                wrap.put("ok", true)
                wrap.put("ret", o.optJSONArray("ret") ?: org.json.JSONArray())
                wrap.put("data", o.optJSONObject("data") ?: JSONObject())
                dataFetchPending = false
                handlePackagesResult(wrap.toString())
            } catch (t: Throwable) {
                Log.w("TaobaoFetch", "旁听解析失败: ${t.message}")
            }
        }
    }

    /** ★ vc613：DOM / SSR 兜底（主界面隐形页）。解析仍走 [handlePackagesResult]。 */
    private fun grabDataFromDom() {
        val w = dataWebView ?: return
        try {
            w.evaluateJavascript(DomPickup.GRAB_JS) { v ->
                val raw = try {
                    WebHook.unquoteJsString(v ?: "")
                } catch (_: Throwable) {
                    v ?: ""
                }
                val json = DomPickup.extractEnclosingJson(DomPickup.unescape(raw), "stationPackageAssembleList")
                // ★ vc615：落盘这一屏（覆盖式，含页面前 4KB），便于事后核对
                try {
                    getExternalFilesDir(null)?.let { dir ->
                        java.io.File(dir, "dom_pickup_dump.txt").writeText(
                            "===== TaobaoFetch @ ${System.currentTimeMillis()} =====\n" +
                                    "页面字节=" + raw.length + " | 抠出JSON=" + (json?.length ?: 0) + "\n" +
                                    "-- JSON 前 4000 字 --\n" + (json ?: "").take(4000) + "\n" +
                                    "-- 页面 前 4000 字 --\n" + raw.take(4000) + "\n"
                        )
                    }
                } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
                if (json != null) {
                    Log.i("TaobaoFetch", "DOM 兜底命中（${json.length}B）")
                    try {
                        val o = JSONObject(json)
                        val wrap = JSONObject()
                        wrap.put("ok", true)
                        wrap.put("ret", o.optJSONArray("ret") ?: org.json.JSONArray())
                        wrap.put("data", o)
                        dataFetchPending = false
                        handlePackagesResult(wrap.toString())
                    } catch (t: Throwable) {
                        Log.w("TaobaoFetch", "DOM 兜底解析失败: ${t.message}")
                    }
                } else {
                    Log.i("TaobaoFetch", "DOM 兜底未命中（页面 ${raw.length}B），等自拼查询")
                }
            }
        } catch (t: Throwable) {
            Log.w("TaobaoFetch", "DOM 兜底异常: ${t.message}")
        }
    }

    private fun runQueryJs(attempt: Int = 0) {
        val w = dataWebView ?: return
        w.evaluateJavascript("(window.lib&&window.lib.mtop)?1:0") { v ->
            if (v == "1" || attempt >= 14) {
                // vc268d：探测开关开着 ⇒ 用注入的接口名/参数替换默认取件码查询（其余机制全同）
                val js = if (debugTbApi != null) probeQueryJs(debugTbApi!!, debugTbData) else QUERY_JS
                w.evaluateJavascript(js, null)
            } else {
                mainHandler.postDelayed({ runQueryJs(attempt + 1) }, 500)
            }
        }
    }

    /**
     * vc268d：A 路探测——同一旁听页试调**任意** mtop 接口，响应照旧走 handlePackagesResult
     * （配合 --ez debug_dump_tb true 落盘原始返回）。找"淘宝物流详情页背后那个数据接口"用。
     */
    private fun probeQueryJs(api: String, dataJson: String?): String {
        val safeApi = api.replace("'", "")   // 防注入：接口名里不允许出现引号
        val data = try { JSONObject(dataJson ?: "{}").toString() } catch (_: Throwable) { "{}" }
        return ("""
            (function(){
              function post(s){ try{ AndroidData.onPackages(s); }catch(e){} }
              if(!(window.lib && window.lib.mtop)){ post(JSON.stringify({ok:false,error:['NO_LIB']})); return; }
              window.lib.mtop.request({
                api:'$safeApi', v:'1.0', data:$data,
                ecode:1, dataType:'json', type:'GET', timeout:10000
              }).then(function(r){
                post(JSON.stringify({ok:true, api:'$safeApi', ret:r&&r.ret||[], data:(r&&r.data!==undefined)?r.data:r}));
              }, function(e){
                post(JSON.stringify({ok:false, api:'$safeApi', error:(e&&e.ret)||['UNKNOWN'],
                  msg:(e&&e.errorInfo&&e.errorInfo.errorMsg)||''}));
              });
            })()
        """).trimIndent().replace("\n", "")
    }

    /**
     * 菜鸟/淘宝侧取件码查询入口。
     *
     * ★ 淘宝是【单会话体系】（2026-09-17 两次实测：登录 B 账号会作废 A 的登录票据，
     * 快照导得再完整也没用）⇒ 早期的"快照队列遍历"只会对失效账号白白发请求、
     * 然后报"账号登录已过期"误导用户，已废弃。
     *
     * 现在只查【当前 jar 里实际有效的登录态】= 最后在本 App 登录的账号。
     * 想查别的账号：设置页点那个号的「重新登录」（一键确认，closeWeb 自愈会存回快照）。
     */
    private fun fetchTaobaoAsync() {
        Log.i("TaobaoFetch", "开始查淘宝/菜鸟取件码")
        if (dataWebView == null) {
            onPickupPart("cainiao", ArrayList())
        } else if (dataPageReady) {
            runQueryJs()
        } else {
            dataFetchPending = true
        }
    }

    // ---------- vc270 淘宝在途链路（网页层旁听 → 订单收割 → 详情页抠轨迹） ----------

    /**
     * 淘宝在途抓取总入口。整条链路（2026-10-15 全部真机验证）：
     * ① 正式网页层（openWeb，隐形模式）打开 H5 订单列表 —— **唯一能渲染订单的路径**
     *    （1x1 / 垫底等隐形手法页面都不出订单，lazy-load 要真可见）；
     * ② 16s 后扫 window 内存（currentUltronData 等）正则收 bizOrderId + 订单状态前后文；
     * ③ 状态启发式过滤在途（排除 交易成功/关闭/退款/已签收）；
     * ④ 每个在途订单开 h5-detail（SSR 页，隐形 1x1 WebView 也能出数据）抠全程轨迹；
     * ⑤ 组卡进第 5 栏（src=tb，平台标「淘宝」，身份键=bizOrderId）。
     */
    // ---------- vc270f 直连：复用旁听捕获的订单接口（甩开 olist 页面） ----------

    /** 旁听捕获的订单接口完整 URL（含 api 名/t/sign/data 参数），存 SP 持久化 */
    private fun tbDirectUrl(): String =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString("tb_direct_url", "").orEmpty()

    private fun tbSaveDirectUrl(u: String) {
        // 只存真正的 mtop 网关地址（带签名参数的）；页面导航地址不要
        if (u.contains("h5api.m.taobao.com") && u.contains("sign=")) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("tb_direct_url", u).apply()
            Log.i("TbTransit", "订单接口地址已存档（下轮直连）")
        }
    }

    private fun fetchTaobaoTransit() {
        // ★ vc342：记住本轮的代数 —— tbTransitDone 只在同一代里结算，避免旧代迟到回调
        //   把新一代的 "tb 还没回来" 提前抹掉（那会让缓存落盘残缺、转圈提前停）。
        tbTransitRoundGen = transitRoundGen
        if (tbTransitBusy) {
            // 上一轮还在跑：它自己会收尾并计数（remove 成功才 ++，不会双重计数）。
            // ★ vc342：但仍要挂 20s 兜底 —— 上一轮是在**上一代**里跑的，若它中途被作废/返回时
            //   层已关，就没人给本轮记账了（P0-4 的第二条漏账路径）。
            Log.i("TbTransit", "上一轮还没跑完，本轮跳过并挂兜底结算")
            onTransitGate("tb")
            return
        }
        // 网页层被用户占着（正开着某个页面）⇒ 不抢，本轮放弃（旧卡保留，下一轮再试）
        if (webLayer != null) {
            Log.i("TbTransit", "网页层被占用，本轮放弃")
            tbTransitDone(failed = true)
            return
        }
        // ★ vc311：新一轮开跑 ⇒ 清空上一轮的终态记录（下面 buildTbCard 会按本轮实况重填）
        tbPickedNow.clear()
        tbTerminalHits = 0
        // ★ vc270f 直连：之前旁听捕获过订单接口 ⇒ 原生 HTTP 复放（带登录 cookie），
        //   ~1s 拿到订单数据，**整轮不开任何页面**；失败自动退回页面旁听（顺带刷新存档）。
        val direct = tbDirectUrl()
        if (direct.isNotEmpty()) {
            tbTransitBusy = true
            val t0 = System.currentTimeMillis()
            val cookie = try {
                CookieManager.getInstance().getCookie("https://h5.m.taobao.com/").orEmpty()
            } catch (_: Throwable) { "" }
            Log.i("TbTransit", "直连模式：复用已捕获的订单接口")
            pddHttpPool.execute tbDirect@{
                val body = try {
                    val conn = java.net.URL(direct).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.setRequestProperty("Cookie", cookie)
                    conn.setRequestProperty("Referer", "https://h5.m.taobao.com/mlapp/olist.html")
                    val code = conn.responseCode
                    if (code == 200) conn.inputStream.readBytes().toString(Charsets.UTF_8)
                    else "HTTP $code"
                } catch (t: Throwable) {
                    "EXC ${t.message}"
                }
                mainHandler.post {
                    if (!tbTransitBusy) return@post   // 期间栏已关/新一轮开始 ⇒ 丢弃
                    val ids = LinkedHashMap<String, String>()
                    val goods = LinkedHashMap<String, String>()
                    if (body.contains("bizOrderId")) harvestTbIdsFromText(body, ids, goods)
                    if (ids.isNotEmpty()) {
                        Log.i("TbTransit", "直连命中：${ids.size} 单（耗时 ${System.currentTimeMillis() - t0}ms）")
                        proceedTaobaoOrders(ids, goods, fromCapture = true)
                    } else {
                        Log.i("TbTransit", "直连未命中（${body.take(60)}），转网页层旁听并刷新存档")
                        fetchTaobaoTransitViaPage()
                    }
                }
            }
            return
        }
        fetchTaobaoTransitViaPage()
    }

    /** 页面旁听路（首轮/直连失败时走）：开隐形网页层 + 钩子截响应 + 学习接口地址 */
    private fun fetchTaobaoTransitViaPage() {
        tbTransitBusy = true
        Log.i("TbTransit", "开始：网页层旁听淘宝订单")
        openWeb(
            "https://h5.m.taobao.com/mlapp/olist.html", "淘宝物流",
            accent = "#FF5000", alphaHidden = true
        )
        tbLayerOpen = true
        // vc270e：快路首割 4s（接口响应 load 后 1~3s 就到，2s 步进多试几轮）
        // ★ vc275：首割 4s → **1.5s**、步进 2s → **1s** —— 钩子快路对 SSR 页结构性失效
        //   （订单数据零接口命中，02:23 实证白等 12.1s），改刮页面数据 blob（几十毫秒），
        //   数据 DOM 解析后就有 ⇒ 1.5s 起每 1s 刮，中了整链 ~3s，不中还有全量刮兜底。
        mainHandler.postDelayed({ harvestTaobaoOrders(0) }, 1_500)
    }

    /**
     * 收割订单（vc270e 重构，vc275 改两级）：
     * · 钩子路 = 抄到的订单接口响应原文（olist 是 SSR 页，这条路结构性空守，但不花钱就留着）；
     * · **便宜快路（vc275 主力）** = 刮页面数据 blob（currentUltronData，~150KB 几十毫秒），
     *   1.5s 起每 1s 一轮共 6 轮 —— 数据 DOM 解析后就有，实测能把整链从 ~13s 压到 ~3s；
     * · 慢路兜底 = 全量刮（innerHTML 830KB + 所有 window 对象，贵），2s 步进最多 2 轮。
     * 响应里还带接口名/参数（记入 tb_order_api.txt），为"直连接口、甩开页面"留路。
     */
    private fun harvestTaobaoOrders(attempt: Int) {
        val wv = webView
        if (!tbLayerOpen || wv == null || transitLayer == null) {
            Log.i("TbTransit", "网页层已不在（用户关了/栏已关），放弃本轮")
            tbTransitDone(failed = true)
            return
        }
        // vc275：attempt < TB_ULTRON_ATTEMPTS = 便宜快路（刮数据 blob）；
        //        之后 = 全量刮兜底（innerHTML + 所有 window 对象，贵）
        val tierB = attempt >= TB_ULTRON_ATTEMPTS
        WebHook.readCaptures(wv) { caps ->
            mainHandler.post {
                if (!tbLayerOpen) return@post
                val ids = LinkedHashMap<String, String>()
                val goods = LinkedHashMap<String, String>()
                var apiHit = ""
                for ((u, body) in caps) {
                    if (!body.contains("bizOrderId")) continue
                    if (apiHit.isEmpty()) {
                        apiHit = u
                        try {
                            java.io.File(getExternalFilesDir(null), "tb_order_api.txt")
                                .appendText(u + "\n----\n")
                        } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
                        // vc270f：存档接口地址（下轮直连，甩开页面）
                        tbSaveDirectUrl(u)
                    }
                    harvestTbIdsFromText(body, ids, goods)
                }
                if (apiHit.isNotEmpty()) {
                    Log.i("TbTransit", "钩子命中订单接口（第${attempt + 1}次收割）：${apiHit.take(150)}")
                }
                if (ids.isNotEmpty()) {
                    proceedTaobaoOrders(ids, goods, fromCapture = true)
                    return@post
                }
                if (!tierB) {
                    // ★ vc275 便宜快路：只刮页面数据 blob（currentUltronData 等，~150KB，
                    //   几十毫秒）。olist 是 SSR 页、订单接口零命中 ⇒ 钩子快路结构性失效
                    //   （详见 TbTransitProbe.HARVEST_ULTRON_JS 注释），旧代码白等
                    //   4/6/8/10/12s 五轮才跑全量刮，02:23 真机实测整轮 12.1s 才出订单号。
                    //   现在 1.5s 起每 1s 刮一次 blob，中就早走，不中也不亏（后面还有全量刮）。
                    wv.evaluateJavascript(TbTransitProbe.HARVEST_ULTRON_JS) { res ->
                        mainHandler.post {
                            if (!tbLayerOpen) return@post
                            val hv = parseTbHarvest(res)
                            if (hv.ids.isNotEmpty()) {
                                Log.i("TbTransit", "数据blob命中（第${attempt + 1}次收割，" +
                                        "进页后 ${System.currentTimeMillis() - transitEnterMs}ms）")
                                proceedTaobaoOrders(hv.ids, hv.goods, fromCapture = false)
                                return@post
                            }
                            mainHandler.postDelayed({ harvestTaobaoOrders(attempt + 1) }, 1_000)
                        }
                    }
                    return@post
                }
                if (attempt >= TB_ULTRON_ATTEMPTS + 2) {
                    Log.i("TbTransit", "快慢两路都没收割到订单，保留旧卡")
                    tbTransitDone(failed = true)
                    return@post
                }
                // ── 慢路兜底：全量刮（innerHTML + 所有 window 对象），2s 步进 ──
                wv.evaluateJavascript(TbTransitProbe.HARVEST_ORDERS_JS) { res ->
                    mainHandler.post {
                        if (!tbLayerOpen) return@post
                        val hv = parseTbHarvest(res)
                        if (!hv.hrefOk || hv.ids.isEmpty()) {
                            mainHandler.postDelayed({ harvestTaobaoOrders(attempt + 1) }, 2_000)
                            return@post
                        }
                        proceedTaobaoOrders(hv.ids, hv.goods, fromCapture = false)
                    }
                }
            }
        }
    }

    /** 从接口响应原文里收割订单号（含状态前后文）+ 商品名（orderLineId 邻位的 item.title） */
    private fun harvestTbIdsFromText(
        text: String, ids: MutableMap<String, String>, goods: MutableMap<String, String>
    ) {
        val idRe = Regex("bizOrderId[^0-9]{0,8}([0-9]{12,25})")
        for (m in idRe.findAll(text)) {
            val id = m.groupValues[1]
            if (!ids.containsKey(id)) {
                ids[id] = text.substring(
                    maxOf(0, m.range.first - 300), minOf(text.length, m.range.first + 600)
                )
            }
        }
        // item.title 紧跟 orderLineId（中间隔着含花括号的 priceInfo ⇒ 用懒惰点号跨）
        val itemRe = Regex("\"orderLineId\":\"([0-9]{15,25})\"\\},\"item\":\\{.*?\"title\":\"([^\"]{2,80})\"")
        for (m in itemRe.findAll(text)) {
            val gid = m.groupValues[1]
            if (!goods.containsKey(gid)) goods[gid] = m.groupValues[2]
        }
    }

    /** 收割后的公共后续：取证落盘 → 判在途 → 商品名兜底 → 进详情页 */
    private fun proceedTaobaoOrders(
        ids: Map<String, String>, jsGoods: Map<String, String>, fromCapture: Boolean
    ) {
        // ★ 取证：订单号+状态前后文落盘（校准在途判据用）
        try {
            val f = java.io.File(getExternalFilesDir(null), "tb_harvest_dump.txt")
            val sb = StringBuilder()
            ids.forEach { (id, ctx) -> sb.append("== ").append(id).append(" ==\n").append(ctx).append("\n\n") }
            f.writeText(sb.toString())
        } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        // 在途判据：显式状态字段优先（ctx 里 "status":"卖家已发货"），viewLogistic 兜底
        val inTransit = ids.filterValues { tbInTransitHeuristic(it) }.keys.toList()
        Log.i("TbTransit", "订单 ${ids.size} 个，判在途 ${inTransit.size} 个：$inTransit" +
                if (fromCapture) "（快路：接口响应直收）" else "（慢路：页面内存）")
        if (inTransit.isEmpty()) {
            tbTransitDone()
            return
        }
        // 商品名：JS 结构化配对 → 店铺名兜底
        val goodsMap = LinkedHashMap<String, String>()
        ids.forEach { (id, ctx) ->
            val g = jsGoods[id].orEmpty()
            goodsMap[id] = if (g.isNotEmpty()) g else tbShopNameOf(ctx)
        }
        // ★★ vc485：**这里原来写的是 `inTransit.take(2)`** —— 淘宝在途单从第 3 单起
        //   直接被截掉，连详情页都不开（真机日志实锤：判在途 3 个，只开 2 个详情页，
        //   随后 "淘宝源完成：2 张卡"，用户看到"买了 3 件只显示 2 件"）。
        //   现在改为**全部处理**，只保留一个防失控上限（12 单），超限时明确打日志。
        val cap = 12
        val todo = ArrayList(if (inTransit.size > cap) inTransit.take(cap) else inTransit)
        if (inTransit.size > cap) {
            Log.w("TbTransit", "在途 ${inTransit.size} 单，超出单轮上限 $cap，本轮先处理前 $cap 单")
        }
        Log.i("TbTransit", "进入详情页阶段：${todo.size} 单（在途 ${inTransit.size}）")
        fetchTaobaoDetails(todo, ArrayList(), goodsMap)
    }

    /** 收割结果三件套：href 是否订单页 / id→状态前后文 / id→商品名 */
    private class TbHarvest(val hrefOk: Boolean, val ids: Map<String, String>, val goods: Map<String, String>)

    /** 解析 HARVEST_ORDERS_JS 的返回（双层 JSON 编码） */
    private fun parseTbHarvest(res: String?): TbHarvest {
        val ids = LinkedHashMap<String, String>()
        val goods = LinkedHashMap<String, String>()
        var hrefOk = false
        try {
            val s = WebHook.unquoteJsString(res)
            val o = JSONObject(s)
            val href = o.optString("href")
            hrefOk = href.contains("mlapp/olist")
            val arr = o.optJSONArray("ids") ?: return TbHarvest(hrefOk, ids, goods)
            val ctxO = o.optJSONObject("ctx") ?: JSONObject()
            val gO = o.optJSONObject("goods") ?: JSONObject()
            for (i in 0 until arr.length()) {
                val id = arr.optString(i)
                if (id.isNotEmpty()) {
                    ids[id] = ctxO.optString(id)
                    goods[id] = gO.optString(id)
                }
            }
        } catch (t: Throwable) {
            Log.w("TbTransit", "收割结果解析失败：${t.message}")
        }
        return TbHarvest(hrefOk, ids, goods)
    }

    /**
     * 在途判据（两道，按可靠性排序）：
     * ① 订单数据里有显式状态字段（真机实证 ±2500 字符窗口内有 `"status":"卖家已发货"`）
     *    ⇒ 有状态就用状态：含终态词排除，含在途词通过；
     * ② 状态字段没抓到 ⇒ 退回「查看物流」按钮信号（viewLogistic 只在有物流可看的订单上出现）。
     */
    private fun tbInTransitHeuristic(ctx: String): Boolean {
        val status = tbOrderStatusOf(ctx)
        if (status.isNotEmpty()) {
            val negative = listOf("交易成功", "交易关闭", "退款成功", "已签收", "已收货", "已取走")
            if (negative.any { status.contains(it) }) return false
            val positive = listOf("发货", "运输", "待收货", "下单", "揽收", "派送", "途中")
            return positive.any { status.contains(it) }
        }
        return ctx.contains("viewLogistic")
    }

    /** 从订单前后文里抠显式状态字段（"status":"卖家已发货" 这类）。
     *  ⚠️ 前后文里的 JSON 常是**二次转义**的（\"status\":\"...\"）⇒ 先反转义再匹配 */
    private fun tbOrderStatusOf(ctx: String): String {
        val norm = ctx.replace("\\\"", "\"")
        return Regex("\"status\":\"([^\"]{2,12})\"").find(norm)?.groupValues?.get(1).orEmpty()
    }

    /** 从订单前后文里抠店铺名（"shopName":"三个制皂匠"）—— 卡片标题用它比"淘宝包裹"有用 */
    private fun tbShopNameOf(ctx: String): String {
        val norm = ctx.replace("\\\"", "\"")
        return Regex("\"shopName\":\"([^\"]{2,30})\"").find(norm)?.groupValues?.get(1).orEmpty()
    }

    /** 逐单串行开 h5-detail（隐形 1x1 WebView —— SSR 页不依赖可见性，实测能出数据） */
    /**
     * 淘宝在途详情页（vc270o 重写，同志报"拉取太慢"）：
     *
     * 旧实现两个时间漏斗：
     * ① **串行** —— 一单开一个 WebView，等它 onPageFinished + 死等 2s + 抽数据，
     *    才开下一单。两单就是 2 ×（加载 1~5s + 2s）≈ 6~14s。
     * ② **固定 2s 盲等** —— 数据常常 0.3s 就进页面内存了，那 1.7s 纯浪费；
     *    偶尔 2s 还不够，又白跑一趟慢路。
     *
     * 新实现：
     * ① **并行** [TB_DETAIL_PARALLEL] 单同时开（每单各自独立 WebView，互不依赖），
     *    谁先好谁先出卡；用 [tbDetailInflight] 计数代替原来的递归收尾。
     * ② **轮询取代盲等** —— 首抽 250ms 起，之后每 300ms 抽一次，**抽到卡立刻走**；
     *    最多 8 次（≈2.65s 上限，与旧固定 2s 同量级，但均值从 2s 降到 ~0.5s）。
     */
    private fun fetchTaobaoDetails(
        queue: ArrayList<String>, done: ArrayList<JSONObject>, goods: Map<String, String>
    ) {
        if (!tbLayerOpen || transitLayer == null) {
            tbTransitDone(failed = true)
            return
        }
        tbDetailInflight = 0
        pumpTbDetails(queue, done, goods)
    }

    /** 维持 [TB_DETAIL_PARALLEL] 个详情页在飞；有单就补，没单且没在飞 ⇒ 收尾 */
    private fun pumpTbDetails(
        queue: ArrayList<String>, done: ArrayList<JSONObject>, goods: Map<String, String>
    ) {
        // 已经收尾/被新一轮接管 ⇒ 在飞的那些落地后直接丢，别再补
        if (!tbTransitBusy) return
        if (!tbLayerOpen || transitLayer == null) {
            tbTransitDone(failed = true)
            return
        }
        while (tbDetailInflight < TB_DETAIL_PARALLEL && queue.isNotEmpty()) {
            val id = queue.removeAt(0)
            tbDetailInflight++
            openTbDetailOne(id, queue, done, goods)
        }
        if (tbDetailInflight == 0) {
            // 全部落地（或一张都没抽到）：与旧递归收尾同一语义 —— 空手⇒保留旧卡。
            // ★★ vc311：但「一张卡都没有」有两种截然不同的含义，必须分开：
            //   · 页面没抽到任何数据（网络/风控/结构变了）⇒ failed，保留旧卡；
            //   · **每一单都判成终态**（全取走了）⇒ 这是**成功**，done 当然是空的。
            //   旧写法只看 done.isEmpty() ⇒ 只有一单且已取走时整轮被判"中断"，
            //   收尾里 `if (!failed)` 那段（落缓存 / 刷界面 / 发快照）根本不跑 ——
            //   真机日志实锤（2026-10-18）：「订单 ... 已是终态（已签收），不出卡」
            //   紧跟「淘宝源完成：0 张卡（中断，保留旧卡）」。
            //   ★ vc316：标记已不再自动打，但这套"全终态 = 成功"的判定仍然必须留着 ——
            //     否则会拿旧卡覆盖，界面与快照都卡在上一轮。
            tbTransitDone(done, failed = done.isEmpty() && tbTerminalHits == 0)
        }
    }

    /** 开**一单**的详情页：加载 → 轮询抽取 → 落地 → 补下一张 */
    private fun openTbDetailOne(
        id: String, queue: ArrayList<String>, done: ArrayList<JSONObject>, goods: Map<String, String>
    ) {
        Log.i("TbTransit", "开物流详情页：$id（并发 ${tbDetailInflight}/$TB_DETAIL_PARALLEL，剩 ${queue.size} 单排队）")
        val wv = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.blockNetworkImage = true   // 只要文本数据，图片全省
            setBackgroundColor(0x00000000)
        }
        var fired = false
        var tries = 0
        var settled = false          // vc270o：防重复落地（看门狗 vs 探针回调赛跑）
        var watchdog: Runnable? = null
        val url = "https://pages-g.m.taobao.com/wow/z/app/mtb/logisticsV2/h5-detail" +
                "?x-ssr=true&bizOrderId=$id&viewLogisticDetail=true"

        /** 落地一张（无论成败）：销毁 WebView、计数 -1、补下一张 */
        fun settle(card: JSONObject?) {
            if (settled) return
            settled = true
            watchdog?.let { mainHandler.removeCallbacks(it) }
            if (card != null) done.add(card)
            try { (wv.parent as? ViewGroup)?.removeView(wv) } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
            try { wv.destroy() } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
            tbDetailInflight--
            pumpTbDetails(queue, done, goods)
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, u: String) {
                if (fired) return
                fired = true
                Log.i("TbTransit", "详情页加载完成：$u")
                // ★ vc270o：轮询抽取（250ms 起抽，之后每 300ms；抽到卡立刻落地）
                //   ⚠️ 用局部递归函数而不是自引用 Runnable —— val 初始化器里引用自己编译不过
                fun pump() {
                    if (!tbTransitBusy) { settle(null); return }  // 栏已关/新一轮接管⇒销毁走人
                    tries++
                    v.evaluateJavascript(TbTransitProbe.DETAIL_EXTRACT_JS) { res ->
                        mainHandler.post {
                            if (!tbTransitBusy) { settle(null); return@post }
                            val card = buildTbCard(url, res, goods[id].orEmpty())
                            if (card != null) {
                                settle(card)
                            } else if (tbPickedNow.containsKey(id)) {
                                // ★ vc311：已判终态 ⇒ 立刻落地。**这不是失败**（终态不出卡是正常口径），
                                //   以前这里会白白轮询 8 次（真机日志：同一单连打 8 行"已是终态"）。
                                settle(null)
                            } else if (tries >= 8) {
                                Log.i("TbTransit", "订单 $id 轮询 $tries 次仍没数据，放弃这单")
                                settle(null)
                            } else {
                                mainHandler.postDelayed({ pump() }, 300)
                            }
                        }
                    }
                }
                mainHandler.postDelayed({ pump() }, 250)
            }
        }
        try {
            root.addView(wv, android.widget.FrameLayout.LayoutParams(1, 1))
            wv.alpha = 0.01f
        } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        // ★ vc270o：看门狗 —— onPageFinished 永远不来（网络挂死/页面 404）时，
        //   这单不能卡住整轮（tbDetailInflight 不归零 ⇒ 淘宝在途瘫痪到杀 App）
        watchdog = Runnable {
            if (!settled) {
                Log.w("TbTransit", "订单 $id 详情页 12s 还没加载完，放弃这单（不等了）")
                settle(null)
            }
        }
        mainHandler.postDelayed(watchdog, 12_000)
        wv.loadUrl(url)
    }

    /** 详情页抽取结果 → 第 5 栏卡片；终态件（已签收等）不出卡 */
    private fun buildTbCard(loadedUrl: String, res: String?, orderGoods: String): JSONObject? {
        try {
            val id = Regex("bizOrderId=([0-9]+)").find(loadedUrl)?.groupValues?.get(1) ?: ""
            val s = WebHook.unquoteJsString(res)
            val o = JSONObject(s)
            // ★ vc354：淘宝/菜鸟自己的物流状态枚举（物流详情页 SSR 里的 logisticsPackageStatus）
            val stsCode = o.optString("stsCode")
            val stsDesc = o.optString("stsDesc")
            val stsSeq = o.optString("stsSeq")
            // ★ vc669：AGENT_SIGN 在淘宝真实页面上有两种语义：
            //   一种是已取走；另一种（本次 2026-11-02 实证）是「待取件」且 seq=900。
            //   先把“待取件”这个更具体的业务状态传给裁决器，不能只看枚举代号。
            val tbVerdict = tbEnumVerdict(stsCode, stsDesc, stsSeq)
            Log.i(
                "TBEnum",
                "订单 $id 枚举=${stsCode.ifEmpty { "(无)" }} 「$stsDesc」seq=$stsSeq → 判定=$tbVerdict"
            )
            val traces = o.optJSONArray("traces") ?: return null
            if (traces.length() == 0) {
                Log.i("TbTransit", "订单 $id 没抠到轨迹（blobLen=${o.optInt("blobLen")}）")
                return null
            }
            val first = traces.optJSONObject(0) ?: return null
            val firstStatus = first.optString("status")
            // ★ vc311：补「已取件 / 已取货」—— 淘系对"取走"有几种说法，漏一个就会把
            //   已经取走的单当到站卡收进取件码栏（卡片上写着"已取件"却还占着待取位）。
            val negative = listOf("已签收", "已投递", "代签收", "已取走", "已取件", "已取货")
            // ★ vc354：枚举优先 —— 枚举说"已放入柜/驿站"时，即便文案写着已签收也不出终态（那是站点代签）
            if (tbVerdict == TB_EN_TAKEN || (tbVerdict != TB_EN_PICKUP && negative.any { firstStatus.contains(it) })) {
                // ★ vc311：终态单**不出卡，但必须记住**（不进 tbTransit 就没法参与分流与收尾判定）。
                // ★ vc316：记住 ≠ 打标记 —— 自动打「已取件」整条链已删，这里只记账。
                val rw = o.optString("waybill")
                // 一单只计一次（轮询会对同一单反复调本函数）
                val newTerm = !tbPickedNow.containsKey(id)
                if (rw.isNotEmpty()) tbPickedNow[rw] = firstStatus
                tbPickedNow[id] = firstStatus      // 缓存键可能退化成订单号（无真运单号时）
                if (newTerm) tbTerminalHits++
                // ★ vc357：自动打已取标签 —— 判定已取走时记灰标（缓存里的卡会变灰 24h）
                if (autoPickedTagOn() && rw.isNotEmpty()) {
                    setManualPicked("tb:$rw", true)
                    val c = PddCodeStore.get(this, "tb:$rw")
                    if (c != null && c.pickedAt == 0L) {
                        PddCodeStore.put(
                            this, "tb:$rw", c.copy(pickedAt = System.currentTimeMillis())
                        )
                    }
                }
                Log.i("TbTransit", "订单 $id 已是终态（$firstStatus），不出卡（vc316 起不打标记）")
                return null
            }
            val sb = StringBuilder()
            // ★ vc302：从轨迹文案里抠代收点名（如「抚州幼儿师范学校快递驿站」）
            //   —— 明细行显示地址用（同志：底部应该是派送地址，不是单号尾号）。
            //   词表要覆盖各家：菜鸟驿站/兔喜超市（极兔）/妈妈驿站/快递超市/服务站/丰巢…
            var station = ""
            for (i in 0 until traces.length()) {
                val t = traces.optJSONObject(i) ?: continue
                val text = t.optString("text") + " " + t.optString("status")
                if (station.isEmpty()) {
                    val m = Regex("([\\u4e00-\\u9fa5A-Za-z0-9\\+]{2,24}(?:驿站|快递柜|代收点|自提点|菜鸟站|兔喜|超市|服务站|丰巢|快递超市|妈妈驿站))")
                        .find(text)
                    if (m != null) station = m.groupValues[1]
                }
                sb.append(t.optString("time")).append(" ")
                    .append(t.optString("status")).append(" ")
                    .append(t.optString("text")).append("\n")
            }
            val realWaybill = o.optString("waybill")
            val card = JSONObject()
            card.put("src", "tb")
            // ★ vc314：把**订单号（bizOrderId）**也带出来 —— 静默复查要按它直开详情页
            //   （详情页只认 bizOrderId，不认运单号）。存进 SP 后，取件码页也能自查状态。
            card.put("orderId", id)
            // 身份键：有真运单号用真运单号（可与手动添加的同单号卡去重合并）；没有退订单号
            card.put("waybillNo", realWaybill.ifEmpty { id })
            card.put("realWaybill", realWaybill)
            // ★ vc301：详情页 blob 里抠到的取件码（极兔等自营驿站只在详情页给码，
            //   驿站列表接口 queryMultiStationPackages 查不到这类件）。空就留空，
            //   第 4 栏无码卡有兜底显示（状态/地址）。
            card.put("pickupCode", o.optString("pickCode"))
            card.put("goodsName", o.optString("goods").ifEmpty { orderGoods }.ifEmpty { "淘宝包裹" })
            card.put("latest", first.optString("text"))
            card.put("statusText", firstStatus)
            // ★ vc354/vc669：把枚举的完整上下文带在卡上；AGENT_SIGN 单看代号不够，
            //   渲染层还要知道它的文案/序号，才能识别「AGENT_SIGN + 待取件 + 900」。
            if (stsCode.isNotEmpty()) card.put("statusCode", stsCode)
            if (stsDesc.isNotEmpty()) card.put("enumDesc", stsDesc)
            if (stsSeq.isNotEmpty()) card.put("enumSeq", stsSeq)
            // ★ vc302：驿站名 —— 轨迹文案抠不到时，退回 blob 的 stationName 类字段
            //   （极兔/兔喜的文案里常不带"驿站"二字，只有独立字段有）
            card.put("stationText", station.ifEmpty { o.optString("stationName") })
            card.put("traceLines", sb.toString().trim())
            Log.i("TbTransit", "出卡：$id 运单=$realWaybill 状态=$firstStatus 码=${o.optString("pickCode")} 轨迹=${traces.length()}条 商品=${card.optString("goodsName")}")
            return card
        } catch (t: Throwable) {
            Log.w("TbTransit", "详情抽取解析失败：${t.message}")
            return null
        }
    }

    /** 收尾：交卡渲染 + 关隐形网页层 + 解除整链互斥。
     *  vc270c：failed=true（用户中断/页面异常）⇒ **保留旧卡**（按源缓存口径），只解除占位。 */
    /**
     * ★ vc302：淘宝在途卡是否「已到站」—— **两栏分流**的唯一判据（同志定的规矩：
     *   一单快递只出现在两栏其一 —— 在途 → 只在「在途快递」，已到站 → 只在「取件码」）。
     *   tbTransit 这份数据两栏共用，所以第 5 栏渲染时剔掉到站卡、第 4 栏只收到站卡。
     *   词表与 ExpressStore.W_ARRIVED 同语义（驿站/柜/代收点/待取），但不依赖 Item 转换。
     */
    // ★ vc354：淘宝/菜鸟标准物流状态枚举（见 companion object 的常量说明）
    //   ★ vc355：公开依据 = 阿里/菜鸟物流状态对照表（与韵达开放平台同源）：
    //     STA_INBOUND 已放入快递柜/驿站 → 取件码；STA_SIGN 从柜/驿站取出、SIGN/AGENT_SIGN 已签收 → 不显示；
    //     TRANSPORT/ON_THE_WAY/SEND_ON/ARRIVE_CITY/DELIVERING/ACCEPT/WAIT_ACCEPT/RECEIVE → 途中。
    //     未知值一律走文案兜底（页面实际是否下发子状态还没样本，见 2026-10-23 记忆）。
    /**
     * ★ vc669：淘宝枚举裁决，必须同时看 code + desc + seq。
     *
     * 真实样本（2026-11-02）：
     *   code=AGENT_SIGN, desc=「待取件」, seq=900
     * 这不是已取走，而是已经到代收点、等待用户取件；只看 code 会把它错误过滤掉。
     * 其他 AGENT_SIGN 若没有明确「待取件」文案，继续按原来的已取走口径处理。
     */
    private fun tbEnumVerdict(code: String, desc: String = "", seq: String = ""): Int {
        val c = code.uppercase()
        val d = desc.trim()
        val waiting = d.contains("待取件") || d.contains("待取") ||
                d.contains("取件码") || d.contains("取货码")
        if (waiting && (c == "AGENT_SIGN" || c == "SIGN" || c == "SIGNED" || seq == "900")) {
            Log.i("TBEnum", "具体文案覆盖枚举：$c 「$d」seq=$seq → 待取")
            return TB_EN_PICKUP
        }
        return when (c) {
            // 阿里/菜鸟标准表：已放入柜/驿站（含第三方代收入库）
            "STA_INBOUND", "INBOUND" -> TB_EN_PICKUP
            // 已签收 / 已从柜取出（含第三方代收取出、退回签收）
            "STA_SIGN", "SIGN", "SIGNED", "AGENT_SIGN", "OUTBOUND", "RETURN_SIGN" -> TB_EN_TAKEN
            // 运输 / 派送 / 揽收
            "TRANSPORT", "ON_THE_WAY", "SEND_ON", "ARRIVE_CITY", "DELIVERING", "SENT",
            "ACCEPT", "WAIT_ACCEPT", "RECEIVE" -> TB_EN_TRANSIT
            // SIGNFAIL / FAILED / RETURN / ISSUE / REJECTION 等异常：不从枚举硬判，交文案层
            else -> TB_EN_UNKNOWN
        }
    }

    private fun tbCardArrived(p: JSONObject): Boolean {
        // ★ vc354：枚举优先 —— 有 statusCode 就不看字
        when (tbEnumVerdict(
            p.optString("statusCode"), p.optString("enumDesc"), p.optString("enumSeq")
        )) {
            TB_EN_PICKUP -> return true
            TB_EN_TAKEN, TB_EN_TRANSIT -> return false
        }
        val st = p.optString("statusText") + " " + p.optString("latest")
        return listOf("待取件", "待取", "已到达", "已到", "已存放", "入柜", "快递柜", "代收点", "驿站")
            .any { st.contains(it) }
    }

    /**
     * ★ vc311：用户**手动**打的「已取件」标记（长按卡片打/撤）。
     * 返回标记时刻，没标记（或已超 24h）返回 0。
     *
     * ★ vc316 起这是**唯一**的标记来源（自动判定整条链已删）：
     * 用户说取走了就是取走了，自动轮次只负责保住它（[saveTbArrivedCache] 写 pickedAt
     * 时直接读这里），不许改它的主意。存在 SP 里，24h 后自然失效（与 pickedAt 同寿命）。
     */
    private fun manualPickedAt(key: String): Long {
        if (key.isEmpty()) return 0L
        val t = getSharedPreferences(PREFS, MODE_PRIVATE).getLong("mpicked_$key", 0L)
        return if (t > 0L && System.currentTimeMillis() - t < PddCodeStore.PICKED_KEEP_MS) t else 0L
    }

    private fun setManualPicked(key: String, on: Boolean) {
        val e = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
        if (on) e.putLong("mpicked_$key", System.currentTimeMillis()) else e.remove("mpicked_$key")
        e.apply()
        // ★★ v1.3.0：**同步写进取件码缓存**（同志 2026-11-09 报的 bug 的根因）——
        //   首页那张「N 件待取」是按 PddCodeStore 的 pickedAt 算的，而这里原来只改 SP 里的
        //   mpicked_* 账本 ⇒ 取件码页标了「已取件」，首页照旧显示"2 件待取"（两份账本各算各的）。
        //   现在两处一起写：标记动作完成的那一刻，首页/组件/流体云就在同一步里同步。
        //   （调用方本来就会 rebuildHome()，这里只补缓存与推送。）
        try {
            val c = PddCodeStore.all(this)[key]
            if (c != null) {
                // ★★ v1.3.2：这里必须走 **putForce** ——
                //   `put()` 有一条"新值 pickedAt=0 就沿用旧值"的保护（vc655 加的，防抓取回写抹掉标记），
                //   而"取消已取件"恰恰要把 pickedAt 显式写成 0 ⇒ 用 put 会被那条保护改回去，取消无效。
                //   putForce 就是为这个场景准备的（PddCodeStore 里原本就有）。
                PddCodeStore.putForce(
                    this, key,
                    c.copy(pickedAt = if (on) System.currentTimeMillis() else 0L)
                )
            } else {
                Log.w("PickupSync", "标记已取件但缓存里没有这条：$key")
            }
            PickupPublish.publish(this, notifyNew = false)
            Log.i("PickupSync", "标记已取件同步：$key on=$on")
        } catch (t: Throwable) {
            SafeRun.w("PickupSync", t)
        }
    }

    /**
     * ★ vc357：自动打已取标签开关（设置页「快递跟踪」分类）。
     * ★ vc358：同志拍板**默认关**——打标会保留灰卡 24h，有人不想要；要用自己开。
     */
    /**
     * ★★ v1.4.13：**「自动打已取标签」永久停用**（同志 2026-11-10 13:31：
     * 「取件码页面的清已取的来源是拼多多已签收的，并不是当前页面有标记已取件的取件码数量」）。
     *
     * 停用的理由不是"开关不好用"，而是**它的语义从一开始就错了**：
     * 拼多多的枚举 `SIGN` / `OUT_CABINET` 只说明"站点代签了"，跟"我本人把件拿走了"是两件事 ——
     * 用户 12:40 也正是因为这个抱怨（"已签收但没确认收货的件，取件码页和在途栏都看不到"：
     * 它们被自动打上灰标后既不进取件码页的待取、也不留在在途）。
     *
     * 而且它把「清已取」污染了：那些灰标不是用户标的，却和手动标记混在同一份计数里，
     * 于是右上角数字对不上页面。⇒ 现在**只有用户手动点的那一下算「已取件」**。
     *
     * 开关（`auto_picked_tag`）连同设置页那一项一并撤掉；这里恒 false，四处调用点自动失效，
     * 保留调用不作删改 —— 万一以后要恢复（比如平台真的提供了"已领取"这个明确枚举），改这一行就行。
     */
    private fun autoPickedTagOn(): Boolean = false

    // ================= ★ vc303：自动查真实格口码（Stellar shell 注入驱动）=================

    private var autoQueryRunning = false

    /**
     * 若这张到站卡的码是「疑似短码」⇒ 自动去拼多多查询页把**真实格口码**查出来。
     *
     * ## 为什么必须用真实输入（不能纯 JS）
     * 该页对 JS 合成事件免疫（填值例外，点击不认）⇒ 借 Stellar 的 shell 特权进程
     * 执行 /system/bin/input（等价于 adb 真键盘，实测可触发搜索与点击进入详情）。
     * **因此不需要无障碍权限** —— 用的是同志已授权的 Stellar。
     *
     * ## 代价与控制
     * 注入的是屏幕坐标 ⇒ 查询时页面必须在最上层可见（会看到「查真实取件码」的网页层数秒，
     * 跑完自动关闭并回填）。限频：同一单 6 小时只跑一次。
     */
    /**
     * ★ vc305：自动查真实取件码 —— **批量版**（vc304 直连链的多单化改造）。
     *
     * ## 为什么改
     * vc304b 链每单各开一次 assistant_pickup 取件页（串行 ~4s/单，两单排 8s 队）。
     * 而取件页是「驿站待取件列表」——一屏 = 全部件 + 各自格口码 ⇒ 开**一次**页即可把
     * 所有「运单号↔码」配对抓回（PddExpressChannel.openPickupPageBatch），N 单同速 ~4.5s。
     *
     * ## 链路（全程零坐标、零固定延时、不依赖 Stellar —— 纯 cookie + 页面读取）
     *   ① 收集待查单：到站 + 码空/短码 + 6h 限频过 —— 一次 fetchList(force) 全查；
     *   ② 列表文案自带码 → 直接回填；列表没有的 → cabinet 柜码接口**并行**查（~0.3s）；
     *   ③ 仍无码的单集 → **一次**开取件页批量配对；
     *   ④ 兜底：批量没配上的单（页面没有/渲染失败）逐个直开单件详情页。
     * 全链跑完统一 closeWeb + 记限频戳 + 刷卡面（finishAutoCodeBatch）。
     */
    private fun maybeAutoQueryRealCodeBatch() {
        if (autoQueryRunning) return
        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val want = LinkedHashSet<String>()
        for (t in tbTransit) {
            if (!tbCardArrived(t)) continue
            val w = t.optString("waybillNo")
            if (w.isEmpty()) continue
            val cur = t.optString("pickupCode")
            val hasReal = cur.isNotEmpty() && !PickupCodeResolver.isShortCode(w, cur)
            // ★ vc340：有真码但没站名/柜名的单也查一轮 —— cabinet 接口顺带把取件地址带回
            //   （快递柜单的码本来就来自这条接口，旧链只回码 ⇒ 地址永远空；6h 限频兜住频率）
            if (hasReal && t.optString("stationText").isNotEmpty()) continue       // 码、址双全，不用查
            if (now - sp.getLong("autoq_$w", 0L) < 6L * 3600_000L) continue          // 6h 限频
            want.add(w)
        }
        if (want.isEmpty()) return
        autoQueryRunning = true
        Log.i("AutoCode", "直连查码·批量（路线B）：${want.joinToString()}")
        PddExpressChannel.fetchList(this, force = true) { list ->
            val remain = ArrayList<Pair<String, PddExpressChannel.ListEntry>>() // 还要往下查的
            for (w in want) {
                val hit = PickupCodeResolver.matchFromList(list, w)
                val fromList = hit?.pickupCode.orEmpty()
                if (fromList.isNotEmpty() && !PickupCodeResolver.isShortCode(w, fromList)) {
                    fillCode(w, fromList, "列表")
                    Log.i("AutoCode", "★ 列表文案自带格口码：$w → $fromList")
                    continue
                }
                if (hit == null || hit.shippingId <= 0L) {
                    Log.i("AutoCode", "列表无此单（shipping_id 缺失）：$w ⇒ 放弃本轮")
                    continue
                }
                remain.add(w to hit)
            }
            if (remain.isEmpty()) {
                taobaoStage(want.toList())
                return@fetchList
            }
            // ② cabinet 柜码接口：每单一个线程**并行**（总耗时 ~0.3s，与单量无关）
            val after = ArrayList<Pair<String, PddExpressChannel.ListEntry>>()
            var pending = remain.count { it.second.orderSn.isNotEmpty() }
            if (pending == 0) {
                pickupPageStage(after, want.toList())
            } else {
                for ((w, hit) in remain) {
                    val osn = hit.orderSn
                    if (osn.isEmpty()) {
                        after.add(w to hit)
                        continue
                    }
                    Thread {
                        // ★ vc340：cabinetInfo = 码 + 柜名/取件地址 一起拿（旧 cabinetCode 把地址扔了）
                        val (code2, place2) = try { PddNative.cabinetInfo(this, osn) } catch (_: Throwable) { "" to "" }
                        mainHandler.post {
                            if (!autoQueryRunning) return@post // 上层已收口（异常路径），别再续链
                            if (code2.isNotEmpty() && !PickupCodeResolver.isShortCode(w, code2)) {
                                fillCode(w, code2, "cabinet", place2.take(60))
                                Log.i("AutoCode", "★ cabinet 接口拿到格口码：$w → $code2 柜=${place2.ifEmpty { "无" }}")
                            } else {
                                // ★ vc341：接口没回码但回了地址（"有真码缺地址"单的补址场景）—— 地址收下，只落址不动码
                                if (code2.isEmpty() && place2.isNotEmpty()) {
                                    val t = tbTransit.firstOrNull {
                                        it.optString("waybillNo") == w || it.optString("realWaybill") == w
                                    }
                                    if (t != null && t.optString("stationText").isEmpty()) {
                                        t.put("stationText", place2.take(60))
                                        PddCodeStore.get(this, "tb:$w")?.let { c ->
                                            if (c.station.isEmpty()) PddCodeStore.put(
                                                this, "tb:$w", c.copy(station = place2.take(60))
                                            )
                                        }
                                        Log.i("AutoCode", "★ cabinet 接口补到取件地址（码已齐）：$w 柜=$place2")
                                    }
                                }
                                if (code2.isEmpty()) Log.i("AutoCode", "cabinet 无码：$w ⇒ 走批量取件页")
                                after.add(w to hit)
                            }
                            if (--pending == 0) pickupPageStage(after, want.toList())
                        }
                    }.start()
                }
            }
        }
    }

    /** 批量链③：**一次**开 assistant_pickup 取件页，配回多少算多少；④没配上的走详情页兜底。 */
    private fun pickupPageStage(
        queue: List<Pair<String, PddExpressChannel.ListEntry>>, queried: List<String>
    ) {
        // ★ vc340：只为「还没有真码」的单开页面 —— 有码但缺地址的单在 ② 已试过 cabinet 接口，
        //   非柜件那里拿不到地址，别为它们反复开页（vc340 把缺址单也放进 want 了，6h 一轮会走到这）。
        val need = queue.filter { (w, _) ->
            val cur = tbTransit.firstOrNull {
                it.optString("waybillNo") == w || it.optString("realWaybill") == w
            }?.optString("pickupCode").orEmpty()
            cur.isEmpty() || PickupCodeResolver.isShortCode(w, cur)
        }
        if (need.isEmpty() || !autoQueryRunning) {
            taobaoStage(queried)
            return
        }
        val wbs = need.map { it.first }
        PddExpressChannel.openPickupPageBatch(this, wbs) { map ->
            if (!autoQueryRunning) return@openPickupPageBatch
            for ((w, c) in map) {
                fillCode(w, c, "取件页")
                Log.i("AutoCode", "★ 取件页批量配对：$w → $c")
            }
            val missed = need.filter { !map.containsKey(it.first) }
            if (missed.isEmpty()) {
                taobaoStage(queried)
            } else {
                Log.i("AutoCode", "取件页未配上 ${missed.size} 单 ⇒ 逐个详情页兜底")
                detailFallbackChain(missed, queried)
            }
        }
    }

    /** 批量链④兜底：取件页没配上的单，逐个直开**单件详情页**（不再重复开取件页）。
     *  openDetailForCode 回调在主线程，串行续链；全跑完统一收口。 */
    private fun detailFallbackChain(
        queue: List<Pair<String, PddExpressChannel.ListEntry>>, queried: List<String>
    ) {
        if (queue.isEmpty() || !autoQueryRunning) {
            taobaoStage(queried)
            return
        }
        val (w, hit) = queue[0]
        PddExpressChannel.openDetailForCode(this, detailUrlFor(hit, w), w) { code ->
            if (code.isNotEmpty()) fillCode(w, code, "详情页")
            detailFallbackChain(queue.drop(1), queried)
        }
    }

    /**
     * ★ vc308 批量链⑤：**淘宝侧查码**（校园「我的快递」页）。
     *
     * ## 为什么放在最后
     * ①②③④ 是已经跑通、约 4.3 秒收口的拼多多链 —— 不能因为新增淘宝侧就把它拖慢。
     * 淘宝这一环只补**拼多多看不到的件**：
     *   · 拼多多「我的快递」里压根没有的单（纯淘宝单）；
     *   · 前面几道都配不上的单。
     * 所以它挂在链尾：跑完不损失既有速度，只在有缺口时才多开一个隐形页（约 4~6 秒）。
     *
     * ## 它凭什么能查到
     * 淘宝校园页自带 lib-mtop + 登录 cookie，在页面里调
     * `mtop.cainiao.pickup.xiaoyuan.queryMultisitePkgs`（按手机号 / 按单号），
     * 返回包裹 DTO 里的 `shelfNo` 就是货架/格口号。详见 [TbSchoolChannel]。
     */
    private fun taobaoStage(queried: List<String>) {
        if (!autoQueryRunning) {
            finishAutoCodeBatch(queried)
            return
        }
        val still = queried.filter { w ->
            val cur = tbTransit.firstOrNull {
                it.optString("waybillNo") == w || it.optString("realWaybill") == w
            }?.optString("pickupCode").orEmpty()
            cur.isEmpty() || PickupCodeResolver.isShortCode(w, cur)
        }
        if (still.isEmpty()) {
            finishAutoCodeBatch(queried)
            return
        }
        Log.i("AutoCode", "淘宝校园页查码：${still.joinToString()}")
        TbSchoolChannel.queryBatch(this, still) { map ->
            if (!autoQueryRunning) return@queryBatch
            for ((w, c) in map) {
                if (c.isNotEmpty() && !PickupCodeResolver.isShortCode(w, c)) {
                    fillCode(w, c, "淘宝校园")
                    Log.i("AutoCode", "★ 淘宝校园页拿到码：$w → $c")
                }
            }
            if (map.isEmpty()) Log.i("AutoCode", "淘宝校园页无命中 ⇒ 本轮收口")
            finishAutoCodeBatch(queried)
        }
    }

    /** 单件详情页直链（官方 jump_url 参数最全，缺失则退回手拼版） */
    private fun detailUrlFor(hit: PddExpressChannel.ListEntry, waybill: String): String =
        if (hit.jumpUrl.startsWith("http")) hit.jumpUrl
        else if (hit.jumpUrl.isNotEmpty())
            "https://mobile.yangkeduo.com/" + hit.jumpUrl.removePrefix("/")
        else "https://mobile.yangkeduo.com/psnl_goods_help.html?_t_module_name=assistant_waybill" +
                "&shipping_id=${hit.shippingId}&tracking_number=$waybill"

    private fun fillCode(waybill: String, code: String, src: String, station: String = "") {
        PddCodeStore.get(this, "tb:$waybill")?.let { c ->
            // ★ vc340：station 非空时一并落缓存（新值优先；空则保留旧站名）
            PddCodeStore.put(this, "tb:$waybill", c.copy(code = code, station = station.ifEmpty { c.station }))
            Log.i("AutoCode", "★ $src 抠到格口码：$waybill → $code" + if (station.isNotEmpty()) " 柜/站=$station" else "")
        }
        // ★ vc304b：同步内存卡 —— 第 4 栏渲染读 tbTransit，只改缓存不改内存 = 卡面仍短码
        //   （真机实证：日志报回填成功、dump 卡面还是 43943）
        for (t in tbTransit) {
            if (t.optString("waybillNo") == waybill || t.optString("realWaybill") == waybill) {
                t.put("pickupCode", code)
                // ★ vc340：卡上没地址才填（别把已有的更准站名洗掉）
                if (station.isNotEmpty() && t.optString("stationText").isEmpty()) {
                    t.put("stationText", station)
                }
                break
            }
        }
    }

    /** 批量链统一收口：关网页层、给**所有参与查询的单**记 6h 限频戳、刷三处卡面。 */
    private fun finishAutoCodeBatch(queried: List<String>) {
        try {
            closeWeb()
        } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        autoQueryRunning = false
        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        val now = System.currentTimeMillis()
        for (w in queried) {
            sp.edit().putLong("autoq_$w", now).apply()
        }
        if (pickupListContainer != null) renderMerged()
        HomeWidgets.updateAll(this)
        LiveUpdate.syncFromSnapshot(this)
        IslandHelper.syncFromSnapshot(this)
    }

    /**
     * ★ vc302：把「已到站」的淘宝卡**持久化**进取件码缓存（键 `tb:运单号`）。
     *
     * ## 为什么必须有这一步（真机 16:10 实测的教训）
     * tbTransit 是**内存态**：App 一杀就空。菜鸟驿站接口又查不到极兔等自营驿站的件，
     * 第 4 栏若不从缓存补，重启后就是「途中页没有（已分流剔掉）、取件码页也没有（内存空）」
     * —— 两栏全空。持久化后第 4 栏每轮渲染从缓存补出，脑图④「已到达 → 取件码页」才成立。
     *
     * ## 取走判定（vc316 起）
     * **不再自动判定**。「已取件」灰标只由用户长按卡片手动打（24h 后自动清）。
     * 理由：状态文案会滞后/说谎（极兔「已签收」其实只是到代收点，
     * vc315 真机实证），打错一次就把还要用的码划掉，比"没标记"更糟。
     */
    private fun saveTbArrivedCache() {
        for (t in tbTransit) {
            if (!tbCardArrived(t)) continue
            val waybill = t.optString("waybillNo")
            if (waybill.isEmpty()) continue
            val st = t.optString("statusText")
            val prev = PddCodeStore.get(this, "tb:$waybill")
            // ★ vc304b：prev 里若是真格口码（非短码），别让 tbTransit 的短码把它洗掉——
            //   真机实证：直连查码刚回填 59-6-6012，下一轮刷新 saveTbArrivedCache 又写回 43943
            val prevCode = prev?.code.orEmpty()
            val keepReal = prevCode.isNotEmpty() && !PickupCodeResolver.isShortCode(waybill, prevCode)
            PddCodeStore.put(
                this, "tb:$waybill",
                PddCodeStore.Cached(
                    src = "tb",
                    code = if (keepReal) prevCode else t.optString("pickupCode").ifEmpty { prevCode },
                    station = t.optString("stationText").ifEmpty { prev?.station.orEmpty() },
                    waybill = waybill,
                    latest = st.ifEmpty { prev?.latest.orEmpty() },
                    ts = System.currentTimeMillis(),
                    // ★★ vc316：**只有长按手动标记**才允许 pickedAt != 0。
                    //   自动链一律写 0 —— 灰标从此只代表"用户自己标的"，
                    //   不会再有机器猜的（猜错一次就把还要用的码划掉）。
                    pickedAt = manualPickedAt("tb:$waybill")
                )
            )
            Log.i("TbTransit", "到站卡已入缓存：tb:$waybill 码=${t.optString("pickupCode")} 站=${t.optString("stationText")}")
            // ★ vc314：订单号落 SP（运单号 → bizOrderId）。留档用：淘宝详情页只认订单号，
            //   运单号打不开；以后要开详情/复查直接从 SP 取，不用再猜。
            val oid = t.optString("orderId")
            if (oid.isNotEmpty()) {
                val k = "tb_oid_$waybill"
                val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
                if (sp.getString(k, "") != oid) sp.edit().putString(k, oid).apply()
            }
        }
        // ★★ vc316：原本这里会把「本轮判为终态」的 tb 卡自动打上「已取件」。
        //   整段已删 —— 现在只有长按手动标记。tbPickedNow 仍保留，
        //   但只用于第 5 栏分流（终态件不出在途卡）与收尾判定。
        // ★★ vc524：**到站交接**（同志 10:35 定的逻辑）——
        //   ① 已到站 ⇒ 取件码栏先摆一张"待补取件码"卡（**不写缓存**，避免脏数据）
        //   ② 无码 ⇒ 用运单号去「淘宝按运单号查」→ 查不到再用「拼多多列表」兜底
        //   ③ 查到 ⇒ 写缓存 + **从途中删除**（交接完成）
        //   ④ 查不到 ⇒ 卡上写「查询不出取件码」，**途中保留不删**
        val arrived = tbTransit.filter { tbCardArrived(it) }
        val toResolve = ArrayList<JSONObject>()
        val spTry = getSharedPreferences(PREFS, MODE_PRIVATE)
        val nowTry = System.currentTimeMillis()
        for (t in arrived) {
            val wb = t.optString("waybillNo")
            if (wb.isEmpty()) continue
            val cached = PddCodeStore.get(this, "tb:$wb")
            if (cached != null && cached.code.isNotEmpty()) continue   // 已有码，交给缓存卡
            val prev = tbPendingCards[wb]
            if (prev != null && prev.optBoolean("resolveFailed")) continue  // 已判失败，等用户再刷新
            val pc = prev ?: JSONObject().apply {
                put("src", "cainiao")
                put("waybillNo", wb)
                put("pickupCode", "")
                put("stationText", t.optString("stationText"))
                put("statusText", "待补取件码")
                put("pendingResolve", true)
            }
            if (pc.optString("stationText").isEmpty()) pc.put("stationText", t.optString("stationText"))
            tbPendingCards[wb] = pc
            // 单卡 30 分钟限频（同一单不反复查）
            if (nowTry - spTry.getLong("tbcode_try_$wb", 0L) > 30 * 60_000L) toResolve.add(t)
        }
        if (toResolve.isNotEmpty()) {
            Log.i("CodeBridge", "到站待补码 ${toResolve.size} 单，本轮先补 ${minOf(4, toResolve.size)}")
            resolveArrivedCodes(toResolve.take(4))
        }
    }

    /**
     * ★ vc524：到站件的补码链。
     * ① 淘宝按运单号（TbSchoolChannel，一次≤4）→ ② 拼多多「我的快递」列表 → ③ 标记失败（途中保留）
     */
    private fun resolveArrivedCodes(cards: List<JSONObject>) {
        val wbs = cards.mapNotNull { it.optString("waybillNo").ifEmpty { null } }
        if (wbs.isEmpty()) return
        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        val now = System.currentTimeMillis()
        sp.edit().apply { for (w in wbs) putLong("tbcode_try_$w", now) }.apply()
        Log.i("CodeBridge", "① 淘宝按运单号查：$wbs")
        TbSchoolChannel.queryBatch(this, wbs) { map ->
            var changed = false
            val remain = ArrayList<String>()
            for (wb in wbs) {
                val c = map[wb.uppercase()] ?: map[wb]
                if (!c.isNullOrEmpty()) {
                    if (applyResolvedCode(wb, c)) changed = true
                } else {
                    remain.add(wb)
                }
            }
            Log.i("CodeBridge", "① 结果：命中 ${wbs.size - remain.size} / ${wbs.size}")
            if (changed) refreshAfterCodeResolve()
            if (remain.isEmpty()) return@queryBatch
            // ② 拼多多列表兜底
            Log.i("CodeBridge", "② 拼多多列表兜底：$remain")
            PddExpressChannel.fetchList(this) { list ->
                var changed2 = false
                for (wb in remain) {
                    if (list.isEmpty()) {
                        markResolveFailed(wb)
                        continue
                    }
                    val st = tbPendingCards[wb]?.optString("stationText").orEmpty()
                    PickupCodeResolver.resolveFromList(this, wb, "", st, list) { res ->
                        if (res.code.isNotEmpty()) {
                            if (applyResolvedCode(wb, res.code)) changed2 = true
                        } else {
                            markResolveFailed(wb)
                        }
                    }
                }
                if (changed2) refreshAfterCodeResolve()
            }
        }
    }

    /** 补到码：落缓存 → 移除占位卡 → **从途中删除**（交接完成） */
    private fun applyResolvedCode(wb: String, code: String): Boolean {
        val st = tbPendingCards[wb]?.optString("stationText").orEmpty()
        val cur = PddCodeStore.get(this, "tb:$wb")
        PddCodeStore.put(
            this, "tb:$wb",
            PddCodeStore.Cached(
                src = "cainiao", code = code,
                station = st.ifEmpty { cur?.station ?: "" },
                waybill = wb, latest = cur?.latest ?: "",
                ts = System.currentTimeMillis(), pickedAt = cur?.pickedAt ?: 0L
            )
        )
        tbPendingCards.remove(wb)
        tbTransit.removeAll { it.optString("waybillNo") == wb }
        Log.i("CodeBridge", "✅ 补到码 $wb → $code（已从途中删除）")
        return true
    }

    /** 补不到：卡上标提示，**途中保留** */
    private fun markResolveFailed(wb: String) {
        tbPendingCards[wb]?.apply {
            put("resolveFailed", true)
            put("statusText", "查询不出取件码")
            // 主信息位是"码/站名"二选一，这里把提示拼在站名后面，确保用户一定看得到
            val st = optString("stationText")
            if (!st.contains("查询不出取件码")) put("stationText", (st + "（查询不出取件码）").trim())
        }
        Log.w("CodeBridge", "❌ 补码失败（途中保留）：$wb")
    }

    private fun refreshAfterCodeResolve() {
        try {
            if (pickupListContainer != null) renderMerged()
            if (transitLayer != null) renderInTransit()
            HomeWidgets.updateAll(this)
            LiveUpdate.syncFromSnapshot(this)
            IslandHelper.syncFromSnapshot(this)
        } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
    }

    /** 缓存里的 tb 到站卡 → 第 4 栏卡片（缺字段给兜底；48h 兜底过期） */
    private fun tbCachedCard(c: PddCodeStore.Cached): JSONObject = JSONObject().apply {
        put("src", "tb")
        put("waybillNo", c.waybill)
        put("pickupCode", c.code)
        put("stationText", c.station)
        // ★ vc311：已取件的卡状态位直接写「已取件」—— 缓存里的 latest 是**取走前**的旧文案
        //   （"待取件/在驿站待取"），照搬上卡会出现「已取件」灰标 +「待取件」正文自相矛盾。
        put("statusText", if (c.pickedAt != 0L) "已取件" else c.latest.ifEmpty { "待取件" })
        put("pickedAt", c.pickedAt)
        put("goodsName", "淘宝包裹")
        put("realWaybill", c.waybill)
    }

    private fun tbTransitDone(cards: ArrayList<JSONObject>? = null, failed: Boolean = false) {
        val got = cards ?: ArrayList()
        tbTransitBusy = false
        if (tbLayerOpen) {
            tbLayerOpen = false
            if (webLayer != null) closeWeb()
        }
        if (!failed) tbTransit = got
        // ★ vc342（P0-4）：**先结算、再看层在不在**。
        //   旧代码下面那句 `if (transitLayer == null) return` 在 transitGot++ 之前 ——
        //   用户中途退出第 5 栏时，这一路永远不记账 ⇒ `allDone` 永假 ⇒
        //   ①下拉刷新的转圈不复位 ②这一轮 transit_cache 不落盘（冷启动看到更旧的数据）。
        val counted = tbTransitRoundGen == transitRoundGen && transitPendings.remove("tb")
        if (counted) transitGot++
        Log.i("TbTransit", "淘宝源完成：${got.size} 张卡${if (failed) "（中断，保留旧卡）" else ""}" +
                if (counted) "" else "（本轮已结算过）")
        if (transitLayer == null) return
        renderInTransit()
        // ★ vc301：淘宝在途落地后要接两件事 ——
        //   ① 第 4 栏重渲染：到站件（待取件/派送中）现在会进取件码栏。
        //      ⚠️ renderMerged 开头 `pickupListContainer ?: return` —— **第 4 栏没打开过
        //      （容器未建）时直接 return**，所以不能只靠它。
        //   ② publishTrackSnapshot：快照发布 + 跃迁/首见补报通知**不依赖第 4 栏 UI**，
        //      单独调 —— 否则用户没开过第 4 栏就永远收不到补报（真机 15:59 实测踩中）。
        if (!failed) {
            // ★ vc311：**先落缓存、再渲染**。原来顺序反了 ⇒ saveTbArrivedCache 刚回填的
            //   真格口码/驿站名要等下一轮渲染才看得见（一轮可能就是几十秒）。
            saveTbArrivedCache()   // ★ vc302：到站卡持久化（第 4 栏重启后从缓存补出）
            if (pickupListContainer != null) renderMerged()
            publishTrackSnapshot()
            // ★ vc302：流体云对齐**不依赖第 4 栏打开** —— 途中页刷出取件码后用户往往
            //   直接退桌面，而 syncFromSnapshot 原来只挂在 renderMerged 尾部 ⇒ 实况位
            //   不挂（同志 16:33 实测：码刷出来了，退桌面流体云没有）。补对齐这一下。
            LiveUpdate.syncFromSnapshot(this)
            IslandHelper.syncFromSnapshot(this)
        }
    }

    private fun cancelPickupTimeout() {
        pickupTimeout?.let { mainHandler.removeCallbacks(it) }
        pickupTimeout = null
    }

    private fun handlePackagesResult(json: String?) {
        if (json.isNullOrEmpty()) {
            cainiaoErr = "空响应"
            onPickupPart("cainiao", ArrayList())
            return
        }
        try {
            val obj = JSONObject(json)
            if (obj.optBoolean("ok")) {
                // vc268c：调试开关开着就把原始返回整体落盘（翻口袋：查有没有 bizOrderId/完整轨迹）
                if (debugDumpTb) dumpTbQuery(json)
                val pkgs = ArrayList<JSONObject>()
                collectPackages(obj.opt("data"), pkgs)
                // 打来源标记，渲染层靠它区分角标
                for (p in pkgs) p.put("src", "cainiao")
                cainiaoNeedLogin = false
                // ★ 这条日志以前没有 ⇒ 体检时"跑了但 0 件"和"压根没跑"在日志上长得一样
                Log.i("TaobaoFetch", "淘宝/菜鸟返回 ${pkgs.size} 个包裹（带 pickupCode 的）via=${obj.optString("via").ifEmpty { "?" }}")
                // ★ vc521：0 条时把原始回包打出来 —— 到底是"真没有待取"还是"参数不对返回空"
                if (pkgs.isEmpty()) {
                    runCatching {
                        Log.w("TaobaoFetch", "空列表，原始回包=" + json.take(900))
                    }
                    // ★★ vc605：**平台说"没有待取件"就是权威结果** ⇒ 清掉这个源的陈旧缓存。
                    //   以前只在 48h 后被动过期，于是昨天的 6 个码今天还能拄在首页（同志报的"假数据"）。
                    purgeStaleCodeCache("cainiao")
                }
                onPickupPart("cainiao", pkgs)
            } else {
                val err = obj.optJSONArray("error")?.join(",") ?: "UNKNOWN"
                // NO_LIB 不算"未登录"：那是页面组件还没就绪（runQueryJs 已重试过），
                // 报成未登录会把用户误导去重新登录一个其实好好的账号
                Log.w("TaobaoFetch", "淘宝/菜鸟查询失败: $err")
                if (err.contains("NO_LIB")) {
                    cainiaoErr = "页面组件未就绪"
                } else if (err.contains("SESSION") || err.contains("LOGIN") ||
                    err.contains("TOKEN")
                ) {
                    cainiaoNeedLogin = true
                } else {
                    cainiaoErr = err.substringAfter("::").ifEmpty { err }
                }
                onPickupPart("cainiao", ArrayList())
            }
        } catch (e: Exception) {
            cainiaoErr = e.message ?: "解析异常"
            onPickupPart("cainiao", ArrayList())
        }
    }

    /**
     * vc268c：淘宝 queryMultiStationPackages **原始返回**整体落盘（外部目录，adb 可拉）。
     * 用途：翻口袋——确认返回里有没有 bizOrderId（订单号）/ 完整物流轨迹，决定"补全淘宝物流"怎么走。
     */
    private fun dumpTbQuery(json: String) {
        try {
            val dir = getExternalFilesDir(null) ?: return
            val f = java.io.File(dir, "tb_query_dump.json")
            f.writeText(json)
            Log.i("TaobaoFetch", "原始返回已落盘：${f.absolutePath}（${json.length} 字符）")
        } catch (t: Throwable) {
            Log.w("TaobaoFetch", "原始返回落盘失败：${t.message}")
        }
    }

    /** 递归收集所有带 pickupCode 的包裹对象 —— 不猜响应层级，结构改版也不怕 */
    private fun collectPackages(node: Any?, out: ArrayList<JSONObject>) {        when (node) {
            is JSONObject -> {
                if (!node.optString("pickupCode").isNullOrEmpty()) out.add(node)
                val keys = node.keys()
                while (keys.hasNext()) collectPackages(node.opt(keys.next()), out)
            }
            is JSONArray -> {
                for (i in 0 until node.length()) collectPackages(node.opt(i), out)
            }
        }
    }

    // ---------- WebView 层（第 1 / 3 栏的官方 H5 页面） ----------

    @SuppressLint("SetJavaScriptEnabled")
    /**
     * ★ vc375：清掉 App 内残留的淘宝/菜鸟登录 cookie（换号时必须）。
     * `CookieManager.removeAllCookies` 会把拼多多一起清掉，所以按域逐个过期。
     */
    private fun clearTaobaoCookies() {
        val cm = CookieManager.getInstance()
        val hosts = listOf(
            "https://taobao.com", "https://m.taobao.com", "https://h5.m.taobao.com",
            "https://pages-fast.m.taobao.com", "https://pages-g.m.taobao.com",
            "https://trade.taobao.com", "https://uland.taobao.com",
            "https://tmall.com", "https://m.tmall.com",
            "https://cainiao.com", "https://m.cainiao.com",
            "https://tb.cn", "https://m.tb.cn"
        )
        var n = 0
        for (h in hosts) {
            val ck = try { cm.getCookie(h).orEmpty() } catch (_: Throwable) { "" }
            if (ck.isEmpty()) continue
            for (pair in ck.split(';')) {
                val name = pair.substringBefore('=').trim()
                if (name.isEmpty()) continue
                try {
                    cm.setCookie(h, "$name=; Max-Age=0; Path=/")
                    n++
                } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
            }
        }
        try { cm.flush() } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        Log.i("TbLogin", "清淘宝/菜鸟登录 cookie $n 个")
    }

    /**
     * ★ vc404：精确清淘宝/菜鸟 cookie。
     * 关键： 要带 **Domain**，否则只删掉 host-only 的那一份，
     * 真正的  域 cookie 还在（这就是之前"清不干净"的原因）。
     */
    private fun clearTaobaoCookiesExact(): Int {
        val cm = CookieManager.getInstance()
        val urls = listOf(
            "https://taobao.com", "https://m.taobao.com",
            "https://pages-fast.m.taobao.com", "https://h5api.m.taobao.com",
            "https://cainiao.com", "https://m.cainiao.com",
            "https://tmall.com", "https://m.tb.cn", "https://tb.cn"
        )
        val domains = listOf(".taobao.com", ".m.taobao.com", ".cainiao.com", ".tmall.com", ".tb.cn")
        val names = LinkedHashSet<String>()
        for (u in urls) {
            val ck = try { cm.getCookie(u).orEmpty() } catch (_: Throwable) { "" }
            for (pair in ck.split(';')) {
                val n = pair.substringBefore('=').trim()
                if (n.isNotEmpty()) names.add(n)
            }
        }
        for (u in urls) {
            for (n in names) {
                for (d in domains) {
                    try { cm.setCookie(u, "$n=; Max-Age=0; Domain=$d; Path=/") } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
                }
                try { cm.setCookie(u, "$n=; Max-Age=0; Path=/") } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
            }
        }
        try { cm.flush() } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        // 复查：还剩几个名字
        var left = 0
        for (u in listOf("https://taobao.com", "https://pages-fast.m.taobao.com")) {
            val ck = try { cm.getCookie(u).orEmpty() } catch (_: Throwable) { "" }
            left += ck.split(';').count { it.substringBefore('=').trim().isNotEmpty() }
        }
        Log.i("TbLogin", "清淘宝/菜鸟 cookie：名字 ${names.size}，复查剩余 $left")
        return names.size
    }

    // ---------------- 菜鸟身份码：主动取码 + 原生画条码（vc525） ----------------

    /** 页面 → 原生：把 identity.code 接口的返回送回来 */
    private inner class IdCodeBridge {
        @android.webkit.JavascriptInterface
        fun onCode(json: String?) {
            mainHandler.post { handleIdentityCodeJson(json) }
        }
    }

    /**
     * ★ vc525：身份码页**自己不画码**（真机日志：页面渲染正常、canvas=0、且全程没有
     * `identity.code.mini.app` 请求）。这里改为：在页面上下文里主动调那个接口（签名由页面算），
     * 拿到 `identityCode` 后由**原生**画 Code128 条码 —— 不再依赖页面的画码逻辑。
     */
    /**
     * ★ vc527：接口缺 `positionName`（真机日志实证：
     * `FAIL_SYS_BIZPARAM_MISSED::缺少业务参数positionName`）。
     * 不知道正确取值，就按候选列表**逐个试探**，谁返回 identityCode 就用谁，结果写日志。
     */
    /**
     * ★ vc529：参数逐级试探。
     * 真机实证（2026-10-29 14:15 日志）：
     *   空 data         → `缺少业务参数positionName`
     *   positionName=identityCode → **`缺少业务参数appName`**（说明 positionName 取值对了）
     * 现在固定 positionName=identityCode，遍历 appName 候选。
     */
    /**
     * ★ vc531：**换接口**！
     * 从淘宝页面 JS 包（end-collect-platform/1.0.424/js/p_layout.js，模块 70736）扒出
     * 页面真正的取码函数：
     *   `he() → mtop.cainiao.pickup.identity.getIdentityMessage`（**无业务参数**）
     *   返回对象里就有 `identityCode`。
     * 之前调的 `identity.code.mini.app` 根本不是页面用的接口（还缺 positionName/appName）。
     */
    private fun identityFetchJs(): String {
        return ("(function(){try{" +
                "if(!(window.lib&&window.lib.mtop)){window.__idcode.onCode('NOLIB');return 'nolib';}" +
                "window.lib.mtop.request({api:'mtop.cainiao.pickup.identity.getIdentityMessage',v:'1.0'," +
                "type:'GET',dataType:'json',data:{}}).then(function(r){var d=JSON.stringify(r);" +
                "window.__idcode.onCode(/identityCode/.test(d)?('OKHIT:|'+d):('OTHER:'+d.take(300)));}," +
                "function(e){window.__idcode.onCode('ERR:'+JSON.stringify((e&&e.ret)||''));});})()" +
                "}catch(e){return 'ex:'+e;}})()")
    }

    private fun handleIdentityCodeJson(json: String?) {
        val raw = json ?: ""
        Log.i("IdCodeNative", "页面取码返回: " + raw.take(300))
        // ★ vc549：页面内 RPC 直接给的原始回包（含 identityCode）——先处理这种最快路径
        if (raw.contains("identityCode")) {
            val c = raw.substringAfter("\"identityCode\":\"", "").substringBefore('"')
            if (c.isNotEmpty()) {
                idCodeTs = System.currentTimeMillis()
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("id_code_last", c).putLong("id_code_ts", idCodeTs).apply()
                val pv = pendingReplaceView
                if (pv != null) {
                    pendingReplaceView = null
                    replaceCodeInPage(pv, c)
                }
                return
            }
        }
        if (raw.startsWith("OTHER:")) {
            // vc531：接口成功但没返回 identityCode（把原文打出来看结构）
            Log.w("IdCodeNative", "回包无 identityCode: " + raw.removePrefix("OTHER:").take(280))
            return
        }
        if (raw == "NOLIB") {
            Log.w("IdCodeNative", "页面组件未就绪（NOLIB），不打扰用户")
            return
        }
        if (raw.startsWith("ERR:")) {
            // ★ vc526：注入取码只是兜底，失败**静默**（弹窗打扰用户且没意义）
            Log.w("IdCodeNative", "兜底取码失败：$raw")
            return
        }
        val code = raw.substringAfter("\"identityCode\":\"", "").substringBefore('"').ifEmpty { null }
        if (code.isNullOrEmpty()) {
            Log.w("IdCodeNative", "兜底取码：回包无 identityCode")
            return
        }
        if (identityCodeShown) return
        identityCodeShown = true
        showNativeIdentityCode(code)
    }

    @Volatile private var identityCodeShown = false

    /** ★ vc532：调试开关——强制走"我们自己的取码"（用于与官方页面码比对） */
    @Volatile var DEBUG_IDCODE_FORCE = false

    /** ★ vc538：自动跳转"只准跳一次" —— 否则按返回落回驿站首页会被再次踢回身份码页 */
    @Volatile private var idAutoJumpArmed = false

    /** ★ vc542：开关开着但还没有缓存码时，先记下页面，等隐形页取到码再替换 */
    @Volatile private var pendingReplaceView: android.webkit.WebView? = null

    /** ★ vc598：当前网页是不是「菜鸟身份码」入口（决定要不要做备用码替换） */
    @Volatile private var idReplaceForCainiao = false

    /** ★ vc606：当前网页层的返回键是否直接关层（身份码这类工具页） */
    @Volatile private var webBackClosesLayer = false

    /** ★ vc554：设置按钮的屏幕矩形 —— 用于"点击没落到按钮上"时兜底打开设置 */
    private val settingsBtnRect = android.graphics.Rect()

    /**
     * ★ vc536：把备用身份码**嵌进页面**（替换页面那块空白），不弹窗。
     * 白底 + 4px 模块 + 200 高（对齐官方 JsBarcode 参数），扫码枪可读。
     */
    /**
     * ★ vc550：**尽早替换** —— 页面首帧可见（onPageCommitVisible）就动手，不等整页加载完。
     * 有缓存码 ⇒ 立刻嵌进页面（1 秒内可见）；同时请隐形页取一次新码，回来再换掉。
     */
    private fun maybeReplaceIdentity(view: android.webkit.WebView, tag: String) {
        try {
            // ★ vc598：非菜鸟入口（比如淘宝身份码）一律不替换
            if (!idReplaceForCainiao) {
                Log.i("IdCodeNative", "非菜鸟身份码入口（$tag），跳过替换")
                return
            }
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            if (!prefs.getBoolean("id_backup_code", false)) return
            val cached = prefs.getString("id_code_last", "").orEmpty()
            val ts = prefs.getLong("id_code_ts", 0L)
            val fresh = cached.isNotEmpty() && System.currentTimeMillis() - ts <= 5 * 60_000L
            Log.i("IdCodeNative", "替换触发($tag) cached=${cached.take(6)}… fresh=$fresh")
            if (fresh) {
                // ★ vc551：5 分钟内直接用缓存 —— 服务端对重复取码会回
                //   `FAIL_BIZ_pickup-4002::条形码重复`（说明码仍有效），没必要每次现取。
                replaceCodeInPage(view, cached)
                return
            }
            // 没有可用缓存才去取（隐形页；已验证能成功）
            pendingReplaceView = view
            Log.i("IdCodeNative", "无可用缓存 ⇒ 隐形页取码")
            try { ensureDataWebView() } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
            if (dataPageReady && !idCodeFetching) {
                idCodeFetching = true
                dataWebView?.evaluateJavascript(IDENTITY_MSG_JS, null)
            }
        } catch (t: Throwable) {
            Log.w("IdCodeNative", "替换触发失败: ${t.message}")
        }
    }

    private fun replaceCodeInPage(view: android.webkit.WebView, code: String) {
        try {
            val bmp = PddBarcode.bitmap(code, moduleWidth = 4, height = 200, bgColor = 0xFFFFFFFF.toInt())
            val bos = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos)
            val b64 = android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
            val js = "(function(){try{" +
                    "var old=document.getElementById('fy_backup_code');if(old&&old.parentNode)old.parentNode.removeChild(old);" +
                    // 官方条码（data:image 的 img，且尺寸较大）隐藏；小图标不动
                    "var im=document.getElementsByTagName('img');" +
                    "for(var i=0;i<im.length;i++){var u=im[i].src||'';" +
                    "if(u.indexOf('data:image')===0&&im[i].clientWidth>150){" +
                    "im[i].style.visibility='hidden';im[i].style.height='0';im[i].style.overflow='hidden';}}" +
                    "var d=document.createElement('div');d.id='fy_backup_code';" +
                    // ★ 固定定位覆盖在页面中部（官方码所在区域），不参与页面布局 ⇒ 不会被压扁/挤到顶部
                    "d.style.cssText='position:fixed;left:4%;right:4%;top:26%;z-index:9999;background:#fff;" +
                    "padding:12px 8px;border-radius:12px;box-shadow:0 2px 10px rgba(0,0,0,0.08);text-align:center;';" +
                    "var i2=new Image();i2.src='data:image/png;base64," + b64 + "';" +
                    "i2.style.cssText='width:100%;max-width:520px;height:190px;object-fit:contain;display:block;margin:0 auto;';" +
                    "d.appendChild(i2);" +
                    "var t3=document.createElement('div');t3.textContent='" + code + "';" +
                    "t3.style.cssText='color:#111;font-size:16px;margin-top:8px;letter-spacing:1px;font-family:monospace;';" +
                    "d.appendChild(t3);" +
                    "var t2=document.createElement('div');t2.textContent='备用身份码';" +
                    "t2.style.cssText='color:#999;font-size:12px;margin-top:2px;';" +
                    "d.appendChild(t2);" +
                    "document.body.appendChild(d);" +
                    "return 'ok';}catch(e){return 'ex:'+e;}})()"
            view.evaluateJavascript(js) { r ->
                Log.i("IdCodeNative", "页内替换结果: $r")
                if (r == null || r == "null") Log.w("IdCodeNative", "页内替换没返回结果（JS 可能报错）")
            }
            Log.i("IdCodeNative", "已把备用码放进官方码位置（隐藏官方）code=${code.take(8)}…")
        } catch (t: Throwable) {
            Log.w("IdCodeNative", "页内替换失败: ${t.message}")
        }
    }

    /**
     * ★ vc557：**进页面自动刷新**（设置 → 其他）。
     * 默认开（保持原来行为）；关掉后进「取件码 / 在途快递」只显示缓存，等用户下拉。
     */
    private fun autoRefreshOnOpen(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("auto_refresh_on_open", true)

    @Volatile private var lastShownCode = ""
    @Volatile private var lastShownAt = 0L

    /** 原生条码浮层（Code128，驿站扫码枪可扫） */
    private fun showNativeIdentityCode(code: String) {
        // ★ vc535：防重——同码 5 分钟内不重复弹；任意弹窗 1.5 秒内不重复
        val now = System.currentTimeMillis()
        if (code == lastShownCode && now - lastShownAt < 5 * 60_000L) {
            Log.i("IdCodeNative", "同码已显示过，跳过")
            return
        }
        if (now - lastShownAt < 1500L) {
            Log.i("IdCodeNative", "弹窗冷却中，跳过")
            return
        }
        lastShownCode = code
        lastShownAt = now
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt(), (4 * dp).toInt())
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val img = android.widget.ImageView(this)
        try {
            img.setImageBitmap(PddBarcode.bitmap(code, moduleWidth = 3, height = 220))
            img.adjustViewBounds = true
        } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        box.addView(img, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(TextView(this).apply {
            text = code
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, (8 * dp).toInt(), 0, 0)
            setTextColor(cTextPrimary)
            setTextIsSelectable(true)
        })
        val dlg = AlertDialog.Builder(this)
            .setTitle("菜鸟身份码")
            .setView(box)
            .setPositiveButton("关闭") { _, _ -> }
            .create()
        try { dialogIn(dlg) } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        dlg.show()
        Log.i("IdCodeNative", "已用原生条码显示身份码 len=${code.length}")
    }

    /** ★ vc533：隐形数据页调 getIdentityMessage（页面 JS 包里真正的取码接口，无业务参数） */
    private val IDENTITY_MSG_JS = ("(function(){try{" +
            "if(!(window.lib&&window.lib.mtop)){window.__idcode.onCode('NOLIB');return;}" +
            "window.lib.mtop.request({api:'mtop.cainiao.pickup.identity.getIdentityMessage',v:'1.0'," +
            "type:'GET',dataType:'json',data:{}}).then(function(r){" +
            "window.__idcode.onIdentity(JSON.stringify(r));},function(e){" +
            "window.__idcode.onIdentity('ERR:'+JSON.stringify((e&&e.ret)||''));});" +
            "}catch(e){window.__idcode.onIdentity('EX:'+e);}})()")

    /**
     * ★ vc549：**在身份码页自己身上调取码接口**（页面已有 lib.mtop，免去等隐形页）。
     * 之前慢 5 秒就是因为要等隐形页先加载首页（2~4 秒）再发请求。
     */
    private val IDENTITY_RPC_JS = ("(function(){try{" +
            "if(!(window.lib&&window.lib.mtop)){window.__idcode.onCode('NOLIB');return;}" +
            "window.lib.mtop.request({api:'mtop.cainiao.pickup.identity.getIdentityMessage',v:'1.0'," +
            "type:'GET',dataType:'json',data:{}}).then(function(r){" +
            "window.__idcode.onCode(JSON.stringify(r));},function(e){" +
            "window.__idcode.onCode('ERR');});}catch(e){window.__idcode.onCode('EX');}})()")

    /** 隐形页用的回调桥（与 DataBridge 并列，避免混进包裹解析） */
    private inner class IdentityBridge {
        @android.webkit.JavascriptInterface
        fun onIdentity(json: String?) {
            mainHandler.post {
                val raw = json ?: ""
                Log.i("IdCodeNative", "隐形页取码返回: " + raw.take(400))
                val c = raw.substringAfter("\"identityCode\":\"", "").substringBefore('"')
                if (c.isNotEmpty()) {
                    idCodeTs = System.currentTimeMillis()
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("id_code_last", c).putLong("id_code_ts", idCodeTs).apply()
                    idCodeFetching = false
                    Log.i("IdCodeNative", "身份码已缓存 code=${c.take(8)}…")
                    // ★ vc542：有页面在等 ⇒ 直接替换进页面（不弹窗）
                    val pv = pendingReplaceView
                    if (pv != null) {
                        pendingReplaceView = null
                        replaceCodeInPage(pv, c)
                    }
                } else {
                    idCodeFetching = false
                    Log.w("IdCodeNative", "隐形页取码无 identityCode")
                }
            }
        }
    }

    @Volatile private var idCodeTs: Long = 0L
    @Volatile private var idCodeFetching = false
    @Volatile private var idCodeRequestedAt = 0L

    /** ★ vc533：点「菜鸟身份码」——缓存新鲜直接原生显示，否则开网页兜底 */
    private fun openCainiaoIdentityFast() {
        idCodeRequestedAt = System.currentTimeMillis()
        val c = getSharedPreferences(PREFS, MODE_PRIVATE).getString("id_code_last", "").orEmpty()
        val ts = getSharedPreferences(PREFS, MODE_PRIVATE).getLong("id_code_ts", 0L)
        if (c.isNotEmpty() && System.currentTimeMillis() - ts <= 5 * 60_000L) {
            showNativeIdentityCode(c)
            // 后台顺带刷新一份新的
            if (dataWebView != null && dataPageReady && !idCodeFetching) {
                idCodeFetching = true
                dataWebView?.evaluateJavascript(IDENTITY_MSG_JS, null)
            }
            return
        }
        openWeb(TAOBAO_URL, getString(R.string.web_title_cainiao), cainiaoIdentity = true)
    }

    private fun openWeb(
        url: String, title: String, accent: String = "#3CA1DD", loginMode: String? = null,
        // vc270：淘宝在途旁听借用本层 —— 近乎全透明 + 上盖触摸隔离层，用户无感
        alphaHidden: Boolean = false,
        /**
         * ★ vc606：返回键是否**直接关掉这一层**（不走网页后退）。
         * 拼多多身份码页打开后会自己在 WebView 里跳 2~3 层（订单列表 → 身份码），
         * 以前返回是"网页后退"，于是要按三次才回首页，中间还会露出订单列表（同志实测）。
         * 身份码这类"工具页"不是浏览器，用户不指望里面后退 ⇒ 一次返回就整层退出。
         */
        backClosesLayer: Boolean = false,
        /**
         * ★ vc598：**只有从「菜鸟身份码」入口进来的页面**才允许做"备用身份码替换"。
         * 淘宝身份码和菜鸟身份码用的是同一个 URL（pages-fast …/identity-code），
         * 以前只按 URL 判断 ⇒ 开了替换后**淘宝身份码页也被换掉**（同志实测报的 bug）。
         */
        cainiaoIdentity: Boolean = false
    ) {
        if (webLayer != null) return
        idReplaceForCainiao = cainiaoIdentity
        webBackClosesLayer = backClosesLayer
        // ★ vc399：任何网页打开前先对齐账号范围 —— 保证"换号清旧数据"不依赖是否进过取件页
        try { enforceAccountScopes() } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // ★ vc264r：默认（PDD_KEEP_IDENTITY=true）**不再换设备标识、不清 cookie** ——
        //   让拼多多认为"还是上次那台设备"，避免每次登录都顶号（详见该常量注释）
        if (loginMode == "pdd" && !PDD_KEEP_IDENTITY) {
            // ★ 换设备标识（登录前做，这是关键时机）。
            // 拼多多服务端把"同设备重复登录"当成同一个会话 ⇒ 会顶掉用户手机上的拼多多 App。
            // H5 层面唯一能做的抵抗，就是让这次登录看起来像「另一台设备」：
            //   ① 清拼多多域 cookie（② 见 createWebView：换 UA；③ 清 WebStorage）
            // ⚠️ 绝不能像参考项目那样 removeAllCookies() —— 那会把第 1/3 栏的淘宝登录态一起清掉。
            PddChannel.clearCookies()
            try {
                android.webkit.WebStorage.getInstance().deleteAllData()
            } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        }

        val layer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
        }
        // ★ Linear 统一顶栏（改造 Step 5）：跟主页/设置页/途中栏同一套 ——
        //   中性面板色（不用平台色刷整条），平台辨识度交给标题前的小色点。
        //   WebView 内部（第三方页面）一个像素都不动。
        val top = makeTopBar(title, accent = accent)
        // 右侧按钮：
        //   · mdkd（第 2 栏）：刷新；**未登录时**额外给「登录」——
        //     已登录时不再显示那个按钮（那时它是「在 App 中打开」，2026-09-22 同志要求去掉）。
        //   · 其它登录页（小米/京东等）：刷新 + 「完成」（自动轮询没抓到时手动兜底）。
        //   · 其余（第 1/3 栏的身份码日常页）：**只留刷新** ——
        //     原来的「清除登录」按钮已按同志要求删除。
        if (loginMode == "mdkd") {
            // ★ vc264n（同志 04:16 要求）：第 2 栏的右上角刷新键已删（唯一手动入口不再需要）
            // ★ 只有**未登录**时才给登录入口 —— 删掉它用户就没法登录拼多多了
            if (!PddChannel.isLoggedIn(this)) {
                top.addView(topBarBtn(getString(R.string.pdd_login_only)) { mdkdTopAction() },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, (52 * dp).toInt()
                    ).apply { setMargins(0, 0, (4 * dp).toInt(), 0) })
            }
        } else if (loginMode != null) {
            // 登录页（小米/京东等，非 1-5 栏）：**保留刷新** ——
            // 登录过程中看页面状态需要它；同志要删的是"1-5 栏"，不是这里
            top.addView(refreshBtn { webView?.reload() },
                LinearLayout.LayoutParams((48 * dp).toInt(), (52 * dp).toInt()))
            top.addView(topBarBtn(getString(R.string.pdd_done)) { finishLogin(loginMode) },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, (52 * dp).toInt()
                ).apply { setMargins(0, 0, (4 * dp).toInt(), 0) })
        }
        // ★ vc264n：第 1/3 栏（淘宝/菜鸟身份码）的右上角刷新键已删
        //   —— 那两栏是"打开即加载"的日常页，重新进页面即刷新
        attachTopBar(layer, top)

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        layer.addView(progress, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (4 * dp).toInt()
        ))

        // ★ vc284c：原「拼多多登录页专属说明卡」整段删除（同志要求）——
        //   其内容（扫码/手机号差异）已由登录须知强制弹窗承担。

        val pull = PullContainer(this)
        val web = createWebView(loginMode)
        // ★ vc538：每次从入口打开身份码页，允许自动跳转一次（返回后不再跳）
        if (url.contains("identity-code")) idAutoJumpArmed = true
        // ★ vc525：身份码页需要一条回调通道（原生画条码用）
        if (url.contains("identity-code")) {
            try {
                web.addJavascriptInterface(IdCodeBridge(), "__idcode")
                identityCodeShown = false
                Log.i("IdCodeNative", "已注册 __idcode 桥")
            } catch (t: Throwable) {
                Log.w("IdCodeNative", "注册桥失败: ${t.message}")
            }
        }
        pull.addView(web, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        layer.addView(pull, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        root.addView(layer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        webLayer = layer
        webView = web
        progressBar = progress
        // vc270：隐形旁听模式 —— 页面要"真可见"才肯渲染（1x1/垫底实测都不行），
        // 所以整层压到 0.1% 透明度（vc272：同志 2%→0%→0.1% —— 留一丝非零，
        // 绕开个别手机 GPU「全透明层直接跳过」的理论风险；千分之一肉眼绝对不可见），
        // 再盖一层触摸隔离，用户点了也不穿透到页面（绝不能让隐形页面偷走用户的操作）。
        if (alphaHidden) {
            layer.alpha = 0.001f
            val eater = View(this)
            eater.isClickable = true
            eater.setOnTouchListener { _, _ -> true }
            root.addView(eater, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
            tbTouchEater = eater
        }
        // 页面进入动效：跟途中栏/取件码页/设置页同一套（Step 6 统一）
        // ★ vc270b：隐形旁听模式**跳过进页动画** —— layerIn 会把 alpha 动画回 1，
        //   把上面的 0.1% 覆盖掉（21:31 同志实测：刷新时淘宝页面肉眼可见地"打开"了）
        if (!alphaHidden) layerIn(layer)
        // ★ vc270e：隐形旁听页 document-start 挂钩子 —— 订单接口的**响应原文**比页面渲染
        //   早 ~10s 到，抄到就能出卡，不用等页面画完（响应里还带接口名/参数，给直连留路）
        if (alphaHidden) {
            try {
                WebHook.attachDocumentStart(
                    web, WebHook.hookJs("/h5\\/mtop\\.|h5api\\.m\\.taobao\\.com/i"),
                    setOf("https://h5.m.taobao.com", "https://pages-g.m.taobao.com")
                )
            } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        }
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)
        if (loginMode == "mdkd") {
            // 身份码页在 m.pinduoduo.net，登录种在 yangkeduo —— 跨顶级域必须靠原生补种
            // （injectCookies 里已含 .pinduoduo.net 的显式 domain 补种）。
            PddChannel.injectCookies(PddChannel.cookies(this))
            PddMdkd.attachDebug(this)
            PddMdkd.resetDebug()
        }
        var tbGatedLoad = false
        if (loginMode == "tb") {
            // ★ vc404：换号 = 只清淘宝/菜鸟 cookie（精确到 Domain），拼多多/京东/小米不受影响；
            //   清完**再**加载登录页，避免登录页带着旧 cookie 自动登回上一个账号。
            tbGatedLoad = true
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("tb_prev_uid", TbChannel.uid()).apply()
            val names = clearTaobaoCookiesExact()
            try { web.clearCache(true) } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
            Log.i("TbLogin", "已清淘宝/菜鸟 cookie（$names 个名字），准备加载登录页")
            mainHandler.postDelayed({
                if (webView === web) {
                    Log.i("TbLogin", "登录页开始加载（uid 现在是 ${TbChannel.uid()}）")
                    web.loadUrl(url)
                }
            }, 500)
        }
        // ★ vc376：身份码页不许吃缓存 —— 换号后旧页面会带着上一个账号的手机号/空壳。
        //   非破坏性做法：① 加时间戳绕开 HTTP/CDN 缓存；② 只清这个页面自己的
        //   localStorage/sessionStorage（不动 cookie，不影响任何登录），然后重载一次。
        var identityUrl = url
        if (url.contains("identity-code")) {
            try {
                web.clearCache(true)
                web.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                // ★ vc389：**不要**再冒充淘宝 App UA / 注入假桥。
                //   成功日志（v1.0.7）用的是 WebView 默认 UA，同一个页面能正常出码；
                //   冒充 App UA 后页面会去等原生桥（utdid），在 WebView 里永远等不到 ⇒ 空壳。
            } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
            identityUrl = url + (if (url.contains("?")) "&" else "?") + "_t=" + System.currentTimeMillis()
            try {
                val pkg = WebView.getCurrentWebViewPackage()
                Log.i(
                    "IdDiag",
                    "环境 WebView=${pkg?.packageName}:${pkg?.versionName} " +
                            "UA=${web.settings.userAgentString?.take(120)}"
                )
            } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        }
        // ★ vc476：身份码页的 identityCode 接口由页面自己发（参数/签名都对），
        //   我们**不猜参数**，只旁听响应（document-start 注入，必须早于 loadUrl）。
        // ★ vc497：淘宝请求记录器（调试用）—— 录下页面调用的 api + data 原文
        if (DEBUG_TB_REC) {
            // ★ vc498：换成**旁听网络请求**（WebHook 的钩子抓 XHR/fetch 的完整 URL），
            //   因为页面不一定走 window.lib.mtop（模块内部实例），包不住。
            val rec = WebHook.hookJs("/h5api\\.m\\.taobao\\.com|\\/h5\\/mtop/i")
            val okRec = WebHook.attachDocumentStart(web, rec, setOf("*"))
            if (!okRec) WebHook.attachFallback(web, rec)
            Log.i("TbRec", "网络旁听注入=$okRec url=${url.take(80)}")
        }
        if (tbGatedLoad) {
            // 登录页由上面的 removeAllCookies 回调负责加载，这里不重复
        } else if (url.contains("identity-code") && url.contains("/wow/z/")) {
            // ★ vc389：回到 v1.0.7 的成功做法 —— 直接开身份码页（默认 UA），
            //   并在前 15 秒内再补两次加载（成功日志里正是连续 3 次加载后出码）。
            // 只保留历史路径：让淘宝页面自己渲染（绝大多数账号靠它出码）
            Log.i("IdDiag", "身份码：页面自己渲染")
            identityLayerActive = true
            web.loadUrl(identityUrl)
            // 注：清 localStorage 对"显示上一个账号"实测无效（2026-10-24），
            //     有效手段只有"退出登录（换 cookie）→ 重新登录"，见 openWeb 的 tb 分支。
        } else {
            // ★ vc385：小程序内部的 identity-code 页直接开会 404（err.tmall.com），
            //   必须带"从菜鸟驿站首页点进来"的来源头，服务端才认。
            if (url.contains("identity-code")) {
                val hdr = HashMap<String, String>()
                hdr["Referer"] = HOME_URL
                web.loadUrl(identityUrl, hdr)
                Log.i("IdDiag", "直开身份码（带 Referer=$HOME_URL）")
            } else {
                web.loadUrl(identityUrl)
            }
        }
        // ★ vc360：身份码页诊断 —— 打开后 4s 起、每 6s 探一次（最多 5 次），
        //   把页面真实状态记进日志；层关掉/换页后自动停止。
        if (url.contains("identity-code")) {
            Log.i("IdDiag", "打开身份码页（4s 后开始诊断，共 5 次）")
            // ★ vc373：把本 App WebView 里的淘宝凭证"名字"打出来（不泄漏值），
            //   用来判断是不是"淘宝 App 是 A 号、App 内 WebView 还是 B 号/半个登录态"。
            try {
                val cm2 = CookieManager.getInstance()
                val ck1 = cm2.getCookie("https://pages-fast.m.taobao.com").orEmpty()
                val ck2 = cm2.getCookie("https://.taobao.com").orEmpty()
                val all = ck1 + ";" + ck2
                val names = all.split(';').map { it.substringBefore('=').trim() }
                    .filter { it.isNotEmpty() }.distinct()
                fun tail(k: String): String {
                    val v = Regex("(?:^|;)\\s*" + k + "=([^;]+)").find(all)?.groupValues?.get(1) ?: return "无"
                    return if (v.length <= 4) "***" else "***" + v.takeLast(4)
                }
                fun nick(): String {
                    val raw = Regex("(?:^|;)\\s*(?:tracknick|_w_tb_nick)=([^;]+)").find(all)
                        ?.groupValues?.get(1).orEmpty()
                    return try { java.net.URLDecoder.decode(raw, "UTF-8") } catch (_: Throwable) { raw }
                }
                Log.i("IdDiag", "淘宝会员名 ${nick()}")
                Log.i(
                    "IdDiag",
                    "淘宝凭证 名=${names.joinToString(",")} unb=${tail("unb")} " +
                            "lgc=${tail("lgc")} cookie2=${if (names.contains("cookie2")) "有" else "无"} " +
                            "_nk_=${if (names.contains("_nk_")) "有" else "无"}"
                )
            } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
            var diagTries = 0
            var bootstrapped = false
            var apiTried = false
            var hintView: TextView? = null
            var retryCount = 0
            fun setHint(msg: String) {
                if (hintView == null) {
                    hintView = TextView(this@MainActivity).apply {
                        textSize = 13f
                        setTextColor(cTextSecondary)
                        setPadding(
                            (16 * dp).toInt(), (10 * dp).toInt(),
                            (16 * dp).toInt(), (10 * dp).toInt()
                        )
                        setBackgroundColor(
                            if (isDark()) Color.parseColor("#1C1D1F")
                            else Color.parseColor("#FFF6E5")
                        )
                    }
                    try {
                        layer.addView(hintView, 2)
                    } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
                }
                hintView?.text = msg
            }
            val diag = object : Runnable {
                override fun run() {
                    diagTries++
                    val w = webView
                    if (w == null || w !== web) return   // 层已关/已换页 ⇒ 停
                    try {
                        w.evaluateJavascript(IDENTITY_DIAG_JS) { res ->
                            val s = res?.trim()?.trim('"').orEmpty().replace("\\\"", "\"")
                            Log.i("IdDiag", "身份码页#$diagTries $s")
                            val m = Regex("\"m\":(\\d+)").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: 9999
                            val txt = Regex("\"txt\":\"([^\"]*)\"").find(s)?.groupValues?.get(1).orEmpty()
                            val cv = Regex("\"cv\":(\\d+)").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            val ci = Regex("\"ci\":(\\d+)").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            val cw = Regex("\"cw\":(\\d+)").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            // 有码判据只认"真画出来的东西"：canvas 或 data:image 图片。
                            // 不能用 class 含 code 的元素数（页面骨架本身就有这种 class）。
                            val hasCode = cv > 0 || ci > 0
                            val needLogin = txt.contains("需要登录") || txt.contains("请登录")
                            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
                            val loginAt = try {
                                prefs.getLong("tb_login_at", 0L)
                            } catch (_: ClassCastException) {
                                // 旧版本退出逻辑曾把时间戳写成空字符串；兼容旧数据并修正类型。
                                val legacy = prefs.getString("tb_login_at", null)
                                    ?.toLongOrNull() ?: 0L
                                prefs.edit().remove("tb_login_at").apply()
                                legacy
                            }
                            val mins = if (loginAt > 0L)
                                ((System.currentTimeMillis() - loginAt) / 60_000L).toInt() else -1
                            // 没记到登录时刻但确实已登录 ⇒ 以本次为起点估算（宁可按"刚登录"对待）
                            if (loginAt == 0L && !needLogin) {
                                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                    .putLong("tb_login_at", System.currentTimeMillis()).apply()
                            }
                            Log.i(
                                "IdDiag",
                                "判定：有码=$hasCode(画布$cv/图$ci/元素$cw) 需登录=$needLogin 登录于${mins}分钟前"
                            )
                            // ★ vc377：淘宝身份码刚登录约 30 分钟才生成 —— 按"登录时长"给不同提示，
                            //   等待期内每 5 分钟自动重载一次，别让用户傻等或以为坏了。
                            when {
                                needLogin -> setHint("淘宝未登录：请在「设置 → 淘宝 / 菜鸟」登录")
                                hasCode -> hintView?.visibility = View.GONE
                                diagTries >= 4 ->
                                    setHint("该淘宝账号暂无身份码，可在淘宝 App 的「菜鸟驿站 → 身份码」内查看")
                                else -> setHint("正在读取身份码…")
                            }
                        }
                    } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
                    if (diagTries < 5) mainHandler.postDelayed(this, 6000)
                }
            }
            mainHandler.postDelayed(diag, 4000)
        }
        when (loginMode) {
            "pdd" -> startPddLoginPoll()
            "jd" -> startJdLoginPoll()
            "tb" -> startTbLoginPoll()
            "mi" -> startMiLoginPoll()
        }
    }

    /**
     * 登录模式下**必须留在 WebView 里跑完**的域。
     *
     * ★ 这一条踩过大坑：小米登录流程里"点登录 → 跳 STS"是**跨域跳转**，
     *   当时白名单只有淘宝/拼多多系，于是 STS 被 `openExternal` 扔进系统浏览器，
     *   App 内的页面停在原地、令牌永远写不进 jar（同志实测：浏览器里看到 `{"R":"","S":"OK"}`）。
     */
    private fun ownHosts(loginMode: String?): Set<String> = when (loginMode) {
        // miui.com 覆盖 api.assistant.miui.com（STS 令牌就在这个域上）
        "mi" -> setOf("xiaomi.com", "xiaomi.com.cn", "miui.com", "mi.com", "xiaomi.net", "mioffice.com")
        else -> setOf("taobao", "tbopen")
    }

    /**
     * ★ vc317：顺丰官网「rem 断点缝隙」补丁。
     *
     * 顺丰首页/登录页的字号方案：body { font-size:.16rem }，根字号（1rem）由 CSS 断点表给出：
     *   ≤375px→75px｜376~499→80px｜500~768→90px｜769~899→66px｜900~1023→71px｜1024~1229→83px｜≥1230→100px
     * **375~376 之间是缝隙**（两档都取不到）：像 K70 Ultra（1220px ÷ 3.25 = 375.38）这类
     * 除不尽的机型，html 退回浏览器默认 16px，body 只有 2.56px（Chrome 字型自动放大后约 8px），
     * 整页缩小成"微生物" ⇒ 用户看到的就是"顺丰登录界面 UI 非常拥挤"。
     * 真机取证（2026-10-18，QWZ98LNFUKAUTW6X）：系统浏览器 bodyFs=2.56px；App WebView=8px；
     * 经 DevTools 注入 75px 后 bodyFs=12px、页面恢复全宽正常手机版（同志目测确认）。
     * 做法：按顺丰自家断点表给 html 补设根字号（幂等），onPageStarted + onPageFinished 各注入一次。
     */
    private fun createWebView(loginMode: String? = null): WebView {
        // ★ vc518：优先复用池里的实例（见 webPool 注释）；池空才新建
        val web = webPool.removeLastOrNull() ?: WebView(this)
        if (defaultUa == null) defaultUa = web.settings.userAgentString
        try {
            web.webViewClient = android.webkit.WebViewClient()
            web.webChromeClient = null
        } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        web.settings.apply {
            // 复用实例：先把 UA 复位，再按 loginMode 覆盖
            defaultUa?.let { userAgentString = it }
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            // ★ vc387：小程序子页靠 window.open 打开，必须放行（由 onCreateWindow 接住）
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            // 拼多多登录：★ vc266 走【电脑端通道】——桌面 Chrome 身份打开登录页，
            //   页面原生提供「扫码登录 + 手机登录」两种方式；扫码 = 电脑端会话，实测不顶掉拼多多 App。
            if (loginMode == "pdd") userAgentString = PDD_LOGIN_UA
            // mdkd 身份码页：冒充拼多多原生容器。
            // ★ 页面靠 UA 里的 `phh_android_version` 判定"我在 App 容器里"（mdkd bundle 的 5551 模块）。
            //   base 也要统一：以前是 WebView 默认（真机机型 + "; wv"），与登录身份不一致
            if (loginMode == "mdkd") userAgentString = PddMdkd.nativeUa(PddChannel.BROWSER_UA)
            // 小米：必须和 MiChannel 换票请求用同一个 UA，否则服务端判"登录验证失败"
            if (loginMode == "mi") userAgentString = MiChannel.UA
            // ★ vc443：抖音手机版只给「下载 APP」，桌面版才有扫码登录 ⇒ 用桌面 UA
            if (loginMode == "dy") userAgentString = DESKTOP_UA
            // 调试总开关：最后生效，覆盖上面所有 UA（真机调研「电脑端能不能走通拼多多」用）
            if (DEBUG_DESKTOP_UA) userAgentString = DESKTOP_UA
            DEBUG_CONTAINER_UA?.let { userAgentString = it }
        }
        web.setBackgroundColor(cBg)
        // mdkd：装上原生桥替身 + document-start 注入。
        // ★ document-start 是成败关键：mdkd 的桥模块在初始化那一刻就定死了
        //   `window._fastJsN ? 同步桥 : iframe桥` 这条分支，注入晚了它就走 iframe/alert 老路。
        var mdkdDocStart = false
        if (loginMode == "mdkd") {
            try {
                web.addJavascriptInterface(
                    PddMdkd.Bridge(this, web) { },
                    PddMdkd.BRIDGE
                )
            } catch (e: Throwable) {
                Log.w("PddMdkd", "addJavascriptInterface failed: $e")
            }
            // ★ vc270j：驿站包裹探针 —— 在 mdkd 页上叠加旁听钩子，抓 express_trackbox 家族接口
            val probeJs = if (mdkdPkgProbeActive) {
                PddMdkd.bridgeJs() + "\n" +
                        WebHook.hookJs("/pinduoduo/i")
            } else PddMdkd.bridgeJs()
            mdkdDocStart = try {
                WebHook.attachDocumentStart(web, probeJs, setOf(PddMdkd.ORIGIN))
            } catch (_: Throwable) {
                false
            }
        }
        // EMS：它的登录态**不在 cookie 里，在 localStorage**（phone/userId/H5token），
        // 所以拿不到 jar 就完事 —— 必须开个轮询从页面里读出来回存 SP。
        // ★ 必须允许第三方 cookie：小米的 STS 跳转会跨域把 serviceToken 写进 jar
        //   （域 api.assistant.miui.com）。不开的话令牌进不来，只能走那条会被拒的换票路。
        try {
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                val scheme = request.url.scheme ?: ""
                // ★ 平台自己的 App deep link 一律在 WebView 里拦掉（理由见 ownAppSchemes 注释）。
                //   不拦的话，页面发的 xxx:// 会被扔给系统浏览器 → 绕一圈再回来，链路又长又乱。
                if (scheme in this@MainActivity.ownAppSchemes(loginMode)) {
                    return true
                }
                if (scheme == "http" || scheme == "https") {
                    val host = request.url.host ?: return false
                    if (this@MainActivity.inAppHost(host, loginMode)) return false
                    return openExternal(url)
                }
                return openExternal(url)
            }

            // ★★ 诊断三件套（2026-09-22 为 ump 白屏加）：
            //   SPA 白屏最常见的原因就是**某个子资源加载失败**（JS/CSS 没到 ⇒ Vue 挂不上），
            //   而页面自己往往一声不吭 —— 不在这里抓就只能靠猜。
            override fun onReceivedHttpError(
                view: WebView, request: android.webkit.WebResourceRequest,
                errorResponse: android.webkit.WebResourceResponse
            ) {
                // 主文档的错误 onReceivedError 也会报一次，这里只看子资源，免得刷屏
                if (!request.isForMainFrame) {
                    Log.w("WebDiag", "HTTP ${errorResponse.statusCode}  ${request.url}")
                }
            }

            override fun onReceivedError(
                view: WebView, request: android.webkit.WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                Log.w("WebDiag", "ERR(${error.errorCode}) ${error.description}  ${request.url}")
            }

            override fun onReceivedSslError(
                view: WebView, handler: android.webkit.SslErrorHandler,
                error: android.net.http.SslError
            ) {
                Log.w("WebDiag", "SSL(${error.primaryError}) ${error.url}")
            }

            // ★★ 拦掉「host 不是合法域名」的子资源（2026-09-22 为 ump 白屏加）。
            //
            //   ump 登录页的 HTML 里有一条静态脚本：
            //   （支付宝小程序 web-view SDK，注释写此链接仅在支付宝端内访问有效）。
            //   它的 host 是  —— **没有顶级域，DNS 必然失败**。
            //
            //   要命的是它排在真正的  **前面**，而且页面用
            //    写脚本（那是**阻塞解析**的）⇒ 它一卡住，
            //   后面的 app.js / chunk-vendors.js 就永远执行不到 ⇒
            //   表现为「页面 load 完成、没有任何报错、但 #app 是空的（appLen=70）」。
            //
            //   判据刻意取得很窄（只认"host 里没有点"），正常域名一个都碰不到。
            override fun shouldInterceptRequest(
                view: WebView, request: android.webkit.WebResourceRequest
            ): android.webkit.WebResourceResponse? {
                val host = request.url?.host
                val reqUrl = request.url?.toString().orEmpty()
                val reqHost = host.orEmpty()
                // ★ vc386：身份码页请求快照 —— 看小程序到底调哪个接口取码
                if (reqHost.contains("h5api") || reqHost.contains("mtop") ||
                    (reqUrl.contains("pages-fast") && reqUrl.contains("mtop"))
                ) {
                    // ★ vc401：身份码相关接口记完整 URL（含 data 参数），其余只记前 240 字
                    val full = reqUrl.contains("getIdentityMessage", true) ||
                            reqUrl.contains("pushRealNameCode", true)
                    Log.i("IdNet", "接口 $reqHost ${if (full) reqUrl.take(900) else reqUrl.take(240)}")
                }
                // ★ vc388：身份码相关接口的**返回体**读出来（自建连接复放同一请求），
                //   看服务端到底回了什么（成功 / 无权限 / 需要实名 …）。
                if (reqHost.contains("h5api") &&
                    (reqUrl.contains("cainiao") || reqUrl.contains("identity") ||
                            reqUrl.contains("realname"))
                ) {
                    try {
                        val conn = java.net.URL(reqUrl).openConnection() as java.net.HttpURLConnection
                        conn.requestMethod = "GET"
                        for ((k, v) in request.requestHeaders) {
                            try { conn.setRequestProperty(k, v) } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
                        }
                        val ck = CookieManager.getInstance().getCookie(reqUrl).orEmpty()
                        if (ck.isNotEmpty()) conn.setRequestProperty("Cookie", ck)
                        conn.connectTimeout = 8000
                        conn.readTimeout = 8000
                        val code2 = conn.responseCode
                        val body = try {
                            conn.inputStream.readBytes().toString(Charsets.UTF_8)
                        } catch (_: Throwable) {
                            conn.errorStream?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
                        }
                        Log.i("IdApi", "返回 $code2 ${reqUrl.substringAfter("api=").take(60)} → ${body.take(900)}")
                        return android.webkit.WebResourceResponse(
                            "application/json", "UTF-8",
                            java.io.ByteArrayInputStream(body.toByteArray())
                        )
                    } catch (t: Throwable) {
                        Log.w("IdApi", "复放失败：${t}".take(200))
                    }
                }
                if (!host.isNullOrEmpty() && !host.contains(".") && !request.isForMainFrame) {
                    Log.w("WebDiag", "拦截无效域子资源: $host")
                    return android.webkit.WebResourceResponse(
                        "application/javascript", "UTF-8",
                        java.io.ByteArrayInputStream(ByteArray(0))
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                // 退化路径：老 WebView 不支持 document-start 时补注入（时机偏晚，但聊胜于无）
                if (loginMode == "mdkd" && !mdkdDocStart) {
                    WebHook.attachFallback(view, PddMdkd.bridgeJs())
                }
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                // ★ vc550：首帧可见就替换（比 onPageFinished 早 1~3 秒）
                if (url.contains("identity-code")) maybeReplaceIdentity(view, "commit")
            }

            override fun onPageFinished(view: WebView, url: String) {
                // ★ vc264o 诊断：所有页面都把真实 URL 记进日志
                //   （排查"页面空白 / 跳到了别的页 / 要换入口链接"时，靠它拿地址）
                Log.i("WebUrl", "onPageFinished: $url")
                // ★ vc525：身份码页加载完 1.5s，主动在页面里调接口取码（页面自己不画码）
                if (url.contains("identity-code")) {
                    // ★ vc538：进入身份码页后清掉历史（跳转/重定向都算），返回一次即退出
                    try { view.clearHistory() } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
                    mainHandler.postDelayed({
                        try {
                            // ★ vc526：先看页面**有没有画码**（canvas / data:image）。
                            //   有码就别管（正常手机走这条）；没码才尝试兜底取码。
                            // ★★ vc546：**开关开 = 直接替换**（不等官方）。
                            //   同志定的口径：开启「菜鸟身份码替换」后，官方码本来就可能加载不出来，
                            //   所以不等它 —— 页面一就绪就把我们的码放进官方码的位置，并先把官方条码隐藏，
                            //   避免两张码叠在一起。开关关 = 完全不干预。
                            val replaceOnNow = getSharedPreferences(PREFS, MODE_PRIVATE)
                                .getBoolean("id_backup_code", false)
                            fun doReplace() {
                                maybeReplaceIdentity(view, "finish")
                            }
                            fun probeOnce(attempt: Int) {
                                Log.i("IdCodeNative", "替换开关=$replaceOnNow（第${attempt + 1}次处理）")
                                if (replaceOnNow) {
                                    doReplace()
                                    // SPA 刷新会重画官方码 ⇒ 3 秒后再覆盖一次（幂等）
                                    if (attempt == 0) mainHandler.postDelayed({ doReplace() }, 3000)
                                }
                            }
                            probeOnce(0)
                        } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
                    }, 2500)
                }
                // （vc616：顺丰登录已移除，此补丁随之不再需要）
                //   但整页跳转后 HTML 是新的，这里再补一次（幂等，重复设同值无害）
                progressBar?.visibility = View.GONE
        try { UiTrace.page(this@MainActivity, "web", url) } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
                // 小米登录：登录页 URL 常带 `_user=1xxxxxxxxxx`（手机号登录必带）—— 白捡一个手机号
                if (loginMode == "mi") {
                    val ph = MiChannel.phoneFromUrl(url)
                    if (ph.isNotEmpty()) MiChannel.savePhone(this@MainActivity, ph)
                }
                // 身份码页（mdkd 域）加载完记一次诊断：各域 cookie + 页面 URL/标题/document.cookie。
                // 「登录了却仍提示未登录」时靠它判断是域没对上，还是页面要求 App 容器注入的 token。
                // ★ vc423：开了「淘宝身份码跳转」且当前落在菜鸟驿站首页 ⇒ 自动跳回身份码页
                if (identityLayerActive &&
                    idAutoJumpArmed &&
                    getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("tb_id_autojump", false) &&
                    url.contains("home-ssr-index")
                ) {
                    Log.i("IdJump", "落在驿站首页，自动跳转身份码页（本次只跳一次）")
                    idAutoJumpArmed = false          // ★ vc538：机会用掉，返回时不再跳
                    view.loadUrl(TAOBAO_URL)
                    // ★ vc537/538：跳转=替换语义。立刻清一次，页面完成后再清一次，
                    //   保证"按一次返回就退出"，不经过驿站首页白页。
                    try { view.clearHistory() } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
                    view.postDelayed({
                        try { view.clearHistory() } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
                    }, 500)
                    return
                }
                // ★ vc473：菜鸟身份码页 —— 页面自己那个「授权申请」弹窗在 WebView 里
                //   会点了不关（它等原生桥回调），但 mtop 接口其实已经成功。
                //   这里绕开弹窗：直接调接口拿 identityCode，自己画条码给用户看。
                if (url.contains("mdkd")) {
                    view.evaluateJavascript(
                        "(function(){return JSON.stringify({u:location.href,t:document.title," +
                            "c:(document.cookie||'').slice(0,900)});})()"
                    ) { v -> PddChannel.diagnose(this@MainActivity, WebHook.unquoteJsString(v)) }
                }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            // ★ vc387：小程序用 window.open 打开子页（身份码/出库码）。
            //   给新窗口一个**临时 WebView** 接住，再把地址转回主 WebView ——
            //   不能把 transport 指回自己（vc384 那样会递归崩溃）。
            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean,
                resultMsg: android.os.Message
            ): Boolean {
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                val tmp = WebView(this@MainActivity)
                tmp.settings.javaScriptEnabled = true
                tmp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView, req: android.webkit.WebResourceRequest
                    ): Boolean {
                        val u = req.url.toString()
                        Log.i("IdDiag", "新窗口 → $u")
                        if (u.startsWith("http")) view.loadUrl(u)
                        tmp.destroy()
                        return true
                    }
                }
                transport.webView = tmp
                resultMsg.sendToTarget()
                Log.i("IdDiag", "onCreateWindow：已用临时 WebView 接住新窗口")
                return true
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar?.apply {
                    progress = newProgress
                    visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                }
            }

            override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                // mdkd 调试：页面 console 全部落盘，"身份码没出来"时靠它定位是哪一步挂了
                if (loginMode == "mdkd" && message != null) {
                    PddMdkd.debugLog(
                        "JS    ${message.messageLevel()} ${message.message().take(300)}" +
                            " @${message.sourceId()?.take(90)}:${message.lineNumber()}"
                    )
                }
                // ★ vc390：身份码页的 JS 报错也要看 —— 页面只画骨架，多半是脚本抛异常
                if (loginMode == "身份码" && message != null) {
                    val lvl = message.messageLevel()
                    if (lvl == android.webkit.ConsoleMessage.MessageLevel.ERROR ||
                        lvl == android.webkit.ConsoleMessage.MessageLevel.WARNING
                    ) {
                        Log.w(
                            "IdJs",
                            "$lvl ${message.message().take(240)} " +
                                    "@${message.sourceId()?.takeLast(60)}:${message.lineNumber()}"
                        )
                    }
                }
                return super.onConsoleMessage(message)
            }

            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    startActivityForResult(params.createIntent(), REQ_FILE_CHOOSER)
                    true
                } catch (_: ActivityNotFoundException) {
                    filePathCallback = null
                    false
                }
            }
        }
        return web
    }

    /** 站外链接交给系统浏览器：别把外站塞进我们的 WebView 里，也没必要 */
    private fun openExternal(url: String): Boolean = try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        true
    } catch (_: Throwable) {
        true
    }

    /** 退出淘宝登录：只清淘宝/菜鸟数据与对应域 Cookie。 */
    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.btn_logout))
            .setMessage(getString(R.string.logout_confirm))
            .setPositiveButton("确定") { _, _ ->
                clearSourceData("tb")
                clearTaobaoCookiesExact()
                webView?.post {
                    webView?.reload()
                    appToast(this, R.string.logout_done)
                }
            }
            .setNegativeButton("取消", null)
            .showAnimated()
    }

    private fun closeWeb() {
        pendingReplaceView = null
        // ★ vc506：副号登录流程清过 jar ⇒ 关页面时把主号 cookie 写回（身份码要用主号）
        try { PddAccounts.restorePrimaryToJar(this) } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        identityLayerActive = false
        stopPddLoginPoll()
        stopJdLoginPoll()
        stopTbLoginPoll()
        stopMiLoginPoll()
        // vc270：隐形旁听的触摸隔离层跟着一起收
        tbTouchEater?.let { root.removeView(it) }
        tbTouchEater = null
        webLayer?.let { root.removeView(it) }
        webView?.apply {
            try { stopLoading() } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
            try { clearHistory() } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
            try { loadUrl("about:blank") } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
            try { clearHistory() } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
            try { webViewClient = android.webkit.WebViewClient() } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
            // ★ vc518：**不 destroy**，放回池里复用 —— 残留回调再调用也不会炸
            if (webPool.size < 1) {
                try { (parent as? ViewGroup)?.removeView(this) } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
                webPool.addLast(this)
            } else {
                try { destroy() } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
            }
        }
        webView = null
        webLayer = null
        webBackClosesLayer = false
        progressBar = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---------- 拼多多登录（取件码数据源） ----------

    private var pddLoginPoll: Runnable? = null

    /** ★★ vc692：登录页打开那一刻的「登录指纹」（= PDDAccessToken + pdd_user_id） */
    private var pddLoginBaseFp = ""
    /** ★★ vc692：已经拿去服务端验过的指纹，避免同一份 cookie 每 1.5 秒打一次接口 */
    private var pddLoginTriedFp = ""
    private var pddLoginVerifying = false
    private var pddLoginBadToast = false
    /** ★ vc692：同一份新凭证已经被服务端否掉几次（拼多多写 cookie 与生效之间有延迟，允许重试 3 次） */
    private var pddLoginVerifyFails = 0

    /**
     * ★★ vc692（同志 2026-11-04 定 A 方案）：**只认"换到新凭证 + 服务端认账"才算登录成功**。
     *
     * ## 旧逻辑为什么会"刚登录就自动退出"（真机日志实证）
     * 旧判据只有一句：`captureCookiesFromJar().contains("PDDAccessToken")` —— **只看 cookie 名**。
     * 而拼多多的 token 过期后**仍旧躺在 jar 里**（`PddChannel.markLoginDead` 只置标记、不清理），
     * 于是用户一点开登录页，1.5 秒内就被判"登录成功"：
     *
     *   12:14:23 打开登录页 → 12:14:24.756 `登录落主号` → 12:14:28 `服务端 40001 ⇒ 标记登录态失效`
     *   12:35:58 打开登录页 → 12:35:59 又判成功（**0.7 秒，人手不可能完成登录**）
     *   ⇒ 页面被 `closeWeb()` 关掉、死 cookie 被存成"已登录"、4 秒后又变回未登录 —— 死循环。
     *
     * ## 新判据（两道闸，缺一不可）
     *   ① **指纹必须变**：打开登录页时记下指纹，只有 `PDDAccessToken`/`pdd_user_id` 真的换过才继续；
     *   ② **服务端必须认**：拿这份新 cookie 调一次轻量接口（拉 1 条订单，`PddNative.verify`），
     *      不认就**继续留在登录页**，绝不关闭、绝不保存。
     * 另外：打开时若现成那份本来就有效，验一次就直接收工（避免"明明登录着还让人傻等"）。
     */
    private fun startPddLoginPoll() {
        val had = pddLoginPoll != null
        stopPddLoginPoll()
        // 登录页会多次 onPageFinished（重定向/二次渲染）—— 已经在轮询就不重置基线，
        // 否则新换到的 token 会被当成"基线"，永远等不到"变化"。
        if (!had) {
            pddLoginBaseFp = pddLoginFp(PddChannel.captureCookiesFromJar())
            pddLoginTriedFp = ""
            pddLoginBadToast = false
            pddLoginVerifyFails = 0
            Log.i("PddLogin", "轮询开始：基线指纹=${pddLoginBaseFp.take(12)}…")
            // ★ 现成的那份先验一次：有效就直接收工（用户其实没掉线）
            val ck0 = PddChannel.captureCookiesFromJar()
            if (ck0.contains("PDDAccessToken")) {
                pddLoginVerifying = true
                verifyPddLogin(ck0) { ok, why ->
                    pddLoginVerifying = false
                    if (ok) {
                        Log.i("PddLogin", "打开登录页时其实已有效 ⇒ 直接收工")
                        stopPddLoginPoll()
                        finishPddLogin(verified = true)
                    } else {
                        Log.i("PddLogin", "现成 cookie 服务端不认（$why）⇒ 等用户真正登录")
                    }
                }
            }
        }
        val r = object : Runnable {
            var tries = 0
            override fun run() {
                tries++
                val ck = PddChannel.captureCookiesFromJar()
                val fp = pddLoginFp(ck)
                val hasToken = fp.substringBefore('|').isNotEmpty()
                // ① 没 token ⇒ 等；
                // ② 指纹没变（就是那份躺着的旧死 token）⇒ 等，绝不再秒判成功；
                // ③ 正在验 ⇒ 等（防重入）；
                // ④ 同一份已经验过且失败超过 3 次 ⇒ 不再纠缠它（真登录会换指纹）。
                val canVerify = hasToken && fp != pddLoginBaseFp && !pddLoginVerifying &&
                        (fp != pddLoginTriedFp || pddLoginVerifyFails in 1..3)
                if (canVerify) {
                    if (fp != pddLoginTriedFp) {
                        pddLoginTriedFp = fp
                        pddLoginVerifyFails = 0
                    }
                    pddLoginVerifying = true
                    Log.i("PddLogin", "换到新凭证（指纹=${fp.take(12)}…）⇒ 去服务端确认")
                    verifyPddLogin(ck) { ok, why ->
                        pddLoginVerifying = false
                        if (ok) {
                            Log.i("PddLogin", "服务端确认有效 ⇒ 落登录态")
                            stopPddLoginPoll()
                            finishPddLogin(verified = true)
                        } else {
                            pddLoginVerifyFails++
                            Log.w(
                                "PddLogin",
                                "服务端不认这份新 cookie（第 $pddLoginVerifyFails 次）：$why ⇒ 留在登录页"
                            )
                            if (pddLoginVerifyFails <= 3) {
                                // ★ 拼多多把 cookie 写进 jar 与服务端生效之间常有 1~3 秒差 ⇒ 同一份重试几次
                                mainHandler.postDelayed(this, 2500)
                            } else {
                                if (!pddLoginBadToast) {
                                    pddLoginBadToast = true
                                    appToast(
                                        this@MainActivity,
                                        "登录还没生效，请继续在页面上完成登录",
                                        true
                                    )
                                }
                                if (tries < 120) mainHandler.postDelayed(this, 1500)
                            }
                        }
                    }
                    return
                }
                if (tries >= 120) {   // 约 3 分钟没等到有效登录就停，别空转
                    Log.w("PddLogin", "3 分钟内没等到有效登录，停止轮询（页面保持不动）")
                    return
                }
                mainHandler.postDelayed(this, 1500)
            }
        }
        pddLoginPoll = r
        mainHandler.postDelayed(r, 1500)
    }

    /**
     * ★ vc692：登录指纹 —— 只看"身份相关的两样"，别被无关 cookie 抖动（api_uid 之类）带偏。
     */
    private fun pddLoginFp(ck: String): String {
        fun grab(k: String) =
            Regex("(?:^|;\\s*)$k=([^;]*)").find(ck)?.groupValues?.get(1)?.trim().orEmpty()
        return grab("PDDAccessToken") + "|" + grab("pdd_user_id")
    }

    /**
     * ★ vc692：把某份 cookie 拿去服务端验一次（后台线程，回调主线程）。
     * 判据来自 [PddNative.verify]：**没有 error_code 才是有效**（40001 = 登录已过期）。
     * 单次最长约 12s 连接 + 20s 读取，所以调用前必须置 `pddLoginVerifying` 防重入。
     */
    private fun verifyPddLogin(ck: String, onResult: (Boolean, String) -> Unit) {
        pddHttpPool.execute {
            val r = try {
                val token = Regex("PDDAccessToken=([^;]+)")
                    .find(ck)?.groupValues?.get(1)?.trim().orEmpty()
                val uid = PddChannel.uidFromCookie(ck)
                val ses = PddNative.Session(token = token, uid = uid, cookie = ck)
                val t = PddNative.verify(ses)
                t.first to t.third
            } catch (t: Throwable) {
                false to ("异常:" + (t.message ?: t.javaClass.simpleName))
            }
            mainHandler.post {
                try { onResult(r.first, r.second) } catch (t: Throwable) { SafeRun.w("PddLogin", t) }
            }
        }
    }

    private fun stopPddLoginPoll() {
        pddLoginPoll?.let { mainHandler.removeCallbacks(it) }
        pddLoginPoll = null
    }

    /** 收拼多多登录态：存 cookie 存档 → 关页面 → 就地刷新（设置页/取件码层） */
    private fun finishPddLogin(verified: Boolean = false) {
        val ck = PddChannel.captureCookiesFromJar()
        if (!ck.contains("PDDAccessToken")) {
            appToast(this, R.string.pdd_login_not_yet)
            return
        }
        // ★★ vc692：手动点「完成」也要过服务端这一关。
        //   旧写法直接保存 ⇒ jar 里那份过期 token 会被存成"已登录"，
        //   4 秒后业务接口回 40001 又打回未登录（同志 11-04 报的"登录后自动退出"就是它）。
        if (!verified) {
            if (pddLoginVerifying) {
                appToast(this, "正在确认登录态，请稍候…", false)
                return
            }
            pddLoginVerifying = true
            appToast(this, "正在确认登录态…", false)
            verifyPddLogin(ck) { ok, why ->
                pddLoginVerifying = false
                if (ok) {
                    finishPddLogin(verified = true)
                } else {
                    Log.w("PddLogin", "手动「完成」被服务端否掉：$why")
                    appToast(this, "登录还没生效（$why），请继续在页面上完成登录", true)
                }
            }
            return
        }
        stopPddLoginPoll()
        // ★ vc490：**副号登录不许覆盖主号**。
        //   PddChannel 里的 cookie 是"主号"的全局身份（身份码、主页文案、第 4/5 栏都读它）；
        //   副号登录只进账号库，登录完再把主号 cookie 写回 jar —— 拼多多身份码永远用主号。
        val slot = PddAccounts.pendingSlot(this)
        val isSecondaryLogin = slot == "new" || slot.startsWith("=")
        val loginUid = PddChannel.uidFromCookie(ck)
        val primaryUid = PddAccounts.primary(this)?.uid.orEmpty()
        // ★ vc506：副号槽却登成了主号 ⇒ 这次登录必须当作主号登录落库（否则主号永远显示失效）
        val sameAsPrimary = loginUid.isNotEmpty() && loginUid == primaryUid
        if (!isSecondaryLogin || sameAsPrimary) {
            PddChannel.saveCookies(this, ck)
            PddChannel.markLoginOk(this)
            Log.i("PddAccount", "登录落主号 uid=${loginUid.takeLast(6)}（副号槽=$isSecondaryLogin 同号=$sameAsPrimary）")
        }
        val acc = PddAccounts.saveLoginResult(this, ck, loginUid)
        if (isSecondaryLogin) {
            PddAccounts.primary(this)?.let { p ->
                try {
                    PddChannel.injectCookies(p.cookie)
                    Log.i("PddAccount", "副号登录完成，已把主号 cookie 写回 jar（身份码仍用主号）")
                } catch (t: Throwable) {
                    Log.w("PddAccount", "回写主号 cookie 失败: ${t.message}")
                }
            }
        }
        try {
            LogEvents.event(this, "login", mapOf("src" to "pdd", "account" to acc.label,
                "uidTail" to acc.uid.takeLast(6), "secondary" to isSecondaryLogin))
        } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        if (acc.primary && isSecondaryLogin) {
            appToast(this, "已按主号登录（检测到就是主号，不新增副号）", true)
        } else {
            appToast(this, "已登录：" + acc.label)
        }
        closeWeb()
        // 登录态变了：主页第 2 栏副标题（App 内直开 / 跳 App）要跟着变
        rebuildHome()
        // 设置页开着就重建它（入口上的"已登录/未登录"也要变）
        if (settingsLayer != null) {
            settingsLayer?.let { root.removeView(it) }
            settingsLayer = null
            openSettings()
        }
        if (pickupLayer != null) fetchPickup()
    }

    /** 重建主页（四栏文案依赖登录态时会用到） */
    private fun rebuildHome() {
        try {
            val idx = root.indexOfChild(homeView)
            if (idx < 0) return
            root.removeView(homeView)
            homeView = buildHome()
            root.addView(homeView, idx, matchParent())
            // ★ vc674：重建后主动要一次 inset（否则要等系统下次分发才补状态栏高度）
            runCatching { root.requestApplyInsets() }
        } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
    }

    // ---------- 京东登录（取件码数据源） ----------

    private var jdLoginPoll: Runnable? = null

    /** EMS 登录看护（每 2 秒读一次 localStorage） */

    private fun openJdLogin() {
        // ★ 清京东域旧 cookie：不清的话页面会复用"死会话"直接显示已登录 → 假登录
        //   （只清京东域，绝不碰淘宝/拼多多的会话）
        JdChannel.clearCookies()
        openWeb(
            JdChannel.LOGIN_URL, getString(R.string.jd_login_title),
            accent = "#E1251B", loginMode = "jd"
        )
    }

    /** 京东登录轮询：抓住 `pt_key` + `pt_pin` 就算成功 */
    private fun startJdLoginPoll() {
        stopJdLoginPoll()
        val r = object : Runnable {
            var tries = 0
            override fun run() {
                tries++
                val ck = JdChannel.captureCookiesFromJar()
                if (ck.contains("pt_key=") && ck.contains("pt_pin=")) {
                    finishJdLogin()
                    return
                }
                if (tries >= 120) return
                mainHandler.postDelayed(this, 1500)
            }
        }
        jdLoginPoll = r
        mainHandler.postDelayed(r, 1500)
    }

    private fun stopJdLoginPoll() {
        jdLoginPoll?.let { mainHandler.removeCallbacks(it) }
        jdLoginPoll = null
    }

    private fun finishJdLogin() {
        val ck = JdChannel.captureCookiesFromJar()
        if (!ck.contains("pt_key=")) {
            appToast(this, R.string.jd_login_not_yet)
            return
        }
        stopJdLoginPoll()
        JdChannel.saveCookies(this, ck)
        appToast(this, R.string.jd_login_ok)
        closeWeb()
        // 设置页/取件码层开着就刷新（登录状态变了）
        if (settingsLayer != null) {
            settingsLayer?.let { root.removeView(it) }
            settingsLayer = null
            openSettings()
        }
        if (pickupLayer != null) fetchPickup()
    }

    // ---------- 取件码：隐形数据 WebView + 原生渲染层 ----------
    // 机理：隐形 WebView 加载官方驿站首页（自带 lib-mtop + 登录 cookie），
    // 页面上下文里调 queryMultiStationPackages，JSON 经 JS 桥回原生，原生列表渲染。
    // 网页层只是数据通道，永远不可见；用户看到的全是原生 UI。

    private inner class DataBridge {
        @JavascriptInterface
        fun onPackages(json: String?) {
            runOnUiThread { handlePackagesResult(json) }
        }
    }

    /**
     * 建一个 1×1 的隐形 WebView 当 mtop 宿主页面。
     * 必须等 pages-fast 域加载完成（dataPageReady）才能跑 QUERY_JS ——
     * mtop 的签名由页面里的 lib-mtop 现算，页面没就绪就查不了。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureDataWebView() {
        if (dataWebView != null) return
        val wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.setBackgroundColor(Color.TRANSPARENT)
        wv.addJavascriptInterface(DataBridge(), "AndroidData")
        wv.addJavascriptInterface(IdentityBridge(), "__idcode")
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val ready = url.startsWith("https://pages-fast.m.taobao.com")
                dataPageReady = ready
                // ★ vc533：隐形页就绪后顺带取一次身份码（26 分钟缓存，过期才取）
                if (ready && System.currentTimeMillis() - idCodeTs > 5 * 60_000L && !idCodeFetching) {
                    idCodeFetching = true
                    mainHandler.postDelayed({
                        try {
                            wv.evaluateJavascript(IDENTITY_MSG_JS) { r ->
                                Log.i("IdCodeNative", "隐形页取身份码: $r")
                            }
                        } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
                    }, 1200)
                }
                if (ready && dataFetchPending) {
                    // 先给页面 1.2s 发它自己的取件接口；读不到再回退自拼查询
                    mainHandler.postDelayed({ readDataPageCapture() }, 1200)
                    mainHandler.postDelayed({
                        if (dataFetchPending) {
                            dataFetchPending = false
                            runQueryJs()
                        }
                    }, 3500)
                }
            }
        }
        root.addView(wv, FrameLayout.LayoutParams(1, 1))
        // ★★ vc523：**旁听页面自己发的取件接口**。
        //   自拼查询只带 {source,traceId}，服务端返回 allPkgSize=0（真机实证），
        //   而驿站首页自己会带完整站点上下文请求同名接口 —— 抄它的响应最靠谱。
        try {
            val hookJs = WebHook.hookJs("/h5api\\.m\\.taobao\\.com\\/h5\\/mtop\\.cainiao\\.pickup/i")
            val okHook = WebHook.attachDocumentStart(wv, hookJs, setOf("*"))
            if (!okHook) WebHook.attachFallback(wv, hookJs)
            Log.i("TaobaoFetch", "旁听钩子注入=$okHook")
        } catch (t: Throwable) {
            Log.w("TaobaoFetch", "旁听钩子注入失败: ${t.message}")
        }
        dataWebView = wv
        wv.loadUrl(HOME_URL)
    }

    // ---------- 取件码层（第 4 栏） ----------

    /**
     * ★★ v1.4.9b：页面上**真正显示出来**的「已取件」卡数（-1 = 本轮还没渲染过）。
     * 顶栏「清已取 N」以它为准 —— 数缓存会把过了 24h、被名单挡掉、去重后没上屏的历史标记也算进去，
     * 于是出现过"页面一个都没有，右上角写着 5"（同志 2026-11-10 报的）。
     */
    private var pickupPickedVisible: Int = -1
    /** 顶栏按钮的刷新回调（renderMerged 渲染完调一次） */
    private var pickupClearBtnRefresh: (() -> Unit)? = null
    /** v2 顶栏副行（N 张 · 待取 M）—— renderMerged 后刷新 */
    private var pickupSubRefresh: (() -> Unit)? = null

    /** 首页网格里的一格 */
    data class HomeCode(val code: String, val station: String, val picked: Boolean = false)

    /**
     * ★★ v1.4.10：**首页取件码网格的唯一数据源**。
     *
     * 由 [renderMerged] 在算完「这一屏到底显示哪些码」之后发布，
     * null = 本轮还没渲染过（冷启动第一帧）⇒ 首页回落读持久缓存。
     * 发布后用 [homeFeedFp] 比对，内容变了才重建首页 —— 否则每次源回调都重建，
     * 首页会跟着闪。
     */
    @Volatile
    private var homePickupCodes: List<HomeCode>? = null
    private var homeFeedFp = ""

    private fun openPickup(mode: String = "all") {
        if (pickupLayer != null) return
        pickupPickedVisible = -1
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // ★ vc321：layer 从 LinearLayout 换成「FrameLayout 外壳 + column 纵列」——
        //   底部三图标 dock 要挂进层内（LinearLayout 没法叠加），图标才能跟页面同进同出。
        val layer = FrameLayout(this).apply {
            // ★ v2 马卡龙：取件码页底铺环境光
            background = AmbientGlowDrawable(isDark())
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        // ★ v2 顶栏版式：左＝大标题「取件码」26px + 副行「N 张 · 待取 M」12px；
        //   右＝「清已取」天青胶囊按钮（.pillbtn）。顶栏透明，环境光透出。
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((22 * dp).toInt(), (6 * dp).toInt(), (18 * dp).toInt(), (14 * dp).toInt())
        }
        val pickupSubTv = TextView(this).apply {
            text = pickupSubtitle()
            textSize = UiTok.T12
            setTextColor(cTextSecondary)
        }
        run {
            /** 缓存里所有带「已取件」标记的条目 —— 只用于弹窗里交代"另有 N 条历史"，不再当按钮数字 */
            fun pickedCacheCount() = PddCodeStore.all(this).values.count { it.pickedAt != 0L }
            // ★ v2 .pillbtn：天青胶囊 + 强调字
            val clearBtn = TextView(this).apply {
                textSize = UiTok.T135
                typeface = tfMedium
                gravity = Gravity.CENTER
                setTextColor(cAccent)
                background = sq(UiTok.R999, UiTok.accentSoft(isDark()))
                setPadding((15 * dp).toInt(), (9 * dp).toInt(), (15 * dp).toInt(), (9 * dp).toInt())
                isClickable = true
            }
            // ★★ v1.4.13：**数字 = 当前页面上真正显示着「已取件」的卡数**。
            //   同志 2026-11-10 13:31：「取件码页面的清已取的来源是拼多多已签收的，
            //   并不是当前页面有标记已取件的取件码数量」—— 根因是这里原来数的是**缓存条数**，
            //   而缓存里的 pickedAt 有相当一部分是「自动打已取标签」按拼多多 SIGN/OUT_CABINET
            //   写进去的（平台显示"已签收"≠ 我取走了），这些卡根本没上屏。
            //   现在两头都收干净：① 自动打标已停用（见 [autoPickedTagOn]）；
            //   ② 按钮只认 [pickupPickedVisible]（renderMerged 写进来的"本页已取件卡数"）。
            fun refreshClearBtn() {
                val n = if (pickupPickedVisible >= 0) pickupPickedVisible else 0
                clearBtn.text = if (n > 0) "清已取 $n" else "清已取"
                clearBtn.setTextColor(if (n > 0) cAccent else cTextFaint)
            }
            pickupSubRefresh = { pickupSubTv.text = pickupSubtitle() }
        pickupClearBtnRefresh = { refreshClearBtn() }
            refreshClearBtn()
            clearBtn.setOnClickListener {
                val shown = if (pickupPickedVisible >= 0) pickupPickedVisible else 0
                val total = pickedCacheCount()
                if (shown == 0 && total == 0) {
                    appToast(this, "没有已取件的快递")
                } else {
                    // ★★ v1.4.13：页面数 = 你眼前看到的那些；缓存数可能更多（含旧版自动打标的残留）。
                    //   两个数分开说，避免"按钮写 2、弹窗说 7"的割裂感。
                    val msg = buildString {
                        if (shown > 0) append("当前页面上有 $shown 个「已取件」的快递。\n\n")
                        if (total > shown) {
                            append("缓存里另有 ${total - shown} 条历史「已取件」记录")
                            append("（旧版「自动打已取标签」按拼多多已签收写进去的，这次一并清掉）。\n\n")
                        }
                        append("删除后立即从取件码页与首页撤掉，并按之前的规则进黑名单（不会再被重新抓回来）。\n")
                        append("待取的不会动。")
                    }
                    AlertDialog.Builder(this)
                        .setTitle("清已取件")
                        .setMessage(msg)
                        .setPositiveButton("删除") { _, _ ->
                            var del = 0
                            for ((k, c) in PddCodeStore.all(this)) {
                                if (c.pickedAt != 0L) {
                                    // ★★ v1.4.12（同志 13:08）：「或者**手动删除**，并且需要按之前的形式
                                    //   进入黑名单」——手删 = 立即进名单（[MuteStore.Entry.holdUntil] 传 0）。
                                    //
                                    //   与 v1.4.10 那次"索性不写名单"的区别：那次写进去的是**永久**记录
                                    //   （订单号身份永不过期），一次「清已取 5」会把 5 个包裹永久屏蔽，
                                    //   连还没到的货一起消失（皇帝柑事故）。现在这批记录由**明确的用户标记**
                                    //   产生，且可在黑名单页单独「移出」，误伤可恢复。
                                    val osn = k.substringAfter(':')
                                    MuteStore.addPicked(
                                        c = this,
                                        id = "picked:$k",
                                        src = c.src,
                                        orderSn = osn,
                                        waybill = if (c.waybill == osn) "" else c.waybill,
                                        code = c.code,
                                        station = c.station,
                                        title = PddTitleStore.get(this, osn),
                                        holdMs = 0L
                                    )
                                    // ★ v1.4.12：**同时把「已取件」标记本身也取消** ——
                                    //   否则 [PddCodeStore.all] 会按身份回查 [PickedStore] 把它补回来，
                                    //   下一轮抓取又变成一张灰卡，"清已取"等于没清。
                                    val ids = LinkedHashSet<String>()
                                    ids.add(osn)
                                    if (c.waybill.isNotBlank()) ids.add(c.waybill)
                                    if (c.code.isNotBlank()) ids.add(c.code)
                                    PickedStore.unmark(this, ids)
                                    setManualPicked(k, false)
                                    PddCodeStore.remove(this, k)
                                    del++
                                }
                            }
                            Log.i("PickupClear", "一键清已取：删除 $del 条")
                            appToast(this, "已清 $del 个已取件")
                            // 收口：首页 + 组件 + 流体云 + 列表一次同步
                            refreshClearBtn()
                            runCatching { rebuildHome() }
                            runCatching { refreshPickupBarSub() }
                            runCatching { PickupPublish.publish(this, notifyNew = false) }
                            runCatching { renderMerged() }
                        }
                        .setNegativeButton("取消", null)
                        .showAnimated()
                }
            }
            // 左：大标题 + 副行（v2 .bar h2 / .sub）
            val titleBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            titleBox.addView(TextView(this).apply {
                text = getString(R.string.pickup_title)
                textSize = UiTok.T26
                typeface = tfMedium
                letterSpacing = -0.015f
                setTextColor(cTextPrimary)
            })
            titleBox.addView(pickupSubTv, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * dp).toInt() })
            top.addView(titleBox, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ))
            top.addView(clearBtn)
        }
        // ★ vc264n（同志 04:16 要求）：删掉右上角刷新键 —— 手动刷新走**下拉手势**
        //   （vc264m 加的 SwipeRefreshLayout，触发 fetchPickup(keepCards=true)）
        attachTopBar(column, top)

        val status = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(cTextSecondary)
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), 0)
        }
        column.addView(status, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val scroll = ScrollView(this)
        // ★ P2 渐进遮罩（与在途页同一套口径）
        runCatching {
            scroll.foreground = ProgressiveFadeDrawable(cBg, fromTop = true, fadePx = 28f * dp)
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // ★ vc318：底部多留 96dp —— 给悬浮导航让通道，最后一张卡能滚到图标上方而不被盖住
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), ((24 + 96) * dp).toInt())
        }
        scroll.addView(list)
        // ★ vc368：按地址分组时的吸顶标题 —— 滚动区包一层 FrameLayout，标题浮在顶部
        val scrollWrap = FrameLayout(this)
        scrollWrap.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        val stickyBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(cBg)
            setPadding((2 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            visibility = View.GONE
        }
        stickyBox.addView(View(this).apply { setBackgroundColor(cAccent) },
            LinearLayout.LayoutParams((3 * dp).toInt(), (20 * dp).toInt()))
        val stickyText = TextView(this).apply {
            textSize = 15f
            setTextColor(cTextPrimary)
            setPadding((8 * dp).toInt(), 0, 0, 0)
        }
        stickyBox.addView(stickyText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        scrollWrap.addView(stickyBox, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        pickupStickyBox = stickyBox
        pickupSticky = stickyText
        pickupScroll = scroll
        scroll.setOnScrollChangeListener { _, _, y, _, _ -> updatePickupSticky(y) }
        // ★ vc264m：下拉松手刷新（SwipeRefreshLayout 包住 ScrollView）。
        //   触发调 fetchPickup(keepCards=true)——与 vc229 秒显同款：刷新中**保留已有卡**，
        //   不闪白；状态行改"刷新中·上次 xx:xx"，回来后再整体重绘
        val swipe = androidx.swiperefreshlayout.widget.SwipeRefreshLayout(this).apply {
            setColorSchemeColors(cAccent)   // 转圈弧色 = 项目主色（靛紫）
            setProgressBackgroundColorSchemeColor(if (isDark()) Color.parseColor("#1C1D1F") else Color.WHITE)
            setDistanceToTriggerSync((88 * dp).toInt())    // 拉多少距离触发（手感）
            setSlingshotDistance((140 * dp).toInt())       // 阻尼拉伸距离（拖拽更跟手）
            // ★ vc369：滚动区外面包了一层 FrameLayout（放吸顶标题），SwipeRefreshLayout
            //   默认问"直接子 View 能不能往上滚"——FrameLayout 不滚 ⇒ 永远判"到顶了"，
            //   于是一往下拉就触发刷新。显式改成问里层的 ScrollView。
            setOnChildScrollUpCallback { _, _ -> scroll.canScrollVertically(-1) }
            setOnRefreshListener {
                fetchPickup(keepCards = true)
                // 兜底：60 秒还没停就强制收起（防异常路径卡转圈）
                postDelayed({ if (isRefreshing) isRefreshing = false }, 60_000L)
            }
            addView(scrollWrap)
        }
        column.addView(swipe, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        layer.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))

        root.addView(layer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        pickupLayer = layer
        // ★ vc321：dock **挂进取件码层内部**（vc318 时挂 root —— 侧滑返回时页面跟手滑走、
        //   图标留在原地不动，关闭提交后才消失，就是同志报的"页面退出图标还在"的割裂感）。
        //   挂进层内后，跟手滑出 / 淡入淡出全部天然同步；先挂好再 layerIn ⇒ 图标随层一起淡入。
        attachPickupDock(layer)
        layerIn(layer)
        pickupListContainer = list
        pickupSwipe = swipe
        pickupStatusText = status
        this.pickupMode = mode
        ensureDataWebView()
        // ★★ 一进页面就**先用缓存渲染一次**，不等抓取（2026-09-20 同志报的
        //   "退出应用再打开，刷新出来的拼多多页面没有取件码"）：
        //   取件码已经持久化在 PddCodeStore 里，没必要等所有源抓完（拼多多要走一次 WebView，
        //   常常 30~60 秒）才渲染 —— 那段时间用户看到的就是空白/「正在获取」，像"没刷出来"。
        //   有缓存就立刻上屏，抓取完成后 renderMerged() 会再刷一次（并顺带消除已取出的件）。
        val hadCache = PddCodeStore.all(this).isNotEmpty()
        if (hadCache) renderMerged()
        // ★ vc229：秒显出来的卡**不许**被下面的抓取清掉（fetchPickup 里原本无条件
        //   removeAllViews()，秒显渲染完立刻被抹平 ⇒ "第二次打开还是空白"）。
        if (autoRefreshOnOpen()) {
            fetchPickup(keepCards = hadCache)
        } else {
            Log.i("AutoRefresh", "取件码：自动刷新关闭，只显示缓存")
            setStatusText(
                pickupStatusText,
                if (hadCache) "自动刷新已关闭 · 下拉可刷新" else "自动刷新已关闭 · 下拉刷新"
            )
        }
    }

    private fun closePickup() {
        val layer = pickupLayer ?: return
        pickupLayer = null
        pickupListContainer = null
        pickupSwipe = null
        pickupStatusText = null
        pickupGroupHeaders.clear()
        pickupStickyBox = null
        pickupSticky = null
        pickupScroll = null
        // ★ v1.4.9b：顶栏按钮的引用一起清掉（视图已随层销毁，留着是脏引用）
        pickupClearBtnRefresh = null
        pickupSubRefresh = null
        pickupPickedVisible = -1
        // ★ vc321：dock 已在层内，随层一起走，不再单独拆（引用清掉防挂脏）
        pickupDock = null
        cancelPickupTimeout()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // ★★ vc673：关层时若在取件码页动过数据（标记已取件 / 删除），**重建首页主卡** ——
        //   首页那张「N 件待取」是 buildHome 的快照，不重建就一直是旧数字/旧码，
        //   表现为"打了已取件，回首页还显示，必须杀进程重进"（同志 2026-11-03 报的）。
        if (pickupDirty) {
            pickupDirty = false
            mainHandler.post { rebuildHome() }
        }
        // ★ vc321：退出不再"瞬间消失"—— 页面（含底部三图标）一起 180ms 淡出后再拆。
        //   侧滑手势路径走到这里时层已被跟手滑出屏，这段淡出只是看不见的收尾；
        //   无手势路径（预测性返回关闭/程序化关层）才真正可见 —— 那是割裂感的根修。
        //   期间 pickupLayer 已置空 ⇒ 重复返回/重开都安全（旧层自己淡自己的，新层照常建）。
        layer.animate().alpha(0f).setDuration(UiTok.D190)
            .withEndAction { (layer.parent as? ViewGroup)?.removeView(layer) }
            .start()
    }

    // ---------- ★ vc318 取件码页底部悬浮导航 ----------

    /**
     * 底部悬浮导航：淘 / 拼 / 菜 三个图标 —— 样式与主页三栏徽标**同款**
     * （圆角方块 + 白字 + tfMedium），点击直达对应身份码页（与主页三栏完全同源）。
     *
     * · dock = 透明 FrameLayout，**非 clickable** ⇒ 空白处触摸穿透到下面的列表/下拉刷新；
     * · ★ vc321：dock **挂进取件码层内部**（原挂 root）—— 侧滑跟手/淡入淡出时图标与页面
     *   同进同出；层内后加 ⇒ z 序仍压在卡片上方（列表底部另留 96dp 通道）；
     * · 动效：进出场 = 继承层动画（layerIn 220ms 淡入 / closePickup 180ms 淡出 / 跟手滑出），
     *   图标自身只保留**按压**（同志 10-18 晚定）：按下缩到 0.90，松手轻 Overshoot 弹回。
     */
    private fun attachPickupDock(parent: ViewGroup) {
        closePickupDock()
        val dock = FrameLayout(this).apply {
            isClickable = false
            isFocusable = false
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // ★ v1.4.20：玻璃胶囊内衬（48dp 离底移到 glass 的 bottomMargin，见下）
            setPadding((10 * dp).toInt(), (9 * dp).toInt(), (10 * dp).toInt(), (9 * dp).toInt())
        }
        row.addView(dockCell("淘", "#FF5000", getString(R.string.bar_taobao)) {
            openWeb(TAOBAO_URL, getString(R.string.web_title), accent = "#FF5000")
        })
        row.addView(dockCell("拼", "#E02E24", getString(R.string.bar_pdd_code)) {
            if (PddChannel.isLoggedIn(this)) openPddIdentity() else openPddLogin()
        })
        row.addView(dockCell("菜", "#0078FF", getString(R.string.bar_cainiao)) {
            openWeb(TAOBAO_URL, getString(R.string.web_title_cainiao), cainiaoIdentity = true)
        })
        // ★ v1.4.20：凝光玻璃胶囊 —— 背景采样 → 模糊 → 材质混色 → 轮廓光 四段全走。
        //   采样源 pickupScroll（惰性取，层重建跟得上）；玻璃自身采样时临时隐藏，零递归。
        //   列表从底下滚过时模糊实时跟手，这正是"凝光"浮层的签名。
        val glass = FrameLayout(this).apply {
            isClickable = false
            isFocusable = false
        }
        glass.addView(row, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        NingGuang.glass(
            target = glass,
            source = { pickupScroll },
            dark = isDark(),
            cornerDp = UiTok.R999,   // 胶囊：超椭圆半径自动 clamp 到半高
        )
        dock.addView(glass, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            // ★ vc583 口径不变：离底 48dp（手势条避让），从 row 的内边距挪到胶囊外距
            bottomMargin = (48 * dp).toInt()
        })
        // ★ vc321：挂进取件码层（原挂 root）—— 层内后加 ⇒ z 序仍压在卡片列表上方；
        //   层被跟手滑出/淡出时图标跟着走，淡入直接继承 layerIn 的 220ms，不单独播。
        parent.addView(dock, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ))
        pickupDock = dock
    }

    /**
     * 两个颜色按 t 混合（c1 → c2，t=0 全 c1 / t=1 全 c2）。
     * 深色徽章配色用 —— 不引 androidx ColorUtils，纯 Color 位运算，零依赖。
     */
    private fun blendARGB(c1: Int, c2: Int, t: Float): Int {
        val inv = 1f - t
        val a = (Color.alpha(c1) * inv + Color.alpha(c2) * t).toInt()
        val r = ((c1 shr 16 and 0xFF) * inv + (c2 shr 16 and 0xFF) * t).toInt()
        val g = ((c1 shr 8 and 0xFF) * inv + (c2 shr 8 and 0xFF) * t).toInt()
        val b = ((c1 and 0xFF) * inv + (c2 and 0xFF) * t).toInt()
        return Color.argb(a, r, g, b)
    }

    /** 单个悬浮图标：主页三栏同款徽标（48dp 圆角方块）+ 按压动效 */
    private fun dockCell(ch: String, colorHex: String, desc: String, action: () -> Unit): View {
        // ★ v2 .dock .it：**竖排** —— 上＝36dp 马卡龙色块（14sp 字），下＝11.5px 文字标签；
        //   选中态仍归系统蓝（.it.on），糖果色只做底不抢「当前在哪」。
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        cell.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = (UiTok.S16 * dp).toInt(); marginEnd = (UiTok.S16 * dp).toInt() }

        // 色块底色：淘=樱 / 拼=薰 / 菜=薄（与首页身份三格同一套）；其余退回品牌色
        val tileBg = when (ch) {
            "淘" -> UiTok.macRose(isDark())
            "拼" -> UiTok.macLilac(isDark())
            "菜" -> UiTok.macMint(isDark())
            else -> {
                val b = Color.parseColor(colorHex)
                UiTok.brandWithAlpha(b, UiTok.brandTileAlpha(isDark()))
            }
        }
        val badge = TextView(this).apply {
            text = ch
            textSize = UiTok.T14
            typeface = tfMedium
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(cTextPrimary)
            background = sq(UiTok.R14, tileBg)
        }
        // ★ vc583：36dp 色块（v2 .ch = 30px，本机手指加大一档）
        cell.addView(badge, LinearLayout.LayoutParams(
            (36 * dp).toInt(), (36 * dp).toInt()
        ))
        // ★ v2 标签（.dock .it：11.5px 次级色，与色块间距 5px）
        val label = TextView(this).apply {
            text = desc
            textSize = UiTok.T115
            setTextColor(cTextSecondary)
            gravity = Gravity.CENTER
            maxLines = 1
        }
        cell.addView(label, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (5 * dp).toInt() })

        // —— 按压动效（唯一保留）：按下缩小，松手轻弹回；返回 false 不拦截 ⇒ click 照常 ——
        //   ★ v2：动效挂 cell（整个 .it 含标签），手指点标签也能按
        var pressed = false
        cell.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    pressed = true
                    v.animate().scaleX(0.90f).scaleY(0.90f).setDuration(UiTok.D100)
                        .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (pressed) {
                        pressed = false
                        v.animate().scaleX(1f).scaleY(1f).setDuration(UiTok.D240)
                            .setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
                    }
                }
            }
            false
        }
        badge.setOnClickListener { action() }
        return cell
    }

    /** 撤导航（幂等兜底；vc321 起 dock 挂层内，正常随层消亡，只有重开前清残留才走到这） */
    private fun closePickupDock() {
        pickupDockAnims.forEach { it.cancel() }
        pickupDockAnims.clear()
        pickupDock?.let { d -> (d.parent as? ViewGroup)?.removeView(d) }
        pickupDock = null
    }

    // ---------- 设置页（主题） ----------

    private fun buildSettingsButton(): View {
        // ★ v2 .gear：36dp 圆形、薰衣草淡底，站在顶栏右侧（不再是右下 52dp 悬浮球）
        val size = (36 * dp).toInt()
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_setting)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding((9 * dp).toInt(), (9 * dp).toInt(), (9 * dp).toInt(), (9 * dp).toInt())
            contentDescription = getString(R.string.cd_settings)
            if (isDark()) setColorFilter(cTextPrimary)
        }
        // ★ v1.4.20：设置键换凝光玻璃 —— 采样首页内容（homeView 惰性取源，
        //   首页 rebuild 后不会采到旧尸体），四段管线与 dock 同源。
        val box = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { v ->
                // ★★ v1.4.9：**日志必须在所有判断之前** —— 否则"点不动"的时候什么线索都没有。
                val now = System.currentTimeMillis()
                val gap = now - settingsBtnLastTapMs
                if (gap in 0..450) {
                    Log.i("SetPerf", "click 忽略（${gap}ms 内连点）")
                    return@setOnClickListener
                }
                settingsBtnLastTapMs = now
                v.scaleX = 1f
                v.scaleY = 1f
                Log.i("SetPerf", "click layer=${settingsLayer != null} closing=$settingsClosing")
                // ★★ 先动手，再播反馈（2026-09-22 提速）：
                //   原顺序是「点 → 压扁70 → 回弹90 → 才 openSettings()」，那 170ms 用户纯干等
                //   （实测 click→openSettings 间隔 170ms，占整条链路的 29%）。
                //   现在**立刻构建并滑入**，按钮缩放只是视觉反馈，不再阻塞页面出现。
                //   重入由 openSettings 里的自愈判断拦住，安全。
                openSettings()
                // Linear 口径：短、快、不弹跳
                // ★ 恢复态**直接写值**，不再依赖"再开一段动画回来" ——
                //   那段动画被打断就是"按钮永久缩着、再也点不动"的根源。
                v.animate().scaleX(0.90f).scaleY(0.90f).setDuration(UiTok.D100).withEndAction {
                    v.scaleX = 1f
                    v.scaleY = 1f
                }.start()
                // 兜底自愈：无论动画有没有跑完，400ms 后一定归位
                v.postDelayed({
                    v.scaleX = 1f
                    v.scaleY = 1f
                }, 400)
            }
        }
        // ★ v1.4.20：玻璃面 + 涟漪（RippleDrawable 的 content/mask 都用玻璃，
        //   涟漪裁剪跟着超椭圆走，比旧的 OVAL GradientDrawable 更贴形）
        val glassD = NingGuang.glass(
            target = box,
            source = { if (::homeView.isInitialized) homeView else null },
            dark = isDark(),
            cornerDp = UiTok.R999,
        )
        box.background = RippleDrawable(ColorStateList.valueOf(cRipple), glassD, glassD)
        box.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            try {
                val loc = IntArray(2)
                v.getLocationOnScreen(loc)
                if (v.width > 0 && v.height > 0) {
                    settingsBtnRect.set(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
                }
            } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        }
        box.addView(icon, FrameLayout.LayoutParams(size, size))
        box.layoutParams = FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.END
            // 初始给一个保守值，避免 inset 未分发时齿轮压进状态栏
            setMargins(0, maxOf(statusBarTop, (36 * dp).toInt()) + (10 * dp).toInt(),
                       (20 * dp).toInt(), 0)
        }
        // ★ inset 到达后按真实状态栏高度重排（onCreate 时 statusBarTop 常为 0）
        box.setOnApplyWindowInsetsListener { v, insets ->
            val t = insets.getInsets(WindowInsets.Type.statusBars()).top
            (v.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.topMargin = maxOf(t, statusBarTop) + (10 * dp).toInt()
                v.layoutParams = lp
            }
            insets
        }
        box.post { box.requestApplyInsets() }
        return box
    }

    private fun openSettings() {
        try { UiTrace.page(this, "settings") } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        Log.i("SetPerf", "openSettings enter layer=${settingsLayer != null} closing=$settingsClosing")
        // ★★ v1.4.9：**自愈**。设置层是"有没有页面"的唯一真相，但它有可能变成孤儿：
        //   ① 退场动画的 withEndAction 没跑到 ⇒ 字段非空、视图其实已经不在 root 上；
        //   ② `settingsClosing` 卡在 true ⇒ 之后 closeSettings 直接 return，页面再也关不掉。
        //   两个残留都会表现成"设置页进不去/出不来"，且只看字段完全看不出来。
        //   这里进门先校验一次：层不在 root 里就丢掉重来。
        val stale = settingsLayer
        if (stale != null) {
            val attached = try { stale.parent === root } catch (_: Throwable) { false }
            if (!attached) {
                Log.w("SetPerf", "自愈：设置层已不在视图树上，丢弃旧字段重建")
                try { root.removeView(stale) } catch (_: Throwable) { }
                settingsLayer = null
            } else if (!settingsClosing) {
                Log.i("SetPerf", "设置页已开着，忽略重复打开")
                return
            }
        }
        settingsClosing = false
        if (settingsLayer != null) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        var scheduled = prefs.getString(KEY_THEME_MODE, "system") == "scheduled"
        val start = prefs.getString(KEY_DARK_START, "22:00") ?: "22:00"
        val end = prefs.getString(KEY_DARK_END, "07:00") ?: "07:00"

        val layer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // ★ v2 马卡龙：设置页底铺环境光
            background = AmbientGlowDrawable(isDark())
        }
        // ★ vc307：同志要求删除左上角「‹ 返回」键 —— 手机侧滑手势 / 底部返回键仍可返回
        //   （handleBack 对设置层的判定保持不变，只是不再显示这个按钮）。
        // ★ vc328：同志要求设置页左上角「设置」放大（其余页面顶栏字号不变，默认仍 17f）
        // ★ v2 .set-h：设置页大标题 26px（顶栏透明，环境光透出）
        attachTopBar(layer, makeTopBar(getString(R.string.settings_title), titleSize = UiTok.T26))

        val scroll = ScrollView(this)
        // 页面级外层：只管外边距，卡片自己管背景和内边距
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), 0)
        }
        fun addCard(c: View) = outer.addView(c, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (16 * dp).toInt() })
        // ★★ v1.4.13：**重置取件码缓存**（同志 2026-11-10 13:50：「可以增加一个选项，用户可以点击，
        //   此时清除取件码的所有缓存和数据，重新抓取」）。
        //   码彻底乱掉时（同码挂多单、真码被借来的码顶掉、首页少了某件），与其一条条猜哪条错，
        //   不如整份丢掉重抓一次 —— 平台会重新报一遍，比修数据快且不会留下"人工修过的痕迹"。
        run {
            val card = linearCard().apply { orientation = LinearLayout.VERTICAL }
            card.addView(sectionTitle("取件码缓存"))
            card.addView(hintLine("码对不上时用：同一个码挂在好几单、首页少了某件、真码被顶掉。清空后立即重新抓取。"))
            card.addView(trackRow(
                "重置取件码缓存",
                TextView(this).apply {
                    text = "清空并重抓"
                    textSize = 13f
                    setTextColor(cTextTertiary)
                }
            ) { confirmResetPickupCodes() })
            addCard(card)
        }
        // ★ vc693：自检卡 —— 各项功能是否正常一眼可见，还能模拟一条取件码通知。
        run {
            val card = linearCard().apply { orientation = LinearLayout.VERTICAL }
            // ★ vc707：排版与其它卡统一 —— 分类标题 13sp 次级色；入口走标准行，不再是小胶囊
            card.addView(sectionTitle("自检"))
            card.addView(hintLine("检查通知使用权、各平台登录态、取件码与在途缓存、后台轮询是否正常。"))
            // ⚠️ sourceRow 是 openSettings 里的局部函数（定义在本卡之后），这里只能用类级的 trackRow
            card.addView(trackRow(
                "运行自检",
                TextView(this).apply {
                    text = "逐项列结果"
                    textSize = 13f
                    setTextColor(cTextTertiary)
                }
            ) { showSelfTestDialog() })
            addCard(card)
        }


        // ★ vc327：分类折叠公共件（「深色模式」「快递数据源」两张卡共用）
        //   高度动画：收起 0 ← 满高，展开 0 → 满高（DecelerateInterpolator，220ms），
        //   动画结束把 height 还原成 WRAP_CONTENT，避免后续子行增删时被定死。
        fun animateSection(box: LinearLayout, arrow: android.widget.ImageView, expand: Boolean, key: String) {
            prefs.edit().putBoolean(key, !expand).apply()
            if (expand) {
                box.visibility = View.VISIBLE
                val parentW = (box.parent as? android.view.ViewGroup)?.width
                    ?: resources.displayMetrics.widthPixels
                box.measure(
                    View.MeasureSpec.makeMeasureSpec(parentW, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                val target = box.measuredHeight
                val anim = android.animation.ValueAnimator.ofInt(0, target).apply {
                    duration = 220
                    interpolator = android.view.animation.DecelerateInterpolator()
                    addUpdateListener {
                        box.layoutParams.height = it.animatedValue as Int
                        box.requestLayout()
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(a: android.animation.Animator) {
                            box.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                            box.requestLayout()
                        }
                    })
                }
                anim.start()
                arrow.animate().rotation(180f).setDuration(UiTok.D240).start()
            } else {
                val startH = box.height.takeIf { it > 0 } ?: box.measuredHeight
                val anim = android.animation.ValueAnimator.ofInt(startH, 0).apply {
                    duration = 200
                    interpolator = android.view.animation.DecelerateInterpolator()
                    addUpdateListener {
                        box.layoutParams.height = it.animatedValue as Int
                        box.requestLayout()
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(a: android.animation.Animator) {
                            box.visibility = View.GONE
                            box.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                            box.requestLayout()
                        }
                    })
                }
                anim.start()
                arrow.animate().rotation(0f).setDuration(UiTok.D190).start()
            }
        }

        // 可折叠标题行：标题占满 + 右侧箭头（染主题次级色，深浅色自动适配）
        // 点击整行切换 box 展开/收起（带动画），折叠状态存档（key），重进设置页不丢
        fun collapsibleHeader(title: String, box: LinearLayout, key: String, collapsed0: Boolean): View {
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                // ★ v2 对齐：与 sectionTitle / 行卡同一条 16dp 基准线
                setPadding((UiTok.S16 * dp).toInt(), (8 * dp).toInt(), (UiTok.S16 * dp).toInt(), (8 * dp).toInt())
            }
            header.addView(TextView(this).apply {
                text = title
                textSize = 13f
                setTextColor(cTextSecondary)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val arrow = android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_expand_arrow)
                setColorFilter(cTextSecondary)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                // 收起时箭头朝下（▾），展开时转 180° 朝上（▴）
                rotation = if (collapsed0) 0f else 180f
            }
            header.addView(arrow, LinearLayout.LayoutParams(
                (20 * dp).toInt(), (20 * dp).toInt()
            ).apply { leftMargin = (4 * dp).toInt() })
            box.visibility = if (collapsed0) View.GONE else View.VISIBLE
            header.setOnClickListener {
                animateSection(box, arrow, expand = box.visibility != View.VISIBLE, key = key)
            }
            applyPress(header)
            return header
        }
        // ★ 当前卡片：下面所有 card.addView(...) 都落在这张卡里。
        //   要换一张卡，只需重新给 card 赋值 —— 几百行既有代码一行都不用动（2026-09-20 拆卡片）
        var card = linearCard().apply {
            // ★ v2 统一：卡级不再套 padding —— 标题/行各自带 16dp 左缩进，标题左缘永远对齐
            orientation = LinearLayout.VERTICAL
        }
        addCard(card)
        // ★ vc327：「深色模式」分类加折叠 —— 标题行右侧箭头，点击展开/收起整卡内容
        val themeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val themeCollapsed0 = prefs.getBoolean("theme_collapsed", false)
        card.addView(collapsibleHeader(
            getString(R.string.settings_theme_section), themeBox, "theme_collapsed", themeCollapsed0
        ))
        card.addView(themeBox)

        val accent = cAccent

        fun saveConfig(newScheduled: Boolean, newStart: String, newEnd: String) {
            prefs.edit()
                .putString(KEY_THEME_MODE, if (newScheduled) "scheduled" else "system")
                .putString(KEY_DARK_START, newStart)
                .putString(KEY_DARK_END, newEnd)
                .putBoolean(KEY_REOPEN_SETTINGS, true)
                .apply()
            recreate()
        }

        fun radio(text: String, checked: Boolean) = RadioButton(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(cTextPrimary)
            buttonTintList = ColorStateList.valueOf(cTextPrimary)
            // ★ vc702：与设置页其它行同一套左右内边距（原来 0 ⇒ 单选项比所有行都往左凸 16dp）
            setPadding((UiTok.S16 * dp).toInt(), (10 * dp).toInt(),
                       (UiTok.S16 * dp).toInt(), (10 * dp).toInt())
            isChecked = checked
        }

        val rSystem = radio(getString(R.string.settings_follow_system), !scheduled)
        val rScheduled = radio(getString(R.string.settings_scheduled), scheduled)
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(rSystem)
            addView(rScheduled)
        }
        themeBox.addView(group)

        // 深色时间段（只在"自定义时段"下显示）
        val timeBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (scheduled) View.VISIBLE else View.GONE
        }
        var curStart = start
        var curEnd = end

        fun pickTime(cur: String, onOk: (String) -> Unit) {
            val p = cur.split(":")
            val d = TimePickerDialog(this, { _, h, m ->
                onOk(String.format(java.util.Locale.US, "%02d:%02d", h, m))
            }, p.getOrNull(0)?.toIntOrNull() ?: 22, p.getOrNull(1)?.toIntOrNull() ?: 0, true)
            dialogIn(d)
            d.show()
        }

        // 把 value 那个 TextView 一起交给回调，免得回调里去引用"后面才声明"的局部变量
        fun timeRow(labelRes: Int, value: String, onPick: (String, TextView) -> Unit): View {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // ★ vc702：与其它行统一到 S16
                setPadding((UiTok.S16 * dp).toInt(), (12 * dp).toInt(),
                           (UiTok.S16 * dp).toInt(), (12 * dp).toInt())
            }
            row.addView(TextView(this).apply {
                text = getString(labelRes)
                textSize = 15f
                setTextColor(cTextPrimary)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val valueTv = TextView(this).apply {
                text = value
                textSize = 15f
                setTextColor(accent)
                setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
            }
            valueTv.setOnClickListener { onPick(valueTv.text.toString(), valueTv) }
            row.addView(valueTv)
            return row
        }

        timeBox.addView(timeRow(R.string.settings_dark_start, start) { _, tv ->
            pickTime(curStart) { v ->
                curStart = v
                tv.text = v
                saveConfig(true, curStart, curEnd)
            }
        })
        timeBox.addView(timeRow(R.string.settings_dark_end, end) { _, tv ->
            pickTime(curEnd) { v ->
                curEnd = v
                tv.text = v
                saveConfig(true, curStart, curEnd)
            }
        })

        themeBox.addView(timeBox)
        themeBox.addView(TextView(this).apply {
            text = getString(R.string.settings_scheduled_hint)
            textSize = UiTok.T115
            setTextColor(cTextTertiary)
            // ★ vc702：与其它行统一到 S16
            setPadding((UiTok.S16 * dp).toInt(), (8 * dp).toInt(), (UiTok.S16 * dp).toInt(), 0)
        })

        // ---- 快递数据源：拼多多（应用内登录一次 → 之后才能抓到拼多多取件码）----
        // ★ 换一张新卡片：外观（深色模式）与数据源从此各占一张卡（2026-09-20 同志要求）
        // ★ vc326：分类改名「快递数据源」+ 可折叠；vc327 换用公共折叠件（带动画）
        card = linearCard().apply {
            // ★ v2 统一：卡级不再套 padding —— 标题/行各自带 16dp 左缩进，标题左缘永远对齐
            orientation = LinearLayout.VERTICAL
        }
        addCard(card)
        val srcBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(collapsibleHeader(
            getString(R.string.settings_sources_section), srcBox, "sources_collapsed",
            prefs.getBoolean("sources_collapsed", false)
        ))
        card.addView(srcBox)

        fun sourceRow(
            name: String, status: String, ok: Boolean,
            // ★ vc422：状态文字可单独指定颜色/字号（默认沿用原来的蓝色/三级灰）
            statusColor: Int? = null,
            // ★ v2 统一：行卡右侧值 14px（.rowcard .v）
            statusSize: Float = UiTok.T14,
            // ★ onMoreClick 必须排在 onClick 前面：调用方都用尾随 lambda，尾参永远匹配最后一个参数
            onMoreClick: (() -> Unit)? = null,
            onClick: () -> Unit
        ): View {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // ★ vc572：统一到令牌 —— 行高 64dp、左右 16dp、主文 15sp
                minimumHeight = (UiTok.ROW_H * dp).toInt()
                setPadding((UiTok.S16 * dp).toInt(), (UiTok.S12 * dp).toInt(),
                           (UiTok.S16 * dp).toInt(), (UiTok.S12 * dp).toInt())
                isClickable = true
                setOnClickListener { onClick() }
            }
            row.addView(TextView(this).apply {
                text = name
                // ★ v2 统一：行卡主标题 16px Medium（.rowcard .k .t）
                textSize = UiTok.T16
                typeface = tfMedium
                setTextColor(cTextPrimary)
            })
            // "更多"图标：紧贴名称文字右侧，高度跟主标题齐平（18dp）
            if (onMoreClick != null) {
                val iv = android.widget.ImageView(this).apply {
                    setImageResource(R.drawable.mi_more)
                    // 原图是深灰圆点，深色模式下会糊 → 染成主题的次级文字色
                    setColorFilter(cTextTertiary)
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    isClickable = true
                    setOnClickListener { onMoreClick() }
                }
                row.addView(iv, LinearLayout.LayoutParams(
                    (16 * dp).toInt(), (24 * dp).toInt()
                ).apply { leftMargin = (3 * dp).toInt() })
            }
            // 弹性空隙：把状态文字推到行尾
            row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            if (status.isNotBlank()) {
                row.addView(TextView(this).apply {
                    text = status
                    textSize = statusSize
                    // ★ v2 统一：右侧值用次级色（.rowcard .v = ink2），数字场景由调用方覆盖
                    setTextColor(statusColor ?: cTextSecondary)
                    maxLines = 1
                    gravity = Gravity.END
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { rightMargin = (UiTok.S8 * dp).toInt() })
            }
            // ★ vc572：行尾箭头（20sp / cTextFaint，替代此前"什么都没有"的空尾）
            row.addView(TextView(this).apply {
                text = "\u203a"
                textSize = UiTok.T20
                setTextColor(cTextFaint)
            })
            applyPress(row)
            return row
        }

        // 顺序跟主页四栏对齐：淘宝/菜鸟 → 拼多多 → 京东
        // 淘宝：登录态直接活在 WebView cookie jar 里（第1/3栏与第4栏都用它），不需要存档
        val tbOk = TbChannel.isLoggedIn()
        srcBox.addView(sourceRow(
            "淘宝 / 菜鸟",
            if (tbOk) getString(R.string.source_logged_in, TbChannel.uid().takeLast(4))
            else getString(R.string.source_not_logged_in),
            tbOk,
            onMoreClick = {
                infoDialog(
                    "淘宝 / 菜鸟",
                    "登录后从淘宝/菜鸟抓取件码与到站物流。这里的登录态与主页第 1 栏共用，" +
                            "退出会一并清掉淘宝 cookie。"
                )
            }
        ) {
            if (tbOk) confirmLogoutSource("淘宝 / 菜鸟") { clearSourceData("tb"); clearTaobaoCookiesExact() }
            else openTbLogin()
        })

        // ★ vc490：拼多多多账号 —— 标题行带展开/收起箭头（点击切换，带动画）。
        //   折叠状态存在 SP（pdd_accounts_collapsed），默认收起。
        //   展开后：主号 / 副号… / ＋添加账号。身份码永远用主号（见 openPddMdkd）。
        val pddOk = PddChannel.isLoggedIn(this)
        PddAccounts.ensureMigrated(this)
        PddAccounts.pruneDuplicates(this)
        val pddAccs = PddAccounts.all(this)
        val pddBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pddCollapsed0 = prefs.getBoolean("pdd_accounts_collapsed", true)
        val pddHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // ★ vc702：与「快递数据源」里其它 sourceRow（S16）对齐 —— 原来 12dp 让"拼多多"比邻居左凸 4dp
            setPadding((UiTok.S16 * dp).toInt(), (16 * dp).toInt(),
                       (UiTok.S16 * dp).toInt(), (16 * dp).toInt())
            isClickable = true
        }
        pddHeader.addView(TextView(this).apply {
            text = "拼多多"
            // ★ v2 统一：行卡主标题 16px Medium（与 sourceRow 同档）
            textSize = UiTok.T16
            typeface = tfMedium
            setTextColor(cTextPrimary)
        })
        // ★ vc703：与其它行同款 ⓘ（多账号语义容易误解，值得说明）
        run {
            val iv = android.widget.ImageView(this).apply {
                setImageResource(R.drawable.mi_more)
                setColorFilter(cTextTertiary)
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                isClickable = true
                setOnClickListener {
                    infoDialog(
                        "拼多多",
                        "在应用内登录一次即可抓取拼多多的取件码与在途物流；可添加多个账号，" +
                                "身份码永远用主号。点本行可展开/收起账号列表。"
                    )
                }
            }
            pddHeader.addView(iv, LinearLayout.LayoutParams(
                (16 * dp).toInt(), (24 * dp).toInt()
            ).apply { leftMargin = (3 * dp).toInt() })
        }
        pddHeader.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        pddHeader.addView(TextView(this).apply {
            val base = if (pddOk) getString(R.string.source_logged_in, PddChannel.uid(this@MainActivity))
            else getString(R.string.source_not_logged_in)
            text = base + if (pddAccs.size > 1) " · ${pddAccs.size} 个账号" else ""
            textSize = 13f
            setTextColor(cTextSecondary)   // ★ vc516：与「导出日志 设置」同色同字号
        })
        val pddArrow = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_expand_arrow)
            setColorFilter(cTextSecondary)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            rotation = if (pddCollapsed0) 0f else 180f
        }
        pddHeader.addView(pddArrow, LinearLayout.LayoutParams(
            (20 * dp).toInt(), (20 * dp).toInt()
        ).apply { leftMargin = (6 * dp).toInt() })
        pddBox.visibility = if (pddCollapsed0) View.GONE else View.VISIBLE
        pddHeader.setOnClickListener {
            animateSection(
                pddBox, pddArrow,
                expand = pddBox.visibility != View.VISIBLE,
                key = "pdd_accounts_collapsed"
            )
        }
        applyPress(pddHeader)
        srcBox.addView(pddHeader)
        srcBox.addView(pddBox)

        // 主号（身份码/取件码都用它）
        pddBox.addView(sourceRow(
            "主号",
            if (pddOk) getString(R.string.source_logged_in, PddChannel.uid(this))
            else getString(R.string.source_not_logged_in),
            pddOk
        ) {
            if (pddOk) {
                confirmLogoutSource("拼多多主号") { clearSourceData("pdd"); PddChannel.clearLogin(this) }
            } else {
                PddAccounts.beginPrimaryLogin(this)
                openPddLogin()
            }
        })

        // 副号：一行一个；点行重新登录，长按删除
        for (acc in PddAccounts.secondaries(this)) {
            val alive = acc.deadAt == 0L
            val row = sourceRow(
                acc.label,
                if (alive) getString(R.string.source_logged_in, acc.uid.takeLast(6))
                else "登录已失效 · 点此重登",
                alive
            ) {
                // ★ vc495：**已登录的副号，点一下应当是"退出"确认**（与主号行一致）；
                //   只有"未登录/已失效"才进登录页。原来无脑开登录页，用户以为登录坏了。
                if (alive) {
                    confirmLogoutSource("拼多多 · ${acc.label}") {
                        // ★★ vc670：退出副号必须把**它补出来的取件码**一起清掉，
                        //   否则页面上那几张卡会一直挂着（用户 11-02 实测）。
                        val n = purgeAccountCodes(acc.label)
                        PddAccounts.remove(this, acc.id)
                        appToast(
                            this,
                            if (n > 0) "已退出 ${acc.label}，清掉 $n 个取件码" else "已退出 ${acc.label}",
                            true
                        )
                        if (pickupLayer != null) renderMerged()
                        refreshSettingsIfOpen()
                    }
                } else {
                    PddAccounts.beginRelogin(this, acc.id)
                    // 重登副号必须先清 cookie（否则登录页直接是主号的已登录态）
                    try {
                        PddAccounts.backupPrimaryCookie(this)
                        PddChannel.clearCookies()
                    } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
                    openPddLogin()
                }
            }
            row.setOnLongClickListener {
                android.app.AlertDialog.Builder(this)
                    .setTitle("删除 ${acc.label}？")
                    .setMessage("只移除这个副账号的登录态；主号和其他账号不受影响。")
                    .setPositiveButton("删除") { _, _ ->
                        // ★★ vc670：删账号同时清它名下的取件码（同"退出副号"口径）
                        val n = purgeAccountCodes(acc.label)
                        PddAccounts.remove(this, acc.id)
                        appToast(
                            this,
                            if (n > 0) "已删除 ${acc.label}，清掉 $n 个取件码" else "已删除 ${acc.label}",
                            true
                        )
                        if (pickupLayer != null) renderMerged()
                        refreshSettingsIfOpen()
                    }
                    .setNegativeButton("取消", null)
                    .showAnimated()
                true
            }
            pddBox.addView(row)
        }

        pddBox.addView(sourceRow(
            "＋ 添加账号",
            if (pddAccs.size >= 3) "最多 3 个账号（1 主 + 2 副）" else "点击登录",
            false
        ) {
            if (PddAccounts.all(this).size >= 3) {
                appToast(this, "最多 3 个账号（1 主 + 2 副）", false)
            } else {
                addPddSecondary()
            }
        })


        val jdOk = JdChannel.isLoggedIn(this)
        srcBox.addView(sourceRow(
            "京东",
            if (jdOk) getString(R.string.source_logged_in, JdChannel.pin(this))
            else getString(R.string.source_not_logged_in),
            jdOk,
            onMoreClick = {
                infoDialog(
                    "京东",
                    "在应用内登录京东后，用隐形网页旁听订单接口拿取件码与在途物流。" +
                            "京东接口要现算签名，所以这条路比另外两家慢一些。"
                )
            }
        ) {
            if (jdOk) confirmLogoutSource("京东") { clearSourceData("jd"); JdChannel.clearLogin(this) }
            else openJdLogin()
        })

        // 小米：登录后拿到的是 serviceToken（换票在后台自动完成），抓取功能随后接入
        // 行尾"更多"图标：说明接入小米智能助理能拿到哪些快递推送
        // 小米数据源开关（默认关）：开了才在取件码/在途快递里加载小米，其余源不受影响
        val miOn = miEnabled()
        val miOk = miOn && MiChannel.isLoggedIn(this)
        // 已登录但还没探到手机号 → 后台问一次小米账号资料（拿包裹要靠手机号，别让用户手输）
        if (miOk && MiChannel.phones(this).isEmpty()) {
            pddIo.execute {
                val ph = MiChannel.detectPhoneFromProfile()
                if (ph.isNotEmpty()) {
                    MiChannel.savePhone(this, ph)
                    runOnUiThread {
                        if (settingsLayer != null) {
                            settingsLayer?.let { root.removeView(it) }
                            settingsLayer = null
                            openSettings()
                        }
                    }
                }
            }
        }
        val miTag = MiChannel.phones(this).firstOrNull()?.takeLast(4)
            ?: MiChannel.accountId(this).takeLast(4)
        // 小米：走**小米商城 H5 旁听**（原生接口那条路依赖 MIUI 系统类，非小米设备不通）
        if (miOk) {
            pddIo.execute {
                runOnUiThread {
                    // 小米跑完（两个站点）再顺序执行下一个源 —— 同一时刻只能有一个 WebView 抓取
                    MiShopChannel.tryDebugIfDue(this) {
                                }
                }
            }
        }
        srcBox.addView(sourceRow(
            "小米",
            when {
                !miOn -> "已关闭 · 点击开启"
                miOk -> getString(R.string.source_logged_in, miTag)
                else -> getString(R.string.source_not_logged_in)
            },
            miOk,
            onMoreClick = { openMiMoreDialog() }
        ) {
            when {
                !miOn -> {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("mi_enabled", true).apply()
                    appToast(this, "小米数据源已开启")
                    settingsLayer?.let { root.removeView(it) }
                    settingsLayer = null
                    openSettings()
                }
                miOk -> confirmLogoutSource("小米") {
                    clearSourceData("mi")
                    MiChannel.clearLogin(this)
                    MiChannel.clearJar()
                    // 退出即关闭数据源开关（想再用就重新点行开启+登录）
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("mi_enabled", false).apply()
                }
                else -> openMiLogin()
            }
        })
        // 快递100：不是账号聚合，而是「运单号 → 完整轨迹 + 预计送达」的增强服务（免登录）。
        // 行尾开关一键启停；行点击弹能力说明。
        val kdOn = Kd100Channel.isEnabled(this)
        val kdRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // ★ vc702：与同卡其它行统一到 S16
            setPadding((UiTok.S16 * dp).toInt(), (16 * dp).toInt(),
                       (UiTok.S16 * dp).toInt(), (16 * dp).toInt())
            isClickable = true
            setOnClickListener {
                // ★ 这个弹窗从"只读的能力说明"升级成"说明 + 官方凭据配置" ——
                //   照参考项目 ExpressAssistant 的做法（它把快递100 的 key/customer 放在
                //   「更多连接方式」里让用户自己填，填了走**官方接口**、否则退回免费通道）。
                //   为什么要它：官方接口的 param **支持 phone**，而韵达这类"查前要手机尾号"
                //   的快递在免费通道根本拿不到数据（2026-09-21 实测）。
                val keyIn = android.widget.EditText(this@MainActivity).apply {
                    hint = "快递100 key（可留空）"
                    setSingleLine(true)
                    val p = (16 * dp).toInt()
                    setPadding(p, p / 2, p, p / 2)
                    setText(Kd100Channel.kdKey(this@MainActivity))
                }
                val custIn = android.widget.EditText(this@MainActivity).apply {
                    hint = "快递100 customer（可留空）"
                    setSingleLine(true)
                    val p = (16 * dp).toInt()
                    setPadding(p, p / 2, p, p / 2)
                    setText(Kd100Channel.kdCustomer(this@MainActivity))
                }
                val box = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    val p = (20 * dp).toInt()
                    setPadding(p, p / 2, p, 0)
                    addView(TextView(this@MainActivity).apply {
                        text = getString(R.string.kd_capability_note)
                        textSize = 13f
                        setTextColor(cTextSecondary)
                    })
                    // ★ vc239：把"不保证补得到"明写出来。起因：2026-10-12 实测免费通道已基本
                    //   拿不到数据（同一单号官方给 8 条、免费给 0 条），原来那句"免登录、不需要账号"
                    //   会让人以为开了就一定有轨迹。
                    addView(TextView(this@MainActivity).apply {
                        text = getString(R.string.kd_capability_warn)
                        textSize = 12f
                        setTextColor(cTextTertiary)
                        setPadding(0, (8 * dp).toInt(), 0, 0)
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = if (Kd100Channel.hasOfficial(this@MainActivity))
                            "官方凭据：已配置 · 走官方接口（支持手机尾号校验）\n" +
                                    "⚠️ 免费通道目前已基本查不到数据，实际能补到多少仍取决于官方侧"
                        else
                            "官方凭据：未配置 · 免费通道目前已基本查不到数据，\n" +
                                    "建议到快递100 开放平台申请 key 和 customer 填在这里"
                        textSize = 12f
                        setTextColor(if (Kd100Channel.hasOfficial(this@MainActivity)) accent else cTextTertiary)
                        setPadding(0, (10 * dp).toInt(), 0, 0)
                    })
                    addView(keyIn)
                    addView(custIn)
                }
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.kd_more_title))
                    .setView(box)
                    .setPositiveButton("保存") { _, _ ->
                        Kd100Channel.saveOfficial(
                            this@MainActivity,
                            keyIn.text.toString(), custIn.text.toString()
                        )
                        appToast(this@MainActivity, "已保存，正在重新查询")
                        fetchManualChannel()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .showAnimated()
            }
        }
        kdRow.addView(TextView(this).apply {
            text = "快递100"
            textSize = 15f
            setTextColor(cTextPrimary)
        })
        // ★ vc703：与其它行同款 ⓘ（顺带说明"免费通道不保证有数据"）
        run {
            val iv = android.widget.ImageView(this).apply {
                setImageResource(R.drawable.mi_more)
                setColorFilter(cTextTertiary)
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                isClickable = true
                setOnClickListener {
                    infoDialog(
                        "快递100",
                        "不登录任何账号，按运单号补完整轨迹与预计送达。免费通道近年常拿不到数据，" +
                                "想稳定就点本行填写官方 key（开放平台申请）。"
                    )
                }
            }
            kdRow.addView(iv, LinearLayout.LayoutParams(
                (16 * dp).toInt(), (24 * dp).toInt()
            ).apply { leftMargin = (3 * dp).toInt() })
        }
        kdRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        // ★ 状态文字单独持引用：开关切换时**原地只改这一行**，不再重建整个设置页 ——
        //   重建等于把整页 layerIn 重播一遍（用户看到"页面闪一下又滑进来"），比不做动效更糟。
        val kdStatus = TextView(this).apply {
            text = if (kdOn) getString(R.string.kd_status_on) else getString(R.string.kd_status_off)
            textSize = 13f
            setTextColor(cTextSecondary)   // ★ vc516：与「导出日志 设置」同色同字号
        }
        kdRow.addView(kdStatus)
        kdRow.addView(android.widget.Switch(this).apply {
            isChecked = kdOn
            // 与 animateSwitchTint 同一套口径（滑块=实心基础色，轨道=同色 38%），
            // 否则首帧是平台色、点一下才跳成我们的色，会看到"跳一下"
            val base0 = if (kdOn) cAccent else cTextFaint
            thumbTintList = android.content.res.ColorStateList.valueOf(base0)
            trackTintList = android.content.res.ColorStateList.valueOf(withAlpha(base0, 0.38f))
            setOnCheckedChangeListener { sw, checked ->
                Kd100Channel.setEnabled(this@MainActivity, checked)
                // ★ `setOnCheckedChangeListener` 给的是 CompoundButton；`trackTintList` /
                //   `thumbTintList` 是 **Switch 自己**声明的（不在 CompoundButton 上），所以要强转。
                animateSwitchTint(sw as android.widget.Switch, checked)
                setStatusText(
                    kdStatus,
                    getString(if (checked) R.string.kd_status_on else R.string.kd_status_off)
                )
                kdStatus.setTextColor(cTextSecondary)   // ★ vc516：切开关也保持统一色
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = (8 * dp).toInt() })
        srcBox.addView(kdRow)
        // 手动添加单号（快递100 增强）：设置页在快递100 下面给个入口
        val manualCount = manualWaybills(this).size
        srcBox.addView(sourceRow(
            "手动添加单号",
            if (manualCount > 0) "已添加 $manualCount 个 · 点击添加/管理" else "点击添加运单号",
            true,
            onMoreClick = {
                infoDialog(
                    "手动添加单号",
                    "不依赖任何平台账号：按运单号走快递100 查询轨迹与预计送达，" +
                            "在「在途快递」里作为独立标签展示。"
                )
            }
        ) { showAddWaybillDialog() })

        // ---- 身份码（vc536）：淘宝身份码跳转 + 菜鸟身份码替换 ----
        card = linearCard().apply {
            // ★ v2 统一：卡级不再套 padding —— 标题/行各自带 16dp 左缩进，标题左缘永远对齐
            orientation = LinearLayout.VERTICAL
        }
        addCard(card)
        card.addView(TextView(this).apply {
            text = "身份码"
            textSize = 13f
            setTextColor(cTextSecondary)
            setPadding(0, 0, 0, (2 * dp).toInt())
        })
        // ① 淘宝身份码跳转（原在「快递数据源」里，vc536 挪到这里）
        card.addView(switchRow(
            "淘宝身份码跳转",
            prefs.getBoolean("tb_id_autojump", false),
            onMoreClick = {
                infoDialog(
                    "淘宝身份码跳转",
                    "打开身份码时会自动跳到淘宝/菜鸟的取件码页面，省一次手动点击。" +
                            "不想要自动跳转时关掉即可。"
                )
            }
        ) { on, _ ->
            prefs.edit().putBoolean("tb_id_autojump", on).apply()
            Log.i("Settings", "淘宝身份码跳转 = $on")
        })
        // ② 菜鸟身份码替换（新）：开启后页面没出码时，用备用码**替换**页面那块区域（不弹窗）
        card.addView(switchRow(
            "菜鸟备用身份码",
            prefs.getBoolean("id_backup_code", false),
            onMoreClick = {
                infoDialog(
                    "菜鸟备用身份码",
                    "页面自己没出码时，用本地保存的备用码替换那块区域（不弹窗）。" +
                            "仅在官方接口抽风时用得上。"
                )
            }
        ) { on, _ ->
            prefs.edit().putBoolean("id_backup_code", on).apply()
            Log.i("Settings", "菜鸟备用身份码 = $on")
        })

        // ---- 后台快递跟踪：不开 App 也能发现物流变化并通知 ----
        card = linearCard().apply {
            // ★ v2 统一：卡级不再套 padding —— 标题/行各自带 16dp 左缩进，标题左缘永远对齐
            orientation = LinearLayout.VERTICAL
        }
        addCard(card)
        card.addView(TextView(this).apply {
            text = "提醒与跟踪"
            textSize = 13f
            setTextColor(cTextSecondary)
            setPadding(0, 0, 0, (2 * dp).toInt())
        })
        // 跟踪范围：★ 同志 2026-09-20 定的规则 —— 默认盯全部包裹，但只在
        // 「派件中 / 已到达」两个节点发声；可改成「指定」，那就是长按哪单盯哪单（原效果）。
        val mode = ExpressStore.trackMode(this)
        val modeStatus = TextView(this).apply {
            text = if (mode == ExpressStore.MODE_ALL) "全部包裹 · 只在派件中 / 已到达时通知"
            else "仅指定 · 长按卡片添加"
            textSize = 13f
            setTextColor(cTextSecondary)   // ★ vc515：与「导出日志 设置」同色同字号
        }
        card.addView(trackRow("跟踪范围", modeStatus,
            onMoreClick = {
                infoDialog(
                    "跟踪范围",
                    "「全部」= 盯所有在途包裹（手动屏蔽的除外）；「指定」= 只盯你长按标记过的那几单。" +
                            "只有「派件中」和「已到达」两种情况会通知。"
                )
            }) { showTrackModeSheet() })
        val pollMin = ExpressStore.pollIntervalMin(this)
            val pm = maxOf(pollMin, ExpressStore.MIN_INTERVAL_MIN)
            val all = ExpressStore.trackMode(this) == ExpressStore.MODE_ALL
            val pollStatus = TextView(this).apply {
                // ★ 文案要跟着范围模式走：全部模式下说"仅轮询有在途跟踪件的平台"是错的，
                //   它盯的是全部在途包裹（手动屏蔽的那几个除外）。
                text = if (pollMin <= 0) "默认关闭"
                else if (all) "每 $pm 分钟 · 盯全部在途包裹"
                else "每 $pm 分钟 · 仅轮询有在途跟踪件的平台"
                textSize = 13f
                setTextColor(cTextSecondary)   // ★ vc515：与「导出日志 设置」同色同字号
            }
        card.addView(trackRow(
            "后台轮询", pollStatus,
            onMoreClick = {
                AlertDialog.Builder(this)
                    .setTitle("后台轮询")
                    .setMessage(
                        "默认盯全部包裹，但只有「派件中」和「已到达」两种情况会通知" +
                                "（运输中的每一跳不打扰）。\n\n" +
                                "长按任一卡片可单独屏蔽这一单；把上面的范围改成「指定」，" +
                                "就是只盯手动添加的那几单。"
                    )
                    .setPositiveButton("好", null)
                    .showAnimated()
            }
        ) { showPollIntervalSheet(pollStatus) })
        // ★★ v1.4.13：**撤掉「自动打已取标签」开关**（见 [autoPickedTagOn] 的说明）。
        //   拼多多枚举的 SIGN/OUT_CABINET 是"站点代签"，不等于"我取走了"；
        //   自动打灰标会让这些件既不在取件码页待取、也不在在途栏，还会污染「清已取」的计数。
        //   现在「已取件」只能是用户手动点的那一下。
        card.addView(infoRow(
            "已取件只能手动标记",
            "平台显示「已签收」不代表你已经取走（可能是站点代签）。" +
                    "要标记某单已取，直接在取件码卡上点「标记已取件」。"
        ))
        // ★ vc599：同志要求 —— 「自动刷新」从「其他」搬到「提醒与跟踪」（它属于刷新/抓取行为）
        card.addView(switchRow(
            "自动刷新",
            prefs.getBoolean("auto_refresh_on_open", true),
            onMoreClick = {
                infoDialog(
                    "自动刷新",
                    "每次打开取件码/在途快递页面时自动抓一次。" +
                            "关掉后进页只显示上次结果，需要时下拉手动刷新。"
                )
            }
        ) { on, _ ->
            prefs.edit().putBoolean("auto_refresh_on_open", on).apply()
            Log.i("Settings", "自动刷新 = $on")
        })
        // ★ vc705：「取件位置标签」规则（1.3 特性移植）——
        //   把一整句驿站原文压成"北门 3 号柜"这种人话；规则可增删、可试测。
        run {
            val labelCount = PickupLabel.load(this).size
            val labelStatus = TextView(this).apply {
                text = if (labelCount > 0) "已配置 $labelCount 条 · 点击管理" else "点击添加规则"
                textSize = 13f
                setTextColor(cTextSecondary)
            }
            card.addView(trackRow("取件位置标签", labelStatus,
                onMoreClick = {
                    infoDialog(
                        "取件位置标签",
                        "驿站/代收点的原文常常是一整句（取件时间、状态、站名混在一起），界面放不下。" +
                                "这里配规则：匹配文字（支持 * 通配）→ 显示成；" +
                                "命中就停 = 这条改完就结束，继续 = 让后面的规则接着改。"
                    )
                }) { openLabelRulesPage(labelStatus) })
        }
        // 通知权限：Android 13+ 没这个权限，通知会被系统**静默丢弃** —— 必须显式提示，
        // 否则用户会以为"功能坏了"。授权后这行自动消失（openSettings 会重建）。
        if (!TrackNotifier.canNotify(this)) {
            card.addView(trackRow("通知权限", TextView(this).apply {
                text = "未开启 · 点此授权"
                textSize = 13f
                setTextColor(accent)
            }) { requestNotifPermission() })
        }

        // 卡片已各自带背景和边框（linearCard），原来那层实色 wrapCard 不再需要
        scroll.addView(outer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        layer.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        group.setOnCheckedChangeListener { _, id ->
            scheduled = (id == rScheduled.id)
            fadeToggle(timeBox, scheduled)
            saveConfig(scheduled, curStart, curEnd)
        }

        // ---- 系统功能适配（vc288 细分重组；vc321 同志 10-19 改名：原「ColorOS 系统功能」，
        //      因为里面现在也住着小米超级岛这类非 ColorOS 的系统能力）----
        //   ① 流体云 + ② 侧滑跟手动画 ⇒ 本卡（系统能力适配）；
        //   ③ Shizuku / ④ Stellar / ⑤ 隐藏最近任务卡片 ⇒ 下一张「常驻后台增强」卡；
        //   ⑥ 导出日志 ⇒ 单独一张卡，放设置页最底部。
        //   ★ 同志 2026-09-22：流体云从「数据源」卡片里挪出来 —— 它不是"数据源"，而是系统能力适配。
        card = linearCard().apply {
            // ★ v2 统一：卡级不再套 padding —— 标题/行各自带 16dp 左缩进，标题左缘永远对齐
            orientation = LinearLayout.VERTICAL
        }
        addCard(card)
        card.addView(TextView(this).apply {
            text = "系统集成"
            textSize = 13f
            setTextColor(cTextSecondary)
            setPadding(0, 0, 0, (2 * dp).toInt())
        })

        // ① 流体云（原「取件码上流体云」，同志 2026-09-22 改名）
        //   机理：不是厂商私有 SDK —— Android 16 的「提升型常驻通知」在 ColorOS 上就显示为流体云。
        //   ★ 开关走 switchRow（跟快递100 一致），**不再重建整页** —— 原地改状态文案即可。
        //   ★ 点开关时：没权限则先去系统设置开通；已开通则只弹一次诊断说明。
        //   ★★ 纯 App 侧开关（同志 2026-09-22 的口径）：
        //      「就好比有一条路我可以自己选择走或不走，但不要因为我不走就把这条路破坏掉」
        //      ⇒ 关掉只是"不上报 + 撤下已发的"，**不碰系统权限、也绝不跳系统设置**。
        //      系统「提升型通知」权限归用户自己在系统设置里管，本开关不碰它。
        //   ★ 行内只留名称 + 开关（同志要求删掉右侧状态提示）。
        card.addView(switchRow("流体云", LiveUpdate.isEnabled(this),
            onMoreClick = {
                infoDialog(
                    "流体云",
                    "把未取件的取件码挂到系统「流体云 / 实况位」上，聚类成一条通知。" +
                            "需要系统设置里也允许本应用的「提升型通知」，否则只会是一条普通常驻通知。"
                )
            }) { checked, _ ->
            LiveUpdate.setEnabled(this, checked)      // 立刻生效（开=补推，关=全撤）
        })

        // ② 侧滑跟手动画（预测性返回）
        //   ★ 关掉之后：侧滑返回**不会**带着页面走，但手势本身照常可用（照样能返回）。
        //   ★ 同样只留名称 + 开关
        card.addView(switchRow("侧滑跟手动画", prefs.getBoolean(KEY_PREDICTIVE, true),
            onMoreClick = {
                infoDialog(
                    "侧滑跟手动画",
                    "侧滑返回时页面跟着手指走（系统预测性返回）。关掉则用系统默认的返回动画。"
                )
            }) { checked, _ ->
            prefs.edit().putBoolean(KEY_PREDICTIVE, checked).apply()
        })

        // ③ 超级岛·实验版（vc321 复刻；vc322/323 闸门口径终定 —— 同志 10-19 03:48 拍板：
        //   **只看设置里 Shizuku / Stellar 两个开关有没有开**，有一个开着就放行，都关着才拦。
        //   不做底层服务探测（管理器里改"拒绝"并不会收回已发授权，实测两套账，没有可读信号）。
        //   名称「实验版」；授权提示只在**点击开关那一刻**弹（appToast，圆角+反色+深浅色自适应）。
        //   ★ 机理见 IslandHelper / IslandBypass —— HyperOS3 焦点通知（miui.focus.param），
        //     仅小米/红米澎湃OS 3 会变成岛；本机能力探测不过 ⇒ 一条通知都不发（防抽屉垃圾）。
        //   ★ vc331：同志 10-19 —— 「Shizuku 绕过白名单」行并入这里：想用超级岛就必须开绕过，
        //     ⇒ 开岛即自动开绕过、关岛即自动关绕过，不再单独设开关。
        //     绕过引擎实际是**双引擎**（IslandBypass.engine(): Shizuku 优先、Stellar 自动兜底，
        //     binder 直调与 cmd 兜底两条路都有 Stellar 分支）——旧行名只写 Shizuku 是历史误导。
        card.addView(switchRow("超级岛（实验版）", IslandHelper.isEnabled(this),
            onMoreClick = {
                infoDialog(
                    "超级岛（实验版）",
                    "把取件码送到 HyperOS 超级岛（焦点通知）。实验功能：只在支持的机型上出现，" +
                            "其它设备开关打开也不会有东西。"
                )
            }) { checked, sw ->
            if (checked) {
                val engineOn = ShizukuBridge.isEnabled(this) || StellarBridge.isEnabled(this)
                Log.i("Island", "主开关闸门（设置口径）: shizuku开关=${ShizukuBridge.isEnabled(this)} " +
                        "stellar开关=${StellarBridge.isEnabled(this)}")
                if (!engineOn) {
                    sw.isChecked = false
                    appToast(
                        this,
                        "Shizuku 或 Stellar 未开启，无法开启超级岛\n先到「常驻后台增强」打开其中一个，再回来打开",
                        true
                    )
                } else {
                    IslandHelper.setEnabled(this, true)
                    // ★ vc331：绕过白名单跟岛走 —— 开岛即开、关岛即关
                    IslandHelper.setBypass(this, true)
                    if (!IslandHelper.isSupportIsland()) {
                        appToast(
                            this,
                            "已开启。这台手机检测不到超级岛，仅澎湃系统可用",
                            true
                        )
                    }
                }
            } else {
                IslandHelper.setEnabled(this, false)
                IslandHelper.setBypass(this, false)
            }
        })

        // ---- 常驻后台增强（vc288 新分类：Shizuku + Stellar + 隐藏最近任务卡片）----
        //   ★ 三项都是"给常驻保护/后台存活加料"的增强能力，语义归到同一张卡。
        card = linearCard().apply {
            // ★ v2 统一：卡级不再套 padding —— 标题/行各自带 16dp 左缩进，标题左缘永远对齐
            orientation = LinearLayout.VERTICAL
        }
        addCard(card)
        card.addView(TextView(this).apply {
            text = "后台常驻"
            textSize = 13f
            setTextColor(cTextSecondary)
            setPadding(0, 0, 0, (2 * dp).toInt())
        })

        // ③ Shizuku（同志 10-15 改成：左名称 + 右开关，**打开时校验权限**）
        //   有权限 ⇒ 正常开启并连上保护进程；没权限 ⇒ **开不了**（开关弹回 + 提示原因）。
        card.addView(switchRow("Shizuku", ShizukuBridge.isEnabled(this),
            onMoreClick = {
                infoDialog(
                    "Shizuku",
                    "借 Shizuku 的权限做两件增强：身份码页自动点按、免 root 读系统待办。" +
                            "没装 Shizuku 时全部逻辑静默降级，不影响任何基本功能。"
                )
            }) { checked, sw ->
            if (checked) {
                if (ShizukuBridge.isAvailable()) {
                    ShizukuBridge.setEnabled(this, true)
                    // interactive=true：用户主动点开关，未授权时会弹申请框
                    ShizukuBridge.bindWatchdog(this, interactive = true)
                    appToast(this, "已开启：独立进程保护连接中")
                } else {
                    // ★ 没权限就开不了：开关弹回，并如实说明原因。
                    //   ⚠️ 弹回会再触发一次 onToggle(false)，那个分支要"闭嘴"，
                    //      否则用户会看到莫名其妙的"已关闭"提示（见 suppressShizukuOffToast）。
                    suppressShizukuOffToast = true
                    sw.isChecked = false
                    val why = ShizukuBridge.blockReason()
                    Log.i("Shizuku", "开关校验未通过：$why")
                    // 已装 Shizuku 只是没授权 ⇒ 顺手发一次授权请求（由 Shizuku 那边弹框）
                    if (why.contains("授权")) {
                        try {
                            rikka.shizuku.Shizuku.requestPermission(1000)
                        } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
                    }
                    AlertDialog.Builder(this)
                        .setTitle("Shizuku 开不了")
                        .setMessage(
                            "$why。\n\n这是**可选增强**：不开它，蜉蝣照样正常跟踪快递" +
                                "（常驻服务 + 开机自启 + 通知监听/控制中心磁贴都不需要任何特权），" +
                                "只是少了\"被彻底回收后 45 秒自愈\"这一层。\n\n" +
                                "要用它，需要你自己装 Shizuku 并授权 —— 蜉蝣不会替你装、也不会引导你去装。"
                        )
                        .setPositiveButton("知道了", null)
                        .showAnimated()
                }
            } else {
                if (suppressShizukuOffToast) {
                    // 只是"开不了被迫弹回"，不是用户主动关 ⇒ 什么都不做、也不提示
                    suppressShizukuOffToast = false
                } else {
                    ShizukuBridge.setEnabled(this, false)
                    // ★ 互斥保护：两个开关共用一个保护进程 —— **两个都关了才真断**，
                    //   否则关一个会把另一个还开着的也弄断（2026-10-15 修）
                    if (!StellarBridge.isEnabled(this)) {
                        ShizukuBridge.unbindWatchdog(this)
                        appToast(this, "已关闭")
                    } else {
                        appToast(this, "Shizuku 已关（Stellar 还开着，保护继续）")
                    }
                }
            }
        })

        // ④ Stellar（同志 10-15 要求，与 Shizuku 并存；样式与上面完全一致）
        //   同志手机上只装了 Stellar（没装原版 Shizuku）⇒ 这个开关才是实际能用的那个。
        card.addView(switchRow("Stellar", StellarBridge.isEnabled(this),
            onMoreClick = {
                infoDialog(
                    "Stellar",
                    "Shizuku 的深度定制分支，机制同源。装了哪个用哪个；两个都没装则全部静默降级。"
                )
            }) { checked, sw ->
            if (checked) {
                if (StellarBridge.isAvailable()) {
                    StellarBridge.setEnabled(this, true)
                    // interactive=true：用户主动点开关，未授权时会弹申请框
                    StellarBridge.bindWatchdog(this, interactive = true)
                    appToast(this, "已开启：Stellar 连接中")
                } else {
                    suppressStellarOffToast = true
                    sw.isChecked = false
                    val why = StellarBridge.blockReason()
                    Log.i("Stellar", "开关校验未通过：$why")
                    if (why.contains("授权")) {
                        try {
                            roro.stellar.Stellar.requestPermission(requestCode = 2000)
                        } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
                    }
                    AlertDialog.Builder(this)
                        .setTitle("Stellar 开不了")
                        .setMessage(
                            "$why。\n\n这是**可选增强**：不开它，蜉蝣照样正常跟踪快递" +
                                "（常驻服务 + 开机自启 + 通知监听/控制中心磁贴都不需要任何特权），" +
                                "只是少了\"被彻底回收后 45 秒自愈\"这一层。\n\n" +
                                "要用它，需要你自己装 Stellar 管理器并授权 —— 蜉蝣不会替你装、也不会引导你去装。"
                        )
                        .setPositiveButton("知道了", null)
                        .showAnimated()
                }
                } else {
                    if (suppressStellarOffToast) {
                        suppressStellarOffToast = false
                    } else {
                        StellarBridge.setEnabled(this, false)
                        // ★ 互斥保护：同上 —— 两个都关了才真断
                        if (!ShizukuBridge.isEnabled(this)) {
                            StellarBridge.unbindWatchdog(this)
                            appToast(this, "已关闭")
                        } else {
                            appToast(this, "Stellar 已关（Shizuku 还开着，保护继续）")
                        }
                    }
                }
        })

        // ⑤ 隐藏最近任务卡片（vc264h，同志 10-15 要求：**可自己开关、默认不隐藏**）
        //   机理：ActivityManager.AppTask.setExcludeFromRecents() —— 官方**运行时**接口，
        //   打开 = 卡片从最近任务消失（想切回 App 请从桌面图标进）；关闭 = 立刻恢复显示。
        //   ★ 不用 Manifest 的 android:excludeFromRecents —— 那是永久性的、装完定死，
        //     做不成"想开就开、想关就关"的开关。
        card.addView(switchRow("隐藏最近任务卡片", prefs.getBoolean("hide_recents", false),
            onMoreClick = {
                infoDialog(
                    "隐藏最近任务卡片",
                    "让本应用不出现在系统「最近任务」里，避免被一键清理顺手划掉（后台保活的一环）。"
                )
            }) { checked, _ ->
            prefs.edit().putBoolean("hide_recents", checked).apply()
            applyHideRecents(checked)
            appToast(
                this,
                if (checked) "已隐藏（切回请从桌面图标进）" else "已恢复显示在最近任务"
            )
        })

        // ---- 桌面小组件（vc289，同志 10-16 要求：材质/不透明度自定义，借鉴南风天气）----
        card = linearCard().apply {
            // ★ v2 统一：卡级不再套 padding —— 标题/行各自带 16dp 左缩进，标题左缘永远对齐
            orientation = LinearLayout.VERTICAL
        }
        addCard(card)
        card.addView(TextView(this).apply {
            text = "桌面小组件"
            textSize = 13f
            setTextColor(cTextSecondary)
            setPadding(0, 0, 0, (2 * dp).toInt())
        })
        card.addView(sourceRow("常规小组件", "自定义", true) { openWidgetStylePage() })
        card.addView(sourceRow("鲸鱼娘组件", "装扮自选", true) { openWhaleStylePage() })

        // ---- 导出日志（vc288，同志 10-16 要求：单独列卡、放设置页最底部）----
        // ★ vc328：删标题「日志」两字
        // ★ vc330：同志 10-19 标注图澄清 —— 他说的「宽」= 短的那条边 = 卡片**纵向厚度**（Y），
        //   横向那条是「长」。vc329 把横向缩窄是缩错了方向 ⇒ 横向恢复撑满（回 addCard），
        //   纵向缩 1/3：卡 padding 上下 16→8dp + 行 padding 上下 14→9dp（78dp → 52dp）。
        //   sourceRow 是共用件不能动，这里自建紧凑行（结构同 sourceRow：名 + 弹性 + 状态）。
        card = linearCard().apply {
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        }
        addCard(card)
        // ★ vc695 新增：权限与保活 —— 三个系统页跳转入口（通知使用权 / 电池优化白名单 / 特殊权限）
        run {
            // ★ vc707：排版与其它卡统一 —— 分类标题 13sp；三个入口 + 一键申请全部走 trackRow
            val permCard = linearCard().apply { orientation = LinearLayout.VERTICAL }
            permCard.addView(sectionTitle("权限与保活"))
            permCard.addView(hintLine(
                "通知使用权用于从通知里抓取件码；电池优化白名单与特殊权限用于后台保活。"
            ))
            // ① 通知使用权（系统「通知使用权」列表页）
            // ★★ v1.4.17：这一整卡原来有个通病 —— **点了没反应**。三处根因：
            //   · 通知使用权：跳转失败只打日志（除第一个 catch 外没有 toast）；
            //   · 电池优化白名单：`if (batteryExempt()) return` —— 已加入后彻底静默；
            //   · 一键申请：通知已开时就直奔电池，电池也已加入 ⇒ 一路 return。
            //   现在每行点下去都有 toast + 状态文字刷新，不再出现"点了没动静"。
            val permRow1 = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            val tvNotifyStatus = TextView(this).apply {
                text = if (notifListenerGranted()) "已开启" else "去系统设置 ›"
                textSize = 13f
                setTextColor(if (notifListenerGranted()) cTextSecondary else cTextTertiary)
            }
            val btnNotifyListener: View = trackRow("通知使用权", tvNotifyStatus) {
                val ok = openFirst(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                appToast(
                    this,
                    if (ok) "在列表里找到本应用，勾选「允许通知使用权」" else "打不开通知使用权设置",
                    true
                )
                mainHandler.postDelayed(
                    { refreshPermRowTexts(tvNotifyStatus, null, null) }, 1_200L
                )
            }
            permRow1.addView(btnNotifyListener, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            // ② 电池优化白名单（部分 ROM 不认 ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS ⇒ 退回列表页）
            // ★★ v1.4.17：**已加入时不再静默返回**。旧代码第一行 `if (batteryExempt()) return`
            //   就结束了，用户点的是一张「已加入」的行 —— 一个像素的反馈都没有，看起来就是坏的。
            //   现在改成打开系统「电池优化」列表页（可以核对，也能把本应用移出去重加），并给 toast。
            val tvBattStatus = TextView(this).apply {
                text = if (batteryExempt()) "已加入" else "去添加 ›"
                textSize = 13f
                setTextColor(if (batteryExempt()) cTextSecondary else cTextTertiary)
            }
            val btnBatteryOpt: View = trackRow("电池优化白名单", tvBattStatus) {
                Log.i("Perm", "点电池优化白名单：当前已加入=${batteryExempt()}")
                if (batteryExempt()) {
                    val ok = openFirst(
                        Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                        Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS),
                        appDetailsIntent()
                    )
                    appToast(
                        this,
                        if (ok) "已在白名单中，已打开系统列表供核对" else "已在白名单中（系统列表打不开）",
                        true
                    )
                } else {
                    requestBatteryExemption()
                }
                mainHandler.postDelayed(
                    { refreshPermRowTexts(null, tvBattStatus, null) }, 1_200L
                )
            }
            permRow1.addView(btnBatteryOpt, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            permCard.addView(permRow1, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (UiTok.S4 * dp).toInt() })
            // ③ 后台运行 / 自启动（文案长，单独一行放，免得和上面两个挤一行被裁掉）
            // ★★ v1.4.17：**原来跳的是悬浮窗权限** —— ACTION_MANAGE_OVERLAY_PERMISSION 是
            //   「显示在其他应用上层」，而本应用连 SYSTEM_ALERT_WINDOW 都没在 Manifest 里声明，
            //   跳过去是一页跟后台保活毫无关系的设置（同志报"后台运行权限跳转的是悬浮窗权限"）。
            //   现在按 ROM 找真正的自启动管理页：ColorOS/一加 → MIUI → 华为/荣耀 → vivo → 三星，
            //   全部不认时由 romAutoStartIntents() 末尾兜到「应用信息」页（那里能进电池/自启动）。
            val permRow2 = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            val btnSpecialAccess: View = trackRow(
                "后台运行 / 自启动",
                TextView(this).apply {
                    text = "去系统设置 ›"
                    textSize = 13f
                    setTextColor(cTextTertiary)
                }
            ) {
                when (openAutoStartPage()) {
                    "startup" -> appToast(this, "在「自启动」列表里找到本应用并允许", true)
                    "details" -> appToast(this, "进「耗电管理」→ 选「允许后台活动」", true)
                    else -> appToast(this, "打不开系统设置，请在系统设置里手动允许后台运行", true)
                }
            }
            permRow2.addView(btnSpecialAccess, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            permCard.addView(permRow2, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            // ★ vc698：状态行 + 一键申请（vc707 起排版对齐：状态用 12sp 三级灰说明行，
            //   一键申请用标准行 + 右侧状态，不再是小胶囊）
            val permStatus = hintLine(permStatusLine())
            permCard.addView(permStatus, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (UiTok.S4 * dp).toInt() })
            // ★★ v1.4.17：文案从"通知 + 电池白名单"改成三项 —— 一键申请现在真的会把
            //   通知使用权（从通知抓取件码的关键）也纳进体检，见 requestCorePermissions()。
            val btnRequestCore: View = trackRow(
                "一键申请权限",
                TextView(this).apply {
                    text = "通知 + 电池 + 通知使用权"
                    textSize = 13f
                    setTextColor(cTextTertiary)
                }
            ) {
                try {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putLong("perm_prompt_at", System.currentTimeMillis()).apply()
                    requestCorePermissions()
                    mainHandler.postDelayed(
                        { refreshPermRowTexts(tvNotifyStatus, tvBattStatus, permStatus) }, 2_500L
                    )
                } catch (t: Throwable) {
                    SafeRun.w("Perm", t)
                }
            }
            permCard.addView(btnRequestCore, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addCard(permCard)
        }
        // ★ vc695 新增：关于应用 —— 版本号运行时读 PackageInfo，不硬编码
        run {
            // ★ vc707：排版与其它卡统一 —— 分类标题 13sp 次级色；两行信息走标准行规格
            val aboutCard = linearCard().apply { orientation = LinearLayout.VERTICAL }
            aboutCard.addView(sectionTitle("关于应用"))
            val aboutInfo = try {
                packageManager.getPackageInfo(packageName, 0)
            } catch (t: Throwable) {
                SafeRun.w("MainActivity", t)
                null
            }
            val aboutAppName = try {
                val label = aboutInfo?.applicationInfo?.loadLabel(packageManager)?.toString()
                if (label.isNullOrBlank()) getString(R.string.app_name) else label
            } catch (t: Throwable) {
                SafeRun.w("MainActivity", t)
                "蜉蝣·取件通"
            }
            val aboutVersion = try {
                if (aboutInfo == null) {
                    "版本未知"
                } else {
                    val vName = aboutInfo.versionName ?: "?"
                    val vCode: Long = try {
                        aboutInfo.longVersionCode
                    } catch (t: Throwable) {
                        aboutInfo.versionCode.toLong()
                    }
                    "$vName（内部版本 $vCode）"
                }
            } catch (t: Throwable) {
                SafeRun.w("MainActivity", t)
                "版本未知"
            }
            aboutCard.addView(infoRow(aboutAppName, aboutVersion))
            aboutCard.addView(infoRow("包名", packageName))
            addCard(aboutCard)
        }
        // ★ vc415：新增「其他」分类（设置页最底部），导出日志归入该分类
        // ★ vc600：同志要求 —— 「其他」支持**折叠/展开**（默认展开，状态记忆到 SP）
        val otherCollapsed0 = prefs.getBoolean("settings_other_collapsed", false)
        val otherHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(0, (UiTok.S8 * dp).toInt(), 0, (UiTok.S8 * dp).toInt())
        }
        otherHeader.addView(TextView(this).apply {
            text = "其他"
            textSize = UiTok.T13
            setTextColor(cTextSecondary)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val otherArrow = TextView(this).apply {
            text = "\u2304"   // ⌄
            textSize = UiTok.T15
            setTextColor(cTextTertiary)
            rotation = if (otherCollapsed0) 180f else 0f
        }
        otherHeader.addView(otherArrow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = (UiTok.S8 * dp).toInt() })
        val otherContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (otherCollapsed0) View.GONE else View.VISIBLE
        }
        otherHeader.setOnClickListener {
            val show = otherContent.visibility != View.VISIBLE
            animateHeight(otherContent, show)
            otherArrow.animate().rotation(if (show) 0f else 180f)
                .setDuration(UiTok.D190).start()
            prefs.edit().putBoolean("settings_other_collapsed", !show).apply()
            Log.i("Settings", "其他分类展开=$show")
        }
        card.addView(otherHeader)
        card.addView(otherContent)
        val logRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // ★ vc702：与其它行统一到 S16
            setPadding((UiTok.S16 * dp).toInt(), (9 * dp).toInt(),
                       (UiTok.S16 * dp).toInt(), (9 * dp).toInt())
            isClickable = true
            // ★ vc501：改走「4 档时间范围 → zip」的导出（旧 exportLogs 只导 logcat 缓冲区，已弃用）
            setOnClickListener { exportLogsRange() }
        }
        logRow.addView(TextView(this).apply {
            text = "导出日志"
            textSize = 15f
            setTextColor(cTextPrimary)
        })
        // ★ vc510：隐私状态灯**紧贴「导出日志」四个字**（不是贴"设置"）—— 开=绿，关=红
        val privDot = View(this).apply {
            val col = if (logPrivacyOn()) 0xFF22C55E.toInt() else 0xFFEF4444.toInt()
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(col)
            }
        }
        logRow.addView(privDot, LinearLayout.LayoutParams(
            (8 * dp).toInt(), (8 * dp).toInt()
        ).apply { leftMargin = (6 * dp).toInt() })
        logRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        // ★ vc509：「设置」入口 —— 点它弹「日志隐私模式」开关（行本身仍是导出）
        logRow.addView(TextView(this).apply {
            text = "设置"
            textSize = 13f
            setTextColor(cTextSecondary)
            setPadding((10 * dp).toInt(), (4 * dp).toInt(), (2 * dp).toInt(), (4 * dp).toInt())
            isClickable = true
            setOnClickListener { showLogPrivacyDialog() }
        })
        applyPress(logRow)
        otherContent.addView(logRow)
        // ★ vc553：黑名单（删除过的快递都在这里，可移出）
        // ★ vc694：入口条数只算黑名单本身；永久隐藏的单独提示，别混成一个数糊弄人
        val blCount = pickupMutedOnly().size
        val hiddenCount = MuteStore.hiddenCount(this)
        otherContent.addView(sourceRow(
            "黑名单",
            when {
                blCount > 0 && hiddenCount > 0 -> "$blCount 个 · 隐藏 $hiddenCount · 点击管理"
                blCount > 0 -> "$blCount 个 · 点击管理"
                hiddenCount > 0 -> "隐藏 $hiddenCount · 点击管理"
                else -> "暂无"
            },
            true
        ) { openBlacklistPage() })
        // ★ vc417：「其他」里加免责声明（文案由同志定稿 2026-10-25）
        otherContent.addView(sourceRow("免责声明", "查看", true) { showDisclaimer() })

        // ⑥ 导出日志（vc268，同志 10-15 要求：导出 App 全部日志 + 自定义保存位置）
        //   机理：① `logcat -d` 一次性读出缓冲区里本应用的日志 —— Android 4.1 起 App
        //   只能读到**自己 UID** 的日志 ⇒ 天然就是"蜉蝣的全部日志"，零权限；
        //   ② 保存位置走系统「另存为」对话框（ACTION_CREATE_DOCUMENT）—— 同志自己挑
        //   目录和文件名（下载/文档/任何地方都行），蜉蝣不申请任何存储权限。
        //   ★ vc330：改用上面的自建紧凑行 logRow（vc328 前是 sourceRow —— 共用件行高定死，
        //   撑不住"高度缩 1/3"的需求），点击 exportLogs() 弹另存为。

        root.addView(layer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        Log.i("SetPerf", "built -> addView done")
        settingsLayer = layer
        // ★ 侧滑：从右往左推进来
        slideIn(layer)
    }

    private fun closeSettings() {
        // 组件设置子页压在设置页上 ⇒ 一并收掉（不播动画，反正在滑出的层底下）
        if (blacklistLayer != null) {
            root.removeView(blacklistLayer)
            blacklistLayer = null
        }
        if (widgetStyleLayer != null) {
            root.removeView(widgetStyleLayer)
            widgetStyleLayer = null
        }
        if (whaleStyleLayer != null) {
            root.removeView(whaleStyleLayer)
            whaleStyleLayer = null
        }
        val layer = settingsLayer ?: return
        // ★ 防重入：收拢动画期间连点返回会重复 removeView（直接崩）
        if (settingsClosing) return
        settingsClosing = true
        // ★★ v1.4.9：**硬兜底**。原来只靠 `withEndAction` 收尾：动画被打断
        //   （视图重建 / 又开了一次动画 / 层被别处 addView 覆盖）时 endAction 永远不跑
        //   ⇒ settingsLayer 与 settingsClosing 双双卡住 ⇒ "退出来就再也进不去"。
        //   这里再加一条 400ms 的强制收尾，幂等，不会重复 removeView。
        mainHandler.postDelayed({
            if (layer.parent === root) {
                Log.w("SetPerf", "closeSettings 硬兜底：动画未回调，强制移除")
                try { root.removeView(layer) } catch (t: Throwable) { SafeRun.w("SetPerf", t) }
            }
            if (settingsLayer === layer) settingsLayer = null
            settingsClosing = false
        }, 400)
        // ★ 从左往右滑出 —— 动画播完再真正移除，否则会看到"啪"地消失
        slideOut(layer) {
            root.removeView(layer)
            settingsLayer = null
            settingsClosing = false
        }
    }

    private fun makeGap(h: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, (h * dp).toInt())
    }

    /** 需要登录/失败时的可操作卡片（点击即去对应登录入口） */
    private fun makeLoginCard(title: String, btnText: String, onClick: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt())
        }
        box.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(cTextPrimary)
            setLineSpacing(0f, 1.25f)
        })
        box.addView(Button(this).apply {
            text = btnText
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (10 * dp).toInt() })
        val bg = linearCardBg(12)
        val wrap = FrameLayout(this).apply {
            background = bg
            if (!isDark()) elevation = 2 * dp
        }
        wrap.addView(box, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        wrap.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return wrap
    }

    /** 合并渲染：菜鸟 + 拼多多两路，按当前模式取舍 */
    /**
     * 取件码「常态显示 + 已取出自动消除」—— **三源通用**（2026-09-20 举一反三：
     * 菜鸟/京东与拼多多有同样的"易失"隐患，一份实现管三源，缓存键 = `src:单号`）。
     *
     * ★ vc316：**只消除、不打标记**。源明确说这单不在它那儿 ⇒ 把码从栏里拿掉；
     *   「已取件」灰标只认用户长按手动打（见 syncPickupCodeCache ① 段的实现）。
     *
     * 消除信号按源取**各自最可靠**的那个：
     *  · pdd     ：`express/track/status` 该单状态 ≠ IN_CABINET（已取走）
     *  · cainiao ：`queryMultiStationPackages` **只返回在驿站的** ⇒ 本次抓取成功（无错）时，
     *              缓存里的单不在返回集合 = 已取出
     *  · jd      ：本次抓取成功时不在京东单集合（parse 已把终态滤掉）= 已取出/已完成
     * 对应源本次抓取**失败**（err != null）时**不动缓存** —— 失败不代表取走了。
     */
    // ★★ vc620：黑名单统一走 [MuteStore]（与后台通知共用一份存储，见 MuteStore 注释）
    // ★★ v1.4.9：**改成"按包裹记录"模型**。这里返回的是整份记录的身份 token 集合，
    //   只用于日志/粗筛与"这件是不是被我删过"的快速判断；
    //   真正的判定（带身份强度与时间窗）在 [isPickupMuted] → [MuteStore.blocks] 里做。
    private fun pickupMutedAll(): MutableSet<String> {
        val s = LinkedHashSet<String>()
        for (e in MuteStore.entries(this)) {
            if (e.orderSn.isNotBlank()) s.add(e.orderSn)
            if (e.waybill.isNotBlank()) s.add(e.waybill)
            if (e.code.isNotBlank()) s.add(e.code)
        }
        return s
    }

    /** ★ v1.4.9：真正的黑名单记录（不含永久隐藏）—— 黑名单页列表、条数、逐条移出都用它 */
    private fun pickupMutedOnly(): List<MuteStore.Entry> = MuteStore.active(this)

    /** ★ v1.4.9：一条记录 → 给人看的来源名（标题为空时兜底用） */
    private fun srcDisplayName(src: String): String = when (src) {
        "pdd" -> "拼多多"; "cainiao" -> "菜鸟"; "jd" -> "京东"
        "mi" -> "小米"; "manual" -> "手动单号"; "tb" -> "淘宝"
        "" -> "包裹"
        else -> src
    }

    // ---------------- ★ vc553：黑名单页面（设置 → 其他 → 黑名单） ----------------

    private var blacklistLayer: android.view.View? = null

    /**
     * ★★ vc694（同志 2026-11-04 要求）：黑名单页重做 + 底部两个胶囊按钮。
     *
     * · **全部清除 = 永久隐藏**：这批键搬进 `MuteStore` 的 `hide_keys`，
     *   之后任何来源再返回都不显示（渲染/写缓存都走 `pickupMutedAll()` 的并集）。
     * · **全部移除 = 正常移除**：只清 `mute_keys`，平台还返回就会重新出现。
     * · 两个胶囊 56dp 高、圆角 28dp、离底 48dp —— 与取件码页底部「淘/拼/菜」同尺寸口径。
     */
    private fun openBlacklistPage() {
        if (blacklistLayer != null) return
        val layer = FrameLayout(this).apply { setBackgroundColor(cBg) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // 顶栏：返回 + 标题 + 条数
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * dp).toInt(), (statusBarTop + (6 * dp).toInt()), (8 * dp).toInt(), (6 * dp).toInt())
        }
        bar.addView(TextView(this).apply {
            text = "‹"
            textSize = 24f
            setTextColor(cTextPrimary)
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
            setOnClickListener { closeBlacklistPage() }
        })
        bar.addView(TextView(this).apply {
            text = "黑名单"
            textSize = UiTok.T17
            setTextColor(cTextPrimary)
            setTypeface(tfMedium)
            setPadding((4 * dp).toInt(), 0, 0, 0)
        })
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val countTv = TextView(this).apply {
            textSize = UiTok.T13
            setTextColor(cTextSecondary)
        }
        bar.addView(countTv)
        column.addView(bar)

        // ★★ vc695（同志 2026-11-04 15:09 定稿）：
        //   全部删除 = 永久隐藏，且**没有后悔药**（只能清 App 数据才恢复）；
        //   弹窗用项目自绘圆角样式，左下「否」、右下「是」。
        val clearAll: () -> Unit = {
            val keys = pickupMutedOnly()
            if (keys.isEmpty()) {
                appToast(this, "黑名单已经是空的")
            } else {
                showDangerDialog(
                    title = "全部删除",
                    message = "删除仅能通过清除APP数据才能恢复，是否删除？"
                ) {
                    MuteStore.hideAll(this)
                    appToast(this, "已删除 ${keys.size} 条")
                    Log.i("Blacklist", "全部删除=永久隐藏 ${keys.size} 条")
                    rebuildBlacklist()
                }
            }
        }
        val removeAll: () -> Unit = {
            val keys = pickupMutedOnly()
            if (keys.isEmpty()) {
                appToast(this, "黑名单已经是空的")
            } else {
                android.app.AlertDialog.Builder(this)
                    .setTitle("全部移除")
                    .setMessage(
                        "把这 ${keys.size} 条从黑名单移除。\n\n" +
                                "如果平台仍然返回这些件，它们会重新出现在在途快递或取件码里。"
                    )
                    .setPositiveButton("全部移除") { _, _ ->
                        MuteStore.clear(this)
                        appToast(this, "已移除 ${keys.size} 条")
                        Log.i("Blacklist", "全部移除 ${keys.size} 条")
                        rebuildBlacklist()
                    }
                    .setNegativeButton("取消", null)
                    .showAnimated()
            }
        }

        // —— 列表 ——
        val scroll = android.widget.ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 底部留 132dp：56dp 胶囊 + 48dp 离底 + 28dp 呼吸，避免最后一张卡被按钮压住
            setPadding((16 * dp).toInt(), (4 * dp).toInt(), (16 * dp).toInt(), (132 * dp).toInt())
        }
        scroll.addView(col)
        column.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        layer.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // —— 底部悬浮胶囊 ——
        fun pill(text: String, brandHex: String, action: () -> Unit): TextView {
            val brand = Color.parseColor(brandHex)
            return TextView(this).apply {
                this.text = text
                textSize = UiTok.T15
                typeface = tfMedium
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(brand)
                setPadding((26 * dp).toInt(), 0, (26 * dp).toInt(), 0)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = (28 * dp)
                    setColor(UiTok.brandWithAlpha(brand, UiTok.brandTileAlpha(isDark())))
                    setStroke(
                        maxOf(1, (UiTok.HAIR * dp).toInt()),
                        if (isDark()) 0x2EFFFFFF else 0x14000000
                    )
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
        }
        val clearPill = pill("全部删除", "#D93025") { clearAll() }
        val removePill = pill("全部移除", ACCENT_HEX) { removeAll() }
        applyPress(clearPill, removePill)
        val dockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (UiTok.S8 * dp).toInt(), 0, (48 * dp).toInt())
        }
        fun pillLp() = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, (56 * dp).toInt()
        ).apply {
            marginStart = (6 * dp).toInt()
            marginEnd = (6 * dp).toInt()
        }
        dockRow.addView(clearPill, pillLp())
        dockRow.addView(removePill, pillLp())
        val dock = FrameLayout(this).apply {
            isClickable = false
            isFocusable = false
        }
        dock.addView(dockRow, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ))
        layer.addView(dock, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ))

        root.addView(layer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        blacklistLayer = layer

        fun fill() {
            col.removeAllViews()
            val keys = pickupMutedOnly().sortedByDescending { it.ts }
            countTv.text = if (keys.isEmpty()) "" else "${keys.size} 件"
            val hasAny = keys.isNotEmpty()
            listOf(clearPill, removePill).forEach { it.alpha = if (hasAny) 1f else 0.35f }

            // 说明卡：讲清两个动作的差别 + 新模型（同志 11-10 要求重新设计的原因）
            col.addView(linearCard(UiTok.R14).apply {
                setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
                addView(TextView(this@MainActivity).apply {
                    text = "长按「在途快递」或「取件码」里的卡片 → 删除，就会加到下面这个名单。\n\n" +
                            "一条记录 = 一个包裹（订单号 + 运单号 + 取件码 一起存），\n" +
                            "不会再把一个件拆成三条记。\n" +
                            "取件码/运单号会被复用：只有 7 天内的取件码（且同一驿站）、" +
                            "60 天内的运单号才认，以后的新件不会被误伤。\n\n" +
                            "全部删除：永久删除，只能清除 App 数据才能恢复。\n" +
                            "全部移除：正常移除，平台有返回就会重新出现。"
                    textSize = UiTok.T13
                    setTextColor(cTextSecondary)
                    setLineSpacing((5 * dp).toFloat(), 1f)
                })
            })
            if (keys.isEmpty()) {
                col.addView(TextView(this).apply {
                    text = "名单是空的。"
                    textSize = UiTok.T15
                    setTextColor(cTextSecondary)
                    setPadding((6 * dp).toInt(), (22 * dp).toInt(), (6 * dp).toInt(), 0)
                })
            }
            for (e in keys) {
                val card = linearCard(UiTok.R14).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((16 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
                }
                val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                // 主行 = 商品标题（拿不到标题才退回单号尾号）
                textCol.addView(TextView(this).apply {
                    text = e.displayTitle()
                    textSize = UiTok.T15
                    setTextColor(cTextPrimary)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                // 次行 = 来源 + 各身份（订单号/运单号/取件码/驿站）
                val ids = e.identityLine()
                // ★★ v1.4.12：标记「已取件」写进来的记录要标清去向 ——
                //   holdUntil 未到 = 卡片还在列表里给他核对，到点才真正拦；
                //   否则用户会奇怪"我明明标了已取件，它怎么还在黑名单里"。
                val pickedNote = when {
                    // id 以 `picked:` 开头的是「标记已取件」/「清已取」写进来的（长按删除的是 m…）
                    !e.id.startsWith("picked:") -> ""
                    e.holdUntil > System.currentTimeMillis() ->
                        "已取件·剩 ${((e.holdUntil - System.currentTimeMillis()) / TimeGates.HOUR_MS) + 1}h"
                    else -> "已取件·已撤"
                }
                textCol.addView(TextView(this).apply {
                    text = listOf(srcDisplayName(e.src), ids, pickedNote)
                        .filter { it.isNotBlank() }.joinToString(" · ")
                    textSize = UiTok.T11
                    setTextColor(cTextTertiary)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, (4 * dp).toInt(), 0, 0)
                })
                card.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                card.addView(TextView(this).apply {
                    text = "移出"
                    textSize = UiTok.T13
                    setTextColor(cAccent)
                    setPadding((12 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt())
                    setOnClickListener {
                        MuteStore.remove(this@MainActivity, e.id)
                        appToast(this@MainActivity, "已移出：${e.displayTitle()}")
                        Log.i("Blacklist", "移出记录 id=${e.id} ${e.src}/${e.orderSn}/${e.waybill}/${e.code}")
                        fill()
                    }
                })
                col.addView(card, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (8 * dp).toInt() })
            }
            // ★ vc695：按同志要求**去掉"已永久隐藏 N 个 / 全部恢复"** ——
            //   删除就是删除（弹窗里已明确"只能清除 App 数据才能恢复"），页面不再给后悔入口。
        }
        rebuildBlacklist = { fill() }
        fill()
        Log.i(
            "Blacklist",
            "打开黑名单页：记录 ${pickupMutedOnly().size} 条 / 永久隐藏 ${MuteStore.hiddenCount(this)} 条"
        )
        Log.i("Blacklist", "名单明细：" + MuteStore.dump(this))
    }

    private var rebuildBlacklist: (() -> Unit)? = null

    private fun rebuildBlacklist() {
        rebuildBlacklist?.invoke()
    }

    private fun closeBlacklistPage() {
        blacklistLayer?.let { root.removeView(it) }
        blacklistLayer = null
    }

    /**
     * ★★ v1.4.9：**删除一个包裹 = 写一条记录**（不再是往名单里塞三个互不相干的键）。
     *
     * 三个键各写各的后果：取件码/运单号被复用后，新件会撞上旧键被误伤
     * （真机实证 2026-11-10：在途 11 件被名单剔掉 3 件，含用户要找的「化妆棉」）。
     * 现在把同一件的订单号 + 运单号 + 取件码 + 驿站 + 商品标题**捆成一条**，
     * 判定时按身份强度分级（见 [MuteStore.blocks]）。
     */
    private fun mutePackage(src: String, pkg: JSONObject) {
        val station = pkg.optString("stationText")
            .ifEmpty { pkg.optString("stationName") }
            .ifEmpty { pkg.optString("nickName") }
        val waybill = pkg.optString("realWaybill").ifEmpty { pkg.optString("waybillNo") }
            .ifEmpty { pkg.optString("mailNo") }
        MuteStore.addEntry(
            this,
            MuteStore.Entry(
                id = "e" + System.currentTimeMillis(),
                src = src,
                orderSn = pkg.optString("orderSn"),
                waybill = waybill,
                code = pkg.optString("pickupCode"),
                station = station,
                title = pkg.optString("goodsName"),
                ts = System.currentTimeMillis()
            )
        )
        Log.i(
            "MuteStore",
            "删除并入名单：src=$src sn=${pkg.optString("orderSn")} wb=$waybill " +
                    "code=${pkg.optString("pickupCode")} goods=${pkg.optString("goodsName").take(16)}"
        )
    }

    /**
     * ★★ vc670：退出/删除某个拼多多账号时，把**这个账号留下的取件码**一并清掉。
     *
     * 旧行为只删账号条目（`PddAccounts.remove`），`PddCodeStore` 里 `account=副号1` 的缓存
     * 原样留着；而且同一件在菜鸟源里还有一份 `account=""` 的副本（键是运单号/取件码）。
     * 结果：副号都退出了，取件码仍挂在页面上（2026-11-02 用户实测）。
     *
     * 做法：先收该账号名下所有身份（订单号/运单号/取件码），再按身份清**所有来源**的副本。
     * @return 清掉的缓存条数
     */
    private fun purgeAccountCodes(label: String): Int {
        if (label.isBlank()) return 0
        val snapshot = PddCodeStore.all(this)
        val owned = snapshot.filterValues { it.account == label }
        if (owned.isEmpty()) {
            Log.i("AcctScope", "退出 $label：名下没有取件码缓存")
            return 0
        }
        val ids = LinkedHashSet<String>()
        owned.forEach { (k, c) ->
            if (c.code.isNotBlank()) ids.add(c.code)
            if (c.waybill.isNotBlank()) ids.add(c.waybill)
            k.substringAfter(':').takeIf { it.isNotBlank() }?.let { ids.add(it) }
        }
        var n = 0
        for ((k, c) in snapshot) {
            val hit = c.account == label ||
                    k.substringAfter(':') in ids || c.code in ids || c.waybill in ids
            if (hit) {
                PddCodeStore.remove(this, k)
                n++
            }
        }
        // 内存里的卡也要一起撤（否则重画时旧 JSON 对象还在，看着像"没清掉"）
        val gone = pddPkgs.removeAll { q ->
            q.optString("account") == label ||
                    q.optString("pickupCode") in ids ||
                    q.optString("waybill") in ids ||
                    q.optString("waybillNo") in ids
        }
        for (w in ids) pddShortByWb.remove(w)
        Log.i("AcctScope", "退出 $label：清掉取件码缓存 $n 条（身份 ${ids.size} 个，内存卡撤 $gone）")
        return n
    }

    /**
     * ★ vc223：拼多多取件码的**保鲜期**（天）—— 消除信号的兜底。
     *
     * 为什么需要：消除本该只信「已取出」，但**已取走 / 已完结的老单，服务端
     * express/track/status 根本不再返回它**（2026-10-11 真机取证：track 只回了 3 个近期单，
     * 而缓存里 3 条旧码对应的单一个都不在里面）⇒ 那些码永远等不到"已取出"，就永久显示
     * —— 这正是同志报的「取件码有部分是很久之前的」。
     *
     * ⇒ 状态查得到 ⇒ 信状态（在驿站待取的单不受影响）；
     *   状态查不到 ⇒ 说明服务端已不管这单，按**下单日**（order_sn 前 6 位 = YYMMDD）判保鲜期。
     *
     * ★ 阈值取 7 天（2026-10-11 真机定标）：那天缓存里是
     *   260923(18 天) / 260926(15 天) 两个老码 + 261009(2 天，IN_CABINET 正常待取)。
     *   阈值 20 天把它们全放过了；驿站件一般 3~7 天必取 ⇒ 取 7 天既能清掉 15/18 天的旧码，
     *   又不会碰 2 天那种正常待取的。
     *
     * ★ 日期解析不出 ⇒ **不消除**（保守，宁可多显示）。不用缓存 ts 兜底：ts 只在码变化时才更新，
     *   长期待取的件 ts 会一直很老，拿它判会误删。
     */
    private val PDD_CODE_KEEP_DAYS = TimeGates.PDD_CODE_KEEP_DAYS

    /**
     * ★ vc229 验证用：为 true 时**不许**撤销「已取件」标记。
     *
     * 为什么要它：正常逻辑里，若下一轮抓取发现这单其实还在待取（状态回退/上次误判），
     * 会把标记撤掉 —— 这是必须的自愈。但这样一来，调试开关刚打上的标记一刷新就没了，
     * 真机上根本看不到「已取件」的卡片长什么样。只在 adb 显式传 debug_lock_picked 时置位。
     */
    private var debugLockPicked = false
    /** --ez debug_dump_tb true：淘宝 queryMultiStationPackages 原始返回落盘（vc268c 翻口袋用） */
    private var debugDumpTb = false
    /** --es debug_tb_api <接口名>：A 路探测——用同一旁听页试调任意淘宝 mtop 接口（vc268d） */
    private var debugTbApi: String? = null
    /** --es debug_tb_data '<json>'：配合 debug_tb_api 的请求参数 */
    private var debugTbData: String? = null

    /**
     * ★★ v1.4.13：**一次性清洗「自动打标」的历史残留**（同志 2026-11-10 13:31）。
     *
     * 旧版开着「自动打已取标签」时，拼多多枚举 SIGN / OUT_CABINET（= 站点代签）
     * 会被写成 `pickedAt`，于是缓存里堆着一批"平台说已签收、其实我还没取"的伪已取件：
     * 它们既不在取件码页待取、也不在在途栏，还会把「清已取」的计数撑大。
     *
     * 判据很简单：**手动标记的都记在 [PickedStore] 里**（`picked:` 那条路一定写它），
     * 只写 `pickedAt` 没进 PickedStore 的，就是自动打标的 —— 把标记清掉，让它回到待取。
     * 只跑一次（SP 打标记），且不动缓存里的码本身。
     */
    private fun cleanAutoPickedOnce() {
        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (sp.getBoolean("auto_picked_cleaned_v1413", false)) return
        var n = 0
        try {
            for ((k, c) in PddCodeStore.all(this)) {
                if (c.pickedAt == 0L) continue
                val ids = listOf(k.substringAfter(':'), c.waybill, c.code)
                    .filter { it.isNotBlank() }
                if (ids.isNotEmpty() && PickedStore.of(this, ids) > 0L) continue
                // ⚠️ 必须走 putForce：[PddCodeStore.put] 对 pickedAt=0 有"沿用旧值"的保护，
                //    用它清标记会一声不响地失败（vc655 那条保护是为了防抓取回写抹掉标记）。
                PddCodeStore.putForce(this, k, c.copy(pickedAt = 0L))
                n++
            }
        } catch (t: Throwable) {
            SafeRun.w("PickupClear", t)
        }
        sp.edit().putBoolean("auto_picked_cleaned_v1413", true).apply()
        Log.i("PickupClear", "清洗自动打标：$n 条伪「已取件」恢复为待取")
    }

    /**
     * ★★ v1.4.13：**一次性收敛"一码多单"**（同志 2026-11-10 13:45 实机：`73-3-5888` 同时挂在
     * 4 个订单上，其中润唇膏自己的真码 `114-4-9453` 被它顶掉 ⇒ 首页那格码重复、去重后润唇膏消失）。
     *
     * 格口码同一时刻只能属于一件：同码 ≥2 单就是抽取污染。处理方式**不是删缓存**，
     * 而是把"非保留者"的码清空，交给补码链重新抓 —— 万一清错了，下一轮就能补回来。
     *
     * 保留谁：**下单最早**的那个（驿站先分配的码，先到的件拿走）。
     * 只跑一次（SP 标记），旧数据清干净之后交给 [applyPddCodeQuiet] 的"不许被占用码覆盖"守门。
     */
    private fun cleanSharedCodesOnce() {
        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (sp.getBoolean("shared_code_cleaned_v1413", false)) return
        var cleared = 0
        try {
            val byCode = PddCodeStore.all(this).entries
                .filter { it.value.code.isNotBlank() }
                .groupBy { it.value.code.trim() }
            for ((code, list) in byCode) {
                if (list.size < 2) continue
                val keep = list.minByOrNull {
                    PddNative.snToDateMs(it.key.substringAfter(':')) ?: Long.MAX_VALUE
                } ?: continue
                for ((k, c) in list) {
                    if (k == keep.key) continue
                    PddCodeStore.putForce(this, k, c.copy(code = ""))
                    cleared++
                    Log.i("PddCodeMatch", "一码多单清洗：$k 的 $code 归 ${keep.key} ⇒ 清码等重补")
                }
            }
        } catch (t: Throwable) {
            SafeRun.w("PddCodeMatch", t)
        }
        sp.edit().putBoolean("shared_code_cleaned_v1413", true).apply()
        Log.i("PddCodeMatch", "一码多单清洗完成：清掉 $cleared 条重复归属")
    }

    /**
     * ★★ v1.4.13：**重置取件码缓存**的确认与执行（同志 2026-11-10 13:50）。
     *
     * 清三样，都是"取件码这条链"的产物：
     * · [PddCodeStore] 全部码与驿站信息（含被污染的归属）；
     * · [PickedStore] 所有「已取件」标记（码都重抓了，旧标记没有意义）；
     * · [MuteStore] 里由「已取件」写进去的记录（`picked:` 前缀；手动删过的 `m…` 不动）。
     *
     * 不动：在途缓存、用户手动删除的黑名单记录 —— 那是明确的用户意图，不该被一次重置带走。
     * 清完立刻重抓，所以用户不需要再手动下拉。
     */
    private fun confirmResetPickupCodes() {
        AlertDialog.Builder(this)
            .setTitle("重置取件码缓存")
            .setMessage(
                "会清空：\n\n" +
                        "· 所有取件码与驿站信息\n" +
                        "· 所有「已取件」标记\n" +
                        "· 由「已取件」写进黑名单的记录\n\n" +
                        "清空后立即重新抓取。在途快递、以及你长按删过的件不受影响。"
            )
            .setPositiveButton("清空并重抓") { _, _ ->
                try {
                    PddCodeStore.clearAll(this)
                    PickedStore.clear(this)
                    MuteStore.clearPickedRecords(this)
                    // 内存里的卡片也丢掉，否则重抓回来之前屏幕上是旧卡
                    pddPkgs = ArrayList()
                    pickupDirty = true
                    pickupPickedVisible = -1
                    Log.i("PickupClear", "用户重置取件码缓存：已清空缓存/标记/名单记录，开始重抓")
                    appToast(this, "已清空，正在重新抓取…")
                    runCatching { renderMerged() }
                    runCatching { rebuildHome() }
                    runCatching { PickupPublish.publish(this, notifyNew = false) }
                    // 重抓：逐条补码链会重新跑一遍（keepCards=false ⇒ 不复用旧卡）
                    fetchPickup(keepCards = false)
                } catch (t: Throwable) {
                    SafeRun.w("PickupClear", t)
                    appToast(this, "重置失败：${t.message ?: t.javaClass.simpleName}")
                }
            }
            .setNegativeButton("取消", null)
            .showAnimated()
    }

    private fun pddCodeExpired(sn: String): Boolean {
        val born = PddNative.snToDateMs(sn) ?: return false
        return System.currentTimeMillis() - born > PDD_CODE_KEEP_DAYS * 86_400_000L
    }

    /**
     * ★ vc229 调试开关：把缓存里**所有**取件码打上「已取件」标记。
     *
     * 为什么需要它：vc316 起标记只能靠长按手动打，要验证「标记 → 卡片渲染 → 待取数 →
     * 24h 后清除」整条链，总不能真去驿站取一件。留个开关，一条命令就能看效果：
     *   adb shell am start -n com.agent.idcode/.MainActivity --ez debug_mark_picked true
     *   --ei debug_picked_age_h 25    ⇒ 假装 25 小时前标的，下一轮 sync 会被清掉
     */
    private fun debugMarkAllPicked(ageHours: Int = 0) {
        val now = System.currentTimeMillis() - ageHours * 3_600_000L
        val map = PddCodeStore.all(this)
        for ((k, c) in map) {
            PddCodeStore.put(this, k, c.copy(pickedAt = now))
            Log.i("PddCache", "调试：打已取件标记 $k（${ageHours}h 前）")
        }
        appToast(this, "已给 ${map.size} 条缓存打上「已取件」标记（${ageHours}h 前）")
    }

    private fun syncPickupCodeCache(pending: ArrayList<JSONObject>) {
        // ★ vc257：先把上次的 track 状态读回 —— 它是"这单取没取"的唯一可靠判据，
        //   而它是进程内 Map，App 一杀就丢；拼多多登录态失效时也不会再有新的。
        //   不读回就只剩"下单超 7 天"的笨兜底 ⇒ 取件码一直挂在「在驿站待取」。
        PddChannel.restoreTrackStatus(this)
        val cache = PddCodeStore.all(this).toMutableMap()
        // ⓪ 清理旧格式键（131 版及更早写入的没有 `src:` 前缀 —— 不清的话同一件会补出两张卡，
        //   实测 21:14："2 个待取"、取件码重复两次）
        for (k in cache.keys.toList()) {
            // ★ vc302：白名单加 "tb" —— 淘宝到站卡（极兔等自营驿站件）也持久化在这里，
            //   别让旧格式清理把它当垃圾删了
            if (k.substringBefore(':') !in setOf("pdd", "cainiao", "jd", "tb")) {
                Log.i("PddCache", "清理旧格式缓存键 $k")
                PddCodeStore.remove(this, k)
                cache.remove(k)
            }
        }
        if (cache.isEmpty() && pending.isEmpty()) return
        // ★★ v1.4.11：**撤回「摘掉借来的码」**（v1.4.10 加的，判断错了）。
        //   当初据「114-4-9453 是昨天已取过的快递」认定这枚码是 cabinet 错配过来的，
        //   于是把同码的新卡一律摘成无码。同志随后澄清：**114-4-9453 就是今天刚到的
        //   润唇膏的码，他还没取** —— 也就是说这枚码只是被驿站**重新分配**了。
        //   格口码复用是常态（MuteStore 那次误伤化妆棉也是同一个坑），
        //   用它反推"这码该归谁"必然误杀，只能信本单自己抓到的证据。
        //   ---------------------------------------------------------------
        // ★ vc270g：取件码唯一性清理 —— 但**只在同一天下单**的订单之间生效。
        //   两个订单报同一个码 = 抽取污染（页面级码元素被重复抠到，另一台设备实测：
        //   261013-624699676181159 与 261012-446525632021159 都被抓到 3-3-4013）。
        //   ★★ v1.4.11：不同日期下单的**不算污染** —— 驿站格口码会被复用（旧件取走后
        //   同一格口分给新件），那种情况下两单都该保留自己的码。旧写法无条件"只留一单"，
        //   会把新到的件的码直接清掉，观感就是"码又没了"。
        val byCode = HashMap<String, MutableList<String>>()
        for ((k, c) in cache) {
            if (c.src == "pdd" && c.code.isNotBlank()) byCode.getOrPut(c.code) { mutableListOf() }.add(k)
        }
        for ((code, allKeys) in byCode) {
            // 按下单日期分组：同一组（同一天下单）才算抽取污染
            val keys = allKeys.groupBy { PddNative.snToDateMs(it.substringAfter(':')) ?: 0L }
                .filterValues { it.size > 1 }
                .flatMap { it.value }
            if (keys.size < 2) continue
            // 保留**下单最新**的那个（真在驿站的包裹一定是最近下单的；
            //   22:39 教训——古董订单的 ts 被补码刷新过，按 ts 挑会挑错）
            val keep = keys.minByOrNull {
                -(PddNative.snToDateMs(it.substringAfter(':')) ?: 0L)
            } ?: continue
            for (k in keys) {
                if (k == keep) continue
                val fixed = cache.getValue(k).copy(code = "")
                PddCodeStore.put(this, k, fixed)
                cache[k] = fixed
                // 注意措辞：被清码的是 **k**（没当上 keep 的那个），keep 才保留
                Log.w("PddCache", "取件码冲突清理（同天下单）：$k 与 $keep 同码 $code —— 保留 $keep，清掉 $k 的码")
            }
        }
        // ★ vc616：拼多多「在驿站待取」的**历史订单**（下单超 15 天）不进取件码栏 ——
        //   驿站不会有 134 天前的件还在架上，那是订单列表的归档条目；它们还会污染补码
        //   （23:13 实测：3-3-4013 被 260407/260603 两个古董订单抢走，真包裹反而没码）。
        //   顺带清掉它们缓存里的污染码。
        //
        // ★★ vc270n（10-16）：**必须连缓存一起删，且不能只在卡片在列表里时才删**。
        //   旧写法只在 pending 里有这张卡时才清 —— 而老单早就被终态词/缺码过滤出列表，
        //   于是 45 天清理**永远不执行**，缓存里那个 134 天前的码被 `② 常态显示`
        //   一轮轮补回界面（真机日志实证：界面唯一的码就是 6 月老单的，且刷了 14 次都没掉）。
        //   ⇒ 改成先扫缓存、后扫 pending，两边都清。
        run {
            val nowMs45 = System.currentTimeMillis()
            for ((k, c) in cache.keys.toList().map { it to cache.getValue(it) }) {
                if (c.src.ifEmpty { k.substringBefore(':') } != "pdd") continue
                val sn = k.substringAfter(':', k)
                val born = PddNative.snToDateMs(sn) ?: continue
                if (nowMs45 - born > PickupVerdict.MAX_ORDER_AGE_DAYS * 86_400_000L) {
                    // ★ vc280：**服务端仍报待取**（IN_CABINET/SIGN）的老单不删 —— 见下面
                    //   pending.removeAll 里的说明（10-16 小米机：6 月老单的码就是这么没的）
                    val st0 = PddChannel.lastTrackStatus[sn]
                    if (st0 != null && !PddChannel.isNonPickupState(st0)) continue
                    PddCodeStore.remove(this, k)
                    cache.remove(k)
                    Log.i("PddCache", "清掉 ${PickupVerdict.MAX_ORDER_AGE_DAYS} 天以上的历史订单缓存：$k（下单 ${(nowMs45 - born) / 86_400_000L} 天前）")
                }
            }
        }
        pending.removeAll { p ->
            if (p.optString("src") != "pdd") return@removeAll false
            val sn = p.optString("orderSn")
            val born = PddNative.snToDateMs(sn) ?: return@removeAll false
            val ancient = System.currentTimeMillis() - born > PickupVerdict.MAX_ORDER_AGE_DAYS * 86_400_000L
            if (ancient) {
                // ★★ vc280：track 状态**仍是待取**（IN_CABINET/SIGN）⇒ 服务端还认这单，
                //   件可能真还在架上。旧写法无条件删 ⇒ 10-16 小米机实证：同志报
                //   「唯一能拉到的 1 个取件码消失了」，删的正是 6 月老单的真码
                //   （260603，服务端每轮都返回 SIGN）——而卡上不印下单日期，
                //   用户根本看不出那是老单，只以为"新单的码被 App 搞丢了"。
                //   ⇒ 只有"状态查不到 / 已终态"的老单才清；待取态的老单留在栏里，
                //     真不要了用户长按就能删（且现在卡片明细行印着下单日期，看得明白）。
                val st0 = PddChannel.lastTrackStatus[sn]
                if (st0 != null && !PddChannel.isNonPickupState(st0)) {
                    Log.i("PddCache", "老单但服务端仍报待取（$st0），保留在栏：$sn")
                    return@removeAll false
                }
                Log.i("PddCache", "历史订单不进取件码栏（下单超${PickupVerdict.MAX_ORDER_AGE_DAYS}天）：$sn")
                // ★ vc270m：连缓存一起**删除**（旧版只清码，结果古董条目还挂着、且可能与新包裹
                //   抢同一个码 ⇒ 一轮轮刷"取件码冲突清理"，界面上还留着 6 月订单带码的怪卡）
                val key = "pdd:$sn"
                if (cache.containsKey(key)) {
                    PddCodeStore.remove(this, key)
                    cache.remove(key)
                    Log.i("PddCache", "清掉历史订单缓存：$key")
                }
            }
            ancient
        }
        // ★ vc223 诊断：把缓存全量打出来（key / 源 / 码 / 缓存年龄 / 下单日年龄）
        Log.i("PddCache", "vc223 缓存全量(" + cache.size + "): " + cache.entries.joinToString(" | ") {
            val sn0 = it.key.substringAfter(':', it.key)
            val born0 = PddNative.snToDateMs(sn0)
            it.key + "|src=" + it.value.src + "|code=" + it.value.code +
                "|tsAgeD=" + ((System.currentTimeMillis() - it.value.ts) / 86_400_000L) +
                "|snAgeD=" + (if (born0 != null) (System.currentTimeMillis() - born0) / 86_400_000L else -1)
        })
        // ★ vc343：必须真有"这一轮"才谈得上完成 —— 旧写法在没有抓取轮次时（expect=0）
        //   也算"全部回来"，于是任何一次杂项重绘都会拿空列表去消缓存
        //   （2026-10-20 日志：启动后第一次渲染就把 cainiao 的码删了，随后又按新键重建 ⇒ 键漂移/重复）。
        val allDone = pickupExpect > 0 && pickupGot >= pickupExpect      // 各源都回来了
        // ★ vc264m：下拉刷新收尾——所有源都回来 ⇒ 收起转圈（异常路径由 60 秒兜底收尾）
        if (allDone) pickupSwipe?.let { if (it.isRefreshing) it.isRefreshing = false }
        // ① 消除（只信"该源本次抓取成功"前提下的缺席）
        for ((key, c) in cache.keys.toList().map { it to cache.getValue(it) }) {
            val src = c.src.ifEmpty { key.substringBefore(':') }
            val sn = key.substringAfter(':', key)
            // ★ vc229：已打「已取件」标记且满 24 小时 ⇒ 这才是真正清除的时机
            if (PddCodeStore.pickedExpired(c)) {
                Log.i("PddCache", "已取件满 24h，清除取件码 $key")
                PddCodeStore.remove(this, key)
                cache.remove(key)
                // ★★ v1.3.1：**清理后统一刷新**（审计发现的漏洞）——
                //   原来只动缓存 ⇒ 首页「N 件待取」、桌面组件、流体云都还挂着已删掉的码。
                //   这里延后一拍再刷（本条循环里可能连删多条，避免每条都重画一次）。
                mainHandler.postDelayed({
                    runCatching { rebuildHome() }
                    runCatching { refreshPickupBarSub() }
                    runCatching { PickupPublish.publish(this, notifyNew = false) }
                }, 300L)
                continue
            }
            // ★★ vc316（同志 2026-10-18 定）：**取消"取件后自动打标记"整条链**。
            //   口径：
            //     · App 不再猜"这单你取走了没有"，也不再往卡片上写 pickedAt；
            //     · 只做一件确定的事 —— 某个源**这次抓取成功**、且明确说这单已不在它那儿
            //       ⇒ 把取件码从栏里拿掉（"取件码"栏的定义就是"还要用的码"）；
            //     · 「已取件」灰标只由**长按卡片**手动打（24h 后由上面的 pickedExpired 清）。
            //   为什么这么定：判"取走"的判据（平台状态文案 / 列表消失）都会滞后或说谎，
            //   打错一次就是把还要用的码划掉 —— 让用户自己点一下最可靠。
            val gone = when (src) {
                // ★★ 消除口径必须与 parse 收卡**同一份**（2026-09-26 修，这是个自相残杀的循环）：
                //   旧写法 `!= "IN_CABINET"` 会把 SIGN（= 待取！物流页写着「待取件」）的件
                //   当成"已取出"—— 于是 enrich 刚把 20-6-6001 写进缓存，下一轮 sync 就把它消掉
                //   ⇒ 观感就是同志报的「打开先显示'在驿站待取'，要再等 5 秒码才出来」：
                //     缓存永远存不住，每次打开都得重新开一遍物流页。
                // ★ vc223：老单（已取走/订单完结）服务端不再返回 ⇒ lastTrackStatus 查不到
                //   ⇒ 旧写法 isNonPickupState(null) 恒 false ⇒ 取件码永久显示。
                //   状态查得到就信状态；查不到说明服务端已不管这单 ⇒ 按保鲜期兜底消除。
                "pdd" -> {
                    val st = PddChannel.lastTrackStatus[sn]
                    // ★★ vc655：**只有 GOT 算"已取走"**。
                    //   事故（2026-11-01 20:13 真机日志）：副号补出 4 个码 → 5ms 后这条把
                    //   `261029-200131253802219` 删掉，因为它的枚举是 OUT_CABINET。
                    //   而 OUT_CABINET = 出了柜/驿站代签，件还在（vc347 实证），
                    //   `isNonPickupState` 那张表还含 ARRIVAL/DEPARTURE（根本没到站）。
                    //   ⇒ 一律改用裁决器的口径；码只在真取走 / 查不到状态且超期时才清。
                    if (st != null) {
                        if (PickupVerdict.enumSaysTaken(st)) {
                            Log.i("PddCache", "枚举 GOT（已取走）⇒ 清码：$key")
                            true
                        } else {
                            // OUT_CABINET / SIGN / IN_CABINET / ARRIVAL… 都留着
                            false
                        }
                    } else pddCodeExpired(sn)
                }
                "cainiao" -> allDone && cainiaoErr == null && !cainiaoNeedLogin &&
                        cainiaoPkgs.none { cainiaoKey(it) == sn }
                "jd" -> allDone && jdErr == null &&
                        jdPkgs.none { it.optString("waybillNo") == sn }
                // 淘宝（tb）到站卡这里不掺和：vc316 起 App 不再自动判「取没取走」，
                // 卡片去留只由用户长按决定（24h 后由 pickedExpired 清）。
                else -> false
            }
            if (gone) {
                // 删掉而不是打灰标 —— 灰标现在只代表"用户自己长按标记过"，
                // 自动链不许再用它，否则用户永远分不清哪个是人标的、哪个是机器猜的。
                Log.i("PddCache", "源里已无此件，移除取件码 $key（不打标记）")
                PddCodeStore.remove(this, key)
                cache.remove(key)
            }
        }
        // ② 常态显示：缓存里有、本次列表里没有 ⇒ 补一张卡（不因刷新/失败而消失）
        // ★★ vc675：**回填也要过黑名单**。这一步在 renderMerged 的黑名单过滤**之后**执行，
        //   旧写法不看屏蔽 ⇒ 用户长按删除的卡下一轮又从缓存补回来
        //   （日志实证：`渲染开始 cainiao=0 pdd=0 … 删除屏蔽=29`，源里全空但页面上还有卡）。
        val mutedNow = pickupMutedAll()
        // ★★ v1.4.9b：**必须遍历快照**。循环体里有两处 `cache.remove(key)`（清掉"已取走"的旧码），
        //   直接在 `LinkedHashMap` 上边遍历边删 ⇒ `ConcurrentModificationException`，
        //   整个进程崩掉（同志 2026-11-10 11:45:58 的崩溃栈：renderMerged → syncPickupCodeCache）。
        //   以前偶尔才崩，这一次缓存里正好躺着几条"已取件"的旧码，百分百触发。
        for ((key, c) in cache.entries.toList()) {
            if (c.code.isBlank()) continue
            val sn = key.substringAfter(':', key)
            val src = c.src.ifEmpty { key.substringBefore(':') }
            if (mutedNow.isNotEmpty()) {
                // ★★ v1.4.10：**改走记录制判据**（订单号永久 / 运单号 60 天 / 码 7 天且同驿站）。
                //   旧写法把名单摊平成 Set 后 `listOf(sn, code, waybill).any { it in mutedNow }` ——
                //   等于"取件码也参与匹配、且没有时效也没有驿站约束"，
                //   格口码一被复用就把新件挡在门外（化妆品/皇帝柑那批都是这条路丢的）。
                val hit = MuteStore.blocks(
                    this, src,
                    orderSn = if (src == "pdd") sn else "",
                    waybill = c.waybill,
                    code = c.code,
                    station = c.station
                )
                if (hit != null) {
                    Log.i(
                        "PickupCard",
                        "回填跳过（黑名单）：$key code=${c.code} ← 记录(${hit.src}/${hit.orderSn}/${hit.waybill}/${hit.code})"
                    )
                    continue
                }
            }
            // ★ vc609：缓存文案已经写着"已取走/已取件" ⇒ 这码是旧的，直接删缓存、不补卡
            // ★★ v1.4.10：**但别只凭一句旧文案就把码删掉**。真机日志（12:07:47）：
            //     `缓存文案=已取走，删除并跳过：pdd:261107-… ｜已取件2026-11-09 19:11:45 您的快件已领取`
            //     而同一天平台侧这单还在「在驿站待取」—— 文案是上一轮（甚至串卡）留下的，
            //     拿它判死就会把还要用的码删掉，观感就是"首页/页面上的码闪一下就没了"。
            //   ⇒ 只有在**平台也查不到待取证据**时才采信这句文案。
            if (PddCodeStore.looksTaken(c)) {
                val st = PddChannel.lastTrackStatus[sn]
                val platformWaiting = st != null && !PickupVerdict.enumSaysTaken(st)
                if (platformWaiting) {
                    Log.i("PddCache", "文案说已取走但平台仍报 $st ⇒ 保留这枚码：$key")
                } else {
                    Log.i("PddCache", "缓存文案=已取走，删除并跳过：$key｜${c.latest.take(40)}")
                    PddCodeStore.remove(this, key)
                    cache.remove(key)
                    continue
                }
            }
            // ★ vc343（同志 2026-10-20 反馈「重复的取件码」）：
            //   旧判据只认 orderSn/waybillNo —— 菜鸟卡的号在 `mailNo` 里，这两个字段都是空，
            //   于是**每轮渲染都把缓存卡再补一遍**，同一张卡出现两次。
            //   改用 pickKey（与写缓存用同一套键）+ 取件码兜底：同源同码只要列表里已有活卡，
            //   就不再补缓存卡。
            if (pending.any {
                    it.optString("src") == src &&
                            (pickKey(src, it) == sn || it.optString("pickupCode") == c.code)
                }) continue
            pending.add(JSONObject().apply {
                put("src", src)
                if (src == "pdd") put("orderSn", sn)
                put("pickupCode", c.code)
                put("waybillNo", c.waybill)
                put("upCpName", when (src) {
                    // ★ vc343：缓存里没有承运商信息，别把平台名当快递公司写进去
                    //   （以前写「菜鸟」，卡上会多出"菜鸟"三个字，同志以为平台标错了）。
                    "cainiao" -> ""
                    "jd" -> "京东"
                    "tb" -> "淘宝"
                    else -> "拼多多"
                })
                put("statusText", if (c.pickedAt != 0L) "已取件" else "在驿站待取")
                put("stationName", c.station)
                put("nickName", c.station)
                if (c.latest.isNotEmpty()) { put("latest", c.latest); put("tip", c.latest) }
                put("fromCache", true)
                // ★ vc229：把「已取件」标记带到卡上（渲染层据此打标记）
                if (c.pickedAt != 0L) put("pickedAt", c.pickedAt)
            })
        }
        // ③④ 有码的卡写缓存（三源统一 upsert）；缺码的卡用缓存填上
        for (p in pending) {
            val src = p.optString("src")
            if (src !in setOf("pdd", "cainiao", "jd")) continue
            val sn = pickKey(src, p) ?: continue
            val key = "$src:$sn"
            if (key in pickupMutedAll()) continue      // 用户删过的不再写回缓存
            val code = p.optString("pickupCode")
            val cached = cache[key]
            // ★ vc229：缓存里已有的「已取件」标记要带到卡上（否则卡片不知道自己已被取走）
            if (cached != null && cached.pickedAt != 0L) {
                p.put("pickedAt", cached.pickedAt)
                // 状态文案也得跟着改：这张卡的 statusText 是抓取那一刻写进去的旧值，
                // 若还停在「在驿站待取」，卡上就会出现「已取件」+「在驿站待取」自相矛盾。
                if (p.optString("statusText") == "在驿站待取") p.put("statusText", "已取件")
            }
            if (code.isNotEmpty()) {
                // ★ vc270g：唯一性守护 —— 别的订单已持有同码 ⇒ 本次抽取疑似污染，不写码
                val clash = cache.entries.firstOrNull {
                    it.key != key && it.value.src == src && it.value.code == code
                }
                if (clash != null) {
                    Log.w("PddCache", "取件码冲突：$key 与 ${clash.key} 同码 $code —— 本次不写码")
                    p.remove("pickupCode")
                } else {
                    val nc = PddCodeStore.Cached(
                        src = src, code = code,
                        station = p.optString("stationText").ifEmpty { p.optString("nickName") }.take(60),
                        waybill = p.optString("waybillNo"),
                        latest = p.optString("latest").take(220),
                        ts = System.currentTimeMillis(),
                        // 写回缓存时**保留**标记 —— 刷新时把它抹掉就永远删不掉了
                        pickedAt = cached?.pickedAt ?: 0L
                    )
                    if (cached == null || cached.code != nc.code || cached.station != nc.station) {
                        PddCodeStore.put(this, key, nc)
                        cache[key] = nc
                        // ★ vc343：同一件可能因为「当时有没有运单号」被存成两个键
                        //   （运单号键 / 取件码键）⇒ 写入新键后清掉同源同码的旧键，
                        //   否则下一轮渲染会补出一张重复卡（同志 10-20 报的重复码）。
                        val ghosts = cache.entries
                            .filter { it.key != key && it.value.src == src && it.value.code == code }
                            .map { it.key }
                        for (g in ghosts) {
                            PddCodeStore.remove(this, g)
                            cache.remove(g)
                            Log.i("PddCache", "清掉同码旧缓存键：$g（保留 $key，码 $code）")
                        }
                    }
                }
            } else if (cached != null) {
                p.put("pickupCode", cached.code)
                if (p.optString("waybillNo").isEmpty()) p.put("waybillNo", cached.waybill)
                if (p.optString("stationName").isEmpty()) p.put("stationName", cached.station)
                if (p.optString("nickName").isEmpty()) p.put("nickName", cached.station)
                if (p.optString("latest").isEmpty() && cached.latest.isNotEmpty()) {
                    p.put("latest", cached.latest); p.put("tip", cached.latest)
                }
            }
        }
    }

    /** 菜鸟包裹的缓存键单号：运单号 → 包裹 id → 取件码（菜鸟对象没有订单号） */
    private fun cainiaoKey(p: JSONObject): String =
        p.optString("mailNo").ifEmpty { p.optString("packageId") }.ifEmpty { p.optString("pickupCode") }

    /** 各源取缓存键用的单号：pdd=订单号；cainiao=运单号；jd=订单号 */
    private fun pickKey(src: String, p: JSONObject): String? = when (src) {
        "pdd" -> p.optString("orderSn").ifEmpty { p.optString("waybillNo") }.ifEmpty { null }
        "cainiao" -> cainiaoKey(p).ifEmpty { null }
        "jd" -> p.optString("waybillNo").ifEmpty { null }
        // ★ vc302：淘宝到站卡（实时/缓存补出）也要能长按删除 —— 键与缓存键同一格式
        "tb" -> p.optString("waybillNo").ifEmpty { null }
        else -> null
    }

    /**
     * ★★ vc670：**一件包裹的全部身份**（订单号 / 运单号 / 取件码）。
     *
     * 为什么需要：同一个包裹会被两个源各抓一次 —— 拼多多卡键是 `pdd:订单号`，
     * 菜鸟卡键是 `cainiao:运单号`（运单号缺省时退化成取件码）。长按删除只屏蔽
     * `src:键` 时，**另一来源的同一件照样回来**（2026-11-02 用户实测：删了还在、
     * 连删几次都一样）。跨源只有"运单号 / 取件码"是共通的，所以删除与过滤都按这组身份走。
     */
    private fun parcelIds(src: String, p: JSONObject): List<String> {
        val out = LinkedHashSet<String>()
        fun add(v: String) {
            val s = v.trim()
            if (s.isNotEmpty() && s != "null") out.add(s)
        }
        add(p.optString("orderSn"))
        add(p.optString("realWaybill"))
        add(p.optString("waybillNo"))
        add(p.optString("mailNo"))
        add(p.optString("pickupCode"))
        add(pickKey(src, p).orEmpty())
        return out.toList()
    }

    /**
     * 这张卡是否已被用户长按删除（**跨来源**）。
     *
     * ★★ v1.4.9：判定交给 [MuteStore.blocks] —— 按身份强度分级（订单号永久 /
     * 运单号 60 天 / 取件码 7 天且必须同驿站）。
     * 以前是"任一身份命中就整件隐藏"，而 `parcelIds()` 里含**取件码**，
     * 取件码是驿站格口码、天天复用 ⇒ 新到的件被旧记录误伤
     * （同志 2026-11-10 的「化妆棉」就是这么丢的：在途 11 件被名单剔掉 3 件）。
     */
    private fun isPickupMuted(src: String, p: JSONObject, muted: Set<String>): Boolean =
        pickupMuteHit(src, p) != null

    /**
     * ★★ v1.4.14：命中名单时**返回那条记录**，而不只是 true。
     *
     * 调用方要按 `id.startsWith("picked:")` 区分两种记录：
     * · `picked:*` = 用户亲手点过「标记已取件 / 清已取」⇒ 名单直接生效，不给"待确认"豁免；
     * · 其它（长按删除）= 可能是误删 ⇒ 件还在运输中/待确认时放行（误删保护）。
     * 这个区分是「清已取后快递又回到在途栏」那条 bug 的正根，见 [TransitRules.judge] 判据①。
     */
    /**
     * ★★ v1.4.16：**缓存条目**是否命中黑名单（key 形如 `pdd:订单号`）。
     *
     * 与 [pickupMuteHit] 的区别：那个吃的是实时卡片 JSON，这个吃 `PddCodeStore` 的条目 ——
     * 首页冷启动首帧只有缓存、没有实时卡，得用这条。
     */
    private fun isCachedEntryMuted(key: String, c: PddCodeStore.Cached): Boolean =
        MuteStore.blocks(this, c.src, key.substringAfter(':'), c.waybill, c.code, c.station) != null

    private fun pickupMuteHit(src: String, p: JSONObject): MuteStore.Entry? {
        val hit = MuteStore.blocks(
            this,
            src,
            p.optString("orderSn"),
            p.optString("realWaybill").ifEmpty { p.optString("waybillNo") }
                .ifEmpty { p.optString("mailNo") },
            p.optString("pickupCode"),
            p.optString("stationText").ifEmpty { p.optString("stationName") }
                .ifEmpty { p.optString("nickName") }
        )
        if (hit != null) {
            Log.i(
                "PickupMuted",
                "命中名单：$src ${p.optString("orderSn")} 码=${p.optString("pickupCode")} " +
                        "← 记录(${hit.src}/${hit.orderSn}/${hit.waybill}/${hit.code}) id=${hit.id}"
            )
        }
        return hit
    }

    // ---------------- ★★ 统一取件码解析（照参考项目 GoodsPresentation）----------------

    /**
     * ★★ 取件码统一解析 —— 照参考项目 `GoodsPresentation.pickupCodeFrom` 的做法。
     *
     * ## 为什么必须"统一"
     * 各源的取件码来路完全不同：菜鸟是官方 `pickupCode` 字段、拼多多靠详情页 DOM 抠、
     * 京东从轨迹文本正则、顺丰压根没有。**任何一路抠不到，那一栏就空着**。
     * 参考项目的解法是：**在融合层对每个件都跑一遍同一个正则** —— 谁的轨迹文本里出现
     * "取件码 XXX"就提出来 ⇒ **任何源都能出码**，不依赖各源自己实现。
     * （它连关键词都比我们全：多"取货码/提货码/自提码/驿站码/取件验证码"；
     *  码的形态 `[A-Za-z0-9][A-Za-z0-9\-]{1,12}` 允许连字符，正好覆盖 `31-5-7006`。）
     */
    private val PICKUP_RE = Regex(
        "(取件码|取货码|提货码|自提码|驿站码|取件验证码)[:：\\s]*([A-Za-z0-9][A-Za-z0-9\\-]{1,12})"
    )

    private fun pickupCodeOf(text: String): String {
        if (text.isBlank()) return ""
        return PICKUP_RE.find(text)?.groupValues?.get(2)?.trim().orEmpty()
    }

    /** 对每个件把它手上所有文本都试一遍，缺码就补上（已有码的不动） */    private fun fillPickupCodes(list: MutableList<JSONObject>) {
        var filled = 0
        for (p in list) {
            if (p.optString("pickupCode").isNotEmpty()) continue
            val texts = listOf(
                p.optString("latest"), p.optString("tip"),
                p.optString("statusText"), p.optString("stationText"),
                p.optString("nickName")
            )
            for (t in texts) {
                val c = pickupCodeOf(t)
                if (c.isNotEmpty()) {
                    p.put("pickupCode", c)
                    filled++
                    break
                }
            }
        }
        if (filled > 0) Log.i("PickupFill", "统一解析补出 $filled 个取件码")
    }

    /**
     * ★★ v1.4.9：**商品标题的"记账 + 回填"**。
     *
     * 同志 2026-11-10 要求：取件码页面要显示取件码对应的快递标题（这单买的是什么）。
     * 难点在于标题的来源不齐：拼多多订单列表有 `goodsName`，而**物流页补出来的卡**
     * （`PddEnrich` 新建的那条）只有码和驿站；重启后从缓存恢复的卡也可能没有。
     *
     * 做法：
     *  ① 凡是这轮见到标题，就按**订单号**记进 [PddTitleStore]（一行字，500 条上限）；
     *  ② 缺标题的卡先按订单号查字典，查不到再看同一轮里**同单号/同运单号**的另一份卡
     *     （拼多多卡和菜鸟卡常常是同一件）；
     *  ③ 只补空值，绝不覆盖已有标题。
     */
    private fun backfillPackageTitles(list: MutableList<JSONObject>) {
        val learn = HashMap<String, String>()
        for (p in list) {
            val sn = p.optString("orderSn")
            val t = p.optString("goodsName")
            if (sn.isNotEmpty() && t.isNotEmpty()) learn[sn] = t
        }
        if (learn.isNotEmpty()) PddTitleStore.putAll(this, learn)
        var filled = 0
        for (p in list) {
            if (p.optString("goodsName").isNotEmpty()) continue
            val sn = p.optString("orderSn")
            var t = if (sn.isNotEmpty()) PddTitleStore.get(this, sn) else ""
            if (t.isEmpty()) {
                val wb = p.optString("realWaybill").ifEmpty { p.optString("waybillNo") }
                t = list.firstOrNull { q ->
                    q !== p && q.optString("goodsName").isNotEmpty() && (
                            (sn.isNotEmpty() && q.optString("orderSn") == sn) ||
                                    (wb.isNotEmpty() && (q.optString("realWaybill") == wb ||
                                            q.optString("waybillNo") == wb))
                            )
                }?.optString("goodsName").orEmpty()
            }
            if (t.isNotEmpty()) {
                p.put("goodsName", t)
                filled++
            }
        }
        if (filled > 0) Log.i("PickupTitle", "回填商品标题 $filled 张（字典 ${learn.size} 条）")
    }

    // ---------------- ★★ 跨源去重 ----------------

    /**
     * ★★ 同一件被两个平台同时抓到时只留一张卡（2026-09-20 同志提的隐患）。
     *
     * ## 为什么会重复
     * 同一个快递可能同时出现在多个源里：拼多多下单 → 菜鸟驿站代收 ⇒ 拼多多、菜鸟各出一张卡；
     * 手动加的单号也可能和某个平台的件撞上。**而各源的主键并不统一**：
     *  - 菜鸟用**运单号** `mailNo`，京东/顺丰用 `waybillNo`；
     *  - **拼多多用订单号 `orderSn`**（运单号要另开详情页才抠得到，常常是空的）。
     * ⇒ 原来 `TrackFetcher` 里那句 `distinctBy { it.no }` 在跨源时**根本不成立**
     *   （拼多多的 no 是订单号，跟菜鸟的运单号永远不等），
     *   第 4 栏 `renderMerged` 更是直接把六个列表首尾相接、压根没去重。
     *
     * ## 判据（宁可漏合并，不可误合并 —— 把用户的件误删掉比重复显示严重得多）
     * 1. **运单号归一化后相同** ⇒ 同一件。归一化 = 只留字母数字 + 转大写，
     *    这样 `SF1234567` 与 `sf-1234567` 才会被认成同一个件。
     * 2. 两边都没有运单号时，**取件码 + 驿站** 都相同 ⇒ 同一件。
     *    ★ 只比取件码不安全：不同驿站完全可能发出同一个货架码（`5-1-1001` 这种）。
     * 3. 都不满足 ⇒ 各自保留（当成不同件）。
     *
     * ## 合并时留谁
     * 信息更全的那张（有取件码 > 有运单号 > 有轨迹），并把被丢弃那张的**缺失**字段补过来
     * （拼多多的 `orderSn` 必须留住，否则后续抠取件码的链路会断）。
     * ★ 同分时**保留先出现的**（列表顺序 = 菜鸟 → 拼多多 → 京东 → 小米 → 手动），
     *   不额外偏袒某个源，避免卡片的 src 来回跳变影响「长按删除」用的 `src:单号` 键。
     */
    private fun normNo(s: String): String = s.filter { it.isLetterOrDigit() }.uppercase()

    /**
     * ★★ v1.4.5：**这个值能不能当单号用**。
     *
     * 为什么必须单独立一道判据（v1.4.3 在途 11 件塌成 1 件的根因）：
     *  · `org.json` 的 `optString()` 读到 JSON `null` 返回的是**字符串 "null"**，不是空串
     *    ⇒ `"null".isNotEmpty() == true` ⇒ 所有缺号的卡拿到同一个键 `W:NULL` ⇒ 整批并成一张。
     *    `parcelIds()` 早就防过这个坑（`s != "null"`），`dedupKey()` 一直漏着同一道闸。
     *  · 归一化 `normNo()` 会把 `-`、`—`、`*`、`(无)` 这类占位符滤成**空串**
     *    ⇒ 只看 `isNotEmpty()` 仍会得到同一个空键 `W:`。所以这里连归一化结果一起判。
     */
    private fun validNo(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return false
        val low = t.lowercase()
        if (low == "null" || low == "undefined" || low == "none" || low == "nan") return false
        if (t == "无" || t == "暂无" || t == "(无)" || t == "-" || t == "—") return false
        return normNo(t).isNotEmpty()
    }

    /** 跨源唯一键；返回 `U:` 开头的表示「没有可比对键」⇒ 永远各自保留 */
    private fun dedupKey(p: JSONObject): String {
        val src = p.optString("src")
        // ① 运单号（都必须是**有效号**才算数）
        if (src == "pdd") {
            // ★★ v1.4.6：拼多多卡的 `waybillNo` 存的是**订单号**（建卡时 put 的就是 t.orderSn），
            //   真运单号在 `realWaybill`。跨源合并要用真运单号，所以 pdd 先看 realWaybill。
            val real = p.optString("realWaybill")
            if (validNo(real)) return "W:" + normNo(real)
        } else {
            val waybill = p.optString("waybillNo").ifEmpty { p.optString("mailNo") }
            if (validNo(waybill)) return "W:" + normNo(waybill)
            val real = p.optString("realWaybill")
            if (validNo(real)) return "W:" + normNo(real)
        }
        // ② 取件码 + 驿站（只比码不安全：不同驿站完全可能复用同一个格口码）
        val code = p.optString("pickupCode")
        if (validNo(code)) {
            val st = p.optString("stationText").ifEmpty { p.optString("nickName") }
            if (st.isNotEmpty()) return "C:" + normNo(code) + ":" + st.take(20)
        }
        // ③ 各源自己的稳定标识
        val own = when (src) {
            "pdd" -> p.optString("orderSn")
            "cainiao" -> cainiaoKey(p)
            else -> p.optString("waybillNo").ifEmpty { p.optString("orderSn") }
        }
        return if (validNo(own)) "S:$src:" + normNo(own) else "U:${System.identityHashCode(p)}"
    }

    /** 信息完整度打分：分高的留下 */
    private fun pkgScore(p: JSONObject): Int {
        var s = 0
        if (p.optString("pickupCode").isNotEmpty()) s += 8   // 取件码最值钱
        if (p.optString("waybillNo").isNotEmpty()) s += 4
        if (p.optString("latest").isNotEmpty()) s += 2
        if (p.optString("statusText").isNotEmpty()) s += 1
        if (p.optString("stationText").isNotEmpty()) s += 1
        return s
    }

    private fun dedupPkgs(list: List<JSONObject>): ArrayList<JSONObject> {
        // ★ vc343（同志 2026-10-20 反馈「重复的取件码」）：
        //   `syncPickupCodeCache` 在某个源这轮没回来时会用缓存补一张卡（fromCache=true）。
        //   若同一轮里活卡也在（例如源刚回来、但缓存键和活卡的键对不上），
        //   两张卡会同时上屏、印同一个码。这里先做一次**只针对缓存卡**的归并：
        //   同源 + 同码 + 活卡存在 ⇒ 把缓存卡里活卡缺的字段补过去，然后丢掉缓存卡。
        //   （只动缓存卡，不会误合并两个真实包裹。）
        val liveByCode = HashMap<String, JSONObject>()
        for (p in list) {
            if (p.optBoolean("fromCache")) continue
            val c = p.optString("pickupCode")
            if (c.isNotEmpty()) liveByCode[p.optString("src") + "|" + c] = p
        }
        val source = if (liveByCode.isEmpty()) list else ArrayList<JSONObject>().apply {
            for (p in list) {
                if (p.optBoolean("fromCache")) {
                    val live = liveByCode[p.optString("src") + "|" + p.optString("pickupCode")]
                    if (live != null) {
                        for (f in arrayOf(
                            "stationText", "nickName", "latest", "tip", "goodsName",
                            "statusText", "upCpName", "shelfNo", "phone", "waybillNo"
                        )) {
                            if (live.optString(f).isEmpty()) {
                                val v = p.optString(f)
                                if (v.isNotEmpty()) live.put(f, v)
                            }
                        }
                        Log.i(
                            "Dedup",
                            "缓存卡与活卡同码，合并：" + p.optString("src") + " " + p.optString("pickupCode")
                        )
                        continue
                    }
                }
                add(p)
            }
        }
        val byKey = LinkedHashMap<String, JSONObject>()
        for (p in source) {
            // 同取件码跨源归并：只有站点相同，或一方缺站点时才允许合并。
            // 不同驿站可能复用同一格口码，不能只按取件码硬合并。
            val code = p.optString("pickupCode").trim()
            val station = p.optString("stationText").ifEmpty { p.optString("stationName") }
                .ifEmpty { p.optString("nickName") }.trim()
            val codeKey = if (code.isEmpty()) null else normNo(code)
            val codeMatch = if (codeKey == null) null else byKey.entries.firstOrNull { (_, old) ->
                if (old.optString("src") == p.optString("src")) return@firstOrNull false
                val oldCode = old.optString("pickupCode").trim()
                if (normNo(oldCode) != codeKey) return@firstOrNull false
                val oldStation = old.optString("stationText").ifEmpty { old.optString("stationName") }
                    .ifEmpty { old.optString("nickName") }.trim()
                station.isEmpty() || oldStation.isEmpty() || normNo(station) == normNo(oldStation)
            }
            var k = codeMatch?.key ?: dedupKey(p)
            // ★★ v1.4.6：**同源、订单号不同 ⇒ 绝不是同一件，绝不合并**。
            //   跨源去重的本意是「同一件被两个源各抓一次」；**同一个源里的两张卡必然是两个包裹**
            //   （一个源不会把同一件抓成两张）。所以同源 + 不同 orderSn 时强制拆键。
            //   真机实证（2026-11-10 10:09:43）：9 张 pdd 卡 orderSn 各不相同，`waybillNo`
            //   却被上游涂成同一个真运单号 ⇒ 若只靠运单号做键，9 件会被并成 1 件。
            //   这道闸让"上游字段被写脏"不再等于"包裹消失"（宁可重复显示，不可整批消失）。
            if (codeMatch == null) {
                val clash = byKey[k]
                if (clash != null && clash.optString("src") == p.optString("src")) {
                    val a = clash.optString("orderSn")
                    val b = p.optString("orderSn")
                    if (validNo(a) && validNo(b) && a != b) {
                        k = "$k#sn:" + normNo(b)
                        Log.w("Dedup", "同源不同单号，拒绝合并：${p.optString("src")} $b（键与本单 $a 相同）")
                    }
                }
            }
            val prev = codeMatch?.value ?: byKey[k]
            if (prev == null) {
                byKey[k] = p
                continue
            }
            val (keep, drop) = if (pkgScore(p) > pkgScore(prev)) p to prev else prev to p
            // ★ 只补「缺失」字段，不覆盖已有值 —— 免得把更可信的那张的值冲掉
            for (f in arrayOf(
                "pickupCode", "waybillNo", "orderSn", "stationText", "stationName", "nickName",
                "latest", "tip", "goodsName", "statusText", "upCpName", "shelfNo", "phone"
            )) {
                if (keep.optString(f).isEmpty()) {
                    val v = drop.optString(f)
                    if (v.isNotEmpty()) keep.put(f, v)
                }
            }
            byKey[k] = keep
        }
        val out = ArrayList(byKey.values)
        val merged = list.size - out.size
        if (merged > 0) Log.i("Dedup", "跨源去重 ${list.size} → ${out.size}（合并 $merged 条）")
        // ★★ v1.4.4：**塌缩保护** —— 去重把绝大多数件错误并成一条时，放弃去重、原样返回。
        //   同志 2026-11-10 实测（v1.4.3）：拼多多 11 件被 `Dedup: 11→1（合并 10 条）` 塌缩成 1 件，
        //   导致「在途快递」只剩 1 张卡、其余全消失。正常跨源去重只合并「同一件被两源各抓一次」，
        //   绝不会把 11 件并成 1 件 ⇒ 这是 dedupKey 因字段竞态（waybillNo 等被填成同值）误判。
        //   策略：输入>=3 且保留<一半 ⇒ 判为异常，返回原列表（宁显重复，不整批消失）。
        //   并 dump 每张卡的键字段，留线索定位到底哪个字段被填错。
        if (list.size >= 3 && out.size * 2 < list.size) {
            val dump = StringBuilder()
            for (p in source) {
                dump.append("\n    src=").append(p.optString("src"))
                    .append(" wb=").append(p.optString("waybillNo"))
                    .append(" sn=").append(p.optString("orderSn"))
                    .append(" code=").append(p.optString("pickupCode"))
                    .append(" fromCache=").append(p.optBoolean("fromCache"))
                    .append(" key=").append(dedupKey(p))
            }
            Log.i("Dedup", "塌缩异常 ${list.size}→${out.size}，放弃去重原样返回：${dump}")
            return ArrayList(list)
        }
        return out
    }

    /**
     * ★ 跨源去重自检（`--ez debug_dedup_selftest true`）。
     *
     * ## 为什么非要有它
     * 去重这件事的方向是「**宁可漏合并，不可误合并**」—— 误合并等于把同志的件**弄丢**，
     * 比重复显示严重得多。而这种 bug 只在"两个源同时抓到同一件"时才现身，
     * 手上没重复件的时候（现在就是这样）**根本没法用真数据验**。
     * ⇒ 用构造数据把每条判据跑一遍，结果 Toast + 落盘 `dedup_selftest.txt`。
     */
    private fun selfTestDedup() {
        val cases = ArrayList<Pair<String, Boolean>>()

        fun pkg(
            src: String, waybill: String = "", code: String = "",
            station: String = "", orderSn: String = ""
        ): JSONObject = JSONObject().apply {
            put("src", src)
            if (waybill.isNotEmpty()) put("waybillNo", waybill)
            if (code.isNotEmpty()) put("pickupCode", code)
            if (station.isNotEmpty()) put("stationText", station)
            if (orderSn.isNotEmpty()) put("orderSn", orderSn)
        }

        // ① 同一运单号、两个源（大小写与连字符不同）⇒ 必须合并，且取件码要留住
        run {
            val r = dedupPkgs(
                listOf(
                    pkg("cainiao", waybill = "SF1234567890", code = "31-5-7006", station = "A驿站"),
                    pkg("pdd", waybill = "sf-1234567890")
                )
            )
            cases.add("① 同运单号(大小写/连字符不同) 2→${r.size}" to (r.size == 1))
            val kept = r.firstOrNull()?.optString("pickupCode")
            cases.add("① 合并后仍保留取件码($kept)" to (kept == "31-5-7006"))
        }
        // ② 不同运单号 ⇒ 绝不能合并（误合并就是丢件）
        run {
            val r = dedupPkgs(
                listOf(
                    pkg("cainiao", waybill = "SF1111111111"),
                    pkg("jd", waybill = "JD2222222222")
                )
            )
            cases.add("② 不同运单号 保持 2 条（实际 ${r.size}）" to (r.size == 2))
        }
        // ③ 都无运单号、同取件码 + 同驿站 ⇒ 合并
        run {
            val r = dedupPkgs(
                listOf(
                    pkg("cainiao", code = "5-1-1001", station = "A驿站"),
                    pkg("jd", code = "5-1-1001", station = "A驿站")
                )
            )
            cases.add("③ 同码+同驿站 2→${r.size}" to (r.size == 1))
        }
        // ④ 同取件码但**不同驿站** ⇒ 不能合并（不同驿站完全可能撞同一个货架码）
        run {
            val r = dedupPkgs(
                listOf(
                    pkg("cainiao", code = "5-1-1001", station = "A驿站"),
                    pkg("jd", code = "5-1-1001", station = "B驿站")
                )
            )
            cases.add("④ 同码不同驿站 保持 2 条（实际 ${r.size}）" to (r.size == 2))
        }
        // ⑤ 相同取件码且一方缺站点 ⇒ 合并，并保留两侧标识
        run {
            val r = dedupPkgs(
                listOf(
                    pkg("pdd", code = "53-5-5510", orderSn = "PDD-ORDER-1"),
                    pkg("cainiao", code = "53-5-5510", station = "A驿站", waybill = "YT777")
                )
            )
            val kept = r.firstOrNull()
            cases.add("⑤ 同码且一方缺站点 2→${r.size}" to (r.size == 1))
            cases.add("⑤ 合并保留订单号/运单号(${kept?.optString("orderSn")}/${kept?.optString("waybillNo")})" to
                    (kept != null && kept.optString("orderSn") == "PDD-ORDER-1" && kept.optString("waybillNo") == "YT777"))
        }
        // ⑥ 相同取件码但两个明确站点不同 ⇒ 绝不能合并
        run {
            val r = dedupPkgs(
                listOf(
                    pkg("cainiao", code = "53-5-5510", station = "A驿站"),
                    pkg("pdd", code = "53-5-5510", station = "B驿站", orderSn = "PDD-ORDER-2")
                )
            )
            cases.add("⑥ 同码不同明确站点保持 2 条（实际 ${r.size}）" to (r.size == 2))
        }
        // ⑦ 拼多多只有订单号、菜鸟是另一个运单号 ⇒ 主键不同源，宁可重复也不能丢件
        run {
            val r = dedupPkgs(
                listOf(
                    pkg("pdd", orderSn = "PDD-ORDER-1"),
                    pkg("cainiao", waybill = "YT8888888888")
                )
            )
            cases.add("⑤ 拼多多订单号 vs 菜鸟运单号 保持 2 条（实际 ${r.size}）" to (r.size == 2))
        }
        // ⑥ 同源同订单号（拼多多自己重复）⇒ 压掉
        run {
            val r = dedupPkgs(
                listOf(
                    pkg("pdd", orderSn = "PDD-ORDER-1"),
                    pkg("pdd", orderSn = "PDD-ORDER-1")
                )
            )
            cases.add("⑥ 同源同订单号 2→${r.size}" to (r.size == 1))
        }
        // ⑦ 单条不能丢
        run {
            val r = dedupPkgs(listOf(pkg("jd", waybill = "JD1")))
            cases.add("⑦ 单条保持 1（实际 ${r.size}）" to (r.size == 1))
        }
        // ⑧ 合并时把缺失字段补过来（拼多多的 orderSn 必须留住，否则抠码链路会断）
        run {
            val r = dedupPkgs(
                listOf(
                    pkg("cainiao", waybill = "YT9999", code = "7-7-7"),
                    pkg("pdd", waybill = "YT9999", orderSn = "PDD-X")
                )
            )
            val kept = r.firstOrNull()
            cases.add("⑧ 合并后补回 orderSn(${kept?.optString("orderSn")})" to
                    (r.size == 1 && kept?.optString("orderSn") == "PDD-X"))
        }

        val pass = cases.count { it.second }
        val sb = StringBuilder("跨源去重自检 $pass/${cases.size}\n")
        for ((name, ok) in cases) sb.append(if (ok) "  ✓ " else "  ✗ ").append(name).append('\n')
        val msg = sb.toString()
        Log.i("DedupTest", msg)
        appToast(this, msg, true)
        try {
            java.io.File(getExternalFilesDir(null), "dedup_selftest.txt").writeText(msg)
        } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
    }

    /**
     * 本会话已经**抠到有效数据**的拼多多订单号（防止 renderMerged → 补码 → 重渲染 无限循环）。
     *
     * ★★ 2026-09-26 修：旧实现在 `fetch` **之前**就 `add(sn)`，于是**一次**网络抖动 /
     * 页面没渲染完 / 列表页定位失败，就把这个单号判成"已处理"—— 之后用户怎么下拉刷新
     * 都不会再重试，观感就是「明明有取件码却一直刷不出来」，而且不杀进程就一直这样。
     * ⇒ 现在只在真的拿到东西时才封顶；失败交给 [pddEnrichTries] 留重试额度，并封顶 3 次，
     *   避免退化成对拼多多页面的反复轮询（触碰风控，2026-09-20 亲历）。
     */
    private val pddEnriched = HashSet<String>()
    private val pddEnrichTries = HashMap<String, Int>()
    /** vc270i：纯超时计数（超时不烧重试次数；满 6 次才放弃本会话） */
    private val pddEnrichTimeouts = HashMap<String, Int>()
    private val PDD_ENRICH_MAX_TRIES = 3
    /** ★ vc339：连续「通道忙」自愈重排计数（任何非忙结果清零；满 30 次停手，等下一轮自然渲染）。
     *  PLC110 日志实证：途中栏轨迹增强批次（单例 ~90s+）占道期间，每轮渲染的补码请求都被
     *  「正在抓取中」当场拒掉，而旧逻辑拒一次烧一次额度 ⇒ 3 次烧完本会话封顶，码永远来不了。 */
    private var pddEnrichBusyRequeues = 0

    /** ★ vc342：每个单号最后一次尝试的时间（落盘用；6h 后自动衰减、重新给机会） */
    private val pddEnrichAt = HashMap<String, Long>()

    /** ★ vc342：恢复补码状态 —— 进程重启后既不"从零重试"，也不"永久放弃" */
    private fun loadEnrichState() {
        val raw = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString("pdd_enrich_state", "") ?: return
        try {
            val o = JSONObject(raw)
            val now = System.currentTimeMillis()
            var n = 0
            for (k in o.keys()) {
                val v = o.optJSONObject(k) ?: continue
                val ts = v.optLong("ts")
                if (now - ts > 6L * 3600_000L) continue      // 6h 衰减：老账不再算数
                val t = v.optInt("tries")
                val to = v.optInt("timeouts")
                if (t > 0) pddEnrichTries[k] = t
                if (to > 0) pddEnrichTimeouts[k] = to
                if (v.optBoolean("done")) pddEnriched.add(k)
                pddEnrichAt[k] = ts
                n++
            }
            Log.i("PddEnrich", "补码状态恢复：$n 条（done=${pddEnriched.size} tries=${pddEnrichTries.size}）")
        } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
    }

    /** ★ vc342：落盘补码状态（每次补码结束调一次，数据量很小） */
    private fun saveEnrichState() {
        try {
            val o = JSONObject()
            val keys = LinkedHashSet<String>()
            keys.addAll(pddEnrichTries.keys)
            keys.addAll(pddEnrichTimeouts.keys)
            keys.addAll(pddEnriched)
            for (k in keys) {
                o.put(k, JSONObject().apply {
                    put("tries", pddEnrichTries[k] ?: 0)
                    put("timeouts", pddEnrichTimeouts[k] ?: 0)
                    put("done", pddEnriched.contains(k))
                    put("ts", pddEnrichAt[k] ?: System.currentTimeMillis())
                })
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("pdd_enrich_state", o.toString()).apply()
        } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
    }

    // ================= ★ vc342：批量补码作业（P0-1）=================
    //
    // 旧结构（vc341）：renderMerged 末尾调 enrichOnePddCode → 每轮只补 1 张 → 补到后
    //   renderMerged → 再补下一张。4 张缺码 = 4 趟串行、每趟 10~20s，必然撞上 70s 超时，
    //   所以"一次刷新拿全部码"结构上不可能。
    //
    // 新结构：一次刷新只跑**一次**批量作业，按"便宜 → 贵"三级走完再统一重绘：
    //   ① PddNative.scan —— 纯 HTTP（订单页 60s 缓存 + cabinet 直查），通常 2~5s 拿全码
    //   ② PddExpressChannel.openPickupPageBatch —— 取件页一次配对多单（~5s，只对有待运单号的）
    //   ③ PddTraceChannel 逐单（10~20s/单）—— 只兜最后 1~2 单，走优先级队列

    private var pddCodeJobRunning = false
    private var pddCodeJobGen = -1

    /** ★ vc342：两次批量作业的最小间隔 —— 订单页晚回来时允许再跑一批，但不许抖动重入 */
    private var lastPddCodeJobAt = 0L
    private val CODE_JOB_MIN_INTERVAL_MS = TimeGates.CODE_JOB_MIN_INTERVAL_MS

    /**
     * 同一单的批量链最小重试间隔。
     * ★ vc342：6h → **3 分钟**。6h 太狠：一次网络抖动就把这些单锁死半天，
     *   用户反复下拉都补不回来（观感比旧版更差）。3 分钟足够挡住连续刷新的抖动，
     *   又不至于让"再试一次"失效。逐单开页那条最贵的链仍由 [pddEnrichTries] 单独封顶。
     */
    private val CODE_JOB_MIN_GAP_MS = TimeGates.CODE_JOB_MIN_GAP_MS

    private fun ensurePddCodesBatch(pending: List<JSONObject>) {
        if (pddCodeJobRunning) return
        // ★ vc342：不再"同一轮只跑一次" —— 订单页可能比首批渲染晚 30s 才回来，
        //   那时新出现的缺码单否则要等用户再刷新一次。改成 10s 间隔 + 逐单 6h 限频：
        //   同名单跑过就不会再跑，新出现的单才触发第二批。
        val nowJob = System.currentTimeMillis()
        if (nowJob - lastPddCodeJobAt < CODE_JOB_MIN_INTERVAL_MS) return
        val targets = pending.filter {
            it.optString("src") == "pdd" && it.optString("pickupCode").isEmpty() &&
                    it.optString("orderSn").isNotEmpty() &&
                    // ★ vc361：已打「已取」灰标的卡不再补码（补了也没意义，还白跑页面）
                    it.optLong("pickedAt", 0L) == 0L
        }
        if (targets.isEmpty()) return
        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val fresh = targets.filter {
            now - sp.getLong("codejob_${it.optString("orderSn")}", 0L) >= CODE_JOB_MIN_GAP_MS
        }
        if (fresh.isEmpty()) {
            Log.i("PddCodeJob", "缺码 ${targets.size} 单，但全部在 3 分钟限频内 —— 本轮跳过")
            return
        }
        pddCodeJobRunning = true
        pddCodeJobGen = pickupRoundGen
        lastPddCodeJobAt = now
        val gen = pickupRoundGen
        val sns = fresh.map { it.optString("orderSn") }
        sp.edit().apply { for (sn in sns) putLong("codejob_$sn", now) }.apply()
        Log.i("PddCodeJob", "批量补码开始：${sns.size} 单 $sns")

        val missing = LinkedHashSet(sns)
        // ① 纯 HTTP 批量扫描 —— 不占 WebView 通道，PddChannel 忙也照样能跑
        waybillIo.execute {
            val got = HashMap<String, Pair<String, String>>()
            var scanMsg = ""
            try {
                val ck = PddChannel.cookies(this)
                if (ck.isNotEmpty()) {
                    val uid = PddChannel.uid(this).ifEmpty { PddChannel.uidFromCookie(ck) }
                    val token = Regex("PDDAccessToken=([^;]+)").find(ck)
                        ?.groupValues?.get(1)?.trim().orEmpty()
                    val ses = PddNative.Session(token = token, uid = uid, cookie = ck)
                    if (ses.usable) {
                        val rep = PddNative.scan(ses, maxPages = 2, cap = 40)
                        if (rep.needLogin) PddChannel.markLoginDead(this)
                        scanMsg = "err=${rep.message} 扫描=${rep.scanned} 候选=${rep.waitingCandidates}"
                        for (p in rep.items) {
                            if (p.code.isNotEmpty() && p.orderSn.isNotEmpty()) {
                                got[p.orderSn] = p.code to p.place
                            }
                        }
                    } else scanMsg = "登录态不可用"
                } else scanMsg = "无 cookie"
            } catch (t: Throwable) {
                scanMsg = "异常:${t.message}"
            }
            mainHandler.post {
                if (gen != pickupRoundGen) {
                    endPddCodeJob()
                    return@post
                }
                var changed = false
                for (sn in missing.toList()) {
                    val cp = got[sn] ?: continue
                    if (applyPddCodeQuiet(sn, "", cp.first, cp.second)) changed = true
                    missing.remove(sn)
                }
                if (changed) renderMerged()
                Log.i("PddCodeJob", "① 原生扫描：补 ${got.size} 单，仍缺 ${missing.size}（$scanMsg）")
                step2PickupPage(gen, missing)
            }
        }
    }

    /** ② 取件页批量配对（一次开页配多单；只对"有运单号"的缺码单有意义） */
    private fun step2PickupPage(gen: Int, missing: MutableSet<String>) {
        if (gen != pickupRoundGen || missing.isEmpty() || pickupLayer == null) {
            step3Trace(gen, missing)
            return
        }
        val wb2sn = HashMap<String, String>()
        for (sn in missing) {
            val wb = pddPkgs.firstOrNull { it.optString("orderSn") == sn }
                ?.optString("waybillNo").orEmpty()
            if (wb.isNotEmpty()) wb2sn[wb] = sn
        }
        if (wb2sn.isEmpty()) {
            Log.i("PddCodeJob", "② 缺码单都没有运单号，跳过取件页配对")
            step3Trace(gen, missing)
            return
        }
        Log.i("PddCodeJob", "② 取件页批量配对：${wb2sn.size} 单")
        PddExpressChannel.openPickupPageBatch(this, wb2sn.keys.toList()) { map ->
            if (gen != pickupRoundGen) {
                endPddCodeJob()
                return@openPickupPageBatch
            }
            var changed = false
            for ((wb, code) in map) {
                val sn = wb2sn[wb] ?: continue
                if (applyPddCodeQuiet(sn, wb, code, "")) changed = true
                missing.remove(sn)
            }
            if (changed) renderMerged()
            Log.i("PddCodeJob", "② 取件页配对：补 ${map.size} 单，仍缺 ${missing.size}")
            step3Trace(gen, missing)
        }
    }

    /** ③ 逐单开物流页（最贵）—— 每轮最多 4 单（原 2 单太保守，缺 5 单要跑 3 轮），走优先级队列 */
    private fun step3Trace(gen: Int, missing: MutableSet<String>) {
        // ★ vc507：2 → 4；超出部分留给下一轮（有日志，不再静默丢）
        if (missing.size > 4) Log.i("PddCodeJob", "逐单补码：缺 ${missing.size} 单，本轮先补 4 单，其余下轮")
        val todo = missing.take(4)
        if (todo.isEmpty() || pickupLayer == null) {
            endPddCodeJob()
            return
        }
        Log.i("PddCodeJob", "③ 逐单补码：$todo")
        fun next(i: Int) {
            if (gen != pickupRoundGen || i >= todo.size) {
                endPddCodeJob()
                return
            }
            val sn = todo[i]
            val card = pddPkgs.firstOrNull { it.optString("orderSn") == sn }
            if (card == null) {
                next(i + 1)
                return
            }
            enrichOnePddCode(listOf(card)) {
                mainHandler.postDelayed({ next(i + 1) }, 400)
            }
        }
        next(0)
    }

    private fun endPddCodeJob() {
        pddCodeJobRunning = false
        saveEnrichState()
    }

    /**
     * ★ vc345：把"其实已到站"的拼多多在途单搬进取件码栏。
     * 只在 cabinet 明确给出取件码时调用（没码不动，免得把真在途误搬走）。
     */
    private fun promotePddTransitToPickup(t: PddNative.TransitItem, code: String, place: String) {
        if (t.orderSn.isEmpty() || code.isEmpty()) return
        applyPddCodeQuiet(t.orderSn, t.waybill, code, place, t.goods)
        Log.i(
            "PddNative",
            "已到站 → 转取件码栏：${t.orderSn} code=$code 地址=${place.ifEmpty { "无" }}"
        )
    }

    /**
     * ★ vc356：**已到站、但接口没给码**（菜鸟驿站这类，码在菜鸟手里）——
     * 以前这种单会被"途中剔除 + 取件码要有码"两条规矩夹掉，两栏都看不见。
     * 现在先出一张占位卡（主信息位显示"正在拉取取件码…"），并触发补码链。
     */
    private fun promotePddTransitToPickupNoCode(t: PddNative.TransitItem, place: String) {
        if (t.orderSn.isEmpty()) return
        // ★★ vc634：**不再往取件码栏塞"取件码获取中"占位卡**（同志 13:08 实测：2 张占位卡
        //   把"5 个待取"撑成 5 件，且永远补不到码）。
        //   这类件留在**途中栏**（那里有地址和轨迹），补到码后由 applyNativePddCode 自动搬过来。
        if (pddPkgs.any { it.optString("orderSn") == t.orderSn }) {
            // 已经有卡（可能已补到码）⇒ 什么都不做
        } else {
            Log.i(
                "PddNative",
                "已到站但无码 → 留在途中栏（不占取件码位）：${t.orderSn} 地址=${place.ifEmpty { "无" }}"
            )
        }
        // 码可能只在淘宝/菜鸟那边 —— 有运单号就按运单号去问
        if (t.waybill.isNotEmpty()) taobaoFillPddPickupCodes()
    }

    /**
     * 批量补码的落点：写缓存 + 更新卡片，**不重绘**（作业统一重绘一次）。
     * @return 是否真的产生了变化
     */
    private fun applyPddCodeQuiet(
        sn: String, waybill: String, code: String, place: String, goods: String = ""
    ): Boolean {
        if (sn.isEmpty() || code.isEmpty()) return false
        val key = "pdd:$sn"
        val old = pddPkgs.firstOrNull { it.optString("orderSn") == sn }
        val prevCached = PddCodeStore.get(this, key)
        // ★ vc361：新地址为空 ⇒ 沿用缓存里的旧地址，别把卡片底部洗成「待取件+时间」
        val prevStation = prevCached?.station.orEmpty()
        // ★★ vc691：**真格口码/具体站名优先** —— 这条链每轮都会被 cabinet 的"单号后五位 +
        //   菜鸟驿站"冲一遍，不护住的话反查来的真码活不过下一轮（与 vc662/vc666 同口径）。
        val effWb = waybill.ifEmpty { old?.optString("waybillNo").orEmpty() }
            .ifEmpty { prevCached?.waybill.orEmpty() }
        val effCodeRaw = preferRealPddCode(prevCached?.code.orEmpty(), effWb, code)
        // ★★ v1.4.13：**不许拿"别人已经在用的码"覆盖本单已有的码**。
        //   事故（2026-11-10 13:45 真机）：润唇膏 261107-067916295711958 的真码是 114-4-9453，
        //   被 `73-3-5888`（同时挂在 3 个 11-05 的旧单上）覆盖 ⇒ 首页那格码变成重复的 73-3-5888，
        //   按码去重后润唇膏整个从首页消失，用户拿着错码去驿站也打不开柜。
        //   判据：新码**已被别的订单占用**、且本单**已经有码** ⇒ 保留本单的旧码。
        //   （本单原本没码时照常写入；新码没被占用时也照常升级 —— 比如短码换成真格口码。）
        val effCode = run {
            val prev = prevCached?.code.orEmpty().trim()
            val inc = effCodeRaw.trim()
            if (prev.isEmpty() || inc.isEmpty() || prev == inc) return@run effCodeRaw
            val takenByOther = PddCodeStore.all(this).entries.any { (k, v) ->
                k != key && v.code.trim() == inc
            }
            if (takenByOther) {
                Log.w(
                    "PddCodeMatch",
                    "保留本单旧码 $sn：$prev（新码 $inc 已被其它订单占用，不覆盖）"
                )
                prev
            } else effCodeRaw
        }
        val station = preferRealPddStation(prevStation, place.take(60)).ifEmpty { prevStation }
        // ★★ v1.4.11：**别再拿取件码判归属**（v1.4.10 那道校验放宽到"同天下单"才拦）。
        //   事故复盘（同志 2026-11-10）：我按「114-4-9453 是昨天已取过的快递」判定这枚码
        //   是错配、拒写；可他随后澄清「114-4-9453 是今天刚到的润唇膏的码，我还没取」——
        //   驿站把**取过的格口码重新分配**给了新件，码本身没错，是我把"复用"当成了"错配"。
        //   ⇒ 现在的判据：只有「同码 + 对方已取件 + **同一天下单**」才认定是页面抽取污染
        //   （同一格口不可能同一天分给两件）；跨天的一律放行，各自保留自己的码。
        run {
            val c = effCode.trim()
            if (c.isNotEmpty()) {
                val myDay = PddNative.snToDateMs(sn) ?: 0L
                val owner = PddCodeStore.all(this).entries.firstOrNull { (k, v) ->
                    k != key && v.code.trim() == c && v.pickedAt != 0L && myDay != 0L &&
                            PddNative.snToDateMs(k.substringAfter(':', k)) == myDay
                }
                if (owner != null) {
                    Log.w(
                        "PddCodeMatch",
                        "拒绝写码 $sn ← $c：与已取件的 ${owner.key} 同码、且同天下单（判为抽取污染）"
                    )
                    return false
                }
            }
        }
        if (key !in pickupMutedAll()) {
            PddCodeStore.put(
                this, key, PddCodeStore.Cached(
                    src = "pdd", code = effCode, station = station,
                    waybill = effWb,
                    latest = old?.optString("latest").orEmpty(), ts = System.currentTimeMillis()
                )
            )
        }
        // ★★ vc691：**短码反查接到这个出口**。在途单的 cabinet 只给「单号后五位」
        //   （日志：菜鸟单凭单号后五位取件 order=261101… code=99100），以前这条链不排队，
        //   所以永远不去淘宝校园驿站换真格口码 —— 同志 11-04 报的"为什么不查询"就是这里。
        queuePddShortCode(sn, effWb, effCode, station)
        if (old == null) {
            pddPkgs.add(JSONObject().apply {
                put("src", "pdd"); put("orderSn", sn)
                put("pickupCode", effCode); put("waybillNo", effWb)
                put("upCpName", "拼多多"); put("statusText", "在驿站待取")
                // ★ vc345：有商品名就用（在途转过来的单带着），否则用尾号兜底
                put("goodsName", goods.ifEmpty { "拼多多包裹·" + sn.takeLast(6) })
                if (station.isNotEmpty()) {
                    put("stationText", station); put("nickName", station)
                }
            })
            Log.i("PddCodeJob", "补码（新建卡）$sn → $effCode ${if (station.isEmpty()) "" else "@$station"}")
            return true
        }
        var changed = false
        val curCode = old.optString("pickupCode")
        if (curCode != effCode && (curCode.isEmpty() ||
                (PickupCodeResolver.isShortCode(effWb, curCode) &&
                        !PickupCodeResolver.isShortCode(effWb, effCode)))
        ) {
            old.put("pickupCode", effCode); changed = true
        }
        if (effWb.isNotEmpty() && old.optString("waybillNo").isEmpty()) {
            old.put("waybillNo", effWb); changed = true
        }
        // ★ vc691：泛称（菜鸟驿站/驿站/空）也允许被更具体的站名换掉 —— 反查回来的站名才落得到卡上
        val curSt = old.optString("stationText")
        val curGeneric = curSt.isEmpty() || curSt == "菜鸟驿站" || curSt == "驿站" ||
                curSt == "快递柜" || curSt == "代收点"
        if (station.isNotEmpty() && curGeneric && station != curSt) {
            old.put("stationText", station)
            val curNick = old.optString("nickName")
            if (curNick.isEmpty() || curNick == curSt) old.put("nickName", station)
            changed = true
        }
        if (changed) Log.i("PddCodeJob", "补码 $sn → $effCode ${if (station.isEmpty()) "" else "@$station"}")
        return changed
    }

    /**
     * 拼多多取件码补齐：从 `pending` 里挑**第一张**缺取件码、且有订单号的拼多多卡，
     * 去列表页点它的「查看物流」抠码（`PddTraceChannel`），抠到就更新数据并重渲染。
     * 每轮渲染只补 1 张 —— 多张时靠重渲染逐轮补齐，额度由 [pddEnrichTries] 兜底。
     */
    private fun enrichOnePddCode(pending: List<JSONObject>, onFinished: (() -> Unit)? = null) {
        // ★ vc270i：目标改为**轮转**（挑重试次数最少的缺码单）——原"挑第一张"会让
        //   卡住的单饿死后面的（4 单 × 55s 超时 = 后面三张永远轮不到）
        val target = pending.filter {
            it.optString("src") == "pdd" &&
                    it.optString("pickupCode").isEmpty() &&
                    it.optString("orderSn").isNotEmpty() &&
                    !pddEnriched.contains(it.optString("orderSn")) &&
                    (pddEnrichTries[it.optString("orderSn")] ?: 0) < PDD_ENRICH_MAX_TRIES
        }.minByOrNull { (pddEnrichTries[it.optString("orderSn")] ?: 0) }
            ?: run {
                // ★ vc270n：补码没启动时必须说清为什么 —— 10-16 真机日志里
                //   PddTraceChannel 一次都没跑、裸卡降级 0 次、45天过滤 0 次，
                //   光看界面根本分不清是"没抓到包裹"还是"抓到了但被丢在半路"。
                val pddAll = pending.filter { it.optString("src") == "pdd" }
                val noCode = pddAll.count { it.optString("pickupCode").isEmpty() }
                val noSn = pddAll.count { it.optString("pickupCode").isEmpty() && it.optString("orderSn").isEmpty() }
                val capped = pddAll.count {
                    it.optString("pickupCode").isEmpty() && pddEnriched.contains(it.optString("orderSn"))
                }
                Log.i("PddEnrich", "本轮无补码目标：pending=${pending.size} pdd=${pddAll.size} " +
                        "缺码=$noCode（其中无订单号 $noSn / 已封顶 $capped）" +
                        // ★ vc280：删除屏蔽数 —— 10-16 小米机"4 个码全拉不到"的真相就是
                        //   3 个待取单被用户删过进了屏蔽名单（pending=0 而 pddPkgs=18）
                        " | 删除屏蔽=${pickupMutedAll().size}（被删过的单永不再补，空态有恢复入口）")
                onFinished?.invoke()
                return
            }
        val sn = target.optString("orderSn")
        val roundAtCall = pickupRoundGen
        pddEnrichTries[sn] = (pddEnrichTries[sn] ?: 0) + 1
        pddEnrichAt[sn] = System.currentTimeMillis()
        PddTraceChannel.fetch(this, sn) { r ->
            runOnUiThread {
                if (roundAtCall != pickupRoundGen) {
                    // ★ vc639：补码页可能跨刷新返回；旧轮结果不许新建/改写当前卡片。
                    //   正式订单列表会在新轮收尾时统一确认，避免临时码闪现后又消失。
                    Log.i("PddEnrich", "旧刷新轮结果丢弃，不重绘：请求=$sn 页面=${r.realOrderSn.ifEmpty { "?" }} code=${r.pickupCode.orEmpty()}")
                    onFinished?.invoke()
                    return@runOnUiThread
                }
                Log.i(
                    "PddEnrich",
                    "sn=$sn try=${pddEnrichTries[sn]}/$PDD_ENRICH_MAX_TRIES " +
                            "waybill=${r.waybillNo} pickup=${r.pickupCode} err=${r.err}"
                )
                // ★★ 回填一律以**物流页的真实订单号**为准（见 Result.realOrderSn）：
                //   我们点的是"列表页第一个待取件"，它未必等于传入的 sn（实测传 260919、
                //   页面却是 260923）。仍按 sn 找卡片，就会把这张单的取件码写到另一张单上。
                val own = r.realOrderSn.ifEmpty { sn }
                if (own != sn) Log.w("PddEnrich", "请求 $sn，但物流页是 $own —— 以页面为准")
                // ★ 抠到有效数据才算成功；否则保留额度，等下一次渲染继续补
                val useful = !r.pickupCode.isNullOrEmpty() || r.waybillNo.isNotEmpty() ||
                        PddTraceChannel.parseTraceLines(r.text).isNotEmpty()
                if (useful) {
                    pddEnriched.add(sn)
                    if (own != sn) pddEnriched.add(own)
                    pddEnrichBusyRequeues = 0
                } else if (r.err == "超时") {
                    // ★ vc270i：超时**不烧重试次数**（小米机 55s 内页面没产出 ≠ 单子有问题，
                    //   23:14 实测 3 轮超时后整单被永久放弃 ⇒ 码永远来不了）。
                    //   退回一次并单独记超时数；同一单超时满 6 次才放弃本会话。
                    pddEnrichBusyRequeues = 0
                    pddEnrichTries[sn] = ((pddEnrichTries[sn] ?: 1) - 1).coerceAtLeast(0)
                    val to = (pddEnrichTimeouts[sn] ?: 0) + 1
                    pddEnrichTimeouts[sn] = to
                    if (to >= 6) {
                        pddEnriched.add(sn)
                        Log.w("PddEnrich", "sn=$sn 超时已 $to 次，本会话放弃补码")
                    } else {
                        Log.w("PddEnrich", "sn=$sn 超时（$to/6）—— 不烧重试次数，下次渲染再试")
                    }
                } else if (r.err == "已在队列") {
                    // ★ vc342：同单已被另一个入口排进队列（现在不再"忙就拒绝"）——
                    //   不烧额度，12s 后若仍缺码再排一次（那时队列里的那趟已经跑完）
                    pddEnrichTries[sn] = ((pddEnrichTries[sn] ?: 1) - 1).coerceAtLeast(0)
                    if (pddEnrichBusyRequeues < 30) {
                        pddEnrichBusyRequeues++
                        root.postDelayed({
                            val still = pddPkgs.firstOrNull { it.optString("orderSn") == sn }
                            if (still != null && still.optString("pickupCode").isEmpty() &&
                                !pddEnriched.contains(sn) && (pddEnrichTries[sn] ?: 0) < PDD_ENRICH_MAX_TRIES
                            ) enrichOnePddCode(listOf(still))
                        }, 12_000L)
                    }
                } else if (r.err?.contains(PICKUP_GATE_MSG) == true) {
                    // ★ vc339（PLC110 实证）：「正在抓取中」= 通道忙拒，**不开页面零成本** ——
                    //   旧逻辑落到"没拿到有效数据"分支白烧额度，长批次占道几分钟就把 3 次
                    //   烧光 ⇒ 本会话永远封顶。处理：退回额度 + 10s 后自排一次重试
                    //   （单还缺码且没被封顶才会真的再跑；连续 30 次忙拒后停手等自然渲染）。
                    pddEnrichTries[sn] = ((pddEnrichTries[sn] ?: 1) - 1).coerceAtLeast(0)
                    if (pddEnrichBusyRequeues < 30) {
                        pddEnrichBusyRequeues++
                        Log.i("PddEnrich", "sn=$sn 通道忙（不烧额度）—— 10s 后自排重试 ${pddEnrichBusyRequeues}/30")
                        root.postDelayed({
                            val still = pddPkgs.firstOrNull { it.optString("orderSn") == sn }
                            if (still != null && still.optString("pickupCode").isEmpty() &&
                                !pddEnriched.contains(sn) && (pddEnrichTries[sn] ?: 0) < PDD_ENRICH_MAX_TRIES
                            ) enrichOnePddCode(listOf(still))
                        }, 10_000L)
                    } else {
                        Log.i("PddEnrich", "sn=$sn 通道忙且自排已达 30 次 —— 停手，等下一轮自然渲染")
                    }
                } else Log.w("PddEnrich", "本轮没拿到有效数据 sn=$sn err=${r.err} —— 下次渲染继续重试")
                var changed = false
                var q = pddPkgs.firstOrNull { it.optString("orderSn") == own }
                    ?: pddPkgs.firstOrNull { it.optString("orderSn") == sn }
                if (q == null) {
                    // 两边都对不上 ⇒ 以物流页为准新建一张。
                    // （track/status 的 IN_CABINET 口径和"列表页待取件"不是同一套，
                    //   对不上时物流页才是真的：它有运单号、有取件码、有驿站。）
                    q = JSONObject().apply {
                        put("src", "pdd"); put("orderSn", own)
                        put("pickupCode", ""); put("waybillNo", "")
                        put("upCpName", "拼多多"); put("statusText", "在驿站待取")
                        put("goodsName", ""); put("stationName", "")
                    }
                    pddPkgs.add(q)
                    changed = true
                    Log.i("PddEnrich", "按物流页新建卡片 sn=$own")
                }
                run {
                    // ★★ vc691：反查出口③（逐单补码）—— 物流页也可能只给「单号后五位」，
                    //   同样要护住已有的真码、并排队去淘宝换真格口码。
                    val prevCached = PddCodeStore.get(this, "pdd:$own")
                    val effWb = r.waybillNo.ifEmpty { q.optString("waybillNo") }
                        .ifEmpty { prevCached?.waybill.orEmpty() }
                    val codeIn = r.pickupCode.orEmpty()
                    if (codeIn.isNotEmpty()) {
                        val effCode = preferRealPddCode(prevCached?.code.orEmpty(), effWb, codeIn)
                        q.put("pickupCode", effCode); changed = true
                    }
                    if (effWb.isNotEmpty() && q.optString("waybillNo").isEmpty()) {
                        q.put("waybillNo", effWb); changed = true
                    }
                    val lines = PddTraceChannel.parseTraceLines(r.text)
                    if (lines.isNotEmpty()) {
                        q.put("latest", lines.first())
                        q.put("tip", lines.first())
                        changed = true
                    }
                    // ★ vc341：行族放宽 + 「取件地址:」句式先抠地址段（stationLineOf 统一口径）
                    val st = stationLineOf(lines)
                    if (st != null) {
                        q.put("stationText", st.take(60))
                        q.put("nickName", st.take(60))
                        changed = true
                    }
                    // ★ 抠到的码**写进缓存** ⇒ 之后刷新不用再开物流页（常态显示，见 syncPddCodeCache）
                    val code = r.pickupCode
                    if (!code.isNullOrEmpty()) {
                        val effCode = preferRealPddCode(prevCached?.code.orEmpty(), effWb, code)
                        val effStation = preferRealPddStation(
                            prevCached?.station.orEmpty(), q.optString("stationText").take(60)
                        )
                        // ★ 键 = src:单号（三源通用缓存，见 syncPickupCodeCache）
                        PddCodeStore.put(
                            this, "pdd:$own", PddCodeStore.Cached(
                                src = "pdd",
                                code = effCode,
                                station = effStation,
                                waybill = effWb,
                                latest = lines.firstOrNull().orEmpty().take(220),
                                ts = System.currentTimeMillis()
                            )
                        )
                        Log.i("PddEnrich", "写入取件码缓存 sn=$own code=$effCode")
                        // ★★ vc691：排队去换真格口码（6h 限频在 queuePddShortCode 里）
                        queuePddShortCode(own, effWb, effCode, effStation)
                        // ★ vc341：码到手、三个地址位仍全空 ⇒ 问一次 cabinet 接口补（快递柜单）
                        if (q.optString("stationText").isEmpty() &&
                            q.optString("stationName").isEmpty() &&
                            q.optString("nickName").isEmpty()
                        ) ensurePddStationAddress(own)
                    }
                }
                if (changed && pickupLayer != null) renderMerged()
                // ★ vc342：补码状态落盘（进程被杀后不用从零再来），并通知作业推进下一单
                pddEnrichAt[own] = System.currentTimeMillis()
                if (own != sn) pddEnrichAt[sn] = System.currentTimeMillis()
                saveEnrichState()
                onFinished?.invoke()
            }
        }
    }

    /**
     * ★★ v1.4.8：**把内存卡片上的「已取件」标记与磁盘对齐**（[PddCodeStore] 是唯一真相）。
     *
     * ## 症状（同志 2026-11-10 报）
     * 取件码页点「取消已取件」：toast 说成功了，卡片却照样是灰的；要杀进程重进才恢复。
     * 更要命的是**下一次点还是走「取消」分支** —— 判定用的 `pkg.pickedAt` 是内存里的旧值，
     * 用户于是感觉"怎么点都取消不掉"。
     *
     * ## 根因
     * 标记/取消只写了 [PddCodeStore] + [PickedStore]，**没有回写内存里那批 JSONObject**；
     * 而 [renderMerged] 全程只读 `p.optLong("pickedAt")`，一处都没回查存储。
     * 真机实证（2026-11-10 10:28）：
     *   `I/PickedStore: 取消已取件 3 个身份`
     *   `I/PickupSync: 标记已取件同步：pdd:261107-067916295711958 on=false`
     *   `I/AcctScope: 卡片按钮切换已取件：… → 同件缓存 1 条`
     * 三条都打了，界面不动；force-stop 重启后卡片立刻恢复未取件 —— 磁盘是对的，内存是旧的。
     *
     * ## 做法
     * 渲染前按「订单号 / 运单号 / 取件码」把每张卡对到缓存条目上，用缓存里的 pickedAt
     * 覆盖内存值。一个件命中多个身份时取**最近一次**标记（与 [PickedStore.of] 同口径）。
     * 缓存里没有这条（刚抓到、还没落盘）时**保持原值不动**，免得把实时卡误判成未取件。
     */
    private fun syncPickedFlagsFromStore() {
        val store = try {
            PddCodeStore.all(this)
        } catch (t: Throwable) {
            SafeRun.w("PickupSync", t)
            return
        }
        if (store.isEmpty()) return
        // 索引：键名去前缀（pdd:订单号 → 订单号）+ 取件码 + 运单号 → 打标时刻
        val idx = HashMap<String, Long>(store.size * 3)
        for ((k, c) in store) {
            val sn = k.substringAfter(':', k)
            if (sn.isNotEmpty()) idx[sn] = c.pickedAt
            if (c.code.isNotBlank()) idx[c.code] = c.pickedAt
            if (c.waybill.isNotBlank()) idx[c.waybill] = c.pickedAt
        }
        var fixed = 0
        fun sync(list: List<JSONObject>) {
            for (p in list) {
                var v = 0L
                var hit = false
                for (id in parcelIds(p.optString("src"), p)) {
                    val t = idx[id] ?: continue
                    hit = true
                    if (t > v) v = t
                }
                if (!hit) continue
                if (p.optLong("pickedAt", 0L) != v) {
                    p.put("pickedAt", v)
                    fixed++
                }
            }
        }
        sync(pddPkgs)
        sync(cainiaoPkgs)
        sync(jdPkgs)
        sync(miPkgs)
        sync(manualPkgs)
        sync(tbTransit)
        sync(tbPendingCards.values.toList())
        if (fixed > 0) Log.i("PickupSync", "渲染前对齐已取件标记：修正 $fixed 张卡")
    }

    private fun renderMerged() {
        try {
            LogEvents.event(this, "render", mapOf(
                "pdd" to pddPkgs.size, "cainiao" to cainiaoPkgs.size,
                "jd" to jdPkgs.size, "mi" to miPkgs.size, "manual" to manualPkgs.size
            ))
        } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        val list = pickupListContainer ?: return
        cancelPickupTimeout()

        // ★★ v1.4.8：渲染前先把「已取件」标记跟磁盘对齐 —— 否则"取消已取件"当场不生效。
        syncPickedFlagsFromStore()

        // ★★ v1.4.15：渲染前扫一遍"一码多单" —— 每轮都扫（只在真发现时才写盘），
        //   因为抓取链随时可能把借来的码重新塞进缓存，只在启动时扫一次等于没扫。
        runCatching { PddCodeStore.sweepShared(this) }

        // ★ vc270n：一眼看出"哪个源是空的" —— 本项目已三次栽在静默丢弃上
        //   （vc182 手动单号被误伤 / 盲试假轨迹 / 在途不显示无法定位），
        //   所以每次渲染都把各源实况摊开写，出问题不用再靠猜。
        Log.i("PddCache", "渲染开始：cainiao=${cainiaoPkgs.size} pdd=${pddPkgs.size} " +
                "jd=${jdPkgs.size} mi=${miPkgs.size} manual=${manualPkgs.size}" +
                // ★ vc280：被长按删过的单数 —— "抓到了却不上屏"时第一嫌疑就是它
                " 删除屏蔽=${pickupMutedAll().size}")

        val pending = ArrayList<JSONObject>()
        for (p in cainiaoPkgs) if (!p.optBoolean("pickedWaybill")) pending.add(p)
        // ★ vc301/vc302：淘宝在途里「已到站」的件进取件码栏 ——
        //   以前第 4 栏只吃菜鸟驿站接口（queryMultiStationPackages），而极兔/圆通等
        //   **自营驿站**的件不在那个接口里 ⇒ 已到站（甚至取件码都出来了）却永远不出现，
        //   用户以为件丢了（2026-10-17 极兔 JT3180960043943 实测）。
        //   ★ vc302 分流规矩：到站（待取/入柜/代收点…）→ 只在「取件码」；
        //     在途/派送中 → 只在「在途快递」（renderInTransit 那侧剔到站卡）。
        //   与驿站卡同运单号的会在下面 dedupPkgs 合并（驿站卡有码分高，优先保留）。
        //   无码时 makePackageCard 有兜底显示（状态文案），不会出空行。
        for (t in tbTransit) {
            // ★ vc354/vc357：枚举说已取走 ⇒ 开关开着就打灰标并保留 24h（否则剔除）
            if (tbEnumVerdict(
                    t.optString("statusCode"), t.optString("enumDesc"), t.optString("enumSeq")
                ) == TB_EN_TAKEN) {
                if (autoPickedTagOn()) {
                    if (t.optLong("pickedAt", 0L) == 0L) {
                        t.put("pickedAt", System.currentTimeMillis())
                        val wb = t.optString("realWaybill").ifEmpty { t.optString("waybillNo") }
                        if (wb.isNotEmpty()) setManualPicked("tb:$wb", true)
                        Log.i("TBEnum", "自动打已取标签：$wb")
                    }
                    pending.add(t)
                }
                continue
            }
            if (tbCardArrived(t)) {
                // ★ vc303：Resolver 回填的驿站名存在**缓存**里（实时详情页抠不到站名）
                //   ⇒ 实时卡上屏前从缓存继承（否则实时卡站名空、明细行退回单号尾号）
                val wb = t.optString("waybillNo")
                if (t.optString("stationText").isEmpty() && wb.isNotEmpty()) {
                    PddCodeStore.get(this, "tb:$wb")?.let { c ->
                        if (c.station.isNotEmpty()) t.put("stationText", c.station)
                        if (t.optString("pickupCode").isEmpty() && c.code.isNotEmpty())
                            t.put("pickupCode", c.code)
                    }
                }
                pending.add(t)
            }
        }
        // ★ vc524：到站待补码卡（内存占位）也进第4栏 ——
        //   否则"已到站但还没码"的件会两栏都看不到（交接空窗）。
        if (tbPendingCards.isNotEmpty()) pending.addAll(tbPendingCards.values)
        // ★ vc305：到站且「疑似短码」⇒ 自动查真实格口码 —— **批量版**：
        //   一次收集全部待查单（到站 + 无真码 + 6h 限频过），整条链只开一次拼多多取件页，
        //   驿站全部件的「单号↔码」一次抓回（旧链每单各开一次页，两单要排 8s 队）。
        maybeAutoQueryRealCodeBatch()
        // ★ vc341：PDD 卡「有码没地址」的兜底扫描 —— 谁缺谁按订单号问一次 cabinet 接口
        //   （6h 限频在 ensurePddStationAddress 里）。覆盖 native 兜底/缓存恢复等所有来路，
        //   不只 AutoCode 链 —— PME110 那张"码来自兜底链、AutoCode 永远不启动"的老卡就靠这扫活。
        for (p in pddPkgs) {
            if (p.optString("pickupCode").isNotEmpty() &&
                p.optString("stationText").isEmpty() &&
                p.optString("stationName").isEmpty() &&
                p.optString("nickName").isEmpty()
            ) ensurePddStationAddress(p.optString("orderSn"))
        }
        // ★ vc302：再从**持久缓存**补齐（tbTransit 是内存态，重启即空；而菜鸟驿站接口
        //   查不到极兔等自营驿站的件 ⇒ 不补的话重启后「途中页没有、取件码页也没有」）。
        //   实时 tbTransit 里已有的单跳过（实时数据更新鲜）；48h 未更新的兜底过期。
        run {
            val nowMs = System.currentTimeMillis()
            for ((k, c) in PddCodeStore.all(this)) {
                if (c.src != "tb") continue
                if (tbPendingCards.containsKey(c.waybill)) continue   // ★ vc524：已有占位卡，别重复
                if (nowMs - c.ts > 48L * 3600_000L) {
                    PddCodeStore.remove(this, k)
                    Log.i("PddCache", "清掉 48h 未更新的淘宝到站缓存：$k")
                    continue
                }
                if (pending.any { it.optString("waybillNo") == c.waybill }) continue
                pending.add(tbCachedCard(c))
            }
        }
        // 多来源聚合：拼多多 + 京东（各自登录过才有数据）
        pending.addAll(pddPkgs)
        pending.addAll(jdPkgs)
        pending.addAll(miPkgs)
        pending.addAll(manualPkgs)
        // ★ vc637：PDD track/status 的 SEND 是已发货/在途，不是到站待取。
        // 即使旧数据链路把它包装成“在驿站待取”，也不能进入取件码页。
        pending.removeAll { p ->
            p.optString("src") == "pdd" &&
                p.optString("pickupCode").isEmpty() &&
                PddChannel.lastTrackStatus[p.optString("orderSn")] == "SEND"
        }
        // ★ vc637：没有码、订单号、站点、商品或物流信息的 PDD 裸卡没有可展示内容，
        // 不再伪造“取件码获取中…”占位卡；真正到站无码卡由已有补码链处理。
        pending.removeAll { p ->
            p.optString("src") == "pdd" &&
                p.optString("pickupCode").isEmpty() &&
                p.optString("orderSn").isEmpty() &&
                p.optString("waybillNo").isEmpty() &&
                p.optString("stationText").isEmpty() &&
                p.optString("stationName").isEmpty() &&
                p.optString("nickName").isEmpty() &&
                p.optString("goodsName").isEmpty() &&
                p.optString("latest").isEmpty()
        }
        // ★★ vc635 诊断：把取件码栏每张卡的字段打出来（排查"多出来的卡"从哪来）
        try {
            for (p in pending) {
                Log.i(
                    "PickupCard",
                    "src=${p.optString("src")} sn=${p.optString("orderSn")} code=${p.optString("pickupCode")}" +
                            " station=${p.optString("stationText").take(24)} tip=${p.optString("tip").take(20)}" +
                            " status=${p.optString("statusText").take(16)} cache=${p.optBoolean("fromCache")}" +
                            " goods=${p.optString("goodsName").take(16)}"
                )
            }
        } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        // ★★ vc618：**取件码栏也走裁决器**（原 vc614 只观测、不改行为）。
        //   现在两栏同一个判据：裁决 PICKUP 才进取件码栏，HIDDEN 一律不上屏。
        try {
            val dead = ArrayList<JSONObject>()
            for (p in pending) {
                val vsrc = p.optString("src")
                val vsn = pickKey(vsrc, p) ?: p.optString("orderSn")
                val vv = PickupVerdict.decide(
                    PickupVerdict.Input(
                        src = vsrc,
                        // ★★ vc630：取件码栏也走多键查表（orderSn → realWaybill → waybillNo → vsn）
                        enumState = if (vsrc == "pdd") {
                            listOf(
                                p.optString("orderSn"),
                                p.optString("realWaybill"),
                                p.optString("waybillNo"),
                                vsn
                            ).firstNotNullOfOrNull { k ->
                                if (k.isNotEmpty()) PddChannel.lastTrackStatus[k] else null
                            }
                        } else p.optString("statusCode").ifEmpty { null },
                        orderStatus = p.optString("statusText"),
                        shippingMsg = p.optString("latest"),
                        code = p.optString("pickupCode"),
                        waybill = p.optString("realWaybill").ifEmpty { p.optString("waybillNo") },
                        bornAtMs = if (vsrc == "pdd") (PddNative.snToDateMs(vsn) ?: 0L) else 0L,
                        cacheTs = if (p.optBoolean("fromCache")) System.currentTimeMillis() else 0L,
                        pickedAt = p.optLong("pickedAt", 0L)
                    )
                )
                if (vv.hidden) {
                    Log.i("Verdict", "取件码栏隐藏 $vsrc:$vsn → ${vv.reason}")
                    dead.add(p)
                }
            }
            // ★★ vc633：**撤回强制执行**（同途中栏）。裁决器只观察、不上屏过滤，
            //   避免"整栏被清空"这类功能级事故。原过滤逻辑照旧生效。
            if (dead.isNotEmpty()) {
                Log.i("Verdict", "观察：取件码栏有 ${dead.size} 张卡裁决=HIDDEN —— 仍按原逻辑显示")
            }
        } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        // ★ 统一取件规则：已取走/已签收/已完成的件不再显示在取件码栏（拿回家了就没必要再占卡片）
        //   ★ "交易成功""待评价"是**拼多多**的已完成文案（2026-09-20：8 张已完成订单占着取件码页），
        //     别只按淘系的"已完成/已签收"口径来。
        pending.removeAll { p ->
            // ★★ vc311：**已打「已取件」标记的卡不许在这里被删** —— vc229 定的口径是
            //   「留着灰标显示 24 小时，让用户知道这单取过了，24h 后自动清」。
            //   这条完成态过滤原本会把 statusText="已取件" 的卡当场删掉（"已取件" 就在下面词表里），
            //   于是标记打上了也看不见 —— 同志报的 bug 的第三层。
            if (p.optLong("pickedAt", 0L) != 0L) return@removeAll false
            val st = p.optString("statusText")
            // ★ 2026-09-21 补：换拼多多账号后暴露 —— 新账号里全是「已评价 / 极速退款成功 /
            //   换货、补寄成功」这类**完成态**文案，而白名单里只有"交易成功/待评价"两个老文案
            //   ⇒ 11 张已完成订单霸占了取件码栏。
            //   ⚠️ 别把「待收货」加进来 —— 那是**还在途**，恰恰是要显示的。
            listOf(
                "已取货", "已取件", "已签收", "已收货", "已完成", "已取消", "已退款",
                "交易关闭", "交易成功", "待评价", "已评价",
                "退款", "换货", "补寄", "已关闭", "已失效"
            ).any { st.contains(it) }
        }

        // ★★ vc710：**还没到驿站、又没有取件码的件，不进「取件码」页**。
        //   同志 2026-11-09 截图实证：一张拼多多「待收货」卡（皇帝柑 · 极兔）被当成取件码卡
        //   显示在"地址未识别"分组里 —— 它还在路上，属于「在途快递」页。
        //   判据（三个都成立才剔）：① 没有取件码 ② 没有任何到站信息（驿站/代收点/柜/货架）
        //   ③ 状态与最新轨迹里也没有"已到/待取/入柜/代收"这类词。
        //   ⚠️ 这样不会误杀"刚到、码还没抓到"的件：那种件一定有到站信息或到站文案。
        val ARRIVE_HINT = listOf(
            "待取", "已到", "到达", "入柜", "代收", "驿站", "快递柜", "自提", "暂存", "取件", "派件"
        )
        pending.removeAll { p ->
            if (p.optString("pickupCode").isNotBlank()) return@removeAll false
            val addr = listOf("stationText", "stationName", "nickName", "shelfNo", "tip")
                .joinToString(" ") { p.optString(it) }.trim()
            if (addr.isNotBlank()) return@removeAll false
            val st = p.optString("statusText") + " " + p.optString("latest")
            if (ARRIVE_HINT.any { st.contains(it) }) return@removeAll false
            Log.i(
                "PickupGhost",
                "剔除无码且未到站的卡：${p.optString("src")} ${p.optString("waybillNo")} " +
                        "status=${p.optString("statusText")}"
            )
            true
        }

        // ★ vc692：通知来源并入 —— IdNotifyListener 从拼多多 / 淘宝 / 京东等 App 的通知里
        //   白捡到的取件码（存在 PddCodeStore 的 "ntf:<码>" 条目里）。
        //   刻意放在黑名单与跨源去重**之前**：通知卡也要能被长按删除、也要和接口卡合并，
        //   不能自带一条绕过既有闸门的捷径。
        run {
            var ntfCount = 0
            for (c in PddCodeStore.all(this).values) {
                if (c.src != "ntf") continue
                if (c.code.isBlank() || PddCodeStore.isPicked(c) || PddCodeStore.looksTaken(c)) continue
                ntfCount++
                pending.add(JSONObject().apply {
                    put("src", "ntf")
                    put("pickupCode", c.code)
                    put("waybillNo", c.waybill)
                    put("realWaybill", c.waybill)
                    put("goodsName", "通知里的取件码")
                    put("statusText", "待取件")
                    put("latest", c.latest)
                    if (c.station.isNotBlank()) {
                        put("stationText", c.station)
                        put("nickName", c.station)
                    }
                })
            }
            if (ntfCount > 0) Log.i("NtfCode", "通知来源并入取件码栏：$ntfCount 条")
        }
        // ★ 用户长按删除过的单：不再自动补回（键 = src:单号，与取件码缓存同一套键）
        // ★★ vc670：**跨来源判**。同一件在拼多多/菜鸟各有一张卡、键不同，
        //   旧写法只比 `src:键` ⇒ 删掉一张、另一张下一次渲染就把它顶回来
        //   （2026-11-02 用户实测"删了还在，删几次都一样"）。
        val pickupMuted = pickupMutedAll()
        if (pickupMuted.isNotEmpty()) {
            // ★ vc675 诊断：删不掉时先看这两行 —— 黑名单里到底存了什么、卡片的身份又是什么
            Log.i("PickupCard", "黑名单(${pickupMuted.size})=" + pickupMuted.take(12).joinToString(","))
            pending.firstOrNull { p ->
                val s0 = p.optString("src")
                !isPickupMuted(s0, p, pickupMuted)
            }?.let { p ->
                Log.i("PickupCard", "未命中样例 src=${p.optString("src")} " +
                        "ids=" + parcelIds(p.optString("src"), p).joinToString("/"))
            }
            val before = pending.size
            pending.removeAll { p -> isPickupMuted(p.optString("src"), p, pickupMuted) }
            val removed = before - pending.size
            if (removed > 0) Log.i("PickupCard", "按黑名单剔除 $removed 张（跨来源判）")
        }

        // ★ 跨源去重：同一件被两个平台抓到时只留一张卡（放在缓存同步之前，
        //   免得被合并掉的那张又把自己的缓存补回来）。详见 dedupPkgs 的说明。
        val deduped = dedupPkgs(pending)
        pending.clear()
        pending.addAll(deduped)

        // ★ vc635：补码占位卡也按订单号去重。订单页、在途转入和缓存回填
        //   可能在同一轮各带入一张无码 PDD 卡；它们没有运单号/取件码，跨源
        //   dedup 无法识别，只能在这里按 orderSn 合并，避免同一单出现两张“获取中”。
        val byPddOrder = LinkedHashMap<String, JSONObject>()
        val uniquePending = ArrayList<JSONObject>()
        for (p in pending) {
            val sn = p.optString("orderSn")
            if (p.optString("src") != "pdd" || sn.isEmpty()) {
                uniquePending.add(p)
                continue
            }
            val old = byPddOrder[sn]
            if (old == null) {
                byPddOrder[sn] = p
                uniquePending.add(p)
            } else {
                // 选择信息更完整的对象，避免缓存占位覆盖实时地址/商品名/码。
                val oldScore = listOf("pickupCode", "stationText", "stationName", "nickName", "goodsName")
                    .count { old.optString(it).isNotEmpty() }
                val newScore = listOf("pickupCode", "stationText", "stationName", "nickName", "goodsName")
                    .count { p.optString(it).isNotEmpty() }
                if (newScore > oldScore) {
                    val index = uniquePending.indexOf(old)
                    if (index >= 0) uniquePending[index] = p
                    byPddOrder[sn] = p
                }
                Log.i("PickupCard", "PDD 同订单占位去重：$sn（保留信息更完整卡）")
            }
        }
        pending.clear()
        pending.addAll(uniquePending)

        // ★★ 统一取件码解析（照参考项目）：任何源的件，只要轨迹文本里有"取件码 XXX"就提出来。
        //   这一步是"全源都能出码"的兜底 —— 各源自己的实现可能有洞（京东正则…），
        //   这里再过一遍，能补多少补多少。放在去重之后、写缓存之前。
        fillPickupCodes(pending)

        // ★★ v1.4.9：**补商品标题**（同志 2026-11-10：取件码页面要显示这单是什么东西）。
        //   取件码卡有好几条来路，物流页补出来的卡根本没有标题字段；
        //   这里一边把见过的标题按订单号记下来，一边给缺标题的卡回填。
        backfillPackageTitles(pending)

        // ★ 拼多多取件码「常态显示 + 已取出自动消除」（2026-09-20 同志定的规则）
        val beforeCacheSync = pending.size
        syncPickupCodeCache(pending)
        val cachedCodeCount = pending.count { it.optString("pickupCode").isNotEmpty() }
        if (cachedCodeCount > 0) {
            Log.i("PddCodeJob", "首屏缓存码已并入：${cachedCodeCount} 张（同步前 pending=${beforeCacheSync}），后台继续补码")
        }

        // ★ vc270g：会话内消失防护 —— 手动刷新时若某轮解析缺单（超时/风控），把本会话
        //   见过的拼多多在途卡补回来（仅内存 + 48h 上限，进程死亡即清，不造僵尸）。
        //   真取走了/回在途的（track 状态可查且非待取）不补。
        val nowMs = System.currentTimeMillis()
        pddLastSeenBare.entries.removeAll { nowMs - it.value.optLong("_ts") > 48L * 3600_000L }
        for (p in pending) {
            if (p.optString("src") != "pdd") continue
            val sn = p.optString("orderSn")
            if (sn.isEmpty()) continue
            val copy = JSONObject(p.toString())
            copy.put("_ts", nowMs)
            pddLastSeenBare[sn] = copy
        }
        for ((sn, saved) in pddLastSeenBare) {
            val exists = pending.any {
                it.optString("orderSn") == sn || it.optString("waybillNo") == sn
            }
            if (exists || PddCodeStore.get(this, "pdd:$sn") != null) continue
            // ★★ vc675：会话内补回同样要过黑名单（否则删掉的卡又冒出来）
            if (isPickupMuted("pdd", saved, pickupMutedAll())) {
                Log.i("PickupCard", "会话内补回跳过（黑名单）：$sn")
                continue
            }
            val st = PddChannel.lastTrackStatus[sn]
            if (st != null && PddChannel.isNonPickupState(st)) continue   // 真取走/在途 ⇒ 不补
            Log.i("PddCache", "会话内补回消失的拼多多卡：$sn")
            pending.add(saved)
        }

        // ★ vc638：缓存同步和会话回填之后再做一次最终去重。
        //   首轮 dedupPkgs 发生在 syncPickupCodeCache 之前；缓存键与实时卡的主键不同
        //   时，syncPickupCodeCache 会补回一张 PDD 卡，导致同一个取件码重新出现两次。
        //   这里必须等所有缓存/会话卡都进入 pending 后再归并，才能同时修正上屏数量、
        //   地址分组和后续补码输入；dedupPkgs 仍坚持“同码 + 跨源 + 站点兼容”规则。
        val beforeFinalDedup = pending.size
        val finalDeduped = dedupPkgs(pending)
        if (finalDeduped.size != beforeFinalDedup) {
            Log.i("Dedup", "缓存回填后最终去重 $beforeFinalDedup → ${finalDeduped.size}")
            pending.clear()
            pending.addAll(finalDeduped)
        }

        // ★ vc222：零识别信息的拼多多裸卡**不上屏**（同志 03:47 报的「两张取件码卡，
        //   一张只有『在驿站待取』」）。根因已修（PddChannel.NON_PICKUP_STATES 补
        //   DEPARTURE，在途单不再收进第 4 栏）；这里是渲染层兜底 —— track/status 枚举
        //   还会变，未来新的在途态再漏进来时，裸卡（码/商品/驿站全空，渲染出来就是一行
        //   干巴巴的「在驿站待取」）不再吓人。
        //   ★ 只是**不渲染**，卡仍留在 pending 里：enrichOnePddCode 靠它当补码目标、
        //     syncPickupCodeCache 的 ④ 缓存回填靠它落码 —— 抠到码/补上驿站后自然出现。
        //   ⚠️ 判据不看 waybillNo：拼多多卡渲染根本不显示运单号（真机 dump 验过），
        //     「只有运单号」的卡上屏照样是零信息幻影；而运单号互认的合并由 dedupPkgs
        //     的 W: 键负责（下一轮渲染就会跟菜鸟同运单卡合并），不靠上屏。
        val visible = ArrayList<JSONObject>()
        for (p in pending) {
            // ★★ v1.4.16：**命中黑名单的卡不上屏**（同志 14:20：「首页会出现在黑名单中的
            //   已删除的取件码，显示待取」）。
            //
            //   为什么以前会跑出来：清已取 / 长按删除写进黑名单后，[PickedStore] 的标记和
            //   缓存条目都被撤掉，平台下一轮照样把它当"在驿站待取"报上来 ⇒ 这张卡重新变成
            //   一张**普通的待取卡**，于是取件码页上屏、首页也跟着发格子。
            //   黑名单只挡住了「在途快递」，没挡「取件码」——这条链一直缺一道闸。
            //
            //   ⚠️ 时间语义靠 [MuteStore.Entry.holdUntil]，这里不用再判一次：
            //   · 「标记已取件」写的记录 holdUntil = 24h 后 ⇒ 此刻 blocks() 返回 null ⇒ 灰卡照旧显示；
            //   · 「清已取 / 长按删除」写的记录 holdUntil = 0 ⇒ 立刻生效 ⇒ 卡片从这里就被挡住。
            if (pickupMuteHit(p.optString("src"), p) != null) {
                Log.i(
                    "PickupMuted",
                    "黑名单里的卡不上屏：${p.optString("orderSn").take(22)} 码=${p.optString("pickupCode")}"
                )
                continue
            }
            // ★★ v1.4.12（同志 13:08）：「标记完以后还和之前一样**留存 24 小时**，或者手动删除」——
            //   所以打了「已取件」的卡**照旧上屏**（灰标 + 码划掉），24h 后由
            //   [PickedStore] / [MuteStore] 两边同时让它退场（时长都是 PICKED_TAG_KEEP_HOURS）。
            //   v1.4.11 那版是"当场消失"，结果他取完件想核对「哪些还没确认收货」时无处可查。
            //
            //   ⚠️ 灰卡只影响显示，**不进「N 件待取」**（见下面的 activeCount），
            //   也不进首页大数字、不进流体云（PickedStore 过期 + 黑名单 24h 后接管）。
            val bare = p.optString("src") == "pdd" &&
                    p.optString("pickupCode").isEmpty() &&
                    p.optString("goodsName").isEmpty() &&
                    p.optString("stationText").isEmpty() &&
                    p.optString("nickName").isEmpty() &&
                    p.optString("stationName").isEmpty()
            if (bare) {
                // 保留 pending 供后台补码，但不把无法识别的裸卡伪装成待取包裹显示。
                Log.i("PddCache", "零信息 PDD 裸卡暂不上屏，保留后台补码：${p.optString("orderSn").take(22)}")
                continue
            }
            visible.add(p)
        }

        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
        val now = fmt.format(java.util.Date())
        // ★ vc229：「N 个待取」只数**还没取**的 —— 打了「已取件」标记的卡虽然还显示，
        //   但它已经不是待取了，混进计数会让数字虚高。
        val activeCount = visible.count { it.optLong("pickedAt", 0L) == 0L }
        // ★★ v1.4.9b：**「清已取 N」用页面上真正显示出来的已取件卡数**，不再数缓存条数
        pickupPickedVisible = visible.count { it.optLong("pickedAt", 0L) != 0L }
        try { pickupClearBtnRefresh?.invoke() } catch (t: Throwable) { SafeRun.w("PickupClear", t) }
        try { pickupSubRefresh?.invoke() } catch (t: Throwable) { SafeRun.w("PickupClear", t) }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt(KEY_LAST_PKG_COUNT, activeCount)
            .putString(KEY_LAST_PKG_TIME, now)
            .apply()
        // ★★ v1.4.10：把「这一屏真正显示的待取码」发布给首页 —— 首页网格从此与本页同源。
        //   这是「首页偶现有码却不显示、要点查看全部才看得到」的根治点：
        //   以前首页自己翻持久缓存（还可能被 looksTaken / 冲突清理 / 补码没落盘挡住），
        //   跟本页看到的根本不是同一份数据。
        run {
            val feedNow = ArrayList<HomeCode>()
            val seen = HashSet<String>()
            for (p in visible) {
                // ★★ v1.4.13：**已取件的码一律不进首页**（同志 13:35：「首页取件码会显示
                //   取件码页面的所有取件码，无论是否标记已取件，都会显示待取」）。
                //   v1.4.12 这里把 picked 写死成 false（当时只想让"卡片留在页面上"，忘了
                //   首页是按键值过滤的）⇒ 首页那道 `filter { !it.picked }` 形同虚设，
                //   灰卡也照常占格子、还算进「N 件待取」。
                //   现在按真实标记发布：页面留 24h 给用户核对，首页当场不要（两处口径不同是有意的）。
                val code = p.optString("pickupCode").trim()
                if (code.isEmpty() || !seen.add(code)) continue
                val st = p.optString("stationText")
                    .ifEmpty { p.optString("stationName") }
                    .ifEmpty { p.optString("nickName") }.trim()
                feedNow.add(HomeCode(code, st, p.optLong("pickedAt", 0L) != 0L))
            }
            val fp = feedNow.joinToString("|") { "${it.code}/${it.station}/${it.picked}" }
            if (fp != homeFeedFp) {
                homeFeedFp = fp
                homePickupCodes = feedNow
                Log.i(
                    "HomeFeed",
                    "首页取件码同步 ${feedNow.size} 个（已标记 ${feedNow.count { it.picked }}）：" +
                            feedNow.joinToString(",") { it.code }
                )
                mainHandler.post { runCatching { rebuildHome() } }
            }
        }
        // 顺手把取件码推给桌面小组件
        publishWidgetCodes(pending)
        // 后台快递跟踪：把本次抓到的全量包裹写成基线快照（TrackWorker 靠它判断"有没有变化"）
        publishTrackSnapshot()
        // ★ 流体云：**必须按快照推，不能按当次 pending**。
        //   实测踩过：拼多多退登后进第 4 栏，pending 里没有那个件 ⇒ 会被当成"已取出"，
        //   把流体云上正确的卡误撤掉。快照是持久化的全量基线，天生抗"某个源这次没抓到"。
        //   （放在 publishTrackSnapshot 之后，这样读到的就是刚合并完的最新快照）
        LiveUpdate.syncFromSnapshot(this)
        IslandHelper.syncFromSnapshot(this)
        // 主页横栏副标题同步刷新（重建四个栏太重，直接改文案）
        refreshPickupBarSub()

        // ★★ vc342（P0-1）：补码从"每轮渲染补 1 张、补完再渲染再补"改成**一次刷一批**。
        //   旧链路 4 张缺码 = 4 趟 × 10~20s 串行，必然撞 70s 超时 ⇒ "一次刷新拿全码"不可能。
        //   新链路：先纯 HTTP 批量扫（2~5s）→ 取件页批量配对（~5s）→ 最后 1~2 单才逐单开页。
        //   ⚠️ 这里**只**调批量入口；逐单补码由作业内部收尾，不再挂在每次渲染上。
        ensurePddCodesBatch(pending)

        // ★★ vc708：**同内容不重画**（消闪第二道闸）。取件码页每次源回调 / 补码都会走到这儿，
        //   而多数回调算出来的东西与上一屏完全一样 —— 整列重建纯属"白闪"：
        //   卡片会重播入场、滚动位置跳动、展开态丢失。
        //   指纹一致就直接返回，页面一个像素都不动。
        //   ⚠️ 位置很讲究：所有状态 / 通知 / LiveUpdate / 组件推送都在**这之前**跑过了，
        //   所以只跳过"重建视图"这一步，功能一点不受影响。
        val fpNow = buildString {
            append(cainiaoNeedLogin).append('|')
            for (p in pending) {
                append(p.optString("src")).append(':')
                append(p.optString("waybillNo")).append(':')
                append(p.optString("pickupCode")).append(':')
                append(p.optLong("pickedAt", 0L)).append(':')
                append(p.optString("statusText")).append(':')
                append(p.optString("goodsName")).append(';')
            }
        }
        if (fpNow == pickupRenderFp && list.childCount > 0) {
            Log.i("PickupRender", "内容未变，跳过重画（消闪）")
            return
        }
        pickupRenderFp = fpNow

        list.removeAllViews()

        var warned = false
        if (cainiaoNeedLogin) {
            list.addView(makeLoginCard(
                getString(R.string.pickup_need_login),
                getString(R.string.pickup_go_login)
            ) { openWeb(TAOBAO_URL, getString(R.string.web_title)) })
            list.addView(makeGap(10))
            warned = true
        }

        // vc222：空态判定用 visible —— 裸卡在等补码时，界面上确实什么都没有
        if (visible.isEmpty()) {
            pickupGroupHeaders.clear()
            pickupStickyBox?.visibility = View.GONE
            showStatusText(pickupStatusText)
            setStatusText(
                pickupStatusText,
                when {
                    pickupGot < pickupExpect -> loadingText()
                    cainiaoErr != null && !warned ->
                        getString(R.string.pickup_error, cainiaoErr!!)
                    pddErr != null -> getString(R.string.pickup_error, pddErr!!)
                    jdErr != null -> getString(R.string.pickup_error, jdErr!!)
                    // ★ vc273：加载完且真的没件 ⇒ 主文案一律"暂无取件码"。
                    //   旧逻辑在这里把"去登录拼多多/京东"当成主文案，同志报看不懂
                    //   （“加载完毕后如无快递提示暂无取件码”）。
                    else -> getString(R.string.pickup_empty)
                }
            )
            if (!warned && pickupGot >= pickupExpect) {
                // ★ vc273："还有源没登录"的提示降到副行（不抢主文案）；
                //   都登录了才显示常规空态说明。
                val moreSrc = !PddChannel.isLoggedIn(this) || !JdChannel.isLoggedIn(this)
                list.addView(TextView(this).apply {
                    text = if (moreSrc) getString(R.string.pickup_more_sources)
                    else getString(R.string.pickup_empty_hint)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(cTextTertiary)
                    setPadding(0, (8 * dp).toInt(), 0, 0)
                })
            }
            return
        }

        showStatusText(pickupStatusText)
        // ★ vc592：同样 3 秒后淡出（这条含件数+时间，卡片上仍有件数，不会丢信息）
        showUpdateThenHide(
            pickupStatusText,
            getString(R.string.bar_pickup_sub_count, activeCount, now)
        )
        // ★ vc368：按取件地址分组（只在出现 2 个及以上不同驿站时启用）
        pickupGroupHeaders.clear()
        pickupStickyBox?.visibility = View.GONE
        val groups = LinkedHashMap<String, MutableList<JSONObject>>()
        val unknown = ArrayList<JSONObject>()
        for (pkg in visible) {
            val g = stationGroupName(pkg)
            if (g.isEmpty()) unknown.add(pkg)
            else groups.getOrPut(g) { ArrayList() }.add(pkg)
        }
        if (groups.size >= 2) {
            fun addGroupHeader(name: String, count: Int, muted: Boolean) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, (2 * dp).toInt(), 0, (6 * dp).toInt())
                }
                row.addView(View(this).apply {
                    setBackgroundColor(if (muted) cTextFaint else cAccent)
                }, LinearLayout.LayoutParams((3 * dp).toInt(), (20 * dp).toInt()))
                row.addView(TextView(this).apply {
                    text = name
                    textSize = 15f
                    setTextColor(if (muted) cTextSecondary else cTextPrimary)
                    setPadding((8 * dp).toInt(), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(TextView(this).apply {
                    text = "$count 件"
                    textSize = 12f
                    setTextColor(cTextTertiary)
                })
                list.addView(row)
                pickupGroupHeaders.add(row to "$name · $count 件")
            }
            for ((name, cards) in groups) {
                addGroupHeader(name, cards.size, false)
                for (c in cards) {
                    list.addView(makePackageCard(c))
                    list.addView(makeGap(10))
                }
            }
            if (unknown.isNotEmpty()) {
                addGroupHeader("地址未识别", unknown.size, true)
                for (c in unknown) {
                    list.addView(makePackageCard(c))
                    list.addView(makeGap(10))
                }
            }
            // 重绘后按当前滚动位置重新算一次吸顶（防止停留在旧组的名字上）
            list.post { pickupScroll?.let { updatePickupSticky(it.scrollY) } }
        } else {
            for (pkg in visible) {
                list.addView(makePackageCard(pkg))
                list.addView(makeGap(10))
            }
        }
    }

    /** ★ vc368：分组用的"驿站名"——只取到站名后缀为止，后面的详细地址不参与分组 */
    private fun stationGroupName(p: JSONObject): String {
        // ★★ vc634：手动单号（快递100）不是驿站件 ⇒ 固定归"手动单号"，别被 stationText
        //   里的杂字段误分成"运单号添加"这种怪组（同志 13:08 截图里就多出这么一组）。
        if (p.optString("src") == "manual") return "手动单号"
        // ★★ v1.4.8：三个来源都先过 [StationNames.clean] —— 签到轨迹（"已取件…已领取…"）
        //   不算站名，否则卡片副标题会写着"已取件"，用户以为系统判他取过件了
        //   （同志 2026-11-10 报"显示已取件，但我没有取件"）。脏值跳过、换下一个来源。
        val raw = listOf(
            p.optString("stationText"),
            p.optString("stationName"),
            p.optString("nickName")
        ).map { StationNames.clean(it) }.firstOrNull { it.isNotEmpty() }.orEmpty()
        if (raw.isEmpty()) return ""
        val name = extractStationName(raw)
        if (name.isEmpty() || isNonPlaceStationLabel(name)) return ""
        return normalizeStationGroupName(name)
    }

    private fun isNonPlaceStationLabel(name: String): Boolean {
        val value = name.replace(Regex("\\s+"), "").trim()
        return value.contains("运单号添加") || value.contains("快递单号添加") ||
                value == "添加运单号" || value == "未知地址" || value == "暂无地址"
    }

    private fun normalizeStationGroupName(name: String): String {
        var value = name.replace(Regex("\\s+"), "").trim()
        value = value
            .replace("抚州幼儿师范高等专科学校", "抚州幼儿师专")
            .replace("幼儿师范高等专科学校", "幼儿师专")
            .replace("菜鸟驿站", "驿站")
            .replace("快递驿站", "驿站")
            .replace("抚州幼儿师专学校", "抚州幼儿师专")
        return value.trim()
    }

    /**
     * ★★ vc625：从一段**任意文本**里提取"驿站短名"（卡片副行、分组标题共用）。
     * 例：「待取件2026-10-30 15:43:00 快件已由抚州幼儿师范高等专科学校店菜鸟驿站代收，…」
     *   → 「抚州幼儿师范高等专科学校店菜鸟驿站」
     */
    private fun extractStationName(text: String): String {
        val raw = text.trim()
        if (raw.isEmpty()) return ""
        val suffixes = listOf(
            "快递驿站", "驿站", "快递柜", "智能柜", "代收点",
            "自提点", "服务点", "服务站", "便利店", "超市"
        )
        var end = -1
        for (s in suffixes) {
            val i = raw.indexOf(s)
            if (i >= 0) {
                val e = i + s.length
                if (end < 0 || e < end) end = e
            }
        }
        if (end <= 0) return raw.take(24).trim()
        // ★★ vc624：**不要从行首截到"驿站"** ——
        //   拼多多的地址字段常是整句「待取件2026-10-30 15:43:00 快件已由XX店菜鸟驿站代收…」，
        //   从行首截会把时间/状态全带上（同志截图里分组标题就是一整句）。
        //   改为从「由 / 到达 / 至 / 在」这些介词之后开始取，得到干净的驿站名。
        var startIdx = 0
        for (kw in listOf("由", "到达", "送至", "至", "在")) {
            val k = raw.lastIndexOf(kw, end - 1)
            if (k >= 0 && k + kw.length < end) startIdx = maxOf(startIdx, k + kw.length)
        }
        var name = raw.substring(startIdx.coerceIn(0, end), end).trim()
        // 名字仍过长 ⇒ 取最后 24 字（驿站名都在尾部）
        if (name.length > 24) name = name.takeLast(24).trim()
        // ★ vc705：最后过一遍用户的「取件位置标签」规则（1.3 特性移植）——
        //   规则在设置页里配，这里是唯一出口，卡片 / 通知 / 明细行都会跟着变。
        return try {
            PickupLabel.apply(this, name)
        } catch (t: Throwable) {
            SafeRun.w("PickupLabel", t)
            name
        }
    }

    /**
     * ★ vc593：吸顶标题加**贴顶动画**（原实现只是硬切 VISIBLE/GONE，滚动时"啪"地冒出来）。
     *
     * 三件事：
     *  1) **跟手推挤**：下一组标题进入吸顶高度时，当前条按 1:1 进度向上顶走（`translationY`），
     *     不是等它整块过去再换 —— 手感上像一层纸被推出去；
     *  2) **淡入淡出**：出现 190ms 淡入、离开 190ms 淡出（令牌 D190）；
     *  3) **换组淡切**：文字先在 100ms 内淡掉，换字后再 190ms 淡回来，避免"字突然换了"的跳变。
     */
    private var stickyShownTitle: String? = null

    private fun updatePickupSticky(scrollY: Int) {
        val box = pickupStickyBox ?: return
        val text = pickupSticky ?: return
        if (pickupGroupHeaders.isEmpty() || scrollY <= 0) {
            if (box.visibility == View.VISIBLE) {
                box.animate().alpha(0f).setDuration(UiTok.D190).withEndAction {
                    box.visibility = View.GONE
                    box.translationY = 0f
                    box.alpha = 1f
                }.start()
            }
            stickyShownTitle = null
            return
        }
        var cur: String? = null
        var nextTop = Int.MAX_VALUE
        val trigger = (2 * dp).toInt()
        for ((v, name) in pickupGroupHeaders) {
            val top = v.top - scrollY
            if (top <= trigger) {
                cur = name
            } else {
                nextTop = top
                break
            }
        }
        if (cur == null) {
            if (box.visibility == View.VISIBLE) {
                box.animate().alpha(0f).setDuration(UiTok.D190).withEndAction {
                    box.visibility = View.GONE
                    box.translationY = 0f
                    box.alpha = 1f
                }.start()
            }
            stickyShownTitle = null
            return
        }

        // ① 推挤：下一组标题顶上来时，当前条被顶出去（1:1 跟手，不做插值）
        val h = if (box.height > 0) box.height else (32 * dp).toInt()
        val push = if (nextTop in 0 until h) (nextTop - h).toFloat() else 0f
        val ratio = if (h > 0) (push / -h).coerceIn(0f, 1f) else 0f
        box.translationY = push
        box.alpha = 1f - ratio * 0.55f

        // ② 出现：淡入
        if (box.visibility != View.VISIBLE) {
            box.visibility = View.VISIBLE
            box.animate().cancel()
            box.alpha = 0f
            box.translationY = 0f
            box.animate().alpha(1f).setDuration(UiTok.D190).start()
        }

        // ③ 换组：文字淡切（不在同一帧硬改）
        if (stickyShownTitle != cur) {
            stickyShownTitle = cur
            text.animate().cancel()
            text.animate().alpha(0f).setDuration(100).withEndAction {
                text.text = cur
                text.animate().alpha(1f).setDuration(UiTok.D190).start()
            }.start()
        }
    }

    /**
     * 把当前待取的取件码存下来并推给桌面小组件。
     *
     * 小组件自己不能联网（取件码要先登录、淘宝那条还要重载隐形 WebView 算签名），
     * 所以由 App 拉完数据后主动推过去 —— 这也是小组件唯一的数据来源。
     */
    private fun publishWidgetCodes(pending: List<JSONObject>) {
        val codes = JSONArray()
        for (p in pending) {
            val c = p.optString("pickupCode")
            // ★ vc229：已取件的不占小组件位置（码已经没用了）
            if (c.isNotEmpty() && p.optLong("pickedAt", 0L) == 0L) {
                codes.put(JSONObject().apply {
                    put("src", p.optString("src"))
                    put("code", c)
                })
            }
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(HomeWidgets.KEY_WIDGET_CODES, codes.toString())
            .apply()
        HomeWidgets.updateAll(this)
        // ★ vc699："已给用户看过"的账本由公共出口维护 —— 不记账的话，
        //   用户刚在 App 里看到的码，下一轮后台还会当成"新到"再推一条通知。
        try { PickupPublish.markSeen(this) } catch (t: Throwable) { SafeRun.w("PickupPublish", t) }
    }

    /**
     * 把本次抓到的**全部来源**包裹写进后台跟踪快照（供 [TrackWorker] 做「有没有变化」的对比）。
     *
     * ★ 这里用**未经「已取件」过滤的原始列表**（cainiaoPkgs + 各源全量），
     *   而不是上面 `renderMerged` 里那份 `pending`：因为 `pending` 会把已签收/已取件的件剔掉，
     *   一旦剔掉，后台就再也看不出「这单从在途变成了已签收」—— 而「刚签收」恰恰是最该通知的。
     *
     * ★ 只写快照、不动跟踪名单：跟踪是用户长按卡片显式开的（语义是「我盯着这单」），
     *   快照是全部件的基线。
     */
    private fun publishTrackSnapshot() {
        try {
            val src = ArrayList<JSONObject>()
            src.addAll(cainiaoPkgs)
            src.addAll(pddPkgs)
            src.addAll(jdPkgs)
            src.addAll(miPkgs)
                src.addAll(manualPkgs)
            // ★ 第 5 栏「在途快递」也必须收进来：同志定的通知节点是「派件中 / 已到达」，
            //   而"派件中"发生在在途阶段 —— 只盯第 4 栏（待取）的话，派件中这个节点会整个漏掉。
            //   ★ vc301：**淘宝在途（tbTransit）此前漏收** —— 极兔/圆通自营驿站的件不在
            //     菜鸟驿站接口里，cainiaoPkgs 抓不到它们；后台 Worker 同样抓不到（同因）。
            //     结果：整条淘宝单从派送到到站快照里根本没有它 ⇒ 永远不通知
            //     （2026-10-17 极兔 JT3180960043943 实测漏报）。
            src.addAll(tbTransit)
            src.addAll(pddTransit)
            src.addAll(jdTransit)
            src.addAll(miTransit)
                src.addAll(manualTransit)
            // ★★ v1.4.9b：**在这里记商品标题**（同志要求取件码页显示在途页那种标题）。
            //   这里是"所有来源的件都会过一遍"的唯一汇合点，而拼多多卡的 `goodsName`
            //   只有在订单列表这一路才带得出来；取件码页的缓存卡往往没有。
            //   记成 订单号 → 标题，取件码卡渲染时按订单号回查即可。
            run {
                val pairs = HashMap<String, String>()
                for (p in src) {
                    if (p.optString("src") != "pdd") continue
                    val sn = p.optString("orderSn")
                    val g = p.optString("goodsName")
                    if (sn.isNotEmpty() && g.isNotEmpty()) pairs[sn] = g
                }
                if (pairs.isNotEmpty()) PddTitleStore.putAll(this, pairs)
            }
            // ★ 跨源去重：快照里同一件只留一条 —— 否则 TrackWorker 会对同一件各判一次，
            //   「派件中」「已到达」同一个物流动态会发出**两条**通知。
            val items = dedupPkgs(src).mapNotNull { ExpressStore.fromPkg(it) }
            if (items.isEmpty()) return                 // 空结果不覆盖旧基线（防误清）
            val old = ExpressStore.load(this).associateBy { it.no }
            // ★ vc301：App 内刷新看到的「阶段跃迁」也要发通知 —— 以前只有后台 Worker 判定，
            //   而 App 常开时刷新总是先一步把基线推进 ⇒ 后台再轮询时 to==from 判"没变化"，
            //   「派件中/已到达」的跃迁整个被吃掉（2026-10-17 极兔件派送+到站双漏报的根因之二）。
            //   与 TrackWorker 走**同一份** notifyReason（铁律：同一判定只写一处）。
            //   双边并发极端情况下同一单可能响两次（通知 id 相同、内容覆盖），可接受；
            //   不为此加跨线程去重锁 —— 复杂度换不回多少收益。
            val mode = ExpressStore.trackMode(this)
            val tracked = ExpressStore.trackedNos(this)
            val muted = ExpressStore.mutedNos(this)
            for (it in items) {
                val reason = ExpressStore.notifyReason(old[it.no], it, mode, tracked, muted)
                    ?: continue
                Log.i("TrackSnapshot", "App 内跃迁通知 ${it.no}：$reason（「${old[it.no]?.statusText}」→「${it.statusText}」）")
                TrackNotifier.notifyUpdate(
                    this, it.no, it.company,
                    it.latest.ifBlank { it.statusText },
                    it.stationText
                )
            }
            // 合并而非覆盖：重装的源可能只回来一部分，整表覆盖会丢别的源
            val merged = old.toMutableMap()
            for (it in items) merged[it.no] = it
            ExpressStore.save(this, merged.values.toList())
            // 首次抓到某单时顺手补基线，避免用户第一次开跟踪就被历史轨迹刷屏
            Log.i("TrackSnapshot", "快照 ${merged.size} 条（本次 ${items.size}）")
        } catch (e: Throwable) {
            Log.w("TrackSnapshot", "写快照失败: ${e.message}")
        }
    }

    /**
     * ★★ 后台跟踪的**闭环自检**（`--ez debug_track_selftest true`）。
     *
     * ## 为什么需要它
     * 闭环的最后一环是「抓到新数据 → 阶段跃迁 → 发通知」，而这**需要真实在途包裹**
     * 才能自然发生 —— 同志手上没件的时候就没法验（2026-09-20 就卡在这）。
     * 这个自检把判定逻辑跑一张用例表，**再真发一条通知**，于是
     * 「判定对不对」和「通知发不发得出去」这两件事，在没有包裹时也能被验证。
     *
     * ## 关键：走的是同一份逻辑
     * 判定调用 `ExpressStore.notifyReason` —— 与 [TrackWorker] 完全同一份实现。
     * 如果这里另抄一套判定，自检就变成"测了另一份逻辑"，等于没测。
     */
    private fun selfTestTrack() {
        fun item(no: String, st: String, latest: String = "") = ExpressStore.Item(
            no = no, src = "test", company = "自检", statusText = st, latest = latest
        )

        val all = ExpressStore.MODE_ALL
        val manual = ExpressStore.MODE_MANUAL
        val none = emptySet<String>()

        data class Case(
            val name: String, val prev: ExpressStore.Item?, val now: ExpressStore.Item,
            val mode: Int, val tracked: Set<String>, val muted: Set<String>, val want: String?
        )

        val cases = listOf(
            Case("全部·首见·运输中（只记基线不扰）", null, item("T1", "运输中"), all, none, none, null),
            // ★ vc301：首见到站/派件要补报 —— "首见"可能是旧版本漏收/新装 App，件已在驿站
            Case("全部·首见·已在驿站 ★应补报", null, item("T2", "待取件"), all, none, none, "已到达"),
            Case("全部·首见·派送中 ★应补报", null, item("T3", "派送中"), all, none, none, "派件中"),
            Case("全部·首见·已签收（不扰）", null, item("T4", "已签收"), all, none, none, null),
            Case(
                "全部·运输中→运输中（中转站，★不该通知）",
                item("T1", "运输中", "已发往郑州"), item("T1", "运输中", "已到达郑州转运中心"),
                all, none, none, null
            ),
            Case(
                "全部·运输中→到达驿站（★应通知）",
                item("T1", "运输中", "已发往郑州"),
                item("T1", "运输中", "您的快件已到达金水区瀚海驿站，请及时领取"),
                all, none, none, "已到达"
            ),
            Case(
                "全部·泛化「已到达」（★应通知）",
                item("T1", "运输中"), item("T1", "已到达"), all, none, none, "已到达"
            ),
            Case("全部·运输中→派送中 ★应通知", item("T1", "运输中"), item("T1", "派送中"), all, none, none, "派件中"),
            Case("全部·派送中→派件中（同派件阶段）", item("T1", "派送中"), item("T1", "派件中"), all, none, none, null),
            Case("全部·运输中→已到达 ★应通知", item("T1", "运输中"), item("T1", "待取件"), all, none, none, "已到达"),
            Case("全部·已到达→已签收（完成不打扰）", item("T1", "待取件"), item("T1", "已签收"), all, none, none, null),
            Case("全部·被屏蔽（长按过的不提醒）", item("T1", "运输中"), item("T1", "派送中"), all, none, setOf("T1"), null),
            Case("指定·不在名单", item("T1", "运输中"), item("T1", "派送中"), manual, none, none, null),
            Case(
                "指定·在名单且有变化 ★应通知",
                item("T1", "运输中", "已发出"), item("T1", "运输中", "已到郑州"),
                manual, setOf("T1"), none, "有变化"
            ),
            Case(
                "指定·在名单但没变化",
                item("T1", "运输中", "已到郑州"), item("T1", "运输中", "已到郑州"),
                manual, setOf("T1"), none, null
            ),
        )

        val sb = StringBuilder()
        var pass = 0
        sb.append("后台跟踪 · 闭环自检\n")
        sb.append("判定走 ExpressStore.notifyReason（与 TrackWorker 同一份实现）\n")
        sb.append("=".repeat(60)).append('\n')
        for (c in cases) {
            val got = ExpressStore.notifyReason(c.prev, c.now, c.mode, c.tracked, c.muted)
            val ok = got == c.want
            if (ok) pass++
            val line = (if (ok) "PASS  " else "★FAIL ") + c.name +
                    "    期望=" + (c.want ?: "不通知") + "  实际=" + (got ?: "不通知")
            sb.append(line).append('\n')
            if (ok) Log.i("TrackSelfTest", line) else Log.e("TrackSelfTest", line)
        }
        sb.append("-".repeat(60)).append('\n')
        sb.append("判定表：$pass/${cases.size} 通过\n\n")

        sb.append("阶段判定核对（statusText → Stage）\n")
        for (pair in listOf(
            "运输中" to ExpressStore.Stage.NORMAL,
            "派送中" to ExpressStore.Stage.DELIVERING,
            "已到达" to ExpressStore.Stage.ARRIVED,
            "待取件" to ExpressStore.Stage.ARRIVED,
            "已签收" to ExpressStore.Stage.DONE
        )) {
            val got = ExpressStore.stageOf(item("X", pair.first))
            val ok = got == pair.second
            sb.append("  ").append(if (ok) "PASS  " else "★FAIL ")
                .append("「").append(pair.first).append("」 → ").append(got.name)
                .append("（期望 ").append(pair.second.name).append("）\n")
        }

        // 推送链路：真发一条，确认通知出得去（走的是 TrackNotifier，与 Worker 同一份）
        TrackNotifier.notifyUpdate(
            this, "SELFTEST", "自检", "闭环自检：通知链路正常（这不是真实物流动态）"
        )
        sb.append("-".repeat(60)).append('\n')
        sb.append("已发出一条自检通知（单号 SELFTEST）—— 请看通知栏确认收得到。\n")
        sb.append("能收到 ⇒ 「阶段跃迁→通知」这一环的推送路径通；\n")
        sb.append("判定表全绿 ⇒ 判定逻辑对。两者合起来 = 闭环成立。\n")

        try {
            val f = java.io.File(getExternalFilesDir(null), "track_selftest.txt")
            f.writeText(sb.toString())
            Log.i("TrackSelfTest", "-> ${f.absolutePath}")
        } catch (e: Throwable) {
            Log.w("TrackSelfTest", "落盘失败: ${e.message}")
        }
        appToast(this, "闭环自检完成：判定表 $pass/${cases.size}，并已发出一条通知", true)
    }

    /**
     * EMS 登录看护：每 2 秒从登录页 WebView 里读一次 `localStorage`（`phone`/`userId`/`H5token`）。
     * 一旦出现手机号就认为登录成功 —— 自动收尾，不用用户再点「完成」。
     *
     * ★ 为什么要轮询：EMS 的登录态**不是 cookie**，是 `localStorage`（源码：
     *   `isLogin(){ localStorage.getItem("phone") ? status=!0 : status=!1 }`）。
     *   cookie jar 那边什么都读不到，只能从页面里取。
     */
        /** EMS 顶栏「完成」按钮：手动收尾（页面还没刷新 localStorage 时用） */
            /** 打开 EMS 登录（走会员页，路由守卫会自己弹登录弹窗；扫码/短信都行） */
        /**
     * ★ EMS 探测：依次加载候选会员页，看哪一页真的会发 `/order/v3/` 请求，
     */
        /** 把探测结果按 URL 归类落盘，便于判断"哪条路径真的会拉订单" */
        /** 调试用：把拼多多登录态落盘（完整 cookie + 各域 jar），用来从 PC 复打判定 40001 的真因 */    private fun dumpPddState() {
        try {
            val sb = StringBuilder()
            val stored = PddChannel.cookies(this)
            sb.append("stored_len=").append(stored.length).append('\n')
            sb.append("stored=").append(stored).append('\n')
            sb.append("uid=").append(PddChannel.uid(this)).append('\n')
            sb.append("loginOk=").append(PddChannel.loginOk(this)).append('\n')
            sb.append("isLoggedIn=").append(PddChannel.isLoggedIn(this)).append('\n')
            val cm = CookieManager.getInstance()
            for (h in listOf(
                "https://mobile.yangkeduo.com", "https://yangkeduo.com", "https://pinduoduo.com",
                "https://mobile.pinduoduo.com", "https://m.pinduoduo.net", "https://api.pinduoduo.com",
                "https://passport.pinduoduo.com"
            )) {
                val ck = try { cm.getCookie(h).orEmpty() } catch (_: Throwable) { "" }
                sb.append("\n[").append(h).append("] len=").append(ck.length).append('\n')
                sb.append(ck).append('\n')
            }
            val f = java.io.File(getExternalFilesDir(null), "pdd_state.txt")
            f.writeText(sb.toString())
            Log.i("PddDump", "-> ${f.absolutePath} (${f.length()} B)")
        } catch (e: Throwable) {
            Log.w("PddDump", "落盘失败: ${e.message}")
        }
    }

    /** ★ vc311：取件码缓存 + 淘宝在途实况落盘 —— 「取走了不打标记」类问题的取证明入口 */
    private fun dumpPickupCache() {
        try {
            val sb = StringBuilder()
            val now = System.currentTimeMillis()
            sb.append("now=").append(now).append('\n')
            sb.append("tbPickedNow(").append(tbPickedNow.size).append(")=")
                .append(tbPickedNow.entries.joinToString { "${it.key}=${it.value}" }).append('\n')
            val all = PddCodeStore.all(this)
            sb.append("cache(").append(all.size).append("):\n")
            for ((k, c) in all) {
                sb.append("  ").append(k)
                    .append(" | src=").append(c.src)
                    .append(" | code=").append(c.code)
                    .append(" | station=").append(c.station)
                    .append(" | latest=").append(c.latest.take(60))
                    .append(" | tsAge=").append((now - c.ts) / 60_000L).append("m")
                    .append(" | pickedAt=")
                    .append(if (c.pickedAt == 0L) "0" else ((now - c.pickedAt) / 60_000L).toString() + "m前")
                    .append(" | manual=").append(if (manualPickedAt(k) != 0L) "Y" else "-")
                    .append('\n')
            }
            sb.append("tbTransit(").append(tbTransit.size).append("):\n")
            for (t in tbTransit) {
                sb.append("  ").append(t.optString("waybillNo"))
                    .append(" | arrived=").append(tbCardArrived(t))
                    .append(" | st=").append(t.optString("statusText"))
                    .append(" | latest=").append(t.optString("latest").take(50))
                    .append(" | code=").append(t.optString("pickupCode"))
                    .append('\n')
            }
            val f = java.io.File(getExternalFilesDir(null), "pickup_cache.txt")
            f.writeText(sb.toString())
            Log.i("CacheDump", "-> ${f.absolutePath}")
        } catch (e: Throwable) {
            Log.w("CacheDump", "落盘失败: ${e.message}")
        }
    }

    /** 调试用：把后台跟踪的当前状态落盘，adb pull 出来看（验证时不靠猜） */
    private fun dumpTrackState() {
        try {
            val items = ExpressStore.load(this)
            val tracked = ExpressStore.trackedNos(this)
            val min = ExpressStore.pollIntervalMin(this)
            val sb = StringBuilder()
            sb.append("pollIntervalMin=").append(min).append('\n')
            sb.append("trackMode=").append(ExpressStore.trackMode(this))
                .append(if (ExpressStore.trackMode(this) == ExpressStore.MODE_ALL) " (全部)" else " (指定)").append('\n')
            sb.append("canNotify=").append(TrackNotifier.canNotify(this)).append('\n')
            sb.append("tracked(").append(tracked.size).append(")=").append(tracked.joinToString(",")).append('\n')
            sb.append("muted(").append(ExpressStore.mutedNos(this).size).append(")=")
                .append(ExpressStore.mutedNos(this).joinToString(",")).append('\n')
            sb.append("snapshot=").append(items.size).append('\n')
            for (it in items) {
                sb.append("  ").append(it.no)
                    .append(" | src=").append(it.src)
                    .append(" | stage=").append(ExpressStore.stageOf(it).name)
                    .append(" | st=").append(it.statusText)
                    .append(" | latest=").append(it.latest.take(60))
                    .append('\n')
            }
            val f = java.io.File(getExternalFilesDir(null), "track_state.txt")
            f.writeText(sb.toString())
            Log.i("TrackDump", "-> ${f.absolutePath}")
        } catch (e: Throwable) {
            Log.w("TrackDump", "落盘失败: ${e.message}")
        }
    }

    /** 设置页「快递跟踪」卡里的一行：左名称 + 右状态（状态用 TextView 传入，便于原地改文案/颜色） */
    private fun trackRow(
        name: String,
        status: TextView,
        // ★ onMoreClick 必须排在 onClick 前面：调用方都用尾随 lambda，尾参永远匹配最后一个参数
        onMoreClick: (() -> Unit)? = null,
        onClick: () -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // ★ vc596：与 sourceRow / switchRow 同一套行规格（之前是 12/16，和另两类不一致）
            minimumHeight = (UiTok.ROW_H * dp).toInt()
            setPadding((UiTok.S16 * dp).toInt(), (UiTok.S12 * dp).toInt(),
                       (UiTok.S16 * dp).toInt(), (UiTok.S12 * dp).toInt())
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = name
            // ★ v2 统一：行卡主标题 16px Medium
            textSize = UiTok.T16
            typeface = tfMedium
            setTextColor(cTextPrimary)
        })
        // "更多"图标：紧贴名称文字右侧，高度跟主标题齐平（与 sourceRow 同一套）
        if (onMoreClick != null) {
            val iv = android.widget.ImageView(this).apply {
                setImageResource(R.drawable.mi_more)
                setColorFilter(cTextTertiary)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                isClickable = true
                setOnClickListener { onMoreClick() }
            }
            row.addView(iv, LinearLayout.LayoutParams(
                (16 * dp).toInt(), (24 * dp).toInt()
            ).apply { leftMargin = (3 * dp).toInt() })
        }
        // ★ vc597：不再用"弹簧 + 定宽状态"，状态直接吃掉剩余宽度（否则长文案被截成"…/"）
        status.apply {
            maxLines = 1
            gravity = Gravity.END
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        row.addView(status, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ).apply { leftMargin = (UiTok.S12 * dp).toInt() })
        applyPress(row)
        return row
    }

    /**
     * 「名称 ──── [开关]」的行 —— 只有名称和开关两个元素（同志 2026-09-22 定的）。
     *
     * 曾经带过一行状态文字（"已开启 · …"），已按同志要求删掉：那行里只要开关。
     * ★ 行内用弹性空隙把 Switch 推到行尾；不用「状态文字占 weight」那种写法了，
     *   否则文案一长就会把 Switch 挤出屏幕（真机 dump 实证过）。
     */
    private fun switchRow(
        name: String,
        checked: Boolean,
        // ★ vc703：与 sourceRow / trackRow 同一套 —— 有说明的行都在名称右侧挂一个同款 ⓘ
        onMoreClick: (() -> Unit)? = null,
        onToggle: (Boolean, android.widget.Switch) -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // ★ vc572：与 sourceRow 同一套行规格
            minimumHeight = (UiTok.ROW_H * dp).toInt()
            setPadding((UiTok.S16 * dp).toInt(), (UiTok.S12 * dp).toInt(),
                       (UiTok.S16 * dp).toInt(), (UiTok.S12 * dp).toInt())
            isClickable = true
            setOnClickListener {
                // 整行可点：直接切换开关（比只点小开关好按）
                val sw = getChildAt(childCount - 1) as? android.widget.Switch
                sw?.let { it.isChecked = !it.isChecked }
            }
        }
        row.addView(TextView(this).apply {
            text = name
            // ★ v2 统一：行卡主标题 16px Medium
            textSize = UiTok.T16
            typeface = tfMedium
            setTextColor(cTextPrimary)
        })
        // ★ vc703：说明图标（与 sourceRow 同款、同尺寸、同位置规则）
        if (onMoreClick != null) {
            val iv = android.widget.ImageView(this).apply {
                setImageResource(R.drawable.mi_more)
                setColorFilter(cTextTertiary)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                isClickable = true
                setOnClickListener { onMoreClick() }
            }
            row.addView(iv, LinearLayout.LayoutParams(
                (16 * dp).toInt(), (24 * dp).toInt()
            ).apply { leftMargin = (3 * dp).toInt() })
        }
        // 弹性空隙：把开关推到行尾
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(android.widget.Switch(this).apply {
            isChecked = checked
            // ★ vc575：同志反馈"开关太小" ⇒ 取消 0.82 缩放，用系统默认尺寸（约 52×32dp）
            //   44×26 只作为**视觉目标**写在规范里，实际以"手指好点"为准。
            // 与 animateSwitchTint 同一套口径（滑块=实心基础色，轨道=同色 38%），
            // 否则首帧是平台色、点一下才跳成我们的色
            // ★ v2 统一：开关 = 白滑块 + 凝光渐变轨道（#8FC0FA→#0080FF）；
            //   关闭态灰轨道。轨道用 GradientDrawable 承载渐变（tint 只能纯色）。
            thumbTintList = android.content.res.ColorStateList.valueOf(
                if (checked) 0xFFFFFFFF.toInt() else 0xFFB9BEC6.toInt()
            )
            trackDrawable = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = if (checked) intArrayOf(
                    UiTok.switchTrackTop(isDark()), UiTok.switchTrackBottom(isDark())
                ) else intArrayOf(UiTok.switchOff(isDark()), UiTok.switchOff(isDark()))
                cornerRadius = 999f
            }
            trackTintList = null
            setOnCheckedChangeListener { sw, c ->
                val s = sw as android.widget.Switch
                // 渐变轨道：直接换 drawable（tint 动画不适用于渐变，硬切）
                s.trackDrawable = GradientDrawable().apply {
                    orientation = GradientDrawable.Orientation.TOP_BOTTOM
                    colors = if (c) intArrayOf(
                        UiTok.switchTrackTop(isDark()), UiTok.switchTrackBottom(isDark())
                    ) else intArrayOf(UiTok.switchOff(isDark()), UiTok.switchOff(isDark()))
                    cornerRadius = 999f
                }
                s.thumbTintList = android.content.res.ColorStateList.valueOf(
                    if (c) 0xFFFFFFFF.toInt() else 0xFFB9BEC6.toInt()
                )
                onToggle(c, s)
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = (UiTok.S8 * dp).toInt() })
        return row
    }

    // ---------- 自绘圆角提示（替代系统 Toast，2026-10-15）----------

    private var appToastView: View? = null

    /**
     * ★ 自绘提示条（替代系统 Toast）
     *
     * 为什么换掉系统 Toast：ColorOS 上被渲染成**纯白直角矩形**，与 Linear 风格格格不入。
     * 设计口径（照 UI 统一规范）：
     *  · **圆角 14dp**，颜色随深浅模式（深=#1C1D1F 浮层 / 浅=白底），半透明细边框
     *  · **文字自动反色**：直接用 [cTextPrimary]/[cTextSecondary] 的 getter ——
     *    深色模式给 #f7f8f8、浅色模式给 #08090a，永远不会"黑字黑底/白字白底"
     *  · **不拦截触摸**（isClickable=false）：落在手势区上方也不会影响滑动返回
     *  · 底部居中偏上，淡入淡出；连点会替换上一条，不堆叠
     *
     * @param msg 字符串、资源 id、或任何可 toString 的对象
     * @param long 长提示（约 2.6 秒），默认短提示（约 1.7 秒）
     */
    fun appToast(ctx: Context, msg: Any?, long: Boolean = false) {
        // ★ 变量名别叫 text：Kotlin 里**局部变量优先于隐式接收者**，
        //   apply{} 里写 `text = text` 两边都会解析到这个局部 val（编译报 val cannot be reassigned）。
        val msgText = when (msg) {
            is Int -> try {
                ctx.getString(msg)
            } catch (_: Throwable) {
                ""
            }
            null -> ""
            else -> msg.toString()
        }
        if (msgText.isBlank() || isFinishing || isDestroyed) return
        try {
            appToastView?.let { v -> try { root.removeView(v) } catch (t: Throwable) { SafeRun.w("MainActivity", t) } }
            val dark = isDark()
            val tv = TextView(this).apply {
                text = msgText
                textSize = 15f
                setTextColor(cTextPrimary)                  // ★ 自动反色（getter 跟随 isDark）
                setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())
                maxWidth = (300 * dp).toInt()
                isClickable = false
                isFocusable = false
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 14 * dp                 // ★ 圆角
                    setColor(if (dark) 0xF21C1D1F.toInt() else 0xF2FFFFFF.toInt())
                    setStroke((1 * dp).toInt(), if (dark) 0x1FFFFFFF else 0x14000000)
                }
            }
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = (140 * dp).toInt()
            }
            appToastView = tv
            root.addView(tv, lp)
            tv.alpha = 0f
            tv.translationY = 14 * dp
            tv.animate().alpha(1f).translationY(0f).setDuration(170).start()
            tv.postDelayed({
                tv.animate().alpha(0f).translationY(10 * dp).setDuration(UiTok.D240)
                    .withEndAction {
                        try {
                            root.removeView(tv)
                        } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
                        if (appToastView === tv) appToastView = null
                    }.start()
            }, if (long) 2600L else 1700L)
        } catch (_: Throwable) {
            // 任何异常都不许让提示把功能搞挂
        }
    }

    /** Shizuku 开关"开不了被迫弹回"时，抑制掉随之而来的那次"已关闭"提示 */
    private var suppressShizukuOffToast = false

    /** 同理，Stellar 开关的抑制标志 */
    private var suppressStellarOffToast = false

    /**
     * 「名称 + 右侧状态文案 + 可点击」的行（给**状态型**设置项用，不是开关）。
     * 用于 Shizuku 保护进程这类"装没装、连没连上"只能展示、不能一键开关的东西。
     */
    private fun statusRow(name: String, status: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt())
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = name
            textSize = 15f
            setTextColor(cTextPrimary)
        })
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(TextView(this).apply {
            text = status
            textSize = 12f
            setTextColor(cTextSecondary)
        })
        return row
    }

    /**
     * Shizuku 保护进程说明弹窗（vc288 起放「常驻后台增强」卡片）
     *
     * ★★ 口径（项目铁律①的延伸）：**绝不要求用户装任何东西**。
     *   说清"这是可选增强、不装蜉蝣照样能用"，装不装由用户自己决定。
     */
    private fun showShizukuSheet() {
        val alive = ShizukuBridge.isWatchdogAlive()
        val msg = buildString {
            append(ShizukuBridge.statusText(this@MainActivity)).append('\n')
            append(
                if (alive) {
                    "\n它跑在独立进程里（shell 权限），主进程被系统回收后，它会在 45 秒内把服务叫回来。"
                } else {
                    "\n这是**可选增强**：没装 Shizuku 时，蜉蝣照样能用（常驻服务 + 开机自启 + " +
                        "通知监听/控制中心磁贴这三层都不需要任何特权），只是少了" +
                        "'被彻底回收后 45 秒自愈'这一层。\n\n" +
                        "要用它，需要你自己装 Shizuku 并激活（App 不会替你装、也不会引导你去装）。"
                }
            )
        }
        AlertDialog.Builder(this)
            .setTitle("常驻保护（Shizuku）")
            .setMessage(msg)
            .setPositiveButton(if (alive) "好" else "重新连接") { d, _ ->
                ShizukuBridge.bindWatchdog(this@MainActivity)
                d.dismiss()
            }
            .setNegativeButton("关闭", null)
            .showAnimated()
    }

    /** 跟踪范围选择：全部（默认，只在派件中/已到达发声）/ 指定（长按哪单盯哪单） */
    private fun showTrackModeSheet() {
        val opts = arrayOf(
            "全部包裹（默认）\n只在「派件中」「已到达」时通知",
            "指定包裹\n长按卡片添加，有变化就通知"
        )
        val cur = ExpressStore.trackMode(this)
        AlertDialog.Builder(this)
            .setTitle("跟踪范围")
            .setSingleChoiceItems(opts, if (cur == ExpressStore.MODE_MANUAL) 1 else 0) { d, w ->
                ExpressStore.saveTrackMode(
                    this,
                    if (w == 1) ExpressStore.MODE_MANUAL else ExpressStore.MODE_ALL
                )
                d.dismiss()
                refreshSettingsIfOpen()      // 卡片文案要跟着变，重建一次最省事
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showAnimated()
    }

    /**
     * 轮询间隔输入弹窗。0 = 关闭。
     *
     * ★ 弹窗里**如实说明 15 分钟下限**：用户填 5 分钟却按 15 分钟跑，不说明就是欺骗。
     *   （`PeriodicWorkRequest` 的周期低于 15 分钟直接抛 IllegalArgumentException，改不了。）
     */
    private fun showPollIntervalSheet(statusView: TextView) {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "分钟（0 = 关闭）"
            val cur = ExpressStore.pollIntervalMin(this@MainActivity)
            // ★ 0（关闭）时不预填：留着 hint 就好，否则预填的 "0" 会变成新数字的前缀
            if (cur > 0) setText(cur.toString())
            // ★ 光标放到末尾。adb `input text` 与部分输入法是按"当前光标位置"插入的，
            //   不设的话数字会插到最前面（填 15 变成 150 这种事已经发生过一次）。
            setSelection(text.length)
            textSize = 15f
            setTextColor(cTextPrimary)
            setHintTextColor(cTextFaint)
            setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 6 * dp
                setColor(if (isDark()) Color.parseColor("#0DFFFFFF") else Color.parseColor("#F7F8F8"))
                setStroke((1 * dp).toInt(), cBorder)
            }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
            addView(TextView(this@MainActivity).apply {
                text = "0 = 关闭。\n" +
                        "只轮询「有在途且已开启跟踪」的快递所属平台；\n" +
                        "跟踪件进入完成 / 异常后，该平台自动停止轮询。\n" +
                        "★ 系统对后台任务的下限是 15 分钟（填 1~14 也按 15 分钟跑），" +
                        "实际执行时间可能晚 1~3 分钟。"
                textSize = 12f
                setTextColor(cTextTertiary)
                setLineSpacing(0f, 1.3f)
                setPadding(0, 0, 0, (12 * dp).toInt())
            })
            addView(input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        AlertDialog.Builder(this)
            .setTitle("快递轮询")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val min = input.text.toString().trim().toIntOrNull() ?: 0
                ExpressStore.savePollIntervalMin(this, min)
                TrackScheduler.apply(this)
                // ★ 兜底闹钟跟着一起开/关：关掉轮询就不该再被叫醒
                if (min > 0) {
                    KeepAlive.setInterval(this, maxOf(min, ExpressStore.MIN_INTERVAL_MIN).toLong())
                    KeepAlive.arm(this)
                } else {
                    KeepAlive.disarm(this)
                }
                statusView.text = if (min <= 0) "默认关闭"
                else "每 ${maxOf(min, ExpressStore.MIN_INTERVAL_MIN)} 分钟 · 仅轮询有在途跟踪件的平台"
                statusView.setTextColor(if (min > 0) cAccent else cTextTertiary)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showAnimated()
    }

    /** 申请通知权限（Android 13+）。用户点「未开启 · 点此授权」时调用 */
    private fun requestNotifPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) {
            appToast(this, "系统设置里打开「通知」即可", true)
            return
        }
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_NOTIF) return
        val ok = TrackNotifier.canNotify(this)
        appToast(this, if (ok) "通知已开启" else "没拿到通知权限：请到系统设置里手动允许「通知」", true)
        if (ok) refreshSettingsIfOpen()
        // ★ vc698：自动申请的第二段 —— 通知对话框关掉之后，再弹电池优化白名单
        //   （两个系统对话框不能同时弹，只能串起来）
        if (autoPermFlow) {
            autoPermFlow = false
            mainHandler.postDelayed({ requestBatteryExemption() }, 700L)
        }
    }

    private fun refreshPickupBarSub() {
        // homeView 结构：title(0) subtitle(1) spacer(2) 淘(3) 拼(5) 菜(7) 码(9) footer(10)
        try {
            val bar = homeView.getChildAt(9) as? FrameLayout ?: return
            val row = bar.getChildAt(0) as? LinearLayout ?: return
            var textBox: LinearLayout? = null
            for (i in 0 until row.childCount) {
                val c = row.getChildAt(i)
                if (c is LinearLayout) textBox = c
            }
            (textBox?.getChildAt(1) as? TextView)?.text = pickupSubtitle()
        } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
    }

    /**
     * 身份码卡片 —— 对齐第 1 栏「淘宝身份码」的样子：一张白色大卡片，
     * 上半是 Code128 条形码（驿站扫码枪可直接读），下半是大字号号码（读不出时人工报号）。
     * 数据来自原生通道，全程不打开任何拼多多页面、不唤起拼多多 App。
     */
    private fun makeIdentityCard(pkg: JSONObject): View {
        // 主信息位：优先取件码；**顺丰没有取件码**，退回「派送驿站 / 状态文案」
        val code = pkg.optString("pickupCode")
            .ifEmpty { pkg.optString("stationText") }
            .ifEmpty { pkg.optString("statusText") }
        val goods = pkg.optString("goodsName")
        val status = pkg.optString("statusText")
        val place = pkg.optString("nickName")
        val tip = pkg.optString("tip")

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt())
        }

        // 顶部：来源角标 + 账号标签（多账号时一眼看出这是哪个号的码）
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = place.ifEmpty { getString(R.string.pdd_tag) }
                textSize = 12f
                setTextColor(cTextSecondary)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(chip(getString(R.string.pdd_tag), "#E02E24"), chipLp())
            val accLabel = pkg.optString("accountLabel")
            if (accLabel.isNotEmpty()) {
                addView(TextView(this@MainActivity).apply {
                    text = accLabel
                    textSize = 11f
                    setTextColor(cTextTertiary)
                }, chipLp())
            }
        })

        // 条形码：按可用宽度反推模块宽度，铺满卡片
        if (code.isNotEmpty()) {
            try {
                val mods = PddBarcode.modules(code).size + 24   // +24 = 两侧静默区各 12
                val avail = resources.displayMetrics.widthPixels -
                    ((16 + 16 + 18 + 18) * dp).toInt()          // 页面左右留白 + 卡片内边距
                val mw = (avail / mods.coerceAtLeast(1)).coerceAtLeast(1)
                val bmp = PddBarcode.bitmap(
                    code,
                    moduleWidth = mw,
                    height = (72 * dp).toInt(),
                    bgColor = Color.WHITE,
                    barColor = Color.BLACK
                )
                box.addView(ImageView(this).apply {
                    setImageBitmap(bmp)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(Color.WHITE)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (72 * dp).toInt()
                ).apply { topMargin = (12 * dp).toInt() })
            } catch (_: Throwable) {
                // 条码画不出来不影响号码展示
            }
        }

        // 大号号码（同志确认缩小：34f → 28f，保住醒目感的同时一屏能多看一张卡片）
        box.addView(TextView(this).apply {
            text = code
            textSize = 28f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(cTextPrimary)
            letterSpacing = 0.06f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (10 * dp).toInt() })

        // 明细：商品 · 状态
        // ★ vc699：商品长标题 → 小标签
        val detail = listOf(goodsTag(goods), status).filter { it.isNotEmpty() }.joinToString(" · ")
        if (detail.isNotEmpty()) {
            box.addView(TextView(this).apply {
                text = detail
                textSize = 12f
                setTextColor(cTextTertiary)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * dp).toInt() })
        }
        if (tip.isNotEmpty()) {
            box.addView(TextView(this).apply {
                text = tip
                textSize = 12f
                setTextColor(cTextSecondary)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * dp).toInt() })
        }

        box.addView(TextView(this).apply {
            text = getString(R.string.pdd_id_card_hint)
            textSize = 11f
            setTextColor(cTextFaint)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (12 * dp).toInt() })

        val cardBg = linearCardBg(12)
        val wrap = FrameLayout(this).apply {
            background = cardBg
            if (!isDark()) elevation = 2 * dp
        }
        wrap.addView(box, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        wrap.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return wrap
    }

    /** 小圆角色块标签（来源角标用） */
    /**
     * 平台标签（Linear 胶囊形）。
     * ★ 色底保留 —— 取件码场景要一眼认出是哪个平台，辨识度优先于"非彩色底"的纯粹性；
     *   但圆角改全胶囊、字号 11f、字重 medium，拿到 Linear 的形。
     */
    /**
     * ★ vc587：平台色标统一成「淡底 + 品牌色字」（与首页 36dp 品牌方块同一套语言）。
     * 以前是品牌色实底 + 白字 —— 一排卡片上挂着顺丰蓝/京东红/拼多多红，
     * 跟新的中性卡片放一起太跳（同志反复提的"调色板打翻"就是这个）。
     */
    /**
     * ★ vc692：卡片上「已取件」按钮的一键切换（用户要求：取件码旁边放按钮确认是否已取）。
     *
     * 逻辑与长按菜单里的「标记 / 取消已取件」**完全一致**，只是不用再绕一层弹窗：
     *   ① 标记要落到**整件包裹**上（同一个码常有两份缓存，只标一条会被去重后留下没标的那条）；
     *   ② 先写身份标记 [PickedStore]，再刷缓存条目 —— 否则取消标记会被 all() 又补回来；
     *   ③ 取消时必须走 putForce 强制清 0，不能被「保留旧标记」的兜底逻辑吃掉。
     */
    /**
     * ★★ v1.3.3：解析「这张卡在取件码缓存里对应哪一条」，并**保证一定有落点**。
     *
     * 原来标记逻辑自己拼键（`waybillNo` 优先），而缓存键对拼多多是**订单号**
     * （见 [pickKey]：pdd → orderSn 优先）⇒ 拼出来对不上，就弹
     * 「这单没有取件码缓存，没法标记」（同志 2026-11-09 截图报的现象，偶发即由此而来）。
     *
     * 现在：
     *   ① 先按卡片身份找（pickKey 的规范键 → 用 [parcelIds] 的全部身份在缓存里扫一遍）；
     *   ② 找不到、但卡片**有取件码** ⇒ 按卡片现成字段 upsert 一条（日志留痕），标记照常生效；
     *   ③ 连码都没有 ⇒ 返回 null（这种卡本来就不该有「已取件」这个动作）。
     */
    private fun resolvePickupKeyForCard(src: String, pkg: JSONObject): String? {
        try {
            val code = pkg.optString("pickupCode")
            val sn = pkg.optString("waybillNo").ifEmpty { pkg.optString("realWaybill") }
            val ids = LinkedHashSet<String>(parcelIds(src, pkg))
            if (code.isNotEmpty()) ids.add(code)
            if (sn.isNotEmpty()) ids.add(sn)
            val direct = pickKey(src, pkg)?.let { "$src:$it" }
            val cache = PddCodeStore.all(this)
            if (direct != null && cache.containsKey(direct)) return direct
            for ((ck, cc) in cache) {
                if (ck == direct || ck.substringAfter(':') in ids ||
                    cc.code in ids || cc.waybill in ids
                ) {
                    Log.i("PickupSync", "标记键按身份匹配到 $ck（卡片规范键=$direct）")
                    return ck
                }
            }
            if (code.isEmpty()) return null
            val key = direct ?: "$src:$code"
            PddCodeStore.putForce(
                this, key, PddCodeStore.Cached(
                    src = src.ifEmpty { "pdd" },
                    code = code,
                    station = pkg.optString("stationText")
                        .ifEmpty { pkg.optString("nickName") }.take(60),
                    waybill = sn,
                    latest = pkg.optString("latest").take(220),
                    ts = System.currentTimeMillis(),
                    pickedAt = 0L
                )
            )
            Log.i("PickupSync", "缓存里没有这条，已按卡片新建：$key code=$code")
            return key
        } catch (t: Throwable) {
            SafeRun.w("PickupSync", t)
            return null
        }
    }

    private fun togglePickedFromCard(pkg: JSONObject) {
        try {
            val src = pkg.optString("src")
            val code = pkg.optString("pickupCode")
            val sn = pkg.optString("waybillNo").ifEmpty { pkg.optString("realWaybill") }
            // ★ v1.3.3：键统一走解析器（原来自己拼 waybillNo 优先，与缓存键口径不一致 ⇒ 偶发"查不到"）
            val k = resolvePickupKeyForCard(src, pkg).orEmpty()
            val c = if (k.isNotEmpty()) PddCodeStore.get(this, k) else null
            if (c == null) {
                appToast(this, "这单没有取件码缓存，没法标记")
                return
            }
            // ★★ v1.4.8：**开关状态以磁盘为准**，不再读 `pkg.pickedAt` 这个内存值。
            //   原来读内存：取消成功后内存没同步 ⇒ 下一次点仍然判成"已取件"⇒ 又走取消分支
            //   ⇒ 用户看到的就是"怎么点都取消不掉"（真机 2026-11-10 复现）。
            val isPicked = c.pickedAt != 0L
            val ids = LinkedHashSet<String>(parcelIds(src, pkg))
            if (code.isNotEmpty()) ids.add(code)
            if (sn.isNotEmpty()) ids.add(sn)
            val newAt = if (isPicked) 0L else System.currentTimeMillis()
            if (isPicked) PickedStore.unmark(this, ids) else PickedStore.mark(this, ids)
            // ★★ v1.4.12（同志 13:08）：「标记完以后……**并且需要按之前的形式进入黑名单**」。
            //   旧版（v1.3.x）打「已取件」时往名单里塞三个身份：`pdd:订单号`、`源:运单号`、裸取件码；
            //   现在一条记录装下全部身份（订单号 > 运单号 > 取件码 的强度规则不变），
            //   只把**生效时间推到 24 小时后**——这 24 小时里卡片照旧显示给他核对，
            //   到点后自动退场，并且**不管缓存怎么重建都不会再被当成待取件抓回来**
            //   （这正是 v1.4.10「标记过的件又从在途栏冒出来」的根因：只靠 pickedAt 标记，
            //    缓存条目一重建标记就丢了，名单按订单号认人才不会）。
            val muteId = "picked:$k"
            val orderSn = pkg.optString("orderSn").ifEmpty { pkg.optString("order_sn") }
            // ★ v1.4.12：有些卡的 `waybillNo` 里装的其实就是订单号（历史数据被写过一次），
            //   直接塞进名单的运单号字段会让"运单号身份"和"订单号身份"撞在一起 —— 去掉。
            val wbRaw = pkg.optString("realWaybill").ifEmpty { pkg.optString("waybillNo") }
            val wb = if (wbRaw == orderSn || wbRaw == code) "" else wbRaw
            if (isPicked) {
                MuteStore.remove(this, muteId)
                // 兜底：万一 id 因缓存键变化对不上，按订单号再删一次
                if (orderSn.isNotBlank()) MuteStore.removeKey(this, "pdd:$orderSn")
                Log.i("MuteStore", "取消已取件 → 移出名单 $muteId")
            } else {
                MuteStore.addPicked(
                    c = this,
                    id = muteId,
                    src = src,
                    orderSn = orderSn,
                    waybill = wb,
                    code = code,
                    station = pkg.optString("stationText")
                        .ifEmpty { pkg.optString("stationName") }
                        .ifEmpty { pkg.optString("nickName") },
                    title = pkg.optString("goodsName"),
                    holdMs = TimeGates.PICKED_TAG_KEEP_MS
                )
            }
            var hit = 0
            for ((ck, cc) in PddCodeStore.all(this)) {
                val same = ck == k || ck.substringAfter(':') in ids ||
                        cc.code in ids || cc.waybill in ids
                if (!same) continue
                setManualPicked(ck, !isPicked)
                PddCodeStore.putForce(this, ck, cc.copy(pickedAt = newAt))
                hit++
            }
            pickupDirty = true
            Log.i("AcctScope", "卡片按钮切换已取件：$k → 同件缓存 $hit 条")
            appToast(
                this,
                if (isPicked) "已取消「已取件」标记"
                else "已标记取件 · 卡片保留 24 小时后自动撤掉"
            )
            // ★★ v1.4.8：磁盘改完立刻把内存卡片对齐，否则 renderMerged 画的还是旧状态
            syncPickedFlagsFromStore()
            if (pickupLayer != null) renderMerged()
            mainHandler.post { runCatching { rebuildHome() } }
        } catch (t: Throwable) {
            SafeRun.w("MainActivity", t)
        }
    }
    /**
     * ★ vc693：**一键自检** —— 把「这个软件现在还能不能用」摊成一张清单。
     *
     * 全部是**只读**检查（不发网络请求、不改缓存），失败项也能看出是"没授权"还是"真坏了"。
     */
    private fun runSelfTest(): String {
        val sb = StringBuilder()
        fun line(ok: Boolean?, name: String, detail: String) {
            val mark = when (ok) {
                true -> "✅"
                false -> "❌"
                else -> "•"
            }
            sb.append(mark).append(' ').append(name)
            if (detail.isNotEmpty()) sb.append("：").append(detail)
            sb.append('\n')
        }
        // 通知使用权（通知取件码那条链的前提）
        try {
            val enabled = android.provider.Settings.Secure.getString(
                contentResolver, "enabled_notification_listeners"
            ).orEmpty()
            val on = enabled.contains(packageName)
            line(on, "通知使用权", if (on) "已开启" else "未开启（通知里的取件码抓不到）")
        } catch (t: Throwable) {
            line(null, "通知使用权", "读取失败：${t.message}")
        }
        // 各平台登录态
        val pdd = try { PddChannel.isLoggedIn(this) } catch (t: Throwable) { false }
        val tb = try { TbChannel.isLoggedIn() } catch (t: Throwable) { false }
        val jd = try { JdChannel.isLoggedIn(this) } catch (t: Throwable) { false }
        val mi = try { MiChannel.isLoggedIn(this) } catch (t: Throwable) { false }
        line(pdd, "拼多多", if (pdd) "已登录" else "未登录")
        line(tb, "淘宝/菜鸟", if (tb) "已登录" else "未登录")
        line(jd, "京东", if (jd) "已登录" else "未登录")
        line(mi, "小米", if (mi) "已登录" else "未登录")
        // 取件码缓存（含通知来源与手动打标）
        try {
            val codes = PddCodeStore.all(this)
            val pendingN = codes.values.count { !PddCodeStore.isPicked(it) }
            val ntfN = codes.values.count { it.src == "ntf" }
            val ntfPicked = codes.values.count { it.src == "ntf" && PddCodeStore.isPicked(it) }
            line(codes.isNotEmpty(), "取件码缓存",
                "共 ${codes.size} 条，待取 $pendingN 条；通知来源 $ntfN 条（其中已标已取 $ntfPicked）")
        } catch (t: Throwable) {
            line(null, "取件码缓存", "读取失败：${t.message}")
        }
        // 在途缓存（这次新加的"按源回灌 + 退出落盘"就看这一行）
        try {
            val snap = ExpressStore.load(this)
            val f = transitCacheFile()
            val last = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_LAST_TRANSIT_TIME, "").orEmpty()
            val fmtd = if (f.exists()) java.text.SimpleDateFormat(
                "MM-dd HH:mm", java.util.Locale.CHINA
            ).format(java.util.Date(f.lastModified())) else "无"
            line(f.exists(), "在途缓存",
                "快照 ${snap.size} 条；文件 ${f.length() / 1024}KB（写入于 $fmtd）；上次更新 $last")
        } catch (t: Throwable) {
            line(null, "在途缓存", "读取失败：${t.message}")
        }
        // 后台轮询
        val iv = ExpressStore.pollIntervalMin(this)
        line(iv > 0, "后台轮询", if (iv > 0) "每 $iv 分钟" else "已关闭（不会自动刷新）")
        // ★ 各源条数 —— 「11 件只出 8 件」这类问题，一眼看出丢在哪一级
        line(null, "在途各源", "拼多多 ${pddTransit.size} / 京东 ${jdTransit.size} / " +
            "小米 ${miTransit.size} / 淘宝 ${tbTransit.size} / 手动 ${manualTransit.size}")
        // ★ v1.4.3：屏蔽名单条数 —— 对账「删掉的件去哪了」一眼可见
        try {
            val muted = pickupMutedAll()
            line(null, "屏蔽名单", "${muted.size} 条（删除取件码的件不再提醒；运输中仍照常显示）")
        } catch (t: Throwable) {
            line(null, "屏蔽名单", "读取失败：${t.message}")
        }
        // 预计到达时间解析（本次新增）
        val etaSample = etaForCard("运输中 预计明天送达", "", "运输中")
        val etaSample2 = etaForCard("", "", "派送中")
        line(etaSample.isNotEmpty(), "预计到达解析",
            "「预计明天送达」→「$etaSample」；派送态→「$etaSample2」")
        // 通知抠码解析（纯解析，不写缓存）
        val sample = "您的快件已到菜鸟驿站，取件码 8-3-2201，请及时领取"
        val got = IdNotifyListener.pickCode(sample)
        line(got == "8-3-2201", "通知抠码解析",
            if (got == "8-3-2201") "样例抠出 $got" else "样例只抠出「$got」（解析规则可能已失效）")
        return sb.toString().trim()
    }

    /** ★ vc693：自检结果弹窗（只读清单 + 一个「模拟一条取件码通知」按钮） */
    private fun showSelfTestDialog() {
        try {
            android.app.AlertDialog.Builder(this)
                .setTitle("自检")
                .setMessage(runSelfTest())
                .setNeutralButton("模拟一条取件码通知") { _, _ -> simulatePickupNotification() }
                .setPositiveButton("关闭", null)
                .showAnimated()
        } catch (t: Throwable) {
            appToast(this, "自检失败：${t.message}")
        }
    }

    /**
     * ★ vc693：**模拟一条取件码通知** —— 直接走通知监听那条链
     * （looksLikeExpress → pickCode → persistCodeFrom 的冲突判定 → 写缓存）。
     *
     * 用来验证「通知里有码就写入」这条功能，不必真等一条快递通知。
     * 刻意用**不重复**的样例码，避免第二次点就被"一致则丢弃"挡掉、看起来像失败。
     */
    private fun simulatePickupNotification() {
        try {
            val tail = (System.currentTimeMillis() % 9000 + 1000).toString()
            val sample = "您的快件已放至菜鸟驿站，取件码 9-2-$tail，请凭码取件"
            val code = IdNotifyListener.pickCode(sample)
            val r = IdNotifyListener.persistCodeFrom(this, sample, code)
            Log.i("NtfCode", "模拟通知：$r")
            appToast(this, r)
            if (pickupLayer != null) renderMerged()
            mainHandler.post { runCatching { rebuildHome() } }
        } catch (t: Throwable) {
            appToast(this, "模拟失败：${t.message}")
        }
    }
    /** ★ vc694：复制物流单号到剪贴板（用户要求：物流单号旁边给个复制按钮） */
    private fun copyWaybill(no: String) {
        if (no.isBlank()) return
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("waybill", no))
            appToast(this, "已复制单号 $no")
        } catch (t: Throwable) {
            SafeRun.w("MainActivity", t)
            appToast(this, "复制失败：${t.message}")
        }
    }
    // ---------------- ★ vc697：在途快递「按平台分开加载 + 左右滑动切换」 ----------------

    /** 当前能用的在途平台（有登录态 / 有手动单号才给标签），顺序 = 标签顺序 */
    private fun availableTransitSources(): ArrayList<String> {
        val out = ArrayList<String>()
        try {
            if (PddChannel.isLoggedIn(this)) out.add("pdd")
            if (JdChannel.isLoggedIn(this)) out.add("jd")
            if (TbChannel.isLoggedIn()) out.add("tb")
            if (miEnabled() && MiChannel.isLoggedIn(this)) out.add("mi")
            if (manualWaybills(this).isNotEmpty()) out.add("manual")
        } catch (t: Throwable) {
            SafeRun.w("TransitTab", t)
        }
        return out
    }

    /** 首选标签：沿用上次看的平台；不可用就优先拼多多，再不行取第一个可用平台 */
    private fun preferTransitSrc(avail: List<String>): String = when {
        avail.isEmpty() -> ""
        avail.contains(transitSrc) -> transitSrc
        avail.contains("pdd") -> "pdd"
        else -> avail.first()
    }

    /** 某个平台此刻有多少张卡（标签上的数字）。
     *  ★ v1.4.3：优先用过滤后的**显示数**（跨源去重+剔终态+名单+已到站分流之后），
     *  让标签和实际卡片数对得上；尚无统计（进页前一瞬）时回退源原始数。 */
    private fun transitCountOf(src: String): Int {
        transitShownCount[src]?.let { return it }
        return when (src) {
            "pdd" -> pddTransit.size
            "jd" -> jdTransit.size
            "tb" -> tbTransit.size
            "mi" -> miTransit.size
            "manual" -> manualTransit.size
            else -> 0
        }
    }

    private fun transitTabChipText(src: String): String {
        val n = transitCountOf(src)
        return srcName(src) + (if (n > 0) " $n" else "")
    }

    private fun applyTransitTabChipLook(tv: TextView, active: Boolean) {
        tv.text = transitTabChipText(tv.tag as? String ?: "")
        // ★ v2 统一：分段控件 = .seg —— 容器薰衣草、选中项白（夜 22% 白）+ 主文字色，
        //   未选中项透明（让容器的薰衣草透出来），不再整块高饱和蓝。
        tv.setTextColor(if (active) cTextPrimary else cTextSecondary)
        val g = tv.background as? GradientDrawable
        g?.setColor(if (active) UiTok.segSelBg(isDark()) else Color.TRANSPARENT)
    }

    /** 重建 / 刷新标签条（数量随渲染变化，每次渲染后调一次） */
    private fun refreshTransitTabs() {
        val row = transitTabRow ?: return
        try {
            val avail = availableTransitSources()
            if (avail.isEmpty()) {
                row.visibility = View.GONE
                return
            }
            row.visibility = View.VISIBLE
            if (!avail.contains(transitSrc)) transitSrc = preferTransitSrc(avail)
            if (row.childCount != avail.size) {
                row.removeAllViews()
                for (s in avail) {
                    val tv = TextView(this).apply {
                        tag = s
                        textSize = UiTok.T125
                        typeface = tfMedium
                        gravity = Gravity.CENTER
                        maxLines = 1
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = UiTok.R999 * dp
                        }
                        isClickable = true
                        setOnClickListener { switchTransitTabTo(s) }
                    }
                    applyTransitTabChipLook(tv, s == transitSrc)
                    // ★ v2 .seg .s：等分、32dp 高、3dp 间隙
                    row.addView(tv, LinearLayout.LayoutParams(
                        0, (32 * dp).toInt(), 1f
                    ).apply { leftMargin = (1 * dp).toInt(); rightMargin = (1 * dp).toInt() })
                }
            } else {
                for (i in 0 until row.childCount) {
                    val tv = row.getChildAt(i) as? TextView ?: continue
                    applyTransitTabChipLook(tv, (tv.tag as? String) == transitSrc)
                }
            }
        } catch (t: Throwable) {
            SafeRun.w("TransitTab", t)
        }
    }

    /** 点标签切换平台 */
    private fun switchTransitTabTo(src: String) {
        if (src == transitSrc) return
        transitSrc = src
        afterTransitTabChanged()
    }

    /** 左右滑动切换平台（delta = ±1，循环） */
    private fun switchTransitTabBy(delta: Int) {
        val avail = availableTransitSources()
        if (avail.size < 2) return
        val idx = avail.indexOf(transitSrc).let { if (it < 0) 0 else it }
        val nxt = ((idx + delta) % avail.size + avail.size) % avail.size
        if (avail[nxt] == transitSrc) return
        transitSrc = avail[nxt]
        afterTransitTabChanged()
    }

    /**
     * 切标签之后：先把内存里这一路的卡画出来（切页不闪白），
     * 本轮还没抓过这个平台才只抓它自己 —— 这就是"按平台分开加载"的落点。
     */
    private fun afterTransitTabChanged() {
        refreshTransitTabs()
        renderInTransit()
        if (transitSrc.isEmpty()) return
        if (transitRoundLoaded.contains(transitSrc)) {
            Log.i("TransitTab", "切到 ${srcName(transitSrc)}：本轮已抓过，直接用已有卡")
            transitStatus?.let { tv ->
                showStatusText(tv)
                val t = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString(KEY_LAST_TRANSIT_TIME, "") ?: ""
                if (t.isNotEmpty()) showUpdateThenHide(tv, "更新于 $t")
            }
            return
        }
        Log.i("TransitTab", "切到 ${srcName(transitSrc)}：本轮首次，只抓这一路")
        fetchInTransit(force = true)
    }

    /** 左右滑动（只旁听触摸事件，不抢 ScrollView 的滚动） */
    private fun attachTransitTabSwipe(scroll: View) {
        try {
            val gd = android.view.GestureDetector(this,
                object : android.view.GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: android.view.MotionEvent): Boolean = true
                    override fun onFling(
                        e1: android.view.MotionEvent?, e2: android.view.MotionEvent,
                        vx: Float, vy: Float
                    ): Boolean {
                        if (kotlin.math.abs(vx) <= kotlin.math.abs(vy) * 1.4f) return false
                        if (kotlin.math.abs(vx) < 260f) return false
                        switchTransitTabBy(if (vx < 0) 1 else -1)
                        return true
                    }
                })
            scroll.setOnTouchListener { _, ev ->
                try { gd.onTouchEvent(ev) } catch (t: Throwable) { SafeRun.w("TransitTab", t) }
                false   // 不消费事件 —— 竖向滚动照旧归 ScrollView / SwipeRefreshLayout
            }
        } catch (t: Throwable) {
            SafeRun.w("TransitTab", t)
        }
    }

    // ---------------- ★ vc698：权限主动申请 ----------------

    /** 自动申请流程进行中（通知对话框 → 关掉后再弹电池白名单） */
    private var autoPermFlow = false

    private fun notifGranted(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 33) return true
        return try {
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            SafeRun.w("Perm", t)
            true
        }
    }

    /** 通知使用权（NotificationListenerService 是否勾上）—— 从系统安全设置里读，无权限也能读自己 */
    private fun notifListenerGranted(): Boolean = try {
        android.provider.Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ).orEmpty().contains(packageName)
    } catch (t: Throwable) {
        SafeRun.w("Perm", t)
        false
    }

    private fun batteryExempt(): Boolean = try {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        pm.isIgnoringBatteryOptimizations(packageName)
    } catch (t: Throwable) {
        SafeRun.w("Perm", t)
        false
    }

    /**
     * 弹系统「电池优化白名单」对话框（Manifest 已声明 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS）。
     *
     * ★★ v1.4.17：**已加入时不再静默 return** —— 直接开系统电池优化列表页供核对，
     *   否则用户点「电池优化白名单」看到的是"完全没反应"（同志报的第二个无效项）。
     */
    private fun requestBatteryExemption() {
        if (batteryExempt()) {
            val ok = openFirst(
                Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS),
                appDetailsIntent()
            )
            appToast(
                this,
                if (ok) "已在白名单中，已打开系统列表供核对" else "已在白名单中（系统列表打不开）",
                true
            )
            return
        }
        var opened = false
        try {
            startActivity(Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            ))
            opened = true
            Log.i("Perm", "电池优化对话框已弹出（ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS）")
        } catch (t: Throwable) {
            Log.w("Perm", "电池优化对话框打不开：${t.javaClass.simpleName}，改试列表页")
            SafeRun.w("Perm", t)
        }
        if (!opened) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                opened = true
            } catch (t: Throwable) {
                SafeRun.w("Perm", t)
            }
        }
        if (!opened) appToast(this, "打不开电池优化设置")
    }

    /**
     * 一键申请：通知 → 电池白名单 → 通知使用权，**缺什么补什么，每次点击都有反馈**。
     *
     * ★★ v1.4.17：旧实现是「通知缺 ⇒ 弹；否则直接弹电池」，两个都已达标时**一路 return**，
     *   点击等于没反应（同志报的"一键申请权限点击无效"）。
     *   另外它从来没有管过「通知使用权」—— 那才是从通知里抓取件码的关键一项。
     *
     * 一次点击只能推进一项：运行时权限框、电池对话框、通知使用权列表页三者不能同时出，
     * 且跳系统页会离开 App（没有可靠的"回来了"回调）。所以每次都用 toast 报清**还剩哪几项**，
     * 用户再点一次继续。
     */
    private fun requestCorePermissions() {
        val missNotif = !notifGranted()
        val missBatt = !batteryExempt()
        val missListener = !notifListenerGranted()
        Log.i("Perm", "一键申请：通知缺=$missNotif 电池缺=$missBatt 通知使用权缺=$missListener")
        if (!missNotif && !missBatt && !missListener) {
            appToast(this, "权限已齐全：通知、电池白名单、通知使用权均已开启", true)
            refreshSettingsIfOpen()
            return
        }
        if (missNotif) {
            // 通知是运行时权限，必须当前 Activity 在前台弹；回调里会接着弹电池（autoPermFlow）
            autoPermFlow = true
            requestNotifPermission()
            return
        }
        if (missBatt) {
            autoPermFlow = false
            requestBatteryExemption()
            appToast(
                this,
                if (missListener) "还剩「通知使用权」未开，回来后再点一次" else "请在弹出的系统对话框里选「允许」",
                true
            )
            return
        }
        // 只剩通知使用权：跳系统列表页
        autoPermFlow = false
        val ok = openFirst(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        appToast(
            this,
            if (ok) "在列表里找到本应用，勾选「允许通知使用权」" else "打不开通知使用权设置",
            true
        )
    }

    /** 启动时自动弹一次；3 天内弹过就跳过（设置页仍有手动入口） */
    private fun maybeAutoRequestPermissions() {
        try {
            val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
            val needNotif = !notifGranted()
            val needBatt = !batteryExempt()
            if (!needNotif && !needBatt) return
            val last = sp.getLong("perm_prompt_at", 0L)
            if (last != 0L && System.currentTimeMillis() - last < 3L * 24 * 3600_000L) {
                Log.i("Perm", "权限申请 3 天内已弹过，本轮跳过（通知缺=$needNotif 白名单缺=$needBatt）")
                return
            }
            sp.edit().putLong("perm_prompt_at", System.currentTimeMillis()).apply()
            Log.i("Perm", "自动申请权限：通知缺=$needNotif 电池白名单缺=$needBatt")
            requestCorePermissions()
        } catch (t: Throwable) {
            SafeRun.w("Perm", t)
        }
    }

    /**
     * ★★ v1.4.17：刷新「权限与保活」卡上三处状态文字（不重建整页，避免滚动位置丢失）。
     * 传 null 的项跳过。
     */
    private fun refreshPermRowTexts(
        notifyRow: TextView?, battRow: TextView?, statusLine: TextView?
    ) {
        try {
            notifyRow?.let {
                val ok = notifListenerGranted()
                it.text = if (ok) "已开启" else "去系统设置 ›"
                it.setTextColor(if (ok) cTextSecondary else cTextTertiary)
            }
            battRow?.let {
                val ok = batteryExempt()
                it.text = if (ok) "已加入" else "去添加 ›"
                it.setTextColor(if (ok) cTextSecondary else cTextTertiary)
            }
            statusLine?.text = permStatusLine()
        } catch (t: Throwable) {
            SafeRun.w("Perm", t)
        }
    }

    // ---------------- ★★ v1.4.17：系统页跳转（后台保活三件套） ----------------

    /**
     * 依次尝试多个 Intent，任一成功即 `true`；全失败返回 `false`（异常不外抛）。
     *
     * 为什么要一次性试一串：各 ROM（ColorOS / MIUI / 华为 / vivo / 三星）的后台管理页
     * 组件名完全不同，且系统升级会改名。逐个 try 比 `resolveActivity` 可靠 ——
     * Android 11+ 的包可见性限制会让 `resolveActivity` 对未在 `<queries>` 声明的包直接返回 null，
     * 而显式组分跳转本身仍可成功。
     */
    private fun openFirst(vararg intents: Intent): Boolean {
        for (it in intents) {
            val tag = it.action ?: it.component?.flattenToShortString() ?: "?"
            try {
                startActivity(it)
                Log.i("Perm", "系统页跳转成功：$tag")
                return true
            } catch (t: Throwable) {
                Log.w("Perm", "系统页跳转失败：$tag → ${t.javaClass.simpleName}")
            }
        }
        return false
    }

    /** 本应用的「应用信息」页 —— 各家 ROM 的电池／自启动入口都挂在里面，万能兜底 */
    private fun appDetailsIntent(): Intent = Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:$packageName")
    )

    /**
     * 「后台运行 / 自启动」该跳的地方 —— 返回落点标识，调用方据此给不同提示：
     *
     * | 返回 | 落在哪 | 用户下一步 |
     * |---|---|---|
     * | `"startup"` | 各家 ROM 的自启动管理页 | 在列表里找到本应用，打开开关 |
     * | `"details"` | 应用详情页（里面有「耗电管理」） | 进「耗电管理」→「允许后台活动」 |
     * | `""` | 全失败 | 只能手动翻系统设置 |
     *
     * ★★ v1.4.17：原来是 `ACTION_MANAGE_OVERLAY_PERMISSION` —— 那是**悬浮窗**权限，
     * 而本应用根本没有 `SYSTEM_ALERT_WINDOW`，跳过去是一页「显示在其他应用上层」，
     * 与"后台能不能活"完全无关（同志报的第三项）。
     *
     * ★ v1.4.17 真机踩坑（一加 PJZ110 / ColorOS，Android 17）：
     * 显式组分 `com.oplus.battery/com.oplus.startupapp.view.StartupAppListActivity`
     * 在 App 内被 **SecurityException** 拦掉（该 Activity 未导出，`adb shell am start`
     * 以 shell 身份能过、普通应用不行 —— 所以"命令行能跳"不等于"App 能跳"）。
     * 而它同时声明了 intent-filter `com.oplus.battery.permission.startup.StartupAppListActivity`，
     * 隐式 action 交给系统解析有机会放行，因此隐式排在显式之前。
     */
    private fun openAutoStartPage(): String {
        val tagged = ArrayList<Pair<String, Intent>>()
        // ① 隐式 action（ColorOS 自启动页对外声明的入口）
        tagged.add("startup" to Intent("com.oplus.battery.permission.startup.StartupAppListActivity")
            .addCategory(Intent.CATEGORY_DEFAULT))
        tagged.add("startup" to Intent("com.oplus.battery.permission.startup.LaunchRecordActivity")
            .addCategory(Intent.CATEGORY_DEFAULT))
        tagged.add("startup" to Intent("safe.intent.action.OPT_AUTO_APP_ACTIVITY")
            .addCategory(Intent.CATEGORY_DEFAULT))
        // ② 显式组分（各家 ROM）
        for (c in ROM_AUTOSTART_COMPS) {
            val cn = android.content.ComponentName.unflattenFromString(c) ?: continue
            tagged.add("startup" to Intent().apply { component = cn })
        }
        // ③ 兜底：应用详情页（ColorOS 的「耗电管理 → 允许后台活动」在里面）
        tagged.add("details" to appDetailsIntent())

        for ((tag, it) in tagged) {
            val desc = it.action ?: it.component?.flattenToShortString() ?: "?"
            try {
                startActivity(it)
                Log.i("Perm", "后台管理页跳转成功（落点=$tag）：$desc")
                return tag
            } catch (t: Throwable) {
                Log.w("Perm", "后台管理页跳转失败：$desc → ${t.javaClass.simpleName}")
            }
        }
        return ""
    }

    /** 各家 ROM 的自启动管理页组分（按厂商顺序，末尾由 [openAutoStartPage] 兜应用详情页） */
    private val ROM_AUTOSTART_COMPS = listOf(
        // ColorOS / 一加 / realme
        "com.oplus.battery/com.oplus.startupapp.view.StartupAppListActivity",
        "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter/com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.coloros.phonemanager/com.coloros.phonemanager.permission.startup.StartupAppListActivity",
        "com.oplus.safecenter/com.oplus.safecenter.permission.startup.StartupAppListActivity",
        // MIUI / 澎湃
        "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
        // 华为 / 荣耀
        "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.hihonor.systemmanager/com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        // vivo / iQOO
        "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
        // 三星
        "com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity",
        // 原生「不受限制」电池优化列表
        "com.android.settings/com.android.settings.Settings\$HighPowerApplicationsActivity"
    )

    /** 「权限与保活」卡上的状态行 */
    private fun permStatusLine(): String {
        val a = if (notifGranted()) "通知：已开启" else "通知：未开启"
        val b = if (notifListenerGranted()) "通知使用权：已开" else "通知使用权：未开"
        val c = if (batteryExempt()) "电池白名单：已加" else "电池白名单：未加"
        return "$a · $b · $c"
    }

    // ---------------- ★ vc699：商品名 → 小标签 ----------------

    /** 商品标题里的促销/修饰噪音词（去掉后剩下的才是"这是个什么东西"） */
    private val GOODS_NOISE = listOf(
        "包邮", "特价", "活动", "促销", "正品", "官方", "旗舰", "新款", "当季", "新鲜", "现摘", "现挖",
        "加厚", "加大", "加宽", "宽松", "加绒", "超薄", "超厚", "家用", "商用", "多功能", "通用", "适用",
        "同款", "网红", "爆款", "限时", "秒杀", "清仓", "批发", "零售", "男士", "女士", "男生", "女生",
        "冬季", "夏季", "春秋", "秋季", "春季", "四季", "大码", "小码", "中码", "纯棉", "舒适", "亲肤",
        "透气", "高级感", "创意", "进口", "国产", "精选", "优选", "严选", "专用", "特级", "微瑕"
    )

    /** 规格/型号：数字 + 单位（"5斤装" "500ml" "10个" …） */
    private val GOODS_UNIT = Regex(
        "\\d+(\\.\\d+)?\\s*(g|kg|ml|l|G|KG|ML|L|斤|两|克|千克|毫升|升|个|件|双|只|条|片|瓶|盒|袋|包|箱|套|枚|支|卷|张|米|cm|CM|mm|寸|码|号|pcs|PCS|装|片装|斤装|套装)"
    )

    /** 品类词（命中最长的那个 —— 决定"这是个什么东西"） */
    private val GOODS_CATE = listOf(
        "运动鞋", "拖鞋", "凉鞋", "雨鞋", "布鞋", "皮靴", "棉服", "羽绒服", "外套", "大衣", "卫衣", "毛衣",
        "衬衫", "T恤", "背心", "内衣", "内裤", "睡衣", "袜子", "帽子", "围巾", "手套", "裤子", "牛仔裤",
        "裙子", "连衣裙", "手机壳", "充电宝", "数据线", "耳机", "键盘", "鼠标", "电池", "插座", "灯泡",
        "抽纸", "卷纸", "湿巾", "洗脸巾", "垃圾袋", "洗衣液", "洗洁精", "洗手液", "沐浴露", "洗发水",
        "牙膏", "牙刷", "口罩", "收纳", "保温杯", "水杯", "勺子", "筷子", "盘子", "饭盒", "炒锅", "汤锅",
        "电饭煲", "微波炉", "空气炸锅", "破壁机", "电磁炉", "菜刀", "剪刀", "砧板", "面膜", "眼霜",
        "面霜", "精华", "口红", "粉底", "防晒", "香水", "护手霜", "记号笔", "笔记本", "橡皮", "胶带",
        "柿子", "桔子", "橙子", "柚子", "桃子", "梨子", "李子", "枣子", "栗子", "橘子", "苹果", "香蕉",
        "葡萄", "芒果", "菠萝", "西瓜", "柠檬", "草莓", "蓝莓", "樱桃", "猕猴桃", "火龙果", "玉米",
        "红薯", "土豆", "山药", "南瓜", "番茄", "黄瓜", "白菜", "生菜", "菠菜", "萝卜", "茄子", "辣椒",
        "大蒜", "生姜", "蘑菇", "木耳", "海带", "大米", "面粉", "面条", "食用油", "酱油", "食盐",
        "牛奶", "奶茶", "咖啡", "茶叶", "饮料", "白酒", "啤酒", "坚果", "瓜子", "花生", "饼干", "面包",
        "化妆棉", "湿敷棉", "棉柔巾", "洗面奶", "洁面", "润唇膏", "卸妆油", "口红", "羊羔绒",
        "置物架", "收纳架", "收纳袋", "垃圾桶", "保鲜袋", "保鲜膜", "密封罐", "拖把", "扫把", "抹布",
        "抱枕", "枕套", "被套", "床单", "蚊帐", "凉席", "雨伞", "双肩包", "背包", "手提包", "钱包",
        "腰带", "皮带", "眼镜", "太阳镜", "发夹", "发绳", "梳子", "暖宝宝", "插排", "垃圾袋",
        "棉裤", "棉鞋", "棉拖", "棉袜", "棉被", "棉帽", "帽子", "袜子", "裤子",
        "鸡蛋", "鸡胸肉", "牛肉", "羊肉", "猪肉", "虾仁", "鱼", "虾", "蟹",
        "柑", "橘", "橙", "柚", "桃", "柿", "枣", "莓", "梨", "鞋", "靴", "袜", "帽",
        "衣", "服", "裤", "裙", "巾", "纸", "锅", "碗", "刀", "剪", "杯", "壶", "笔", "灯", "膜", "液", "霜", "皂"
    ).sortedByDescending { it.length }

    /** 修饰字：紧贴在品类词前面时不算名字的一部分（"丰脆柿子" → "柿子"） */
    private val GOODS_MOD = "脆甜新鲜大小好优纯正特超薄厚宽高多全老香美嫩软硬干湿精品级版款型号装盒袋".toSet()

    /** 小标签的胶囊底色 —— ★ v2 .gtag 口径：中性灰 rgba(0,0,0,.05)，不带品牌色 */
    private fun tagBg(): android.graphics.drawable.Drawable = sq(
        UiTok.R999, UiTok.gtagBg(isDark())
    )

    /**
     * ★ vc699：把商品长标题压成 2~6 字的**小标签**。
     *
     *   【彩箱】广西皇帝柑新鲜当季水果薄      → 皇帝柑
     *   VANCAMEL/西域骆驼座山雕猫耳棉服男款   → 猫耳棉服
     *   阳丰脆柿子5斤装                      → 柿子
     *   男款冬季加绒运动鞋                    → 运动鞋
     * 认不出来就退回"去噪后的前 6 字"；App 自己造的兜底名（"拼多多包裹·xxxxxx"）原样返回。
     */
    private fun goodsTag(rawIn: String): String {
        val raw = rawIn.trim()
        if (raw.isEmpty()) return ""
        if (raw.startsWith("拼多多包裹") || raw == "淘宝包裹" || raw == "快递包裹" ||
            raw == "通知里的取件码" || raw == "手动添加的单号"
        ) return raw
        var s = raw
        s = s.replace(Regex("[【\\[（(][^】\\]）)]*[】\\]）)]"), " ")
        s = s.replace(GOODS_UNIT, " ")
        for (w in GOODS_NOISE) s = s.replace(w, " ")
        s = s.replace(Regex("[A-Za-z0-9%×*+#/—…、,，.。·:：!！?？]"), " ")
        s = s.replace("-", " ").replace("|", " ")
        s = s.replace(Regex("\\s+"), "")
        if (s.isEmpty()) return raw.take(6)
        if (s.length <= 3) return s
        // 命中品类词：**位置最靠前的优先**（同位置取更长的那个）。
        //   例：「广西皇帝柑新鲜水果薄皮无渣橘子」里"柑"(第4位) 与"橘子"(第10位) 都在表里，
        //   要的是"皇帝柑"而不是"橘子" —— 先出现的就是这件东西本身。
        var cate: String? = null
        var cateIdx = Int.MAX_VALUE
        for (c in GOODS_CATE) {
            val i = s.indexOf(c)
            if (i < 0) continue
            if (i < cateIdx || (i == cateIdx && c.length > (cate?.length ?: 0))) {
                cate = c
                cateIdx = i
            }
        }
        val hit = cate
        if (hit != null) {
            // 双字以上品类词本身就是完整名字（棉服 / 柿子 / 运动鞋 / 手机壳…）
            if (hit.length >= 2) return hit
            // 单字品类词（柑 / 柿 / 鞋 / 帽…）要带上前面的限定字："皇帝柑"而不是"柑"
            var head = s.substring(0, cateIdx)
            while (head.isNotEmpty() && head.last() in GOODS_MOD) head = head.dropLast(1)
            val tag = (if (head.isEmpty()) "" else head.takeLast(2)) + hit
            return if (tag.length in 2..4) tag else hit
        }
        return s.take(6)
    }

    /**
     * ★ vc705：**取件位置标签**规则管理页（1.3 特性移植）。
     *
     * 结构与 1.3 一致：上面是已有规则列表（匹配文字 → 显示成 · 命中就停/继续），
     * 下面是「添加一条规则」表单（匹配文字 + 显示成 + 优先级 + 试一下）。
     * 1.3 的原话是"先在输入框写一条匹配文字，再用它试"——试测样本直接取库里真实的驿站原文。
     */
    private fun openLabelRulesPage(statusTv: TextView?) {
        if (labelLayer != null) return
        val layer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AmbientGlowDrawable(isDark())
        }
        attachTopBar(layer, makeTopBar("取件位置标签"))
        val scroll = ScrollView(this)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt())
        }
        scroll.addView(outer)
        layer.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        var rules = PickupLabel.load(this)

        fun refreshStatus() {
            statusTv?.text =
                if (rules.isEmpty()) "点击添加规则" else "已配置 ${rules.size} 条 · 点击管理"
        }

        fun rebuild() {
            outer.removeAllViews()
            outer.addView(TextView(this).apply {
                text = "把取件位置的原文压成你想看的短标签。匹配文字支持 * 通配；" +
                        "显示成留空 = 直接用匹配到的那段原文。"
                textSize = UiTok.T12
                setTextColor(cTextSecondary)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * dp).toInt() })

            val listCard = linearCard().apply { orientation = LinearLayout.VERTICAL }
            listCard.addView(TextView(this).apply {
                text = if (rules.isEmpty()) "还没有规则" else "已有规则"
                textSize = UiTok.T15
                typeface = tfMedium
                setTextColor(cTextPrimary)
            })
            for ((i, r) in rules.withIndex()) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = (UiTok.ROW_H * dp).toInt()
                    setPadding(0, (UiTok.S12 * dp).toInt(), 0, (UiTok.S12 * dp).toInt())
                    isClickable = true
                    isLongClickable = true
                }
                row.addView(TextView(this).apply {
                    text = r.match + "  →  " + r.show.ifBlank { "（原文）" } +
                            if (r.stop) "  ·  命中就停" else "  ·  继续"
                    textSize = 14f
                    setTextColor(cTextPrimary)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.setOnLongClickListener {
                    val list = PickupLabel.load(this).toMutableList()
                    if (i in list.indices) list.removeAt(i)
                    PickupLabel.save(this, list)
                    rules = PickupLabel.load(this)
                    appToast(this, "已删除这条规则")
                    refreshStatus()
                    rebuild()
                    true
                }
                listCard.addView(row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }
            if (rules.isNotEmpty()) {
                listCard.addView(TextView(this).apply {
                    text = "长按某条可删除"
                    textSize = 12f
                    setTextColor(cTextTertiary)
                    setPadding(0, (6 * dp).toInt(), 0, 0)
                })
            }
            outer.addView(listCard, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (16 * dp).toInt() })

            val form = linearCard().apply { orientation = LinearLayout.VERTICAL }
            form.addView(TextView(this).apply {
                text = "添加一条规则"
                textSize = UiTok.T15
                typeface = tfMedium
                setTextColor(cTextPrimary)
            })
            fun input(hintText: String): android.widget.EditText = android.widget.EditText(this).apply {
                hint = hintText
                textSize = 15f
                setTextColor(cTextPrimary)
                setHintTextColor(cTextTertiary)
                setSingleLine(true)
                val p = (12 * dp).toInt()
                setPadding(p, p / 2, p, p / 2)
            }
            val matchIn = input("匹配文字，如 北门*号快递柜")
            val showIn = input("显示成，如 北门柜（可留空）")
            form.addView(matchIn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * dp).toInt() })
            form.addView(showIn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * dp).toInt() })

            var stopMode = true
            val rStop = RadioButton(this).apply {
                text = "命中就停"
                textSize = 15f
                setTextColor(cTextPrimary)
                buttonTintList = android.content.res.ColorStateList.valueOf(cTextPrimary)
                isChecked = true
                setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            }
            val rPass = RadioButton(this).apply {
                text = "继续"
                textSize = 15f
                setTextColor(cTextPrimary)
                buttonTintList = android.content.res.ColorStateList.valueOf(cTextPrimary)
                setPadding((12 * dp).toInt(), (8 * dp).toInt(), 0, (8 * dp).toInt())
            }
            val grp = RadioGroup(this).apply {
                orientation = RadioGroup.HORIZONTAL
                addView(rStop)
                addView(rPass)
                setOnCheckedChangeListener { _, id -> stopMode = (id == rStop.id) }
            }
            form.addView(grp, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * dp).toInt() })

            val testTv = TextView(this).apply {
                textSize = 13f
                setTextColor(cTextSecondary)
                visibility = View.GONE
                setPadding(0, (8 * dp).toInt(), 0, 0)
            }
            form.addView(testTv, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))

            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val testBtn = chip("试一下", ACCENT_HEX).apply {
                isClickable = true
                setOnClickListener {
                    val sample = sampleStationText()
                    val r = PickupLabel.tryOne(
                        matchIn.text.toString(), showIn.text.toString(), sample
                    )
                    testTv.visibility = View.VISIBLE
                    if (r == null) {
                        testTv.setTextColor(cTextTertiary)
                        testTv.text = "这条匹配写法用不了，换一条试试。\n试测样本：" + sample
                    } else {
                        testTv.setTextColor(cTextSecondary)
                        testTv.text = "试测结果：" + r + "\n试测样本：" + sample
                    }
                }
            }
            val addBtn = chip("添加这条规则", "#2E9E5B").apply {
                isClickable = true
                setOnClickListener {
                    // ⚠️ 这里是 chip(...).apply{} 内部 ⇒ this 是 TextView，凡是要 Context 的一律显式写
                    val m = matchIn.text.toString().trim()
                    if (m.isEmpty()) {
                        appToast(this@MainActivity, "先写要匹配的文字")
                    } else {
                        val list = PickupLabel.load(this@MainActivity).toMutableList()
                        list.add(PickupLabel.Rule(m, showIn.text.toString().trim(), stopMode))
                        PickupLabel.save(this@MainActivity, list)
                        rules = PickupLabel.load(this@MainActivity)
                        refreshStatus()
                        appToast(this@MainActivity, "已添加")
                        rebuild()
                    }
                }
            }
            btnRow.addView(testBtn)
            btnRow.addView(addBtn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = (8 * dp).toInt() })
            form.addView(btnRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * dp).toInt() })
            outer.addView(form, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        rebuild()
        refreshStatus()
        root.addView(layer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        labelLayer = layer
        layerIn(layer)
    }

    private var labelLayer: LinearLayout? = null

    private fun closeLabelPage() {
        labelLayer?.let { root.removeView(it) }
        labelLayer = null
    }

    /** 试测样本：优先用库里最后一条取件码卡的驿站原文，没有就给个示例句 */
    private fun sampleStationText(): String = try {
        PddCodeStore.all(this).values.lastOrNull { it.station.isNotBlank() }?.station?.take(80)
            ?: "待取件 2026-11-09 08:41 您的快件已到达[代收点](菜鸟驿站)北门 3 号智能柜"
    } catch (t: Throwable) {
        SafeRun.w("PickupLabel", t)
        "待取件 2026-11-09 08:41 您的快件已到达[代收点](菜鸟驿站)北门 3 号智能柜"
    }

    /**
     * ★ vc707：设置页的「只读信息行」—— 行规格（行高/左右内边距/字号）与 sourceRow、
     *  trackRow、switchRow **完全一致**，只是行尾没有箭头、也不可点。
     *
     * 为什么需要它：自检 / 权限与保活 / 关于应用这三张卡原来是"分类大标题(15sp 加粗主色) +
     *  正文段落 + 11sp 小胶囊按钮"的排版，跟设置页其它卡（分类标题 13sp 次级色、内容一律是
     *  15sp 主文的行）不是一个体系 —— 同志说的"突兀"就是这一层字体与排版的不统一。
     */
    private fun infoRow(label: String, value: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (UiTok.ROW_H * dp).toInt()
            setPadding((UiTok.S16 * dp).toInt(), (UiTok.S12 * dp).toInt(),
                       (UiTok.S16 * dp).toInt(), (UiTok.S12 * dp).toInt())
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = UiTok.T15
            setTextColor(cTextPrimary)
        })
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        if (value.isNotBlank()) {
            row.addView(TextView(this).apply {
                text = value
                textSize = 13f
                setTextColor(cTextSecondary)
                maxLines = 1
                gravity = Gravity.END
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { rightMargin = (UiTok.S8 * dp).toInt() })
        }
        return row
    }

    /** ★ v2 统一：分类标题 = 13px · Medium · 次级色 · 字距 +0.2；左缩进 16dp 与行卡对齐 */
    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = UiTok.T13
        typeface = tfMedium
        letterSpacing = 0.015f
        setTextColor(cTextSecondary)
        // ★ v2 对齐：标题左缘 = 行卡左缘（16dp）；上下留呼吸，不再挤在卡边
        setPadding((UiTok.S16 * dp).toInt(), (12 * dp).toInt(), (UiTok.S16 * dp).toInt(), (8 * dp).toInt())
    }

    /** ★ v2 统一：卡内提示行 = 11.5px · 三级灰（.id .nm / .ccard .date 口径，比旧 12sp 弱一档） */
    private fun hintLine(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = UiTok.T115
        setTextColor(cTextTertiary)
        setPadding((UiTok.S16 * dp).toInt(), 0,
                   (UiTok.S16 * dp).toInt(), (UiTok.S8 * dp).toInt())
    }

    /**
     * ★ vc703：设置页统一的「这一行是干什么的」说明弹窗 ——
     *   与 sourceRow / trackRow / switchRow 的 ⓘ 同一个出口，样式（showAnimated）也一致。
     */
    private fun infoDialog(title: String, msg: String) {
        try {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("好", null)
                .showAnimated()
        } catch (t: Throwable) {
            SafeRun.w("Settings", t)
        }
    }

    private fun chip(text: String, colorHex: String): TextView = TextView(this).apply {
        // ★ v2 统一：平台胶囊 = .plat —— 樱粉底 + 深樱粉字（首页/取件码/在途共用一套），
        //   不再按品牌色各染各的（v2 稿口径：平台胶囊一律樱粉，身份区分交给身份码三格）。
        this.text = text
        textSize = UiTok.T13
        typeface = tfMedium
        letterSpacing = 0.015f
        setTextColor(UiTok.platText(isDark()))
        gravity = Gravity.CENTER
        background = sq(UiTok.R999, UiTok.platBg(isDark()))
        setPadding((UiTok.S12 * dp).toInt(), (4 * dp).toInt(), (UiTok.S12 * dp).toInt(), (4 * dp).toInt())
    }

    private fun chipLp(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { leftMargin = (6 * dp).toInt() }

    /**
     * vc279：这段文字像不像「一个地点」（收货地址或驿站/网点名）——
     * 顺丰无码件只在像地点时才给主信息位前缀「已到达」，状态文案不前缀。
     * 判据刻意保守：宁可不多前缀，也不要出现「已到达派送中」这种废话。
     * （顺丰运单字段名系推断、data 空数组无真实样本，等有件时按实际校正。）
     */
    private fun looksLikePlace(s: String): Boolean {
        if (s.length < 3) return false
        val statusWords = listOf(
            "运输中", "运送中", "已揽收", "派送中", "已发货", "待取件", "已到达",
            "签收", "完结", "已取", "异常", "问题件", "拒收", "退回"
        )
        if (statusWords.any { s.contains(it) }) return false
        val placeWords = listOf(
            "省", "市", "区", "县", "路", "街", "道", "号", "楼", "栋", "幢",
            "单元", "室", "层", "小区", "花园", "大厦", "广场", "大学", "学校",
            "学院", "医院", "驿站", "快递柜", "自提", "网点", "分拣", "园"
        )
        return placeWords.any { s.contains(it) }
    }

    // ---------- ★★ vc683：取件码卡上的「快递公司」标签 ----------

    /**
     * 只做 A 类（驿站/快递柜里最常见的那批）。识别顺序：
     * ① 接口给的代号 `upCpCode` → ② 接口给的公司名（排除平台名）→
     * ③ **运单号字母前缀**（最硬：快递公司自己编号）→ ④ 轨迹/状态文案里的公司名。
     * 四步都认不出 ⇒ 返回 null，**不显示标签**（绝不猜）。
     */
    private val COURIER_SHORT = listOf(
        "顺丰", "中通", "圆通", "申通", "韵达", "极兔", "京东", "邮政", "EMS",
        "德邦", "百世", "丰网", "菜鸟", "天天", "优速", "跨越", "宅急送", "速尔", "安能"
    )

    /** 运单号前缀 → 公司短名（长前缀必须排在短前缀前面，否则 JDVA 会被 JD 抢走） */
    private val COURIER_BY_PREFIX = listOf(
        "JDVA" to "京东", "JDVB" to "京东", "JDVC" to "京东",
        "SF" to "顺丰", "JT" to "极兔", "ZTO" to "中通", "STO" to "申通",
        "YT" to "圆通", "YD" to "韵达", "JD" to "京东", "EMS" to "EMS",
        "DPK" to "德邦", "DBL" to "德邦", "HTKY" to "百世", "BSHT" to "百世",
        "FW" to "丰网", "CN" to "菜鸟", "TT" to "天天", "UC" to "优速",
        "KY" to "跨越", "ZJS" to "宅急送", "SUER" to "速尔", "ANE" to "安能"
    )

    /** 文案里的公司名（拼多多轨迹常写「极兔速递」「韵达快递」） */
    private val COURIER_BY_TEXT = listOf(
        "极兔" to listOf("极兔", "J&T"), "中通" to listOf("中通"),
        "圆通" to listOf("圆通"), "申通" to listOf("申通"), "韵达" to listOf("韵达"),
        "顺丰" to listOf("顺丰"), "京东" to listOf("京东物流", "京东快递"),
        "EMS" to listOf("EMS", "ems"), "邮政" to listOf("邮政"),
        "德邦" to listOf("德邦"), "百世" to listOf("百世"), "丰网" to listOf("丰网"),
        "菜鸟" to listOf("菜鸟"), "天天" to listOf("天天快递"), "优速" to listOf("优速"),
        "跨越" to listOf("跨越"), "宅急送" to listOf("宅急送"), "速尔" to listOf("速尔"),
        "安能" to listOf("安能")
    )

    /** 公司短名 → 品牌色（chip 用） */
    private val COURIER_COLOR = mapOf(
        "中通" to "#0B4EA2", "圆通" to "#6E2A8C", "申通" to "#2B5CAA", "韵达" to "#D7000F",
        "极兔" to "#F04A23", "顺丰" to "#111111", "京东" to "#E1251B", "EMS" to "#005BAC",
        "邮政" to "#007A33", "德邦" to "#0057B8", "百世" to "#1B4E9B", "丰网" to "#C8102E",
        "菜鸟" to "#0091D5", "天天" to "#D7000F", "优速" to "#1B4E9B", "跨越" to "#005BAC",
        "宅急送" to "#D7000F", "速尔" to "#1B4E9B", "安能" to "#D7000F"
    )

    /** 把「京东物流/邮政快递包裹/EMS」这类长名收敛成短名 */
    private fun normalizeCourier(s: String): String? {
        val n = s.trim()
        if (n.isEmpty()) return null
        return COURIER_SHORT.firstOrNull { n.contains(it) }
    }

    /**
     * 认这单的快递公司；认不出返回 null（调用方据此不画标签）。
     */
    private fun courierTagOf(pkg: JSONObject): String? {
        // ① 接口代号
        val cpCode = pkg.optString("upCpCode").trim().uppercase()
        if (cpCode.isNotEmpty()) CP_NAMES[cpCode]?.let { n -> normalizeCourier(n)?.let { return it } }
        // ② 接口公司名（平台名不算：缓存补卡会把 upCpName 写成「拼多多」）
        val cpName = pkg.optString("upCpName")
        val selfName = srcName(pkg.optString("src"))
        if (cpName.isNotEmpty() && cpName != selfName) normalizeCourier(cpName)?.let { return it }
        // ③ 运单号字母前缀（最硬）
        val wb = pkg.optString("realWaybill").ifEmpty { pkg.optString("waybillNo") }.trim().uppercase()
        if (wb.length >= 2) {
            COURIER_BY_PREFIX.firstOrNull { wb.startsWith(it.first) }?.let { return it.second }
        }
        // ④ 文案兜底
        val text = listOf(
            pkg.optString("latest"), pkg.optString("traceLines"),
            pkg.optString("statusText"), pkg.optString("tip")
        ).joinToString(" ")
        if (text.isNotBlank()) {
            COURIER_BY_TEXT.firstOrNull { (_, kws) -> kws.any { text.contains(it) } }?.let { return it.first }
        }
        return null
    }

    private fun makePackageCard(pkg: JSONObject): View {        val code = pkg.optString("pickupCode")
        val shelf = pkg.optString("shelfNo")
        val waybill = pkg.optString("waybillNo")
        val src = pkg.optString("src")
        // 平台来源（拼多多/京东）没有菜鸟那套货架号，明细行改用「商品 · 状态」
        val isPlatform = src == "pdd" || src == "jd"
        val who = listOfNotNull(
            pkg.optString("nickName").takeIf { it.isNotEmpty() },
            pkg.optString("receiverName").takeIf { it.isNotEmpty() },
            pkg.optString("receiverPhone").takeIf { it.isNotEmpty() }
        ).joinToString(" ")
        // 明细行：拼多多/京东没有货架号/运单信息，用「商品 · 状态」代替
        //
        // ★ 快递公司名（cp）原来和平台 chip **并排显示在左上角**，而缓存补卡那条路把
        //   `upCpName` 直接写成了平台名（菜鸟/京东/拼多多）⇒ 左上角出现两个一模一样的
        //   「拼多多」—— 一个裸 TextView、一个带底色的 chip（同志报的重复）。
        //   ⇒ 2026-09-20 删掉了裸 TextView；vc683 起公司名改回头部、做成独立 chip
        //     （`courierTagOf`，带颜色、认不出就不显示），明细行不再重复它。
        // ★ vc280：拼多多卡印上下单日期（orderSn 前 6 位 = YYMMDD）——
        //   10-16 教训：6 月老单的码被 45 天清理删掉，用户以为"新单的码消失了"，
        //   卡上压根看不出新旧 ⇒ 明细行末尾补一格（如「在驿站待取 · 10-12下单」），
        //   老单一眼可辨，用户能自己决定删不删。
        val orderDate = if (src == "pdd") {
            val born = PddNative.snToDateMs(pkg.optString("orderSn"))
            if (born != null) {
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = born }
                String.format(
                    java.util.Locale.US, "%02d-%02d 下单",
                    cal.get(java.util.Calendar.MONTH) + 1,
                    cal.get(java.util.Calendar.DAY_OF_MONTH)
                )
            } else ""
        } else ""
        val detail = if (isPlatform) {
            // ★ vc340（PME110 用户实证）：PDD/京东卡也显示取件地址 —— 快递柜单在拼多多 App 里
            //   有「丰巢快递柜丨XX」而我们只有干巴巴的「在驿站待取」。有地址时**替换**状态词
            //   （卡片已在取件码栏，「待取」是废话；地址才是用户要的）。轨迹句式的站名
            //   （「…取件地址:XX驿站」）先抠出地址段再上屏，别把整句贴卡上。
            // ★ vc341：PDD 直连补卡的站名落在 stationName/nickName 两格（stationText 只在
            //   轨迹/enrich 路径才有）—— 显示端一并认领，别让「有名字的卡」因字段名对不上而空着。
            //   只对 pdd 放行：京东卡的 nickName 是最新轨迹文案，混进来会变成噪音。
            val addrRaw = pkg.optString("stationText").ifEmpty {
                if (src == "pdd") pkg.optString("stationName").ifEmpty { pkg.optString("nickName") } else ""
            }
            val addr = when {
                addrRaw.isEmpty() -> ""
                addrRaw.contains("取件地址") ->
                    Regex("取件地址[:：]([^,，。\\n]{2,40})").find(addrRaw)?.groupValues?.get(1)
                        ?.trim()?.take(28) ?: addrRaw.take(28)
                else -> addrRaw.take(28)
            }.let { if (isNonPlaceStationLabel(it)) "" else it }
            listOf(
                // ★ vc683：公司名已经做成头部 chip，明细行不再重复（原来会「极兔 · 极兔速递」）
                // ★ v1.4.9：商品标题已经单独占一行（见上方 goodsTitle），明细行不再重复它
                // ★ v2：下单时间已提升到卡顶独立行（.ccard .date），明细行不再带
                addr.ifEmpty { pkg.optString("statusText") }
            ).filter { it.isNotEmpty() }.joinToString(" · ")
        } else {
            // ★ vc302：有驿站地址的卡（淘宝到站卡等）明细行显示**地址** ——
            //   同志期望「底部是派送地址」而不是干巴巴的单号尾号；
            //   单号尾号只在拿不到地址时兜底。
            // ★ vc634：明细行地址只留**驿站短名**（原来整段物流原文会把这行撑爆）
            val addr = extractStationName(pkg.optString("stationText"))
            listOf(who, addr.ifEmpty { waybill.takeLast(6) })
                .filter { it.isNotEmpty() }.joinToString(" · ")
        }
        val tip = pkg.optString("tip")
        val detailAddress = extractStationName(pkg.optString("stationText"))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // ★ vc579：留白统一到令牌（20 / 16），与首页卡片同一套呼吸感
            setPadding((UiTok.S20 * dp).toInt(), (UiTok.S16 * dp).toInt(),
                       (UiTok.S20 * dp).toInt(), (UiTok.S16 * dp).toInt())
        }
        // 左：来源 + 取件码 + 说明
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ★ v2 卡顶下单时间（.ccard .date：11.5px 三级灰）—— 先看新旧，再看内容
        if (orderDate.isNotEmpty()) {
            left.addView(TextView(this@MainActivity).apply {
                text = orderDate
                textSize = UiTok.T115
                setTextColor(cTextTertiary)
                letterSpacing = 0.01f
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * dp).toInt() })
        }
        val headRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // ★ 这里原来还有一个裸 TextView 显示 `cp`（快递公司名，无背景底色）。
        //   它与右边的平台 chip 并排 ⇒ 缓存补卡时 cp 被写成平台名，左上角就出现两个
        //   「拼多多」（同志 2026-09-20 报的重复）。按同志要求**删掉没有背景底色那个**，
        //   公司名改在明细行显示（见上方 detail 的 cpExtra）。
        // 来源标签：一眼看出这单来自哪个平台（菜鸟 / 拼多多 / 京东）
        // ★ vc683：平台文案先落进 platText，快递公司标签要拿它去重（同名不重复画）
        var platText = ""
        when (src) {
            "pdd" -> { platText = getString(R.string.pdd_tag)
                headRow.addView(chip(platText, "#E02E24"), chipLp()) }
            "jd" -> { platText = getString(R.string.jd_tag)
                headRow.addView(chip(platText, "#E1251B"), chipLp()) }
            "mi" -> { platText = "小米"
                headRow.addView(chip(platText, "#FF6900"), chipLp()) }
            "manual" -> { platText = "手动"
                headRow.addView(chip(platText, "#BA7517"), chipLp()) }
            // ★ vc302：tb（淘宝订单在途）以前落进 else 分支被标成「菜鸟」——
            //   这单明明是淘宝发的货，用户看着困惑。给淘宝橙色专牌。
            "tb" -> { platText = "淘宝"
                headRow.addView(chip(platText, "#FF5000"), chipLp()) }
            // ★ vc343：菜鸟通道是**淘宝登录态**查出来的驿站包裹 —— 以前一律标「菜鸟」，
            //   同志买的是淘宝件、卡上却写菜鸟（2026-10-20 反馈）。改成先看包裹自带的平台字段，
            //   没有字段就按账号归属显示「淘宝」。
            "cainiao" -> {
                val (label, color) = cainiaoLabelOf(pkg)
                // ★ vc345：认不出平台就不加 chip（同志口径：不要猜一个平台名贴上去）
                if (label.isNotEmpty()) {
                    platText = label
                    headRow.addView(chip(label, color), chipLp())
                }
            }
            else -> { platText = getString(R.string.cainiao_tag)
                headRow.addView(chip(platText, "#0078FF"), chipLp()) }
        }
        // ★★ vc683：**快递公司标签**（同志 2026-11-03 要求）——
        //   跟平台 chip 排在同一行、紧跟在它后面；认不出公司就不画（不猜）。
        //   公司名与平台同名时不画（京东平台的卡不出现「京东 京东」）。
        courierTagOf(pkg)?.let { cpTag ->
            if (cpTag != platText) {
                headRow.addView(chip(cpTag, COURIER_COLOR[cpTag] ?: "#5F5E5A"), chipLp())
            }
        }
        // ★★ v1.4.9b：**商品标题与在途快递同款**（同志 2026-11-10 要求"按照在途快递那里的标题"）。
        //   在途卡上是「官方 chip + 商品小标签（棉服/内裤/润唇膏）」的排法，
        //   取件码卡原来直接贴一长串描述标题，两页观感对不上。
        //   ⇒ 统一走同一个 [goodsTag] 收敛 + 同一个 tagBg 样式、同一个字号，紧跟在平台 chip 后面。
        // ★ 缓存补出来的卡常常没有 goodsName ⇒ 按订单号回查字典（见 [PddTitleStore]）。
        var goodsRaw = pkg.optString("goodsName")
        if (goodsRaw.isEmpty() && src == "pdd") {
            val cachedTitle = PddTitleStore.get(this, pkg.optString("orderSn"))
            if (cachedTitle.isNotEmpty()) {
                goodsRaw = cachedTitle
                pkg.put("goodsName", cachedTitle)
            }
        }
        val goodsShort = goodsTag(goodsRaw)
        if (goodsShort.isNotEmpty() && goodsShort != platText) {
            headRow.addView(TextView(this@MainActivity).apply {
                text = goodsShort
                textSize = UiTok.T13
                typeface = tfMedium
                setTextColor(cTextSecondary)
                gravity = Gravity.CENTER
                background = tagBg()
                setPadding((8 * dp).toInt(), (3 * dp).toInt(), (8 * dp).toInt(), (3 * dp).toInt())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = (6 * dp).toInt() })
        }
        // 多账号：标出这单属于哪个账号 —— 第4栏混合列表就靠它区分「哪个包裹是哪个号的」
        val accLabel = pkg.optString("accountLabel")
        if (accLabel.isNotEmpty()) {
            headRow.addView(TextView(this@MainActivity).apply {
                text = accLabel
                textSize = UiTok.T11
                setTextColor(cTextTertiary)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = (6 * dp).toInt() })
        }
        // ★ vc229：已取件 ⇒ 打一个灰色「已取件」标记，让用户一眼知道"这单取过了"
        //   （卡片 24 小时后自动清除；期间不再算进「N 个待取」）
        val pickedAt = pkg.optLong("pickedAt", 0L)
        val isPicked = pickedAt != 0L
        if (isPicked) {
            headRow.addView(chip("已取件", "#757575"), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = (6 * dp).toInt() })
        }
        left.addView(headRow)

        // ★ 无码件（京东物流）**已到达** ⇒ 主信息位显示「已到达<地址>」
        //   地址来路：京东订单详情页 `progressList[]` 里 `progressId=="address"` 的 `tip`
        //   （见 JdChannel.fetchDetailAddress；订单列表 JSON 没有地址）
        val arrivedAddr = pkg.optString("arrivedAddress")
        // ★ 无取件码的源：主信息位不能是空行，改用「已到达<地址>/状态/最新轨迹」，字号降一档。
        // ★★ vc623：无码时主信息位显示**简短取件地址**（同志口径："取件码下方应该是简短地址"）。
        //   原来用 `tip`（=最新一条物流原文，常常上百字），卡片被撑成一大段。
        val mainText = code.ifEmpty {
            // ★ vc624：走 stationGroupName 的"短名提取"，避免把整句物流文案贴在码下面
            val addr = stationGroupName(pkg)
                .ifEmpty { arrivedAddr }
                .ifEmpty { extractStationName(pkg.optString("tip")) }
            when {
                addr.isNotEmpty() -> addr
                pkg.optString("tip").isNotEmpty() -> pkg.optString("tip").take(28)
                pkg.optString("statusText").isNotEmpty() -> pkg.optString("statusText")
                else -> "取件码获取中"
            }
        }
        // ★ vc692：取件码与「已取件」按钮**同一行**（用户要求：码旁边给个按钮确认取没取）。
        //   按钮只在真有取件码的卡上出现；点一下 = 长按菜单里那套「标记 / 取消已取件」，
        //   只是省掉一层弹窗。
        val codeTv = TextView(this@MainActivity).apply {
            text = mainText.ifEmpty { "—" }
            // ★★ v1.4.19（凝光）：取件码 28sp → **41sp**，全 App 只有它和首页的待取数字算大字。
            //   tabular（tnum）让每个数字等宽 —— 几张卡竖着排时，码能逐位对齐着核对。
            textSize = if (code.isEmpty()) UiTok.T15 else UiTok.T41
            if (code.isNotEmpty()) fontFeatureSettings = "tnum"
            typeface = tfMedium
            // ★ vc229：已取件 ⇒ 取件码划掉 + 转灰，和还在待取的卡明显区分开
            setTextColor(if (isPicked) cTextTertiary else cTextPrimary)
            if (code.isEmpty()) {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            if (isPicked) {
                paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            }
        }
        // ★★ v1.4.19（凝光）：41sp 的码 + 右边那颗「标记已取件」一行放不下（10 位码 ≈ 260dp，
        //   胶囊 ≈ 92dp，卡内可用宽度只有 ~300dp）⇒ 按钮下移到明细行右侧。
        //   凝光的版式本来就该是"数字在上、动作在下"，按钮贴着码反而挤。
        val codeRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(codeTv, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        left.addView(codeRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (2 * dp).toInt() })
        // ★ 无取件码的源，`mainText` 上面已经取过 tip 当主信息位了 ⇒ 这里别再加一遍
        //   （实测：手动单号的"查不到轨迹"在卡面上出现了两次）
        val tipShort = extractStationName(tip).ifEmpty { tip.take(40) }
        // ★ vc636：tip 提取出的地址与明细地址相同时不重复显示。
        //   之前同一地址一行来自 tip、一行来自 detail，视觉上像重复卡片信息。
        if (tipShort.isNotEmpty() && tipShort != mainText && tipShort != detailAddress) {
            // ★★ vc634：**这行必须是短的**。拼多多的 tip 常是整段物流原文
            //   （同志截图里一张卡下面铺了上百字 + 电话 + 投诉电话）。
            //   优先提取驿站短名；提不出就截断到 40 字 + 最多两行。
            left.addView(TextView(this@MainActivity).apply {
                text = tipShort
                textSize = UiTok.T13
                setTextColor(cTextSecondary)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * dp).toInt() })
        }
        // 明细行 + 「标记已取件」胶囊：明细占满左侧，按钮右对齐（凝光的"卡片脚"）
        val footRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        footRow.addView(TextView(this@MainActivity).apply {
            text = detail
            textSize = UiTok.T13
            setTextColor(cTextTertiary)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (code.isNotEmpty()) {
            // ★ v2 统一：操作按钮 = .tbtn（天青胶囊 + 强调字），不是平台胶囊（.plat 樱粉）——
            //   未取件 = accent-soft 蓝，已取件 = 中性灰（可撤销）。
            val markBtn = TextView(this@MainActivity).apply {
                text = if (isPicked) "取消已取件" else "标记已取件"
                textSize = UiTok.T13
                typeface = tfMedium
                gravity = Gravity.CENTER
                if (isPicked) {
                    setTextColor(cTextSecondary)
                    background = sq(UiTok.R999, UiTok.gtagBg(isDark()))
                } else {
                    setTextColor(cAccent)
                    background = sq(UiTok.R999, UiTok.accentSoft(isDark()))
                }
                setPadding((14 * dp).toInt(), (7 * dp).toInt(), (14 * dp).toInt(), (7 * dp).toInt())
            }
            markBtn.isClickable = true
            markBtn.setOnClickListener { togglePickedFromCard(pkg) }
            footRow.addView(markBtn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = (UiTok.S8 * dp).toInt() })
        }
        left.addView(footRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (UiTok.S8 * dp).toInt() })
        card.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 右：货架号
        if (shelf.isNotEmpty() && shelf != "-1" && shelf != "-" && shelf != "0") {
            // ★ vc636：接口用 -1 表示没有货架号，不能把内部哨兵值画给用户。
            // 货架号角标：实色底保留（要一眼看见去哪取），但圆角走全胶囊，与平台 chip 一致
            val chipBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 9999f
                setColor(Color.parseColor("#0A9E7B"))
            }
            card.addView(TextView(this@MainActivity).apply {
                text = shelf
                textSize = 15f
                typeface = tfMedium
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = chipBg
            }, LinearLayout.LayoutParams(
                (64 * dp).toInt(), (40 * dp).toInt()
            ))
        }

        // ★ v2 统一：卡片圆角 28dp（.card = 26px 超椭圆）—— 原来 12dp 太尖，不像系统卡
        val rounded = linearCardBg(UiTok.R28)
        val wrap = FrameLayout(this).apply {
            background = RippleDrawable(
                ColorStateList.valueOf(cRipple), rounded, rounded
            )
            if (!isDark()) elevation = 2 * dp
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                if (code.isEmpty() && arrivedAddr.isNotEmpty()) {
                    // ★ vc279：无码件点一下 = 复制**到达地址**（取件码本来就是空的，
                    //   复制它没意义；地址倒是有用——导航、告诉别人放哪了）
                    cm.setPrimaryClip(ClipData.newPlainText("address", arrivedAddr))
                    appToast(this@MainActivity, "已复制地址：" + arrivedAddr.take(24))
                } else {
                    cm.setPrimaryClip(ClipData.newPlainText("pickupCode", code))
                    appToast(this@MainActivity, getString(R.string.pickup_copied, code))
                }
            }
            // 长按 = 提示是否删除（2026-09-20 同志定的交互：点击复制 / 长按删除）。
            //   删除 = 卡片消失 + 取件码缓存删除 + 进屏蔽名单（防 track/status 或缓存自动补回）。
            //   注意：**不动**通知屏蔽/跟踪状态 —— 那是后台快递跟踪的功能，与这里无关。
            setOnLongClickListener {
                val sn = pickKey(src, pkg)
                val key = if (sn != null) "$src:$sn"
                else if (code.isNotEmpty()) "code:${pkg.optString("pickupCode")}" else null
                if (key == null) {
                    appToast(this@MainActivity, "这单没有可删除的取件码")
                } else {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("删除取件码")
                        .setMessage(
                            "取件码：" + code.ifEmpty { "（无）" } + "\n\n" +
                                    "删除后这张卡片会消失，\n同一包裹之后也不会再出现在这里（即使它还在驿站）。"
                        )
                        .setPositiveButton("删除") { _, _ ->
                            // ★★ vc670：按**这件包裹的全部身份**屏蔽 + 清缓存。
                            //   只屏蔽 `src:键` 时，同一件在另一个来源里的副本键不同，
                            //   下一次渲染就把它顶回来（用户实测"删了还在"）。
                            val ids = LinkedHashSet<String>(parcelIds(src, pkg))
                            if (code.isNotEmpty()) ids.add(code)
                            // ★★ v1.4.9：一条记录装下这件包裹的全部身份（不再写三个扁平键）
                            mutePackage(src, pkg)
                            // 缓存：键精确命中，或该件任一身份命中 ⇒ 两个来源的副本一起清
                            for ((k, c) in PddCodeStore.all(this@MainActivity)) {
                                if (k == key || c.code in ids || c.waybill in ids ||
                                    k.substringAfter(':') in ids
                                ) PddCodeStore.remove(this@MainActivity, k)
                            }
                            val mutedNow = pickupMutedAll()
                            fun drop(list: ArrayList<JSONObject>) {
                                list.removeAll { q -> isPickupMuted(q.optString("src"), q, mutedNow) }
                            }
                            drop(pddPkgs); drop(jdPkgs); drop(cainiaoPkgs)
                            drop(miPkgs); drop(manualPkgs)
                            tbTransit.removeAll { q -> isPickupMuted("tb", q, mutedNow) }
                            pickupDirty = true   // ★ vc673：删过 ⇒ 关层时重建首页
                            appToast(this@MainActivity, "已删除")
                            if (pickupLayer != null) renderMerged()
                        }
                        // ★ vc311：手动「已取件」开关 —— 平台状态不更新时的兜底（同志 10-18 报的
                        //   "取走了不打标记"）。打上后自动轮次不许再撤（见 manualPickedAt）。
                        .setNeutralButton(if (isPicked) "取消已取件" else "标记已取件") { _, _ ->
                            // ★ v1.3.3：与卡片按钮同一条键解析路径
                            val k = resolvePickupKeyForCard(src, pkg).orEmpty()
                            val c = if (k.isNotEmpty()) PddCodeStore.get(this@MainActivity, k) else null
                            if (c == null) {
                                appToast(this@MainActivity, "这单没有取件码缓存，没法标记")
                            } else {
                                // ★★ v1.4.8：**开关状态以磁盘为准**，不再用外层那个读内存
                                //   `pkg.pickedAt` 的 isPicked —— 取消后内存不同步时，下次长按
                                //   仍会显示"取消已取件"并再走一遍取消 ⇒ 用户觉得"取消不掉"。
                                val pickedNow = c.pickedAt != 0L
                                // ★★ vc673：**标记要落到整件包裹上，不能只落当前这一条缓存**。
                                //   同一个码常有两份缓存（拼多多一条、菜鸟一条），只标其中一条时，
                                //   首页按码去重后留下的恰好是**没标的那条** ⇒
                                //   "打了已取件，回首页还显示这个码"（同志 2026-11-03 报的）。
                                val ids = LinkedHashSet<String>(parcelIds(src, pkg))
                                if (code.isNotEmpty()) ids.add(code)
                                if (sn != null) ids.add(sn)
                                val newAt = if (pickedNow) 0L else System.currentTimeMillis()
                                // ★★ vc674：**先把身份标记写掉**，再刷缓存条目。
                                //   否则 PddCodeStore.all() 会按旧的身份标记把 pickedAt 又补回来，
                                //   导致"取消标记"看似成功、下一次读又变回已取件。
                                if (pickedNow) PickedStore.unmark(this@MainActivity, ids)
                                else PickedStore.mark(this@MainActivity, ids)
                                var hit = 0
                                for ((ck, cc) in PddCodeStore.all(this@MainActivity)) {
                                    val same = ck == k || ck.substringAfter(':') in ids ||
                                            cc.code in ids || cc.waybill in ids
                                    if (!same) continue
                                    setManualPicked(ck, !pickedNow)
                                    // ★ vc470：取消标记必须**强制清 0**，不能被「保留旧标记」兜住
                                    PddCodeStore.putForce(
                                        this@MainActivity, ck, cc.copy(pickedAt = newAt)
                                    )
                                    hit++
                                }
                                // ★ vc673：动过标记 ⇒ 关层时重建首页，避免首页还挂着这张码
                                pickupDirty = true
                                Log.i("AcctScope", "标记${if (pickedNow) "取消" else ""}已取件：$k → 同件缓存 $hit 条")
                                appToast(
                                    this@MainActivity,
                                    if (pickedNow) "已取消「已取件」标记" else "已标记为取件（24 小时后自动清除）"
                                )
                                // ★★ v1.4.8：磁盘改完立刻对齐内存卡片，界面才会当场变
                                syncPickedFlagsFromStore()
                                if (pickupLayer != null) renderMerged()
                                // 首页此刻若可见，立即跟着更新（不用等关层）
                                mainHandler.post { runCatching { rebuildHome() } }
                            }
                        }
                        .setNegativeButton("取消", null)
                        .showAnimated()
                }
                true     // ★ 吃掉长按：否则松开后还会触发 click，顺手把取件码复制了
            }
        }
        wrap.addView(card, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        wrap.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return wrap
    }

    // ---------- 下拉刷新容器 ----------
    // 页面卡住时下拉触发 reload（取件助手的排障提示证明这一步是刚需）

    private inner class PullContainer(context: android.content.Context) : FrameLayout(context) {
        private var downY = 0f
        private var armed = false
        private val threshold = 150 * dp

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = ev.y
                    armed = webView?.scrollY?.let { it <= 0 } == true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (armed && (webView?.scrollY ?: 1) <= 0 && ev.y - downY > threshold) {
                        armed = false
                        webView?.reload()
                        appToast(this@MainActivity, R.string.loading)
                    }
                }
            }
            return super.dispatchTouchEvent(ev)
        }
    }

    // ---------- 生命周期 / 返回键 / 文件选择回调 ----------

    /**
     * 返回的**唯一真相**：从上往下逐层关，返回 true 表示"吃掉了这次返回"。
     *
     * ★ 抽出来是因为有**两个入口**：
     *   · API 33+ → `OnBackInvokedCallback.onBackInvoked()`（开了 enableOnBackInvokedCallback 后，
     *     `onBackPressed()` **不会再被调用**）
     *   · API <33 → `onBackPressed()`
     *   两条路必须走同一套逻辑，不然侧滑会直接把 App 退掉。
     */
    private fun handleBack(): Boolean {
        Log.i(
            "Predictive",
            "handleBack: settings=${settingsLayer != null} web=${webLayer != null} " +
                    "transit=${transitLayer != null} pickup=${pickupLayer != null}"
        )
        // 截取编辑器压在小组件设置页上 ⇒ 最先关它（vc294）
        if (widgetCropLayer != null) {
            closeWidgetCrop()
            return true
        }
        // ★ vc705：位置标签规则页（压在设置页上）⇒ 优先关它
        if (labelLayer != null) {
            closeLabelPage()
            return true
        }
        // 黑名单页（压在设置页上）⇒ 优先关它
        if (blacklistLayer != null) {
            closeBlacklistPage()
            return true
        }
        // 小组件设置子页压在设置页上 ⇒ 最先关它（设置页留在原地）
        if (widgetStyleLayer != null) {
            closeWidgetStyle()
            return true
        }
        // 鲸鱼娘组件设置子页（vc296）同样压在设置页上
        if (whaleStyleLayer != null) {
            closeWhaleStyle()
            return true
        }
        // 设置页打开时其他层必然未开（设置键只在主页可点），放最前判断无歧义
        if (settingsLayer != null) {
            closeSettings()
            return true
        }
        // 网页层永远压在最上（去登录从别的层打开网页），按层级从上往下退
        val web = webView
        if (web != null && webLayer != null) {
            // ★ vc537：历史里只剩 about:blank 时别再"回退到白页"，直接关层
            var blankOnly = false
            try {
                val list = web.copyBackForwardList()
                val n = list.size
                if (n <= 1) {
                    blankOnly = true
                } else {
                    var nonBlank = 0
                    for (i in 0 until n) {
                        val u = list.getItemAtIndex(i)?.url ?: ""
                        if (!u.startsWith("about:")) nonBlank++
                    }
                    blankOnly = nonBlank <= 1
                }
            } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
            // ★ vc606：工具页（身份码）一次返回就关层，不做网页后退
            if (webBackClosesLayer) {
                Log.i("WebBack", "工具页返回 → 直接关层（忽略网页历史）")
                closeWeb()
                return true
            }
            if (web.canGoBack() && !blankOnly) web.goBack() else closeWeb()
            return true
        }
        if (transitLayer != null) {
            closeTransit()
            return true
        }
        if (pickupLayer != null) {
            closePickup()
            return true
        }
        return false
    }


    // ================= 桌面小组件设置页（vc289，借鉴南风天气小组件） =================

    /** 小组件设置子页（压在设置页上；返回键优先关它，见 handleBack/topLayer） */
    private var widgetStyleLayer: LinearLayout? = null
    private var widgetStyleClosing = false
    /** 小组件设置页的局部 refresh()，截完图后调它刷预览（vc294） */
    private var widgetStyleRefresh: (() -> Unit)? = null

    // ---- vc294 组件自定义图片：截取编辑器状态 ----
    private var widgetCropLayer: ViewGroup? = null
    private var widgetCropClosing = false
    private var pendingCropBitmap: Bitmap? = null
    private var pendingCropKey: String? = null

    private fun openWidgetStylePage() {
        if (widgetStyleLayer != null) return
        var sel = HomeWidgets.W_T2   // 当前编辑哪个组件
        val layer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AmbientGlowDrawable(isDark())
        }
        val back = TextView(this).apply {
            text = getString(R.string.btn_back)
            textSize = 15f
            setTextColor(cTextPrimary)
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
            setOnClickListener { closeWidgetStyle() }
        }
        back.layoutParams = LinearLayout.LayoutParams((76 * dp).toInt(), (52 * dp).toInt())
        attachTopBar(layer, makeTopBar("常规小组件", leading = back))

        val scroll = ScrollView(this)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt())
        }
        scroll.addView(outer)
        layer.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        fun label(txt: String): TextView = TextView(this).apply {
            text = txt
            textSize = 13f
            setTextColor(cTextSecondary)
            setPadding(0, (4 * dp).toInt(), 0, (8 * dp).toInt())
        }

        /** 设置页风格的圆角面板 */
        fun panel(): GradientDrawable = GradientDrawable().apply {
            cornerRadius = 14 * dp
            setColor(cPanel)
        }

        /** 胶囊底（选中=cAccent，未选=cPanel）；颜色刷新时重画 */
        fun chipBg(on: Boolean): GradientDrawable = GradientDrawable().apply {
            cornerRadius = 20 * dp
            setColor(if (on) cAccent else cPanel)
        }

        // ---- ① 选择组件（4 个 chip）----
        val names = mapOf(
            HomeWidgets.W_P2 to "2×2 取件码",
            HomeWidgets.W_P4 to "4×2 取件码",
            HomeWidgets.W_T2 to "2×2 在途快递",
            HomeWidgets.W_T4 to "4×2 在途快递"
        )
        outer.addView(label("选择小组件"))
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chipViews = HashMap<String, TextView>()
        for (k in HomeWidgets.ALL_KEYS) {
            val tv = TextView(this).apply {
                text = names[k]
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding((4 * dp).toInt(), (7 * dp).toInt(), (4 * dp).toInt(), (7 * dp).toInt())
            }
            chipViews[k] = tv
            chipRow.addView(tv, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = (6 * dp).toInt() })
        }
        outer.addView(chipRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (16 * dp).toInt() })

        // ---- ② 默认样式预览（实时跟随材质/不透明度）----
        outer.addView(label("组件默认样式预览"))
        val previewBox = FrameLayout(this).apply { background = panel() }
        outer.addView(previewBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (170 * dp).toInt()
        ).apply { bottomMargin = (16 * dp).toInt() })

        fun rebuildPreview() {
            previewBox.removeAllViews()
            // 预览统一用在途快递卡的示例数据（文案=同志指定的「快递已到达蜉蝣取件通」）；
            // 选 4×2 的组件就放 4×2 的布局，让形状差异也能看到
            val isBig = sel == HomeWidgets.W_P4 || sel == HomeWidgets.W_T4
            val v = layoutInflater.inflate(
                if (isBig) R.layout.widget_transit_4x2 else R.layout.widget_transit_2x2,
                previewBox, false
            )
            val bg = v.findViewById<ImageView>(R.id.w_bg)
            // ★ vc338：材质只剩浅/深两套 + 自动切换（跟随手机/自定义时间）—— 预览按「现在」解析出的样子显示
            val dark = HomeWidgets.resolvedDark(this, sel)
            // ★ vc335：预览比例改成真实格口口径（2×2=110:110、4×2=250:110），出图同比例
            //   —— 旧版容器 160×130/280×130 跟 1:1/2:1 出图不一致，全设备预览横向拉
            val aspect = if (isBig) 250f / 110f else 1f
            val pw = ((if (isBig) 260 else 130) * dp).toInt()
            val ph = (pw / aspect).toInt()
            // vc294：设了自定义图的组件，预览直接铺那张图（与真实组件"无数据时"的表现一致）
            // ★ vc336：同志 10-19 —— 旧版把示例快递文字叠在图上=重叠。桌面真实语义是
            //   「无快递→纯图藏字；有快递→材质+物流」，预览照此模拟：有图 ⇒ 纯图 + 全部文字 GONE；
            //   无图 ⇒ 材质 + 示例文字（调材质/透明度时的样子）
            val wb = HomeWidgets.widgetBitmap(this, sel, aspect)
            if (wb != null) {
                bg.setImageBitmap(wb)
                bg.imageAlpha = 255
                for (id in intArrayOf(
                    R.id.w_status, R.id.w_tag, R.id.w_trace, R.id.w_pill, R.id.w_trace2,
                    R.id.w_title, R.id.widget_empty, R.id.widget_grid
                )) {
                    v.findViewById<View?>(id)?.visibility = View.GONE
                }
            } else {
                bg.setImageResource(if (dark) R.drawable.wmat_glass else R.drawable.wmat_frost)
                bg.imageAlpha = HomeWidgets.alphaOf(this, sel) * 255 / 100
                // 浅色底 ⇒ 文字换深色（与真实组件渲染同口径）
                val light = !dark
                val primary = if (light) 0xE6000000.toInt() else 0xFFFFFFFF.toInt()
                val secondary = if (light) 0x99000000.toInt() else 0xB3FFFFFF.toInt()
                v.findViewById<TextView>(R.id.w_status)?.setTextColor(primary)
                v.findViewById<TextView>(R.id.w_trace)?.setTextColor(primary)
                v.findViewById<TextView>(R.id.w_trace2)?.setTextColor(secondary)
                v.findViewById<TextView>(R.id.w_pill)?.setTextColor(if (light) 0xCC000000.toInt() else 0xCCFFFFFF.toInt())
            }
            bg.tag = "wbg"   // 滑条拖动时按 tag 找到它实时调透明度
            previewBox.addView(v, FrameLayout.LayoutParams(pw, ph, Gravity.CENTER))
        }

        // ---- ③ 背景材质（★ vc338：只留浅/深两套底 + 跟随手机/自定义时间自动切换）----
        outer.addView(label("背景材质"))
        val matCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panel()
            setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
        }
        val matNames = listOf("浅色", "深色", "跟随手机", "自定义时间")
        val matRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val matViews = ArrayList<TextView>()
        for (i in matNames.indices) {
            val tv = TextView(this).apply {
                text = matNames[i]
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding((2 * dp).toInt(), (7 * dp).toInt(), (2 * dp).toInt(), (7 * dp).toInt())
            }
            matViews.add(tv)
            matRow.addView(tv, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = if (i < matNames.size - 1) (6 * dp).toInt() else 0 })
        }
        matCard.addView(matRow)
        matCard.addView(TextView(this).apply {
            text = "跟随手机：手机开深色模式时组件用深色底；自定义时间：所选时段内用深色底。"
            textSize = 12f
            setTextColor(cTextFaint)
            setPadding((4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt(), 0)
        })
        outer.addView(matCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (12 * dp).toInt() })

        // ★ vc338：「自定义时间」模式下才出现的深色时段两行（每组件各存一份，默认 22:00~07:00）
        //   显隐/回填统一在 refresh() 里做；点击监听放在 refresh 声明之后（局部函数先声明后引用）
        val matTimeBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val wgStartTv = TextView(this).apply {
            textSize = 15f
            setTextColor(cAccent)
            setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
        }
        val wgEndTv = TextView(this).apply {
            textSize = 15f
            setTextColor(cAccent)
            setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
        }
        fun wgTimeRow(lbl: String, valueTv: TextView): View {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
            }
            row.addView(TextView(this).apply {
                text = lbl
                textSize = 15f
                setTextColor(cTextPrimary)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(valueTv)
            return row
        }
        fun wgPickTime(cur: String, onOk: (String) -> Unit) {
            val p = cur.split(":")
            val d = TimePickerDialog(this, { _, h, m ->
                onOk(String.format(java.util.Locale.US, "%02d:%02d", h, m))
            }, p.getOrNull(0)?.toIntOrNull() ?: 22, p.getOrNull(1)?.toIntOrNull() ?: 0, true)
            dialogIn(d)
            d.show()
        }
        matTimeBox.addView(wgTimeRow("深色开始", wgStartTv))
        matTimeBox.addView(wgTimeRow("深色结束", wgEndTv))
        matTimeBox.addView(TextView(this).apply {
            text = "时段内组件用深色底；到点后随下一次刷新切换（后台每 15~25 分钟轮一轮，打开 App 也会立即刷）。"
            textSize = 12f
            setTextColor(cTextFaint)
            setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), (12 * dp).toInt())
        })
        outer.addView(matTimeBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (16 * dp).toInt() })

        // ---- ③b 自定义图片（vc294）：无快递时铺满显示；选图进截取编辑器 ----
        outer.addView(label("自定义图片（无快递时铺满显示）"))
        val imgCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panel()
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
        }
        val imgHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val imgStatus = TextView(this).apply {
            text = "未设置"
            textSize = 15f
            setTextColor(cTextPrimary)
        }
        imgHead.addView(imgStatus, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // ★ vc337：按钮必须自带胶囊底色 —— 旧版只给白字没铺底，浅色模式白卡片上整颗「隐身」
        //   （隐着但能点，误触清除就把图清了）。主按钮=主色实底白字；次按钮=描边空心+主题灰字，
        //   cAccent/cTextSecondary/cTextFaint 都跟主题走，深浅色都可见。
        fun imgBtn(txt: String, bg: Int, fg: Int = 0xFFFFFFFF.toInt(), stroke: Int = 0): TextView = TextView(this).apply {
            text = txt
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(fg)
            background = GradientDrawable().apply {
                cornerRadius = 20 * dp
                setColor(bg)
                if (stroke != 0) setStroke((1 * dp).toInt(), stroke)
            }
            setPadding((16 * dp).toInt(), (6 * dp).toInt(), (16 * dp).toInt(), (6 * dp).toInt())
        }
        val btnClearImg = imgBtn("清除图片", Color.TRANSPARENT, cTextSecondary, cTextFaint)
            .apply { setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt()) }
        imgHead.addView(btnClearImg, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = (8 * dp).toInt() })
        val btnPickImg = imgBtn("选择图片", cAccent)
        imgHead.addView(btnPickImg, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        imgCard.addView(imgHead)
        imgCard.addView(TextView(this).apply {
            text = "有物流信息时组件显示物流内容；没有时铺满显示这张图。选图后可拖动截取框，只留想显示的部分（截取框比例与组件一致）。"
            textSize = 12f
            setTextColor(cTextFaint)
            setPadding(0, (10 * dp).toInt(), 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        outer.addView(imgCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (16 * dp).toInt() })

        // ---- ④ 背景不透明度（滑条：拖动实时预览，松手保存并推桌面）----
        outer.addView(label("背景不透明度"))
        val alphaCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panel()
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
        }
        val alphaHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        alphaHead.addView(TextView(this).apply {
            text = "不透明度"
            textSize = 15f
            setTextColor(cTextPrimary)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val alphaText = TextView(this).apply {
            textSize = 15f
            setTextColor(cTextPrimary)
        }
        alphaHead.addView(alphaText)
        alphaCard.addView(alphaHead)
        val seek = SeekBar(this).apply { max = 100 }
        alphaCard.addView(seek, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (10 * dp).toInt() })
        outer.addView(alphaCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (12 * dp).toInt() })

        outer.addView(TextView(this).apply {
            text = "百分比越低，桌面壁纸透出越多；0% 表示完全透出壁纸。调整后立即生效。"
            textSize = 12f
            setTextColor(cTextFaint)
        })

        // ---- 刷新全部控件状态（Kotlin 局部函数先声明后引用 ⇒ 先定义、后挂监听）----
        fun refresh() {
            for (k in HomeWidgets.ALL_KEYS) {
                chipViews[k]?.background = chipBg(k == sel)
                chipViews[k]?.setTextColor(if (k == sel) 0xFFFFFFFF.toInt() else cTextPrimary)
            }
            val curMat = HomeWidgets.modeOf(this, sel)
            for (i in matViews.indices) {
                matViews[i].background = chipBg(i == curMat)
                matViews[i].setTextColor(if (i == curMat) 0xFFFFFFFF.toInt() else cTextPrimary)
            }
            // ★ vc338：自定义时间模式的时段行 —— 显隐跟随当前模式，值跟随当前选中的组件回填
            matTimeBox.visibility = if (curMat == HomeWidgets.MODE_TIMED) View.VISIBLE else View.GONE
            wgStartTv.text = HomeWidgets.darkStartOf(this, sel)
            wgEndTv.text = HomeWidgets.darkEndOf(this, sel)
            val cur = HomeWidgets.alphaOf(this, sel)
            alphaText.text = "$cur%"
            seek.progress = cur
            // 自定义图片状态（vc294）
            val hasImg = HomeWidgets.hasImage(this, sel)
            imgStatus.text = if (hasImg) "已设置 · 无快递时显示" else "未设置"
            btnClearImg.visibility = if (hasImg) View.VISIBLE else View.GONE
            rebuildPreview()
        }

        // ---- 监听器（refresh 已声明，可安全引用）----
        for (k in HomeWidgets.ALL_KEYS) {
            val tv = chipViews[k]!!
            tv.setOnClickListener {
                sel = k
                refresh()
            }
        }
        for (i in matViews.indices) {
            val tv = matViews[i]
            tv.setOnClickListener {
                HomeWidgets.saveMode(this, sel, i)
                HomeWidgets.updateAll(this)
                refresh()
            }
        }
        // ★ vc338：深色时段两行的时间选择（放在 refresh 声明之后才能引用它）
        wgStartTv.setOnClickListener {
            wgPickTime(wgStartTv.text.toString()) { v ->
                wgStartTv.text = v
                HomeWidgets.saveDarkWindow(this, sel, v, HomeWidgets.darkEndOf(this, sel))
                HomeWidgets.updateAll(this)
                refresh()
            }
        }
        wgEndTv.setOnClickListener {
            wgPickTime(wgEndTv.text.toString()) { v ->
                wgEndTv.text = v
                HomeWidgets.saveDarkWindow(this, sel, HomeWidgets.darkStartOf(this, sel), v)
                HomeWidgets.updateAll(this)
                refresh()
            }
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                alphaText.text = "$progress%"
                // 实时预览：只调预览卡的背景透明度（不落盘）
                previewBox.findViewWithTag<ImageView>("wbg")?.imageAlpha = progress * 255 / 100
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                HomeWidgets.saveAlpha(this@MainActivity, sel, sb?.progress ?: 100)
                HomeWidgets.updateAll(this@MainActivity)
                refresh()
            }
        })

        // 自定义图片：选图 → 截取编辑器；清除 → 立即还原（vc294）
        btnPickImg.setOnClickListener {
            pendingCropKey = sel
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            try {
                startActivityForResult(Intent.createChooser(intent, "选择图片"), REQ_WIDGET_IMG)
            } catch (t: Throwable) {
                Log.w("WidgetImg", "相册打不开：${t.message}")
                appToast(this, "打不开图片选择器：${t.message ?: ""}")
            }
        }
        btnClearImg.setOnClickListener {
            HomeWidgets.clearImage(this, sel)
            HomeWidgets.updateAll(this)
            refresh()
        }

        widgetStyleRefresh = { refresh() }
        refresh()

        root.addView(layer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        widgetStyleLayer = layer
        slideIn(layer)
    }

    private fun closeWidgetStyle() {
        val layer = widgetStyleLayer ?: return
        if (widgetStyleClosing) return
        widgetStyleClosing = true
        slideOut(layer) {
            root.removeView(layer)
            widgetStyleLayer = null
            widgetStyleClosing = false
        }
    }

    // ================= 鲸鱼娘组件设置页（vc296，同志 10-17） =================
    //
    // 干什么：选 8 套装扮（vc306 删除 4 套后重排；默认第一套=原学习装），选完立即推桌面。
    // 布局：顶部标题 + 8 个 chip（每行 3 个） + 实时预览 + 玩法说明 + 一句署名。

    private var whaleStyleLayer: LinearLayout? = null
    private var whaleStyleClosing = false

    private fun openWhaleStylePage() {
        if (whaleStyleLayer != null) return
        val layer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AmbientGlowDrawable(isDark())
        }
        val back = TextView(this).apply {
            text = getString(R.string.btn_back)
            textSize = 15f
            setTextColor(cTextPrimary)
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
            setOnClickListener { closeWhaleStyle() }
        }
        back.layoutParams = LinearLayout.LayoutParams((76 * dp).toInt(), (52 * dp).toInt())
        attachTopBar(layer, makeTopBar("鲸鱼娘组件", leading = back))

        val scroll = ScrollView(this)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (28 * dp).toInt())
        }
        scroll.addView(outer)
        layer.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        fun label(txt: String): TextView = TextView(this).apply {
            text = txt
            textSize = 13f
            setTextColor(cTextSecondary)
            setPadding(0, (4 * dp).toInt(), 0, (8 * dp).toInt())
        }
        fun panel(): GradientDrawable = GradientDrawable().apply {
            cornerRadius = 14 * dp
            setColor(cPanel)
        }
        fun chipBg(on: Boolean): GradientDrawable = GradientDrawable().apply {
            cornerRadius = 20 * dp
            setColor(if (on) cAccent else cPanel)
        }

        var sel = WhaleWidgets.kitOf(this)

        // ---- ① 选择装扮（12 个 chip，每行 3 个）----
        outer.addView(label("选择装扮"))
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chipViews = HashMap<String, TextView>()
        var row: LinearLayout? = null
        for ((i, kp) in WhaleWidgets.KITS.withIndex()) {
            if (i % 3 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(row!!, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (6 * dp).toInt() })
            }
            val tv = TextView(this).apply {
                text = kp.second
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding((2 * dp).toInt(), (8 * dp).toInt(), (2 * dp).toInt(), (8 * dp).toInt())
            }
            chipViews[kp.first] = tv
            row!!.addView(tv, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = if (i % 3 < 2) (6 * dp).toInt() else 0 })
        }
        outer.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (20 * dp).toInt() })

        // ---- ② 实时预览 ----
        outer.addView(label("预览（桌面实际效果）"))
        val previewBox = FrameLayout(this).apply { background = panel() }
        outer.addView(previewBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (190 * dp).toInt()
        ).apply { bottomMargin = (20 * dp).toInt() })

        fun rebuildPreview() {
            previewBox.removeAllViews()
            // 预览用真布局 inflate，把立绘换成选中套装的静态图，并把在途徽标显示出来示意
            // ★ vc300：4×2 面板里那行「装扮名」已按同志要求整行删除，这里也不再赋值
            val v = layoutInflater.inflate(R.layout.widget_whale_4x2, previewBox, false)
            val char = v.findViewById<ImageView>(R.id.wk_char)
            if (char != null) char.setImageResource(WhaleWidgets.kitAnimRes(sel))
            v.findViewById<TextView>(R.id.wk_info)?.let {
                it.visibility = View.VISIBLE
                it.text = "取件码 2 个"
            }
            v.findViewById<TextView>(R.id.wk_transit_badge)?.let {
                it.visibility = View.VISIBLE
                it.text = "3"
            }
            previewBox.addView(v, FrameLayout.LayoutParams(
                (300 * dp).toInt(), (150 * dp).toInt(), Gravity.CENTER
            ))
        }

        // ---- ③ 玩法说明 ----
        outer.addView(label("怎么玩"))
        val tipCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panel()
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
        }
        tipCard.addView(TextView(this).apply {
            text = "· 单击鲸鱼娘：她说一句话\n" +
                    "· 2×2：整幅立绘铺满，台词浮在底部气泡里\n" +
                    "· 4×2：左边是对话框，右边是鲸鱼娘\n" +
                    "· 双击鲸鱼娘：直接打开取件码页面\n" +
                    "· 有取件码时点 10 次，至少 7 次会报取件码\n" +
                    "· 只有在途快递或没有快递时，她只跟你闲聊（词库 200+ 句）\n" +
                    "· 有包裹在路上时，右下角有个蓝色小圆圈写着件数"
            textSize = 13f
            setTextColor(cTextPrimary)
            setLineSpacing((4 * dp), 1f)
        })
        outer.addView(tipCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (20 * dp).toInt() })

        // ---- ④ 底部署名（vc300：同志要求精简为一句） ----
        outer.addView(TextView(this).apply {
            text = "deepseek形象取于互联网"
            textSize = 11f
            setTextColor(cTextFaint)
            setLineSpacing((3 * dp), 1f)
        })

        fun refresh() {
            for ((k, tv) in chipViews) {
                tv.background = chipBg(k == sel)
                tv.setTextColor(if (k == sel) 0xFFFFFFFF.toInt() else cTextPrimary)
            }
            rebuildPreview()
        }

        for ((k, tv) in chipViews) {
            tv.setOnClickListener {
                sel = k
                WhaleWidgets.saveKit(this, k)
                WhaleWidgets.updateAll(this)
                refresh()
            }
        }

        refresh()
        root.addView(layer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        whaleStyleLayer = layer
        slideIn(layer)
    }

    private fun closeWhaleStyle() {
        val layer = whaleStyleLayer ?: return
        if (whaleStyleClosing) return
        whaleStyleClosing = true
        slideOut(layer) {
            root.removeView(layer)
            whaleStyleLayer = null
            whaleStyleClosing = false
        }
    }

    // ================= vc294 组件自定义图片：截取编辑器 =================
    //
    // 流程：相册选图（REQ_WIDGET_IMG）→ 全屏编辑器：图 fitCenter 铺开，
    // 上面浮一个**可拖动/可缩放的白框**（比例锁死为组件比例：2×2=1:1，4×2=2:1），
    // 点「用这个」把框内像素裁出来存成该组件的自定义图。
    // 拖动=平移选区；抓角上圆点=以对角为锚缩放（保持比例）；选区不许跑出图片外。

    private fun openWidgetCrop(key: String, bmp: Bitmap) {
        if (widgetCropLayer != null) return
        // ★ vc335：4×2 的裁剪比例从 2:1 改成真实格口 250:110≈2.27:1（与出图/桌面显示一致）
        val aspect = if (key == HomeWidgets.W_P4 || key == HomeWidgets.W_T4) 250f / 110f else 1f
        val layer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE6000000.toInt())
        }

        val cropView = WidgetCropView(this, bmp, aspect)
        layer.addView(cropView, LinearLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
        }
        fun barBtn(txt: String, color: Int, onClick: () -> Unit): TextView = TextView(this).apply {
            text = txt
            textSize = 15f
            setTextColor(color)
            gravity = Gravity.CENTER
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            setOnClickListener { onClick() }
        }
        bar.addView(barBtn("取消", cTextSecondary) { closeWidgetCrop() })
        bar.addView(TextView(this).apply {
            text = "拖动白框选区，抓角上圆点缩放"
            textSize = 13f
            setTextColor(cTextFaint)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(barBtn("用这个 ✓", cAccent) { confirmWidgetCrop(key, cropView) })
        layer.addView(bar, LinearLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        root.addView(layer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        widgetCropLayer = layer
        slideIn(layer)
    }

    private fun confirmWidgetCrop(key: String, cv: WidgetCropView) {
        val src = pendingCropBitmap
        if (src == null || src.isRecycled) { closeWidgetCrop(); return }
        val r = cv.bitmapCropRect()
        val l = r.left.toInt().coerceIn(0, src.width - 2)
        val t = r.top.toInt().coerceIn(0, src.height - 2)
        val w = r.width().toInt().coerceIn(2, src.width - l)
        val h = r.height().toInt().coerceIn(2, src.height - t)
        if (w < 8 || h < 8) {
            appToast(this, "选区太小，拖大一点再试")
            return
        }
        val cropped = Bitmap.createBitmap(src, l, t, w, h)
        val ok = HomeWidgets.saveImage(this, key, cropped)
        if (cropped != src) cropped.recycle()
        if (!ok) {
            appToast(this, "保存失败，再试一次")
            return
        }
        HomeWidgets.updateAll(this)
        widgetStyleRefresh?.invoke()
        appToast(this, "已保存，去桌面看看组件吧")
        closeWidgetCrop()
    }

    private fun closeWidgetCrop() {
        val layer = widgetCropLayer ?: return
        if (widgetCropClosing) return
        widgetCropClosing = true
        slideOut(layer) {
            root.removeView(layer)
            widgetCropLayer = null
            widgetCropClosing = false
            pendingCropBitmap = null   // 松开选图引用（Bitmap 交给 GC，不在 UI 线程外乱 recycle）
        }
    }

    /**
     * 截取画布：底层铺图（fitCenter），上层画遮罩 + 选框。
     * 比例锁定：拖角缩放时高由宽按 aspect 推出，锚定对角不动。
     */
    private inner class WidgetCropView(
        context: Context, val src: Bitmap, val aspect: Float
    ) : View(context) {

        private val imgRect = RectF()
        private val crop = RectF()
        private val paintBmp = Paint(Paint.FILTER_BITMAP_FLAG)
        private val paintDim = Paint().apply { color = 0x99000000.toInt() }
        private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * dp
        }
        private val paintDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }

        private var mode = 0          // 0 无 / 1 平移 / 2~5 角点（tl tr br bl）
        private var lastX = 0f
        private var lastY = 0f
        private val hitR = 26f * dp   // 角点命中半径
        private val minW = 56f * dp   // 选区最小宽

        private fun layoutImage() {
            val s = minOf(width / src.width.toFloat(), height / src.height.toFloat())
            val w = src.width * s
            val h = src.height * s
            imgRect.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        }

        private fun initCrop() {
            // 图片内能放下的、比例=aspect 的最大居中框
            var w = imgRect.width()
            var h = w / aspect
            if (h > imgRect.height()) { h = imgRect.height(); w = h * aspect }
            val cx = imgRect.centerX()
            val cy = imgRect.centerY()
            crop.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            layoutImage()
            if (crop.isEmpty) initCrop()
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val x = e.x; val y = e.y
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = x; lastY = y
                    mode = when {
                        dist(x, y, crop.left, crop.top) <= hitR -> 2
                        dist(x, y, crop.right, crop.top) <= hitR -> 3
                        dist(x, y, crop.right, crop.bottom) <= hitR -> 4
                        dist(x, y, crop.left, crop.bottom) <= hitR -> 5
                        crop.contains(x, y) -> 1
                        else -> 0
                    }
                    return mode != 0
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = x - lastX; val dy = y - lastY
                    lastX = x; lastY = y
                    if (mode == 1) {
                        // 平移：整体夹在图片内
                        val ddx = if (dx > 0) minOf(dx, imgRect.right - crop.right) else maxOf(dx, imgRect.left - crop.left)
                        val ddy = if (dy > 0) minOf(dy, imgRect.bottom - crop.bottom) else maxOf(dy, imgRect.top - crop.top)
                        crop.offset(ddx, ddy)
                    } else if (mode in 2..5) {
                        // 角点缩放：对角为锚，比例锁定
                        val anchorX = when (mode) { 2 -> crop.right; 3 -> crop.left; 4 -> crop.left; else -> crop.right }
                        val anchorY = when (mode) { 2 -> crop.bottom; 3 -> crop.bottom; 4 -> crop.top; else -> crop.top }
                        var w = kotlin.math.abs(x - anchorX).coerceAtLeast(minW)
                        var h = w / aspect
                        if (h > imgRect.height()) { h = imgRect.height(); w = h * aspect }
                        if (w > imgRect.width()) { w = imgRect.width(); h = w / aspect }
                        val l = if (x >= anchorX) anchorX else anchorX - w
                        val t = if (y >= anchorY) anchorY else anchorY - h
                        // 夹回图片内（保持尺寸，整体挪进来）
                        val cl = l.coerceIn(imgRect.left, imgRect.right - w)
                        val ct = t.coerceIn(imgRect.top, imgRect.bottom - h)
                        crop.set(cl, ct, cl + w, ct + h)
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { mode = 0; return true }
                else -> return false
            }
        }

        private fun dist(px: Float, py: Float, ax: Float, ay: Float) =
            kotlin.math.sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay))

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            c.drawBitmap(src, null, imgRect, paintBmp)
            // 选框外遮罩（四条）
            c.drawRect(imgRect.left, imgRect.top, imgRect.right, crop.top, paintDim)
            c.drawRect(imgRect.left, crop.bottom, imgRect.right, imgRect.bottom, paintDim)
            c.drawRect(imgRect.left, crop.top, crop.left, crop.bottom, paintDim)
            c.drawRect(crop.right, crop.top, imgRect.right, crop.bottom, paintDim)
            c.drawRect(crop, paintLine)
            // 四角圆点（抓手）
            for (p in listOf(
                crop.left to crop.top, crop.right to crop.top,
                crop.right to crop.bottom, crop.left to crop.bottom
            )) {
                c.drawCircle(p.first, p.second, 5f * dp, paintDot)
            }
        }

        /** 选框换算回原图坐标（给 confirmWidgetCrop 裁剪用） */
        fun bitmapCropRect(): RectF {
            val s = src.width / imgRect.width().coerceAtLeast(1f)
            return RectF(
                (crop.left - imgRect.left) * s, (crop.top - imgRect.top) * s,
                (crop.right - imgRect.left) * s, (crop.bottom - imgRect.top) * s
            )
        }
    }

    /**
     * 导出日志（vc268，同志 10-15 要求）：
     * 设置页「日志 → 导出日志」（vc288 单独列卡放最底部）—— 把本应用缓冲区里的全部日志收集起来，
     * 弹系统「另存为」让同志自定义保存位置和文件名。
     *
     * 权限说明（铁律①：不要求任何特殊权限）：
     * · Android 4.1 起，App 执行 `logcat` 只能读到**自己 UID** 的日志 ⇒
     *   天然就是"蜉蝣的全部日志"，不需要 READ_LOGS；
     * · 保存走 ACTION_CREATE_DOCUMENT（SAF），写哪个目录都不需要存储权限。
     * ⚠️ 范围口径：导出的是**当前日志缓冲区**里的内容（系统会滚动覆盖最老的），
     *    不是"从安装至今的全部历史"——日志这东西系统本来就只留最近一段。
     */
    /**
     * ★ vc446：读本应用日志，带回退。
     *
     * 背景：某用户的 nubia 机器上 `logcat -d --pid=<pid>` 返回空 ⇒ 导出文件只剩表头。
     * 策略：
     *  ① pid 过滤（快，绝大多数机器可用）
     *  ② 全量读 main+crash，再按 threadtime 第 3 列 PID 在本地筛
     *  ③ 都不行 → 返回 null + 具体原因（写进导出文件，便于判断是"没日志"还是"被系统拦了"）
     */
    /**
     * ★★ vc487：导出日志重写。
     *
     * 旧实现用 `logcat --pid=<当前 pid>` 过滤 —— 只要 App 中途被系统杀掉重启过，
     * **用户刚刷新那一段（旧进程）的日志就全被丢掉**，导出来只剩"刚打开设置"那几行。
     * 真机证据：2026-10-27/28 两份日志都只有 40~100 行、全在 openSettings 附近。
     *
     * 新策略：
     *  ① 读**整个 logcat 缓冲区**（不带 --pid，天然跨进程）；
     *  ② 按 [LogTags] 白名单过滤（既去掉系统噪音，又保留历史进程）；
     *  ③ 再把 FileLog 的 `app.log`（跨进程持久化）追加进来；
     *  ④ 上限 6MB，超出从旧行截断（保最新）。
     */
    private fun collectLogcat(): Pair<ByteArray?, String> {
        var err = ""
        val sb = StringBuilder()
        try {
            val p = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "threadtime", "-b", "main", "-b", "crash")
            )
            val raw = p.inputStream.readBytes().toString(Charsets.UTF_8)
            val errText = p.errorStream.readBytes().toString(Charsets.UTF_8).trim()
            p.waitFor()
            val all = raw.split('\n')
            var kept = 0
            for (line in all) {
                if (line.isBlank()) continue
                if (LogTags.matches(line)) {
                    sb.append(line).append('\n')
                    kept++
                }
            }
            Log.i("ExportLogs", "全缓冲区=${all.size} 行，命中白名单=$kept 行 err=${errText.take(120)}")
            if (kept == 0) err = "缓冲区里没有本应用的行（${errText.ifBlank { "logcat 正常" }}）"
        } catch (t: Throwable) {
            err = "logcat 调用异常：" + (t.message ?: t.javaClass.simpleName)
            Log.w("ExportLogs", err)
        }
        // ③ FileLog（跨进程，专门用来兜底）
        try {
            val f = java.io.File(getExternalFilesDir(null), "app.log")
            if (f.exists() && f.length() > 0) {
                sb.append("\n================ FileLog（跨进程持久化） ================\n")
                sb.append(f.readText())
            }
        } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        if (sb.isEmpty()) return null to err
        var bytes = sb.toString().toByteArray(Charsets.UTF_8)
        val cap = 6 * 1024 * 1024
        if (bytes.size > cap) {
            val text = String(bytes, Charsets.UTF_8)
            bytes = text.substring(text.length - cap / 2).toByteArray(Charsets.UTF_8)
        }
        return bytes to err
    }

    /** ★ vc484：导出日志附带的状态快照（不依赖 logcat，短日志也能排障） */
    private fun diagSnapshot(): String {
        val sb = StringBuilder()
        sb.append("================ 状态快照 ================\n")
        sb.append("淘宝：uid=").append(TbChannel.uid().ifEmpty { "无" })
            .append(" 登录=").append(if (TbChannel.isLoggedIn()) "是" else "否").append('\n')
        sb.append("拼多多：uid=").append(PddChannel.uid(this).ifEmpty { "无" })
            .append(" loginOk=").append(PddChannel.loginOk(this)).append('\n')
        sb.append("京东：pin=").append(JdChannel.pin(this).ifEmpty { "无" })
            .append(" 登录=").append(if (JdChannel.isLoggedIn(this)) "是" else "否").append('\n')
        sb.append("小米：登录=").append(if (MiChannel.isLoggedIn(this)) "是" else "否").append('\n')
        sb.append("快递100：开关=").append(if (Kd100Channel.isEnabled(this)) "开" else "关").append('\n')
        sb.append("—— 本轮源错误 ——\n")
        sb.append("淘宝：").append(cainiaoErr ?: "无").append('\n')
        sb.append("拼多多：").append(pddErr ?: "无").append('\n')
        sb.append("—— 数量 ——\n")
        val items = try { ExpressStore.load(this) } catch (_: Throwable) { emptyList() }
        val codes = try { PddCodeStore.all(this) } catch (_: Throwable) { emptyMap() }
        val manual = try { manualWaybills(this) } catch (_: Throwable) { emptyList<String>() }
        sb.append("快照条目=").append(items.size)
            .append("（").append(items.groupingBy { it.src.ifEmpty { "?" } }.eachCount()).append("）\n")
        sb.append("取件码缓存=").append(codes.size).append("  手动单号=").append(manual.size).append('\n')
        if (manual.isNotEmpty()) {
            sb.append("手动单号：").append(manual.joinToString(", ")).append('\n')
        }
        if (items.isNotEmpty()) {
            sb.append("—— 快照明细（最多 30 条）——\n")
            for (it in items.take(30)) {
                sb.append("src=").append(it.src).append(" no=").append(it.no)
                    .append(" stage=").append(ExpressStore.stageOf(it))
                    .append(" code=").append(it.pickupCode)
                    .append(" status=").append(it.statusText.take(40))
                    .append(" 站=").append(it.stationText.take(30)).append('\n')
            }
        }
        if (codes.isNotEmpty()) {
            sb.append("—— 取件码缓存明细（最多 30 条）——\n")
            for ((k, c) in codes.entries.take(30)) {
                sb.append(k).append(" code=").append(c.code)
                    .append(" 站=").append(c.station.take(30))
                    .append(" pickedAt=").append(if (c.pickedAt == 0L) "0" else "已标记").append('\n')
            }
        }
        sb.append("========================================\n\n")
        return sb.toString()
    }

    /** ★ vc509：日志隐私模式（默认开）—— 只在导出 zip 时打码，落盘日志保持原文 */
    private fun logPrivacyOn(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("log_privacy_mode", true)

    private fun showLogPrivacyDialog() {
        val on = logPrivacyOn()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt(), (6 * dp).toInt())
        }
        box.addView(TextView(this).apply {
            text = "是否开启日志隐私模式"
            textSize = 15f
            setTextColor(cTextPrimary)          // 深浅色自动反色
            gravity = Gravity.CENTER
        })
        box.addView(TextView(this).apply {
            text = "开启后将自动给手机号、地址等关键信息打码。"
            textSize = 15f
            setTextColor(cTextPrimary)   // ★ vc511：与标题统一用同一个文字色
            gravity = Gravity.CENTER
            setLineSpacing((3 * dp).toFloat(), 1f)
            setPadding(0, (10 * dp).toInt(), 0, (2 * dp).toInt())
        })
        val dlg = AlertDialog.Builder(this)
            .setView(box)
            .setNegativeButton("否") { _, _ ->
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("log_privacy_mode", false).apply()
                appToast(this, "已关闭：导出的日志是完整原文", false)
                Log.i("LogPrivacy", "隐私模式=关")
                refreshSettingsIfOpen()
            }
            .setPositiveButton("是") { _, _ ->
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("log_privacy_mode", true).apply()
                appToast(this, "已开启：导出时自动打码", true)
                Log.i("LogPrivacy", "隐私模式=开")
                refreshSettingsIfOpen()
            }
            .create()
        // 圆角 + 深浅色底板 + 文字反色 + 弹入动画（全 App 统一那套）
        // ★ vc512：按钮颜色要在 onShow 之后再压（dialogIn 会重设一次）——
        //   否则会出现"标题/正文白了、按钮还是紫的"三色混搭。
        // dialogIn 会在 show 之后重设一次按钮配色 ⇒ 这里延后 220ms 再压一次，保证最终一致
        fun paintButtons() {
            try {
                dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(cTextPrimary)
                dlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(cTextPrimary)
            } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        }
        dlg.setOnShowListener {
            dlg.window?.decorView?.post { paintButtons() }
            mainHandler.postDelayed({ paintButtons() }, 220)
        }
        try { dialogIn(dlg) } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        dlg.show()
        Log.i("LogPrivacy", "隐私模式弹窗（当前=$on）")
    }

    /** ★ vc500：按时间范围导出 zip（1 小时 / 24 小时 / 3 天 / 7 天） */
    private fun exportLogsRange() {
        val labels = LogStore.RANGES.map { it.second }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("导出日志")
            .setItems(labels) { _, idx ->
                val range = LogStore.RANGES[idx].first
                appToast(this, "正在打包日志…")
                exportIo.execute {
                    val snap = try {
                        diagSnapshot()
                    } catch (t: Throwable) {
                        "状态快照生成失败：${t.message}"
                    }
                    val ver = try {
                        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
                    } catch (_: Throwable) {
                        "?"
                    }
                    val dev = "版本：v$ver\n设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}" +
                            "（Android ${android.os.Build.VERSION.RELEASE}）\n日志存量：${LogStore.stat(this@MainActivity)}"
                    val zip = try {
                        LogStore.exportZip(this@MainActivity, range, snap, dev, sanitize = logPrivacyOn())
                    } catch (t: Throwable) {
                        Log.w("ExportLogs", "打包失败: ${t.message}")
                        null
                    }
                    runOnUiThread {
                        if (zip == null) {
                            appToast(this, "打包失败，请稍后再试")
                            return@runOnUiThread
                        }
                        pendingLogExport = zip
                        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.CHINA)
                            .format(java.util.Date())
                        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "application/zip"
                            putExtra(Intent.EXTRA_TITLE, "蜉蝣日志_$ts.zip")
                        }
                        try {
                            startActivityForResult(intent, REQ_EXPORT_LOGS)
                        } catch (t: Throwable) {
                            appToast(this, "保存失败：系统没有文件选择器")
                            zip.delete()
                            pendingLogExport = null
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .showAnimated()
    }

    private fun exportLogs() {
        appToast(this, "正在收集日志…")
        waybillIo.execute logs@{
            // ① 收集（vc446：多策略回退）——部分机型（nubia/红魔等）对未授权应用
            //   的 `logcat --pid=` 直接返回空，先按 pid 过滤、失败再全量读+本地筛。
            val (logBytes, logErr) = collectLogcat()

            // ② 头部信息（哪台机 / 哪一版 / 什么时候导的）—— 回看日志时先知道背景
            val versionName = try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
            } catch (_: Throwable) { "?" }
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.CHINA)
                .format(java.util.Date())
            val header = buildString {
                append("蜉蝣·取件通 日志导出\n")
                append("版本：v$versionName\n")
                append("设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                append("（Android ${android.os.Build.VERSION.RELEASE}）\n")
                append("导出时间：$ts\n")
                append("范围：本应用缓冲区（main + crash，按进程过滤）\n")
                append("========================================\n\n")
                // ★ vc484：日志缓冲区经常只有"刚打开那几秒"（导出前才启动），
                //   只靠 logcat 会看不到抓取过程。这里附一份**持久化状态快照**，
                //   哪怕日志很短，也能看出"哪个源登录失效、缓存里有什么、哪个源报错"。
                try {
                    append(diagSnapshot())
                } catch (t: Throwable) {
                    append("（状态快照生成失败：").append(t.message).append("）\n")
                }
            }

            // ③ 落临时文件（cacheDir，不占同志的空间，导完即删）
            val tmp = java.io.File(cacheDir, "log_export.tmp")
            try {
                tmp.outputStream().use { os ->
                    os.write(header.toByteArray(Charsets.UTF_8))
                    if (logBytes != null && logBytes.isNotEmpty()) {
                        os.write(logBytes)
                    } else {
                        os.write(
                            ("\n（未能读到日志内容：" +
                                logErr.ifBlank { "缓冲区为空或 logcat 不可用" } + "）\n")
                                .toByteArray(Charsets.UTF_8)
                        )
                    }
                }
            } catch (t: Throwable) {
                Log.w("ExportLogs", "临时文件写入失败：${t.message}")
                runOnUiThread { appToast(this, "日志收集失败：${t.message ?: "写入出错"}") }
                return@logs
            }

            // ④ 弹系统「另存为」：同志自己挑目录和文件名（默认名带时间戳，防覆盖）
            runOnUiThread {
                pendingLogExport = tmp
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, "蜉蝣日志_$ts.txt")
                }
                try {
                    startActivityForResult(intent, REQ_EXPORT_LOGS)
                } catch (t: Throwable) {
                    // 极少数精简系统没有文件选择器
                    Log.w("ExportLogs", "另存为弹不起来：${t.message}")
                    appToast(this, "保存失败：系统没有文件选择器")
                    tmp.delete()
                    pendingLogExport = null
                }
            }
        }
    }

    /**
     * ★ vc503：**全局点击埋点** —— 任何点击都记一行（页面 + 坐标 + 命中控件文字）。
     * 只处理 ACTION_UP（一次点击一行），命中测试浅层遍历、命中即停，成本可忽略。
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        if (ev != null && ev.actionMasked == android.view.MotionEvent.ACTION_UP) {
            try {
                val hit = UiTrace.hitTest(window?.decorView, ev.rawX, ev.rawY)
                UiTrace.tap(this, ev.rawX, ev.rawY, hit)
            } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
            // ★★ vc554：**设置按钮点击兜底**。
            //   ColorOS 的 OplusViewDragTouchViewHelper 有时会把"落在设置按钮上的点击"判成滑动结束，
            //   按钮的 onClick 根本不触发（真机日志实证：332 次里 2 次无 "SetPerf click"）。
            //   这里在触摸层兜底：手指抬起落在按钮矩形内、320ms 后设置页还没开 ⇒ 直接打开。
            try {
                val inBtn = settingsBtnRect.contains(ev.rawX.toInt(), ev.rawY.toInt())
                val noLayer = settingsLayer == null && webLayer == null &&
                        pickupLayer == null && transitLayer == null
                if (inBtn && noLayer) {
                    mainHandler.postDelayed({
                        if (settingsLayer == null && webLayer == null &&
                            pickupLayer == null && transitLayer == null
                        ) {
                            Log.i("SetPerf", "fallback open（点击未落到按钮）")
                            openSettings()
                        }
                    }, 320)
                }
            } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onBackPressed() {
        // ★ API <33 才走这里；33+ 走 OnBackInvokedCallback（见 registerPredictiveBack）
        if (!handleBack()) super.onBackPressed()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_EXPORT_LOGS) {
            // vc268 导出日志：同志在「另存为」里选好了位置（或取消了）
            val tmp = pendingLogExport
            pendingLogExport = null
            if (tmp == null) return
            if (resultCode == RESULT_OK && data?.data != null) {
                try {
                    contentResolver.openOutputStream(data.data!!)?.use { os ->
                        tmp.inputStream().use { it.copyTo(os) }
                    }
                    appToast(this, "日志已保存到所选位置")
                } catch (t: Throwable) {
                    Log.w("ExportLogs", "写入所选位置失败：${t.message}")
                    appToast(this, "保存失败：${t.message ?: "写入出错"}")
                }
            } // 取消 = 什么都不做，临时文件直接删
            tmp.delete()
        } else if (requestCode == REQ_WIDGET_IMG) {
            // vc294 组件自定义图片：相册选完 → 进截取编辑器
            val key = pendingCropKey
            pendingCropKey = null
            val uri = if (resultCode == RESULT_OK) data?.data else null
            if (key != null && uri != null) {
                try {
                    // 先量尺寸，再按目标 1280 内取采样，避免超大图直接吃爆内存
                    val cr = contentResolver
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    cr.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1280) sample *= 2
                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                    val bmp = cr.openInputStream(uri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it, null, opts)
                    }
                    if (bmp == null) {
                        appToast(this, "图片读取失败")
                    } else {
                        pendingCropBitmap = bmp
                        openWidgetCrop(key, bmp)
                    }
                } catch (t: Throwable) {
                    Log.w("WidgetImg", "读图失败：${t.message}")
                    appToast(this, "图片读取失败：${t.message ?: ""}")
                }
            }
        } else if (requestCode == REQ_FILE_CHOOSER) {
            val callback = filePathCallback
            filePathCallback = null
            callback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            )
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onPause() {
        super.onPause()
        // cookie 落盘，保证下次打开登录态还在
        CookieManager.getInstance().flush()
        webView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        // ★ vc264h：开关开着时，每次回前台都重新确保"最近任务卡片隐藏"
        //   —— 任务可能被系统重建（新任务不带上次的设置），兜底重设一次，开销可忽略
        if (getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean("hide_recents", false)
        ) {
            applyHideRecents(true)
        }
    }

    /**
     * 把本应用的任务从「最近任务列表」里摘掉 / 恢复（vc264h，同志 10-15 要求）。
     *
     * ★ 用 `ActivityManager.AppTask.setExcludeFromRecents()` —— 官方**运行时**接口
     *   （API 21+，不需要任何权限），随时开、随时关：
     *   · hide=true  → 卡片从最近任务里消失（想切回 App 从桌面图标进）
     *   · hide=false → 卡片恢复正常显示
     * ★ 与 Manifest 的 `android:excludeFromRecents` 不同：后者是**永久性**的、装完就定死，
     *   无法做成开关 —— 所以这里必须用运行时 API。
     */
    private fun applyHideRecents(hide: Boolean) {
        try {
            val am = getSystemService(android.app.ActivityManager::class.java) ?: return
            val tasks = am.appTasks ?: return
            for (t in tasks) {
                try {
                    t.setExcludeFromRecents(hide)
                } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
            }
            Log.i("HideRecents", "最近任务卡片 隐藏=$hide（本应用任务数 ${tasks.size}）")
        } catch (t: Throwable) {
            Log.w("HideRecents", "设置失败: ${t.message}")
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        pddIo.shutdownNow()
        pddAccPool.shutdownNow()
        pddHttpPool.shutdownNow()
        exportIo.shutdownNow()
        webView?.destroy()
        webView = null
        // 数据 WebView 挂在 root 上，先摘下来再销毁
        dataWebView?.apply {
            (parent as? ViewGroup)?.removeView(this)
            loadUrl("about:blank")
            destroy()
        }
        dataWebView = null
        // ★ vc518：池里的 WebView 也要销毁
        while (webPool.isNotEmpty()) {
            try { webPool.removeLast().destroy() } catch (t: Throwable) { SafeRun.w("MainActivity", t) }
        }
        super.onDestroy()
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    )
}