package com.example.gnssbridge

object NmeaParser {

    // ==================================================
    // Latest PQTMEPE values
    // ==================================================

    private var latestNorthError: Double? = null

    private var latestEastError: Double? = null

    private var latestVerticalError: Double? = null

    private var latestAccuracy: Double? = null


    // ==================================================
    // Latest DRPVA values
    // ==================================================

    private var latestPositionSource: String? = null

    private var latestDrSpeed: Double? = null

    private var latestDrCourse: Double? = null


    // ==================================================
    // PARSE
    // ==================================================

    fun parse(
        line: String
    ): GnssData? {

        val cleanLine =
            line.trim()


        // ==================================================
        // PQTMEPE
        // ==================================================

        if (
            cleanLine.startsWith(
                "\$PQTMEPE"
            )
        ) {

            parsePqtmEpe(
                cleanLine
            )

            return null
        }


        // ==================================================
        // PQTMDRPVA
        // ==================================================

        if (
            cleanLine.startsWith(
                "\$PQTMDRPVA"
            )
        ) {

            parsePqtmDrpva(
                cleanLine
            )

            return null
        }


        // ==================================================
        // GGA
        // ==================================================

        if (
            cleanLine.contains("GGA")
        ) {

            return parseGga(
                cleanLine
            )
        }


        return null
    }


    // ======================================================
    // PQTMEPE
    // ======================================================

    private fun parsePqtmEpe(
        line: String
    ) {

        val fields =
            line.substringBefore("*")
                .split(",")


        if (
            fields.size < 7
        ) {

            return
        }


        // ----------------------------------------------
        // North error
        // ----------------------------------------------

        latestNorthError =
            fields[2]
                .toDoubleOrNull()


        // ----------------------------------------------
        // East error
        // ----------------------------------------------

        latestEastError =
            fields[3]
                .toDoubleOrNull()


        // ----------------------------------------------
        // Vertical error
        // ----------------------------------------------

        latestVerticalError =
            fields[4]
                .toDoubleOrNull()


        // ----------------------------------------------
        // Horizontal / 2D error
        // ----------------------------------------------

        latestAccuracy =
            fields[5]
                .toDoubleOrNull()
    }


    // ======================================================
    // PQTMDRPVA
    // ======================================================
    //
    // Example:
    //
    // $PQTMDRPVA,1,437416,101554.000,1,
    // 28.49075434,77.07955216,270.933,
    // -36.367,0.100,0.113,0.000,0.151,,,43.267490*47
    //
    // Important:
    //
    // This sentence contains DR/PVA information.
    //
    // We DO NOT use its solution/source field
    // as the GGA fix type.
    //
    // ======================================================

    private fun parsePqtmDrpva(
        line: String
    ) {

        val fields =
            line.substringBefore("*")
                .split(",")


        if (
            fields.size < 10
        ) {

            return
        }


        /*
         * PQTMDRPVA field 4 is the
         * navigation solution/source status.
         *
         * From the observed receiver output:
         *
         * 1 = GNSS solution.
         *
         * We keep this separate from GGA fix type.
         */

        val solutionStatus =
            fields[4]
                .toIntOrNull()


        latestPositionSource =
            when (solutionStatus) {

                0 ->
                    "No Fix"

                1 ->
                    "GNSS"

                2 ->
                    "GNSS + DR"

                3 ->
                    "DR"

                else ->
                    "Unknown"
            }


        /*
         * Based on the supplied DRPVA examples:
         *
         * field 9 = velocity / speed-related value
         * field 10 = velocity / speed-related value
         *
         * Do not use these for the RTK fix status.
         *
         * We keep them optional for the DR display.
         */

        latestDrSpeed =
            fields
                .getOrNull(9)
                ?.toDoubleOrNull()


        latestDrCourse =
            fields
                .getOrNull(10)
                ?.toDoubleOrNull()
    }


    // ======================================================
    // GGA
    // ======================================================

    private fun parseGga(
        line: String
    ): GnssData? {

        val fields =
            line.substringBefore("*")
                .split(",")


        if (
            fields.size < 10
        ) {

            return null
        }


        try {

            // ----------------------------------------------
            // Latitude
            // ----------------------------------------------

            val latitude =
                parseCoordinate(
                    fields[2],
                    fields[3]
                )


            // ----------------------------------------------
            // Longitude
            // ----------------------------------------------

            val longitude =
                parseCoordinate(
                    fields[4],
                    fields[5]
                )


            // ----------------------------------------------
            // Fix quality
            // ----------------------------------------------

            val quality =
                fields[6]
                    .toIntOrNull()
                    ?: 0


            // ----------------------------------------------
            // Satellites
            // ----------------------------------------------

            val satellites =
                fields[7]
                    .toIntOrNull()


            // ----------------------------------------------
            // HDOP
            // ----------------------------------------------

            val hdop =
                fields[8]
                    .toDoubleOrNull()


            // ----------------------------------------------
            // Altitude
            // ----------------------------------------------

            val altitude =
                fields[9]
                    .toDoubleOrNull()


            // ----------------------------------------------
            // FIX TYPE
            //
            // IMPORTANT:
            //
            // This comes ONLY from GGA quality.
            // ----------------------------------------------

            val fixType =
                when (quality) {

                    0 ->
                        "Invalid"

                    1 ->
                        "Single"

                    2 ->
                        "DGPS"

                    4 ->
                        "RTK Fixed"

                    5 ->
                        "RTK Float"

                    6 ->
                        "Dead Reckoning"

                    else ->
                        "Unknown"
                }


            // ----------------------------------------------
            // Create GNSS data
            // ----------------------------------------------

            return GnssData(

                latitude =
                    latitude,

                longitude =
                    longitude,

                altitude =
                    altitude,


                // PQTMEPE
                accuracy =
                    latestAccuracy,


                northError =
                    latestNorthError,

                eastError =
                    latestEastError,

                verticalError =
                    latestVerticalError,


                satellites =
                    satellites,

                hdop =
                    hdop,


                // GGA fix type
                fixType =
                    fixType,


                // DRPVA source
                positionSource =
                    latestPositionSource,


                speed =
                    latestDrSpeed,

                course =
                    latestDrCourse,


                connected =
                    true,

                status =
                    "connected",

                timestamp =
                    java.time.Instant
                        .now()
                        .toString()
            )

        } catch (
            _: Exception
        ) {

            return null
        }
    }


    // ======================================================
    // COORDINATE PARSER
    // ======================================================

    private fun parseCoordinate(
        value: String,
        direction: String
    ): Double? {

        if (
            value.isBlank() ||
            direction.isBlank()
        ) {

            return null
        }


        val degreeLength =
            if (
                direction == "N" ||
                direction == "S"
            ) {

                2

            } else {

                3
            }


        if (
            value.length <=
            degreeLength
        ) {

            return null
        }


        val degrees =
            value
                .substring(
                    0,
                    degreeLength
                )
                .toDouble()


        val minutes =
            value
                .substring(
                    degreeLength
                )
                .toDouble()


        var coordinate =
            degrees +
                    minutes / 60.0


        if (
            direction == "S" ||
            direction == "W"
        ) {

            coordinate =
                -coordinate
        }


        return coordinate
    }
}