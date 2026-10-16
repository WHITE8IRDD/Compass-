package com.compass.vault.ui.screen

import android.graphics.Bitmap
import android.media.MediaPlayer
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import com.compass.vault.AppGraph
import com.compass.vault.MainActivity
import com.compass.vault.data.VaultFile
import com.compass.vault.ui.Navigator
import com.compass.vault.ui.Palette
import com.compass.vault.ui.Screen
import com.compass.vault.ui.Spacing
import com.compass.vault.ui.Type
import com.compass.vault.ui.body
import com.compass.vault.ui.dp
import com.compass.vault.ui.formatDuration
import com.compass.vault.ui.hairline
import com.compass.vault.ui.label
import com.compass.vault.ui.outlineButton
import com.compass.vault.ui.padForSystemBars
import com.compass.vault.ui.spacer
import com.compass.vault.util.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.compass.vault.ui.hspace

/**
 * The viewer for images and videos.
 *
 * Images are decoded from the encrypted payload straight into memory - the
 * plaintext is never written to disk.
 *
 * Videos need a seekable file, so the payload is decrypted into a temporary
 * file in the cache directory. That file is wiped the moment playback stops
 * and whenever the vault locks.
 */
class ViewerScreen(
    private val activity: MainActivity,
    private val graph: AppGraph,
    private val navigator: Navigator,
    private val file: VaultFile
) : Screen {

    private var scope = CoroutineScope(SupervisorJob() + AppDispatchers.main)

    private val chrome = LinearLayout(activity)
    private val bodyContainer = LinearLayout(activity)

    private var mediaPlayer: MediaPlayer? = null
    private var tempFile: java.io.File? = null
    private var seekBar: SeekBar? = null
    private var playLabel: android.widget.TextView? = null
    private var timeLabel: android.widget.TextView? = null
    private var chromeVisible = true

    override val view: View = buildLayout()

    private fun buildLayout(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BLACK)
            padForSystemBars()
        }

        // ---- top bar --------------------------------------------------------
        chrome.orientation = LinearLayout.HORIZONTAL
        chrome.gravity = Gravity.CENTER_VERTICAL
        chrome.setPadding(activity.dp(Spacing.MD), activity.dp(Spacing.SM), activity.dp(Spacing.MD), activity.dp(Spacing.SM))

        val close = activity.label("CLOSE", Type.LABEL, Palette.WHITE, 0.18f)
        close.setPadding(0, activity.dp(Spacing.SM), 0, activity.dp(Spacing.SM))
        close.setOnClickListener { navigator.showVault() }
        chrome.addView(close)

        chrome.addView(activity.hspace(Spacing.MD))
        val title = activity.body(file.name, Type.BODY, Palette.GRAY, 500)
        title.maxLines = 1
        title.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        chrome.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(chrome)
        root.addView(activity.hairline())

        // ---- content --------------------------------------------------------
        bodyContainer.orientation = LinearLayout.VERTICAL
        bodyContainer.gravity = Gravity.CENTER
        bodyContainer.setBackgroundColor(Palette.BLACK)
        root.addView(bodyContainer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        when {
            file.isImage -> showImage()
            file.isVideo -> showVideo()
            else -> showDocument()
        }
        return root
    }

    override fun onShow() {
        // The scope is cancelled in onHide(); a fresh one is needed when the
        // very same screen instance is shown again.
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + AppDispatchers.main)
        if (file.isImage) {
            scope.launch {
                val bitmap = graph.repository.decodeImage(file, 2048)
                val imageView = bodyContainer.findViewWithTag<ImageView>("image")
                if (imageView != null && bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                }
                bodyContainer.findViewWithTag<android.widget.TextView>("loading")
                    ?.let { it.visibility = View.GONE }
            }
        }
    }

    override fun onHide() {
        stopPlayback()
        scope.cancel()
    }

    override fun onBack(): Boolean {
        navigator.showVault()
        return true
    }

    // -----------------------------------------------------------------------
    // Image
    // -----------------------------------------------------------------------

    private fun showImage() {
        val imageView = ImageView(activity).apply {
            tag = "image"
            setBackgroundColor(Palette.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener { toggleChrome() }
        }
        bodyContainer.addView(imageView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val loading = activity.label("DECRYPTING…", Type.LABEL, Palette.GRAY, 0.2f)
        loading.tag = "loading"
        loading.gravity = Gravity.CENTER
        bodyContainer.addView(loading, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    // -----------------------------------------------------------------------
    // Video
    // -----------------------------------------------------------------------

    private fun showVideo() {
        val surface = SurfaceView(activity)
        surface.setBackgroundColor(Palette.BLACK)
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = startPlayback(holder)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        })
        bodyContainer.addView(surface, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(Spacing.MD), activity.dp(Spacing.SM), activity.dp(Spacing.MD), activity.dp(Spacing.SM))
        }
        val play = activity.label("PLAY", Type.LABEL, Palette.WHITE, 0.18f)
        playLabel = play
        play.setOnClickListener { togglePlayPause() }
        controls.addView(play)

        val seek = SeekBar(activity).apply {
            max = 1000
            setProgressTintList(android.content.res.ColorStateList.valueOf(Palette.WHITE))
            setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Palette.HAIRLINE))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val player = mediaPlayer ?: return
                    if (fromUser && player.duration > 0) {
                        player.seekTo((player.duration.toLong() * progress / 1000L).toInt())
                    }
                }

                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        seekBar = seek
        controls.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val time = activity.label("0:00", Type.LABEL, Palette.GRAY, 0.1f)
        timeLabel = time
        controls.addView(time)
        bodyContainer.addView(controls)
    }

    private fun startPlayback(holder: SurfaceHolder) {
        scope.launch {
            val decrypted = try {
                graph.repository.decryptToTemp(file)
            } catch (e: Exception) {
                navigator.toast("Could not decrypt the file")
                return@launch
            }
            tempFile = decrypted

            try {
                val player = MediaPlayer()
                mediaPlayer = player
                player.setDisplay(holder)
                player.setDataSource(decrypted.absolutePath)
                player.setOnPreparedListener {
                    it.start()
                    playLabel?.text = "PAUSE"
                    scope.launch { updateProgress() }
                }
                player.setOnCompletionListener {
                    playLabel?.text = "REPLAY"
                    it.seekTo(0)
                }
                player.prepareAsync()
            } catch (e: Exception) {
                navigator.toast("Could not play this file")
            }
        }
    }

    private suspend fun updateProgress() {
        while (mediaPlayer != null) {
            val player = mediaPlayer ?: break
            if (player.isPlaying && player.duration > 0) {
                seekBar?.progress = (player.currentPosition.toLong() * 1000L / player.duration.toLong()).toInt()
                timeLabel?.text = formatDuration(player.currentPosition.toLong())
            }
            kotlinx.coroutines.delay(500)
        }
    }

    private fun togglePlayPause() {
        val player = mediaPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            playLabel?.text = "PLAY"
        } else {
            player.start()
            playLabel?.text = "PAUSE"
        }
    }

    /**
     * Stops playback and wipes the decrypted temporary file immediately.
     */
    private fun stopPlayback() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Exception) {
        }
        mediaPlayer = null
        tempFile?.let { com.compass.vault.security.SecureWipe.wipe(it) }
        tempFile = null
    }

    // -----------------------------------------------------------------------
    // Document
    // -----------------------------------------------------------------------

    private fun showDocument() {
        val message = activity.body(
            "There is no preview for this file type.\nUse EXPORT to save a decrypted copy.",
            Type.BODY, Palette.GRAY
        )
        message.gravity = Gravity.CENTER
        bodyContainer.addView(message, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        bodyContainer.addView(activity.spacer(Spacing.LG))
        bodyContainer.addView(activity.outlineButton("EXPORT A COPY", {
            navigator.showVault()
        }), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    // -----------------------------------------------------------------------

    private fun toggleChrome() {
        chromeVisible = !chromeVisible
        chrome.visibility = if (chromeVisible) View.VISIBLE else View.GONE
    }
}
