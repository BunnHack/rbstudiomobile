package com.example.auth

import android.content.Context

class RobloxAuthStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getRoblosecurityCookie(): String = prefs.getString(KEY_ROBLOSECURITY, "").orEmpty()

    fun saveRoblosecurityCookie(cookie: String) {
        val normalized = normalizeRoblosecurityCookie(cookie)
        prefs.edit().putString(KEY_ROBLOSECURITY, normalized).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_ROBLOSECURITY).apply()
    }

    companion object {
        fun normalizeRoblosecurityCookie(input: String): String {
            val trimmed = input.trim()
                .removePrefix("Cookie:")
                .trim()
            return when {
                ".ROBLOSECURITY=" in trimmed -> trimmed.substringAfter(".ROBLOSECURITY=").substringBefore(";").trim()
                trimmed.startsWith(".ROBLOSECURITY=", ignoreCase = true) -> trimmed.substringAfter("=").substringBefore(";").trim()
                else -> trimmed.substringBefore(";").trim()
            }
        }

        private const val PREFS_NAME = "roblox_auth"
        private const val KEY_ROBLOSECURITY = "roblosecurity"
    }
}
