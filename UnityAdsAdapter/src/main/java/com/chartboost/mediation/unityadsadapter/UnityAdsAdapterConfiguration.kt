package com.chartboost.mediation.unityadsadapter

import android.content.Context
import com.chartboost.chartboostmediationsdk.domain.PartnerAdapterConfiguration
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.CUSTOM
import com.chartboost.core.consent.ConsentValues
import com.unity3d.ads.UnityAds
import com.unity3d.ads.metadata.MetaData

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
                CUSTOM,
                "Unity Ads debug mode is ${if (value) "enabled" else "disabled"}.",
            )
        }

    /**
     * Use to manually set the GDPR consent status on the Unity Ads SDK.
     * This is generally unnecessary as the Mediation SDK will set the consent status automatically
     * based on the latest consent info.
     *
     * @param context The Android Context.
     * @param gdprConsentGiven True if GDPR consent has been given, false otherwise.
     */
    fun setGdprConsentOverride(context: Context, gdprConsentGiven: Boolean) {
        isGdprConsentOverridden = true
        // See https://docs.unity.com/ads/en/manual/GDPRCompliance
        MetaData(context).apply {
            this["gdpr.consent"] = gdprConsentGiven
            commit()
        }
        PartnerLogController.log(CUSTOM, "UnityAds GDPR consent status overridden to $gdprConsentGiven")
    }

    /**
     * Use to manually set the CCPA privacy consent status on the Unity Ads SDK.
     * This is generally unnecessary as the Mediation SDK will set the consent status automatically
     * based on the latest consent info.
     *
     * @param context The Android Context.
     * @param privacyConsentGiven True if privacy consent has been given, false otherwise.
     */
    fun setPrivacyConsentOverride(context: Context, privacyConsentGiven: Boolean) {
        isPrivacyConsentOverridden = true
        // See https://docs.unity.com/ads/en/manual/CCPACompliance
        MetaData(context).apply {
            this["privacy.consent"] = privacyConsentGiven
            commit()
        }
        PartnerLogController.log(CUSTOM, "UnityAds privacy consent status overridden to $privacyConsentGiven")
    }

    /**
     * Whether GDPR consent has been overridden by the publisher.
     */
    internal var isGdprConsentOverridden = false

    /**
     * Whether privacy consent has been overridden by the publisher.
     */
    internal var isPrivacyConsentOverridden = false
}
