package com.cjryrx.cameraredesigned.ui.render

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30 as GL
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.util.Log
import com.cjryrx.cameraredesigned.domain.FilterSpec
import com.cjryrx.cameraredesigned.imaging.CubeLut
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class PreviewStyle(
    val rotation: Int = 90,
    val mirror: Boolean = false,
    val filter: FilterSpec? = null,
    val warnings: Boolean = false,
    val peaking: Boolean = false,
    val magnifier: Boolean = false,
    val focusX: Float = .5f,
    val focusY: Float = .5f,
    val analysis: Boolean = false,
)

/**
 * Owns EGL and the camera's OES SurfaceTexture on one render thread. Frame callbacks coalesce;
 * camera-rate display never waits for inference. Analysis uses a fenced pixel-buffer readback.
 * All callbacks run on the main thread; the analysis callback owns each returned bitmap.
 */
class GpuPreviewRenderer(
    private val onInputReady: (SurfaceTexture, Int, Int) -> Unit,
    private val onAnalysis: (Bitmap) -> Unit,
    private val onError: (String) -> Unit,
) : AutoCloseable {
    private val thread = HandlerThread("CameraPreviewGL").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val loading = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lutLoad: Job? = null
    @Volatile private var requestedLut: String? = null
    private data class PreparedLut(val path: String, val data: CubeLut.LutTextureData)
    @Volatile private var preparedLut: PreparedLut? = null
    private val closed = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val frameQueued = AtomicBoolean(false)
    @Volatile private var style = PreviewStyle()
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var output: Surface? = null
    private var input: SurfaceTexture? = null
    private var width = 0
    private var height = 0
    private var externalTexture = 0
    private var vertexBuffer = 0
    private var vertexArray = 0
    private var lutTexture = 0
    private var lutKey: String? = null
    private var lutSize = 2
    private var domainMin = floatArrayOf(0f, 0f, 0f)
    private var domainMax = floatArrayOf(1f, 1f, 1f)
    private val matrix = FloatArray(16)
    private val programs = HashMap<String, Int>()
    private val uniforms = HashMap<Int, MutableMap<String, Int>>()
    private val targets = ArrayList<Target>()
    private var analysisTarget: Target? = null
    private var pixelBuffer = 0
    private var fence = 0L
    private var readWidth = 0
    private var readHeight = 0
    private var readRotation = 0
    private var readMirror = false
    private var lastAnalysis = 0L
    private val vertices = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }

    fun configure(value: PreviewStyle) {
        style = value
        val path = value.filter?.lutPath
        if (path != requestedLut) {
            requestedLut = path; preparedLut = null; lutLoad?.cancel()
            if (path != null) lutLoad = loading.launch {
                try {
                    val data = CubeLut.read(File(path)).textureData()
                    ensureActive()
                    if (requestedLut == path) preparedLut = PreparedLut(path, data)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { fail(error) }
            }
        }
    }

    /** Takes ownership of the output Surface (not its TextureView SurfaceTexture). */
    fun start(surface: Surface, width: Int, height: Int) {
        if (closed.get()) { surface.release(); return }
        handler.post {
            if (closed.get()) { surface.release(); return@post }
            try {
                output = surface
                this.width = width; this.height = height
                createEgl(surface)
                programs["external"] = program(PreviewShaders.external)
                programs["copy"] = program(PreviewShaders.copy)
                programs["grade"] = program(PreviewShaders.grade)
                programs["blur"] = program(PreviewShaders.blur)
                programs["sharpen"] = program(PreviewShaders.sharpen)
                programs["display"] = program(PreviewShaders.display)
                val bufferId = IntArray(1); GL.glGenBuffers(1, bufferId, 0); vertexBuffer = bufferId[0]
                GL.glBindBuffer(GL.GL_ARRAY_BUFFER, vertexBuffer)
                vertices.position(0); GL.glBufferData(GL.GL_ARRAY_BUFFER, 8 * 4, vertices, GL.GL_STATIC_DRAW)
                val arrayId = IntArray(1); GL.glGenVertexArrays(1, arrayId, 0); vertexArray = arrayId[0]
                GL.glBindVertexArray(vertexArray)
                GL.glVertexAttribPointer(0, 2, GL.GL_FLOAT, false, 0, 0); GL.glEnableVertexAttribArray(0)
                externalTexture = texture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
                input = SurfaceTexture(externalTexture).also { source ->
                    source.setOnFrameAvailableListener({
                        if (frameQueued.compareAndSet(false, true)) handler.post {
                            frameQueued.set(false)
                            if (!closed.get() && !paused.get()) try { drawFrame() } catch (e: Exception) { fail(e) }
                        }
                    }, handler)
                    main.post { if (!closed.get() && !paused.get()) onInputReady(source, width, height) }
                }
            } catch (e: Exception) { fail(e) }
        }
    }

    fun resize(width: Int, height: Int) {
        handler.post {
            if (closed.get() || paused.get()) return@post
            if (this.width == width && this.height == height) return@post
            this.width = width; this.height = height
            destroyTargets()
            input?.let { source -> main.post { if (!closed.get() && !paused.get()) onInputReady(source, width, height) } }
        }
    }

    /** Stop consuming frames immediately; retain the camera input until CameraDevice.onClosed. */
    fun pause() {
        paused.set(true)
        handler.post { input?.setOnFrameAvailableListener(null) }
    }

    private fun createEgl(surface: Surface) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY && EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0)) { "EGL initialization failed" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, 0x40, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "OpenGL ES 3 is unavailable" }
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "Cannot create OpenGL ES 3 context" }
        window = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(window != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, window, window, context)) { "Cannot create preview surface" }
        EGL14.eglSwapInterval(display, 1)
    }

    private fun drawFrame() {
        if (width <= 0 || height <= 0) return
        val source = input ?: return
        source.updateTexImage(); source.getTransformMatrix(matrix)
        val current = style
        if (targets.isEmpty()) targets += target(width, height)
        val original = targets[0]
        draw(programs.getValue("external"), original, externalTexture, GLES11Ext.GL_TEXTURE_EXTERNAL_OES) { p ->
            GL.glUniformMatrix4fv(location(p, "textureMatrix"), 1, false, matrix, 0)
            int(p, "rotation", current.rotation); int(p, "mirror", if (current.mirror) 1 else 0)
        }
        pollAnalysis()
        if (current.analysis) requestAnalysis(original)
        var shown = original
        current.filter?.let { filter ->
            if (!loadLut(filter.lutPath)) return@let
            ensureTargetCount(2)
            val a = filter.adjustments
            draw(programs.getValue("grade"), targets[1], original.texture) { p ->
                float(p, "strength", filter.intensity); float(p, "exposure", a.exposure)
                float(p, "temperature", a.temperature); float(p, "tint", a.tint)
                float(p, "saturation", a.saturation); float(p, "vibrance", a.vibrance)
                float(p, "contrast", a.contrast); float(p, "highlights", a.highlights); float(p, "shadows", a.shadows)
                int(p, "hasLut", if (filter.lutPath != null) 1 else 0)
                int(p, "lut", 1) // Different sampler types must never share a texture unit, even in an untaken branch.
                if (filter.lutPath != null) {
                    GL.glActiveTexture(GL.GL_TEXTURE1); GL.glBindTexture(GL.GL_TEXTURE_3D, lutTexture)
                    float(p, "lutSize", lutSize.toFloat())
                    GL.glUniform3fv(location(p, "domainMin"), 1, domainMin, 0)
                    GL.glUniform3fv(location(p, "domainMax"), 1, domainMax, 0)
                }
            }
            shown = targets[1]
            if (filter.lutPath == null && (a.sharpness > 0f || a.blur > 0f)) {
                ensureTargetCount(4)
                if (a.sharpness > 0f) {
                    blur(shown, targets[2], targets[3], 2)
                    draw(programs.getValue("sharpen"), targets[2], shown.texture) { p ->
                        GL.glActiveTexture(GL.GL_TEXTURE1); GL.glBindTexture(GL.GL_TEXTURE_2D, targets[3].texture)
                        int(p, "softened", 1); float(p, "amount", a.sharpness * filter.intensity * 2f)
                    }
                    shown = targets[2]
                }
                val radius = ceil(a.blur * filter.intensity * 12).toInt().coerceIn(0, 12)
                if (radius > 0) {
                    val horizontal = if (shown === targets[1]) targets[2] else targets[1]
                    blur(shown, horizontal, targets[3], radius)
                    shown = targets[3]
                }
            }
        }
        draw(programs.getValue("display"), null, shown.texture) { p ->
            GL.glUniform2f(location(p, "texel"), 1f / width, 1f / height)
            val zoom = if (current.magnifier) 2f else 1f
            val cx = if (current.magnifier) current.focusX.coerceIn(.25f, .75f) else .5f
            val cy = if (current.magnifier) (1f - current.focusY).coerceIn(.25f, .75f) else .5f
            GL.glUniform2f(location(p, "center"), cx, cy); float(p, "zoom", zoom)
            int(p, "warnings", if (current.warnings) 1 else 0); int(p, "peaking", if (current.peaking) 1 else 0)
        }
        check(GL.glGetError() == GL.GL_NO_ERROR) { "GPU preview rendering failed" }
        check(EGL14.eglSwapBuffers(display, window)) { "Preview EGL surface lost" }
    }

    private fun blur(source: Target, horizontal: Target, vertical: Target, radius: Int) {
        draw(programs.getValue("blur"), horizontal, source.texture) { p ->
            GL.glUniform2f(location(p, "direction"), 1f / width, 0f); int(p, "radius", radius)
        }
        draw(programs.getValue("blur"), vertical, horizontal.texture) { p ->
            GL.glUniform2f(location(p, "direction"), 0f, 1f / height); int(p, "radius", radius)
        }
    }

    private fun loadLut(path: String?): Boolean {
        if (path == lutKey) return true
        val prepared = preparedLut?.takeIf { it.path == path }
        if (path != null && prepared == null) return false
        if (lutTexture != 0) { GL.glDeleteTextures(1, intArrayOf(lutTexture), 0); lutTexture = 0 }
        lutKey = path
        if (path == null) return true
        val data = requireNotNull(prepared).data
        lutSize = data.size; domainMin = data.domainMin; domainMax = data.domainMax
        lutTexture = texture(GL.GL_TEXTURE_3D)
        GL.glTexParameteri(GL.GL_TEXTURE_3D, GL.GL_TEXTURE_WRAP_R, GL.GL_CLAMP_TO_EDGE)
        val buffer = ByteBuffer.allocateDirect(data.values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(data.values); position(0) }
        GL.glTexImage3D(GL.GL_TEXTURE_3D, 0, GL.GL_RGB16F, data.size, data.size, data.size, 0, GL.GL_RGB, GL.GL_FLOAT, buffer)
        check(GL.glGetError() == GL.GL_NO_ERROR) { "Cannot upload LUT texture" }
        preparedLut = null
        return true
    }

    private fun requestAnalysis(source: Target) {
        if (fence != 0L || SystemClock.elapsedRealtime() - lastAnalysis < 200) return
        val scale = min(1f, 640f / max(width, height))
        val w = (width * scale).roundToInt().coerceAtLeast(1)
        val h = (height * scale).roundToInt().coerceAtLeast(1)
        val analysis = analysisTarget ?: target(w, h).also { analysisTarget = it }
        draw(programs.getValue("copy"), analysis, source.texture)
        if (pixelBuffer == 0) { val id = IntArray(1); GL.glGenBuffers(1, id, 0); pixelBuffer = id[0] }
        GL.glBindBuffer(GL.GL_PIXEL_PACK_BUFFER, pixelBuffer)
        GL.glBufferData(GL.GL_PIXEL_PACK_BUFFER, w * h * 4, null, GL.GL_STREAM_READ)
        GL.glReadPixels(0, 0, w, h, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, 0)
        fence = GL.glFenceSync(GL.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        GL.glFlush(); GL.glBindBuffer(GL.GL_PIXEL_PACK_BUFFER, 0)
        readWidth = w; readHeight = h
        readRotation = style.rotation; readMirror = style.mirror
        lastAnalysis = SystemClock.elapsedRealtime()
    }

    private fun pollAnalysis() {
        if (fence == 0L) return
        val status = GL.glClientWaitSync(fence, 0, 0)
        if (status != GL.GL_ALREADY_SIGNALED && status != GL.GL_CONDITION_SATISFIED) return
        GL.glDeleteSync(fence); fence = 0
        GL.glBindBuffer(GL.GL_PIXEL_PACK_BUFFER, pixelBuffer)
        val bytes = GL.glMapBufferRange(GL.GL_PIXEL_PACK_BUFFER, 0, readWidth * readHeight * 4, GL.GL_MAP_READ_BIT) as? ByteBuffer
        if (bytes != null) {
            val pixels = IntArray(readWidth * readHeight)
            for (y in 0 until readHeight) for (x in 0 until readWidth) {
                val r = bytes.get().toInt() and 255; val g = bytes.get().toInt() and 255
                val b = bytes.get().toInt() and 255; bytes.get()
                pixels[(readHeight - 1 - y) * readWidth + x] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
            GL.glUnmapBuffer(GL.GL_PIXEL_PACK_BUFFER)
            val bitmap = Bitmap.createBitmap(pixels, readWidth, readHeight, Bitmap.Config.ARGB_8888)
            val rotation = readRotation; val mirror = readMirror
            main.post {
                val current = style
                if (closed.get() || paused.get() || !current.analysis || current.rotation != rotation || current.mirror != mirror) bitmap.recycle()
                else onAnalysis(bitmap)
            }
        }
        GL.glBindBuffer(GL.GL_PIXEL_PACK_BUFFER, 0)
    }

    private data class Target(val texture: Int, val framebuffer: Int, val width: Int, val height: Int)
    private fun ensureTargetCount(count: Int) { while (targets.size < count) targets += target(width, height) }
    private fun target(width: Int, height: Int): Target {
        val texture = texture(GL.GL_TEXTURE_2D)
        GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA8, width, height, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, null)
        val fb = IntArray(1); GL.glGenFramebuffers(1, fb, 0); GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, fb[0])
        GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, texture, 0)
        check(GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER) == GL.GL_FRAMEBUFFER_COMPLETE) { "Incomplete preview framebuffer" }
        return Target(texture, fb[0], width, height)
    }
    private fun texture(target: Int): Int {
        val id = IntArray(1); GL.glGenTextures(1, id, 0); GL.glBindTexture(target, id[0])
        GL.glTexParameteri(target, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR); GL.glTexParameteri(target, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(target, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE); GL.glTexParameteri(target, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        return id[0]
    }
    private inline fun draw(program: Int, target: Target?, texture: Int, textureType: Int = GL.GL_TEXTURE_2D, uniforms: (Int) -> Unit = {}) {
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, target?.framebuffer ?: 0)
        GL.glViewport(0, 0, target?.width ?: width, target?.height ?: height)
        GL.glUseProgram(program); GL.glActiveTexture(GL.GL_TEXTURE0); GL.glBindTexture(textureType, texture)
        int(program, "image", 0); uniforms(program)
        GL.glBindVertexArray(vertexArray)
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
    }
    private fun location(program: Int, name: String) = uniforms.getOrPut(program) { HashMap() }
        .getOrPut(name) { GL.glGetUniformLocation(program, name) }
    private fun int(program: Int, name: String, value: Int) = GL.glUniform1i(location(program, name), value)
    private fun float(program: Int, name: String, value: Float) = GL.glUniform1f(location(program, name), value)
    private fun program(fragment: String): Int {
        fun shader(type: Int, source: String): Int {
            val id = GL.glCreateShader(type); GL.glShaderSource(id, source); GL.glCompileShader(id)
            val result = IntArray(1); GL.glGetShaderiv(id, GL.GL_COMPILE_STATUS, result, 0)
            check(result[0] != 0) { "Preview shader: ${GL.glGetShaderInfoLog(id)}" }; return id
        }
        val vertex = shader(GL.GL_VERTEX_SHADER, PreviewShaders.vertex); val pixel = shader(GL.GL_FRAGMENT_SHADER, fragment)
        val id = GL.glCreateProgram(); GL.glAttachShader(id, vertex); GL.glAttachShader(id, pixel); GL.glLinkProgram(id)
        GL.glDeleteShader(vertex); GL.glDeleteShader(pixel)
        val status = IntArray(1); GL.glGetProgramiv(id, GL.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { "Preview program: ${GL.glGetProgramInfoLog(id)}" }; return id
    }
    private fun destroyTargets() {
        (targets + listOfNotNull(analysisTarget)).forEach {
            GL.glDeleteTextures(1, intArrayOf(it.texture), 0); GL.glDeleteFramebuffers(1, intArrayOf(it.framebuffer), 0)
        }
        targets.clear(); analysisTarget = null
        if (fence != 0L) { GL.glDeleteSync(fence); fence = 0 }
    }
    private fun fail(error: Exception) {
        Log.w("CameraPreviewGL", "GPU preview failed; falling back to native preview", error)
        if (!closed.get() && !paused.getAndSet(true)) main.post { onError(error.message ?: "GPU preview unavailable") }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        loading.cancel()
        handler.post {
            runCatching {
                input?.setOnFrameAvailableListener(null); input?.release(); input = null
                if (context != EGL14.EGL_NO_CONTEXT) {
                    destroyTargets(); programs.values.forEach(GL::glDeleteProgram)
                    GL.glDeleteTextures(2, intArrayOf(externalTexture, lutTexture), 0)
                    GL.glDeleteBuffers(1, intArrayOf(vertexBuffer), 0)
                    GL.glDeleteVertexArrays(1, intArrayOf(vertexArray), 0)
                    if (pixelBuffer != 0) GL.glDeleteBuffers(1, intArrayOf(pixelBuffer), 0)
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    EGL14.eglDestroySurface(display, window); EGL14.eglDestroyContext(display, context)
                }
                if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(display)
            }
            output?.release(); thread.quitSafely()
        }
    }
}
