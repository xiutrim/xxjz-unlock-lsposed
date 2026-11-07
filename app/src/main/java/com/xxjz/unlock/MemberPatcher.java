package com.xxjz.unlock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 三类 hook：
 *  1) boolean 方法 → true（门禁判定）
 *  2) long 方法且返回像时间戳 → 9999-12-31（到期判定）
 *  3) VipDialog.show → 不弹
 * ConfigEntity 额外交给 StateStore 做状态改写；VipActivity 交给 Lifecycle 刷新 UI。
 */
final class MemberPatcher {

    /** 这些方法名字太通用，强推 true 会破坏正常逻辑 */
    private static final Set<String> SKIP = new HashSet<>(Arrays.asList(
            "equals", "hashCode", "toString", "isFinishing", "isDestroyed",
            "isChangingConfigurations", "isAttachedToWindow", "isVisible",
            "isRemoving", "isHidden", "isAdded", "post", "send",
            "dispatchTouchEvent", "dispatchKeyEvent", "dispatchGenericMotionEvent",
            "onTouchEvent", "onKeyDown", "onKeyUp", "onKeyLongPress", "onBackPressed",
            "performClick", "onInterceptTouchEvent"));

    /** 语义相反的判定：这些必须返回 false，否则"到期/失效"分支会被误触发 */
    private static final java.util.regex.Pattern NEG = java.util.regex.Pattern.compile(
            "(?i).*(expire|expired|timeout|time_out|overdue|invalid|fail|error|banned|blocked|used_up|usedup|limit_full|no_vip|not_vip).*");

    private static final Set<String> HOOKED = new HashSet<>();

    private MemberPatcher() {
    }

    static void patch(Class<?> c) {
        final String cn = c.getName();
        if (MainHook.DUMP_ONLY) {
            dump(c);
            return;
        }

        final boolean isDialog = cn.contains("VipDialog");
        int hooked = 0;

        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            // 只处理 App 自己的类，绝不碰 Activity/View 等框架方法（否则返回键、触摸事件会被吞掉）
            if (!k.getName().startsWith("com.cxincx")) break;
            Method[] ms;
            try {
                ms = k.getDeclaredMethods();
            } catch (Throwable t) {
                continue;
            }
            for (final Method m : ms) {
                if (m.isSynthetic() || m.isBridge()) continue;
                final String key = k.getName() + "#" + m.getName() + Arrays.toString(m.getParameterTypes());
                synchronized (HOOKED) {
                    if (!HOOKED.add(key)) continue;
                }

                final String mn = m.getName();
                final String ret = m.getReturnType().getName();

                if (isDialog && mn.startsWith("show")) {
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam p) {
                                MainHook.log("静音 " + shortName(cn) + "#" + mn);
                                p.setResult(null);
                            }
                        });
                        hooked++;
                    } catch (Throwable t) {
                        MainHook.log("hook " + key + " 失败: " + t);
                    }
                    continue;
                }

                if ("boolean".equals(ret) && !SKIP.contains(mn)) {
                    final boolean wantFalse = NEG.matcher(mn).matches();
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam p) {
                                Object r = p.getResult();
                                if (!(r instanceof Boolean)) return;
                                boolean v = (Boolean) r;
                                if (wantFalse) {
                                    if (v) {
                                        MainHook.log("bool " + shortName(cn) + "#" + mn + " true->false");
                                        p.setResult(Boolean.FALSE);
                                    }
                                } else if (!v) {
                                    MainHook.log("bool " + shortName(cn) + "#" + mn + " false->true");
                                    p.setResult(Boolean.TRUE);
                                }
                            }
                        });
                        hooked++;
                    } catch (Throwable t) {
                        MainHook.log("hook " + key + " 失败: " + t);
                    }
                } else if ("long".equals(ret)) {
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam p) {
                                Object r = p.getResult();
                                if (!(r instanceof Long)) return;
                                long v = (Long) r;
                                if (v > 1000000000000L && v < MainHook.FOREVER) {
                                    MainHook.log("time " + shortName(cn) + "#" + mn + " " + v + "->FOREVER");
                                    p.setResult(MainHook.FOREVER);
                                }
                            }
                        });
                        hooked++;
                    } catch (Throwable t) {
                        MainHook.log("hook " + key + " 失败: " + t);
                    }
                }
            }
        }

        if (cn.contains("ConfigEntity")) {
            StateStore.bindEntity(c);
            logFields(c);
        }
        MainHook.log("已处理 " + cn + "，挂载 " + hooked + " 处");
    }

    /** ConfigEntity 一出现就把字段清单打出来（不需要 DUMP_ONLY） */
    static void logFields(Class<?> c) {
        Object probe = instantiate(c);
        StringBuilder sb = new StringBuilder("ConfigEntity 字段:");
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            Field[] fs;
            try {
                fs = k.getDeclaredFields();
            } catch (Throwable t) {
                continue;
            }
            for (Field f : fs) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                sb.append("\n    ").append(f.getName()).append(" : ").append(f.getType().getSimpleName());
                if (probe != null) {
                    try {
                        sb.append(" = ").append(f.get(probe));
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        MainHook.log(sb.toString());
    }

    private static String shortName(String cn) {
        int i = cn.lastIndexOf('.');
        return i < 0 ? cn : cn.substring(i + 1);
    }

    /** DUMP_ONLY：把真实字段名/类型/当前值打到 LSPosed 日志，用来确认混淆后的命名 */
    private static void dump(Class<?> c) {
        MainHook.log("=== DUMP " + c.getName() + "  super=" + (c.getSuperclass() == null ? "?" : c.getSuperclass().getName()));
        Object probe = instantiate(c);
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            Field[] fs;
            try {
                fs = k.getDeclaredFields();
            } catch (Throwable t) {
                continue;
            }
            for (Field f : fs) {
                f.setAccessible(true);
                String head = (Modifier.isStatic(f.getModifiers()) ? "static " : "") + f.getName() + " : " + f.getType().getName();
                String val = "";
                if (!Modifier.isStatic(f.getModifiers()) && probe != null) {
                    try {
                        val = "  = " + f.get(probe);
                    } catch (Throwable ignored) {
                    }
                }
                MainHook.log("  field " + head + val);
            }
        }
        for (Method m : c.getDeclaredMethods()) {
            MainHook.log("  method " + m.getReturnType().getName() + " " + m.getName()
                    + Arrays.toString(m.getParameterTypes()));
        }
    }

    private static Object instantiate(Class<?> c) {
        try {
            return c.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            return null;
        }
    }
}
