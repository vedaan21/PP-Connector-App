package com.example.gnssbridge

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

class MockLocationManager(
    private val context: Context
) {

    companion object {

        private const val TAG =
            "MOCK_LOCATION"

        private const val PROVIDER =
            LocationManager.GPS_PROVIDER
    }


    private val locationManager =
        context.getSystemService(
            Context.LOCATION_SERVICE
        ) as LocationManager


    @Volatile
    private var mockEnabled =
        false


    // ==================================================
    // CHECK WHETHER APP IS SELECTED
    // ==================================================

    fun isSelectedAsMockLocationApp(): Boolean {

        return try {

            val appOps =
                context.getSystemService(
                    Context.APP_OPS_SERVICE
                ) as AppOpsManager


            @Suppress("DEPRECATION")

            val mode =
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION,
                    Process.myUid(),
                    context.packageName
                )


            mode ==
                    AppOpsManager.MODE_ALLOWED

        } catch (
            e: Exception
        ) {

            Log.e(
                TAG,
                "Unable to check mock-location state",
                e
            )

            false
        }
    }


    // ==================================================
    // IS OUR MOCK LOCATION CURRENTLY ACTIVE?
    // ==================================================

    fun isEnabled(): Boolean {

        return mockEnabled
    }


    // ==================================================
    // ENABLE
    // ==================================================

    fun enable(): Boolean {

        /*
         * First make sure Android has actually
         * selected this application as the
         * mock-location app.
         */

        if (
            !isSelectedAsMockLocationApp()
        ) {

            Log.d(
                TAG,
                "Airtel PP Connector is not selected as mock app"
            )

            mockEnabled =
                false

            return false
        }


        return try {

            /*
             * Remove an old test provider if one
             * exists from a previous session.
             */

            try {

                locationManager
                    .removeTestProvider(
                        PROVIDER
                    )

            } catch (
                _: Exception
            ) {
            }


            /*
             * Android 12+
             */

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S
            ) {

                val properties =
                    android.location.provider
                        .ProviderProperties
                        .Builder()

                        .setAccuracy(
                            android.location.provider
                                .ProviderProperties
                                .ACCURACY_FINE
                        )

                        .setPowerUsage(
                            android.location.provider
                                .ProviderProperties
                                .POWER_USAGE_LOW
                        )

                        .setHasAltitudeSupport(
                            true
                        )

                        .setHasSpeedSupport(
                            true
                        )

                        .setHasBearingSupport(
                            true
                        )

                        .setHasSatelliteRequirement(
                            false
                        )

                        .setHasNetworkRequirement(
                            false
                        )

                        .build()


                locationManager
                    .addTestProvider(
                        PROVIDER,
                        properties
                    )

            } else {

                @Suppress("DEPRECATION")

                locationManager
                    .addTestProvider(

                        PROVIDER,

                        false, // requiresNetwork
                        false, // requiresSatellite
                        false, // requiresCell
                        false, // hasMonetaryCost

                        true,  // supportsAltitude
                        true,  // supportsSpeed
                        true,  // supportsBearing

                        1,     // power usage
                        1      // fine accuracy
                    )
            }


            locationManager
                .setTestProviderEnabled(
                    PROVIDER,
                    true
                )


            mockEnabled =
                true


            Log.d(
                TAG,
                "🟢 Mock location provider enabled"
            )


            true

        } catch (
            e: SecurityException
        ) {

            Log.e(
                TAG,
                "Mock-location permission not granted",
                e
            )

            mockEnabled =
                false

            false

        } catch (
            e: Exception
        ) {

            Log.e(
                TAG,
                "Failed to enable mock location",
                e
            )

            mockEnabled =
                false

            false
        }
    }


    // ==================================================
    // INJECT GNSS LOCATION
    // ==================================================

    fun updateLocation(
        data: GnssData
    ) {

        if (
            !mockEnabled
        ) {
            return
        }


        /*
         * Check again because the user may have
         * removed this app from Developer Options
         * while the bridge was running.
         */

        if (
            !isSelectedAsMockLocationApp()
        ) {

            Log.d(
                TAG,
                "Mock-location selection was removed"
            )

            disable()

            return
        }


        val latitude =
            data.latitude
                ?: return


        val longitude =
            data.longitude
                ?: return


        try {

            val location =
                Location(
                    PROVIDER
                )


            location.latitude =
                latitude


            location.longitude =
                longitude


            /*
             * Altitude
             */

            data.altitude?.let {

                location.altitude =
                    it
            }


            /*
             * Horizontal accuracy
             */

            data.accuracy?.let {

                if (
                    it >= 0.0
                ) {

                    location.accuracy =
                        it.toFloat()
                }
            }


            /*
             * Time
             */

            location.time =
                System.currentTimeMillis()


            location.elapsedRealtimeNanos =
                SystemClock
                    .elapsedRealtimeNanos()


            /*
             * Send the location to Android.
             */

            locationManager
                .setTestProviderLocation(
                    PROVIDER,
                    location
                )


            Log.d(
                TAG,
                "Mock location updated: " +
                        "lat=$latitude " +
                        "lon=$longitude " +
                        "accuracy=${data.accuracy}"
            )

        } catch (
            e: SecurityException
        ) {

            Log.e(
                TAG,
                "Mock-location permission lost",
                e
            )

            disable()

        } catch (
            e: Exception
        ) {

            Log.e(
                TAG,
                "Failed to inject location",
                e
            )
        }
    }


    // ==================================================
    // DISABLE
    // ==================================================

    fun disable() {

        try {

            locationManager
                .setTestProviderEnabled(
                    PROVIDER,
                    false
                )

        } catch (
            _: Exception
        ) {
        }


        try {

            locationManager
                .removeTestProvider(
                    PROVIDER
                )

        } catch (
            _: Exception
        ) {
        }


        mockEnabled =
            false


        Log.d(
            TAG,
            "🔴 Mock location provider disabled"
        )
    }


    // ==================================================
    // OPEN DEVELOPER OPTIONS
    // ==================================================

    fun openDeveloperOptions() {

        try {

            val intent =
                Intent(
                    Settings
                        .ACTION_APPLICATION_DEVELOPMENT_SETTINGS
                )


            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
            )


            context.startActivity(
                intent
            )

        } catch (
            e: Exception
        ) {

            Log.e(
                TAG,
                "Unable to open Developer Options",
                e
            )


            try {

                val intent =
                    Intent(
                        Settings.ACTION_SETTINGS
                    )


                intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )


                context.startActivity(
                    intent
                )

            } catch (
                _: Exception
            ) {
            }
        }
    }
}