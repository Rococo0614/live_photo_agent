## <span style="color:#172B4D">**音频重采样接口AudioResample**</span>

调用流程：

1.创建一个AudioResample对象：

AudioResample mAudioResample = new AudioResample();

2. 调用resample接口对音频做重采样，重采样的结果直接返回

byte[] destData = mAudioResample.resample(srcData, nInputSampleRate, sampleCount, nInputChannelCount,

nInputBitsPerSampe, nOutputSampleRate, nOutputChannelCount, nOutputBitsPerSampe);

3.使用完成后释放资源

mAudioResample.release();

参数说明：

/**
* 重采样函数，用于更改音频数据的采样率。
*
* @param srcBuffer 输入PCM数组
* @param inSampleRate 输入采样率
* @param sampleCount 输入采样点数
* @param inChannels 输入声道数，如单声道用1表示，双声道用2表示
* @param inBitsPerSample 输入的位宽，一般为16bit 或者 32bit
* @param outSampleRate 输出采样率
* @param outChannels 输出声道数
* @param outBitsPerSample 输出位宽
* @return
*/
public native byte[] resample(byte[] srcBuffer, int inSampleRate, int sampleCount, int inChannels, int inBitsPerSample, int outSampleRate, int outChannels, int outBitsPerSample);

## <span style="color:#172B4D">**声音淡入淡出**</span>

淡入调用接口

 /*
  * 声音淡入处理
  *pcmBuffer 传入的pcm
  * bufferLength pcm的长度
  * fadeouTotalCount 需要做淡出处理的总sample count
 * feadoutIndex 当前pcm buffer首地址在淡出总sample中的位置，从0开始
 */
AudioEditor.fadein(byte[] pcmBuffer, int bufferLength, long fadeinTotalCount, long feadoutIndex);

淡出调用接口
 /*
 * 声音淡出处理
  *pcmBuffer 传入的pcm
 * bufferLength pcm的长度
 * fadeouTotalCount 需要做淡出处理的总sample count
 * feadoutIndex 当前pcm buffer首地址在淡出总sample中的位置，从0开始
 */
AudioEditor.fadeout(byte[] pcmBuffer, int bufferLength, long fadeouTotalCount, long feadoutIndex);

## <span style="color:#172B4D">**文本朗读**</span>

接口定义
TextOverlay.TTSParameters tTSParameters = new TextOverlay.TTSParameters.Builder()
        .setAudioFile("/sdcard/Music/台湾小哥.wav") //设置文本朗读的音频路径
        .setSpeed(2.0f) //设置文本朗读的播放速度 默认1.0f
        .setNeedAudioSonic(true) //设置文本朗读的播放速度 默认true，不变调
        .build();

textOverlay.setTTSParameters(tTSParameters);

## <span style="color:#172B4D">**视频变声**</span>

 接口定义
    /**
     * 变声算法分两种：
     * 1、本地变声算法：此时素材全段变声，只需要两个参数：
     *      a、设置 mode 为 VoiceChanger.VoiceChangeMode.OFFLINE
     *      b、VCPType 变声类型
     *      c、volume 本地变声算法增益，一般取值范围0.5~2.0，不设置则采用默认增益

     * 2、在线变声算法：需要应用设置三个参数：
     *      a、设置 mode 为 VoiceChanger.VoiceChangeMode.ONLINE
     *      b、在线变声算法处理后的PCM音频文件路径；
     *      c、PCM文件对应着原始文件中的起始时间和结束时间
     *      d、在线变声后的pcm采样率（默认24000）和声道（默认1）
     */
VoiceChanger.VoiceChangeParameters voiceChangeParameters =
        new VoiceChanger.VoiceChangeParameters.Builder()
                .setMode(VoiceChanger.VoiceChangeMode.OFFLINE)//用于设置变声模式，有 '无变声'、'离线变声'、'在线变声'，默认为 '无变声'
                .setVCPType(VoiceChanger.VCPType.VCP_TYPE_CHIPMUNK)//设置本地变声算法类型，以便SDK调用算法
                .setStartTime()//PCM文件对应着原始文件中的起始时间
                .setEndTime()//PCM文件对应着原始文件中的结束时间
                .setOnlineAudioFilePath()//设置在线变声算法处理后的PCM音频文件路径
                                                                .setOnlineAudioSampleRate(24000)
                                                                .setOnlineAudioChannelCount(1)
                .build();

返回使用该实例参数初始化的{@link VoiceChangeParameters.Builder}. 可以用于调整参数
VoiceChangeParameters.Builder builder = voiceChangeParameters.buildUpon();

videoClip.setVoiceChangeParams(voiceChangeParameters);
    //默认本地变声效果增益，一般取值0.5f~2.0f
    public static float getDefaultVcpVolume(VoiceChanger.VCPType vcpType) {
        switch (vcpType) {
            case VCP_TYPE_ROBOT:
                return 1.2f;//1.25f;
            case VCP_TYPE_UNCLE:
                return 1.15f;//1.35f;
            case VCP_TYPE_MICKING:
                return 1.1f;//1.2f;
            case VCP_TYPE_CHIPMUNK:
                return 1.2f;//1.25f;
            case VCP_TYPE_VIBRATO:
                return 1.2f;//1.35f;
            default:
                return 1.0f;
        }
    }

## <span style="color:#172B4D">**视频静音 **</span>

<span style="color:#172B4D">**   **</span><span style="color:#172B4D">把一个mp4文件去掉音频轨道</span>

        boolean moveAudioTrack = true;  //是否去掉音轨，  不去掉就是把音轨替换成静音的aac
        AudioUtils mAudioUtils = new AudioUtils();

        if(moveAudioTrack)
        {
            mAudioUtils.removeAudioTrack(mFilePath, mTargetFilePath, new AudioUtils.AudioUtilCb() {
                @Override
                public void AudioCallBack(boolean suc, int errorCode, String errorMsg) {
                    Logger.i(TAG, "AudioUtils.removeAudioTrack suc = " + suc + ", errorCode = " + errorCode + " , errorMsg = " + errorMsg);
                }
            });
        }
        else
        {
            mAudioUtils.setAudioTrackMute(mFilePath, mTargetFilePath, new AudioUtils.AudioUtilCb() {
                @Override
                public void AudioCallBack(boolean suc, int errorCode, String errorMsg) {
                    Logger.i(TAG, "AudioUtils.setAudioTrackMute suc = " + suc + ", errorCode = " + errorCode + " , errorMsg = " + errorMsg);
                }
            });
        }

## <span style="color:#172B4D">**两个视频合成一个有声音视频 **</span>

<span style="color:#172B4D">**   场景：ai消除的时候，原视频包含了音轨，ai消除之后没有了音轨，这时候把两个视频合成一个消除之后有音轨的视频**</span>

<span style="color:#172B4D">    MuxerUtils mix = new MuxerUtils();</span>
<span style="color:#172B4D">        mix.setMixCb(new MuxerUtils.MixUtilCb() {</span>
<span style="color:#172B4D">            @Override</span>
<span style="color:#172B4D">            public void MixCallBack(boolean suc, int errorCode, String errorMsg) {</span>
<span style="color:#172B4D">                Logger.i(TAG, " mp4Mix  suc = " + suc + " , errorCode = " + errorCode + ", errorMsg = " + errorMsg);</span>
<span style="color:#172B4D">            }</span>
<span style="color:#172B4D">        });</span>
<span style="color:#172B4D">        mix.mixMp4("/sdcard/DCIM/aac.mp4", "/sdcard/DCIM/noAac.mp4", "/sdcard/DCIM/mix.mp4");</span>
<span style="color:#172B4D">//        try {</span>
<span style="color:#172B4D">//            Thread.sleep(15);</span>
<span style="color:#172B4D">//        } catch (Exception e) {</span>
<span style="color:#172B4D">//            throw new RuntimeException(e);</span>
<span style="color:#172B4D">//        }</span>
<span style="color:#172B4D">//        mix.cancelMix();</span>

## <span style="color:#172B4D">**获取文件音频振幅**</span>

<span style="color:#172B4D">**场景：获取文件的音频振幅，绘画波动条**</span>

<span style="color:#172B4D">**  **</span><span style="color:#172B4D">  WaveFormGenerator mWaveFormGenerator = new WaveFormGenerator(filePath);</span>
<span style="color:#172B4D">        mWaveFormGenerator.setOnWaveGeneratedCompleteListener(new WaveFormGenerator.OnWaveGeneratedCompleteListener() {</span>
<span style="color:#172B4D">            @Override</span>
<span style="color:#172B4D">            public void onComplete(WaveFormGenerator generator, int extra, short[] gains) {</span>
<span style="color:#172B4D">                // 回调返回振幅     gains 数组长度代表数量，gains[]  代表幅度</span>

<span style="color:#172B4D">                //extra  负数代表失败了</span>
<span style="color:#172B4D">            }</span>
<span style="color:#172B4D">        });</span>

<span style="color:#172B4D">// samplesCount 样品数量，  start_time 文件开始时间，  end_time 文件结束时间</span>
<span style="color:#172B4D">        mWaveFormGenerator.generateWaveFormData(final int samplesCount,final long start_time,final long end_time)</span>