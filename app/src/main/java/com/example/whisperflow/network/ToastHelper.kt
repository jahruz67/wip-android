package com.example.whisperflow.network

import android.content.Context
import android.widget.Toast

object ToastHelper {
    @Volatile
    private var currentToast: Toast? = null

    fun showToast(context: Context, message: String, duration: Int = Toast.LENGTH_SHORT) {
        synchronized(this) {
            currentToast?.cancel()
            currentToast = Toast.makeText(context.applicationContext, message, duration).also {
                it.show()
            }
        }
    }
}
