<span style="color:#172B4D">视频编辑SDK在充分发挥平台软硬件能力的基础上，提供了丰富的API接口，集成了常见的视频编辑能力。使得集成该SDK的应用开发者得以方便、快捷地在自己的应用程序中实现诸如视频拼接、裁剪、动态图片、字幕、画布、变速、转码、倒放、背景音乐、特效、滤镜、美颜、字体等视频处理功能，为用户提供便捷、流畅、有趣的音视频内容再创作功能。</span>

#### 主要包含以下几个包

![image-2025-7-2_14-38-45.png](https://veditor.vivo.xyz/api/v1/attachment/file/a_edVYby3ns1fOuwtqGinxfLs-YvbS3HgTGbMbQBjrR4-qdrbXAk9Q67KrCHDvBC "image-2025-7-2_14-38-45.png")

#### 一、主要概念

| VideoFactory | 视频编辑引擎，负责视频编辑工程中的预览，导出，seek等操作。 |
| --- | --- |
| Clip | Clip 是视频编辑中的基本单位，包括VideoClip, AudioClip, ImageClip. 所有的Clip添加到项目中会被按前后顺序渲染输出。 |
| VideoProject | 用来管理所有需要被编辑处理后输出的 各种clip、背景音乐，文字、主题模板、 视频图层。 |
| Overlay | 对应某个时间点上，需要同时编辑、渲染、输出的各种对象，如多画面显示以及字幕。 |
| VideoFactoryListener | SDK的使用者需要注册到VideoFactory，用以监听和接受VideoFactory 的notify, 需要使用者重写实现。 |
| VideoRenderView | 视频预览窗口，有VideoEditorView (TextureView类型)和VideoSurfaceView(SurfaceView类型)两个类型继承实现，可以通过activity xml加入到Activity中。 |
| VideoConverter | 视频转换，裁切视频，导出不同分辨率，不同时长视频，实现livePhoto。 |

#### 二、剪辑sdk 播放导出流程

![image-2025-6-30_19-40-27.png](https://veditor.vivo.xyz/api/v1/attachment/file/aLr1jhaitTx2kUwf395INDySi9jAB17yf-wRJu2rcJrsVoDTTpRe1-gHUcLGqi-_ "image-2025-6-30_19-40-27.png")

1. 编辑环境初始化

VideoEditorConfig.init(this, null);
 VideoEditorConfig.setAspectMode(VideoEditorConfig.AspectRatio_16v9); //设置画布比例(1:1, 16:9, 4:3, 9:16)

mVideoFactory = new VideoFactory();
 mProject = new VideoProject();

Clip videoClip = Clip.getSupportedClip(file);
if (videoClip instanceof VideoClip == false) {
    return;
}

#####   2. 设置参数

// 实现该接口以接收VideoProject发送的各种message notify。
VideoFactoryListener mListener = new VideoFactoryListener() {
     //收到消息后，具体的操作要放在另外一个thread里面做。
} mVideoFactory.setEventHandler(mListener);
 mVideoFactory.setProject(mProject);
  
 mVideoFactory.setVideoView(mVideoEditorView);
 mVideoFactory.setOnSurfaceChangeListener(this);
 mVideoFactory.setVideoFrameRate(30);

//设置到画布的裁切模式
videoClip.setCropMode(CropMode.Fit);
//如果需要调整该clip 最终输出的起始和结束时间，可以设具体的指，否则设 -1， -1.
int duration =  clip.getDuration();
videoClip.setPlayTime(startTime, endTime);
​
//设置播放速度,可以是0.1 到4.0f
videoClip.setSpeed(1.0f)
​
//设置音量， 为0-2.0f的值， 0位静音， 1位不变， 2为增倍。
videoClip.setVolume(this.getVolume());
​
//设置显示角度, 0, 90, 270。
videoClip.setExtraVideoRotation(90);
​
//默认添加到尾部，如果要插入到某个位置，可以用addClip(int position, Clip clip)
mProject.addClip(videoClip);

在初始阶段或者编辑过程中，如果VideoProject 中记录的内容发生变化后，要跟新project, 并且把编辑画面定位到起始位置。

mProject.updateProject();
mVideoFactory.seekTo(0); //时间单位为毫秒。
mCurrentPosition = 0；

等待VideoFactoryListener 收到 VideoFactory 的notify, 以初始化UI界面, 使用户可以继续编辑。

- onContentChanged() // 开始通过VideoFactory 的api ,获取时长之类的信息设置UI界面。
- onFirstFrameShown() // 表明第一帧画面已经显示出来了。如果当前没有做VideoFactory 的start, 那么画面静止处于pause状态。UI上的各种操作按钮 可以enable，用户可以开始剪辑了。

如果对工程的编辑，参数的设置不会影响工程的总时长，可以直接刷新画面，不需要updateProject()。

mVideoFactory.refreshVideo();

##### 3. 预览

用户编辑后，如果希望让画面播放起来预览效果。

mVideoFactory.play();
// 在画面预览播放过程中，可以在一定的时间间隔内循环获取当前播放进度位置，然后跟新UI.
mCurrentPosition = mVideoFactory.getCurrentPosition();

如果希望继续剪辑，需要让播放停下来。

mVideoFactory.pause()

##### 4.导出

导出阶段，VideoFactory 会根据用户的各种设定，渲染画面，并且把画面编码保存成新的视频文件。

public class ExportParam {
    // 导出的文件
    protected String strExportFile;
    //音频文件
    protected String strExportAudioFile;
    //宽
    protected int mEncodeWidth;
    //高
    protected int mEncodeHeight;
    //比特率
    protected  int mEncodeBitrate;
    //最大体积
    protected long mEncodeMaxFileSize;
    // 角度
    protected int mRotate;
    //采样率
    protected int mSamplingRate;
    // 全部I帧的 mp4 文件变量
    protected boolean mAllKeyFrame;
}
mVideoFactory.export(ExportParam param);

导出过程中，无法做编辑处理。根据收到的notify 做相应的UI处理和程序控制。

- onEncodingProgress（int percent, int pts） //得到进度信息。
- onEncodingDone(boolean iserror, int result) // 导出结束，根据iserror 来判断是非导出成果。

导出结束或者导出过程中取消导出，需要释放资源，重置VideoFactory.

mVideoFactory.stopSync();

如果需要继续编辑，让编辑界面定位到某一个位置。

mVideoFactory.seekTo()

如果需要结束编辑，进入退出阶段。

##### 5. 退出编辑

mVideoFactory.release();

#### 三、主要api介绍

##### VideoFactory

构造和初始化VideoFactory

//默认构造
public VideoFactory()
//用另外一个VideoProject 做初始化，设定消息监听器。
public void setProject(VideoProject project)
public VideoProject getProject()
​
//设定VideoRenderView和消息变化的接听器
public void setEventHandler(VideoFactoryListener listener)
​
//设定画面显示的view.
public void setVideoView(VideoRenderView view)
public void setOnSurfaceChangeListener(OnSurfaceChangeListener listener)

参数设置

//设置默认背景色
public void setBackgroundColor(int backgroundColor)
//设置预览和导出帧率。
public boolean setVideoFrameRate(int rate)
​
//获取所有的位移、旋转、缩放等参数容器，然后做相应的设定。
public TransformParameters getTransformParameters()
​

状态控制和参数获取

//播放状态控制的函数。
public void play()
public void playBackTimeLine(int startTime, int endTime) //播放一段时间
public void pause()
public void resume()
public boolean isPlaying()
public void stopSync() // 同步stop, 函数返回时,VideoFactory已经stop.
public void stop(OnCompletionListener listener)
public void release()
​
//画面编辑后，如果不需要update, 直接显示效果。
public void refreshVideo()
​
//seek到某个位置，异步操作。
public void seekTo(int msec)
//整个工程的总时长，单位毫秒。
public int getDuration()
//当前画面在整个工程中的时间点。
public int getCurrentPosition()
//是否已经有画面显示出来了。
public boolean isFirstFrameShown()

抓取视频画面

//用当前显示的画面生成Bitmap, isMeasure用于标记是否要做裁剪，0 表示不做 即全屏显示，1表示需要根据Clip设置的纹理裁剪区域进行裁剪
public Bitmap getCurrentFrame()
public Bitmap getCurrentFrame(int isMeasure)
//用当前画面生成一张指定大小的Bitmap，可以用来做视频封面。如果是原图大小，可以设-1.
public Bitmap getCover(int width, int height)
​
​
//用当前帧生成模糊图片。
//shortLength 输出纹理的短边长度，-1表示默认是180.
//isMeasure 表示是否需要做构图，0表示不需要，非0表示需要
public Bitmap getCurrentBlurFrame()
public Bitmap getCurrentBlurFrame(int shortLength)
public Bitmap getCurrentBlurFrame(int shortLength, int isMeasure)

视频导出

//设置导出视频的编码格式，可以是"video/avc" 或"video/hevc"
public void setVideoEncoderFormat(String mime)
//在导出的mp4文件中的metedata设定位置信息。
public void setLocation(float latitude, float longitude)
​
public class ExportParam {
    // 导出的文件
    protected String strExportFile;
    //音频文件
    protected String strExportAudioFile;
    //宽
    protected int mEncodeWidth;
    //高
    protected int mEncodeHeight;
    //比特率
    protected  int mEncodeBitrate;
    //最大体积
    protected long mEncodeMaxFileSize;
    // 角度
    protected int mRotate;
    //采样率
    protected int mSamplingRate;
    // 全部I帧的 mp4 文件变量
    protected boolean mAllKeyFrame;
}
​
//文件单独导出到另一个文件。
mVideoFactory.export(ExportParam param);

##### Clip

Clip的创建

//用同一个文件创建clip.
public static Clip getSupportedClip(String path)
//用来做不同文件视频流，音频流创建clip.
public static MediaClip createMediaClip(String videoPath, String audioPath)
// clone当前clip.
public Clip cloneClip();
// 新创建一个clip, 但是clone当前clip的参数设定。
public Clip cloneClip(String path);
//设置是否为主clip。
public void setMainClip(boolean mainClip)
​

获取Clip参数

//获取clip信息。
public boolean hasVideo()
public boolean hasAudio()
public int getWidth()
public int getHeight()
public int getRotate()
//有时视频文件中的音频和视频时间长度并不相等，需要分别获取。
public int getVideoDuration()
public int getAudioDuration()

设置渲染参数

//clip 设定
public void setVolume(float volume)
public float getVolume()
//获取播放的总时长，audio clip 和vidoe clip在重复部分的情况下，总时长会大于本身的时长。
public int getDuration()
​
//设置移动、旋转、缩放参数
public TransformParameters getTransformParameters()
public void setTransformParameters(TransformParameters parameters)
​
//设置在画布上的裁切模式。
public void setCropMode(CropMode mode)
public CropMode getCropMode()
//判断在某一尺寸的画布(surface)上显示，如果保持画面比例，边缘是否会有未被遮盖的部分。
public boolean isNeedDrawBackground(int surfaceWidth, int surfaceHeight)
//如果画布有未被遮挡的部分，设定这些位置的着色方式，前面有做说明。
public void setBackgroundMode(int backgroundMode, int blurEffectLevel, int rgbColor)
public int getBackgroundMode()
public int getBlurEffectLevel()
public int getBackgroundRGBColor()
​
​
//设置水平和垂直镜像
public void setTextureFlipHorizon(boolean isFlipHorizon)
public boolean getTextureFlipHorizon()
public void setTextureFlipVertical(boolean isFlipVertical)
public boolean getTextureFlipVertical()
​
//设置滤镜
public void setEffect(String effectID)
public String getEffectID()
//设置转场特效
public void setTransition(String transitionID, int transitionPeriods)
public String getTransitionID()
public int getTransitionPeriods()
​
//设置音频的淡入，淡出效果。
public void setFadeInDuration(int duration)
public void setFadeOutDuration(int duration)
public int getFadeInDuration()
public int getFadeOutDuration()
​
​
//设置额外的旋转角度，会与文件本身视频角度相加，需要设90的倍数。
public void setExtraVideoRotation(int degree)
//直接设定最终旋转角度。
public void setRotateDegree(int degree)
​
//设定输出视频的帧率。
public void setRenderFrameRate(int frameRate)
​

状态控制

//设定是否enable 音频或视频。在VideoFactory中，一般对于同一个文件会创建2个clip, 一个用于音频，一个用于视频。可以通过该API设定。
   public void setAudioVideoEnable(boolean enableAudio, boolean enableVideo)
   
   //音频clip和视频clip的启动和停止。
   public void startVideo()
   public void startAudio()
   
   public void stopVideo()
   public void stopAudio()
   
   //如果音视频用同一个clip, 一起启动、停止。
   public start()
   public void stop()
   
   //快进到视频的某个位置，同步和非同步方法。由于seek是一个比较慢速的操作，所以一般用非同步方法。
   public void seekTo(int timeMs)
   public void seekTo(int timeMs, OnSeekCompletelistener listener)

获取缩略图序列

//生成视频序列的缩略图，前面已有详细简绍。
   public VideoThumbnailDecodThread getVideoClipDetailThumbnails (
          int thumbWidth, int thumbHeight,
          final int startTime, final int endTime, int maxCount, int rotate, boolean noCache,
          int[] arrayTimeTab, final
          OnGetVideoClipDetailThumbnailsListener listener )

音视频Clip的扩展API

//设置循环播放时的总时长。
   public void setMediaLoop(boolean isLoop, int loopDurationMs)
   //播放速度
   public void setSpeed(float speed)
   
   //原文件中需要渲染的起始、结束时间点。
   public void setPlayTime(int startTimeMs, int endtimeMs)
   
   //获取文件原始时长。
   public int getFileDuration()
   
   //快捷时，设定音频快捷时的处理方式。
   public void setNeedAudioSonic(boolean audioSonic)

##### VideoProject

创建视频编辑项目

public VideoProject()
public VideoProject(VideoProject cloneProject)
public VideoProject clone()
​
//VideoFactory会做设定
public void addDataChangeListener(DataChangeListener listener)
​
// 把VideoEditorView 的size设给VideoProject
public void setViewPortSize(int width, int height)

添加和管理Clip

//默认添加在末尾
public int addClip(Clip clip)
//实现clip的插入
public int addClip(int position, Clip clip)
​
//移除Clip
public void removeClip(Clip clip)
public void removeClip(int clipIndex)
​
//交换clip位置
public void swapClip(Clip clip1, Clip clip2)
​
//获取clip和 整个clip list.
public Clip getClip(int index, boolean isPrimary)
public List<Clip> getPrimaryItems()
​
//设定用来做封面的image clip.
public void setCoverClip(ImageClip clip)
public ImageClip getCoverClip()
​
//获取某个时间点的clip对象
public Clip getClipByTimelinePosition(long timeMs)
​
//获取某个clip的起止时间和位置
public int getClipStartTimeMs(Clip targetClip)
public int getClipEndTimeMs(Clip targetClip)
public int getClipIndex(Clip clip)
​
//获取最终的clip数量和总时间长度，单位毫秒。
public int getClipCount()
public int getTotalTime()
public void printProjectInfo()
public float getVideoVolume()

主题和特效的设置

public void setTheme(Theme theme)
public void setSameStyleTheme(SameStyleTemplate template)
public SameStyleTemplate getSameStyleTheme()
public Theme getTheme()
​
private TimelineEffectManager mTimelineEffectManager;
public TimelineEffectManager getTimelineEffectManager()
public void notifyDataChange()
public void notifyEffectChange()
​
//视频主题的设置
public int setVideoThemeByPath(String themePath)
public VideoTheme getVideoTheme()
public int calVideoThemeRenderDuration()
public void setVideoTheme(VideoTheme theme)
public void clearVideoTheme()
​
public void setAutoAddThemeMusicToTimeline(boolean autoAdd)
public boolean isAutoAddThemeMusicToTimeline()

背景音乐的设置

public static enum BackgroundMusicType {
    Default, //默认视频音
    Custom,  //通过setBackgroundMusicPath设置。
    None,  //无背景音
}
public void setBackgroundMusicType(BackgroundMusicType type) 
public BackgroundMusicType getBackgroundMusicType()
​
//设定背景音， 内部会创建一个专门的audio clip。
public boolean setBackgroundMusicPath(String path)
public String getBackgroundMusicPath()
public AudioClip getBackgroundClip()
​
​
//设置背景音数据流开始的位置，播放时长，以及是否重复播放。
public void setBackgroundTrim(int leftTrimTime, int duration)
public int getBackgroundMusicStartTime()
public void repeatBackgroundMusic(boolean isRepeat) 
public boolean isRepeatBackgroundMusic() 
​
​
//设置背景音在整个video project中的起始位置。
public void setBackgroundMusicTimeLinePos(int ptsMs)
public int getBackgroundMusicTimeLinePos()
​
//设置背景音音量
public void setBackgroundMusicVolume(float volume)
public float getBackgroundMusicVolume()

Overlay的管理

//添加
public void addOverlayItem(OverlayItem overlay)
public void setOverlayItem(int index, OverlayItem overlayItem)
​
//查询获取overlay
public boolean haveOverlay(OverlayItem overlay)
public List<OverlayItem> getOverlayItemList()
public List<AudioLayer> getAudioLayerList()
​
//删除
public boolean removeOverlay(OverlayItem overlay)
//移除overlay是释放同时释放openGL资源
public boolean removeOverlay(OverlayItem overlay, boolean releaseInGL)
//删除文字overlay
public void removeAllTextOverlay()
public void removeAllTextOverlay(boolean releaseInGL)
​
​
//清空和跟新project.
public void allClear(boolean primary)
public void updateProject()
public void releaseGLObjects()
​

##### BeautyParameters

//磨皮
public int getSmoothSkin()
public void setSmoothSkin(int smoothSkin)
​
//美白
public int getWhiten()
public void setWhiten(int whiten)
​
//瘦脸
public int getThinFace()
public void setThinFace(int thinFace)
​
//削脸
public int getCutFace()
public void setCutFace(int level)
​
//大眼
public int getBigEye()
public void setBigEye(int level)
​
//瘦鼻
public int getThinNose()
public void setThinNose(int level)
​
//水平透视
public float getHorValue()
public void setHorValue(float value)
​
//垂直透视
public float getVerValue()
public void setVerValue(float value)
​
//高光
public float getInsHighlight()
public void setInsHighlight(float fInsHighlight)
​
//阴影
public float getInsShadow()
public void setInsShadow(float fInsShadow)
​
//色温
public float getInsTemperature()
public void setInsTemperature(float fInsTemperature)
​
//色调
public float getInsTint()
public void setInsTint(float fInsTint)
​
//亮度
public float getInsBright()
public void setInsBright(float fInsBright)
​
//对比度
public float getInsContrast()
public void setInsContrast(float fInsContrast)
​
//饱和度
public float getInsSaturation()
public void setInsSaturation(float fInsSaturation)
​
//自然饱和度
public float getInsVibrance()
public void setInsVibrance(float fInsVibrance)
​
//曝光
public float getInsExposure()
public void setInsExposure(float fInsExposure)
​
//锐利
public float getInsSharpen()
public void setInsSharpen(float fInsSharpen)
​
//褪色
public float getInsFading()
public void setInsFading(float fInsFading)
​
//暗角
public float getInsDarkCorner()
public void setInsDarkCorner(float fInsDarkCorner)
​
//颗粒
public float getInsGrain()
public void setInsGrain(float fInsGrain)
​
//设置和判断参数是否改变
public boolean isAdjustParamChange()
public void setAdjustParamChange(boolean isChange)
​
//设置和判断长腿参数是否改变
public boolean isPerspectiveParamChange()
public void setPerspectiveParamChange(boolean isChange)
​
//设置和判断美颜参数是否改变
public boolean isBeautyParamChange()
public void setBeautyParamChange(boolean isChange)
​
//重置美颜参数，包括磨皮、美白、瘦脸、削脸、大眼、瘦鼻
public void resetBeautyParam()
​
//重置长腿参数，垂直透视调节， 水平透视
public void resetPerspectiveParam()
​
//重置其他画质调节参数
public void resetPerspectiveParam()
​
//重置画质调节参数
public void resetAdjustParam()
​
设置是否开启美颜
public boolean isBeautyParam()
public void setBeautyParam(boolean isBeauty)
​
//设置是否开启长腿
public boolean isPerspectiveParam()
public void setPerspectiveParam(boolean isPerspective)