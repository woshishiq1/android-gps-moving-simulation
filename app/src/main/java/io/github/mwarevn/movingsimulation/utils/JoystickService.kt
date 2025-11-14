package io.github.mwarevn.movingsimulation.utils

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.*
import io.github.controlwear.virtual.joystick.android.JoystickView
import io.github.mwarevn.movingsimulation.R
import kotlin.math.cos
import kotlin.math.sin

class JoystickService : Service(),View.OnTouchListener,View.OnClickListener {

    private var wm: WindowManager? = null
    private var mJoystickContainerView: View? = null
    private var mJoystickView: JoystickView? = null
    private var mJoystickLayoutParams: WindowManager.LayoutParams? = null
    private var lat : Double = PrefManager.getLat
    private var lon : Double = PrefManager.getLng

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        wm =  getSystemService(WINDOW_SERVICE) as WindowManager
        val mInflater :LayoutInflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        mJoystickContainerView = mInflater.inflate(R.layout.joystick, null as ViewGroup?) as View
        mJoystickView = mJoystickContainerView!!.findViewById(R.id.joystickView_right)
        mJoystickView?.setOnTouchListener { v, event ->
            if (event.action == 1){
                try {
                    lat = PrefManager.getLat
                    lon = PrefManager.getLng
                    updateLocation(lat, lon)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

            }
            false
        }
        // mJoystickView?.setOnMoveListener { angle, strength ->
        mJoystickView?.setOnMoveListener { angle, strength, event ->
            try {
                // Get camera bearing (map rotation) for screen-relative movement
                val cameraBearing = PrefManager.cameraBearing
                
                // Adjust joystick angle by camera bearing to maintain screen-relative direction
                // When camera rotates, we need to compensate so "up" on joystick = "up" on screen
                val adjustedAngle = angle - cameraBearing
                val radians = Math.toRadians(adjustedAngle.toDouble())
                
                android.util.Log.d("JoystickService", "Joystick moved: angle=$angle, strength=$strength, cameraBearing=$cameraBearing, adjustedAngle=$adjustedAngle")
                
                // Calculate movement factors based on adjusted angle
                // cos/sin give us the X/Y components of movement
                val factorX: Double = cos(radians) / 100000.0 * (strength / 30)
                val factorY: Double = sin(radians) / 100000.0 * (strength / 30)
                
                // Apply movement to current position
                lon = PrefManager.getLng + factorX
                lat = PrefManager.getLat + factorY
                
                android.util.Log.d("JoystickService", "New position: lat=$lat, lon=$lon")
                android.util.Log.d("JoystickService", "Updating location to PrefManager: lat=$lat, lon=$lon, start=true")
                
                updateLocation(lat, lon)

            }catch (e : Exception){
                e.printStackTrace()
                android.util.Log.e("JoystickService", "Error in joystick movement: ${e.message}", e)
            }
        }
        mJoystickLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        mJoystickLayoutParams?.let {
            it.gravity = Gravity.LEFT
        }

        wm!!.addView(mJoystickContainerView,mJoystickLayoutParams)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        TODO("Not yet implemented")
    }

    override fun onClick(v: View?) {
        TODO("Not yet implemented")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (this.mJoystickContainerView != null) {
            this.wm!!.removeView(mJoystickContainerView);
            this.mJoystickContainerView = null;
        }
    }

    private fun updateLocation(lat : Double,lon : Double){
        // JoystickService uses direct PrefManager for manual control (not auto-route)
        // This maintains consistency with the original manual GPS control feature
        // Always set start = true to ensure GPS is active when joystick is used
        PrefManager.update(start = true, la = lat, ln = lon)

    }

}