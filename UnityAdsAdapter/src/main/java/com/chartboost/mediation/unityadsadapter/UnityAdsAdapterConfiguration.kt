package com.chartboost.mediation.unityadsadapter

import com.chartboost.chartboostmediationsdk.domain.PartnerAdapterConfiguration
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController
import com.unity3d.ads.UnityAds

object UnityAdsAdapterConfiguration : PartnerAdapterConfiguration {
    /**
     * The partner name for internal uses.
     */
    override val partnerId = "unity"

    /**
     * The partner name for external uses.
     */
    override val partnerDisplayName = "Unity Ads"

    /**
     * The version of the partner SDK.
     */
    override val partnerSdkVersion = UnityAds.version

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
    override val adapterVersion = BuildConfig.CHARTBOOST_MEDIATION_UNITY_ADS_ADAPTER_VERSION

    /**
     * Flag that can optionally be set to enable Unity Ads debug mode.
     */
    var debugMode = UnityAds.debugMode
        set(value) {
            field = value
            UnityAds.debugMode = value
            PartnerLogController.log(
                PartnerLogController.PartnerAdapterEvents.CUSTOM,
                "Unity Ads debug mode is ${if (value) "enabled" else "disabled"}.",
            )
        }
}
