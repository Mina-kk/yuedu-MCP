-keep class io.modelcontextprotocol.** { *; }
-keep class io.ktor.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
-dontwarn com.sun.nio.file.SensitivityWatchEventModifier
-dontwarn javax.swing.**
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn org.htmlunit.corejs.javascript.tools.**

# Stable JSON/MCP/WebView contracts
-keep class com.mina.legadostudio.data.db.** { *; }
-keep class com.mina.legadostudio.runtime.** { *; }
-keep class com.mina.legadostudio.network.** { *; }
-keep class com.mina.legadostudio.device.** { *; }
-keep class com.mina.legadostudio.skills.** { *; }
-keep class com.mina.legadostudio.mcp.** { *; }
-keep class com.mina.legadostudio.export.** { *; }
-keep class io.legado.app.** { *; }
-keep class com.script.** { *; }
-keep class org.htmlunit.** { *; }
# 书源 JS 经 Rhino 反射按名调用 jsoup / JsonPath 对象方法（Jsoup.parse(..).select 等），名字必须原样保留
-keep class org.jsoup.** { *; }
-keep class com.jayway.jsonpath.** { *; }
-keep class net.minidev.** { *; }
-keep class org.seimicrawler.xpath.** { *; }
-dontwarn org.jsoup.**
-dontwarn com.jayway.jsonpath.**
-dontwarn net.minidev.**
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
