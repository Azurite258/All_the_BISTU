package com.bistu.allthebistu.ui.wechat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 把动作递给微信。
 *
 * 背景：校内消费是商户扫码枪扫**你的** E 卡通码，App 没有扣款能力，
 * 自己做扫一扫意义不大；而校园码是 E 卡通服务端下发的动态加密码，
 * 外面根本生成不出来。所以这两个按钮的正解是——把用户送到微信那边去。
 *
 * `weixin://scanqrcode` 是微信公开的「扫一扫」scheme（2025 年实测仍可用），
 * 兜底再试 `weixin://dl/scan`，都不行就只把微信拉到前台。
 *
 * 注意：`<queries>` 里必须声明 com.tencent.mm，否则 Android 11+ 的包可见性
 * 会让 [isInstalled] 永远返回 false。
 */
object WeChatEntry {

    private const val PKG_WECHAT = "com.tencent.mm"
    private const val PKG_WECOM = "com.tencent.wework"

    /** 微信「扫一扫」的候选 scheme，按可靠性排序 */
    private val SCAN_SCHEMES = listOf(
        "weixin://scanqrcode",
        "weixin://dl/scan",
    )

    /** 微信装没装 */
    fun isInstalled(context: Context): Boolean = installed(context, PKG_WECHAT)

    /** 企业微信装没装。E 卡通在学校的**企业号**里，所以在企业微信里能直接摸到 */
    fun isWeComInstalled(context: Context): Boolean = installed(context, PKG_WECOM)

    private fun installed(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)

    /**
     * 直接进微信扫一扫。
     *
     * 微信是主线：E 卡通是学校**企业号**里的应用，学生在微信里就能进，
     * 所以扫一扫优先走微信的 scheme。
     *
     * @return true 表示已经成功把意图交出去了；false 表示微信/企业微信都没装
     */
    fun openScanner(context: Context): Boolean {
        if (isInstalled(context)) {
            for (scheme in SCAN_SCHEMES) {
                if (launch(context, Uri.parse(scheme))) return true
            }
            return openWeChat(context)
        }
        // 只装了企业微信：送到工作台，扫一扫让用户点右上角（企微没有公开的扫码 scheme）
        return openWeCom(context)
    }

    /** 只把微信拉到前台（校园码只能走到这一步，具体页面得用户自己点） */
    fun openWeChat(context: Context): Boolean {
        if (!isInstalled(context)) return false
        return launch(context, Uri.parse("weixin://")) ||
            launch(context, Uri.parse("wechat://"))
    }

    /**
     * 企业微信——**兜底**用，只在没装微信时走这条路。
     *
     * `wxwork://jump?target=workbench` 是企微官方的 schema（工作台），
     * E 卡通就在工作台里，比只把 App 拉起来准。
     */
    fun openWeCom(context: Context): Boolean {
        if (!isWeComInstalled(context)) return false
        return launch(context, Uri.parse("wxwork://jump?target=workbench")) ||
            launch(context, Uri.parse("wxwork://"))
    }

    private fun launch(context: Context, uri: Uri): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            // 部分系统/微信版本会对 scheme 做限制
            false
        }
    }
}
