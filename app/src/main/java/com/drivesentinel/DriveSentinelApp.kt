package com.drivesentinel

import android.app.Application
import com.drivesentinel.di.AppContainer
import com.drivesentinel.service.DriveNotifications

class DriveSentinelApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        DriveNotifications.createChannel(this)
    }
}

val android.content.Context.appContainer: AppContainer
    get() = (applicationContext as DriveSentinelApp).container
