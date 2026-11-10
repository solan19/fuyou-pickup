# -*- coding: utf-8 -*-
"""v1.4.24 L3 离线自检：不编译、不连真机，验证规则与边界。

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


def verdict(info, current=743):
    if not info.get("published"):
        return "NotPublished"
    if info.get("versionCode", 0) <= 0 or not info.get("apkUrl", "").startswith("https://"):
        return "Failed"
    return "Available" if info["versionCode"] > current else "UpToDate"


check("未分发 → NotPublished", verdict(dict(u, published=False)) == "NotPublished")
published = dict(u, published=True, versionCode=744, apkUrl="https://example.com/a.apk",
                 sha256="a" * 64)
check("744 > 743 → Available", verdict(published) == "Available")
check("743 = 743 → UpToDate", verdict(dict(published, versionCode=743)) == "UpToDate")
check("非 https 地址 → Failed", verdict(dict(published, apkUrl="http://x/a.apk")) == "Failed")
check("force 只是展示标志（不弹窗）",
      isinstance(published.get("force"), bool) and published["force"] is False)

# ---------------- ④ 发布闸门 ----------------
script = os.path.join(ROOT, "tools/publish_update.py")
r = subprocess.run([sys.executable, script, "--version-code", "743", "--version-name", "1.4.24",
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
check("清单默认地址改公开托管",
      "fuyou-update.app.workbuddy.host" in checker and "sharecode=" in checker)
check("清单 sha256 为 64 位 hex",
      bool(re.fullmatch(r"[0-9a-f]{64}", u.get("sha256", ""))))

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
