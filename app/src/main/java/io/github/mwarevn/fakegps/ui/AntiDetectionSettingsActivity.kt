package io.github.mwarevn.fakegps.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.mwarevn.fakegps.R
import io.github.mwarevn.fakegps.utils.LocationAccessMonitorService
import io.github.mwarevn.fakegps.utils.PrefManager

class AntiDetectionSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_anti_detection_settings)

        // Toolbar
        setSupportActionBar(findViewById(R.id.toolbar))
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        // ====== Section 1: Location ======
        val switchAccuracy = findViewById<MaterialSwitch>(R.id.switchAccuracySpoof)
        switchAccuracy.isChecked = PrefManager.isAccuracySpoofEnabled
        switchAccuracy.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.isAccuracySpoofEnabled = isChecked
        }

        val switchGeocoder = findViewById<MaterialSwitch>(R.id.switchGeocoderSpoof)
        switchGeocoder.isChecked = PrefManager.isGeocoderSpoofEnabled
        switchGeocoder.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.isGeocoderSpoofEnabled = isChecked
        }

        // ====== Section 2: Sensor ======
        val switchSensor = findViewById<MaterialSwitch>(R.id.switchSensorSpoof)
        switchSensor.isChecked = PrefManager.isSensorSpoofEnabled
        switchSensor.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.isSensorSpoofEnabled = isChecked
        }

        // ====== Section 3: Network & Connectivity ======
        val switchNetwork = findViewById<MaterialSwitch>(R.id.switchNetworkSimulation)
        switchNetwork.isChecked = PrefManager.isNetworkSimEnabled
        switchNetwork.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.isNetworkSimEnabled = isChecked
        }

        val switchBluetooth = findViewById<MaterialSwitch>(R.id.switchBluetoothSpoof)
        switchBluetooth.isChecked = PrefManager.isBluetoothSpoofEnabled
        switchBluetooth.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.isBluetoothSpoofEnabled = isChecked
        }

        val switchCell = findViewById<MaterialSwitch>(R.id.switchCellSpoof)
        switchCell.isChecked = PrefManager.isCellSpoofEnabled
        switchCell.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.isCellSpoofEnabled = isChecked
        }

        // ====== Section 4: System Settings Simulation ======
        val switchWifiScan = findViewById<MaterialSwitch>(R.id.switchWifiScanSpoof)
        switchWifiScan.isChecked = PrefManager.isWifiScanSpoofEnabled
        switchWifiScan.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.isWifiScanSpoofEnabled = isChecked
        }

        val switchBtScan = findViewById<MaterialSwitch>(R.id.switchBtScanSpoof)
        switchBtScan.isChecked = PrefManager.isBtScanSpoofEnabled
        switchBtScan.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.isBtScanSpoofEnabled = isChecked
        }

        // ====== Section 5: Monitoring ======
        val switchMonitor = findViewById<MaterialSwitch>(R.id.switchLocationMonitor)
        switchMonitor.isChecked = PrefManager.isLocationMonitorEnabled
        switchMonitor.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.isLocationMonitorEnabled = isChecked
            if (isChecked) {
                startLocationMonitorService()
            } else {
                stopLocationMonitorService()
            }
        }

        // ====== Reset Button ======
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnResetToDefault)
            .setOnClickListener {
                resetToDefaults()
            }
    }

    private fun startLocationMonitorService() {
        val intent = Intent(this, LocationAccessMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopLocationMonitorService() {
        val intent = Intent(this, LocationAccessMonitorService::class.java)
        stopService(intent)
    }

    private fun resetToDefaults() {
        PrefManager.isAccuracySpoofEnabled = true
        PrefManager.isSensorSpoofEnabled = true
        PrefManager.isNetworkSimEnabled = true
        PrefManager.isBluetoothSpoofEnabled = true
        PrefManager.isCellSpoofEnabled = true
        PrefManager.isGeocoderSpoofEnabled = true
        PrefManager.isWifiScanSpoofEnabled = true
        PrefManager.isBtScanSpoofEnabled = true
        PrefManager.isLocationMonitorEnabled = false

        stopLocationMonitorService()

        // Refresh all switches
        findViewById<MaterialSwitch>(R.id.switchAccuracySpoof).isChecked = true
        findViewById<MaterialSwitch>(R.id.switchGeocoderSpoof).isChecked = true
        findViewById<MaterialSwitch>(R.id.switchSensorSpoof).isChecked = true
        findViewById<MaterialSwitch>(R.id.switchNetworkSimulation).isChecked = true
        findViewById<MaterialSwitch>(R.id.switchBluetoothSpoof).isChecked = true
        findViewById<MaterialSwitch>(R.id.switchCellSpoof).isChecked = true
        findViewById<MaterialSwitch>(R.id.switchWifiScanSpoof).isChecked = true
        findViewById<MaterialSwitch>(R.id.switchBtScanSpoof).isChecked = true
        findViewById<MaterialSwitch>(R.id.switchLocationMonitor).isChecked = false
    }
}
