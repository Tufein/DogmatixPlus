package com.tufein.valkyr.util

import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window

/**
 * 4.7.0: collects the time the app's window needed for each frame while something scrolls, through
 * `Window.addOnFrameMetricsAvailableListener` (the platform API behind JankStats; available since
 * API 24, so on every supported device). A frame's total duration runs from the start of its
 * input/animation work to the moment its drawing is handed to the display, so a number above one
 * refresh interval is a frame that was shown late. The first frame after the listener is added is
 * left out (it carries the one-off setup of the window).
 *
 * The callback runs on its own thread so measuring does not add work to the main thread. Based on
 * the public documentation of `FrameMetrics`; not verified on a handheld from here.
 */
class FrameSampler(private val window: Window) {
    private val lock = Any()
    private val durationsMs = ArrayList<Double>()
    private var thread: HandlerThread? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null

    /** Starts collecting; does nothing when already running. */
    fun start() {
        if (listener != null) return
        synchronized(lock) { durationsMs.clear() }
        val t = HandlerThread("valkyr-frames").also { it.start() }
        val l = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener
            val ms = metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0
            synchronized(lock) { if (durationsMs.size < PerfCheck.MAX_FRAMES) durationsMs.add(ms) }
        }
        thread = t
        listener = l
        window.addOnFrameMetricsAvailableListener(l, Handler(t.looper))
    }

    /** Stops collecting and returns the frame durations in ms; empty when never started. */
    fun stop(): List<Double> {
        val l = listener ?: return emptyList()
        runCatching { window.removeOnFrameMetricsAvailableListener(l) }
        listener = null
        thread?.quitSafely()
        thread = null
        return synchronized(lock) { durationsMs.toList() }
    }
}
