package io.github.mwarevn.movingsimulation.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import io.github.mwarevn.movingsimulation.R
import io.github.mwarevn.movingsimulation.databinding.ActivityAntiDetectionSettingsBinding
import io.github.mwarevn.movingsimulation.utils.PrefManager

/**
 * Activity for configuring Anti-Detection hooks
 * Allows users to enable/disable individual hooks with descriptions and warnings
 */
class AntiDetectionSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAntiDetectionSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAntiDetectionSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupSwitches()
        setupResetButton()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Anti-Detection Hooks"
        }
    }

    private fun setupSwitches() {
        // TIER 1: SAFE HOOKS
        setupSwitch(
            binding.switchIsFromMockProvider,
            PrefManager.hookIsFromMockProvider,
            "Hook Location.isFromMockProvider()",
            "✅ SAFE\n\nHides that GPS location is from mock provider.\n\n" +
                    "CRITICAL for fake GPS to work!\n\n" +
                    "Risk: NONE - Always safe"
        ) { PrefManager.hookIsFromMockProvider = it }

        setupSwitch(
            binding.switchMockLocationCheck,
            PrefManager.hookMockLocationCheck,
            "Hook Settings.Secure Mock Location",
            "✅ SAFE\n\nHides mock location setting from Settings.Secure.\n\n" +
                    "Banking apps check this setting.\n\n" +
                    "Risk: NONE - Always safe"
        ) { PrefManager.hookMockLocationCheck = it }

        setupSwitch(
            binding.switchBuildFields,
            PrefManager.hookBuildFields,
            "Hook Build.TAGS (test-keys)",
            "✅ SAFE\n\nReplaces 'test-keys' with 'release-keys' in Build.TAGS.\n\n" +
                    "Banking apps detect test builds.\n\n" +
                    "Risk: NONE - One-time field replacement"
        ) { PrefManager.hookBuildFields = it }

        setupSwitch(
            binding.switchStackTrace,
            PrefManager.hookStackTrace,
            "Hook Stack Trace Cleaning",
            "✅ SAFE\n\nRemoves Xposed/LSPosed frames from stack traces.\n\n" +
                    "Banking apps inspect stack traces.\n\n" +
                    "Risk: NONE - Optimized, only filters traces"
        ) { PrefManager.hookStackTrace = it }

        // TIER 2: MODERATE RISK HOOKS
        setupSwitch(
            binding.switchPackageManagerSafe,
            PrefManager.hookPackageManagerSafe,
            "Hook PackageManager (SAFE)",
            "⚠️ MODERATE\n\nHides LSPosed packages from PackageManager.\n\n" +
                    "SAFE version: Only filters results, doesn't block.\n\n" +
                    "Risk: LOW - May cause minor slowdown"
        ) { PrefManager.hookPackageManagerSafe = it }

        setupSwitch(
            binding.switchClassLoaderSafe,
            PrefManager.hookClassLoaderSafe,
            "Hook ClassLoader (SAFE)",
            "⚠️ MODERATE\n\nBlocks loading of Xposed classes.\n\n" +
                    "SAFE version: Only blocks exact Xposed classes.\n\n" +
                    "Risk: LOW - Uses whitelist to avoid blocking important classes"
        ) { PrefManager.hookClassLoaderSafe = it }

        setupSwitch(
            binding.switchApplicationInfo,
            PrefManager.hookApplicationInfo,
            "Hook ApplicationInfo (Debug Flag)",
            "⚠️ MODERATE\n\nHides debuggable flag from ApplicationInfo.\n\n" +
                    "Banking apps check if app is debuggable.\n\n" +
                    "Risk: LOW - Only modifies flag, doesn't block"
        ) { PrefManager.hookApplicationInfo = it }

        setupSwitch(
            binding.switchSystemProperties,
            PrefManager.hookSystemProperties,
            "Hook SystemProperties (ro.debuggable)",
            "⚠️ MODERATE\n\nFakes system properties:\n" +
                    "• ro.debuggable = 0\n" +
                    "• ro.secure = 1\n" +
                    "• ro.build.type = user\n\n" +
                    "CRITICAL for banking apps!\n\n" +
                    "Risk: LOW - Only intercepts specific properties"
        ) { PrefManager.hookSystemProperties = it }

        // TIER 3: RISKY HOOKS
        setupSwitch(
            binding.switchClassForName,
            PrefManager.hookClassForName,
            "Hook Class.forName() [IMPROVED]",
            "� IMPROVED VERSION\n\n" +
                    "Blocks Xposed classes via Class.forName() using SAFE techniques:\n\n" +
                    "✅ IMPROVEMENTS:\n" +
                    "• Exact matching (no contains() false positives)\n" +
                    "• Inner class safety checks\n" +
                    "• Tracks failed blocks to avoid breaking critical classes\n" +
                    "• Graceful error handling\n\n" +
                    "⚠️ Still has SMALL risk:\n" +
                    "• Some apps may still freeze if they heavily use Class.forName()\n" +
                    "• But MUCH safer than old version!\n\n" +
                    "Recommended: Try enabling if banking app still detects LSPosed after TIER 1+2"
        ) { PrefManager.hookClassForName = it }

        setupSwitch(
            binding.switchClassLoader,
            PrefManager.hookClassLoader,
            "Hook ClassLoader [IMPROVED]",
            "� IMPROVED VERSION\n\n" +
                    "Blocks Xposed classes via ClassLoader using SAFE techniques:\n\n" +
                    "✅ IMPROVEMENTS:\n" +
                    "• CRITICAL: Skips system server (no bootloop!)\n" +
                    "• Exact matching + prefix checks only\n" +
                    "• Inner class safety checks\n" +
                    "• Tracks problematic loads\n" +
                    "• Only hooks app-level (not system)\n\n" +
                    "⚠️ Risk reduced from HIGH to LOW:\n" +
                    "• Bootloop risk: 0% (was 60%)\n" +
                    "• App freeze risk: <5% (was 80%)\n\n" +
                    "Recommended: Can enable safely if needed for advanced banking apps"
        ) { PrefManager.hookClassLoader = it }

        setupSwitch(
            binding.switchPackageManager,
            PrefManager.hookPackageManager,
            "Hook PackageManager (AGGRESSIVE) [RISKY]",
            "🔴 RISKY\n\nAggressive PackageManager hook.\n\n" +
                    "⚠️ WARNING: May cause startup issues!\n\n" +
                    "May cause:\n" +
                    "• Slow app startup\n" +
                    "• Package detection issues\n\n" +
                    "Use SAFE version instead!"
        ) { PrefManager.hookPackageManager = it }

        setupSwitch(
            binding.switchNativeLibrary,
            PrefManager.hookNativeLibrary,
            "Hook Native Libraries [RISKY]",
            "🔴 RISKY\n\nBlocks loading of Xposed native libraries.\n\n" +
                    "⚠️ WARNING: May break native code!\n\n" +
                    "May cause:\n" +
                    "• Native crashes\n" +
                    "• Missing libraries\n" +
                    "• App malfunction\n\n" +
                    "Rarely needed!"
        ) { PrefManager.hookNativeLibrary = it }

        setupSwitch(
            binding.switchMapView,
            PrefManager.hookMapView,
            "Hook GoogleMap.isMyLocationEnabled() [OPTIONAL]",
            "⚠️ OPTIONAL\n\nHides that location is enabled in Google Maps.\n\n" +
                    "Usually not needed for banking apps.\n\n" +
                    "Risk: LOW - But may affect Maps functionality"
        ) { PrefManager.hookMapView = it }
    }

    private fun setupSwitch(
        switch: SwitchCompat,
        currentValue: Boolean,
        title: String,
        description: String,
        onChanged: (Boolean) -> Unit
    ) {
        // Set initial state
        switch.isChecked = currentValue

        // Variable to track if we're programmatically changing the switch
        var isUpdating = false

        // Set up listener
        switch.setOnCheckedChangeListener { _, isChecked ->
            // Ignore if we're programmatically updating
            if (isUpdating) return@setOnCheckedChangeListener

            // Show confirmation dialog
            showHookInfo(title, description, isChecked,
                onConfirm = {
                    // User confirmed - save the change
                    onChanged(isChecked)
                    Toast.makeText(
                        this,
                        if (isChecked) "Hook enabled - Reboot to apply" else "Hook disabled - Reboot to apply",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onCancel = {
                    // User cancelled - revert switch silently
                    isUpdating = true
                    switch.isChecked = !isChecked
                    isUpdating = false
                }
            )
        }
    }

    private fun showHookInfo(
        title: String,
        description: String,
        enabling: Boolean,
        onConfirm: () -> Unit,
        onCancel: () -> Unit
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(description)
            .setPositiveButton(if (enabling) "Enable" else "Disable") { _, _ ->
                onConfirm()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                onCancel()
            }
            .setCancelable(false)
            .show()
    }

    private fun setupResetButton() {
        binding.btnResetToDefault.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset to Default")
                .setMessage(
                    "This will reset all hooks to recommended safe settings:\n\n" +
                            "✅ ENABLED:\n" +
                            "• All SAFE hooks (Tier 1)\n" +
                            "• All MODERATE hooks (Tier 2)\n\n" +
                            "❌ DISABLED:\n" +
                            "• All RISKY hooks (Tier 3)\n\n" +
                            "Continue?"
                )
                .setPositiveButton("Reset") { _, _ ->
                    PrefManager.resetAntiDetectionToDefault()
                    Toast.makeText(this, "Reset to default settings", Toast.LENGTH_SHORT).show()
                    recreate()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
