package com.dime.app

import android.content.Context
import android.content.SharedPreferences

object SessionManager {
    private const val PREFS = "dime_prefs"
    private const val KEY_TOKEN = "token"
    private const val KEY_SERVER = "server"

    // Default server base (sin /api)
    private const val DEFAULT_SERVER = "https://inspection-sister-wondering-ask.trycloudflare.com"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveToken(ctx: Context, token: String) {
        prefs(ctx).edit().putString(KEY_TOKEN, token).apply()
    }

    fun getToken(ctx: Context): String {
        return prefs(ctx).getString(KEY_TOKEN, "") ?: ""
    }

    fun clearToken(ctx: Context) {
        prefs(ctx).edit().remove(KEY_TOKEN).apply()
    }

    fun saveServerUrl(ctx: Context, serverBase: String) {
        prefs(ctx).edit().putString(KEY_SERVER, serverBase).apply()
    }

    fun getServerUrl(ctx: Context): String {
        return prefs(ctx).getString(KEY_SERVER, DEFAULT_SERVER) ?: DEFAULT_SERVER
    }

    fun clearServerUrl(ctx: Context) {
        prefs(ctx).edit().remove(KEY_SERVER).apply()
    }

    fun clearAll(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }
}
