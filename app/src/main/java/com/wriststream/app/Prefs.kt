package com.wriststream.app

import android.content.Context
import android.content.SharedPreferences

/** 简单设置存取（接收端地址等） */
object Prefs {
    private const val NAME = "wriststream"
    private const val KEY_ENDPOINT = "endpoint"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getEndpoint(ctx: Context): String =
        sp(ctx).getString(KEY_ENDPOINT, "http://192.168.1.100:9025/data") ?: ""

    fun saveEndpoint(ctx: Context, url: String) {
        sp(ctx).edit().putString(KEY_ENDPOINT, url).apply()
    }
}
