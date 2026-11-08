package com.agent.idcode

/**
 * ★ vc509：**导出脱敏**（只在打包 zip 时生效，落盘日志保持原文）。
 *
 * 原则：**打完码仍然可排查**。
 * - 手机号：保留前 3 + 后 4（能确认是哪个号）
 * - 取件码 `31-5-7006` → `31-*-06`（能核结构，不能拿去取件）
 * - uid：只留后 6 位（能区分主号/副号）
 * - cookie/token：整段替换成 `len=长度`（足够判断有没有拿全）
 * - 地址里的门牌号/单元/室：打码，驿站名保留
 */
object LogSanitizer {

    private val phone = Regex("(?<!\\d)(1[3-9]\\d{9})(?!\\d)")
    private val codeHyphen = Regex("(?<![\\d-])(\\d{1,3})-(\\d{1,2})-(\\d{2,4})(?![\\d-])")
    private val tokenVal = Regex(
        "((?:PDDAccessToken|cookie2?|_m_h5_tk|_m_h5_tk_enc|unb|_tb_token_|SID|OWFSESSION|sgcookie)=)([^;\\s\"']{4,})"
    )
    private val uidVal = Regex("(uid=)(\\d{6,})(?!\\d)")
    private val house = Regex("(\\d{1,4})(号(?:楼)?|单元|室|栋)")

    fun line(s: String): String {
        if (s.isEmpty()) return s
        var t = s
        try {
            t = phone.replace(t) { m ->
                val v = m.groupValues[1]
                v.take(3) + "****" + v.takeLast(4)
            }
            t = codeHyphen.replace(t) { m ->
                m.groupValues[1] + "-*-" + m.groupValues[3].takeLast(2)
            }
            t = tokenVal.replace(t) { m ->
                m.groupValues[1] + "len=" + m.groupValues[2].length
            }
            t = uidVal.replace(t) { m ->
                m.groupValues[1] + "…" + m.groupValues[2].takeLast(6)
            }
            t = house.replace(t) { m -> "*" + m.groupValues[2] }
        } catch (t: Throwable) { SafeRun.w("LogSanitizer", t) }
        return t
    }
}
