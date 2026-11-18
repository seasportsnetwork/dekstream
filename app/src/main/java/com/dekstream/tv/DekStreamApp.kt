package com.dekstream.tv

import android.app.Application
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dekstream.tv.data.EpgIndex
import com.dekstream.tv.data.Prefs
import com.dekstream.tv.data.Profile
import com.dekstream.tv.data.Provider
import com.dekstream.tv.data.Repo
import com.dekstream.tv.data.Source
import com.dekstream.tv.data.SportsRepo
import com.dekstream.tv.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Who is watching and from which playlist. */
class Session(val profile: Profile, val source: Source, val prefs: Prefs, val repo: Repo) {
    val key get() = profile.id + ":" + source.id
}

class DekStreamApp : Application() {

    lateinit var http: OkHttpClient
        private set
    lateinit var store: Store
        private set
    lateinit var sports: SportsRepo
        private set
    lateinit var epg: EpgIndex
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Current session; Compose observes it, so switching profile or playlist redraws the app. */
    var session by mutableStateOf<Session?>(null)
        private set

    /** Profile picked but it has no playlist yet. */
    var pendingProfile by mutableStateOf<Profile?>(null)
        private set

    val repo: Repo? get() = session?.repo
    val prefs: Prefs get() = session?.prefs ?: error("No active profile")

    override fun onCreate() {
        super.onCreate()
        instance = this
        http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        store = Store(this)
        sports = SportsRepo(http)
        epg = EpgIndex(http)
        // Go straight in when there's only one profile.
        store.profiles.value.singleOrNull()?.let { start(it) }
    }

    fun provider(s: Source): Provider = Provider.create(s, http, epg, filesDir)

    /** Opens a profile with its playlist (or asks for one if it has none). */
    fun start(p: Profile) {
        store.lastProfileId = p.id
        val src = store.source(p.sourceId) ?: store.sources.value.firstOrNull()
        if (src == null) { session = null; pendingProfile = p; return }
        val profile = if (p.sourceId != src.id) p.copy(sourceId = src.id).also { store.saveProfile(it) } else p
        val prefs = Prefs(this, profile.id, src.id)
        val provider = provider(src)
        pendingProfile = null
        session = Session(profile, src, prefs, Repo(provider, prefs, epg))
        // Xtream catch-up needs the panel's timezone.
        scope.launch { runCatching { provider.verify()?.timezone?.let { prefs.serverTimezone = it } } }
    }

    /** Switch the current profile to another playlist. */
    fun useSource(s: Source) {
        val p = session?.profile ?: pendingProfile ?: return
        val updated = p.copy(sourceId = s.id)
        store.saveProfile(updated)
        start(updated)
    }

    /** Back to the "Who's watching?" screen. */
    fun switchProfile() {
        session = null
        pendingProfile = null
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
