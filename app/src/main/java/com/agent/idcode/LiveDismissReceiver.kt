package com.agent.idcode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * ★ vc467：流体云（实况位）被用户**划掉**时的回调。
 *
 * 以前只在岛/通知上点「已完成」才记录，用户直接划掉等于没记录，
 * 下一轮 sync 又把同一条挂回去 → 同志反馈"划掉也还会弹"。
 */
class LiveDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LiveUpdate.ACTION_DISMISS) return
        val id = intent.getIntExtra("id", 0)
        if (id != 0) LiveUpdate.onDismissed(context, id)
    }
}
