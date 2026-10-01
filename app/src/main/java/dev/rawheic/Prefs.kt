package dev.rawheic

import android.content.Context

object Prefs {
    private const val FILE = "rawheic_prefs"

    fun attachOnBoot(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean("attach_on_boot", true)

    fun setAttachOnBoot(context: Context, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean("attach_on_boot", value).apply()
    }

    fun keepDng(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean("keep_dng", false)

    fun setKeepDng(context: Context, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean("keep_dng", value).apply()
    }
}
