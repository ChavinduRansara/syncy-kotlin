package com.example.syncy_p2p.ui

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import com.example.syncy_p2p.databinding.DialogConflictResolutionBinding
import com.example.syncy_p2p.sync.ConflictResolution
import com.example.syncy_p2p.sync.FileConflict
import java.text.SimpleDateFormat
import java.util.*

class ConflictResolutionDialog {
    
    companion object {
        fun show(
            context: Context,
            conflict: FileConflict,
            onResolutionSelected: (ConflictResolution) -> Unit
        ) {
            val binding = DialogConflictResolutionBinding.inflate(LayoutInflater.from(context))
            
            // Set file information
            binding.tvFileName.text = conflict.fileName
            binding.tvFilePath.text = conflict.filePath
            
            // Format file details
            val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())
            
            // Local file details
            binding.tvLocalSize.text = formatFileSize(conflict.localFileSize)
            binding.tvLocalModified.text = dateFormat.format(Date(conflict.localLastModified))
            
            // Remote file details
            binding.tvRemoteSize.text = formatFileSize(conflict.remoteFileSize)
            binding.tvRemoteModified.text = dateFormat.format(Date(conflict.remoteLastModified))
            
            // Determine recommended action
            val recommendation = when {
                conflict.localLastModified > conflict.remoteLastModified -> "Local file is newer"
                conflict.remoteLastModified > conflict.localLastModified -> "Remote file is newer"
                conflict.localFileSize != conflict.remoteFileSize -> "Files have different sizes"
                else -> "Files appear to be different versions"
            }
            binding.tvRecommendation.text = recommendation
              // Set up radio button listeners
            var selectedResolution = ConflictResolution.OVERWRITE_REMOTE // Default: keep local
            
            binding.radioKeepLocal.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedResolution = ConflictResolution.OVERWRITE_REMOTE
            }
            
            binding.radioKeepRemote.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedResolution = ConflictResolution.OVERWRITE_LOCAL
            }
            
            binding.radioKeepBoth.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedResolution = ConflictResolution.KEEP_BOTH
            }
            
            binding.radioSkip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedResolution = ConflictResolution.ASK_USER
            }
            
            // Create and show dialog
            AlertDialog.Builder(context)
                .setTitle("File Conflict")
                .setView(binding.root)
                .setPositiveButton("Apply") { _, _ ->
                    onResolutionSelected(selectedResolution)
                }
                .setNegativeButton("Cancel", null)
                .setCancelable(false)
                .show()
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
}
