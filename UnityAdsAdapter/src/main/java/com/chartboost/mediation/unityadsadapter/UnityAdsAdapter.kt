/*
 * Copyright 2023-2024 Chartboost, Inc.
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

package com.chartboost.mediation.unityadsadapter

import android.app.Activity
import android.content.Context
import android.util.Size
import com.chartboost.chartboostmediationsdk.ChartboostMediationSdk
import com.chartboost.chartboostmediationsdk.domain.*
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.*
import com.chartboost.mediation.unityadsadapter.UnityAdsAdapterConfiguration.adapterVersion
import com.unity3d.ads.IUnityAdsInitializationListener
import com.unity3d.ads.IUnityAdsLoadListener
import com.unity3d.ads.IUnityAdsShowListener
import com.unity3d.ads.UnityAds
import com.unity3d.ads.UnityAds.UnityAdsLoadError
import com.unity3d.ads.UnityAds.UnityAdsShowError
import com.unity3d.ads.metadata.MediationMetaData
import com.unity3d.ads.metadata.MetaData
import com.unity3d.services.banners.BannerErrorInfo
import com.unity3d.services.banners.BannerView
import com.unity3d.services.banners.UnityBannerSize
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.coroutines.resume

/**
 * The Chartboost Mediation Unity Ads Adapter.
 */
class UnityAdsAdapter : PartnerAdapter {
    companion object {
        /**
         * Key for parsing the Unity Ads game ID.
         */
        private const val GAME_ID_KEY = "game_id"
    }

    /**
     * The Unity Ads adapter configuration.
     */
    override var configuration: PartnerAdapterConfiguration = UnityAdsAdapterConfiguration

    /**
     * A map of Chartboost Mediation's listeners for the corresponding load identifier.
     */
    private val listeners = mutableMapOf<String, PartnerAdListener>()

    /**
     * Unity Ads does not have a "ready" check, so we need to manually keep track of it, keyed by the Unity placement ID.
     */
    private var readinessTracker = mutableMapOf<String, Boolean>()

    /**
     * Initialize the Unity Ads SDK so that it is ready to request ads.
     *
     * @param context The current [Context].
     * @param partnerConfiguration Configuration object containing relevant data to initialize Unity Ads.
     */
    override suspend fun setUp(
        context: Context,
        partnerConfiguration: PartnerConfiguration,
    ): Result<Unit> {
        PartnerLogController.log(SETUP_STARTED)

        readinessTracker.clear()

        Json.decodeFromJsonElement<String>((partnerConfiguration.credentials as JsonObject).getValue(GAME_ID_KEY))
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let { gameId ->
                setMediationMetadata(context)

                return suspendCancellableCoroutine { continuation ->
                    fun resumeOnce(result: Result<Unit>) {
                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    }

                    UnityAds.initialize(
                        context.applicationContext,
                        gameId,
                        false,
                        object : IUnityAdsInitializationListener {
                            override fun onInitializationComplete() {
                                resumeOnce(
                                    Result.success(
                                        PartnerLogController.log(
                                            SETUP_SUCCEEDED,
                                        ),
                                    ),
                                )
                            }

                            override fun onInitializationFailed(
                                error: UnityAds.UnityAdsInitializationError?,
                                message: String?,
                            ) {
                                PartnerLogController.log(
                                    SETUP_FAILED,
                                    "Error: $error. Message: $message",
                                )
                                resumeOnce(
                                    Result.failure(
                                        ChartboostMediationAdException(ChartboostMediationError.InitializationError.Unknown),
                                    ),
                                )
                            }
                        },
                    )
                }
            } ?: run {
            PartnerLogController.log(SETUP_FAILED, "Missing game_id value.")
            return Result.failure(ChartboostMediationAdException(ChartboostMediationError.InitializationError.InvalidCredentials))
        }
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
        PartnerLogController.log(BIDDER_INFO_FETCH_SUCCEEDED)
        return emptyMap()
    }

    /**
     * Attempt to load a Unity Ads ad.
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
        readinessTracker[request.partnerPlacement] = false

        return when (request.format.key) {
            AdFormat.BANNER.key, "adaptive_banner" -> loadBannerAd(context, request, partnerAdListener)
            AdFormat.INTERSTITIAL.key, AdFormat.REWARDED.key -> loadFullscreenAd(request, partnerAdListener)
            else -> {
                PartnerLogController.log(LOAD_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.LoadError.UnsupportedAdFormat))
            }
        }
    }

    /**
     * Attempt to load a Unity Ads banner.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     */
    private suspend fun loadBannerAd(
        context: Context,
        request: PartnerAdLoadRequest,
        listener: PartnerAdListener,
    ): Result<PartnerAd> {
        if (context !is Activity) {
            PartnerLogController.log(LOAD_FAILED, "Context is not an Activity instance.")
            return Result.failure(ChartboostMediationAdException(ChartboostMediationError.LoadError.ActivityNotFound))
        }

        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<PartnerAd>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            val ad =
                BannerView(
                    context,
                    request.partnerPlacement,
                    getUnityAdsBannerSize(request.size),
                )

            ad.listener =
                object : BannerView.Listener() {
                    override fun onBannerLoaded(bannerAdView: BannerView) {
                        readinessTracker[request.partnerPlacement] = true

                        PartnerLogController.log(LOAD_SUCCEEDED)
                        resumeOnce(
                            Result.success(
                                PartnerAd(
                                    ad = bannerAdView,
                                    details = emptyMap(),
                                    request = request,
                                ),
                            ),
                        )
                    }

                    override fun onBannerShown(bannerAdView: BannerView?) {
                        PartnerLogController.log(DID_TRACK_IMPRESSION)
                        listener.onPartnerAdImpression(
                            PartnerAd(
                                ad = bannerAdView,
                                details = emptyMap(),
                                request = request,
                            ),
                        )
                    }

                    override fun onBannerClick(bannerAdView: BannerView) {
                        PartnerLogController.log(DID_CLICK)
                        listener.onPartnerAdClicked(
                            PartnerAd(
                                ad = bannerAdView,
                                details = emptyMap(),
                                request = request,
                            ),
                        )
                    }

                    override fun onBannerFailedToLoad(
                        bannerAdView: BannerView,
                        errorInfo: BannerErrorInfo,
                    ) {
                        readinessTracker[request.partnerPlacement] = false

                        PartnerLogController.log(
                            LOAD_FAILED,
                            "Error: ${errorInfo.errorCode}. " +
                                "Message: ${errorInfo.errorMessage}",
                        )
                        resumeOnce(Result.failure(ChartboostMediationAdException(ChartboostMediationError.LoadError.Unknown)))
                    }

                    override fun onBannerLeftApplication(bannerView: BannerView) {}
                }

            ad.load()
        }
    }

    /**
     * Attempt to load a Unity Ads fullscreen ad. This method supports both interstitial and rewarded ads.
     *
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     */
    private suspend fun loadFullscreenAd(
        request: PartnerAdLoadRequest,
        listener: PartnerAdListener,
    ): Result<PartnerAd> {
        // Save the listener for later use.
        listeners[request.identifier] = listener

        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<PartnerAd>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            UnityAds.load(
                request.partnerPlacement,
                object : IUnityAdsLoadListener {
                    override fun onUnityAdsAdLoaded(placementId: String) {
                        readinessTracker[request.partnerPlacement] = true

                        PartnerLogController.log(LOAD_SUCCEEDED)
                        resumeOnce(
                            Result.success(
                                PartnerAd(
                                    ad = null,
                                    details = emptyMap(),
                                    request = request,
                                ),
                            ),
                        )
                    }

                    override fun onUnityAdsFailedToLoad(
                        placementId: String,
                        error: UnityAdsLoadError,
                        message: String,
                    ) {
                        readinessTracker[request.partnerPlacement] = false

                        PartnerLogController.log(LOAD_FAILED, "Error: ${error.name}. Message: $message")
                        resumeOnce(Result.failure(ChartboostMediationAdException(getChartboostMediationError(error))))
                    }
                },
            )
        }
    }

    /**
     * Attempt to show the currently loaded Unity Ads ad.
     *
     * @param activity The current [Activity]
     * @param partnerAd The [PartnerAd] object containing the ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    override suspend fun show(
        activity: Activity,
        partnerAd: PartnerAd,
    ): Result<PartnerAd> {
        PartnerLogController.log(SHOW_STARTED)

        val listener = listeners.remove(partnerAd.request.identifier)

        return when (partnerAd.request.format.key) {
            // Banner ads do not have a separate "show" mechanism.
            AdFormat.BANNER.key, "adaptive_banner" -> {
                PartnerLogController.log(SHOW_SUCCEEDED)
                Result.success(partnerAd)
            }
            AdFormat.INTERSTITIAL.key, AdFormat.REWARDED.key ->
                showFullscreenAd(
                    activity,
                    partnerAd,
                    listener,
                )
            else -> {
                PartnerLogController.log(SHOW_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.UnsupportedAdFormat))
            }
        }
    }

    /**
     * Attempt to show a Unity Ads fullscreen ad.
     *
     * @param activity The current [Activity].
     * @param partnerAd The [PartnerAd] object containing the Unity Ads ad to be shown.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     */
    private suspend fun showFullscreenAd(
        activity: Activity,
        partnerAd: PartnerAd,
        listener: PartnerAdListener?,
    ): Result<PartnerAd> {
        if (!readyToShow(partnerAd.request.partnerPlacement)) {
            return Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.InvalidPartnerPlacement))
        }

        readinessTracker[partnerAd.request.partnerPlacement] = false

        return suspendCancellableCoroutine { continuation ->
            fun resumeOnce(result: Result<PartnerAd>) {
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            UnityAds.show(
                activity,
                partnerAd.request.partnerPlacement,
                object : IUnityAdsShowListener {
                    override fun onUnityAdsShowFailure(
                        placementId: String,
                        error: UnityAdsShowError,
                        message: String,
                    ) {
                        PartnerLogController.log(
                            SHOW_FAILED,
                            "Error: ${error.name}. Message: $message",
                        )
                        resumeOnce(Result.failure(ChartboostMediationAdException(getChartboostMediationError(error))))
                    }

                    override fun onUnityAdsShowStart(placementId: String) {
                        PartnerLogController.log(SHOW_SUCCEEDED)
                        listener?.onPartnerAdImpression(partnerAd)
                            ?: PartnerLogController.log(
                                CUSTOM,
                                "Unable to fire onPartnerAdImpression for Unity Ads adapter.",
                            )
                        resumeOnce(Result.success(partnerAd))
                    }

                    override fun onUnityAdsShowClick(placementId: String) {
                        PartnerLogController.log(DID_CLICK)
                        listener?.onPartnerAdClicked(partnerAd) ?: run {
                            PartnerLogController.log(
                                CUSTOM,
                                "Unable to fire onPartnerAdClicked for Unity Ads adapter. " +
                                    "Listener is null.",
                            )
                        }
                    }

                    override fun onUnityAdsShowComplete(
                        placementId: String,
                        state: UnityAds.UnityAdsShowCompletionState,
                    ) {
                        readinessTracker[partnerAd.request.partnerPlacement] = false
                        if (partnerAd.request.format == AdFormat.REWARDED &&
                            state == UnityAds.UnityAdsShowCompletionState.COMPLETED
                        ) {
                            PartnerLogController.log(DID_REWARD)
                            listener?.onPartnerAdRewarded(partnerAd)
                                ?: PartnerLogController.log(
                                    CUSTOM,
                                    "Unable to fire onPartnerAdRewarded for Unity Ads adapter. " +
                                        "Listener is null.",
                                )
                        }

                        PartnerLogController.log(DID_DISMISS)
                        listener?.onPartnerAdDismissed(partnerAd, null) ?: PartnerLogController.log(
                            CUSTOM,
                            "Unable to fire onPartnerAdClosed for Unity Ads adapter. " +
                                "Listener is null.",
                        )
                    }
                },
            )
        }
    }

    /**
     * Check if the currently loaded Unity Ads ad is ready to be shown. This is only applicable to
     * fullscreen ads, as banner ads do not have a separate "show" mechanism.
     *
     * @param placement The Unity Ads placement associated with the current ad.
     *
     * @return True if the ad is ready to be shown, false otherwise.
     */
    private fun readyToShow(placement: String): Boolean {
        return when {
            readinessTracker[placement] != true -> {
                PartnerLogController.log(SHOW_FAILED, "Ad is not ready.")
                false
            }
            else -> true
        }
    }

    /**
     * Discard unnecessary Unity Ads ad objects and release resources.
     *
     * @param partnerAd The [PartnerAd] object containing the ad to be discarded.
     *
     * @return Result.success(PartnerAd) if the ad was successfully discarded, Result.failure(Exception) otherwise.
     */
    override suspend fun invalidate(partnerAd: PartnerAd): Result<PartnerAd> {
        PartnerLogController.log(INVALIDATE_STARTED)

        listeners.remove(partnerAd.request.identifier)
        readinessTracker.remove(partnerAd.request.partnerPlacement)

        // Only invalidate banner ads.
        // For fullscreen ads, since Unity Ads does not provide an ad in the load callback, we
        // don't have an ad in PartnerAd to invalidate.
        partnerAd.ad?.let {
            if (it is BannerView) {
                it.destroy()
            }
        }

        PartnerLogController.log(INVALIDATE_SUCCEEDED)
        return Result.success(partnerAd)
    }

    /**
     * Notify the Unity Ads SDK of the GDPR applicability and consent status.
     *
     * @param context The current [Context].
     * @param applies True if GDPR applies, false otherwise.
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

        if (applies == true) {
            MetaData(context).apply {
                this["gdpr.consent"] = gdprConsentStatus == GdprConsentStatus.GDPR_CONSENT_GRANTED
                commit()
            }
        }
    }

    /**
     * Set Unity Ads user's consent value using a boolean.
     * This is for publishers to manually set the consent status.
     * This uses CONSENT_GIVEN for true and CONSENT_DECLINED for false.
     *
     * @param context a context that will be passed to the SharedPreferences to set the user consent.
     * @param applies True if GDPR applies, false otherwise.
     * @param consented whether or not the user has consented.
     */
    fun setGdpr(
        context: Context,
        applies: Boolean?,
        consented: Boolean,
    ) {
        setGdpr(
            context,
            applies,
            if (consented) GdprConsentStatus.GDPR_CONSENT_GRANTED else GdprConsentStatus.GDPR_CONSENT_DENIED,
        )
    }

    /**
     * Notify Unity Ads of the user's CCPA consent status, if applicable.
     *
     * @param context The current [Context].
     * @param hasGrantedCcpaConsent True if the user has granted CCPA consent, false otherwise.
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

        val gdprMetaData = MetaData(context)
        gdprMetaData["privacy.consent"] = hasGrantedCcpaConsent
        gdprMetaData.commit()
    }

    /**
     * Notify Unity Ads of the user's CCPA consent status, if applicable.
     *
     * @param context The current [Context].
     * @param hasGrantedCcpaConsent True if the user has granted CCPA consent, false otherwise.
     */
    fun setCcpaConsent(
        context: Context,
        hasGrantedCcpaConsent: Boolean,
    ) {
        setCcpaConsent(context, hasGrantedCcpaConsent, "")
    }

    /**
     * Notify Unity Ads of the COPPA subjectivity.
     *
     * @param context The current [Context].
     * @param isSubjectToCoppa True if the user is subject to COPPA, false otherwise.
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

        val coppaMetaData = MetaData(context)

        // True if the user is over the age limit, meaning that the subject is not subject to COPPA.
        // False if the user is under the limit, meaning subject is subject to COPPA.
        coppaMetaData["privacy.useroveragelimit"] = !isSubjectToCoppa
        coppaMetaData.commit()
    }

    /**
     * Send mediation-specific data to Unity Ads.
     */
    private fun setMediationMetadata(context: Context) {
        val mediationMetaData = MediationMetaData(context)
        mediationMetaData.setName("Chartboost")
        mediationMetaData.setVersion(ChartboostMediationSdk.getVersion())
        mediationMetaData["adapter_version"] = adapterVersion
        mediationMetaData.commit()
    }

    /**
     * Convert a Chartboost Mediation banner size into the corresponding Unity Ads banner size.
     *
     * @param size The Chartboost Mediation banner size.
     *
     * @return The Unity Ads banner size.
     */
    private fun getUnityAdsBannerSize(size: Size?): UnityBannerSize {
        return size?.height?.let {
            when {
                it in 50 until 90 -> UnityBannerSize(320, 50)
                it in 90 until 250 -> UnityBannerSize(728, 90)
                it >= 250 -> UnityBannerSize(300, 250)
                else -> UnityBannerSize(320, 50)
            }
        } ?: UnityBannerSize(320, 50)
    }

    /**
     * Convert a given Unity Ads error code into a [ChartboostMediationError].
     *
     * @param error The Unity Ads error code - either a [UnityAds.UnityAdsLoadError] or [UnityAds.UnityAdsShowError].
     *
     * @return The corresponding [ChartboostMediationError].
     */
    private fun getChartboostMediationError(error: Any) =
        when (error) {
            UnityAds.UnityAdsInitializationError.AD_BLOCKER_DETECTED -> ChartboostMediationError.InitializationError.AdBlockerDetected
            UnityAdsLoadError.NO_FILL -> ChartboostMediationError.LoadError.NoFill
            UnityAdsLoadError.INITIALIZE_FAILED, UnityAdsShowError.NOT_INITIALIZED -> ChartboostMediationError.InitializationError.Unknown
            UnityAdsShowError.NO_CONNECTION -> ChartboostMediationError.OtherError.NoConnectivity
            UnityAdsLoadError.TIMEOUT -> ChartboostMediationError.LoadError.AdRequestTimeout
            UnityAdsShowError.TIMEOUT -> ChartboostMediationError.ShowError.Timeout
            else -> ChartboostMediationError.OtherError.PartnerError
        }
}
