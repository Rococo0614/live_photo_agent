package com.vivo.lpa;

import com.vivo.mediaeffectsdk.motioneffect.core.render.gpufilters.videoEffect.GPUDongGanFilter;
import com.vivo.mediaeffectsdk.motioneffect.core.render.opengl.GPUFilter;
import com.vivo.mediaeffectsdk.motioneffect.core.render.opengl.Rotation;
import com.vivo.mediaeffectsdk.motioneffect.core.render.opengl.TextureRotationUtil;
import com.vivo.videoeditorsdk.effect.TimelineEffect;
import com.vivo.videoeditorsdk.render.LayerRender;
import com.vivo.videoeditorsdk.render.RenderData;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Port of the reference project's real MediaEffectSDK timeline filters. */
final class MotionTimelineEffect extends TimelineEffect {
    private static final String MIRROR = "varying lowp vec2 textureCoordinate;\n"
            + "uniform sampler2D inputImageTexture;\n"
            + "void main(){ vec2 uv=vec2(1.0-textureCoordinate.x,textureCoordinate.y);"
            + "gl_FragColor=texture2D(inputImageTexture,uv); }";
    private static final String SPLIT = "varying lowp vec2 textureCoordinate;\n"
            + "uniform sampler2D inputImageTexture;\n"
            + "void main(){ vec2 uv=vec2(fract(textureCoordinate.x*2.0),textureCoordinate.y);"
            + "gl_FragColor=texture2D(inputImageTexture,uv); }";
    private static final String PHANTOM = "precision mediump float; varying vec2 textureCoordinate;\n"
            + "uniform sampler2D inputImageTexture;\n"
            + "void main(){ vec2 uv=textureCoordinate; vec4 base=texture2D(inputImageTexture,uv);"
            + "vec4 ghost=texture2D(inputImageTexture,clamp(uv+vec2(0.035,-0.018),0.0,1.0));"
            + "vec4 red=texture2D(inputImageTexture,clamp(uv+vec2(0.012,0.0),0.0,1.0));"
            + "gl_FragColor=vec4(mix(base.rgb,ghost.rgb,0.34),1.0);"
            + "gl_FragColor.r=mix(gl_FragColor.r,red.r,0.55); }";
    private static final String ILLUSION = "precision mediump float; varying vec2 textureCoordinate;\n"
            + "uniform sampler2D inputImageTexture;\n"
            + "void main(){ vec2 p=textureCoordinate-0.5; p=abs(p);"
            + "vec2 uv=clamp(p*1.72+vec2(0.07,0.07),0.0,1.0);"
            + "vec4 a=texture2D(inputImageTexture,uv);"
            + "vec4 b=texture2D(inputImageTexture,clamp(1.0-uv,0.0,1.0));"
            + "gl_FragColor=mix(a,b,0.28); }";
    private static final String BLUR = "precision mediump float; varying vec2 textureCoordinate;\n"
            + "uniform sampler2D inputImageTexture;\n"
            + "void main(){ vec2 u=textureCoordinate; vec2 d=vec2(0.0032,0.0024);"
            + "vec4 c=texture2D(inputImageTexture,u)*0.20;"
            + "c+=texture2D(inputImageTexture,u+vec2(d.x,0.0))*0.12;"
            + "c+=texture2D(inputImageTexture,u-vec2(d.x,0.0))*0.12;"
            + "c+=texture2D(inputImageTexture,u+vec2(0.0,d.y))*0.12;"
            + "c+=texture2D(inputImageTexture,u-vec2(0.0,d.y))*0.12;"
            + "c+=texture2D(inputImageTexture,u+d)*0.08;"
            + "c+=texture2D(inputImageTexture,u-d)*0.08;"
            + "c+=texture2D(inputImageTexture,u+vec2(d.x,-d.y))*0.08;"
            + "c+=texture2D(inputImageTexture,u+vec2(-d.x,d.y))*0.08;"
            + "gl_FragColor=c; }";

    private final String effect;
    private final int outputWidth;
    private final int outputHeight;
    private final FloatBuffer cubeBuffer;
    private final FloatBuffer rotationBuffer;
    private GPUFilter filter;
    private GPUDongGanFilter motionLight;
    private int width;
    private int height;

    MotionTimelineEffect(String effect, int outputWidth, int outputHeight, int rotation) {
        this.effect = effect == null ? "motion_light" : effect;
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
        cubeBuffer = buffer(TextureRotationUtil.CUBE);
        int textureRotation = (rotation == 90 || rotation == 270) ? 0 : rotation;
        rotationBuffer = buffer(TextureRotationUtil.getRotation(
                Rotation.fromInt(textureRotation), false, false));
    }

    @Override
    public int renderFrame(LayerRender layer, RenderData data, int ptsMs, int unused) {
        int w = Math.max(2, data.getTextureWidth());
        int h = Math.max(2, data.getTextureHeight());
        if (w != width || h != height || (filter == null && motionLight == null)) initialize(w, h);
        layer.setDefaultProjection();
        cubeBuffer.position(0);
        rotationBuffer.position(0);
        if (motionLight != null) {
            com.vivo.mediaeffectsdk.motioneffect.core.render.gpufilters.videoEffect.GPUVideoEffect.DongGanLightParam param =
                    new com.vivo.mediaeffectsdk.motioneffect.core.render.gpufilters.videoEffect.GPUVideoEffect.DongGanLightParam();
            param.isEnable = true;
            param.duration = Math.max(0, ptsMs) / 1000f;
            motionLight.setParam(param);
            motionLight.onDrawFrame(data.nTextureId, cubeBuffer, rotationBuffer);
        } else {
            filter.onDrawFrame(data.nTextureId, cubeBuffer, rotationBuffer);
        }
        return 0;
    }

    private void initialize(int w, int h) {
        releaseFilters();
        width = w;
        height = h;
        if ("motion_light".equals(effect)) {
            motionLight = new GPUDongGanFilter();
            if (!motionLight.init()) throw new IllegalStateException("GPUDongGanFilter init failed");
            motionLight.onOutputSizeChanged(outputWidth, outputHeight);
            return;
        }
        String fragment;
        if ("mirror".equals(effect)) fragment = MIRROR;
        else if ("split".equals(effect)) fragment = SPLIT;
        else if ("phantom".equals(effect)) fragment = PHANTOM;
        else if ("illusion".equals(effect)) fragment = ILLUSION;
        else if ("blur".equals(effect)) fragment = BLUR;
        else throw new IllegalArgumentException("unsupported motion effect: " + effect);
        filter = new GPUFilter(GPUFilter.NO_FILTER_VERTEX_SHADER, fragment);
        if (!filter.init()) throw new IllegalStateException("GPUFilter init failed: " + effect);
        filter.onOutputSizeChanged(outputWidth, outputHeight);
    }

    private static FloatBuffer buffer(float[] values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(values).position(0);
        return buffer;
    }

    private void releaseFilters() {
        if (filter != null) filter.destroy();
        if (motionLight != null) motionLight.destroy();
        filter = null;
        motionLight = null;
    }

    @Override
    public void release() {
        releaseFilters();
    }
}
