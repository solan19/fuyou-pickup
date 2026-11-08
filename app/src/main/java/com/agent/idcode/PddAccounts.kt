package com.agent.idcode

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * ★ vc489：拼多多多账号账本。
 *
 * 设计要点（见 `D:\知识库\取件码\11_拼多多多账号与调度设计.md`）：
 * - 旧版本只有**一套** `pdd_cookies` / `pdd_uid`（PddChannel 直接读写）。
 *   这里把它们迁移成「主号」存进 `pdd_accounts`，旧键继续保留（PddChannel 仍以主号为准）。
 * - 每个账号独立存 cookie / uid / 是否主号 / 登录失效时间。
 * - **纯 HTTP 通道**（PddNative）可以直接按账号构造 [PddNative.Session] 并发跑；
 *   需要 WebView 的通道（取件页/详情补码）仍只给主号，避免 cookie 串号。
 */
object PddAccounts {

    private const val TAG = "PddAccount"
    private const val PREF = "idcode_prefs"
    private const val KEY_LIST = "pdd_accounts"
    /** 本次登录要写进哪个槽：空 = 主号，"=" 前缀 id = 指定账号，"new" = 新增副号 */
    private const val KEY_PENDING_SLOT = "pdd_login_slot"

    data class Account(
        val id: String,
        val label: String,
        val cookie: String,
        val uid: String,
        val primary: Boolean,
        val addedAt: Long = 0L,
        val deadAt: Long = 0L
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ---------------- 读写 ----------------

    fun all(ctx: Context): List<Account> {
        val raw = prefs(ctx).getString(KEY_LIST, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<Account>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    Account(
                        id = o.optString("id"),
                        label = o.optString("label"),
                        cookie = o.optString("cookie"),
                        uid = o.optString("uid"),
                        primary = o.optBoolean("primary", false),
                        addedAt = o.optLong("addedAt", 0L),
                        deadAt = o.optLong("deadAt", 0L)
                    )
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "解析账号库失败: ${t.message}")
        }
        return out
    }

    private fun save(ctx: Context, list: List<Account>) {
        val arr = JSONArray()
        for (a in list) {
            arr.put(
                JSONObject()
                    .put("id", a.id)
                    .put("label", a.label)
                    .put("cookie", a.cookie)
                    .put("uid", a.uid)
                    .put("primary", a.primary)
                    .put("addedAt", a.addedAt)
                    .put("deadAt", a.deadAt)
            )
        }
        prefs(ctx).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    // ---------------- 迁移 ----------------

    /** 旧版只有单账号：首次调用时把 PddChannel 的 cookie 迁成「主号」 */
    fun ensureMigrated(ctx: Context) {
        if (all(ctx).isNotEmpty()) return
        val ck = PddChannel.cookies(ctx)
        if (ck.isBlank()) return
        val uid = PddChannel.uid(ctx).ifEmpty { PddChannel.uidFromCookie(ck) }
        val a = Account(
            id = "a1", label = "主号", cookie = ck, uid = uid,
            primary = true, addedAt = System.currentTimeMillis()
        )
        save(ctx, listOf(a))
        Log.i(TAG, "已迁移旧登录态为主号 uid=${uid.take(8)}")
    }

    fun primary(ctx: Context): Account? {
        ensureMigrated(ctx)
        return all(ctx).firstOrNull { it.primary } ?: all(ctx).firstOrNull()
    }

    fun secondaries(ctx: Context): List<Account> =
        all(ctx).filter { !it.primary && it.cookie.isNotBlank() }

    // ---------------- 增删改 ----------------

    fun setPrimary(ctx: Context, id: String) {
        val list = all(ctx).map { it.copy(primary = it.id == id) }
        save(ctx, list)
        all(ctx).firstOrNull { it.id == id }?.let { a ->
            // 兼容旧链路：PddChannel 的全局键始终指向主号
            PddChannel.saveCookies(ctx, a.cookie)
            prefs(ctx).edit().putString(PddChannel.KEY_UID, a.uid).apply()
            PddChannel.markLoginOk(ctx)
        }
        Log.i(TAG, "设为主号 id=$id")
    }

    fun remove(ctx: Context, id: String) {
        val target = all(ctx).firstOrNull { it.id == id } ?: return
        val list = all(ctx).filterNot { it.id == id }
        // 删的是主号 ⇒ 把剩下的第一个提为主号（保持至少有一个主号）
        val fixed = if (target.primary && list.isNotEmpty()) {
            list.mapIndexed { i, a -> if (i == 0) a.copy(primary = true) else a.copy(primary = false) }
        } else list
        save(ctx, fixed)
        Log.i(TAG, "删除账号 id=$id，剩 ${fixed.size}")
        if (target.primary) {
            fixed.firstOrNull()?.let { a ->
                PddChannel.saveCookies(ctx, a.cookie)
                prefs(ctx).edit().putString(PddChannel.KEY_UID, a.uid).apply()
            }
        }
    }

    /**
     * ★ vc494：清掉"和主号同一个号"的副号。
     *
     * 事故：添加副号时网页里还带着主号 cookie，登录页直接显示"已登录"，
     * 抓到的仍是主号身份 ⇒ 副号 uid 与主号一模一样（日志实证 副号2/副号3 uid 都等于主号）。
     * 这里按 uid/cookie 判重，把这类脏数据清掉，避免"三个号看起来是一个号"。
     * @return 清掉的个数
     */
    fun pruneDuplicates(ctx: Context): Int {
        val list = all(ctx)
        val p = list.firstOrNull { it.primary } ?: return 0
        val kept = list.filter { a ->
            a.primary || (a.uid != p.uid && (p.cookie.isBlank() || a.cookie != p.cookie))
        }
        val n = list.size - kept.size
        if (n > 0) {
            save(ctx, kept)
            Log.w(TAG, "清掉 $n 个与主号重复的副号（主号 uid=${p.uid.take(8)}）")
        }
        return n
    }

    fun markDead(ctx: Context, id: String) {
        save(ctx, all(ctx).map { if (it.id == id) it.copy(deadAt = System.currentTimeMillis()) else it })
    }

    fun markAlive(ctx: Context, id: String) {
        save(ctx, all(ctx).map { if (it.id == id) it.copy(deadAt = 0L) else it })
    }

    // ---------------- 登录槽 ----------------

    private const val KEY_JAR_BACKUP = "pdd_jar_backup"

    /**
     * ★ vc506：清 jar 前备份主号 cookie。
     * 添加/重登副号会先清空拼多多域 cookie；若用户中途退出，jar 里就没主号了，
     * 拼多多身份码页会要求重新登录。备份后 closeWeb 时自动恢复。
     */
    fun backupPrimaryCookie(ctx: Context) {
        primary(ctx)?.cookie?.takeIf { it.isNotBlank() }?.let {
            prefs(ctx).edit().putString(KEY_JAR_BACKUP, it).apply()
            Log.i(TAG, "已备份主号 cookie 到 SP（供清 jar 后恢复）")
        }
    }

    fun restorePrimaryToJar(ctx: Context) {
        val ck = prefs(ctx).getString(KEY_JAR_BACKUP, "").orEmpty()
        if (ck.isBlank()) return
        try {
            PddChannel.injectCookies(ck)
            Log.i(TAG, "已把备份的主号 cookie 写回 jar")
        } catch (t: Throwable) { SafeRun.w("PddAccounts", t) }
        prefs(ctx).edit().remove(KEY_JAR_BACKUP).apply()
    }

    /** 当前登录槽（空=主号，"new"=新增副号，"=id"=指定账号） */
    fun pendingSlot(ctx: Context): String =
        prefs(ctx).getString(KEY_PENDING_SLOT, "").orEmpty()

    /** 开始一次「添加副号」登录 */
    fun beginAddSecondary(ctx: Context) {
        prefs(ctx).edit().putString(KEY_PENDING_SLOT, "new").apply()
    }

    /** 开始一次「重新登录某个账号」 */
    fun beginRelogin(ctx: Context, id: String) {
        prefs(ctx).edit().putString(KEY_PENDING_SLOT, "=$id").apply()
    }

    /** 开始一次「主号登录」 */
    fun beginPrimaryLogin(ctx: Context) {
        prefs(ctx).edit().remove(KEY_PENDING_SLOT).apply()
    }

    /**
     * 登录成功后把当前 jar 里的 cookie 落到正确的账号槽。
     * @return 落到的账号（label 可用于提示）
     */
    fun saveLoginResult(ctx: Context, cookie: String, uid: String): Account {
        ensureMigrated(ctx)
        val slot = prefs(ctx).getString(KEY_PENDING_SLOT, "").orEmpty()
        val list = all(ctx).toMutableList()
        // ★ vc494：副号登录若拿到的仍是主号身份 ⇒ 拒绝写入（否则会出现"两个副号同号"）
        val p0 = list.firstOrNull { it.primary }
        // ★★ vc506：副号槽却登成了主号 —— **不能丢弃这次登录**！
        //   事故（2026-10-28 用户日志实证）：点"添加账号"会先清拼多多 cookie，
        //   用户在登录页登的仍是主号 ⇒ 旧逻辑判"同号"直接 return，既不更新主号 cookie
        //   也不清失效标记，主号就一直 loginOk=false，表现为"怎么登都登不上"。
        //   正确做法：**按主号登录处理**（更新主号 cookie/uid、清失效标记）。
        val isSecondarySlot = slot == "new" || slot.startsWith("=")
        if (isSecondarySlot && p0 != null && uid.isNotEmpty() && uid == p0.uid) {
            val fixed = list.map {
                if (it.primary) it.copy(cookie = cookie, uid = uid, deadAt = 0L) else it
            }
            save(ctx, fixed)
            prefs(ctx).edit().remove(KEY_PENDING_SLOT).apply()
            Log.i(TAG, "副号槽登成了主号（uid=${uid.take(8)}）⇒ 按主号登录处理，已更新主号 cookie")
            return fixed.first { it.primary }
        }
        var result: Account
        when {
            slot == "new" -> {
                val n = list.count { !it.primary } + 1
                result = Account(
                    id = "a$n", label = "副号$n", cookie = cookie, uid = uid,
                    primary = false, addedAt = System.currentTimeMillis()
                )
                list.add(result)
                Log.i(TAG, "新增副号 ${result.label} uid=${uid.take(8)}")
            }
            slot.startsWith("=") -> {
                val id = slot.drop(1)
                val idx = list.indexOfFirst { it.id == id }
                if (idx >= 0) {
                    result = list[idx].copy(cookie = cookie, uid = uid, deadAt = 0L)
                    list[idx] = result
                    Log.i(TAG, "更新账号 ${result.label} uid=${uid.take(8)}")
                } else {
                    val n2 = list.count { !it.primary } + 1
                    result = Account("a$n2", "副号$n2", cookie, uid, false)
                    list.add(result)
                }
            }
            else -> {
                val idx = list.indexOfFirst { it.primary }
                if (idx >= 0) {
                    result = list[idx].copy(cookie = cookie, uid = uid, deadAt = 0L)
                    list[idx] = result
                } else {
                    result = Account("a1", "主号", cookie, uid, true)
                    list.add(result)
                }
                Log.i(TAG, "更新主号 uid=${uid.take(8)}")
            }
        }
        // 同一份 cookie 可能被登录到别的槽 ⇒ 清掉其它槽里的重复（防止同一账号出现两行）
        val dedup = list.filterIndexed { i, a ->
            i == list.indexOfFirst { it.cookie == a.cookie && it.cookie.isNotBlank() } || a.cookie.isBlank()
        }
        save(ctx, dedup.ifEmpty { list })
        prefs(ctx).edit().remove(KEY_PENDING_SLOT).apply()
        return result
    }

    /** 给纯 HTTP 通道用的会话 */
    fun sessionOf(a: Account): PddNative.Session {
        val token = Regex("PDDAccessToken=([^;]+)").find(a.cookie)?.groupValues?.get(1)?.trim().orEmpty()
        val uid = a.uid.ifEmpty { PddChannel.uidFromCookie(a.cookie) }
        return PddNative.Session(token = token, uid = uid, cookie = a.cookie)
    }
}
