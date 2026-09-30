package com.example.gnssbridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber

import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread


class UsbGnssManager(
    private val context: Context,

    private val onUsbPermissionGranted:
        () -> Unit,

    private val onGnssData:
        (GnssData) -> Unit,

    private val onStatus:
        (String) -> Unit,

    private val onNmeaLine:
        (String) -> Unit
) {

    companion object {

        private const val ACTION_USB_PERMISSION =
            "com.example.gnssbridge.USB_PERMISSION"

        private const val TAG_NMEA =
            "GNSS_NMEA"

        private const val TAG_CMD =
            "GNSS_CMD"
    }


    private val usbManager =
        context.getSystemService(
            Context.USB_SERVICE
        ) as UsbManager


    private var port:
            UsbSerialPort? = null


    @Volatile
    private var running = false


    private val usbPermissionReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                if (
                    intent?.action !=
                    ACTION_USB_PERMISSION
                ) {
                    return
                }


                val device =
                    if (
                        Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.TIRAMISU
                    ) {

                        intent.getParcelableExtra(
                            UsbManager.EXTRA_DEVICE,
                            UsbDevice::class.java
                        )

                    } else {

                        @Suppress("DEPRECATION")

                        intent.getParcelableExtra(
                            UsbManager.EXTRA_DEVICE
                        )
                    }


                val granted =
                    intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED,
                        false
                    )


                if (
                    granted &&
                    device != null
                ) {

                    onStatus(
                        "USB permission granted"
                    )


                    /*
                     * Tell the foreground service that
                     * USB permission now exists.
                     */

                    onUsbPermissionGranted()


                    openDevice(device)

                } else {

                    onStatus(
                        "USB permission denied"
                    )
                }
            }
        }


    init {

        val filter =
            IntentFilter(
                ACTION_USB_PERMISSION
            )


        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            context.registerReceiver(
                usbPermissionReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )

        } else {

            @Suppress("DEPRECATION")

            context.registerReceiver(
                usbPermissionReceiver,
                filter
            )
        }
    }


    fun findDevices():
            List<UsbDevice> {

        return usbManager
            .deviceList
            .values
            .toList()
    }


    fun connect(
        device: UsbDevice
    ) {

        if (
            usbManager.hasPermission(
                device
            )
        ) {

            /*
             * Permission already exists.
             */

            onUsbPermissionGranted()

            openDevice(device)

            return
        }


        onStatus(
            "Requesting USB permission..."
        )


        val permissionIntent =
            PendingIntent.getBroadcast(

                context,

                0,

                Intent(
                    ACTION_USB_PERMISSION
                ).setPackage(
                    context.packageName
                ),

                PendingIntent.FLAG_IMMUTABLE
            )


        usbManager.requestPermission(
            device,
            permissionIntent
        )
    }


    private fun openDevice(
        device: UsbDevice
    ) {

        thread {

            try {

                onStatus(
                    "Opening USB GNSS receiver..."
                )


                val driver =
                    UsbSerialProber
                        .getDefaultProber()
                        .probeDevice(device)


                if (
                    driver == null
                ) {

                    onStatus(
                        "Unsupported USB serial device"
                    )

                    return@thread
                }


                val connection =
                    usbManager.openDevice(device)


                if (
                    connection == null
                ) {

                    onStatus(
                        "Could not open USB device"
                    )

                    return@thread
                }


                port =
                    driver.ports.firstOrNull()


                if (
                    port == null
                ) {

                    onStatus(
                        "No serial port found"
                    )

                    connection.close()

                    return@thread
                }


                port?.open(connection)


                port?.setParameters(
                    115200,
                    8,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE
                )


                running = true


                onStatus(
                    "🟢 GNSS Receiver Connected"
                )


                // ==================================================
                // ENABLE PQTMEPE
                // ==================================================

                /*
                 * PQTMEPE version 2.
                 *
                 * Output once every 2 position fixes.
                 */

                sendCommand(
                    "\$PQTMCFGMSGRATE,W,PQTMEPE,1,2*1D\r\n"
                )

                Thread.sleep(200)


                // ==================================================
                // ENABLE PQTMDRPVA
                // ==================================================

                /*
                 * PQTMDRPVA version 1.
                 *
                 * Output once every position fix.
                 *
                 * SolType:
                 *
                 * 0 = No Fix
                 * 1 = GNSS
                 * 2 = GNSS + DR
                 * 3 = DR
                 */

                sendCommand(
                    "\$PQTMCFGMSGRATE,W,PQTMDRPVA,1,1*1F\r\n"
                )

                Thread.sleep(200)


                // ==================================================
                // ENABLE PQTMDRCAL
                // ==================================================

                /*
                 * PQTMDRCAL version 1.
                 *
                 * Output once every position fix.
                 *
                 * This is the important command for our
                 * DR calibration test.
                 *
                 * Official command:
                 *
                 * $PQTMCFGMSGRATE,W,PQTMDRCAL,1,1*16
                 */

                sendCommand(
                    "\$PQTMCFGMSGRATE,W,PQTMDRCAL,1,1*16\r\n"
                )

                Thread.sleep(300)


                // ==================================================
                // QUERY PQTMDRCAL CONFIGURATION
                // ==================================================

                /*
                 * Ask the receiver what rate is currently
                 * configured for PQTMDRCAL.
                 *
                 * Expected successful response:
                 *
                 * $PQTMCFGMSGRATE,OK,PQTMDRCAL,1,1*...
                 */

                sendCommand(
                    "\$PQTMCFGMSGRATE,R,PQTMDRCAL,1*0E\r\n"
                )

                Thread.sleep(300)


                /*
                 * IMPORTANT:
                 *
                 * We intentionally do NOT send:
                 *
                 * $PQTMSAVEPAR*5A
                 *
                 * and we do NOT reboot the receiver yet.
                 *
                 * First we want to confirm that the receiver
                 * accepts PQTMDRCAL and actually outputs it.
                 */


                /*
                 * Now start reading NMEA.
                 */

                readLoop()

            } catch (
                e: Exception
            ) {

                running = false

                onStatus(
                    "USB error: ${e.message}"
                )

                closePort()
            }
        }
    }


    private fun readLoop() {

        val buffer =
            ByteArray(4096)


        val lineBuffer =
            StringBuilder()


        while (running) {

            try {

                val count =
                    port?.read(
                        buffer,
                        1000
                    ) ?: 0


                if (
                    count <= 0
                ) {
                    continue
                }


                val text =
                    String(
                        buffer,
                        0,
                        count,
                        StandardCharsets.US_ASCII
                    )


                lineBuffer.append(text)


                while (
                    lineBuffer.contains("\n")
                ) {

                    val newlineIndex =
                        lineBuffer.indexOf("\n")


                    val line =
                        lineBuffer
                            .substring(
                                0,
                                newlineIndex
                            )
                            .trim()


                    lineBuffer.delete(
                        0,
                        newlineIndex + 1
                    )


                    if (
                        line.isBlank()
                    ) {
                        continue
                    }


                    // ==================================================
                    // RAW NMEA LOGGING
                    // ==================================================

                    Log.d(
                        TAG_NMEA,
                        line
                    )


                    /*
                     * Pass raw NMEA to the foreground service.
                     */

                    onNmeaLine(line)


                    // ==================================================
                    // GNSS PARSING
                    // ==================================================

                    val data =
                        NmeaParser.parse(line)


                    if (
                        data != null
                    ) {

                        onGnssData(data)
                    }
                }

            } catch (
                e: Exception
            ) {

                running = false

                onStatus(
                    "🔴 GNSS Receiver Disconnected"
                )

                closePort()

                break
            }
        }
    }


    /*
     * Send a command to the GNSS receiver.
     */

    fun sendCommand(
        command: String
    ): Boolean {

        return try {

            val currentPort =
                port ?: return false


            Log.d(
                TAG_CMD,
                "TX: ${command.trim()}"
            )


            currentPort.write(
                command.toByteArray(
                    StandardCharsets.US_ASCII
                ),
                2000
            )


            onStatus(
                "GNSS command sent"
            )


            true

        } catch (
            e: Exception
        ) {

            Log.e(
                TAG_CMD,
                "Command failed",
                e
            )


            onStatus(
                "Command failed: ${e.message}"
            )


            false
        }
    }


    private fun closePort() {

        try {

            port?.close()

        } catch (
            _: Exception
        ) {
        }


        port = null
    }


    fun disconnect() {

        running = false

        closePort()

        onStatus(
            "GNSS Receiver Disconnected"
        )
    }


    fun destroy() {

        disconnect()

        try {

            context.unregisterReceiver(
                usbPermissionReceiver
            )

        } catch (
            _: Exception
        ) {
        }
    }


    fun writeRtcm(
        data: ByteArray
    ): Boolean {

        return try {

            val currentPort =
                port ?: return false


            currentPort.write(
                data,
                2000
            )


            true

        } catch (
            e: Exception
        ) {

            onStatus(
                "RTCM write error: ${e.message}"
            )

            false
        }
    }
}