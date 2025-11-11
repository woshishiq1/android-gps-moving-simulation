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
import io.github.mwarevn.movingsimulation.BuildConfig
import io.github.mwarevn.movingsimulation.R
import io.github.mwarevn.movingsimulation.network.OsrmClient
import io.github.mwarevn.movingsimulation.network.RoutingService
import io.github.mwarevn.movingsimulation.network.VehicleType
import io.github.mwarevn.movingsimulation.utils.PolylineUtils
import io.github.mwarevn.movingsimulation.utils.PrefManager
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

    // Distance tracking
    private var totalRouteDistanceKm = 0.0 // Total route distance in kilometers
    private var traveledDistanceKm = 0.0 // Distance traveled so far in kilometers
    private var lastDistancePosition: LatLng? = null // Last position for distance calculation

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

    // Camera follow throttling - Optimized for better performance
    private var lastCameraUpdateTime = 0L
    private val CAMERA_UPDATE_INTERVAL_MS = 1000L // Increased from 500ms to 1000ms for better performance

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

    // Route cache to avoid redundant API calls when swapping
    private data class RouteCacheKey(
        val startLat: Double,
        val startLng: Double,
        val destLat: Double,
        val destLng: Double,
        val vehicleType: VehicleType
    ) {
        companion object {
            // Helper to create key from markers
            fun fromMarkers(start: LatLng, dest: LatLng, vehicleType: VehicleType): RouteCacheKey {
                return RouteCacheKey(
                    startLat = start.latitude,
                    startLng = start.longitude,
                    destLat = dest.latitude,
                    destLng = dest.longitude,
                    vehicleType = vehicleType
                )
            }
        }
    }

    private data class RouteCacheEntry(
        val routePoints: List<LatLng>,
        val serviceName: String,
        val isFallback: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val routeCache = mutableMapOf<RouteCacheKey, RouteCacheEntry>()
    private val CACHE_EXPIRY_MS = 30 * 60 * 1000L // 30 minutes cache

    // Routing service (hybrid MapBox + OSRM)
    // Always get fresh instance with latest API key from preferences
    private val routingService: RoutingService
        get() {
            val apiKey = PrefManager.mapBoxApiKey
            return OsrmClient.createRoutingService(mapBoxApiKey = apiKey)
        }

    // Current vehicle type (can be changed by user later)
    private var currentVehicleType: VehicleType
        get() = VehicleType.fromString(PrefManager.vehicleType)
        set(value) { PrefManager.vehicleType = value.name }

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
                    // Button visibility will be updated in setStartMarker()
                } else if (hasSelectedStartPoint) {
                    // After first click - only allow drag/drop for fine-tuning
                    // User can see the marker, no need for toast
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

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSearchBoxes() {
        // Destination search (always visible)
        // Add TextWatcher to show/hide clear icon
        binding.destinationSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Show clear icon only when there's text
                if (s.isNullOrEmpty()) {
                    binding.destinationSearch.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
                } else {
                    // Use custom smaller icon
                    binding.destinationSearch.setCompoundDrawablesRelativeWithIntrinsicBounds(
                        0, 0, R.drawable.ic_clear_text, 0
                    )
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Handle click on clear icon
        binding.destinationSearch.setOnTouchListener { v, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                val editText = v as android.widget.EditText
                if (editText.text.isNotEmpty()) {
                    // Check if click is on the drawableEnd (clear icon)
                    val drawableEnd = editText.compoundDrawables[2]
                    if (drawableEnd != null) {
                        val clickX = event.x.toInt()
                        val drawableWidth = drawableEnd.intrinsicWidth
                        val drawableStart = editText.width - editText.paddingEnd - drawableWidth

                        if (clickX >= drawableStart) {
                            // Clicked on clear icon - clear destination
                            clearDestinationMarker()
                            return@setOnTouchListener true
                        }
                    }
                }
            }
            false
        }

        // Search only when user presses Enter/Done on keyboard
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

        // Start location search (shown in route mode)
        // Add TextWatcher to show/hide clear icon
        binding.startSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Show clear icon only when there's text
                if (s.isNullOrEmpty()) {
                    binding.startSearch.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
                } else {
                    // Use custom smaller icon
                    binding.startSearch.setCompoundDrawablesRelativeWithIntrinsicBounds(
                        0, 0, R.drawable.ic_clear_text, 0
                    )
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Handle click on clear icon
        binding.startSearch.setOnTouchListener { v, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                val editText = v as android.widget.EditText
                if (editText.text.isNotEmpty()) {
                    // Check if click is on the drawableEnd (clear icon)
                    val drawableEnd = editText.compoundDrawables[2]
                    if (drawableEnd != null) {
                        val clickX = event.x.toInt()
                        val drawableWidth = drawableEnd.intrinsicWidth
                        val drawableStart = editText.width - editText.paddingEnd - drawableWidth

                        if (clickX >= drawableStart) {
                            // Clicked on clear icon - clear start marker and text
                            startMarker?.remove()
                            startMarker = null
                            hasSelectedStartPoint = false
                            editText.text.clear()
                            updateSwapButtonVisibility()
                            updateUseCurrentLocationButtonVisibility()
                            return@setOnTouchListener true
                        }
                    }
                }
            }
            false
        }

        // Search only when user presses Enter/Done on keyboard
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

                // Remove fake location marker, circle AND center dot FIRST (before updating state)
                fakeLocationMarker?.remove()
                fakeLocationMarker = null
                fakeLocationCircle?.remove()
                fakeLocationCircle = null
                fakeLocationCenterDot?.remove()
                fakeLocationCenterDot = null

                // Then update state
                isGpsSet = false
                currentFakeLocationPos = null
                viewModel.update(false, 0.0, 0.0)
                updateSetLocationButton()
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
                binding.speedLabel.text = "${actualSpeed.toInt()} km/h"

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
                // Update button visibility (will hide since start point now matches fake GPS)
                updateUseCurrentLocationButtonVisibility()
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

        // Cancel route button (next to action button)
        // Dual purpose: clear destination in SEARCH mode, cancel route in ROUTE_PLAN mode
        binding.cancelRouteButton.setOnClickListener {
            when (currentMode) {
                AppMode.SEARCH -> {
                    // In SEARCH mode: clear destination marker
                    clearDestinationMarker()
                }
                AppMode.ROUTE_PLAN -> {
                    // In ROUTE_PLAN mode: cancel route planning
                    cancelRoutePlan()
                }
                else -> {
                    // Should not happen, but handle gracefully
                    cancelRoutePlan()
                }
            }
        }

        // Swap locations button (exchange start and destination)
        binding.swapLocationsButton.setOnClickListener {
            swapStartAndDestination()
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

    /**
     * Get full address string from coordinates (reverse geocoding)
     * Returns complete address with all available components
     * For named places, includes both name and street address for clarity
     */
    private suspend fun getAddressFromLocation(latLng: LatLng): String {
        return withContext(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(this@MapActivity, Locale.getDefault())
                val addresses = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)

                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]

                    // Build full address from available components
                    val addressParts = mutableListOf<String>()

                    // 1. Feature name (if it's a named place, not just coordinates)
                    val featureName = address.featureName?.takeIf {
                        it.isNotBlank() && !it.matches(Regex("^[\\d,\\.\\s]+$"))
                    }

                    // 2. Street address (house number + street name)
                    val streetAddress = buildString {
                        address.subThoroughfare?.let { append("$it ") } // House number
                        address.thoroughfare?.let { append(it) } // Street name
                    }.trim()

                    // If we have both feature name and street address, combine them
                    // e.g., "Mộc Hương, 89 Lê Đức Thọ" or "Sunsquare, 11/66/132 Cầu Diễn"
                    if (!featureName.isNullOrBlank() && streetAddress.isNotBlank()) {
                        addressParts.add("$featureName, $streetAddress")
                    } else {
                        // Add them separately if only one exists
                        featureName?.let { addressParts.add(it) }
                        if (streetAddress.isNotBlank()) {
                            addressParts.add(streetAddress)
                        }
                    }

                    // 3. Sub-locality (Phường)
                    address.subLocality?.let {
                        if (it.isNotBlank()) addressParts.add(it)
                    }

                    // 4. Locality (Quận/Huyện)
                    address.locality?.let {
                        if (it.isNotBlank() && it != address.subLocality) addressParts.add(it)
                    }

                    // 5. Administrative area (Tỉnh/Thành phố)
                    address.adminArea?.let {
                        if (it.isNotBlank()) addressParts.add(it)
                    }

                    // 6. Country (if available)
                    address.countryName?.let {
                        if (it.isNotBlank()) addressParts.add(it)
                    }

                    // Join all parts with comma separator
                    if (addressParts.isNotEmpty()) {
                        return@withContext addressParts.joinToString(", ")
                    }
                }

                // Fallback: Coordinates
                "${String.format("%.6f", latLng.latitude)}, ${String.format("%.6f", latLng.longitude)}"
            } catch (e: Exception) {
                android.util.Log.e("MapActivity", "Error getting address: ${e.message}")
                // Fallback: Coordinates
                "${String.format("%.6f", latLng.latitude)}, ${String.format("%.6f", latLng.longitude)}"
            }
        }
    }

    private fun setDestinationMarker(position: LatLng) {
        // Remove old marker
        destMarker?.remove()

        // Add new marker - only draggable in ROUTE_PLAN mode
        destMarker = mMap.addMarker(
            MarkerOptions()
                .position(position)
                .title("Điểm đến")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                .draggable(currentMode == AppMode.ROUTE_PLAN) // Only draggable in route planning mode
        )

        // Move camera
        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(position, 16f))

        // Get address and fill into search box
        lifecycleScope.launch {
            val address = getAddressFromLocation(position)
            binding.destinationSearch.setText(address)
        }

        // Show "Chỉ đường" button AND close button in SEARCH mode
        if (currentMode == AppMode.SEARCH) {
            binding.actionButton.apply {
                text = "Chỉ đường"
                visibility = View.VISIBLE
                setIconResource(R.drawable.ic_navigation)
            }
            // Show close button to allow user to clear destination without routing
            binding.cancelRouteButton.apply {
                text = "✕"  // X character
                visibility = View.VISIBLE
            }
        }

        // Stay in SEARCH mode - only "Chỉ đường" button switches to PLAN mode
        // This allows user to keep clicking to change destination

        // If in route planning mode and destination changes, clear route
        if (routePolyline != null) {
            routePolyline?.remove()
            routePolyline = null
        }

        // Update swap button visibility
        updateSwapButtonVisibility()
    }

    private fun clearDestinationMarker() {
        // Clear destination marker and reset to clean state
        destMarker?.remove()
        destMarker = null
        binding.destinationSearch.text.clear()

        // If in ROUTE_PLAN mode, need to reset everything back to SEARCH mode
        if (currentMode == AppMode.ROUTE_PLAN) {
            // Clear start marker and route
            startMarker?.remove()
            startMarker = null
            routePolyline?.remove()
            routePolyline = null
            routePoints = emptyList()
            hasSelectedStartPoint = false

            // Clear start search
            binding.startSearch.text.clear()

            // Hide route plan UI
            binding.startSearchContainer.visibility = View.GONE
            binding.useCurrentLocationContainer.visibility = View.GONE
            binding.routeLoadingCard.visibility = View.GONE
            binding.routeErrorCard.visibility = View.GONE

            // Reset to SEARCH mode
            currentMode = AppMode.SEARCH

            // Update markers to non-draggable
            updateMarkersDraggableState()
        }

        // Hide action buttons
        binding.actionButton.visibility = View.GONE
        binding.cancelRouteButton.visibility = View.GONE

        // Update swap button (will hide since destMarker is null)
        updateSwapButtonVisibility()
    }

    private fun setStartMarker(position: LatLng) {
        // Remove old marker
        startMarker?.remove()

        // Add new marker - only draggable in ROUTE_PLAN mode
        startMarker = mMap.addMarker(
            MarkerOptions()
                .position(position)
                .title("Điểm bắt đầu")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                .draggable(currentMode == AppMode.ROUTE_PLAN) // Only draggable in route planning mode
        )

        // Get address and fill into start search box
        lifecycleScope.launch {
            val address = getAddressFromLocation(position)
            binding.startSearch.setText(address)
        }

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

        // Update swap button visibility
        updateSwapButtonVisibility()

        // Update "use current location" button visibility
        updateUseCurrentLocationButtonVisibility()
    }

    /**
     * Set start marker and mark that user has selected start point
     */
    private fun setStartMarkerWithSelection(position: LatLng) {
        setStartMarker(position)
        hasSelectedStartPoint = true
    }

    /**
     * Update swap button visibility based on marker states
     */
    private fun updateSwapButtonVisibility() {
        // Show swap button only when both markers exist and not during navigation
        val shouldShowSwap = startMarker != null && destMarker != null && currentMode != AppMode.NAVIGATION
        binding.swapButtonContainer.visibility = if (shouldShowSwap) View.VISIBLE else View.GONE
    }

    private fun enterRoutePlanMode() {
        currentMode = AppMode.ROUTE_PLAN
        hasSelectedStartPoint = false // Reset start point selection state

        // Show start location input
        binding.startSearchContainer.visibility = View.VISIBLE
        binding.startSearch.requestFocus()

        // Update cancel button text for ROUTE_PLAN mode
        binding.cancelRouteButton.apply {
            text = "Huỷ"
            visibility = View.GONE // Hide until route is drawn
        }

        // Update marker draggable state (now draggable in ROUTE_PLAN mode)
        updateMarkersDraggableState()

        // Update quick use current location button visibility
        updateUseCurrentLocationButtonVisibility()

        // User can see the UI change - no need for toast
    }

    /**
     * Update visibility of "use current location" button based on:
     * - GPS is set (has fake location)
     * - Start point not selected OR selected but different from fake GPS location
     */
    private fun updateUseCurrentLocationButtonVisibility() {
        if (currentMode != AppMode.ROUTE_PLAN) {
            binding.useCurrentLocationContainer.visibility = View.GONE
            return
        }

        if (isGpsSet && currentFakeLocationPos != null) {
            // Check if start marker exists and matches fake GPS location
            val startPos = startMarker?.position
            val isSameLocation = startPos != null &&
                Math.abs(startPos.latitude - currentFakeLocationPos!!.latitude) < 0.0001 &&
                Math.abs(startPos.longitude - currentFakeLocationPos!!.longitude) < 0.0001

            // Show button only if start point not selected or different from fake GPS
            if (startPos == null || !isSameLocation) {
                binding.useCurrentLocationContainer.visibility = View.VISIBLE
                binding.useCurrentLocationText.text = "Dùng vị trí hiện tại (${String.format("%.4f", currentFakeLocationPos!!.latitude)}, ${String.format("%.4f", currentFakeLocationPos!!.longitude)})"
            } else {
                binding.useCurrentLocationContainer.visibility = View.GONE
            }
        } else {
            binding.useCurrentLocationContainer.visibility = View.GONE
        }
    }

    /**
     * Update marker draggable state based on current mode
     * Only draggable in ROUTE_PLAN mode
     */
    private fun updateMarkersDraggableState() {
        val isDraggable = currentMode == AppMode.ROUTE_PLAN

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

    /**
     * Swap start and destination markers
     */
    private fun swapStartAndDestination() {
        if (startMarker == null || destMarker == null) {
            showToast("Cần có cả 2 điểm để đảo ngược")
            return
        }

        // Store current positions
        val startPos = startMarker!!.position
        val destPos = destMarker!!.position

        // Store current search texts
        val startText = binding.startSearch.text.toString()
        val destText = binding.destinationSearch.text.toString()

        // Remove old markers
        startMarker?.remove()
        destMarker?.remove()

        // Create new markers with swapped positions - only draggable in ROUTE_PLAN mode
        destMarker = mMap.addMarker(
            MarkerOptions()
                .position(startPos)
                .title("Điểm đến")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                .draggable(currentMode == AppMode.ROUTE_PLAN)
        )

        startMarker = mMap.addMarker(
            MarkerOptions()
                .position(destPos)
                .title("Điểm bắt đầu")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                .draggable(currentMode == AppMode.ROUTE_PLAN)
        )

        // Swap search texts
        binding.destinationSearch.setText(startText)
        binding.startSearch.setText(destText)

        // Redraw route with new positions
        if (currentMode == AppMode.ROUTE_PLAN) {
            drawRoute()
        }

        // showToast("Đã đảo ngược điểm bắt đầu và điểm đến")
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

                // Create cache key
                val cacheKey = RouteCacheKey.fromMarkers(start, dest, currentVehicleType)

                // Check cache first
                val cachedEntry = routeCache[cacheKey]
                val isCacheValid = cachedEntry != null &&
                    (System.currentTimeMillis() - cachedEntry.timestamp) < CACHE_EXPIRY_MS

                val result = if (isCacheValid && cachedEntry != null) {
                    // Use cached route
                    android.util.Log.d("MapActivity", "✅ Using cached route")

                    // Use the cached route
                    routePoints = cachedEntry.routePoints

                    // Show notification about cached route (only log, no toast)
                    android.util.Log.d("MapActivity", "💾 Using cached route from ${cachedEntry.serviceName}")

                    // Return null to skip API call
                    null
                } else {
                    // Call API for new route
                    android.util.Log.d("MapActivity", "🌐 Fetching route from API")

                    val apiResult = routingService.getRoute(
                        startLat = start.latitude,
                        startLng = start.longitude,
                        endLat = dest.latitude,
                        endLng = dest.longitude,
                        vehicleType = currentVehicleType
                    )

                    if (apiResult != null) {
                        // Use the first (best) route
                        routePoints = apiResult.routes[0]

                        // Cache the route
                        routeCache[cacheKey] = RouteCacheEntry(
                            routePoints = routePoints,
                            serviceName = apiResult.serviceName,
                            isFallback = apiResult.isFallback
                        )

                        // Clean old cache entries (keep only last 10)
                        if (routeCache.size > 10) {
                            val oldestKey = routeCache.entries
                                .minByOrNull { it.value.timestamp }
                                ?.key
                            oldestKey?.let { routeCache.remove(it) }
                        }

                        // Log success (no toast)
                        android.util.Log.d("MapActivity", "✓ Route found via ${apiResult.serviceName}")
                    }

                    apiResult
                }

                if (result == null && !isCacheValid) {
                    // Handle error - all services failed and no cache
                    routeLoadError = "Không tìm thấy đường đi. Vui lòng thử lại."
                    isLoadingRoute = false
                    showRouteErrorUI()
                    return@launch
                }

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
                    setIconResource(R.drawable.ic_navigation)
                    visibility = View.VISIBLE
                }
                binding.cancelRouteButton.apply {
                    text = "Huỷ"
                    visibility = View.VISIBLE
                }

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

        // Clear start search text
        binding.startSearch.text.clear()

        // Reset to search mode
        currentMode = AppMode.SEARCH
        hasSelectedStartPoint = false

        // Update markers to be non-draggable in search mode
        updateMarkersDraggableState()

        // Hide search containers
        binding.startSearchContainer.visibility = View.GONE
        binding.useCurrentLocationContainer.visibility = View.GONE

        // Show "Chỉ đường" button again with destination marker still visible
        binding.actionButton.apply {
            text = "Chỉ đường"
            setIconResource(R.drawable.ic_navigation)
            visibility = View.VISIBLE
        }

        // Update cancel button text for SEARCH mode (show X to clear destination)
        binding.cancelRouteButton.apply {
            text = "✕"
            visibility = View.VISIBLE
        }

        // Show search card
        binding.searchCard.visibility = View.VISIBLE

        // Update swap button visibility (will hide since startMarker is null)
        updateSwapButtonVisibility()

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

        // Clear old fake location marker, circle AND center dot when starting navigation
        // because the fake location is now moving along the route
        fakeLocationMarker?.remove()
        fakeLocationMarker = null
        fakeLocationCircle?.remove()
        fakeLocationCircle = null
        fakeLocationCenterDot?.remove()
        fakeLocationCenterDot = null
        currentFakeLocationPos = null

        // Hide action button and show navigation controls
        binding.actionButton.visibility = View.GONE
        binding.navigationControlsCard.visibility = View.VISIBLE

        // Update navigation addresses
        updateNavigationAddresses()

        // Hide swap button during navigation
        binding.swapButtonContainer.visibility = View.GONE

        // Reset pause/resume buttons
        binding.pauseButton.visibility = View.VISIBLE
        binding.resumeButton.visibility = View.GONE
        binding.stopButton.visibility = View.GONE

        // Set initial speed and update label
        currentSpeed = binding.speedSlider.value.toDouble()
        binding.speedLabel.text = "${currentSpeed.toInt()} km/h"

        // Initialize distance tracking
        totalRouteDistanceKm = calculateTotalRouteDistance(routePoints)
        traveledDistanceKm = 0.0
        lastDistancePosition = null
        updateDistanceLabel()

        android.util.Log.d("MapActivity", "Navigation started with speed: $currentSpeed km/h, total distance: $totalRouteDistanceKm km")

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

        // CRITICAL FIX: Use lifecycleScope instead of GlobalScope to prevent memory leaks
        // GlobalScope causes coroutines to run indefinitely even after Activity is destroyed
        // lifecycleScope automatically cancels when Activity is destroyed
        routeSimulator = RouteSimulator(
            points = routePoints,
            speedKmh = currentSpeed,
            updateIntervalMs = 300L, // Optimized: 300ms for better performance (was 100ms)
            scope = lifecycleScope // FIXED: Use lifecycleScope for proper cleanup
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

                    // Calculate speed in m/s from current speedKmh
                    val speedMs = (currentSpeed * 1000.0 / 3600.0).toFloat()

                    // Update GPS location with bearing and speed for smooth rendering
                    viewModel.update(
                        start = true,
                        la = position.latitude,
                        ln = position.longitude,
                        bearing = bearing,
                        speed = speedMs
                    )

                    // Log GPS data with timing variance for debugging
                    val timeDiff = if (lastGpsUpdateTime > 0) currentTime - lastGpsUpdateTime else 0
                    // Optimize: Reduce logging frequency to lower I/O overhead
                    if (BuildConfig.DEBUG && currentPositionIndex % 5 == 0) {
                        android.util.Log.d("GPS_AntiDetect", "GPS: lat=${position.latitude}, lng=${position.longitude}, bearing=${bearing}°, interval=${timeDiff}ms")
                    }
                    lastGpsUpdateTime = currentTime

                    // Track current navigation position for pause/stop functionality
                    currentNavigationPosition = position

                    // Update traveled distance
                    updateTraveledDistance(position)

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

    private fun updateNavigationAddresses() {
        lifecycleScope.launch {
            try {
                // Get start and destination positions
                val startPos = startMarker?.position
                val destPos = destMarker?.position

                if (startPos != null && destPos != null) {
                    // Get addresses in background with timeout
                    val fromAddress = withContext(Dispatchers.IO) {
                        withTimeout(5000L) { // 5 second timeout
                            getAddressFromLocation(startPos)
                        }
                    }

                    val toAddress = withContext(Dispatchers.IO) {
                        withTimeout(5000L) { // 5 second timeout
                            getAddressFromLocation(destPos)
                        }
                    }

                    // Update UI on main thread
                    binding.navFromAddress.text = "• $fromAddress"
                    binding.navToAddress.text = "• $toAddress"
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                android.util.Log.e("MapActivity", "Timeout getting navigation addresses")
                // Show coordinates as fallback
                val startPos = startMarker?.position
                val destPos = destMarker?.position
                binding.navFromAddress.text = "• ${String.format("%.6f, %.6f", startPos?.latitude ?: 0.0, startPos?.longitude ?: 0.0)}"
                binding.navToAddress.text = "• ${String.format("%.6f, %.6f", destPos?.latitude ?: 0.0, destPos?.longitude ?: 0.0)}"
            } catch (e: Exception) {
                android.util.Log.e("MapActivity", "Error getting navigation addresses", e)
                // Show coordinates as fallback
                val startPos = startMarker?.position
                val destPos = destMarker?.position
                binding.navFromAddress.text = "• ${String.format("%.6f, %.6f", startPos?.latitude ?: 0.0, startPos?.longitude ?: 0.0)}"
                binding.navToAddress.text = "• ${String.format("%.6f, %.6f", destPos?.latitude ?: 0.0, destPos?.longitude ?: 0.0)}"
            }
        }
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
        // Update icon based on local GPS state with distinct icons
        if (isGpsSet) {
            // GPS is set -> show gray pin with X (to unset)
            binding.setLocationButton.setImageResource(R.drawable.ic_location_off)
            // Show getFakeLocation button when GPS is set
            binding.getFakeLocation.visibility = View.VISIBLE
        } else {
            // GPS is not set -> show green pin with checkmark (to set)
            binding.setLocationButton.setImageResource(R.drawable.ic_location_on)
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
        // Optimize: Increase threshold to reduce polyline updates and GPU load
        // Reduced vertex count = better performance on long routes
        val last = completedPathPoints.lastOrNull()
        if (last == null || distanceBetween(last, currentPosition) >= 5.0) { // Increased from 2.5m to 5m
            completedPathPoints.add(currentPosition)
        }

        // Draw or update polyline incrementally
        // Optimize: Only update every 3rd point to reduce rendering overhead
        if (completedPathPoints.size > 1 && completedPathPoints.size % 3 == 0) {
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
            // Show camera following icon
            binding.cameraFollowToggle.setImageResource(R.drawable.ic_camera_follow)
        } else {
            // Show free move camera icon
            binding.cameraFollowToggle.setImageResource(R.drawable.ic_camera_free)
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
     * Calculate total route distance in kilometers
     */
    private fun calculateTotalRouteDistance(points: List<LatLng>): Double {
        if (points.size < 2) return 0.0

        var totalMeters = 0.0
        for (i in 0 until points.size - 1) {
            totalMeters += distanceBetween(points[i], points[i + 1])
        }

        return totalMeters / 1000.0 // Convert to kilometers
    }

    /**
     * Update distance label with current progress
     */
    private fun updateDistanceLabel() {
        val traveledStr = String.format("%.2f", traveledDistanceKm)
        val totalStr = String.format("%.2f", totalRouteDistanceKm)
        binding.distanceLabel.text = "${traveledStr}km/${totalStr}km"
    }

    /**
     * Update traveled distance based on current position
     */
    private fun updateTraveledDistance(currentPosition: LatLng) {
        lastDistancePosition?.let { lastPos ->
            val distanceMeters = distanceBetween(lastPos, currentPosition)
            // Only add distance if movement is significant (> 0.5m) and reasonable (< 100m)
            // This filters out GPS jitter and prevents huge jumps
            if (distanceMeters > 0.5 && distanceMeters < 100) {
                traveledDistanceKm += distanceMeters / 1000.0
                updateDistanceLabel()
            }
        }
        lastDistancePosition = currentPosition
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

        // Reset speed to default for next trip
        binding.speedSlider.value = 40f
        currentSpeed = 40.0
        binding.speedLabel.text = "40 km/h"

        // Hide navigation controls
        binding.navigationControlsCard.visibility = View.GONE
        binding.cameraFollowToggle.visibility = View.GONE
        binding.cancelRouteButton.visibility = View.GONE

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

        // Reset speed to default for next trip
        binding.speedSlider.value = 40f
        currentSpeed = 40.0
        binding.speedLabel.text = "40 km/h"

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
        // CRITICAL: Proper cleanup to prevent memory leaks
        routeSimulator?.stop()
        routeSimulator = null
        searchJob?.cancel()
        searchJob = null

        // Clear route cache to free memory
        routeCache.clear()

        // Clear polylines and markers to free GPU memory
        routePolyline?.remove()
        routePolyline = null
        completedPolyline?.remove()
        completedPolyline = null
        fakeLocationCircle?.remove()
        fakeLocationCircle = null
        fakeLocationCenterDot?.remove()
        fakeLocationCenterDot = null

        android.util.Log.d("MapActivity", "Activity destroyed - all resources cleaned up")
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
            binding.speedLabel.text = "${currentSpeed.toInt()} km/h"

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

        // Update address in search box when drag ends
        lifecycleScope.launch {
            val address = getAddressFromLocation(marker.position)
            when (marker) {
                destMarker -> {
                    binding.destinationSearch.setText(address)
                }
                startMarker -> {
                    binding.startSearch.setText(address)
                    // Update "use current location" button visibility when start marker is dragged
                    updateUseCurrentLocationButtonVisibility()
                }
            }
        }

        // Update route only when drag ends to reduce server calls
        if (currentMode == AppMode.ROUTE_PLAN && startMarker != null && destMarker != null) {
            drawRoute()
        }
    }

    override fun onMarkerDragStart(marker: Marker) {
        // Only allow dragging in ROUTE_PLAN mode
        if (currentMode != AppMode.ROUTE_PLAN) {
            if (currentMode == AppMode.NAVIGATION) {
                showToast("Không thể thay đổi điểm đến khi đang di chuyển")
            } else if (currentMode == AppMode.SEARCH) {
                showToast("Vui lòng nhấn 'Chỉ đường' để điều chỉnh vị trí")
            }
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

        // Reset speed to default for next trip
        binding.speedSlider.value = 40f
        currentSpeed = 40.0
        binding.speedLabel.text = "40 km/h"

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
        binding.cancelRouteButton.visibility = View.GONE
        binding.startSearchContainer.visibility = View.GONE
        binding.useCurrentLocationContainer.visibility = View.GONE
        binding.searchCard.visibility = View.VISIBLE

        // Restore getFakeLocation button since GPS is still set at destination
        binding.getFakeLocation.visibility = View.VISIBLE

        // Stop the route simulator
        routeSimulator?.stop()

        // Completion reached - no need for toast
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
            binding.cancelRouteButton.visibility = View.GONE

            // Show camera follow toggle and reset to follow mode
            isCameraFollowing = true
            binding.cameraFollowToggle.visibility = View.VISIBLE
            updateCameraFollowButton()

            // Hide getFakeLocation button during navigation restart
            binding.getFakeLocation.visibility = View.GONE

            // Ensure speed label shows current speed
            binding.speedLabel.text = "${currentSpeed.toInt()} km/h"

            // Create fake location circle at start point to show GPS position
            val startPos = routePoints.firstOrNull()
            if (startPos != null) {
                fakeLocationCircle = createStationaryLocationCircle(startPos)
            }

            // CRITICAL FIX: Use lifecycleScope instead of GlobalScope
            routeSimulator = RouteSimulator(
                points = routePoints,
                speedKmh = currentSpeed,
                updateIntervalMs = 300L, // Optimized: 300ms for better performance
                scope = lifecycleScope // FIXED: Proper lifecycle management
            )

            routeSimulator?.start(
                onPosition = { position ->
                    runOnUiThread {
                        val currentTime = System.currentTimeMillis()

                        // Calculate bearing for GPS metadata
                        val bearing = previousLocation?.let { prev ->
                            calculateBearing(prev, position)
                        } ?: 0f

                        // Calculate speed in m/s from current speedKmh
                        val speedMs = (currentSpeed * 1000.0 / 3600.0).toFloat()

                        // Update GPS location with bearing and speed for smooth rendering
                        viewModel.update(
                            start = true,
                            la = position.latitude,
                            ln = position.longitude,
                            bearing = bearing,
                            speed = speedMs
                        )

                        // Log GPS data with timing variance for debugging
                        val timeDiff = if (lastGpsUpdateTime > 0) currentTime - lastGpsUpdateTime else 0
                        // Optimize: Log only every 5th update in debug mode
                        if (BuildConfig.DEBUG && currentPositionIndex % 5 == 0) {
                            android.util.Log.d("GPS_AntiDetect", "GPS: lat=${position.latitude}, lng=${position.longitude}, bearing=${bearing}°, interval=${timeDiff}ms")
                        }
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

            // Navigation restarted - no need for toast
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

        // Reset speed to default for next trip
        binding.speedSlider.value = 40f
        currentSpeed = 40.0
        binding.speedLabel.text = "40 km/h"

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
        binding.cancelRouteButton.visibility = View.GONE
        binding.pauseButton.visibility = View.VISIBLE
        binding.resumeButton.visibility = View.GONE
        binding.stopButton.visibility = View.GONE

        // Set GPS at current paused position
        if (currentPosition != null) {
            isGpsSet = true
            currentFakeLocationPos = currentPosition
            viewModel.update(true, currentPosition.latitude, currentPosition.longitude)

            // Clear old circle and center dot before creating new ones
            fakeLocationCircle?.remove()
            fakeLocationCircle = null
            fakeLocationCenterDot?.remove()
            fakeLocationCenterDot = null

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

        // Navigation stopped - no need for toast
    }
}