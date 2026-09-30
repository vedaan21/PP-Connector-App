package com.example.gnssbridge

data class GnssData(

    // ==================================================
    // Position
    // ==================================================

    val latitude: Double?,

    val longitude: Double?,

    val altitude: Double?,


    // ==================================================
    // Accuracy
    // ==================================================

    // Horizontal / 2D accuracy from PQTMEPE
    val accuracy: Double?,


    // ==================================================
    // PQTMEPE errors
    // ==================================================

    val northError: Double?,

    val eastError: Double?,

    val verticalError: Double?,


    // ==================================================
    // GNSS information
    // ==================================================

    val satellites: Int?,

    val hdop: Double?,


    // ==================================================
    // GGA positioning solution
    //
    // Examples:
    // Single
    // DGPS
    // RTK Float
    // RTK Fixed
    // Dead Reckoning
    // ==================================================

    val fixType: String?,


    // ==================================================
    // DRPVA position source
    //
    // Examples:
    // GNSS
    // GNSS + DR
    // DR
    // ==================================================

    val positionSource: String?,


    // ==================================================
    // DRPVA velocity
    // ==================================================

    val speed: Double?,

    val course: Double?,


    // ==================================================
    // Connection
    // ==================================================

    val connected: Boolean,

    val status: String,

    val timestamp: String
)