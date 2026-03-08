package io.github.mwarevn.fakegps.data.map

import android.graphics.Bitmap
import io.github.mwarevn.fakegps.domain.map.IMapController
import io.github.mwarevn.fakegps.domain.model.LatLng
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.extension.style.layers.properties.generated.IconAnchor
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.animation.easeTo
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.*
import java.util.*

class MapboxController(
    private val mapView: MapView,
    private val mapboxMap: MapboxMap,
    private val pointAnnotationManager: PointAnnotationManager,
    private val polylineAnnotationManager: PolylineAnnotationManager,
    private val icons: MapIcons
) : IMapController {

    data class MapIcons(
        val locationIcon: Bitmap,
        val destinationIcon: Bitmap,
        val startIcon: Bitmap
    )

    private var currentLocationMarker: PointAnnotation? = null
    private var startMarker: PointAnnotation? = null
    private var destinationMarker: PointAnnotation? = null
    private var currentRouteLine: PolylineAnnotation? = null
    private var completedPolyline: PolylineAnnotation? = null

    override fun moveCamera(position: LatLng, zoom: Double, animate: Boolean) {
        val cameraOptions = CameraOptions.Builder()
            .center(Point.fromLngLat(position.longitude, position.latitude))
            .zoom(zoom)
            .build()
        if (animate) {
            mapView.camera.easeTo(cameraOptions)
        } else {
            mapboxMap.setCamera(cameraOptions)
        }
    }

    override fun loadStyle(styleUri: String, onComplete: () -> Unit) {
        mapboxMap.loadStyleUri(styleUri) {
            onComplete()
        }
    }

    override fun addOnCameraChangeListener(listener: (Double) -> Unit) {
        mapboxMap.subscribeCameraChanged {
            listener(mapboxMap.cameraState.bearing)
        }
    }

    override fun updateFakeLocationMarker(position: LatLng, visible: Boolean) {
        currentLocationMarker?.let { pointAnnotationManager.delete(it) }
        if (!visible) {
            currentLocationMarker = null
            return
        }
        val options = PointAnnotationOptions()
            .withPoint(Point.fromLngLat(position.longitude, position.latitude))
            .withIconImage(icons.locationIcon)
            .withIconSize(2.5)
            .withIconAnchor(IconAnchor.BOTTOM)
            .withDraggable(false)
        currentLocationMarker = pointAnnotationManager.create(options)
    }

    override fun setDestinationMarker(position: LatLng, visible: Boolean) {
        destinationMarker?.let { pointAnnotationManager.delete(it) }
        if (!visible) {
            destinationMarker = null
            return
        }
        val options = PointAnnotationOptions()
            .withPoint(Point.fromLngLat(position.longitude, position.latitude))
            .withIconImage(icons.destinationIcon)
            .withIconSize(2.5)
            .withIconAnchor(IconAnchor.BOTTOM)
            .withDraggable(true)
        destinationMarker = pointAnnotationManager.create(options)
    }

    override fun setStartMarker(position: LatLng, visible: Boolean) {
        startMarker?.let { pointAnnotationManager.delete(it) }
        if (!visible) {
            startMarker = null
            return
        }
        val options = PointAnnotationOptions()
            .withPoint(Point.fromLngLat(position.longitude, position.latitude))
            .withIconImage(icons.startIcon)
            .withIconSize(2.5)
            .withIconAnchor(IconAnchor.BOTTOM)
            .withDraggable(true)
        startMarker = pointAnnotationManager.create(options)
    }

    override fun setDestinationDraggable(draggable: Boolean) {
        destinationMarker?.isDraggable = draggable
    }
    
    override fun setStartDraggable(draggable: Boolean) {
        startMarker?.isDraggable = draggable
    }

    override fun clearDestinationMarker() {
        destinationMarker?.let { pointAnnotationManager.delete(it) }
        destinationMarker = null
    }

    override fun clearStartMarker() {
        startMarker?.let { pointAnnotationManager.delete(it) }
        startMarker = null
    }

    override fun clearAllMarkers() {
        currentLocationMarker?.let { pointAnnotationManager.delete(it) }
        startMarker?.let { pointAnnotationManager.delete(it) }
        destinationMarker?.let { pointAnnotationManager.delete(it) }
        currentLocationMarker = null
        startMarker = null
        destinationMarker = null
    }

    override fun hasDestinationMarker(): Boolean = destinationMarker != null
    override fun hasStartMarker(): Boolean = startMarker != null

    override fun getDestinationPosition(): LatLng? = destinationMarker?.point?.let { LatLng(it.latitude(), it.longitude()) }
    override fun getStartPosition(): LatLng? = startMarker?.point?.let { LatLng(it.latitude(), it.longitude()) }

    override fun getDestinationId(): String? = destinationMarker?.id
    override fun getStartId(): String? = startMarker?.id

    override fun drawRoute(points: List<LatLng>, color: String, width: Double) {
        clearRoute()
        val routePoints = points.map { Point.fromLngLat(it.longitude, it.latitude) }
        val options = PolylineAnnotationOptions()
            .withPoints(routePoints)
            .withLineColor(color)
            .withLineWidth(width)
        currentRouteLine = polylineAnnotationManager.create(options)
    }

    override fun drawCompletedPath(points: List<LatLng>, color: String, width: Double) {
        completedPolyline?.let { polylineAnnotationManager.delete(it) }
        val routePoints = points.map { Point.fromLngLat(it.longitude, it.latitude) }
        val options = PolylineAnnotationOptions()
            .withPoints(routePoints)
            .withLineColor(color)
            .withLineWidth(width)
        completedPolyline = polylineAnnotationManager.create(options)
    }

    override fun clearRoute() {
        currentRouteLine?.let { polylineAnnotationManager.delete(it) }
        completedPolyline?.let { polylineAnnotationManager.delete(it) }
        currentRouteLine = null
        completedPolyline = null
    }

    override fun checkPermissions(): Boolean {
        // This will be delegated back to Activity or handled via a permission checker
        return true 
    }

    override fun requestPermissions() {
        // Delegate to Activity
    }
}
