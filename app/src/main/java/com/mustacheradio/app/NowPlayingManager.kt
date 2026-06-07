package com.mustacheradio.app

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Polls each radio station's website/API to retrieve what's currently on air.
 *
 * Each station uses its own fetch strategy because Israeli radio stations do not
 * consistently embed programme information in ICY stream metadata:
 *
 *  - eco99fm  : public schedule JSON API + time-matching
 *  - 103FM    : live-player page HTML (ASP.NET, server-rendered, small page)
 *  - KAN (88FM/Bet/Gimel) : KAN Umbraco/CMS API with channelId
 *  - Galatz/Galgalatz     : GLZ schedule API
 *  - 100FM / 102FM        : HTML scraping (WordPress / Next.js)
 */
class NowPlayingManager {

    interface Listener {
        fun onNowPlayingUpdated(stationId: String, text: String?)
    }

    companion object {
        private const val TAG           = "NowPlayingManager"
        private const val POLL_INTERVAL = 30L
        private const val TIMEOUT_MS    = 10_000

        // KAN channel IDs as used in the page's inline liveSchedule JSON (data-channel-id attribute).
        // Player IDs (data-player-id) are used for the live-item-info DOM element.
        // Page URLs are the live radio pages that embed the schedule inline.
        private const val KAN_88_CH  = 4;  private const val KAN_88_PL  = 4504; private const val KAN_88_URL  = "https://www.kan.org.il/content/kan/kan-88/"
        private const val KAN_BET_CH = 8;  private const val KAN_BET_PL = 4483; private const val KAN_BET_URL = "https://www.kan.org.il/content/kan/kan-b/"
        private const val KAN_GIM_CH = 9;  private const val KAN_GIM_PL = 4490; private const val KAN_GIM_URL = "https://www.kan.org.il/content/kan/kan-gimel/"

        // GLZ station page URLs and channel IDs (from page data-glzid / data-glglzid attributes)
        private const val GLZ_GAL_ID   = 1051; private const val GLZ_GAL_URL   = "https://glz.co.il/%D7%92%D7%9C%D7%A6"
        private const val GLZ_GALG_ID  = 1920; private const val GLZ_GALG_URL  = "https://glz.co.il/%D7%92%D7%9C%D7%92%D7%9C%D7%A6"

        /**
         * Process-wide cache shared with RadioPlaybackService so it can update the
         * MediaSession subtitle (= lock screen / notification) without IPC.
         */
        val sharedCache = ConcurrentHashMap<String, String>()

        /**
         * Listeners registered by other components (e.g. RadioPlaybackService) that need
         * to be notified when the now-playing text changes.
         * Key = arbitrary tag, value = (stationId, text) callback invoked on the main thread.
         */
        val serviceListeners = ConcurrentHashMap<String, (String, String) -> Unit>()
    }

    /** Call from Activity.onCreate / onDestroy so WebView-based fetchers have a valid context. */
    private var activityRef: WeakReference<Activity>? = null
    fun setActivity(a: Activity?) { activityRef = a?.let { WeakReference(it) } }

    private val executor      = Executors.newScheduledThreadPool(4)
    private val mainHandler   = Handler(Looper.getMainLooper())
    private val cache         = ConcurrentHashMap<String, String>()
    private var listener: Listener? = null
    private var scheduledFuture: ScheduledFuture<*>? = null

    fun setListener(l: Listener?) { listener = l }
    fun isRunning()               = scheduledFuture != null
    fun getNowPlaying(id: String) = cache[id]

    /** Begin polling. No-op if already running. */
    fun start() {
        if (scheduledFuture != null) return
        fetchAll()
        scheduledFuture = executor.scheduleAtFixedRate(
            ::fetchAll, POLL_INTERVAL, POLL_INTERVAL, TimeUnit.SECONDS
        )
        Log.d(TAG, "Started polling")
    }

    fun stop() {
        scheduledFuture?.cancel(false)
        scheduledFuture = null
        Log.d(TAG, "Stopped polling")
    }

    fun release() {
        stop()
        executor.shutdownNow()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Polling
    // ─────────────────────────────────────────────────────────────────────────

    private fun fetchAll() = Stations.ALL.forEach { s ->
        executor.execute { fetchStation(s) }
    }

    private fun fetchStation(station: RadioStation) {
        val text = try {
            when (station.id) {
                "eco99fm"    -> fetchEco99()
                "103fm"      -> fetch103FM()
                "88fm"       -> fetchKan(KAN_88_CH,  KAN_88_PL,  KAN_88_URL)
                "kanbet"     -> fetchKan(KAN_BET_CH, KAN_BET_PL, KAN_BET_URL)
                "kangimel"   -> fetchKan(KAN_GIM_CH, KAN_GIM_PL, KAN_GIM_URL)
                "galatz"     -> fetchGlz(GLZ_GAL_ID,  GLZ_GAL_URL)
                "galgalatz"  -> fetchGlz(GLZ_GALG_ID, GLZ_GALG_URL)
                "radius100fm"-> fetch100FM()
                "102fm"      -> fetch102FM()
                else         -> null
            }
        } catch (e: Exception) {
            Log.d(TAG, "[${station.id}] error: ${e.message}")
            null
        }
        Log.d(TAG, "[${station.id}] => $text")

        val prev = cache[station.id]
        if (text != null && text != prev) {
            cache[station.id] = text
            sharedCache[station.id] = text
            mainHandler.post {
                listener?.onNowPlayingUpdated(station.id, text)
                serviceListeners.values.forEach { it(station.id, text) }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // eco99 — public schedule API + time-matching
    // ─────────────────────────────────────────────────────────────────────────

    private fun fetchEco99(): String? {
        val body = httpGet("https://eco99fm.maariv.co.il/api/v1/public/radio-single-broadcast/all")
            ?: return null
        val items = JSONObject(body).optJSONArray("items") ?: return null
        val (day, mins) = israelDayAndMinutes()

        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val dayRange  = item.optString("day_range", "")
            val hourRange = item.optString("hour_range", "")
            if (matchesDay(dayRange, day) && matchesHour(hourRange, mins)) {
                return item.optString("program_name", "").ifBlank { null }
            }
        }
        return null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 103FM — live-player page (server-rendered, no JavaScript required)
    // ─────────────────────────────────────────────────────────────────────────

    private fun fetch103FM(): String? {
        val html = httpGet("https://103fm.maariv.co.il/include/OnLineView.aspx") ?: return null
        // "play_now_info" may contain "Show name | schedule" — take the part before '|'
        val infoText = Regex("""class="play_now_info"[^>]*>([^<]+)""")
            .find(html)?.groupValues?.get(1)?.trim()
        if (infoText != null && '|' in infoText) {
            val showName = infoText.substringBefore('|').trim()
            if (showName.isNotEmpty()) return showName
        }
        // When no "|", play_now_info is just a schedule string — use the host/show title instead
        return Regex("""class="play_now_title"[^>]*>([^<]+)""")
            .find(html)?.groupValues?.get(1)?.trim()?.ifBlank { null }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // KAN (88FM / Kan Bet / Kan Gimel) — WebView-based fetcher
    //
    // The KAN website embeds the full day schedule as inline JSON in the HTML,
    // with an "IsPlaying": true flag on the current show.  Cloudflare blocks
    // all non-browser HTTP clients (error 1020), so we use Android WebView
    // which uses Chrome's TLS stack and can solve the JS challenge.
    // ─────────────────────────────────────────────────────────────────────────

    private fun fetchKan(channelId: Int, playerId: Int, pageUrl: String): String? =
        fetchKanViaWebView(pageUrl, channelId, playerId)

    private fun fetchKanViaWebView(pageUrl: String, channelId: Int, playerId: Int): String? {
        val activity = activityRef?.get() ?: run {
            Log.d(TAG, "fetchKan[$channelId]: no Activity context")
            return null
        }
        val latch  = CountDownLatch(1)
        var result: String? = null
        var done   = false

        mainHandler.post {
            Log.d(TAG, "fetchKan[$channelId]: loading $pageUrl via WebView")
            val wv = WebView(activity)
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = true  // prevent KAN's audio player from stealing focus
            }
            // Attach to the activity's decor view so the WebView is in a live window and
            // JavaScript (including evaluateJavascript) executes normally.
            val container = android.widget.FrameLayout(activity).also { it.visibility = android.view.View.INVISIBLE }
            container.addView(wv, android.widget.FrameLayout.LayoutParams(1, 1))
            activity.addContentView(container, android.view.ViewGroup.LayoutParams(1, 1))
            wv.resumeTimers()
            wv.onResume()

            fun cleanup(view: WebView) {
                view.stopLoading()
                view.onPause()
                // Remove container (which holds the WebView) from the decor view
                val cont = view.parent as? android.view.ViewGroup
                (cont?.parent as? android.view.ViewGroup)?.removeView(cont)
                view.destroy()
            }

            wv.webViewClient = object : WebViewClient() {

                override fun onPageFinished(view: WebView, url: String) {
                    Log.d(TAG, "fetchKan[$channelId]: onPageFinished url=$url done=$done")
                    if (done) return
                    // Wait for KAN's liveSchedule JS and ACRCloud to populate
                    view.postDelayed({
                        if (done) return@postDelayed
                        // Mute any audio/video on the page before extracting data
                        view.evaluateJavascript(
                            "document.querySelectorAll('audio,video').forEach(function(m){m.muted=true;m.pause();});",
                            null
                        )
                        done = true
                        val js = """
                            (function() {
                                try {
                                    if (typeof liveSchedule !== 'undefined') {
                                        for (var i = 0; i < liveSchedule.length; i++) {
                                            var ch = liveSchedule[i];
                                            if (ch.ChannelId == $channelId) {
                                                var items = ch.SchedulelItems || [];
                                                for (var j = 0; j < items.length; j++) {
                                                    if (items[j].IsPlaying === true) {
                                                        var prog = items[j].ProgramName || items[j].EpisodeName || '';
                                                        var aEl = document.querySelector('.now-playing[data-channel-id="$channelId"] .artist-name');
                                                        var sEl = document.querySelector('.now-playing[data-channel-id="$channelId"] .song-name');
                                                        // ACRCloud puts a trailing " -" separator in the artist span — strip it
                                                        var artist = aEl ? aEl.innerText.trim().replace(/\s*[-\u2013\u2014]+\s*$/, '') : '';
                                                        // Song span may start with zero-width characters — strip them
                                                        var song   = sEl ? sEl.innerText.replace(/[\u200B\u200C\u200D\u2060\uFEFF]/g, '').trim() : '';
                                                        if (artist && song) return prog ? prog + ' \u2013 ' + artist + ' \u2013 ' + song : artist + ' \u2013 ' + song;
                                                        return prog;
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    var el = document.getElementById('live-item-info-$playerId');
                                    if (el) return el.innerText.trim();
                                    return 'DBG:liveSchedule=' + (typeof liveSchedule) + ' title=' + document.title;
                                } catch(e) { return 'ERR:' + e; }
                            })()
                        """.trimIndent()
                        Log.d(TAG, "fetchKan[$channelId]: evaluating JS")
                        view.evaluateJavascript(js) { value ->
                            Log.d(TAG, "fetchKan[$channelId]: JS result=$value")
                            val v = value?.trim('"')?.replace("\\n", " ")?.trim() ?: ""
                            result = if (v.startsWith("DBG:") || v.startsWith("ERR:") || v.isBlank()) null else v
                            latch.countDown()
                            cleanup(view)
                        }
                    }, 4000) // 4 s: Cloudflare challenge resolution + ACRCloud fetch
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                    Log.d(TAG, "fetchKan[$channelId]: onReceivedError main=${request.isForMainFrame} url=${request.url}")
                    if (request.isForMainFrame && !done) {
                        done = true; latch.countDown(); cleanup(view)
                    }
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: android.webkit.WebResourceResponse) {
                    Log.d(TAG, "fetchKan[$channelId]: HTTP ${errorResponse.statusCode} for ${request.url} main=${request.isForMainFrame}")
                    // Don't abort on HTTP errors — Cloudflare challenge pages come as 200 with the challenge HTML;
                    // the actual 403 blocks arrive as onReceivedError, not here.
                }
            }
            wv.loadUrl(pageUrl)
        }

        return if (latch.await(25, TimeUnit.SECONDS)) result else {
            Log.d(TAG, "fetchKan[$channelId]: TIMEOUT after 25s")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Galatz / Galgalatz — WebView-based fetcher
    //
    // glz.co.il is behind Incapsula which blocks all non-browser HTTP clients.
    // We load the station's homepage in an invisible WebView so Incapsula issues
    // its clearance cookie, then use fetch() from within the page context to call
    // the GlzSrv schedule API (which now succeeds because the cookie is present).
    // ─────────────────────────────────────────────────────────────────────────

    private fun fetchGlz(channelId: Int, pageUrl: String): String? =
        fetchGlzViaWebView(pageUrl, channelId)

    private fun fetchGlzViaWebView(pageUrl: String, channelId: Int): String? {
        val activity = activityRef?.get() ?: run {
            Log.d(TAG, "fetchGlz[$channelId]: no Activity context")
            return null
        }
        val latch  = CountDownLatch(1)
        var result: String? = null
        var done   = false

        mainHandler.post {
            Log.d(TAG, "fetchGlz[$channelId]: loading $pageUrl via WebView")
            val wv = WebView(activity)
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = true
            }

            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Jerusalem") }
                .format(java.util.Date())

            // JavascriptInterface so JavaScript can return the async fetch result to Kotlin
            wv.addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun onResult(text: String) {
                    if (done) return
                    done = true
                    val v = text.trim()
                    Log.d(TAG, "fetchGlz[$channelId]: JS result=$v")
                    result = if (v.startsWith("ERR:") || v.startsWith("DBG:") || v.isBlank()) null else v
                    latch.countDown()
                    mainHandler.post {
                        wv.stopLoading()
                        val cont = wv.parent as? android.view.ViewGroup
                        (cont?.parent as? android.view.ViewGroup)?.removeView(cont)
                        wv.destroy()
                    }
                }
            }, "Android")

            val container = android.widget.FrameLayout(activity).also { it.visibility = android.view.View.INVISIBLE }
            container.addView(wv, android.widget.FrameLayout.LayoutParams(1, 1))
            activity.addContentView(container, android.view.ViewGroup.LayoutParams(1, 1))
            wv.resumeTimers()
            wv.onResume()

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    Log.d(TAG, "fetchGlz[$channelId]: onPageFinished url=$url done=$done")
                    if (done) return
                    // Wait for Incapsula JS challenge to complete and Angular to initialize
                    view.postDelayed({
                        if (done) return@postDelayed
                        // Mute any auto-playing GLZ audio player
                        view.evaluateJavascript(
                            "document.querySelectorAll('audio,video').forEach(function(m){m.muted=true;m.pause();});",
                            null
                        )
                        // Extract current program from the Angular-rendered DOM.
                        // The GLZ /GlzSrv/ API has separate Incapsula protection, so we
                        // read the already-rendered page DOM instead.
                        // NOTE: result MUST be delivered via Android.onResult() — not via
                        // the evaluateJavascript callback — because the call is async.
                        val js = """
                            (function() {
                                try {
                                    // 1. Music stations (Galgalatz): .current-song shows what's playing now.
                                    //    Use innerText only for leaf/short nodes — Galatz may have a .current-song
                                    //    container that spans a large page section (skip those).
                                    var songEl = document.querySelector('.current-song');
                                    if (songEl) {
                                        var song = songEl.innerText.trim();
                                        if (song && song.length > 1 && song.length < 80 && song.indexOf('\n') < 0) {
                                            Android.onResult(song); return;
                                        }
                                    }

                                    var bodyText = document.body.innerText.replace(/\s+/g, ' ');

                                    // 2. "Playing now:" label (Galgalatz fallback if .current-song not found)
                                    var nowMatch = bodyText.match(/מתנגן כעת:\s*([^\n]{3,60})/);
                                    if (nowMatch) {
                                        var t = nowMatch[1].split(/  /)[0].trim();
                                        if (t && t.length > 1) { Android.onResult(t); return; }
                                    }

                                    // 3. Talk radio (Galatz): show name sits between "לחצו כאן" and "חי" (= live)
                                    //    Body pattern: "...לא הצלחתם להאזין? לחצו כאן <SHOW_NAME> חי ..."
                                    var liveMatch = bodyText.match(/לחצו כאן\s+([^\d\n]{3,50}?)\s+חי/);
                                    if (liveMatch) {
                                        var name = liveMatch[1].trim();
                                        if (name && name.length > 1) { Android.onResult(name); return; }
                                    }

                                    Android.onResult('DBG:' + bodyText.substring(0, 300));
                                } catch(e) { Android.onResult('ERR:' + e.toString()); }
                            })()
                        """.trimIndent()
                        Log.d(TAG, "fetchGlz[$channelId]: evaluating JS")
                        view.evaluateJavascript(js, null)  // result delivered via Android.onResult()
                    }, 8000) // 8 s: Incapsula challenge + Angular bootstrap + data render
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                    Log.d(TAG, "fetchGlz[$channelId]: onReceivedError main=${request.isForMainFrame}")
                    if (request.isForMainFrame && !done) {
                        done = true; latch.countDown()
                        val cont = view.parent as? android.view.ViewGroup
                        (cont?.parent as? android.view.ViewGroup)?.removeView(cont)
                        view.destroy()
                    }
                }
            }
            wv.loadUrl(pageUrl)
        }

        return if (latch.await(60, TimeUnit.SECONDS)) result else {
            Log.d(TAG, "fetchGlz[$channelId]: TIMEOUT after 60s")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 100FM — WordPress homepage HTML scraping
    // The homepage schedule widget lists the current program first inside <h4>.
    // ─────────────────────────────────────────────────────────────────────────

    private fun fetch100FM(): String? {
        val html = httpGet("https://www.100fm.co.il/") ?: return null
        // First <h4> inside a /program/ link = current on-air show
        return Regex("""href="https?://www\.100fm\.co\.il/program/[^"]+">[\s\S]{0,200}?<h4>\s*([^<]+)\s*</h4>""")
            .find(html)?.groupValues?.get(1)?.trim()?.ifBlank { null }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 102FM — schedule from homepage API proxy (time-matched)
    // /api/apiProxy returns all shows with Hebrew day/time slots identical
    // in format to eco99, so the same matchesDay/matchesHour helpers work.
    // ─────────────────────────────────────────────────────────────────────────

    private fun fetch102FM(): String? {
        val body = httpGet("https://www.102fm.co.il/api/apiProxy") ?: return null
        val sections = JSONObject(body).optJSONObject("data")?.optJSONArray("section") ?: return null
        val (day, mins) = israelDayAndMinutes()

        for (i in 0 until sections.length()) {
            val section = sections.optJSONObject(i) ?: continue
            val shows   = section.optJSONArray("shows") ?: continue
            for (j in 0 until shows.length()) {
                val show    = shows.getJSONObject(j)
                val timeStr = show.optString("time", "")
                if (matches102FMTime(timeStr, day, mins)) {
                    return show.optString("title", "").ifBlank { null }
                }
            }
        }
        return null
    }

    /**
     * 102FM time strings: "א׳ - ד׳ 16:00-18:00" or "א׳ - ד׳ 13:00-14:00, ה' 17:00-20:00".
     * Splits on comma, extracts HH:MM-HH:MM from each slot, delegates to matchesDay/Hour.
     */
    private fun matches102FMTime(timeStr: String, day: Int, mins: Int): Boolean {
        if (timeStr.isBlank()) return false
        val timeRangeRe = Regex("""(\d{1,2}:\d{2}-\d{1,2}:\d{2})""")
        for (slot in timeStr.split(",")) {
            val trimmed   = slot.trim()
            val timeMatch = timeRangeRe.find(trimmed) ?: continue
            val hourRange = timeMatch.groupValues[1]
            val dayPart   = trimmed.substringBefore(hourRange)
            if (matchesDay(dayPart, day) && matchesHour(hourRange, mins)) return true
        }
        return false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP helper
    // ─────────────────────────────────────────────────────────────────────────

    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = TIMEOUT_MS
                readTimeout    = TIMEOUT_MS
                setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                setRequestProperty("Accept",          "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "he-IL,he;q=0.9,en-US;q=0.8,en;q=0.7")
                setRequestProperty("Connection",      "keep-alive")
                setRequestProperty("Cache-Control",   "no-cache")
            }
            conn.connect()
            val code = conn.responseCode
            if (code != 200) {
                Log.d(TAG, "HTTP $code for $url")
                return null
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // eco99 time-matching helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns (dayOfWeek, minutesSinceMidnight) in the Israel timezone.
     * dayOfWeek uses [Calendar.DAY_OF_WEEK] conventions: 1=Sunday … 7=Saturday.
     */
    private fun israelDayAndMinutes(): Pair<Int, Int> {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jerusalem"))
        val day  = cal.get(Calendar.DAY_OF_WEEK)
        val mins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return day to mins
    }

    /**
     * Returns true if [dayNum] (1=Sun…7=Sat) falls within [dayRange].
     * eco99 format: "ימים א'-ה'" (letter range), or day names like "שישי"/"שבת".
     * Hebrew letters map to day numbers: א=1 (Sun), ב=2, ג=3, ד=4, ה=5, ו=6, ז=7 (Sat).
     */
    private fun matchesDay(dayRange: String, dayNum: Int): Boolean {
        val letterToDay = mapOf('א' to 1, 'ב' to 2, 'ג' to 3, 'ד' to 4, 'ה' to 5, 'ו' to 6, 'ז' to 7)
        val letters = dayRange.filter { it in letterToDay }
        return when {
            letters.length >= 2 -> {
                val start = letterToDay[letters[0]] ?: return true
                val end   = letterToDay[letters.last()] ?: return true
                dayNum in start..end
            }
            letters.length == 1 -> letterToDay[letters[0]] == dayNum
            "ראשון" in dayRange  -> dayNum == Calendar.SUNDAY
            "שני"   in dayRange  -> dayNum == Calendar.MONDAY
            "שלישי" in dayRange  -> dayNum == Calendar.TUESDAY
            "רביעי" in dayRange  -> dayNum == Calendar.WEDNESDAY
            "חמישי" in dayRange  -> dayNum == Calendar.THURSDAY
            "שישי"  in dayRange  -> dayNum == Calendar.FRIDAY
            "שבת"   in dayRange  -> dayNum == Calendar.SATURDAY
            else                 -> true  // unknown format — include to avoid missing data
        }
    }

    /**
     * Returns true if [mins] (minutes since midnight) falls within [hourRange].
     * eco99 format: "HH:MM-HH:MM". Handles overnight ranges (e.g. "23:00-02:00").
     */
    private fun matchesHour(hourRange: String, mins: Int): Boolean {
        val parts = hourRange.split("-")
        if (parts.size < 2) return false
        fun hhmm(s: String): Int? {
            val hm = s.trim().split(":")
            if (hm.size != 2) return null
            return (hm[0].toIntOrNull() ?: return null) * 60 + (hm[1].toIntOrNull() ?: return null)
        }
        val start = hhmm(parts[0]) ?: return false
        val end   = hhmm(parts[1]) ?: return false
        return if (start <= end) mins in start until end
        else mins >= start || mins < end   // overnight slot
    }

}
