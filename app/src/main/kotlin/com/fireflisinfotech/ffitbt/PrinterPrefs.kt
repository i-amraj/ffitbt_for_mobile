package com.fireflisinfotech.ffitbt

import android.content.SharedPreferences

object PrinterPrefs {
    private const val KEY_MAC  = "last_mac"
    private const val KEY_NAME = "last_name"

    fun savePrinter(prefs: SharedPreferences, name: String, mac: String) {
        prefs.edit().putString(KEY_MAC, mac).putString(KEY_NAME, name).apply()
    }

    fun getSavedMac(prefs: SharedPreferences)  = prefs.getString(KEY_MAC, "") ?: ""
    fun getSavedName(prefs: SharedPreferences) = prefs.getString(KEY_NAME, "") ?: ""
}
