# Live Photo Agent 成功展示手册

## 推荐展示顺序

1. 打开四阶段看板，先讲清“语言请求→私有动态预览→确认review→撤销且原片安全”：

   ```text
   /mnt/ssd/lpa-aihub-probe/artifacts/android-dynamic-frame-timeline/
     demo-success-vfr-blur/success-board.png
   ```

2. 播放8秒局部动态对比，重点看草地上行走的橘猫；其他区域由mask保护：

   ```text
   demo-success-vfr-blur/before-after-blur-zoom.mp4
   ```

3. 播放35秒真机精剪，展示自然语言、QAIRT 2.45执行、完整动态预览、确认和撤销：

   ```text
   demo-success-vfr-blur/ui-demo-highlight.mp4
   ```

4. 需要审计时再打开`README.md`、`agent-evidence.json`、`vfr-pts.json`或97秒未剪辑录屏。

## 现场讲解要点

- 用户只输入自然语言；box、point、mask、QNN图和DAG都不暴露给用户。
- 演示素材是真VFR，7帧PTS不等间隔；输出PTS逐项相同。
- Qwen决定语义目标，OWLv2定位，EdgeTAM从封面条件帧双向传播，host验证后签发内容绑定证书。
- blur是完整时间线的双pass GLES效果，直接进入MediaCodec surface；不是单帧贴图。
- “确认预览”只接受视觉结果。确认后出现“发布副本”，证明第二道授权门存在；不要在未获授权时点击。
- 演示以“撤销预览”结束，临时目录清零、源SHA不变、相册不新增。

## 复现实验UI

仅实验Debug构建包含只读、未导出的私有演示Provider；Release和默认构建不包含它：

```bash
cd android
./gradlew :app:assembleDebug \
  -PagentRuntime=dag \
  -PlocalTrackedPreview=true \
  -PfullTimelineTrackedPreview=true \
  -PpersistentQnn=true

adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.vivo.lpa/.AgentActivity \
  --es demo_media_uri \
  content://com.vivo.lpa.debugfiles/live-result-1793755405250.jpg \
  --es demo_query '请把草地上正在走的橘猫做局部模糊，模糊半径6像素'
```

点击“发送”后运行真实端侧闭环。展示“查看原图/查看预览”、旋转、确认和撤销即可；除非用户另行明确
授权真实MediaStore写入，否则不要点击“发布副本”。
