package com.lynko.app

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * Screen tab: mirrors the desktop's ScreenView surface on the phone itself.
 *
 * Lynko local preview. LocalSend is file transfer, not a mirror engine.
 *
 * The preview is LOCAL-ONLY (what the capture pipeline sees). It does NOT
 * interfere with the desktop stream — LinkService calls both
 * [ScreenSession.onFrame] (desktop WS) and this view's [onLocalFrame]
 * (phone preview) from the same JPEG.
 */
class ScreenTabView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    interface Callbacks {
        fun onStartMirror()
        fun onStopMirror()
    }

    var callbacks: Callbacks? = null

    private val main = Handler(Looper.getMainLooper())
    private lateinit var previewImg: ImageView
    private lateinit var previewHint: TextView
    private lateinit var screenState: TextView
    private lateinit var fpsText: TextView
    private lateinit var resText: TextView
    private lateinit var btnToggle: Button

    // fps counter (1-sec sliding window, same as desktop footer counter)
    private val frameTimes = ArrayDeque<Long>()
    private var lastBitmap: android.graphics.Bitmap? = null
    @Volatile private var previewing = false
    private val generation = java.util.concurrent.atomic.AtomicInteger()
    private val decodeBusy = java.util.concurrent.atomic.AtomicBoolean()
    private val decoder = java.util.concurrent.ThreadPoolExecutor(
        0, 1, 5L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue<Runnable>()
    )
    private val sink: (ByteArray) -> Unit = { onLocalFrame(it) }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_screen_tab, this, true)
        previewImg = findViewById(R.id.previewImg)
        previewHint = findViewById(R.id.previewHint)
        screenState = findViewById(R.id.screenState)
        fpsText = findViewById(R.id.fpsText)
        resText = findViewById(R.id.resText)
        btnToggle = findViewById(R.id.btnToggle)

        previewHint.text = Loc.t("phone", "screen_hint")
        btnToggle.text = Loc.t("phone", "screen_start")
        btnToggle.setOnClickListener { v ->
            Haptics.tap(v)
            if (PhoneState.mirror) callbacks?.onStopMirror()
            else callbacks?.onStartMirror()
        }
        render()
    }

    /** Refresh labels from PhoneState (called from MainActivity.onChange). */
    fun render() {
        if (!previewing) return
        val mirroring = PhoneState.mirror
        val linked = PhoneState.link
        screenState.text = when {
            mirroring && linked -> Loc.t("phone", "screen_state_streaming")
            mirroring -> Loc.t("phone", "screen_state_local")
            LinkService.running -> Loc.t("phone", "screen_state_ready")
            else -> Loc.t("phone", "screen_state_idle")
        }
        screenState.setTextColor(
            if (mirroring) 0xFFFFB454.toInt() else 0xFF555658.toInt()
        )
        btnToggle.text = if (mirroring)
            Loc.t("phone", "screen_stop")
        else
            Loc.t("phone", "screen_start")
        previewHint.visibility = if (PhoneState.mirror) View.GONE else View.VISIBLE
        if (!mirroring) {
            generation.incrementAndGet()
            previewImg.setImageDrawable(null)
            lastBitmap = null
            fpsText.text = "0 fps"
            resText.text = ""
            frameTimes.clear()
        }
    }

    /**
     * Called by LinkService for every captured JPEG when the Screen tab is
     * visible. Throttled to 10fps locally (the desktop gets full rate) —
     * decoding every frame on the phone would double CPU for a preview.
     */
    private var lastPreviewAt = 0L
    fun onLocalFrame(jpeg: ByteArray) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!previewing || !PhoneState.mirror || now - lastPreviewAt < 100L) return
        if (!decodeBusy.compareAndSet(false, true)) return
        lastPreviewAt = now
        val epoch = generation.get()
        decoder.execute {
            val bmp = try { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) } catch (_: Exception) { null }
            main.post {
                try {
                    if (bmp == null) return@post
                    if (!previewing || !PhoneState.mirror || generation.get() != epoch) {
                        bmp.recycle() // Never handed to the renderer.
                        return@post
                    }
                    previewImg.setImageBitmap(bmp)
                    // Previously displayed bitmaps may still belong to RenderThread.
                    // Drop our reference; let Android reclaim them safely.
                    lastBitmap = bmp
                    previewHint.visibility = View.GONE
                    frameTimes.addLast(now)
                    while (frameTimes.isNotEmpty() && now - frameTimes.first() > 1000L) frameTimes.removeFirst()
                    fpsText.text = "${frameTimes.size} preview fps"
                    resText.text = "${bmp.width}×${bmp.height}"
                } finally { decodeBusy.set(false) }
            }
        }
    }

    /** Called when the tab becomes visible / hidden (bottom nav switch). */
    fun setPreviewing(v: Boolean) {
        generation.incrementAndGet()
        previewing = v
        if (!v) {
            previewImg.setImageDrawable(null)
            lastBitmap = null
            frameTimes.clear()
            fpsText.text = "0 fps"
            resText.text = ""
        } else render()
    }

    /** Install only while this tab is visible and resumed. */
    fun attachAsPreviewSink() {
        ScreenPreview.sink = sink
    }

    fun detachPreviewSink() {
        if (ScreenPreview.sink === sink) ScreenPreview.sink = null
    }
}

/**
 * Global preview sink: LinkService routes every captured JPEG here in
 * addition to the desktop WS broadcast. Null when the Screen tab is hidden
 * (zero overhead — no decode, no bitmap).
 */
object ScreenPreview {
    @Volatile
    var sink: ((ByteArray) -> Unit)? = null
}
