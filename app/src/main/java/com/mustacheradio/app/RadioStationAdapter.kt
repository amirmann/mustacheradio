package com.mustacheradio.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mustacheradio.app.databinding.ItemRadioStationBinding

class RadioStationAdapter(
    stations: List<RadioStation>,
    private val onStationClick: (RadioStation) -> Unit
) : RecyclerView.Adapter<RadioStationAdapter.StationViewHolder>() {

    private var stations: List<RadioStation> = stations
    private val nowPlayingMap = mutableMapOf<String, String>()
    private var showNowPlaying = false

    inner class StationViewHolder(
        private val binding: ItemRadioStationBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(station: RadioStation) {
            binding.stationName.text        = station.name
            binding.stationDescription.text = station.description
            binding.stationIcon.setImageResource(station.iconRes)

            val np = if (showNowPlaying) nowPlayingMap[station.id] else null
            if (np != null) {
                binding.nowPlayingText.visibility = View.VISIBLE
                binding.nowPlayingText.text = "♪ $np"
            } else {
                binding.nowPlayingText.visibility = View.GONE
            }

            binding.root.setOnClickListener { onStationClick(station) }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /** Replace the station list (e.g. after reordering by last-played). */
    fun updateStations(newStations: List<RadioStation>) {
        stations = newStations
        notifyDataSetChanged()
    }

    /** Enable or disable the now-playing row for all items. */
    fun setShowNowPlaying(enabled: Boolean) {
        if (showNowPlaying == enabled) return
        showNowPlaying = enabled
        notifyDataSetChanged()
    }

    /** Update now-playing text for a single station without a full rebind. */
    fun updateNowPlaying(stationId: String, text: String?) {
        if (text != null) {
            nowPlayingMap[stationId] = text
        } else {
            nowPlayingMap.remove(stationId)
        }
        if (showNowPlaying) {
            val idx = stations.indexOfFirst { it.id == stationId }
            if (idx >= 0) notifyItemChanged(idx)
        }
    }

    /** Bulk-load initial now-playing data (e.g. after re-enabling the feature). */
    fun setNowPlayingData(data: Map<String, String>) {
        nowPlayingMap.clear()
        nowPlayingMap.putAll(data)
        if (showNowPlaying) notifyDataSetChanged()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RecyclerView.Adapter
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StationViewHolder {
        val binding = ItemRadioStationBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return StationViewHolder(binding)
    }

    override fun onBindViewHolder(holder: StationViewHolder, position: Int) =
        holder.bind(stations[position])

    override fun getItemCount() = stations.size
}
