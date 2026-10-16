package com.compass.vault.ui.screen

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
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
import com.compass.vault.data.LibraryLogic
import com.compass.vault.data.MediaFile
import com.compass.vault.data.MediaSort
import com.compass.vault.data.VaultFile
import com.compass.vault.data.VaultRepository
import com.compass.vault.ui.FileLocker
import com.compass.vault.ui.Navigator
import com.compass.vault.ui.Palette
import com.compass.vault.ui.Screen
import com.compass.vault.ui.Spacing
import com.compass.vault.ui.Type
import com.compass.vault.ui.body
import com.compass.vault.ui.dp
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * The home screen of Compass: a real, visual browser for the files Android
 * lets the app reach, plus the authenticated Locked Files view.
 *
 * Structure
 *  * **LIBRARY** shows the sections Videos, Photos, Documents, APK files and
 *    Other files with previews. Tapping a category chip (or VIEW ALL) opens
 *    the full, paged list of that category.
 *  * **LOCKED FILES** is the vault. It is only reachable after PIN/biometric
 *    authentication and never renders encrypted content before that.
 *
 * Data comes from the MediaStore collections (photos, videos, audio) and from
 * the Storage Access Framework folders the owner approved. Android does not
 * allow an ordinary app to crawl arbitrary shared storage, so the screen says
 * exactly which locations are missing instead of pretending to be complete.
 */
class LibraryScreen(
    private val activity: MainActivity,
    private val graph: AppGraph,
    private val navigator: Navigator,
    initialLocked: Boolean = false
) : Screen {

    private enum class Tab { LIBRARY, LOCKED }

    private enum class Category(val title: String, val kind: VaultFile.Kind?) {
        ALL("ALL", null),
        VIDEOS("VIDEOS", VaultFile.Kind.VIDEO),
        PHOTOS("PHOTOS", VaultFile.Kind.IMAGE),
        DOCUMENTS("DOCUMENTS", VaultFile.Kind.DOCUMENT),
        APK("APK FILES", VaultFile.Kind.APK),
        OTHER("OTHER", VaultFile.Kind.OTHER)
    }

    /** One rendered row. Headers and notices exist so the list reads as sections. */
    private sealed class Item {
        data class SectionHeader(val title: String, val subtitle: String, val onViewAll: (() -> Unit)?) : Item()
        data class MediaItem(val file: MediaFile) : Item()
        data class VaultItem(val file: VaultFile) : Item()
        data class Notice(val title: String, val text: String) : Item()

        /** "VIEW ALL n ›" footer of a home section. */
        data class More(val label: String, val onClick: () -> Unit) : Item()
    }

    private data class RemovalResult(val removed: Boolean, val message: String?)

    private class MediaRow(
        val preview: ImageView,
        val badge: TextView,
        val name: TextView,
        val details: TextView,
        val security: TextView,
        val action: TextView,
        val more: TextView
    )

    private var scope = CoroutineScope(SupervisorJob() + AppDispatchers.main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    private val thumbnailSlots = Semaphore(4)

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
    private val accessPanel = LinearLayout(activity)
    private val accessMessage = activity.body("", Type.BODY, Palette.GRAY)
    private val allowMediaButton = activity.outlineButton("ALLOW MEDIA ACCESS", { requestMediaAccess() })
    private val addFolderButton = activity.outlineButton("ADD A FOLDER", { chooseLibraryFolder() }, emphasized = true)
    private val libraryTab = activity.label("LIBRARY", Type.LABEL, Palette.WHITE, 0.14f)
    private val lockedTab = activity.label("LOCKED FILES", Type.LABEL, Palette.WHITE, 0.14f)
    private val searchBox = EditText(activity)
    private val sortButton = activity.label("SORT · NEWEST", Type.LABEL, Palette.GRAY, 0.12f)
    private val refreshButton = activity.label("REFRESH", Type.LABEL, Palette.GRAY, 0.14f)
    private val selectButton = activity.label("SELECT", Type.LABEL, Palette.GRAY, 0.14f)
    private val primaryButton = activity.outlineButton("ADD FILES", { startImport() }, emphasized = true)
    private val secondaryButton = activity.outlineButton("OPEN VAULT", { openOrLockVault() })

    private var tab = if (initialLocked) Tab.LOCKED else Tab.LIBRARY
    private var category = Category.ALL
    private var sort = MediaSort.NEWEST
    private var query = ""

    // Library state
    private var mediaFiles = mutableListOf<MediaFile>()
    private var sectionPreview = mapOf<VaultFile.Kind, List<MediaFile>>()
    private var nextMediaOffset: Int? = 0
    private var mediaAccessError = false
    private var loadingPage = false

    // Approved SAF folders
    private var treeFiles: List<MediaFile> = emptyList()
    private var treeFolderNames: List<String> = emptyList()
    private var treeFailedFolders: List<String> = emptyList()
    private var treeScanTruncated = false
    private var treeScanLoaded = false

    // Vault state
    private var vaultFiles: List<VaultFile> = emptyList()
    private var folders: List<String> = emptyList()
    private var currentFolder: String? = null

    private var items: List<Item> = emptyList()
    private val selected = LinkedHashSet<String>()
    private var selectMode = false
    private var queryGeneration = 0
    private var lockedGeneration = 0
    private var treeGeneration = 0
    private var isVisible = false
    private var operationInProgress = false

    private val searchDebounce = Runnable {
        query = searchBox.text.toString().trim()
        if (tab == Tab.LIBRARY && category != Category.ALL) refreshMedia(reset = true) else renderItems()
    }

    override val view: View = buildLayout()

    // -----------------------------------------------------------------------
    // Layout
    // -----------------------------------------------------------------------

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
            contentDescription = "Open the compass"
            setOnClickListener { navigator.showCompass() }
        }
        topBar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        countLabel.gravity = Gravity.CENTER_VERTICAL
        topBar.addView(countLabel)
        topBar.addView(activity.hspace(Spacing.SM))
        selectButton.apply {
            contentDescription = "Select several files"
            setPadding(activity.dp(Spacing.SM), activity.dp(Spacing.SM), activity.dp(Spacing.SM), activity.dp(Spacing.SM))
            setOnClickListener { toggleSelectMode() }
        }
        topBar.addView(selectButton)
        refreshButton.apply {
            contentDescription = "Refresh the file library"
            setPadding(activity.dp(Spacing.SM), activity.dp(Spacing.SM), activity.dp(Spacing.SM), activity.dp(Spacing.SM))
            setOnClickListener { refreshAll(forceTree = true) }
        }
        topBar.addView(refreshButton)
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
        configureTab(libraryTab, Tab.LIBRARY)
        configureTab(lockedTab, Tab.LOCKED)
        tabs.addView(libraryTab, LinearLayout.LayoutParams(0, activity.dp(44), 1f))
        tabs.addView(activity.hspace(Spacing.SM))
        tabs.addView(lockedTab, LinearLayout.LayoutParams(0, activity.dp(44), 1f))
        root.addView(tabs)
        root.addView(activity.hairline())

        categoryScroll.isHorizontalScrollBarEnabled = false
        categoryRow.orientation = LinearLayout.HORIZONTAL
        categoryRow.gravity = Gravity.CENTER_VERTICAL
        categoryRow.setPadding(activity.dp(Spacing.MD), activity.dp(Spacing.SM), activity.dp(Spacing.MD), activity.dp(Spacing.SM))
        rebuildCategoryChips()
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
            contentDescription = "Change the sort order"
            setPadding(activity.dp(Spacing.SM), activity.dp(Spacing.SM), 0, activity.dp(Spacing.SM))
            setOnClickListener { showSortDialog() }
        }
        searchRow.addView(sortButton)
        root.addView(searchRow)

        folderScroll.isHorizontalScrollBarEnabled = false
        folderScroll.visibility = View.GONE
        folderRow.orientation = LinearLayout.HORIZONTAL
        folderRow.gravity = Gravity.CENTER_VERTICAL
        folderScroll.addView(folderRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(folderScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        accessPanel.orientation = LinearLayout.VERTICAL
        accessPanel.setPadding(activity.dp(Spacing.MD), activity.dp(Spacing.MD), activity.dp(Spacing.MD), activity.dp(Spacing.MD))
        accessPanel.setBackgroundColor(Palette.SURFACE)
        accessPanel.addView(accessMessage)
        accessPanel.addView(activity.spacer(Spacing.SM))
        accessPanel.addView(allowMediaButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        accessPanel.addView(activity.spacer(Spacing.SM))
        accessPanel.addView(addFolderButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        accessPanel.visibility = View.GONE
        root.addView(accessPanel)

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
        listView.setOnItemClickListener { _, _, position, _ -> onItemTapped(items.getOrNull(position)) }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val item = items.getOrNull(position)
            when (item) {
                is Item.MediaItem -> { showMediaActions(item.file); true }
                is Item.VaultItem -> { showVaultActions(item.file); true }
                else -> false
            }
        }
        listView.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: android.widget.AbsListView?, scrollState: Int) = Unit
            override fun onScroll(view: android.widget.AbsListView?, first: Int, visible: Int, total: Int) {
                if (tab == Tab.LIBRARY && category != Category.ALL && total > 0 && first + visible >= total - 10) {
                    loadNextMediaPage()
                }
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
        bottom.addView(primaryButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(activity.hspace(Spacing.MD))
        bottom.addView(secondaryButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bottom)
        root.addView(activity.hairline())

        updateChrome()
        return root
    }

    private fun configureTab(view: TextView, target: Tab) {
        view.gravity = Gravity.CENTER
        view.contentDescription = if (target == Tab.LIBRARY) "Browse the file library" else "Browse locked files"
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
            selectMode = false
            selected.clear()
            progressBar.visibility = View.GONE
            progressBar.isIndeterminate = false
            tab = target
            updateChrome()
            refreshCurrentTab()
        }
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

    private fun makeChip(text: String, selectedChip: Boolean, onClick: () -> Unit): TextView =
        activity.label(text, Type.LABEL, Palette.WHITE, 0.16f).apply {
            setPadding(activity.dp(Spacing.MD), activity.dp(Spacing.SM), activity.dp(Spacing.MD), activity.dp(Spacing.SM))
            setTextColor(if (selectedChip) Palette.BLACK else Palette.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = activity.dp(14).toFloat()
                setColor(if (selectedChip) Palette.WHITE else Palette.BLACK)
                setStroke(activity.dp(1), if (selectedChip) Palette.WHITE else Palette.HAIRLINE)
            }
            setOnClickListener { onClick() }
        }

    private fun styleTab(view: TextView, selectedTab: Boolean) {
        view.setTextColor(if (selectedTab) Palette.BLACK else Palette.WHITE)
        view.background = GradientDrawable().apply {
            cornerRadius = activity.dp(Spacing.SM).toFloat()
            setColor(if (selectedTab) Palette.WHITE else Palette.BLACK)
            setStroke(activity.dp(1), if (selectedTab) Palette.WHITE else Palette.HAIRLINE)
        }
    }

    /** Everything that depends on the current tab / selection. */
    private fun updateChrome() {
        val librarySelected = tab == Tab.LIBRARY
        styleTab(libraryTab, librarySelected)
        styleTab(lockedTab, !librarySelected)
        categoryScroll.visibility = if (librarySelected) View.VISIBLE else View.GONE
        folderScroll.visibility = if (tab == Tab.LOCKED) View.VISIBLE else View.GONE
        sortButton.text = "SORT · ${sort.name.replace('_', ' ')}"
        searchBox.hint = if (librarySelected) "Search ${category.title.lowercase(Locale.ROOT)} files" else "Search locked files"
        selectButton.visibility = if (tab == Tab.LIBRARY) View.VISIBLE else View.GONE
        selectButton.text = if (selectMode) "CANCEL" else "SELECT"
        primaryButton.visibility = if (tab == Tab.LIBRARY) View.VISIBLE else View.GONE
        secondaryButton.visibility = View.VISIBLE
        secondaryButton.text = when {
            selectMode -> "CANCEL SELECTION"
            graph.lockController.isUnlocked -> "LOCK VAULT"
            else -> "OPEN VAULT"
        }
        updateAccessPanel()
    }

    private fun toggleSelectMode() {
        selectMode = !selectMode
        if (!selectMode) selected.clear()
        updateChrome()
        renderItems()
    }

    // -----------------------------------------------------------------------
    // Screen lifecycle
    // -----------------------------------------------------------------------

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
        if (selectMode) {
            toggleSelectMode()
            return true
        }
        if (tab == Tab.LIBRARY && category != Category.ALL) {
            selectCategory(Category.ALL)
            return true
        }
        navigator.showLibrary()
        return true
    }

    private fun refreshCurrentTab() {
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + AppDispatchers.main)
        if (tab == Tab.LIBRARY) {
            refreshTree(force = !treeScanLoaded)
            refreshMedia(reset = true)
        } else {
            refreshLocked()
        }
    }

    private fun refreshAll(forceTree: Boolean) {
        if (operationInProgress) return
        mediaAccessError = false
        if (tab == Tab.LIBRARY) refreshTree(force = forceTree)
        refreshCurrentTab()
    }

    // -----------------------------------------------------------------------
    // SAF folders (documents, APKs, other files)
    // -----------------------------------------------------------------------

    private fun refreshTree(force: Boolean) {
        if (tab != Tab.LIBRARY || (!force && treeScanLoaded)) return
        val token = ++treeGeneration
        treeScanLoaded = true
        if (treeFiles.isEmpty()) {
            progressBar.visibility = View.VISIBLE
            progressBar.isIndeterminate = true
        }
        scope.launch {
            try {
                val scan = graph.documentLibrary.scan()
                if (!isVisible || token != treeGeneration) return@launch
                treeFiles = scan.files
                treeFolderNames = scan.folderNames
                treeFailedFolders = scan.failedFolders
                treeScanTruncated = scan.truncated
                treeHasFolderGrant = scan.folderNames.isNotEmpty()
                updateAccessPanel()
                renderItems()
                loadSectionPreviews()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!isVisible || token != treeGeneration) return@launch
                treeFiles = emptyList()
                treeFolderNames = emptyList()
                treeFailedFolders = if (treeHasFolderGrant) listOf("approved folder") else emptyList()
                updateAccessPanel()
                renderItems()
            } finally {
                if (isVisible && token == treeGeneration && !loadingPage && !operationInProgress) {
                    progressBar.visibility = View.GONE
                    progressBar.isIndeterminate = false
                }
            }
        }
    }

    private var treeHasFolderGrant = false

    // -----------------------------------------------------------------------
    // MediaStore rows
    // -----------------------------------------------------------------------

    /**
     * Android exposes media through the media permission and - on Android
     * 10-12 - the non-media rows of the media database through the storage
     * permission too. Every category is therefore asked of MediaStore; what
     * comes back is filtered by the real file type, and whatever Android
     * withholds shows up as an empty section with an explanation instead of a
     * wrong claim.
     */
    private fun categoryUsesMediaStore(): Boolean = true

    private fun mediaStoreKind(): VaultFile.Kind? = when (category) {
        Category.VIDEOS -> VaultFile.Kind.VIDEO
        Category.PHOTOS -> VaultFile.Kind.IMAGE
        Category.DOCUMENTS -> VaultFile.Kind.DOCUMENT
        Category.APK -> VaultFile.Kind.APK
        Category.OTHER -> VaultFile.Kind.OTHER
        else -> null
    }

    private fun refreshMedia(reset: Boolean) {
        if (tab != Tab.LIBRARY) return
        if (reset) {
            queryGeneration++
            mediaFiles.clear()
            nextMediaOffset = 0
            loadingPage = false
            mediaAccessError = false
        }
        if (!categoryUsesMediaStore()) {
            mediaFiles.clear()
            nextMediaOffset = null
            loadingPage = false
            updateAccessPanel()
            renderItems()
            return
        }
        if (!navigator.hasMediaAccess()) {
            mediaFiles.clear()
            nextMediaOffset = null
            updateAccessPanel()
            renderItems()
            loadSectionPreviews()
            return
        }
        updateAccessPanel()
        if (category == Category.ALL) loadSectionPreviews() else loadNextMediaPage()
    }

    /** First page of every media section for the grouped home screen. */
    private fun loadSectionPreviews() {
        val token = queryGeneration
        scope.launch {
            val previews = LinkedHashMap<VaultFile.Kind, List<MediaFile>>()
            if (navigator.hasMediaAccess()) {
                for (kind in listOf(VaultFile.Kind.VIDEO, VaultFile.Kind.IMAGE, VaultFile.Kind.OTHER)) {
                    try {
                        val page = graph.mediaLibrary.page(
                            offset = 0,
                            query = query,
                            sort = sort,
                            partialAccess = navigator.hasPartialMediaAccess(),
                            pageSize = PREVIEW_PER_SECTION,
                            kind = kind
                        )
                        if (page.files.isNotEmpty()) previews[kind] = page.files
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // A failed preview must never break the screen.
                    }
                }
            }
            if (!isVisible || token != queryGeneration || category != Category.ALL) return@launch
            sectionPreview = previews
            renderItems()
        }
    }

    private fun loadNextMediaPage() {
        if (tab != Tab.LIBRARY || loadingPage || nextMediaOffset == null || !navigator.hasMediaAccess()) return
        if (!categoryUsesMediaStore()) return
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
                mediaFiles.addAll(page.files)
                nextMediaOffset = if (page.files.isEmpty()) null else page.nextOffset
                loadingPage = false
                progressBar.visibility = View.GONE
                progressBar.isIndeterminate = false
                updateAccessPanel()
                renderItems()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!isVisible || token != queryGeneration) return@launch
                loadingPage = false
                progressBar.visibility = View.GONE
                mediaAccessError = true
                updateAccessPanel()
                renderItems()
            }
        }
    }

    private fun requestMediaAccess() {
        navigator.requestMediaAccess { granted ->
            val accessible = granted || navigator.hasMediaAccess()
            mediaAccessError = !accessible
            if (accessible) {
                refreshMedia(reset = true)
                loadSectionPreviews()
            } else {
                updateAccessPanel()
                navigator.toast("Media access was not granted. You can still add a folder or single files.")
            }
        }
    }

    private fun chooseLibraryFolder() {
        if (operationInProgress) return
        navigator.pickLibraryFolder { uri ->
            if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + AppDispatchers.main)
            scope.launch {
                try {
                    graph.documentLibrary.addFolder(uri)
                    treeScanLoaded = false
                    treeFiles = emptyList()
                    treeHasFolderGrant = true
                    refreshTree(force = true)
                    navigator.toast("Folder added. Compass can browse and lock the files in it.")
                } catch (error: Exception) {
                    navigator.toast("Could not save folder access. No source files were changed.")
                }
            }
        }
    }

    private fun selectCategory(target: Category) {
        if (operationInProgress || category == target) return
        category = target
        selectMode = false
        selected.clear()
        rebuildCategoryChips()
        updateChrome()
        refreshMedia(reset = true)
        renderItems()
    }

    // -----------------------------------------------------------------------
    // Access panel
    // -----------------------------------------------------------------------

    private fun updateAccessPanel() {
        if (tab != Tab.LIBRARY) {
            accessPanel.visibility = View.GONE
            return
        }
        val mediaRelevant = categoryUsesMediaStore()
        val needsMedia = mediaRelevant &&
            (!navigator.hasMediaAccess() || navigator.hasPartialMediaAccess() || mediaAccessError)
        val documentRelevant = category in setOf(Category.ALL, Category.DOCUMENTS, Category.APK, Category.OTHER)
        val needsFolder = documentRelevant && !treeHasFolderGrant
        allowMediaButton.visibility = if (needsMedia) View.VISIBLE else View.GONE
        addFolderButton.visibility = if (documentRelevant) View.VISIBLE else View.GONE
        addFolderButton.text = if (treeHasFolderGrant) "ADD ANOTHER FOLDER" else "ADD A FOLDER"
        accessPanel.visibility = if (needsMedia || needsFolder || treeScanTruncated || treeFailedFolders.isNotEmpty()) {
            View.VISIBLE
        } else {
            View.GONE
        }
        accessMessage.text = when {
            treeFailedFolders.isNotEmpty() ->
                "${treeFailedFolders.joinToString(", ")} could not be read any more. Android may have revoked the folder grant; add the folder again to keep browsing and locking its files."
            needsMedia && navigator.hasPartialMediaAccess() ->
                "Android is showing only the photos/videos/audio you selected. Update access to see every media file."
            needsMedia ->
                "Grant optional media access to browse the phone's photos, videos and audio. Compass works offline and never uploads anything."
            needsFolder ->
                "Documents, APK files and other types live outside the media library. Android only shows them to apps you give a folder to: add the folder that holds them (for example Documents, or a subfolder of Download)."
            treeScanTruncated ->
                "Showing the first ${treeFiles.size} files of the approved folders; there are more than the scan limit."
            else -> ""
        }
    }

    // -----------------------------------------------------------------------
    // Vault (Locked Files)
    // -----------------------------------------------------------------------

    private fun refreshLocked() {
        val token = ++lockedGeneration
        if (!operationInProgress) {
            progressBar.visibility = View.VISIBLE
            progressBar.isIndeterminate = true
        }
        scope.launch {
            try {
                // An empty MediaStore query is not proof that a source was
                // removed (grants can be partial or revoked), so the only
                // accepted evidence is the per-item removal verification that
                // the lock transaction recorded.
                val loadedFiles = graph.repository.list()
                val loadedFolders = graph.repository.folders()
                if (!isVisible || tab != Tab.LOCKED || token != lockedGeneration) return@launch
                vaultFiles = loadedFiles
                folders = loadedFolders
                if (currentFolder != null && folders.none { it == currentFolder }) currentFolder = null
                rebuildFolderChips()
                renderItems()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!isVisible || tab != Tab.LOCKED || token != lockedGeneration) return@launch
                statusLabel.visibility = View.VISIBLE
                statusLabel.text = "The vault library could not be read. Encrypted data was not changed."
                items = listOf(Item.Notice(
                    "VAULT INDEX UNREADABLE",
                    "Compass could not verify its encrypted index. Do not uninstall or clear app data: the encrypted files are still on the device and can be recovered with the same installation."
                ))
                adapter.notifyDataSetChanged()
                emptyLabel.visibility = View.GONE
                listView.visibility = View.VISIBLE
            } finally {
                if (isVisible && tab == Tab.LOCKED && token == lockedGeneration && !operationInProgress) {
                    progressBar.visibility = View.GONE
                    progressBar.isIndeterminate = false
                }
            }
        }
    }

    private fun rebuildFolderChips() {
        folderRow.removeAllViews()
        folderRow.setPadding(activity.dp(Spacing.MD), 0, activity.dp(Spacing.MD), 0)
        folderRow.addView(makeChip("ALL", currentFolder == null) {
            currentFolder = null
            renderItems()
        })
        folderRow.addView(activity.hspace(Spacing.SM))
        folders.forEach { name ->
            folderRow.addView(makeChip(name.uppercase(Locale.ROOT), currentFolder == name) {
                currentFolder = name
                renderItems()
            })
            folderRow.addView(activity.hspace(Spacing.SM))
        }
        val add = activity.label("+ NEW FOLDER", Type.LABEL, Palette.GRAY_DIM, 0.16f).apply {
            setPadding(activity.dp(Spacing.MD), activity.dp(Spacing.SM), activity.dp(Spacing.MD), activity.dp(Spacing.SM))
            contentDescription = "Create a vault folder"
            setOnClickListener { showNewFolderDialog() }
        }
        folderRow.addView(add)
    }

    private fun filteredVaultFiles(): List<VaultFile> {
        val matching = vaultFiles.asSequence()
            .filter { currentFolder == null || it.folder == currentFolder }
            .filter { LibraryLogic.matchesQuery(it.name, query) }
        return sortVault(matching.toList())
    }

    private fun sortVault(files: List<VaultFile>): List<VaultFile> = when (sort) {
        MediaSort.NEWEST -> files.sortedByDescending { it.createdAt }
        MediaSort.NAME -> files.sortedBy { it.name.lowercase(Locale.ROOT) }
        MediaSort.LARGEST -> files.sortedByDescending { it.size }
    }

    private fun sortMedia(files: List<MediaFile>): List<MediaFile> = when (sort) {
        MediaSort.NEWEST -> files.sortedByDescending { maxOf(it.modifiedAt, it.dateTakenMs) }
        MediaSort.NAME -> files.sortedBy { it.displayName.lowercase(Locale.ROOT) }
        MediaSort.LARGEST -> files.sortedByDescending { it.size }
    }

    // -----------------------------------------------------------------------
    // Row model
    // -----------------------------------------------------------------------

    private fun matchesCategory(file: MediaFile): Boolean = when (category) {
        Category.ALL -> true
        Category.VIDEOS -> file.kind == VaultFile.Kind.VIDEO
        Category.PHOTOS -> file.kind == VaultFile.Kind.IMAGE
        Category.DOCUMENTS -> file.kind == VaultFile.Kind.DOCUMENT
        Category.APK -> file.kind == VaultFile.Kind.APK
        Category.OTHER -> file.kind == VaultFile.Kind.OTHER || file.kind == VaultFile.Kind.AUDIO
    }

    private fun treeFilesFor(kind: VaultFile.Kind?): List<MediaFile> {
        val matching = treeFiles.asSequence()
            .filter { file ->
                when (kind) {
                    null -> true
                    VaultFile.Kind.OTHER -> file.kind == VaultFile.Kind.OTHER || file.kind == VaultFile.Kind.AUDIO
                    else -> file.kind == kind
                }
            }
            .filter { LibraryLogic.matchesQuery(it.displayName, query) }
        return sortMedia(matching.toList())
    }

    /** MediaStore rows of the current category, minus the SAF duplicates. */
    private fun visibleMediaFiles(): List<MediaFile> {
        val treeKeys = treeFiles.mapTo(HashSet()) { it.locationKey() }
        return sortMedia(
            mediaFiles.asSequence()
                .filter(::matchesCategory)
                .filter { LibraryLogic.matchesQuery(it.displayName, query) }
                .filter { it.locationKey() !in treeKeys }
                .toList()
        )
    }

    private fun renderItems() {
        items = buildItems()
        adapter.notifyDataSetChanged()
        updateAccessPanel()
        updateCountLabel()
        listView.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        emptyLabel.visibility = if (items.isEmpty() && progressBar.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        emptyLabel.text = emptyMessage()
        primaryButton.text = when {
            selectMode && selected.isNotEmpty() -> "LOCK ${selected.size} SELECTED"
            selectMode -> "LOCK SELECTED"
            else -> "ADD FILES"
        }
    }

    private fun updateCountLabel() {
        val count = if (tab == Tab.LIBRARY) {
            if (category == Category.ALL) sectionPreview.values.sumOf { it.size } + treeFiles.size
            else visibleMediaFiles().size + treeFilesFor(category.kind).size
        } else {
            filteredVaultFiles().size
        }
        countLabel.text = when {
            count == 0 -> ""
            tab == Tab.LOCKED -> "$count LOCKED"
            else -> count.toString()
        }
    }

    private fun buildItems(): List<Item> {
        if (tab == Tab.LOCKED) {
            val files = filteredVaultFiles()
            if (files.isEmpty()) return emptyList()
            return files.map { Item.VaultItem(it) }
        }
        if (category == Category.ALL) return buildHomeSections()

        val result = ArrayList<Item>()
        val media = visibleMediaFiles()
        val tree = treeFilesFor(category.kind)
        media.forEach { result += Item.MediaItem(it) }
        tree.forEach { result += Item.MediaItem(it) }
        if (operationInProgress || selected.isEmpty()) return result
        return result
    }

    /** The home screen: one section per file type with previews. */
    private fun buildHomeSections(): List<Item> {
        val result = ArrayList<Item>()
        val sections = listOf(
            Triple(VaultFile.Kind.VIDEO, "VIDEOS", Category.VIDEOS),
            Triple(VaultFile.Kind.IMAGE, "PHOTOS", Category.PHOTOS),
            Triple(VaultFile.Kind.DOCUMENT, "DOCUMENTS", Category.DOCUMENTS),
            Triple(VaultFile.Kind.APK, "APK FILES", Category.APK),
            Triple(VaultFile.Kind.OTHER, "OTHER FILES", Category.OTHER)
        )
        val treeKeys = treeFiles.mapTo(HashSet()) { it.locationKey() }
        for ((kind, title, target) in sections) {
            val preview = ArrayList<MediaFile>()
            sectionPreview[kind]
                ?.asSequence()
                ?.filter { LibraryLogic.matchesQuery(it.displayName, query) }
                ?.filter { it.locationKey() !in treeKeys }
                ?.filter { if (kind == VaultFile.Kind.OTHER) true else it.kind == kind }
                ?.forEach { preview += it }
            val fromTree = treeFilesFor(if (kind == VaultFile.Kind.OTHER) null else kind)
                .filter { kind != VaultFile.Kind.OTHER || it.kind == VaultFile.Kind.OTHER || it.kind == VaultFile.Kind.AUDIO }
            preview += fromTree
            if (preview.isEmpty()) {
                result += Item.SectionHeader(title, "", null)
                result += Item.Notice("NOTHING TO SHOW YET", emptySectionHint(title))
                continue
            }
            val sorted = sortMedia(preview)
            val subtitle = if (sorted.size > PREVIEW_PER_SECTION) {
                "SHOWING ${PREVIEW_PER_SECTION}+"
            } else {
                "${sorted.size} FILE" + if (sorted.size == 1) "" else "S"
            }
            result += Item.SectionHeader(title, subtitle, { selectCategory(target) })
            sorted.take(PREVIEW_PER_SECTION).forEach { result += Item.MediaItem(it) }
            if (sorted.size > PREVIEW_PER_SECTION) {
                result += Item.More("VIEW ALL $title ›") { selectCategory(target) }
            }
        }
        return result
    }

    /** Why a section can be empty, and what the owner can do about it. */
    private fun emptySectionHint(title: String): String = when (title) {
        "VIDEOS" ->
            "No videos are visible yet. Grant the media permission above, or add a folder that holds videos."
        "PHOTOS" ->
            "No photos are visible yet. Grant the media permission above, or add a folder that holds photos."
        else ->
            "Android shows documents, APK files, archives and audio only inside folders you approve. " +
                "Add the folder that holds them - for example Documents, or a subfolder of Download."
    }

    private fun emptyMessage(): String = when {
        tab == Tab.LOCKED && query.isNotBlank() -> "No locked files match your search."
        tab == Tab.LOCKED && currentFolder != null -> "This folder is empty."
        tab == Tab.LOCKED -> "No locked files yet.\nSelect a file in the Library and tap LOCK."
        query.isNotBlank() -> "No accessible files match your search."
        category == Category.DOCUMENTS -> "No documents found yet.\nAdd the folder that holds your PDFs and office files."
        category == Category.APK -> "No APK files found yet.\nAdd the folder that holds your APKs; Compass never installs them."
        category == Category.VIDEOS -> "No accessible videos were found.\nGrant media access or add a folder."
        category == Category.PHOTOS -> "No accessible images were found.\nGrant media access or add a folder."
        category == Category.OTHER -> "No audio, archives or other files were found."
        else -> "Nothing to show yet.\nGrant media access, add a folder, or use ADD FILES."
    }

    private fun onItemTapped(item: Item?) {
        when (item) {
            is Item.MediaItem -> {
                if (selectMode) toggleSelection(item.file) else openMedia(item.file)
            }
            is Item.VaultItem -> openVaultFile(item.file)
            is Item.SectionHeader -> item.onViewAll?.invoke()
            else -> Unit
        }
    }

    private fun toggleSelection(file: MediaFile) {
        val key = file.locationKey()
        if (!selected.remove(key)) selected.add(key)
        renderItems()
    }

    private fun selectedFiles(): List<MediaFile> {
        val all = (mediaFiles + treeFiles + sectionPreview.values.flatten()).distinctBy { it.locationKey() }
        return all.filter { it.locationKey() in selected }
    }

    // -----------------------------------------------------------------------
    // Adapter
    // -----------------------------------------------------------------------

    private inner class LibraryAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Item = items[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getViewTypeCount(): Int = 5
        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is Item.SectionHeader -> 0
            is Item.MediaItem -> 1
            is Item.VaultItem -> 2
            is Item.Notice -> 3
            is Item.More -> 4
        }

        override fun isEnabled(position: Int): Boolean = items[position] !is Item.SectionHeader

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            return when (val item = items[position]) {
                is Item.SectionHeader -> sectionHeader(item)
                is Item.MediaItem -> mediaRow(item.file, convertView)
                is Item.VaultItem -> vaultRow(item.file, convertView)
                is Item.Notice -> noticeRow(item)
                is Item.More -> moreRow(item)
            }
        }

        private fun moreRow(item: Item.More): View {
            val view = activity.label(item.label, Type.LABEL, Palette.GRAY, 0.16f).apply {
                gravity = Gravity.CENTER
                setPadding(activity.dp(Spacing.MD), activity.dp(Spacing.MD), activity.dp(Spacing.MD), activity.dp(Spacing.MD))
                setBackgroundColor(Palette.BLACK)
                contentDescription = item.label
                setOnClickListener { item.onClick() }
            }
            return view
        }

        private fun sectionHeader(item: Item.SectionHeader): View {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(activity.dp(Spacing.MD), activity.dp(Spacing.MD), activity.dp(Spacing.MD), activity.dp(Spacing.SM))
                setBackgroundColor(Palette.BLACK)
            }
            val title = activity.label(item.title, Type.LABEL, Palette.WHITE, 0.22f)
            row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val subtitle = activity.label(item.subtitle, Type.LABEL, Palette.GRAY_DIM, 0.12f)
            row.addView(subtitle)
            row.addView(activity.hspace(Spacing.SM))
            val viewAll = activity.label("VIEW ALL ›", Type.LABEL, Palette.GRAY, 0.14f).apply {
                contentDescription = "View every ${item.title.lowercase(Locale.ROOT)} file"
                setOnClickListener { item.onViewAll?.invoke() }
            }
            row.addView(viewAll)
            return row
        }

        private fun noticeRow(item: Item.Notice): View {
            val column = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(activity.dp(Spacing.MD), activity.dp(Spacing.MD), activity.dp(Spacing.MD), activity.dp(Spacing.MD))
                setBackgroundColor(Palette.BLACK)
            }
            if (item.title.isNotBlank()) {
                column.addView(activity.label(item.title, Type.LABEL, Palette.WHITE, 0.16f))
                column.addView(activity.spacer(Spacing.XS))
            }
            column.addView(activity.body(item.text, Type.BODY, Palette.GRAY))
            return column
        }

        private fun mediaRow(file: MediaFile, convertView: View?): View {
            val root: LinearLayout
            val row: MediaRow
            if (convertView is LinearLayout && convertView.tag is MediaRow) {
                root = convertView
                row = convertView.tag as MediaRow
            } else {
                root = buildMediaRow()
                row = root.tag as MediaRow
            }
            val selectedNow = file.locationKey() in selected
            row.name.text = file.displayName
            row.badge.text = LibraryLogic.badge(file.kind)
            row.details.text = buildString {
                append(file.mimeType.substringAfter('/').takeIf { it.isNotBlank() && it != "octet-stream" }
                    ?: file.extensionOrKind())
                if (file.size > 0) append(" · ${LibraryLogic.formatSize(file.size)}")
                val duration = LibraryLogic.formatDuration(file.durationMs)
                if (duration.isNotEmpty()) append(" · $duration")
                val date = maxOf(file.dateTakenMs, file.modifiedAt)
                if (date > 0) append(" · ${dateFormat.format(Date(date))}")
            }
            row.security.text = when {
                !file.deletionSupported -> "PROVIDER DOES NOT ALLOW REMOVAL"
                selectMode && selectedNow -> "SELECTED · TAP TO UNSELECT"
                selectMode -> "TAP TO SELECT"
                file.source == MediaFile.Source.SAF_TREE -> "IN AN APPROVED FOLDER · AVAILABLE TO LOCK"
                else -> "AVAILABLE TO LOCK"
            }
            row.security.setTextColor(Palette.GRAY_DIM)
            row.action.text = if (selectMode) (if (selectedNow) "[x]" else "[ ]") else "LOCK"
            row.action.contentDescription = "Lock ${file.displayName}"
            row.action.setOnClickListener {
                if (selectMode) toggleSelection(file) else lockMedia(file)
            }
            row.more.visibility = if (selectMode) View.GONE else View.VISIBLE
            row.more.setOnClickListener { showMediaActions(file) }
            root.setOnClickListener { if (selectMode) toggleSelection(file) else openMedia(file) }
            root.setOnLongClickListener { showMediaActions(file); true }
            root.setBackgroundColor(if (selectedNow && selectMode) Palette.SURFACE_RAISED else Palette.BLACK)
            loadThumbnail(row, file)
            return root
        }

        private fun vaultRow(file: VaultFile, convertView: View?): View {
            val root: LinearLayout
            val row: MediaRow
            if (convertView is LinearLayout && convertView.tag is MediaRow) {
                root = convertView
                row = convertView.tag as MediaRow
            } else {
                root = buildMediaRow()
                row = root.tag as MediaRow
            }
            row.name.text = file.name
            row.badge.text = LibraryLogic.badge(file.kind)
            row.details.text = buildString {
                append(file.mimeType.substringAfter('/').takeIf { it.isNotBlank() && it != "octet-stream" }
                    ?: file.extension.ifBlank { file.kind.name }.uppercase(Locale.ROOT))
                append(" · ${LibraryLogic.formatSize(file.size)}")
                val duration = LibraryLogic.formatDuration(file.durationMs)
                if (duration.isNotEmpty()) append(" · $duration")
                append(" · ${dateFormat.format(Date(file.createdAt))}")
                if (!file.folder.isNullOrBlank()) append(" · ${file.folder}")
            }
            val status = securityStatus(file)
            row.security.text = status.first
            row.security.setTextColor(status.second)
            row.action.text = "UNLOCK"
            row.action.contentDescription = "Unlock and restore ${file.name}"
            row.action.setOnClickListener { unlock(file) }
            row.more.visibility = View.VISIBLE
            row.more.setOnClickListener { showVaultActions(file) }
            root.setOnClickListener { openVaultFile(file) }
            root.setOnLongClickListener { showVaultActions(file); true }
            root.setBackgroundColor(Palette.BLACK)
            loadThumbnail(row, file)
            return root
        }

        private fun buildMediaRow(): LinearLayout {
            val root = LinearLayout(activity).apply {
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
                contentDescription = "File preview"
            }
            val badge = activity.label("FILE", Type.LABEL, Palette.GRAY, 0.12f).apply {
                gravity = Gravity.CENTER
                setBackgroundColor(Palette.SURFACE)
                contentDescription = "File type"
            }
            thumbnail.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            thumbnail.addView(badge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            root.addView(thumbnail, LinearLayout.LayoutParams(activity.dp(58), activity.dp(58)))
            root.addView(activity.hspace(Spacing.MD))

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
            root.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val action = activity.label("", Type.LABEL, Palette.WHITE, 0.1f).apply {
                gravity = Gravity.CENTER
                minimumWidth = activity.dp(58)
                minimumHeight = activity.dp(48)
                setPadding(activity.dp(Spacing.XS), activity.dp(Spacing.SM), activity.dp(Spacing.XS), activity.dp(Spacing.SM))
            }
            root.addView(action)
            val more = activity.label("···", Type.BODY, Palette.GRAY, 0.1f).apply {
                gravity = Gravity.CENTER
                contentDescription = "More file actions"
                minimumWidth = activity.dp(44)
                minimumHeight = activity.dp(48)
            }
            root.addView(more)
            root.tag = MediaRow(preview, badge, name, details, security, action, more)
            return root
        }

        private fun loadThumbnail(row: MediaRow, file: MediaFile) {
            val key = "media:${file.uri}"
            row.preview.tag = key
            row.preview.setImageDrawable(null)
            row.badge.visibility = View.VISIBLE
            if (file.kind != VaultFile.Kind.IMAGE && file.kind != VaultFile.Kind.VIDEO) return
            val wanted = file
            scope.launch {
                val bitmap: Bitmap? = thumbnailSlots.withPermit {
                    graph.mediaLibrary.thumbnailAny(wanted, activity.dp(58))
                }
                if (row.preview.tag == key && bitmap != null) {
                    row.preview.setImageBitmap(bitmap)
                    row.badge.visibility = View.GONE
                }
            }
        }

        private fun loadThumbnail(row: MediaRow, file: VaultFile) {
            val key = "vault:${file.id}"
            row.preview.tag = key
            row.preview.setImageDrawable(null)
            row.badge.visibility = View.VISIBLE
            if (!file.isImage && !file.isVideo) return
            scope.launch {
                val bitmap: Bitmap? = thumbnailSlots.withPermit {
                    graph.repository.thumbnail(file, activity.dp(58))
                }
                if (row.preview.tag == key && bitmap != null) {
                    row.preview.setImageBitmap(bitmap)
                    row.badge.visibility = View.GONE
                }
            }
        }
    }

    private fun MediaFile.extensionOrKind(): String =
        LibraryLogic.extensionOf(displayName).uppercase(Locale.ROOT).ifBlank { kind.name }

    /** The honest label shown on a locked row. */
    private fun securityStatus(file: VaultFile): Pair<String, Int> = when {
        file.operationState == VaultFile.OperationState.SOURCE_REMOVAL_PENDING ->
            "REMOVAL OUTCOME UNKNOWN · VERIFY SOURCE" to Palette.WHITE
        file.operationState == VaultFile.OperationState.RESTORE_RECOVERY_REQUIRED ->
            "RESTORE NEEDS REVIEW · VAULT COPY KEPT" to Palette.WHITE
        file.sourceState == VaultFile.SourceState.PRIVATE_ONLY ->
            "ENCRYPTED · PUBLIC ORIGINAL REMOVED" to Palette.GRAY
        file.sourceState == VaultFile.SourceState.PUBLIC_COPY_PRESENT ->
            "NOT FULLY HIDDEN · PUBLIC ORIGINAL MAY REMAIN" to Palette.WHITE
        else ->
            "SOURCE STATUS UNKNOWN · VERIFY BEFORE TRUSTING" to Palette.WHITE
    }

    // -----------------------------------------------------------------------
    // Locking
    // -----------------------------------------------------------------------

    private val locker = FileLocker(graph, navigator)

    private fun startImport() {
        if (selectMode) {
            val files = selectedFiles()
            if (files.isEmpty()) {
                navigator.toast("Select at least one file")
                return
            }
            lockFiles(files)
            return
        }
        navigator.pickFiles(arrayOf("image/*", "video/*", "audio/*", "application/*", "text/*")) { uris ->
            if (uris.isNotEmpty()) lockUris(uris)
        }
    }

    private fun lockMedia(file: MediaFile) = lockFiles(listOf(file))

    /**
     * Asks once, then runs the shared [FileLocker] transaction: verified
     * encryption first, provider/consent removal afterwards, and only the
     * removals Android confirms are reported as hidden.
     */
    private fun lockFiles(files: List<MediaFile>) {
        if (operationInProgress) return
        val preview = files.take(3).joinToString(", ") { it.displayName } +
            if (files.size > 3) " +${files.size - 3} more" else ""
        val userDeletable = files.count { it.deletionSupported }
        val note = if (files.any { it.source == MediaFile.Source.SAF_TREE } && userDeletable == files.size) {
            "Files in an approved folder are deleted through Android's document provider without an extra dialog."
        } else {
            "Android will ask you to confirm the deletion of media items; the vault copy is kept if you cancel."
        }
        AlertDialog.Builder(activity)
            .setTitle(if (files.size == 1) "Lock this file?" else "Lock ${files.size} files?")
            .setMessage(
                "$preview\n\nCompass encrypts each file into its private vault, verifies the encrypted copy, " +
                    "then removes the public original so it disappears from galleries and file managers.\n\n$note"
            )
            .setPositiveButton("LOCK") { _, _ -> performLock(files) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performLock(files: List<MediaFile>) {
        if (operationInProgress) return
        beginOperation("ENCRYPTING 1 OF ${files.size}")
        if (selectMode) {
            selectMode = false
            selected.clear()
            updateChrome()
        }
        scope.launch {
            try {
                val summary = locker.lock(files, currentFolder) { _, text -> reportProgress(text) }
                finishOperation()
                showSummary(summary)
                refreshAll(forceTree = true)
            } catch (cancelled: CancellationException) {
                operationInProgress = false
                throw cancelled
            } catch (error: Exception) {
                finishOperation()
                navigator.toast("Nothing was deleted. ${error.message ?: "The files could not be protected."}")
            }
        }
    }

    private fun lockUris(uris: List<Uri>) {
        if (operationInProgress) return
        beginOperation("PREPARING SECURE IMPORT")
        scope.launch {
            try {
                val summary = locker.lockPicked(uris, currentFolder) { _, text -> reportProgress(text) }
                finishOperation()
                showSummary(summary)
                refreshAll(forceTree = true)
            } catch (cancelled: CancellationException) {
                operationInProgress = false
                throw cancelled
            } catch (error: Exception) {
                finishOperation()
                navigator.toast("Nothing was deleted. ${error.message ?: "The files could not be protected."}")
            }
        }
    }

    private fun retryRemovalFor(files: List<VaultFile>) {
        if (operationInProgress) return
        beginOperation("VERIFYING PUBLIC SOURCE")
        scope.launch {
            try {
                val summary = locker.retryRemoval(files) { _, text -> reportProgress(text) }
                finishOperation()
                if (summary.removed > 0) {
                    navigator.toast("Android confirmed removal of ${summary.removed} public original(s)")
                } else {
                    navigator.toast("The public original could not be removed; the encrypted copy is untouched")
                }
                refreshLocked()
                refreshMedia(reset = true)
            } catch (cancelled: CancellationException) {
                operationInProgress = false
                throw cancelled
            } catch (error: Exception) {
                finishOperation()
                navigator.toast("Removal outcome is uncertain; the encrypted copy is preserved.")
                refreshLocked()
            }
        }
    }

    private fun showSummary(summary: FileLocker.Summary) {
        val parts = ArrayList<String>()
        if (summary.protected > 0) parts += "${summary.protected} encrypted"
        if (summary.removed > 0) parts += "${summary.removed} public original(s) removed"
        if (summary.retained > 0) parts += "${summary.retained} still public - retry from LOCKED FILES"
        if (summary.failed > 0) parts += "${summary.failed} could not be protected"
        if (parts.isEmpty()) parts += "Nothing to do"
        navigator.toast(parts.joinToString(" · "))
        summary.messages.firstOrNull()?.let { navigator.toast(it) }
    }

    private fun beginOperation(status: String) {
        operationInProgress = true
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        statusLabel.visibility = View.VISIBLE
        statusLabel.text = status
    }

    private fun reportProgress(text: String) {
        mainHandler.post { if (isVisible) statusLabel.text = text }
    }

    // -----------------------------------------------------------------------
    // Unlocking
    // -----------------------------------------------------------------------

    private fun unlock(file: VaultFile) {
        if (operationInProgress) return
        operationInProgress = true
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        statusLabel.visibility = View.VISIBLE
        statusLabel.text = "RESTORING ${file.name}"
        scope.launch {
            try {
                when (val outcome = graph.repository.restoreToOriginal(file) { bytes ->
                    reportProgress("RESTORING ${LibraryLogic.formatSize(bytes)}")
                }) {
                    is VaultRepository.RestoreOutcome.Restored -> {
                        finishOperation()
                        refreshAfterUnlock()
                        navigator.toast("Unlocked: \"${outcome.name}\" is back at ${outcome.location}")
                    }
                    is VaultRepository.RestoreOutcome.NeedsDestination -> {
                        finishOperation()
                        offerDestination(file, outcome.reason)
                    }
                }
            } catch (cancelled: CancellationException) {
                operationInProgress = false
                throw cancelled
            } catch (error: Exception) {
                finishOperation()
                navigator.toast("The file could not be restored; the encrypted copy was kept. ${error.message ?: "Try again."}")
                refreshLocked()
            }
        }
    }

    private fun offerDestination(file: VaultFile, reason: String) {
        AlertDialog.Builder(activity)
            .setTitle("Where should \"${file.name}\" go?")
            .setMessage("$reason\n\nChoose a folder through Android's save dialog, or cancel to keep the file locked.")
            .setPositiveButton("CHOOSE LOCATION") { _, _ ->
                navigator.pickRestoreTarget(file.name, file.mimeType) { target -> restoreToDestination(file, target) }
            }
            .setNegativeButton("Keep it locked", null)
            .show()
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
                    reportProgress("RESTORING ${LibraryLogic.formatSize(bytes)}")
                }
                finishOperation()
                refreshAfterUnlock()
                navigator.toast("Unlocked and verified; the encrypted copy was removed")
            } catch (cancelled: CancellationException) {
                operationInProgress = false
                throw cancelled
            } catch (error: Exception) {
                finishOperation()
                navigator.toast("Restore failed; the encrypted copy was kept. ${error.message ?: ""}")
                refreshLocked()
            }
        }
    }

    private fun refreshAfterUnlock() {
        treeScanLoaded = false
        refreshMedia(reset = true)
        refreshTree(force = true)
        refreshLocked()
        renderItems()
    }

    private fun finishOperation() {
        operationInProgress = false
        progressBar.visibility = View.GONE
        progressBar.isIndeterminate = false
        statusLabel.visibility = View.GONE
    }

    // -----------------------------------------------------------------------
    // Item actions
    // -----------------------------------------------------------------------

    private fun openMedia(file: MediaFile) {
        when (file.kind) {
            VaultFile.Kind.IMAGE, VaultFile.Kind.VIDEO, VaultFile.Kind.AUDIO -> navigator.showMediaPreview(file)
            VaultFile.Kind.APK -> showMediaActions(file)
            else -> showMediaActions(file)
        }
    }

    private fun openVaultFile(file: VaultFile) {
        if (file.isImage || file.isVideo || file.isAudio) navigator.showViewer(file) else showVaultActions(file)
    }

    private fun showMediaActions(file: MediaFile) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (file.kind in setOf(VaultFile.Kind.IMAGE, VaultFile.Kind.VIDEO, VaultFile.Kind.AUDIO)) {
            actions += "Preview / play in Compass" to { navigator.showMediaPreview(file) }
        }
        if (file.kind != VaultFile.Kind.APK && file.kind != VaultFile.Kind.IMAGE && file.kind != VaultFile.Kind.VIDEO) {
            actions += "Open with an installed app" to { navigator.openPublicFile(file.uri, file.mimeType) }
        }
        actions += "Lock (hide from other apps)" to { lockMedia(file) }
        actions += "File information" to { showMediaDetails(file) }
        AlertDialog.Builder(activity)
            .setTitle(file.displayName)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions.getOrNull(which)?.second?.invoke() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showVaultActions(file: VaultFile) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (file.isImage || file.isVideo || file.isAudio) actions += "Open / play in Compass" to { openVaultFile(file) }
        actions += "Unlock and restore" to { unlock(file) }
        if (file.sourceState != VaultFile.SourceState.PRIVATE_ONLY) {
            actions += "Retry removing the public original" to { retryRemovalFor(listOf(file)) }
        }
        actions += "Move to a folder" to { showMoveDialog(file) }
        actions += "File information" to { showVaultDetails(file) }
        actions += "Delete the encrypted copy" to { confirmDelete(file) }
        AlertDialog.Builder(activity)
            .setTitle(file.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions.getOrNull(which)?.second?.invoke() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMediaDetails(file: MediaFile) {
        val duration = LibraryLogic.formatDuration(file.durationMs)
        AlertDialog.Builder(activity)
            .setTitle("File information")
            .setMessage(
                "Name: ${file.displayName}\nType: ${file.mimeType}\nSize: ${LibraryLogic.formatSize(file.size)}" +
                    (if (duration.isNotEmpty()) "\nDuration: $duration" else "") +
                    "\nLocation: ${LibraryLogic.locationLabel(file.relativePath, file.absolutePath, "Android media library")}" +
                    "\nFound via: ${if (file.source == MediaFile.Source.SAF_TREE) "approved folder" else "media library"}" +
                    "\nCan be removed when locked: ${if (file.deletionSupported) "yes" else "the provider refuses deletion"}"
            )
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showVaultDetails(file: VaultFile) {
        val sourceStatus = when (file.sourceState) {
            VaultFile.SourceState.PRIVATE_ONLY -> "removed (verified)"
            VaultFile.SourceState.PUBLIC_COPY_PRESENT -> "a public copy may still be accessible"
            VaultFile.SourceState.UNKNOWN -> "not verified (imported by an older version or the source was unavailable)"
        }
        val operation = when (file.operationState) {
            VaultFile.OperationState.READY -> "none"
            else -> file.operationState.name.replace('_', ' ').lowercase(Locale.ROOT)
        }
        val duration = LibraryLogic.formatDuration(file.durationMs)
        AlertDialog.Builder(activity)
            .setTitle("File information")
            .setMessage(
                "Name: ${file.name}\nType: ${file.mimeType}\nSize: ${LibraryLogic.formatSize(file.size)}" +
                    (if (duration.isNotEmpty()) "\nDuration: $duration" else "") +
                    "\nLocked on: ${dateFormat.format(Date(file.createdAt))}" +
                    "\nProtected storage: app-private, AES-256-GCM" +
                    "\nOriginal location: ${LibraryLogic.locationLabel(file.originRelativePath, file.originAbsolutePath, "recorded privately")}" +
                    "\nPublic original: $sourceStatus" +
                    "\nPending operation: $operation"
            )
            .setPositiveButton("Close", null)
            .show()
    }

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
                updateChrome()
                if (tab == Tab.LIBRARY && category != Category.ALL) refreshMedia(reset = true) else renderItems()
                if (tab == Tab.LIBRARY) loadSectionPreviews()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(file: VaultFile) {
        AlertDialog.Builder(activity)
            .setTitle("Delete the encrypted copy?")
            .setMessage(
                "\"${file.name}\" is removed from the vault permanently. A public original is not affected; " +
                    "if the file is locked, this also throws away the only copy Compass holds."
            )
            .setPositiveButton("Delete") { _, _ ->
                scope.launch {
                    try {
                        graph.repository.delete(file)
                        refreshLocked()
                        navigator.toast("Encrypted copy deleted")
                    } catch (_: Exception) {
                        navigator.toast("Could not delete the encrypted copy; nothing was changed")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMoveDialog(file: VaultFile) {
        val options = arrayOf("No folder") + folders.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("Move to a folder")
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
            .setTitle("New vault folder")
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

    private fun openOrLockVault() {
        if (selectMode) {
            toggleSelectMode()
            return
        }
        if (graph.lockController.isUnlocked) {
            graph.lockController.lock()
            navigator.toast("Vault locked; decrypted temporary files were wiped")
        } else {
            navigator.showVault()
        }
    }

    companion object {
        private const val PREVIEW_PER_SECTION = 4
    }
}
