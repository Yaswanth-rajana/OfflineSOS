package com.yaswanth.offlinesos

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.*
import android.os.Bundle
import android.content.pm.PackageManager
import android.Manifest

object BleManager {

    private const val TAG = "BleManager"

    // UUIDs
    val SOS_SERVICE_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcde01")
    val SOS_CHARACTERISTIC_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcde02")
    val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var context: Context? = null
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    
    // Rescuer
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    var isRescuerActive = false
        private set

    // Victim
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private val discoveredRescuers = mutableSetOf<String>()
    
    // Data
    val activeAlerts = mutableListOf<SosMessage>()
    val completedAlerts = mutableListOf<SosMessage>()
    
    // Listeners
    interface BleEventListener {
        fun onSosReceived(alert: SosMessage)
        fun onUiUpdate(status: String, msg: String?)
    }
    
    private var listener: BleEventListener? = null
    private val handler = Handler(Looper.getMainLooper())
    
    // SOS State
    enum class VictimState {
        IDLE, WARMUP, FETCHING_GPS, SCANNING, SENT, ACKNOWLEDGED, TIMEOUT
    }
    var victimState: VictimState = VictimState.IDLE
        private set

    var currentSosPayload: String? = null
    private var scanTimeoutRunnable: Runnable? = null
    private val JOIN_TIMEOUT = 15000L

    fun init(ctx: Context) {
        context = ctx.applicationContext
        bluetoothManager = context?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
    }

    fun setListener(l: BleEventListener?) {
        listener = l
    }
    
    // =========================================================================
    // RESCUER
    // =========================================================================

    @SuppressLint("MissingPermission")
    fun startRescuerMode() {
        if (bluetoothAdapter?.isEnabled != true) return
        if (isRescuerActive) return
        
        startAdvertising()
        startGattServer()
        isRescuerActive = true
    }

    @SuppressLint("MissingPermission")
    fun stopRescuerMode() {
        stopAdvertising()
        stopGattServer()
        isRescuerActive = false
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        bluetoothLeAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SOS_SERVICE_UUID))
            .build()
        
        bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)
        Log.d(TAG, "Started advertising")
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        try {
            bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) { Log.e(TAG, "Stop adv error: ${e.message}") }
        Log.d(TAG, "Stopped advertising")
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGattServer() {
        if (gattServer != null) return // Already started
        
        gattServer = bluetoothManager?.openGattServer(context, gattServerCallback)
        
        val service = BluetoothGattService(SOS_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            SOS_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val configDescriptor = BluetoothGattDescriptor(
             CLIENT_CHARACTERISTIC_CONFIG_UUID, 
             BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
        )
        characteristic.addDescriptor(configDescriptor)
        
        service.addCharacteristic(characteristic)
        gattServer?.addService(service)
        Log.d(TAG, "GATT Server started")
    }

    @SuppressLint("MissingPermission")
    private fun stopGattServer() {
        try {
            gattServer?.close()
            gattServer = null
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to close GATT: ${e.message}")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            if (characteristic.uuid == SOS_CHARACTERISTIC_UUID) {
                val message = String(value, Charset.forName("UTF-8"))
                val now = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                
                Log.d(TAG, "Received SOS: $message")

                val parts = message.split("|")
                val msgId = if (parts.size > 1) parts[1] else "Unknown"
                val sosTime = if (parts.size > 2) parts[2] else now
                val content = if (parts.size > 3) parts[3] else "None"
                val locStr = if (parts.size > 4) parts[4] else "Unavailable"

                var distStr = "Unknown"
                val myLoc = getLastKnownLocation()
                val targetCoords = parseLocation(locStr)
                
                if (myLoc != null && targetCoords != null) {
                     val dist = calculateDistance(myLoc, targetCoords.first, targetCoords.second)
                     // If distance > 200m but connected via BLE, suspect GPS error, but don't show "Nearby" forcefully if we have some data.
                     // Actually, if we have coordinates, show the calculated distance.
                     distStr = "%.0f meters".format(dist)
                } else {
                     // Fallback check: if either is missing, we can't calc distance.
                     // BUT we know they are in BLE range.
                     distStr = "Signal detected (<100m)" 
                }

                val newAlert = SosMessage(msgId, content, locStr, sosTime, content, distStr)

                // Post to Main Thread for Safety with potential UI Converters
                handler.post {
                     // Deduplicate
                     if (activeAlerts.none { it.id == msgId }) {
                         activeAlerts.add(0, newAlert)
                         listener?.onSosReceived(newAlert)
                     }
                }

                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }

                // Send ACK
                val ackLoc = if(myLoc!=null) "${myLoc.latitude},${myLoc.longitude}" else "Unavailable"
                val ack = "ACK:$msgId:Rescuer:$now:$ackLoc"
                characteristic.value = ack.toByteArray(Charset.forName("UTF-8"))
                gattServer?.notifyCharacteristicChanged(device, characteristic, false) 
            }
        }
        
        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
    }

    // =========================================================================
    // VICTIM
    // =========================================================================
    
    @SuppressLint("MissingPermission")
    fun startScanning() {
        if (isScanning || bluetoothAdapter == null) return
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        if (bluetoothLeScanner == null) return

        // CLEAR Previous Discoveries so we can re-find the same rescuer for a NEW message
        discoveredRescuers.clear()

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SOS_SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
            
        bluetoothLeScanner?.startScan(filters, settings, scanCallback)
        isScanning = true
        victimState = VictimState.SCANNING
        Log.d(TAG, "Started scanning")
        
        // Timeout
        scanTimeoutRunnable = Runnable { 
            stopScanning()
            victimState = VictimState.TIMEOUT
            listener?.onUiUpdate("TIMEOUT", null)
        }
        handler.postDelayed(scanTimeoutRunnable!!, JOIN_TIMEOUT)
    }

    fun stopScanning() {
        if (!isScanning) return
        scanTimeoutRunnable?.let { handler.removeCallbacks(it) }
        bluetoothLeScanner?.stopScan(scanCallback)
        isScanning = false
        // Note: We don't necessarily reset victimState here as it might be transitioning to SENT or TIMEOUT
        // But if manually stopped (not implemented yet for UI), we might want IDLE.
        // For now, leave state management to the specific events (timeout, success) or manual reset.
        Log.d(TAG, "Stopped scanning")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            if (discoveredRescuers.add(device.address)) {
                 scanTimeoutRunnable?.let { handler.removeCallbacks(it) }
                 Log.d(TAG, "Found rescuer: ${device.address}")
                 listener?.onUiUpdate("FOUND", "Found rescuer, connecting...")
                 connectAndSend(device)
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
             Log.e(TAG, "Scan failed: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectAndSend(device: BluetoothDevice) {
        val payload = currentSosPayload ?: return
        
        device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "Connected to rescuer. Requesting MTU change...")
                    if (!gatt.requestMtu(512)) {
                        Log.e(TAG, "MTU request failed, proceeding with default.")
                        gatt.discoverServices()
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    gatt.close()
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                Log.d(TAG, "MTU Changed to $mtu, status: $status")
                // Regardless of success, we try to discover services now
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val service = gatt.getService(SOS_SERVICE_UUID)
                    val characteristic = service?.getCharacteristic(SOS_CHARACTERISTIC_UUID)
                    if (characteristic != null) {
                        try {
                            gatt.setCharacteristicNotification(characteristic, true)
                            val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
                            if (descriptor != null) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                                } else {
                                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    gatt.writeDescriptor(descriptor)
                                }
                            } else {
                                writePayload(gatt, characteristic, payload)
                            }
                        } catch(e: Exception) {
                            Log.e(TAG, "Error setting up notifications: ${e.message}")
                            writePayload(gatt, characteristic, payload)
                        }
                    }
                }
            }
            
            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor?, status: Int) {
                 val service = gatt.getService(SOS_SERVICE_UUID)
                 val char = service?.getCharacteristic(SOS_CHARACTERISTIC_UUID)
                 if (char != null) writePayload(gatt, char, currentSosPayload ?: "")
            }

            private fun writePayload(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, data: String) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(char, data.toByteArray(Charset.forName("UTF-8")), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                } else {
                    char.value = data.toByteArray(Charset.forName("UTF-8"))
                    char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    gatt.writeCharacteristic(char)
                }
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    handler.post { 
                        victimState = VictimState.SENT
                        listener?.onUiUpdate("SENT", "Signal sent. Waiting for ACK...") 
                    }
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val value = characteristic.value
                val message = String(value, Charset.forName("UTF-8"))
                if (message.startsWith("ACK:")) {
                     handler.post { 
                         victimState = VictimState.ACKNOWLEDGED
                         listener?.onUiUpdate("ACK", message) 
                     }
                     gatt.close()
                     stopScanning() 
                }
            }
        }, BluetoothDevice.TRANSPORT_LE)
    }

    // =========================================================================
    // UTILS
    // =========================================================================
    
    // Location Caching
    var cachedVictimLocation: Location? = null
        private set

    fun updateCachedLocation(location: Location?) {
        if (location == null) return
        
        // Basic validation: ignore 0,0
        if (location.latitude == 0.0 && location.longitude == 0.0) return

        if (cachedVictimLocation == null) {
            cachedVictimLocation = location
        } else {
             // If new location is significantly newer or cleaner, take it.
             // For now, we trust the latest update from our warm-up/active listener.
             // We can check time if needed, but usually updates are chronological.
             cachedVictimLocation = location
        }
    }

    @SuppressLint("MissingPermission")
    fun getLastKnownLocation(): Location? {
        // Return our warm cached location if we have a good one
        if (cachedVictimLocation != null) return cachedVictimLocation

        val lm = context?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = lm.getProviders(true)
        var best: Location? = null
        for (p in providers) {
            val l = lm.getLastKnownLocation(p) ?: continue
            if (best == null || l.accuracy < best.accuracy) best = l
        }
        
        // Update cache with this system last known if it looks valid
        if (best != null && (best.latitude != 0.0 || best.longitude != 0.0)) {
            cachedVictimLocation = best
        }
        
        return best
    }
    
    private fun hasLocationPermission(): Boolean {
        val c = context ?: return false
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
               c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    
    // =========================================================================
    // SOS ORCHESTRATION
    // =========================================================================

    private val warmUpLocationListener = object : android.location.LocationListener {
        override fun onLocationChanged(location: Location) {
             updateCachedLocation(location)
        }
        override fun onProviderDisabled(provider: String) {}
        override fun onProviderEnabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    fun startLocationWarmup() {
        if (!hasLocationPermission()) return
        
        val lm = context?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 10f, warmUpLocationListener, Looper.getMainLooper())
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 10f, warmUpLocationListener, Looper.getMainLooper())
            }
            // If we were IDLE, we are effectively in WARMUP now, but IDLE/WARMUP are treated similarly by UI usually.
            // Let's not strict set WARMUP state to avoid blocking SOS calls, unless we want to show it.
        } catch (e: Exception) {
            Log.e(TAG, "Warmup error: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopLocationWarmup() {
        val lm = context?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        lm.removeUpdates(warmUpLocationListener)
    }

    fun triggerSos(content: String) {
        // Quick check
        val cached = getLastKnownLocation()
        if (cached != null && (cached.latitude != 0.0 || cached.longitude != 0.0)) {
            processSos(cached, content)
        } else {
            victimState = VictimState.FETCHING_GPS
            listener?.onUiUpdate("PENDING", "Fetching location...")
            fetchLocationAndSend(content)
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchLocationAndSend(content: String) {
        if (!hasLocationPermission()) {
             // Just send with what we have (likely unavailable)
             processSos(getLastKnownLocation(), content)
             return
        }
        val lm = context?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        
        val locListener = object : android.location.LocationListener {
            override fun onLocationChanged(location: Location) {
                lm.removeUpdates(this)
                handler.removeCallbacksAndMessages("LOC_TIMEOUT_BLE")
                updateCachedLocation(location)
                processSos(location, content)
            }
            override fun onProviderDisabled(provider: String) {}
            override fun onProviderEnabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        
        val timeoutRunnable = Runnable {
            lm.removeUpdates(locListener)
            val best = getLastKnownLocation()
            processSos(best, content)
        }
        
        val token = "LOC_TIMEOUT_BLE"
        
        try {
            var requested = false
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, locListener, Looper.getMainLooper())
                requested = true
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, locListener, Looper.getMainLooper())
                requested = true
            }
            
            if (requested) {
                handler.postAtTime(timeoutRunnable, token, android.os.SystemClock.uptimeMillis() + 15000)
            } else {
                processSos(getLastKnownLocation(), content)
            }
        } catch (e: Exception) {
            processSos(getLastKnownLocation(), content)
        }
    }

    private fun processSos(location: Location?, contentRaw: String) {
        val id = UUID.randomUUID().toString().substring(0, 5)
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val content = contentRaw.replace("|", " ").trim().ifEmpty { "None" }
        
        val locStr: String
        if (location != null) {
             if (location.latitude == 0.0 && location.longitude == 0.0) {
                 locStr = "Unavailable"
             } else {
                 locStr = String.format(Locale.US, "%.6f,%.6f", location.latitude, location.longitude)
             }
        } else {
            locStr = "Unavailable"
        }
        
        currentSosPayload = "SOS|$id|$timestamp|$content|$locStr"
        
        listener?.onUiUpdate("PENDING", "Starting...") // UI update
        listener?.onUiUpdate("PENDING", "Starting...") // UI update
        stopScanning() // Reset any previous scan state
        startScanning() // Changes state to SCANNING
    }

    // =========================================================================
    // UTILS
    // =========================================================================
    
    fun calculateDistance(loc1: Location, lat2: Double, lon2: Double): Float {
        val r = FloatArray(1)
        try {
            Location.distanceBetween(loc1.latitude, loc1.longitude, lat2, lon2, r)
            return r[0]
        } catch (e: Exception) {
            return 0f
        }
    }

    fun parseLocation(locStr: String): Pair<Double, Double>? {
        if (locStr == "Unknown" || locStr == "Unavailable" || locStr == "0,0" || !locStr.contains(",")) return null
        return try {
            val parts = locStr.split(",")
            val lat = parts[0].toDouble()
            val lon = parts[1].toDouble()
            if (lat == 0.0 && lon == 0.0) null else Pair(lat, lon)
        } catch (e: Exception) {
            null
        }
    }
    
    fun resetVictimState() {
        victimState = VictimState.IDLE
    }
}
