package com.xxjz.unlock;

import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * SharedPreferences 兜底：万一 App 把 "已激活/到期" 存在 SP 而不是 ObjectBox，
 * 读的时候直接给永久值。
 */
final class Prefs {

    private static final Pattern P_BOOL = Pattern.compile("(?i).*(vip|active|member|forever|pro|paid|perm).*");
    private static final Pattern P_LONG = Pattern.compile("(?i).*(expir|deadline|endtime|end_time|valid).*");

    private Prefs() {
    }

    static void install(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("android.app.SharedPreferencesImpl", cl,
                    "getBoolean", String.class, boolean.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            Object k = p.args[0];
                            Object r = p.getResult();
                            if (!(k instanceof String)) return;
                            if (r instanceof Boolean && !((Boolean) r) && P_BOOL.matcher((String) k).matches()) {
                                MainHook.log("sp " + k + " false->true");
                                p.setResult(Boolean.TRUE);
                            }
                        }
                    });
        } catch (Throwable t) {
            MainHook.log("hook sp.getBoolean 失败: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod("android.app.SharedPreferencesImpl", cl,
                    "getLong", String.class, long.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            Object k = p.args[0];
                            Object r = p.getResult();
                            if (!(k instanceof String)) return;
                            if (!(r instanceof Long)) return;
                            long v = (Long) r;
                            if (P_LONG.matcher((String) k).matches() && v > 1000000000000L && v < MainHook.FOREVER) {
                                MainHook.log("sp " + k + " " + v + "->FOREVER");
                                p.setResult(MainHook.FOREVER);
                            }
                        }
                    });
        } catch (Throwable t) {
            MainHook.log("hook sp.getLong 失败: " + t);
        }

        MainHook.log("Prefs 就绪");
    }
}
