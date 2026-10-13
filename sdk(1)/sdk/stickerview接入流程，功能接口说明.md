stickerview sdk 使用maven的方式接入

 1.  在 添加 repositories { } 中添加

     maven {
            url = uri("[<span style="color:#00B540">http://maven2.vivo.xyz:8081/nexus/content/repositories/lib/</span>](http://maven2.vivo.xyz:8081/nexus/content/repositories/lib/)")
            isAllowIsecureProtocol = true
        }

  如：

![image-2026-5-15_14-45-23.png](https://veditor.vivo.xyz/api/v1/attachment/file/UFgwVh4Woh0tGzBZ_EIEBWZWV51mX2h6pewVGlkcDk3xSSiKAus7cxsHe_TV6BQ7 "image-2026-5-15_14-45-23.png")

   

2. 然后在接入的build.gradle 添加   implementation(files("libs/sbs-StickerView-release-1.0.4.0.aar")) 就可以使用了

# stickerview 使用demo

SBSMattingTextureView view = <span style="color:#CF8E6D">new </span>SBSMattingTextureView(<span style="color:#CF8E6D">this</span>, SBSMattingView.<span style="color:#C77DBB">PLAYER_TYPE_MEDIA_PLAYER</span>);
view.setLooping(<span style="color:#CF8E6D">false</span>);

view.setOnCompletionListener(() -> {
    <span style="color:#7A7E85">// 视频播放完成（非循环）时的回调</span>
    VLog.i(<span style="color:#C77DBB">TAG</span>, <span style="color:#6AAB73">" SBSMattingView 播放完成"</span>);
});
view.setOnPreparedListener(()->{
    VLog.i(<span style="color:#C77DBB">TAG</span>, <span style="color:#6AAB73">" SBSMattingView OnPrepared 完成"</span>);

    <span style="color:#C77DBB">view</span>.play();
});

view.setOnSeekCompleteListener(()->{
    VLog.i(<span style="color:#C77DBB">TAG</span>, <span style="color:#6AAB73">" SBSMattingView SeekComplete 完成 "</span>);
});
view.setOnFirstFrameRenderListener(() -> {
    VLog.i(<span style="color:#C77DBB">TAG</span>, <span style="color:#6AAB73">" SBSMattingView 渲染第一帧"</span>);  <span style="color:#7A7E85">// 这里去做隐藏静态图片</span>

});
view.setOnPauseCompleteListener(() -> {
    VLog.i(<span style="color:#C77DBB">TAG</span>, <span style="color:#6AAB73">" SBSMattingView pause"</span>);
});

<span style="color:#C77DBB">mainContainer</span>.addView(view);
view.bringToFront(); <span style="color:#7A7E85">// 显式移至最前，防止被布局中已有的其他 View 遮挡</span>
<span style="color:#7A7E85">// 确保在视图添加到窗口后再设置 URI</span>
view.post(() -> {
    <span style="color:#C77DBB">view</span>.setStickerUri(<span style="color:#C77DBB">uri</span>);
});

# SBSMattingTextureView 接口文档

## 概述

`SBSMattingTextureView` 是基于 `TextureView` 实现的 SBS（Side-By-Side）抠图视频视图组件，继承自 `SBSMattingView`，实现了 `StickerView` 接口。该组件通过 OpenGL ES 渲染管线实现视频抠图功能，支持 MediaPlayer 和 ExoPlayer 两种播放器后端。

**包路径**：`com.vivo.sdk.stickerview.sbsmatting`

**继承关系**：

```
FrameLayout
  └── SBSMattingView (abstract, implements StickerView)
        ├── SBSMattingTextureView (TextureView 实现)
        └── SBSMattingSurfaceView (SurfaceView 实现)

```

---

## 版本信息

| 字段 | 说明 |
| --- | --- |
| `SDKVersion` | SDK 版本号 |
| `SDKBuildInfo` | SDK 构建包信息 |

---

## 构造函数

### `SBSMattingTextureView(Context context)`

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| context | `Context` | 上下文，默认使用 MediaPlayer 播放器 |

### `SBSMattingTextureView(Context context, AttributeSet attrs)`

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| context | `Context` | 上下文 |
| attrs | `AttributeSet` | XML 属性集，默认使用 MediaPlayer 播放器 |

### `SBSMattingTextureView(Context context, int playerType)`

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| context | `Context` | 上下文 |
| playerType | `int` | 播放器类型，取值见下方常量 |

### `SBSMattingTextureView(Context context, AttributeSet attrs, int playerType)`

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| context | `Context` | 上下文 |
| attrs | `AttributeSet` | XML 属性集 |
| playerType | `int` | 播放器类型，取值见下方常量 |

---

## 常量

### 播放器类型

| 常量名 | 值 | 说明 |
| --- | --- | --- |
| `PLAYER_TYPE_MEDIA_PLAYER` | 0 | 使用 Android 系统 MediaPlayer |
| `PLAYER_TYPE_EXO_PLAYER` | 1 | 使用 ExoPlayer |

---

## 公开方法

### 视频设置

#### `setStickerUri(Uri uri)`

设置贴纸视频的 URI。

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| uri | `Uri` | 视频资源路径 |

> 可在 Surface 创建前调用，会缓存设置；Surface 创建后调用会立即生效。

#### `setPlayerType(int playerType)`

设置播放器类型。**必须在 Surface 创建之前调用**，若已启动渲染线程，切换类型需先销毁并重新创建视图。

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| playerType | `int` | `PLAYER_TYPE_MEDIA_PLAYER` 或 `PLAYER_TYPE_EXO_PLAYER` |

#### `setPlanToSeek(boolean isSeek)`

设置是否计划进行 Seek 操作，影响首帧渲染逻辑。

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| isSeek | `boolean` | 是否计划 Seek |

---

### 播放控制

#### `play()`

开始播放视频。

#### `pause()`

暂停播放视频。

#### `isPlaying()`

获取当前播放状态。

**返回值**：`boolean` — 是否正在播放

#### `seekTo(int ms)`

跳转到指定播放位置。

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| ms | `int` | 目标位置，单位毫秒 |

**返回值**：`int` — 结果码，0 表示成功

#### `getCurrentPosition()`

获取当前播放进度。

**返回值**：`int` — 当前播放位置，单位毫秒

#### `getDuration()`

获取视频总时长。

**返回值**：`int` — 视频总时长，单位毫秒

---

### 播放参数

#### `setLooping(boolean looping)`

设置是否循环播放。

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| looping | `boolean` | `true` 循环播放，`false` 单次播放 |

#### `setRotation(int rotation)`

设置视频旋转角度。

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| rotation | `int` | 旋转角度（0, 90, 180, 270） |

#### `setPlaybackSpeed(float speed)`

设置播放倍速。

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| speed | `float` | 播放速度倍率，1.0f 为正常速度 |

#### `setBackGroundAlphaVal(float alpha)`

设置背景透明度。

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| alpha | `float` | 透明度值，范围 0.0 ~ 1.0 |

| 值 | 效果 |
| --- | --- |
| 0.0 | 背景全透明 |
| 1.0 | 背景不透明 |

#### `setBrightnessVal(float brightness)`

设置画面亮度。

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| brightness | `float` | 亮度值，范围 0.0 ~ 1.0 |

| 值 | 效果 |
| --- | --- |
| 0.0 | 画面全黑 |
| 1.0 | 原始亮度，不变黑 |

---

### 截图

#### `getBitmap()`

获取当前渲染帧的 Bitmap 截图。

**返回值**：`Bitmap` — 当前帧的位图，若 TextureView 不可用则返回 `null`

---

### 监听器

#### `setOnPreparedListener(OnPreparedListener listener)`

设置播放器准备完成监听器。

```
public interface OnPreparedListener {
    void onPrepared();
}

```

| 回调 | 说明 |
| --- | --- |
| `onPrepared()` | 播放器 prepare 完成时回调 |

#### `setOnFirstFrameRenderListener(OnFirstFrameRenderListener listener)`

设置首帧渲染完成监听器。

```
public interface OnFirstFrameRenderListener {
    void onFirstFrameRendered();
}

```

| 回调 | 说明 |
| --- | --- |
| `onFirstFrameRendered()` | 首帧渲染完成时回调 |

#### `setOnCompletionListener(OnCompletionListener listener)`

设置播放完成监听器。

```
public interface OnCompletionListener {
    void onCompletion();
}

```

| 回调 | 说明 |
| --- | --- |
| `onCompletion()` | 非循环播放结束时回调 |

#### `setOnSeekCompleteListener(OnSeekCompleteListener listener)`

设置 Seek 完成监听器。

```
public interface OnSeekCompleteListener {
    void onSeekComplete();
}

```

| 回调 | 说明 |
| --- | --- |
| `onSeekComplete()` | seekTo 操作完成时回调 |

#### `setOnPauseCompleteListener(OnPauseCompleteListener listener)`

设置暂停完成监听器。

```
public interface OnPauseCompleteListener {
    void onPauseComplete();
}

```

| 回调 | 说明 |
| --- | --- |
| `onPauseComplete()` | pause 操作完成时回调 |

---

## 使用示例

### 基本用法

```
// 1. 创建视图（使用 ExoPlayer）
SBSMattingTextureView mattingView = new SBSMattingTextureView(context, SBSMattingTextureView.PLAYER_TYPE_MEDIA_PLAYER);

// 2. 设置监听器
mattingView.setOnPreparedListener(() -> {
    // 播放器准备完成，可以开始播放
    mattingView.play();
});

mattingView.setOnFirstFrameRenderListener(() -> {
    // 首帧已渲染
});

mattingView.setOnCompletionListener(() -> {
    // 非循环播放结束
});

// 3. 配置播放参数
mattingView.setStickerUri(videoUri);
mattingView.setLooping(true);
mattingView.setBackGroundAlphaVal(0.0f);  // 背景全透明
mattingView.setPlaybackSpeed(1.0f);

// 4. 播放控制
mattingView.play();
mattingView.pause();
mattingView.seekTo(5000);  // 跳转到 5 秒处

// 5. 获取当前状态
boolean playing = mattingView.isPlaying();
int position = mattingView.getCurrentPosition();
int duration = mattingView.getDuration();

// 6. 截图
Bitmap frame = mattingView.getBitmap();

```

### XML 布局中使用

```
<com.vivo.sdk.stickerview.sbsmatting.SBSMattingTextureView
    android:id="@+id/matting_view"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />

```

> **注意**：XML 中使用时默认采用 MediaPlayer 播放器类型，如需使用 ExoPlayer 请在代码中调用 `setPlayerType()` 并确保在 Surface 创建前设置。

---

## 生命周期说明

| 阶段 | 回调/方法 | 说明 |
| --- | --- | --- |
| Surface 创建 | `onSurfaceTextureAvailable` | 自动初始化 GL 渲染线程 |
| Surface 尺寸变化 | `onSurfaceTextureSizeChanged` | 自动通知 GL 线程更新视口 |
| Surface 销毁 | `onSurfaceTextureDestroyed` | 自动释放 GL 线程资源 |
| 视图分离 | `onDetachedFromWindow` | 自动释放 GL 线程资源 |

> **注意**：监听器可在 Surface 创建前设置，内部会缓存并在 GL 线程初始化时自动应用。