# 小星会员永久化 · LSPosed 模块

目标应用：**小星记账** `com.cxincx.xxjz`（4.0.8）
形态：经典 Xposed API 模块（LSPosed / EdXposed / 太极 均可加载），**不改目标 APK**。

## 它做了什么

| 层 | 做法 | 解决什么 |
|---|---|---|
| 类发现 | hook `ClassLoader.loadClass` / `Class.forName` | 目标被百度加固，自有类运行时才解密加载，必须"出现即挂" |
| 判定层 | `com.cxincx.xxjz.z.*` 里所有返回 `boolean` 的方法 → `true` | 各功能门禁、`VipDialog` 前置判断 |
| 到期层 | 同范围内返回 `long` 且值像时间戳 → `9999-12-31` | 到期判定、剩余天数 |
| 状态层 | 反射改写 ObjectBox 的 `ConfigEntity`：激活位 `true`、到期 `9999-12-31` | 本地持久化状态（重启不回退） |
| 弹窗层 | `VipDialog.show*` 直接吞掉 | 试用用尽弹窗 |
| 兜底层 | `SharedPreferences.getBoolean/getLong` 对 vip/active/expire 键给永久值 | 万一状态存在 SP |
| 刷新层 | 首个 `onResume` + `VipActivity` 进入时改写并 `recreate()` 一次 | 界面立刻显示「已激活 / 永久」 |

关键点：**状态 + 判定 + 到期必须三层同时按**。只改库会被 App 的再校验覆盖，只 hook 判定则重启失效。

## 目录

```
xxjz_lsposed/
├─ settings.gradle / build.gradle / gradle.properties
├─ app/
│  ├─ build.gradle
│  ├─ proguard-rules.pro
│  └─ src/main/
│     ├─ AndroidManifest.xml          # xposedmodule / xposedscope
│     ├─ assets/xposed_init           # 入口类
│     ├─ res/values/{strings,arrays}.xml
│     └─ java/com/xxjz/unlock/
│        ├─ MainHook.java             # 入口 + 常量（DUMP_ONLY / DEEP / FOREVER）
│        ├─ ClassWatcher.java         # 类加载拦截（加固必需）
│        ├─ MemberPatcher.java        # boolean / long / dialog
│        ├─ StateStore.java           # ObjectBox ConfigEntity 改写（反射）
│        ├─ BoxWatcher.java           # 拿 Box 实例
│        ├─ Lifecycle.java            # onResume 触发 + UI 刷新
│        └─ Prefs.java                # SP 兜底
└─ .github/workflows/build.yml        # 云编译
```

## 构建（三选一）

### A. Android Studio（最稳）
1. 装 Android Studio + SDK 34；
2. `File → Open` 选 `xxjz_lsposed` 目录；
3. 等待 Gradle 同步（会自动去 `api.xposed.info` 拉 Xposed API）；
4. `Build → Build Bundle(s)/APK(s) → Build APK(s)`；
5. 产物：`app/build/outputs/apk/debug/app-debug.apk`。

### B. 命令行
```bash
cd xxjz_lsposed
gradle wrapper --gradle-version 8.7      # 首次生成 wrapper
./gradlew :app:assembleRelease
# 产物 app/build/outputs/apk/release/app-release.apk（已用 debug 签名，可直接装）
```
> 本机若没有 JDK：装 Temurin 17 即可（Android Studio 自带 JBR 也能用）。

### C. GitHub Actions 云编译（本机没有 Android SDK 时用这个）
1. 把 `xxjz_lsposed` 推到一个 GitHub 仓库；
2. `Actions → build-apk → Run workflow`；
3. 跑完在 Artifacts 里下 `xxjz-unlock-apk`。

## 安装与启用

```bash
adb install -r app-release.apk
# 或直接把 APK 拷进手机点击安装
```

1. 打开 **LSPosed 管理器 → 模块**，确认「小星会员永久化」已列出并**启用**；
2. 点进模块 → **作用域** 只勾 `小星记账`（`com.cxincx.xxjz`）；
3. **强制停止** 小星记账（`adb shell am force-stop com.cxincx.xxjz`），再打开；
4. 进「我的 → 产品待激活/已激活」，应显示 **已激活 / 于 xxxx 永久激活**。

> 启用模块后不重启手机也行，但目标 App 必须**完全杀掉再启动**，Xposed 才会注入。

## 首次运行务必先"只读"确认字段名

加固后的字段名可能被混淆，默认的正则只能覆盖语义化命名。第一次请：

1. 打开 `MainHook.java`，把 `DUMP_ONLY` 改成 `true`（此时不改任何东西，只打印）；
2. 重新构建安装 → 进一次激活页；
3. 看日志：
   ```bash
   adb logcat -s Xposed | findstr XXJZ-UNLOCK
   # 或 LSPosed 管理器 → 日志 → 搜 XXJZ-UNLOCK
   ```
4. 日志里会打出 `ConfigEntity` 的真实字段：`field xxx : boolean`、`field yyy : long`、以及当前值；
5. 把字段名填进 `StateStore` 的三个正则（`P_BOOL` / `P_LONG` / `P_INT`），或直接把 `DEEP` 打开；
6. `DUMP_ONLY` 改回 `false`，重建安装。

典型日志（正常生效时）：

```
XXJZ-UNLOCK: attach pkg=com.cxincx.xxjz process=com.cxincx.xxjz first=true
XXJZ-UNLOCK: ClassWatcher 就绪
XXJZ-UNLOCK: 已处理 com.cxincx.xxjz.entity.ConfigEntity，挂载 7 处
XXJZ-UNLOCK: 捕获 ConfigEntity Box: io.objectbox.Box
XXJZ-UNLOCK: patch ConfigEntity { isVip=true; expireAt 1735660800000->253402300799000; }
XXJZ-UNLOCK: bool VipActivity#isVip false->true
XXJZ-UNLOCK: VipActivity 已刷新一次
```

## 参数（`MainHook.java`）

| 常量 | 默认 | 说明 |
|---|---|---|
| `FOREVER` | `253402300799000L` | 9999-12-31 23:59:59 UTC；别用 `Long.MAX_VALUE`（部分金额/时间格式化会异常） |
| `DUMP_ONLY` | `false` | `true` = 只打印结构，不改行为。**第一次必须先用它** |
| `DEEP` | `false` | `true` = 连 `int` 型 level/type/state 也推到 4（确认含义后再开） |

## 排错

| 现象 | 原因 / 处理 |
|---|---|
| 模块列表里没有它 | APK 没装成功，或 `AndroidManifest` 的 `xposedmodule` meta-data 被改；重装 |
| 模块启用但日志无 `attach` | 作用域没勾 `com.cxincx.xxjz`；或目标 App 没被完全杀掉；或 LSPosed 未启用 Zygisk |
| 有 `attach` 但没有任何 `已处理 com.cxincx…` | 类名被壳改过 → 用 `DUMP_ONLY` 看实际类名，把 `MainHook.VIP_SCOPE`（默认 `com.cxincx.xxjz.z.`）放宽 |
| 状态改了但界面还是待激活 | 说明 UI 走的是另一条判定（比如 `int` 套餐类型 / `String` 密钥校验结果）→ 开 `DEEP`，或看日志里哪个 boolean 没被调用 |
| 重启后回退 | 说明启动时会重新校验并覆盖 → 三层 hook（状态+判定+到期）必须同时启用，确认日志里 `bool … false->true` 有出现 |
| 打开就闪退 | 先关 `DEEP`；仍闪退就 `DUMP_ONLY=true` 跑一遍，确认是哪个类被 hook 坏（日志最后一条即嫌疑） |
| 自动记账开关报"密钥校验失败" | 该路径会再校验密钥 → 已由 boolean hook 覆盖；若仍报错，说明它比对的是字符串/订单号，需在日志里找对应方法补 hook |

## 验收清单

1. 「我的」显示 `已激活` + `于 <日期> 永久激活`，无「到期时间：xxx」倒计时；
2. 新建第 2 个账本 / 云备份 / 记账提醒 / 安全锁 / 收入大类 / 商家分组 / 语音记账不限次，全部可用；
3. 杀进程重启、飞行模式重启，状态不回落；
4. 打开一次「自动记账」开关（会再验密钥）不弹待激活。

## 与 Frida 路线的关系

`F:\AI\tools\xxjz_vip_unlock.js` 是同一套逻辑的动态版（临时验证用）；本模块把六层兜底固化进 Xposed，开机自启、不用常挂 frida-server，也不受壳的端口/进程名检测影响。
两者可以互补：先用 Frida 版 `inspect` 摸清字段，再把结论填进本模块的正则。
