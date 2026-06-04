package com.amiradio.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.amiradio.app.databinding.ItemRadioStationBinding

class RadioStationAdapter(
    private val stations: List<RadioStation>,
    private val onStationClick: (RadioStation) -> Unit
) : RecyclerView.Adapter<RadioStationAdapter.StationViewHolder>() {
    
    inner class StationViewHolder(
        private val binding: ItemRadioStationBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(station: RadioStation) {
            binding.stationName.text = station.name
            binding.stationDescription.text = station.description
            binding.stationIcon.setImageResource(station.iconRes)
            
            binding.root.setOnClickListener {
                onStationClick(station)
            }
        }
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StationViewHolder {
        val binding = ItemRadioStationBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return StationViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: StationViewHolder, position: Int) {
        holder.bind(stations[position])
    }
    
    override fun getItemCount() = stations.size
}
