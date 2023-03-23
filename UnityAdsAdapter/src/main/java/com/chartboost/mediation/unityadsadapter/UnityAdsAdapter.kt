/*
 * Copyright 2022-2023 Chartboost, Inc.
 * 
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

package com.chartboost.mediation.unityadsadapter

import android.app.Activity
import android.content.Context
import android.util.Size
import com.chartboost.heliumsdk.HeliumSdk
import com.chartboost.heliumsdk.domain.*
import com.chartboost.heliumsdk.utils.PartnerLogController
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.*
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The Chartboost Mediation Unity Ads Adapter.
 */
class UnityAdsAdapter : PartnerAdapter {
    companion object {
        /**
         * Flag that can optionally be set to enable Unity Ads debug mode.
         */
        var debugMode = UnityAds.getDebugMode()
            set(value) {
                field = value
                UnityAds.setDebugMode(value)
                PartnerLogController.log(
                    CUSTOM,
                    "Unity Ads debug mode is ${if (value) "enabled" else "disabled"}."
                )
            }

        /**
         * Key for parsing the Unity Ads game ID.
         */
        private const val GAME_ID_KEY = "game_id"
    }

    /**
     * A map of Chartboost Mediation's listeners for the corresponding Chartboost placements.
     */
    private val listeners = mutableMapOf<String, PartnerAdListener>()

    /**
     * Unity Ads does not have a "ready" check, so we need to manually keep track of it, keyed by the Unity placement ID.
     */
    private var readinessTracker = mutableMapOf<String, Boolean>()

    /**
     * Get the Unity Ads adapter version.
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
        get() = BuildConfig.CHARTBOOST_MEDIATION_UNITY_ADS_ADAPTER_VERSION

    /**
     * Get the Unity Ads SDK version.
     */
    override val partnerSdkVersion: String
        get() = UnityAds.getVersion()

    /**
     * Get the partner name for internal uses.
     */
    override val partnerId: String
        get() = "unity"

    /**
     * Get the partner name for external uses.
     */
    override val partnerDisplayName: String
        get() = "Unity Ads"

    /**
     * Initialize the Unity Ads SDK so that it is ready to request ads.
     *
     * @param context The current [Context].
     * @param partnerConfiguration Configuration object containing relevant data to initialize Unity Ads.
     */
    override suspend fun setUp(
        context: Context,
        partnerConfiguration: PartnerConfiguration
    ): Result<Unit> {
        PartnerLogController.log(SETUP_STARTED)

        readinessTracker.clear()

        Json.decodeFromJsonElement<String>((partnerConfiguration.credentials as JsonObject).getValue(GAME_ID_KEY))
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let { gameId ->
                setMediationMetadata(context)

                return suspendCoroutine { continuation ->
                    UnityAds.initialize(
                        context.applicationContext,
                        gameId,
                        false,
                        object : IUnityAdsInitializationListener {
                            override fun onInitializationComplete() {
                                continuation.resume(
                                    Result.success(
                                        PartnerLogController.log(
                                            SETUP_SUCCEEDED
                                        )
                                    )
                                )
                            }

                            override fun onInitializationFailed(
                                error: UnityAds.UnityAdsInitializationError?,
                                message: String?
                            ) {
                                PartnerLogController.log(
                                    SETUP_FAILED,
                                    "Error: $error. Message: $message"
                                )
                                continuation.resume(Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INITIALIZATION_FAILURE_UNKNOWN)))
                            }
                        })
                }
            } ?: run {
            PartnerLogController.log(SETUP_FAILED, "Missing game_id value.")
            return Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INITIALIZATION_FAILURE_INVALID_CREDENTIALS))
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
        request: PreBidRequest
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
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
        PartnerLogController.log(LOAD_STARTED)
        readinessTracker[request.partnerPlacement] = false

        return when (request.format) {
            AdFormat.BANNER -> loadBannerAd(context, request, partnerAdListener)
            AdFormat.INTERSTITIAL, AdFormat.REWARDED -> loadFullscreenAd(request, partnerAdListener)
            else -> {
                PartnerLogController.log(LOAD_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_UNSUPPORTED_AD_FORMAT))
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
        listener: PartnerAdListener
    ): Result<PartnerAd> {
        if (context !is Activity) {
            PartnerLogController.log(LOAD_FAILED, "Context is not an Activity instance.")
            return Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_ACTIVITY_NOT_FOUND))
        }

        return suspendCoroutine { continuation ->
            val ad = BannerView(
                context, request.partnerPlacement,
                getUnityAdsBannerSize(request.size)
            )

            ad.listener = object : BannerView.Listener() {
                override fun onBannerLoaded(bannerAdView: BannerView) {
                    readinessTracker[request.partnerPlacement] = true

                    PartnerLogController.log(LOAD_SUCCEEDED)
                    continuation.resume(
                        Result.success(
                            PartnerAd(
                                ad = bannerAdView,
                                details = emptyMap(),
                                request = request,
                            )
                        )
                    )
                }

                override fun onBannerClick(bannerAdView: BannerView) {
                    PartnerLogController.log(DID_CLICK)
                    listener.onPartnerAdClicked(
                        PartnerAd(
                            ad = bannerAdView,
                            details = emptyMap(),
                            request = request,
                        )
                    )
                }

                override fun onBannerFailedToLoad(
                    bannerAdView: BannerView,
                    errorInfo: BannerErrorInfo
                ) {
                    readinessTracker[request.partnerPlacement] = false

                    PartnerLogController.log(
                        LOAD_FAILED, "Error: ${errorInfo.errorCode}. " +
                                "Message: ${errorInfo.errorMessage}"
                    )
                    continuation.resume(Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_UNKNOWN)))
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
        listener: PartnerAdListener
    ): Result<PartnerAd> {
        // Save the listener for later use.
        listeners[request.chartboostPlacement] = listener

        return suspendCoroutine { continuation ->
            UnityAds.load(request.partnerPlacement, object : IUnityAdsLoadListener {
                override fun onUnityAdsAdLoaded(placementId: String) {
                    readinessTracker[request.partnerPlacement] = true

                    PartnerLogController.log(LOAD_SUCCEEDED)
                    continuation.resume(
                        Result.success(
                            PartnerAd(
                                ad = null,
                                details = emptyMap(),
                                request = request,
                            )
                        )
                    )
                }

                override fun onUnityAdsFailedToLoad(
                    placementId: String,
                    error: UnityAdsLoadError,
                    message: String
                ) {
                    readinessTracker[request.partnerPlacement] = false

                    PartnerLogController.log(LOAD_FAILED, "Error: ${error.name}. Message: $message")
                    continuation.resume(Result.failure(ChartboostMediationAdException(getChartboostMediationError(error))))
                }
            })
        }
    }

    /**
     * Attempt to show the currently loaded Unity Ads ad.
     *
     * @param context The current [Context]
     * @param partnerAd The [PartnerAd] object containing the ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    override suspend fun show(context: Context, partnerAd: PartnerAd): Result<PartnerAd> {
        PartnerLogController.log(SHOW_STARTED)

        val listener = listeners.remove(partnerAd.request.chartboostPlacement)

        return when (partnerAd.request.format) {
            // Banner ads do not have a separate "show" mechanism.
            AdFormat.BANNER -> {
                PartnerLogController.log(SHOW_SUCCEEDED)
                Result.success(partnerAd)
            }
            AdFormat.INTERSTITIAL, AdFormat.REWARDED -> showFullscreenAd(
                context,
                partnerAd,
                listener
            )
            else -> {
                PartnerLogController.log(SHOW_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_UNSUPPORTED_AD_FORMAT))
            }
        }
    }

    /**
     * Attempt to show a Unity Ads fullscreen ad.
     *
     * @param context The current [Context].
     * @param partnerAd The [PartnerAd] object containing the Unity Ads ad to be shown.
     * @param listener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     */
    private suspend fun showFullscreenAd(
        context: Context,
        partnerAd: PartnerAd,
        listener: PartnerAdListener?
    ): Result<PartnerAd> {
        if (!readyToShow(context, partnerAd.request.partnerPlacement)) {
            return Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_INVALID_PARTNER_PLACEMENT))
        }

        readinessTracker[partnerAd.request.partnerPlacement] = false

        return suspendCoroutine { continuation ->
            UnityAds.show(
                context as Activity,
                partnerAd.request.partnerPlacement,
                object : IUnityAdsShowListener {
                    override fun onUnityAdsShowFailure(
                        placementId: String,
                        error: UnityAdsShowError,
                        message: String
                    ) {
                        PartnerLogController.log(
                            SHOW_FAILED,
                            "Error: ${error.name}. Message: $message"
                        )
                        continuation.resume(Result.failure(ChartboostMediationAdException(getChartboostMediationError(error))))
                    }

                    override fun onUnityAdsShowStart(placementId: String) {
                        PartnerLogController.log(SHOW_SUCCEEDED)
                        continuation.resume(Result.success(partnerAd))
                    }

                    override fun onUnityAdsShowClick(placementId: String) {
                        PartnerLogController.log(DID_CLICK)
                        listener?.onPartnerAdClicked(partnerAd) ?: run {
                            PartnerLogController.log(
                                CUSTOM,
                                "Unable to fire onPartnerAdClicked for Unity Ads adapter. " +
                                        "Listener is null."
                            )
                        }
                    }

                    override fun onUnityAdsShowComplete(
                        placementId: String,
                        state: UnityAds.UnityAdsShowCompletionState
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
                                            "Listener is null."
                                )
                        }

                        PartnerLogController.log(DID_DISMISS)
                        listener?.onPartnerAdDismissed(partnerAd, null) ?: PartnerLogController.log(
                            CUSTOM,
                            "Unable to fire onPartnerAdClosed for Unity Ads adapter. " +
                                    "Listener is null."
                        )
                    }
                })

        }
    }

    /**
     * Check if the currently loaded Unity Ads ad is ready to be shown. This is only applicable to
     * fullscreen ads, as banner ads do not have a separate "show" mechanism.
     *
     * @param context The current [Context].
     * @param placement The Unity Ads placement associated with the current ad.
     *
     * @return True if the ad is ready to be shown, false otherwise.
     */
    private fun readyToShow(context: Context, placement: String): Boolean {
        return when {
            context !is Activity -> {
                PartnerLogController.log(SHOW_FAILED, "Context is not an Activity instance.")
                false
            }
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

        listeners.remove(partnerAd.request.chartboostPlacement)
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

        if (applies == true) {
            MetaData(context).apply {
                this["gdpr.consent"] = gdprConsentStatus == GdprConsentStatus.GDPR_CONSENT_GRANTED
                commit()
            }
        }
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
        privacyString: String
    ) {
        PartnerLogController.log(
            if (hasGrantedCcpaConsent) CCPA_CONSENT_GRANTED
            else CCPA_CONSENT_DENIED
        )

        val gdprMetaData = MetaData(context)
        gdprMetaData["privacy.consent"] = hasGrantedCcpaConsent
        gdprMetaData.commit()
    }

    /**
     * Notify Unity Ads of the COPPA subjectivity.
     *
     * @param context The current [Context].
     * @param isSubjectToCoppa True if the user is subject to COPPA, false otherwise.
     */
    override fun setUserSubjectToCoppa(context: Context, isSubjectToCoppa: Boolean) {
        PartnerLogController.log(
            if (isSubjectToCoppa) COPPA_SUBJECT
            else COPPA_NOT_SUBJECT
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
        mediationMetaData.setVersion(HeliumSdk.version)
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
    private fun getChartboostMediationError(error: Any) = when (error) {
        UnityAds.UnityAdsInitializationError.AD_BLOCKER_DETECTED -> ChartboostMediationError.CM_INITIALIZATION_FAILURE_AD_BLOCKER_DETECTED
        UnityAdsLoadError.NO_FILL -> ChartboostMediationError.CM_LOAD_FAILURE_NO_FILL
        UnityAdsLoadError.INITIALIZE_FAILED, UnityAdsShowError.NOT_INITIALIZED -> ChartboostMediationError.CM_INITIALIZATION_FAILURE_UNKNOWN
        UnityAdsShowError.NO_CONNECTION -> ChartboostMediationError.CM_NO_CONNECTIVITY
        UnityAdsLoadError.TIMEOUT -> ChartboostMediationError.CM_LOAD_FAILURE_TIMEOUT
        UnityAdsShowError.TIMEOUT -> ChartboostMediationError.CM_SHOW_FAILURE_TIMEOUT
        else -> ChartboostMediationError.CM_PARTNER_ERROR
    }
}
