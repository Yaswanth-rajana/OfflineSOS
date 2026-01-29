package com.yaswanth.offlinesos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.View
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlin.math.roundToInt

class TrackingActivity : AppCompatActivity(), SensorEventListener, LocationListener {

    private lateinit var tvDistance: TextView
    private lateinit var tvCoordinates: TextView
    private lateinit var ivArrow: ImageView
    
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    
    // Sensors
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null
    
    // Sensor Data
    private var gravity: FloatArray? = null
    private var geomagnetic: FloatArray? = null
    
    // Locations
    private var targetLat: Double = 0.0
    private var targetLon: Double = 0.0
    private var isTargetValid = false
    private var myLocation: Location? = null
    
    // Orientation
    private var currentAzimuth = 0f
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tracking)

        tvDistance = findViewById(R.id.tvDistance)
        tvCoordinates = findViewById(R.id.tvCoordinates)
        ivArrow = findViewById(R.id.ivArrow)
        
        // Get Intent Data
        targetLat = intent.getDoubleExtra("LAT", 0.0)
        targetLon = intent.getDoubleExtra("LON", 0.0) 
        
        // Check Validity
        if (targetLat == 0.0 && targetLon == 0.0) {
            isTargetValid = false
        } else {
            isTargetValid = true
        }

        // Setup UI
        if (isTargetValid) {
            tvCoordinates.text = "Victim Location:\nLat: %.6f\nLon: %.6f".format(targetLat, targetLon)
            ivArrow.visibility = View.VISIBLE
        } else {
            tvCoordinates.text = "Victim Location:\nUnavailable"
            ivArrow.visibility = View.INVISIBLE
            tvDistance.text = "Distance: Unknown\n(Location data missing)"
            Toast.makeText(this, "Valid coordinates unavailable for tracking", Toast.LENGTH_LONG).show()
        }
        
        // Setup System Services
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        
        if (accelerometer == null || magnetometer == null) {
            if (isTargetValid) Toast.makeText(this, "Sensors missing. Compass will not work.", Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onResume() {
        super.onResume()
        
        if (!isTargetValid) return // Don't start sensors if no target

        // Register Sensors
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        magnetometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        
        // Request Location Updates (Live)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 1f, this)
            // Also try Network provider for faster fix
             val providers = locationManager.getProviders(true)
             for (provider in providers) {
                 locationManager.requestLocationUpdates(provider, 1000, 1f, this)
             }
             
             // Get last known immediately
             val lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
             if (lastKnown != null) {
                 onLocationChanged(lastKnown)
             }
        }
    }
    
    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(this)
    }

    // Low Pass Filter Constants
    private val ALPHA = 0.10f // 10% new data, 90% old data (Smoothing factor)

    private fun lowPass(input: FloatArray, output: FloatArray?): FloatArray {
        if (output == null) return input
        for (i in input.indices) {
            output[i] = output[i] + ALPHA * (input[i] - output[i])
        }
        return output
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !isTargetValid) return
        
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            gravity = lowPass(event.values.clone(), gravity)
        } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            geomagnetic = lowPass(event.values.clone(), geomagnetic)
        }
        
        if (gravity != null && geomagnetic != null) {
            val R = FloatArray(9)
            val I = FloatArray(9)
            if (SensorManager.getRotationMatrix(R, I, gravity, geomagnetic)) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(R, orientation)
                // orientation[0] is azimuth in radians
                val azimuthRad = orientation[0]
                var azimuthDeg = Math.toDegrees(azimuthRad.toDouble()).toFloat()
                
                // Adjust for True North if we have location (Geomagnetic Field)
                if (myLocation != null) {
                     val geoField = android.hardware.GeomagneticField(
                         myLocation!!.latitude.toFloat(),
                         myLocation!!.longitude.toFloat(),
                         myLocation!!.altitude.toFloat(),
                         System.currentTimeMillis()
                     )
                     azimuthDeg += geoField.declination
                }
                
                updateArrow(azimuthDeg)
            }
        }
    }

    private fun updateArrow(azimuthDeg: Float) {
        if (myLocation == null || !isTargetValid) return
        
        val targetLoc = Location("target")
        targetLoc.latitude = targetLat
        targetLoc.longitude = targetLon
        
        // Bearing from Me to Target (True North)
        var bearing = myLocation!!.bearingTo(targetLoc)
        
        // Direction arrow should point = Bearing - MyHeading
        var direction = bearing - azimuthDeg
        
        // Normalize
        while (direction < 0) direction += 360
        while (direction >= 360) direction -= 360

        // Optimize rotation direction
        var diff = direction - currentAzimuth
        while (diff < -180) diff += 360
        while (diff > 180) diff -= 360
        val targetRotate = currentAzimuth + diff

        // Smooth Animation
        val animation = RotateAnimation(
            currentAzimuth,
            targetRotate,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        )
        animation.duration = 250
        animation.fillAfter = true
        
        ivArrow.startAnimation(animation)
        currentAzimuth = targetRotate
    }

    override fun onLocationChanged(location: Location) {
        myLocation = location
        if (!isTargetValid) return

        // Update Distance
        val results = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, targetLat, targetLon, results)
        val distance = results[0]
        
        if (distance >= 1000) {
            tvDistance.text = "%.2f km".format(distance / 1000)
        } else {
            tvDistance.text = "%.0f m".format(distance)
        }
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
