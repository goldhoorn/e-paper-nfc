package com.joshuatz.nfceinkwriter

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import com.joshuatz.nfceinkwriter.Constants.PreferenceKeys
import com.joshuatz.nfceinkwriter.Constants.Preference_File_Key

class Preferences {
    private var mActivity: Activity
    private var mAppContext: Context

    constructor(activity: Activity) {
        this.mActivity = activity
        this.mAppContext = activity.applicationContext
    }

    fun getPreferences(): SharedPreferences {
        return this.mAppContext.getSharedPreferences(Preference_File_Key, Context.MODE_PRIVATE)
    }

    fun getScreenSize(): String {
        val screenSize = this.getPreferences().getString(PreferenceKeys.DisplaySize, DefaultScreenSize)
        return screenSize ?: DefaultScreenSize
    }

    fun getScreenSizeEnum(): Int {
        val screenSize: String = this.getPreferences().getString(PreferenceKeys.DisplaySize, DefaultScreenSize)!!
        return (ScreenSizes.indexOf(screenSize) + 1)
    }

    fun getScreenSizePixels(): Pair<Int, Int> {
        val screenSize: String = this.getPreferences().getString(PreferenceKeys.DisplaySize, DefaultScreenSize)!!
        return ScreenSizesInPixels[screenSize]!!
    }

    fun getRenderSettings(): RenderSettings {
        val prefs = getPreferences()
        return RenderSettings(
            mode = RenderMode.fromKey(prefs.getString(PreferenceKeys.RenderMode, RenderMode.THRESHOLD.key)),
            invert = prefs.getBoolean(PreferenceKeys.Invert, false),
            threshold = prefs.getInt(PreferenceKeys.Threshold, 128),
            soften = prefs.getInt(PreferenceKeys.Soften, 2)
        )
    }

    fun setRenderMode(mode: RenderMode) {
        getPreferences().edit().putString(PreferenceKeys.RenderMode, mode.key).apply()
    }

    fun setInvert(invert: Boolean) {
        getPreferences().edit().putBoolean(PreferenceKeys.Invert, invert).apply()
    }

    fun setThreshold(threshold: Int) {
        getPreferences().edit().putInt(PreferenceKeys.Threshold, threshold.coerceIn(0, 255)).apply()
    }

    fun setSoften(soften: Int) {
        getPreferences().edit().putInt(PreferenceKeys.Soften, soften.coerceIn(0, ImageRenderer.MAX_SOFTEN)).apply()
    }

    fun showScreenSizePicker(callback: (String) -> Void?) {
        val alertBuilder = AlertDialog.Builder(this.mActivity)
        alertBuilder
            .setTitle("Pick Your Screen Size")
            .setItems(ScreenSizes) { _, which ->
                val selectedSize = ScreenSizes[which]
                with(this.getPreferences().edit()) {
                    putString(PreferenceKeys.DisplaySize, selectedSize)
                    apply()
                }
                callback(selectedSize)
            }
        alertBuilder.show()
    }
}