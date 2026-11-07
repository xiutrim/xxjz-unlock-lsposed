package com.xxjz.unlock;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * V2：真实状态在 SharedPreferences（key 前缀 a_），所以这里做三件事
 *   1) 读：getLong 到期类 → FOREVER；getBoolean 会员类 → true
 *   2) 写：putLong/putBoolean 同样改写 → 让"永久"落盘，重启不回退
 *   3) 全量：getAll() 打一次 schema（键名+值），并把补丁写回 map
 * 日志做了去重，避免 a_end_time 被高频读取时刷屏。
 */
final class Prefs {

    /** 到期/有效期类 key */
    private static final Pattern K_END = Pattern.compile("(?i).*(end|expir|deadline|valid|overdue|timeout).*");
    private static final Pattern K_END_SKIP = Pattern.compile("(?i).*(start|begin|create|install|update|sync|backup).*");
    /** 会员标记类 key */
    private static final Pattern K_FLAG = Pattern.compile("(?i).*(vip|member|active|actived|forever|forever|pro|paid|perm|unlock|license|premium).*");

    private static final Set<String> ONCE = new HashSet<>();
    private static final AtomicBoolean SCHEMA_LOGGED = new AtomicBoolean(false);

    private Prefs() {
    }

    static void install(ClassLoader cl) {
        hookGetBoolean(cl);
        hookGetLong(cl);
        hookGetInt(cl);
        hookGetString(cl);
        hookGetAll(cl);
        hookEditor(cl);
        MainHook.log("Prefs V2 就绪（读+写+getAll）");
    }

    private static boolean isEndKey(String k) {
        return K_END.matcher(k).matches() && !K_END_SKIP.matcher(k).matches();
    }

    private static boolean isFlagKey(String k) {
        return K_FLAG.matcher(k).matches();
    }

    private static void once(String tag, String msg) {
        synchronized (ONCE) {
            if (!ONCE.add(tag)) return;
        }
        MainHook.log(msg);
    }

    // ---------------------------------------------------------------- 读

    private static void hookGetBoolean(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("android.app.SharedPreferencesImpl", cl,
                    "getBoolean", String.class, boolean.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            Object k = p.args[0];
                            Object r = p.getResult();
                            if (!(k instanceof String) || !(r instanceof Boolean)) return;
                            String key = (String) k;
                            if (Boolean.FALSE.equals(r) && isFlagKey(key)) {
                                once("gb#" + key, "sp.getBoolean " + key + " false->true");
                                p.setResult(Boolean.TRUE);
                            }
                        }
                    });
        } catch (Throwable t) {
            MainHook.log("hook getBoolean 失败: " + t);
        }
    }

    private static void hookGetLong(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("android.app.SharedPreferencesImpl", cl,
                    "getLong", String.class, long.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            Object k = p.args[0];
                            Object r = p.getResult();
                            if (!(k instanceof String) || !(r instanceof Long)) return;
                            String key = (String) k;
                            long v = (Long) r;
                            if (isEndKey(key) && v > 1000000000000L && v < MainHook.FOREVER) {
                                once("gl#" + key, "sp.getLong " + key + " " + v + "->FOREVER");
                                p.setResult(MainHook.FOREVER);
                            }
                        }
                    });
        } catch (Throwable t) {
            MainHook.log("hook getLong 失败: " + t);
        }
    }

    private static void hookGetInt(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("android.app.SharedPreferencesImpl", cl,
                    "getInt", String.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            Object k = p.args[0];
                            Object r = p.getResult();
                            if (!(k instanceof String) || !(r instanceof Integer)) return;
                            String key = (String) k;
                            once("gi#" + key, "sp.getInt " + key + " = " + r);
                            // DEEP：套餐/类型位推到 4（永久）
                            if (MainHook.DEEP && isFlagKey(key)) {
                                int v = (Integer) r;
                                if (v >= 0 && v < 4) {
                                    once("giF#" + key, "sp.getInt " + key + " " + v + "->4");
                                    p.setResult(4);
                                }
                            }
                        }
                    });
        } catch (Throwable t) {
            MainHook.log("hook getInt 失败: " + t);
        }
    }

    private static void hookGetString(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("android.app.SharedPreferencesImpl", cl,
                    "getString", String.class, String.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            Object k = p.args[0];
                            if (!(k instanceof String)) return;
                            String key = (String) k;
                            Object r = p.getResult();
                            String v = String.valueOf(r);
                            if (v.length() > 60) v = v.substring(0, 60) + "...(" + String.valueOf(r).length() + ")";
                            once("gs#" + key, "sp.getString " + key + " = " + v);
                        }
                    });
        } catch (Throwable t) {
            MainHook.log("hook getString 失败: " + t);
        }
    }

    /** 一次性 dump 整个 prefs 文件（键名 + 值），并顺手把补丁写回返回的 map */
    private static void hookGetAll(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("android.app.SharedPreferencesImpl", cl, "getAll",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            Object r = p.getResult();
                            if (!(r instanceof Map)) return;
                            Map<?, ?> src = (Map<?, ?>) r;

                            Map<String, Object> out = new HashMap<>();
                            StringBuilder sb = new StringBuilder();
                            int n = 0;
                            for (Map.Entry<?, ?> e : src.entrySet()) {
                                String k = String.valueOf(e.getKey());
                                Object v = e.getValue();
                                Object nv = patchValue(k, v);
                                out.put(k, nv);
                                if (n++ < 60) {
                                    String sv = String.valueOf(v);
                                    if (sv.length() > 50) sv = sv.substring(0, 50) + "...";
                                    sb.append("\n    ").append(k).append(" = ").append(sv);
                                }
                            }
                            if (SCHEMA_LOGGED.compareAndSet(false, true)) {
                                MainHook.log("SP schema（" + out.size() + " 个键）:" + sb);
                            }
                            p.setResult(out);
                        }
                    });
        } catch (Throwable t) {
            MainHook.log("hook getAll 失败: " + t);
        }
    }

    private static Object patchValue(String k, Object v) {
        if (v instanceof Long) {
            long l = (Long) v;
            if (isEndKey(k) && l > 1000000000000L && l < MainHook.FOREVER) {
                once("pvL#" + k, "sp.getAll patch " + k + " " + l + "->FOREVER");
                return MainHook.FOREVER;
            }
        } else if (v instanceof Boolean) {
            if (Boolean.FALSE.equals(v) && isFlagKey(k)) {
                once("pvB#" + k, "sp.getAll patch " + k + " false->true");
                return Boolean.TRUE;
            }
        }
        return v;
    }

    // ---------------------------------------------------------------- 写

    private static void hookEditor(ClassLoader cl) {
        try {
            Class<?> editor = XposedHelpers.findClass("android.app.SharedPreferencesImpl$EditorImpl", cl);

            XposedHelpers.findAndHookMethod(editor, "putLong", String.class, long.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    Object k = p.args[0];
                    if (!(k instanceof String)) return;
                    String key = (String) k;
                    long v = (Long) p.args[1];
                    once("pl#" + key, "sp.putLong " + key + " " + v);
                    if (isEndKey(key) && v > 1000000000000L && v < MainHook.FOREVER) {
                        p.args[1] = MainHook.FOREVER;
                        once("plF#" + key, "sp.putLong " + key + " ->FOREVER");
                    }
                }
            });

            XposedHelpers.findAndHookMethod(editor, "putBoolean", String.class, boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    Object k = p.args[0];
                    if (!(k instanceof String)) return;
                    String key = (String) k;
                    boolean v = (Boolean) p.args[1];
                    once("pb#" + key, "sp.putBoolean " + key + " " + v);
                    if (isFlagKey(key) && !v) {
                        p.args[1] = Boolean.TRUE;
                        once("pbF#" + key, "sp.putBoolean " + key + " ->true");
                    }
                }
            });

            XposedHelpers.findAndHookMethod(editor, "putInt", String.class, int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    Object k = p.args[0];
                    if (!(k instanceof String)) return;
                    String key = (String) k;
                    once("pi#" + key, "sp.putInt " + key + " " + p.args[1]);
                    if (MainHook.DEEP && isFlagKey(key)) {
                        int v = (Integer) p.args[1];
                        if (v >= 0 && v < 4) p.args[1] = 4;
                    }
                }
            });

            XposedHelpers.findAndHookMethod(editor, "putString", String.class, String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    Object k = p.args[0];
                    if (!(k instanceof String)) return;
                    String key = (String) k;
                    Object v = p.args[1];
                    String sv = String.valueOf(v);
                    if (sv.length() > 60) sv = sv.substring(0, 60) + "...(" + String.valueOf(v).length() + ")";
                    once("ps#" + key, "sp.putString " + key + " = " + sv);
                }
            });

            MainHook.log("Editor 写入钩子就绪");
        } catch (Throwable t) {
            MainHook.log("hook Editor 失败: " + t);
        }
    }
}
