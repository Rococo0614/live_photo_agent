package com.vivo.lpa

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** One EGL context and texture set reused across a whole masked-effect timeline. */
class AndroidPersistentTintRenderer private constructor(
    private val width: Int,
    private val height: Int,
    private val effect: Effect,
    colorHex: String?,
    intensity: Double?,
    radiusPx: Double?,
    private val encoderOutputSurface: Surface?
) : Closeable {
    constructor(
        width: Int,
        height: Int,
        colorHex: String,
        intensity: Double
    ) : this(width, height, Effect.TINT, colorHex, intensity, null, null)
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var tintProgram = 0
    private var horizontalBlurProgram = 0
    private var verticalBlurProgram = 0
    private var sourceTexture = 0
    private var maskTexture = 0
    private var horizontalTexture = 0
    private var outputTexture = 0
    private var horizontalFramebuffer = 0
    private var framebuffer = 0
    private var closed = false
    private val blurRadius = radiusPx?.roundToInt()?.coerceIn(1, 64)

    var renderedFrameCount: Int = 0
        private set

    init {
        require(width > 0 && height > 0)
        when (effect) {
            Effect.TINT -> {
                require(colorHex != null && Regex("#[0-9A-Fa-f]{6}").matches(colorHex))
                require(intensity != null && intensity in 0.0..1.0)
                require(radiusPx == null)
            }
            Effect.BLUR -> {
                require(colorHex == null && intensity == null)
                require(radiusPx != null && radiusPx > 0.0)
            }
        }
        try {
            check(display != EGL14.EGL_NO_DISPLAY)
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1))
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val surfaceType = if (encoderOutputSurface == null) {
                EGL14.EGL_PBUFFER_BIT
            } else {
                EGL14.EGL_WINDOW_BIT
            }
            val configAttributes = mutableListOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, surfaceType,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8
            ).apply {
                if (encoderOutputSurface != null) {
                    add(EGL_RECORDABLE_ANDROID)
                    add(1)
                }
                add(EGL14.EGL_NONE)
            }.toIntArray()
            check(EGL14.eglChooseConfig(
                display,
                configAttributes,
                0,
                configs,
                0,
                1,
                count,
                0
            ) && count[0] > 0)
            val config = requireNotNull(configs[0])
            context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0
            )
            check(context != EGL14.EGL_NO_CONTEXT)
            surface = if (encoderOutputSurface == null) {
                EGL14.eglCreatePbufferSurface(
                    display,
                    config,
                    intArrayOf(
                        EGL14.EGL_WIDTH, width,
                        EGL14.EGL_HEIGHT, height,
                        EGL14.EGL_NONE
                    ),
                    0
                )
            } else {
                EGL14.eglCreateWindowSurface(
                    display,
                    config,
                    encoderOutputSurface,
                    intArrayOf(EGL14.EGL_NONE),
                    0
                )
            }
            check(surface != EGL14.EGL_NO_SURFACE)
            check(EGL14.eglMakeCurrent(display, surface, surface, context))
            sourceTexture = createTexture()
            maskTexture = createTexture()
            when (effect) {
                Effect.TINT -> tintProgram = linkProgram(VERTEX_SHADER, TINT_SHADER)
                Effect.BLUR -> {
                    horizontalBlurProgram = linkProgram(VERTEX_SHADER, HORIZONTAL_BOX_BLUR_SHADER)
                    verticalBlurProgram = linkProgram(
                        VERTEX_SHADER,
                        VERTICAL_BOX_BLUR_AND_MASK_SHADER
                    )
                    horizontalTexture = createTexture(allocateRgba = true)
                    horizontalFramebuffer = createFramebuffer(horizontalTexture)
                }
            }
            if (encoderOutputSurface == null) {
                outputTexture = createTexture(allocateRgba = true)
                framebuffer = createFramebuffer(outputTexture)
            }
            if (effect == Effect.TINT) {
                GLES20.glUseProgram(tintProgram)
                val color = requireNotNull(colorHex).removePrefix("#").toInt(16)
                GLES20.glUniform3f(
                    GLES20.glGetUniformLocation(tintProgram, "uTint"),
                    (color ushr 16 and 0xff) / 255f,
                    (color ushr 8 and 0xff) / 255f,
                    (color and 0xff) / 255f
                )
                GLES20.glUniform1f(
                    GLES20.glGetUniformLocation(tintProgram, "uIntensity"),
                    requireNotNull(intensity).toFloat()
                )
            }
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    fun render(source: Bitmap, maskValues: ByteArray): Bitmap {
        require(encoderOutputSurface == null) { "encoder renderer does not support readback" }
        draw(source, maskValues)
        return readBitmap()
    }

    fun renderToEncoder(source: Bitmap, maskValues: ByteArray, presentationTimeUs: Long) {
        require(encoderOutputSurface != null) { "offscreen renderer has no encoder surface" }
        require(presentationTimeUs >= 0)
        draw(source, maskValues)
        check(EGLExt.eglPresentationTimeANDROID(display, surface, presentationTimeUs * 1_000L))
        check(EGL14.eglSwapBuffers(display, surface))
    }

    private fun draw(source: Bitmap, maskValues: ByteArray) {
        check(!closed)
        require(!source.isRecycled && source.width == width && source.height == height)
        require(maskValues.size == width * height)
        check(EGL14.eglMakeCurrent(display, surface, surface, context))
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTexture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, source, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTexture)
        val mask = ByteBuffer.allocateDirect(maskValues.size).apply {
            put(maskValues)
            rewind()
        }
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_LUMINANCE,
            width,
            height,
            0,
            GLES20.GL_LUMINANCE,
            GLES20.GL_UNSIGNED_BYTE,
            mask
        )
        when (effect) {
            Effect.TINT -> drawTint()
            Effect.BLUR -> drawBlur()
        }
        renderedFrameCount += 1
    }

    private fun drawTint() {
        bindOutputFramebuffer()
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(tintProgram)
        bindTexture(tintProgram, "uSource", sourceTexture, 0)
        bindTexture(tintProgram, "uMask", maskTexture, 1)
        drawQuad(tintProgram, encoderCoordinates = encoderOutputSurface != null)
        checkGl("persistent tint draw")
    }

    private fun drawBlur() {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, horizontalFramebuffer)
        checkFramebuffer()
        GLES20.glUseProgram(horizontalBlurProgram)
        bindTexture(horizontalBlurProgram, "uSource", sourceTexture, 0)
        setBlurUniforms(horizontalBlurProgram)
        drawQuad(horizontalBlurProgram, encoderCoordinates = false)
        checkGl("persistent horizontal box blur")

        bindOutputFramebuffer()
        GLES20.glUseProgram(verticalBlurProgram)
        bindTexture(verticalBlurProgram, "uSource", sourceTexture, 0)
        bindTexture(verticalBlurProgram, "uHorizontal", horizontalTexture, 1)
        bindTexture(verticalBlurProgram, "uMask", maskTexture, 2)
        setBlurUniforms(verticalBlurProgram)
        drawQuad(verticalBlurProgram, encoderCoordinates = encoderOutputSurface != null)
        checkGl("persistent vertical box blur and mask blend")
    }

    private fun bindOutputFramebuffer() {
        GLES20.glBindFramebuffer(
            GLES20.GL_FRAMEBUFFER,
            if (encoderOutputSurface == null) framebuffer else 0
        )
        if (encoderOutputSurface == null) checkFramebuffer()
    }

    private fun checkFramebuffer() {
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) ==
            GLES20.GL_FRAMEBUFFER_COMPLETE)
    }

    private fun setBlurUniforms(targetProgram: Int) {
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(targetProgram, "uTexelSize"),
            1f / width,
            1f / height
        )
        GLES20.glUniform1i(
            GLES20.glGetUniformLocation(targetProgram, "uRadius"),
            requireNotNull(blurRadius)
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        if (display != EGL14.EGL_NO_DISPLAY && context != EGL14.EGL_NO_CONTEXT &&
            surface != EGL14.EGL_NO_SURFACE
        ) {
            EGL14.eglMakeCurrent(display, surface, surface, context)
            intArrayOf(tintProgram, horizontalBlurProgram, verticalBlurProgram)
                .filter { it != 0 }
                .forEach { GLES20.glDeleteProgram(it) }
            val framebuffers = intArrayOf(horizontalFramebuffer, framebuffer)
                .filter { it != 0 }
                .toIntArray()
            if (framebuffers.isNotEmpty()) {
                GLES20.glDeleteFramebuffers(framebuffers.size, framebuffers, 0)
            }
            val textures = intArrayOf(
                sourceTexture,
                maskTexture,
                horizontalTexture,
                outputTexture
            ).filter { it != 0 }.toIntArray()
            if (textures.isNotEmpty()) GLES20.glDeleteTextures(textures.size, textures, 0)
        }
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    private fun createTexture(allocateRgba: Boolean = false): Int {
        val value = IntArray(1)
        GLES20.glGenTextures(1, value, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, value[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        if (allocateRgba) {
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
            )
        }
        checkGl("persistent texture creation")
        return value[0]
    }

    private fun createFramebuffer(texture: Int): Int {
        val value = IntArray(1)
        GLES20.glGenFramebuffers(1, value, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, value[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            texture,
            0
        )
        checkGl("persistent framebuffer")
        return value[0]
    }

    private fun bindTexture(targetProgram: Int, name: String, texture: Int, unit: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(targetProgram, name), unit)
    }

    private fun drawQuad(targetProgram: Int, encoderCoordinates: Boolean) {
        val bottomV = if (encoderCoordinates) 1f else 0f
        val topV = if (encoderCoordinates) 0f else 1f
        val vertices = ByteBuffer.allocateDirect(16 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(floatArrayOf(
                -1f, -1f, 0f, bottomV,
                1f, -1f, 1f, bottomV,
                -1f, 1f, 0f, topV,
                1f, 1f, 1f, topV
            )).also { it.position(0) }
        vertices.position(0)
        val position = GLES20.glGetAttribLocation(targetProgram, "aPosition")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices)
        vertices.position(2)
        val texCoord = GLES20.glGetAttribLocation(targetProgram, "aTexCoord")
        GLES20.glEnableVertexAttribArray(texCoord)
        GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun readBitmap(): Bitmap {
        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        checkGl("persistent read pixels")
        buffer.rewind()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.copyPixelsFromBuffer(buffer)
        }
    }

    private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val result = GLES20.glCreateProgram()
        GLES20.glAttachShader(result, vertex)
        GLES20.glAttachShader(result, fragment)
        GLES20.glLinkProgram(result)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        val status = IntArray(1)
        GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(result) }
        return result
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(shader) }
        return shader
    }

    private fun checkGl(stage: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "GLES error $error at $stage" }
    }

    private enum class Effect { TINT, BLUR }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        fun forEncoder(
            width: Int,
            height: Int,
            colorHex: String,
            intensity: Double,
            outputSurface: Surface
        ): AndroidPersistentTintRenderer = AndroidPersistentTintRenderer(
            width,
            height,
            Effect.TINT,
            colorHex,
            intensity,
            null,
            outputSurface
        )

        fun forBlur(
            width: Int,
            height: Int,
            radiusPx: Double
        ): AndroidPersistentTintRenderer = AndroidPersistentTintRenderer(
            width,
            height,
            Effect.BLUR,
            null,
            null,
            radiusPx,
            null
        )

        fun forEncoderBlur(
            width: Int,
            height: Int,
            radiusPx: Double,
            outputSurface: Surface
        ): AndroidPersistentTintRenderer = AndroidPersistentTintRenderer(
            width,
            height,
            Effect.BLUR,
            null,
            null,
            radiusPx,
            outputSurface
        )

        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """
        const val TINT_SHADER = """
            precision highp float;
            uniform sampler2D uSource;
            uniform sampler2D uMask;
            uniform vec3 uTint;
            uniform float uIntensity;
            varying vec2 vTexCoord;
            void main() {
                vec4 source = texture2D(uSource, vTexCoord);
                float amount = clamp(texture2D(uMask, vTexCoord).r * uIntensity, 0.0, 1.0);
                gl_FragColor = vec4(mix(source.rgb, uTint, amount), source.a);
            }
        """

        const val HORIZONTAL_BOX_BLUR_SHADER = """
            precision highp float;
            uniform sampler2D uSource;
            uniform vec2 uTexelSize;
            uniform int uRadius;
            varying vec2 vTexCoord;
            void main() {
                vec3 sum = vec3(0.0);
                float count = 0.0;
                for (int offset = -64; offset <= 64; ++offset) {
                    if (offset >= -uRadius && offset <= uRadius) {
                        vec2 coordinate = vTexCoord + vec2(float(offset) * uTexelSize.x, 0.0);
                        if (coordinate.x >= 0.0 && coordinate.x <= 1.0) {
                            vec3 sampleValue = texture2D(uSource, coordinate).rgb;
                            sum += floor(sampleValue * 255.0 + 0.5);
                            count += 1.0;
                        }
                    }
                }
                vec4 source = texture2D(uSource, vTexCoord);
                gl_FragColor = vec4(floor(sum / count) / 255.0, source.a);
            }
        """

        const val VERTICAL_BOX_BLUR_AND_MASK_SHADER = """
            precision highp float;
            uniform sampler2D uSource;
            uniform sampler2D uHorizontal;
            uniform sampler2D uMask;
            uniform vec2 uTexelSize;
            uniform int uRadius;
            varying vec2 vTexCoord;
            void main() {
                vec3 sum = vec3(0.0);
                float count = 0.0;
                for (int offset = -64; offset <= 64; ++offset) {
                    if (offset >= -uRadius && offset <= uRadius) {
                        vec2 coordinate = vTexCoord + vec2(0.0, float(offset) * uTexelSize.y);
                        if (coordinate.y >= 0.0 && coordinate.y <= 1.0) {
                            vec3 sampleValue = texture2D(uHorizontal, coordinate).rgb;
                            sum += floor(sampleValue * 255.0 + 0.5);
                            count += 1.0;
                        }
                    }
                }
                vec4 sourceSample = texture2D(uSource, vTexCoord);
                vec3 source = floor(sourceSample.rgb * 255.0 + 0.5);
                vec3 blurred = floor(sum / count);
                float mask = floor(texture2D(uMask, vTexCoord).r * 255.0 + 0.5);
                vec3 outputValue = floor(
                    (source * (255.0 - mask) + blurred * mask) / 255.0 + 0.5
                );
                gl_FragColor = vec4(outputValue / 255.0, sourceSample.a);
            }
        """
    }
}
