# ============================================================
# 身份码 · R8 keep 规则（vc282 起启用混淆裁剪）
# 原则：保守 keep。裁错了的表现是"运行时崩"，比体积大更糟。
# ============================================================

# ---------- 反射调用的元信息 ----------
# Kotlin 协程/注解处理/泛型签名都依赖这些属性，缺了会 NoSuchField/类型擦除问题
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*, Exceptions

# ---------- WorkManager：Worker 按类名字符串反射实例化 ----------
# TrackWorker 等在 WorkSpec 里存的是全限定类名，R8 重命名后反序列化必崩
-keep class * extends androidx.work.ListenableWorker { *; }
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }

# ---------- 桌面小组件：Provider 按 Manifest 类名反射实例化（vc289）----------
-keep class * extends android.appwidget.AppWidgetProvider { *; }
# vc296：鲸鱼娘点击接收器（单击/双击判定）也是 Manifest 注册，同样按类名反射实例化
-keep class * extends android.content.BroadcastReceiver { *; }

# ---------- AIDL：跨进程 Binder 接口（Shizuku/Stellar 看门人）----------
# 接口名与方法序号必须跨进程一致，混淆即断链
-keep interface com.agent.idcode.watchdog.IWatchDog { *; }
-keep class com.agent.idcode.watchdog.** { *; }
-keep class roro.stellar.userservice.** { *; }

# ---------- Stellar 用户服务（看门人）：由 Stellar **反射实例化** ----------
# ★ vc303 实测翻车：Stellar 用反射 new WatchUserService()，而本文件此前 keep 的是
#   com.agent.idcode.watchdog.**（包路径写错）⇒ 类被混淆成 I.O2、无参构造被裁 ⇒
#   StellarUserServiceStarter: java.lang.NoSuchMethodException: I.O2.<init> []
#   ⇒ 独立特权进程永远起不来（连带"shell 注入 input"能力也拿不到）。
#   这里按**真实包名** keep 类本身 + public 无参构造。
-keep class com.agent.idcode.WatchUserService { public <init>(); *; }
-keep interface com.agent.idcode.IWatchDog { *; }
-keep class com.agent.idcode.IWatchDog$Stub { *; }
-keep class com.agent.idcode.IWatchDog$Stub$Proxy { *; }
-keep class com.agent.idcode.IWatchDog$Stub{ *; }

# ---------- Shizuku / Stellar ----------
# provider 模块通过 Manifest 注册（自动 keep），但内部反射调用 Binder 代理，
# 接口与内部类名不能动
-keep class dev.rikka.shizuku.** { *; }
-keep interface dev.rikka.shizuku.** { *; }
-keep class rikka.** { *; }
-keep class moe.shizuku.** { *; }

# ---------- WebView 注入 ----------
# evaluateJavascript 注入的 JS 桥对象若被 R8 改名，addJavascriptInterface 的
# @JavascriptInterface 方法会失效；本项目虽未用 JS 桥，但保留此规则防回归
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---------- 行内崩溃兜底 ----------
# Kotlin 协程内部类在 R8 裁剪后偶发 KotlinMetadata 缺失告警，显式忽略
-dontwarn kotlinx.coroutines.**
-dontwarn org.jetbrains.annotations.**
