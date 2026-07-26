package com.dime.app

import android.content.Context
import android.content.SharedPreferences

object SessionManager {
    private const val PREFS = "dime_prefs"
    private const val KEY_TOKEN = "token"

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
}
