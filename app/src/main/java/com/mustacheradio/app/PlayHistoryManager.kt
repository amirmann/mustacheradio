package com.mustacheradio.app

import android.content.Context
import android.content.SharedPreferences

class PlayHistoryManager(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "play_history",
        Context.MODE_PRIVATE
    )
    
    fun recordPlay(stationId: String) {
        val timestamp = System.currentTimeMillis()
        // commit() so MediaBrowser reloads see the new order immediately (Android Auto)
        prefs.edit().putLong(stationId, timestamp).commit()
    }
    
    fun getLastPlayedTimestamp(stationId: String): Long {
        return prefs.getLong(stationId, 0L)
    }
    
    fun sortStationsByLastPlayed(stations: List<RadioStation>): List<RadioStation> {
        return stations.sortedByDescending { getLastPlayedTimestamp(it.id) }
    }
}
