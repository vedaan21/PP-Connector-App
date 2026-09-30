package com.example.gnssbridge

import android.Manifest
import android.util.Log
import android.content.pm.PackageManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.usb.UsbDevice
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt


class MainActivity :
    AppCompatActivity(),
    SensorEventListener {

    private lateinit var deviceSpinner: Spinner

    private lateinit var positionSourceSpinner: Spinner

    private lateinit var connectButton: Button

    private lateinit var comparePositionsButton: Button

    private lateinit var ntripButton: Button

    private lateinit var mockLocationButton: Button

    private lateinit var startDrButton: Button

    private lateinit var stopDrButton: Button

    private lateinit var resetDrButton: Button


    private lateinit var statusText: TextView

    private lateinit var ntripStatusText: TextView

    private lateinit var mockLocationStatusText: TextView

    private lateinit var gnssText: TextView

    private lateinit var androidLocationText: TextView

    private lateinit var pdrText: TextView


    private var devices:
            List<UsbDevice> =
        emptyList()


    private var bridgeRunning =
        false

    private var androidLocationEnabled =
        false

    private var comparisonEnabled =
        false

    private var pendingComparisonEnable =
        false

    private var pendingAndroidLocationEnable =
        false

    private companion object {
        const val REQUEST_ANDROID_LOCATION = 2101
    }


    private var ntripEnabled =
        false


    private var mockLocationEnabled =
        false


    private var mockLocationSelected =
        false


    private lateinit var mockLocationManager:
            MockLocationManager


    // ======================================================
    // PDR SENSOR SYSTEM
    // ======================================================

    private lateinit var sensorManager:
            SensorManager


    private var accelerometer:
            Sensor? =
        null


    private var gyroscope:
            Sensor? =
        null


    private var rotationVector:
            Sensor? =
        null


    private var drRunning =
        false


    // ======================================================
    // PDR POSITION
    // ======================================================

    private var steps =
        0


    private var distance =
        0.0


    /*
     * X = East/right displacement in metres
     * Y = North/forward displacement in metres
     */

    private var posX =
        0.0


    private var posY =
        0.0


    /*
     * Absolute heading:
     *
     * This is initialized from the phone's absolute azimuth
     * when START DR is pressed.
     *
     * 0°   = North
     * 90°  = East/right
     * 180° = South
     * 270° = West/left
     */

    private var headingDegrees =
        0.0


    /*
     * The phone azimuth captured when START DR
     * is pressed.
     *
     * This becomes the absolute starting heading
     * for Phone PDR.
     */

    private var startHeadingDegrees =
        Double.NaN


    // ======================================================
    // GNSS ANCHOR
    // ======================================================

    private var latestGnssLatitude =
        Double.NaN


    private var latestGnssLongitude =
        Double.NaN


    private var latestGnssAltitude =
        Double.NaN


    private var latestGnssAccuracy =
        Double.NaN


    private var latestGnssSatellites =
        -1


    private var latestGnssHdop =
        Double.NaN


    private var latestGnssFixType =
        "--"


    /*
     * Latest absolute course reported by the QLM29H.
     *
     * This is still displayed as GNSS data, but it is
     * NOT used to initialize Phone PDR.
     */

    private var latestGnssCourse =
        Double.NaN


    /*
     * Position captured when START DR is pressed.
     */

    private var anchorLatitude =
        Double.NaN


    private var anchorLongitude =
        Double.NaN


    // ======================================================
    // SENSOR VALUES
    // ======================================================

    private var accelMagnitude =
        0.0


    private var gyroMagnitude =
        0.0


    private var gyroZ =
        0.0


    private var lastGyroTimestampNs =
        0L


    // ======================================================
    // ACCELEROMETER / STEP DETECTION
    // ======================================================

    private var gravityEstimate =
        9.81


    private var filteredAcceleration =
        0.0


    private var lastStepTimeNs =
        0L


    private val minimumStepIntervalNs =
        300_000_000L


    /*
     * Same step length used by the standalone PDR
     * application that was tested.
     */

    private val stepLengthMeters =
        0.70


    private val accelerationHistory =
        ArrayDeque<Double>()


    // ======================================================
    // ROTATION VECTOR
    // ======================================================

    private val rotationMatrix =
        FloatArray(9)


    private val orientation =
        FloatArray(3)


    private var rotationVectorHeading =
        Double.NaN



    // ======================================================
    // BACKEND COROUTINE SCOPE
    // ======================================================

    private val backendScope =
        CoroutineScope(
            SupervisorJob() +
                    Dispatchers.IO
        )


    private val backendClient =
        BackendClient()


    // ======================================================
    // BROADCAST RECEIVER
    // ======================================================

    private val bridgeReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                when (
                    intent?.action
                ) {

                    // ======================================
                    // STATUS
                    // ======================================

                    GnssBridgeService.ACTION_STATUS -> {

                        val status =
                            intent.getStringExtra(
                                GnssBridgeService.EXTRA_STATUS
                            )


                        if (
                            status != null
                        ) {

                            statusText.text =
                                status
                        }
                    }


                    // ======================================
                    // NTRIP
                    // ======================================

                    GnssBridgeService.ACTION_NTRIP_STATUS -> {

                        val status =
                            intent.getStringExtra(
                                GnssBridgeService.EXTRA_STATUS
                            )


                        ntripEnabled =
                            intent.getBooleanExtra(
                                GnssBridgeService.EXTRA_NTRIP_ENABLED,
                                false
                            )


                        if (
                            status != null
                        ) {

                            ntripStatusText.text =
                                status
                        }


                        ntripButton.text =
                            if (
                                ntripEnabled
                            ) {

                                "Disable NTRIP"

                            } else {

                                "Enable NTRIP"
                            }


                        ntripButton.isEnabled =
                            bridgeRunning
                    }


                    // ======================================
                    // MOCK LOCATION
                    // ======================================

                    GnssBridgeService.ACTION_MOCK_STATUS -> {

                        val status =
                            intent.getStringExtra(
                                GnssBridgeService.EXTRA_STATUS
                            )


                        mockLocationEnabled =
                            intent.getBooleanExtra(
                                GnssBridgeService.EXTRA_MOCK_ENABLED,
                                false
                            )


                        mockLocationSelected =
                            intent.getBooleanExtra(
                                GnssBridgeService.EXTRA_MOCK_SELECTED,
                                false
                            )


                        if (
                            status != null
                        ) {

                            mockLocationStatusText.text =
                                status
                        }


                        updateMockLocationUi()
                    }


                    // ======================================
                    // GNSS DATA
                    // ======================================

                    GnssBridgeService.ACTION_COMPARISON_STATUS -> {

                        comparisonEnabled =
                            intent.getBooleanExtra(
                                GnssBridgeService.EXTRA_COMPARISON_ENABLED,
                                false
                            )

                        androidLocationEnabled = comparisonEnabled

                        updatePositionSourceUi()
                    }


                    GnssBridgeService.ACTION_ANDROID_LOCATION_STATUS -> {

                        val status =
                            intent.getStringExtra(
                                GnssBridgeService.EXTRA_STATUS
                            )

                        androidLocationEnabled =
                            intent.getBooleanExtra(
                                GnssBridgeService.EXTRA_ANDROID_LOCATION_ENABLED,
                                false
                            )

                        if (status != null) {
                            statusText.text = status
                        }

                        updatePositionSourceUi()
                    }


                    GnssBridgeService.ACTION_ANDROID_LOCATION -> {

                        val latitude =
                            intent.getDoubleExtra(
                                GnssBridgeService.EXTRA_LATITUDE,
                                Double.NaN
                            )

                        val longitude =
                            intent.getDoubleExtra(
                                GnssBridgeService.EXTRA_LONGITUDE,
                                Double.NaN
                            )

                        val altitude =
                            intent.getDoubleExtra(
                                GnssBridgeService.EXTRA_ALTITUDE,
                                Double.NaN
                            )

                        val accuracy =
                            intent.getDoubleExtra(
                                GnssBridgeService.EXTRA_ACCURACY,
                                Double.NaN
                            )

                        val speed =
                            intent.getDoubleExtra(
                                GnssBridgeService.EXTRA_SPEED,
                                Double.NaN
                            )

                        val bearing =
                            intent.getDoubleExtra(
                                GnssBridgeService.EXTRA_COURSE,
                                Double.NaN
                            )

                        androidLocationText.text =
                            """
                            Android Location: 🟢 ACTIVE

                            Latitude: ${formatCoordinate(latitude)}

                            Longitude: ${formatCoordinate(longitude)}

                            Altitude: ${format3(altitude)} m

                            Accuracy: ${format3(accuracy)} m

                            Speed: ${format3(speed)} m/s

                            Bearing: ${
                                if (bearing.isFinite())
                                    String.format("%.1f°", bearing)
                                else
                                    "--"
                            }
                            """.trimIndent()
                    }


                    GnssBridgeService.ACTION_GNSS -> {

                        val latitude =
                            intent.getDoubleExtra(
                                GnssBridgeService.EXTRA_LATITUDE,
                                Double.NaN
                            )


                        val longitude =
                            intent.getDoubleExtra(
                                GnssBridgeService.EXTRA_LONGITUDE,
                                Double.NaN
                            )


                        val altitude =
                            intent.getDoubleExtra(
                                GnssBridgeService.EXTRA_ALTITUDE,
                                Double.NaN
                            )


                        val accuracy =
                            intent.getDoubleExtra(
                                GnssBridgeService.EXTRA_ACCURACY,
                                Double.NaN
                            )


                        val satellites =
                            intent.getIntExtra(
                                GnssBridgeService.EXTRA_SATELLITES,
                                -1
                            )


                        val hdop =
                            intent.getDoubleExtra(
                                GnssBridgeService.EXTRA_HDOP,
                                Double.NaN
                            )


                        val fixType =
                            intent.getStringExtra(
                                GnssBridgeService.EXTRA_FIX_TYPE
                            )


                        val positionSource =
                            intent.getStringExtra(
                                GnssBridgeService.EXTRA_POSITION_SOURCE
                            )


                        /*
                         * IMPORTANT:
                         *
                         * Course comes directly from the QLM29H.
                         *
                         * We keep receiving GNSS locally even while
                         * Phone PDR is running.
                         */

                        val course =
                            intent.getDoubleExtra(
                                GnssBridgeService.EXTRA_COURSE,
                                Double.NaN
                            )


                        /*
                         * IMPORTANT:
                         *
                         * We ALWAYS keep receiving GNSS locally,
                         * even while PDR is running.
                         *
                         * The service is only prevented from
                         * forwarding GNSS to the backend while
                         * Phone PDR is active.
                         */

                        if (
                            latitude.isFinite() &&
                            longitude.isFinite()
                        ) {

                            latestGnssLatitude =
                                latitude

                            latestGnssLongitude =
                                longitude
                        }


                        latestGnssAltitude =
                            altitude


                        latestGnssAccuracy =
                            accuracy


                        latestGnssSatellites =
                            satellites


                        latestGnssHdop =
                            hdop


                        latestGnssFixType =
                            fixType ?: "--"


                        /*
                         * Save the latest QLM29H course.
                         *
                         * Do not modify headingDegrees here.
                         *
                         * Once PDR starts, the phone gyro controls
                         * the heading.
                         */

                        if (
                            course.isFinite()
                        ) {

                            latestGnssCourse =
                                normalizeHeading(
                                    course
                                )
                        }


                        gnssText.text =
                            """
                            Latitude: ${formatCoordinate(latitude)}
                            
                            Longitude: ${formatCoordinate(longitude)}
                            
                            Altitude: ${format3(altitude)} m
                            
                            Accuracy: ${format3(accuracy)} m
                            
                            Satellites: ${
                                if (
                                    satellites >= 0
                                )
                                    satellites
                                else
                                    "--"
                            }
                            
                            HDOP: ${format2(hdop)}
                            
                            Fix Type: ${fixType ?: "--"}
                            
                            Position Source: ${positionSource ?: "--"}
                            
                            Course: ${
                                if (course.isFinite())
                                    String.format(
                                        "%.1f°",
                                        course
                                    )
                                else
                                    "--"
                            }
                            """.trimIndent()


                        /*
                         * GNSS availability can change while the
                         * app is running, so update START DR button.
                         */

                        updateDrButtonState()
                    }
                }
            }
        }


    // ======================================================
    // CREATE
    // ======================================================

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )


        setContentView(
            R.layout.activity_main
        )


        // ==================================================
        // VIEWS
        // ==================================================

        deviceSpinner =
            findViewById(
                R.id.deviceSpinner
            )


        positionSourceSpinner =
            findViewById(
                R.id.positionSourceSpinner
            )

        comparePositionsButton =
            findViewById(
                R.id.comparePositionsButton
            )

        androidLocationText =
            findViewById(
                R.id.androidLocationText
            )


        connectButton =
            findViewById(
                R.id.connectButton
            )


        ntripButton =
            findViewById(
                R.id.ntripButton
            )


        mockLocationButton =
            findViewById(
                R.id.mockLocationButton
            )


        startDrButton =
            findViewById(
                R.id.startDrButton
            )


        stopDrButton =
            findViewById(
                R.id.stopDrButton
            )


        resetDrButton =
            findViewById(
                R.id.resetDrButton
            )


        statusText =
            findViewById(
                R.id.statusText
            )


        ntripStatusText =
            findViewById(
                R.id.ntripStatusText
            )


        mockLocationStatusText =
            findViewById(
                R.id.mockLocationStatusText
            )
        gnssText =
            findViewById(
                R.id.gnssText
            )


        pdrText =
            findViewById(
                R.id.pdrText
            )


        // ==================================================
        // MOCK LOCATION MANAGER
        // ==================================================

        mockLocationManager =
            MockLocationManager(
                context = this
            )


        // ==================================================
        // SENSORS
        // ==================================================

        initializeSensors()


        // ==================================================
        // POSITION SOURCE
        // ==================================================

        setupPositionSourceSelector()


        androidLocationText.text =
            "Android Location: ⚪ OFF\n\nWaiting for Android location..."


        // INITIAL NTRIP
        // ==================================================

        ntripEnabled =
            false


        ntripButton.text =
            "Enable NTRIP"


        ntripButton.isEnabled =
            false


        ntripStatusText.text =
            "🔴 NTRIP Disabled"
// ==================================================
        // INITIAL PHONE PDR
        // ==================================================

        pdrText.text =
            """
            Phone PDR: ⚪ STOPPED
            
            Waiting for START DR...
            """.trimIndent()


        startDrButton.isEnabled =
            false


        stopDrButton.isEnabled =
            false


        resetDrButton.isEnabled =
            true


        // ==================================================
        // INITIAL MOCK LOCATION
        // ==================================================

        refreshMockLocationState()


        // ==================================================
        // USB
        // ==================================================

        loadUsbDevices()


        // ==================================================
        // CONNECT
        // ==================================================

        connectButton.setOnClickListener {

            if (bridgeRunning) {
                stopBridge()
            } else {
                connectSelectedPositionSource()
            }
        }


        // ==================================================
        // POSITION COMPARISON
        // ==================================================

        comparePositionsButton.setOnClickListener {

            if (comparisonEnabled) {
                disablePositionComparison()
            } else {
                enablePositionComparison()
            }
        }


        // ==================================================
        // NTRIP
        // ==================================================

        ntripButton.setOnClickListener {

            if (
                !bridgeRunning
            ) {

                return@setOnClickListener
            }


            if (
                ntripEnabled
            ) {

                disableNtrip()

            } else {

                enableNtrip()
            }
        }


        // ==================================================
        // MOCK LOCATION
        // ==================================================

        mockLocationButton.setOnClickListener {

            handleMockLocationButton()
        }


        // ==================================================
        // START DR
        // ==================================================

        startDrButton.setOnClickListener {

            startDr()
        }


        // ==================================================
        // STOP DR
        // ==================================================

        stopDrButton.setOnClickListener {

            stopDr()
        }


        // ==================================================
        // RESET DR
        // ==================================================

        resetDrButton.setOnClickListener {

            resetDr()
        }
    }


    // ======================================================
    // SENSOR INITIALIZATION
    // ======================================================

    private fun initializeSensors() {

        sensorManager =
            getSystemService(
                SENSOR_SERVICE
            ) as SensorManager


        accelerometer =
            sensorManager.getDefaultSensor(
                Sensor.TYPE_ACCELEROMETER
            )


        gyroscope =
            sensorManager.getDefaultSensor(
                Sensor.TYPE_GYROSCOPE
            )


        rotationVector =
            sensorManager.getDefaultSensor(
                Sensor.TYPE_ROTATION_VECTOR
            )
    }


    // ======================================================
    // START LIFECYCLE
    // ======================================================

    override fun onStart() {

        super.onStart()


        registerBridgeReceiver()


        registerRotationVectorOnly()


        loadUsbDevices()
    }


    // ======================================================
    // RESUME
    // ======================================================

    override fun onResume() {

        super.onResume()


        if (
            ::mockLocationManager.isInitialized
        ) {

            refreshMockLocationState()
        }


        if (
            drRunning
        ) {

            registerSensors()

        } else {

            registerRotationVectorOnly()
        }
    }


    // ======================================================
    // PAUSE
    // ======================================================

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int
    ) {
        // Not required for the current PDR implementation.
    }


    override fun onPause() {

        super.onPause()


        if (
            drRunning
        ) {

            sensorManager
                .unregisterListener(
                    this
                )
        }
    }


    // ======================================================
    // STOP LIFECYCLE
    // ======================================================

    override fun onStop() {

        super.onStop()


        sensorManager.unregisterListener(this)


        try {

            unregisterReceiver(
                bridgeReceiver
            )

        } catch (
            _: Exception
        ) {
        }
    }


    // ======================================================
    // DESTROY
    // ======================================================

    override fun onDestroy() {

        /*
         * If Activity is destroyed while PDR is active,
         * stop PDR and resume GNSS backend forwarding.
         */

        if (
            drRunning
        ) {

            stopDr()
        }

        if (comparisonEnabled) {
            disablePositionComparison()
        }


        backendScope.cancel()


        super.onDestroy()
    }


    // ======================================================
    // REGISTER RECEIVER
    // ======================================================

    private fun registerBridgeReceiver() {

        val filter =
            IntentFilter().apply {

                addAction(
                    GnssBridgeService.ACTION_STATUS
                )


                addAction(
                    GnssBridgeService.ACTION_GNSS
                )

                addAction(
                    GnssBridgeService.ACTION_NTRIP_STATUS
                )


                addAction(
                    GnssBridgeService.ACTION_MOCK_STATUS
                )

                addAction(
                    GnssBridgeService.ACTION_ANDROID_LOCATION_STATUS
                )

                addAction(
                    GnssBridgeService.ACTION_COMPARISON_STATUS
                )

                addAction(
                    GnssBridgeService.ACTION_ANDROID_LOCATION
                )
            }


        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            registerReceiver(
                bridgeReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )

        } else {

            @Suppress(
                "DEPRECATION"
            )

            registerReceiver(
                bridgeReceiver,
                filter
            )
        }
    }


    // ======================================================
    // START PHONE PDR
    // ======================================================

    private fun startDr() {

        if (
            positionSourceSpinner.selectedItemPosition != 0
        ) {

            pdrText.text =
                "Phone PDR: 🔴 Cannot start\n\nSelect QLM29H GNSS first."

            return
        }


        if (
            drRunning
        ) {

            return
        }


        /*
         * A valid GNSS position is required to establish
         * the starting coordinate.
         */

        if (
            !latestGnssLatitude.isFinite() ||
            !latestGnssLongitude.isFinite()
        ) {

            pdrText.text =
                """
                Phone PDR: 🔴 Cannot start
                
                Waiting for a valid GNSS/RTK position.
                """.trimIndent()

            return
        }


        /*
         * Phone PDR uses the phone's absolute azimuth as the
         * initial real-world direction.
         *
         * The QLM29H course is intentionally NOT used here.
         */

        if (
            !rotationVectorHeading.isFinite()
        ) {

            pdrText.text =
                """
                Phone PDR: 🔴 Cannot start
                
                Waiting for phone heading.
                
                Keep the phone still for a moment.
                """.trimIndent()

            return
        }


        if (
            accelerometer == null ||
            gyroscope == null
        ) {

            pdrText.text =
                """
                Phone PDR: 🔴 Cannot start
                
                Accelerometer and gyroscope are required.
                """.trimIndent()

            return
        }


        // ==================================================
        // SAVE GNSS/RTK ANCHOR
        // ==================================================

        anchorLatitude =
            latestGnssLatitude


        anchorLongitude =
            latestGnssLongitude


        // ==================================================
        // HEADING SYNCHRONIZATION
        // ==================================================

        /*
         * IMPORTANT:
         *
         * The phone's absolute azimuth provides the initial
         * real-world walking direction.
         *
         * Example:
         *
         * Phone azimuth = 310°
         *
         * Therefore:
         *
         * PDR Heading = 310°
         *
         * From this point onward, the phone gyroscope tracks
         * only the subsequent turns.
         */

        val phoneAzimuth =
            normalizeHeading(
                rotationVectorHeading
            )

        startHeadingDegrees =
            phoneAzimuth

        headingDegrees =
            phoneAzimuth

        Log.d(
            "PDR_HEADING",
            "START DR - phone azimuth=" +
                    String.format(
                        "%.2f",
                        phoneAzimuth
                    ) +
                    "°, initial PDR heading=" +
                    String.format(
                        "%.2f",
                        headingDegrees
                    )
        )


        // ==================================================
        // RESET PDR
        // ==================================================

        steps =
            0


        distance =
            0.0


        posX =
            0.0


        posY =
            0.0


        /*
         * IMPORTANT:
         *
         * Do NOT reset headingDegrees here.
         *
         * It has just been initialized from the phone azimuth.
         */


        gravityEstimate =
            9.81


        filteredAcceleration =
            0.0


        lastStepTimeNs =
            0L


        lastGyroTimestampNs =
            0L


        gyroZ =
            0.0


        accelerationHistory.clear()


        // ==================================================
        // TELL SERVICE TO STOP GNSS BACKEND FORWARDING
        // ==================================================

        val pdrOnIntent =
            Intent(
                this,
                GnssBridgeService::class.java
            )


        pdrOnIntent.action =
            GnssBridgeService.ACTION_PDR_ON


        startService(
            pdrOnIntent
        )


        // ==================================================
        // START PDR
        // ==================================================

        drRunning =
            true


        registerSensors()


        startDrButton.isEnabled =
            false


        stopDrButton.isEnabled =
            true


        resetDrButton.isEnabled =
            true


        statusText.text =
            "DR Status: 🟠 PHONE PDR RUNNING"


        updatePdrDisplay()
    }


    // ======================================================
    // STOP PHONE PDR
    // ======================================================

    private fun stopDr() {

        if (
            !drRunning
        ) {

            /*
             * Make sure backend GNSS forwarding is enabled
             * even if this method is called during cleanup.
             */

            sendPdrOffToService()

            return
        }


        drRunning =
            false


        sensorManager
            .unregisterListener(
                this
            )


        // ==================================================
        // RESUME GNSS BACKEND FORWARDING
        // ==================================================

        sendPdrOffToService()


        startDrButton.isEnabled =
            bridgeRunning &&
                    latestGnssLatitude.isFinite() &&
                    latestGnssLongitude.isFinite() &&
                    rotationVectorHeading.isFinite()


        stopDrButton.isEnabled =
            false


        statusText.text =
            "DR Status: PHONE PDR STOPPED"


        updatePdrDisplay()
    }


    // ======================================================
    // PDR OFF → SERVICE
    // ======================================================

    private fun sendPdrOffToService() {

        val intent =
            Intent(
                this,
                GnssBridgeService::class.java
            )


        intent.action =
            GnssBridgeService.ACTION_PDR_OFF


        startService(
            intent
        )
    }


    // ======================================================
    // RESET
    // ======================================================

    private fun resetDr() {

        if (
            drRunning
        ) {

            stopDr()
        }


        steps =
            0


        distance =
            0.0


        posX =
            0.0


        posY =
            0.0


        headingDegrees =
            0.0


        startHeadingDegrees =
            Double.NaN



        anchorLatitude =
            Double.NaN


        anchorLongitude =
            Double.NaN


        gravityEstimate =
            9.81


        filteredAcceleration =
            0.0


        lastStepTimeNs =
            0L


        lastGyroTimestampNs =
            0L


        gyroZ =
            0.0


        accelerationHistory.clear()


        rotationVectorHeading =
            Double.NaN


        pdrText.text =
            """
            Phone PDR: ⚪ STOPPED
            
            Waiting for START DR...
            """.trimIndent()


        statusText.text =
            if (
                bridgeRunning
            ) {

                "GNSS Bridge running"

            } else {

                "DR Status: READY"
            }


        updateDrButtonState()
    }


    // ======================================================
    // SENSOR REGISTRATION
    // ======================================================

    private fun registerRotationVectorOnly() {

        rotationVector?.let {

            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_GAME
            )
        }
    }


    private fun registerSensors() {

        accelerometer?.let {

            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_GAME
            )
        }


        gyroscope?.let {

            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_GAME
            )
        }


        rotationVector?.let {

            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_GAME
            )
        }
    }


    // ======================================================
    // SENSOR EVENTS
    // ======================================================

    override fun onSensorChanged(
        event: SensorEvent
    ) {

        when (
            event.sensor.type
        ) {

            Sensor.TYPE_ACCELEROMETER -> {

                processAccelerometer(
                    event
                )
            }


            Sensor.TYPE_GYROSCOPE -> {

                processGyroscope(
                    event
                )
            }


            Sensor.TYPE_ROTATION_VECTOR -> {

                processRotationVector(
                    event
                )
            }
        }
    }


    // ======================================================
    // ACCELEROMETER
    // ======================================================

    private fun processAccelerometer(
        event: SensorEvent
    ) {

        val x =
            event.values[0].toDouble()


        val y =
            event.values[1].toDouble()


        val z =
            event.values[2].toDouble()


        val magnitude =
            sqrt(
                x * x +
                        y * y +
                        z * z
            )


        accelMagnitude =
            magnitude


        gravityEstimate =
            0.90 * gravityEstimate +
                    0.10 * magnitude


        val linearAcceleration =
            magnitude -
                    gravityEstimate


        filteredAcceleration =
            0.75 * filteredAcceleration +
                    0.25 * linearAcceleration


        accelerationHistory.addLast(
            filteredAcceleration
        )


        if (
            accelerationHistory.size >
            8
        ) {

            accelerationHistory.removeFirst()
        }


        if (
            !drRunning
        ) {

            return
        }


        detectStep(
            event.timestamp
        )
    }


    // ======================================================
    // STEP DETECTION
    // ======================================================

    private fun detectStep(
        timestampNs: Long
    ) {

        if (
            accelerationHistory.size <
            5
        ) {

            return
        }


        val values =
            accelerationHistory.toList()


        val current =
            values.last()


        val previous =
            values[
                values.size - 2
            ]


        val isPeak =
            current > previous &&
                    current > 0.80


        if (
            !isPeak
        ) {

            return
        }


        if (
            lastStepTimeNs != 0L
        ) {

            val interval =
                timestampNs -
                        lastStepTimeNs


            if (
                interval <
                minimumStepIntervalNs
            ) {

                return
            }
        }


        lastStepTimeNs =
            timestampNs


        registerStep()
    }


    // ======================================================
    // GYROSCOPE
    // ======================================================

    private fun processGyroscope(
        event: SensorEvent
    ) {

        val gx =
            event.values[0].toDouble()


        val gy =
            event.values[1].toDouble()


        val gz =
            event.values[2].toDouble()


        gyroZ =
            gz


        gyroMagnitude =
            sqrt(
                gx * gx +
                        gy * gy +
                        gz * gz
            )


        if (
            drRunning
        ) {

            if (
                lastGyroTimestampNs != 0L
            ) {

                val dt =
                    (
                            event.timestamp -
                                    lastGyroTimestampNs
                            ) /
                            1_000_000_000.0


                if (
                    dt > 0.0 &&
                    dt < 0.2
                ) {

                    /*
                     * Negative sign is intentional.
                     *
                     * This is the tested version where:
                     *
                     * Right turn → heading increases
                     * Left turn  → heading decreases
                     *
                     * The heading was already behaving correctly
                     * after the initial direction.
                     */

                    val deltaRadians =
                        -gz * dt


                    val deltaDegrees =
                        Math.toDegrees(
                            deltaRadians
                        )


                    headingDegrees +=
                        deltaDegrees


                    headingDegrees =
                        normalizeHeading(
                            headingDegrees
                        )
                }
            }


            lastGyroTimestampNs =
                event.timestamp
        }
    }


    // ======================================================
    // ROTATION VECTOR
    // ======================================================

    private fun processRotationVector(
        event: SensorEvent
    ) {

        SensorManager
            .getRotationMatrixFromVector(
                rotationMatrix,
                event.values
            )


        SensorManager.getOrientation(
            rotationMatrix,
            orientation
        )


        var azimuth =
            Math.toDegrees(
                orientation[0].toDouble()
            )


        if (
            azimuth < 0.0
        ) {

            azimuth += 360.0
        }


        rotationVectorHeading =
            azimuth


        // The phone heading is now available, so START DR
        // can be enabled if GNSS position is also ready.
        if (!drRunning) {
            updateDrButtonState()
        }
    }


    // ======================================================
    // STEP POSITION UPDATE
    // ======================================================

    private fun registerStep() {

        steps++


        distance +=
            stepLengthMeters


        val headingRadians =
            Math.toRadians(
                headingDegrees
            )


        /*
         * headingDegrees is now an ABSOLUTE heading.
         *
         * 0°   = North
         * 90°  = East
         * 180° = South
         * 270° = West
         */

        val deltaX =
            stepLengthMeters *
                    sin(
                        headingRadians
                    )


        val deltaY =
            stepLengthMeters *
                    cos(
                        headingRadians
                    )


        posX +=
            deltaX


        posY +=
            deltaY


        updatePdrDisplay()


        /*
         * Every detected step generates a new PDR
         * latitude/longitude and sends it to the same
         * backend endpoint used by GNSS.
         */

        sendPdrPositionToBackend()
    }


    // ======================================================
    // PDR LATITUDE
    // ======================================================

    private fun calculatePdrLatitude():
            Double {

        /*
         * Approximately 111,320 metres per degree
         * of latitude.
         */

        return anchorLatitude +
                (
                        posY /
                                111_320.0
                        )
    }


    // ======================================================
    // PDR LONGITUDE
    // ======================================================

    private fun calculatePdrLongitude():
            Double {

        /*
         * Longitude scale depends on latitude.
         */

        val latitudeRadians =
            Math.toRadians(
                anchorLatitude
            )


        val metersPerLongitudeDegree =
            111_320.0 *
                    cos(
                        latitudeRadians
                    )


        if (
            metersPerLongitudeDegree == 0.0
        ) {

            return anchorLongitude
        }


        return anchorLongitude +
                (
                        posX /
                                metersPerLongitudeDegree
                        )
    }


    // ======================================================
    // SEND PDR TO BACKEND
    // ======================================================

    private fun sendPdrPositionToBackend() {

        if (
            !drRunning
        ) {

            return
        }


        if (
            !anchorLatitude.isFinite() ||
            !anchorLongitude.isFinite()
        ) {

            return
        }


        val pdrLatitude =
            calculatePdrLatitude()


        val pdrLongitude =
            calculatePdrLongitude()


        val safeAltitude =
            if (latestGnssAltitude.isFinite())
                latestGnssAltitude
            else
                0.0

        val safeAccuracy =
            if (latestGnssAccuracy.isFinite())
                latestGnssAccuracy
            else
                0.0

        val safeSatellites =
            if (latestGnssSatellites >= 0)
                latestGnssSatellites
            else
                0

        val safeHdop =
            if (latestGnssHdop.isFinite())
                latestGnssHdop
            else
                0.0

        val safeCourse =
            if (headingDegrees.isFinite())
                normalizeHeading(headingDegrees)
            else
                0.0

        backendScope.launch {

            try {

                backendClient.sendPdrData(

                    latitude =
                        pdrLatitude,

                    longitude =
                        pdrLongitude,

                    altitude =
                        safeAltitude,

                    accuracy =
                        safeAccuracy,

                    satellites =
                        safeSatellites,

                    hdop =
                        safeHdop,

                    fixType =
                        "Phone PDR",

                    connected =
                        true,

                    status =
                        "Phone PDR",

                    positionSource =
                        "Phone PDR",

                    speed =
                        0.0,

                    course =
                        safeCourse
                )

            } catch (e: Exception) {

                Log.e(
                    "PDR_BACKEND",
                    "PDR backend update failed",
                    e
                )
            }
        }
    }


    // ======================================================
    // HEADING NORMALIZATION
    // ======================================================

    private fun normalizeHeading(
        heading: Double
    ): Double {

        var result =
            heading % 360.0


        if (
            result < 0.0
        ) {

            result += 360.0
        }


        return result
    }


    // ======================================================
    // PDR DISPLAY
    // ======================================================

    private fun updatePdrDisplay() {

        runOnUiThread {

            if (
                !drRunning
            ) {

                pdrText.text =
                    """
                    Phone PDR: ⚪ STOPPED
                    
                    Steps: $steps
                    
                    Distance: ${
                        String.format(
                            "%.2f",
                            distance
                        )
                    } m
                    """.trimIndent()

                return@runOnUiThread
            }


            val pdrLatitude =
                if (
                    anchorLatitude.isFinite()
                ) {

                    calculatePdrLatitude()

                } else {

                    Double.NaN
                }


            val pdrLongitude =
                if (
                    anchorLongitude.isFinite()
                ) {

                    calculatePdrLongitude()

                } else {

                    Double.NaN
                }


            pdrText.text =
                """
                Phone PDR: 🟢 RUNNING
                
                Initial Phone Heading: ${
                    if (
                        startHeadingDegrees.isFinite()
                    )
                        String.format(
                            "%.1f",
                            startHeadingDegrees
                        )
                    else
                        "--"
                }°
                
                Current Phone Azimuth: ${
                    if (
                        rotationVectorHeading.isFinite()
                    )
                        String.format(
                            "%.1f",
                            rotationVectorHeading
                        )
                    else
                        "--"
                }°
                
                Anchor Latitude: ${
                    formatCoordinate(
                        anchorLatitude
                    )
                }
                
                Anchor Longitude: ${
                    formatCoordinate(
                        anchorLongitude
                    )
                }
                
                PDR Latitude: ${
                    formatCoordinate(
                        pdrLatitude
                    )
                }
                
                PDR Longitude: ${
                    formatCoordinate(
                        pdrLongitude
                    )
                }
                
                Steps: $steps
                
                Distance: ${
                    String.format(
                        "%.2f",
                        distance
                    )
                } m
                
                Current Heading: ${
                    String.format(
                        "%.1f",
                        headingDegrees
                    )
                }°
                
                X / East: ${
                    String.format(
                        "%.2f",
                        posX
                    )
                } m
                
                Y / North: ${
                    String.format(
                        "%.2f",
                        posY
                    )
                } m
                """.trimIndent()
        }
    }


    // ======================================================
    // MOCK STATE
    // ======================================================

    private fun refreshMockLocationState() {

        mockLocationSelected =
            mockLocationManager
                .isSelectedAsMockLocationApp()


        if (
            !mockLocationSelected
        ) {

            mockLocationEnabled =
                false


            mockLocationStatusText.text =
                "🔴 Mock Location OFF"
        }


        updateMockLocationUi()
    }


    // ======================================================
    // MOCK UI
    // ======================================================

    private fun updateMockLocationUi() {

        if (
            !mockLocationSelected
        ) {

            mockLocationButton.text =
                "⚙️ Setup Mock Location"


            mockLocationButton.isEnabled =
                true


            mockLocationStatusText.text =
                "🔴 Mock Location OFF"

            return
        }


        if (
            mockLocationEnabled
        ) {

            mockLocationButton.text =
                "Disable Mock Location"


            mockLocationButton.isEnabled =
                true


            mockLocationStatusText.text =
                "🟢 Mock Location ON"

            return
        }


        mockLocationButton.text =
            "Enable Mock Location"


        mockLocationButton.isEnabled =
            bridgeRunning


        mockLocationStatusText.text =
            "🔴 Mock Location OFF"
    }


    // ======================================================
    // MOCK BUTTON
    // ======================================================

    private fun handleMockLocationButton() {

        if (
            !mockLocationSelected
        ) {

            openDeveloperOptions()

            return
        }


        if (
            mockLocationEnabled
        ) {

            disableMockLocation()

            return
        }


        if (
            !bridgeRunning
        ) {

            mockLocationStatusText.text =
                "🔴 Start GNSS Bridge first."

            return
        }


        enableMockLocation()
    }


    // ======================================================
    // DEVELOPER OPTIONS
    // ======================================================

    private fun openDeveloperOptions() {

        try {

            val intent =
                Intent(
                    Settings
                        .ACTION_APPLICATION_DEVELOPMENT_SETTINGS
                )


            startActivity(
                intent
            )

        } catch (
            _: Exception
        ) {

            try {

                startActivity(
                    Intent(
                        Settings.ACTION_SETTINGS
                    )
                )

            } catch (
                _: Exception
            ) {
            }
        }
    }


    // ======================================================
    // ENABLE MOCK
    // ======================================================

    private fun enableMockLocation() {

        val intent =
            Intent(
                this,
                GnssBridgeService::class.java
            )


        intent.action =
            GnssBridgeService.ACTION_MOCK_ON


        startService(
            intent
        )
    }


    // ======================================================
    // DISABLE MOCK
    // ======================================================

    private fun disableMockLocation() {

        val intent =
            Intent(
                this,
                GnssBridgeService::class.java
            )


        intent.action =
            GnssBridgeService.ACTION_MOCK_OFF


        startService(
            intent
        )


        mockLocationEnabled =
            false


        mockLocationStatusText.text =
            "🔴 Mock Location OFF"


        updateMockLocationUi()
    }


    // ======================================================
    // USB DEVICES
    // ======================================================

    private fun loadUsbDevices() {

        val usbManager =
            getSystemService(
                Context.USB_SERVICE
            ) as android.hardware.usb.UsbManager


        devices =
            usbManager
                .deviceList
                .values
                .toList()


        val deviceNames =
            devices.map { device ->

                val product =
                    try {

                        device.productName
                            ?: "USB Serial Device"

                    } catch (
                        _: Exception
                    ) {

                        "USB Serial Device"
                    }


                val manufacturer =
                    try {

                        device.manufacturerName
                            ?: "Unknown"

                    } catch (
                        _: Exception
                    ) {

                        "Unknown"
                    }


                "$product - $manufacturer\n" +
                        "VID: ${
                            String.format(
                                "%04X",
                                device.vendorId
                            )
                        }  " +
                        "PID: ${
                            String.format(
                                "%04X",
                                device.productId
                            )
                        }"
            }


        if (
            deviceNames.isEmpty()
        ) {

            val adapter =
                ArrayAdapter(
                    this,
                    android.R.layout.simple_spinner_item,
                    listOf(
                        "No USB GNSS receiver detected"
                    )
                )


            adapter.setDropDownViewResource(
                android.R.layout
                    .simple_spinner_dropdown_item
            )


            deviceSpinner.adapter =
                adapter


            connectButton.isEnabled = !bridgeRunning
            ntripButton.isEnabled = false
            startDrButton.isEnabled = false

            if (positionSourceSpinner.selectedItemPosition == 1) {
                statusText.text =
                    "Android Location selected — ready to connect"
            } else {
                statusText.text =
                    "Waiting for GNSS receiver..."
            }


            updateMockLocationUi()

            return
        }


        val adapter =
            ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                deviceNames
            )


        adapter.setDropDownViewResource(
            android.R.layout
                .simple_spinner_dropdown_item
        )


        deviceSpinner.adapter =
            adapter


        connectButton.isEnabled =
            !bridgeRunning


        val ch340Index =
            devices.indexOfFirst { device ->

                device.vendorId ==
                        0x1A86 &&

                        device.productId ==
                        0x7523
            }


        if (
            ch340Index >= 0
        ) {

            deviceSpinner
                .setSelection(
                    ch340Index
                )
        }


        if (
            !bridgeRunning
        ) {

            statusText.text =
                "USB GNSS receiver detected"
        }


        updateMockLocationUi()


        updateDrButtonState()
    }


    // ======================================================
    // DR BUTTON STATE
    // ======================================================

    private fun updateDrButtonState() {

        /*
         * START DR requires:
         *
         * 1. GNSS bridge running
         * 2. PDR not already running
         * 3. Valid GNSS latitude
         * 4. Valid GNSS longitude
         * 5. Valid phone absolute azimuth
         *
         * The phone azimuth is used to establish the initial
         * absolute PDR heading.
         */

        startDrButton.isEnabled =
            bridgeRunning &&
                    positionSourceSpinner.selectedItemPosition == 0 &&
                    !drRunning &&
                    latestGnssLatitude.isFinite() &&
                    latestGnssLongitude.isFinite() &&
                    rotationVectorHeading.isFinite()


        stopDrButton.isEnabled =
            drRunning
    }


    // ======================================================
    // CONNECT SELECTED POSITION SOURCE
    // ======================================================

    private fun connectSelectedPositionSource() {
        if (positionSourceSpinner.selectedItemPosition == 1) {
            connectAndroidLocation()
        } else {
            startBridge()
        }
    }


    // ======================================================
    // START GNSS BRIDGE
    // ======================================================

    private fun startBridge() {

        if (devices.isEmpty()) {
            statusText.text =
                "No USB GNSS receiver detected. Plug in the QLM29H first."
            return
        }

        val selectedPosition = deviceSpinner.selectedItemPosition

        if (selectedPosition < 0 || selectedPosition >= devices.size) {
            statusText.text =
                "Select a GNSS receiver first."
            return
        }

        val device = devices[selectedPosition]

        statusText.text = "Starting GNSS bridge..."
        bridgeRunning = true

        pendingAndroidLocationEnable = false
        pendingComparisonEnable = false
        comparisonEnabled = false
        androidLocationEnabled = false
        disableAndroidLocation()

        if (drRunning) {
            stopDr()
        }

        resetDr()

        ntripEnabled = false
        ntripButton.text = "Enable NTRIP"
        ntripButton.isEnabled = true
        ntripStatusText.text = "🔴 NTRIP Disabled"

        mockLocationEnabled = false
        refreshMockLocationState()

        connectButton.text = "Disconnect"
        updatePositionSourceUi()

        val intent = Intent(
            this,
            GnssBridgeService::class.java
        ).apply {
            action = GnssBridgeService.ACTION_CONNECT
            putExtra(GnssBridgeService.EXTRA_VENDOR_ID, device.vendorId)
            putExtra(GnssBridgeService.EXTRA_PRODUCT_ID, device.productId)
        }

        ContextCompat.startForegroundService(this, intent)
    }


    // ======================================================
    // STOP BRIDGE
    // ======================================================

    private fun stopBridge() {

        if (drRunning) {
            stopDr()
        }

        if (comparisonEnabled) {
            disablePositionComparison()
        } else {
            disableAndroidLocation()
        }

        ntripEnabled = false
        ntripButton.text = "Enable NTRIP"
        ntripButton.isEnabled = false
        ntripStatusText.text = "🔴 NTRIP Disabled"

        mockLocationEnabled = false
        mockLocationStatusText.text = "🔴 Mock Location OFF"

        val intent = Intent(
            this,
            GnssBridgeService::class.java
        ).apply {
            action = GnssBridgeService.ACTION_STOP
        }

        startService(intent)

        bridgeRunning = false
        androidLocationEnabled = false
        connectButton.text = "Connect"
        statusText.text = "Disconnected"

        resetDr()
        refreshMockLocationState()
        updatePositionSourceUi()
    }


    // ======================================================
    // NTRIP
    // ======================================================

    private fun enableNtrip() {

        if (
            !bridgeRunning
        ) {

            return
        }


        ntripEnabled =
            true


        ntripButton.text =
            "Disable NTRIP"


        ntripStatusText.text =
            "🟡 NTRIP enabled - connecting..."


        val intent =
            Intent(
                this,
                GnssBridgeService::class.java
            )


        intent.action =
            GnssBridgeService.ACTION_NTRIP_ON


        startService(
            intent
        )
    }


    private fun disableNtrip() {

        ntripEnabled =
            false


        ntripButton.text =
            "Enable NTRIP"


        ntripStatusText.text =
            "🔴 NTRIP Disabled"


        val intent =
            Intent(
                this,
                GnssBridgeService::class.java
            )


        intent.action =
            GnssBridgeService.ACTION_NTRIP_OFF


        startService(
            intent
        )
    }


    // ======================================================
    // POSITION SOURCE
    // ======================================================

    private fun setupPositionSourceSelector() {

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf(
                "QLM29H GNSS",
                "Android Location"
            )
        )

        adapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        )

        positionSourceSpinner.adapter = adapter
        positionSourceSpinner.setSelection(0)

        positionSourceSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {

                override fun onNothingSelected(
                    parent: android.widget.AdapterView<*>?
                ) {
                }

                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    if (drRunning) return

                    if (!bridgeRunning || comparisonEnabled) {
                        updatePositionSourceUi()
                        updateDrButtonState()
                        return
                    }

                    if (position == 1) {
                        switchToAndroidLocation()
                    } else {
                        switchToQlM29h()
                    }

                    updatePositionSourceUi()
                    updateDrButtonState()
                }
            }

        updatePositionSourceUi()
    }


    private fun updatePositionSourceUi() {

        val androidSelected =
            positionSourceSpinner.selectedItemPosition == 1

        positionSourceSpinner.isEnabled = !drRunning && !comparisonEnabled

        deviceSpinner.isEnabled =
            !bridgeRunning && !androidSelected

        connectButton.isEnabled = true
        connectButton.text =
            if (bridgeRunning) "Disconnect" else "Connect"

        comparePositionsButton.text =
            if (comparisonEnabled) "Stop Comparison" else "Compare Positions"

        comparePositionsButton.isEnabled =
            bridgeRunning &&
                    positionSourceSpinner.selectedItemPosition == 0 &&
                    !drRunning

        ntripButton.isEnabled =
            bridgeRunning && !androidSelected

        mockLocationButton.isEnabled =
            bridgeRunning && !androidSelected

        if (androidSelected) {
            startDrButton.isEnabled = false
            stopDrButton.isEnabled = false

            if (!androidLocationEnabled && !bridgeRunning) {
                androidLocationText.text =
                    "Android Location: ⚪ Ready to connect"
            } else if (!androidLocationEnabled) {
                androidLocationText.text =
                    "Android Location: 🟡 Starting..."
            }
        } else {
            updateDrButtonState()
        }
    }


    private fun enablePositionComparison() {

        if (!bridgeRunning) {
            statusText.text =
                "Connect to QLM29H GNSS first to start position comparison."
            return
        }

        if (positionSourceSpinner.selectedItemPosition != 0) {
            statusText.text =
                "Select QLM29H GNSS to start position comparison."
            return
        }

        if (drRunning) {
            statusText.text =
                "Stop Phone PDR before changing comparison mode."
            return
        }

        val fineGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {
            pendingComparisonEnable = true

            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQUEST_ANDROID_LOCATION
            )

            statusText.text =
                "Requesting Android Location permission for comparison..."
            return
        }

        pendingComparisonEnable = false
        comparisonEnabled = true
        androidLocationEnabled = true

        val intent = Intent(
            this,
            GnssBridgeService::class.java
        ).apply {
            action = GnssBridgeService.ACTION_COMPARISON_ON
        }

        startService(intent)

        statusText.text =
            "🟢 Position Comparison active — QLM29H + Android Location"

        updatePositionSourceUi()
    }


    private fun disablePositionComparison() {

        pendingComparisonEnable = false
        comparisonEnabled = false
        androidLocationEnabled = false

        val intent = Intent(
            this,
            GnssBridgeService::class.java
        ).apply {
            action = GnssBridgeService.ACTION_COMPARISON_OFF
        }

        startService(intent)

        statusText.text =
            "🟢 QLM29H GNSS active"

        updatePositionSourceUi()
    }


    private fun switchToAndroidLocation() {

        if (!bridgeRunning || drRunning) return

        val fineGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {
            pendingAndroidLocationEnable = true

            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQUEST_ANDROID_LOCATION
            )

            statusText.text =
                "Requesting Android Location permission..."
            return
        }

        pendingAndroidLocationEnable = false

        val intent = Intent(
            this,
            GnssBridgeService::class.java
        ).apply {
            action = GnssBridgeService.ACTION_ANDROID_LOCATION_ON
        }

        startService(intent)
        statusText.text = "Switching to Android Location..."
    }


    private fun switchToQlM29h() {

        if (!bridgeRunning || drRunning) return

        if (devices.isEmpty()) {
            statusText.text =
                "QLM29H selected — plug in the GNSS receiver."
            return
        }

        val selectedPosition = deviceSpinner.selectedItemPosition

        if (selectedPosition < 0 || selectedPosition >= devices.size) {
            statusText.text =
                "Select a QLM29H GNSS receiver first."
            return
        }

        val device = devices[selectedPosition]
        pendingAndroidLocationEnable = false

        val intent = Intent(
            this,
            GnssBridgeService::class.java
        ).apply {
            action = GnssBridgeService.ACTION_CONNECT
            putExtra(GnssBridgeService.EXTRA_VENDOR_ID, device.vendorId)
            putExtra(GnssBridgeService.EXTRA_PRODUCT_ID, device.productId)
        }

        startService(intent)
        statusText.text = "Switching to QLM29H GNSS..."
    }


    private fun connectAndroidLocation() {

        val fineGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {
            pendingAndroidLocationEnable = true

            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQUEST_ANDROID_LOCATION
            )

            statusText.text =
                "Requesting Android Location permission..."
            return
        }

        pendingAndroidLocationEnable = false
        bridgeRunning = true
        connectButton.text = "Disconnect"
        updatePositionSourceUi()

        val intent = Intent(
            this,
            GnssBridgeService::class.java
        ).apply {
            action = GnssBridgeService.ACTION_ANDROID_LOCATION_ON
        }

        ContextCompat.startForegroundService(this, intent)
    }


    private fun enableAndroidLocation() {
        connectAndroidLocation()
    }


    private fun disableAndroidLocation() {

        pendingAndroidLocationEnable = false

        if (!::positionSourceSpinner.isInitialized) {
            return
        }

        val intent =
            Intent(
                this,
                GnssBridgeService::class.java
            )

        intent.action =
            GnssBridgeService.ACTION_ANDROID_LOCATION_OFF

        startService(intent)
    }


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {

        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode != REQUEST_ANDROID_LOCATION) {
            return
        }

        val fineGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        if (
            (fineGranted || coarseGranted) &&
            pendingComparisonEnable &&
            positionSourceSpinner.selectedItemPosition == 0
        ) {

            pendingComparisonEnable = false
            enablePositionComparison()
            return

        }

        if (
            (fineGranted || coarseGranted) &&
            pendingAndroidLocationEnable &&
            positionSourceSpinner.selectedItemPosition == 1
        ) {

            enableAndroidLocation()

        } else {

            pendingAndroidLocationEnable = false
            pendingComparisonEnable = false
            positionSourceSpinner.setSelection(0)
            statusText.text =
                "Android Location permission denied."
        }
    }


    // FORMATTING
    // ======================================================

    private fun formatCoordinate(
        value: Double
    ): String {

        if (
            !value.isFinite()
        ) {

            return "--"
        }


        return String.format(
            "%.8f",
            value
        )
    }


    private fun format3(
        value: Double
    ): String {

        if (
            !value.isFinite()
        ) {

            return "--"
        }


        return String.format(
            "%.3f",
            value
        )
    }


    private fun format2(
        value: Double
    ): String {

        if (
            !value.isFinite()
        ) {

            return "--"
        }


        return String.format(
            "%.2f",
            value
        )
    }
}
