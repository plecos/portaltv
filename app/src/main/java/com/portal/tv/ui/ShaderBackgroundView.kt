package com.portal.tv.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig as Egl14Config
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import android.view.TextureView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Animated gradient background rendered on the GPU via a fragment shader.
 *
 * Draws a full-screen quad and uses a time-based shader to create a slowly shifting gradient in the
 * chosen [ShaderPalettes.Palette]. Near-zero CPU usage: all work is on the GPU.
 *
 * It draws into a [TextureView] rather than a GLSurfaceView. A GLSurfaceView lives on a surface of its
 * own, behind the window, so it ignores its parents' opacity and clipping and can show where it
 * shouldn't, such as around the intro's growing circle. A TextureView is an ordinary view.
 */
class ShaderBackgroundView(
    context: Context,
    palette: ShaderPalettes.Palette = ShaderPalettes.find(null),
    style: ShaderStyles.Style = ShaderStyles.find(null),
    speed: Float = 1f,
    cycle: Boolean = false,
    /** The size it is drawn at; small for a preview, which has little room to show detail. */
    private val bufferWidth: Int = WIDTH,
    private val bufferHeight: Int = HEIGHT,
    /** Where in its animation it starts, in seconds, so a still picture of it isn't the empty first moment. */
    startAt: Float = 0f
) : TextureView(context), TextureView.SurfaceTextureListener {

    /** Called on the main thread once the first picture has been drawn. */
    var onFirstFrame: (() -> Unit)? = null

    private val renderer = ShaderRenderer(palette, style, speed, cycle, startAt) { post { onFirstFrame?.invoke() } }

    /** Milliseconds between frames; a preview that isn't the one in focus can draw less often. */
    @Volatile var frameMs = FRAME_MS

    /** Changes the colors while it runs. */
    fun setPalette(palette: ShaderPalettes.Palette) { renderer.palette = palette }

    /** Turns color cycling on or off while it runs. */
    fun setCycle(cycle: Boolean) { renderer.cycle = cycle }
    private var surface: SurfaceTexture? = null
    private var thread: RenderThread? = null
    private var resumed = false

    init {
        surfaceTextureListener = this
        isOpaque = true
    }

    /** Starts drawing, as soon as there is somewhere to draw. */
    fun onResume() {
        resumed = true
        startIfReady()
    }

    /** Stops drawing and gives the graphics chip its time back. */
    fun onPause() {
        resumed = false
        stopThread()
    }

    private fun startIfReady() {
        val st = surface ?: return
        if (!resumed || thread != null) return
        // Half the screen's pixels is plenty for soft color, and a quarter of the work for the GPU.
        st.setDefaultBufferSize(bufferWidth, bufferHeight)
        thread = RenderThread(st, renderer) { frameMs }.also { it.start() }
    }

    private fun stopThread() {
        thread?.let {
            it.finish()
            runCatching { it.join(1000) }
        }
        thread = null
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        surface = st
        startIfReady()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        // The view resets the buffer to its own size whenever it is laid out again, for instance on coming back
        // from another app. Keep it at the half size the shader is drawn at.
        st.setDefaultBufferSize(bufferWidth, bufferHeight)
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        stopThread()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    /** Owns an OpenGL context for the texture and draws a frame about every 16 ms until told to finish. */
    private class RenderThread(
        private val texture: SurfaceTexture,
        private val renderer: ShaderRenderer,
        private val frameMs: () -> Long
    ) : Thread("shader-render") {

        @Volatile private var running = true

        fun finish() { running = false }

        override fun run() {
            val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return
            val configs = arrayOfNulls<Egl14Config>(1)
            val count = IntArray(1)
            EGL14.eglChooseConfig(
                display,
                intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE
                ), 0, configs, 0, 1, count, 0
            )
            val config = configs[0]
            if (config == null || count[0] == 0) { EGL14.eglTerminate(display); return }
            val context: EGLContext = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            val window: EGLSurface = EGL14.eglCreateWindowSurface(display, config, texture, intArrayOf(EGL14.EGL_NONE), 0)
            try {
                if (!EGL14.eglMakeCurrent(display, window, window, context)) {
                    Log.w("Portal", "Shader: couldn't use the graphics context")
                    return
                }
                renderer.onSurfaceCreated(null, null)
                var drawWidth = 0
                var drawHeight = 0
                val size = IntArray(1)
                while (running) {
                    val start = System.nanoTime()
                    // Draw at whatever size the surface really is, so the picture always fills it.
                    EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, size, 0)
                    val surfaceWidth = size[0]
                    EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, size, 0)
                    val surfaceHeight = size[0]
                    if (surfaceWidth > 0 && surfaceHeight > 0 && (surfaceWidth != drawWidth || surfaceHeight != drawHeight)) {
                        drawWidth = surfaceWidth
                        drawHeight = surfaceHeight
                        renderer.onSurfaceChanged(null, drawWidth, drawHeight)
                    }
                    renderer.onDrawFrame(null)
                    if (!EGL14.eglSwapBuffers(display, window)) break
                    val spent = (System.nanoTime() - start) / 1_000_000
                    val wanted = frameMs()
                    if (spent < wanted) sleep(wanted - spent)
                }
            } catch (e: InterruptedException) {
                // told to stop
            } finally {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, window)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
        }
    }

    private class ShaderRenderer(
        @Volatile var palette: ShaderPalettes.Palette,
        private val style: ShaderStyles.Style,
        private val speed: Float,
        @Volatile var cycle: Boolean,
        startAt: Float,
        private val onFirstDrawn: () -> Unit
    ) {

        private var firstDrawn = false

        private var uResLoc = 0
        private var width = 960f
        private var height = 540f
        /** Animation time in seconds. It advances by the real time between frames times [speed]. */
        private var elapsed = startAt
        private var lastNanos = 0L
        private val shifted = Array(5) { FloatArray(3) }

        private var program = 0
        private var positionLoc = 0
        private var uTimeLoc = 0
        private var uBase1Loc = 0
        private var uBase2Loc = 0
        private var uAurora1Loc = 0
        private var uAurora2Loc = 0
        private var uAurora3Loc = 0

        // Full-screen quad (two triangles)
        private val quad = floatArrayOf(
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
            -1f,  1f,
             1f, -1f,
             1f,  1f
        )

        private val vertexShaderSource = """
            attribute vec2 aPosition;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """.trimIndent()

        fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            program = createProgram(vertexShaderSource, ShaderStyles.fragmentSource(style))
            GLES20.glUseProgram(program)
            positionLoc = GLES20.glGetAttribLocation(program, "aPosition")
            uTimeLoc = GLES20.glGetUniformLocation(program, "uTime")
            uResLoc = GLES20.glGetUniformLocation(program, "uRes")
            uBase1Loc = GLES20.glGetUniformLocation(program, "uBase1")
            uBase2Loc = GLES20.glGetUniformLocation(program, "uBase2")
            uAurora1Loc = GLES20.glGetUniformLocation(program, "uAurora1")
            uAurora2Loc = GLES20.glGetUniformLocation(program, "uAurora2")
            uAurora3Loc = GLES20.glGetUniformLocation(program, "uAurora3")
            GLES20.glClearColor(palette.base1[0], palette.base1[1], palette.base1[2], 1.0f)
        }

        fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            this.width = width.toFloat()
            this.height = height.toFloat()
            GLES20.glViewport(0, 0, width, height)
        }

        fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)

            val vertexBuffer = java.nio.ByteBuffer.allocateDirect(quad.size * 4)
                .order(java.nio.ByteOrder.nativeOrder())
                .asFloatBuffer()
            vertexBuffer.put(quad).position(0)

            GLES20.glEnableVertexAttribArray(positionLoc)
            GLES20.glVertexAttribPointer(positionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

            // Time moves at the chosen speed. A long pause, such as the app being away, counts as one frame.
            val now = System.nanoTime()
            if (lastNanos != 0L) elapsed += ((now - lastNanos) / 1e9f).coerceAtMost(0.1f) * speed
            lastNanos = now
            // Wrapped, so the number stays small enough for the GPU.
            val time = elapsed % 7200f
            GLES20.glUniform1f(uTimeLoc, time)
            GLES20.glUniform2f(uResLoc, width, height)
            val colors = arrayOf(palette.base1, palette.base2, palette.aurora1, palette.aurora2, palette.aurora3)
            if (cycle) {
                // One trip round the color wheel takes about two minutes at normal speed.
                val angle = (elapsed % 120f) / 120f * 2f * Math.PI.toFloat()
                colors.forEachIndexed { i, c -> rotateHue(c, angle, shifted[i]) }
            } else {
                colors.forEachIndexed { i, c -> System.arraycopy(c, 0, shifted[i], 0, 3) }
            }
            GLES20.glUniform3fv(uBase1Loc, 1, shifted[0], 0)
            GLES20.glUniform3fv(uBase2Loc, 1, shifted[1], 0)
            GLES20.glUniform3fv(uAurora1Loc, 1, shifted[2], 0)
            GLES20.glUniform3fv(uAurora2Loc, 1, shifted[3], 0)
            GLES20.glUniform3fv(uAurora3Loc, 1, shifted[4], 0)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)

            GLES20.glDisableVertexAttribArray(positionLoc)
            if (!firstDrawn) {
                firstDrawn = true
                onFirstDrawn()
            }
        }

        /** Turns a color's hue by [angle] radians, keeping its brightness, into [out]. */
        private fun rotateHue(c: FloatArray, angle: Float, out: FloatArray) {
            val cosA = Math.cos(angle.toDouble()).toFloat()
            val sinA = Math.sin(angle.toDouble()).toFloat()
            val k = (1f - cosA) / 3f
            val s = sinA * 0.57735f
            val r = c[0]
            val g = c[1]
            val b = c[2]
            out[0] = (r * (cosA + k) + g * (k - s) + b * (k + s)).coerceIn(0f, 1f)
            out[1] = (r * (k + s) + g * (cosA + k) + b * (k - s)).coerceIn(0f, 1f)
            out[2] = (r * (k - s) + g * (k + s) + b * (cosA + k)).coerceIn(0f, 1f)
        }

        private fun createProgram(vs: String, fs: String): Int {
            val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vs)
            val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fs)
            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, vertex)
            GLES20.glAttachShader(prog, fragment)
            GLES20.glLinkProgram(prog)
            val status = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                android.util.Log.e("Portal", "Shader link failed: ${GLES20.glGetProgramInfoLog(prog)}")
            }
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            return prog
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                android.util.Log.e("Portal", "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            }
            return shader
        }
    }

    companion object {
        const val WIDTH = 960
        const val HEIGHT = 540
        const val FRAME_MS = 16L
    }
}
