package com.xxjz.unlock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * V2：范围从 com.cxincx.xxjz.z. 放宽到整个 com.cxincx（只放"看见"，不放"乱改"）
 *  - 强信号类（vip/secret/license/trial/activ/unlock/pay/order/ConfigEntity）→ 真正挂 hook
 *  - 其余 com.cxincx 类 → 只登记，12 秒后一次性打印类名清单（用来找真正的判定类）
 */
final class ClassWatcher {

    private static final Set<String> SEEN = new HashSet<>();
    private static final List<String> CENSUS = new ArrayList<>();
    private static final ThreadLocal<Boolean> IN_DISPATCH = new ThreadLocal<>();
    private static volatile boolean installed = false;

    private static final String[] PATCH_SIGNALS = {
            "vip", "secret", "license", "trial", "activ", "unlock", "pay", "order", "purchase", "goods"
    };

    private ClassWatcher() {
    }

    static void install(ClassLoader cl) {
        if (installed) return;
        installed = true;

        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object r = param.getResult();
                if (r instanceof Class) {
                    dispatch((Class<?>) r);
                }
            }
        };

        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class, hook);
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class, boolean.class, hook);
            XposedHelpers.findAndHookMethod(Class.class, "forName", String.class, boolean.class,
                    ClassLoader.class, hook);
            MainHook.log("ClassWatcher V2 就绪");
        } catch (Throwable t) {
            MainHook.log("ClassWatcher 安装失败: " + t);
        }

        startCensus();
    }

    private static void dispatch(Class<?> c) {
        if (c == null) return;
        if (Boolean.TRUE.equals(IN_DISPATCH.get())) return;
        IN_DISPATCH.set(Boolean.TRUE);
        try {
            final String n = c.getName();

            // ObjectBox 可能比模块晚加载，这里补挂
            if ("io.objectbox.BoxStore".equals(n)) {
                BoxWatcher.installOn(c);
                return;
            }
            if ("io.objectbox.Box".equals(n)) {
                BoxWatcher.installBox(c);
                return;
            }

            if (!n.startsWith("com.cxincx")) return;
            synchronized (SEEN) {
                if (!SEEN.add(n)) return;
                CENSUS.add(n);
            }

            if (shouldPatch(n)) {
                try {
                    MemberPatcher.patch(c);
                } catch (Throwable t) {
                    MainHook.log("patch 失败 " + n + " : " + t);
                }
            } else if (MainHook.LOG_CLASSES) {
                MainHook.log("class " + n);
            }
        } finally {
            IN_DISPATCH.set(Boolean.FALSE);
        }
    }

    private static boolean shouldPatch(String n) {
        if (n.contains("ConfigEntity") || n.contains("VipDialog") || n.contains("VipActivity")) return true;
        String l = n.toLowerCase(Locale.ROOT);
        for (String s : PATCH_SIGNALS) {
            if (l.contains(s)) return true;
        }
        return false;
    }

    /** 12 秒后把见过的 com.cxincx 类名一次性打出来，方便定位真正的会员判定类 */
    private static void startCensus() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(12000);
                } catch (InterruptedException ignored) {
                }
                List<String> copy;
                synchronized (SEEN) {
                    copy = new ArrayList<>(CENSUS);
                }
                Collections.sort(copy);
                StringBuilder sb = new StringBuilder("census " + copy.size() + " 个类:");
                int n = 0;
                for (String s : copy) {
                    if (n++ >= 120) {
                        sb.append("\n    ...");
                        break;
                    }
                    sb.append("\n    ").append(s);
                }
                MainHook.log(sb.toString());
            }
        }, "xxjz-census");
        t.setDaemon(true);
        t.start();
    }
}
