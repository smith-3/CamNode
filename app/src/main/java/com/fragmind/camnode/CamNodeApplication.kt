package com.fragmind.camnode

import android.app.Application
import android.content.Context
import com.fragmind.camnode.core.data.locale.AppLanguage
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class CamNodeApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }
}
