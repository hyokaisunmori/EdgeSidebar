package com.example.edgesidebar

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val FILE = "sidebar_prefs"
    const val KEY_ALPHA = "alpha_percent"
    const val KEY_HANDLE_Y = "handle_y"
    const val KEY_APPS = "selected_apps"

    fun of(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun alphaPercent(c: Context): Int = of(c).getInt(KEY_ALPHA, 40)
    fun setAlphaPercent(c: Context, v: Int) = of(c).edit().putInt(KEY_ALPHA, v).apply()

    /** -1 = belum pernah digeser (otomatis di tengah layar). */
    fun handleY(c: Context): Int = of(c).getInt(KEY_HANDLE_Y, -1)
    fun setHandleY(c: Context, v: Int) = of(c).edit().putInt(KEY_HANDLE_Y, v).apply()

    fun apps(c: Context): Set<String> =
        of(c).getStringSet(KEY_APPS, emptySet())?.toSet() ?: emptySet()

    fun setApps(c: Context, s: Set<String>) =
        of(c).edit().putStringSet(KEY_APPS, HashSet(s)).apply()
}
