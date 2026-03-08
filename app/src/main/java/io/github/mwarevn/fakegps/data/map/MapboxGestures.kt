package io.github.mwarevn.fakegps.data.map

import com.mapbox.maps.plugin.annotation.Annotation
import com.mapbox.maps.plugin.annotation.generated.OnPointAnnotationDragListener
import com.mapbox.maps.plugin.annotation.generated.PointAnnotation
import io.github.mwarevn.fakegps.domain.map.IMapController

fun createDragListener(wrapper: IMapController.OnPointAnnotationDragListenerWrapper): OnPointAnnotationDragListener {
    return object : OnPointAnnotationDragListener {
        override fun onAnnotationDragStarted(annotation: Annotation<*>) {}
        override fun onAnnotationDrag(annotation: Annotation<*>) {}
        override fun onAnnotationDragFinished(annotation: Annotation<*>) {
            if (annotation is PointAnnotation) {
                wrapper.onAnnotationDragFinished(annotation.id, annotation.point)
            }
        }
    }
}
