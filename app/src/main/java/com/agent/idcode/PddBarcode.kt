package com.agent.idcode

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * Code128B 条形码生成 —— 零外部依赖（工程既有风格：只用框架 API）。
 *
 * 为什么用它：淘宝/菜鸟的「身份码」页就是用 JsBarcode 把身份码渲染成一张大条形码
 * （bundle 里能直接看到 jsbarcode 模块 + canvas 绘制），出示给驿站扫码枪扫。
 * 拼多多身份码页要对齐同一种视觉，所以这里自带一个 Code128 编码器。
 *
 * 码表：107 个符号，每个 6 个元素（条/空交替的宽度），Stop(106) 是 7 个元素共 13 模块。
 */
object PddBarcode {

    /** Code128 标准码表，值 0..106 */
    private val PATTERNS = arrayOf(
        "212222", "222122", "222221", "121223", "121322", "131222", "122213", "122312",
        "132212", "221213", "221312", "231212", "112232", "122132", "122231", "113222",
        "123122", "123221", "223211", "221132", "221231", "213212", "223112", "312131",
        "311222", "321122", "321221", "312212", "322112", "322211", "212123", "212321",
        "232121", "111323", "131123", "131321", "112313", "132113", "132311", "211313",
        "231113", "231311", "112133", "112331", "132131", "113123", "113321", "133121",
        "313121", "211331", "231131", "213113", "213311", "213131", "311123", "311321",
        "331121", "312113", "312311", "332111", "314111", "221411", "431111", "111224",
        "111422", "121124", "121421", "141122", "141221", "112214", "112412", "122114",
        "122411", "142112", "142211", "241211", "221114", "413111", "241112", "134111",
        "111242", "121142", "121241", "114212", "124112", "124211", "411212", "421112",
        "421211", "212141", "214121", "412121", "111143", "111341", "131141", "114113",
        "114311", "411113", "411311", "113141", "114131", "311141", "411131", "211412",
        "211214", "211232", "2331112"
    )

    private const val START_B = 104
    private const val STOP = 106

    /**
     * 把文本编成模块序列（true = 条，false = 空）。
     * 非法字符（不在 ASCII 32..126）会被替换成 '?'，保证编码永远能出来。
     */
    fun modules(text: String): BooleanArray {
        val clean = text.map { c -> if (c.code in 32..126) c else '?' }
        val values = ArrayList<Int>()
        values.add(START_B)
        for (c in clean) values.add(c.code - 32)

        var sum = START_B
        for (i in clean.indices) sum += (i + 1) * (clean[i].code - 32)
        values.add(sum % 103)
        values.add(STOP)

        val out = ArrayList<Boolean>()
        for ((idx, v) in values.withIndex()) {
            val pat = PATTERNS[v]
            var bar = true
            for (ch in pat) {
                val n = ch - '0'
                repeat(n) { out.add(bar) }
                bar = !bar
            }
            if (idx >= 0) { /* 符号之间本来就是条空交替，无需额外分隔 */ }
        }
        return out.toBooleanArray()
    }

    /**
     * 渲染成 Bitmap。
     * @param moduleWidth 单个模块的像素宽（越小越窄，但别小于 2 否则扫码枪可能读不出）
     * @param height      条码高度（不含底部文字）
     * @param quietZone   两侧静默区模块数（标准要求 ≥10）
     */
    fun bitmap(
        text: String,
        moduleWidth: Int = 3,
        height: Int = 180,
        quietZone: Int = 12,
        barColor: Int = Color.BLACK,
        /** 背景色（默认纯白）。官方身份码也是白底，深色主题下必须铺白否则扫码枪读不出 */
        bgColor: Int = Color.WHITE
    ): Bitmap {
        val mods = modules(text)
        val totalW = (mods.size + quietZone * 2) * moduleWidth
        val bmp = Bitmap.createBitmap(totalW.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(bgColor)
        val paint = Paint().apply {
            color = barColor
            style = Paint.Style.FILL
        }
        var x = (quietZone * moduleWidth).toFloat()
        for (m in mods) {
            if (m) canvas.drawRect(x, 0f, x + moduleWidth, height.toFloat(), paint)
            x += moduleWidth
        }
        return bmp
    }
}
