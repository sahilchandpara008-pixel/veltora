package com.veltora.app

import android.app.Application
import com.veltora.app.data.Attribution
import com.veltora.app.data.MetaEvents
import com.veltora.app.data.DebitSms
import com.veltora.app.data.UpiPay

class VeltoraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppState.init(this)
        Attribution.init(this)
        UpiPay.init(this)
        DebitSms.init(this)
        MetaEvents.init(this)       // Meta ads SDK, if the owner set it up in Admin → Settings
        Attribution.onAppStart()   // reads the Play Install Referrer once per install
    }
}
