# vivo 视频编辑 SDK 离屏（headless）渲染可行性验证

验证日期：2026-08-19
设备：vivo PD2547 / V2547A，Android 16（API 36），arm64-v8a
SDK：`videoeditorsdk-v4.9.0.3-SNAPSHOT`、`mediaeffectsdk-gallery-1.3.2.2`

## 1. 为什么要做这件事

端侧架构是「LLM 决策 + SDK 执行」。如果 SDK 的导出能力必须依附一个可见的
Activity / Window，那么执行层就只能跑在前台 UI 里，Agent 无法在后台批量处理，
整个架构需要重做。因此「SDK 能否离屏渲染」是继续开发前必须先关掉的架构风险。

## 2. 结论

**可以完全离屏运行，包括完整时间线编辑。**

验证载体是一个前台 Service（`com.vivo.lpa.HeadlessService`），全程
**不创建 Activity、不创建可见 View、不持有 Window**。三条通路实测：

| 通路 | 能力范围 | init/start 返回 | 回调 | 产物（ffprobe 实测） |
| --- | --- | --- | --- | --- |
| `VMVideoConverter` | 整文件转码，构造器连 Context 都不需要 | 0 | `onCompletion` | 5.03s / 151 帧 / 1080x1920 / 8.38MB |
| `VideoExportEngine` | 整文件转码 | 0 | `onCompletion` | 5.03s / 151 帧 / 1920x1080 / 17.63MB |
| `VMEditor` | **完整时间线编辑**（裁剪 / 滤镜 / 转场 / 贴纸） | 0 | `onCompletion` | **1.03s / 31 帧** / 1080x1920 / 1.74MB |

输入为 5.00s / 150 帧 / 1920x1080 的 Live Photo 内嵌视频。

`VMEditor` 那一行是关键证据：请求的是 `startTimeMs=0, durationMs=1000` 的裁剪，
产物精确为 1.03s / 31 帧，说明**编辑参数真实生效**，不是把源文件拷了一份。

### 2.1 最重要的一条发现

`VMEditor.init(Context, VMResource, VMEditorView)` 的签名要求一个
`VMEditorView`，但实测**只需 `new VMEditorView(context)`，无需 addView 到任何
窗口**，`init` 即返回 0，`VideoFactory$VideoRenderThread` 正常启动并逐帧渲染。

也就是说 `VMEditorView` 在导出路径上只作为渲染目标的持有者，不要求被布局或可见。
这条无法从签名推断，只能实测。

### 2.2 前台 Service 的必要性

Android 8+ 禁止后台启动普通 Service，`adb shell am start-service` 会直接报
`Error: app is in background uid null`。因此验证载体必须
`startForeground()`。**这与 UI 依赖无关** —— 前台通知只是为了让进程不被后台
限制杀掉，Android 14+ 还需要在 manifest 声明 `foregroundServiceType`
（此处用 `dataSync`）并申请对应权限。

## 3. SDK 未声明的依赖（三个）

两个 AAR 既没打包这些依赖，也没有 POM 声明，全靠字节码常量池反查得出。
**任何一个缺失都会让导出路径抛 `NoClassDefFoundError`。**

### 3.1 `vivo.util.VLog`

最隐蔽的一个。SDK 里 7 个类引用它，但：

- 两个 AAR 的 `classes.jar` 均不含
- 设备 `/system/framework/vivo-framework.jar` 解包后 dex 中也**不存在**该类
  （已用字符串检索确认，并非 hidden-API 拦截 —— logcat 无任何 hidden-api
  拒绝记录，且 `hidden_api_policy` 与此无关，改动它不产生影响）

由于它不在 bootclasspath 上，App 自带同名类不会与系统冲突。解决方式是自建
stub 转发到 `android.util.Log`，见 `app/src/main/java/vivo/util/VLog.java`。

实际被调用的 8 个签名（从字节码常量池反查，**勿随意增删**）：

```
v(String, String):I
d(String, String):I
i(String, String):I          i(String, String, Throwable):I
w(String, String):I          w(String, String, Throwable):I
e(String, String):I          e(String, String, Throwable):I
```

### 3.2 `com.google.code.gson:gson`

`VMEditor.init()` 路径依赖。缺失时 init 抛
`NoClassDefFoundError: Lcom/google/gson/Gson;`。

### 3.3 `com.tencent.tav:libpag:4.2.41`

`mediaeffectsdk` 的 `TimelineMotionEffect.onRenderFrame` → `initEngine`
会**无条件**初始化 PAG 引擎，即使 `VMResource` 里没有任何贴纸/模板也一样，
所以绕不开。缺失时渲染线程崩溃：

```
FATAL EXCEPTION: VideoRenderThread
NoClassDefFoundError: Lorg/libpag/PAGPlayer;
  at ...EffectEngineImpl.init
  at ...TimelineMotionEffect.initEngine / onRenderFrame
  at ...VideoFactory$VideoRenderThread.renderFrame
```

版本注意：**`4.4.6` 在 Maven Central 上不存在**，会 `Could not find`。
实测可用 `4.2.41`。SDK 引用的 PAG API（`PAGComposition.Make`、
`PAGPlayer`、`PAGSurface.FromTexture`、`PAGImage.FromTexture` 等）属 4.x
通用面，未使用版本敏感接口。

设备 `/system/lib64` 下有 vivo 改名的 `libpag_n1..n4.so`，但**没有 Java 层**，
无法直接复用，仍需引入 Maven 依赖。

## 4. 回调语义陷阱

`VMExportListener.onEncodingDone(boolean, int)` 的第一个布尔是
**`isError` 而不是 `success`**。

依据：SDK 内 `VideoConverter$1.onEncodingDone` 的日志字符串常量为
`"onEncodingDone iserror"`。

误读会把成功导出判成失败 —— 实测正常完成时回调为 `(false, 0)`，
若按 success 解读就会得出「导出失败」的错误结论。判定条件应为
`!isError && code == 0`，并且**仍需校验产物**（有回调不等于产物可用）。

各 listener 的完整签名见 `sdk_api_probe_V2547A.txt`：

- `VMConverterListener`: `onCompletion()` / `onError(int)` / `onProgressChanged(int, int)`
- `VMExportListener`: `onEncodingDone(boolean, int)` / `onEncodingProgress(int, int)`
- `VideoExportEngine.ExportListener`（嵌套接口）:
  `onCompletion(VideoConverter)` / `onError(int)` / `onProgressChanged(int, int, long)`

## 5. Live Photo 拆包

vivo Live Photo 是 `JPEG + MP4` 顺序拼接的单文件。拆包做法：

1. 扫描 `ftyp` box 标识，MP4 起点 = `indexOf("ftyp") - 4`
2. 校验该起点前两字节为 JPEG EOI（`FF D9`），确认确实是拼接边界而非误命中

实测样本 `/sdcard/DCIM/Camera/115.jpg`：总长 18,125,695 字节，
MP4 起点 806,721，EOI 紧邻校验通过，切出的 MP4 为 17,318,974 字节且可正常解码。

## 6. 复现步骤

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=$HOME/Android/Sdk

# 构建 + 安装（脚本会自动定位并点击安装确认弹窗，非坐标硬编码）
./gradlew :app:assembleDebug
./tools/install_and_probe.sh <device-serial>

# 跑离屏验证（注意必须是 start-foreground-service）
adb -s <device-serial> logcat -c
adb -s <device-serial> shell am start-foreground-service \
    -n com.vivo.lpa/.HeadlessService
sleep 120
adb -s <device-serial> logcat -d -s LPA_HEADLESS
```

产物校验（在设备上位于 `files/out_{converter,engine,editor}.mp4`）：

```bash
adb -s <serial> exec-out run-as com.vivo.lpa cat files/out_editor.mp4 > out_editor.mp4
ffprobe -v error -show_entries format=duration \
        -show_entries stream=width,height,nb_frames -of default=nw=1 out_editor.mp4
```

`install_and_probe.sh` 有一个已修的坑值得注意：原先用「包是否已安装」作为安装
成功判据，但旧版本存在会让判断在弹窗点完之前就通过，导致实际装的仍是旧 APK
且表现为「代码改了没生效」。现已改为先 `uninstall` 再安装。

## 7. 尚未验证

- 多 clip 拼接，以及 `VMFilterData` / `VMTransitionData` 的合法取值域
- `VMEditor` 在无 `Looper` 的纯工作线程上能否 init（当前实现把 View 构造与
  `init` 都 post 到主线程 Looper，未测试更宽松的条件）
- 长视频 / 高分辨率下的内存与耗时表现
