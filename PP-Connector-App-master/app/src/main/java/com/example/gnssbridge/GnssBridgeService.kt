package com.example.gnssbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class GnssBridgeService : Service() {

    companion object {
        const val ACTION_CONNECT = "com.example.gnssbridge.CONNECT"
        const val ACTION_STOP = "com.example.gnssbridge.STOP"
        const val ACTION_STATUS = "com.example.gnssbridge.STATUS"
        const val ACTION_GNSS = "com.example.gnssbridge.GNSS"
        const val ACTION_NMEA = "com.example.gnssbridge.NMEA"

        const val ACTION_PDR_ON = "com.example.gnssbridge.PDR_ON"
        const val ACTION_PDR_OFF = "com.example.gnssbridge.PDR_OFF"

        const val ACTION_DR_CALIBRATION = "com.example.gnssbridge.DR_CALIBRATION"

        const val ACTION_NTRIP_ON = "com.example.gnssbridge.NTRIP_ON"
        const val ACTION_NTRIP_OFF = "com.example.gnssbridge.NTRIP_OFF"
        const val ACTION_NTRIP_STATUS = "com.example.gnssbridge.NTRIP_STATUS"

        const val ACTION_MOCK_ON = "com.example.gnssbridge.MOCK_ON"
        const val ACTION_MOCK_OFF = "com.example.gnssbridge.MOCK_OFF"
        const val ACTION_MOCK_STATUS = "com.example.gnssbridge.MOCK_STATUS"

        const val ACTION_ANDROID_LOCATION_ON = "com.example.gnssbridge.ANDROID_LOCATION_ON"
        const val ACTION_ANDROID_LOCATION_OFF = "com.example.gnssbridge.ANDROID_LOCATION_OFF"
        const val ACTION_ANDROID_LOCATION_STATUS = "com.example.gnssbridge.ANDROID_LOCATION_STATUS"
        const val ACTION_ANDROID_LOCATION = "com.example.gnssbridge.ANDROID_LOCATION"

        const val ACTION_COMPARISON_ON = "com.example.gnssbridge.COMPARISON_ON"
        const val ACTION_COMPARISON_OFF = "com.example.gnssbridge.COMPARISON_OFF"
        const val ACTION_COMPARISON_STATUS = "com.example.gnssbridge.COMPARISON_STATUS"

        const val EXTRA_STATUS = "status"
        const val EXTRA_NMEA = "nmea"
        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"
        const val EXTRA_ALTITUDE = "altitude"
        const val EXTRA_ACCURACY = "accuracy"
        const val EXTRA_SATELLITES = "satellites"
        const val EXTRA_HDOP = "hdop"
        const val EXTRA_FIX_TYPE = "fixType"
        const val EXTRA_VENDOR_ID = "vendorId"
        const val EXTRA_PRODUCT_ID = "productId"

        const val EXTRA_NORTH_ERROR = "northError"
        const val EXTRA_EAST_ERROR = "eastError"
        const val EXTRA_VERTICAL_ERROR = "verticalError"

        const val EXTRA_POSITION_SOURCE = "positionSource"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_COURSE = "course"

        const val EXTRA_DR_CALIBRATION_STATE = "drCalibrationState"
        const val EXTRA_DR_CALIBRATION_STATUS = "drCalibrationStatus"

        const val EXTRA_NTRIP_ENABLED = "ntripEnabled"
        const val EXTRA_MOCK_ENABLED = "mockEnabled"
        const val EXTRA_MOCK_SELECTED = "mockSelected"
        const val EXTRA_ANDROID_LOCATION_ENABLED = "androidLocationEnabled"
        const val EXTRA_COMPARISON_ENABLED = "comparisonEnabled"

        private const val CHANNEL_ID = "gnss_bridge_channel"
        private const val NOTIFICATION_ID = 1001
        private const val GNSS_TIMEOUT_MS = 5000L
        private const val WATCHDOG_INTERVAL_MS = 1000L
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)

    private lateinit var usbGnssManager: UsbGnssManager
    private lateinit var ntripClient: NtripClient
    private lateinit var mockLocationManager: MockLocationManager
    private lateinit var fusedLocationManager: FusedLocationManager

    @Volatile private var ntripEnabled = false
    @Volatile private var mockLocationEnabled = false
    @Volatile private var pdrEnabled = false
    @Volatile private var androidLocationEnabled = false
    @Volatile private var comparisonEnabled = false
    @Volatile private var lastGnssDataTime = 0L
    @Volatile private var receiverCurrentlyConnected = false
    @Volatile private var waitingForFirstGnssData = false

    private var foregroundStarted = false
    @Volatile private var drCalibrationState = -1
    @Volatile private var drCalibrationStatus = "Waiting for DR calibration data..."

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        mockLocationManager = MockLocationManager(this)

        fusedLocationManager = FusedLocationManager(
            context = this,
            onLocation = { location ->
                if (androidLocationEnabled) {
                    processAndroidLocation(location)
                }
            },
            onStatus = { status ->
                sendAndroidLocationStatus(status)
                updateNotification(status)
            }
        )

        ntripClient = NtripClient(
            scope = serviceScope,
            onRtcmData = { rtcm ->
                try {
                    usbGnssManager.writeRtcm(rtcm)
                } catch (e: Exception) {
                    sendNtripStatus("RTCM error: ${e.message}")
                }
            },
            onStatus = { status ->
                updateNotification(status)
                sendNtripStatus(status)
            }
        )

        usbGnssManager = UsbGnssManager(
            context = this,
            onUsbPermissionGranted = { startForegroundServiceNow() },
            onGnssData = { data ->
                lastGnssDataTime = System.currentTimeMillis()

                if (!receiverCurrentlyConnected) {
                    receiverCurrentlyConnected = true
                    waitingForFirstGnssData = false
                    sendStatus("🟢 Receiver Connected")
                }

                if (mockLocationEnabled) {
                    mockLocationManager.updateLocation(data)
                    if (!mockLocationManager.isEnabled()) {
                        mockLocationEnabled = false
                        sendMockStatus("🔴 Mock Location OFF")
                    }
                }

                if (!pdrEnabled && (!androidLocationEnabled || comparisonEnabled)) {
                    serviceScope.launch {
                        try {
                            BackendClient().sendGnssData(data)
                        } catch (e: Exception) {
                            sendStatus("Backend error: ${e.message}")
                        }
                    }
                } else {
                    Log.d(
                        "GNSS_BACKEND",
                        "QLM29H backend forwarding suppressed - active alternative source"
                    )
                }

                sendGnssData(data)
            },
            onStatus = { status ->
                updateNotification(status)
                sendStatus(status)
            },
            onNmeaLine = { line ->
                sendNmea(line)

                if (line.startsWith("\$PQTMDRCAL")) {
                    processDrCalibration(line)
                }

                if (line.startsWith("\$GNGGA") || line.startsWith("\$GPGGA")) {
                    if (ntripEnabled) {
                        ntripClient.sendGga(line)
                    }
                }
            }
        )

        ntripEnabled = false
        mockLocationEnabled = false
        pdrEnabled = false
        androidLocationEnabled = false
        startGnssWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> connectToGnss(intent)
            ACTION_STOP -> stopBridge()
            ACTION_PDR_ON -> enablePdrBackendMode()
            ACTION_PDR_OFF -> disablePdrBackendMode()
            ACTION_ANDROID_LOCATION_ON -> enableAndroidLocation()
            ACTION_ANDROID_LOCATION_OFF -> disableAndroidLocation()
            ACTION_COMPARISON_ON -> enableComparisonMode()
            ACTION_COMPARISON_OFF -> disableComparisonMode()
            ACTION_NTRIP_ON -> enableNtrip()
            ACTION_NTRIP_OFF -> disableNtrip()
            ACTION_MOCK_ON -> enableMockLocation()
            ACTION_MOCK_OFF -> disableMockLocation()
        }
        return START_STICKY
    }

    private fun enablePdrBackendMode() {
        if (!comparisonEnabled) {
            androidLocationEnabled = false
            fusedLocationManager.stop()
        }
        pdrEnabled = true
        Log.d("PDR_BACKEND", "Phone PDR backend mode ENABLED - GNSS forwarding disabled")
        updateNotification("Phone PDR active - GNSS backend forwarding disabled")
        if (!comparisonEnabled) {
            sendAndroidLocationStatus("🔴 Android Location disabled")
        }
    }

    private fun disablePdrBackendMode() {
        pdrEnabled = false
        Log.d("PDR_BACKEND", "Phone PDR backend mode DISABLED - GNSS forwarding resumed")
        updateNotification("GNSS backend forwarding resumed")
    }

    private fun enableAndroidLocation() {
        if (!fusedLocationManager.hasLocationPermission()) {
            androidLocationEnabled = false
            sendAndroidLocationStatus("🔴 Android Location permission not granted")
            return
        }

        // Android Location can run without a QLM29H, so make sure
        // the service enters the foreground for an Android-only session.
        if (!foregroundStarted) {
            startForegroundServiceNow("Android Location active")
        }

        pdrEnabled = false
        comparisonEnabled = false
        androidLocationEnabled = true
        val started = fusedLocationManager.start()

        if (!started) {
            androidLocationEnabled = false
            sendAndroidLocationStatus("🔴 Failed to start Android Location")
            return
        }

        sendAndroidLocationStatus("🟢 Android Location active")
        updateNotification("Android Location active - QLM29H backend forwarding disabled")
    }

    private fun enableComparisonMode() {
        if (!fusedLocationManager.hasLocationPermission()) {
            comparisonEnabled = false
            androidLocationEnabled = false
            sendComparisonStatus(false)
            sendAndroidLocationStatus("🔴 Android Location permission not granted")
            return
        }

        if (!foregroundStarted) {
            startForegroundServiceNow("Position comparison active")
        }

        pdrEnabled = false
        comparisonEnabled = true
        androidLocationEnabled = true

        val started = fusedLocationManager.start()
        if (!started) {
            comparisonEnabled = false
            androidLocationEnabled = false
            sendComparisonStatus(false)
            return
        }

        sendComparisonStatus(true)
        sendAndroidLocationStatus("🟢 Android Location active for comparison")
        updateNotification("Position comparison active - QLM29H + Android Location")
    }

    private fun disableComparisonMode() {
        comparisonEnabled = false
        androidLocationEnabled = false
        fusedLocationManager.stop()
        sendComparisonStatus(false)
        sendAndroidLocationStatus("🔴 Android Location disabled")
        updateNotification("Position comparison disabled")
    }

    private fun sendComparisonStatus(enabled: Boolean) {
        val intent = Intent(ACTION_COMPARISON_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_COMPARISON_ENABLED, enabled)
        }
        sendBroadcast(intent)
    }

    private fun disableAndroidLocation() {
        androidLocationEnabled = false
        fusedLocationManager.stop()
        sendAndroidLocationStatus("🔴 Android Location disabled")
        updateNotification("Android Location disabled")
    }

    private fun processAndroidLocation(location: Location) {
        if (!androidLocationEnabled) return

        val course = if (location.hasBearing()) location.bearing.toDouble() else Double.NaN

        val intent = Intent(ACTION_ANDROID_LOCATION).apply {
            setPackage(packageName)
            putExtra(EXTRA_LATITUDE, location.latitude)
            putExtra(EXTRA_LONGITUDE, location.longitude)
            putExtra(EXTRA_ALTITUDE, if (location.hasAltitude()) location.altitude else Double.NaN)
            putExtra(EXTRA_ACCURACY, if (location.hasAccuracy()) location.accuracy.toDouble() else Double.NaN)
            putExtra(EXTRA_SPEED, if (location.hasSpeed()) location.speed.toDouble() else Double.NaN)
            putExtra(EXTRA_COURSE, course)
        }
        sendBroadcast(intent)

        serviceScope.launch {
            try {
                BackendClient().sendAndroidLocation(location)
            } catch (e: Exception) {
                Log.e("ANDROID_LOCATION", "Backend update failed", e)
            }
        }
    }

    private fun sendAndroidLocationStatus(status: String) {
        val intent = Intent(ACTION_ANDROID_LOCATION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_ANDROID_LOCATION_ENABLED, androidLocationEnabled)
        }
        sendBroadcast(intent)
    }

    private fun processDrCalibration(line: String) {
        try {
            val fields = line.substringBefore("*").split(",")
            if (fields.size < 3) return
            val state = fields[2].toIntOrNull() ?: return
            val status = when (state) {
                0 -> "⚪ DR Calibration: Not calibrated"
                1 -> "🟡 DR Calibration: Calibrating"
                2 -> "🟢 DR Calibration: Fully calibrated"
                3 -> "🟢 DR Calibration: Fully calibrated (high-precision heading)"
                else -> "❓ DR Calibration: Unknown"
            }
            drCalibrationState = state
            drCalibrationStatus = status
            Log.d("DR_CALIBRATION", "State=$state Status=$status")
            sendDrCalibrationStatus(state, status)
        } catch (e: Exception) {
            Log.e("DR_CALIBRATION", "Failed to parse PQTMDRCAL: $line", e)
        }
    }

    private fun sendDrCalibrationStatus(state: Int, status: String) {
        val intent = Intent(ACTION_DR_CALIBRATION).apply {
            setPackage(packageName)
            putExtra(EXTRA_DR_CALIBRATION_STATE, state)
            putExtra(EXTRA_DR_CALIBRATION_STATUS, status)
        }
        sendBroadcast(intent)
    }

    private fun startGnssWatchdog() {
        serviceScope.launch {
            while (true) {
                delay(WATCHDOG_INTERVAL_MS)
                if (waitingForFirstGnssData || lastGnssDataTime == 0L) continue
                val elapsed = System.currentTimeMillis() - lastGnssDataTime
                if (elapsed >= GNSS_TIMEOUT_MS && receiverCurrentlyConnected) {
                    receiverCurrentlyConnected = false
                    if (!androidLocationEnabled && !pdrEnabled) {
                        sendDisconnectedToBackend()
                    }
                    sendStatus("🔴 Receiver Disconnected")
                    updateNotification("Receiver disconnected")
                }
            }
        }
    }

    private fun sendDisconnectedToBackend() {
        serviceScope.launch {
            try {
                BackendClient().sendConnectionStatus(false, "disconnected")
            } catch (e: Exception) {
                sendStatus("Failed to update backend: ${e.message}")
            }
        }
    }

    private fun enableNtrip() {
        if (ntripEnabled) {
            sendNtripStatus("🟢 NTRIP already enabled")
            return
        }
        if (!::usbGnssManager.isInitialized) {
            sendNtripStatus("NTRIP unavailable - GNSS bridge not running")
            return
        }
        ntripEnabled = true
        sendNtripStatus("🟡 NTRIP enabled - connecting...")
        updateNotification("NTRIP enabled - connecting...")
        ntripClient.start()
    }

    private fun disableNtrip() {
        if (!ntripEnabled) {
            sendNtripStatus("🔴 NTRIP already disabled")
            return
        }
        ntripEnabled = false
        ntripClient.stop()
        sendNtripStatus("🔴 NTRIP Disabled")
        updateNotification("NTRIP disabled")
    }

    private fun enableMockLocation() {
        if (!mockLocationManager.isSelectedAsMockLocationApp()) {
            mockLocationEnabled = false
            sendMockStatus("⚙️ Setup Mock Location")
            return
        }
        val success = mockLocationManager.enable()
        if (success) {
            mockLocationEnabled = true
            sendMockStatus("🟢 Mock Location ON")
            updateNotification("Mock Location ON")
        } else {
            mockLocationEnabled = false
            sendMockStatus("🔴 Mock Location OFF")
        }
    }

    private fun disableMockLocation() {
        mockLocationEnabled = false
        mockLocationManager.disable()
        sendMockStatus("🔴 Mock Location OFF")
        updateNotification("Mock Location OFF")
    }

    private fun connectToGnss(intent: Intent) {
        val devices = usbGnssManager.findDevices()
        if (devices.isEmpty()) {
            waitingForFirstGnssData = true
            sendStatus("Waiting for GNSS receiver...")
            return
        }

        val selectedVendorId = intent.getIntExtra(EXTRA_VENDOR_ID, -1)
        val selectedProductId = intent.getIntExtra(EXTRA_PRODUCT_ID, -1)
        val selectedDevice = if (selectedVendorId != -1 && selectedProductId != -1) {
            devices.firstOrNull { it.vendorId == selectedVendorId && it.productId == selectedProductId }
        } else {
            devices.first()
        }

        if (selectedDevice == null) {
            waitingForFirstGnssData = true
            sendStatus("Selected GNSS receiver not found")
            return
        }

        lastGnssDataTime = 0L
        receiverCurrentlyConnected = false
        waitingForFirstGnssData = true
        pdrEnabled = false
        comparisonEnabled = false
        androidLocationEnabled = false
        fusedLocationManager.stop()

        drCalibrationState = -1
        drCalibrationStatus = "Waiting for DR calibration data..."
        sendDrCalibrationStatus(drCalibrationState, drCalibrationStatus)
        sendAndroidLocationStatus("🔴 Android Location disabled")

        if (ntripEnabled) {
            ntripEnabled = false
            ntripClient.stop()
            sendNtripStatus("🔴 NTRIP Disabled")
        }

        sendStatus("Requesting USB permission...")
        usbGnssManager.connect(selectedDevice)
    }

    private fun startForegroundServiceNow(
        notificationText: String = "GNSS receiver connected"
    ) {
        if (foregroundStarted) return
        val notification = createNotification(notificationText)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
            sendStatus("USB receiver connected - waiting for GNSS data...")
        } catch (e: Exception) {
            foregroundStarted = false
            sendStatus("Foreground service error: ${e.message}")
        }
    }

    private fun sendStatus(status: String) {
        val intent = Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, status)
        }
        sendBroadcast(intent)
    }

    private fun sendNtripStatus(status: String) {
        val intent = Intent(ACTION_NTRIP_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_NTRIP_ENABLED, ntripEnabled)
        }
        sendBroadcast(intent)
    }

    private fun sendMockStatus(status: String) {
        val intent = Intent(ACTION_MOCK_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_MOCK_ENABLED, mockLocationEnabled)
            putExtra(EXTRA_MOCK_SELECTED, mockLocationManager.isSelectedAsMockLocationApp())
        }
        sendBroadcast(intent)
    }

    private fun sendGnssData(data: GnssData) {
        val intent = Intent(ACTION_GNSS).apply {
            setPackage(packageName)
            putExtra(EXTRA_LATITUDE, data.latitude ?: Double.NaN)
            putExtra(EXTRA_LONGITUDE, data.longitude ?: Double.NaN)
            putExtra(EXTRA_ALTITUDE, data.altitude ?: Double.NaN)
            putExtra(EXTRA_ACCURACY, data.accuracy ?: Double.NaN)
            putExtra(EXTRA_NORTH_ERROR, data.northError ?: Double.NaN)
            putExtra(EXTRA_EAST_ERROR, data.eastError ?: Double.NaN)
            putExtra(EXTRA_VERTICAL_ERROR, data.verticalError ?: Double.NaN)
            putExtra(EXTRA_SATELLITES, data.satellites ?: -1)
            putExtra(EXTRA_HDOP, data.hdop ?: Double.NaN)
            putExtra(EXTRA_FIX_TYPE, data.fixType ?: "--")
            putExtra(EXTRA_POSITION_SOURCE, data.positionSource ?: "--")
            putExtra(EXTRA_SPEED, data.speed ?: Double.NaN)
            putExtra(EXTRA_COURSE, data.course ?: Double.NaN)
        }
        sendBroadcast(intent)
    }

    private fun sendNmea(line: String) {
        val intent = Intent(ACTION_NMEA).apply {
            setPackage(packageName)
            putExtra(EXTRA_NMEA, line)
        }
        sendBroadcast(intent)
    }

    private fun updateNotification(text: String) {
        if (!foregroundStarted) return
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            createNotification(text)
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "GNSS Bridge",
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = "GNSS receiver, NTRIP and mock location bridge"
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(text: String): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("GNSS Bridge")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("GNSS Bridge")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build()
        }
    }

    private fun stopBridge() {
        pdrEnabled = false
        comparisonEnabled = false
        androidLocationEnabled = false
        fusedLocationManager.stop()
        ntripEnabled = false
        ntripClient.stop()
        mockLocationEnabled = false
        mockLocationManager.disable()
        usbGnssManager.destroy()
        lastGnssDataTime = 0L
        receiverCurrentlyConnected = false
        waitingForFirstGnssData = true
        drCalibrationState = -1
        drCalibrationStatus = "Waiting for DR calibration data..."

        sendNtripStatus("🔴 NTRIP Disabled")
        sendMockStatus("🔴 Mock Location OFF")
        sendAndroidLocationStatus("🔴 Android Location disabled")
        sendComparisonStatus(false)
        sendDrCalibrationStatus(drCalibrationState, drCalibrationStatus)
        sendStatus("Bridge stopped - waiting for GNSS receiver...")

        if (foregroundStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pdrEnabled = false
        comparisonEnabled = false
        androidLocationEnabled = false
        try { fusedLocationManager.stop() } catch (_: Exception) { }
        try { mockLocationManager.disable() } catch (_: Exception) { }
        try { ntripClient.stop() } catch (_: Exception) { }
        try { usbGnssManager.destroy() } catch (_: Exception) { }
        serviceJob.cancel()
        super.onDestroy()
    }
}
