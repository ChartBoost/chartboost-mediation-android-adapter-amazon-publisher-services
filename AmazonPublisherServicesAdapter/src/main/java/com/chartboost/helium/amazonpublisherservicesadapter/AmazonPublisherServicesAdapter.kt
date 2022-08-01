package com.chartboost.helium.amazonpublisherservicesadapter

import android.content.Context
import android.view.View
import com.amazon.device.ads.*
import com.chartboost.heliumsdk.domain.*
import com.chartboost.heliumsdk.utils.LogController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The Helium Amazon Publisher Services (APS) adapter.
 */
class AmazonPublisherServicesAdapter : PartnerAdapter {
    companion object {
        /**
         * The tag used for log messages.
         */
        private val TAG = "[${this::class.java.simpleName}]"

        /**
         * Key for setting the CCPA privacy.
         */
        private const val CCPA_PRIVACY_KEY = "us_privacy"

        /**
         * Key for parsing the APS SDK application ID.
         */
        private const val APS_APPLICATION_ID_KEY = "application_id"

        /**
         * String Helium placement name to the APS prebid. This is synchronized via ConcurrentHashMap.
         */
        private val placementToAdResponseMap: ConcurrentMap<String, DTBAdResponse?> =
            ConcurrentHashMap()

        /**
         * Stores the pre bid settings so we can make a pre bid once the previous one has been consumed.
         * Only access this from the main thread.
         */
        private val placementToPreBidSettings: MutableMap<String, PreBidSettings> = mutableMapOf()
    }

    /**
     * Indicate whether GDPR currently applies to the user.
     */
    private var isSubjectToGdpr = false

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
     * Note that the version string will be in the format of `Helium.Partner.Partner.Partner.Adapter`,
     * in which `Helium` is the version of the Helium SDK, `Partner` is the major.minor.patch version
     * of the partner SDK, and `Adapter` is the version of the adapter.
     */
    override val adapterVersion: String
        get() = BuildConfig.HELIUM_APS_ADAPTER_VERSION

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
        return try {
            partnerConfiguration.credentials[APS_APPLICATION_ID_KEY]?.let { appKey ->

                AdRegistration.getInstance(appKey, context)

                AdRegistration.setAdNetworkInfo(DTBAdNetworkInfo(DTBAdNetwork.OTHER))
                AdRegistration.setMRAIDSupportedVersions(arrayOf("1.0", "2.0", "3.0"))
                AdRegistration.setMRAIDPolicy(MRAIDPolicy.CUSTOM)

                // TODO: Remove once pipes have proven to function.
                AdRegistration.enableLogging(true, DTBLogLevel.All)

                Result.success(LogController.i("$TAG APS SDK successfully initialized."))
            } ?: run {
                LogController.e("$TAG Failed to initialize APS SDK: Missing application ID.")
                Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_SDK_NOT_INITIALIZED))
            }
        } catch (illegalArgumentException: IllegalArgumentException) {
            LogController.e("$TAG Failed to initialize APS SDK: Illegal Argument Exception. ${illegalArgumentException.message}")
            Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_SDK_NOT_INITIALIZED))
        }
    }

    /**
     * Notify APS as to whether GDPR applies.
     *
     * @param context The current [Context].
     * @param gdprApplies Whether GDPR applies or not.
     */
    override fun setGdprApplies(context: Context, gdprApplies: Boolean) {
        AdRegistration.setCMPFlavor(AdRegistration.CMPFlavor.CMP_NOT_DEFINED)
        this.isSubjectToGdpr = gdprApplies
    }

    /**
     * Notify APS of user GDPR consent.
     *
     * @param context The current [Context]
     * @param gdprConsentStatus The user's current GDPR consent status.
     */
    override fun setGdprConsentStatus(context: Context, gdprConsentStatus: GdprConsentStatus) {
        if (isSubjectToGdpr) {
            when (gdprConsentStatus) {
                GdprConsentStatus.GDPR_CONSENT_GRANTED -> AdRegistration.setConsentStatus(
                    AdRegistration.ConsentStatus.EXPLICIT_YES
                )
                GdprConsentStatus.GDPR_CONSENT_DENIED -> AdRegistration.setConsentStatus(
                    AdRegistration.ConsentStatus.EXPLICIT_NO
                )
                GdprConsentStatus.GDPR_CONSENT_UNKNOWN -> AdRegistration.setConsentStatus(
                    AdRegistration.ConsentStatus.UNKNOWN
                )
            }
        } else {
            AdRegistration.setConsentStatus(AdRegistration.ConsentStatus.CONSENT_NOT_DEFINED)
        }
    }

    /**
     * Notify APS of the CCPA compliance.
     *
     * @param context The current [Context]
     * @param hasGivenCcpaConsent The user's current CCPA consent status.
     * @param privacyString The CCPA privacy string.
     */
    override fun setCcpaConsent(
        context: Context,
        hasGivenCcpaConsent: Boolean,
        privacyString: String?
    ) {
        ccpaPrivacyString = privacyString
    }

    /**
     * Notify APS of the COPPA subjectivity.
     *
     * @param context The current [Context]
     * @param isSubjectToCoppa Whether the user is subject to COPPA.
     */
    override fun setUserSubjectToCoppa(context: Context, isSubjectToCoppa: Boolean) {
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

        val placement = request.heliumPlacement
        val pricePoint = SDKUtilities.getPricePoint(placementToAdResponseMap[placement])

        if (pricePoint.isNotEmpty()) {
            return hashMapOf(placement to pricePoint)
        }

        return suspendCoroutine { continuation ->
            CoroutineScope(Main).launch {
                val preBidSettings = placementToPreBidSettings[placement] ?: run {
                    LogController.d("Could not find prebidSettings for this placement.")
                    continuation.resumeWith(
                        Result.success(
                            HashMap()
                        )
                    )
                    return@launch
                }


                val adRequest = DTBAdRequest()
                val isVideo = preBidSettings.video

                if (preBidSettings.partnerPlacement.isEmpty()) {
                    continuation.resumeWith(
                        Result.success(
                            HashMap()
                        )
                    )
                    return@launch
                }

                if (isSubjectToCoppa) {
                    continuation.resumeWith(
                        Result.success(
                            HashMap()
                        )
                    )
                    return@launch
                }

                buildAdRequestSize(request.format, adRequest, isVideo, preBidSettings)
                buildCcpaPrivacy(adRequest, ccpaPrivacyString)

                withContext(IO) {
                    adRequest.loadAd(object : DTBAdCallback {
                        override fun onFailure(adError: AdError) {
                            LogController.d(
                                "Failed to fetch price point for placement " +
                                        "$placement, with error ${adError.code}: ${adError.message}"
                            )

                            continuation.resumeWith(
                                Result.success(
                                    HashMap()
                                )
                            )
                        }

                        override fun onSuccess(adResponse: DTBAdResponse) {
                            placementToAdResponseMap[placement] = adResponse
                            continuation.resumeWith(
                                Result.success(
                                    hashMapOf(
                                        placement to SDKUtilities.getPricePoint(adResponse)
                                    )
                                )
                            )
                        }
                    })
                }
            }
        }
    }

    private fun buildAdRequestSize(
        format: AdFormat,
        adRequest: DTBAdRequest,
        isVideo: Boolean,
        preBidSettings: PreBidSettings
    ) {
        return when (format) {
            AdFormat.INTERSTITIAL -> {
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
     * @param request An [AdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    override suspend fun load(
        context: Context,
        request: AdLoadRequest,
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
        if (isSubjectToCoppa) {
            LogController.d("$TAG User subject to COPPA. Failing load.")
            return Result.failure(HeliumAdException(HeliumErrorCode.NO_FILL))
        }

        return when (request.format) {
            AdFormat.BANNER -> loadBannerAd(context, request, partnerAdListener)
            AdFormat.INTERSTITIAL -> loadInterstitialAd(context, request, partnerAdListener)
            AdFormat.REWARDED -> return Result.failure(HeliumAdException(HeliumErrorCode.AD_FORMAT_NOT_SUPPORTED))
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
        if (isSubjectToCoppa) {
            LogController.d("$TAG User subject to COPPA. Failing all show.")
            return Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_ERROR))
        }

        return when (partnerAd.request.format) {
            AdFormat.BANNER -> {
                // Banner ads do not have a separate "show" mechanism.
                Result.success(partnerAd)
            }
            AdFormat.INTERSTITIAL -> showInterstitialAd(partnerAd)
            AdFormat.REWARDED -> Result.failure(HeliumAdException(HeliumErrorCode.AD_FORMAT_NOT_SUPPORTED))
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
        return when (partnerAd.request.format) {
            AdFormat.BANNER -> destroyBannerAd(partnerAd)
            else -> Result.success(partnerAd)
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
    ): DTBAdRequest {
        return adRequest.apply {
            ccpaPrivacyString?.let {
                putCustomTarget(CCPA_PRIVACY_KEY, it)
            }
        }
    }

    /**
     * Attempt to load an APS banner ad.
     *
     * @param context The current [Context].
     * @param request An [AdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadBannerAd(
        context: Context,
        request: AdLoadRequest,
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
        val placementName = request.heliumPlacement
        val adResponse = placementToAdResponseMap.remove(placementName)
            ?: return Result.failure(HeliumAdException(HeliumErrorCode.NO_FILL))

        return suspendCoroutine { continuation ->
            CoroutineScope(Main).launch {
                DTBAdView(context, object : DTBAdBannerListener {
                    override fun onAdLoaded(adView: View?) {
                        continuation.resume(
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
                        LogController.d("$TAG Failed to load Amazon Publisher Services banner ad.")
                        continuation.resumeWith(
                            Result.failure(
                                HeliumAdException(HeliumErrorCode.PARTNER_ERROR)
                            )
                        )
                    }

                    override fun onAdClicked(adView: View?) {
                        partnerAdListener.onPartnerAdClicked(
                            PartnerAd(
                                ad = adView,
                                details = mapOf(),
                                request = request
                            )
                        )
                    }

                    override fun onAdLeftApplication(adView: View?) {
                        // NO-OP
                    }

                    override fun onAdOpen(adView: View?) {
                        // NO-OP
                    }

                    override fun onAdClosed(adView: View?) {
                        partnerAdListener.onPartnerAdDismissed(
                            PartnerAd(
                                ad = adView,
                                details = mapOf(),
                                request = request
                            ),
                            null
                        )
                    }

                    override fun onImpressionFired(adView: View?) {
                        partnerAdListener.onPartnerAdImpression(
                            PartnerAd(
                                ad = adView,
                                details = mapOf(),
                                request = request
                            )
                        )
                    }
                }).fetchAd(SDKUtilities.getBidInfo(adResponse))
            }
        }
    }

    /**
     * Attempt to load an APS interstitial ad.
     *
     * @param request An [AdLoadRequest] instance containing data to load the ad with.
     * @param partnerAdListener A [PartnerAdListener] to notify Helium of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadInterstitialAd(
        context: Context,
        request: AdLoadRequest,
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
        val placementName = request.heliumPlacement
        val adResponse = placementToAdResponseMap.remove(placementName)
            ?: return Result.failure(HeliumAdException(HeliumErrorCode.NO_FILL))

        return suspendCoroutine { continuation ->
            DTBAdInterstitial(context, object : DTBAdInterstitialListener {
                override fun onAdLoaded(adView: View?) {
                    continuation.resume(
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
                    LogController.d("$TAG Failed to load Amazon Publisher Services interstitial ad.")
                    continuation.resumeWith(
                        Result.failure(
                            HeliumAdException(HeliumErrorCode.PARTNER_ERROR)
                        )
                    )
                }

                override fun onAdClicked(adView: View?) {
                    partnerAdListener.onPartnerAdClicked(
                        PartnerAd(
                            ad = adView,
                            details = mapOf(),
                            request = request
                        )
                    )
                }

                override fun onAdLeftApplication(adView: View?) {
                    // NO-OP
                }

                override fun onAdOpen(adView: View?) {
                    // NO-OP
                }

                override fun onAdClosed(adView: View?) {
                    partnerAdListener.onPartnerAdDismissed(
                        PartnerAd(
                            ad = adView,
                            details = mapOf(),
                            request = request
                        ),
                        null
                    )
                }

                override fun onImpressionFired(adView: View?) {
                    partnerAdListener.onPartnerAdImpression(
                        PartnerAd(
                            ad = adView,
                            details = mapOf(),
                            request = request
                        )
                    )
                }

            }).fetchAd(SDKUtilities.getBidInfo(adResponse))
        }
    }

    /**
     * Attempt to show an APS interstitial ad.
     *
     * @param partnerAd The [PartnerAd] object containing the APS ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private fun showInterstitialAd(partnerAd: PartnerAd): Result<PartnerAd> {
        (partnerAd.ad as DTBAdInterstitial).show()
        return Result.success(partnerAd)
    }

    /**
     * Destroy the current APS banner ad.
     *
     * @param partnerAd The [PartnerAd] object containing the APS ad to be destroyed.
     *
     * @return Result.success(PartnerAd) if the ad was successfully destroyed, Result.failure(Exception) otherwise.
     */
    private fun destroyBannerAd(partnerAd: PartnerAd): Result<PartnerAd> {
        return partnerAd.ad?.let {
            if (it is DTBAdView) {
                it.destroy()
                Result.success(partnerAd)
            } else {
                LogController.w("$TAG Failed to destroy APS banner ad. Ad is not a DTBAdView.")
                Result.failure(HeliumAdException(HeliumErrorCode.INTERNAL))
            }
        } ?: run {
            LogController.w("$TAG Failed to destroy APS banner ad. Ad is null.")
            Result.failure(HeliumAdException(HeliumErrorCode.INTERNAL))
        }
    }
}
