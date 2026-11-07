package com.xxjz.unlock;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 拿到 ConfigEntity 的 ObjectBox Box：
 *  - 首选 BoxStore.boxFor(Class)（App 每次取 box 都会经过这里）
 *  - 兜底 Box.put(T)（写入时反推）
 * 两个类都可能比模块晚加载，所以既能主动 install，也能被 ClassWatcher 回调补挂。
 */
final class BoxWatcher {

    private static boolean storeInstalled = false;
    private static boolean boxInstalled = false;

    private BoxWatcher() {
    }

    static void install(ClassLoader cl) {
        try {
            installOn(Class.forName("io.objectbox.BoxStore", false, cl));
        } catch (Throwable t) {
            MainHook.log("BoxStore 尚未加载，等 ClassWatcher 回调: " + t);
        }
    }

    static synchronized void installOn(Class<?> boxStore) {
        if (storeInstalled || boxStore == null) return;
        storeInstalled = true;

        Method boxFor = null;
        try {
            for (Method m : boxStore.getDeclaredMethods()) {
                if (!m.getName().equals("boxFor")) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 1 && ps[0] == Class.class) {
                    boxFor = m;
                    break;
                }
            }
        } catch (Throwable t) {
            MainHook.log("枚举 BoxStore 方法失败: " + t);
        }

        if (boxFor != null) {
            try {
                XposedBridge.hookMethod(boxFor, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        Object arg = p.args[0];
                        Object box = p.getResult();
                        if (arg instanceof Class && box != null) {
                            String n = ((Class<?>) arg).getName();
                            if (n.contains("ConfigEntity")) {
                                StateStore.bindBox(box, (Class<?>) arg);
                            }
                        }
                    }
                });
                MainHook.log("BoxStore.boxFor 已挂");
            } catch (Throwable t) {
                MainHook.log("hook boxFor 失败: " + t);
            }
        }

        try {
            installBox(Class.forName("io.objectbox.Box", false, boxStore.getClassLoader()));
        } catch (Throwable t) {
            MainHook.log("Box 尚未加载，等回调: " + t);
        }
    }

    static synchronized void installBox(Class<?> boxCls) {
        if (boxInstalled || boxCls == null) return;
        boxInstalled = true;
        int n = 0;
        try {
            for (Method m : boxCls.getDeclaredMethods()) {
                if (!m.getName().equals("put")) continue;
                if (m.getParameterTypes().length != 1) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        Object arg = p.args[0];
                        if (arg == null) return;
                        if (arg.getClass().getName().contains("ConfigEntity")) {
                            StateStore.bindBox(p.thisObject, arg.getClass());
                        }
                    }
                });
                n++;
            }
        } catch (Throwable t) {
            MainHook.log("hook Box.put 失败: " + t);
        }
        MainHook.log("Box.put 兜底已挂 " + n + " 个重载");
    }
}
