package com.agent.idcode

/**
 * ★ vc487：本应用的日志标签白名单。
 *
 * 导出日志原来用 `logcat --pid=<当前进程>` 过滤 —— App 被系统杀掉重启后，
 * **上一个进程（也就是用户刚刷新那段）的日志全被丢掉**，导出来只剩"刚打开设置"几行。
 * 现在改成：读**整个 logcat 缓冲区**（跨进程），再按这里的标签过滤，
 * 既去掉系统噪音，又不会因为换进程而丢失现场。
 */
object LogTags {
    val ALL: Array<String> = arrayOf(
        "AcctScope",
        "AutoCode",
        "CacheDump",
        "CodeResolver",
        "Dedup",
        "DedupTest",
        "EmsHead",
        "EmsLen",
        "EnumProbe",
        "ExportLogs",
        "FakeGroups",
        "FakeTrack",
        "HideRecents",
        "HomeWidgets",
        "IdApi",
        "IdDiag",
        "IdJs",
        "IdJump",
        "IdNet",
        "Island",
        "JdAddr",
        "JdParse",
        "KeepAlive",
        "LiveTest",
        "ManualWaybill",
        "MdkdProbe",
        "MiLogin",
        "MiShopChannel",
        "PddAccount",
        "PddAddr",
        "PddCache",
        "PddChannel",
        "PddCodeJob",
        "PddDebug",
        "PddDetail",
        "PddDump",
        "PddEnrich",
        "PddEnum",
        "PddExpressProbe",
        "PddMdkd",
        "PddNative",
        "PddNotice",
        "PddPost",
        "PddSearch",
        "PddShareClean",
        "PddStation",
        "PddTbDetail",
        "PddTraceProbe",
        "PddTrackbox",
        "PddUrlProbe",
        "PickupFill",
        "PickupGate",
        "PickupTimeout",
        "Predictive",
        "SetPerf",
        "Settings",
        "SfAutoR",
        "SfDebug",
        "SfKeep",
        "SfLogin",
        "SfRelogin",
        "SfScan",
        "SfSpeed",
        "Shizuku",
        "Stellar",
        "StellarInput",
        "TBEnum",
        "TaobaoFetch",
        "TbChannel",
        "TbLogin",
        "TbProbe",
        "TbSilent",
        "TbTransit",
        "TestSeed",
        "TrackDbg",
        "TrackDump",
        "TrackSelfTest",
        "TrackSnapshot",
        "TransitCache",
        "TransitEnhance",
        "TransitGate",
        "TransitTimeout",
        "TransitTiming",
        "UrlDump",
        "WebDiag",
        "WebJs",
        "WebUrl",
        "WhaleWidgets",
        "WidgetImg",
        "UpdateCheck",
        "PostFetch",
        "CainiaoId",
        "CainiaoAuth",
        "TbHarvest",
        "CaiNiaoChannel",
        "IdNotifyListener",
        "IdQuickTile",
        "IslandBypass",
        "JdChannel",
        "Kd100Channel",
        "KeepAliveGuard",
        "KeepAliveRecovery",
        "KeepAliveService",
        "LiveUpdate",
        "LogEvents",
        "LogPrivacy",
        "LogStore",
        "MiChannel",
        "PddExpress",
        "PddNoAuth",
        "PddTraceChannel",
        "ShizukuBridge",
        "StellarBridge",
        "TbPickupProbe",
        "TbRec",
        "TbSchoolChannel",
        "TrackFetcher",
        "TrackScheduler",
        "TrackWorker",
        "UiCacheSync",
        "UiData",
        "UiPage",
        "UiTap",
        "WatchUserService",
        "WebHook"
    )

    /** brief 格式：`I/Tag: msg` */
    private val RE_BRIEF: Regex =
        Regex("(?:^|\\s)[VDIWEF]/(" + ALL.joinToString("|") { Regex.escape(it) } + ")\\s*[:：]")

    /**
     * ★ vc518：**threadtime 格式**：`10-29 09:08:23.340 21501 21636 I WebUrl  : msg`
     * 之前只认 `I/Tag:`，而 LogStore 抓的是 threadtime ⇒ **所有自家日志都匹配不上**，
     * 用户日志才会"只有系统行"（1.0.9 那两份就是这么空的）。
     */
    private val RE_THREADTIME: Regex =
        Regex("(?:^|\\s)[VDIWEF]\\s+(" + ALL.joinToString("|") { Regex.escape(it) } + ")\\s*[:：]")

    fun matches(line: String): Boolean =
        RE_BRIEF.containsMatchIn(line) || RE_THREADTIME.containsMatchIn(line) ||
                line.contains("com.agent.idcode")
}
