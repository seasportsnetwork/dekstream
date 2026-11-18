package com.dekstream.tv

import android.app.Application
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import com.dekstream.tv.data.Account
import com.dekstream.tv.data.EpgIndex
import com.dekstream.tv.data.Prefs
import com.dekstream.tv.data.Repo
import com.dekstream.tv.data.SportsRepo
import com.dekstream.tv.data.XtreamApi
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class DekStreamApp : Application() {

    lateinit var http: OkHttpClient
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var sports: SportsRepo
        private set
    lateinit var epg: EpgIndex
        private set

    /** Null until signed in. */
    var repo: Repo? = null
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        prefs = Prefs(this)
        sports = SportsRepo(http)
        epg = EpgIndex(http)
        prefs.account?.let { useAccount(it) }
    }

    fun useAccount(a: Account) {
        prefs.account = a
        repo = Repo(XtreamApi(http, a), prefs)
    }

    fun signOut() {
        prefs.clearAll()
        repo = null
    }

    val isTv: Boolean
        get() {
            val ui = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
            return ui.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
                packageManager.hasSystemFeature("android.software.leanback")
        }

    companion object {
        lateinit var instance: DekStreamApp
            private set
    }
}
