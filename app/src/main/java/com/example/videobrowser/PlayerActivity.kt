package com.example.videobrowser

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem as ExoItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class PlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = intent.getStringArrayExtra("uris")
        if (uris == null || uris.isEmpty()) { finish(); return }
        val names = intent.getStringArrayExtra("names") ?: uris
        val index = intent.getIntExtra("index", 0).coerceIn(0, uris.size - 1)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val pv = PlayerView(this)
        root.addView(pv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x88000000.toInt())
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val back = iconBtn(R.drawable.ic_back, Color.WHITE).apply { setOnClickListener { finish() } }
        val title = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            maxLines = 1
            text = names.getOrNull(index) ?: ""
        }
        val rotate = iconBtn(R.drawable.ic_rotate, Color.WHITE).apply {
            setOnClickListener {
                requestedOrientation =
                    if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        }
        val share = iconBtn(R.drawable.ic_share, Color.WHITE).apply {
            setOnClickListener {
                val p = player ?: return@setOnClickListener
                val u = uris[p.currentMediaItemIndex.coerceIn(0, uris.size - 1)]
                try {
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("video/*")
                                .putExtra(Intent.EXTRA_STREAM, Uri.parse(u))
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            "مشاركة"
                        )
                    )
                } catch (e: Exception) {
                }
            }
        }
        bar.addView(back, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        bar.addView(rotate, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(share, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        setContentView(root)

        val p = ExoPlayer.Builder(this).build()
        player = p
        pv.player = p
        p.setMediaItems(uris.map { ExoItem.fromUri(it) }, index, 0L)
        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: ExoItem?, reason: Int) {
                title.text = names.getOrNull(p.currentMediaItemIndex) ?: ""
            }
        })
        p.prepare()
        p.playWhenReady = true

        pv.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            bar.visibility = if (v == View.VISIBLE) View.VISIBLE else View.GONE
        })

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onStop() {
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
