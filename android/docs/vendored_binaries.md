# 随仓入库的二进制：来源、用途与校验

本工程刻意把 SDK 的 AAR 和两个 `.so` 提交进 git。原因是这些制品在任何公共
Maven 仓库都取不到，不入库则 clone 后无法构建。代价是仓库多约 22M 二进制。

修改任何一项后，请同步更新下表的 md5。

## 1. AAR（`app/libs/`）

| 文件 | 大小 | md5 |
|---|---|---|
| `videoeditorsdk-v4.9.0.3-SNAPSHOT-release.aar` | 6.5M | `502e6dfe6f7d5f5bd123b2e60c196299` |
| `mediaeffectsdk-gallery-1.3.2.2-release.aar` | 7.1M | `16a44923f548935929ddcc01f5655b74` |

来源：vivo 内部提供的 SDK 包（非公开发布）。仓库根目录曾存在 `编辑sdk/`、
`特效sdk/` 两个目录存放同一批文件，经 md5 比对与 `app/libs/` 下**逐字节相同**，
属重复副本，整理时已不再保留。

引入方式见 `app/build.gradle.kts`：

```kotlin
implementation(fileTree("libs") { include("*.aar") })
```

### 这两个 AAR 未声明的外部依赖

AAR 既没打包这些依赖，也没有 POM 声明依赖关系，只能靠字节码常量池反查。
缺任何一个都会在运行期抛 `NoClassDefFoundError`，且报错点离真正原因很远
（例如缺 PAG 时崩在 `VideoRenderThread`，看起来像渲染问题）。

| 依赖 | 触发路径 | 说明 |
|---|---|---|
| `com.google.code.gson:gson:2.11.0` | `VMEditor.init()` | 缺失则 init 抛 `Lcom/google/gson/Gson;` |
| `com.tencent.tav:libpag:4.2.41` | `TimelineMotionEffect.onRenderFrame()` | 缺失则渲染线程崩 `Lorg/libpag/PAGPlayer;`。**注意 4.4.6 在 Maven Central 上不存在** |
| `vivo.util.VLog` | SDK 全部日志调用 | 各方都不提供，本工程自建 stub，见下 |

## 2. `vivo.util.VLog` stub（`app/src/main/java/vivo/util/VLog.java`）

不是二进制，但同属「补齐 SDK 缺失依赖」，一并记录。

`videoeditorsdk` 链接了 `vivo.util.VLog`，但：

- 两个 AAR 的 `classes.jar` 都不含该类；
- 设备 `/system/framework/vivo-framework.jar` 解包后在 `classes.dex` 中
  **搜不到** `vivo/util/VLog` 字符串（该 jar 确实在 BOOTCLASSPATH 上，
  但里面没有这个类）。

因其不在 bootclasspath 上，App 自带同名类不会与系统冲突。stub 的 8 个方法
签名是从 SDK 字节码常量池反查出的**实际调用签名**，不是猜的，勿随意增删：

```
v/d/i/w/e (String, String) -> int
i/w/e (String, String, Throwable) -> int
```

## 3. `.so`（`app/src/main/jniLibs/arm64-v8a/`）

| 文件 | 大小 | md5 | 来源 |
|---|---|---|---|
| `libmediacore4g_vivo_public.so` | 8.9M | `0693c8f3c6bf6e25a9f7173803b17432` | 从测试机 `/system/lib64/` 拉取，**与设备上的文件逐字节相同** |
| `libvivolog.so` | 8.0K | `bc511b94b1ba30c4f39c7fb5504284be` | **来源未确认**，设备 `/system/lib64`、`/vendor/lib64`、`/system_ext/lib64` 下均无同名文件，也不来自两个 AAR |

两个 AAR 自带的 native 库（`libVideoEditorSDK_jni.so`、`libmediaeffectsdk.so`
等）由 AAR 的 `jni/` 目录自动打包，**不在** `jniLibs/` 下，无需手工管理。

### 待办：`libmediacore4g_vivo_public.so` 很可能是多余的 8.9M

崩溃日志中该进程的 native 库搜索路径为：

```
nativeLibraryDirectories=[
  /data/app/~~.../lib/arm64,
  /data/app/~~.../base.apk!/lib/arm64-v8a,
  /system/lib64,          <-- 已包含
  /system_ext/lib64
]
```

`/system/lib64` 本就在搜索路径内，而该文件与 `/system/lib64` 下的版本完全一致，
因此打进 APK 的这份副本推测是冗余的。

本轮**未做验证，保持现状**。后续可按此步骤确认：从 `jniLibs/` 移除该文件 →
重跑 `HeadlessService` 三条通路 → 若全部仍判定「无头渲染可行」，即可确认冗余并
删除；失败则还原。

注意这个结论只对**该测试机**成立。若换成没有预置这个库的机型，仍需随包携带，
删除前应先确认目标机型覆盖范围。
