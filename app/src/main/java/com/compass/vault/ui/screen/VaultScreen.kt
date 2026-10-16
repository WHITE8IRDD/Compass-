package com.compass.vault.ui.screen

import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
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
import com.compass.vault.ui.formatSize
import com.compass.vault.ui.hairline
import com.compass.vault.ui.hspace
import com.compass.vault.ui.label
import com.compass.vault.ui.outlineButton
import com.compass.vault.ui.outlineButtonRow
import com.compass.vault.ui.padForSystemBars
import com.compass.vault.ui.spacer
import com.compass.vault.util.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The vault: the encrypted file list.
 *
 * Everything the user can do with a file lives here:
 *  * import through the system picker (streamed straight into the encrypted
 *    storage, then the original is offered for deletion),
 *  * open images and videos,
 *  * export a decrypted copy to a place of their choosing,
 *  * move a file into a folder,
 *  * delete a file (secure wipe).
 */
class VaultScreen(
    private val activity: MainActivity,
    private val graph: AppGraph,
    private val navigator: Navigator
) : Screen {

    private var scope = CoroutineScope(SupervisorJob() + AppDispatchers.main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val adapter = FileAdapter()
    private val listView = ListView(activity)
    private val countLabel = activity.label("", Type.LABEL, Palette.GRAY, 0.16f)
    private val statusLabel = activity.body("", Type.BODY, Palette.GRAY)
    private val emptyLabel = activity.body("Nothing here yet.\nImport a file to keep it safe.", Type.BODY, Palette.GRAY)
    private val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
    private val folderRow = LinearLayout(activity)

    private var files: List<VaultFile> = emptyList()
    private var folders: List<String> = emptyList()
    private var currentFolder: String? = null

    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.US)

    override val view: View = buildLayout()

    private fun buildLayout(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BLACK)
            padForSystemBars()
        }

        // ---- top bar --------------------------------------------------------
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(Spacing.MD), activity.dp(Spacing.SM), activity.dp(Spacing.MD), activity.dp(Spacing.SM))
        }
        val title = activity.body("VAULT", Type.SCREEN_TITLE, Palette.WHITE, 600)
        topBar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        countLabel.gravity = Gravity.CENTER_VERTICAL
        topBar.addView(countLabel)
        topBar.addView(activity.hspace(Spacing.MD))
        val settings = activity.label("SETTINGS", Type.LABEL, Palette.GRAY, 0.18f)
        settings.setPadding(0, activity.dp(Spacing.SM), 0, activity.dp(Spacing.SM))
        settings.setOnClickListener { navigator.showSettings() }
        topBar.addView(settings)
        root.addView(topBar)
        root.addView(activity.hairline())

        // ---- folder chips ---------------------------------------------------
        val scroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(activity.dp(Spacing.MD), 0, activity.dp(Spacing.MD), 0)
        }
        folderRow.orientation = LinearLayout.HORIZONTAL
        folderRow.gravity = Gravity.CENTER_VERTICAL
        scroll.addView(folderRow, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(activity.hairline())

        // ---- progress -------------------------------------------------------
        progressBar.max = 100
        progressBar.progress = 0
        progressBar.visibility = View.GONE
        progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Palette.WHITE))
        progressBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Palette.HAIRLINE))
        root.addView(progressBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        statusLabel.visibility = View.GONE
        statusLabel.setPadding(
            activity.dp(Spacing.MD), activity.dp(Spacing.SM),
            activity.dp(Spacing.MD), activity.dp(Spacing.SM)
        )
        root.addView(statusLabel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // ---- list -----------------------------------------------------------
        listView.adapter = adapter
        listView.divider = null
        listView.dividerHeight = 0
        listView.setBackgroundColor(Palette.BLACK)
        listView.setOnItemClickListener { _, _, position, _ ->
            val file = adapter.getItem(position)
            if (file != null) openFile(file)
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val file = adapter.getItem(position)
            if (file != null) {
                showActions(file)
                true
            } else {
                false
            }
        }
        root.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        emptyLabel.gravity = Gravity.CENTER
        emptyLabel.visibility = View.GONE
        root.addView(emptyLabel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // ---- bottom bar -----------------------------------------------------
        val bottom = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(activity.dp(Spacing.MD), activity.dp(Spacing.SM), activity.dp(Spacing.MD), activity.dp(Spacing.SM))
        }
        bottom.addView(activity.outlineButton("IMPORT", { startImport() }, emphasized = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(activity.hspace(Spacing.MD))
        bottom.addView(activity.outlineButton("LOCK", { graph.lockController.lock() }),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bottom)
        root.addView(activity.hairline())

        return root
    }

    override fun onShow() {
        // The scope is cancelled in onHide(); a fresh one is needed when the
        // very same screen instance is shown again.
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + AppDispatchers.main)
        refresh()
    }

    override fun onHide() {
        scope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
    }

    override fun onBack(): Boolean {
        navigator.showCompass()
        return true
    }

    // -----------------------------------------------------------------------
    // Data
    // -----------------------------------------------------------------------

    private fun refresh() {
        scope.launch {
            val all = graph.repository.list()
            folders = graph.repository.folders()
            files = if (currentFolder == null) all else all.filter { it.folder == currentFolder }
            adapter.notifyDataSetChanged()
            countLabel.text = if (files.isEmpty()) "" else "${files.size} ITEM${if (files.size == 1) "" else "S"}"
            emptyLabel.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
            rebuildFolderChips()
        }
    }

    private fun rebuildFolderChips() {
        folderRow.removeAllViews()

        folderRow.addView(makeChip("ALL", currentFolder == null) {
            currentFolder = null
            refresh()
        })
        folderRow.addView(activity.hspace(Spacing.SM))

        folders.forEach { name ->
            folderRow.addView(makeChip(name.uppercase(), currentFolder == name) {
                currentFolder = name
                refresh()
            })
            folderRow.addView(activity.hspace(Spacing.SM))
        }

        val add = activity.label("+ NEW", Type.LABEL, Palette.GRAY_DIM, 0.16f)
        add.setPadding(activity.dp(Spacing.MD), activity.dp(Spacing.SM), activity.dp(Spacing.MD), activity.dp(Spacing.SM))
        add.setOnClickListener { showNewFolderDialog() }
        folderRow.addView(add)
    }

    /** A small pill shaped filter button. */
    private fun makeChip(text: String, selected: Boolean, onClick: () -> Unit): TextView {
        val chip = activity.label(text, Type.LABEL, Palette.WHITE, 0.16f)
        chip.setPadding(
            activity.dp(Spacing.MD), activity.dp(Spacing.SM),
            activity.dp(Spacing.MD), activity.dp(Spacing.SM)
        )
        chip.setTextColor(if (selected) Palette.BLACK else Palette.WHITE)
        chip.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = activity.dp(14).toFloat()
            setColor(if (selected) Palette.WHITE else android.graphics.Color.TRANSPARENT)
            setStroke(activity.dp(1), if (selected) Palette.WHITE else Palette.HAIRLINE)
        }
        chip.setOnClickListener { onClick() }
        return chip
    }

    // -----------------------------------------------------------------------
    // Import
    // -----------------------------------------------------------------------

    private fun startImport() {
        navigator.pickFiles(
            arrayOf("image/*", "video/*", "application/*", "text/*", "audio/*")
        ) { uris -> importUris(uris) }
    }

    private fun importUris(uris: List<Uri>) {
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        statusLabel.visibility = View.VISIBLE
        statusLabel.text = "IMPORTING 0%"

        scope.launch {
            var done = 0
            var failed = 0
            for (uri in uris) {
                // The size of the source is not known in advance (the picker
                // only hands over a stream), so the progress is shown as the
                // number of bytes that have been encrypted so far.
                var lastPosted = 0L
                try {
                    graph.repository.import(uri, currentFolder) { bytes ->
                        // Only post every 256 KB so the main thread stays
                        // free even for multi gigabyte files.
                        if (bytes - lastPosted > 256 * 1024) {
                            lastPosted = bytes
                            val text = "IMPORTING ${formatSize(bytes)}"
                            mainHandler.post { statusLabel.text = text }
                        }
                    }
                } catch (e: Exception) {
                    failed++
                    navigator.toast("Could not import one of the files")
                }
                done++
                updateProgress((done * 100) / uris.size)
            }

            progressBar.visibility = View.GONE
            statusLabel.visibility = View.GONE
            refresh()

            // Offer to delete the originals from the gallery.
            val asked = navigator.requestDeleteOriginals(uris)
            if (asked) {
                navigator.toast("Confirm the deletion, then empty your gallery trash")
            } else {
                navigator.toast("Delete the original copies from your gallery by hand")
            }
        }
    }

    private fun updateProgress(percent: Int) {
        progressBar.progress = percent
        statusLabel.text = "IMPORTING $percent%"
    }

    // -----------------------------------------------------------------------
    // File actions
    // -----------------------------------------------------------------------

    private fun openFile(file: VaultFile) {
        if (file.isImage || file.isVideo) {
            navigator.showViewer(file)
        } else {
            showActions(file)
        }
    }

    private fun showActions(file: VaultFile) {
        val options = arrayOf("Open", "Export a copy", "Move to folder", "Delete")
        AlertDialog.Builder(activity)
            .setTitle(file.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openFile(file)
                    1 -> confirmExport(file)
                    2 -> showMoveDialog(file)
                    3 -> confirmDelete(file)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmExport(file: VaultFile) {
        AlertDialog.Builder(activity)
            .setTitle("Export a copy?")
            .setMessage(
                "This writes an UNENCRYPTED copy of \"${file.name}\" to a place you choose. " +
                    "Anyone with access to that file can read it. The vault itself stays encrypted."
            )
            .setPositiveButton("Export") { _, _ -> export(file) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun export(file: VaultFile) {
        navigator.pickExportTarget(file.name) { target ->
            scope.launch {
                progressBar.visibility = View.VISIBLE
                statusLabel.visibility = View.VISIBLE
                statusLabel.text = "EXPORTING"
                try {
                    graph.repository.export(file, target) { bytes ->
                        mainHandler.post { statusLabel.text = "EXPORTING ${formatSize(bytes)}" }
                    }
                    navigator.toast("Exported a decrypted copy")
                } catch (e: Exception) {
                    navigator.toast("Export failed")
                }
                progressBar.visibility = View.GONE
                statusLabel.visibility = View.GONE
            }
        }
    }

    private fun confirmDelete(file: VaultFile) {
        AlertDialog.Builder(activity)
            .setTitle("Delete?")
            .setMessage("\"${file.name}\" is removed from the vault and wiped from storage. This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                scope.launch {
                    graph.repository.delete(file)
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMoveDialog(file: VaultFile) {
        val options = arrayOf("No folder") + folders.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("Move to folder")
            .setItems(options) { _, which ->
                val target = if (which == 0) null else folders[which - 1]
                scope.launch {
                    graph.repository.moveToFolder(file, target)
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNewFolderDialog() {
        val input = android.widget.EditText(activity).apply {
            hint = "Folder name"
            setTextColor(Palette.WHITE)
            setHintTextColor(Palette.GRAY_DIM)
            setSingleLine(true)
        }
        val container = LinearLayout(activity).apply {
            setPadding(activity.dp(Spacing.LG), activity.dp(Spacing.MD), activity.dp(Spacing.LG), 0)
            addView(input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        AlertDialog.Builder(activity)
            .setTitle("New folder")
            .setView(container)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    scope.launch {
                        graph.repository.createFolder(name)
                        refresh()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // -----------------------------------------------------------------------
    // Adapter
    // -----------------------------------------------------------------------

    private inner class FileAdapter : BaseAdapter() {
        override fun getCount(): Int = files.size
        override fun getItem(position: Int): VaultFile? = files.getOrNull(position)
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val file = files[position]
            val row = convertView as? LinearLayout ?: buildRow(file).also { holder ->
                holder.tag = holder
            }
            bindRow(row, file)
            return row
        }

        private fun buildRow(file: VaultFile): LinearLayout {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    activity.dp(Spacing.MD), activity.dp(Spacing.MD),
                    activity.dp(Spacing.MD), activity.dp(Spacing.MD)
                )
            }
            row.addView(FileIconView(activity), LinearLayout.LayoutParams(
                activity.dp(34), activity.dp(34)
            ))
            row.addView(activity.hspace(Spacing.MD))

            val textColumn = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            val name = activity.body(file.name, Type.BODY, Palette.WHITE, 500)
            name.maxLines = 1
            name.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            textColumn.addView(name, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            textColumn.addView(activity.spacer(Spacing.XS))
            val meta = activity.label("", Type.LABEL, Palette.GRAY, 0.1f)
            meta.tag = "meta"
            textColumn.addView(meta, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            row.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val more = activity.label("···", Type.BODY, Palette.GRAY)
            more.setPadding(activity.dp(Spacing.SM), 0, 0, 0)
            more.setOnClickListener {
                val index = files.indexOf(file)
                if (index >= 0) showActions(files[index])
            }
            row.addView(more)
            return row
        }

        private fun bindRow(row: LinearLayout, file: VaultFile) {
            val nameView = (row.getChildAt(2) as LinearLayout).getChildAt(0) as TextView
            val metaView = (row.getChildAt(2) as LinearLayout).getChildAt(2) as TextView
            nameView.text = file.name

            val meta = buildString {
                append(formatSize(file.size))
                if (file.durationMs > 0) {
                    append("  ·  ")
                    append(com.compass.vault.ui.formatDuration(file.durationMs))
                }
                append("  ·  ")
                append(dateFormat.format(java.util.Date(file.createdAt)))
                if (!file.folder.isNullOrEmpty()) {
                    append("  ·  ")
                    append(file.folder)
                }
            }
            metaView.text = meta
            (row.getChildAt(0) as FileIconView).kind = file.kind
        }
    }

    /**
     * A tiny monochrome glyph that tells the three file types apart.
     */
    private class FileIconView(context: android.content.Context) : View(context) {
        var kind: VaultFile.Kind = VaultFile.Kind.DOCUMENT
            set(value) {
                field = value
                invalidate()
            }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.4f * context.resources.displayMetrics.density
            color = Palette.GRAY
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val path = Path()
            when (kind) {
                VaultFile.Kind.IMAGE -> {
                    path.addRect(w * 0.12f, h * 0.18f, w * 0.88f, h * 0.82f, Path.Direction.CW)
                    canvas.drawPath(path, paint)
                    canvas.drawCircle(w * 0.35f, h * 0.38f, w * 0.08f, paint)
                    val mountain = Path().apply {
                        moveTo(w * 0.12f, h * 0.82f)
                        lineTo(w * 0.42f, h * 0.48f)
                        lineTo(w * 0.62f, h * 0.68f)
                        lineTo(w * 0.74f, h * 0.55f)
                        lineTo(w * 0.88f, h * 0.82f)
                    }
                    canvas.drawPath(mountain, paint)
                }

                VaultFile.Kind.VIDEO -> {
                    path.addRect(w * 0.12f, h * 0.22f, w * 0.88f, h * 0.78f, Path.Direction.CW)
                    canvas.drawPath(path, paint)
                    val play = Path().apply {
                        moveTo(w * 0.42f, h * 0.38f)
                        lineTo(w * 0.64f, h * 0.50f)
                        lineTo(w * 0.42f, h * 0.62f)
                        close()
                    }
                    canvas.drawPath(play, paint)
                }

                VaultFile.Kind.DOCUMENT -> {
                    path.moveTo(w * 0.24f, h * 0.12f)
                    path.lineTo(w * 0.60f, h * 0.12f)
                    path.lineTo(w * 0.78f, h * 0.30f)
                    path.lineTo(w * 0.78f, h * 0.88f)
                    path.lineTo(w * 0.24f, h * 0.88f)
                    path.close()
                    canvas.drawPath(path, paint)
                    canvas.drawLine(w * 0.60f, h * 0.12f, w * 0.60f, h * 0.30f, paint)
                    canvas.drawLine(w * 0.60f, h * 0.30f, w * 0.78f, h * 0.30f, paint)
                }
            }
        }
    }
}
