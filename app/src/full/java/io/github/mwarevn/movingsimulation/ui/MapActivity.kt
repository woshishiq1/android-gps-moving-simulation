package io.github.mwarevn.movingsimulation.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.mwarevn.movingsimulation.R
import io.github.mwarevn.movingsimulation.network.OsrmClient
import io.github.mwarevn.movingsimulation.utils.PolylineUtils
import io.github.mwarevn.movingsimulation.utils.RouteSimulator
import io.github.mwarevn.movingsimulation.utils.ext.showToast
import kotlinx.coroutines.*
import java.util.*

/**
 * Clean redesigned MapActivity with Google Maps-like UX
 */
class MapActivity : BaseMapActivity(), OnMapReadyCallback, GoogleMap.OnMapClickListener, GoogleMap.OnMarkerDragListener {

    // Map & Markers
    private lateinit var mMap: GoogleMap
    private var destMarker: Marker? = null
    private var startMarker: Marker? = null
    private var routePolyline: Polyline? = null

    // Route simulation
    private var routeSimulator: RouteSimulator? = null
    private var routePoints: List<LatLng> = emptyList()
    private var isDriving = false
    private var isPaused = false
    private var currentSpeed = 40.0 // Default motorbike speed

    // Movement tracking
    private var currentPositionMarker: Marker? = null
    private var fakeLocationMarker: Marker? = null // Marker for fake GPS location (persistent)
    private var fakeLocationCircle: Circle? = null // Circle to mark fake GPS location
    private var completedPolyline: Polyline? = null
    private var currentPositionIndex = 0
    private var isGpsSet = false // Track GPS state locally for immediate UI update
    private var currentFakeLocationPos: LatLng? = null // Store current fake location for quick use

    // UI State
    private enum class AppMode {
        SEARCH,      // Initial: searching for destination
        ROUTE_PLAN,  // Planning route: selecting start point
        NAVIGATION   // Navigating along route
    }
    private var currentMode = AppMode.SEARCH

    // Search jobs
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure GPS settings
        io.github.mwarevn.movingsimulation.utils.PrefManager.isRandomPosition = false
        io.github.mwarevn.movingsimulation.utils.PrefManager.isSystemHooked = false
        io.github.mwarevn.movingsimulation.utils.PrefManager.accuracy = "15"
    }

    override fun initializeMap() {
        val mapFragment = SupportMapFragment.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(R.id.map, mapFragment)
            .commit()
        mapFragment.getMapAsync(this)
    }

    override fun getActivityInstance() = this

    override fun hasMarker() = false

    override fun moveMapToNewLocation(moveNewLocation: Boolean) {
        // Not used in new design
    }

    @SuppressLint("MissingPermission")
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap

        // Configure map
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            mMap.isMyLocationEnabled = true
            // Get current location
            getCurrentLocation()
        } else {
            // Request permission
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }

        mMap.uiSettings.apply {
            isMyLocationButtonEnabled = false
            isZoomControlsEnabled = false
            isCompassEnabled = false
        }

        mMap.setOnMapClickListener(this)
        mMap.setOnMarkerDragListener(this)

        setupButtons()
        setupSearchBoxes()
        
        // Restore fake location marker if it was set before app was closed
        restoreFakeLocationMarker()
    }

    private fun getCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            return
        }

        try {
            val locationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)
            locationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    lat = location.latitude
                    lon = location.longitude
                    // Move camera to current location
                    val currentLatLng = LatLng(lat, lon)
                    mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 15f))
                } else {
                    // Fallback to default location (Hanoi)
                    lat = 21.0285
                    lon = 105.8542
                    showToast("Không lấy được vị trí hiện tại. Sử dụng vị trí mặc định.")
                }
            }
        } catch (e: Exception) {
            // Fallback to default location
            lat = 21.0285
            lon = 105.8542
            showToast("Lỗi lấy vị trí: ${e.message}")
        }
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 99
    }

    override fun onMapClick(position: LatLng) {
        when (currentMode) {
            AppMode.SEARCH -> {
                // Mark as destination
                setDestinationMarker(position)
            }
            AppMode.ROUTE_PLAN -> {
                // User can re-mark start or destination
                if (startMarker == null) {
                    setStartMarker(position)
                } else {
                    setDestinationMarker(position)
                }
            }
            AppMode.NAVIGATION -> {
                // Do nothing during navigation
            }
        }
    }

    private fun setupSearchBoxes() {
        // Destination search (always visible)
        binding.destinationSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val text = s.toString().trim()
                binding.clearSearch.visibility = if (text.isNotEmpty()) View.VISIBLE else View.GONE

                if (text.length >= 3) {
                    searchJob?.cancel()
                    searchJob = lifecycleScope.launch {
                        delay(800) // Debounce
                        searchLocation(text) { latLng ->
                            setDestinationMarker(latLng)
                        }
                    }
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        binding.destinationSearch.setOnEditorActionListener { v, _, _ ->
            val text = v.text.toString().trim()
            if (text.isNotEmpty()) {
                lifecycleScope.launch {
                    searchLocation(text) { latLng ->
                        setDestinationMarker(latLng)
                    }
                }
            }
            true
        }

        // Clear search button
        binding.clearSearch.setOnClickListener {
            binding.destinationSearch.text.clear()
            clearDestinationMarker()
        }

        // Start location search (shown in route mode)
        binding.startSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val text = s.toString().trim()
                if (text.length >= 3) {
                    searchJob?.cancel()
                    searchJob = lifecycleScope.launch {
                        delay(800)
                        searchLocation(text) { latLng ->
                            setStartMarker(latLng)
                        }
                    }
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        binding.startSearch.setOnEditorActionListener { v, _, _ ->
            val text = v.text.toString().trim()
            if (text.isNotEmpty()) {
                lifecycleScope.launch {
                    searchLocation(text) { latLng ->
                        setStartMarker(latLng)
                    }
                }
            }
            true
        }
    }

    override fun setupButtons() {
        // Floating buttons are always visible by default in layout
        // Initialize set/unset button icon based on GPS state
        isGpsSet = viewModel.isStarted
        updateSetLocationButton()

        // Main action button (Chỉ đường / Bắt đầu)
        binding.actionButton.setOnClickListener {
            when (currentMode) {
                AppMode.SEARCH -> {
                    // User wants to plan route
                    enterRoutePlanMode()
                }
                AppMode.ROUTE_PLAN -> {
                    // Start navigation
                    if (startMarker != null && destMarker != null) {
                        startNavigation()
                    } else {
                        showToast("Vui lòng chọn điểm bắt đầu và điểm đến")
                    }
                }
                AppMode.NAVIGATION -> {
                    // Stop navigation
                    stopNavigation()
                }
            }
        }

        // Get my location button
        binding.getlocation.setOnClickListener {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                    LatLng(lat, lon), 15f
                ))
            }
        }

        // Get fake location button
        binding.getFakeLocation.setOnClickListener {
            if (currentFakeLocationPos != null) {
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                    currentFakeLocationPos!!, 15f
                ))
            }
        }

        // Compass button
        binding.autoXoay.setOnClickListener {
            val currentPos = mMap.cameraPosition
            mMap.animateCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(currentPos.target)
                    .zoom(currentPos.zoom)
                    .bearing(0f)
                    .tilt(0f)
                    .build()
            ))
        }

        // Set/Unset location button
        binding.setLocationButton.setOnClickListener {
            // Toggle GPS state
            android.util.Log.d("MapActivity", "Set/Unset clicked - isGpsSet: $isGpsSet")

            if (isGpsSet) {
                // Unset GPS - remove circle to avoid confusion
                android.util.Log.d("MapActivity", "Unsetting GPS")
                isGpsSet = false
                currentFakeLocationPos = null
                viewModel.update(false, 0.0, 0.0)
                updateSetLocationButton()
                // Remove fake location marker and circle when unsetting
                fakeLocationMarker?.remove()
                fakeLocationMarker = null
                fakeLocationCircle?.remove()
                fakeLocationCircle = null
                showToast("GPS đã được reset về vị trí thật")
            } else {
                // Set GPS to destination marker if exists, otherwise current map center
                val targetPosition = destMarker?.position ?: mMap.cameraPosition.target
                android.util.Log.d("MapActivity", "Setting GPS to: ${targetPosition.latitude}, ${targetPosition.longitude}")
                isGpsSet = true
                currentFakeLocationPos = targetPosition
                viewModel.update(true, targetPosition.latitude, targetPosition.longitude)
                updateSetLocationButton()
                // Show fake location circle
                updateFakeLocationMarker(targetPosition)
                showToast("GPS đã được set tại ${targetPosition.latitude}, ${targetPosition.longitude}")
            }
        }        // Speed slider control
        binding.speedSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                currentSpeed = value.toDouble()
                binding.speedLabel.text = "Tốc độ: ${value.toInt()} km/h"
                // Update speed in real-time if driving
                if (isDriving && !isPaused) {
                    routeSimulator?.setSpeedKmh(currentSpeed)
                }
            }
        }

        // Pause button
        binding.pauseButton.setOnClickListener {
            if (isDriving && !isPaused) {
                isPaused = true
                routeSimulator?.pause()
                binding.pauseButton.visibility = View.GONE
                binding.resumeButton.visibility = View.VISIBLE
                showToast("Đã tạm dừng")
            }
        }

        // Resume button
        binding.resumeButton.setOnClickListener {
            if (isDriving && isPaused) {
                isPaused = false
                routeSimulator?.resume()
                binding.pauseButton.visibility = View.VISIBLE
                binding.resumeButton.visibility = View.GONE
                showToast("Tiếp tục di chuyển")
            }
        }

        // Quick use current fake location as start point
        binding.useCurrentLocationContainer.setOnClickListener {
            if (currentFakeLocationPos != null) {
                setStartMarker(currentFakeLocationPos!!)
                binding.useCurrentLocationContainer.visibility = View.GONE
                showToast("Đã chọn vị trí hiện tại làm điểm bắt đầu")
            }
        }
    }

    private suspend fun searchLocation(query: String, onFound: (LatLng) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(this@MapActivity, Locale.getDefault())
                val addresses = geocoder.getFromLocationName(query, 1)

                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]
                    val latLng = LatLng(address.latitude, address.longitude)

                    withContext(Dispatchers.Main) {
                        onFound(latLng)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        showToast("Không tìm thấy địa điểm")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showToast("Lỗi tìm kiếm: ${e.message}")
                }
            }
        }
    }

    private fun setDestinationMarker(position: LatLng) {
        // Remove old marker
        destMarker?.remove()

        // Add new marker
        destMarker = mMap.addMarker(
            MarkerOptions()
                .position(position)
                .title("Điểm đến")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                .draggable(true)
        )

        // Move camera
        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(position, 16f))

        // Show "Chỉ đường" button
        binding.actionButton.apply {
            text = "Chỉ đường"
            visibility = View.VISIBLE
            setIconResource(R.drawable.ic_baseline_directions_24)
        }

        currentMode = AppMode.SEARCH

        // If in route planning mode and destination changes, clear route
        if (routePolyline != null) {
            routePolyline?.remove()
            routePolyline = null
        }
    }

    private fun clearDestinationMarker() {
        destMarker?.remove()
        destMarker = null
        binding.actionButton.visibility = View.GONE
        currentMode = AppMode.SEARCH

        // Also clear route if exists
        routePolyline?.remove()
        routePolyline = null
        startMarker?.remove()
        startMarker = null
        binding.startSearchContainer.visibility = View.GONE
    }

    private fun setStartMarker(position: LatLng) {
        // Remove old marker
        startMarker?.remove()

        // Add new marker
        startMarker = mMap.addMarker(
            MarkerOptions()
                .position(position)
                .title("Điểm bắt đầu")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                .draggable(true)
        )

        // Move camera to show both markers
        if (destMarker != null) {
            val bounds = LatLngBounds.builder()
                .include(position)
                .include(destMarker!!.position)
                .build()
            mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 150))

            // Draw route
            drawRoute()
        }
    }

    private fun enterRoutePlanMode() {
        currentMode = AppMode.ROUTE_PLAN

        // Show start location input
        binding.startSearchContainer.visibility = View.VISIBLE
        binding.startSearch.requestFocus()

        // Show quick use current location button if GPS is set
        if (isGpsSet && currentFakeLocationPos != null) {
            binding.useCurrentLocationContainer.visibility = View.VISIBLE
            binding.useCurrentLocationText.text = "Dùng vị trí hiện tại (${String.format("%.4f", currentFakeLocationPos!!.latitude)}, ${String.format("%.4f", currentFakeLocationPos!!.longitude)})"
        } else {
            binding.useCurrentLocationContainer.visibility = View.GONE
        }

        showToast("Chọn điểm bắt đầu trên bản đồ hoặc nhập địa chỉ")
    }

    private fun drawRoute() {
        if (startMarker == null || destMarker == null) return

        lifecycleScope.launch {
            try {
                val start = startMarker!!.position
                val dest = destMarker!!.position

                // Call OSRM API
                val coords = "${start.longitude},${start.latitude};${dest.longitude},${dest.latitude}"
                val route = OsrmClient.service.getRoute(coords)

                if (route.routes.isNullOrEmpty() || route.routes[0].geometry == null) {
                    showToast("Không tìm thấy đường đi")
                    return@launch
                }

                val geometry = route.routes[0].geometry!!
                routePoints = PolylineUtils.decode(geometry)

                // Draw polyline
                routePolyline?.remove()
                routePolyline = mMap.addPolyline(
                    PolylineOptions()
                        .addAll(routePoints)
                        .color(android.graphics.Color.BLUE)
                        .width(12f)
                )

                // Update button to "Bắt đầu"
                binding.actionButton.apply {
                    text = "Bắt đầu"
                    setIconResource(R.drawable.ic_play)
                }

                showToast("Đường đi đã sẵn sàng. Nhấn Bắt đầu để di chuyển")

            } catch (e: Exception) {
                showToast("Lỗi vẽ đường: ${e.message}")
            }
        }
    }

    private fun startNavigation() {
        if (routePoints.isEmpty()) {
            showToast("Chưa có đường đi")
            return
        }

        isDriving = true
        isPaused = false
        currentMode = AppMode.NAVIGATION
        currentPositionIndex = 0

        // Clear old fake location marker and circle when starting navigation
        // because the fake location is now moving along the route
        fakeLocationMarker?.remove()
        fakeLocationMarker = null
        fakeLocationCircle?.remove()
        fakeLocationCircle = null
        currentFakeLocationPos = null

        // Hide action button and show navigation controls
        binding.actionButton.visibility = View.GONE
        binding.navigationControlsCard.visibility = View.VISIBLE

        // Reset pause/resume buttons
        binding.pauseButton.visibility = View.VISIBLE
        binding.resumeButton.visibility = View.GONE

        // Set initial speed
        currentSpeed = binding.speedSlider.value.toDouble()

        // Hide search inputs
        binding.searchCard.visibility = View.GONE

        // Create current position marker (simple cyan circle)
        val startPos = routePoints.first()
        // Use circle instead of marker to avoid bitmap issues
        fakeLocationCircle = mMap.addCircle(
            CircleOptions()
                .center(startPos)
                .radius(15.0) // 15 meters radius
                .strokeColor(0xFF00BCD4.toInt()) // Cyan stroke
                .fillColor(0x5000BCD4.toInt()) // Semi-transparent cyan fill
                .strokeWidth(3f)
        )

        // Start route simulator
        routeSimulator = RouteSimulator(
            points = routePoints,
            speedKmh = currentSpeed,
            updateIntervalMs = 150L,
            scope = lifecycleScope
        )

        showToast("🏍️ Bắt đầu di chuyển với tốc độ ${currentSpeed.toInt()} km/h")

        routeSimulator?.start(
            onPosition = { position ->
                runOnUiThread {
                    // Update GPS location
                    viewModel.update(true, position.latitude, position.longitude)

                    // Update current position circle
                    fakeLocationCircle?.center = position

                    // Update completed path (gray polyline)
                    updateCompletedPath(position)

                    // Move camera to follow
                    mMap.animateCamera(CameraUpdateFactory.newLatLng(position))

                    currentPositionIndex++
                }
            },
            onComplete = {
                runOnUiThread {
                    onNavigationComplete()
                }
            }
        )
    }

    private fun stopNavigation() {
        routeSimulator?.stop()
        routeSimulator = null
        isDriving = false
        isPaused = false

        // Keep GPS at current position (don't reset)
        // Remove current position circle
        fakeLocationCircle?.remove()
        fakeLocationCircle = null

        // Remove completed path
        completedPolyline?.remove()
        completedPolyline = null

        // Show search again and hide navigation controls
        binding.searchCard.visibility = View.VISIBLE
        binding.navigationControlsCard.visibility = View.GONE
        binding.actionButton.visibility = View.VISIBLE
        binding.actionButton.apply {
            text = "Dừng"
            setIconResource(R.drawable.ic_stop)
        }

        // Reset to search mode
        resetToSearchMode()

        showToast("Đã dừng di chuyển")
    }

    private fun updateSetLocationButton() {
        // Update icon based on local GPS state
        if (isGpsSet) {
            // GPS is set -> show stop icon (to unset)
            binding.setLocationButton.setIconResource(R.drawable.ic_stop)
            // Show getFakeLocation button when GPS is set
            binding.getFakeLocation.visibility = View.VISIBLE
        } else {
            // GPS is not set -> show play icon (to set)
            binding.setLocationButton.setIconResource(R.drawable.ic_play)
            // Hide getFakeLocation button when GPS is not set
            binding.getFakeLocation.visibility = View.GONE
        }
    }

    private fun updateFakeLocationMarker(position: LatLng) {
        // Remove old marker and circle if exists
        fakeLocationMarker?.remove()
        fakeLocationCircle?.remove()

        // Only use circle to mark fake GPS location (no marker to avoid bitmap issues)
        fakeLocationCircle = mMap.addCircle(
            CircleOptions()
                .center(position)
                .radius(15.0) // 15 meters radius
                .strokeColor(0xFF00BCD4.toInt()) // Cyan stroke
                .fillColor(0x5000BCD4.toInt()) // Semi-transparent cyan fill
                .strokeWidth(3f)
                .zIndex(100f) // Keep on top
        )
    }

    private fun updateCompletedPath(currentPosition: LatLng) {
        // Remove old completed polyline
        completedPolyline?.remove()

        // Find closest point on route to current position
        var closestIndex = 0
        var minDistance = Double.MAX_VALUE

        for (i in routePoints.indices) {
            val distance = distanceBetween(currentPosition, routePoints[i])
            if (distance < minDistance) {
                minDistance = distance
                closestIndex = i
            }
        }

        // Create gray polyline for completed path
        if (closestIndex > 0) {
            val completedPoints = routePoints.subList(0, closestIndex + 1).toMutableList()
            completedPoints.add(currentPosition)

            completedPolyline = mMap.addPolyline(
                PolylineOptions()
                    .addAll(completedPoints)
                    .color(android.graphics.Color.GRAY)
                    .width(10f)
            )
        }
    }

    private fun distanceBetween(p1: LatLng, p2: LatLng): Double {
        val lat1 = Math.toRadians(p1.latitude)
        val lon1 = Math.toRadians(p1.longitude)
        val lat2 = Math.toRadians(p2.latitude)
        val lon2 = Math.toRadians(p2.longitude)

        val dLat = lat2 - lat1
        val dLon = lon2 - lon1

        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(lat1) * Math.cos(lat2) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))

        return 6371000 * c // Return distance in meters
    }

    private fun onNavigationComplete() {
        isDriving = false
        isPaused = false

        // Hide navigation controls
        binding.navigationControlsCard.visibility = View.GONE

        // Remove current position circle
        fakeLocationCircle?.remove()
        fakeLocationCircle = null

        // Keep GPS at destination and show fake location marker
        val destinationPos = routePoints.lastOrNull() ?: destMarker?.position
        if (destinationPos != null) {
            isGpsSet = true
            currentFakeLocationPos = destinationPos
            viewModel.update(true, destinationPos.latitude, destinationPos.longitude)
            updateFakeLocationMarker(destinationPos)
            updateSetLocationButton()
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Đã đến đích")
            .setMessage("GPS đã được set tại điểm đến. Bạn muốn làm gì?")
            .setPositiveButton("Tìm đường mới") { _, _ ->
                // Remove circle when finding new route
                fakeLocationCircle?.remove()
                fakeLocationCircle = null
                // Reset UI but keep GPS set
                resetToSearchMode()
            }
            .setNegativeButton("Ở lại đây") { _, _ ->
                // Keep GPS at destination and keep circle visible
                // Just close dialog and show search UI
                binding.searchCard.visibility = View.VISIBLE
                binding.navigationControlsCard.visibility = View.GONE
            }
            .setCancelable(false)
            .show()
    }

    private fun resetToSearchMode() {
        // Clear all markers and routes
        destMarker?.remove()
        destMarker = null
        startMarker?.remove()
        startMarker = null
        routePolyline?.remove()
        routePolyline = null
        completedPolyline?.remove()
        completedPolyline = null
        currentPositionMarker?.remove()
        currentPositionMarker = null
        fakeLocationCircle?.remove()
        fakeLocationCircle = null
        fakeLocationMarker?.remove()
        fakeLocationMarker = null
        routePoints = emptyList()

        // Reset UI
        currentMode = AppMode.SEARCH
        binding.actionButton.visibility = View.GONE
        binding.navigationControlsCard.visibility = View.GONE
        binding.startSearchContainer.visibility = View.GONE
        binding.useCurrentLocationContainer.visibility = View.GONE
        binding.destinationSearch.text.clear()
        binding.startSearch.text.clear()
        binding.searchCard.visibility = View.VISIBLE

        routeSimulator?.stop()
        routeSimulator = null
        isPaused = false
    }

    private fun restoreFakeLocationMarker() {
        // Check if fake location was set in previous session
        if (viewModel.isStarted) {
            val lat = viewModel.getLat
            val lng = viewModel.getLng
            
            if (lat != 0.0 && lng != 0.0) {
                val position = LatLng(lat, lng)
                isGpsSet = true
                currentFakeLocationPos = position
                
                // Show circle marker at saved position
                updateFakeLocationMarker(position)
                updateSetLocationButton()
                
                android.util.Log.d("MapActivity", "Restored fake location from previous session: $lat, $lng")
                showToast("Đánh dấu lại vị trí GPS lần trước: ${String.format("%.4f", lat)}, ${String.format("%.4f", lng)}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        routeSimulator?.stop()
        searchJob?.cancel()
    }

    // Marker Drag Listener implementations
    override fun onMarkerDrag(marker: Marker) {
        // Don't update route while dragging to reduce server load
        // Route will be updated when drag ends
    }

    override fun onMarkerDragEnd(marker: Marker) {
        // Update route only when drag ends to reduce server calls
        if (currentMode == AppMode.ROUTE_PLAN && startMarker != null && destMarker != null) {
            drawRoute()
        }

        // Show final position
        when (marker) {
            destMarker -> {
                showToast("Điểm đến cập nhật tại ${String.format("%.4f", marker.position.latitude)}, ${String.format("%.4f", marker.position.longitude)}")
            }
            startMarker -> {
                showToast("Điểm bắt đầu cập nhật tại ${String.format("%.4f", marker.position.latitude)}, ${String.format("%.4f", marker.position.longitude)}")
            }
        }
    }

    override fun onMarkerDragStart(marker: Marker) {
        // Add vibration feedback when drag starts
        val vibrator = getSystemService(android.content.Context.VIBRATOR_SERVICE) as Vibrator
        if (vibrator.hasVibrator()) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                // Vibrate for 100ms with amplitude 200
                vibrator.vibrate(VibrationEffect.createOneShot(100, 200))
            } else {
                // For older Android versions
                @Suppress("DEPRECATION")
                vibrator.vibrate(100)
            }
        }
    }
}