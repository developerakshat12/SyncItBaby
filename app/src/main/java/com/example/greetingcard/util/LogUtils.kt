package com.example.greetingcard.util

import android.util.Log

object LogUtils {
    fun d(tag: String, msg: String) {
        try {
            Log.d(tag, msg)
        } catch (e: Throwable) {
            println("[$tag] $msg")
        }
    }

    fun w(tag: String, msg: String) {
        try {
            Log.w(tag, msg)
        } catch (e: Throwable) {
            println("[$tag] WARNING: $msg")
        }
    }

    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        try {
            Log.e(tag, msg, throwable)
        } catch (e: Throwable) {
            println("[$tag] ERROR: $msg ${throwable?.message ?: ""}")
        }
    }
}

