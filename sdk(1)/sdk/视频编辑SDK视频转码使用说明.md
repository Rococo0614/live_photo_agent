**一、视频压缩转码**

使用com.vivo.videoeditorsdk.videoeditor.VideoConverter类

开启转码：

    **void** startConvert() {

        mVideoConverter = **new** VideoConverter();

        //设置原视频和转码视频路径

        mVideoConverter.setSourcePath(mFilePath);

        mVideoConverter.setTargetFilePath(mTargetFilePath);

        //设置事件监听器

        ConvertHandler handler = **new** ConvertHandler();

        mVideoConverter.setOnCompletionListener(handler);

        mVideoConverter.setOnProgressChangeListener(handler);

        mVideoConverter.setOnErrorListener(handler);

        //设置转码参数，包括视频宽，高，比特率，帧率

        mVideoConverter.setExportParameter(1280, 720, 2000000, 30);

        //开始转码

        mVideoConverter.start();

        mConvertStartTime = System._currentTimeMillis_();

    }

停止转码

    **void** stopConvert() {

        **if** (mVideoConverter != **null**) {

            mVideoConverter.stop();

            mVideoConverter = **null**;

        }

    }

事件处理

    **class** ConvertHandler **implements** OnProgressChangeListener, OnCompletionListener, OnErrorListener {

        @Override

        **public** **void** onCompletion(VideoConverter vc) {

            //导出成功处理

        }

        @Override

        **public** **void** onProgressChanged(**int** percent, int pts) {

            // 进度显示

        }

        @Override

        **public** **void** onError(**int** errorCode) {

            //转码失败后要停止转码并删除临时文件

stopConvert();

        }

    }

其他接口

public class VideoConverter {

//判断某文件是否支持转码, sourceFilePath是视频文件的路径

public static boolean isSupportConvert(String sourceFilePath)

//预估一个视频压缩后的文件size，durationMs是视频文件总时间 videobitrate是要转码的码率

public static long estimateConvertedFileSize(int durationMs, int videobitrate)

         //获取剩余转码时间，单位毫秒

         public long getRemainingTime()

}

**二、视频压缩效果预览**

使用com.vivo.videoeditorsdk.videoeditor.VideoConverterPreviewer

开启预览

    **void** startEncodePreview() {

        getConvertParams();

        //创建预览对象，并设置回调函数

        mVideoConverterPreviewer = **new** VideoConverterPreviewer();

        mVideoConverterPreviewer.setPreviewStatusCallback(**new** VideoConverterPreviewer.PreviewStatusCallback() {

           

            @Override

            **public** **void** onPreviewTimeChange(**int** ptsMs) {

                Log._v_(TAG, "encode prevew pts " + ptsMs);

                //预览时间控制在10秒

                if (ptsMs > 10000) {

                    stopEncodePreivew();

}

            }

           

            @Override

            **public** **void** onPrevewComplete() {

                Log._v_(TAG, "onPrevewComplete");

                stopEncodePreivew();

            }

           

            @Override

            **public** **void** onError(**int** error) {

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

    **void** stopEncodePreivew() {

        **if** (mVideoConverterPreviewer != **null**) {

            mVideoConverterPreviewer.stop();

            mVideoConverterPreviewer = **null**;

        }

    } 