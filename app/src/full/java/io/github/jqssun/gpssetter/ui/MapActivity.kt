package io.github.mwarevn.fakegpsmoving.ui


import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import android.graphics.Color
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import io.github.mwarevn.fakegpsmoving.R
import io.github.mwarevn.fakegpsmoving.utils.ext.getAddress
import io.github.mwarevn.fakegpsmoving.utils.ext.showToast
import io.github.mwarevn.fakegpsmoving.network.OsrmClient
import io.github.mwarevn.fakegpsmoving.utils.PolylineUtils
import io.github.mwarevn.fakegpsmoving.utils.RouteSimulator
import android.location.Geocoder
import android.location.Location
import java.util.Locale
import android.location.Address
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.animation.LinearInterpolator
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.regex.Pattern
import android.text.TextWatcher
import android.text.Editable
import kotlinx.coroutines.Job

typealias CustomLatLng = LatLng

class MapActivity: BaseMapActivity(), OnMapReadyCallback, GoogleMap.OnMapClickListener {

    private lateinit var mMap: GoogleMap
    private var mLatLng: LatLng? = null
    private var mMarker: Marker? = null
    private var mMarkerStart: Marker? = null
    private var mMarkerDest: Marker? = null
    private var mRoutePolyline: Polyline? = null
    private var mDonePolyline: Polyline? = null
    private var routeSimulator: RouteSimulator? = null
    private var isDriving: Boolean = false
    private var isPaused: Boolean = false
    private var originalPoints: List<LatLng> = emptyList()
    private var activeInput: Int = 0 // 0 none, 1 start, 2 dest
    private var routePlotted: Boolean = false
    // Removed isRouteMode - app now only supports route mode + basic set/unset location

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable GPS drift simulation for natural movement
        io.github.mwarevn.fakegpsmoving.utils.PrefManager.isRandomPosition = true
        // Use application-level hooking for better stealth (safer than system hook)
        io.github.mwarevn.fakegpsmoving.utils.PrefManager.isSystemHooked = false
        // Set realistic GPS accuracy (10-20 meters like real GPS)
        io.github.mwarevn.fakegpsmoving.utils.PrefManager.accuracy = "15"
    }

    override fun hasMarker(): Boolean {
        if (!mMarker?.isVisible!!) {
            return true
        }
        return false
    }
    private fun updateMarker(it: LatLng) {
        mMarker?.position = it!!
        mMarker?.isVisible = true
    }
    private fun removeMarker() {
        mMarker?.isVisible = false
    }
    override fun initializeMap() {
        val mapFragment = SupportMapFragment.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(R.id.map, mapFragment)
            .commit()
        mapFragment?.getMapAsync(this)
    }
    override fun moveMapToNewLocation(moveNewLocation: Boolean) {
        if (moveNewLocation) {
            mLatLng = LatLng(lat, lon)
            mLatLng.let { latLng ->
                mMap.animateCamera(CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                        .target(latLng!!)
                        .zoom(12.0f)
                        .bearing(0f)
                        .tilt(0f)
                        .build()
                ))
                mMarker?.apply {
                    position = latLng
                    isVisible = true
                    showInfoWindow()
                }
            }
        }
    }
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        with(mMap){


            // gms custom ui
            if (ActivityCompat.checkSelfPermission(this@MapActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                setMyLocationEnabled(true);
            } else {
                ActivityCompat.requestPermissions(this@MapActivity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 99);
            }
            setTrafficEnabled(true)
            uiSettings.isMyLocationButtonEnabled = false
            uiSettings.isZoomControlsEnabled = false
            uiSettings.isCompassEnabled = false
            setPadding(0,80,0,0)
            mapType = viewModel.mapType


            val zoom = 12.0f
            lat = viewModel.getLat
            lon  = viewModel.getLng
            mLatLng = LatLng(lat, lon)
            mLatLng.let {
                mMarker = addMarker(
                    MarkerOptions().position(it!!).draggable(false).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)).visible(false)
                )
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(it, zoom))
            }


            setOnMapClickListener(this@MapActivity)
            if (viewModel.isStarted){
                mMarker?.let {
                    // TODO:
                    // it.isVisible = true
                    // it.showInfoWindow()
                }
            }
        }
    }
    override fun onMapClick(latLng: LatLng) {
        // Don't allow marking during driving
        if (isDriving) {
            showToast("Cannot change markers while driving")
            return
        }

        // Route mode logic
        // Map click selects a location according to currently active input (1 = start, 2 = dest)
        when (activeInput) {
            1 -> {
                // set start marker
                mMarkerStart?.remove()
                mMarkerStart = mMap.addMarker(
                    MarkerOptions().position(latLng).draggable(false)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                        .title("Start")
                )
                showToast("Start location marked")
                // Show destination input after start is marked
                binding.search.searchBox.visibility = View.VISIBLE
                activeInput = 0

                // Keep original search text, don't overwrite with coordinates
                // binding.search.startBox.setText("${latLng.latitude}, ${latLng.longitude}")
            }
            2 -> {
                mMarkerDest?.remove()
                mMarkerDest = mMap.addMarker(
                    MarkerOptions().position(latLng).draggable(false)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                        .title("Destination")
                )
                showToast("Destination marked")
                activeInput = 0

                // Keep original search text, don't overwrite with coordinates
                // binding.search.searchBox.setText("${latLng.latitude}, ${latLng.longitude}")
            }
            else -> {
                // Allow re-marking by double-tap or when no active input
                // Check which marker is closer to tap and allow re-marking it
                val startDist = mMarkerStart?.position?.let { start ->
                    val results = FloatArray(1)
                    android.location.Location.distanceBetween(
                        latLng.latitude, latLng.longitude,
                        start.latitude, start.longitude,
                        results
                    )
                    results[0]
                } ?: Float.MAX_VALUE

                val destDist = mMarkerDest?.position?.let { dest ->
                    val results = FloatArray(1)
                    android.location.Location.distanceBetween(
                        latLng.latitude, latLng.longitude,
                        dest.latitude, dest.longitude,
                        results
                    )
                    results[0]
                } ?: Float.MAX_VALUE

                // If tap is close to existing marker (within 100m), re-mark it
                if (startDist < 100f && startDist <= destDist) {
                    activeInput = 1
                    onMapClick(latLng) // Recursive call to handle as start marker
                    return
                } else if (destDist < 100f && destDist < startDist) {
                    activeInput = 2
                    onMapClick(latLng) // Recursive call to handle as dest marker
                    return
                } else if (mMarkerStart == null) {
                    // No start marker yet, set as start
                    activeInput = 1
                    onMapClick(latLng)
                    return
                } else if (mMarkerDest == null) {
                    // No dest marker yet, set as dest
                    activeInput = 2
                    onMapClick(latLng)
                    return
                } else {
                    // Both markers exist, show options
                    MaterialAlertDialogBuilder(this@MapActivity)
                        .setTitle("Update Location")
                        .setMessage("Which location do you want to update?")
                        .setPositiveButton("Start") { _, _ ->
                            activeInput = 1
                            onMapClick(latLng)
                        }
                        .setNegativeButton("Destination") { _, _ ->
                            activeInput = 2
                            onMapClick(latLng)
                        }
                        .setNeutralButton("Cancel", null)
                        .show()
                    return
                }
            }
        }

        // Clear any existing route when markers change
        if (routePlotted) {
            resetRouteState()
        }

        // if both markers present and no route plotted, show drive button
        if (mMarkerStart != null && mMarkerDest != null && !routePlotted) {
            binding.driveButton.visibility = View.VISIBLE
            binding.newRouteButton.visibility = View.VISIBLE
            showToast("Both locations set. Press 'Di chuyển' to plot route")
        } else if (mMarkerStart != null || mMarkerDest != null) {
            binding.driveButton.visibility = View.GONE
            binding.newRouteButton.visibility = View.VISIBLE
        } else {
            binding.driveButton.visibility = View.GONE
            binding.newRouteButton.visibility = View.GONE
        }
    }

    override fun getActivityInstance(): BaseMapActivity {
        return this@MapActivity
    }

    // Helper method to reset all route-related state
    private fun resetRouteState() {
        routeSimulator?.stop()
        routeSimulator = null
        isDriving = false
        isPaused = false
        routePlotted = false
        originalPoints = emptyList()

        // Remove polylines
        mRoutePolyline?.remove()
        mRoutePolyline = null
        mDonePolyline?.remove()
        mDonePolyline = null

        // Hide route controls
        binding.startButtonRoute.visibility = View.GONE
        binding.pauseButtonRoute.visibility = View.GONE
        binding.resumeButtonRoute.visibility = View.GONE
        binding.cancelButtonRoute.visibility = View.GONE
        binding.cancelButtonRoute.text = "Hủy" // Reset text
        binding.driveControls.visibility = View.GONE

        // Show appropriate buttons based on marker state
        if (mMarkerStart != null && mMarkerDest != null) {
            binding.driveButton.visibility = View.VISIBLE
            binding.newRouteButton.visibility = View.VISIBLE
        } else if (mMarkerStart != null || mMarkerDest != null) {
            binding.driveButton.visibility = View.GONE
            binding.newRouteButton.visibility = View.VISIBLE
        } else {
            binding.driveButton.visibility = View.GONE
            binding.newRouteButton.visibility = View.GONE
        }
    }

    // Helper method to reset all markers and inputs
    private fun resetAllState() {
        resetRouteState()

        // Remove markers
        mMarkerStart?.remove()
        mMarkerStart = null
        mMarkerDest?.remove()
        mMarkerDest = null

        // Clear inputs
        binding.search.startBox.text?.clear()
        binding.search.searchBox.text?.clear()
        binding.search.searchBox.visibility = View.GONE
        binding.newRouteButton.visibility = View.GONE

        // Reset input state
        activeInput = 0

        showToast("Ready for new route planning")
    }

    private suspend fun resolveAddressToLatLng(address: String): LatLng? = withContext(Dispatchers.IO) {
        try {
            // check if input looks like coordinates "lat,lon" or "lon,lat"
            val coordPattern = Pattern.compile("[-+]?\\d{1,3}([.]\\d+)?, *[-+]?\\d{1,3}([.]\\d+)?")
            val matcher = coordPattern.matcher(address)
            if (matcher.matches()) {
                val parts = address.split(",").map { it.trim() }
                val a = parts[0].toDouble()
                val b = parts[1].toDouble()
                // assume lat,lon
                return@withContext LatLng(a, b)
            }

            val geocoder = Geocoder(this@MapActivity, Locale.getDefault())
            val list: List<Address>? = try {
                geocoder.getFromLocationName(address, 1)
            } catch (e: Exception) {
                null
            }
            if (list != null && list.isNotEmpty()) {
                val addr = list[0]
                return@withContext LatLng(addr.latitude, addr.longitude)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
    }

    private fun updatePolylines(pos: LatLng) {
        if (originalPoints.isEmpty()) return
        val pts = originalPoints
        var bestIdx = 0
        var bestT = 0.0
        var bestDist = Double.MAX_VALUE

        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val dx = b.latitude - a.latitude
            val dy = b.longitude - a.longitude
            val denom = dx * dx + dy * dy
            if (denom == 0.0) continue
            val t = ((pos.latitude - a.latitude) * dx + (pos.longitude - a.longitude) * dy) / denom
            val tc = t.coerceIn(0.0, 1.0)
            val projLat = a.latitude + tc * dx
            val projLng = a.longitude + tc * dy
            val proj = LatLng(projLat, projLng)
            val dist = PolylineUtils.haversineDistanceMeters(proj, pos)
            if (dist < bestDist) {
                bestDist = dist
                bestIdx = i
                bestT = tc
            }
        }

        val done = ArrayList<LatLng>()
        for (i in 0..bestIdx) done.add(pts[i])
        // add projected point
        val a = pts[bestIdx]
        val b = pts[bestIdx + 1]
        val projLat = a.latitude + bestT * (b.latitude - a.latitude)
        val projLng = a.longitude + bestT * (b.longitude - a.longitude)
        val projPoint = LatLng(projLat, projLng)
        done.add(projPoint)

        val remain = ArrayList<LatLng>()
        remain.add(projPoint)
        for (i in bestIdx + 1 until pts.size) remain.add(pts[i])

        mDonePolyline?.points = done
        mRoutePolyline?.points = remain
    }

    private var markerAnimator: ValueAnimator? = null
    private fun animateMarkerTo(target: LatLng) {
        val marker = mMarker ?: return
        marker.isVisible = true
        val start = marker.position
        markerAnimator?.cancel()
        val animDuration = 150L
        markerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = animDuration
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                val lat = start.latitude + (target.latitude - start.latitude) * t
                val lon = start.longitude + (target.longitude - start.longitude) * t
                marker.position = LatLng(lat, lon)
            }
            start()
        }
    }

    private var lastCameraUpdate: Long = 0
    private val cameraThrottleMs: Long = 800
    private fun maybeUpdateCamera(pos: LatLng) {
        val now = System.currentTimeMillis()
        if (now - lastCameraUpdate > cameraThrottleMs) {
            lastCameraUpdate = now
            mMap.animateCamera(CameraUpdateFactory.newLatLng(pos))
        }
    }

    @SuppressLint("MissingPermission")
    override fun setupButtons(){
        binding.addfavorite.setOnClickListener {
            addFavoriteDialog()
        }
        binding.getlocation.setOnClickListener {
            getLastLocation()
        }

        // Show route planning UI by default
        binding.search.startBox.visibility = View.VISIBLE

        // Initially hide destination input and route-related buttons
        binding.search.searchBox.visibility = View.GONE
        binding.driveButton.visibility = View.GONE
        binding.newRouteButton.visibility = View.GONE
        binding.startButtonRoute.visibility = View.GONE
        binding.pauseButtonRoute.visibility = View.GONE
        binding.resumeButtonRoute.visibility = View.GONE
        binding.cancelButtonRoute.visibility = View.GONE
        binding.driveControls.visibility = View.GONE

        // Track focus on startBox
        binding.search.startBox.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !isDriving) {
                activeInput = 1
                showToast("Tap map to set start location")
            }
        }

        // Track focus on searchBox
        binding.search.searchBox.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !isDriving) {
                activeInput = 2
                showToast("Tap map to set destination")
            }
        }

        // Auto-search for start location with delay
        var startSearchJob: Job? = null
        binding.search.startBox.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val text = s.toString().trim()
                if (text.length >= 3) { // Minimum 3 characters to search
                    startSearchJob?.cancel()
                    startSearchJob = lifecycleScope.launch {
                        delay(1000) // Wait 1 second after user stops typing
                        val latlon = resolveAddressToLatLng(text)
                        latlon?.let {
                            // Move and zoom to location with higher zoom level
                            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(it, 18f))

                            // Auto-mark start location
                            mMarkerStart?.remove()
                            mMarkerStart = mMap.addMarker(
                                MarkerOptions().position(it).draggable(false)
                                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                                    .title("Start")
                            )

                            // Show destination input
                            binding.search.searchBox.visibility = View.VISIBLE
                            showToast("Start location found and marked")
                        }
                    }
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Auto-search for destination with delay
        var destSearchJob: Job? = null
        binding.search.searchBox.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val text = s.toString().trim()
                if (text.length >= 3) { // Minimum 3 characters to search
                    destSearchJob?.cancel()
                    destSearchJob = lifecycleScope.launch {
                        delay(1000) // Wait 1 second after user stops typing
                        val latlon = resolveAddressToLatLng(text)
                        latlon?.let {
                            // Move and zoom to location with higher zoom level
                            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(it, 18f))

                            // Auto-mark destination location
                            mMarkerDest?.remove()
                            mMarkerDest = mMap.addMarker(
                                MarkerOptions().position(it).draggable(false)
                                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                                    .title("Destination")
                            )

                            showToast("Destination found and marked")

                            // Show drive button if both markers exist
                            if (mMarkerStart != null && mMarkerDest != null && !routePlotted) {
                                binding.driveControls.visibility = View.VISIBLE
                            }
                        }
                    }
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // handle IME action on startBox: geocode, move map, and auto-mark start location
        binding.search.startBox.setOnEditorActionListener { v, actionId, _ ->
            val text = v.text.toString().trim()
            if (text.isNotEmpty()) {
                lifecycleScope.launch {
                    val latlon = resolveAddressToLatLng(text)
                    latlon?.let {
                        // Move and zoom to location with higher zoom level
                        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(it, 18f))

                        // Auto-mark start location
                        mMarkerStart?.remove()
                        mMarkerStart = mMap.addMarker(
                            MarkerOptions().position(it).draggable(false)
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                                .title("Start")
                        )

                        // Show destination input and allow refinement
                        binding.search.searchBox.visibility = View.VISIBLE
                        activeInput = 1 // Keep start input active for refinement
                        showToast("Start marked. Click map to adjust or enter destination address")
                    } ?: showToast("Start address not found")
                }
                true
            } else {
                false
            }
        }

        // handle IME action on dest (searchBox): geocode, move map, and auto-mark destination
        binding.search.searchBox.setOnEditorActionListener { v, actionId, _ ->
            val text = v.text.toString().trim()
            if (text.isNotEmpty()) {
                lifecycleScope.launch {
                    val latlon = resolveAddressToLatLng(text)
                    latlon?.let {
                        // Move and zoom to location with higher zoom level
                        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(it, 18f))

                        // Auto-mark destination location
                        mMarkerDest?.remove()
                        mMarkerDest = mMap.addMarker(
                            MarkerOptions().position(it).draggable(false)
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                                .title("Destination")
                        )

                        activeInput = 2 // Keep destination input active for refinement
                        showToast("Destination marked. Click map to adjust or press 'Di chuyển' to plan route")

                        // Show drive button if both markers exist
                        if (mMarkerStart != null && mMarkerDest != null && !routePlotted) {
                            binding.driveButton.visibility = View.VISIBLE
                            binding.newRouteButton.visibility = View.VISIBLE
                        }
                    } ?: showToast("Destination address not found")
                }
                true
            } else {
                false
            }
        }

        // drive button: when both markers exist, fetch route and draw it (becomes routePlotted)
        binding.driveButton.setOnClickListener {
            if (mMarkerStart == null || mMarkerDest == null) {
                showToast("Please set both start and destination")
                return@setOnClickListener
            }

            lifecycleScope.launch {
                try {
                    val start = mMarkerStart!!.position
                    val dest = mMarkerDest!!.position
                    val coords = "${start.longitude},${start.latitude};${dest.longitude},${dest.latitude}"
                    val resp = OsrmClient.service.getRoute(coords)
                    val geom = resp.routes?.firstOrNull()?.geometry
                    if (geom == null) {
                        showToast("Route not found")
                        return@launch
                    }
                    val pts = PolylineUtils.decode(geom, 6)
                    // draw polylines
                    originalPoints = pts
                    mDonePolyline?.remove()
                    mRoutePolyline?.remove()
                    mDonePolyline = mMap.addPolyline(PolylineOptions().color(Color.GRAY).width(8f))
                    mRoutePolyline = mMap.addPolyline(PolylineOptions().addAll(pts).color(Color.BLUE).width(8f))
                    routePlotted = true

                    // Hide drive button and show start/cancel buttons
                    binding.driveButton.visibility = View.GONE
                    binding.startButtonRoute.visibility = View.VISIBLE // start journey
                    binding.cancelButtonRoute.visibility = View.VISIBLE // cancel route
                    // No speed controls needed - using realistic motorbike speed

                    showToast("🏍️ Motorbike route ready! Natural GPS drift enabled. Press Start to begin.")
                } catch (e: Exception) {
                    e.printStackTrace()
                    showToast("Failed to fetch route: ${e.message}")
                }
            }
        }

        // New route start button handler
        binding.startButtonRoute.setOnClickListener {
            if (routePlotted && !isDriving) {
                // start journey along plotted route with motorbike speed (35-50 km/h)
                val motorbikeSpeed = (35..50).random().toDouble() // Random motorbike speed for realism
                routeSimulator?.stop()
                routeSimulator = RouteSimulator(originalPoints, speedKmh = motorbikeSpeed)
                // set app-wide start flag at beginning
                originalPoints.firstOrNull()?.let { st ->
                    viewModel.update(true, st.latitude, st.longitude)
                }

                showToast("🏍️ Starting motorbike simulation (${motorbikeSpeed.toInt()} km/h) with natural GPS drift")

                routeSimulator?.start(onPosition = { pos ->
                    runOnUiThread {
                        // Update GPS using the same method as "Set Location" feature
                        viewModel.update(true, pos.latitude, pos.longitude)
                        animateMarkerTo(pos)
                        updatePolylines(pos)
                        maybeUpdateCamera(pos)
                    }
                }, onComplete = {
                    runOnUiThread {
                        isDriving = false
                        // Hide pause/resume buttons and reset cancel button
                        binding.pauseButtonRoute.visibility = View.GONE
                        binding.resumeButtonRoute.visibility = View.GONE
                        binding.cancelButtonRoute.text = "Hủy"

                        // Ensure GPS stays at final destination with natural drift
                        originalPoints.lastOrNull()?.let { finalPos ->
                            viewModel.update(true, finalPos.latitude, finalPos.longitude)
                        }
                        MaterialAlertDialogBuilder(this@MapActivity)
                            .setTitle("Đã đến nơi")
                            .setMessage("🏍️ Arrived safely! Natural GPS drift and motorbike movement simulation active.")
                            .setPositiveButton("New Route") { _, _ ->
                                // Reset GPS to real location and clear all
                                viewModel.update(false, lat, lon)
                                resetAllState()
                            }
                            .setNegativeButton("Stay Here") { _, _ ->
                                // Keep current GPS location at destination, just reset route UI
                                resetRouteState()
                                showToast("GPS staying at destination location")
                            }
                            .show()
                    }
                })
                isDriving = true
                // Hide start button, show pause button and rename cancel to stop
                binding.startButtonRoute.visibility = View.GONE
                binding.pauseButtonRoute.visibility = View.VISIBLE
                binding.cancelButtonRoute.text = "Stop"
                showToast("Journey started")
            }
        }

        // Pause button handler
        binding.pauseButtonRoute.setOnClickListener {
            if (isDriving && routeSimulator?.isRunning() == true) {
                routeSimulator?.pause()
                isPaused = true
                binding.pauseButtonRoute.visibility = View.GONE
                binding.resumeButtonRoute.visibility = View.VISIBLE
                showToast("Journey paused")
            }
        }

        // Resume button handler
        binding.resumeButtonRoute.setOnClickListener {
            if (isDriving && isPaused) {
                routeSimulator?.resume()
                isPaused = false
                binding.resumeButtonRoute.visibility = View.GONE
                binding.pauseButtonRoute.visibility = View.VISIBLE
                showToast("Journey resumed")
            }
        }

        // New route cancel button handler
        binding.cancelButtonRoute.setOnClickListener {
            if (isDriving) {
                // Stop current journey
                MaterialAlertDialogBuilder(this@MapActivity)
                    .setTitle("Stop Journey")
                    .setMessage("Are you sure you want to stop the current journey?")
                    .setPositiveButton("Stop") { _, _ ->
                        viewModel.update(false, lat, lon)  // Reset GPS to real location
                        resetRouteState()
                        showToast("Journey stopped")
                    }
                    .setNegativeButton("Continue", null)
                    .show()
            } else if (routePlotted) {
                // Cancel plotted route - no need to reset GPS since we haven't started
                resetRouteState()
                showToast("Route cancelled")
            }
        }

        // New route button: clear all and start fresh
        binding.newRouteButton.setOnClickListener {
            if (isDriving) {
                MaterialAlertDialogBuilder(this@MapActivity)
                    .setTitle("Stop Current Journey")
                    .setMessage("Stop current journey and start planning new route?")
                    .setPositiveButton("Yes") { _, _ ->
                        viewModel.update(false, lat, lon)  // Reset GPS to real location
                        resetAllState()
                    }
                    .setNegativeButton("No", null)
                    .show()
            } else {
                // Reset GPS to real location and clear everything
                viewModel.update(false, lat, lon)
                resetAllState()
            }
        }

        // Legacy start button (for single location mock)
        if (viewModel.isStarted) {
            binding.startButton.visibility = View.GONE
            binding.stopButton.visibility = View.VISIBLE
        }

        binding.startButton.setOnClickListener {
            // legacy behaviour: set current location as start for system hook
            viewModel.update(true, lat, lon)
            mLatLng.let {
                updateMarker(it!!)
            }
            binding.startButton.visibility = View.GONE
            binding.stopButton.visibility = View.VISIBLE
            lifecycleScope.launch {
                mLatLng?.getAddress(getActivityInstance())?.let { address ->
                    address.collect{ value ->
                        showStartNotification(value)
                    }
                }
            }
            showToast(getString(R.string.location_set))
        }

        binding.stopButton.setOnClickListener {
            // legacy behaviour: unset mock location
            routeSimulator?.stop()
            routeSimulator = null
            isDriving = false
            mRoutePolyline?.remove()
            mRoutePolyline = null

            mLatLng.let {
                viewModel.update(false, it!!.latitude, it.longitude)
            }
            removeMarker()
            binding.stopButton.visibility = View.GONE
            binding.startButton.visibility = View.VISIBLE
            binding.driveControls.visibility = View.GONE
            cancelNotification()
            showToast(getString(R.string.location_unset))
        }
    }
}
