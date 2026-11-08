package com.agent.idcode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * ★ vc321：超级岛「已完成」按钮的回调（HyperOS 焦点通知 miui.focus.actions）。
 *
 * 只做两件事：撤掉这条岛 + 记住「用户手动撤过」——
 * 同一个码 24h 内不再自动重挂。**不改** PddCodeStore 的已取件状态
 * （「已取件」唯一来源仍是第 4 栏长按标记，vc316 同志口径）。
 */
class IslandActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != IslandHelper.ACTION_COMPLETE) return
        val nid = intent.getIntExtra(IslandHelper.EXTRA_NID, 0)
        if (nid == 0) return
        IslandHelper.onCompleteAction(context, nid)
    }
}
