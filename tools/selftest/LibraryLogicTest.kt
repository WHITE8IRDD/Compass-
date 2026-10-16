package com.compass.vault.selftest

import com.compass.vault.data.LibraryLogic
import com.compass.vault.data.VaultFile

/**
 * Plain-JVM test of the classification and naming rules that the library and
 * the vault share. These are the rules that decide which section a file ends
 * up in and - much more important - what a restored file is called when the
 * original name is already taken.
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
    println("Compass - library logic test")
    println("============================")

    // --- 1. classification ------------------------------------------------
    check(
        "a jpg is an image",
        LibraryLogic.kindFor("image/jpeg", "photo.jpg") == VaultFile.Kind.IMAGE
    )
    check(
        "an unknown MIME type falls back to the extension",
        LibraryLogic.kindFor("application/octet-stream", "clip.mp4") == VaultFile.Kind.VIDEO
    )
    check(
        "a specific MIME type wins over a misleading extension",
        LibraryLogic.kindFor("image/png", "not-really.txt") == VaultFile.Kind.IMAGE
    )
    check(
        "an APK is recognised by its MIME type",
        LibraryLogic.kindFor("application/vnd.android.package-archive", "download") == VaultFile.Kind.APK
    )
    check(
        "an APK is recognised by its extension",
        LibraryLogic.kindFor("", "app.apk") == VaultFile.Kind.APK
    )
    check(
        "a PDF is a document",
        LibraryLogic.kindFor("application/pdf", "report.pdf") == VaultFile.Kind.DOCUMENT
    )
    check(
        "an office document is a document",
        LibraryLogic.kindFor("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "cv.docx") ==
            VaultFile.Kind.DOCUMENT
    )
    check(
        "a zip is neither media nor a document",
        LibraryLogic.kindFor("application/zip", "backup.zip") == VaultFile.Kind.OTHER
    )
    check(
        "audio is recognised",
        LibraryLogic.kindFor("audio/mpeg", "song.mp3") == VaultFile.Kind.AUDIO
    )
    check(
        "a file without extension is 'other'",
        LibraryLogic.kindFor("", "LICENSE") == VaultFile.Kind.OTHER
    )
    check(
        "the MIME guess of an unknown type is a safe default",
        LibraryLogic.mimeFromName("weird.bin") == "application/octet-stream"
    )

    // --- 2. conflict naming (never overwrite) ------------------------------
    check(
        "a free name is kept",
        LibraryLogic.uniqueName("holiday.mp4", listOf("other.mp4")) == "holiday.mp4"
    )
    check(
        "a taken name gets a suffix",
        LibraryLogic.uniqueName("holiday.mp4", listOf("holiday.mp4")) == "holiday (1).mp4"
    )
    check(
        "the suffix counts up until the name is free",
        LibraryLogic.uniqueName("holiday.mp4", listOf("holiday.mp4", "holiday (1).mp4", "holiday (2).mp4")) ==
            "holiday (3).mp4"
    )
    check(
        "a nameless-extension file keeps an added suffix",
        LibraryLogic.uniqueName("LICENSE", listOf("LICENSE")) == "LICENSE (1)"
    )
    check(
        "a dotfile is not treated as an extension",
        LibraryLogic.uniqueName(".nomedia", listOf(".nomedia")) == ".nomedia (1)"
    )

    // --- 3. search -------------------------------------------------------
    check("an empty query matches everything", LibraryLogic.matchesQuery("a.mp4", ""))
    check("search is case insensitive", LibraryLogic.matchesQuery("Holiday.MP4", "holiday"))
    check("search matches inside the name", LibraryLogic.matchesQuery("2024-07-14 party.mov", "party"))
    check("search rejects a different name", !LibraryLogic.matchesQuery("a.mp4", "b"))

    // --- 4. formatting ----------------------------------------------------
    check("bytes are shown as bytes", LibraryLogic.formatSize(512) == "512 B")
    check("kilobytes round", LibraryLogic.formatSize(2048) == "2 KB")
    check("megabytes have one decimal", LibraryLogic.formatSize(2L * 1024 * 1024) == "2.0 MB")
    check("gigabytes have two decimals", LibraryLogic.formatSize(3L * 1024 * 1024 * 1024) == "3.00 GB")
    check("a negative size is not claimed as known", LibraryLogic.formatSize(-1) == "unknown")
    check("a duration under a minute is mm:ss", LibraryLogic.formatDuration(65_000) == "1:05")
    check("a duration over an hour is h:mm:ss", LibraryLogic.formatDuration(3_725_000) == "1:02:05")
    check("a missing duration is empty", LibraryLogic.formatDuration(0).isEmpty())

    // --- 5. labels --------------------------------------------------------
    check("the image badge is IMG", LibraryLogic.badge(VaultFile.Kind.IMAGE) == "IMG")
    check("the APK badge is APK", LibraryLogic.badge(VaultFile.Kind.APK) == "APK")
    check(
        "a relative location wins over an absolute one",
        LibraryLogic.locationLabel("Movies/", "/storage/emulated/0/Movies/x.mp4", "fallback") == "Movies/"
    )
    check(
        "an absolute location is used when there is no relative one",
        LibraryLogic.locationLabel(null, "/storage/emulated/0/x.mp4", "fallback") == "/storage/emulated/0/x.mp4"
    )
    check(
        "the fallback is used when nothing is known",
        LibraryLogic.locationLabel(null, null, "fallback") == "fallback"
    )

    println()
    if (failures == 0) {
        println("ALL LIBRARY LOGIC CHECKS PASSED")
    } else {
        println("$failures LIBRARY LOGIC CHECK(S) FAILED")
        kotlin.system.exitProcess(1)
    }
}
