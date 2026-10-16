package com.compass.vault.selftest

import java.io.File

/**
 * A static check for the crash class that killed the first 0.1 build.
 *
 * The bug: `MainActivity` created its root `FrameLayout` in a *field
 * initializer*:
 *
 *     private val root = FrameLayout(this)
 *
 * Field initializers of an Activity run while the Activity object is still
 * being constructed - that is, **before** `attachBaseContext()` has been
 * called. The base context is still null at that point, so the first View
 * that asks the context for its resources kills the app:
 *
 *     java.lang.NullPointerException: Attempt to invoke virtual method
 *     'android.content.res.Resources android.content.Context.getResources()'
 *     on a null object reference
 *         at android.widget.FrameLayout.<init>(FrameLayout.java:85)
 *         at com.compass.vault.MainActivity.<init>(MainActivity.kt:52)
 *
 * The app therefore never showed a single screen.
 *
 * This test greps the sources for the pattern, so the mistake cannot be
 * committed again by accident. It is a static check because the real
 * behaviour can only be observed on a device.
 */
private var failures = 0

private fun check(name: String, condition: Boolean) {
    if (condition) {
        println("  PASS  $name")
    } else {
        println("  FAIL  $name")
        failures++
    }
}

fun main() {
    println("Compass - startup safety test")
    println("===========================")

    val root = File(System.getProperty("compass.root") ?: ".")
    val sourceRoot = File(root, "app/src/main/java/com/compass/vault")
    if (!sourceRoot.isDirectory) {
        println("  FAIL  source directory not found: $sourceRoot")
        kotlin.system.exitProcess(1)
    }

    val sources = sourceRoot.walkTopDown().filter { it.name.endsWith(".kt") }.toList()
    check("the source tree was found (${sources.size} Kotlin files)", sources.isNotEmpty())

    // --- 1. no Activity may build a View in a field initializer ------------
    val activityFiles = sources.filter { file ->
        val text = file.readText()
        Regex(
            """class\s+\w+\s*(\([^)]*\))?\s*:\s*(\w+\.)*(Activity|AppCompatActivity|FragmentActivity)\b"""
        ).containsMatchIn(text)
    }
    check("the Activity classes were found (${activityFiles.map { it.name }})", activityFiles.isNotEmpty())

    var offenders = mutableListOf<String>()
    for (file in activityFiles) {
        file.readLines().forEachIndexed { index, line ->
            val trimmed = line.trim()
            // "private val root = FrameLayout(this)" and friends: a property
            // whose initialiser calls a constructor with "this" as the
            // context.
            val isProperty = Regex(
                """^(private|internal|public|protected)?\s*(lateinit\s+)?(val|var)\s+\w+[^=]*="""
            ).containsMatchIn(trimmed)
            val buildsWithThis = Regex("""=\s*[A-Z]\w*\(\s*this\b""").containsMatchIn(trimmed)
            if (isProperty && buildsWithThis) {
                offenders += "${file.name}:${index + 1}  $trimmed"
            }
        }
    }
    check(
        "no Activity creates a View with 'this' in a field initializer" +
            (if (offenders.isEmpty()) "" else " -> ${offenders.joinToString("; ")}"),
        offenders.isEmpty()
    )

    // --- 2. the Activity must build its root inside onCreate ---------------
    val mainActivity = activityFiles.firstOrNull { it.name == "MainActivity.kt" }
    check("MainActivity.kt was found", mainActivity != null)
    if (mainActivity != null) {
        val text = mainActivity.readText()
        val onCreateBody = text.substringAfter("override fun onCreate", "")
            .substringBefore("override fun onStart")
        check("the root view is created inside onCreate", onCreateBody.contains("FrameLayout(this)"))
        check(
            "the root view is not a field initializer",
            !Regex("""^\s*private\s+(lateinit\s+)?val\s+root\s*=""", RegexOption.MULTILINE).containsMatchIn(text)
        )
    }

    // --- 3. the manifest must not grant network access ----------------------
    val manifest = File(root, "app/src/main/AndroidManifest.xml")
    check("AndroidManifest.xml was found", manifest.isFile)
    if (manifest.isFile) {
        val manifestText = manifest.readText()
        check("the app does not request INTERNET permission", !Regex("<uses-permission[^>]*android.permission.INTERNET").containsMatchIn(manifestText))
        check("the app does not request broad all-files access", !manifestText.contains("MANAGE_EXTERNAL_STORAGE"))
    }

    val mainActivityText = mainActivity?.readText().orEmpty()
    val settingsRoute = mainActivityText.substringAfter("override fun showSettings()", "")
        .substringBefore("override fun showViewer(")
    check("security settings require the vault authentication gate",
        settingsRoute.contains("if (!graph.lockController.isUnlocked)") &&
            settingsRoute.contains("showPin(PinReason.SETTINGS_ACCESS)"))

    val buildConfig = File(root, "app/build.gradle.kts")
    check("the app version is Compass 2.4 / code 24",
        buildConfig.isFile && buildConfig.readText().contains("versionName = \"2.4\"") &&
            buildConfig.readText().contains("versionCode = 24"))

    val pinStore = File(sourceRoot, "security/PinStore.kt")
    val pinStoreText = if (pinStore.isFile) pinStore.readText() else ""
    check("corrupt PIN state fails closed instead of resetting setup",
        pinStoreText.contains("State(setupDone = true)") &&
            pinStoreText.contains("VerifyResult.StorageError") &&
            pinStoreText.contains("AtomicFileWriter.write(file)"))

    // --- 4. file protection must be transactional -------------------------
    val repository = File(sourceRoot, "data/VaultRepository.kt")
    check("VaultRepository.kt was found", repository.isFile)
    val repositoryText = if (repository.isFile) repository.readText() else ""
    val importBody = repositoryText.substringAfter("suspend fun import(", "")
        .substringBefore("/** Persist the platform result")
    val verifyIndex = importBody.indexOf("MessageDigest.isEqual(originalDigest, verifiedDigest.digest())")
    val publishIndex = importBody.indexOf("AtomicFileWriter.replace(partial, payload)")
    val metadataIndex = importBody.indexOf("writeMetadata(file)")
    check("import verifies ciphertext before publishing metadata", verifyIndex >= 0 && verifyIndex < publishIndex && publishIndex < metadataIndex)
    check("repository import never deletes a public source", !importBody.contains("resolver.delete") && !importBody.contains("deleteDocument"))
    val removalJournal = repositoryText.substringAfter("suspend fun markSourceRemovalPending(", "")
        .substringBefore("suspend fun markSourceRemoved(")
    check("source bytes are re-verified before the durable removal intent",
        removalJournal.indexOf("sourceContentMatches(current, source") >= 0 &&
            removalJournal.indexOf("sourceContentMatches(current, source") < removalJournal.indexOf("writeMetadata(updated)"))

    // --- 4a. the library screen is the home screen and it is honest --------
    val libraryScreen = File(sourceRoot, "ui/screen/LibraryScreen.kt")
    check("LibraryScreen.kt was found", libraryScreen.isFile)
    val libraryText = if (libraryScreen.isFile) libraryScreen.readText() else ""
    check(
        "the Locked Files tab is gated behind the vault authentication state",
        libraryText.contains("if (target == Tab.LOCKED && !graph.lockController.isUnlocked)")
    )
    check(
        "the library screen never claims a file is hidden without the transaction result",
        libraryText.contains("public original(s) removed") || libraryText.contains("public original removed")
    )
    val sections = listOf("VIDEOS", "PHOTOS", "DOCUMENTS", "APK FILES", "OTHER FILES")
    check(
        "the home screen is built from the required sections " + sections,
        sections.all { libraryText.contains("\"$it\"") }
    )

    // --- 4b. the lock transaction orders encryption before deletion --------
    val locker = File(sourceRoot, "ui/FileLocker.kt")
    check("FileLocker.kt was found", locker.isFile)
    val lockerText = if (locker.isFile) locker.readText() else ""
    val importCall = lockerText.indexOf("graph.repository.import(")
    val pendingJournal = lockerText.indexOf("markSourceRemovalPending")
    val deleteRequest = lockerText.indexOf("requestDeleteOriginalsAwait(")
    check(
        "the lock flow encrypts before it journals a removal",
        importCall >= 0 && pendingJournal > importCall
    )
    check(
        "the lock flow journals the removal intent before it asks Android to delete",
        pendingJournal >= 0 && deleteRequest > pendingJournal
    )
    check(
        "a removal is recorded as succeeded only when Android confirmed it",
        lockerText.contains("val wasRemoved = uri.toString() in removed") &&
            lockerText.contains("graph.repository.markSourceRemoved(pending, wasRemoved)")
    )

    // --- 4c. the plaintext export workflow is gone -------------------------
    val allSources = sources.joinToString("\n") { it.readText() }
    check(
        "the plaintext export workflow was removed from the app",
        !allSources.contains("pickExportTarget") && !allSources.contains("fun export(")
    )

    // --- 4c2. every Android version has a removal path --------------------
    val activityText = File(sourceRoot, "MainActivity.kt").let { if (it.isFile) it.readText() else "" }
    check(
        "media on Android 11+ is removed through one batched consent request",
        activityText.contains("MediaStore.createDeleteRequest") &&
            activityText.contains("REQUEST_DELETE_ORIGINALS")
    )
    check(
        "media on Android 10 asks item by item instead of failing the batch",
        activityText.contains("RecoverableSecurityException") &&
            activityText.contains("askNextRecoverable")
    )
    check(
        "Android 8-9 removal is gated behind the write permission",
        activityText.contains("WRITE_EXTERNAL_STORAGE") &&
            activityText.contains("REQUEST_LEGACY_WRITE_PERMISSION")
    )
    check(
        "a removal is only reported after the system dialog answered",
        activityText.contains("systemDialogOpen = true") &&
            activityText.contains("Deletion was cancelled; the public originals remain.")
    )

    // --- 4d. multiple approved folders, dead grants pruned ---------------
    val treeLibrary = File(sourceRoot, "data/DocumentTreeLibraryRepository.kt")
    val treeText = if (treeLibrary.isFile) treeLibrary.readText() else ""
    check("the SAF library stores several folders", treeText.contains("library-folders.enc"))
    check(
        "revoked SAF grants are detected instead of being scanned blindly",
        treeText.contains("persistedUriPermissions")
    )

    // --- 4e. the media preview offers the same lock transaction -----------
    val preview = File(sourceRoot, "ui/screen/MediaPreviewScreen.kt")
    val previewText = if (preview.isFile) preview.readText() else ""
    check(
        "the media preview locks through the shared transaction",
        previewText.contains("FileLocker(graph, navigator).lock(")
    )

    // --- 4f. the automatic unlock verifies before it removes ---------------
    val restoreOriginal = repositoryText.substringAfter("suspend fun restoreToOriginal(", "")
        .substringBefore("RestoreOutcome.NeedsDestination(\n                    \"The original folder is no longer available")
    check(
        "the automatic restore verifies an existing public original before dropping the encrypted copy",
        restoreOriginal.indexOf("sourceContentMatches(current, originUri") in 1..restoreOriginal.indexOf("deleteInternal(published)")
    )
    check(
        "the automatic restore refuses to create a second public copy",
        restoreOriginal.contains("current.sourceState != VaultFile.SourceState.PRIVATE_ONLY") &&
            restoreOriginal.contains("NeedsDestination")
    )
    val restorePath = repositoryText.substringAfter("private fun restoreToPath(", "")
        .substringBefore("private fun locationLabelFor(")
    check(
        "the legacy path restore verifies the read-back before publishing",
        restorePath.contains("Read-back verification") &&
            restorePath.indexOf("Read-back verification") < restorePath.indexOf("AtomicFileWriter.replace(partial, target)")
    )
    check(
        "the legacy path restore reports a partial file instead of hiding it",
        restorePath.contains("left a partial file") &&
            restorePath.contains("VaultFile.OperationState.RESTORE_RECOVERY_REQUIRED")
    )
    check(
        "the automatic restore deletes the encrypted copy only after the destination is published",
        restorePath.indexOf("AtomicFileWriter.replace(partial, target)") < restorePath.indexOf("deleteInternal(published)")
    )
    check(
        "an empty MediaStore query is not treated as proof of removal",
        libraryText.contains("An empty MediaStore query is not proof that a source was")
    )

    val restoreMediaStore = repositoryText.substringAfter("private fun restoreToMediaStore(", "")
        .substringBefore("private fun restoreToTreeFolder(")
    val mediaReadBack = restoreMediaStore.indexOf("verifyUriDigest(target, file.size, digest")
    val mediaCleanup = restoreMediaStore.indexOf("deleteInternal(published)")
    check("MediaStore restore reads back and publishes output before removing encrypted copy",
        mediaReadBack >= 0 && mediaReadBack < mediaCleanup &&
            restoreMediaStore.indexOf("resolver.update(target, publishedValues") > mediaReadBack)

    val restoreTree = repositoryText.substringAfter("private fun restoreToTreeFolder(", "")
        .substringBefore("/** A conflicting SAF name")
    val treeReadBack = restoreTree.indexOf("verifyUriDigest(target, file.size, digest")
    val treeCleanup = restoreTree.indexOf("deleteInternal(published)")
    check("SAF folder restore reads back output before removing encrypted copy",
        treeReadBack >= 0 && treeReadBack < treeCleanup)

    val restoreUri = repositoryText.substringAfter("suspend fun restoreToUri(", "")
        .substringBefore("private fun restoreToMediaStore(")
    val uriReadBack = restoreUri.indexOf("verifyUriDigest(target, current.size, digest")
    val uriCleanup = restoreUri.indexOf("deleteInternal(current.copy(")
    check("SAF save-picker restore reads back output before removing encrypted copy",
        uriReadBack >= 0 && uriReadBack < uriCleanup)
    check("corrupt vault metadata is surfaced instead of displayed as an empty vault",
        repositoryText.contains("Vault entry metadata could not be verified") && !repositoryText.substringAfter("suspend fun list(").substringBefore("suspend fun folders").contains("mapNotNull"))

    // --- 5. screens may not be built before the context is attached --------
    // The screens are constructed from MainActivity.onCreate(), which runs
    // after attachBaseContext(). A screen that created a View with the
    // activity as the context inside an *init* block would be fine, but a
    // screen that is constructed from a field initializer of the Activity
    // would not be. The Activity check above already covers that.
    for (file in sources.filter { it.name.endsWith("Screen.kt") }) {
        val text = file.readText()
        check(
            "${file.name} builds its view tree in a function, not in an init block",
            !Regex("""^\s*init\s*\{""", RegexOption.MULTILINE).containsMatchIn(text)
        )
    }

    println()
    if (failures == 0) {
        println("ALL CHECKS PASSED")
    } else {
        println("$failures CHECK(S) FAILED")
        kotlin.system.exitProcess(1)
    }
}
