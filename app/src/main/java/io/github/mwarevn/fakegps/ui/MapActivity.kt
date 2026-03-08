package io.github.mwarevn.fakegps.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.mwarevn.fakegps.utils.LatLng
import io.github.mwarevn.fakegps.utils.ext.*
import com.mapbox.maps.MapView
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.CameraOptions
import com.mapbox.geojson.Point
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.*
import com.mapbox.maps.extension.style.layers.properties.generated.LineCap
import com.mapbox.maps.extension.style.layers.properties.generated.LineJoin
import com.mapbox.maps.plugin.gestures.addOnMapClickListener
import com.mapbox.maps.plugin.scalebar.scalebar
import com.mapbox.maps.plugin.compass.compass
import com.mapbox.maps.plugin.annotation.Annotation
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.locationcomponent.location
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.mwarevn.fakegps.R
import io.github.mwarevn.fakegps.network.OsrmClient
import io.github.mwarevn.fakegps.network.RoutingService
import io.github.mwarevn.fakegps.network.VehicleType
import io.github.mwarevn.fakegps.utils.LocationService
import io.github.mwarevn.fakegps.utils.PrefManager
import io.github.mwarevn.fakegps.utils.ext.showToast
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull
import java.util.*
import io.github.mwarevn.fakegps.databinding.ActivityMapBinding

/**
 * Clean redesigned MapActivity with Google Maps-like UX
 * Optimized to use LocationService for persistent background simulation
 * Enhanced to fully restore state including completed path and position updates.
 */
class MapActivity : BaseMapActivity() {

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 99
        const val ROUTE_COLOR = "#006eff"
        const val ROUTE_WIDTH = 8.0
        const val COMPLETED_ROUTE_COLOR = "#909090"
        const val FAKE_LOCATION_STROKE_COLOR = 0xFFFF4500.toInt()
        const val FAKE_LOCATION_FILL_COLOR = 0x50FF4500.toInt()
        const val FAKE_LOCATION_CENTER_COLOR = 0xFFFF4500.toInt()
        const val CIRCLE_RADIUS = 25.0
        const val CIRCLE_STROKE_WIDTH = 4f
        const val CIRCLE_Z_INDEX = 128f
        const val CENTER_DOT_Z_INDEX = 129f
        private const val CAMERA_UPDATE_INTERVAL_MS = 1000L
    }

    
    // Mapbox components
    private lateinit var mapView: MapView
    private lateinit var mapboxMap: MapboxMap
    private lateinit var pointAnnotationManager: PointAnnotationManager
    private lateinit var polylineAnnotationManager: PolylineAnnotationManager

    // Markers
    private var currentLocationMarker: PointAnnotation? = null
    private var startMarker: PointAnnotation? = null
    private var destinationMarker: PointAnnotation? = null
    private var currentRouteLine: PolylineAnnotation? = null

    // Icons
    private lateinit var locationIcon: Bitmap
    private lateinit var destinationIcon: Bitmap
    private lateinit var startIcon: Bitmap

    private var appMode: AppMode = AppMode.SEARCH
    private var isNavigating = false
    private var navJob: Job? = null
    private var currentStartPos: LatLng? = null
    private var currentDestPos: LatLng? = null
    private var currentRoute: List<LatLng>? = null
    private val completedPathPoints: MutableList<LatLng> = mutableListOf()
    private var isGpsSet = false
    private var isDriving = false
    private var isPaused = false
    private var currentSpeed = 52.0
    private var routePoints: List<LatLng> = emptyList()
    private var currentFakeLocationPos: LatLng? = null
    private var lastJoystickLat = 0.0
    private var lastJoystickLon = 0.0
    private var lastCameraUpdateTime = 0L
    private var isCameraFollowing = true
    private var previousLocation: LatLng? = null
    private var currentNavigationPosition: LatLng? = null
    private var totalRouteDistanceKm = 0.0
    private var traveledDistanceKm = 0.0
    private var lastDistancePosition: LatLng? = null
    private var fakeLocationCircle: PointAnnotation? = null // Placeholder for Mapbox Circle
    private var fakeLocationCenterDot: PointAnnotation? = null

    private enum class AppMode { SEARCH, ROUTE_PLAN, NAVIGATION }
    private var currentMode = AppMode.SEARCH
    private var hasSelectedStartPoint = false

    private var locationService: LocationService? = null
    private var isBound = false
    private var serviceStateJob: Job? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) { if (pendingNavigationStart) { pendingNavigationStart = false; startNavigation() } }
    }
    private var pendingNavigationStart = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as LocationService.LocationBinder
            locationService = binder.getService()
            isBound = true
            
            // CRITICAL: Re-register position update callback to make icon move again
            locationService?.updateCallbacks(
                onPosition = { pos -> runOnUiThread { handleNavigationUpdate(pos) } },
                onComplete = { runOnUiThread { onNavigationComplete() } }
            )
            
            syncWithService()
            observeServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            locationService = null; isBound = false; serviceStateJob?.cancel()
        }
    }

    private fun observeServiceState() {
        serviceStateJob?.cancel()
        serviceStateJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val service = locationService ?: return@repeatOnLifecycle
                launch { service.isPausedFlow.collect { paused -> isPaused = paused; updateNavControlButtons() } }
                launch { service.isDrivingFlow.collect { driving -> if (isDriving && !driving) { onNavigationComplete() }; isDriving = driving } }
            }
        }
    }

    private val routeCache = mutableMapOf<String, List<LatLng>>()
    private val prefChangeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "latitude" || key == "longitude") { if (!isDriving && isGpsSet) { syncJoystickPosition() } }
        if (key == "map_type") { mapboxMap.loadStyleUri(PrefManager.getMapStyleUri()) { restoreMarkersAndRoutes() } }
    }

    private val routingService: RoutingService get() = OsrmClient.createRoutingService(getString(R.string.mapbox_access_token))
    private var currentVehicleType: VehicleType
        get() = VehicleType.fromString(PrefManager.vehicleType)
        set(value) { PrefManager.vehicleType = value.name }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.doGetUserDetails(); observeFavorites()
        Intent(this, LocationService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun observeFavorites() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.allFavList.collect { updateAddFavoriteButtonVisibility() }
            }
        }
    }

    override fun initializeMap() {
        mapView = findViewById(R.id.mapView)
        mapboxMap = mapView.mapboxMap
        
        // Initialize Annotation Managers
        val annotationApi = mapView.annotations
        pointAnnotationManager = annotationApi.createPointAnnotationManager()
        polylineAnnotationManager = annotationApi.createPolylineAnnotationManager()
        polylineAnnotationManager.lineCap = LineCap.ROUND
        polylineAnnotationManager.lineJoin = LineJoin.ROUND
        
        // Initialize Bitmaps
        locationIcon = getBitmapFromDrawable(R.drawable.ic_fake_location)
        destinationIcon = getBitmapFromDrawable(R.drawable.ic_fake_gps_marker)
        startIcon = getBitmapFromDrawable(R.drawable.ic_location_on)
        
        // Enable real location component
        mapView.location.enabled = true

        // Configure Gestures
        mapboxMap.addOnMapClickListener { point ->
            onMapClick(LatLng(point.latitude(), point.longitude()))
            true
        }
        
        pointAnnotationManager.addClickListener { pointAnnotation ->
            true
        }

        pointAnnotationManager.addDragListener(object : OnPointAnnotationDragListener {
            override fun onAnnotationDragStarted(annotation: Annotation<*>) {}
            override fun onAnnotationDrag(annotation: Annotation<*>) {}
            override fun onAnnotationDragFinished(annotation: Annotation<*>) {
                if (currentMode == AppMode.NAVIGATION) return
                if (annotation !is PointAnnotation) return
                
                lifecycleScope.launch {
                    val pos = LatLng(annotation.point.latitude(), annotation.point.longitude())
                    val addr = getAddressFromLocation(pos)
                    
                    if (annotation.id == destinationMarker?.id) {
                        binding.destinationSearch.setText(addr)
                        updateReplaceLocationButtonVisibility()
                        updateAddFavoriteButtonVisibility()
                        updateActionButtonsVisibility()
                    } else if (annotation.id == startMarker?.id) {
                        binding.startSearch.setText(addr)
                        updateUseCurrentLocationButtonVisibility()
                    }
                    if (currentMode == AppMode.ROUTE_PLAN && startMarker != null && destinationMarker != null) {
                        drawRoute()
                    }
                }
            }
        })

        // Run map ready equivalent actions
        onMapReadyMapbox()
    }

    override fun getActivityInstance() = this
    override fun hasMarker() = destinationMarker != null || startMarker != null

    override fun moveMapToNewLocation(moveNewLocation: Boolean, shouldMark: Boolean) {
        if (moveNewLocation) {
            val newPos = Point.fromLngLat(lon, lat)
            val cameraOptions = CameraOptions.Builder()
                .center(newPos)
                .zoom(16.0)
                .build()
            mapView.camera.easeTo(cameraOptions)
            if (shouldMark && currentMode == AppMode.SEARCH) { setDestinationMarker(LatLng(lat, lon)) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun onMapReadyMapbox() {
        mapboxMap.loadStyleUri(PrefManager.getMapStyleUri()) {
            if (checkPermissions()) { 
                // Enable location component (fallback behavior for now without full Mapbox Location Provider migration)
                getLastLocation() 
            } else { 
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_REQUEST_CODE) 
            }
            
            // Map settings
            val scalebar = mapView.scalebar
            scalebar.enabled = false
            val compass = mapView.compass
            compass.enabled = true
            
            // mapboxMap.addOnCameraChangeListener
            mapboxMap.subscribeCameraChanged { event ->
                PrefManager.cameraBearing = mapboxMap.cameraState.bearing.toFloat()
            }
            
            setupButtons()
            setupSearchBoxes()
            restoreMarkersAndRoutes()
            
            if (isBound) syncWithService()
        }
    }

    private fun restoreMarkersAndRoutes() {
        restoreFakeLocationMarker()
        
        // Restore destination and start markers if present
        currentDestPos?.let { setDestinationMarker(it, animateCamera = false) }
        currentStartPos?.let { setStartMarker(it, animateCamera = false) }
        
        // Restore route and navigation marker if driving
        if (isDriving) {
            drawRoute(showLoading = false)
            currentNavigationPosition?.let { updateFakeLocationMarker(it) }
        }
    }

    private fun syncWithService() {
        val service = locationService ?: return
        if (service.isDrivingFlow.value) {
            isDriving = true; currentMode = AppMode.NAVIGATION
            routePoints = service.currentRoutePoints
            
            // 1. Restore path and markers
            currentRouteLine?.let { polylineAnnotationManager.delete(it) }
            val routePointsList = routePoints.map { Point.fromLngLat(it.longitude, it.latitude) }
            val polylineOpt = PolylineAnnotationOptions().withPoints(routePointsList).withLineColor(Color.parseColor(ROUTE_COLOR)).withLineWidth(ROUTE_WIDTH.toDouble())
            currentRouteLine = polylineAnnotationManager.create(polylineOpt)

            if (routePoints.isNotEmpty()) {
                startMarker?.let { pointAnnotationManager.delete(it) }
                val sOpt = PointAnnotationOptions().withPoint(Point.fromLngLat(routePoints.first().longitude, routePoints.first().latitude)).withIconImage(startIcon)
                startMarker = pointAnnotationManager.create(sOpt)
                
                destinationMarker?.let { pointAnnotationManager.delete(it) }
                val dPos = service.currentTargetLatLng ?: routePoints.last()
                val dOpt = PointAnnotationOptions().withPoint(Point.fromLngLat(dPos.longitude, dPos.latitude)).withIconImage(destinationIcon)
                destinationMarker = pointAnnotationManager.create(dOpt)
            }
            
            // 2. CRITICAL: Restore completed path (traveled route)
            completedPathPoints.clear()
            completedPathPoints.addAll(service.traveledPoints)
            if (completedPathPoints.isNotEmpty()) {
                completedPolyline?.let { polylineAnnotationManager.delete(it) }
                val cPtsList = completedPathPoints.map { Point.fromLngLat(it.longitude, it.latitude) }
                val cOpt = PolylineAnnotationOptions().withPoints(cPtsList).withLineColor(Color.parseColor(COMPLETED_ROUTE_COLOR)).withLineWidth(ROUTE_WIDTH.toDouble() + 1.0)
                completedPolyline = polylineAnnotationManager.create(cOpt)
            }
            
            // 3. Switch UI mode
            binding.searchCard.visibility = View.GONE; binding.navigationControlsCard.visibility = View.VISIBLE; binding.cameraFollowToggle.visibility = View.VISIBLE
            updateNavigationAddresses(); updateNavControlButtons()
            
            // 4. Set current position
            service.lastPosition?.let { handleNavigationUpdate(it) }
            
            totalRouteDistanceKm = calculateTotalRouteDistance(routePoints)
            traveledDistanceKm = (completedPathPoints.size * 0.005) // Approximation or recalculate
            updateDistanceLabel()
        }
    }

    private fun syncJoystickPosition() {
        val currentLat = PrefManager.getLat; val currentLon = PrefManager.getLng
        if (Math.abs(currentLat - lastJoystickLat) > 1e-7 || Math.abs(currentLon - lastJoystickLon) > 1e-7) {
            lastJoystickLat = currentLat; lastJoystickLon = currentLon
            val newPos = LatLng(currentLat, currentLon)
            lifecycleScope.launch(Dispatchers.Main) {
                // fakeLocationCircle?.center = newPos; fakeLocationCenterDot?.position = newPos
                currentFakeLocationPos = newPos; updateActionButtonsVisibility()
            }
        }
    }

    fun onMapClick(position: LatLng) {
        when (currentMode) {
            AppMode.SEARCH -> setDestinationMarker(position)
            AppMode.ROUTE_PLAN -> if (!hasSelectedStartPoint) setStartMarkerWithSelection(position)
            AppMode.NAVIGATION -> showToast("Không thể thay đổi khi đang di chuyển")
        }
    }

    private fun setupSearchBoxes() {
        binding.destinationSearch.setOnEditorActionListener { v, _, _ ->
            v.text.toString().trim().takeIf { it.isNotEmpty() }?.let { lifecycleScope.launch { searchLocation(it) { setDestinationMarker(it) } } }
            true
        }
        binding.startSearch.setOnEditorActionListener { v, _, _ ->
            v.text.toString().trim().takeIf { it.isNotEmpty() }?.let { lifecycleScope.launch { searchLocation(it) { setStartMarkerWithSelection(it) } } }
            true
        }
    }

    override fun setupButtons() {
        isGpsSet = viewModel.isStarted; updateSetLocationButton()
        binding.actionButton.setOnClickListener {
            when (currentMode) {
                AppMode.SEARCH -> if (destinationMarker != null) enterRoutePlanMode() else showToast("Vui lòng chọn điểm đến")
                AppMode.ROUTE_PLAN -> if (startMarker != null && destinationMarker != null) checkAndStartNavigation() else showToast("Vui lòng chọn đủ điểm đi và đến")
                AppMode.NAVIGATION -> stopNavigation()
            }
        }
        binding.getlocation.setOnClickListener { getLastLocation() }
        binding.getFakeLocation.setOnClickListener { currentFakeLocationPos?.let { pos -> 
            mapView.camera.easeTo(CameraOptions.Builder().center(Point.fromLngLat(pos.longitude, pos.latitude)).zoom(15.0).build()) 
        } }
        binding.setLocationButton.setOnClickListener {
            if (isGpsSet) {
                currentLocationMarker?.let { pointAnnotationManager.delete(it) }
                currentLocationMarker = null
                isGpsSet = false; currentFakeLocationPos = null; viewModel.update(false, 0.0, 0.0)
                stopService(Intent(this, LocationService::class.java))
            } else {
                val pos = destinationMarker?.point?.let { LatLng(it.latitude(), it.longitude()) } ?: mapboxMap.cameraState.center.let { LatLng(it.latitude(), it.longitude()) }
                isGpsSet = true; currentFakeLocationPos = pos; viewModel.update(true, pos.latitude, pos.longitude)
                updateFakeLocationMarker(pos); checkAndStartForegroundService(LocationService.ACTION_SET_FIXED_LOCATION)
            }
            updateSetLocationButton(); updateActionButtonsVisibility()
        }
        binding.replaceLocationButton.setOnClickListener {
            destinationMarker?.point?.let { pt ->
                val pos = LatLng(pt.latitude(), pt.longitude())
                currentFakeLocationPos = pos; viewModel.update(true, pos.latitude, pos.longitude)
                updateFakeLocationMarker(pos); updateReplaceLocationButtonVisibility()
                updateActionButtonsVisibility(); showToast("Đã cập nhật vị trí Fake GPS")
            }
        }
        binding.addFavoriteButton.setOnClickListener { destinationMarker?.point?.let { pt -> showAddFavoriteDialog(LatLng(pt.latitude(), pt.longitude())) } }
        binding.cameraFollowToggle.setOnClickListener { if (isDriving) { isCameraFollowing = !isCameraFollowing; updateCameraFollowButton() } }
        binding.speedSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                currentSpeed = if (isDriving && value <= 0) 1.0 else value.toDouble()
                updateSpeedLabel(currentSpeed); locationService?.setSpeed(currentSpeed)
            }
        }
        binding.autoCurveSpeedCheckbox.isChecked = PrefManager.autoCurveSpeed
        binding.autoCurveSpeedCheckbox.setOnCheckedChangeListener { _, isChecked -> PrefManager.autoCurveSpeed = isChecked }
        binding.pauseButton.setOnClickListener { if (isDriving && !isPaused) { locationService?.pauseSimulation() } }
        binding.resumeButton.setOnClickListener { if (isDriving && isPaused) { locationService?.resumeSimulation() } }
        binding.stopButton.setOnClickListener { if (isDriving) onStopNavigationEarly() }
        binding.completedFinishButton.setOnClickListener { onFinishNavigation() }
        binding.completedRestartButton.setOnClickListener { onRestartNavigation() }
        binding.useCurrentLocationContainer.setOnClickListener { currentFakeLocationPos?.let { setStartMarkerWithSelection(it); updateUseCurrentLocationButtonVisibility() } }
        binding.routeRetryButton.setOnClickListener { drawRoute() }
        binding.routeErrorCancelButton.setOnClickListener { cancelRoutePlan() }
        binding.cancelRouteButton.setOnClickListener { if (currentMode == AppMode.SEARCH) clearDestinationMarker() else cancelRoutePlan() }
        binding.swapLocationsButton.setOnClickListener { swapStartAndDestination() }
        binding.navControlsToggle.setOnClickListener { toggleNavigationControls() }
        restoreNavigationControlsState()
    }

    private fun checkAndStartNavigation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                pendingNavigationStart = true; requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS); return
            }
        }
        startNavigation()
    }

    private fun checkAndStartForegroundService(action: String = LocationService.ACTION_START_FOREGROUND) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS); return
            }
        }
        Intent(this, LocationService::class.java).apply { this.action = action }.also { startService(it) }
    }

    private fun showAddFavoriteDialog(latLng: LatLng) {
        val view = layoutInflater.inflate(R.layout.dialog, null); val editText = view.findViewById<EditText>(R.id.search_edittxt)
        lifecycleScope.launch { val address = getAddressFromLocation(latLng); editText.setText(address) }
        MaterialAlertDialogBuilder(this).setTitle("Thêm vào yêu thích").setView(view).setPositiveButton("Thêm") { _, _ ->
            val name = editText.text.toString().trim()
            if (name.isNotEmpty()) { viewModel.storeFavorite(name, latLng.latitude, latLng.longitude); showToast("Đã lưu vào yêu thích") }
        }.setNegativeButton("Hủy", null).show()
    }

    private fun updateNavControlButtons() {
        binding.pauseButton.visibility = if (isPaused) View.GONE else View.VISIBLE
        binding.resumeButton.visibility = if (isPaused) View.VISIBLE else View.GONE
        binding.stopButton.visibility = if (isPaused) View.VISIBLE else View.GONE
    }

    private suspend fun searchLocation(query: String, onFound: (LatLng) -> Unit) = withContext(Dispatchers.IO) {
        try {
            val geocoder = Geocoder(this@MapActivity, Locale.getDefault())
            val addresses = geocoder.getFromLocationName(query, 1)
            if (!addresses.isNullOrEmpty()) {
                val addr = addresses[0]
                val pos = LatLng(addr.latitude, addr.longitude)
                lifecycleScope.launch(Dispatchers.Main) { onFound(pos) }
            } else {
                lifecycleScope.launch(Dispatchers.Main) { showToast("Không tìm thấy địa điểm") }
            }
        } catch (e: Exception) {
            lifecycleScope.launch(Dispatchers.Main) { showToast("Lỗi tìm kiếm: ${e.message}") }
        }
    }

    private suspend fun getAddressFromLocation(latLng: LatLng): String = withContext(Dispatchers.IO) {
        try {
            val addresses = Geocoder(this@MapActivity, Locale.getDefault()).getFromLocation(latLng.latitude, latLng.longitude, 1)
            if (!addresses.isNullOrEmpty()) {
                val addr = addresses[0]; val parts = mutableListOf<String>()
                addr.featureName?.takeIf { it.isNotBlank() && !it.matches(Regex("^[\\d,\\.\\s]+$")) }?.let { parts.add(it) }
                val street = "${addr.subThoroughfare ?: ""} ${addr.thoroughfare ?: ""}".trim()
                if (street.isNotBlank()) parts.add(street)
                addr.subLocality?.let { parts.add(it) }; addr.locality?.let { parts.add(it) }; addr.adminArea?.let { parts.add(it) }
                if (parts.isNotEmpty()) return@withContext parts.distinct().joinToString(", ")
            }
            String.format("%.6f, %.6f", latLng.latitude, latLng.longitude)
        } catch (e: Exception) { String.format("%.6f, %.6f", latLng.latitude, latLng.longitude) }
    }

    private fun setDestinationMarker(position: LatLng, animateCamera: Boolean = true) {
        destinationMarker?.let { pointAnnotationManager.delete(it) }
        val pointOptions = PointAnnotationOptions()
            .withPoint(Point.fromLngLat(position.longitude, position.latitude))
            .withIconImage(destinationIcon)
            .withDraggable(currentMode != AppMode.NAVIGATION)
        destinationMarker = pointAnnotationManager.create(pointOptions)
        currentDestPos = position
        
        if (animateCamera) {
            val newPoint = Point.fromLngLat(position.longitude, position.latitude)
            mapView.camera.easeTo(CameraOptions.Builder().center(newPoint).zoom(16.0).build())
        }
        lifecycleScope.launch { binding.destinationSearch.setText(position.getAddress(this@MapActivity).firstOrNull() ?: "") }
        if (currentMode == AppMode.SEARCH) { updateActionButtonsVisibility() }
        currentRouteLine?.let { polylineAnnotationManager.delete(it) }; currentRouteLine = null; updateSwapButtonVisibility(); updateReplaceLocationButtonVisibility()
    }

    private fun updateActionButtonsVisibility() {
        if (currentMode != AppMode.SEARCH) return
        val markerPos = destinationMarker?.point
        val fakePos = currentFakeLocationPos
        val isAtFakeLocation = isGpsSet && markerPos != null && fakePos != null && Math.abs(markerPos.latitude() - fakePos.latitude) < 1e-6 && Math.abs(markerPos.longitude() - fakePos.longitude) < 1e-6
        if (isAtFakeLocation) { binding.actionButton.visibility = View.GONE; binding.cancelRouteButton.visibility = View.GONE; destinationMarker?.let { pointAnnotationManager.delete(it) }; destinationMarker = null; binding.destinationSearch.text.clear() } 
        else if (destinationMarker == null) { binding.actionButton.visibility = View.GONE; binding.cancelRouteButton.visibility = View.GONE } 
        else { binding.actionButton.apply { text = "Chỉ đường"; visibility = View.VISIBLE; setIconResource(R.drawable.ic_navigation) }; binding.cancelRouteButton.apply { text = "Huỷ"; visibility = View.VISIBLE } }
        updateAddFavoriteButtonVisibility()
    }

    private fun updateAddFavoriteButtonVisibility() {
        val dest = destinationMarker?.point
        if (currentMode == AppMode.SEARCH && dest != null) {
            val isFavorite = viewModel.allFavList.value.any { val results = FloatArray(1); android.location.Location.distanceBetween(it.lat ?: 0.0, it.lng ?: 0.0, dest.latitude(), dest.longitude(), results); results[0] < 15 }
            binding.addFavoriteButton.visibility = if (isFavorite) View.GONE else View.VISIBLE
        } else { binding.addFavoriteButton.visibility = View.GONE }
    }

    private fun clearDestinationMarker() {
        destinationMarker?.let { pointAnnotationManager.delete(it) }; destinationMarker = null; currentDestPos = null; binding.destinationSearch.text.clear()
        if (currentMode == AppMode.ROUTE_PLAN) cancelRoutePlan()
        binding.actionButton.visibility = View.GONE; binding.cancelRouteButton.visibility = View.GONE; binding.addFavoriteButton.visibility = View.GONE
        updateSwapButtonVisibility(); updateReplaceLocationButtonVisibility()
    }

    private fun setStartMarker(position: LatLng, animateCamera: Boolean = true) {
        startMarker?.let { pointAnnotationManager.delete(it) }
        val pointOptions = PointAnnotationOptions()
            .withPoint(Point.fromLngLat(position.longitude, position.latitude))
            .withIconImage(startIcon)
            .withDraggable(currentMode == AppMode.ROUTE_PLAN)
        startMarker = pointAnnotationManager.create(pointOptions)
        currentStartPos = position

        lifecycleScope.launch { binding.startSearch.setText(position.getAddress(this@MapActivity).firstOrNull() ?: "") }
        
        if (animateCamera) {
            destinationMarker?.point?.let { pt ->
                val dPos = LatLng(pt.latitude(), pt.longitude())
                val pts = listOf(Point.fromLngLat(position.longitude, position.latitude), Point.fromLngLat(dPos.longitude, dPos.latitude))
                val cameraOptions = mapboxMap.cameraForCoordinates(pts, com.mapbox.maps.EdgeInsets(150.0, 150.0, 150.0, 150.0))
                mapView.camera.easeTo(cameraOptions)
            }
        }
        drawRoute(showLoading = animateCamera)
        updateSwapButtonVisibility(); updateUseCurrentLocationButtonVisibility()
    }

    private fun setStartMarkerWithSelection(position: LatLng) { setStartMarker(position); hasSelectedStartPoint = true }
    private fun updateSwapButtonVisibility() { binding.swapButtonContainer.visibility = if (startMarker != null && destinationMarker != null && currentMode != AppMode.NAVIGATION) View.VISIBLE else View.GONE }

    private fun enterRoutePlanMode() {
        currentMode = AppMode.ROUTE_PLAN; hasSelectedStartPoint = false
        binding.startSearchContainer.visibility = View.VISIBLE; binding.startSearch.requestFocus()
        binding.cancelRouteButton.apply { text = "Huỷ"; visibility = View.VISIBLE }
        binding.actionButton.visibility = View.GONE; binding.addFavoriteButton.visibility = View.GONE
        updateMarkersDraggableState(); updateUseCurrentLocationButtonVisibility(); updateReplaceLocationButtonVisibility()
    }

    private fun updateUseCurrentLocationButtonVisibility() {
        if (currentMode != AppMode.ROUTE_PLAN || !isGpsSet || currentFakeLocationPos == null) { binding.useCurrentLocationContainer.visibility = View.GONE; return }
        val startLoc = startMarker?.point?.let { LatLng(it.latitude(), it.longitude()) }
        val isSame = startLoc != null && Math.abs(startLoc.latitude - currentFakeLocationPos!!.latitude) < 1e-4 && Math.abs(startLoc.longitude - currentFakeLocationPos!!.longitude) < 1e-4
        binding.useCurrentLocationContainer.visibility = if (!isSame) View.VISIBLE else View.GONE
        if (!isSame) binding.useCurrentLocationText.text = "Dùng vị trí hiện tại (${String.format("%.4f", currentFakeLocationPos!!.latitude)}, ${String.format("%.4f", currentFakeLocationPos!!.longitude)})"
    }

    private fun updateMarkersDraggableState() { destinationMarker?.isDraggable = currentMode != AppMode.NAVIGATION; startMarker?.isDraggable = currentMode == AppMode.ROUTE_PLAN }

    private fun swapStartAndDestination() {
        if (startMarker == null || destinationMarker == null) return
        val sPos = LatLng(startMarker!!.point.latitude(), startMarker!!.point.longitude())
        val dPos = LatLng(destinationMarker!!.point.latitude(), destinationMarker!!.point.longitude())
        val sText = binding.startSearch.text.toString()
        val dText = binding.destinationSearch.text.toString()
        
        startMarker?.let { pointAnnotationManager.delete(it) }
        destinationMarker?.let { pointAnnotationManager.delete(it) }
        
        val newSPosOpt = PointAnnotationOptions().withPoint(Point.fromLngLat(dPos.longitude, dPos.latitude)).withIconImage(startIcon).withDraggable(currentMode == AppMode.ROUTE_PLAN)
        val newDPosOpt = PointAnnotationOptions().withPoint(Point.fromLngLat(sPos.longitude, sPos.latitude)).withIconImage(destinationIcon).withDraggable(currentMode != AppMode.NAVIGATION)
        
        startMarker = pointAnnotationManager.create(newSPosOpt)
        destinationMarker = pointAnnotationManager.create(newDPosOpt)
        
        binding.startSearch.setText(dText); binding.destinationSearch.setText(sText)
        if (currentMode == AppMode.ROUTE_PLAN) drawRoute()
    }

    private fun drawRoute(showLoading: Boolean = true) {
        val start = startMarker?.point?.let { LatLng(it.latitude(), it.longitude()) } ?: return
        val dest = destinationMarker?.point?.let { LatLng(it.latitude(), it.longitude()) } ?: return
        
        if (showLoading) {
            binding.actionButton.visibility = View.GONE; binding.routeLoadingCard.visibility = View.VISIBLE
            binding.routeLoadingProgressText.text = "Đang tìm tuyến đường..."
        }
        lifecycleScope.launch {
            try {
                val key = "${start.latitude},${start.longitude}-${dest.latitude},${dest.longitude}-${currentVehicleType.name}"
                routePoints = routeCache[key] ?: routingService.getRoute(start.latitude, start.longitude, dest.latitude, dest.longitude, currentVehicleType)?.routes?.get(0).also { if (it != null) routeCache[key] = it } ?: emptyList()
                if (routePoints.isEmpty()) { showRouteErrorUI(); return@launch }
                
                currentRouteLine?.let { polylineAnnotationManager.delete(it) }
                val routePointsList = routePoints.map { Point.fromLngLat(it.longitude, it.latitude) }
                val polylineOpt = PolylineAnnotationOptions()
                    .withPoints(routePointsList)
                    .withLineColor(Color.parseColor(ROUTE_COLOR))
                    .withLineWidth(ROUTE_WIDTH)
                currentRouteLine = polylineAnnotationManager.create(polylineOpt)
                
                if (showLoading) {
                    binding.routeLoadingCard.visibility = View.GONE
                    binding.actionButton.apply { text = "Bắt đầu"; setIconResource(R.drawable.ic_navigation); visibility = View.VISIBLE }
                    binding.cancelRouteButton.apply { text = "Huỷ"; visibility = View.VISIBLE }
                }
            } catch (e: Exception) { showRouteErrorUI() }
        }
    }

    private fun showRouteErrorUI() { binding.routeLoadingCard.visibility = View.GONE; binding.routeErrorCard.visibility = View.VISIBLE; binding.actionButton.visibility = View.GONE; binding.cancelRouteButton.apply { text = "Huỷ"; visibility = View.VISIBLE } }

    private fun cancelRoutePlan() {
        currentRouteLine?.let { polylineAnnotationManager.delete(it) }; currentRouteLine = null
        startMarker?.let { pointAnnotationManager.delete(it) }; startMarker = null
        binding.startSearch.text.clear(); binding.startSearchContainer.visibility = View.GONE; binding.useCurrentLocationContainer.visibility = View.GONE
        binding.routeLoadingCard.visibility = View.GONE; binding.routeErrorCard.visibility = View.GONE; currentMode = AppMode.SEARCH; hasSelectedStartPoint = false
        updateActionButtonsVisibility(); binding.searchCard.visibility = View.VISIBLE; updateSwapButtonVisibility()
    }

    private fun startNavigation() {
        if (routePoints.isEmpty()) return
        isDriving = true; isPaused = false; currentMode = AppMode.NAVIGATION
        completedPathPoints.clear(); completedPathPoints.add(routePoints.first())
        
        // Remove fake location visual components
        currentFakeLocationPos = null
        
        binding.actionButton.visibility = View.GONE; binding.navigationControlsCard.visibility = View.VISIBLE
        binding.cancelRouteButton.visibility = View.GONE; binding.addFavoriteButton.visibility = View.GONE
        binding.searchCard.visibility = View.GONE; binding.swapButtonContainer.visibility = View.GONE
        binding.getFakeLocation.visibility = View.GONE; binding.setLocationButton.visibility = View.GONE; binding.replaceLocationButton.visibility = View.GONE
        updateNavigationAddresses(); updateNavControlButtons(); isCameraFollowing = true
        binding.cameraFollowToggle.visibility = View.VISIBLE; updateCameraFollowButton()
        totalRouteDistanceKm = calculateTotalRouteDistance(routePoints); traveledDistanceKm = 0.0; lastDistancePosition = null; updateDistanceLabel()
        
        Intent(this, LocationService::class.java).apply { action = LocationService.ACTION_START_FOREGROUND }.also { startService(it) }
        locationService?.startRouteSimulation(points = routePoints, speedKmh = currentSpeed, onPosition = { pos -> runOnUiThread { handleNavigationUpdate(pos) } }, onComplete = { runOnUiThread { onNavigationComplete() } })
    }

    private fun handleNavigationUpdate(pos: LatLng) {
        val time = System.currentTimeMillis(); currentNavigationPosition = pos; updateTraveledDistance(pos); updateSpeedLabel(currentSpeed)
        
        if (PrefManager.isShowFakeIcon) updateFakeLocationMarker(pos)
        updateCompletedPath(pos)
        
        if (isCameraFollowing && time - lastCameraUpdateTime >= CAMERA_UPDATE_INTERVAL_MS) { 
            val cameraOptions = CameraOptions.Builder().center(Point.fromLngLat(pos.longitude, pos.latitude)).build()
            mapView.camera.easeTo(cameraOptions)
            lastCameraUpdateTime = time 
        }
        previousLocation = pos
    }

    private fun updateNavigationAddresses() {
        lifecycleScope.launch {
            val sPos = startMarker?.point?.let { LatLng(it.latitude(), it.longitude()) }
            val dPos = destinationMarker?.point?.let { LatLng(it.latitude(), it.longitude()) }
            if (sPos != null && dPos != null) { binding.navFromAddress.text = "• ${getAddressFromLocation(sPos)}"; binding.navToAddress.text = "• ${getAddressFromLocation(dPos)}" }
        }
    }

    private fun stopNavigation() { locationService?.stopRouteSimulation() }
    private fun updateSetLocationButton() { 
        binding.setLocationButton.setImageResource(if (isGpsSet) R.drawable.ic_gps_off else R.drawable.ic_location_on)
        binding.getFakeLocation.visibility = if (isGpsSet && PrefManager.isShowFakeIcon) View.VISIBLE else View.GONE
        updateReplaceLocationButtonVisibility() 
    }

    private fun updateSpeedLabel(speed: Double) {
        try { val actual = io.github.mwarevn.fakegps.utils.SpeedSyncManager.getActualSpeed(); binding.speedLabel.text = if (actual > 0.01f) "${speed.toInt()} / ${actual.toInt()} km/h" else "${speed.toInt()} km/h" } 
        catch (e: Exception) { binding.speedLabel.text = "${speed.toInt()} km/h" }
    }

    private fun updateReplaceLocationButtonVisibility() { binding.replaceLocationButton.visibility = if (currentMode == AppMode.SEARCH && isGpsSet && destinationMarker != null && currentFakeLocationPos != destinationMarker?.point?.let { LatLng(it.latitude(), it.longitude()) }) View.VISIBLE else View.GONE }

    private fun updateFakeLocationMarker(position: LatLng) { 
        if (!PrefManager.isShowFakeIcon) {
            currentLocationMarker?.let { pointAnnotationManager.delete(it) }
            currentLocationMarker = null
            return
        }
        currentLocationMarker?.let { pointAnnotationManager.delete(it) }
        val options = PointAnnotationOptions()
            .withPoint(Point.fromLngLat(position.longitude, position.latitude))
            .withIconImage(locationIcon)
            .withDraggable(false)
        currentLocationMarker = pointAnnotationManager.create(options)
    }

    override fun onShowFakeIconToggled(show: Boolean) {
        if (!show) {
            currentLocationMarker?.let { pointAnnotationManager.delete(it) }
            currentLocationMarker = null
        } else {
            val positionToDraw = if (isDriving) currentNavigationPosition else currentFakeLocationPos
            positionToDraw?.let { updateFakeLocationMarker(it) }
        }
        updateSetLocationButton()
    }

    private var completedPolyline: PolylineAnnotation? = null

    private fun updateCompletedPath(pos: LatLng) {
        if (completedPathPoints.isEmpty() || distanceBetween(completedPathPoints.last(), pos) >= 5.0) {
            completedPathPoints.add(pos)
            if (completedPathPoints.size % 3 == 0) {
                completedPolyline?.let { polylineAnnotationManager.delete(it) }
                val pts = completedPathPoints.map { Point.fromLngLat(it.longitude, it.latitude) }
                val opt = PolylineAnnotationOptions()
                    .withPoints(pts)
                    .withLineColor(Color.parseColor(COMPLETED_ROUTE_COLOR))
                    .withLineWidth(ROUTE_WIDTH + 1.0)
                completedPolyline = polylineAnnotationManager.create(opt)
            }
        }
    }

    private fun updateCameraFollowButton() { binding.cameraFollowToggle.setImageResource(if (isCameraFollowing) R.drawable.ic_camera_follow else R.drawable.ic_camera_free) }
    private fun distanceBetween(p1: LatLng, p2: LatLng): Double { val results = FloatArray(1); android.location.Location.distanceBetween(p1.latitude, p1.longitude, p2.latitude, p2.longitude, results); return results[0].toDouble() }
    private fun calculateTotalRouteDistance(points: List<LatLng>): Double { var dist = 0.0; for (i in 0 until points.size - 1) dist += distanceBetween(points[i], points[i + 1]); return dist / 1000.0 }
    private fun updateDistanceLabel() { binding.distanceLabel.text = String.format("%.2fkm/%.2fkm", traveledDistanceKm, totalRouteDistanceKm) }
    private fun updateTraveledDistance(pos: LatLng) { lastDistancePosition?.let { val d = distanceBetween(it, pos); if (d in 0.5..100.0) { traveledDistanceKm += d / 1000.0; updateDistanceLabel() } }; lastDistancePosition = pos }
    private fun createFakeLocationCircle(center: LatLng, stroke: Int, fill: Int, centerColor: Int) {
        // Mapbox Circle Annotation isn't standard in the same package, skipped for visual parity 
        // We will rely on PointAnnotation or UserLocation
    }

    private fun createStationaryLocationCircle(center: LatLng) = createFakeLocationCircle(center, FAKE_LOCATION_STROKE_COLOR, FAKE_LOCATION_FILL_COLOR, FAKE_LOCATION_CENTER_COLOR)

    private fun onNavigationComplete() {
        isDriving = false; isPaused = false; binding.speedSlider.value = 52f; currentSpeed = 52.0; binding.speedLabel.text = "52 km/h"
        binding.navigationControlsCard.visibility = View.GONE; binding.cameraFollowToggle.visibility = View.GONE
        
        // FIX: Use current location if stopped early, instead of always forcing destination
        val destPos = destinationMarker?.point?.let { LatLng(it.latitude(), it.longitude()) }
        val finalPos = currentNavigationPosition ?: routePoints.lastOrNull() ?: destPos
        if (finalPos != null) { 
            isGpsSet = true; currentFakeLocationPos = finalPos; 
            viewModel.update(true, finalPos.latitude, finalPos.longitude); 
            updateFakeLocationMarker(finalPos); updateSetLocationButton() 
        }
        binding.completionActionsCard.visibility = View.VISIBLE
    }

    private fun resetToSearchMode() {
        destinationMarker?.let { pointAnnotationManager.delete(it) }; destinationMarker = null
        startMarker?.let { pointAnnotationManager.delete(it) }; startMarker = null
        currentRouteLine?.let { polylineAnnotationManager.delete(it) }; currentRouteLine = null
        completedPolyline?.let { polylineAnnotationManager.delete(it) }; completedPolyline = null
        completedPathPoints.clear(); routePoints = emptyList(); currentMode = AppMode.SEARCH; hasSelectedStartPoint = false
        binding.actionButton.visibility = View.GONE; binding.navigationControlsCard.visibility = View.GONE; binding.cameraFollowToggle.visibility = View.GONE
        binding.startSearchContainer.visibility = View.GONE; binding.useCurrentLocationContainer.visibility = View.GONE; binding.destinationSearch.text.clear()
        binding.startSearch.text.clear(); binding.searchCard.visibility = View.VISIBLE; binding.addFavoriteButton.visibility = View.GONE; updateReplaceLocationButtonVisibility()
    }

    private fun restoreFakeLocationMarker() {
        if (viewModel.isStarted) {
            val lat = viewModel.getLat; val lng = viewModel.getLng
            if (lat != 0.0 && lng != 0.0) { val pos = LatLng(lat, lng); isGpsSet = true; currentFakeLocationPos = pos; updateFakeLocationMarker(pos); updateSetLocationButton(); lastJoystickLat = lat; lastJoystickLon = lng }
        }
    }

    override fun onDestroy() { super.onDestroy(); if (isBound) { unbindService(serviceConnection); isBound = false }; serviceStateJob?.cancel(); PrefManager.sharedPreferences.unregisterOnSharedPreferenceChangeListener(prefChangeListener) }
    override fun onResume() { super.onResume(); PrefManager.sharedPreferences.registerOnSharedPreferenceChangeListener(prefChangeListener); updateNavigationUI() }

    private fun updateNavigationUI() {
        if (isDriving) {
            binding.navigationControlsCard.visibility = View.VISIBLE; binding.searchCard.visibility = View.GONE; binding.cameraFollowToggle.visibility = View.VISIBLE
            binding.setLocationButton.visibility = View.GONE; binding.getFakeLocation.visibility = View.GONE; updateNavControlButtons(); updateSpeedLabel(currentSpeed)
            currentNavigationPosition?.let { updateFakeLocationMarker(it) }
        }
    }

    // Removed Google onMarkerDrag Listeners

    private fun onFinishNavigation() {
        binding.completionActionsCard.visibility = View.GONE; binding.speedSlider.value = 52f; currentSpeed = 52.0; binding.speedLabel.text = "52 km/h"
        currentRouteLine?.let { polylineAnnotationManager.delete(it) }; currentRouteLine = null
        completedPolyline?.let { polylineAnnotationManager.delete(it) }; completedPolyline = null
        completedPathPoints.clear()
        startMarker?.let { pointAnnotationManager.delete(it) }; startMarker = null
        destinationMarker?.let { pointAnnotationManager.delete(it) }; destinationMarker = null
        routePoints = emptyList(); binding.destinationSearch.text.clear(); binding.startSearch.text.clear()
        currentMode = AppMode.SEARCH; binding.actionButton.visibility = View.GONE; binding.navigationControlsCard.visibility = View.GONE; binding.cameraFollowToggle.visibility = View.GONE
        binding.cancelRouteButton.visibility = View.GONE; binding.addFavoriteButton.visibility = View.GONE; binding.startSearchContainer.visibility = View.GONE; binding.useCurrentLocationContainer.visibility = View.GONE
        binding.searchCard.visibility = View.VISIBLE; binding.getFakeLocation.visibility = View.VISIBLE
    }

    private fun onRestartNavigation() { binding.completionActionsCard.visibility = View.GONE; isDriving = false; isPaused = false; completedPathPoints.clear(); if (routePoints.isNotEmpty()) startNavigation() else showToast("Dữ liệu đã mất") }
    private fun onStopNavigationEarly() { locationService?.stopRouteSimulation() }
    private fun toggleNavigationControls() { val expanded = !PrefManager.navControlsExpanded; PrefManager.navControlsExpanded = expanded; binding.navControlsExpandable.visibility = if (expanded) View.VISIBLE else View.GONE; binding.navControlsToggle.setImageResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more) }
    private fun restoreNavigationControlsState() { val expanded = PrefManager.navControlsExpanded; binding.navControlsExpandable.visibility = if (expanded) View.VISIBLE else View.GONE; binding.navControlsToggle.setImageResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more) }

    private fun getBitmapFromDrawable(resId: Int): Bitmap {
        val drawable = ContextCompat.getDrawable(this, resId)
        return if (drawable is BitmapDrawable) {
            drawable.bitmap
        } else {
            val bitmap = Bitmap.createBitmap(drawable!!.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        }
    }
}
