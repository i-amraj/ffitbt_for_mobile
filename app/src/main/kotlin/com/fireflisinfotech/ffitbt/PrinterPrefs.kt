package com.fireflisinfotech.ffitbt

import android.content.SharedPreferences

object PrinterPrefs {
    private const val KEY_MAC  = "last_mac"
    private const val KEY_NAME = "last_name"
    
    private const val KEY_MODE = "connection_mode" // "bluetooth" or "wifi"
    private const val KEY_WIFI_IP = "wifi_ip"
    private const val KEY_WIFI_PORT = "wifi_port"
    private const val KEY_WIFI_SSID = "wifi_ssid"
    private const val KEY_WIFI_SUBNET = "wifi_subnet"

    fun savePrinter(prefs: SharedPreferences, name: String, mac: String) {
        prefs.edit()
            .putString(KEY_MAC, mac)
            .putString(KEY_NAME, name)
            .putString(KEY_MODE, "bluetooth")
            .apply()
    }

    fun saveWifiSettings(prefs: SharedPreferences, ip: String, port: Int, ssid: String, subnet: String) {
        prefs.edit()
            .putString(KEY_WIFI_IP, ip)
            .putInt(KEY_WIFI_PORT, port)
            .putString(KEY_WIFI_SSID, ssid)
            .putString(KEY_WIFI_SUBNET, subnet)
            .putString(KEY_MODE, "wifi")
            .apply()
    }

    fun setConnectionMode(prefs: SharedPreferences, mode: String) {
        prefs.edit().putString(KEY_MODE, mode).apply()
    }

    fun getSavedMac(prefs: SharedPreferences)  = prefs.getString(KEY_MAC, "") ?: ""
    fun getSavedName(prefs: SharedPreferences) = prefs.getString(KEY_NAME, "") ?: ""
    
    fun getSavedMode(prefs: SharedPreferences) = prefs.getString(KEY_MODE, "bluetooth") ?: "bluetooth"
    fun getSavedIp(prefs: SharedPreferences) = prefs.getString(KEY_WIFI_IP, "192.168.1.100") ?: "192.168.1.100"
    fun getSavedPort(prefs: SharedPreferences) = prefs.getInt(KEY_WIFI_PORT, 9100)
    fun getSavedSsid(prefs: SharedPreferences) = prefs.getString(KEY_WIFI_SSID, "") ?: ""
    fun getSavedSubnet(prefs: SharedPreferences) = prefs.getString(KEY_WIFI_SUBNET, "255.255.255.0") ?: "255.255.255.0"
}
