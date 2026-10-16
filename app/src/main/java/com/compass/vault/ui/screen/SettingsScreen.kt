package com.compass.vault.ui.screen

import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.compass.vault.AppGraph
import com.compass.vault.MainActivity
import com.compass.vault.ui.Navigator
import com.compass.vault.ui.Palette
import com.compass.vault.ui.PinReason
import com.compass.vault.ui.Screen
import com.compass.vault.ui.Spacing
import com.compass.vault.ui.Type
import com.compass.vault.ui.body
import com.compass.vault.ui.dp
import com.compass.vault.ui.hairline
import com.compass.vault.ui.hspace
import com.compass.vault.ui.label
import com.compass.vault.ui.padForSystemBars
import com.compass.vault.ui.settingRow
import com.compass.vault.ui.spacer
import com.compass.vault.util.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.compass.vault.ui.outlineButton

/**
 * Settings: the unlock methods, the auto-lock policy and the security
 * information of this installation.
 */
class SettingsScreen(
    private val activity: MainActivity,
    private val graph: AppGraph,
    private val navigator: Navigator
) : Screen {

    private var scope = CoroutineScope(SupervisorJob() + AppDispatchers.main)
    private var biometricHandle: com.compass.vault.security.BiometricGate.Handle? = null
    private val container = LinearLayout(activity)

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
        val back = activity.label("BACK", Type.LABEL, Palette.WHITE, 0.18f)
        back.setPadding(0, activity.dp(Spacing.SM), 0, activity.dp(Spacing.SM))
        back.setOnClickListener { navigator.showLibrary() }
        topBar.addView(back)
        topBar.addView(activity.hspace(Spacing.MD))
        val title = activity.body("SETTINGS", Type.SCREEN_TITLE, Palette.WHITE, 600)
        topBar.addView(title)
        root.addView(topBar)
        root.addView(activity.hairline())

        container.orientation = LinearLayout.VERTICAL
        root.addView(container, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        buildRows()
        return root
    }

    override fun onShow() {
        // The scope is cancelled in onHide(); a fresh one is needed when the
        // very same screen instance is shown again.
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + AppDispatchers.main)
        buildRows()
    }

    override fun onHide() {
        biometricHandle?.cancel()
        biometricHandle = null
        scope.cancel()
    }

    override fun onBack(): Boolean {
        navigator.showLibrary()
        return true
    }

    private fun buildRows() {
        container.removeAllViews()

        // --- unlock ---------------------------------------------------------
        container.addView(sectionLabel("UNLOCK"))
        container.addView(activity.hairline())
        container.addView(activity.settingRow(
            "Secret bearing",
            if (graph.pinStore.isBearingEnabled()) String.format("%03d°", graph.pinStore.secretBearing().toInt()) else "Off"
        ) { showBearingDialog() })
        container.addView(activity.hairline())
        container.addView(activity.settingRow(
            "Fingerprint unlock",
            if (graph.pinStore.isBiometricEnabled()) "On" else "Off"
        ) { toggleBiometric() })
        container.addView(activity.hairline())
        container.addView(activity.settingRow(
            "Change PIN",
            null
        ) { navigator.showPin(PinReason.CHANGE) })
        container.addView(activity.hairline())

        // --- security --------------------------------------------------------
        container.addView(sectionLabel("SECURITY"))
        container.addView(activity.hairline())
        container.addView(activity.settingRow(
            "Decoy vault after failures",
            if (graph.pinStore.isDecoyEnabled()) "On" else "Off"
        ) { toggleDecoy() })
        container.addView(activity.hairline())
        container.addView(activity.settingRow(
            "Auto-lock when backgrounded",
            "${graph.pinStore.graceSeconds()} s"
        ) { showAutoLockDialog() })
        container.addView(activity.hairline())
        container.addView(activity.settingRow(
            "Encryption",
            if (graph.cipher.isHardwareBacked()) "AES-256-GCM, hardware backed" else "AES-256-GCM"
        ))
        container.addView(activity.hairline())
        container.addView(activity.settingRow(
            "Network access",
            "None"
        ))
        container.addView(activity.hairline())

        // --- storage ---------------------------------------------------------
        container.addView(sectionLabel("STORAGE"))
        container.addView(activity.hairline())
        val storageRow = activity.settingRow("Used by the vault", "…")
        container.addView(storageRow)
        container.addView(activity.hairline())
        val lockedRow = activity.settingRow("Locked files", "…")
        container.addView(lockedRow)
        container.addView(activity.hairline())
        container.addView(activity.settingRow("Clear temporary playback data", null) {
            graph.repository.wipeTempFiles()
            graph.mediaLibrary.clearCache()
            navigator.toast("Temporary decrypted files and thumbnails were wiped")
        })
        container.addView(activity.hairline())

        // --- library discovery ------------------------------------------------
        container.addView(sectionLabel("WHERE COMPASS CAN LOOK"))
        container.addView(activity.hairline())
        val mediaRow = activity.settingRow(
            "Photos, videos, audio",
            if (navigator.hasMediaAccess()) {
                if (navigator.hasPartialMediaAccess()) "Selected items only" else "Granted"
            } else {
                "Not granted"
            }
        ) {
            navigator.requestMediaAccess { granted ->
                if (granted) buildRows() else navigator.toast("Media access was not granted")
            }
        }
        container.addView(mediaRow)
        container.addView(activity.hairline())
        val folderRow = activity.settingRow("Approved folders", "…") { showFoldersDialog() }
        container.addView(folderRow)
        container.addView(activity.hairline())
        container.addView(activity.settingRow("Add a folder", null) { chooseFolder() })
        container.addView(activity.hairline())
        val note = activity.body(
            "Android only exposes documents and APK files to an app through a folder you approve. " +
                "Compass does not ask for the all-files permission and never uploads anything.",
            Type.BODY, Palette.GRAY_DIM
        )
        note.setPadding(activity.dp(Spacing.MD), activity.dp(Spacing.SM), activity.dp(Spacing.MD), activity.dp(Spacing.LG))
        container.addView(note)
        container.addView(activity.hairline())

        // --- about -----------------------------------------------------------
        container.addView(sectionLabel("ABOUT"))
        container.addView(activity.hairline())
        container.addView(activity.settingRow("Version", "2.4") { showAboutDialog() })
        container.addView(activity.hairline())

        scope.launch {
            try {
                val used = graph.repository.storageUsedBytes()
                (storageRow.getChildAt(1) as? TextView)?.text = com.compass.vault.ui.formatSize(used)
                val count = graph.repository.list().size
                (lockedRow.getChildAt(1) as? TextView)?.text = count.toString()
                val folders = graph.documentLibrary.folderNames()
                (folderRow.getChildAt(1) as? TextView)?.text = if (folders.isEmpty()) "None" else folders.joinToString(", ")
            } catch (_: Exception) {
                (storageRow.getChildAt(1) as? TextView)?.text = "Unavailable"
                (lockedRow.getChildAt(1) as? TextView)?.text = "Unavailable"
                (folderRow.getChildAt(1) as? TextView)?.text = "Unavailable"
            }
        }
    }

    private fun sectionLabel(text: String): View {
        val view = activity.label(text, Type.LABEL, Palette.GRAY_DIM, 0.22f)
        view.setPadding(
            activity.dp(Spacing.MD), activity.dp(Spacing.LG),
            activity.dp(Spacing.MD), activity.dp(Spacing.SM)
        )
        return view
    }

    // -----------------------------------------------------------------------
    // Actions
    // -----------------------------------------------------------------------

    private fun showBearingDialog() {
        val seek = SeekBar(activity).apply {
            max = 359
            progress = graph.pinStore.secretBearing().toInt()
            setProgressTintList(android.content.res.ColorStateList.valueOf(Palette.WHITE))
            setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Palette.HAIRLINE))
        }
        val readout = activity.body(String.format("%03d°", seek.progress), Type.TITLE, Palette.WHITE, 600)
        readout.gravity = Gravity.CENTER
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                readout.text = String.format("%03d°", progress)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(Spacing.LG), activity.dp(Spacing.MD), activity.dp(Spacing.LG), 0)
            addView(readout, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(seek, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        AlertDialog.Builder(activity)
            .setTitle("Secret bearing")
            .setMessage("Point the needle at this bearing and hold it to open the vault.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                if (graph.pinStore.setCompassUnlock(seek.progress.toFloat(), enabled = true)) buildRows()
                else navigator.toast("Could not save the compass unlock setting")
            }
            .setNeutralButton("Turn off") { _, _ ->
                if (graph.pinStore.setBearingEnabled(false)) buildRows()
                else navigator.toast("Could not save the compass unlock setting")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleBiometric() {
        if (graph.pinStore.isBiometricEnabled()) {
            if (graph.pinStore.setBiometricEnabled(false)) buildRows()
            else navigator.toast("Could not save the biometric setting")
            return
        }
        val gate = com.compass.vault.security.BiometricGate(activity)
        val availability = gate.availability()
        if (availability != com.compass.vault.security.BiometricGate.Availability.READY) {
            AlertDialog.Builder(activity)
                .setTitle("Biometric unlock unavailable")
                .setMessage(gate.availabilityMessage(availability))
                .setPositiveButton("OK", null)
                .show()
            return
        }
        biometricHandle = gate.authenticate(
            title = "Enable biometric unlock",
            subtitle = "Verify your identity to enable this shortcut"
        ) { result ->
            biometricHandle = null
            when (result) {
                com.compass.vault.security.BiometricGate.Result.Success -> {
                    if (graph.pinStore.setBiometricEnabled(true)) buildRows()
                    else navigator.toast("Could not save the biometric setting")
                }
                com.compass.vault.security.BiometricGate.Result.Cancelled -> Unit
                is com.compass.vault.security.BiometricGate.Result.Error -> {
                    AlertDialog.Builder(activity)
                        .setTitle("Could not enable biometrics")
                        .setMessage(result.message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun toggleDecoy() {
        val enabled = graph.pinStore.isDecoyEnabled()
        if (graph.pinStore.setDecoyEnabled(!enabled)) buildRows()
        else navigator.toast("Could not save the decoy setting")
    }

    private fun showAutoLockDialog() {
        val options = arrayOf("Immediately", "5 seconds", "30 seconds", "1 minute", "5 minutes")
        val current = graph.pinStore.graceSeconds()
        val checked = when (current) {
            0 -> 0
            5 -> 1
            30 -> 2
            60 -> 3
            else -> 4
        }
        AlertDialog.Builder(activity)
            .setTitle("Auto-lock")
            .setSingleChoiceItems(options, checked) { dialog, which ->
                val saved = graph.pinStore.setGraceSeconds(
                    when (which) {
                        0 -> 0
                        1 -> 5
                        2 -> 30
                        3 -> 60
                        else -> 300
                    }
                )
                if (saved) buildRows() else navigator.toast("Could not save the auto-lock setting")
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseFolder() {
        navigator.pickLibraryFolder { uri ->
            if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + AppDispatchers.main)
            scope.launch {
                try {
                    graph.documentLibrary.addFolder(uri)
                    navigator.toast("Folder added")
                    buildRows()
                } catch (_: Exception) {
                    navigator.toast("Could not save folder access")
                }
            }
        }
    }

    private fun showFoldersDialog() {
        scope.launch {
            try {
                val folders = graph.documentLibrary.folders()
                if (folders.isEmpty()) {
                    navigator.toast("No folder has been approved yet")
                    return@launch
                }
                val labels = folders.map { it.toString() }.toTypedArray()
                AlertDialog.Builder(activity)
                    .setTitle("Approved folders")
                    .setItems(labels) { _, which ->
                        val chosen = folders.getOrNull(which) ?: return@setItems
                        AlertDialog.Builder(activity)
                            .setTitle("Remove this folder?")
                            .setMessage("$chosen\n\nCompass stops browsing and locking files in this folder. Files already locked stay in the vault.")
                            .setPositiveButton("Remove") { _, _ ->
                                scope.launch {
                                    graph.documentLibrary.removeFolder(chosen)
                                    runCatching { activity.contentResolver.releasePersistableUriPermission(chosen, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                                    navigator.toast("Folder removed")
                                    buildRows()
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    .setNegativeButton("Close", null)
                    .show()
            } catch (_: Exception) {
                navigator.toast("Could not read the approved folders")
            }
        }
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(activity)
            .setTitle("Compass 2.4")
            .setMessage(
                "A private file vault behind a compass.\n\n" +
                    "Vault payloads are encrypted with AES-256-GCM using a key held by Android Keystore. " +
                    "Biometric approval is an app-level gate; it does not re-wrap the encryption key.\n\n" +
                    "Compass does not request network access permission and works fully offline.\n\n" +
                    "Vault files live in app-private storage. Uninstalling or clearing app data can make them unrecoverable."
            )
            .setPositiveButton("Close", null)
            .show()
    }
}
