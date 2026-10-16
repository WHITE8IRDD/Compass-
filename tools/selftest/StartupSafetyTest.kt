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
        check("the app does not request INTERNET permission", !Regex("<uses-permission[^>]*android.permission.INTERNET").containsMatchIn(manifest.readText()))
    }

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

    val vaultScreen = File(sourceRoot, "ui/screen/VaultScreen.kt")
    check("VaultScreen.kt was found", vaultScreen.isFile)
    if (vaultScreen.isFile) {
        val text = vaultScreen.readText()
        val importUris = text.substringAfter("private fun importUris(", "")
            .substringBefore("private fun lockMedia(")
        check("batch flow asks Android to delete only after verified import returns",
            importUris.indexOf("graph.repository.import(") >= 0 &&
                importUris.indexOf("graph.repository.import(") < importUris.indexOf("requestRemoval(uri)"))
        val lockMedia = text.substringAfter("private fun lockMedia(", "")
            .substringBefore("private suspend fun requestRemoval(")
        check("All Media lock flow asks Android to delete only after import returns",
            lockMedia.indexOf("graph.repository.import(") >= 0 &&
                lockMedia.indexOf("graph.repository.import(") < lockMedia.indexOf("requestRemoval(media.uri)"))
        val refreshLocked = text.substringAfter("private fun refreshLocked(", "")
            .substringBefore("// -----------------------------------------------------------------------")
        check("empty MediaStore queries cannot silently mark originals as removed",
            refreshLocked.contains("Do not infer a successful delete") && !refreshLocked.contains("sourceStillExists"))
    }

    val restoreOriginal = repositoryText.substringAfter("suspend fun restoreToOriginalMedia(", "")
        .substringBefore("/**\n     * Restore to a SAF-selected destination")
    check("original restore verifies existing source before removing encrypted copy",
        restoreOriginal.indexOf("sourceContentMatches(file, it") >= 0 &&
            restoreOriginal.indexOf("sourceContentMatches(file, it") < restoreOriginal.indexOf("deleteInternal(file)"))
    check("new MediaStore restore verifies output before removing encrypted copy",
        restoreOriginal.lastIndexOf("verifyUriDigest(") >= 0 &&
            restoreOriginal.lastIndexOf("verifyUriDigest(") < restoreOriginal.lastIndexOf("deleteInternal(file)"))
    val restoreUri = repositoryText.substringAfter("suspend fun restoreToUri(", "")
        .substringBefore("/** Decrypts an explicit user-requested export")
    check("SAF restore reads back output before removing encrypted copy",
        restoreUri.indexOf("verifyUriDigest(") >= 0 && restoreUri.indexOf("verifyUriDigest(") < restoreUri.indexOf("deleteInternal(file)"))
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
