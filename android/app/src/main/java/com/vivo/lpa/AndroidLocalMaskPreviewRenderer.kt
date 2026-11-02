package com.vivo.lpa

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

data class LocalMaskPreviewFrame(val bitmap: Bitmap, val backendId: String)

/**
 * Isolated in-memory frame renderer. It is not registered as an Agent executor and never writes
 * source media. Both effects use mask-aware GLES shaders and fall back to the exact CPU oracle if
 * the device cannot create or execute the required offscreen GL pipeline.
 */
object AndroidLocalMaskPreviewRenderer {
    const val BACKEND_GLES_COLOR_TINT = "gles_mask_color_tint"
    const val BACKEND_GLES_BOX_BLUR = "gles_mask_box_blur"
    const val BACKEND_CPU_FALLBACK = "cpu_reference_fallback"

    fun render(
        source: Bitmap,
        mask: Bitmap,
        change: LocalDeterministicChange
    ): LocalMaskPreviewFrame {
        require(source.width == mask.width && source.height == mask.height)
        require(!source.isRecycled && !mask.isRecycled)
        return try {
            when (change) {
                is LocalDeterministicChange.ColorTint -> LocalMaskPreviewFrame(
                    GlesLocalMaskRenderer.renderColorTint(source, mask, change),
                    BACKEND_GLES_COLOR_TINT
                )
                is LocalDeterministicChange.Blur -> LocalMaskPreviewFrame(
                    GlesLocalMaskRenderer.renderBoxBlur(source, mask, change),
                    BACKEND_GLES_BOX_BLUR
                )
            }
        } catch (_: Exception) {
            LocalMaskPreviewFrame(renderCpu(source, mask, change), BACKEND_CPU_FALLBACK)
        }
    }

    internal fun renderCpu(
        source: Bitmap,
        mask: Bitmap,
        change: LocalDeterministicChange
    ): Bitmap {
        require(source.width == mask.width && source.height == mask.height)
        require(!source.isRecycled && !mask.isRecycled)
        val pixels = IntArray(source.width * source.height)
        val maskPixels = IntArray(pixels.size)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        mask.getPixels(maskPixels, 0, mask.width, 0, 0, mask.width, mask.height)
        val maskValues = ByteArray(maskPixels.size) { index ->
            (maskPixels[index] ushr 16 and 0xff).toByte()
        }
        val output = LocalMaskChangeReferenceRenderer.renderArgb(
            source.width,
            source.height,
            pixels,
            maskValues,
            change
        )
        return Bitmap.createBitmap(output, source.width, source.height, Bitmap.Config.ARGB_8888)
    }
}

private object GlesLocalMaskRenderer {
    private const val VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """
    private const val FRAGMENT_SHADER = """
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

    /*
     * The CPU oracle truncates after the horizontal pass. Recovering every sampled UNORM channel
     * as an integer and explicitly flooring the average preserves that behavior before the result
     * is stored in the RGBA8 intermediate texture.
     */
    private const val HORIZONTAL_BOX_BLUR_SHADER = """
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

    /*
     * The vertical pass also performs the final mask blend. Its integer-domain formula is the
     * same as round(source * (1 - mask/255) + blurred * mask/255) in the CPU oracle.
     */
    private const val VERTICAL_BOX_BLUR_AND_MASK_SHADER = """
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
            vec3 outputValue = floor((source * (255.0 - mask) + blurred * mask) / 255.0 + 0.5);
            gl_FragColor = vec4(outputValue / 255.0, sourceSample.a);
        }
    """

    fun renderColorTint(
        source: Bitmap,
        mask: Bitmap,
        change: LocalDeterministicChange.ColorTint
    ): Bitmap {
        val egl = EglSession(source.width, source.height)
        try {
            val sourceTexture = egl.upload(source)
            val maskTexture = egl.upload(mask)
            val outputTexture = egl.createOutputTexture()
            val framebuffer = egl.createFramebuffer(outputTexture)
            val program = linkProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            try {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
                checkFramebuffer()
                GLES20.glViewport(0, 0, source.width, source.height)
                GLES20.glUseProgram(program)
                bindTexture(program, "uSource", sourceTexture, 0)
                bindTexture(program, "uMask", maskTexture, 1)
                val color = change.colorHex.removePrefix("#").toInt(16)
                GLES20.glUniform3f(
                    GLES20.glGetUniformLocation(program, "uTint"),
                    (color ushr 16 and 0xff) / 255f,
                    (color ushr 8 and 0xff) / 255f,
                    (color and 0xff) / 255f
                )
                GLES20.glUniform1f(
                    GLES20.glGetUniformLocation(program, "uIntensity"),
                    change.intensity.toFloat()
                )
                drawQuad(program)
                checkGl("draw")
                return egl.readBitmap()
            } finally {
                GLES20.glDeleteProgram(program)
                GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
                GLES20.glDeleteTextures(
                    3,
                    intArrayOf(sourceTexture, maskTexture, outputTexture),
                    0
                )
            }
        } finally {
            egl.close()
        }
    }

    fun renderBoxBlur(
        source: Bitmap,
        mask: Bitmap,
        change: LocalDeterministicChange.Blur
    ): Bitmap {
        val radius = change.radiusPx.roundToInt().coerceIn(1, 64)
        val egl = EglSession(source.width, source.height)
        try {
            val sourceTexture = egl.upload(source)
            val maskTexture = egl.upload(mask)
            val horizontalTexture = egl.createOutputTexture()
            val outputTexture = egl.createOutputTexture()
            val horizontalFramebuffer = egl.createFramebuffer(horizontalTexture)
            val outputFramebuffer = egl.createFramebuffer(outputTexture)
            val horizontalProgram = linkProgram(VERTEX_SHADER, HORIZONTAL_BOX_BLUR_SHADER)
            val verticalProgram = linkProgram(VERTEX_SHADER, VERTICAL_BOX_BLUR_AND_MASK_SHADER)
            try {
                GLES20.glViewport(0, 0, source.width, source.height)

                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, horizontalFramebuffer)
                checkFramebuffer()
                GLES20.glUseProgram(horizontalProgram)
                bindTexture(horizontalProgram, "uSource", sourceTexture, 0)
                setBlurUniforms(horizontalProgram, source.width, source.height, radius)
                drawQuad(horizontalProgram)
                checkGl("horizontal box blur")

                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFramebuffer)
                checkFramebuffer()
                GLES20.glUseProgram(verticalProgram)
                bindTexture(verticalProgram, "uSource", sourceTexture, 0)
                bindTexture(verticalProgram, "uHorizontal", horizontalTexture, 1)
                bindTexture(verticalProgram, "uMask", maskTexture, 2)
                setBlurUniforms(verticalProgram, source.width, source.height, radius)
                drawQuad(verticalProgram)
                checkGl("vertical box blur and mask blend")
                return egl.readBitmap()
            } finally {
                GLES20.glDeleteProgram(horizontalProgram)
                GLES20.glDeleteProgram(verticalProgram)
                GLES20.glDeleteFramebuffers(
                    2,
                    intArrayOf(horizontalFramebuffer, outputFramebuffer),
                    0
                )
                GLES20.glDeleteTextures(
                    4,
                    intArrayOf(sourceTexture, maskTexture, horizontalTexture, outputTexture),
                    0
                )
            }
        } finally {
            egl.close()
        }
    }

    private fun setBlurUniforms(program: Int, width: Int, height: Int, radius: Int) {
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(program, "uTexelSize"),
            1f / width,
            1f / height
        )
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uRadius"), radius)
    }

    private fun drawQuad(program: Int) {
        val vertices = floatBuffer(floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f
        ))
        vertices.position(0)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices)
        vertices.position(2)
        val texCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(texCoord)
        GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun checkFramebuffer() {
        check(
            GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) ==
                GLES20.GL_FRAMEBUFFER_COMPLETE
        ) { "GLES framebuffer is incomplete" }
    }

    private fun bindTexture(program: Int, name: String, texture: Int, unit: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, name), unit)
    }

    private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            error("GLES link failed: $log")
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("GLES compile failed: $log")
        }
        return shader
    }

    private fun checkGl(stage: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "GLES error $error at $stage" }
    }

    private fun floatBuffer(values: FloatArray) =
        ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(values)
            .also { it.position(0) }

    private class EglSession(private val width: Int, private val height: Int) {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var closed = false

        init {
            try {
                check(display != EGL14.EGL_NO_DISPLAY) { "EGL display is unavailable" }
                val version = IntArray(2)
                check(EGL14.eglInitialize(display, version, 0, version, 1))
                val configs = arrayOfNulls<EGLConfig>(1)
                val count = IntArray(1)
                check(EGL14.eglChooseConfig(
                    display,
                    intArrayOf(
                        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                        EGL14.EGL_RED_SIZE, 8,
                        EGL14.EGL_GREEN_SIZE, 8,
                        EGL14.EGL_BLUE_SIZE, 8,
                        EGL14.EGL_ALPHA_SIZE, 8,
                        EGL14.EGL_NONE
                    ),
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
                check(context != EGL14.EGL_NO_CONTEXT) { "EGL context creation failed" }
                surface = EGL14.eglCreatePbufferSurface(
                    display,
                    config,
                    intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE),
                    0
                )
                check(surface != EGL14.EGL_NO_SURFACE) { "EGL pbuffer creation failed" }
                check(EGL14.eglMakeCurrent(display, surface, surface, context))
            } catch (failure: Exception) {
                close()
                throw failure
            }
        }

        fun upload(bitmap: Bitmap): Int {
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            checkGl("texture upload")
            return textures[0]
        }

        fun createOutputTexture(): Int {
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_RGBA,
                width,
                height,
                0,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                null
            )
            checkGl("output texture allocation")
            return textures[0]
        }

        fun createFramebuffer(texture: Int): Int {
            val framebuffers = IntArray(1)
            GLES20.glGenFramebuffers(1, framebuffers, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[0])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,
                texture,
                0
            )
            checkGl("framebuffer attachment")
            return framebuffers[0]
        }

        fun readBitmap(): Bitmap {
            val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(
                0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer
            )
            checkGl("read pixels")
            buffer.rewind()
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                it.copyPixelsFromBuffer(buffer)
            }
        }

        fun close() {
            if (closed) return
            closed = true
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
}
