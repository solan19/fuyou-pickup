package com.agent.idcode

import android.content.Context

/**
 * ★★ vc620：**黑名单只有一套存储**（原来分两套，删了还通知 / 通知关了还显示）。
 *
 * ## 原来什么样
 * · `MainActivity.KEY_PICKUP_MUTED = "pickup_muted"` —— 卡片删除屏蔽，键形如 `pdd:261013-xxx`、`code:31-5-7006`；
 * · `ExpressStore.KEY_MUTED = "track_muted"` —— 后台通知屏蔽，键是**纯单号**。
 *
 * 两套各写各的 ⇒ 用户在取件码页删掉一张卡，后台仍会为它发通知（反之亦然）。
 *
 * ## 现在
 * 统一到 `mute_keys`（StringSet），元素两种形态都接受：
 * · `src:no`（带来源，精确）
 * · `no`（纯单号，等价于所有来源）
 *
 * 首次读取时自动把两套旧数据合并进来，**不需要用户重设**。
 * 对外只暴露 [all] / [save] / [add] / [remove] / [clear] / [nos] / [isMutedNo]。
 */
object MuteStore {

    private const val PREFS = "idcode_prefs"
    private const val KEY = "mute_keys"
    /** ★★ vc694：「永久隐藏」名单 —— 与黑名单分开存：黑名单可移出，隐藏则平台再返回也不显示 */
    private const val KEY_HIDDEN = "hide_keys"
    private const val KEY_OLD_PICKUP = "pickup_muted"
    private const val KEY_OLD_TRACK = "track_muted"

    private fun sp(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 全部屏蔽键（首次调用会迁移旧的 `pickup_muted` + `track_muted`） */
    fun all(c: Context): MutableSet<String> {
        val p = sp(c)
        val existing = p.getStringSet(KEY, null)
        if (existing != null) {
            // ★★ vc670：**一次性并入旧 `pickup_muted`**。
            //   vc620 之后 [MainActivity.pickupMutedAdd] 仍在往这个旧键里写，而读取只认
            //   `mute_keys` ⇒ 用户长按删除的记录**全躺在这个死键里**（"删了还在"的根因）。
            //   合并后立刻删掉旧键，避免以后从黑名单页移出的项又被旧数据加回来。
            val legacy = p.getStringSet(KEY_OLD_PICKUP, null)
            if (!legacy.isNullOrEmpty()) {
                val merged = existing.toMutableSet().apply { addAll(legacy) }
                p.edit().putStringSet(KEY, merged).remove(KEY_OLD_PICKUP).apply()
                android.util.Log.i("MuteStore", "并入旧 pickup_muted ${legacy.size} 条（vc670 一次性）")
                return merged
            }
            return existing.toMutableSet()
        }
        val merged = LinkedHashSet<String>()
        try {
            p.getStringSet(KEY_OLD_PICKUP, null)?.let { merged.addAll(it) }
            p.getStringSet(KEY_OLD_TRACK, null)?.forEach { if (it.isNotBlank()) merged.add(it) }
            p.edit().putStringSet(KEY, merged).apply()
            if (merged.isNotEmpty()) {
                android.util.Log.i("MuteStore", "迁移旧黑名单 ${merged.size} 条 → $KEY")
            }
        } catch (t: Throwable) {
            SafeRun.w("MuteStore", t)
        }
        return merged
    }

    fun save(c: Context, set: Set<String>) {
        try {
            sp(c).edit().putStringSet(KEY, LinkedHashSet(set)).apply()
        } catch (t: Throwable) {
            SafeRun.w("MuteStore", t)
        }
    }

    fun add(c: Context, key: String) {
        if (key.isBlank()) return
        val s = all(c); s.add(key); save(c, s)
    }

    fun remove(c: Context, key: String) {
        val s = all(c)
        if (s.remove(key)) save(c, s)
    }

    fun clear(c: Context) {
        try {
            sp(c).edit().remove(KEY).remove(KEY_OLD_PICKUP).remove(KEY_OLD_TRACK).apply()
        } catch (t: Throwable) {
            SafeRun.w("MuteStore", t)
        }
    }

    /** 只清掉明确带目标来源前缀的屏蔽项，纯单号旧项保留。 */
    fun clearSource(c: Context, source: String) {
        try {
            val aliases = if (source == "tb") setOf("tb", "cainiao") else setOf(source)
            val kept = all(c).filterNot { key ->
                val prefix = key.substringBefore(':', "")
                prefix in aliases && key.contains(':')
            }.toSet()
            save(c, kept)
        } catch (t: Throwable) {
            SafeRun.w("MuteStore", t)
        }
    }

    /** 纯单号视图（给后台通知用）：去掉 `src:` 前缀后去重 */
    fun nos(c: Context): Set<String> {
        // ★ vc694：永久隐藏的也要算进来 —— 否则"藏起来了却还在发通知"
        val all = LinkedHashSet<String>()
        all.addAll(all(c))
        all.addAll(hidden(c))
        return all.map { it.substringAfterLast(':') }.filter { it.isNotBlank() }.toSet()
    }

    /** 这单是否被屏蔽（不看来源）。★ vc694：永久隐藏同样算"屏蔽" */
    fun isMutedNo(c: Context, no: String): Boolean {
        if (no.isBlank()) return false
        return all(c).any { it == no || it.endsWith(":$no") } ||
                hidden(c).any { it == no || it.endsWith(":$no") }
    }

    // ---------------- ★★ vc694：永久隐藏（全部清除的落点） ----------------

    /** 永久隐藏名单（键形态与黑名单同：`src:no` 或纯单号） */
    fun hidden(c: Context): MutableSet<String> {
        val s = try { sp(c).getStringSet(KEY_HIDDEN, null) } catch (_: Throwable) { null }
        return if (s.isNullOrEmpty()) LinkedHashSet() else LinkedHashSet(s)
    }

    fun saveHidden(c: Context, set: Set<String>) {
        try {
            sp(c).edit().putStringSet(KEY_HIDDEN, LinkedHashSet(set)).apply()
        } catch (t: Throwable) {
            SafeRun.w("MuteStore", t)
        }
    }

    /** 把一批键加入永久隐藏 */
    fun addHidden(c: Context, keys: Collection<String>) {
        if (keys.isEmpty()) return
        val s = hidden(c)
        s.addAll(keys.filter { it.isNotBlank() })
        saveHidden(c, s)
    }

    /** 撤销全部隐藏（黑名单页「全部恢复」用） */
    fun clearHidden(c: Context) {
        try {
            sp(c).edit().remove(KEY_HIDDEN).apply()
        } catch (t: Throwable) {
            SafeRun.w("MuteStore", t)
        }
    }

    /** 这单是否被永久隐藏（指定来源） */
    fun isHiddenKey(c: Context, src: String, no: String): Boolean {
        if (no.isBlank()) return false
        val s = hidden(c)
        return s.contains("$src:$no") || s.contains(no)
    }

    /** 这单是否被屏蔽（指定来源） */
    fun isMutedKey(c: Context, src: String, no: String): Boolean {
        if (no.isBlank()) return false
        val s = all(c)
        return s.contains("$src:$no") || s.contains(no)
    }

    /** 切换某单的屏蔽（后台"不再提醒"用） */
    fun toggleNo(c: Context, no: String): Boolean {
        if (no.isBlank()) return false
        val s = all(c)
        val now: Boolean
        if (s.remove(no)) {
            now = false
        } else {
            // 同时把带来源的同单号清掉，避免"关掉了但还显示"
            s.removeAll { it.endsWith(":$no") }
            s.add(no)
            now = true
        }
        save(c, s)
        return now
    }
}
