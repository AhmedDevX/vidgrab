package com.vidgrab.app

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.ConnectionsClient

/**
 * Shared entry point for the nearby (Bluetooth + Wi-Fi Direct) transport.
 *
 * The transport itself is Google Play services' Nearby Connections API: it
 * finds the other phone over Bluetooth/BLE, then moves the bytes over
 * Wi-Fi Direct or a private hotspot, fully offline. Everything is free and
 * there is no server. The service ID is namespaced to this app, so only
 * VidGrab instances see each other.
 */
object NearbyLink {

    /** Namespaced service id; must match on both devices. */
    const val SERVICE_ID = "com.vidgrab.app.nearby.v1"

    fun client(context: Context): ConnectionsClient = Nearby.getConnectionsClient(context)

    /**
     * False on phones without Google Play services (the API refuses to run
     * there); the same-Wi-Fi path stays available in that case.
     */
    fun available(context: Context): Boolean = try {
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    } catch (e: Exception) {
        false
    }

    /** "1234" -> "1 2 3 4" for on-screen comparison. */
    fun spaced(digits: String): String = digits.toCharArray().joinToString(" ")
}
