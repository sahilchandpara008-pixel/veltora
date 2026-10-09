package com.streams.app

import android.app.Application
import com.streams.app.data.Attribution
import com.streams.app.data.UpiPay

class StreamsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppState.init(this)
        Attribution.init(this)
        UpiPay.init(this)
        Attribution.onAppStart()   // reads the Play Install Referrer once per install
    }
}
