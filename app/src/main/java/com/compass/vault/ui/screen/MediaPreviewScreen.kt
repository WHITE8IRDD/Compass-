package com.compass.vault.ui.screen

import android.app.AlertDialog
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
import android.widget.TextView
import com.compass.vault.AppGraph
import com.compass.vault.MainActivity
import com.compass.vault.data.MediaFile
import com.compass.vault.ui.Navigator
import com.compass.vault.ui.Palette
import com.compass.vault.ui.Screen
import com.compass.vault.ui.Spacing
import com.compass.vault.ui.Type
import com.compass.vault.ui.body
import com.compass.vault.ui.dp
import com.compass.vault.ui.formatDuration
import com.compass.vault.ui.formatSize
import com.compass.vault.ui.hairline
import com.compass.vault.ui.hspace
import com.compass.vault.ui.label
import com.compass.vault.ui.padForSystemBars
import com.compass.vault.util.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** In-app preview for an unprotected MediaStore item. */
class MediaPreviewScreen(
    private val activity: MainActivity,
    private val graph: AppGraph,
    private val navigator: Navigator,
    private val file: MediaFile
) : Screen {

    private var scope = CoroutineScope(SupervisorJob() + AppDispatchers.main)
    private val content = LinearLayout(activity)
    private var mediaPlayer: MediaPlayer? = null
    private var surfaceHolder: SurfaceHolder? = null
    private var playLabel: TextView? = null
    private var seekBar: SeekBar? = null
    private var timeLabel: TextView? = null
    private var visible = false

    override val view: View = buildLayout()

    private fun buildLayout(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BLACK)
            padForSystemBars()
        }
        val top = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(Spacing.MD), activity.dp(Spacing.SM), activity.dp(Spacing.MD), activity.dp(Spacing.SM))
        }
        val back = activity.label("BACK", Type.LABEL, Palette.WHITE, 0.18f).apply {
            contentDescription = "Back to media library"
            setPadding(0, activity.dp(Spacing.SM), 0, activity.dp(Spacing.SM))
            setOnClickListener { navigator.showVault() }
        }
        top.addView(back)
        top.addView(activity.hspace(Spacing.MD))
        val name = activity.body(file.displayName, Type.BODY, Palette.GRAY, 500).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        top.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val info = activity.label("INFO", Type.LABEL, Palette.GRAY, 0.16f).apply {
            contentDescription = "File information"
            setPadding(activity.dp(Spacing.SM), activity.dp(Spacing.SM), activity.dp(Spacing.SM), activity.dp(Spacing.SM))
            setOnClickListener { showDetails() }
        }
        top.addView(info)
        root.addView(top)
        root.addView(activity.hairline())

        content.orientation = LinearLayout.VERTICAL
        content.gravity = Gravity.CENTER
        content.setBackgroundColor(Palette.BLACK)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        when {
            file.kind == com.compass.vault.data.VaultFile.Kind.IMAGE -> showImage()
            file.kind == com.compass.vault.data.VaultFile.Kind.VIDEO -> showVideo()
            file.kind == com.compass.vault.data.VaultFile.Kind.AUDIO -> showAudio()
            else -> showUnsupported()
        }
        return root
    }

    override fun onShow() {
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + AppDispatchers.main)
        visible = true
        if (file.kind == com.compass.vault.data.VaultFile.Kind.IMAGE) {
            scope.launch {
                val bitmap: Bitmap? = graph.mediaLibrary.preview(file)
                val imageView = content.findViewWithTag<ImageView>("public_image")
                if (bitmap != null && imageView != null) imageView.setImageBitmap(bitmap)
                content.findViewWithTag<TextView>("preview_status")?.apply {
                    text = if (bitmap == null) "Preview unavailable. The file may have moved or access may have changed." else ""
                }
            }
        } else if (file.kind == com.compass.vault.data.VaultFile.Kind.AUDIO) {
            if (mediaPlayer == null) startPlayback()
        } else if (file.kind == com.compass.vault.data.VaultFile.Kind.VIDEO) {
            surfaceHolder?.let(::startPlayback)
        }
    }

    override fun onHide() {
        visible = false
        stopPlayback()
        scope.cancel()
    }

    override fun onBack(): Boolean {
        navigator.showVault()
        return true
    }

    private fun showImage() {
        val image = ImageView(activity).apply {
            tag = "public_image"
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = file.displayName
            setBackgroundColor(Palette.BLACK)
        }
        content.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val status = activity.body("LOADING PREVIEW…", Type.BODY, Palette.GRAY).apply {
            tag = "preview_status"
            gravity = Gravity.CENTER
        }
        content.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun showVideo() {
        val surface = SurfaceView(activity).apply { setBackgroundColor(Palette.BLACK) }
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceHolder = holder
                if (visible) startPlayback(holder)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (surfaceHolder === holder) surfaceHolder = null
                stopPlayback()
            }
        })
        content.addView(surface, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addPlaybackControls()
    }

    private fun showAudio() {
        val title = activity.body(file.displayName, Type.TITLE, Palette.WHITE, 500).apply { gravity = Gravity.CENTER }
        content.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addPlaybackControls()
    }

    private fun addPlaybackControls() {
        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(Spacing.MD), activity.dp(Spacing.SM), activity.dp(Spacing.MD), activity.dp(Spacing.SM))
        }
        val play = activity.label("PLAY", Type.LABEL, Palette.WHITE, 0.18f).apply {
            contentDescription = "Play or pause media"
            setPadding(activity.dp(Spacing.SM), activity.dp(Spacing.SM), activity.dp(Spacing.SM), activity.dp(Spacing.SM))
            setOnClickListener { togglePlayback() }
        }
        playLabel = play
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
        content.addView(controls)
    }

    private fun showUnsupported() {
        val message = activity.body("This media type has no in-app preview.", Type.BODY, Palette.GRAY).apply {
            gravity = Gravity.CENTER
        }
        content.addView(message)
    }

    private fun startPlayback(holder: SurfaceHolder? = null) {
        if (!visible || mediaPlayer != null) return
        try {
            val player = MediaPlayer()
            mediaPlayer = player
            if (holder != null) player.setDisplay(holder)
            player.setDataSource(activity, file.uri)
            player.setOnPreparedListener { prepared ->
                if (!visible) return@setOnPreparedListener
                prepared.start()
                playLabel?.text = "PAUSE"
                scope.launch { updateProgress() }
            }
            player.setOnCompletionListener { completed ->
                playLabel?.text = "REPLAY"
                try { completed.seekTo(0) } catch (_: Exception) { }
            }
            player.setOnErrorListener { _, _, _ ->
                stopPlayback()
                navigator.toast("Could not play this media file")
                true
            }
            player.prepareAsync()
        } catch (_: Exception) {
            navigator.toast("This media is inaccessible or uses an unsupported codec")
            stopPlayback()
        }
    }

    private suspend fun updateProgress() {
        while (visible && mediaPlayer != null) {
            val player = mediaPlayer ?: break
            try {
                if (player.isPlaying && player.duration > 0) {
                    seekBar?.progress = (player.currentPosition.toLong() * 1000L / player.duration.toLong()).toInt()
                    timeLabel?.text = formatDuration(player.currentPosition.toLong())
                }
            } catch (_: Exception) {
                break
            }
            kotlinx.coroutines.delay(500)
        }
    }

    private fun togglePlayback() {
        val player = mediaPlayer
        if (player == null) {
            startPlayback()
            return
        }
        try {
            if (player.isPlaying) {
                player.pause()
                playLabel?.text = "PLAY"
            } else {
                player.start()
                playLabel?.text = "PAUSE"
            }
        } catch (_: Exception) {
            navigator.toast("Playback is unavailable")
        }
    }

    private fun stopPlayback() {
        val player = mediaPlayer
        mediaPlayer = null
        if (player != null) {
            try { if (player.isPlaying) player.stop() } catch (_: Exception) { }
            try { player.release() } catch (_: Exception) { }
        }
    }

    private fun showDetails() {
        val duration = if (file.durationMs > 0) "\nDuration: ${formatDuration(file.durationMs)}" else ""
        AlertDialog.Builder(activity)
            .setTitle("File information")
            .setMessage(
                "Name: ${file.displayName}\nType: ${file.mimeType}\nSize: ${formatSize(file.size)}" +
                    duration + "\nLocation: ${file.relativePath ?: "MediaStore"}"
            )
            .setPositiveButton("Close", null)
            .show()
    }
}
