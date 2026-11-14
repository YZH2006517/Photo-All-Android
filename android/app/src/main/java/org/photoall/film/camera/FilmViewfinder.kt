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
    /**
     * A front camera: its buffers come mirrored (the selfie view) by the SurfaceTexture
     * transform, so the turn left for the screen runs the other way.
     */
    @Volatile var frontFacing = false

    /**
     * Linear gain on the camera frames: a zero-lag burst session runs the sensor under
     * the meter and the viewfinder shows it as metered.
     */
    @Volatile var gain = 1f
        set(value) { field = value; requestRender() }

    /**
     * The viewfinder's own tone: the camera's picture softened ([soften] 0..1, its sharpening
     * taken off) and quieter ([saturation], 1 = as the camera gives it). The film look is
     * left to the developed photo; the viewfinder no longer previews it.
     */
    @Volatile var soften = 0.5f
        set(value) { field = value; requestRender() }
    /** RGBA8 [size]^3 film LUT from FilmEngine.previewLut, shown on the picture; null: the camera's own picture. */
    fun setLut(data: ByteArray?, size: Int) {
        val lut = (data ?: ByteArray(0)) to size
        if (lastLut?.let { it.first === lut.first && it.second == size } == true) return
        lastLut = lut
        pendingLut = lut
        requestRender()
    }

    /** Brighter or darker to look at, in stops; the photo is not touched. */
    @Volatile var exposure = 0f
        set(value) { field = value; requestRender() }

    /** Called once, on the main thread, when the next camera frame arrives. */
    @Volatile var onFrame: (() -> Unit)? = null

    @Volatile var saturation = 0.7f
        set(value) { field = value; requestRender() }

    /** Film grain over the viewfinder, 0..1 (0 = none); a new grain every frame, like film running. */
    @Volatile var grain = 0f
        set(value) { field = value; requestRender() }
    private var grainFrame = 0

    /** Focus peaking: outline in-focus edges in red (manual focus aid). */
    @Volatile var peaking = false
        set(value) { field = value; requestRender() }

    /**
     * Pinch: multiplicative zoom step. Tap: focus/meter at view coordinates. Double tap:
     * focus there and hold it.
     */
    var onPinch: (Float) -> Unit = {}
    var onTap: (Float, Float) -> Unit = { _, _ -> }
    var onDoubleTap: (Float, Float) -> Unit = { _, _ -> }
    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            onPinch(detector.scaleFactor)
            return true
        }
    })
    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        // A single tap waits out the double-tap window to tell the two apart.
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onTap(e.x, e.y)
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDoubleTap(e.x, e.y)
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


    // A Camera2 session waiting for the GL surface: size, rotation and where to hand it.
    private var pendingDirect: Triple<android.util.Size, Int, (Surface) -> Unit>? = null
    private var directSurface: Surface? = null

    /**
     * A surface for a Camera2 session (not CameraX): buffers of [size], [rotationDegrees]
     * from the sensor to upright; the camera applies its own orientation transform.
     * [ready] runs on the GL thread.
     */
    fun provideDirect(size: android.util.Size, rotationDegrees: Int, ready: (Surface) -> Unit) {
        queueEvent {
            if (surfaceTexture == null) pendingDirect = Triple(size, rotationDegrees, ready)
            else direct(size, rotationDegrees, ready)
        }
    }

    /**
     * The screen turned under a running Camera2 session: the same buffers, a new turn to
     * upright. No new surface, so the picture keeps running.
     */
    fun setDirectRotation(rotationDegrees: Int) = queueEvent {
        contentRotation = rotationDegrees
        requestRender()
    }

    /** Counts GL surfaces made (a new one means the camera must be handed a new surface). */
    @Volatile var surfaceGeneration = 0
        private set

    // GL thread.
    private fun direct(size: android.util.Size, rotationDegrees: Int, ready: (Surface) -> Unit) {
        val texture = surfaceTexture ?: return
        texture.setDefaultBufferSize(size.width, size.height)
        bufferWidth = size.width
        bufferHeight = size.height
        contentRotation = rotationDegrees
        cameraTransform = true
        directSurface?.release()
        val surface = Surface(texture)
        directSurface = surface
        ready(surface)
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
        surfaceGeneration++
        // not while the engine compiles its Vulkan kernels (same Mali compiler, see the lock)
        program = synchronized(org.photoall.film.engine.FilmEngine.shaderCompileLock) { buildProgram() }
        val textures = IntArray(2)
        GLES30.glGenTextures(2, textures, 0)
        cameraTexture = textures[0]
        lutTexture = textures[1]
        lutSize = 0
        if (pendingLut == null) pendingLut = lastLut // re-upload after a context loss
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        surfaceTexture?.release()
        surfaceTexture = SurfaceTexture(cameraTexture).apply {
            setOnFrameAvailableListener {
                requestRender()
                onFrame?.let { f -> onFrame = null; post(f) }
            }
        }
        pendingRequest?.let { pendingRequest = null; provide(it) }
        pendingDirect?.let { (size, rotation, ready) -> pendingDirect = null; direct(size, rotation, ready) }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val texture = surfaceTexture ?: return
        // Between one camera session and the next (HDR on or off, RAW on or off, another
        // lens) the producer leaves the texture; some phones then throw here. That frame
        // is skipped: thrown on the GL thread, it would end the app.
        try {
            texture.updateTexImage()
        } catch (e: RuntimeException) {
            android.util.Log.w("FilmViewfinder", "updateTexImage", e)
            return
        }
        texture.getTransformMatrix(transform)
        if (bufferWidth == 0) return

        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uCamera"), 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uSoften"), soften)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uSat"), saturation)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uGrain"), grain)
        grainFrame = (grainFrame + 1) and 1023
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uGrainSeed"), grainFrame * 0.6180339f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uGrainCell"), (resources.displayMetrics.density * 0.9f).coerceAtLeast(1f))
        pendingLut?.let { (data, size) -> pendingLut = null; uploadLut(data, size) }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uLut"), 1)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uHasLut"), if (lutSize > 0) 1 else 0)
        val n = lutSize.coerceAtLeast(1).toFloat()
        GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "uLutMap"), (n - 1f) / n, 0.5f / n)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uTransform"), 1, false, transform, 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uPeaking"), if (peaking) 1 else 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uGain"), gain * Math.pow(2.0, exposure.toDouble()).toFloat())
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
        val rotation = when {
            !cameraTransform -> contentRotation
            frontFacing -> (sensorRotation - contentRotation + 360) % 360
            else -> (contentRotation - sensorRotation + 360) % 360
        }
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
            uniform float uSoften; // 0..1: how much of a small blur replaces the picture
            uniform float uSat;    // saturation, 1 = unchanged
            uniform highp sampler3D uLut;
            uniform bool uHasLut;
            uniform vec2 uLutMap; // scale, offset onto texel centres
            uniform bool uPeaking;
            uniform vec2 uTexel;
            uniform float uGain;
            uniform float uGrain;     // 0..1
            uniform float uGrainSeed; // changes every frame
            uniform float uGrainCell; // screen pixels per grain
            in vec2 vTexcoord;
            out vec4 colour;
            float luma(vec2 t) { return dot(texture(uCamera, t).rgb, vec3(0.299, 0.587, 0.114)); }
            float hash(vec2 p) {
              vec3 q = fract(vec3(p.xyx) * 0.1031);
              q += dot(q, q.yzx + 33.33);
              return fract((q.x + q.y) * q.z);
            }
            // value noise on a grain-sized lattice, smoothly interpolated: soft clumps, not pixels
            float grainNoise(vec2 p) {
              vec2 i = floor(p), f = fract(p);
              f = f * f * (3.0 - 2.0 * f);
              float a = hash(i), b = hash(i + vec2(1.0, 0.0)), c = hash(i + vec2(0.0, 1.0)), d = hash(i + vec2(1.0, 1.0));
              return mix(mix(a, b, f.x), mix(c, d, f.x), f.y) - 0.5;
            }
            void main() {
              vec3 c = texture(uCamera, vTexcoord).rgb;
              if (uSoften > 0.0) {
                // a soft cross of four taps two texels out: the camera's edge sharpening undone
                vec2 ox = vec2(2.0 * uTexel.x, 0.0), oy = vec2(0.0, 2.0 * uTexel.y);
                vec3 b = 0.25 * (texture(uCamera, vTexcoord + ox).rgb + texture(uCamera, vTexcoord - ox).rgb
                               + texture(uCamera, vTexcoord + oy).rgb + texture(uCamera, vTexcoord - oy).rgb);
                c = mix(c, b, 0.8 * uSoften);
              }
              if (uGain != 1.0) { // sRGB -> linear, the gain, back
                vec3 l = mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c)) * uGain;
                l = clamp(l, 0.0, 1.0);
                c = mix(12.92 * l, 1.055 * pow(l, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, l));
              }
              if (uHasLut) c = texture(uLut, clamp(c, 0.0, 1.0) * uLutMap.x + uLutMap.y).rgb;
              else c = mix(vec3(dot(c, vec3(0.299, 0.587, 0.114))), c, uSat);
              if (uGrain > 0.0) {
                // two sizes of grain, fresh each frame; strongest in the mid tones and shadows
                // (as on a negative), little in the deepest black and the brightest white
                vec2 p = gl_FragCoord.xy / uGrainCell + vec2(uGrainSeed * 91.7, uGrainSeed * 37.3);
                float n = 0.75 * grainNoise(p) + 0.45 * grainNoise(p * 2.3 + 17.0);
                float y = dot(c, vec3(0.299, 0.587, 0.114));
                float w = smoothstep(0.0, 0.18, y) * (1.0 - 0.75 * smoothstep(0.55, 1.0, y));
                vec3 tint = vec3(1.0) + 0.15 * vec3(grainNoise(p + 5.1), grainNoise(p + 9.7), grainNoise(p + 2.3));
                c = clamp(c + n * 0.22 * uGrain * w * tint, 0.0, 1.0);
              }
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
