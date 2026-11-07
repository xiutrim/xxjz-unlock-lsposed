package com.xxjz.unlock;

import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 加固壳在运行时解密 dex 并用自己的 ClassLoader 加载，
 * 所以只能拦 ClassLoader，等目标类"出现"再挂 hook。
 */
final class ClassWatcher {

    private static final Set<String> SEEN = new HashSet<>();
    private static final ThreadLocal<Boolean> IN_DISPATCH = new ThreadLocal<>();
    private static volatile boolean installed = false;

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
        } catch (Throwable t) {
            MainHook.log("hook loadClass(String) 失败: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class, boolean.class, hook);
        } catch (Throwable t) {
            MainHook.log("hook loadClass(String,boolean) 失败: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(Class.class, "forName", String.class, boolean.class,
                    ClassLoader.class, hook);
        } catch (Throwable t) {
            MainHook.log("hook Class.forName 失败: " + t);
        }

        MainHook.log("ClassWatcher 就绪");
    }

    private static void dispatch(Class<?> c) {
        if (c == null) return;
        // 防止 hookMethod 自身触发的类加载导致递归
        if (Boolean.TRUE.equals(IN_DISPATCH.get())) return;
        IN_DISPATCH.set(Boolean.TRUE);
        try {
            final String n = c.getName();

            // ObjectBox 可能比本模块晚加载，这里补挂
            if ("io.objectbox.BoxStore".equals(n)) {
                BoxWatcher.installOn(c);
                return;
            }
            if ("io.objectbox.Box".equals(n)) {
                BoxWatcher.installBox(c);
                return;
            }

            if (!interesting(n)) return;
            synchronized (SEEN) {
                if (!SEEN.add(n)) return;
            }
            try {
                MemberPatcher.patch(c);
            } catch (Throwable t) {
                MainHook.log("patch 失败 " + n + " : " + t);
            }
        } finally {
            IN_DISPATCH.set(Boolean.FALSE);
        }
    }

    private static boolean interesting(String n) {
        return n.startsWith(MainHook.VIP_SCOPE)
                || n.contains("ConfigEntity")
                || n.contains("VipDialog")
                || n.contains("VipActivity")
                || n.contains("PayActivity");
    }
}
