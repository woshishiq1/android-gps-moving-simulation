package io.github.mwarevn.fakegps.ui

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Spinner
import io.github.mwarevn.fakegps.R
import io.github.mwarevn.fakegps.network.VehicleType
import io.github.mwarevn.fakegps.utils.PrefManager

/**
 * Dialog to configure routing settings:
 * - Vehicle type selection
 */
object RoutingSettingsDialog {
    
    fun show(context: Context, onSaved: () -> Unit = {}) {
        val dialogView = LayoutInflater.from(context).inflate(
            R.layout.dialog_routing_settings, null
        )
        
        val vehicleTypeSpinner = dialogView.findViewById<Spinner>(R.id.vehicle_type_spinner)
        
        // Setup vehicle type spinner
        val vehicleTypes = VehicleType.entries.toTypedArray()
        val vehicleNames = vehicleTypes.map { it.displayName }
        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, vehicleNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        vehicleTypeSpinner.adapter = adapter
        
        // Set current vehicle type
        val currentType = VehicleType.fromString(PrefManager.vehicleType)
        vehicleTypeSpinner.setSelection(vehicleTypes.indexOf(currentType))
        
        AlertDialog.Builder(context)
            .setTitle("Cài đặt tìm đường")
            .setView(dialogView)
            .setPositiveButton("Lưu") { _, _ ->
                val selectedType = vehicleTypes[vehicleTypeSpinner.selectedItemPosition]
                PrefManager.vehicleType = selectedType.name
                onSaved()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }
}
