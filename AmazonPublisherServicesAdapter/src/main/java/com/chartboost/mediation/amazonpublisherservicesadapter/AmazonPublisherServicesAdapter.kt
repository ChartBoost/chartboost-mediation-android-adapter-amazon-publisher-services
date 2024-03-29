/*
 * Copyright 2023-2024 Chartboost, Inc.
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

package com.chartboost.mediation.amazonpublisherservicesadapter

import android.content.Context
import android.view.View
import com.amazon.device.ads.*
import com.chartboost.heliumsdk.domain.*
import com.chartboost.heliumsdk.utils.PartnerLogController
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_FAILED
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_STARTED
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_SUCCEEDED
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.CCPA_CONSENT_DENIED
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.CCPA_CONSENT_GRANTED
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.COPPA_NOT_SUBJECT
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.COPPA_SUBJECT
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.CUSTOM
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_CLICK
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_DISMISS
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_REWARD
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_TRACK_IMPRESSION
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.GDPR_APPLICABLE
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.GDPR_CONSENT_DENIED
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.GDPR_CONSENT_GRANTED
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.GDPR_CONSENT_UNKNOWN
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.GDPR_NOT_APPLICABLE
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.GDPR_UNKNOWN
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_FAILED
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_STARTED
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_SUCCEEDED
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_FAILED
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_STARTED
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_SUCCEEDED
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_FAILED
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_STARTED
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_SUCCEEDED
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_FAILED
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_STARTED
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_SUCCEEDED
import com.chartboost.mediation.amazonpublisherservicesadapter.AmazonPublisherServicesAdapter.Companion.onShowSuccess
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.serialization.json.*
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * The Chartboost Mediation Amazon Publisher Services (APS) adapter.
 */
class AmazonPublisherServicesAdapter : PartnerAdapter {
    interface PreBiddingListener {
        /**
         * Called when Chartboost Mediation is requesting a prebid from Amazon.
         *
         * @param context The Android context.
         * @param request The pre bidding request details.
         * @return The Amazon pre bid response.
         */
        suspend fun onPreBid(
            context: Context,
            request: AmazonPublisherServicesAdapterPreBidRequest,
        ): Result<AmazonPublisherServicesAdapterPreBidAdInfo>
    }

    /**
     * The info necessary to complete a pre bid request. The convenience constructor taking a
     * [DTBAdResponse] is also available.
     */
    data class AmazonPublisherServicesAdapterPreBidAdInfo(
        /**
         * The price point from a [DTBAdResponse]. See SDKUtilities#getPricePoint(DTBAdResponse).
         */
        val pricePoint: String?,
        /**
         * The bid info from a [DTBAdResponse]. See SDKUtilities#getBidInfo(DTBAdresponse).
         */
        val bidInfo: String?,
    ) {
        constructor(dtbAdResponse: DTBAdResponse) : this(
            SDKUtilities.getPricePoint(dtbAdResponse),
            SDKUtilities.getBidInfo(dtbAdResponse),
        )
    }

    /**
     * Data class to store all the Amazon pre bid settings from the configuration.
     */
    data class AmazonSettings(
        /**
         * The Amazon placement name.
         */
        val partnerPlacement: String,
        /**
         * The width of the expected ad if it's a banner.
         */
        val width: Int,
        /**
         * The height of the expected ad if it's a banner.
         */
        val height: Int,
        /**
         * Whether or not to serve video in this placement.
         */
        val isVideo: Boolean,
    )

    /**
     * Data class to send publishers the pre bid request contents plus the Amazon APS specific
     * pre bid settings.
     */
    data class AmazonPublisherServicesAdapterPreBidRequest(
        /**
         * The Chartboost placement name.
         */
        val chartboostPlacement: String,
        /**
         * The ad format of this pre bid request.
         */
        val format: AdFormat,
        /**
         * Amazon adapter specific settings to do a pre bid request.
         */
        val amazonSettings: AmazonSettings,
        /**
         * The CCPA US Privacy String. This is only used by the internal listener.
         */
        internal val ccpaPrivacyString: String?,
    )

    companion object {
        /**
         * Test mode flag that can optionally be set to true to enable test ads. It can be set at any
         * time and will take effect for the next ad request. Remember to set this to false in
         * production.
         */
        public var testMode = false
            set(value) {
                field = value
                AdRegistration.enableTesting(value)
                PartnerLogController.log(
                    CUSTOM,
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
         * A lambda to call for successful APS ad shows.
         */
        internal var onShowSuccess: () -> Unit = {}

        /*
         * Set this listener to handle prebid requests.
         * Chartboost is not permitted to wrap the Amazon APS initialization or bid request methods
         * directly. The adapter handles APS initialization and wrapped prebidding only when the
         * managed prebidding flag is enabled.
         *
         * For more information please contact the Amazon APS support team at https://aps.amazon.com/aps/contact-us/
         */
        var preBiddingListener: PreBiddingListener? = null

        /**
         * Key for setting the CCPA privacy.
         */
        private const val CCPA_PRIVACY_KEY = "us_privacy"

        /**
         * Key for parsing the APS SDK application ID.
         */
        private const val APS_APPLICATION_ID_KEY = "application_id"

        /**
         * Key for the pre bids array.
         */
        private const val PREBIDS_KEY = "prebids"

        /**
         * Key for the Chartboost placement in a pre bid.
         */
        private const val CHARTBOOST_PLACEMENT_KEY = "helium_placement"

        /**
         * Key for the partner placement in a pre bid.
         */
        private const val PARTNER_PLACEMENT_KEY = "partner_placement"

        /**
         * Key for the width in a pre bid.
         * This is optional and will default to 0 if it doesn't exist.
         */
        private const val WIDTH_KEY = "width"

        /**
         * Key for the height in a pre bid.
         * This is optional and will default to 0 if it doesn't exist.
         */
        private const val HEIGHT_KEY = "height"

        /**
         * Key for whether or not video is acceptable in a pre bid.
         * This is optional and will default to false if it doesn't exist.
         */
        private const val IS_VIDEO_KEY = "video"

        /**
         * Key for using Chartboost-managed prebidding.
         */
        private const val MANAGED_PREBIDDING_KEY = "managed_prebidding"
    }

    /**
     * String Chartboost placement name to the APS pre-bid ad info.
     */
    private val placementToPreBidAdInfoMap: MutableMap<String, AmazonPublisherServicesAdapterPreBidAdInfo?> =
        mutableMapOf()

    /**
     * Stores the pre bid settings so we can make a pre bid once the previous one has been consumed.
     * Only access this from the main thread.
     */
    private val placementToAmazonSettings: MutableMap<String, AmazonSettings> = mutableMapOf()

    /**
     * Indicate whether the user has given CCPA consent.
     */
    private var ccpaPrivacyString: String? = null

    /**
     * Indicate whether COPPA currently applies to the user.
     */
    private var isSubjectToCoppa = false

    /**
     * Get the APS SDK version.
     */
    override val partnerSdkVersion: String
        get() = AdRegistration.getVersion()

    /**
     * Get the APS adapter version.
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
    override val adapterVersion: String
        get() = BuildConfig.CHARTBOOST_MEDIATION_APS_ADAPTER_VERSION

    /**
     * Get the partner name for internal uses.
     */
    override val partnerId: String
        get() = "amazon_aps"

    /**
     * Get the partner name for external uses.
     */
    override val partnerDisplayName: String
        get() = "Amazon Publisher Services"

    /**
     * Initialize the Amazon Publisher Services SDK so that it is ready to request ads.
     *
     * @param context The current [Context].
     * @param partnerConfiguration Configuration object containing relevant data to initialize APS.
     *
     * @return Result.success(Unit) if APS successfully initialized, Result.failure(Exception) otherwise.
     */
    override suspend fun setUp(
        context: Context,
        partnerConfiguration: PartnerConfiguration,
    ): Result<Unit> {
        PartnerLogController.log(SETUP_STARTED)
        return Json.decodeFromJsonElement<String>(
            (partnerConfiguration.credentials as JsonObject).getValue(APS_APPLICATION_ID_KEY),
        )
            .trim()
            .takeIf { it.isNotEmpty() }?.let { appKey ->
                val shouldUseManagedPrebidding =
                    (partnerConfiguration.credentials as JsonObject).get(MANAGED_PREBIDDING_KEY)?.jsonPrimitive?.booleanOrNull
                        ?: false

                if (shouldUseManagedPrebidding && preBiddingListener == null) {
                    PartnerLogController.log(CUSTOM, "Using managed pre bidding.")
                    AdRegistration.getInstance(appKey, context)

                    AdRegistration.setAdNetworkInfo(DTBAdNetworkInfo(DTBAdNetwork.OTHER))
                    AdRegistration.setMRAIDSupportedVersions(arrayOf("1.0", "2.0", "3.0"))
                    AdRegistration.setMRAIDPolicy(MRAIDPolicy.CUSTOM)

                    preBiddingListener = DefaultPreBiddingListener()

                    // TODO: Remove once pipes have proven to function.
                    AdRegistration.enableLogging(true, DTBLogLevel.All)
                } else {
                    PartnerLogController.log(CUSTOM, "Not using managed pre bidding.")
                }

                val preBidArray =
                    Json.decodeFromJsonElement<JsonArray>(
                        (partnerConfiguration.credentials as JsonObject).getValue(PREBIDS_KEY),
                    )
                preBidArray.forEach {
                    withContext(Main) {
                        addPrebid(Json.decodeFromJsonElement(it))
                    }
                }

                Result.success(PartnerLogController.log(SETUP_SUCCEEDED))
            } ?: run {
            PartnerLogController.log(SETUP_FAILED, "Missing application ID.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INITIALIZATION_FAILURE_INVALID_CREDENTIALS))
        }
    }

    private fun addPrebid(preBid: JsonObject?) {
        preBid?.apply {
            val chartboostPlacement =
                get(CHARTBOOST_PLACEMENT_KEY)?.let {
                    Json.decodeFromJsonElement(it)
                } ?: ""
            val partnerPlacement =
                get(PARTNER_PLACEMENT_KEY)?.let {
                    Json.decodeFromJsonElement(it)
                } ?: ""

            val width =
                get(WIDTH_KEY)?.let {
                    Json.decodeFromJsonElement(it)
                } ?: 0

            val height =
                get(HEIGHT_KEY)?.let {
                    Json.decodeFromJsonElement(it)
                } ?: 0

            val isVideo =
                get(IS_VIDEO_KEY)?.let {
                    Json.decodeFromJsonElement(it)
                } ?: false

            placementToAmazonSettings[chartboostPlacement] =
                AmazonSettings(
                    partnerPlacement = partnerPlacement,
                    width = width,
                    height = height,
                    isVideo = isVideo,
                )
        }
    }

    /**
     * Notify the Amazon Publisher Services SDK of the GDPR applicability and consent status.
     *
     * @param context The current [Context].
     * @param applies Whether GDPR applies or not.
     * @param gdprConsentStatus The user's GDPR consent status.
     */
    override fun setGdpr(
        context: Context,
        applies: Boolean?,
        gdprConsentStatus: GdprConsentStatus,
    ) {
        PartnerLogController.log(
            when (applies) {
                true -> GDPR_APPLICABLE
                false -> GDPR_NOT_APPLICABLE
                else -> GDPR_UNKNOWN
            },
        )

        PartnerLogController.log(
            when (gdprConsentStatus) {
                GdprConsentStatus.GDPR_CONSENT_UNKNOWN -> GDPR_CONSENT_UNKNOWN
                GdprConsentStatus.GDPR_CONSENT_GRANTED -> GDPR_CONSENT_GRANTED
                GdprConsentStatus.GDPR_CONSENT_DENIED -> GDPR_CONSENT_DENIED
            },
        )

        AdRegistration.setCMPFlavor(AdRegistration.CMPFlavor.CMP_NOT_DEFINED)

        if (applies == true) {
            AdRegistration.setConsentStatus(
                when (gdprConsentStatus) {
                    GdprConsentStatus.GDPR_CONSENT_UNKNOWN -> AdRegistration.ConsentStatus.UNKNOWN
                    GdprConsentStatus.GDPR_CONSENT_GRANTED -> AdRegistration.ConsentStatus.EXPLICIT_YES
                    GdprConsentStatus.GDPR_CONSENT_DENIED -> AdRegistration.ConsentStatus.EXPLICIT_NO
                },
            )
        } else {
            AdRegistration.setConsentStatus(AdRegistration.ConsentStatus.CONSENT_NOT_DEFINED)
        }
    }

    /**
     * Notify APS of the CCPA compliance.
     *
     * @param context The current [Context]
     * @param hasGrantedCcpaConsent The user's current CCPA consent status.
     * @param privacyString The CCPA privacy string.
     */
    override fun setCcpaConsent(
        context: Context,
        hasGrantedCcpaConsent: Boolean,
        privacyString: String,
    ) {
        PartnerLogController.log(
            if (hasGrantedCcpaConsent) {
                CCPA_CONSENT_GRANTED
            } else {
                CCPA_CONSENT_DENIED
            },
        )

        ccpaPrivacyString = privacyString
    }

    /**
     * Notify APS of the COPPA subjectivity.
     *
     * @param context The current [Context]
     * @param isSubjectToCoppa Whether the user is subject to COPPA.
     */
    override fun setUserSubjectToCoppa(
        context: Context,
        isSubjectToCoppa: Boolean,
    ) {
        PartnerLogController.log(
            if (isSubjectToCoppa) {
                COPPA_SUBJECT
            } else {
                COPPA_NOT_SUBJECT
            },
        )

        this.isSubjectToCoppa = isSubjectToCoppa
    }

    /**
     * Get a bid token if network bidding is supported.
     *
     * @param context The current [Context].
     * @param request The [PreBidRequest] instance containing relevant data for the current bid request.
     *
     * @return A Map of biddable token Strings.
     */
    override suspend fun fetchBidderInformation(
        context: Context,
        request: PreBidRequest,
    ): Map<String, String> {
        PartnerLogController.log(BIDDER_INFO_FETCH_STARTED)

        val placement = request.chartboostPlacement
        withContext(Main) {
            placementToPreBidAdInfoMap[placement]
        }?.let { adInfo ->
            adInfo.pricePoint?.let {
                if (it.isNotEmpty()) {
                    return mutableMapOf(placement to it)
                }
            }
        }

        val amazonSettings =
            withContext(Main) {
                placementToAmazonSettings[placement]
            } ?: run {
                PartnerLogController.log(
                    BIDDER_INFO_FETCH_FAILED,
                    "Could not find amazonSettings for this placement.",
                )
                return mapOf()
            }

        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Map<String, String>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            if (amazonSettings.partnerPlacement.isEmpty()) {
                resumeOnce(mapOf())
                return@suspendCancellableCoroutine
            }

            if (isSubjectToCoppa) {
                resumeOnce(mapOf())
                return@suspendCancellableCoroutine
            }

            CoroutineScope(Main.immediate).launch {
                preBiddingListener?.onPreBid(
                    context,
                    AmazonPublisherServicesAdapterPreBidRequest(
                        chartboostPlacement = request.chartboostPlacement,
                        format = request.format,
                        amazonSettings = amazonSettings,
                        ccpaPrivacyString = ccpaPrivacyString,
                    ),
                )?.fold({ adInfo ->
                    placementToPreBidAdInfoMap[placement] = adInfo

                    adInfo.pricePoint?.let { pricePoint ->
                        PartnerLogController.log(BIDDER_INFO_FETCH_SUCCEEDED)
                        resumeOnce(mutableMapOf(placement to pricePoint))
                    } ?: run {
                        PartnerLogController.log(BIDDER_INFO_FETCH_FAILED, "Placement: $placement.")
                        resumeOnce(mapOf())
                    }
                }, {
                    placementToPreBidAdInfoMap.remove(placement)
                    resumeOnce(mapOf())
                }) ?: run {
                    PartnerLogController.log(BIDDER_INFO_FETCH_FAILED, "Placement: $placement.")
                    resumeOnce(mapOf())
                }
            }
        }
    }

    private class DefaultPreBiddingListener : PreBiddingListener {
        override suspend fun onPreBid(
            context: Context,
            request: AmazonPublisherServicesAdapterPreBidRequest,
        ): Result<AmazonPublisherServicesAdapterPreBidAdInfo> {
            val adRequest = DTBAdRequest()
            val isVideo = request.amazonSettings.isVideo

            buildAdRequestSize(request.format, adRequest, isVideo, request.amazonSettings)
            buildCcpaPrivacy(adRequest, request.ccpaPrivacyString)

            return suspendCancellableCoroutine { continuation ->
                fun resumeOnce(result: Result<AmazonPublisherServicesAdapterPreBidAdInfo>) {
                    if (continuation.isActive) {
                        continuation.resume(result)
                    }
                }
                adRequest.loadAd(
                    object : DTBAdCallback {
                        override fun onFailure(adError: AdError) {
                            PartnerLogController.log(
                                BIDDER_INFO_FETCH_FAILED,
                                "Placement: ${request.chartboostPlacement}. Error: ${adError.code}. Message: ${adError.message}",
                            )

                            resumeOnce(Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_PREBID_FAILURE_EXCEPTION)))
                        }

                        override fun onSuccess(adResponse: DTBAdResponse) {
                            resumeOnce(Result.success(AmazonPublisherServicesAdapterPreBidAdInfo(adResponse)))
                        }
                    },
                )
            }
        }

        /**
         * Builds a [DTBAdSize] object based on the ad format, whether it is a [DTBAdSize.DTBVideo] and
         * passes it to the [DTBAdRequest] ad request.
         *
         * @param format The current [AdFormat].
         * @param adRequest The current [DTBAdRequest].
         * @param isVideo Whether the current ad request is a video or not.
         * @param amazonSettings Relevant data for the current bid request.
         */
        private fun buildAdRequestSize(
            format: AdFormat,
            adRequest: DTBAdRequest,
            isVideo: Boolean,
            amazonSettings: AmazonSettings,
        ) {
            return when (format) {
                AdFormat.INTERSTITIAL, AdFormat.REWARDED -> {
                    adRequest.setSizes(
                        if (isVideo) {
                            (
                                DTBAdSize.DTBVideo(
                                    amazonSettings.width,
                                    amazonSettings.height,
                                    amazonSettings.partnerPlacement,
                                )
                            )
                        } else {
                            (DTBAdSize.DTBInterstitialAdSize(amazonSettings.partnerPlacement))
                        },
                    )
                }
                else -> {
                    adRequest.setSizes(
                        if (isVideo) {
                            (
                                DTBAdSize.DTBVideo(
                                    amazonSettings.width,
                                    amazonSettings.height,
                                    amazonSettings.partnerPlacement,
                                )
                            )
                        } else {
                            (
                                DTBAdSize(
                                    amazonSettings.width,
                                    amazonSettings.height,
                                    amazonSettings.partnerPlacement,
                                )
                            )
                        },
                    )
                }
            }
        }

        /**
         * Attaches the CCPA privacy setting to the APS request.
         *
         * @param adRequest A [DTBAdRequest] to set the privacy setting for the current DTBAdRequest.
         * @param ccpaPrivacyString the privacy string that will be set for the current DTBadRequest.
         */
        private fun buildCcpaPrivacy(
            adRequest: DTBAdRequest,
            ccpaPrivacyString: String?,
        ) {
            adRequest.apply {
                ccpaPrivacyString?.let {
                    putCustomTarget(CCPA_PRIVACY_KEY, it)
                }
            }
        }
    }

    /**
     * Attempt to load an APS ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    override suspend fun load(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener,
    ): Result<PartnerAd> {
        PartnerLogController.log(LOAD_STARTED)

        if (isSubjectToCoppa) {
            PartnerLogController.log(LOAD_FAILED, "User subject to COPPA.")
            return Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_PRIVACY_OPT_IN))
        }

        return when (request.format.key) {
            AdFormat.BANNER.key, "adaptive_banner" -> loadBannerAd(context, request, partnerAdListener)
            AdFormat.INTERSTITIAL.key, AdFormat.REWARDED.key -> loadFullScreenAd(context, request, partnerAdListener)
            else -> {
                PartnerLogController.log(LOAD_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_UNSUPPORTED_AD_FORMAT))
            }
        }
    }

    /**
     * Attempt to show the currently loaded APS ad.
     *
     * @param context The current [Context]
     * @param partnerAd The [PartnerAd] object containing the APS ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    override suspend fun show(
        context: Context,
        partnerAd: PartnerAd,
    ): Result<PartnerAd> {
        PartnerLogController.log(SHOW_STARTED)

        if (isSubjectToCoppa) {
            PartnerLogController.log(SHOW_FAILED, "User subject to COPPA")
            return Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_PRIVACY_OPT_IN))
        }

        return when (partnerAd.request.format.key) {
            AdFormat.BANNER.key, "adaptive_banner" -> {
                // Banner ads do not have a separate "show" mechanism.
                PartnerLogController.log(SHOW_SUCCEEDED)
                Result.success(partnerAd)
            }
            AdFormat.INTERSTITIAL.key, AdFormat.REWARDED.key -> showFullscreenAd(partnerAd)
            else -> {
                PartnerLogController.log(SHOW_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_UNSUPPORTED_AD_FORMAT))
            }
        }
    }

    /**
     * Discard unnecessary APS ad objects and release resources.
     *
     * @param partnerAd The [PartnerAd] object containing the APS ad to be discarded.
     *
     * @return Result.success(PartnerAd) if the ad was successfully discarded, Result.failure(Exception) otherwise.
     */
    override suspend fun invalidate(partnerAd: PartnerAd): Result<PartnerAd> {
        PartnerLogController.log(INVALIDATE_STARTED)

        return when (partnerAd.request.format.key) {
            AdFormat.BANNER.key, "adaptive_banner" -> destroyBannerAd(partnerAd)
            else -> {
                PartnerLogController.log(INVALIDATE_SUCCEEDED)
                Result.success(partnerAd)
            }
        }
    }

    /**
     * Attempt to load an APS banner ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadBannerAd(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener,
    ): Result<PartnerAd> {
        val placementName = request.chartboostPlacement
        val adResponse =
            withContext(Main) {
                placementToPreBidAdInfoMap.remove(placementName)
            } ?: run {
                PartnerLogController.log(LOAD_FAILED, "No ad response found.")
                return Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_NO_FILL))
            }
        val bidInfo =
            adResponse.bidInfo ?: run {
                PartnerLogController.log(LOAD_FAILED, "No bid in ad response")
                return Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_NO_FILL))
            }

        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<PartnerAd>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            DTBAdView(
                context,
                object : DTBAdBannerListener {
                    override fun onAdLoaded(adView: View?) {
                        PartnerLogController.log(LOAD_SUCCEEDED)
                        resumeOnce(
                            Result.success(
                                PartnerAd(
                                    ad = adView,
                                    details = emptyMap(),
                                    request = request,
                                ),
                            ),
                        )
                    }

                    override fun onAdFailed(adView: View?) {
                        PartnerLogController.log(LOAD_FAILED)
                        resumeOnce(
                            Result.failure(
                                ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_UNKNOWN),
                            ),
                        )
                    }

                    override fun onAdClicked(adView: View?) {
                        CoroutineScope(Main).launch {
                            PartnerLogController.log(DID_CLICK)
                            partnerAdListener.onPartnerAdClicked(
                                PartnerAd(
                                    ad = adView,
                                    details = emptyMap(),
                                    request = request,
                                ),
                            )
                        }
                    }

                    override fun onAdLeftApplication(adView: View?) {
                        // NO-OP
                    }

                    override fun onAdOpen(adView: View?) {
                        // NO-OP
                    }

                    override fun onAdClosed(adView: View?) {
                        CoroutineScope(Main).launch {
                            PartnerLogController.log(DID_DISMISS)
                            partnerAdListener.onPartnerAdDismissed(
                                PartnerAd(
                                    ad = adView,
                                    details = emptyMap(),
                                    request = request,
                                ),
                                null,
                            )
                        }
                    }

                    override fun onImpressionFired(adView: View?) {
                        CoroutineScope(Main).launch {
                            PartnerLogController.log(DID_TRACK_IMPRESSION)
                            partnerAdListener.onPartnerAdImpression(
                                PartnerAd(
                                    ad = adView,
                                    details = emptyMap(),
                                    request = request,
                                ),
                            )
                        }
                    }
                },
            ).fetchAd(bidInfo)
        }
    }

    /**
     * Attempt to load an APS fullscreen ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing data to load the ad with.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadFullScreenAd(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener,
    ): Result<PartnerAd> {
        val placementName = request.chartboostPlacement
        val adResponse =
            withContext(Main) {
                placementToPreBidAdInfoMap.remove(placementName)
            } ?: run {
                PartnerLogController.log(LOAD_FAILED, "No ad response found.")
                return Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_NO_FILL))
            }
        val bidInfo =
            adResponse.bidInfo ?: run {
                PartnerLogController.log(LOAD_FAILED, "No bid in ad response")
                return Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_NO_FILL))
            }

        return suspendCancellableCoroutine { continuation ->
            val listener = AdListener(WeakReference(continuation), request, partnerAdListener)
            val fullscreenAd = DTBAdInterstitial(context, listener)

            listener.setAd(fullscreenAd)
            fullscreenAd.fetchAd(bidInfo)
        }
    }

    /**
     * Attempt to show an APS fullscreen ad.
     *
     * @param partnerAd The [PartnerAd] object containing the APS ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private suspend fun showFullscreenAd(partnerAd: PartnerAd): Result<PartnerAd> {
        return (partnerAd.ad)?.let { ad ->
            (ad as? DTBAdInterstitial)?.let {
                suspendCancellableCoroutine { continuation ->
                    val continuationWeakRef = WeakReference(continuation)

                    onShowSuccess = {
                        PartnerLogController.log(SHOW_SUCCEEDED)
                        continuationWeakRef.get()?.let {
                            if (it.isActive) {
                                it.resume(Result.success(partnerAd))
                            }
                        } ?: run {
                            PartnerLogController.log(SHOW_FAILED, "Unable to resume continuation in onShowSuccess. Continuation is null.")
                        }
                    }
                    it.show()
                }
            } ?: run {
                PartnerLogController.log(SHOW_FAILED, "Ad is not DTBAdInterstitial.")
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_WRONG_RESOURCE_TYPE))
            }
        } ?: run {
            PartnerLogController.log(SHOW_FAILED, "Ad is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_AD_NOT_FOUND))
        }
    }

    /**
     * Destroy the current APS banner ad.
     *
     * @param partnerAd The [PartnerAd] object containing the APS ad to be destroyed.
     *
     * @return Result.success(PartnerAd) if the ad was successfully destroyed, Result.failure(Exception) otherwise.
     */
    private fun destroyBannerAd(partnerAd: PartnerAd): Result<PartnerAd> {
        return (partnerAd.ad as? DTBAdView)?.let { bannerAd ->
            bannerAd.destroy()

            PartnerLogController.log(INVALIDATE_SUCCEEDED)
            Result.success(partnerAd)
        } ?: run {
            PartnerLogController.log(INVALIDATE_FAILED, "Ad is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INVALIDATE_FAILURE_AD_NOT_FOUND))
        }
    }
}

/**
 * Ad listener implementation for APS fullscreen ads.
 *
 * @param continuationRef A weak reference to the continuation to resume once the ad has loaded.
 * @param request The [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
 * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
 */
private class AdListener(
    private val continuationRef: WeakReference<CancellableContinuation<Result<PartnerAd>>>,
    private val request: PartnerAdLoadRequest,
    private val listener: PartnerAdListener?,
) : DTBAdInterstitialListener {
    private var fullscreenAd: DTBAdInterstitial? = null

    fun setAd(ad: DTBAdInterstitial) {
        fullscreenAd = ad
    }

    override fun onAdLoaded(adView: View?) {
        PartnerLogController.log(LOAD_SUCCEEDED)
        continuationRef.get()?.let {
            if (it.isActive) {
                it.resume(
                    Result.success(
                        PartnerAd(
                            ad = fullscreenAd,
                            details = emptyMap(),
                            request = request,
                        ),
                    ),
                )
            }
        } ?: run {
            PartnerLogController.log(LOAD_FAILED, "Unable to resume continuation in onAdLoaded. Continuation is null.")
        }
    }

    override fun onAdFailed(adView: View?) {
        PartnerLogController.log(LOAD_FAILED)
        continuationRef.get()?.let {
            if (it.isActive) {
                it.resume(
                    Result.failure(
                        ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_UNKNOWN),
                    ),
                )
            }
        } ?: run {
            PartnerLogController.log(LOAD_FAILED, "Unable to resume continuation in onAdFailed. Continuation is null.")
        }
    }

    override fun onAdClicked(adView: View?) {
        CoroutineScope(Main).launch {
            PartnerLogController.log(DID_CLICK)
            listener?.onPartnerAdClicked(
                PartnerAd(
                    ad = fullscreenAd,
                    details = emptyMap(),
                    request = request,
                ),
            ) ?: run {
                PartnerLogController.log(
                    DID_CLICK,
                    "Unable to fire onPartnerAdClicked for Amazon Publisher Services adapter. Listener is null.",
                )
            }
        }
    }

    override fun onAdLeftApplication(adView: View?) {
        // NO-OP
    }

    override fun onAdOpen(adView: View?) {
    }

    override fun onAdClosed(adView: View?) {
        CoroutineScope(Main).launch {
            PartnerLogController.log(DID_DISMISS)
            listener?.onPartnerAdDismissed(
                PartnerAd(
                    ad = fullscreenAd,
                    details = emptyMap(),
                    request = request,
                ),
                null,
            ) ?: run {
                PartnerLogController.log(
                    DID_DISMISS,
                    "Unable to fire onPartnerAdDismissed for Amazon Publisher Services adapter. Listener is null.",
                )
            }
            fullscreenAd = null
        }
    }

    override fun onImpressionFired(adView: View?) {
        CoroutineScope(Main).launch {
            PartnerLogController.log(DID_TRACK_IMPRESSION)
            onShowSuccess()
            onShowSuccess = {}

            listener?.onPartnerAdImpression(
                PartnerAd(
                    ad = fullscreenAd,
                    details = emptyMap(),
                    request = request,
                ),
            ) ?: run {
                PartnerLogController.log(
                    DID_TRACK_IMPRESSION,
                    "Unable to fire onPartnerAdImpression for Amazon Publisher Services adapter. Listener is null.",
                )
            }
        }
    }

    override fun onVideoCompleted(adView: View?) {
        CoroutineScope(Main).launch {
            if (request.format == AdFormat.REWARDED) {
                PartnerLogController.log(DID_REWARD)
                listener?.onPartnerAdRewarded(
                    PartnerAd(
                        ad = fullscreenAd,
                        details = emptyMap(),
                        request = request,
                    ),
                ) ?: run {
                    PartnerLogController.log(
                        DID_REWARD,
                        "Unable to fire onPartnerAdRewarded for Amazon Publisher Services adapter. Listener is null.",
                    )
                }
            }
        }
    }
}
