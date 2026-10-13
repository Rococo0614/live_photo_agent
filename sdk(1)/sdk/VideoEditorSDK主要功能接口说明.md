## **一. 主要类说明**

**概述：**

视频编辑SDK提供的与应用交互的主要类如下：

**VideoFactory**视频引擎类，负责视频编辑的预览，导出，seek等操作。

**VideoProject**用来管理加入到SDK的所有clip，和背景音乐，主题模板。

**Clip** 多媒体片段抽象类，用来管理多媒体素材文件，其子类包括图像片段ImageClip，音视频片段MediaClip。每个clip可以设置LUT滤镜，结束转场特效。

**VideoRenderView** 视频预览窗口view interface，其实现类有**VideoEditorView** (TextureView类型)和**VideoSurfaceView**(SurfaceView类型)两个类型，可以通过activity xml加入到界面中。

| 视频导出 |
| --- |
| 视频裁剪，视频拼接 |
| 视频拼图 |
| 文字特效textOverLay |
| 时光倒流 |
| 视频转码 |
| 获取视频缩略图 |
| 视频倍速 |
| livePhoto 导出 |
| 添加背景音乐 |

**调用示例：**

 //创建视频编辑引擎

VideoFactory factory = **new** VideoFactory();

//创建视频显示窗口view并设给VideoFactory

VideoEditorView view = **new** VideoEditorView(**this**);

factory.setVideoView(view);

//创建视频编辑工程

VideoProject videoProject = **new** VideoProject();

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

factory.export(ExportParam param);

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

## **视频导出**

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

### 视频裁剪，视频拼接

 视频裁切就是设置当前视频clip的播放时间端

  // nStartTimeMs   开始的时间，nEndTimeMs 结束时间

 MediaClip clip = new MediaClip(path, clipParam);
  clip.setPlayTime(nStartTimeMs, nEndTimeMs);

   mVideoFactory = new VideoFactory();
  VideoFactoryListener mListener = new VideoFactoryListener() {
  mVideoFactory.setEventHandler(mListener);
  mVideoProject = new VideoProject();

    mVideoProject .addClip(clip );  //视频拼接，如果是多个视频，这里继续添加clip

   mVideoFactory.setProject(mVideoProject);
  mVideoFactory.setVideoView(mVideoEditorView);  

mVideoFactory.refrashVideo();

然后导出视频，就可以获取到一个裁剪之后的视频了。

## **视频拼图**

 1. 在activity xml中加入videoeditor view

       <com.vivo.videoeditorsdk.videoeditor.VideoEditorView

           android:id=_"@+id/engineview_3"_

           android:layout_width=_"match_parent"_

           android:layout_height=_"wrap_content"_

           android:layout_gravity=_"center"_ />

2. 创建视频拼图工程

       mVideoFactory = new VideoFactory();

VideoFactoryListener mListener = new VideoFactoryListener() {

        mVideoFactory.setEventHandler(mListener);

        mVideoProject = new VideoProject();

        mVideoFactory.setProject(mVideoProject);

        mVideoFactory.setVideoView(mVideoEditorView);

        mVideoFactory.setVideoFrameRate(30);

3. 添加overlay

//创建一个overlay
VideoOverlay videoOverLay = VideoOverlay.createOverlay(filePath);

//获取overlay显示参数
OverlayParameters videoParams = videoOverLay.getParamters();
//设置overlay沿着x,y,z坐标轴的旋转角度
videoParams.setOverlayRotation(0, 0, 90);
//设置overlay中心点在opengl坐标系中的位置，取值范围为 -1 ~ 1，0,0为中心点
videoParams.setOverlayPosition(0， 0);
//设置overlay的在opengl坐标系中的显示宽高，由于opengl坐标系的x,y取值范围是-1~1，所以宽高为2表示全屏显示
videoParams.setOverlaySize(2.0f, 2.0f);

//把overlay添加但VideoProject
mVideoProject.addOverlayItem(videoOverLay);

4.播放

mVideoFactory.play();

### 文字特效TextOverlay 

//创建一个文字内容为 "vivo vivo"，中心点位于坐标系中央(0.0f, 0.0f)，且在时间线上全程出现的 TextOverlay

TextOverlay textOverlay = new TextOverlay("vivo vivo", 0.0f, 0.0f, -1, -1);

textOverlay.setText("VideoEditor");    //设置文字内容为 "VideoEditor"
textOverlay.setOverlayEffectByPath("[assets:/theme/Overlay_zoomappearline](http://assets/theme/Overlay_zoomappearline)");    //设置文字特效为 "[assets:/theme/Overlay_zoomappearline](http://assets/theme/Overlay_zoomappearline)"
textOverlay.setTextColor(Color.WHITE);    //设置文字颜色为白色
textOverlay.setTextAlpha(1.0f);    //设置文字全不透明
textOverlay.setFontPath("system/fonts/DroidSansFallbackBBK.ttf")    //设置文字字体为 DroidSansFallbackBBK

//设置文字变换参数
OverlayParameters parameters = textOverlay.getParamters();
parameters.setOverlayPosition(0.5f, 0.5f);    //设置文字位置为坐标点(0.5f, 0.5f)
parameters.setOverlayScale(0.5f, 2.0f, 1.0f);    //设置文字缩放比例，其中X轴缩小0.5，Y轴方法2.0，Z轴保持不变。
parameters.setOverlayRotation(0, 0, 90);    //设置文字旋转角度，其中X轴和Y轴不旋转，沿着Z轴旋转90度
textOverlay.setParam(parameters);    //将变换参数设置进 TextOverlay 对象中

//设置文字特效参数
UserTextInfo userTextInfo = textOverlay.getUserTextInfo();     //获取 TextOverlay 的文字特效信息
userTextInfo.setTextHAlign(UserTextInfo.AlignRightString);     //设置文字水平对齐方式，可以使用的值包括：UserTextInfo.AlignCenterString; UserTextInfo.AlignLeftString; UserTextInfo.AlignRightString
userTextInfo.setBackgroundColor(Color.parseColor("#7FFF0000"));     //设置背景颜色，默认效果作用于全部文字
textOverlay.setUserTextInfo(userTextInfo);     //将更新后的 UserTextInfo 设置回 TextOverlay

//设置 TextOverlay 边框
OverlayOutline outline = textOverlay.getOutline();
outline.setVisiable(true);    //显示边框
outline.setIconVisiable(true);    //显示边框的角标
outline.setOutLineIcon(OverlayOutline.kOutLine_Pos_LeftTop, R.drawable.editor_maincategory_text_overlay_close);    //设置边框左上角角标的图示
outline.setOutLineIcon(OverlayOutline.kOutLine_Pos_RightBottom, R.drawable.editor_maincategory_text_overlay_scale);    //设置边框右下角角标的图示

//将准备好的 TextOverlay 添加到视频编辑工程中
mProject.addOverlayItem(textOverlay);

mVideoFactory.setProject(mProject);

mVideoFactory.refrashVideo();

### 时光倒流

时光倒流需要先创建一个反转反转视频，创建完成后再把反转后的视频的clip加入project中。

视频反转参考代码：

    /*

     * 创建反转视频

     * reversedVideoPath 反转视频存放的临时目录

     */

    **void** createReversedVideo(String reversedVideoPath) {

        File file = **new** File(reversedVideoPath);

        **if** (file.exists()) {

            **return**;

        }

        **if** (mVideoReverser != **null**) {

            **return**;

        }

        mVideoReverser = **new** VideoReverser();

        mVideoReverser.setDataSource(mVideoFilePath);

        mVideoReverser.setTargetFile(reversedVideoPath);

        mVideoReverser.setTempPath(mDraftPath);

   

        mVideoReverser.setReverseListener(**new** VideoReverser.ReverseListener() {

   

            @Override

            **public** **void** onStart() {

                // **TODO** Auto-generated method stub

                Logger._v_(TAG, "start");

            }

   

            @Override

            **public** **void** onProgressChange(**int** percent) {

                // **TODO** Auto-generated method stub

                Logger._v_(TAG, "onProgressChange " + percent);

            }

   

            @Override

            **public** **void** onError(**int** code) {

                // **TODO** Auto-generated method stub

                Logger._e_(TAG, "onError " + code);

            }

   

            @Override

            **public** **void** onComplete() {

                // **TODO** Auto-generated method stub

                Logger._i_(TAG, "reverse complete");

                Toast._makeText_(TikTokEffectActivity.**this**, "reverse complete", Toast._LENGTH_LONG_).show();

                mEventHandler.sendEmptyMessage(EventHandler._ReverseVideoComplete_);

            }

        });

        mVideoReverser.start();

        mReverseEffectbutton.setEnabled(**false**);

    }

### 视频转码 （不使用videoFactory 类进行简单的视频转码， 也可以使用mVideoFactory.export(ExportParam param)转码导出 ）

VideoConverter  这里类负责视频转码功能
参考如下代码;
mVideoConverter = new VideoConverter();
mTargetFilePath = getConvertFilePath(mFilePath);//原文件的路径
mVideoConverter.setTargetFilePath(mTargetFilePath);  //输出文件的路径
 mVideoConverter.setAllKeyFame(false);  //是否全I帧输出
     
 ConvertHandler handler = new ConvertHandler();//转码的回调
 mVideoConverter.setOnCompletionListener(handler);
 mVideoConverter.setOnProgressChangeListener(handler);
 mVideoConverter.setOnErrorListener(handler);
//nConvertWidth  输出的宽，nConvertHeight 输出的高， nConvertBitrate 码率 ，nConvertFrameRate 帧率 ，  mVideoMime 类型
 mVideoConverter.setExportParameter(nConvertWidth, nConvertHeight, nConvertBitrate, nConvertFrameRate, mVideoMime);
  mVideoConverter.start();

    class ConvertHandler implements OnProgressChangeListener, OnCompletionListener, OnErrorListener {    //转码回调

        @Override
        public void onCompletion(VideoConverter vc) {
            // 转码完成
            mEventHandler.sendEmptyMessage(EventHandler.ExportComplete);
        }

        @Override
        public void onProgressChanged(int percent, int pts) {
            // TODO Auto-generated method stub
            mEventHandler.sendMessage(mEventHandler.obtainMessage(EventHandler.UpdateProgress, percent, pts));
        }

        @Override
        public void onError(int errorCode) {
            Log.v(TAG, "转码失败 " + errorCode);
            mEventHandler.sendMessage(mEventHandler.obtainMessage(EventHandler.ExportError, errorCode, 0));
        }
    }

**视频转码效果预览获取**

使用com.vivo.videoeditorsdk.videoeditor.VideoConverterPreviewer

开启预览

    **void** startEncodePreview() {

        getConvertParams();

        //创建预览对象，并设置回调函数

        mVideoConverterPreviewer = **new** VideoConverterPreviewer();

        mVideoConverterPreviewer.setPreviewStatusCallback(**new** VideoConverterPreviewer.PreviewStatusCallback() {

           

            @Override

            **public** **void** onPreviewTimeChange(**int** ptsMs) {

                Log._v_(TAG, "encode prevew pts " + ptsMs);

                //预览时间控制在10秒

                if (ptsMs > 10000) {

                    stopEncodePreivew();

                }

            }

           

            @Override

            **public** **void** onPrevewComplete() {

                Log._v_(TAG, "onPrevewComplete");

                stopEncodePreivew();

            }

           

            @Override

            **public** **void** onError(**int** error) {

                //出错后要停止预览

                stopEncodePreivew();

            }

        });

        //设置源视频路径，转码预览参数

        mVideoConverterPreviewer.setVideoPath(mFilePath);

        mVideoConverterPreviewer.setConvertParam(1280, 720, 2000000);

        //设置视频预览surface

 mVideoConverterPreviewer.setSurface(mEncodePreviewView.getHolder().getSurface());

        //开始预览

        mVideoConverterPreviewer.start();

    }

    //停止预览

    **void** stopEncodePreivew() {

        **if** (mVideoConverterPreviewer != **null**) {

            mVideoConverterPreviewer.stop();

            mVideoConverterPreviewer = **null**;

        }

    }

### 获取视频缩略图  

**视频缩略图有四种方式**

**一  根据时间获取视频缩略图**

//timeList   输入需要获取缩略图的时间戳，是一个list

 /**
     * 根据时间戳数组，获取对应的关键帧
     *
     * @param id     视频文件对应的id
     * @param thumbW 缩率图宽
     * @param thumbH 缩略图高
     * @param points 时间戳数组
     */
    public void getVideoClipDetailThumbnails(final int id, int thumbW, int thumbH,
                                             final int[] points)

    /**
     * 获取缩略图回调listener
     */
    public interface OnGetVideoClipDetailThumbnailsListener {
        final int kEvent_Ok = 0;
        final int kEvent_Completed = 1;
        final int kEvent_Fail = -1;
        final int kEvent_systemError = -2;
        final int kEvent_UserCancel = -3;

        /**
         * 缩略图回调接口
         * @param clip 哪个clip的回调
         * @param event 事件
         * @param bm 缩略图bitmap
         * @param index 缩略图index
         * @param totalCount 总的缩略图个数
         * @param timestamp 当前缩略图时间戳
         */
        void onGetDetailThumbnailResult(Clip clip, int event, Bitmap bm, int index, int totalCount, int timestamp);
    }

MediaClip clip = new MediaClip("/sdcard/DCIM/002.mp4");
  int[] timeList = new int[]{0, 359259, 718518, 1077777, 1437036, 1796295, 2155554, 2514813, 2874072, 3233331, 3592590, 3951849, 4311108, 4670367, 5029626};
  clip.getVideoClipDetailThumbnails(301, 128, 0, 0, 15, 0, true, timeList
                        new Clip.OnGetVideoClipDetailThumbnailsListener() {
                            @Override
                            public void onGetDetailThumbnailResult(Clip clip, int event, Bitmap bm, int index, int totalCount, int timestamp) {
                               
                            }
    });

**二  获取当前播放画面（这个一般跟进度条组合，seekTo之后调用）**

  

public Bitmap getCurrentFrame() {
        return getCurrentFrame(0, 0);
}

mVideoFactory.getCurrentFrame();

**三  FastVideoFramePicker  获取缩略图，可以设置软解，硬解，是否精确出图 **

**   **

VideoFramePicker mVideoFramePicker;
 mVideoFramePicker = VideoFramePicker.createFramePicker(mFilePath);   //输入文件路径
        if (mVideoFramePicker == null) {
            Logger.e(TAG, "unsupported video!");
            return;
        }
      //  nVideoDuration = mVideoFramePicker.getDuration();
        mVideoFramePicker.setCallback(new VideoFramePicker.OnFrameCallback() {    //缩略图回调

            @Override
            public void onError(int errorCode) {
                Logger.e(TAG, "onError " + errorCode);
            }

            @Override
            public void onVideoTrackFramesUpdated(ArrayList<TrackFrameBean> trackFrameBeans) {

            }

            @Override
            public void onFinish() {

            }

            @Override
            public void onFrameDone(Bitmap bmp, long pts) {
                mEventHandler.sendMessage(mEventHandler.obtainMessage(EventGetFrameDone, bmp));
            }
        });

mVideoFramePicker.getFrame(0, null);  //设置获取缩略图的时间戳

//  mVideoFramePicker.getFrame(ArrayList<Long> timelist, CountDownLatch latch);  //多个时间点的缩略图这里  正常

**四   获取某个clip的时间点的图片**

  1： 设置回调

mVideoFactory.setBitmapCaptureNotify(new BitmapCaptureNotify() {
                @Override
                public void onBitmapCaptureNotify(Bitmap bitmap, int pts) {
                    
                }
 });

2： 调用时间点，bmp的就在上面回调

mVideoFactory. clipFastSeekTo(Clip clip, int msec)；

** **

### 视频倍速功能，曲线变速功能  

**一    正常倍速功能**

      正常设置倍速，0.1X—4.0X

         VideoClip clip2 = new VideoClip(mVideoFilePath);
        clip2.setCropMode(CropMode.Fit);
        clip2.setPlayTime(slowMotionPositionMs, clip2endtimeMs);
        clip2.setSpeed(speed);      //clip设置倍速
        mProject.addClip(clip2);

        mProject.updateProject();

**二  曲线变速功能**

![image-2025-7-1_20-16-1.png](https://veditor.vivo.xyz/api/v1/attachment/file/AOKiALz2DjrTyU_4zF0_mZLmOWRBFEfAFcmaGJg6MANnId6xsYcRX1ZEOrrJvhMZ "image-2025-7-1_20-16-1.png")

    **添加曲线区域layout**

       <com.vivo.videoeditordemo.BezierCurveView
        android:id="@+id/bezier_curve_chart"
        android:layout_width="match_parent"
        android:layout_height="150dp"
        android:layout_margin="10dp" />

   **设置曲线的变化回调**

       String tipText = "speed=1";
        float maxSpeed = 10;
        mSpeedCurveChart = findViewById([R.id](http://r.id/).bezier_curve_chart);
        final String[] ylabels = new String[]{""+maxSpeed, ""+(maxSpeed/2),"1.0", ""+(2/maxSpeed), ""+(1/maxSpeed)};
        final String[] xlabels = new String[]{" "," "," "," "," "};
        mSpeedCurveChart.init(10, xlabels,ylabels,tipText);
        mSpeedCurveChart.setMaxRanges(100); // 设置最大区间片段
        mSpeedCurveChart.setMaxCtrlPoints(6); // 设置最大贝塞尔控制点
        mSpeedCurveChart.setOnCurveChangedListener(new BezierCurveView.OnCurveChangedListener() {
            @Override
            public void onCurveChanged(PointF[] normalizedPoints) {
                Clip clip = mProject.getClip(0, false);
                if (clip instanceof VideoClip) {
                    long duration = ((VideoClip) clip).getFileDuration()*1000L;
                    Log.d(TAG,"SpeedZoneCurve duration = "+duration +"us,normalizedPoints:" + Arrays.toString(normalizedPoints));

                    ArrayList<SpeedZoneCurve.SpeedZone> speedZones = new ArrayList<>();

                    float minSpeed = 1.0f/maxSpeed;
                    for (int i = 1; i < normalizedPoints.length; i++) {
                        float preStartTimeUs = normalizedPoints[i-1].x;
                        float startTimeUs = normalizedPoints[i].x;
                        float rangeDur = startTimeUs - preStartTimeUs;
                        float speed = normalizedPoints[i].y;
                        if (speed > 0.5) {
                            // 加速
                            speed = (2*maxSpeed-2)*speed - maxSpeed + 2;
                        } else {
                            // 减速
                            speed = (2-2*minSpeed)*speed + minSpeed;
                        }

                        startTimeUs = startTimeUs*duration;
                        rangeDur = rangeDur*duration;
                        speedZones.add(new SpeedZoneCurve.SpeedZone((long) rangeDur,speed));
                    }
                    StringBuilder sb = new StringBuilder("");
                    for (int i = 0; i < speedZones.size(); i++) {
                        SpeedZoneCurve.SpeedZone sp = speedZones.get(i);
                        sb.append("[").append(sp.getOriginDurationUs()).append(",").append(sp.getSpeed()).append("],");
                    }
                    Log.e(TAG,"SpeedZoneCurve SpeedRange:" + sb);
                    SpeedZoneCurve speedZoneCurve = new SpeedZoneCurve(speedZones);
                    updateSpeedClip(speedZoneCurve);
                }
            }
        });

**曲线变化回调之后的参数设置**

  private void updateSpeedClip(SpeedZoneCurve spc) {
        Clip cp = mProject.getClip(0,true);
        if(cp instanceof MediaClip) {
            MediaClip mcp = (MediaClip) cp;
            mcp.setSpeeds(spc);
            mProject.updateProject();
            mVideoFactory.seekTo(0);
        }
    }

这时候再调用mVideoFactory.play() 就可以播放一个变速之后的视频了

### livePhoto 导出 

   livePhoto导出，就是导出一个封面 +  一个视频

  设置一个封面图片的回调

  mVideoFactory.setCoverGenInfo(time, new OnCoverSavedListener() {
            @Override
            public void onBitMapSaved(Bitmap bmp, int errCode, String errMsg) {

                if(bmp != null) {
                    Logger.i(TAG,"onBitMapSaved " + bmp);
                   } else {
                    Logger.e(TAG,"onBitMapSaveFailed " + errCode + ","+errMsg);
                }
            }
    });

 视频的导出，采用上面的接口mVideoFactory.export(ExportParam param);

### 添加背景音乐

// uri 文件路径， starttime开始的时间， repeat 是否重复

mVideoFactory.setBackGroundMusicSource(String uri, int starttime, boolean repeat) 