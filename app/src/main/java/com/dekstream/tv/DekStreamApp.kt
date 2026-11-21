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
import com.dekstream.tv.data.ProfileSettings
import com.dekstream.tv.ui.Appearance
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
class Session(val profile: Profile, val source: Source, val prefs: Prefs, val repo: Repo, val settings: ProfileSettings) {
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
    lateinit var reminders: com.dekstream.tv.data.Reminders
        private set
    lateinit var health: com.dekstream.tv.data.StreamHealth
        private set

    /** Channel to open once signed in (from a reminder notification). */
    var pendingPlayStream by mutableStateOf<Int?>(null)
    /** Debug test hook: tab to open (see MainActivity.handleTestIntent). */
    var pendingTab by mutableStateOf<com.dekstream.tv.ui.Tab?>(null)
    var pendingTestGame by mutableStateOf<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Current session; Compose observes it, so switching profile or playlist redraws the app. */
    var session by mutableStateOf<Session?>(null)
        private set

    /** Profile picked but it has no playlist yet. */
    var pendingProfile by mutableStateOf<Profile?>(null)
        private set

    /** Current sports heads-up (shown over every screen). */
    var alert by mutableStateOf<com.dekstream.tv.ui.SportsAlert?>(null)
    /** Set by the player when OK is pressed while an alert is showing. */
    var alertWatchRequested by mutableStateOf(false)

    /** Set while the full-screen player is open (for auto picture-in-picture). */
    var playerActive by mutableStateOf(false)
    /** True while the activity is shown as a picture-in-picture window. */
    var inPip by mutableStateOf(false)

    val repo: Repo? get() = session?.repo
    val prefs: Prefs get() = session?.prefs ?: error("No active profile")

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Keep the last crash so it can be shown in Settings > About (and sent to us).
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                java.io.File(filesDir, "last_crash.txt").writeText(
                    "DekTV ${com.dekstream.tv.BuildConfig.VERSION_NAME} · ${java.util.Date()}\n" + android.util.Log.getStackTraceString(e))
            }
            previous?.uncaughtException(t, e)
        }
        http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        store = Store(this)
        sports = SportsRepo(http)
        epg = EpgIndex(http, java.io.File(cacheDir, "guide").apply { mkdirs() })
        reminders = com.dekstream.tv.data.Reminders(this)
        health = com.dekstream.tv.data.StreamHealth(this)
        com.dekstream.tv.data.Reminders.ensureChannel(this)
        // Go straight in when there's only one profile.
        store.profiles.value.singleOrNull()?.let { start(it) }
    }

    fun provider(s: Source): Provider = Provider.create(s, http, epg, filesDir)

    /** Opens a profile with its playlist (or asks for one if it has none). */
    fun start(p: Profile) {
        store.lastProfileId = p.id
        applyAppearance(ProfileSettings(this, p.id))
        ProfileSettings(this, p.id).let { ps ->
            com.dekstream.tv.data.ContentFilter.showAdult = ps.showAdult && !p.kids
            // Home is Live TV first: movies and series are opt-in (Settings > Home screen).
            if (!ps.homeLiveFirst) { ps.homeHidden = ps.homeHidden + com.dekstream.tv.data.HomeLayout.liveOnlyHidden; ps.homeLiveFirst = true }
            com.dekstream.tv.data.HomeLayout.hidden = ps.homeHidden
        }
        val visible = store.sources.value.filter { p.canSee(it.id) }
        val src = store.source(p.sourceId)?.takeIf { p.canSee(it.id) } ?: visible.firstOrNull()
        if (src == null) { session = null; pendingProfile = p; return }
        val profile = if (p.sourceId != src.id) p.copy(sourceId = src.id).also { store.saveProfile(it) } else p
        val prefs = Prefs(this, profile.id, src.id)
        val provider = provider(src)
        pendingProfile = null
        session = Session(profile, src, prefs, Repo(provider, prefs, epg), ProfileSettings(this, profile.id))
        // Warm up the full guide in the background so Sports and Search are quick.
        val warm = session?.repo
        scope.launch { kotlinx.coroutines.delay(4_000); runCatching { warm?.guideIndex() } }
        // Xtream catch-up needs the panel's timezone.
        scope.launch { runCatching { provider.verify()?.timezone?.let { prefs.serverTimezone = it } } }
    }

    fun applyAppearance(s: ProfileSettings) {
        Appearance.accentIdx = s.accentIdx
        Appearance.paletteIdx = s.paletteIdx
        Appearance.selectionIdx = s.selectionIdx
        Appearance.guideBgIdx = s.guideBgIdx
        Appearance.panelAlpha = s.panelAlpha
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
