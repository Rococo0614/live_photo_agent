gif 编码sdk 使用maven的方式接入

 1.  在 添加 repositories { } 中添加

     maven {
            url = uri("[<span style="color:#00B540">http://maven2.vivo.xyz:8081/nexus/content/repositories/lib/</span>](http://maven2.vivo.xyz:8081/nexus/content/repositories/lib/)")
            isAllowInsecureProtocol = true
        }

  如：

![image-2026-5-15_14-45-23.png](https://veditor.vivo.xyz/api/v1/attachment/file/oSjjOawHOZqTpxJW3F2d9Fwa32wZfyU-OVOZxmlWFK3e2o6xFr7MwiHOmLu52bW- "image-2026-5-15_14-45-23.png")

   

2. 然后在接入的build.gradle 添加  implementation ("com.vivo.gifcodec:temp_gifcodec:1.0.7.0")   就可以使用了

# GifProcessUtils 使用demo

GifProcessUtils <span style="color:#C77DBB">mGifProcessUtils</span>;
<span style="color:#CF8E6D">public void </span><span style="color:#56A8F5">testGifEncode</span>(){
    <span style="color:#CF8E6D">if</span>(<span style="color:#C77DBB">mGifProcessUtils </span>!= <span style="color:#CF8E6D">null</span>){
        <span style="color:#C77DBB">mGifProcessUtils</span>.release();
        <span style="color:#C77DBB">mGifProcessUtils </span>= <span style="color:#CF8E6D">null</span>;
    }
    <span style="color:#C77DBB">mGifProcessUtils </span>= <span style="color:#CF8E6D">new </span>GifProcessUtils();
    <span style="color:#C77DBB">mGifProcessUtils</span>.setGifSize(<span style="color:#2AACB8">180</span>, <span style="color:#2AACB8">320</span>);  <span style="color:#7A7E85">// 设置输出gif的文件分辨率</span>
    <span style="color:#C77DBB">mGifProcessUtils</span>.setFilePath(<span style="color:#C77DBB">mFilePath</span>);  <span style="color:#7A7E85">// 设置视频文件路径</span>
    <span style="color:#C77DBB">mGifProcessUtils</span>.setEndTime(<span style="color:#2AACB8">3000</span>);     <span style="color:#7A7E85">// 毫秒  设置结束时间</span>
    <span style="color:#C77DBB">mGifProcessUtils</span>.setTargetFps(<span style="color:#2AACB8">20</span>);    <span style="color:#7A7E85">// 设置导出的gif编码帧率</span>
    <span style="color:#C77DBB">mGifProcessUtils</span>.setGifOutputPath(<span style="color:#6AAB73">"/sdcard/DCIM/aTestGif_005.gif"</span>);  <span style="color:#7A7E85">// 输出gif文件的路径</span>
    <span style="color:#C77DBB">mGifProcessUtils</span>.setVideoToGifCallback(<span style="color:#CF8E6D">new </span>GifProcessUtils.VideoToGifCallback() {   <span style="color:#7A7E85">// 设置回调监听</span>
        <span style="color:#B3AE60">@Override</span>
        <span style="color:#CF8E6D">public void </span><span style="color:#56A8F5">onStart</span>() {  <span style="color:#7A7E85">// 开始回调</span>

        }

        <span style="color:#B3AE60">@Override</span>
        <span style="color:#CF8E6D">public void </span><span style="color:#56A8F5">onFinish</span>() {  <span style="color:#7A7E85">// 结束回调</span>
            <span style="color:#C77DBB">mGifProcessUtils</span>.release();
            <span style="color:#C77DBB">mGifProcessUtils </span>= <span style="color:#CF8E6D">null</span>;
        }

        <span style="color:#B3AE60">@Override</span>
        <span style="color:#CF8E6D">public void </span><span style="color:#56A8F5">onError</span>(<span style="color:#CF8E6D">int </span>errorCode, String errorMsg) {  <span style="color:#7A7E85">// 错误回调</span>
         Logger.e(<span style="color:#C77DBB">TAG</span>,<span style="color:#6AAB73">"mGifProcessUtils onError errorCode = " </span>+ errorCode + <span style="color:#6AAB73">", errorMsg = " </span>+ errorMsg);
        }
    });
    <span style="color:#C77DBB">mGifProcessUtils</span>.startVideoToGif(); <span style="color:#7A7E85">//启动视频转gif</span>
}

# GifProcessUtils 接口文档

> 包名：`com.vivo.gifcodec`

GifProcessUtils 是 GIF 编解码工具类，提供视频转 GIF、GIF 裁剪、GIF 缩放等功能。

---

## 常量

| 常量名 | 类型 | 说明 |
| --- | --- | --- |
| `SDKBuildInfo` | `String` | 构建包信息 |
| `SDKVersion` | `String` | SDK 版本号 |

---

## 构造方法

### `GifProcessUtils()`

创建 GifProcessUtils 实例，构造时会打印 SDK 版本和构建信息日志。

---

## 实例方法

### `setVideoToGifCallback(VideoToGifCallback callback)`

设置视频转 GIF 的回调监听。

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `callback` | `VideoToGifCallback` | 回调接口实例 |

---

### `setTargetFps(int targetFps)`

设置目标帧率。

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `targetFps` | `int` | 目标帧率（fps） |

---

### `setFilePath(String filePath)`

设置视频源文件路径。

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `filePath` | `String` | 视频文件路径 |

> 与 `setFileDescriptor` 二选一设置。

---

### `setFileDescriptor(FileDescriptor fileDescriptor)`

设置视频源文件描述符。

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `fileDescriptor` | `FileDescriptor` | 视频文件描述符 |

> 与 `setFilePath` 二选一设置。

---

### `setGifOutputPath(String gifOutputPath)`

设置输出的 GIF 图片路径。

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `gifOutputPath` | `String` | GIF 输出文件路径 |

> **必须在 **`startVideoToGif()`** 之前调用**，否则会触发 `onError(-11, ...)` 回调。

---

### `setEndTime(long endTime)`

设置解析视频的结束时间。

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `endTime` | `long` | 结束时间，单位：毫秒（ms） |

---

### `setStartTime(long startTime)`

设置解析视频的开始时间。

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `startTime` | `long` | 开始时间，单位：毫秒（ms） |

---

### `setGifSize(int w, int h)`

设置导出 GIF 的宽高。

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `w` | `int` | GIF 宽度（像素），默认 270 |
| `h` | `int` | GIF 高度（像素），默认 480 |

---

### `startVideoToGif()`

开始执行视频转 GIF。调用后会依次进行视频抽帧和 GIF 编码。

**调用前提：**

- 必须先通过 `setFilePath` 或 `setFileDescriptor` 设置视频源
- 必须先通过 `setGifOutputPath` 设置输出路径

**执行流程：**

1. 停止并释放上一次的 `VideoFrameExtractor` 和 `GifEncoder`
2. 校验输出路径，为空则回调 `onError(-11, ...)`
3. 配置并启动视频抽帧，按 RGBA 格式解码
4. 每帧解码后通过 `GifEncoder` 编码写入 GIF
5. 抽帧完成后自动释放编码器并回调 `onFinish()`

---

### `release()`

释放资源，停止视频抽帧和 GIF 编码。

**行为：**

- 设置释放标记，中断正在进行的抽帧流程
- 释放 `GifEncoder` 和 `VideoFrameExtractor`
- 清空 RGBA 数据缓冲区
- 置空回调引用

> 建议在 Activity/Fragment 销毁时调用，避免内存泄漏。

---

## 回调接口：VideoToGifCallback

![image](http://localhost:63342/markdownPreview/1756939847/java/com/vivo/gifcodec?_ijt=q8cu4qhice8m3th078kvs6mrav "image")

`public interface VideoToGifCallback `

视频转 GIF 过程的回调接口。

### `void onStart()`

GIF 编码开始时回调。在第一帧数据写入编码器时触发。

### `void onFinish()`

GIF 编码完成时回调。视频抽帧从头到尾全部完成时触发。

### `void onError(int errorCode, String errorMsg)`

发生错误时回调。

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `errorCode` | `int` | 错误码。`-10`：GifEncoder 异常；`-11`：输出路径为空；其他：视频抽帧错误 |
| `errorMsg` | `String` | 错误描述信息 |

---

## 静态方法

### `TrimGif(String inputPath, String outputPath, Rect rect)`

裁剪 GIF 图片，按指定矩形区域截取。

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `inputPath` | `String` | 输入 GIF 文件路径 |
| `outputPath` | `String` | 输出 GIF 文件路径 |
| `rect` | `Rect` | 裁剪区域，为 `null` 时保留原始尺寸 |

**行为：**

- 读取输入 GIF 的所有帧
- 按帧间隔计算每帧延迟
- 逐帧裁剪并编码到输出文件
- 自动释放资源

---

### `TrimGifCondition(String inputPath, String outputPath, Rect rect, int reSample, float reScale)`

按条件裁剪 GIF 图片，支持缩放和抽帧。

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `inputPath` | `String` | 输入 GIF 文件路径 |
| `outputPath` | `String` | 输出 GIF 文件路径 |
| `rect` | `Rect` | 裁剪区域，为 `null` 时使用原始尺寸 |
| `reSample` | `int` | 抽帧间隔（如 2 表示每隔 1 帧取 1 帧） |
| `reScale` | `float` | 缩放比例，不可为 0.0 |

**行为：**

- 按 `reScale` 缩放裁剪区域和输出尺寸
- 按 `reSample` 间隔跳帧，减少输出帧数
- 抽帧时延迟按 `delayMs * reSample` 计算
- 自动释放资源

---

### `resizeGif(String inputPath, String outputPath, float scale)`

缩放 GIF 图片尺寸。

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `inputPath` | `String` | 输入 GIF 文件路径 |
| `outputPath` | `String` | 输出 GIF 文件路径 |
| `scale` | `float` | 缩放比例，必须大于 0 |

**行为：**

- 读取原始 GIF 尺寸，按 `scale` 计算目标宽高
- 使用 `OCTREE_QUANTIZER` 和 `NO_DITHER` 模式编码
- 逐帧缩放并编码到输出文件
- 自动释放资源