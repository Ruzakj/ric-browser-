package com.ruzakj.ricbrowser

import android.app.Application
import io.github.edsuns.adfilter.AdFilter

class RicBrowserApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AdFilter.create(this)
    }
}
