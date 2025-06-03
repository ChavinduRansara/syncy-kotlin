package com.example.syncy_p2p.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.syncy_p2p.databinding.ItemSyncLogBinding
import com.example.syncy_p2p.sync.SyncLogEntry
import com.example.syncy_p2p.sync.SyncLogStatus
import java.text.SimpleDateFormat
import java.util.*

class SyncLogsAdapter : ListAdapter<SyncLogEntry, SyncLogsAdapter.SyncLogViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SyncLogViewHolder {
        val binding = ItemSyncLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SyncLogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SyncLogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SyncLogViewHolder(
        private val binding: ItemSyncLogBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(logEntry: SyncLogEntry) {
            binding.apply {
                // Format timestamp
                val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                tvTimestamp.text = timeFormat.format(Date(logEntry.timestamp))
                
                // Set message
                tvMessage.text = logEntry.message
                  // Set level indicator and color
                when (logEntry.status) {
                    SyncLogStatus.INFO -> {
                        tvLevel.text = "INFO"
                        tvLevel.setTextColor(binding.root.context.getColor(android.R.color.holo_blue_dark))
                        tvMessage.setTextColor(binding.root.context.getColor(android.R.color.primary_text_light))
                    }
                    SyncLogStatus.SUCCESS -> {
                        tvLevel.text = "SUCCESS"
                        tvLevel.setTextColor(binding.root.context.getColor(android.R.color.holo_green_dark))
                        tvMessage.setTextColor(binding.root.context.getColor(android.R.color.primary_text_light))
                    }
                    SyncLogStatus.WARNING -> {
                        tvLevel.text = "WARN"
                        tvLevel.setTextColor(binding.root.context.getColor(android.R.color.holo_orange_dark))
                        tvMessage.setTextColor(binding.root.context.getColor(android.R.color.primary_text_light))
                    }
                    SyncLogStatus.ERROR -> {
                        tvLevel.text = "ERROR"
                        tvLevel.setTextColor(binding.root.context.getColor(android.R.color.holo_red_dark))
                        tvMessage.setTextColor(binding.root.context.getColor(android.R.color.holo_red_dark))
                    }
                }
                
                // Show folder name if available
                if (logEntry.folderName.isNotEmpty()) {
                    tvFolderName.text = "[${logEntry.folderName}]"
                    tvFolderName.visibility = android.view.View.VISIBLE
                } else {
                    tvFolderName.visibility = android.view.View.GONE
                }
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<SyncLogEntry>() {
        override fun areItemsTheSame(oldItem: SyncLogEntry, newItem: SyncLogEntry): Boolean {
            return oldItem.timestamp == newItem.timestamp && oldItem.message == newItem.message
        }

        override fun areContentsTheSame(oldItem: SyncLogEntry, newItem: SyncLogEntry): Boolean {
            return oldItem == newItem
        }
    }
}
