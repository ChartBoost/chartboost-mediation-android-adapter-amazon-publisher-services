/*
 * Copyright 2022-2023 Chartboost, Inc.
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
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.coroutines.resume

/**
 * The Chartboost Mediation Amazon Publisher Services (APS) adapter.
 */
class AmazonPublisherServicesAdapter : PartnerAdapter {
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
                        if (value) "enabled. Remember to disable it before publishing."
                        else "disabled."
                    }"
                )
            }

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
    }

    /**
     * Data class to store all the pre bid settings from the configuration.
     */
    private data class PreBidSettings(
        val partnerPlacement: String,
        val width: Int,
        val height: Int,
        val isVideo: Boolean
    )

    /**
     * A lambda to call for successful APS ad shows.
     */
    private var onShowSuccess: () -> Unit = {}

    /**
     * String Chartboost placement name to the APS prebid.
     */
    private val placementToAdResponseMap: MutableMap<String, DTBAdResponse?> =
        mutableMapOf()

    /**
     * Stores the pre bid settings so we can make a pre bid once the previous one has been consumed.
     * Only access this from the main thread.
     */
    private val placementToPreBidSettings: MutableMap<String, PreBidSettings> = mutableMapOf()

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
        partnerConfiguration: PartnerConfiguration
    ): Result<Unit> {
        PartnerLogController.log(SETUP_STARTED)
        return Json.decodeFromJsonElement<String>(
            (partnerConfiguration.credentials as JsonObject).getValue(APS_APPLICATION_ID_KEY)
        )
            .trim()
            .takeIf { it.isNotEmpty() }?.let { appKey ->
                AdRegistration.getInstance(appKey, context)

                AdRegistration.setAdNetworkInfo(DTBAdNetworkInfo(DTBAdNetwork.OTHER))
                AdRegistration.setMRAIDSupportedVersions(arrayOf("1.0", "2.0", "3.0"))
                AdRegistration.setMRAIDPolicy(MRAIDPolicy.CUSTOM)

                // TODO: Remove once pipes have proven to function.
                AdRegistration.enableLogging(true, DTBLogLevel.All)

                val preBidArray = Json.decodeFromJsonElement<JsonArray>(
                    (partnerConfiguration.credentials as JsonObject).getValue(PREBIDS_KEY)
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
            val chartboostPlacement = get(CHARTBOOST_PLACEMENT_KEY)?.let { 
                Json.decodeFromJsonElement(it)
            } ?: ""
            val partnerPlacement = get(PARTNER_PLACEMENT_KEY)?.let {
                Json.decodeFromJsonElement(it)
            } ?: ""

            val width = get(WIDTH_KEY)?.let {
                Json.decodeFromJsonElement(it)
            } ?: 0

            val height = get(HEIGHT_KEY)?.let {
                Json.decodeFromJsonElement(it)
            } ?: 0

            val isVideo = get(IS_VIDEO_KEY)?.let {
                Json.decodeFromJsonElement(it)
            } ?: false

            placementToPreBidSettings[chartboostPlacement] =
                PreBidSettings(
                    partnerPlacement = partnerPlacement,
                    width = width,
                    height = height,
                    isVideo = isVideo
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
        gdprConsentStatus: GdprConsentStatus
    ) {
        PartnerLogController.log(
            when (applies) {
                true -> GDPR_APPLICABLE
                false -> GDPR_NOT_APPLICABLE
                else -> GDPR_UNKNOWN
            }
        )

        PartnerLogController.log(
            when (gdprConsentStatus) {
                GdprConsentStatus.GDPR_CONSENT_UNKNOWN -> GDPR_CONSENT_UNKNOWN
                GdprConsentStatus.GDPR_CONSENT_GRANTED -> GDPR_CONSENT_GRANTED
                GdprConsentStatus.GDPR_CONSENT_DENIED -> GDPR_CONSENT_DENIED
            }
        )

        AdRegistration.setCMPFlavor(AdRegistration.CMPFlavor.CMP_NOT_DEFINED)

        if (applies == true) {
            AdRegistration.setConsentStatus(
                when (gdprConsentStatus) {
                    GdprConsentStatus.GDPR_CONSENT_UNKNOWN -> AdRegistration.ConsentStatus.UNKNOWN
                    GdprConsentStatus.GDPR_CONSENT_GRANTED -> AdRegistration.ConsentStatus.EXPLICIT_YES
                    GdprConsentStatus.GDPR_CONSENT_DENIED -> AdRegistration.ConsentStatus.EXPLICIT_NO
                }
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
        privacyString: String
    ) {
        PartnerLogController.log(
            if (hasGrantedCcpaConsent) CCPA_CONSENT_GRANTED
            else CCPA_CONSENT_DENIED
        )

        ccpaPrivacyString = privacyString
    }

    /**
     * Notify APS of the COPPA subjectivity.
     *
     * @param context The current [Context]
     * @param isSubjectToCoppa Whether the user is subject to COPPA.
     */
    override fun setUserSubjectToCoppa(context: Context, isSubjectToCoppa: Boolean) {
        PartnerLogController.log(
            if (isSubjectToCoppa) COPPA_SUBJECT
            else COPPA_NOT_SUBJECT
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
        request: PreBidRequest
    ): Map<String, String> {
        // TODO: Remove PreBidSettings and move settings to setUp [HB-4223](https://chartboost.atlassian.net/browse/HB-4223)
        PartnerLogController.log(BIDDER_INFO_FETCH_STARTED)

        val placement = request.chartboostPlacement
        withContext(Main) {
            placementToAdResponseMap[placement]
        }?.let { dtbAdResponse ->
            SDKUtilities.getPricePoint(dtbAdResponse)?.let {
                if (it.isNotEmpty()) {
                    return mutableMapOf(placement to it)
                }
            }
        }

        val preBidSettings = withContext(Main) {
            placementToPreBidSettings[placement]
        } ?: run {
            PartnerLogController.log(
                BIDDER_INFO_FETCH_FAILED,
                "Could not find prebidSettings for this placement."
            )
            return mapOf()
        }

        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Map<String, String>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            val adRequest = DTBAdRequest()
            val isVideo = preBidSettings.isVideo

            if (preBidSettings.partnerPlacement.isEmpty()) {
                resumeOnce(mapOf())
                return@suspendCancellableCoroutine
            }

            if (isSubjectToCoppa) {
                resumeOnce(mapOf())
                return@suspendCancellableCoroutine
            }

            buildAdRequestSize(request.format, adRequest, isVideo, preBidSettings)
            buildCcpaPrivacy(adRequest, ccpaPrivacyString)

            adRequest.loadAd(object : DTBAdCallback {
                override fun onFailure(adError: AdError) {
                    PartnerLogController.log(
                        BIDDER_INFO_FETCH_FAILED,
                        "Placement: $placement. Error: ${adError.code}. Message: ${adError.message}"
                    )

                    CoroutineScope(Main.immediate).launch {
                        placementToAdResponseMap.remove(placement)
                    }

                    resumeOnce(mapOf())
                }

                override fun onSuccess(adResponse: DTBAdResponse) {
                    CoroutineScope(Main.immediate).launch {
                        placementToAdResponseMap[placement] = adResponse
                    }

                    SDKUtilities.getPricePoint(adResponse)?.let { pricePoint ->
                        PartnerLogController.log(BIDDER_INFO_FETCH_SUCCEEDED)
                        resumeOnce(mutableMapOf(placement to pricePoint))
                    } ?: run {
                        PartnerLogController.log(BIDDER_INFO_FETCH_FAILED, "Placement: $placement.")
                        resumeOnce(mapOf())
                    }
                }
            })
        }
    }

    /**
     * Builds a [DTBAdSize] object based on the ad format, whether it is a [DTBAdSize.DTBVideo] and
     * passes it to the [DTBAdRequest] ad request.
     *
     * @param format The current [AdFormat].
     * @param adRequest The current [DTBAdRequest].
     * @param isVideo Whether the current ad request is a video or not.
     * @param preBidSettings Relevant data for the current bid request.
     */
    private fun buildAdRequestSize(
        format: AdFormat,
        adRequest: DTBAdRequest,
        isVideo: Boolean,
        preBidSettings: PreBidSettings
    ) {
        return when (format) {
            AdFormat.INTERSTITIAL.key, AdFormat.REWARDED.key -> {
                adRequest.setSizes(
                    if (isVideo) (DTBAdSize.DTBVideo(
                        preBidSettings.width,
                        preBidSettings.height,
                        preBidSettings.partnerPlacement
                    ))
                    else (DTBAdSize.DTBInterstitialAdSize(preBidSettings.partnerPlacement))
                )
            }
            else -> {
                adRequest.setSizes(
                    if (isVideo) (DTBAdSize.DTBVideo(
                        preBidSettings.width,
                        preBidSettings.height,
                        preBidSettings.partnerPlacement
                    )) else (DTBAdSize(
                        preBidSettings.width,
                        preBidSettings.height,
                        preBidSettings.partnerPlacement
                    ))
                )
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
        partnerAdListener: PartnerAdListener
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
    override suspend fun show(context: Context, partnerAd: PartnerAd): Result<PartnerAd> {
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
     * Attaches the CCPA privacy setting to the APS request.
     *
     * @param adRequest A [DTBAdRequest] to set the privacy setting for the current DTBAdRequest.
     * @param ccpaPrivacyString the privacy string that will be set for the current DTBadRequest.
     */
    private fun buildCcpaPrivacy(
        adRequest: DTBAdRequest,
        ccpaPrivacyString: String?
    ) {
        adRequest.apply {
            ccpaPrivacyString?.let {
                putCustomTarget(CCPA_PRIVACY_KEY, it)
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
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
        val placementName = request.chartboostPlacement
        val adResponse = withContext(Main) {
            placementToAdResponseMap.remove(placementName)
        } ?: run {
            PartnerLogController.log(LOAD_FAILED, "No ad response found.")
            return Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_NO_FILL))
        }

        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<PartnerAd>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            DTBAdView(context, object : DTBAdBannerListener {
                override fun onAdLoaded(adView: View?) {
                    PartnerLogController.log(LOAD_SUCCEEDED)
                    resumeOnce(
                        Result.success(
                            PartnerAd(
                                ad = adView,
                                details = emptyMap(),
                                request = request
                            )
                        )
                    )
                }

                override fun onAdFailed(adView: View?) {
                    PartnerLogController.log(LOAD_FAILED)
                    resumeOnce(
                        Result.failure(
                            ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_UNKNOWN)
                        )
                    )
                }

                override fun onAdClicked(adView: View?) {
                    CoroutineScope(Main).launch {
                        PartnerLogController.log(DID_CLICK)
                        partnerAdListener.onPartnerAdClicked(
                            PartnerAd(
                                ad = adView,
                                details = emptyMap(),
                                request = request
                            )
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
                                request = request
                            ),
                            null
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
                                request = request
                            )
                        )
                    }
                }
            }).fetchAd(SDKUtilities.getBidInfo(adResponse))
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
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
        val placementName = request.chartboostPlacement
        val adResponse = withContext(Main) {
            placementToAdResponseMap.remove(placementName)
        } ?: run {
            PartnerLogController.log(LOAD_FAILED, "No ad response found.")
            return Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_NO_FILL))
        }

        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<PartnerAd>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            lateinit var fullscreenAd: DTBAdInterstitial
            fullscreenAd = DTBAdInterstitial(context, object : DTBAdInterstitialListener {
                override fun onAdLoaded(adView: View?) {
                    PartnerLogController.log(LOAD_SUCCEEDED)
                    resumeOnce(
                        Result.success(
                            PartnerAd(
                                ad = fullscreenAd,
                                details = emptyMap(),
                                request = request
                            )
                        )
                    )
                }

                override fun onAdFailed(adView: View?) {
                    PartnerLogController.log(LOAD_FAILED)
                    resumeOnce(
                        Result.failure(
                            ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_UNKNOWN)
                        )
                    )
                }

                override fun onAdClicked(adView: View?) {
                    CoroutineScope(Main).launch {
                        PartnerLogController.log(DID_CLICK)
                        partnerAdListener.onPartnerAdClicked(
                            PartnerAd(
                                ad = fullscreenAd,
                                details = emptyMap(),
                                request = request
                            )
                        )
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
                        partnerAdListener.onPartnerAdDismissed(
                            PartnerAd(
                                ad = fullscreenAd,
                                details = emptyMap(),
                                request = request
                            ),
                            null
                        )
                    }
                }

                override fun onImpressionFired(adView: View?) {
                    CoroutineScope(Main).launch {
                        PartnerLogController.log(DID_TRACK_IMPRESSION)
                        partnerAdListener.onPartnerAdImpression(
                            PartnerAd(
                                ad = fullscreenAd,
                                details = emptyMap(),
                                request = request
                            )
                        )
                        onShowSuccess()
                    }
                }

                override fun onVideoCompleted(adView: View?) {
                    CoroutineScope(Main).launch {
                        if (request.format == AdFormat.REWARDED) {
                            PartnerLogController.log(DID_REWARD)
                            partnerAdListener.onPartnerAdRewarded(
                                PartnerAd(
                                    ad = fullscreenAd,
                                    details = emptyMap(),
                                    request = request
                                )
                            )
                        }
                    }
                }
            })

            fullscreenAd.fetchAd(SDKUtilities.getBidInfo(adResponse))
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
                    fun resumeOnce(result: Result<PartnerAd>) {
                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    }

                    onShowSuccess = {
                        PartnerLogController.log(SHOW_SUCCEEDED)
                        resumeOnce(Result.success(partnerAd))
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
