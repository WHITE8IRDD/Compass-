package com.compass.vault

import android.app.Application
import com.compass.vault.data.DocumentTreeLibraryRepository
import com.compass.vault.data.MediaLibraryRepository
import com.compass.vault.data.VaultRepository
import com.compass.vault.security.KeystoreCipher
import com.compass.vault.security.LockController
import com.compass.vault.security.PinStore

/**
 * Application entry point.
 *
 * The app has **no third-party dependencies**, so there is no library
 * initialisation to do. What we do instead is build a tiny dependency graph
 * ("AppGraph") that lives as long as the process - this is the MVVM root:
 * the ViewModels (here: the repository + the lock controller) survive screen
 * rotations and activity recreation.
 */
class CompassApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

/**
 * Manual dependency injection.
 *
 * A DI framework would be another third-party dependency; for an app with
 * this many collaborators a hand written graph is clearer *and* keeps the
 * supply chain empty.
 */
class AppGraph(application: Application) {

    /**
     * The single master key holder. The key itself lives inside the Android
     * Keystore and is never exposed.
     */
    val cipher = KeystoreCipher()

    /** PIN hash + brute-force bookkeeping. */
    val pinStore = PinStore(application, cipher)

    /** Encrypted file storage. */
    val repository = VaultRepository(application, cipher)

    /** Scoped MediaStore discovery and in-memory thumbnail cache. */
    val mediaLibrary = MediaLibraryRepository(application)

    /** SAF folder discovery for documents, APKs, and other user-approved files. */
    val documentLibrary = DocumentTreeLibraryRepository(application, cipher)

    /** Open/locked state of the vault + the auto-lock policy. */
    val lockController = LockController(
        appContext = application,
        pinStore = pinStore
    ) {
        // Called whenever the vault closes: wipe decrypted temp files and
        // drop the in-memory thumbnail cache.
        repository.wipeTempFiles()
        mediaLibrary.clearCache()
    }
}
