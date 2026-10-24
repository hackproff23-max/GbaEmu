package com.meu.gbaemu.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/** Tela de abertura: fundo em gradiente (res/drawable-nodpi/loading_bg.png) e "Carregando..." sendo digitado. */
class LoadingActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private val full = "Carregando..."
    private var n = 0
    private lateinit var tv: TextView

    private val typer = object : Runnable {
        override fun run() {
            if (n <= full.length) {
                tv.text = full.substring(0, n) + "_"
                n++
                h.postDelayed(this, 110)
            } else {
                tv.text = full
                h.postDelayed({ openLibrary() }, 650)
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = Prefs(this)               // carrega cores e fonte importada
        p.applyOrientation(this)
        window.statusBarColor = Color.BLACK
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(ImageView(this).apply {
            setImageResource(com.meu.gbaemu.R.drawable.loading_bg)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }, FrameLayout.LayoutParams(-1, -1))
        tv = TextView(this).apply {
            textSize = 20f
            setTextColor(T.GOLD2)
            typeface = T.font ?: Typeface.MONOSPACE
            letterSpacing = 0.06f
        }
        root.addView(tv, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = (resources.displayMetrics.heightPixels * 0.18f).toInt()
        })
        setContentView(root)
    }

    override fun onStart() { super.onStart(); n = 0; h.post(typer) }
    override fun onStop() { super.onStop(); h.removeCallbacksAndMessages(null) }

    private fun openLibrary() {
        if (isFinishing) return
        startActivity(Intent(this, LibraryActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}
