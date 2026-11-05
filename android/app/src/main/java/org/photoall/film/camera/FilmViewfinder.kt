package org.photoall.film.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Viewfinder (sized 3:4 by its ViewfinderFrame): CameraX preview frames through the film's preview 3D LUT.
 * The LUT holds only the per-pixel film response; the capture is developed in full.
 */
class FilmViewfinder(context: Context) : GLSurfaceView(context), GLSurfaceView.Renderer, Preview.SurfaceProvider {

    private var program = 0
    private var cameraTexture = 0
    private var lutTexture = 0
    private var lutSize = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var pendingRequest: SurfaceRequest? = null
    private val transform = FloatArray(16)
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    // Written from camera / UI threads, read on the GL thread.
    @Volatile private var pendingLut: Pair<ByteArray, Int>? = null
    @Volatile private var lastLut: Pair<ByteArray, Int>? = null
    @Volatile private var bufferWidth = 0
    @Volatile private var bufferHeight = 0
    @Volatile private var contentRotation = 0 // sensor buffer -> upright, decides the crop aspect
    @Volatile private var cameraTransform = false // SurfaceTexture matrix already holds the sensor orientation

    /** CameraInfo.sensorRotationDegrees of the bound camera. */
    @Volatile var sensorRotation = 0

    /** Focus peaking: outline in-focus edges in red (manual focus aid). */
    @Volatile var peaking = false
        set(value) { field = value; requestRender() }

    /** Pinch: multiplicative zoom step. Tap: focus/meter at view coordinates. */
    var onPinch: (Float) -> Unit = {}
    var onTap: (Float, Float) -> Unit = { _, _ -> }
    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            onPinch(detector.scaleFactor)
            return true
        }
    })
    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onTap(e.x, e.y)
            return true
        }
    })

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        pinch.onTouchEvent(event)
        if (!pinch.isInProgress) taps.onTouchEvent(event)
        return true
    }

    /** RGBA8 [size]^3 LUT from FilmEngine.previewLut; null shows the camera unprocessed. */
    fun setLut(data: ByteArray?, size: Int) {
        val lut = (data ?: ByteArray(0)) to size
        lastLut = lut
        pendingLut = lut
        requestRender()
    }

    override fun onSurfaceRequested(request: SurfaceRequest) {
        queueEvent {
            if (surfaceTexture == null) {
                pendingRequest?.willNotProvideSurface()
                pendingRequest = request
            } else provide(request)
        }
    }

    // GL thread.
    private fun provide(request: SurfaceRequest) {
        val texture = surfaceTexture ?: return
        val size = request.resolution
        texture.setDefaultBufferSize(size.width, size.height)
        bufferWidth = size.width
        bufferHeight = size.height
        request.setTransformationInfoListener(Executor { it.run() }) { info ->
            contentRotation = info.rotationDegrees
            cameraTransform = info.hasCameraTransform()
            requestRender()
        }
        val surface = Surface(texture)
        request.provideSurface(surface, Executor { it.run() }) { surface.release() }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram()
        val textures = IntArray(2)
        GLES30.glGenTextures(2, textures, 0)
        cameraTexture = textures[0]
        lutTexture = textures[1]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        lutSize = 0
        if (pendingLut == null) pendingLut = lastLut // re-upload after a context loss
        surfaceTexture?.release()
        surfaceTexture = SurfaceTexture(cameraTexture).apply { setOnFrameAvailableListener { requestRender() } }
        pendingRequest?.let { pendingRequest = null; provide(it) }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val texture = surfaceTexture ?: return
        texture.updateTexImage()
        texture.getTransformMatrix(transform)
        pendingLut?.let { (data, size) ->
            pendingLut = null
            uploadLut(data, size)
        }
        if (bufferWidth == 0) return

        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uCamera"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uLut"), 1)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uHasLut"), if (lutSize > 0) 1 else 0)
        val n = lutSize.coerceAtLeast(1).toFloat()
        GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "uLutMap"), (n - 1f) / n, 0.5f / n)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uTransform"), 1, false, transform, 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uPeaking"), if (peaking) 1 else 0)
        // One sensor-buffer texel in texture coordinates, for the edge detector.
        GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "uTexel"),
            1f / bufferWidth.coerceAtLeast(1), 1f / bufferHeight.coerceAtLeast(1))

        fillQuad()
        val stride = 4 * 4
        quad.position(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, stride, quad)
        GLES30.glEnableVertexAttribArray(0)
        quad.position(2)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, quad)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Screen corners with buffer coordinates: centre-crop to fill, then undo sensor rotation. */
    private fun fillQuad() {
        // Only the rotation the SurfaceTexture matrix leaves undone is ours to apply.
        val rotation = if (cameraTransform) (contentRotation - sensorRotation + 360) % 360 else contentRotation
        val sideways = contentRotation == 90 || contentRotation == 270
        val contentAspect = if (sideways) bufferHeight.toFloat() / bufferWidth else bufferWidth.toFloat() / bufferHeight
        val viewAspect = width.toFloat() / height.coerceAtLeast(1)
        var scaleU = 1f
        var scaleV = 1f
        if (contentAspect > viewAspect) scaleU = viewAspect / contentAspect else scaleV = contentAspect / viewAspect
        quad.position(0)
        for ((x, y) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
            val u = 0.5f + (x * 0.5f) * scaleU
            val v = 0.5f + (y * 0.5f) * scaleV
            // upright display (u, v) -> unrotated buffer coordinates, GL y-up
            val (bu, bv) = when (rotation) {
                90 -> (1f - v) to u
                180 -> (1f - u) to (1f - v)
                270 -> v to (1f - u)
                else -> u to v
            }
            quad.put(x).put(y).put(bu).put(bv)
        }
    }

    private fun uploadLut(data: ByteArray, size: Int) {
        if (data.isEmpty() || size < 2) {
            lutSize = 0
            return
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA8, size, size, size, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, ByteBuffer.wrap(data))
        for (param in intArrayOf(GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_TEXTURE_MAG_FILTER))
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, param, GLES30.GL_LINEAR)
        for (param in intArrayOf(GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_TEXTURE_WRAP_R))
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, param, GLES30.GL_CLAMP_TO_EDGE)
        lutSize = size
    }

    private fun buildProgram(): Int {
        val vertex = """
            #version 300 es
            layout(location = 0) in vec2 aPosition;
            layout(location = 1) in vec2 aBuffer;
            uniform mat4 uTransform;
            out vec2 vTexcoord;
            void main() {
              gl_Position = vec4(aPosition, 0.0, 1.0);
              vTexcoord = (uTransform * vec4(aBuffer, 0.0, 1.0)).xy;
            }
        """.trimIndent()
        val fragment = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;
            uniform samplerExternalOES uCamera;
            uniform highp sampler3D uLut;
            uniform bool uHasLut;
            uniform vec2 uLutMap; // scale, offset onto texel centres
            uniform bool uPeaking;
            uniform vec2 uTexel;
            in vec2 vTexcoord;
            out vec4 colour;
            float luma(vec2 t) { return dot(texture(uCamera, t).rgb, vec3(0.299, 0.587, 0.114)); }
            void main() {
              vec3 c = texture(uCamera, vTexcoord).rgb;
              if (uHasLut) c = texture(uLut, clamp(c, 0.0, 1.0) * uLutMap.x + uLutMap.y).rgb;
              if (uPeaking) {
                // Sobel on camera luma two texels apart: strong local contrast = in focus.
                vec2 dx = vec2(2.0 * uTexel.x, 0.0), dy = vec2(0.0, 2.0 * uTexel.y);
                float tl = luma(vTexcoord - dx - dy), t = luma(vTexcoord - dy), tr = luma(vTexcoord + dx - dy);
                float l = luma(vTexcoord - dx), r = luma(vTexcoord + dx);
                float bl = luma(vTexcoord - dx + dy), b = luma(vTexcoord + dy), br = luma(vTexcoord + dx + dy);
                float gx = (tr + 2.0 * r + br) - (tl + 2.0 * l + bl);
                float gy = (bl + 2.0 * b + br) - (tl + 2.0 * t + tr);
                float edge = smoothstep(0.35, 0.6, length(vec2(gx, gy)));
                c = mix(c, vec3(1.0, 0.15, 0.1), edge);
              }
              colour = vec4(c, 1.0);
            }
        """.trimIndent()
        fun compile(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { GLES30.glGetShaderInfoLog(shader) }
            return shader
        }
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, compile(GLES30.GL_VERTEX_SHADER, vertex))
        GLES30.glAttachShader(program, compile(GLES30.GL_FRAGMENT_SHADER, fragment))
        GLES30.glLinkProgram(program)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { GLES30.glGetProgramInfoLog(program) }
        return program
    }
}
