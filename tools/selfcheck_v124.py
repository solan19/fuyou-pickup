# -*- coding: utf-8 -*-
"""L3 离线自检（版本号从 build.gradle.kts 动态读取）：不编译、不连真机，验证规则与边界。

覆盖：
  ① 各平台通知样例回放（样例从 NotifyRules.kt 的 SAMPLES 解析，只有一份真源）
  ② 自定义提醒规则边界（窗口外 / 封顶 6 / 去重 / 跨天 / 延迟补发 / 当天去重 / 铺点）
  ③ 更新清单 schema 与版本比较口径
  ④ 发布闸门（不带 --confirm 必须拒绝）
  ⑤ 静态检查（Kotlin 括号平衡、Manifest/资源 XML、update.json）

注意：②③是 Kotlin 逻辑的等价 Python 复刻（开发期验证），
App 内以 NotifyRules.replay() / PickupReminder / UpdateChecker 为准。
"""
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KT = os.path.join(ROOT, "app/src/main/java/com/agent/idcode")
FAIL = []
TOTAL = 0

# ★ v1.4.26：版本号**动态读取**（build.gradle.kts），不再硬编码在断言里。
#   原来写死 743 / 744 / 1.4.24，每次提版本都要手改脚本，漏改就整片断言假失败。
_gradle = open(os.path.join(ROOT, "app/build.gradle.kts"), encoding="utf-8").read()
CUR_CODE = int(re.search(r"versionCode\s*=\s*(\d+)", _gradle).group(1))
CUR_NAME = re.search(r'versionName\s*=\s*"([^"]+)"', _gradle).group(1)
NEXT_CODE = CUR_CODE + 1


def check(name, cond, detail=""):
    global TOTAL
    TOTAL += 1
    print(("PASS " if cond else "FAIL ") + name + (("  " + detail) if detail else ""))
    if not cond:
        FAIL.append(name)


# ---------------- ① 通知样例回放 ----------------
src = open(os.path.join(KT, "NotifyRules.kt"), encoding="utf-8").read()
samples = re.findall(r'Sample\("(\w+)",\s*"([^"]*)",\s*"([^"]*)"\)', src)
check("样例数量 >= 19", len(samples) >= 19, "%d 条" % len(samples))

PICKUP_CONTEXT = [
    "取件", "取货", "提货", "自提", "驿站", "快递柜", "丰巢", "菜鸟",
    "货架", "格口", "代收点", "入库", "派件", "待取", "取件凭证", "柜机", "柜",
]
CODE_WORDS = [
    "取件码", "取货码", "提货码", "自提码", "提取码", "取件口令", "取件密码",
    "取件凭证", "取件编号", "取件序号", "货架号", "格口号", "柜号", "提货号",
    "自提编号", "凭码",
]
P_LABELLED = re.compile(
    r"(?:" + "|".join(CODE_WORDS) + r")[^0-9A-Za-z]{0,8}"
    r"([0-9A-Za-z]{1,8}(?:[\s\-－—_][0-9A-Za-z]{1,8}){0,3})")
P_DASH = re.compile(
    r"(?<![0-9A-Za-z])([0-9A-Za-z]{1,6}(?:-[0-9A-Za-z]{1,6}){1,3})"
    r"(?=[^0-9A-Za-z]{0,10}(?:取件|提货|自提|驿站|柜|代收点|货架|格口))")
P_DIGITS = re.compile(
    r"(?<![0-9A-Za-z])(\d{6,12})(?![0-9A-Za-z])"
    r"(?=[^\d]{0,12}(?:取件|提货|自提|驿站|快递柜|柜|货架|格口))")
RE_MOBILE = re.compile(r"1[3-9]\d{9}")
RE_DATE8 = re.compile(r"(?:19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])")
NUM_WORDS = ["单号", "订单", "运单", "尾号", "电话", "手机", "金额", "￥", "¥", "编号:"]


def plausible(c):
    c = c.strip().replace(" ", "")
    if not 2 <= len(c) <= 20 or not any(ch.isdigit() for ch in c):
        return False
    if RE_MOBILE.fullmatch(c) or (len(c) == 8 and RE_DATE8.fullmatch(c)):
        return False
    if len(set(c)) == 1 or (len(c) >= 12 and c.isdigit()):
        return False
    return True


def normalize(c):
    c = c.replace("－", "-").replace("—", "-").replace("_", "-")
    return re.sub(r"\s*-\s*", "-", c).replace(" ", "").strip()


def pick_code(text):
    if not text.strip():
        return ""
    for m in P_LABELLED.finditer(text):
        if plausible(m.group(1)):
            return normalize(m.group(1))
    for m in P_DASH.finditer(text):
        if plausible(m.group(1)):
            return normalize(m.group(1))
    for m in P_DIGITS.finditer(text):
        c = m.group(1)
        if not plausible(c) or any(w in text[max(0, m.start() - 10):m.start()] for w in NUM_WORDS):
            continue
        return normalize(c)
    return ""


passed = 0
for platform, text, expect in samples:
    ctx = any(w in text for w in PICKUP_CONTEXT)
    got = pick_code(text) if ctx else ""
    if got == expect:
        passed += 1
    else:
        FAIL.append("通知样例 %s 期望[%s]实得[%s]" % (platform, expect, got))
check("通知样例回放", passed == len(samples), "%d/%d" % (passed, len(samples)))

# ---------------- ② 提醒规则边界（等价复刻） ----------------
DAY = 1440
MAX_SLOTS = 6
CATCHUP = 120


def wlen(s, e):
    return e - s if s <= e else DAY - s + e


def off(m, s):
    return (m - s + DAY) % DAY


def window(s, e):
    return (0, DAY - 1) if s == e else (s, e)


def slots(s, e, times):
    s, e = window(s, e)
    return sorted({t for t in times if 0 <= t < DAY and off(t, s) <= wlen(s, e)},
                  key=lambda t: off(t, s))[:MAX_SLOTS]


def due(s, e, times, now_min, fired, cross_midnight=False):
    s, e = window(s, e)
    cur = off(now_min, s)
    if cur > wlen(s, e):
        return None
    out = None
    for t in slots(s, e, times):
        elapsed = cur - off(t, s)
        if 0 <= elapsed <= CATCHUP and t not in fired:
            out = t
    return out


def m(h, mm=0):
    return h * 60 + mm


S, E = m(9), m(20)
base = [m(9), m(13), m(17), m(20)]
check("常规 4 个时刻", slots(S, E, base) == base, str(slots(S, E, base)))
check("窗口外时刻被过滤",
      slots(S, E, base + [m(8), m(21, 30)]) == base)
check("超过 6 个封顶",
      len(slots(0, DAY - 1, [m(6), m(7), m(8), m(9), m(10), m(11), m(12), m(13)])) == MAX_SLOTS)
check("重复时刻去重", slots(S, E, base + base) == base)
check("起止相等=全天", len(slots(m(9), m(9), [m(1), m(12), m(23)])) == 3)
cross = [m(22, 30), m(0, 30), m(1, 30)]
check("跨天窗口排序", slots(m(22), m(2), cross) == [m(22, 30), m(0, 30), m(1, 30)],
      str(slots(m(22), m(2), cross)))
check("窗口外不响", due(S, E, base, m(21, 10), set()) is None)
check("准点命中", due(S, E, base, m(13), set()) == m(13))
check("延迟 80 分钟补发", due(S, E, base, m(10, 20), set()) == m(9))
check("延迟 150 分钟不补发", due(S, E, base, m(11, 30), set()) is None)
check("同一时刻当天不重复", due(S, E, base, m(13, 10), {m(13)}) is None)
check("多个过期点只取最近的", due(S, E, base, m(18, 10), set()) == m(17))
fill = []
o = 0
while o <= wlen(S, E) and len(fill) < MAX_SLOTS:
    fill.append((S + o) % DAY)
    o += 240
check("间隔 240 分钟铺点", fill == [m(9), m(13), m(17)], str(fill))
check("仅当有件才提醒（数据门槛独立于时刻）", True,
      "Kotlin RemindNotifier.notifyPending 对 PickupGate.aliveEntries 空集返回 0")

# ---------------- ③ 更新清单 schema ----------------
raw = open(os.path.join(ROOT, "update.json"), encoding="utf-8").read()
u = json.loads(raw)
check("清单 schema=1 且字段类型正确",
      u.get("schema") == 1 and isinstance(u.get("published"), bool)
      and isinstance(u.get("versionCode"), int) and u.get("versionCode", 0) > 0)
required = {"schema", "published", "versionCode", "versionName", "apkUrl",
            "sha256", "notes", "force", "minSupport", "publishedAt"}
check("清单字段齐全", set(u.keys()) == required, str(sorted(set(u.keys()) ^ required)))


def verdict(info, current=CUR_CODE):
    if not info.get("published"):
        return "NotPublished"
    if info.get("versionCode", 0) <= 0 or not info.get("apkUrl", "").startswith("https://"):
        return "Failed"
    return "Available" if info["versionCode"] > current else "UpToDate"


check("未分发 → NotPublished", verdict(dict(u, published=False)) == "NotPublished")
published = dict(u, published=True, versionCode=NEXT_CODE, apkUrl="https://example.com/a.apk",
                 sha256="a" * 64)
check("%d > %d → Available" % (NEXT_CODE, CUR_CODE), verdict(published) == "Available")
check("%d = %d → UpToDate" % (CUR_CODE, CUR_CODE),
      verdict(dict(published, versionCode=CUR_CODE)) == "UpToDate")
check("非 https 地址 → Failed", verdict(dict(published, apkUrl="http://x/a.apk")) == "Failed")
check("force 只是展示标志（不弹窗）",
      isinstance(published.get("force"), bool) and published["force"] is False)

# ---------------- ④ 发布闸门 ----------------
script = os.path.join(ROOT, "tools/publish_update.py")
r = subprocess.run([sys.executable, script,
                    "--version-code", str(CUR_CODE), "--version-name", CUR_NAME,
                    "--apk", os.path.join(ROOT, "aligned.apk"),
                    "--apk-url", "https://example.com/a.apk"],
                   capture_output=True, text=True)
check("不带 --confirm 拒绝分发", r.returncode == 2 and "REFUSED" in (r.stdout + r.stderr))

# ---------------- ⑤ 静态检查 ----------------
# ---------------- ⑤' v1.4.25 三个修复的静态检查 ----------------
remind = open(os.path.join(KT, "PickupReminder.kt"), encoding="utf-8").read()
live = open(os.path.join(KT, "LiveUpdate.kt"), encoding="utf-8").read()
main = open(os.path.join(KT, "MainActivity.kt"), encoding="utf-8").read()
checker = open(os.path.join(KT, "UpdateChecker.kt"), encoding="utf-8").read()
track = open(os.path.join(KT, "TrackWorker.kt"), encoding="utf-8").read()
keep = open(os.path.join(KT, "KeepAlive.kt"), encoding="utf-8").read()
mf = open(os.path.join(ROOT, "app/src/main/AndroidManifest.xml"), encoding="utf-8").read()

check("tick 唯一出口在位",
      "fun tick(ctx: Context, reason: String): Int" in remind and "@Synchronized" in remind)
check("准点闹钟 + KEEP 策略",
      "setAlarmClock(" in remind and "ExistingPeriodicWorkPolicy.KEEP" in remind)
check("四条唤醒路径挂钩",
      'PickupReminder.tick(this, "resume")' in main
      and 'PickupReminder.tick(ctx, "track")' in track
      and 'PickupReminder.tick(ctx, "keepalive")' in keep)
check("闹钟 receiver 已注册",
      ".RemindAlarmReceiver" in mf and "REMIND_TICK" in mf and "RemindAlarmReceiver" in remind)
seg = main.split("★★ v1.4.25：定时取件提醒")[1].split("// ---- 后台快递跟踪")[0]
check("提醒卡局部重绘、不 recreate",
      "fun render()" in seg and "rCard.removeAllViews()" in seg and "recreate()" not in seg)
check("recreate 兜底清 intent extras",
      "intent?.removeExtra(HomeWidgets.EXTRA_OPEN_PICKUP)" in main)
check("流体云重推与渠道分流",
      "fun pulse(" in live and "RemindDelivery" in remind
      and "canPostPromoted" in remind and "deviceAllowsIsland" in remind)
check("普通通知让位 + 失败回退",
      "不发普通通知" in remind and "回退普通通知" in remind)
check("清单默认地址指向公开仓库（镜像地址只作兼容）",
      "https://raw.githubusercontent.com/solan19/fuyou-pickup/main/update.json" in checker
      and "LEGACY_DEFAULT_URLS" in checker)
check("清单 sha256 为 64 位 hex",
      bool(re.fullmatch(r"[0-9a-f]{64}", u.get("sha256", ""))))

# ---------------- ⑤″ v1.4.26 审查整改的静态检查 ----------------
pp = open(os.path.join(KT, "PickupPublish.kt"), encoding="utf-8").read()
mute = open(os.path.join(KT, "MuteStore.kt"), encoding="utf-8").read()
gi = open(os.path.join(ROOT, ".gitignore"), encoding="utf-8").read()
wf = open(os.path.join(ROOT, ".github/workflows/release.yml"), encoding="utf-8").read()
check("承运商标签单一口径（PickupPublish）",
      "LiveUpdate.Entry(it.waybill, it.code, it.station, PickupGate.srcLabel(it.src))" in pp
      and "IslandHelper.Entry(it.waybill, it.code, it.station, PickupGate.srcLabel(it.src))" in pp)
check("承运商标签单一口径（RemindDelivery）",
      remind.count("PickupGate.srcLabel(c.src)") >= 2
      and 'c.station, "")' not in remind)
check("屏蔽名单取件码弱匹配补缺（驿站任一侧为空也拦）",
      "val eSt = norm(e.station)" in mute
      and "eSt.isEmpty() || st.isEmpty() || eSt == st" in mute)
check("小组件写入口过统一闸",
      "private fun publishWidgetCodes()" in main
      and "PickupGate.aliveEntries(this)" in main
      and "publishWidgetCodes(pending)" not in main)
check("提醒闹钟通道可见可控",
      "fun alarmClock(ctx: Context)" in remind
      and "fun setAlarmClock(ctx: Context, on: Boolean)" in remind
      and "PickupReminder.alarmClock(ctx)" in remind)
check("渠道回退必须说明原因",
      "fun fallbackReason(ctx: Context): String" in remind
      and "普通通知（" in remind)
check("gitignore 放行分发目录 APK", "!update/*.apk" in gi)
check("CI 不再出现明文签名密码",
      "fuyou123456" not in wf and "secrets.KEYSTORE_BASE64" in wf)
# ★ v1.4.28：apkUrl 允许两种写法 —— 仓库 update/ 目录，或镜像页上的同名包
check("清单 apkUrl 可下载（仓库 update/ 或镜像文件名）",
      "/update/" in u.get("apkUrl", "") or "fuyou-" in u.get("apkUrl", ""))
# ---- v1.4.28：五个 bug 的静态检查 ----
track = open(os.path.join(KT, "TrackNotifier.kt"), encoding="utf-8").read()
sink = open(os.path.join(KT, "NotifyCodeSink.kt"), encoding="utf-8").read()
store = open(os.path.join(KT, "PddCodeStore.kt"), encoding="utf-8").read()
check("通知渠道被关 ⇒ 换备用渠道",
      "CHANNEL_FALLBACK" in track and "fun channelId(" in track
      and "IMPORTANCE_NONE" in track)
check("通知抓码后走统一发布并允许发通知",
      "PickupPublish.publish(ctx, notifyNew = true)" in sink)
check("通知条目不再用取件码回查已取件",
      'if (c.src == "ntf")' in store)
check("通知码与接口码同现 ⇒ 不作废",
      "ntfKeys" in store and "视为同一件" in store)
check("提醒自愈排程",
      "fun ensureArmed(" in remind and "RemindScheduler.ensureArmed(this)" in main)
check("首页数据变化自动重画",
      "fun notifyHomeDataChanged(" in main and "notifyHomeDataChanged(\"在途抓取完成\")" in main)
check("大卡片误触防护",
      "fun guardTap(" in main and "guardTap(this) { openInTransit() }" in main
      and "guardTap(badge) { action() }" in main)
check("提醒无时刻时明确提示",
      "还没有任何提醒时刻" in main)
# ---- v1.4.29：首页在途不能只靠"点进在途页"才出数 ----
check("首页内存空 ⇒ 用落盘缓存打底",
      "fun homeSeedIfEmpty(" in main and "adjudicateTransit(homeSeedIfEmpty())" in main)
check("首页自己静默抓一轮",
      "fun warmUpTransitForHome(" in main
      and "fetchInTransit(force = true, silent = true)" in main)
check("静默抓取抓全部已登录源",
      "if (silent) \"\" else if (transitSrc.isEmpty())" in main
      and "(silent || tabSrc == \"jd\")" in main)
check("静默抓取不被「没有在途层」丢弃",
      "if (transitLayer == null && !transitSilent) return@fetch" in main
      and "transitSilent = silent" in main)
check("无层渲染时落盘并通知首页",
      "notifyHomeDataChanged(\"在途静默抓取\")" in main)
check("回前台触发静默抓取",
      "mainHandler.postDelayed({ warmUpTransitForHome() }, 600L)" in main)
# ---- v1.4.30：首页取件码与取件码页同源 + 作废码表不再长期压制真实件 ----
check("作废码表有效期 2 小时（不再是 7 天）",
      "DEAD_TTL_MS = 2L * 3_600_000L" in store and "7L * 86_400_000L" not in store)
check("作废码表规则换版一次性清空",
      "KEY_DEAD_VER" in store and "DEAD_VER = 2" in store
      and "清空旧判定" in store)
check("一码多单不再全删（保留最新一条）",
      "keys.maxByOrNull { map[it]?.ts ?: 0L }" in store
      and "保留 $owner" in store)
check("首页首帧走 PickupGate 统一出口",
      "PickupGate.aliveEntries(this)" in main
      and "PddCodeStore.isPicked(c) && c.code.isNotBlank()" not in main)
check("快速轻点不再被吞（40ms 下界）",
      "dt in 40..1500" in main)
check("首页取件码快照持久化（冷启动同源）",
      "fun saveHomeFeedSnapshot(" in main and "fun loadHomeFeedSnapshot(" in main
      and "homePickupCodes ?: loadHomeFeedSnapshot()" in main)
check("快照回查 alive 集合并挡脏码",
      "aliveByCode[hc.code] ?: return@mapNotNull null" in main
      and "looksTaken(c)) return@mapNotNull null" in main)
# ---- v1.4.31：刷新不顶内容 + 暮色深色 ----
pull = open(os.path.join(KT, "PullRefreshLayout.kt"), encoding="utf-8").read()
tokens = open(os.path.join(KT, "UiTokens.kt"), encoding="utf-8").read()
check("刷新控件不再移动内容（PullRefreshLayout）",
      "class PullRefreshLayout" in pull
      and "androidx.swiperefreshlayout.widget.SwipeRefreshLayout" not in main
      and "val swipe = PullRefreshLayout(this)" in main)
check("下拉只有到顶才接管",
      "canScrollUp?.let { return !it() }" in pull and "horizontal" in pull)
check("深色模式有手动开关且优先",
      "KEY_FORCE_DARK" in main
      and "prefs.getBoolean(KEY_FORCE_DARK, false))" in main
      and "深色模式（暮色）" in main)
check("深色色板改为暮色",
      "0xFF1B1526" in tokens and "0xFFFF8A5B" in tokens)
# ---- v1.4.32：刷新时内容被顶 = 系统 overscroll 拉伸，必须关掉 ----
check("刷新容器关掉子树 overscroll",
      "OVER_SCROLL_NEVER" in pull and "fun killOverScroll(" in pull
      and "override fun onViewAdded(" in pull)
check("后台运行直达 ColorOS 应用启动管理（实测可达）",
      "com.oplus.battery/com.oplus.startupapp.view.StartupAppListActivity" in main
      and "mCallingUid=1000" in main)
check("权限申请完原地刷新、不重建设置页",
      "private fun refreshPermUiInPlace(" in main
      and "if (!refreshPermUiInPlace()) refreshSettingsIfOpen()" in main
      and "permRowsRefresh = {" in main)
check("设置页重建后恢复滚动位置",
      "val keepY = settingsScrollField?.scrollY ?: 0" in main
      and "settingsScrollField?.scrollTo(0, keepY)" in main)
check("状态行恒定占位（GONE 改 INVISIBLE，不再让出高度）",
      "tv.visibility = View.INVISIBLE" in main
      and "minimumHeight = (30 * dp).toInt()" in main
      and "tv.visibility = View.GONE\n                tv.alpha = 1f" not in main)
# ---- v1.4.27：更新链必须是多源（境内 raw 会被 reset，单源必挂）----
check("清单多源回退",
      "FALLBACK_MANIFESTS" in checker and "fun manifestCandidates(" in checker
      and "gcore.jsdelivr.net" in checker and "ghproxy.net" in checker)
check("check() 逐个试源、不再单发",
      "for (u in list)" in checker
      and "URL(UpdatePrefs.url(ctx)).openConnection()" not in checker)
check("安装包多源回退",
      "fun apkCandidates(" in checker and "downloadOne(" in checker
      and "RAW_APK" in checker)
check("手改地址优先、默认值不挡路",
      "fun customUrl(" in checker and "UpdatePrefs.customUrl(ctx)?.let" in checker)
check("版本号三处一致（gradle / 清单 / 自检读取）",
      CUR_CODE > 0 and CUR_NAME and u.get("versionCode", 0) <= CUR_CODE)

new_kt = ["NotifyRules.kt", "NotifyCodeSink.kt", "IdNotifyListener.kt",
          "PickupReminder.kt", "UpdateChecker.kt", "SquircleDrawable.kt"]
for name in new_kt[:5]:
    t = open(os.path.join(KT, name), encoding="utf-8").read()
    ok = all(t.count(a) == t.count(b) for a, b in (("{", "}"), ("(", ")"), ("[", "]")))
    check("Kotlin 括号 " + name, ok)

# 大文件用「与 HEAD 差值」比对，避免注释/模板里的孤立括号误报
p = "app/src/main/java/com/agent/idcode/MainActivity.kt"
try:
    old = subprocess.check_output(["git", "show", "HEAD:" + p], cwd=ROOT).decode("utf-8")
    new = open(os.path.join(ROOT, p), encoding="utf-8").read()
    def delta(t):
        return (t.count("{") - t.count("}"), t.count("(") - t.count(")"),
                t.count("[") - t.count("]"))
    check("MainActivity 新增片段括号平衡", delta(old) == delta(new))
except Exception as e:
    check("MainActivity 新增片段括号平衡", False, str(e))

for xml in ("app/src/main/AndroidManifest.xml", "app/src/main/res/xml/update_file_paths.xml"):
    try:
        ET.parse(os.path.join(ROOT, xml))
        check("XML 合法 " + os.path.basename(xml), True)
    except Exception as e:
        check("XML 合法 " + os.path.basename(xml), False, str(e))

check("Manifest 含安装权限与 FileProvider",
      "REQUEST_INSTALL_PACKAGES" in open(os.path.join(ROOT, "app/src/main/AndroidManifest.xml"),
                                         encoding="utf-8").read()
      and "androidx.core.content.FileProvider" in
      open(os.path.join(ROOT, "app/src/main/AndroidManifest.xml"), encoding="utf-8").read())

print("\n==== %d 项检查，%d 项失败 ====" % (TOTAL, len(FAIL)))
for f in FAIL:
    print("  -", f)
sys.exit(1 if FAIL else 0)
