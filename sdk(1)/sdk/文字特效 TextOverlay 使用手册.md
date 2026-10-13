# **TextOverlay 使用示例**

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

# **TextOverlay 构造方法**

**TextOverlay(String titleText, float centerX, float centerY, int startTimeMs, int endTimeMs, String fontPath)**
       构造一个文字内容为 titleText，中心点在(centerX, centerY)，在时间线上起止时间为(startTimeMs, endTimeMs)的 TextOverlay，其文字字体路径为 fontPath

**TextOverlay(String titleText, float centerX, float centerY, int startTimeMs, int endTimeMs)**
       构造一个文字内容为 titleText，中心点在(centerX, centerY)，在时间线上起止时间为(startTimeMs, endTimeMs)的 TextOverlay

**TextOverlay(float centerX, float centerY, int startTimeMs, int endTimeMs)**
       构造一个中心点在(centerX, centerY)，在时间线上起止时间为(startTimeMs, endTimeMs)的 TextOverlay，其文字内容为空

# **TextOverlay 文字内容**

**public int getLineCount()**
       获取 TextOverlay 上文字行数，目前**已经废弃**，用 getUserTextCount 代替

**public int getUserTextCount()**
       获取用户可编辑文字区域个数

**public void setText(String text)**
       设置用户自定义文字。默认设置在 line 等于 0 的位置，即默认设置在第一行。

**public void setText(int line, String text)**
       设置用户自定义文字。line 表示行数，取值范围在 0 ~ getUserTextCount()-1；text 表示文字内容。

**public String getText(int line)**
       根据行号，获取文字内容。line 表示行数，取值范围在 0 ~ getUserTextCount()-1；

**public String getDefaultUserText(int line)**
       根据行号，获取文字特效的默认文字内容。line 表示行数，取值范围在 0 ~ getUserTextCount()-1；

# **TextOverlay 文字颜色**

**public void setTextColor(int color)**

       设置 TextOverlay 文字颜色。color 值可以通过 android.graphics.Color 来解析获取，并且已经禁用深色模式的反色显示功能。
**public int getTextColor()**

       获取 TextOverlay 文字颜色。

# **TextOverlay 文字透明度**

**public void setTextAlpha(float alpha)**
       设置文字的透明度。alpha 0 ~ 1 之间的浮点数，0表示全透明，1表示不透明。

**public float getTextAlpha()**
       获取文字的透明度

# **TextOverlay 文字特效样式***

<span style="color:#FF0000">**文字特效样式必须要设置，不然默认不会有内容刷新**</span>

**public void setOverlayEffect(String effectID)**
       设置 TextOverlay 要使用的特效ID。effectID 表示内置文字特效包的路径；旧版文字特效设置接口，仅支持特效："标准"、"展开"、"划入"、"标题"、"聚焦"、"棱镜"、"闪烁"、"擦除"
       比如内置文字特效包路径可以采用如下方式：
              "assets:/theme/Overlay_zoomappearline";//标准
              "assets:/theme/Overlay_zoomappear";//展开
              "assets:/theme/Overlay_right";//划入
              "assets:/theme/Overlay_line";//标题
              "assets:/theme/Overlay_blur";//聚焦
              "assets:/theme/Overlay_merge";//棱镜
              "assets:/theme/Overlay_randombox";//闪烁
              "assets:/theme/Overlay_Draw";//擦除

<span style="color:#FF0000">**public void setOverlayEffectByPath(String overlayEffectByPath)**</span>
       设置 TextOverlay 要使用的特效路径。overlayEffectByPath 表示存放文字特效包的路径；基于 setOverlayEffect 接口扩展，除了支持内置文字特效包以外，还增加了很多新的文字特效，**推荐使用**<span style="color:#FF0000">** setOverlayEffectByPath **</span>**来设置文字特效**。

# **TextOverlay 文字变换（中心点位置、缩放、旋转）**

**1、第一步创建 TextOverlay 对象**
       TextOverlay textOverlay = new TextOverlay(titleText, centerX, centerY, startTimeMs, endTimeMs);

**2、获取 TextOverlay 变换参数设置接口**
       OverlayParameters parameters = textOverlay.getParamters();

**3、**<span style="color:#FF0000">**设置 TextOverlay 中心点、缩放比例、旋转角度**</span>

**        3.1 中心点**
**                parameters.setOverlayPosition(x, y);** //x overlay的中心点在opengl坐标系上的x坐标位置; y overlay的中心点在opengl坐标系上的y坐标位置; 取值范围 -1 ~ 1
**        3.2 缩放**
                **parameters.setOverlayScale(scale_X, scale_Y, scale_Z);** //scale_X 表示在X轴上缩放比例; scale_Y 表示在Y轴上缩放比例; scale_Z 表示在Z轴上缩放比例; 
        或者
                **parameters.scale(scale);** //scale 缩放系数; 该方法表示在原有X轴和Y轴缩放比例的基础上，再乘以缩放系数 scale。
**        3.3 旋转**
               ** parameters.setOverlayRotation(rotX, rotY, rotZ);** //rotX 表示X轴上旋转角度; rotY 表示Y轴上旋转角度; rotZ 表示Z轴上旋转角度; 取值范围 0 ~ 360。

**4、将 TextOverlay 变换参数设置进 TextOverlay 对象**
       textOverlay.setParam(parameters);

# **TextOverlay 文字字体格式**

**public void setFontPath(String fontPath)**
       设置 TextOverlay 文字字体。fontPath 表示文字字体路径。

**public String getFontPath()**
       获取 TextOverlay 的文字字体路径。如果构造函数没有指定或者没用调用 setFontPath 设置，返回值为 null。

# **TextOverlay 文字特效信息**

当 TextOverlay 设置完文字特效样式后，TextOverlay 都<span style="color:#FF0000">至少会有一个默认的文字特效信息类 UserTextInfo</span>，可以通过 textOverlay.getUserTextInfo() 获取；通过更改 UserTextInfo 的参数，可以达到更新特效样式中所配置参数的目的。

**1、获取 TextOverlay 文字特效参数设置接口**
       UserTextInfo userTextInfo = textOverlay.getUserTextInfo()

**2、设置 TextOverlay 文字特效参数，比如文字对齐方式、背景色等**
**         2.1 文字水平对齐方式，可以使用的值包括：UserTextInfo.AlignCenterString; UserTextInfo.AlignLeftString; UserTextInfo.AlignRightString**
**                **userTextInfo.setTextHAlign(UserTextInfo.AlignRightString);
**        2.2 文字垂直对齐方式，可以使用的值包括：UserTextInfo.AlignCenterString; UserTextInfo.AlignTopString; UserTextInfo.AlignBottomString**
**                **userTextInfo.setTextVAlign(UserTextInfo.AlignCenterString);
**        2.3 文字背景色**
**                **userTextInfo.setBackgroundColor(Color.parseColor("#7FFF0000"));

**3、将 TextOverlay 文字特效参数设置进 TextOverlay 对象**
       textOverlay.setUserTextInfo(userTextInfo);