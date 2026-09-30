package com.example.gnssbridge

import android.util.Base64
import android.util.Log

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets


class NtripClient(
    private val scope: CoroutineScope,
    private val onRtcmData: (ByteArray) -> Unit,
    private val onStatus: (String) -> Unit
) {

    companion object {

        private const val TAG =
            "NTRIP_DEBUG"

        private const val CONNECT_TIMEOUT_MS =
            10000

        private const val READ_TIMEOUT_MS =
            15000

        private const val RECONNECT_DELAY_MS =
            5000L
    }


    private val host =
        "caster.in-staging-all-freq-01.ce.swiftnav.com"

    private val port =
        2101

    private val mountPoint =
        "NXRTK-MSM5"

    private val username =
        "airtel.staging.demo"

    private val password =
        "0krqz6atb25q"


    private var socket: Socket? = null

    private var output: OutputStream? = null

    private var job: Job? = null

    @Volatile
    private var connected = false

    @Volatile
    private var shouldRun = false

    private var lastGga: String? = null

    private var totalRtcmBytes = 0L


    // ==================================================
    // START
    // ==================================================

    fun start() {

        if (job?.isActive == true) {

            Log.d(
                TAG,
                "NTRIP start() ignored - already running"
            )

            return
        }

        shouldRun = true

        Log.d(
            TAG,
            "NTRIP starting..."
        )

        job = scope.launch(Dispatchers.IO) {

            while (
                isActive &&
                shouldRun
            ) {

                try {

                    connect()

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "NTRIP connection error: ${e.message}",
                        e
                    )

                    onStatus(
                        "NTRIP error: ${e.message}"
                    )
                }

                if (
                    !shouldRun ||
                    !isActive
                ) {
                    break
                }

                Log.d(
                    TAG,
                    "NTRIP reconnecting in 5 seconds..."
                )

                onStatus(
                    "NTRIP disconnected - reconnecting..."
                )

                delay(
                    RECONNECT_DELAY_MS
                )
            }
        }
    }


    // ==================================================
    // CONNECT
    // ==================================================

    private fun connect() {

        disconnectSocket()

        connected = false

        totalRtcmBytes = 0L


        Log.d(
            TAG,
            "======================================"
        )

        Log.d(
            TAG,
            "Connecting to NTRIP caster"
        )

        Log.d(
            TAG,
            "Host: $host"
        )

        Log.d(
            TAG,
            "Port: $port"
        )

        Log.d(
            TAG,
            "Mountpoint: $mountPoint"
        )

        Log.d(
            TAG,
            "======================================"
        )


        onStatus(
            "Connecting to NTRIP caster..."
        )


        val newSocket =
            Socket()


        /*
         * TCP connection timeout.
         */

        newSocket.connect(
            InetSocketAddress(
                host,
                port
            ),
            CONNECT_TIMEOUT_MS
        )


        /*
         * IMPORTANT:
         *
         * Prevent input.read() from blocking forever.
         */

        newSocket.soTimeout =
            READ_TIMEOUT_MS


        socket =
            newSocket


        Log.d(
            TAG,
            "TCP connection established"
        )


        output =
            newSocket.getOutputStream()


        // ==================================================
        // AUTHENTICATION
        // ==================================================

        val credentials =
            Base64.encodeToString(

                "$username:$password"
                    .toByteArray(
                        StandardCharsets.US_ASCII
                    ),

                Base64.NO_WRAP
            )


        /*
         * NTRIP request.
         */

        val request =
            "GET /$mountPoint HTTP/1.1\r\n" +
                    "Host: $host:$port\r\n" +
                    "User-Agent: NTRIP GNSSBridge/1.0\r\n" +
                    "Ntrip-Version: Ntrip/2.0\r\n" +
                    "Accept: */*\r\n" +
                    "Authorization: Basic $credentials\r\n" +
                    "Connection: keep-alive\r\n" +
                    "\r\n"


        Log.d(
            TAG,
            "======================================"
        )

        Log.d(
            TAG,
            "SENDING NTRIP REQUEST:"
        )

        Log.d(
            TAG,
            request.replace(
                credentials,
                "***"
            )
        )

        Log.d(
            TAG,
            "======================================"
        )


        output?.write(
            request.toByteArray(
                StandardCharsets.US_ASCII
            )
        )


        output?.flush()


        Log.d(
            TAG,
            "NTRIP request sent"
        )


        // ==================================================
        // READ RESPONSE HEADER
        // ==================================================

        val input =
            BufferedInputStream(
                newSocket.getInputStream()
            )


        val headerBuffer =
            StringBuilder()


        try {

            while (true) {

                val b =
                    input.read()


                if (
                    b == -1
                ) {

                    throw Exception(
                        "Caster closed connection before sending response"
                    )
                }


                headerBuffer.append(
                    b.toChar()
                )


                /*
                 * Prevent an endlessly growing header.
                 */

                if (
                    headerBuffer.length > 16384
                ) {

                    throw Exception(
                        "NTRIP response header too large"
                    )
                }


                if (
                    headerBuffer
                        .toString()
                        .contains(
                            "\r\n\r\n"
                        )
                ) {

                    break
                }
            }

        } catch (
            e: java.net.SocketTimeoutException
        ) {

            Log.e(
                TAG,
                "TIMEOUT waiting for NTRIP caster response"
            )

            throw Exception(
                "Timeout waiting for caster response"
            )
        }


        val response =
            headerBuffer.toString()


        val firstLine =
            response
                .substringBefore(
                    "\r\n"
                )
                .trim()


        Log.d(
            TAG,
            "======================================"
        )

        Log.d(
            TAG,
            "CASTER RESPONSE:"
        )

        Log.d(
            TAG,
            response
        )

        Log.d(
            TAG,
            "======================================")


        onStatus(
            "NTRIP: $firstLine"
        )


        // ==================================================
        // SOURCE TABLE
        // ==================================================

        if (
            firstLine.startsWith(
                "SOURCETABLE",
                ignoreCase = true
            )
        ) {

            Log.e(
                TAG,
                "Caster returned SOURCETABLE."
            )

            Log.e(
                TAG,
                "Mountpoint was NOT opened: $mountPoint"
            )

            throw Exception(
                "Caster returned source table"
            )
        }


        // ==================================================
        // CHECK RESPONSE
        // ==================================================

        val responseUpper =
            firstLine.uppercase()


        if (
            !responseUpper.contains(
                "200"
            )
        ) {

            Log.e(
                TAG,
                "Caster rejected request: $firstLine"
            )

            throw Exception(
                "Caster rejected connection: $firstLine"
            )
        }


        // ==================================================
        // CONNECTED
        // ==================================================

        connected = true


        Log.d(
            TAG,
            "======================================"
        )

        Log.d(
            TAG,
            "🟢 NTRIP MOUNTPOINT CONNECTED"
        )

        Log.d(
            TAG,
            "Mountpoint: $mountPoint"
        )

        Log.d(
            TAG,
            "Response: $firstLine"
        )

        Log.d(
            TAG,
            "======================================"
        )


        onStatus(
            "🟢 NTRIP Connected - $mountPoint"
        )


        // ==================================================
        // SEND CURRENT GGA
        // ==================================================

        lastGga?.let { gga ->

            Log.d(
                TAG,
                "Sending latest GGA to caster"
            )

            sendGgaInternal(
                gga
            )
        }


        // ==================================================
        // READ RTCM STREAM
        // ==================================================

        val buffer =
            ByteArray(8192)


        while (
            connected &&
            shouldRun
        ) {

            val count: Int

            try {

                count =
                    input.read(
                        buffer
                    )

            } catch (
                e: java.net.SocketTimeoutException
            ) {

                /*
                 * A timeout after a successful connection
                 * means the caster stopped sending data.
                 */

                Log.e(
                    TAG,
                    "RTCM stream timeout - no data received"
                )

                throw Exception(
                    "RTCM stream timeout"
                )
            }


            if (
                count <= 0
            ) {

                Log.d(
                    TAG,
                    "RTCM stream ended"
                )

                break
            }


            val rtcmData =
                buffer.copyOf(
                    count
                )


            totalRtcmBytes +=
                count.toLong()


            Log.d(
                TAG,
                "RTCM RECEIVED: " +
                        "$count bytes " +
                        "(total=$totalRtcmBytes)"
            )


            // ==================================================
            // FORWARD RTCM TO GNSS
            // ==================================================

            try {

                onRtcmData(
                    rtcmData
                )


                Log.d(
                    TAG,
                    "RTCM FORWARDED TO GNSS: " +
                            "$count bytes"
                )

            } catch (
                e: Exception
            ) {

                Log.e(
                    TAG,
                    "Failed to forward RTCM to GNSS",
                    e
                )
            }
        }


        connected = false

        disconnectSocket()
    }


    // ==================================================
    // SEND GGA
    // ==================================================

    fun sendGga(
        gga: String
    ) {

        lastGga =
            gga.trim()


        Log.d(
            TAG,
            "GGA received for NTRIP"
        )


        if (
            !connected
        ) {

            Log.d(
                TAG,
                "NTRIP not connected - GGA saved"
            )

            return
        }


        scope.launch(
            Dispatchers.IO
        ) {

            sendGgaInternal(
                gga
            )
        }
    }


    private fun sendGgaInternal(
        gga: String
    ) {

        try {

            output?.write(

                (
                        gga.trim() +
                                "\r\n"
                        ).toByteArray(
                        StandardCharsets.US_ASCII
                    )

            )


            output?.flush()


            Log.d(
                TAG,
                "GGA SENT TO CASTER"
            )

        } catch (
            e: Exception
        ) {

            Log.e(
                TAG,
                "Failed to send GGA",
                e
            )
        }
    }


    // ==================================================
    // STOP
    // ==================================================

    fun stop() {

        Log.d(
            TAG,
            "NTRIP stop() called"
        )


        shouldRun =
            false

        connected =
            false


        job?.cancel()

        job =
            null


        disconnectSocket()


        Log.d(
            TAG,
            "NTRIP stopped"
        )
    }


    // ==================================================
    // CLOSE SOCKET
    // ==================================================

    private fun disconnectSocket() {

        try {
            output?.close()
        } catch (_: Exception) {
        }


        try {
            socket?.close()
        } catch (_: Exception) {
        }


        output =
            null

        socket =
            null
    }
}