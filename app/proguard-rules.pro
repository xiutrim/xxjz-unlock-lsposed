# 模块本身不混淆；Xposed 入口类必须保留原名
-keep class com.xxjz.unlock.MainHook { *; }
-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**
