package com.xxjz.unlock;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 小星记账（com.cxincx.xxjz）会员 → 永久
 *
 * 设计要点：
 *  1) 该 App 被百度加固，自有类运行时才解密加载，因此不能"启动时找类"，
 *     必须在 ClassLoader 层拦 loadClass/forName，看到目标类再挂 hook。
 *  2) 会员状态在 ObjectBox 的 ConfigEntity 里（本地持久化），
 *     同时门禁走 boolean 判定 + 到期 long 判定，三处必须一起按。
 *  3) 所有操作都在 try/catch 里，单点失败不影响其它层。
 */
public class MainHook implements IXposedHookLoadPackage {

    static final String PKG = "com.cxincx.xxjz";
    static final String TAG = "XXJZ-UNLOCK";

    /** 会员模块包前缀：日志里若发现类名被壳改造，只改这一行即可放宽 */
    static final String VIP_SCOPE = "com.cxincx.xxjz.z.";

    /** 9999-12-31 23:59:59 UTC，远大于任何真实时间，又不会溢出 */
    static final long FOREVER = 253402300799000L;

    /** true = 只打印结构（类名/字段/方法/当前值），不改任何东西。第一次务必先用它确认真实字段名 */
    static final boolean DUMP_ONLY = false;

    /** true = 连 int 型 level/type/state 也强推（确认字段含义后再开） */
    static final boolean DEEP = false;

    /** true = 把遇到的每个 com.cxincx.* 类名都打日志（找判定类用；日志量大，定位完可关） */
    static final boolean LOG_CLASSES = true;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!PKG.equals(lpparam.packageName)) return;
        log("attach pkg=" + lpparam.packageName + " process=" + lpparam.processName
                + " first=" + lpparam.isFirstApplication);

        try {
            ClassWatcher.install(lpparam.classLoader);
        } catch (Throwable t) {
            log("ClassWatcher 安装失败: " + t);
        }
        try {
            Lifecycle.install(lpparam.classLoader);
        } catch (Throwable t) {
            log("Lifecycle 安装失败: " + t);
        }
        try {
            Prefs.install(lpparam.classLoader);
        } catch (Throwable t) {
            log("Prefs 安装失败: " + t);
        }
        try {
            BoxWatcher.install(lpparam.classLoader);
        } catch (Throwable t) {
            log("BoxWatcher 安装失败: " + t);
        }
    }

    static void log(String s) {
        XposedBridge.log(TAG + ": " + s);
    }
}
