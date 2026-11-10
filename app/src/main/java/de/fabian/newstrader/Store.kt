package de.fabian.newstrader

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Einfacher persistenter Zustand (SharedPreferences + JSON), beobachtbar für die UI. */
object Store {
    private var prefs: SharedPreferences? = null
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state

    @Synchronized
    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("newstrader", Context.MODE_PRIVATE)
        prefs = p
        _state.value = load(p)
    }

    @Synchronized
    fun update(f: (AppState) -> AppState) {
        val n = f(_state.value)
        _state.value = n
        prefs?.let { save(it, n) }
    }

    private fun load(p: SharedPreferences): AppState {
        fun arr(key: String) = try {
            JSONArray(p.getString(key, "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        val settings = try {
            Settings.fromJson(JSONObject(p.getString("settings", "{}") ?: "{}"))
        } catch (_: Exception) {
            Settings()
        }
        return AppState(
            signals = arr("signals").objects { Signal.fromJson(it) },
            sentiments = arr("sentiments").objects { Sentiment.fromJson(it) }.associateBy { it.assetId },
            calendar = arr("calendar").objects { CalEvent.fromJson(it) },
            news = arr("news").objects { NewsItem.fromJson(it) },
            earnings = arr("earnings").objects { Earning.fromJson(it) },
            settings = settings,
            lastRun = p.getLong("lastRun", 0),
            lastError = p.getString("lastError", "") ?: "",
            sentimentTs = p.getLong("sentimentTs", 0),
            calendarTs = p.getLong("calendarTs", 0),
            log = arr("log").strings(),
            decided = arr("decided").strings().toSet(),
        )
    }

    private fun save(p: SharedPreferences, s: AppState) {
        p.edit()
            .putString("signals", JSONArray(s.signals.map { it.toJson() }).toString())
            .putString("sentiments", JSONArray(s.sentiments.values.map { it.toJson() }).toString())
            .putString("calendar", JSONArray(s.calendar.map { it.toJson() }).toString())
            .putString("news", JSONArray(s.news.map { it.toJson() }).toString())
            .putString("earnings", JSONArray(s.earnings.map { it.toJson() }).toString())
            .putString("settings", s.settings.toJson().toString())
            .putLong("lastRun", s.lastRun)
            .putString("lastError", s.lastError)
            .putLong("sentimentTs", s.sentimentTs)
            .putLong("calendarTs", s.calendarTs)
            .putString("log", JSONArray(s.log).toString())
            .putString("decided", JSONArray(s.decided.toList()).toString())
            .apply()
    }
}
