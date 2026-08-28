package com.trapezo.pos

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application entry point for Trapezo POS.
 * Holds a long-lived coroutine scope used by app-level services.
 */
class TrapezoApp : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: TrapezoApp
            private set
    }
}
