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
        back.setOnClickListener { navigator.showVault() }
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
        navigator.showVault()
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

        // --- about -----------------------------------------------------------
        container.addView(sectionLabel("ABOUT"))
        container.addView(activity.hairline())
        container.addView(activity.settingRow("Version", "0.1") { showAboutDialog() })
        container.addView(activity.hairline())

        scope.launch {
            val used = graph.repository.storageUsedBytes()
            val value = (storageRow.getChildAt(1) as? TextView) ?: return@launch
            value.text = com.compass.vault.ui.formatSize(used)
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
                graph.pinStore.setSecretBearing(seek.progress.toFloat())
                graph.pinStore.setBearingEnabled(true)
                buildRows()
            }
            .setNeutralButton("Turn off") { _, _ ->
                graph.pinStore.setBearingEnabled(false)
                buildRows()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleBiometric() {
        if (graph.pinStore.isBiometricEnabled()) {
            graph.pinStore.setBiometricEnabled(false)
            buildRows()
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
                    graph.pinStore.setBiometricEnabled(true)
                    buildRows()
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
        graph.pinStore.setDecoyEnabled(!enabled)
        buildRows()
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
                graph.pinStore.setGraceSeconds(
                    when (which) {
                        0 -> 0
                        1 -> 5
                        2 -> 30
                        3 -> 60
                        else -> 300
                    }
                )
                buildRows()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(activity)
            .setTitle("Compass 0.2")
            .setMessage(
                "A private file vault behind a compass.\n\n" +
                    "Vault payloads are encrypted with AES-256-GCM using a key held by Android Keystore. " +
                    "Biometric approval is an app-level gate; it does not re-wrap the encryption key.\n\n" +
                    "Compass does not request network access permission. Explicitly exported files are plaintext copies.\n\n" +
                    "Vault files live in app-private storage. Uninstalling or clearing app data can make them unrecoverable."
            )
            .setPositiveButton("Close", null)
            .show()
    }
}
