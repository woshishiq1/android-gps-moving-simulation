package io.github.mwarevn.movingsimulation.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import io.github.mwarevn.movingsimulation.databinding.ActivityAntiDetectionSettingsBinding
import io.github.mwarevn.movingsimulation.utils.PrefManager

/**
 * Activity for configuring Advanced Anti-Detection features
 * Streamlined to only show essential advanced features
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
            title = "Advanced Anti-Detection"
        }
    }

    private fun setupSwitches() {
        // Advanced Feature 1: Sensor Spoofing
        setupSwitch(
            binding.switchSensorSpoof,
            PrefManager.enableSensorSpoof,
            "Sensor Spoofing",
            "🚀 ADVANCED FEATURE\n\n" +
                    "Synchronizes device sensors (accelerometer, gyroscope, magnetometer) with GPS movement.\n\n" +
                    "✅ Benefits:\n" +
                    "• Makes movement feel realistic to apps\n" +
                    "• Bypasses ML detection of sensor inconsistencies\n" +
                    "• Uses Kalman filtering for smooth motion\n\n" +
                    "⚠️ Note:\n" +
                    "• May affect apps that depend on real sensors\n" +
                    "• Slightly increases CPU usage\n\n" +
                    "Recommended for: Advanced detection bypass"
        ) { PrefManager.enableSensorSpoof = it }

        // Advanced Feature 2: Network Simulation
        setupSwitch(
            binding.switchNetworkSimulation,
            PrefManager.enableNetworkSimulation,
            "Network Simulation",
            "🚀 ADVANCED FEATURE\n\n" +
                    "Simulates cell tower and WiFi data to match your fake GPS location.\n\n" +
                    "✅ Benefits:\n" +
                    "• Apps can't detect location mismatch via network\n" +
                    "• Generates realistic WiFi AP names and signal strength\n" +
                    "• Fakes cell tower ID and location area code\n\n" +
                    "⚠️ Note:\n" +
                    "• May affect apps that rely on real network info\n" +
                    "• Does not affect actual internet connectivity\n\n" +
                    "Recommended for: Apps that verify location via network triangulation"
        ) { PrefManager.enableNetworkSimulation = it }

        // Advanced Feature 3: Advanced Randomization
        setupSwitch(
            binding.switchAdvancedRandomization,
            PrefManager.enableAdvancedRandomization,
            "Advanced Randomization",
            "🚀 ADVANCED FEATURE\n\n" +
                    "Adds realistic variations to GPS data, timing, and movement patterns.\n\n" +
                    "✅ Benefits:\n" +
                    "• Resists device fingerprinting\n" +
                    "• Defeats ML models analyzing movement patterns\n" +
                    "• Uses Brownian motion for natural position jitter\n" +
                    "• Smooth acceleration/deceleration ramps\n\n" +
                    "⚠️ Note:\n" +
                    "• May cause slight GPS position variations\n" +
                    "• Very lightweight - minimal performance impact\n\n" +
                    "Recommended for: Maximum stealth against advanced detection"
        ) { PrefManager.enableAdvancedRandomization = it }
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
            showFeatureInfo(title, description, isChecked,
                onConfirm = {
                    // User confirmed - save the change
                    onChanged(isChecked)
                    Toast.makeText(
                        this,
                        if (isChecked) "Feature enabled - Restart target app to apply" else "Feature disabled - Restart target app to apply",
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

    private fun showFeatureInfo(
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
                    "This will disable all advanced features (safe default).\n\n" +
                            "❌ All features will be DISABLED:\n" +
                            "• Sensor Spoofing\n" +
                            "• Network Simulation\n" +
                            "• Advanced Randomization\n\n" +
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
