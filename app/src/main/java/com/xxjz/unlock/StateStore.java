package com.xxjz.unlock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 状态层：直接改写 ObjectBox 里的 ConfigEntity
 *  - boolean 语义字段 → true（激活位）
 *  - long 到期类字段 → 9999-12-31（永久）
 *  - int 套餐/等级（DEEP 模式）→ 推到 4
 * 全部走反射，不依赖 ObjectBox 的编译期类型。
 */
final class StateStore {

    private static final Pattern P_BOOL = Pattern.compile("(?i).*(vip|active|member|forever|pro|paid|valid|premium|unlock|perm).*");
    private static final Pattern P_LONG = Pattern.compile("(?i).*(expir|deadline|endtime|end_time|enddate|validuntil|valid_until).*");
    private static final Pattern P_LONG_SKIP = Pattern.compile("(?i).*(backup|sync|create|update|last|start|install).*");
    private static final Pattern P_INT = Pattern.compile("(?i).*(vip|level|type|state|status|kind|plan).*");

    private static volatile Object box;
    private static volatile Class<?> entityClass;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private StateStore() {
    }

    static void bindBox(Object b, Class<?> entity) {
        if (b == null) return;
        box = b;
        if (entity != null) entityClass = entity;
        MainHook.log("捕获 ConfigEntity Box: " + b.getClass().getName());
        scheduleRewrite();
    }

    static void bindEntity(Class<?> c) {
        if (c == null) return;
        entityClass = c;
        MainHook.log("绑定实体类: " + c.getName());
    }

    static void scheduleRewrite() {
        if (!RUNNING.compareAndSet(false, true)) return;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(300);
                    rewrite();
                } catch (Throwable e) {
                    MainHook.log("改写异常: " + e);
                } finally {
                    RUNNING.set(false);
                }
            }
        }, "xxjz-unlock-state");
        t.setDaemon(true);
        t.start();
    }

    static void rewrite() {
        Object b = box;
        Class<?> ec = entityClass;
        if (b == null) {
            MainHook.log("box 尚未捕获，跳过本次改写（等下次 onResume/boxFor）");
            return;
        }
        Method put = findPut(b.getClass());
        if (put == null) {
            MainHook.log("未找到 Box.put(Object)");
            return;
        }
        try {
            Method getAll = b.getClass().getMethod("getAll");
            Object r = getAll.invoke(b);
            List<?> all = (r instanceof List) ? (List<?>) r : null;
            if (all == null || all.isEmpty()) {
                if (ec == null) {
                    MainHook.log("库为空且无实体类，无法新建");
                    return;
                }
                Object e = ec.getDeclaredConstructor().newInstance();
                patchFields(e);
                put.invoke(b, e);
                MainHook.log("ConfigEntity 为空 → 新建并写入永久状态");
            } else {
                for (Object e : all) {
                    patchFields(e);
                    put.invoke(b, e);
                }
                MainHook.log("已改写 " + all.size() + " 条 ConfigEntity 记录");
            }
        } catch (Throwable t) {
            MainHook.log("rewrite 失败: " + t);
        }
    }

    private static Method findPut(Class<?> boxClass) {
        for (Method m : boxClass.getMethods()) {
            if (!m.getName().equals("put")) continue;
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 1) continue;
            if (ps[0] == Object.class || (entityClass != null && ps[0].isAssignableFrom(entityClass))) {
                return m;
            }
        }
        return null;
    }

    private static void patchFields(Object e) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> k = e.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            Field[] fs;
            try {
                fs = k.getDeclaredFields();
            } catch (Throwable t) {
                continue;
            }
            for (Field f : fs) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                String nm = f.getName();
                String tn = f.getType().getName();
                try {
                    if ("boolean".equals(tn)) {
                        if (P_BOOL.matcher(nm).matches() && !f.getBoolean(e)) {
                            f.setBoolean(e, true);
                            sb.append(nm).append("=true; ");
                        }
                    } else if ("long".equals(tn)) {
                        if (P_LONG.matcher(nm).matches() && !P_LONG_SKIP.matcher(nm).matches()) {
                            long old = f.getLong(e);
                            if (old != MainHook.FOREVER) {
                                f.setLong(e, MainHook.FOREVER);
                                sb.append(nm).append(' ').append(old).append("->").append(MainHook.FOREVER).append("; ");
                            }
                        }
                    } else if ("int".equals(tn) && MainHook.DEEP) {
                        if (P_INT.matcher(nm).matches()) {
                            int old = f.getInt(e);
                            if (old < 4) {
                                f.setInt(e, 4);
                                sb.append(nm).append(' ').append(old).append("->4; ");
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        if (sb.length() > 0) {
            MainHook.log("patch " + e.getClass().getSimpleName() + " { " + sb.toString().trim() + " }");
        }
    }
}
