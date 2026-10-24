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
import android.widget.LinearLayout
import android.widget.TextView

/** Tela de abertura: fundo em gradiente, logo que aparece suave (fade), "Carregando..." digitando e, ao terminar, tudo some. */
class LoadingActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private val full = "Carregando..."
    private var n = 0
    private lateinit var tv: TextView
    private lateinit var box: View

    private val typer = object : Runnable {
        override fun run() {
            if (n <= full.length) {
                tv.text = full.substring(0, n) + "_"
                n++
                h.postDelayed(this, 90)
            } else {
                tv.text = full
                h.postDelayed({ fadeOutAndOpen() }, 450)
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

        val dm = resources.displayMetrics
        val logoW = minOf((minOf(dm.widthPixels, dm.heightPixels) * 0.62f).toInt(), T.dp(this, 320))
        val logoH = (logoW * 294f / 512f).toInt()
        val logo = ImageView(this).apply {
            setImageResource(com.meu.gbaemu.R.drawable.logo_gba)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        tv = TextView(this).apply {
            textSize = 20f
            setTextColor(T.GOLD2)
            typeface = T.font ?: Typeface.MONOSPACE
            letterSpacing = 0.06f
            gravity = Gravity.CENTER
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(logo, LinearLayout.LayoutParams(logoW, logoH))
            addView(tv, LinearLayout.LayoutParams(-2, -2).apply { topMargin = T.dp(this@LoadingActivity, 28) })
        }
        box = col
        root.addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        n = 0
        box.animate().cancel()
        box.alpha = 0f
        box.animate().alpha(1f).setDuration(700).start()   // aparece suave enquanto carrega
        h.post(typer)
    }

    override fun onStop() {
        super.onStop()
        h.removeCallbacksAndMessages(null)
        box.animate().cancel()
    }

    private fun fadeOutAndOpen() {
        if (isFinishing) return
        box.animate().alpha(0f).setDuration(450).withEndAction { openLibrary() }.start()   // carregou: some
    }

    private fun openLibrary() {
        if (isFinishing) return
        startActivity(Intent(this, LibraryActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}
