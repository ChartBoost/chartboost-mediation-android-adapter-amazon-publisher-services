/*
 * Copyright 2024-2025 Chartboost, Inc.
 * 
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

package com.chartboost.mediation.amazonpublisherservicesadapter

import com.amazon.device.ads.AdRegistration
import com.amazon.device.ads.DTBLogLevel
import com.chartboost.chartboostmediationsdk.domain.PartnerAdapterConfiguration
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController

object AmazonPublisherServicesAdapterConfiguration : PartnerAdapterConfiguration {
    /**
     * The partner ID for internal uses.
     */
    override val partnerId = "amazon_aps"

    /**
     * The partner name for external uses.
     */
    override val partnerDisplayName = "Amazon Publisher Services"

    /**
     * The partner SDK version.
     */
    override val partnerSdkVersion: String = AdRegistration.getVersion()

    /**
     * The partner adapter version.
     *
     * You may version the adapter using any preferred convention, but it is recommended to apply the
     * following format if the adapter will be published by Chartboost Mediation:
     *
     * Chartboost Mediation.Partner.Adapter
     *
     * "Chartboost Mediation" represents the Chartboost Mediation SDK’s major version that is compatible with this adapter. This must be 1 digit.
     * "Partner" represents the partner SDK’s major.minor.patch.x (where x is optional) version that is compatible with this adapter. This can be 3-4 digits.
     * "Adapter" represents this adapter’s version (starting with 0), which resets to 0 when the partner SDK’s version changes. This must be 1 digit.
     */
    override val adapterVersion = BuildConfig.CHARTBOOST_MEDIATION_APS_ADAPTER_VERSION

    /**
     * Test mode flag that can optionally be set to true to enable test ads. It can be set at any
     * time and will take effect for the next ad request. Remember to set this to false in
     * production.
     */
    var testMode = false
        set(value) {
            field = value
            AdRegistration.enableTesting(value)
            PartnerLogController.log(
                PartnerLogController.PartnerAdapterEvents.CUSTOM,
                "- Amazon Publisher Services test mode is ${
                    if (value) {
                        "enabled. Remember to disable it before publishing."
                    } else {
                        "disabled."
                    }
                }",
            )
        }

    /**
     * Verbose logging flag that can optionally be set to true to enable verbose logging.
     */
    var verboseLoggingEnabled = false
        set(value) {
            field = value
            PartnerLogController.log(
                PartnerLogController.PartnerAdapterEvents.CUSTOM,
                "- Amazon Publisher Services verbose logging is ${
                    if (value) {
                        AdRegistration.enableLogging(true, DTBLogLevel.All)
                        "enabled."
                    } else {
                        AdRegistration.enableLogging(true, DTBLogLevel.Off)
                        "disabled."
                    }
                }",
            )
        }
}
