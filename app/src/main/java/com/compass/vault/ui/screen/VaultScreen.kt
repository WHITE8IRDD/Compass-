package com.compass.vault.ui.screen

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import com.compass.vault.AppGraph
import com.compass.vault.MainActivity
import com.compass.vault.data.MediaFile
import com.compass.vault.data.MediaSort
import com.compass.vault.data.VaultFile
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
import com.compass.vault.ui.outlineButton
import com.compass.vault.ui.padForSystemBars
import com.compass.vault.ui.spacer
import com.compass.vault.util.AppDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Authorized media library: shared MediaStore items are separate from the
 * encrypted vault, and a public source is not called hidden until Android
 * confirms removal after a verified encrypted import.
 */
class VaultScreen(
    private val activity: MainActivity,
    private val graph: AppGraph,
    private val navigator: Navigator,
    initialLocked: Boolean = false
) : Screen {

    private enum class Tab { ALL_MEDIA, LOCKED }
    private enum class Category(val title: String) {
        ALL("ALL"), VIDEOS("VIDEOS"), PHOTOS("PHOTOS"), DOCUMENTS("DOCUMENTS"), APK("APK FILES"), OTHER("OTHER")
    }
    private data class Row(val media: MediaFile? = null, val vault: VaultFile? = null)
    private data class RemovalResult(val removed: Boolean, val message: String?)
    private data class RowHolder(
        val preview: ImageView,
        val icon: TextView,
        val name: TextView,
        val details: TextView,
        val security: TextView,
        val action: TextView,
        val more: TextView
    )

    private var scope = CoroutineScope(SupervisorJob() + AppDispatchers.main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    private val listView = ListView(activity)
    private val adapter = LibraryAdapter()
    private val rowHost = FrameLayout(activity)
    private val emptyLabel = activity.body("", Type.BODY, Palette.GRAY)
    private val countLabel = activity.label("", Type.LABEL, Palette.GRAY, 0.12f)
    private val statusLabel = activity.body("", Type.BODY, Palette.GRAY)
    private val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
    private val folderRow = LinearLayout(activity)
    private val folderScroll = HorizontalScrollView(activity)
    private val categoryRow = LinearLayout(activity)
    private val categoryScroll = HorizontalScrollView(activity)
    private val categoryButtons = LinkedHashMap<Category, TextView>()
    private val permissionPanel = LinearLayout(activity)
    private val permissionMessage = activity.body("", Type.BODY, Palette.GRAY)
    private val allowMediaButton = activity.outlineButton("ALLOW MEDIA ACCESS", { requestMediaAccess() })
    private val chooseFolderButton = activity.outlineButton("CHOOSE A FOLDER", { chooseLibraryFolder() }, emphasized = true)
    private val allMediaTab = activity.label("LIBRARY", Type.LABEL, Palette.WHITE, 0.14f)
    private val lockedTab = activity.label("LOCKED FILES", Type.LABEL, Palette.WHITE, 0.14f)
    private val searchBox = EditText(activity)
    private val sortButton = activity.label("SORT · NEWEST", Type.LABEL, Palette.GRAY, 0.12f)
    private val refreshButton = activity.label("REFRESH", Type.LABEL, Palette.GRAY, 0.14f)

    private var tab = if (initialLocked) Tab.LOCKED else Tab.ALL_MEDIA
    private var category = Category.ALL
    private var sort = MediaSort.NEWEST
    private var query = ""
    private var currentFolder: String? = null
    private var publicFiles = mutableListOf<MediaFile>()
    private var treeFiles: List<MediaFile> = emptyList()
    private var treeFolderUri: Uri? = null
    private var treeFolderName: String? = null
    private var treeScanError: String? = null
    private var treeScanTruncated = false
    private var treeScanLoaded = false
    private var vaultFiles: List<VaultFile> = emptyList()
    private var folders: List<String> = emptyList()
    private var rows: List<Row> = emptyList()
    private var nextMediaOffset: Int? = 0
    private var queryGeneration = 0
    private var lockedGeneration = 0
    private var treeGeneration = 0
    private var loadingPage = false
    private var isVisible = false
    private var operationInProgress = false
    private var mediaAccessError = false

    private val searchDebounce = Runnable {
        query = searchBox.text.toString().trim()
        if (tab == Tab.ALL_MEDIA) refreshMedia(reset = true) else renderRows()
    }

    override val view: View = buildLayout()

    private fun buildLayout(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BLACK)
            padForSystemBars()
        }

        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(Spacing.MD), activity.dp(Spacing.SM), activity.dp(Spacing.MD), activity.dp(Spacing.SM))
        }
        val title = activity.body("COMPASS", Type.SCREEN_TITLE, Palette.WHITE, 600).apply {
            contentDescription = "Open compass"
            setOnClickListener { navigator.showCompass() }
        }
        topBar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        countLabel.gravity = Gravity.CENTER_VERTICAL
        topBar.addView(countLabel)
        topBar.addView(activity.hspace(Spacing.SM))
        refreshButton.apply {
            contentDescription = "Refresh local file library"
            setPadding(activity.dp(Spacing.SM), activity.dp(Spacing.SM), activity.dp(Spacing.SM), activity.dp(Spacing.SM))
            setOnClickListener { refreshLibrary() }
        }
        topBar.addView(refreshButton)
        topBar.addView(activity.hspace(Spacing.SM))
        val settings = activity.label("SETTINGS", Type.LABEL, Palette.GRAY, 0.18f).apply {
            contentDescription = "Settings"
            setPadding(activity.dp(Spacing.SM), activity.dp(Spacing.SM), activity.dp(Spacing.SM), activity.dp(Spacing.SM))
            setOnClickListener { navigator.showSettings() }
        }
        topBar.addView(settings)
        root.addView(topBar)
        root.addView(activity.hairline())

        val tabs = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(activity.dp(Spacing.MD), activity.dp(Spacing.SM), activity.dp(Spacing.MD), activity.dp(Spacing.SM))
        }
        configureTab(allMediaTab, Tab.ALL_MEDIA)
        configureTab(lockedTab, Tab.LOCKED)
        tabs.addView(allMediaTab, LinearLayout.LayoutParams(0, activity.dp(44), 1f))
        tabs.addView(activity.hspace(Spacing.SM))
        tabs.addView(lockedTab, LinearLayout.LayoutParams(0, activity.dp(44), 1f))
        root.addView(tabs)
        root.addView(activity.hairline())

        categoryScroll.isHorizontalScrollBarEnabled = false
        categoryRow.orientation = LinearLayout.HORIZONTAL
        categoryRow.gravity = Gravity.CENTER_VERTICAL
        categoryRow.setPadding(activity.dp(Spacing.MD), activity.dp(Spacing.SM), activity.dp(Spacing.MD), activity.dp(Spacing.SM))
        Category.values().forEachIndexed { index, item ->
            val chip = makeChip(item.title, category == item) { selectCategory(item) }
            categoryButtons[item] = chip
            categoryRow.addView(chip)
            if (index < Category.values().lastIndex) categoryRow.addView(activity.hspace(Spacing.SM))
        }
        categoryScroll.addView(categoryRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(categoryScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val searchRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(Spacing.MD), activity.dp(Spacing.SM), activity.dp(Spacing.MD), activity.dp(Spacing.SM))
        }
        searchBox.apply {
            hint = "Search by name"
            setTextColor(Palette.WHITE)
            setHintTextColor(Palette.GRAY_DIM)
            textSize = Type.BODY
            setSingleLine(true)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            background = GradientDrawable().apply {
                setColor(Palette.SURFACE)
                setStroke(activity.dp(1), Palette.HAIRLINE)
                cornerRadius = activity.dp(Spacing.SM).toFloat()
            }
            setPadding(activity.dp(Spacing.MD), activity.dp(Spacing.SM), activity.dp(Spacing.MD), activity.dp(Spacing.SM))
            contentDescription = "Search files"
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    mainHandler.removeCallbacks(searchDebounce)
                    mainHandler.postDelayed(searchDebounce, 280)
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        searchRow.addView(searchBox, LinearLayout.LayoutParams(0, activity.dp(44), 1f))
        searchRow.addView(activity.hspace(Spacing.SM))
        sortButton.apply {
            contentDescription = "Change sort order"
            setPadding(activity.dp(Spacing.SM), activity.dp(Spacing.SM), 0, activity.dp(Spacing.SM))
            setOnClickListener { showSortDialog() }
        }
        searchRow.addView(sortButton)
        root.addView(searchRow)

        folderScroll.isHorizontalScrollBarEnabled = false
        folderScroll.visibility = View.GONE
        folderScroll.setPadding(activity.dp(Spacing.MD), 0, activity.dp(Spacing.MD), 0)
        folderRow.orientation = LinearLayout.HORIZONTAL
        folderRow.gravity = Gravity.CENTER_VERTICAL
        folderScroll.addView(folderRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(folderScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        permissionPanel.orientation = LinearLayout.VERTICAL
        permissionPanel.setPadding(activity.dp(Spacing.MD), activity.dp(Spacing.MD), activity.dp(Spacing.MD), activity.dp(Spacing.MD))
        permissionPanel.setBackgroundColor(Palette.SURFACE)
        permissionPanel.addView(permissionMessage)
        permissionPanel.addView(activity.spacer(Spacing.SM))
        permissionPanel.addView(allowMediaButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        permissionPanel.addView(activity.spacer(Spacing.SM))
        permissionPanel.addView(chooseFolderButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        permissionPanel.visibility = View.GONE
        root.addView(permissionPanel)

        progressBar.max = 100
        progressBar.progress = 0
        progressBar.visibility = View.GONE
        progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Palette.WHITE))
        progressBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Palette.HAIRLINE))
        root.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        statusLabel.visibility = View.GONE
        statusLabel.setPadding(activity.dp(Spacing.MD), activity.dp(Spacing.SM), activity.dp(Spacing.MD), activity.dp(Spacing.SM))
        root.addView(statusLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        listView.adapter = adapter
        listView.divider = null
        listView.dividerHeight = 0
        listView.setBackgroundColor(Palette.BLACK)
        listView.setOnItemClickListener { _, _, position, _ ->
            rows.getOrNull(position)?.let { row ->
                row.media?.let(navigator::showMediaPreview)
                row.vault?.let(::openVaultFile)
            }
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            rows.getOrNull(position)?.let { row ->
                row.media?.let(::showMediaActions)
                row.vault?.let(::showVaultActions)
            }
            true
        }
        listView.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: android.widget.AbsListView?, scrollState: Int) = Unit
            override fun onScroll(view: android.widget.AbsListView?, first: Int, visible: Int, total: Int) {
                if (tab == Tab.ALL_MEDIA && total > 0 && first + visible >= total - 12) loadNextMediaPage()
            }
        })

        emptyLabel.gravity = Gravity.CENTER
        emptyLabel.setPadding(activity.dp(Spacing.XL), activity.dp(Spacing.MD), activity.dp(Spacing.XL), activity.dp(Spacing.MD))
        emptyLabel.visibility = View.GONE
        rowHost.addView(listView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        rowHost.addView(emptyLabel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(rowHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bottom = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(activity.dp(Spacing.MD), activity.dp(Spacing.SM), activity.dp(Spacing.MD), activity.dp(Spacing.SM))
        }
        bottom.addView(activity.outlineButton("ADD FILES", { startImport() }, emphasized = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(activity.hspace(Spacing.MD))
        bottom.addView(activity.outlineButton(
            if (graph.lockController.isUnlocked) "LOCK VAULT" else "OPEN VAULT",
            {
                if (graph.lockController.isUnlocked) graph.lockController.lock()
                else navigator.showVault()
            }
        ), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bottom)
        root.addView(activity.hairline())

        updateTabAppearance()
        return root
    }

    private fun configureTab(view: TextView, target: Tab) {
        view.gravity = Gravity.CENTER
        view.contentDescription = if (target == Tab.ALL_MEDIA) "Browse all media" else "Browse locked files"
        view.setOnClickListener {
            if (operationInProgress || tab == target) return@setOnClickListener
            if (target == Tab.LOCKED && !graph.lockController.isUnlocked) {
                navigator.showVault()
                return@setOnClickListener
            }
            if (target == Tab.LOCKED) {
                queryGeneration++
                loadingPage = false
            } else {
                lockedGeneration++
            }
            progressBar.visibility = View.GONE
            progressBar.isIndeterminate = false
            tab = target
            updateTabAppearance()
            refreshCurrentTab()
        }
    }

    private fun updateTabAppearance() {
        val allSelected = tab == Tab.ALL_MEDIA
        styleTab(allMediaTab, allSelected)
        styleTab(lockedTab, !allSelected)
        categoryScroll.visibility = if (allSelected) View.VISIBLE else View.GONE
        folderScroll.visibility = if (tab == Tab.LOCKED) View.VISIBLE else View.GONE
        sortButton.text = "SORT · ${sort.name.replace('_', ' ')}"
        searchBox.hint = if (tab == Tab.ALL_MEDIA) "Search ${category.title.lowercase(Locale.ROOT)} files" else "Search locked files"
        countLabel.text = if (tab == Tab.ALL_MEDIA) {
            val count = visiblePublicFiles().size
            if (count == 0) "" else "$count ITEM${if (count == 1) "" else "S"}"
        } else {
            val count = filteredVaultFiles().size
            if (count == 0) "" else "$count LOCKED"
        }
        updatePermissionPanel()
        renderRows()
    }

    private fun styleTab(view: TextView, selected: Boolean) {
        view.setTextColor(if (selected) Palette.BLACK else Palette.WHITE)
        view.background = GradientDrawable().apply {
            cornerRadius = activity.dp(Spacing.SM).toFloat()
            setColor(if (selected) Palette.WHITE else Palette.BLACK)
            setStroke(activity.dp(1), if (selected) Palette.WHITE else Palette.HAIRLINE)
        }
    }

    override fun onShow() {
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + AppDispatchers.main)
        isVisible = true
        if (tab == Tab.LOCKED && !graph.lockController.isUnlocked) {
            navigator.showVault()
            return
        }
        refreshCurrentTab()
    }

    override fun onHide() {
        isVisible = false
        queryGeneration++
        lockedGeneration++
        treeGeneration++
        treeScanLoaded = false
        loadingPage = false
        mainHandler.removeCallbacksAndMessages(null)
        scope.cancel()
    }

    override fun onBack(): Boolean {
        navigator.showLibrary()
        return true
    }

    private fun refreshCurrentTab() {
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + AppDispatchers.main)
        if (tab == Tab.ALL_MEDIA) {
            refreshTree(force = !treeScanLoaded)
            refreshMedia(reset = true)
        } else refreshLocked()
    }

    // -----------------------------------------------------------------------
    // Shared media plus a persisted, user-approved SAF folder.
    // -----------------------------------------------------------------------

    private fun refreshLibrary() {
        if (operationInProgress) return
        treeScanLoaded = false
        mediaAccessError = false
        if (tab == Tab.ALL_MEDIA) {
            refreshTree(force = true)
            refreshMedia(reset = true)
        } else {
            refreshLocked()
        }
    }

    private fun refreshTree(force: Boolean) {
        if (tab != Tab.ALL_MEDIA || (!force && treeScanLoaded)) return
        val token = ++treeGeneration
        treeScanLoaded = true
        treeScanError = null
        if (treeFiles.isEmpty()) {
            progressBar.visibility = View.VISIBLE
            progressBar.isIndeterminate = true
        }
        scope.launch {
            try {
                val scan = graph.documentLibrary.scanSelectedFolder()
                if (!isVisible || token != treeGeneration) return@launch
                treeFiles = scan.files
                treeFolderUri = scan.treeUri
                treeFolderName = scan.rootName
                treeScanTruncated = scan.truncated
                treeScanError = null
                updatePermissionPanel()
                renderRows()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!isVisible || token != treeGeneration) return@launch
                treeFiles = emptyList()
                treeScanError = error.message ?: "The selected folder is unavailable"
                updatePermissionPanel()
                renderRows()
            } finally {
                if (isVisible && token == treeGeneration && !loadingPage && !operationInProgress) {
                    progressBar.visibility = View.GONE
                    progressBar.isIndeterminate = false
                }
            }
        }
    }

    private fun refreshMedia(reset: Boolean) {
        if (tab != Tab.ALL_MEDIA) return
        if (reset) {
            queryGeneration++
            publicFiles.clear()
            nextMediaOffset = 0
            loadingPage = false
            mediaAccessError = false
        }
        if (!categoryUsesMediaStore()) {
            publicFiles.clear()
            nextMediaOffset = null
            loadingPage = false
            updatePermissionPanel()
            renderRows()
            return
        }
        if (!navigator.hasMediaAccess()) {
            publicFiles.clear()
            nextMediaOffset = null
            updatePermissionPanel()
            renderRows()
            return
        }
        updatePermissionPanel()
        loadNextMediaPage()
    }

    private fun loadNextMediaPage() {
        if (tab != Tab.ALL_MEDIA || loadingPage || nextMediaOffset == null || !navigator.hasMediaAccess()) return
        val offset = nextMediaOffset ?: return
        val token = queryGeneration
        loadingPage = true
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        scope.launch {
            try {
                val page = graph.mediaLibrary.page(
                    offset = offset,
                    query = query,
                    sort = sort,
                    partialAccess = navigator.hasPartialMediaAccess(),
                    kind = mediaStoreKind()
                )
                if (!isVisible || token != queryGeneration) return@launch
                publicFiles.addAll(page.files)
                nextMediaOffset = if (page.files.isEmpty()) null else page.nextOffset
                loadingPage = false
                progressBar.visibility = View.GONE
                progressBar.isIndeterminate = false
                updatePermissionPanel()
                renderRows()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SecurityException) {
                if (!isVisible || token != queryGeneration) return@launch
                loadingPage = false
                progressBar.visibility = View.GONE
                mediaAccessError = true
                publicFiles.clear()
                updatePermissionPanel()
                renderRows()
            } catch (_: Exception) {
                if (!isVisible || token != queryGeneration) return@launch
                loadingPage = false
                progressBar.visibility = View.GONE
                mediaAccessError = true
                updatePermissionPanel()
                renderRows()
            }
        }
    }

    private fun requestMediaAccess() {
        navigator.requestMediaAccess { granted ->
            val accessible = granted || navigator.hasMediaAccess()
            mediaAccessError = !accessible
            if (accessible) refreshMedia(reset = true)
            else {
                updatePermissionPanel()
                navigator.toast("Media access was not granted. You can still browse a folder or add individual files.")
            }
        }
    }

    private fun chooseLibraryFolder() {
        if (operationInProgress) return
        navigator.pickLibraryFolder { uri ->
            if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + AppDispatchers.main)
            scope.launch {
                try {
                    graph.documentLibrary.saveSelectedFolder(uri)
                    treeScanLoaded = false
                    treeFiles = emptyList()
                    treeScanError = null
                    treeScanTruncated = false
                    refreshTree(force = true)
                    navigator.toast("Folder access saved on this device")
                } catch (error: Exception) {
                    navigator.toast("Could not save folder access. No source files were changed.")
                }
            }
        }
    }

    private fun selectCategory(target: Category) {
        if (operationInProgress || category == target) return
        category = target
        rebuildCategoryChips()
        updateTabAppearance()
        refreshTree(force = !treeScanLoaded)
        refreshMedia(reset = true)
    }

    private fun rebuildCategoryChips() {
        categoryRow.removeAllViews()
        categoryButtons.clear()
        Category.values().forEachIndexed { index, item ->
            val chip = makeChip(item.title, category == item) { selectCategory(item) }
            categoryButtons[item] = chip
            categoryRow.addView(chip)
            if (index < Category.values().lastIndex) categoryRow.addView(activity.hspace(Spacing.SM))
        }
    }

    private fun categoryUsesMediaStore(): Boolean = category in setOf(
        Category.ALL, Category.VIDEOS, Category.PHOTOS, Category.OTHER
    )

    private fun categoryUsesTree(): Boolean = category in setOf(
        Category.ALL, Category.DOCUMENTS, Category.APK, Category.OTHER
    ) || !navigator.hasMediaAccess()

    private fun mediaStoreKind(): VaultFile.Kind? = when (category) {
        Category.VIDEOS -> VaultFile.Kind.VIDEO
        Category.PHOTOS -> VaultFile.Kind.IMAGE
        Category.OTHER -> VaultFile.Kind.AUDIO
        else -> null
    }

    private fun matchesCategory(file: MediaFile): Boolean = when (category) {
        Category.ALL -> true
        Category.VIDEOS -> file.kind == VaultFile.Kind.VIDEO
        Category.PHOTOS -> file.kind == VaultFile.Kind.IMAGE
        Category.DOCUMENTS -> file.kind == VaultFile.Kind.DOCUMENT
        Category.APK -> file.kind == VaultFile.Kind.APK
        Category.OTHER -> file.kind == VaultFile.Kind.OTHER || file.kind == VaultFile.Kind.AUDIO
    }

    private fun visiblePublicFiles(): List<MediaFile> {
        val selectedMedia = publicFiles.filter { matchesCategory(it) }
        val mediaKeys = selectedMedia.mapTo(HashSet()) { it.locationKey() }
        val tree = treeFiles.asSequence()
            .filter(::matchesCategory)
            .filter { query.isBlank() || it.displayName.contains(query, ignoreCase = true) }
            .filter { it.locationKey() !in mediaKeys }
            .toList()
        val combined = (selectedMedia + tree)
            .distinctBy { it.locationKey() }
            .filter { query.isBlank() || it.displayName.contains(query, ignoreCase = true) }
        return when (sort) {
            MediaSort.NEWEST -> combined.sortedByDescending { maxOf(it.modifiedAt, it.dateTakenMs) }
            MediaSort.NAME -> combined.sortedBy { it.displayName.lowercase(Locale.ROOT) }
            MediaSort.LARGEST -> combined.sortedByDescending { it.size }
        }
    }

    private fun updatePermissionPanel() {
        if (tab != Tab.ALL_MEDIA) {
            permissionPanel.visibility = View.GONE
            return
        }
        val mediaRelevant = categoryUsesMediaStore()
        val treeRelevant = categoryUsesTree()
        val needsMediaAccess = mediaRelevant && (!navigator.hasMediaAccess() || navigator.hasPartialMediaAccess() || mediaAccessError)
        val needsFolderAccess = treeRelevant && (treeFolderUri == null || treeScanError != null)
        allowMediaButton.visibility = if (needsMediaAccess) View.VISIBLE else View.GONE
        chooseFolderButton.visibility = if (treeRelevant) View.VISIBLE else View.GONE
        chooseFolderButton.text = if (treeFolderUri == null) "CHOOSE A FOLDER" else "CHANGE FOLDER"
        permissionPanel.visibility = if (needsMediaAccess || needsFolderAccess || treeScanTruncated) View.VISIBLE else View.GONE
        permissionMessage.text = when {
            treeScanError != null -> "${treeFolderName ?: "The selected folder"} could not be read. Android may have revoked or restricted the grant. Choose another permitted folder."
            needsMediaAccess && navigator.hasPartialMediaAccess() -> "Android is showing only the media categories/items you granted. Update access to see more."
            needsMediaAccess -> "Grant optional media access to browse accessible photos, videos, and audio. Denying it does not block SAF folder browsing or individual file selection."
            needsFolderAccess -> "Android restricts general documents and APK discovery. Choose a folder in the system picker; Compass can browse only that folder and its accessible children."
            treeScanTruncated -> "Showing a bounded subset of files from ${treeFolderName ?: "the selected folder"}; this folder contains more than the scan limit."
            else -> ""
        }
    }

    // -----------------------------------------------------------------------
    // Encrypted vault list and folder filter.
    // -----------------------------------------------------------------------

    private fun refreshLocked() {
        val token = ++lockedGeneration
        if (!operationInProgress) {
            progressBar.visibility = View.VISIBLE
            progressBar.isIndeterminate = true
        }
        scope.launch {
            try {
                // Do not infer a successful delete from an empty query here:
                // Android can also hide rows when a media grant is partial or
                // has been revoked. Only an explicit, verified delete result
                // earns the PRIVATE_ONLY status.
                val loadedFiles = graph.repository.list()
                val loadedFolders = graph.repository.folders()
                if (!isVisible || tab != Tab.LOCKED || token != lockedGeneration) return@launch
                vaultFiles = loadedFiles
                folders = loadedFolders
                if (currentFolder != null && folders.none { it == currentFolder }) currentFolder = null
                rebuildFolderChips()
                renderRows()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!isVisible || tab != Tab.LOCKED || token != lockedGeneration) return@launch
                statusLabel.visibility = View.VISIBLE
                statusLabel.text = "The vault library could not be read. Encrypted data was not changed."
                emptyLabel.text = "Vault metadata could not be read. Do not delete or reinstall the app; encrypted files are preserved."
                emptyLabel.visibility = View.VISIBLE
                listView.visibility = View.GONE
            } finally {
                if (isVisible && tab == Tab.LOCKED && token == lockedGeneration && !operationInProgress) {
                    progressBar.visibility = View.GONE
                    progressBar.isIndeterminate = false
                }
            }
        }
    }

    private fun filteredVaultFiles(): List<VaultFile> {
        val matching = vaultFiles.asSequence()
            .filter { currentFolder == null || it.folder == currentFolder }
            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
        return when (sort) {
            MediaSort.NEWEST -> matching.sortedByDescending { it.createdAt }.toList()
            MediaSort.NAME -> matching.sortedBy { it.name.lowercase(Locale.ROOT) }.toList()
            MediaSort.LARGEST -> matching.sortedByDescending { it.size }.toList()
        }
    }

    private fun rebuildFolderChips() {
        folderRow.removeAllViews()
        folderRow.addView(makeChip("ALL", currentFolder == null) {
            currentFolder = null
            renderRows()
        })
        folderRow.addView(activity.hspace(Spacing.SM))
        folders.forEach { name ->
            folderRow.addView(makeChip(name.uppercase(Locale.ROOT), currentFolder == name) {
                currentFolder = name
                renderRows()
            })
            folderRow.addView(activity.hspace(Spacing.SM))
        }
        val add = activity.label("+ NEW", Type.LABEL, Palette.GRAY_DIM, 0.16f).apply {
            setPadding(activity.dp(Spacing.MD), activity.dp(Spacing.SM), activity.dp(Spacing.MD), activity.dp(Spacing.SM))
            contentDescription = "Create a folder"
            setOnClickListener { showNewFolderDialog() }
        }
        folderRow.addView(add)
    }

    private fun makeChip(text: String, selected: Boolean, onClick: () -> Unit): TextView =
        activity.label(text, Type.LABEL, Palette.WHITE, 0.16f).apply {
            setPadding(activity.dp(Spacing.MD), activity.dp(Spacing.SM), activity.dp(Spacing.MD), activity.dp(Spacing.SM))
            setTextColor(if (selected) Palette.BLACK else Palette.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = activity.dp(14).toFloat()
                setColor(if (selected) Palette.WHITE else Palette.BLACK)
                setStroke(activity.dp(1), if (selected) Palette.WHITE else Palette.HAIRLINE)
            }
            setOnClickListener { onClick() }
        }

    // -----------------------------------------------------------------------
    // Search, sort, and row rendering.
    // -----------------------------------------------------------------------

    private fun showSortDialog() {
        val labels = arrayOf("Newest first", "Name A–Z", "Largest first")
        val current = when (sort) {
            MediaSort.NEWEST -> 0
            MediaSort.NAME -> 1
            MediaSort.LARGEST -> 2
        }
        AlertDialog.Builder(activity)
            .setTitle("Sort files")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                sort = when (which) {
                    1 -> MediaSort.NAME
                    2 -> MediaSort.LARGEST
                    else -> MediaSort.NEWEST
                }
                dialog.dismiss()
                updateTabAppearance()
                if (tab == Tab.ALL_MEDIA) refreshMedia(reset = true) else renderRows()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderRows() {
        rows = if (tab == Tab.ALL_MEDIA) visiblePublicFiles().map { Row(media = it) }
        else filteredVaultFiles().map { Row(vault = it) }
        adapter.notifyDataSetChanged()
        updatePermissionPanel()
        listView.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        emptyLabel.visibility = if (rows.isEmpty() && progressBar.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        emptyLabel.text = when {
            tab == Tab.ALL_MEDIA && query.isNotBlank() -> "No accessible files match your search."
            tab == Tab.ALL_MEDIA && category == Category.DOCUMENTS && treeFolderUri == null -> "Choose a folder above to browse supported documents."
            tab == Tab.ALL_MEDIA && category == Category.APK && treeFolderUri == null -> "Choose a folder above to browse APK files."
            tab == Tab.ALL_MEDIA && category == Category.ALL -> "No accessible files found. Grant media access, choose a folder, or add a file."
            tab == Tab.ALL_MEDIA && category == Category.VIDEOS -> "No accessible videos were found."
            tab == Tab.ALL_MEDIA && category == Category.PHOTOS -> "No accessible photos or images were found."
            tab == Tab.ALL_MEDIA && category == Category.DOCUMENTS -> "No supported documents in ${treeFolderName ?: "the selected folder"}."
            tab == Tab.ALL_MEDIA && category == Category.APK -> "No APK files in ${treeFolderName ?: "the selected folder"}."
            tab == Tab.ALL_MEDIA && category == Category.OTHER -> "No audio or other files in the accessible locations."
            query.isNotBlank() -> "No locked files match your search."
            currentFolder != null -> "This folder is empty."
            else -> "No locked files yet.\nBrowse an accessible file and choose Lock."
        }
        countLabel.text = if (rows.isEmpty()) "" else if (tab == Tab.ALL_MEDIA) {
            "${rows.size} ITEM${if (rows.size == 1) "" else "S"}"
        } else {
            "${rows.size} LOCKED"
        }
    }

    private fun typeBadge(kind: VaultFile.Kind): String = when (kind) {
        VaultFile.Kind.IMAGE -> "IMG"
        VaultFile.Kind.VIDEO -> "VID"
        VaultFile.Kind.AUDIO -> "AUD"
        VaultFile.Kind.DOCUMENT -> "DOC"
        VaultFile.Kind.APK -> "APK"
        VaultFile.Kind.OTHER -> "FILE"
    }

    private inner class LibraryAdapter : BaseAdapter() {
        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Row = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = rows[position]
            val root: LinearLayout
            val holder: RowHolder
            if (convertView == null) {
                root = buildRow()
                holder = root.tag as RowHolder
            } else {
                root = convertView as LinearLayout
                holder = root.tag as RowHolder
            }
            bindRow(root, holder, row)
            return root
        }

        private fun buildRow(): LinearLayout {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(activity.dp(Spacing.MD), activity.dp(Spacing.SM), activity.dp(Spacing.SM), activity.dp(Spacing.SM))
                minimumHeight = activity.dp(76)
                setBackgroundColor(Palette.BLACK)
            }
            val thumbnail = FrameLayout(activity).apply { setBackgroundColor(Palette.SURFACE) }
            val preview = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Palette.SURFACE)
                contentDescription = "File thumbnail"
            }
            val icon = activity.label("FILE", Type.LABEL, Palette.GRAY, 0.12f).apply {
                gravity = Gravity.CENTER
                setBackgroundColor(Palette.SURFACE)
                contentDescription = "File type"
            }
            thumbnail.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            thumbnail.addView(icon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            row.addView(thumbnail, LinearLayout.LayoutParams(activity.dp(58), activity.dp(58)))
            row.addView(activity.hspace(Spacing.MD))
            val textColumn = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val name = activity.body("", Type.BODY, Palette.WHITE, 500).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            val details = activity.label("", Type.LABEL, Palette.GRAY, 0.08f).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val security = activity.label("", Type.LABEL, Palette.GRAY_DIM, 0.08f).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            textColumn.addView(name)
            textColumn.addView(activity.spacer(Spacing.XS))
            textColumn.addView(details)
            textColumn.addView(security)
            row.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val action = activity.label("", Type.LABEL, Palette.WHITE, 0.1f).apply {
                gravity = Gravity.CENTER
                minimumWidth = activity.dp(54)
                minimumHeight = activity.dp(48)
                setPadding(activity.dp(Spacing.XS), activity.dp(Spacing.SM), activity.dp(Spacing.XS), activity.dp(Spacing.SM))
            }
            row.addView(action)
            val more = activity.label("···", Type.BODY, Palette.GRAY, 0.1f).apply {
                gravity = Gravity.CENTER
                contentDescription = "More file actions"
                minimumWidth = activity.dp(44)
                minimumHeight = activity.dp(48)
            }
            row.addView(more)
            val holder = RowHolder(preview, icon, name, details, security, action, more)
            row.tag = holder
            return row
        }

        private fun bindRow(root: LinearLayout, holder: RowHolder, row: Row) {
            val key: String
            when {
                row.media != null -> {
                    val media = row.media
                    key = "media:${media.uri}"
                    holder.name.text = media.displayName
                    holder.icon.text = typeBadge(media.kind)
                    holder.details.text = buildString {
                        append(media.mimeType.substringAfter('/').takeIf { it.isNotBlank() && it != "octet-stream" }
                            ?: media.displayName.substringAfterLast('.', "file").uppercase(Locale.ROOT))
                        if (media.size > 0) append(" · ${formatSize(media.size)}")
                        if (media.durationMs > 0) append(" · ${formatDuration(media.durationMs)}")
                        val date = maxOf(media.dateTakenMs, media.modifiedAt)
                        if (date > 0) append(" · ${dateFormat.format(Date(date))}")
                    }
                    holder.security.text = "AVAILABLE TO LOCK"
                    holder.security.setTextColor(Palette.GRAY_DIM)
                    holder.action.text = "LOCK"
                    holder.action.contentDescription = "Lock ${media.displayName}"
                    holder.action.setOnClickListener { lockMedia(media) }
                    holder.more.setOnClickListener { showMediaActions(media) }
                    root.setOnClickListener {
                        if (media.kind in setOf(VaultFile.Kind.IMAGE, VaultFile.Kind.VIDEO, VaultFile.Kind.AUDIO)) {
                            navigator.showMediaPreview(media)
                        } else {
                            showMediaActions(media)
                        }
                    }
                    root.setOnLongClickListener { showMediaActions(media); true }
                }
                row.vault != null -> {
                    val file = row.vault
                    key = "vault:${file.id}"
                    holder.name.text = file.name
                    holder.icon.text = typeBadge(file.kind)
                    holder.details.text = buildString {
                        append(file.mimeType.substringAfter('/').takeIf { it.isNotBlank() && it != "octet-stream" }
                            ?: file.extension.uppercase(Locale.ROOT).ifBlank { file.kind.name })
                        append(" · ${formatSize(file.size)}")
                        if (file.durationMs > 0) append(" · ${formatDuration(file.durationMs)}")
                        append(" · ${dateFormat.format(Date(file.createdAt))}")
                        if (!file.folder.isNullOrBlank()) append(" · ${file.folder}")
                    }
                    when {
                        file.operationState == VaultFile.OperationState.SOURCE_REMOVAL_PENDING -> {
                            holder.security.text = "REMOVAL OUTCOME UNKNOWN · VERIFY SOURCE"
                            holder.security.setTextColor(Palette.WHITE)
                        }
                        file.operationState == VaultFile.OperationState.RESTORE_RECOVERY_REQUIRED -> {
                            holder.security.text = "RESTORE NEEDS REVIEW · VAULT COPY RETAINED"
                            holder.security.setTextColor(Palette.WHITE)
                        }
                        file.sourceState == VaultFile.SourceState.PRIVATE_ONLY -> {
                            holder.security.text = "ENCRYPTED · SOURCE REMOVAL CONFIRMED"
                            holder.security.setTextColor(Palette.GRAY)
                        }
                        file.sourceState == VaultFile.SourceState.PUBLIC_COPY_PRESENT -> {
                            holder.security.text = "NOT FULLY HIDDEN · PUBLIC ORIGINAL MAY REMAIN"
                            holder.security.setTextColor(Palette.WHITE)
                        }
                        else -> {
                            holder.security.text = "SOURCE STATUS UNKNOWN · VERIFY BEFORE TRUSTING"
                            holder.security.setTextColor(Palette.WHITE)
                        }
                    }
                    holder.action.text = "UNLOCK"
                    holder.action.contentDescription = "Restore or unlock ${file.name}"
                    holder.action.setOnClickListener { restoreOrUnlock(file) }
                    holder.more.setOnClickListener { showVaultActions(file) }
                    root.setOnClickListener { openVaultFile(file) }
                    root.setOnLongClickListener { showVaultActions(file); true }
                }
                else -> return
            }
            holder.preview.tag = key
            holder.preview.setImageDrawable(null)
            holder.icon.visibility = View.VISIBLE
            holder.icon.contentDescription = "${holder.icon.text} file placeholder"
            val requested = row
            scope.launch {
                val bitmap: Bitmap? = when {
                    requested.media != null && requested.media.kind in setOf(VaultFile.Kind.IMAGE, VaultFile.Kind.VIDEO) ->
                        graph.mediaLibrary.thumbnail(requested.media, activity.dp(58))
                    requested.vault != null && (requested.vault.isImage || requested.vault.isVideo) ->
                        graph.repository.thumbnail(requested.vault, activity.dp(58))
                    else -> null
                }
                if (holder.preview.tag == key && bitmap != null) {
                    holder.preview.setImageBitmap(bitmap)
                    holder.icon.visibility = View.GONE
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Locking / removal confirmation.
    // -----------------------------------------------------------------------

    private fun startImport() {
        navigator.pickFiles(arrayOf("image/*", "video/*", "audio/*", "application/*", "text/*")) { uris ->
            importUris(uris)
        }
    }

    private fun importUris(uris: List<Uri>) {
        if (operationInProgress) return
        operationInProgress = true
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        statusLabel.visibility = View.VISIBLE
        statusLabel.text = "PREPARING SECURE IMPORT"
        scope.launch {
            var locked = 0
            var failed = 0
            val alreadyIndexed = try {
                graph.repository.list().associateBy { it.originUri }
            } catch (_: Exception) {
                finishOperation()
                navigator.toast("The vault index could not be verified; no source file was changed.")
                return@launch
            }
            uris.distinct().forEachIndexed { index, uri ->
                try {
                    val existing = alreadyIndexed[uri.toString()]
                    if (existing != null) {
                        if (existing.sourceState == VaultFile.SourceState.PUBLIC_COPY_PRESENT) {
                            statusLabel.text = "VERIFYING SOURCE BEFORE REMOVAL"
                            val pending = graph.repository.markSourceRemovalPending(existing)
                            val removal = requestRemoval(uri)
                            graph.repository.markSourceRemoved(pending, removal.removed)
                            if (removal.removed) locked++ else failed++
                        }
                        return@forEachIndexed
                    }
                    statusLabel.text = "ENCRYPTING FILE ${index + 1} OF ${uris.size}"
                    val imported = graph.repository.import(uri, currentFolder, onProgress = { bytes ->
                        if (bytes % (512 * 1024) < 64 * 1024) {
                            mainHandler.post { if (isVisible) statusLabel.text = "ENCRYPTING ${formatSize(bytes)}" }
                        }
                    })
                    statusLabel.text = "VERIFYING SOURCE BEFORE REMOVAL"
                    val pending = graph.repository.markSourceRemovalPending(imported)
                    statusLabel.text = "COPY VERIFIED · WAITING FOR ANDROID DELETE CONSENT"
                    val result = requestRemoval(uri)
                    graph.repository.markSourceRemoved(pending, result.removed)
                    if (result.removed) locked++ else failed++
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failed++
                    navigator.toast("Protection could not be completed. Keep the encrypted copy and verify the public source before taking further action. ${error.message ?: ""}")
                }
            }
            operationInProgress = false
            progressBar.visibility = View.GONE
            progressBar.isIndeterminate = false
            statusLabel.visibility = View.GONE
            if (graph.lockController.isUnlocked) {
                tab = Tab.LOCKED
                updateTabAppearance()
                refreshLocked()
            } else {
                tab = Tab.ALL_MEDIA
                updateTabAppearance()
                treeScanLoaded = false
                refreshTree(force = true)
                refreshMedia(reset = true)
            }
            when {
                failed == 0 && locked > 0 -> navigator.toast("Protected copy verified and Android confirmed source removal")
                locked > 0 -> navigator.toast("Some files are protected, but one or more public originals remain accessible")
                failed > 0 -> navigator.toast("No file is reported as fully hidden unless source removal was confirmed")
            }
        }
    }

    private fun lockMedia(media: MediaFile) {
        if (operationInProgress) return
        operationInProgress = true
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        statusLabel.visibility = View.VISIBLE
        statusLabel.text = "ENCRYPTING & VERIFYING ${media.displayName}"
        scope.launch {
            try {
                val existing = graph.repository.list().firstOrNull { it.originUri == media.uri.toString() }
                if (existing != null) {
                    operationInProgress = false
                    progressBar.visibility = View.GONE
                    statusLabel.visibility = View.GONE
                    if (existing.sourceState == VaultFile.SourceState.PUBLIC_COPY_PRESENT) retrySourceRemoval(existing)
                    else navigator.toast("This item is already protected in Compass")
                    return@launch
                }
                val imported = graph.repository.import(media.uri, currentFolder, origin = media) { bytes ->
                    if (bytes % (512 * 1024) < 64 * 1024) {
                        mainHandler.post { if (isVisible) statusLabel.text = "ENCRYPTING ${formatSize(bytes)}" }
                    }
                }
                statusLabel.text = "VERIFYING SOURCE BEFORE REMOVAL"
                val pending = graph.repository.markSourceRemovalPending(imported)
                statusLabel.text = "COPY VERIFIED · WAITING FOR ANDROID DELETE CONSENT"
                val result = requestRemoval(media.uri)
                graph.repository.markSourceRemoved(pending, result.removed)
                operationInProgress = false
                progressBar.visibility = View.GONE
                progressBar.isIndeterminate = false
                statusLabel.visibility = View.GONE
                if (graph.lockController.isUnlocked) {
                    tab = Tab.LOCKED
                    updateTabAppearance()
                    refreshLocked()
                } else {
                    tab = Tab.ALL_MEDIA
                    updateTabAppearance()
                    treeScanLoaded = false
                    refreshTree(force = true)
                    refreshMedia(reset = true)
                }
                if (result.removed) {
                    navigator.toast("Protected copy verified; Android confirmed source removal")
                } else {
                    navigator.toast("NOT FULLY HIDDEN: the public original remains accessible. Retry from Locked Files after opening the vault.")
                }
            } catch (cancelled: CancellationException) {
                operationInProgress = false
                throw cancelled
            } catch (error: Exception) {
                operationInProgress = false
                progressBar.visibility = View.GONE
                progressBar.isIndeterminate = false
                statusLabel.visibility = View.GONE
                navigator.toast("Protection could not be completed. Keep the encrypted copy and verify the public source before taking further action. ${error.message ?: ""}")
            }
        }
    }

    private suspend fun requestRemoval(uri: Uri): RemovalResult = suspendCancellableCoroutine { continuation ->
        navigator.requestDeleteOriginal(uri) { removed, message ->
            if (continuation.isActive) continuation.resume(RemovalResult(removed, message))
        }
    }

    private fun retrySourceRemoval(file: VaultFile) {
        val source = file.originUri?.let(Uri::parse)
        if (source == null) {
            navigator.toast("Original location is unknown. Delete the public copy manually, then verify it before relying on this vault.")
            return
        }
        if (operationInProgress) return
        operationInProgress = true
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        statusLabel.visibility = View.VISIBLE
        statusLabel.text = "VERIFYING SOURCE BEFORE REMOVAL"
        scope.launch {
            try {
                val pending = graph.repository.markSourceRemovalPending(file)
                val result = requestRemoval(source)
                graph.repository.markSourceRemoved(pending, result.removed)
                operationInProgress = false
                progressBar.visibility = View.GONE
                statusLabel.visibility = View.GONE
                refreshLocked()
                if (result.removed) navigator.toast("Android confirmed removal of the public source")
                else navigator.toast("NOT FULLY HIDDEN: the public original remains accessible. ${result.message ?: "Retry after granting access."}")
            } catch (cancelled: CancellationException) {
                operationInProgress = false
                throw cancelled
            } catch (error: Exception) {
                operationInProgress = false
                progressBar.visibility = View.GONE
                statusLabel.visibility = View.GONE
                navigator.toast("Source-removal outcome is uncertain. The encrypted copy is preserved; check the status in Locked Files.")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Unlock / restore and other file actions.
    // -----------------------------------------------------------------------

    private fun restoreOrUnlock(file: VaultFile) {
        if (operationInProgress) return
        operationInProgress = true
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        statusLabel.visibility = View.VISIBLE
        statusLabel.text = "RESTORING & VERIFYING ${file.name}"
        scope.launch {
            try {
                val restored = graph.repository.restoreToOriginalMedia(file) { bytes ->
                    if (bytes % (512 * 1024) < 64 * 1024) {
                        mainHandler.post { if (isVisible) statusLabel.text = "RESTORING ${formatSize(bytes)}" }
                    }
                }
                if (restored != null) {
                    finishOperation()
                    tab = Tab.ALL_MEDIA
                    updateTabAppearance()
                    refreshMedia(reset = true)
                    navigator.toast("Restored and verified ${file.name}")
                } else {
                    finishOperation()
                    navigator.pickRestoreTarget(file.name, file.mimeType) { target -> restoreToDestination(file, target) }
                }
            } catch (cancelled: CancellationException) {
                operationInProgress = false
                throw cancelled
            } catch (error: Exception) {
                finishOperation()
                navigator.toast("Restore failed; the encrypted original was retained. ${error.message ?: "Try again."}")
            }
        }
    }

    private fun restoreToDestination(file: VaultFile, target: Uri) {
        if (operationInProgress) return
        operationInProgress = true
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        statusLabel.visibility = View.VISIBLE
        statusLabel.text = "WRITING & VERIFYING RESTORE"
        scope.launch {
            try {
                graph.repository.restoreToUri(file, target) { bytes ->
                    if (bytes % (512 * 1024) < 64 * 1024) {
                        mainHandler.post { if (isVisible) statusLabel.text = "RESTORING ${formatSize(bytes)}" }
                    }
                }
                finishOperation()
                refreshLocked()
                navigator.toast("Restored and verified ${file.name}; the encrypted copy was then removed")
            } catch (cancelled: CancellationException) {
                operationInProgress = false
                throw cancelled
            } catch (error: Exception) {
                finishOperation()
                navigator.toast("Restore failed; the encrypted original was retained. Check for a partial destination file. ${error.message ?: "Try again."}")
            }
        }
    }

    private fun finishOperation() {
        operationInProgress = false
        progressBar.visibility = View.GONE
        progressBar.isIndeterminate = false
        statusLabel.visibility = View.GONE
    }

    private fun openVaultFile(file: VaultFile) {
        if (file.isImage || file.isVideo || file.isAudio) navigator.showViewer(file)
        else showVaultActions(file)
    }

    private fun showMediaActions(file: MediaFile) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (file.kind in setOf(VaultFile.Kind.IMAGE, VaultFile.Kind.VIDEO, VaultFile.Kind.AUDIO)) {
            actions += "Preview / play" to { navigator.showMediaPreview(file) }
        } else if (file.kind != VaultFile.Kind.APK) {
            actions += "Open with an installed app" to { navigator.openPublicFile(file.uri, file.mimeType) }
        }
        actions += "Lock this file" to { lockMedia(file) }
        actions += "File information" to { showMediaDetails(file) }
        AlertDialog.Builder(activity)
            .setTitle(file.displayName)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions.getOrNull(which)?.second?.invoke() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showVaultActions(file: VaultFile) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (file.isImage || file.isVideo || file.isAudio) actions += "Open / play" to { openVaultFile(file) }
        actions += "Restore / unlock" to { restoreOrUnlock(file) }
        if (file.sourceState == VaultFile.SourceState.PUBLIC_COPY_PRESENT) {
            actions += "Retry removal of public original" to { retrySourceRemoval(file) }
        }
        actions += "Export a plaintext copy" to { confirmExport(file) }
        actions += "Move to folder" to { showMoveDialog(file) }
        actions += "File information" to { showVaultDetails(file) }
        actions += "Delete encrypted copy" to { confirmDelete(file) }
        AlertDialog.Builder(activity)
            .setTitle(file.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions.getOrNull(which)?.second?.invoke() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMediaDetails(file: MediaFile) {
        val duration = if (file.durationMs > 0) "\nDuration: ${formatDuration(file.durationMs)}" else ""
        AlertDialog.Builder(activity)
            .setTitle("File information")
            .setMessage(
                "Name: ${file.displayName}\nType: ${file.mimeType}\nSize: ${formatSize(file.size)}" + duration +
                    "\nLocation: ${file.relativePath ?: "MediaStore"}"
            )
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showVaultDetails(file: VaultFile) {
        val sourceStatus = when (file.sourceState) {
            VaultFile.SourceState.PRIVATE_ONLY -> "Removal confirmed"
            VaultFile.SourceState.PUBLIC_COPY_PRESENT -> "Public original may still be accessible"
            VaultFile.SourceState.UNKNOWN -> "Not verified (legacy or unavailable source)"
        }
        val duration = if (file.durationMs > 0) "\nDuration: ${formatDuration(file.durationMs)}" else ""
        AlertDialog.Builder(activity)
            .setTitle("File information")
            .setMessage(
                "Name: ${file.name}\nType: ${file.mimeType}\nSize: ${formatSize(file.size)}" + duration +
                    "\nProtected storage: app-private, encrypted\nPublic source: $sourceStatus"
            )
            .setPositiveButton("Close", null)
            .show()
    }

    private fun confirmExport(file: VaultFile) {
        AlertDialog.Builder(activity)
            .setTitle("Export an unencrypted copy?")
            .setMessage(
                "This creates a plaintext copy of \"${file.name}\" at a location you choose. " +
                    "Other apps may then access it. The encrypted vault copy stays in place."
            )
            .setPositiveButton("Continue") { _, _ -> export(file) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun export(file: VaultFile) {
        navigator.pickExportTarget(file.name) { target ->
            if (operationInProgress) return@pickExportTarget
            operationInProgress = true
            progressBar.visibility = View.VISIBLE
            progressBar.isIndeterminate = true
            statusLabel.visibility = View.VISIBLE
            statusLabel.text = "EXPORTING & VERIFYING"
            scope.launch {
                try {
                    graph.repository.export(file, target) { bytes ->
                        if (bytes % (512 * 1024) < 64 * 1024) {
                            mainHandler.post { if (isVisible) statusLabel.text = "EXPORTING ${formatSize(bytes)}" }
                        }
                    }
                    finishOperation()
                    navigator.toast("Exported and verified a plaintext copy")
                } catch (cancelled: CancellationException) {
                    operationInProgress = false
                    throw cancelled
                } catch (_: Exception) {
                    finishOperation()
                    navigator.toast("Export failed; the encrypted vault copy was retained")
                }
            }
        }
    }

    private fun confirmDelete(file: VaultFile) {
        AlertDialog.Builder(activity)
            .setTitle("Delete encrypted copy?")
            .setMessage("\"${file.name}\" is removed from the vault. This cannot be undone. The public source, if any, is not changed.")
            .setPositiveButton("Delete") { _, _ ->
                scope.launch {
                    try {
                        graph.repository.delete(file)
                        refreshLocked()
                        navigator.toast("Encrypted copy deleted")
                    } catch (_: Exception) {
                        navigator.toast("Could not delete the encrypted copy")
                    }
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
                    try {
                        graph.repository.moveToFolder(file, target)
                        refreshLocked()
                    } catch (_: Exception) {
                        navigator.toast("Could not update the folder")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNewFolderDialog() {
        val input = EditText(activity).apply {
            hint = "Folder name"
            setTextColor(Palette.WHITE)
            setHintTextColor(Palette.GRAY_DIM)
            setSingleLine(true)
        }
        val container = LinearLayout(activity).apply {
            setPadding(activity.dp(Spacing.LG), activity.dp(Spacing.MD), activity.dp(Spacing.LG), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(activity)
            .setTitle("New folder")
            .setView(container)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) scope.launch {
                    try {
                        graph.repository.createFolder(name)
                        folders = graph.repository.folders()
                        rebuildFolderChips()
                    } catch (_: Exception) {
                        navigator.toast("Could not create the folder")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
