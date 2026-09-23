# 微信分组·重生 —— 混淆规则
# 注意：本模块 minifyEnabled=false，以下规则仅作保险。
-keep class io.github.wxgroup.reborn.** { *; }
# 保留 Xposed 入口（防止被 shrink）
-keep class * implements de.robv.android.xposed.IXposedMod
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage
-keepattributes *Annotation*
