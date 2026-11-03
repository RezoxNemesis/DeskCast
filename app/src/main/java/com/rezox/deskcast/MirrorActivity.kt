package com.rezox.deskcast

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout

/**
 * Runs on the external screen and shows the phone's screen, scaled to fill it as much as possible
 * while keeping the shape. Great for games: the picture is composited by the GPU, nothing is encoded.
 */
class MirrorActivity : Activity() {

    companion object {
        var current: MirrorActivity? = null
            private set
    }

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surfaceView = SurfaceView(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        setContentView(root)
        MonitorProbe.applyBestMode(window, display)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                ProjectionService.instance?.attachMirrorSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                ProjectionService.instance?.attachMirrorSurface(holder.surface)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                ProjectionService.instance?.attachMirrorSurface(null)
            }
        })

        ProjectionService.instance?.onMirrorSizeChanged = { s -> runOnUiThread { fit(s) } }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            ProjectionService.instance?.mirrorSize?.let { fit(it) }
        }
    }

    private fun fit(src: Size) {
        val w = root.width; val h = root.height
        if (w == 0 || h == 0 || src.width == 0 || src.height == 0) return
        val scale = minOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val tw = (src.width * scale).toInt(); val th = (src.height * scale).toInt()
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        if (lp.width != tw || lp.height != th) {
            lp.width = tw; lp.height = th
            surfaceView.layoutParams = lp
        }
    }

    override fun onDestroy() {
        if (current === this) current = null
        ProjectionService.instance?.let {
            it.onMirrorSizeChanged = null
            it.attachMirrorSurface(null)
        }
        super.onDestroy()
    }
}
