package com.example.syncy_p2p.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.syncy_p2p.databinding.ItemSyncedFolderBinding
import com.example.syncy_p2p.sync.SyncedFolder
import com.example.syncy_p2p.sync.SyncStatus
import java.text.SimpleDateFormat
import java.util.*

class SyncedFoldersAdapter(
    private val onFolderClick: (SyncedFolder) -> Unit,
    private val onResyncClick: (SyncedFolder) -> Unit,
    private val onRemoveClick: (SyncedFolder) -> Unit
) : ListAdapter<SyncedFolder, SyncedFoldersAdapter.SyncedFolderViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SyncedFolderViewHolder {
        val binding = ItemSyncedFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SyncedFolderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SyncedFolderViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SyncedFolderViewHolder(
        private val binding: ItemSyncedFolderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(syncedFolder: SyncedFolder) {
            binding.apply {
                tvFolderName.text = syncedFolder.name
                tvLocalPath.text = syncedFolder.localPath
                tvRemoteDevice.text = "Synced with: ${syncedFolder.remoteDeviceName}"
                  // Show folder info without file count
                tvFileInfo.text = "Folder sync active"
                
                // Format last sync time
                val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                tvLastSync.text = "Last sync: ${dateFormat.format(Date(syncedFolder.lastSyncTime))}"
                  // Set status and color
                when (syncedFolder.status) {
                    SyncStatus.SYNCED -> {
                        tvStatus.text = "Synced"
                        tvStatus.setTextColor(binding.root.context.getColor(android.R.color.holo_green_dark))
                    }
                    SyncStatus.SYNCING -> {
                        tvStatus.text = "Syncing..."
                        tvStatus.setTextColor(binding.root.context.getColor(android.R.color.holo_blue_dark))
                    }
                    SyncStatus.ERROR -> {
                        tvStatus.text = "Error"
                        tvStatus.setTextColor(binding.root.context.getColor(android.R.color.holo_red_dark))
                    }
                    SyncStatus.PENDING_SYNC -> {
                        tvStatus.text = "Pending"
                        tvStatus.setTextColor(binding.root.context.getColor(android.R.color.holo_orange_dark))
                    }
                    SyncStatus.CONFLICT -> {
                        tvStatus.text = "Conflict"
                        tvStatus.setTextColor(binding.root.context.getColor(android.R.color.holo_red_dark))
                    }
                    SyncStatus.DISCONNECTED -> {
                        tvStatus.text = "Disconnected"
                        tvStatus.setTextColor(binding.root.context.getColor(android.R.color.darker_gray))
                    }
                }
                
                // Set click listeners
                root.setOnClickListener { onFolderClick(syncedFolder) }
                btnResync.setOnClickListener { onResyncClick(syncedFolder) }
                btnRemove.setOnClickListener { onRemoveClick(syncedFolder) }
                
                // Enable/disable resync button based on status
                btnResync.isEnabled = syncedFolder.status != SyncStatus.SYNCING
            }
        }
        
        private fun formatFileSize(bytes: Long): String {
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            
            return when {
                gb >= 1 -> String.format("%.1f GB", gb)
                mb >= 1 -> String.format("%.1f MB", mb)
                kb >= 1 -> String.format("%.1f KB", kb)
                else -> "$bytes B"
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<SyncedFolder>() {
        override fun areItemsTheSame(oldItem: SyncedFolder, newItem: SyncedFolder): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: SyncedFolder, newItem: SyncedFolder): Boolean {
            return oldItem == newItem
        }
    }
}
