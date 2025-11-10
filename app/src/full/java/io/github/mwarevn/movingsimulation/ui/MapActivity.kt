package io.github.mwarevn.movingsimulation.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.Geocoder
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
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

    // ========== Constants & Configuration ==========
    companion object {
        // Permission request codes
        private const val LOCATION_PERMISSION_REQUEST_CODE = 99

        // Route colors
        const val ROUTE_COLOR = "#006eff" // Blue route line
        const val ROUTE_WIDTH = 18f // Route line width
        const val COMPLETED_ROUTE_COLOR = "#909090" // Gray completed path

        // Fake location (stationary) circle colors - Orange Red
        const val FAKE_LOCATION_STROKE_COLOR = 0xFFFF4500.toInt() // Orange Red stroke (solid)
        const val FAKE_LOCATION_FILL_COLOR = 0x50FF4500.toInt()   // Semi-transparent orange red fill
        const val FAKE_LOCATION_CENTER_COLOR = 0xFFFF4500.toInt() // Solid orange red center dot

        // Circle dimensions
        const val CIRCLE_RADIUS = 20.0              // Outer circle radius in meters
        const val CIRCLE_STROKE_WIDTH = 3f          // Outer circle border width
        const val CIRCLE_Z_INDEX = 128f             // Outer circle Z-index
        const val CENTER_DOT_Z_INDEX = 129f         // Center dot Z-index (higher = on top)
    }

    // Map & Markers
    private lateinit var mMap: GoogleMap
    private var destMarker: Marker? = null
    private var startMarker: Marker? = null
    private var routePolyline: Polyline? = null

    // Center dot marker for fake location circle
    private var fakeLocationCenterDot: Marker? = null

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
    // Accumulated points for the completed (traversed) route to keep it aligned with the moving circle
    private val completedPathPoints: MutableList<LatLng> = mutableListOf()
    private var currentPositionIndex = 0
    private var isGpsSet = false // Track GPS state locally for immediate UI update
    private var currentFakeLocationPos: LatLng? = null // Store current fake location for quick use

    // Camera follow throttling
    private var lastCameraUpdateTime = 0L
    private val CAMERA_UPDATE_INTERVAL_MS = 500L

    // Camera follow mode (only relevant during navigation)
    private var isCameraFollowing = true

    // GPS stability improvements
    private var lastGpsUpdateTime = 0L
    private var previousLocation: LatLng? = null
    private var currentNavigationPosition: LatLng? = null // Track current position during navigation

    // Background state management
    private var wasNavigatingBeforeBackground = false
    private var backgroundNavigationState: NavigationState? = null

    data class NavigationState(
        val routePoints: List<LatLng>,
        val currentSpeed: Double,
        val isPaused: Boolean,
        val currentPositionIndex: Int,
        val completedPathPoints: List<LatLng>
    )

    // UI State
    private enum class AppMode {
        SEARCH,      // Initial: searching for destination
        ROUTE_PLAN,  // Planning route: selecting start point
        NAVIGATION   // Navigating along route
    }
    private var currentMode = AppMode.SEARCH
    private var hasSelectedStartPoint = false // Track if start point was clicked (not dragged)

    // Search jobs
    private var searchJob: Job? = null

    // Route loading state
    private var isLoadingRoute = false
    private var routeLoadError: String? = null

    /**
     * Create a circular bitmap icon for center dot that maintains size at all zoom levels
     * Similar to Google Maps real location icon with white border
     */
    private fun createCenterDotIcon(color: Int, sizePx: Int = 36): BitmapDescriptor {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = sizePx / 2f

        // Draw white border (outer circle)
        val borderPaint = Paint().apply {
            this.color = android.graphics.Color.WHITE
            isAntiAlias = true
        }
        canvas.drawCircle(radius, radius, radius, borderPaint)

        // Draw colored center (inner circle)
        val centerPaint = Paint().apply {
            this.color = color
            isAntiAlias = true
        }
        val innerRadius = radius * 0.75f // 75% of outer radius
        canvas.drawCircle(radius, radius, innerRadius, centerPaint)

        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    /**
     * Calculate bearing between two lat/lng points
     * Returns bearing in degrees (0-360)
     */
    private fun calculateBearing(start: LatLng, end: LatLng): Float {
        val lat1 = Math.toRadians(start.latitude)
        val lat2 = Math.toRadians(end.latitude)
        val deltaLng = Math.toRadians(end.longitude - start.longitude)

        val y = Math.sin(deltaLng) * Math.cos(lat2)
        val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(deltaLng)

        var bearing = Math.toDegrees(Math.atan2(y, x))
        if (bearing < 0) {
            bearing += 360.0
        }
        return bearing.toFloat()
    }

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
            isCompassEnabled = false  // Enable built-in compass
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

    override fun onMapClick(position: LatLng) {
        when (currentMode) {
            AppMode.SEARCH -> {
                // Always allow clicking to set/change destination in SEARCH mode
                setDestinationMarker(position)
                if (destMarker != null) {
                    // showToast("Đã chọn điểm đến. Nhấn 'Chỉ đường' để lên kế hoạch route")
                }
            }
            AppMode.ROUTE_PLAN -> {
                if (!hasSelectedStartPoint && destMarker != null) {
                    // First click in PLAN mode - set start point
                    setStartMarkerWithSelection(position)
                    showToast("Đã chọn điểm bắt đầu. Kéo thả để điều chỉnh chính xác, sau đó nhấn 'Bắt đầu di chuyển'")
                } else if (hasSelectedStartPoint) {
                    // After first click - only allow drag/drop for fine-tuning
                    showToast("Kéo thả điểm đánh dấu để điều chỉnh vị trí chính xác")
                } else {
                    showToast("Vui lòng chọn điểm đến trước, rồi nhấn 'Chỉ đường'")
                }
            }
            AppMode.NAVIGATION -> {
                // Prevent any map interactions during navigation
                showToast("Không thể thay đổi điểm đến khi đang di chuyển")
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
                            setStartMarkerWithSelection(latLng)
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
                        setStartMarkerWithSelection(latLng)
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
                    // "Chỉ đường" button: enter route planning mode
                    if (destMarker != null) {
                        enterRoutePlanMode()
                    } else {
                        showToast("Vui lòng chọn điểm đến trước")
                    }
                }
                AppMode.ROUTE_PLAN -> {
                    if (startMarker != null && destMarker != null) {
                        // Both markers set - start navigation
                        startNavigation()
                    } else if (destMarker != null && startMarker == null) {
                        // Only destination set - remind to pick start point
                        showToast("Vui lòng chọn điểm bắt đầu trên bản đồ")
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
        }

        // Camera Follow Toggle Button (only works during navigation)
        binding.cameraFollowToggle.setOnClickListener {
            if (isDriving) {
                isCameraFollowing = !isCameraFollowing
                updateCameraFollowButton()
//                if (isCameraFollowing) {
//                    showToast("Camera bám theo vị trí")
//                } else {
//                    showToast("Camera tự do")
//                }
            }
        }

        // Speed slider control
        binding.speedSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val newSpeed = value.toDouble()
                // Prevent 0 speed during navigation to avoid stopping
                val actualSpeed = if (isDriving && newSpeed <= 0) 1.0 else newSpeed

                currentSpeed = actualSpeed
                binding.speedLabel.text = "Tốc độ: ${actualSpeed.toInt()} km/h"

                android.util.Log.d("MapActivity", "Speed changed to: $actualSpeed km/h (isDriving: $isDriving, isPaused: $isPaused)")

                // Update speed in real-time if driving and not paused
                if (isDriving && !isPaused && routeSimulator != null) {
                    routeSimulator?.setSpeedKmh(actualSpeed)
                    android.util.Log.d("MapActivity", "RouteSimulator speed updated to: $actualSpeed km/h")
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
                binding.stopButton.visibility = View.VISIBLE
                // showToast("Đã tạm dừng")
            }
        }

        // Resume button
        binding.resumeButton.setOnClickListener {
            if (isDriving && isPaused) {
                isPaused = false
                routeSimulator?.resume()
                // Ensure current speed is applied when resuming
                routeSimulator?.setSpeedKmh(currentSpeed)
                binding.pauseButton.visibility = View.VISIBLE
                binding.resumeButton.visibility = View.GONE
                binding.stopButton.visibility = View.GONE
                // showToast("Tiếp tục di chuyển")
            }
        }

        // Stop button (shown when paused)
        binding.stopButton.setOnClickListener {
            if (isDriving && isPaused) {
                onStopNavigationEarly()
            }
        }

        // Completion action buttons
        binding.completedFinishButton.setOnClickListener {
            // Finish: clear route and markers, keep fake location circle
            onFinishNavigation()
        }

        binding.completedRestartButton.setOnClickListener {
            // Restart: resume navigation from current position
            onRestartNavigation()
        }

        // Quick use current fake location as start point
        binding.useCurrentLocationContainer.setOnClickListener {
            if (currentFakeLocationPos != null) {
                setStartMarkerWithSelection(currentFakeLocationPos!!)
                binding.useCurrentLocationContainer.visibility = View.GONE
                // showToast("Đã chọn vị trí hiện tại làm điểm bắt đầu")
            }
        }

        // Route error retry button
        binding.routeRetryButton.setOnClickListener {
            retryDrawRoute()
        }

        // Route error cancel button
        binding.routeErrorCancelButton.setOnClickListener {
            cancelRoutePlan()
        }

        // Cancel route button (next to start button)
        binding.cancelRouteButton.setOnClickListener {
            cancelRoutePlan()
        }

        android.util.Log.d("MapActivity", "========== setupButtons() END ==========")
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

        // Add new marker (not draggable during navigation)
        destMarker = mMap.addMarker(
            MarkerOptions()
                .position(position)
                .title("Điểm đến")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                .draggable(currentMode != AppMode.NAVIGATION) // Disable dragging during navigation
        )

        // Move camera
        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(position, 16f))

        // Show "Chỉ đường" button
        binding.actionButton.apply {
            text = "Chỉ đường"
            visibility = View.VISIBLE
            setIconResource(R.drawable.ic_baseline_directions_24)
        }

        // Stay in SEARCH mode - only "Chỉ đường" button switches to PLAN mode
        // This allows user to keep clicking to change destination

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

        // Add new marker (not draggable during navigation)
        startMarker = mMap.addMarker(
            MarkerOptions()
                .position(position)
                .title("Điểm bắt đầu")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                .draggable(currentMode != AppMode.NAVIGATION) // Disable dragging during navigation
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

    /**
     * Set start marker and mark that user has selected start point
     */
    private fun setStartMarkerWithSelection(position: LatLng) {
        setStartMarker(position)
        hasSelectedStartPoint = true
    }

    private fun enterRoutePlanMode() {
        currentMode = AppMode.ROUTE_PLAN
        hasSelectedStartPoint = false // Reset start point selection state

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

    /**
     * Update marker draggable state based on current mode
     */
    private fun updateMarkersDraggableState() {
        val isDraggable = currentMode != AppMode.NAVIGATION

        destMarker?.let { marker ->
            // Remove and re-add marker with new draggable state
            val position = marker.position
            marker.remove()
            destMarker = mMap.addMarker(
                MarkerOptions()
                    .position(position)
                    .title("Điểm đến")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                    .draggable(isDraggable)
            )
        }

        startMarker?.let { marker ->
            // Remove and re-add marker with new draggable state
            val position = marker.position
            marker.remove()
            startMarker = mMap.addMarker(
                MarkerOptions()
                    .position(position)
                    .title("Điểm bắt đầu")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                    .draggable(isDraggable)
            )
        }
    }

    private fun drawRoute() {
        if (startMarker == null || destMarker == null) return

        // Show loading state
        isLoadingRoute = true
        routeLoadError = null
        binding.actionButton.apply {
            visibility = View.GONE
        }
        binding.routeLoadingCard.visibility = View.VISIBLE
        binding.routeLoadingProgressText.text = "Đang tìm tuyến đường..."

        lifecycleScope.launch {
            try {
                val start = startMarker!!.position
                val dest = destMarker!!.position

                // Call OSRM API
                val coords = "${start.longitude},${start.latitude};${dest.longitude},${dest.latitude}"
                val route = OsrmClient.service.getRoute(coords)

                if (route.routes.isNullOrEmpty() || route.routes[0].geometry == null) {
                    // Handle error
                    routeLoadError = "Không tìm thấy đường đi"
                    isLoadingRoute = false
                    showRouteErrorUI()
                    return@launch
                }

                val geometry = route.routes[0].geometry!!
                routePoints = PolylineUtils.decode(geometry)

                // Draw polyline
                routePolyline?.remove()
                routePolyline = mMap.addPolyline(
                    PolylineOptions()
                        .addAll(routePoints)
                        .color(Color.parseColor(ROUTE_COLOR))
                        .width(ROUTE_WIDTH)
                )

                // Hide loading, show action button
                isLoadingRoute = false
                routeLoadError = null
                binding.routeLoadingCard.visibility = View.GONE

                // Update button to "Bắt đầu" with cancel button
                binding.actionButton.apply {
                    text = "Bắt đầu"
                    setIconResource(R.drawable.ic_play)
                    visibility = View.VISIBLE
                }
                binding.cancelRouteButton.visibility = View.VISIBLE

                // showToast("Đường đi đã sẵn sàng. Nhấn Bắt đầu để di chuyển")

            } catch (e: Exception) {
                // Handle error
                routeLoadError = "Lỗi vẽ đường: ${e.message}"
                isLoadingRoute = false
                showRouteErrorUI()
            }
        }
    }

    private fun showRouteErrorUI() {
        binding.routeLoadingCard.visibility = View.GONE
        binding.routeErrorCard.visibility = View.VISIBLE
        binding.routeErrorText.text = routeLoadError ?: "Không xác định lỗi"
        binding.actionButton.visibility = View.GONE
        binding.cancelRouteButton.visibility = View.GONE
    }

    private fun hideRouteErrorUI() {
        binding.routeErrorCard.visibility = View.GONE
        routeLoadError = null
    }

    private fun retryDrawRoute() {
        hideRouteErrorUI()
        drawRoute()
    }

    private fun cancelRoutePlan() {
        // Stop loading if in progress
        isLoadingRoute = false
        routeLoadError = null

        // Hide error and loading cards
        binding.routeLoadingCard.visibility = View.GONE
        binding.routeErrorCard.visibility = View.GONE

        // Clear route
        routePolyline?.remove()
        routePolyline = null
        routePoints = emptyList()

        // Clear start marker but keep destination marker
        startMarker?.remove()
        startMarker = null

        // Reset to search mode
        currentMode = AppMode.SEARCH
        hasSelectedStartPoint = false

        // Hide search containers
        binding.startSearchContainer.visibility = View.GONE
        binding.useCurrentLocationContainer.visibility = View.GONE

        // Show "Chỉ đường" button again with destination marker still visible
        binding.actionButton.apply {
            text = "Chỉ đường"
            setIconResource(R.drawable.ic_baseline_directions_24)
            visibility = View.VISIBLE
        }

        // Hide cancel route button
        binding.cancelRouteButton.visibility = View.GONE

        // Show search card
        binding.searchCard.visibility = View.VISIBLE

        // showToast("Đã huỷ chỉ đường")
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
        // Reset completed path accumulation at the start of navigation
        completedPathPoints.clear()
        completedPathPoints.add(routePoints.first())

        // Update markers to be non-draggable during navigation
        updateMarkersDraggableState()

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
        binding.stopButton.visibility = View.GONE

        // Set initial speed and update label
        currentSpeed = binding.speedSlider.value.toDouble()
        binding.speedLabel.text = "Tốc độ: ${currentSpeed.toInt()} km/h"

        android.util.Log.d("MapActivity", "Navigation started with speed: $currentSpeed km/h")

        // Hide search inputs
        binding.searchCard.visibility = View.GONE

        // Show camera follow toggle and set to follow mode by default
        isCameraFollowing = true
        binding.cameraFollowToggle.visibility = View.VISIBLE
        updateCameraFollowButton()

        // Hide getFakeLocation button during navigation (user has camera toggle instead)
        binding.getFakeLocation.visibility = View.GONE

        // Hide set/unset location button during navigation
        binding.setLocationButton.visibility = View.GONE

        // Create fake location circle to show current GPS position during navigation
        val startPos = routePoints.first()
        fakeLocationCircle = createStationaryLocationCircle(startPos)

        // Start route simulator with GlobalScope for background execution
        routeSimulator = RouteSimulator(
            points = routePoints,
            speedKmh = currentSpeed,
            updateIntervalMs = 250L, // Optimal balance for smooth + stable GPS
            scope = GlobalScope // Use GlobalScope to continue running in background
        )

       // showToast("🏍️ Bắt đầu di chuyển với tốc độ ${currentSpeed.toInt()} km/h")

        routeSimulator?.start(
            onPosition = { position ->
                runOnUiThread {
                    val currentTime = System.currentTimeMillis()

                    // Calculate bearing for GPS metadata
                    val bearing = previousLocation?.let { prev ->
                        calculateBearing(prev, position)
                    } ?: 0f

                    // Update GPS location - variable timing approach for anti-detection
                    viewModel.update(
                        start = true,
                        la = position.latitude,
                        ln = position.longitude
                    )

                    // Log GPS data with timing variance for debugging
                    val timeDiff = if (lastGpsUpdateTime > 0) currentTime - lastGpsUpdateTime else 0
                    android.util.Log.d("GPS_AntiDetect", "GPS: lat=${position.latitude}, lng=${position.longitude}, bearing=${bearing}°, interval=${timeDiff}ms")
                    lastGpsUpdateTime = currentTime

                    // Track current navigation position for pause/stop functionality
                    currentNavigationPosition = position

                    // Update fake location circle to show current GPS position
                    fakeLocationCircle?.center = position
                    fakeLocationCenterDot?.position = position
                    // Let Google Maps blue dot handle position visualization

                    // Update completed path (gray polyline)
                    updateCompletedPath(position)

                    // Move camera to follow with throttling (only if follow mode is enabled)
                    if (isCameraFollowing) {
                        if (currentTime - lastCameraUpdateTime >= CAMERA_UPDATE_INTERVAL_MS) {
                            mMap.animateCamera(CameraUpdateFactory.newLatLng(position))
                            lastCameraUpdateTime = currentTime
                        }
                    }

                    // Store current position for next bearing calculation
                    previousLocation = position
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
        fakeLocationCenterDot?.remove()
        fakeLocationCenterDot = null

    // Remove completed path
        completedPolyline?.remove()
        completedPolyline = null
    completedPathPoints.clear()

        // Show search again and hide navigation controls
        binding.searchCard.visibility = View.VISIBLE
        binding.navigationControlsCard.visibility = View.GONE
        binding.cameraFollowToggle.visibility = View.GONE

        // Restore getFakeLocation button visibility if GPS is set
        if (isGpsSet) {
            binding.getFakeLocation.visibility = View.VISIBLE
        }

        // Restore set/unset location button visibility
        binding.setLocationButton.visibility = View.VISIBLE

        binding.actionButton.visibility = View.VISIBLE
        binding.actionButton.apply {
            text = "Dừng"
            setIconResource(R.drawable.ic_stop)
        }

        // Reset to search mode
        resetToSearchMode()

        // showToast("Đã dừng di chuyển")
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
        fakeLocationCenterDot?.remove()

        // Create stationary location circle (cyan with center dot)
        fakeLocationCircle = createStationaryLocationCircle(position)
    }

    private fun updateCompletedPath(currentPosition: LatLng) {
        // Append current position if moved at least 2.5m from last stored point to reduce vertices on long routes
        val last = completedPathPoints.lastOrNull()
        if (last == null || distanceBetween(last, currentPosition) >= 2.5) {
            completedPathPoints.add(currentPosition)
        }

        // Draw or update polyline incrementally
        if (completedPathPoints.size > 1) {
            if (completedPolyline == null) {
                completedPolyline = mMap.addPolyline(
                    PolylineOptions()
                        .addAll(completedPathPoints)
                        .color(Color.parseColor(COMPLETED_ROUTE_COLOR))
                        .width(ROUTE_WIDTH + 1)
                )
            } else {
                completedPolyline?.points = ArrayList(completedPathPoints)
            }
        }
    }

    private fun updateCameraFollowButton() {
        if (isCameraFollowing) {
            // Show camera icon when following
            binding.cameraFollowToggle.setIconResource(android.R.drawable.ic_menu_camera)
        } else {
            // Show free move icon when free
            binding.cameraFollowToggle.setIconResource(android.R.drawable.ic_menu_gallery)
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

    /**
     * Helper function to create a fake location circle marker with center dot
     * @param center The center position of the circle
     * @param strokeColor Stroke color
     * @param fillColor Fill color
     * @param centerColor Center dot color
     * @param radius Radius in meters (default: from companion object)
     * @param showCenterDot Whether to show the center dot (default: true)
     */
    private fun createFakeLocationCircle(
        center: LatLng,
        strokeColor: Int,
        fillColor: Int,
        centerColor: Int,
        radius: Double = CIRCLE_RADIUS,
        showCenterDot: Boolean = true
    ): Circle {
        // Remove old center dot if exists
        fakeLocationCenterDot?.remove()

        // Create center dot marker if requested (maintains size at all zoom levels)
        if (showCenterDot) {
            fakeLocationCenterDot = mMap.addMarker(
                MarkerOptions()
                    .position(center)
                    .icon(createCenterDotIcon(centerColor))
                    .anchor(0.5f, 0.5f) // Center the icon on the position
                    .zIndex(CENTER_DOT_Z_INDEX)
            )
        }

        // Create outer circle
        return mMap.addCircle(
            CircleOptions()
                .center(center)
                .radius(radius)
                .strokeColor(strokeColor)
                .fillColor(fillColor)
                .strokeWidth(CIRCLE_STROKE_WIDTH)
                .zIndex(CIRCLE_Z_INDEX)
        )
    }

    /**
     * Helper: Create stationary fake location circle (Orange Red)
     */
    private fun createStationaryLocationCircle(center: LatLng): Circle {
        return createFakeLocationCircle(
            center = center,
            strokeColor = FAKE_LOCATION_STROKE_COLOR,
            fillColor = FAKE_LOCATION_FILL_COLOR,
            centerColor = FAKE_LOCATION_CENTER_COLOR
        )
    }

    private fun onNavigationComplete() {
        isDriving = false
        isPaused = false

        // Hide navigation controls
        binding.navigationControlsCard.visibility = View.GONE
        binding.cameraFollowToggle.visibility = View.GONE

        // Restore getFakeLocation button since navigation completed
        binding.getFakeLocation.visibility = View.VISIBLE

        // Restore set/unset location button since navigation completed
        binding.setLocationButton.visibility = View.VISIBLE

        // Remove current position circle (but will show completion actions)
        fakeLocationCircle?.remove()
        fakeLocationCircle = null
        fakeLocationCenterDot?.remove()
        fakeLocationCenterDot = null

        // Clear marker titles
        startMarker?.title = ""
        destMarker?.title = ""

        // Keep GPS at destination and show fake location marker
        val destinationPos = routePoints.lastOrNull() ?: destMarker?.position
        if (destinationPos != null) {
            isGpsSet = true
            currentFakeLocationPos = destinationPos
            viewModel.update(true, destinationPos.latitude, destinationPos.longitude)
            updateFakeLocationMarker(destinationPos)
            updateSetLocationButton()
        }

        // Show completion action bar instead of dialog
        binding.completionActionsCard.visibility = View.VISIBLE
        // showToast("Đã đến đích!")
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
        completedPathPoints.clear()
        currentPositionMarker?.remove()
        currentPositionMarker = null
        fakeLocationCircle?.remove()
        fakeLocationCircle = null
        fakeLocationCenterDot?.remove()
        fakeLocationCenterDot = null
        fakeLocationMarker?.remove()
        fakeLocationMarker = null
        routePoints = emptyList()

        // Reset UI
        currentMode = AppMode.SEARCH
        hasSelectedStartPoint = false // Reset start point selection state

        // Update markers to be draggable again (though cleared above)
        updateMarkersDraggableState()

        binding.actionButton.visibility = View.GONE
        binding.navigationControlsCard.visibility = View.GONE
        binding.cameraFollowToggle.visibility = View.GONE
        binding.startSearchContainer.visibility = View.GONE
        binding.useCurrentLocationContainer.visibility = View.GONE
        binding.destinationSearch.text.clear()
        binding.startSearch.text.clear()
        binding.searchCard.visibility = View.VISIBLE

        // Restore getFakeLocation button visibility based on GPS state
        binding.getFakeLocation.visibility = if (isGpsSet) View.VISIBLE else View.GONE

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
                // showToast("Đánh dấu lại vị trí GPS lần trước: ${String.format("%.4f", lat)}, ${String.format("%.4f", lng)}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        routeSimulator?.stop()
        searchJob?.cancel()
    }

    override fun onPause() {
        super.onPause()
        // Save navigation state when going to background
        if (isDriving) {
            wasNavigatingBeforeBackground = true
            backgroundNavigationState = NavigationState(
                routePoints = routePoints,
                currentSpeed = currentSpeed,
                isPaused = isPaused,
                currentPositionIndex = currentPositionIndex,
                completedPathPoints = ArrayList(completedPathPoints)
            )
            android.util.Log.d("MapActivity", "Saved navigation state to background")
        }
        // Don't pause route simulator when going to background during navigation
        // Let it continue running for seamless background GPS simulation
    }

    override fun onResume() {
        super.onResume()
        // Restore navigation state when returning from background
        if (wasNavigatingBeforeBackground && backgroundNavigationState != null) {
            val savedState = backgroundNavigationState!!

            // Restore navigation variables
            routePoints = savedState.routePoints
            currentSpeed = savedState.currentSpeed
            isPaused = savedState.isPaused
            currentPositionIndex = savedState.currentPositionIndex
            completedPathPoints.clear()
            completedPathPoints.addAll(savedState.completedPathPoints)

            // Ensure UI is in sync with current navigation state
            updateNavigationUI()
            android.util.Log.d("MapActivity", "Restored navigation state from background")

            // Clear the saved state
            wasNavigatingBeforeBackground = false
            backgroundNavigationState = null
        } else if (isDriving && !isPaused) {
            // Regular UI refresh for ongoing navigation
            updateNavigationUI()
        }
    }

    /**
     * Update navigation UI when returning from background
     */
    private fun updateNavigationUI() {
        if (isDriving) {
            // Ensure navigation controls are visible
            binding.navigationControlsCard.visibility = View.VISIBLE
            binding.searchCard.visibility = View.GONE
            binding.cameraFollowToggle.visibility = View.VISIBLE
            binding.setLocationButton.visibility = View.GONE
            binding.getFakeLocation.visibility = View.GONE

            // Update pause/resume button states
            if (isPaused) {
                binding.pauseButton.visibility = View.GONE
                binding.resumeButton.visibility = View.VISIBLE
                binding.stopButton.visibility = View.VISIBLE
            } else {
                binding.pauseButton.visibility = View.VISIBLE
                binding.resumeButton.visibility = View.GONE
                binding.stopButton.visibility = View.GONE
            }

            // Update speed label
            binding.speedLabel.text = "Tốc độ: ${currentSpeed.toInt()} km/h"

            // Restore fake location circle if missing
            if (fakeLocationCircle == null && currentNavigationPosition != null) {
                fakeLocationCircle = createStationaryLocationCircle(currentNavigationPosition!!)
                android.util.Log.d("MapActivity", "Restored fake location circle at: ${currentNavigationPosition!!.latitude}, ${currentNavigationPosition!!.longitude}")
            }

            // Restore completed path polyline if missing
            if (completedPolyline == null && completedPathPoints.size > 1) {
                completedPolyline = mMap.addPolyline(
                    PolylineOptions()
                        .addAll(completedPathPoints)
                        .color(Color.parseColor(COMPLETED_ROUTE_COLOR))
                        .width(ROUTE_WIDTH + 1)
                )
                android.util.Log.d("MapActivity", "Restored completed path polyline")
            }
        }
    }

    // Marker Drag Listener implementations
    override fun onMarkerDrag(marker: Marker) {
        // Prevent dragging during navigation
        if (currentMode == AppMode.NAVIGATION) {
            return
        }
        // Don't update route while dragging to reduce server load
        // Route will be updated when drag ends
    }

    override fun onMarkerDragEnd(marker: Marker) {
        // Prevent dragging during navigation
        if (currentMode == AppMode.NAVIGATION) {
            showToast("Không thể thay đổi điểm đến khi đang di chuyển")
            return
        }

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
        // Prevent dragging during navigation
        if (currentMode == AppMode.NAVIGATION) {
            showToast("Không thể thay đổi điểm đến khi đang di chuyển")
            return
        }

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

    private fun onFinishNavigation() {
        // Hide completion action bar
        binding.completionActionsCard.visibility = View.GONE

        // Clear route and route-related markers, but keep fake location circle and marker
        routePolyline?.remove()
        routePolyline = null
        completedPolyline?.remove()
        completedPolyline = null
    completedPathPoints.clear()
        currentPositionMarker?.remove()
        currentPositionMarker = null
        startMarker?.remove()
        startMarker = null
        destMarker?.remove()
        destMarker = null
        routePoints = emptyList()

        // Clear search text
        binding.destinationSearch.text.clear()
        binding.startSearch.text.clear()

        // Reset UI to search mode but keep GPS set and fake location marker
        currentMode = AppMode.SEARCH
        binding.actionButton.visibility = View.GONE
        binding.navigationControlsCard.visibility = View.GONE
        binding.cameraFollowToggle.visibility = View.GONE
        binding.startSearchContainer.visibility = View.GONE
        binding.useCurrentLocationContainer.visibility = View.GONE
        binding.searchCard.visibility = View.VISIBLE

        // Restore getFakeLocation button since GPS is still set at destination
        binding.getFakeLocation.visibility = View.VISIBLE

        // Stop the route simulator
        routeSimulator?.stop()

        showToast("Đã hoàn tất. GPS vẫn được set tại vị trí hiện tại")
    }

    private fun onRestartNavigation() {
        // Hide completion action bar
        binding.completionActionsCard.visibility = View.GONE

        // Reset navigation state to allow restart
        isDriving = false
        isPaused = false
    currentPositionIndex = 0
    completedPathPoints.clear()

        // Clear old circle and center dot before restarting
        fakeLocationCircle?.remove()
        fakeLocationCircle = null
        fakeLocationCenterDot?.remove()
        fakeLocationCenterDot = null

        // Re-create route simulator with same route
        if (routePoints.isNotEmpty()) {
            // Show navigation controls again
            binding.navigationControlsCard.visibility = View.VISIBLE
            binding.pauseButton.visibility = View.VISIBLE
            binding.resumeButton.visibility = View.GONE
            binding.stopButton.visibility = View.GONE

            // Show camera follow toggle and reset to follow mode
            isCameraFollowing = true
            binding.cameraFollowToggle.visibility = View.VISIBLE
            updateCameraFollowButton()

            // Hide getFakeLocation button during navigation restart
            binding.getFakeLocation.visibility = View.GONE

            // Ensure speed label shows current speed
            binding.speedLabel.text = "Tốc độ: ${currentSpeed.toInt()} km/h"

            // Create fake location circle at start point to show GPS position
            val startPos = routePoints.firstOrNull()
            if (startPos != null) {
                fakeLocationCircle = createStationaryLocationCircle(startPos)
            }

            // Restart route simulator with GlobalScope for background execution
            routeSimulator = RouteSimulator(
                points = routePoints,
                speedKmh = currentSpeed,
                updateIntervalMs = 250L, // Optimal balance for smooth + stable GPS
                scope = GlobalScope // Use GlobalScope to continue running in background
            )

            routeSimulator?.start(
                onPosition = { position ->
                    runOnUiThread {
                        val currentTime = System.currentTimeMillis()

                        // Calculate bearing for GPS metadata
                        val bearing = previousLocation?.let { prev ->
                            calculateBearing(prev, position)
                        } ?: 0f

                        // Update GPS location - variable timing approach for anti-detection
                        viewModel.update(
                            start = true,
                            la = position.latitude,
                            ln = position.longitude
                        )

                        // Log GPS data with timing variance for debugging
                        val timeDiff = if (lastGpsUpdateTime > 0) currentTime - lastGpsUpdateTime else 0
                        android.util.Log.d("GPS_AntiDetect", "GPS: lat=${position.latitude}, lng=${position.longitude}, bearing=${bearing}°, interval=${timeDiff}ms")
                        lastGpsUpdateTime = currentTime

                        // Track current navigation position for pause/stop functionality
                        currentNavigationPosition = position

                        // Update fake location circle to show current GPS position
                        fakeLocationCircle?.center = position
                        fakeLocationCenterDot?.position = position
                        // Let Google Maps blue dot handle position visualization

                        // Update completed path (gray polyline)
                        updateCompletedPath(position)

                        // Move camera to follow with throttling (only if follow mode is enabled)
                        if (isCameraFollowing) {
                            if (currentTime - lastCameraUpdateTime >= CAMERA_UPDATE_INTERVAL_MS) {
                                mMap.animateCamera(CameraUpdateFactory.newLatLng(position))
                                lastCameraUpdateTime = currentTime
                            }
                        }

                        // Store current position for next bearing calculation
                        previousLocation = position
                        currentPositionIndex++
                    }
                },
                onComplete = {
                    runOnUiThread {
                        onNavigationComplete()
                    }
                }
            )

            isDriving = true
            currentMode = AppMode.NAVIGATION

            showToast("Đã bắt đầu lại chuyến đi")
        } else {
            showToast("Không thể bắt đầu lại - dữ liệu route đã mất")
        }
    }

    private fun onStopNavigationEarly() {
        // Stop the route simulator
        routeSimulator?.stop()
        routeSimulator = null
        isDriving = false
        isPaused = false

        // Get the current position from tracked navigation position
        val currentPosition = currentNavigationPosition

        // Clear route and route-related markers
        routePolyline?.remove()
        routePolyline = null
        completedPolyline?.remove()
        completedPolyline = null
    completedPathPoints.clear()
        currentPositionMarker?.remove()
        currentPositionMarker = null
        startMarker?.remove()
        startMarker = null
        destMarker?.remove()
        destMarker = null
        routePoints = emptyList()

        // Clear search text
        binding.destinationSearch.text.clear()
        binding.startSearch.text.clear()

        // Hide navigation controls
        binding.navigationControlsCard.visibility = View.GONE
        binding.cameraFollowToggle.visibility = View.GONE
        binding.pauseButton.visibility = View.VISIBLE
        binding.resumeButton.visibility = View.GONE
        binding.stopButton.visibility = View.GONE

        // Set GPS at current paused position
        if (currentPosition != null) {
            isGpsSet = true
            currentFakeLocationPos = currentPosition
            viewModel.update(true, currentPosition.latitude, currentPosition.longitude)

            // Create fake location circle at pause position (since navigation stopped)
            fakeLocationCircle = createStationaryLocationCircle(currentPosition)

            updateSetLocationButton()
        }

        // Reset UI to search mode
        currentMode = AppMode.SEARCH
        binding.actionButton.visibility = View.GONE
        binding.startSearchContainer.visibility = View.GONE
        binding.useCurrentLocationContainer.visibility = View.GONE
        binding.searchCard.visibility = View.VISIBLE

        // Restore getFakeLocation button since GPS is now set at pause position
        binding.getFakeLocation.visibility = View.VISIBLE

        // Restore set/unset location button since navigation stopped
        binding.setLocationButton.visibility = View.VISIBLE

        showToast("Đã dừng. GPS được set tại vị trí tạm dừng")
    }
}