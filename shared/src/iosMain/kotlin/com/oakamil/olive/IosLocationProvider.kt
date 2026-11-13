// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.Foundation.NSError
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
class IosLocationProvider(
    private val onLocationUpdated: (Double, Double) -> Unit,
    private val onError: (String) -> Unit,
    private val onPermissionDenied: () -> Unit = {}
) : NSObject(), CLLocationManagerDelegateProtocol {

    private val locationManager = CLLocationManager()

    init {
        locationManager.delegate = this
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
    }

    fun requestLocation() {
        val status = locationManager.authorizationStatus
        when (status) {
            kCLAuthorizationStatusNotDetermined -> {
                locationManager.requestWhenInUseAuthorization()
            }

            kCLAuthorizationStatusAuthorizedWhenInUse,
            kCLAuthorizationStatusAuthorizedAlways -> {
                locationManager.requestLocation()
            }

            kCLAuthorizationStatusDenied,
            kCLAuthorizationStatusRestricted -> {
                onPermissionDenied()
                onError("Location permission denied")
            }

            else -> {
                locationManager.requestLocation()
            }
        }
    }

    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
        val status = manager.authorizationStatus
        when (status) {
            kCLAuthorizationStatusAuthorizedWhenInUse,
            kCLAuthorizationStatusAuthorizedAlways -> {
                manager.requestLocation()
            }

            kCLAuthorizationStatusDenied,
            kCLAuthorizationStatusRestricted -> {
                onPermissionDenied()
                onError("Location permission denied")
            }

            else -> Unit
        }
    }

    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
        val location = didUpdateLocations.lastOrNull() as? CLLocation ?: return
        val coordinate = location.coordinate
        val (lat, lon) = coordinate.useContents { latitude to longitude }
        onLocationUpdated(lat, lon)
    }

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
        onError(didFailWithError.localizedDescription)
    }
}
