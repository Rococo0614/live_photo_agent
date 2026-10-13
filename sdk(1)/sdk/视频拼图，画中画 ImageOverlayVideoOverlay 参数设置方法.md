//创建一个overlay
VideoOverlay videoOverLay = VideoOverlay.createOverlay(filePath);

//获取overlay显示参数
OverlayParameters videoParams = videoOverLay.getParamters();
//设置overlay沿着x,y,z坐标轴的旋转角度
videoParams.setOverlayRotation(<span style="color:#0000FF">0</span>, <span style="color:#0000FF">0</span>, <span style="color:#0000FF">90</span>);
//设置overlay中心点在opengl坐标系中的位置，取值范围为 -1 ~ 1，0,0为中心点
videoParams.setOverlayPosition(<span style="color:#660E7A">0， 0</span>);
//设置overlay的在opengl坐标系中的显示宽高，由于opengl坐标系的x,y取值范围是-1~1，所以宽高为2表示全屏显示
videoParams.setOverlaySize(<span style="color:#0000FF">2.0f, 2.0f</span>);

<span style="color:#660E7A">//把overlay添加但VideoProject</span>
<span style="color:#660E7A">mVideoProject</span>.addOverlayItem(videoOverLay);