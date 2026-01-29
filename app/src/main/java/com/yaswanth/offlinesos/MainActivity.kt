package com.yaswanth.offlinesos

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), BleManager.BleEventListener {

    // UI Elements
    private lateinit var switchMode: SwitchMaterial
    
    // Victim UI
    private lateinit var layoutVictim: View
    private lateinit var btnSos: Button
    private lateinit var tvVictimStatus: TextView
    private lateinit var tvReassurance: TextView
    private lateinit var etMessage: EditText
    
    // Rescuer UI
    private lateinit var layoutRescuer: View
    private lateinit var tvEmptyActive: TextView
    private lateinit var tvEmptyCompleted: TextView
    private lateinit var tvRescuerStatus: TextView
    
    // Adapters
    private lateinit var activeAdapter: SosAdapter
    private lateinit var completedAdapter: SosAdapter
    
    // Bluetooth
    private lateinit var bluetoothManager: BluetoothManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    
    // State
    private var isRescuer = false
    private var sosAnimator: ObjectAnimator? = null
    private val handler = Handler(Looper.getMainLooper())
    
    // Permissions
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                setupBluetooth()
                setupBluetooth()
                BleManager.startLocationWarmup()
            } else {
                showPermissionDeniedDialog()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        setContentView(R.layout.activity_main)

        BleManager.init(this)
        BleManager.setListener(this)

        initViews()
        setupBluetooth()
        
        switchMode.setOnCheckedChangeListener { _, isChecked ->
            isRescuer = isChecked
            // Stop everything before switching
            // stopAllOperations() // No, we manage this more carefully now.
            
            updateModeUI()
            if (isChecked) {
                // If switching to Rescuer mode
                BleManager.startRescuerMode()
                startRescuerLocationUpdates()
                tvRescuerStatus.text = "Rescuer Active: Listen mode"
            } else {
                // Return to victim mode
                BleManager.stopRescuerMode()
                stopRescuerLocationUpdates()
                tvVictimStatus.text = "Ready"
                startSosAnimation()
            }
        }

        btnSos.setOnClickListener {
            if (!isRescuer) {
                if (tvVictimStatus.text == "HELP IS ON THE WAY" || tvVictimStatus.text == "SOS SENT") {
                    AlertDialog.Builder(this)
                        .setTitle("Send New SOS?")
                        .setMessage("Help is already on the way. Do you want to send a NEW emergency signal?")
                        .setPositiveButton("Send New") { _, _ ->
                            initiateSos()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    initiateSos()
                }
            }
        }

        requestPermissionsInitial()
        
        // Restore UI state based on BleManager status if needed
        isRescuer = BleManager.isRescuerActive
        updateModeUI()
    }
    
    private fun initViews() {
        switchMode = findViewById(R.id.switchMode)
        layoutVictim = findViewById(R.id.layoutVictim)
        btnSos = findViewById(R.id.btnSos)
        tvVictimStatus = findViewById(R.id.tvVictimStatus)
        tvReassurance = findViewById(R.id.tvReassurance)
        etMessage = findViewById(R.id.etMessage)
        
        layoutRescuer = findViewById(R.id.layoutRescuer)
        tvEmptyActive = findViewById(R.id.tvEmptyActive)
        tvEmptyCompleted = findViewById(R.id.tvEmptyCompleted)
        tvRescuerStatus = findViewById(R.id.tvRescuerStatus)

        val rvActive = findViewById<RecyclerView>(R.id.rvActiveAlerts)
        val rvCompleted = findViewById<RecyclerView>(R.id.rvCompletedAlerts)
        
        activeAdapter = SosAdapter(BleManager.activeAlerts) { alert ->
            BleManager.activeAlerts.remove(alert)
            activeAdapter.notifyDataSetChanged()
            
            val finishedAlert = alert.copy(isFinished = true)
            BleManager.completedAlerts.add(0, finishedAlert)
            // Fix: Do not call addAlert again, as we just added to the backing list.
            completedAdapter.notifyDataSetChanged()
            
            updateRescuerEmptyStates()
        }
        
        completedAdapter = SosAdapter(BleManager.completedAlerts) { }

        rvActive.layoutManager = LinearLayoutManager(this)
        rvActive.adapter = activeAdapter
        rvCompleted.layoutManager = LinearLayoutManager(this)
        rvCompleted.adapter = completedAdapter
        
        updateRescuerEmptyStates()
    }
    
    override fun onResume() {
        super.onResume()
        BleManager.setListener(this) // Re-attach listener
        if (!hasPermissions()) return
        
        if (isRescuer) {
             // Ensure it's running if expected
             if (!BleManager.isRescuerActive) {
                 BleManager.startRescuerMode()
                 startRescuerLocationUpdates()
             }
        } else {
             // Victim Mode
             startSosAnimation() // Default animation
             BleManager.startLocationWarmup() // Start warm-up

             // Restore UI State
             when (BleManager.victimState) {
                 BleManager.VictimState.FETCHING_GPS -> updateVictimUiState("PENDING", "Fetching location...")
                 BleManager.VictimState.SCANNING -> updateVictimUiState("PENDING", "Searching for rescuers...")
                 BleManager.VictimState.SENT -> updateVictimUiState("SENT", null)
                 BleManager.VictimState.ACKNOWLEDGED -> updateVictimUiState("ACK", "Rescuer received your SOS!")
                 BleManager.VictimState.TIMEOUT -> updateVictimUiState("TIMEOUT", null)
                 else -> {
                     // IDLE or WARMUP - usually means Ready
                     if (tvVictimStatus.text != "Ready") {
                         updateVictimUiState("READY", null)
                     }
                 }
             }
        }
        
        // Refresh Lists
        activeAdapter.notifyDataSetChanged()
        updateRescuerEmptyStates()
    }

    override fun onPause() {
        super.onPause()
        if (!isRescuer) {
            stopSosAnimation()
            BleManager.stopLocationWarmup() // Save battery when backgrounded
        }
        // DO NOT stop BleManager operations here. They must persist.
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Only stop if we are actually killing the app logic, though usually we might want service.
        // For now, allow it to die with activity unless we used a Foreground Service.
        // Prompt says "decouple... so navigation to tracking or map views does not pause".
        // Navigating to TrackingActivity pauses MainActivity. So we are good.
        
        BleManager.setListener(null)
    }
    
    // =========================================================================
    // PERMISSIONS
    // =========================================================================

    private fun hasPermissions(): Boolean {
        val permissions = getRequiredPermissions()
        return permissions.all {
            ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        return permissions
    }

    private fun requestPermissionsInitial() {
        if (!hasPermissions()) {
            requestPermissionLauncher.launch(getRequiredPermissions().toTypedArray())
        }
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage("Offline SOS needs Bluetooth and Location to function. Please enable them in Settings.")
            .setPositiveButton("Settings") { _, _ -> openAppSettings() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = Uri.fromParts("package", packageName, null)
        startActivity(intent)
    }

    private fun setupBluetooth() {
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
    }

    // =========================================================================
    // VICTIM MODE (CLIENT)
    // =========================================================================

    // =========================================================================
    // SOS UI LOGIC
    // =========================================================================

    private fun initiateSos() {
        if (!hasPermissions()) {
            Toast.makeText(this, "Missing permissions! Please restart app.", Toast.LENGTH_LONG).show()
            return
        }
        if (bluetoothAdapter?.isEnabled == false) {
             Toast.makeText(this, "Bluetooth is OFF. Please enable it.", Toast.LENGTH_LONG).show()
             return
        }

        // 1. Disable Button temporarily
        btnSos.isEnabled = false
        btnSos.alpha = 0.5f
        
        // 2. Trigger via Manager
        val rawContent = etMessage.text.toString()
        // BleManager handles location fetching internally if cached is null
        BleManager.triggerSos(rawContent)
    }
    
    // Removed old fetch & process logic as mostly handled by BleManager now.
    // UI updates come via onUiUpdate callback.

    // =========================================================================
    // BleManager Interface Implementation
    // =========================================================================

    override fun onSosReceived(alert: SosMessage) {
        // Run on UI thread is guaranteed by BleManager if it uses handler, but safe to verify
        runOnUiThread {
            activeAdapter.notifyDataSetChanged()
            updateRescuerEmptyStates()
        }
    }
    
    override fun onUiUpdate(status: String, msg: String?) {
        runOnUiThread {
            updateVictimUiState(status, msg)
        }
    }

    private fun updateVictimUiState(status: String, msg: String?) {
        when (status) {
            "READY" -> {
                tvVictimStatus.text = "Ready"
                tvReassurance.visibility = View.INVISIBLE
                btnSos.isEnabled = true
                btnSos.alpha = 1.0f
                startSosAnimation()
            }
            "PENDING", "FOUND" -> {
                tvVictimStatus.text = "Attempting to send..."
                tvReassurance.visibility = View.VISIBLE
                tvReassurance.text = msg ?: "Searching for rescuers..."
                tvReassurance.setBackgroundColor(getColor(R.color.surface_card))
                tvReassurance.setTextColor(getColor(R.color.text_primary))
                btnSos.isEnabled = false
                btnSos.alpha = 0.7f
            }
            "SENT" -> {
                tvVictimStatus.text = "SOS SENT"
                tvReassurance.text = "Signal sent to rescuer.\nWaiting for acknowledgement..."
            }
            "ACK" -> {
                tvVictimStatus.text = "HELP IS ON THE WAY"
                tvReassurance.setBackgroundColor(getColor(R.color.safety_green))
                tvReassurance.setTextColor(getColor(R.color.white))
                
                // Allow sending another SOS immediately if needed
                btnSos.isEnabled = true
                btnSos.alpha = 1.0f
                
                var distMsg = ""
                if (msg != null && msg.startsWith("ACK:")) {
                     val parts = msg.split(":")
                     if (parts.size > 4) {
                         val rescuerLocStr = parts[4]
                         val myLoc = BleManager.getLastKnownLocation()
                         val rescuerCoords = BleManager.parseLocation(rescuerLocStr)
                         
                         if (myLoc != null && rescuerCoords != null) {
                             val dist = BleManager.calculateDistance(myLoc, rescuerCoords.first, rescuerCoords.second)
                             distMsg = "\nRescuer is %.0f meters away".format(dist)
                         }
                     }
                }
                tvReassurance.text = "Rescuer received your SOS!$distMsg\nStay calm."
                stopSosAnimation()
                // Re-enable button fully to allow another send if needed
                 btnSos.isEnabled = true
                 btnSos.alpha = 1.0f
            }
            "TIMEOUT" -> {
                tvVictimStatus.text = "No Rescuers Found"
                tvReassurance.visibility = View.VISIBLE
                tvReassurance.setBackgroundColor(getColor(R.color.surface_card))
                tvReassurance.setTextColor(getColor(R.color.text_primary))
                tvReassurance.text = "No rescuers detected nearby.\nTry moving or try again later."
                btnSos.isEnabled = true
                btnSos.alpha = 1.0f
                stopSosAnimation()
            }
        }
    }
    
    private fun startSosAnimation() {
        if (sosAnimator == null) {
            val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.05f)
            val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.05f)
            sosAnimator = ObjectAnimator.ofPropertyValuesHolder(btnSos, scaleX, scaleY).apply {
                duration = 1000
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
            }
        }
        if (sosAnimator?.isStarted == false) sosAnimator?.start()
    }
    
    private fun stopSosAnimation() {
        sosAnimator?.cancel()
        btnSos.scaleX = 1f
        btnSos.scaleY = 1f
    }

    // =========================================================================
    // RESCUER HELPERS
    // =========================================================================

    // Rescuer Location Tracking
    private val rescuerLocationListener = object : android.location.LocationListener {
        override fun onLocationChanged(location: Location) {
            // Keep system cached location fresh
        }
        override fun onProviderDisabled(provider: String) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    private fun startRescuerLocationUpdates() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                 lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10000L, 10f, rescuerLocationListener, Looper.getMainLooper())
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                 lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000L, 10f, rescuerLocationListener, Looper.getMainLooper())
            }
        } catch (e: Exception) {
            Log.e("RescuerLoc", "Failed to start loc updates: ${e.message}")
        }
    }

    private fun stopRescuerLocationUpdates() {
         val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
         lm.removeUpdates(rescuerLocationListener)
    }

    private fun updateModeUI() {
        if (isRescuer) {
            switchMode.text = "Mode: Active Rescuer"
            layoutRescuer.visibility = View.VISIBLE
            layoutVictim.visibility = View.GONE
            switchMode.isChecked = true
        } else {
            switchMode.text = "Switch to Rescuer"
            layoutRescuer.visibility = View.GONE
            layoutVictim.visibility = View.VISIBLE
            switchMode.isChecked = false
        }
    }
    
    private fun updateRescuerEmptyStates() {
        tvEmptyActive.visibility = if (BleManager.activeAlerts.isEmpty()) View.VISIBLE else View.GONE
        tvEmptyCompleted.visibility = if (BleManager.completedAlerts.isEmpty()) View.VISIBLE else View.GONE
    }
}
