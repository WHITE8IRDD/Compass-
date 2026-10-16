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

    // --- 4. screens may not be built before the context is attached --------
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
