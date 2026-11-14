package org.photoall.film.reel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 拍摄录屏, for showing a shot being taken: while it is on, the screen is encoded all the
 * time at 2K (the short side at least 1440 px) into a few seconds kept in memory; each
 * press of the shutter keeps [before] seconds from before it and [after] seconds from
 * after, written as an MP4 to Movies/Photo All. Nothing is written between shots.
 *
 * The screen comes from MediaProjection, so the system asks once each time it is turned
 * on, and a foreground service ([Service]) holds it while it runs.
 */
object Reel {
    private const val TAG = "Reel"
    private const val CHANNEL = "reel"
    private const val NOTE_RUNNING = 41
    private const val NOTE_SAVED = 42
    /** The short side of the recording: 2K. */
    const val SHORT_SIDE = 1440

    /** What the camera screen shows: running or not, and a line about it. */
    class State(val running: Boolean, val detail: String)

    @Volatile var state = State(false, "")
        private set
    /** Called on the main thread when [state] changes or a clip is saved. */
    @Volatile var listener: ((State) -> Unit)? = null

    @Volatile private var recorder: Recorder? = null
    private val main by lazy { Handler(android.os.Looper.getMainLooper()) }

    /** Seconds kept from before the shot and recorded after it (set from the settings). */
    @Volatile var before = 10f
    @Volatile var after = 10f

    fun consentIntent(context: Context): Intent =
        context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()

    /** After the system's consent: start the service that records. [width] x [height] is the screen now. */
    fun start(context: Context, resultCode: Int, data: Intent, width: Int, height: Int, dpi: Int) {
        val intent = Intent(context, Service::class.java)
            .putExtra("code", resultCode).putExtra("data", data)
            .putExtra("w", width).putExtra("h", height).putExtra("dpi", dpi)
        context.startForegroundService(intent)
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, Service::class.java))
    }

    /** The shutter was pressed (each frame of a burst calls it too: one clip covers them all). */
    fun mark() {
        recorder?.mark()
    }

    /** The screen turned or changed size: record at the new shape (the seconds kept start again). */
    fun screenChanged(width: Int, height: Int, dpi: Int) {
        recorder?.resize(width, height, dpi)
    }

    private fun post(running: Boolean, detail: String) {
        state = State(running, detail)
        main.post { listener?.invoke(state) }
    }

    /** The recording size for a screen of [w] x [h]: the short side at least 2K, then what the encoder can take. */
    internal fun plan(w: Int, h: Int): Plan? {
        val scale = max(1f, SHORT_SIDE.toFloat() / min(w, h))
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (mime in listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)) {
            for (info in list.codecInfos) {
                if (!info.isEncoder || info.isSoftwareOnly) continue
                if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
                val video = info.getCapabilitiesForType(mime).videoCapabilities ?: continue
                val wa = max(video.widthAlignment, 16)
                val ha = max(video.heightAlignment, 16)
                val tw = (w * scale / wa).roundToInt() * wa
                val th = (h * scale / ha).roundToInt() * ha
                for (fps in listOf(60, 30)) {
                    if (!video.areSizeAndRateSupported(tw, th, fps.toDouble())) continue
                    val rate = (tw.toLong() * th * fps * (if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) 0.06 else 0.1)).toInt()
                    return Plan(info.name, mime, tw, th, fps, video.bitrateRange.clamp(rate))
                }
            }
        }
        return null
    }

    internal class Plan(val codec: String, val mime: String, val width: Int, val height: Int, val fps: Int, val bitrate: Int) {
        override fun toString() = "$codec ${if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) "HEVC" else "H.264"} ${width}x$height ${fps}fps ${bitrate / 1_000_000}Mbps"
    }

    // ----- the service -------------------------------------------------------------------

    class Service : android.app.Service() {
        private var projection: MediaProjection? = null

        override fun onBind(intent: Intent?): IBinder? = null

        override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            intent ?: return START_NOT_STICKY
            val notes = getSystemService(NotificationManager::class.java)
            notes.createNotificationChannel(NotificationChannel(CHANNEL, "拍摄录屏", NotificationManager.IMPORTANCE_LOW))
            val note = note("拍摄录屏中", "每次按快门保存前 ${before.toInt()} 秒和后 ${after.toInt()} 秒")
            // before the projection is taken (required from Android 14)
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_RUNNING, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(NOTE_RUNNING, note)
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("data", Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra("data")
            val manager = getSystemService(MediaProjectionManager::class.java)
            val p = runCatching { manager.getMediaProjection(intent.getIntExtra("code", 0), data!!) }.getOrNull()
            if (p == null) {
                post(false, "没能开始录屏")
                stopSelf()
                return START_NOT_STICKY
            }
            projection = p
            val thread = HandlerThread("reel").apply { start() }
            val handler = Handler(thread.looper)
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    main.post { stopSelf() }
                }
                // Android 14: the captured content (the screen, or the one app chosen) changed shape
                override fun onCapturedContentResize(width: Int, height: Int) {
                    recorder?.resize(width, height, 0)
                }
            }, handler)
            val r = Recorder(this, p, handler, thread)
            recorder = r
            r.begin(intent.getIntExtra("w", 1440), intent.getIntExtra("h", 3088), intent.getIntExtra("dpi", 480))
            return START_NOT_STICKY
        }

        override fun onDestroy() {
            recorder?.release()
            recorder = null
            projection?.stop()
            projection = null
            post(false, "")
            super.onDestroy()
        }

        private fun note(title: String, text: String): Notification =
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle(title).setContentText(text)
                .setOngoing(true).build()

        internal fun saved(seconds: Float, plan: Plan) {
            val text = "已保存 %.1f 秒 · %d×%d · 相册 Movies/Photo All".format(seconds, plan.width, plan.height)
            getSystemService(NotificationManager::class.java).notify(NOTE_SAVED,
                Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_save)
                    .setContentTitle("录屏已保存").setContentText(text).setAutoCancel(true).build())
            post(true, text)
        }

        internal fun running(plan: Plan?) {
            if (plan == null) {
                post(false, "这台手机的编码器不支持 2K 录屏")
                main.post { stopSelf() }
                return
            }
            val line = "录屏中 · ${plan.width}×${plan.height} ${plan.fps}fps"
            getSystemService(NotificationManager::class.java).notify(NOTE_RUNNING, note("拍摄录屏中", line))
            post(true, line)
        }
    }

    // ----- encoding, the seconds kept, the clips -------------------------------------------

    private class Sample(val data: ByteArray, val pts: Long, val flags: Int)

    private class Recorder(
        private val service: Service,
        private val projection: MediaProjection,
        private val handler: Handler,
        private val thread: HandlerThread,
    ) {
        private var display: VirtualDisplay? = null
        private var codec: MediaCodec? = null
        private var format: MediaFormat? = null
        private var plan: Plan? = null
        private var dpi = 480
        private val samples = ArrayDeque<Sample>()
        /** The pts of the first shot of the clip being recorded, and until when it runs; -1: none. */
        private var clipFrom = -1L
        private var clipUntil = -1L
        private val writer = Executors.newSingleThreadExecutor()

        fun begin(w: Int, h: Int, dpi: Int) = handler.post { open(w, h, dpi) }

        fun resize(w: Int, h: Int, dpi: Int) = handler.post {
            val p = plan
            // the same shape as now: nothing to do
            if (p != null && (p.width >= p.height) == (w >= h) &&
                kotlin.math.abs(w.toFloat() / h - p.width.toFloat() / p.height) < 0.02f) return@post
            if (clipFrom >= 0) finishClip() // keep what a shot already has
            open(w, h, if (dpi > 0) dpi else this.dpi)
        }

        private fun open(w: Int, h: Int, dpi: Int) {
            closeCodec()
            this.dpi = dpi
            val p = plan(w, h)
            plan = p
            if (p == null) {
                service.running(null)
                return
            }
            val f = MediaFormat.createVideoFormat(p.mime, p.width, p.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, p.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, p.fps)
                setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 1f) // a clip may start at most a second early
                // a still screen still yields frames (a clip without gaps)
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / p.fps)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
            val c = MediaCodec.createByCodecName(p.codec)
            c.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    if (codec !== this@Recorder.codec) return
                    val buffer = runCatching { codec.getOutputBuffer(index) }.getOrNull()
                    if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val bytes = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(bytes)
                        took(Sample(bytes, info.presentationTimeUs, info.flags))
                    }
                    runCatching { codec.releaseOutputBuffer(index, false) }
                }
                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.w(TAG, "encoder", e)
                }
                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    if (codec === this@Recorder.codec) this@Recorder.format = format
                }
            }, handler)
            c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = c.createInputSurface()
            c.start()
            codec = c
            val d = display
            if (d == null) {
                display = projection.createVirtualDisplay("PhotoAllReel", p.width, p.height, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, handler)
            } else {
                d.resize(p.width, p.height, dpi)
                d.surface = surface
            }
            Log.i(TAG, "recording $p")
            service.running(p)
        }

        private fun took(s: Sample) {
            samples.addLast(s)
            if (clipFrom >= 0) {
                if (s.pts >= clipUntil) finishClip()
                return
            }
            // keep [before] seconds, from a key frame
            val keepFrom = s.pts - (before * 1_000_000).toLong()
            while (samples.size > 1) {
                val nextKey = samples.indexOfFirst { it.pts > samples.first().pts && it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 }
                if (nextKey <= 0 || samples[nextKey].pts > keepFrom) break
                repeat(nextKey) { samples.removeFirst() }
            }
        }

        fun mark() = handler.post {
            val now = samples.lastOrNull()?.pts ?: return@post
            if (clipFrom < 0) clipFrom = now
            clipUntil = now + (after * 1_000_000).toLong()
        }

        private fun finishClip() {
            val from = clipFrom
            clipFrom = -1
            clipUntil = -1
            val f = format ?: return
            val p = plan ?: return
            val start = samples.indexOfLast { it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 && it.pts <= from - (before * 1_000_000).toLong() }
                .takeIf { it >= 0 } ?: samples.indexOfFirst { it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 }
            if (start < 0) return
            val clip = samples.toList().subList(start, samples.size)
            // the seconds before the next shot start over from here
            while (samples.size > 1) samples.removeFirst()
            writer.execute { write(clip, f, p) }
        }

        private fun write(clip: List<Sample>, format: MediaFormat, plan: Plan) {
            val name = "PhotoAll_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".mp4"
            val temp = File(service.cacheDir, name)
            try {
                val muxer = MediaMuxer(temp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val track = muxer.addTrack(format)
                muxer.start()
                val info = MediaCodec.BufferInfo()
                val first = clip.first().pts
                for (s in clip) {
                    info.set(0, s.data.size, s.pts - first, s.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    muxer.writeSampleData(track, java.nio.ByteBuffer.wrap(s.data), info)
                }
                muxer.stop()
                muxer.release()
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Photo All")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val resolver = service.contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("no media store entry")
                resolver.openOutputStream(uri)!!.use { out -> temp.inputStream().use { it.copyTo(out) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                service.saved((clip.last().pts - first) / 1e6f, plan)
            } catch (e: Throwable) {
                Log.w(TAG, "write", e)
                post(true, "录屏保存失败：${e.message}")
            } finally {
                temp.delete()
            }
        }

        private fun closeCodec() {
            val c = codec ?: return
            codec = null
            format = null
            samples.clear()
            runCatching { c.signalEndOfInputStream() }
            runCatching { c.stop() }
            runCatching { c.release() }
        }

        fun release() {
            handler.post {
                if (clipFrom >= 0) finishClip()
                display?.release()
                display = null
                closeCodec()
                writer.shutdown()
                thread.quitSafely()
            }
        }
    }
}
