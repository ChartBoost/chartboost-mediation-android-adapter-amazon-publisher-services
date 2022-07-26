package com.chartboost.helium.amazonpublisherservicesadapter

import android.content.Context
import android.view.View
import com.amazon.device.ads.*
import com.chartboost.helium.amazonpublisherservicesadapter.BuildConfig.VERSION_NAME
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
 * The Helium Amazon Publisher Services (APS) SDK adapter.
 */
class AmazonPublisherServicesAdapter : PartnerAdapter {
    companion object {
        /**
         * The tag used for log messages.
         */
        private const val TAG = "[AmazonPublisherServicesAdapter]"

        /**
         * Indicate that the user has given CCPA consent.
         */
        private const val CCPA_HAS_GIVEN_CONSENT = "1YN-"

        /**
         * Indicate that the user has not given CCPA consent.
         */
        private const val CCPA_HAS_NOT_GIVEN_CONSENT = "1YY-"

        /**
         * Key for setting the CCPA privacy.
         */
        private const val CCPA_PRIVACY_KEY = "us_privacy"

        /**
         * Key for parsing the APS SDK application ID.
         */
        private const val APS_APPLICATION_ID = "application_id"

        /**
         * String Helium placement name to the APS prebid. This is synchronized via ConcurrentHashMap.
         */
        private val placementToAdResponseMap: ConcurrentMap<String, DTBAdResponse?> =
            ConcurrentHashMap()

        /**
         * String Helium placement name to the underlying ad. This is not synchronized.
         * Only access this from the main thread.
         */
        private val placementToInterstitialMap: MutableMap<String, DTBAdInterstitial> =
            mutableMapOf()

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
    private var hasGivenCcpaConsent: Boolean? = null

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
        get() = VERSION_NAME

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
        return suspendCoroutine { continuation ->
            try {
                partnerConfiguration.credentials[APS_APPLICATION_ID]?.let { application_id ->

                    AdRegistration.getInstance(application_id, context)

                    AdRegistration.setAdNetworkInfo(DTBAdNetworkInfo(DTBAdNetwork.OTHER))
                    AdRegistration.setMRAIDSupportedVersions(arrayOf("1.0", "2.0", "3.0"))
                    AdRegistration.setMRAIDPolicy(MRAIDPolicy.CUSTOM)

                    AdRegistration.enableLogging(true, DTBLogLevel.All)

                    continuation.resume(
                        Result.success(
                            LogController.i("$TAG APS SDK successfully initialized.")
                        )
                    )

                } ?: run {
                    LogController.e("Failed to initialize APS SDK.")
                    continuation.resume(Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_SDK_NOT_INITIALIZED)))
                }
            } catch (illegalArgumentException: IllegalArgumentException) {
                continuation.resume(Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_SDK_NOT_INITIALIZED)))
            }
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
            if (GdprConsentStatus.GDPR_CONSENT_GRANTED == gdprConsentStatus) {
                AdRegistration.setConsentStatus(AdRegistration.ConsentStatus.EXPLICIT_YES)
            } else {
                AdRegistration.setConsentStatus(AdRegistration.ConsentStatus.EXPLICIT_NO)
            }
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
        this.hasGivenCcpaConsent = hasGivenCcpaConsent
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
        val placement = request.heliumPlacement
        val pricePoint = SDKUtilities.getPricePoint(placementToAdResponseMap[placement])

        if (pricePoint.isNotEmpty()) {
            return hashMapOf(placement to pricePoint)
        }

        return suspendCoroutine { continuation ->
            CoroutineScope(Main).launch {
                val preBidSettings = placementToPreBidSettings[request.heliumPlacement] ?: run {
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

                if (AdFormat.INTERSTITIAL == request.format) {
                    adRequest.setSizes(
                        if (isVideo) (DTBAdSize.DTBVideo(
                            preBidSettings.width,
                            preBidSettings.height,
                            preBidSettings.partnerPlacement
                        ))
                        else (DTBAdSize.DTBInterstitialAdSize(preBidSettings.partnerPlacement))
                    )
                } else {
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

                when (hasGivenCcpaConsent) {
                    true -> adRequest.putCustomTarget(
                        CCPA_PRIVACY_KEY,
                        CCPA_HAS_GIVEN_CONSENT
                    )
                    false -> adRequest.putCustomTarget(
                        CCPA_PRIVACY_KEY,
                        CCPA_HAS_NOT_GIVEN_CONSENT
                    )
                    null -> {}
                }

                withContext(IO) {
                    adRequest.loadAd(object : DTBAdCallback {
                        override fun onFailure(adError: AdError) {
                            LogController.d(
                                "Failed to fetch price point for placement " +
                                        "$placement, with error ${adError.code}: ${adError.message}"
                            )

                            placementToAdResponseMap.remove(placement)
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
            LogController.d("No interstitial pre bid. Failing load.")
            return Result.failure(HeliumAdException(HeliumErrorCode.NO_FILL))
        }

        return when (request.format) {
            AdFormat.BANNER -> loadBanner(context, request, partnerAdListener)
            AdFormat.INTERSTITIAL -> loadInterstitial(context, request, partnerAdListener)
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
            LogController.d("$TAG User subject to COPPA. Failing all actions.")
            return Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_ERROR))
        }

        return when (partnerAd.request.format) {
            AdFormat.BANNER -> Result.success(partnerAd)
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
            AdFormat.INTERSTITIAL -> removeCachedInterstitialAd(partnerAd)
            else -> Result.success(partnerAd)
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
    private suspend fun loadBanner(
        context: Context,
        request: AdLoadRequest,
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
        val placementName = request.partnerPlacement
        val adResponse = placementToAdResponseMap.remove(placementName)
            ?: return Result.failure(HeliumAdException(HeliumErrorCode.NO_FILL))

        return suspendCoroutine { continuation ->
            DTBAdView(context, object : DTBAdBannerListener {
                override fun onAdLoaded(adView: View?) {
                    adView?.let {
                        continuation.resume(
                            Result.success(
                                PartnerAd(
                                    ad = it,
                                    details = emptyMap(),
                                    request = request
                                )
                            )
                        )
                    } ?: continuation.resume(
                        Result.failure(
                            HeliumAdException(HeliumErrorCode.NO_FILL)
                        )
                    )
                }

                override fun onAdFailed(adView: View?) {
                    LogController.d("$TAG banner onAdFailed")
                    continuation.resumeWith(
                        Result.failure(
                            HeliumAdException(HeliumErrorCode.PARTNER_ERROR)
                        )
                    )
                }

                override fun onAdClicked(adView: View?) {
                    adView?.let {
                        partnerAdListener.onPartnerAdClicked(
                            PartnerAd(
                                ad = it,
                                details = mapOf(),
                                request = request
                            )
                        )
                    } ?: LogController.d(
                        "$TAG Unable to fire onPartnerAdClicked for APS adapter. Ad is null"
                    )
                }

                override fun onAdLeftApplication(adView: View?) {
                    // NO-OP
                }

                override fun onAdOpen(adView: View?) {
                    // NO-OP
                }

                override fun onAdClosed(adView: View?) {
                    adView?.let {
                        partnerAdListener.onPartnerAdDismissed(
                            PartnerAd(
                                ad = it,
                                details = mapOf(),
                                request = request
                            ),
                            null
                        )
                    } ?: LogController.d(
                        "$TAG Unable to fire onPartnerAdDismissed for APS adapter. Ad is null"
                    )
                }

                override fun onImpressionFired(adView: View?) {
                    adView?.let {
                        partnerAdListener.onPartnerAdImpression(
                            PartnerAd(
                                ad = it,
                                details = mapOf(),
                                request = request
                            )
                        )
                    } ?: LogController.d(
                        "$TAG Unable to fire onPartnerAdImpression for APS adapter. Ad is null"
                    )
                }
            }).also {
                it.fetchAd(SDKUtilities.getBidInfo(adResponse))
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
    private suspend fun loadInterstitial(
        context: Context,
        request: AdLoadRequest,
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
        val placementName = request.partnerPlacement
        val adResponse = placementToAdResponseMap.remove(placementName)
            ?: return Result.failure(HeliumAdException(HeliumErrorCode.NO_FILL))

        return suspendCoroutine { continuation ->
            placementToInterstitialMap[placementName] =
                DTBAdInterstitial(context, object : DTBAdInterstitialListener {
                    override fun onAdLoaded(adView: View?) {
                        placementToInterstitialMap[placementName]?.let {
                            continuation.resume(
                                Result.success(
                                    PartnerAd(
                                        ad = adView,
                                        details = emptyMap(),
                                        request = request
                                    )
                                )
                            )
                        } ?: continuation.resume(
                            Result.failure(
                                HeliumAdException(HeliumErrorCode.NO_FILL)
                            )
                        )
                    }

                    override fun onAdFailed(adView: View?) {
                        LogController.d("$TAG interstitial onAdFailed")
                        continuation.resumeWith(
                            Result.failure(
                                HeliumAdException(HeliumErrorCode.PARTNER_ERROR)
                            )
                        )
                    }

                    override fun onAdClicked(adView: View?) {
                        adView?.let {
                            partnerAdListener.onPartnerAdClicked(
                                PartnerAd(
                                    ad = it,
                                    details = mapOf(),
                                    request = request
                                )
                            )
                        } ?: LogController.d(
                            "$TAG Unable to fire onPartnerAdClicked for APS adapter. Ad is null"
                        )
                    }

                    override fun onAdLeftApplication(adView: View?) {
                        // NO-OP
                    }

                    override fun onAdOpen(adView: View?) {
                        // NO-OP
                    }

                    override fun onAdClosed(adView: View?) {
                        adView?.let {
                            partnerAdListener.onPartnerAdDismissed(
                                PartnerAd(
                                    ad = it,
                                    details = mapOf(),
                                    request = request
                                ),
                                null
                            )
                        } ?: LogController.d(
                            "$TAG Unable to fire onPartnerAdDismissed for APS adapter. Ad is null"
                        )
                    }

                    override fun onImpressionFired(adView: View?) {
                        adView?.let {
                            partnerAdListener.onPartnerAdImpression(
                                PartnerAd(
                                    ad = it,
                                    details = mapOf(),
                                    request = request
                                )
                            )
                        } ?: LogController.d(
                            "$TAG Unable to fire onPartnerAdImpression for APS adapter. Ad is null"
                        )
                    }

                }).also {
                    it.fetchAd(SDKUtilities.getBidInfo(adResponse))
                }
        }
    }

    /**
     * Attempt to show an APS interstitial ad.
     *
     * @param partnerAd The [PartnerAd] object containing the APS ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    private suspend fun showInterstitialAd(partnerAd: PartnerAd): Result<PartnerAd> {
        (partnerAd.ad as DTBAdInterstitial).let {
            removeCachedInterstitialAd(partnerAd)
            it.show()
        }
        return Result.success(partnerAd)
    }

    /**
     * Removed an already cached APS interstitial ad.
     *
     * @param partnerAd The [PartnerAd] object containing the APS ad to be removed.
     *
     * @return Result.success(PartnerAd)
     */
    private suspend fun removeCachedInterstitialAd(partnerAd: PartnerAd): Result<PartnerAd> {
        return suspendCoroutine { continuation ->
            partnerAd.ad?.let {
                CoroutineScope(Main).launch {
                    val iterator = placementToInterstitialMap.iterator()
                    while (iterator.hasNext()) {
                        if (iterator.next().value == it) {
                            iterator.remove()
                            continuation.resume(
                                Result.success(partnerAd)
                            )
                        }
                    }
                }
            }
        } ?: run {
            LogController.w("$TAG Failed to remove APS interstitial ad. Ad is null.")
            Result.failure(HeliumAdException(HeliumErrorCode.INTERNAL))
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
        return partnerAd.ad?.let {
            if (it is DTBAdView) {
                it.destroy()
                Result.success(partnerAd)
            } else {
                LogController.w("$TAG Failed to destroy APS banner ad. Ad is not an AppLovinAdView.")
                Result.failure(HeliumAdException(HeliumErrorCode.INTERNAL))
            }
        } ?: run {
            LogController.w("$TAG Failed to destroy APS banner ad. Ad is null.")
            Result.failure(HeliumAdException(HeliumErrorCode.INTERNAL))
        }
    }
}
