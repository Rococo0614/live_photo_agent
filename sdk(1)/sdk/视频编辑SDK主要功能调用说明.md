## **一. 主要类说明**

**概述：**

视频编辑SDK提供的与应用交互的主要类如下：

**VideoFactory**视频引擎类，负责视频编辑的预览，导出，seek等操作。

**VideoProject**用来管理加入到SDK的所有clip，和背景音乐，主题模板。

**Clip** 多媒体片段抽象类，用来管理多媒体素材文件，其子类包括图像片段ImageClip，音视频片段MediaClip。每个clip可以设置LUT滤镜，结束转场特效。

**VideoRenderView** 视频预览窗口view interface，其实现类有**VideoEditorView** (TextureView类型)和**VideoSurfaceView**(SurfaceView类型)两个类型，可以通过activity xml加入到界面中。

 

**调用示例：**

 //创建视频编辑引擎

VideoFactory factory = **new** VideoFactory();

//创建视频显示窗口view并设给VideoFactory

VideoEditorView view = **new** VideoEditorView(**this**);

factory.setVideoView(view);

 

//创建视频编辑工程

VideoProject videoProject = **new** VideoProject();

 

//创建素材Clip并加入到工程中

Clip clip = Clip._getSupportedClip_(path);

videoProject.addClip(clip);

//把视频编辑工程传给视频编辑引擎

factory.setProject(videoProject);

//显示第一帧画面

factory.seekTo(0);

…

//播放

videoFactory.play();

…

//导出

factory.export(mTargetFilePath, width, height, 12*1024*1024, 8000 * 1000, 0, 44100);

//停止

videoFactory.stopSync();

//释放

videoFactory.release();

 

** 视频基础接口：**

//按滤镜ID设置滤镜

clip.setColorFilterID(ThemeLibrary.FilterVideoEditor1970);

 //按图片路径设置滤镜

clip. setColorFilterLUTPath(lutPath);

 //设置视频剪裁，本接口只适用于MediaClip

mediaClip.setPlayTime(3000, 6000);

 获取视频缩略图接口

clip. getVideoClipDetailThumbnails

 

## **二. 主要功能调用示例**

## **视频拼图**

 1. 在activity xml中加入videoeditor view

       <com.vivo.videoeditorsdk.videoeditor.VideoEditorView

           android:id=_"@+id/engineview_3"_

           android:layout_width=_"match_parent"_

           android:layout_height=_"wrap_content"_

           android:layout_gravity=_"center"_ />

 

2. 创建视频拼图工程

       mVideoFactory = new VideoFactory();

VideoFactoryListener mListener = new VideoFactoryListener() {

        mVideoFactory.setEventHandler(mListener);

        mVideoProject = new VideoProject();

        mVideoFactory.setProject(mVideoProject);

        mVideoFactory.setVideoView(mVideoEditorView);

        mVideoFactory.setVideoFrameRate(30);

 

3. 添加overlay

//创建overlay，图片使用ImageOverlay，视频使用VideoOverlay

ImageOverlay imageOverlay = ImageOverlay.createOverlay(filePath);

OverlayParameters imageParams = imageOverlay.getParamters();

//设置overlay位置和坐标，坐标系使用opengl 2x2坐标系

imageParams.setOverlayPosition(0, 0);

imageParams.setOverlaySize(2.0f / 3.0f, (2.0f / 3.0f) * (9.0f / 2) / (16.0f / 2));

mVideoProject.addOverlayItem(imageOverlay);

 

4.播放

mVideoFactory.play();

 

### 抖音时间特效

时间特效包括时光倒流，闪一下，慢动作三种特效。

### 时光倒流

时光倒流需要先创建一个反转反转视频，创建完成后再把反转后的视频的clip加入project中。

视频反转参考代码：

 

    /*

     * 创建反转视频

     * reversedVideoPath 反转视频存放的临时目录

     */

    **void** createReversedVideo(String reversedVideoPath) {

        File file = **new** File(reversedVideoPath);

        **if** (file.exists()) {

            **return**;

        }

 

        **if** (mVideoReverser != **null**) {

            **return**;

        }

 

        mVideoReverser = **new** VideoReverser();

        mVideoReverser.setDataSource(mVideoFilePath);

        mVideoReverser.setTargetFile(reversedVideoPath);

        mVideoReverser.setTempPath(mDraftPath);

   

        mVideoReverser.setReverseListener(**new** VideoReverser.ReverseListener() {

   

            @Override

            **public** **void** onStart() {

                // **TODO** Auto-generated method stub

                Logger._v_(TAG, "start");

            }

   

            @Override

            **public** **void** onProgressChange(**int** percent) {

                // **TODO** Auto-generated method stub

                Logger._v_(TAG, "onProgressChange " + percent);

            }

   

            @Override

            **public** **void** onError(**int** code) {

                // **TODO** Auto-generated method stub

                Logger._e_(TAG, "onError " + code);

            }

   

            @Override

            **public** **void** onComplete() {

                // **TODO** Auto-generated method stub

                Logger._i_(TAG, "reverse complete");

                Toast._makeText_(TikTokEffectActivity.**this**, "reverse complete", Toast._LENGTH_LONG_).show();

                mEventHandler.sendEmptyMessage(EventHandler._ReverseVideoComplete_);

            }

        });

        mVideoReverser.start();

 

        mReverseEffectbutton.setEnabled(**false**);

    }

 

### 慢动作

慢动作实现方法为把一个视频放在两个clip中，一个clip正常播放，另一个clip慢速播放。参考代码：

    /*

     * 从视频的某个位置开始实现慢动作

     * slowMotionPositionMs 慢动作起始位置

     * speed 慢放的速度，2慢放传0.5， 4倍慢放传0.25

     */

    **void** applySlowMotionEffect(**int** slowMotionPositionMs, **float** speed) {

        mProject.allClear(**true**);

 

        VideoClip clip1 = **new** VideoClip(mVideoFilePath);

        clip1.setPlayTime(0, slowMotionPositionMs);

        clip1.setCropMode(CropMode._Fit_);

        mProject.addClip(clip1);

 

        **int** clip2endtimeMs = slowMotionPositionMs + (**int**) ((nDurationMs - slowMotionPositionMs) * speed);

        VideoClip clip2 = **new** VideoClip(mVideoFilePath);

        clip2.setCropMode(CropMode._Fit_);

        clip2.setPlayTime(slowMotionPositionMs, clip2endtimeMs);

        clip2.setSpeed(speed);

        mProject.addClip(clip2);

 

        mProject.updateProject();

    }

### 闪一下：

改特效实现原理是对视频中的某一秒重复播放三次，实现上需要把一段视频拆开在放在3个clip中，假设视频总时长为M，闪一下的位置为N （N<M）,三个clip的规则如下：

第一个clip的视频段是0~N

第二个视频片段的播放时长为 N-1 ~ N

第三个片段的播放时长为 N-1 ~ M

参考代码：

    /*

     * 闪一下特效

     * repeatPositionMs为视频闪一下的起始位置

     */

    void apply1sRepeatTimeEffect(int repeatPositionMs) {

        mProject.allClear(true);

 

        VideoClip clip1 = new VideoClip(mVideoFilePath);

        clip1.setCropMode(CropMode.Fit);

        clip1.setPlayTime(0, repeatPositionMs);

        mProject.addClip(clip1);

 

        int clip2StartTime = Math.max(repeatPositionMs - 1000, 0);

        VideoClip clip2 = new VideoClip(mVideoFilePath);

        clip2.setCropMode(CropMode.Fit);

        clip2.setPlayTime(clip2StartTime, clip2StartTime + 1000);

        mProject.addClip(clip2);

 

        VideoClip clip3 = new VideoClip(mVideoFilePath);

        clip3.setCropMode(CropMode.Fit);

        clip3.setPlayTime(clip2StartTime, clip2StartTime + nDurationMs - repeatPositionMs - 1000);

        mProject.addClip(clip3);

 

        mProject.updateProject();

    }

### 歌词字幕

歌词字幕支持时光，烟云，斜阳，告白，嘻哈五种特效，对应的ID在ThemeLibrary.java中

    **public** **static** **final** String _LyricsTimes_ = "com.vivo.videoeditorsdk.lyrics.times";    //时光

    **public** **static** **final** String _LyricsCloud_ = "com.vivo.videoeditorsdk.lyrics.cloud";    //烟云

    **public** **static** **final** String _LyricsSettingsun_ = "com.vivo.videoeditorsdk.lyrics.settingsun";    //斜阳

    **public** **static** **final** String _LyricsConfession_ = "com.vivo.videoeditorsdk.lyrics.confession";    //告白

    **public** **static** **final** String _LyricsHiphop_ = "com.vivo.videoeditorsdk.lyrics.hiphop";    //嘻哈

 

调用方法

        lyricsOverlay = LyricsOverlay._createLyricsOverlay_(ThemeLibrary._LyricsTimes_);

        lyricsOverlay.setPlayTime(0, 3000);

        lyricsOverlay.setLyricsString("hello world\n1234\n中文歌词");

        mProject.addOverlayItem(lyricsOverlay);

 

VideoProject

updateproject()

更新工程，调用此接口后视频编辑工程会重新加载，如果在播放中会停止播放。

 

### 获取视频缩略图

调用Clip.getVideoClipDetailThumbnails()可以获取视频缩略图，对应的listener中回调缩略图获取状态。

/**
 * 获取视频缩略图接口
 * @param thumbWidth 缩略图宽度
 * @param thumbHeight 缩略图高度
 * @param startTime 视频起始时间
 * @param endTime 视频结束时间
 * @param maxCount 最多获取多少个缩略图
 * @param rotate 旋转角度
 * @param noCache 是否不做缓存
 * @param arrayTimeTab 获取缩略图的时间间隔数组
 * @param listener 缩略图回调
 * @return
 */
public VideoThumbnailDecodThread getVideoClipDetailThumbnails (int thumbWidth, int thumbHeight,
        final int startTime, final int endTime, int maxCount, int rotate, boolean noCache, int[] arrayTimeTab, final
        OnGetVideoClipDetailThumbnailsListener listener );

### **多音轨功能**

根据文件路径创建AudioLayer:

AudioLayer layer = AudioLayer.createAudioLayer(String audioFilePath);

把音轨加入到工程中

videoProject.addAudioLayer(layer);

设置音轨在时间线上的位置：

layer.setPlayTime(start, end)

可以通过设置audiolayer中的audioclip来调节音量或者剪裁：

AudioClip clip = layer.getClip();

clip.setVolume(1.0)

clip.setPlayTime() //设置clip的剪裁