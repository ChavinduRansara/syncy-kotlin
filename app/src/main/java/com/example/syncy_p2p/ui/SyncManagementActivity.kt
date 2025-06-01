package com.example.syncy_p2p.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.syncy_p2p.R
import com.example.syncy_p2p.databinding.ActivitySyncManagementBinding
import com.example.syncy_p2p.databinding.DialogSyncLogsBinding
import com.example.syncy_p2p.sync.SyncManager
import com.example.syncy_p2p.sync.SyncedFolder
import kotlinx.coroutines.launch

class SyncManagementActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivitySyncManagementBinding
    private lateinit var syncedFoldersAdapter: SyncedFoldersAdapter
    private lateinit var syncManager: SyncManager
    
    companion object {
        const val EXTRA_SYNC_MANAGER = "sync_manager"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySyncManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupActionBar()
        setupRecyclerView()
        setupSyncManager()
        observeSyncState()
    }
    
    private fun setupActionBar() {
        supportActionBar?.apply {
            title = "Sync Management"
            setDisplayHomeAsUpEnabled(true)
        }
    }
    
    private fun setupRecyclerView() {
        syncedFoldersAdapter = SyncedFoldersAdapter(
            onFolderClick = { folder ->
                showFolderDetails(folder)
            },
            onResyncClick = { folder ->
                initiateResync(folder)
            },
            onRemoveClick = { folder ->
                showRemoveConfirmation(folder)
            }
        )
        
        binding.rvSyncedFolders.apply {
            layoutManager = LinearLayoutManager(this@SyncManagementActivity)
            adapter = syncedFoldersAdapter
        }
    }
    
    private fun setupSyncManager() {
        // Get SyncManager instance from intent or application
        // This would typically be passed from MainActivity or retrieved from a singleton
        // For now, we'll handle the case where it's not available
        try {
            // syncManager = intent.getSerializableExtra(EXTRA_SYNC_MANAGER) as SyncManager
            // For now, show a message that this needs to be implemented
            Toast.makeText(this, "Sync manager integration pending", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to access sync manager", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    
    private fun observeSyncState() {
        // Observe synced folders and update UI
        lifecycleScope.launch {
            try {
                syncManager.syncedFolders.collect { folders ->
                    updateSyncedFolders(folders)
                }
            } catch (e: Exception) {
                // Handle case where syncManager is not properly initialized
            }
        }
        
        // Observe current sync progress
        lifecycleScope.launch {
            try {
                syncManager.currentProgress.collect { progress ->
                    updateSyncProgress(progress)
                }
            } catch (e: Exception) {
                // Handle case where syncManager is not properly initialized
            }
        }
    }
    
    private fun updateSyncedFolders(folders: List<SyncedFolder>) {
        syncedFoldersAdapter.submitList(folders)
        
        // Update empty state
        if (folders.isEmpty()) {
            binding.tvEmptyState.visibility = android.view.View.VISIBLE
            binding.rvSyncedFolders.visibility = android.view.View.GONE
        } else {
            binding.tvEmptyState.visibility = android.view.View.GONE
            binding.rvSyncedFolders.visibility = android.view.View.VISIBLE
        }
    }
    
    private fun updateSyncProgress(progress: com.example.syncy_p2p.sync.SyncProgress?) {
        if (progress != null) {
            val percentage = if (progress.totalFiles > 0) {
                (progress.filesProcessed * 100) / progress.totalFiles
            } else 0
            
            binding.progressBar.visibility = android.view.View.VISIBLE
            binding.tvSyncStatus.visibility = android.view.View.VISIBLE
            binding.tvSyncStatus.text = "Syncing: ${progress.currentFile} ($percentage%)"
            binding.progressBar.progress = percentage
        } else {
            binding.progressBar.visibility = android.view.View.GONE
            binding.tvSyncStatus.visibility = android.view.View.GONE
        }
    }
    
    private fun showFolderDetails(folder: SyncedFolder) {
        AlertDialog.Builder(this)
            .setTitle(folder.name)            .setMessage("""
                Local Path: ${folder.localPath}
                Remote Device: ${folder.remoteDeviceName}
                Status: ${folder.status}
                Last Sync: ${java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(folder.lastSyncTime))}
            """.trimIndent())
            .setPositiveButton("OK", null)
            .show()
    }
    
    private fun initiateResync(folder: SyncedFolder) {
        lifecycleScope.launch {
            try {
                val success = syncManager.initiateFolderSync(folder.localPath, folder.remoteDeviceName)
                if (success) {
                    Toast.makeText(this@SyncManagementActivity, "Resync initiated", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@SyncManagementActivity, "Failed to initiate resync", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@SyncManagementActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun showRemoveConfirmation(folder: SyncedFolder) {
        AlertDialog.Builder(this)
            .setTitle("Remove Sync Folder")
            .setMessage("Are you sure you want to remove '${folder.name}' from sync? This will not delete the local files.")
            .setPositiveButton("Remove") { _, _ ->
                removeSyncFolder(folder)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun removeSyncFolder(folder: SyncedFolder) {
        lifecycleScope.launch {
            try {
                syncManager.removeSyncedFolder(folder.id)
                Toast.makeText(this@SyncManagementActivity, "Sync folder removed", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@SyncManagementActivity, "Error removing folder: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun showSyncLogs() {
        val dialogBinding = DialogSyncLogsBinding.inflate(LayoutInflater.from(this))
        
        val logsAdapter = SyncLogsAdapter()
        dialogBinding.rvSyncLogs.apply {
            layoutManager = LinearLayoutManager(this@SyncManagementActivity)
            adapter = logsAdapter
        }
        
        // Observe sync logs
        lifecycleScope.launch {
            try {
                syncManager.syncLogs.collect { logs ->
                    logsAdapter.submitList(logs.takeLast(100)) // Show last 100 entries
                    
                    // Auto-scroll to bottom
                    if (logs.isNotEmpty()) {
                        dialogBinding.rvSyncLogs.scrollToPosition(logsAdapter.itemCount - 1)
                    }
                }
            } catch (e: Exception) {
                // Handle case where syncManager is not properly initialized
            }
        }
        
        AlertDialog.Builder(this)
            .setTitle("Sync Logs")
            .setView(dialogBinding.root)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear Logs") { _, _ ->
                clearSyncLogs()
            }
            .show()
    }
    
    private fun clearSyncLogs() {
        lifecycleScope.launch {
            try {
                syncManager.clearSyncLogs()
                Toast.makeText(this@SyncManagementActivity, "Sync logs cleared", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@SyncManagementActivity, "Error clearing logs: ${e.message}", Toast.LENGTH_SHORT).show()
            }
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
    
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_sync_management, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                true
            }
            R.id.action_view_logs -> {
                showSyncLogs()
                true
            }
            R.id.action_refresh -> {
                // Refresh sync status
                Toast.makeText(this, "Refreshing sync status", Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
