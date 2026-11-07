package com.xxjz.unlock;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 页面层的兜底：
 *  - 进程内第一个 Activity.onResume → 先把状态刷成永久（用户还没点进会员页就已生效）
 *  - 进入 VipActivity → 再刷一次，并 recreate 一次让 UI 立刻显示「已激活 / 永久」
 */
final class Lifecycle {

    private static final AtomicBoolean FIRST_RESUME = new AtomicBoolean(false);
    private static final AtomicBoolean RECREATED = new AtomicBoolean(false);

    private Lifecycle() {
    }

    static void install(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", cl, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    Object act = p.thisObject;
                    if (act == null) return;
                    String cn = act.getClass().getName();
                    if (!cn.startsWith("com.cxincx.xxjz")) return;

                    if (FIRST_RESUME.compareAndSet(false, true)) {
                        MainHook.log("首个页面 onResume: " + cn + " → 触发状态改写");
                        StateStore.scheduleRewrite();
                    }
                    if (cn.endsWith("VipActivity")) {
                        StateStore.scheduleRewrite();
                        if (RECREATED.compareAndSet(false, true)) {
                            try {
                                XposedHelpers.callMethod(act, "recreate");
                                MainHook.log("VipActivity 已刷新一次");
                            } catch (Throwable t) {
                                MainHook.log("recreate 失败: " + t);
                            }
                        }
                    }
                }
            });
            MainHook.log("Lifecycle 就绪");
        } catch (Throwable t) {
            MainHook.log("Lifecycle 安装失败: " + t);
        }
    }
}
