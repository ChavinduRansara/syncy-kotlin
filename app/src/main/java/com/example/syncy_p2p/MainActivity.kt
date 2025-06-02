package com.example.syncy_p2p

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.documentfile.provider.DocumentFile
import com.example.syncy_p2p.databinding.ActivityMainBinding
import com.example.syncy_p2p.sync.SyncMode
import com.example.syncy_p2p.p2p.WiFiDirectManager
import com.example.syncy_p2p.ui.SyncManagementActivity
import com.example.syncy_p2p.ui.ConflictResolutionDialog
import com.example.syncy_p2p.sync.FileConflict
import com.example.syncy_p2p.sync.ConflictResolution
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.example.syncy_p2p.p2p.core.DeviceInfo
import com.example.syncy_p2p.ui.PeerAdapter
import com.example.syncy_p2p.files.FileManager
import com.example.syncy_p2p.files.FileAdapter
import com.example.syncy_p2p.files.FileItem
import com.example.syncy_p2p.files.FileTransferProgress
import com.example.syncy_p2p.sync.SyncManager
import java.io.File
import org.json.JSONObject

class MainActivity : AppCompatActivity(), WiFiDirectManager.WiFiDirectCallback {

    companion object {
        private const val TAG = "SyncyP2P"
        private const val PERMISSION_REQUEST_CODE = 100
    }
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var wifiDirectManager: WiFiDirectManager
    private lateinit var peerAdapter: PeerAdapter
    private lateinit var fileManager: FileManager
    private lateinit var fileAdapter: FileAdapter
    private lateinit var syncManager: SyncManager

    private val requiredPermissions = mutableListOf<String>().apply {
        add(Manifest.permission.ACCESS_WIFI_STATE)
        add(Manifest.permission.CHANGE_WIFI_STATE)
        add(Manifest.permission.ACCESS_NETWORK_STATE)
        add(Manifest.permission.CHANGE_NETWORK_STATE)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
          if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleSelectedFile(uri)
            }
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleSelectedFolder(uri)
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupUI()
        setupFileManager()
        setupWiFiDirect()
        setupSyncManager()
        checkPermissions()
    }

    private fun setupUI() {
        // Setup RecyclerView for peers
        peerAdapter = PeerAdapter(
            onConnectClick = { device ->
                connectToDevice(device)
            },
            onDisconnectClick = { device ->
                disconnectFromDevice(device)
            }
        )
        
        binding.rvPeers.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = peerAdapter
        }

        // Setup RecyclerView for files
        fileAdapter = FileAdapter(
            onFileClick = { fileItem ->
                handleFileClick(fileItem)
            },
            onSendClick = { fileItem ->
                handleFileSend(fileItem)
            }
        )
        
        binding.rvFiles.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = fileAdapter
        }        // Setup button listeners
        binding.btnInit.setOnClickListener {
            initializeWiFiDirect()
        }
        
        binding.btnDiscover.setOnClickListener {
            discoverPeers()
        }
        
        binding.btnCreateGroup.setOnClickListener {
            createGroup()
        }
        
        binding.btnDisconnect.setOnClickListener {
            disconnect()
        }
        
        binding.btnSendMessage.setOnClickListener {
            sendMessage()
        }
        
        binding.btnSendFile.setOnClickListener {
            selectFile()
        }
        
        binding.btnSelectFolder.setOnClickListener {
            selectFolder()
        }

        binding.btnSyncFolder.setOnClickListener {
            initiateSync()
        }

        binding.btnSyncManagement.setOnClickListener {
            openSyncManagement()
        }

        binding.btnSyncFolder.setOnClickListener {
            startFolderSyncWithMode()
        }
    }

    private fun setupFileManager() {
        fileManager = FileManager(this)
        
        // Update UI with current folder selection
        updateFolderDisplay()
        
        // Load files if folder is already selected
        if (fileManager.hasSelectedFolder()) {
            loadFilesFromSelectedFolder()
        }
    }

    private fun setupWiFiDirect() {
        wifiDirectManager = WiFiDirectManager(this)
        wifiDirectManager.setCallback(this)

        // Observe state changes
        lifecycleScope.launch {
            wifiDirectManager.peers.collect { peers ->
                peerAdapter.submitList(peers)
            }
        }

        lifecycleScope.launch {
            wifiDirectManager.connectionInfo.collect { connectionInfo ->
                val isConnected = connectionInfo?.groupFormed == true
                updateConnectionButtons(isConnected)
            }        }

        lifecycleScope.launch {
            wifiDirectManager.thisDevice.collect { deviceInfo ->
                deviceInfo?.let {
                    binding.tvDeviceInfo.text = "Device: ${it.deviceName} (${it.deviceAddress})"
                }
            }
        }
        
        lifecycleScope.launch {
            wifiDirectManager.isInitialized.collect { initialized ->
                binding.btnInit.isEnabled = !initialized
                binding.btnDiscover.isEnabled = initialized
                binding.btnCreateGroup.isEnabled = initialized
            }
        }
    }

    private fun setupSyncManager() {
        syncManager = SyncManager(this, fileManager, wifiDirectManager)
        
        // Observe sync state changes
        lifecycleScope.launch {
            syncManager.syncRequests.collect { requests ->
                // Handle incoming sync requests - show notification to user
                requests.forEach { request ->
                    if (request.status == com.example.syncy_p2p.sync.SyncRequestStatus.PENDING) {
                        showSyncRequestDialog(request)
                    }
                }
            }
        }
        
        lifecycleScope.launch {
            syncManager.syncedFolders.collect { folders ->
                // Update UI with synced folders
                updateSyncedFoldersDisplay(folders)
            }
        }
        
        lifecycleScope.launch {
            syncManager.currentProgress.collect { progress ->
                // Update sync progress in UI
                updateSyncProgress(progress)
            }
        }
        
        lifecycleScope.launch {
            syncManager.syncLogs.collect { logs ->
                // Update sync logs in UI
                updateSyncLogs(logs)
            }
        }
    }

    private fun showSyncRequestDialog(request: com.example.syncy_p2p.sync.SyncRequest) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Sync Request")
            .setMessage("${request.sourceDeviceName} wants to sync folder '${request.folderName}' (${request.totalFiles} files, ${formatFileSize(request.totalSize)})")
            .setPositiveButton("Accept") { _, _ ->
                lifecycleScope.launch {
                    syncManager.acceptSyncRequest(request.requestId)
                }
            }
            .setNegativeButton("Reject") { _, _ ->
                lifecycleScope.launch {
                    syncManager.rejectSyncRequest(request.requestId)
                }
            }
            .setCancelable(false)
            .show()
    }

    private fun updateSyncedFoldersDisplay(folders: List<com.example.syncy_p2p.sync.SyncedFolder>) {
        // Update UI to show synced folders - could add to a separate RecyclerView
        Log.d(TAG, "Synced folders updated: ${folders.size}")
    }

    private fun updateSyncProgress(progress: com.example.syncy_p2p.sync.SyncProgress?) {
        if (progress != null) {
            runOnUiThread {
                val percentage = if (progress.totalFiles > 0) {
                    (progress.filesProcessed * 100) / progress.totalFiles
                } else 0
                binding.tvStatus.text = "Syncing: ${progress.currentFile} ($percentage%)"
            }
        }
    }

    private fun updateSyncLogs(logs: List<com.example.syncy_p2p.sync.SyncLogEntry>) {
        // Update sync logs in UI - could show in a dialog or separate section
        Log.d(TAG, "Sync logs updated: ${logs.size} entries")
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

    private fun checkPermissions() {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!allGranted) {
                Toast.makeText(this, "Permissions are required for Wi-Fi Direct", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun initializeWiFiDirect() {
        val result = wifiDirectManager.initialize()
        result.onFailure { error ->
            onError("Failed to initialize Wi-Fi Direct: ${error.message}")
        }
    }

    private fun discoverPeers() {
        val result = wifiDirectManager.discoverPeers()
        result.onFailure { error ->
            onError("Failed to discover peers: ${error.message}")
        }
    }

    private fun createGroup() {
        val result = wifiDirectManager.createGroup()
        result.onFailure { error ->
            onError("Failed to create group: ${error.message}")
        }
    }

    private fun disconnect() {
        val result = wifiDirectManager.disconnect()
        result.onFailure { error ->
            onError("Failed to disconnect: ${error.message}")
        }
    }

    private fun connectToDevice(device: DeviceInfo) {
        val result = wifiDirectManager.connectToDevice(device.deviceAddress)
        result.onFailure { error ->
            onError("Failed to connect to ${device.deviceName}: ${error.message}")
        }
    }

    private fun disconnectFromDevice(device: DeviceInfo) {
        // First try to remove the group (for when we're group owner)
        val removeGroupResult = wifiDirectManager.removeGroup()
        removeGroupResult.onSuccess {
            onStatusChanged("Disconnected from ${device.deviceName}")
            Toast.makeText(this, "Disconnected from ${device.deviceName}", Toast.LENGTH_SHORT).show()
        }.onFailure {
            // If removeGroup fails, try cancelConnect (for when we're a client)
            val disconnectResult = wifiDirectManager.disconnect()
            disconnectResult.onSuccess {
                onStatusChanged("Disconnected from ${device.deviceName}")
                Toast.makeText(this, "Disconnected from ${device.deviceName}", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                onError("Failed to disconnect from ${device.deviceName}: ${error.message}")
            }
        }
    }

    private fun sendMessage() {
        val message = binding.etMessage.text.toString().trim()
        if (message.isEmpty()) {
            Toast.makeText(this, "Please enter a message", Toast.LENGTH_SHORT).show()
            return
        }

        val result = wifiDirectManager.sendMessage(message)
        result.onSuccess {
            binding.etMessage.text.clear()
            Toast.makeText(this, "Message sent", Toast.LENGTH_SHORT).show()
        }.onFailure { error ->
            onError("Failed to send message: ${error.message}")
        }
    }

    private fun selectFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        filePickerLauncher.launch(intent)
    }

    private fun handleSelectedFile(uri: Uri) {
        try {
            // Get the actual file path
            val filePath = getRealPathFromUri(uri)
            if (filePath != null) {
                val result = wifiDirectManager.sendFile(filePath)
                result.onSuccess {
                    Toast.makeText(this, "File sent", Toast.LENGTH_SHORT).show()
                }.onFailure { error ->
                    onError("Failed to send file: ${error.message}")
                }
            } else {
                onError("Unable to access selected file")
            }
        } catch (e: Exception) {
            onError("Error selecting file: ${e.message}")
        }
    }

    private fun selectFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        folderPickerLauncher.launch(intent)
    }    private fun handleSelectedFolder(uri: Uri) {
        try {
            fileManager.setSelectedFolder(uri)
            updateFolderDisplay()
            loadFilesFromSelectedFolder()
            
            // Update sync button state
            val isConnected = wifiDirectManager.connectionInfo.value?.groupFormed == true
            binding.btnSyncFolder.isEnabled = isConnected && fileManager.hasSelectedFolder()
            
            val folderPath = fileManager.selectedFolderPath ?: "Unknown"
            Toast.makeText(this, getString(R.string.folder_selected, folderPath), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            onError("Failed to select folder: ${e.message}")
        }
    }

    private fun updateFolderDisplay() {
        val folderPath = fileManager.selectedFolderPath
        binding.tvCurrentPath.text = folderPath ?: getString(R.string.no_folder_selected)
    }    private fun loadFilesFromSelectedFolder() {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "🔄 LOAD FILES - Loading files from selected folder")
                Log.d(TAG, "🔄 LOAD FILES - Selected folder URI: ${fileManager.selectedFolderUri}")
                
                val files = fileManager.getFilesInFolder()
                Log.d(TAG, "🔄 LOAD FILES - Found ${files.size} files")
                
                // Update the adapter on the main thread
                withContext(Dispatchers.Main) {
                    fileAdapter.submitList(files.toList()) // Create new list to trigger diff
                    fileAdapter.notifyDataSetChanged() // Force refresh
                    Log.d(TAG, "🔄 LOAD FILES - Adapter updated with ${files.size} files")
                    
                    // Update file count display if you have one
                    binding.tvCurrentPath.text = "${fileManager.selectedFolderPath ?: "No folder selected"} (${files.size} items)"
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ LOAD FILES - Error loading files", e)
                onError("Failed to load files: ${e.message}")
            }
        }
    }

    private fun handleFileClick(fileItem: FileItem) {
        if (fileItem.isDirectory) {
            // Navigate into directory (for future implementation)
            Toast.makeText(this, "Directory navigation not yet implemented", Toast.LENGTH_SHORT).show()
        } else {
            // Show file details or preview (for future implementation)
            Toast.makeText(this, "File preview not yet implemented", Toast.LENGTH_SHORT).show()
        }
    }    private fun handleFileSend(fileItem: FileItem) {
        if (fileItem.isDirectory) {
            Toast.makeText(this, "Cannot send directories", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                val inputStream = fileManager.getFileInputStream(fileItem.uri)
                if (inputStream != null) {
                    inputStream.use { stream ->
                        // Create temporary file for Wi-Fi Direct transfer with proper permissions
                        val tempFile = File(cacheDir, "temp_${System.currentTimeMillis()}_${fileItem.name}")
                        
                        // Ensure temp file is created and writable
                        tempFile.createNewFile()
                        tempFile.setReadable(true, false)
                        tempFile.setWritable(true, false)
                        
                        var bytesWritten = 0L
                        tempFile.outputStream().use { output ->
                            bytesWritten = stream.copyTo(output)
                        }
                        
                        Log.d("MainActivity", "Created temp file: ${tempFile.absolutePath}, size: $bytesWritten bytes, exists: ${tempFile.exists()}")
                        
                        if (tempFile.exists() && tempFile.length() > 0) {
                            val result = wifiDirectManager.sendFile(tempFile.absolutePath)
                            result.onSuccess {
                                Toast.makeText(this@MainActivity, "File sent: ${fileItem.name}", Toast.LENGTH_SHORT).show()
                            }.onFailure { error ->
                                onError("Failed to send file: ${error.message}")
                            }
                        } else {
                            onError("Failed to create temporary file")
                        }
                        
                        // Clean up temp file after a delay to ensure transfer completes
                        delay(5000)
                        if (tempFile.exists()) {
                            tempFile.delete()
                            Log.d("MainActivity", "Cleaned up temp file: ${tempFile.absolutePath}")
                        }
                    }
                } else {
                    onError("Cannot access file: ${fileItem.name}")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error sending file", e)
                onError("Error sending file: ${e.message}")
            }
        }
    }

    private fun getRealPathFromUri(uri: Uri): String? {
        return try {
            when {
                DocumentsContract.isDocumentUri(this, uri) -> {
                    // Handle document URIs
                    val docId = DocumentsContract.getDocumentId(uri)
                    if (uri.authority == "com.android.externalstorage.documents") {
                        val split = docId.split(":")
                        if (split.size >= 2) {
                            val type = split[0]
                            val relativePath = split[1]
                            when (type) {
                                "primary" -> "${Environment.getExternalStorageDirectory()}/$relativePath"
                                else -> "/storage/$type/$relativePath"
                            }
                        } else null
                    } else {
                        // For other document providers, copy to temp file
                        copyUriToTempFile(uri)
                    }
                }
                uri.scheme == "content" -> copyUriToTempFile(uri)
                uri.scheme == "file" -> uri.path
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file path", e)
            null
        }
    }

    private fun copyUriToTempFile(uri: Uri): String? {
        return try {
            val inputStream = contentResolver.openInputStream(uri)
            val tempFile = File(cacheDir, "temp_${System.currentTimeMillis()}")
            inputStream?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            tempFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error copying file", e)
            null
        }
    }    private fun updateConnectionButtons(isConnected: Boolean) {
        binding.btnSendMessage.isEnabled = isConnected
        binding.btnSendFile.isEnabled = isConnected
        binding.btnDisconnect.isEnabled = isConnected
        binding.btnSyncFolder.isEnabled = isConnected && fileManager.hasSelectedFolder()
    }

    private fun initiateSync() {
        if (!fileManager.hasSelectedFolder()) {
            Toast.makeText(this, "Please select a folder first", Toast.LENGTH_SHORT).show()
            return
        }
          val folderUri = fileManager.selectedFolderUri ?: return
        
        lifecycleScope.launch {
            try {
                val result = syncManager.initiateFolderSync(folderUri, "Remote Device")
                if (result.isSuccess) {
                    Toast.makeText(this@MainActivity, "Sync request sent", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Failed to send sync request", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                onError("Error initiating sync: ${e.message}")
            }
        }
    }

    private fun openSyncManagement() {
        val intent = Intent(this, SyncManagementActivity::class.java)
        // Pass the sync manager instance - this would need proper implementation
        // intent.putExtra(SyncManagementActivity.EXTRA_SYNC_MANAGER, syncManager)
        startActivity(intent)
    }

    private fun handleFileConflict(conflict: FileConflict) {
        runOnUiThread {
            ConflictResolutionDialog.show(this, conflict) { resolution ->
                lifecycleScope.launch {
                    try {
                        syncManager.resolveConflict(conflict.id, resolution)
                        val resolutionText = when (resolution) {
                            ConflictResolution.OVERWRITE_LOCAL -> "kept remote file"
                            ConflictResolution.OVERWRITE_REMOTE -> "kept local file"
                            ConflictResolution.KEEP_BOTH -> "kept both files"
                            ConflictResolution.KEEP_NEWER -> "kept newer file"
                            ConflictResolution.KEEP_LARGER -> "kept larger file"
                            ConflictResolution.ASK_USER -> "user choice required"
                        }
                        Toast.makeText(this@MainActivity, "Conflict resolved: $resolutionText", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        onError("Error resolving conflict: ${e.message}")
                    }
                }
            }
        }
    }

    private fun forceRefreshAndVerifyFiles() {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "🔄 FORCE REFRESH - Starting comprehensive refresh")
                
                // Wait a bit for filesystem to settle
                delay(500)
                
                // Use the enhanced refresh method
                val files = fileManager.refreshFolderContents()
                Log.d(TAG, "🔄 FORCE REFRESH - Found ${files.size} files after refresh")
                
                // Update UI on main thread
                withContext(Dispatchers.Main) {
                    fileAdapter.submitList(files.toList())
                    fileAdapter.notifyDataSetChanged()
                    
                    // Update path display with file count
                    binding.tvCurrentPath.text = "${fileManager.selectedFolderPath ?: "No folder selected"} (${files.size} items)"
                    
                    Log.d(TAG, "🔄 FORCE REFRESH - UI updated with ${files.size} files")
                    
                    // Show toast with file count
                    Toast.makeText(this@MainActivity, "Folder refreshed: ${files.size} files found", Toast.LENGTH_SHORT).show()
                }
                
                // Additional delayed refresh for SAF consistency
                delay(1000)
                val delayedFiles = fileManager.getFilesInFolder()
                if (delayedFiles.size != files.size) {
                    Log.d(TAG, "🔄 FORCE REFRESH - File count changed after delay: ${delayedFiles.size}")
                    withContext(Dispatchers.Main) {
                        fileAdapter.submitList(delayedFiles.toList())
                        fileAdapter.notifyDataSetChanged()
                        binding.tvCurrentPath.text = "${fileManager.selectedFolderPath ?: "No folder selected"} (${delayedFiles.size} items)"
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ FORCE REFRESH - Error during refresh", e)
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        wifiDirectManager.cleanup()
    }

    // WiFiDirectCallback implementation
    override fun onMessageReceived(message: String, senderAddress: String) {
        runOnUiThread {
            Toast.makeText(this, "Message from $senderAddress: $message", Toast.LENGTH_LONG).show()
        }
    }    override fun onFileReceived(filePath: String, senderAddress: String) {
        runOnUiThread {
            Toast.makeText(this, "File received from $senderAddress: $filePath", Toast.LENGTH_LONG).show()
            // Refresh file list to show newly received files
            loadFilesFromSelectedFolder()
        }
    }    override fun onFileTransferProgress(progress: com.example.syncy_p2p.files.FileTransferProgress) {
        runOnUiThread {
            // Update progress in sync management activity or sync progress dialog
            updateSyncProgress(com.example.syncy_p2p.sync.SyncProgress(
                folderName = "File Transfer",
                currentFile = progress.fileName,
                filesProcessed = if (progress.percentage == 100) 1 else 0,
                totalFiles = 1,
                bytesTransferred = progress.bytesTransferred,
                totalBytes = progress.totalBytes,
                status = "${progress.percentage}% - ${progress.fileName}"
            ))
        }
    }    override fun onSyncFileReceived(tempFilePath: String, fileName: String, fileBytes: ByteArray, syncedFolderUri: android.net.Uri, senderAddress: String) {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "🔄 SYNC FILE RECEIVED - Processing file: $fileName (${fileBytes.size} bytes)")
                Log.d(TAG, "🔄 SYNC FILE RECEIVED - Temp file path: $tempFilePath")
                Log.d(TAG, "🔄 SYNC FILE RECEIVED - Synced folder URI: $syncedFolderUri")
                Log.d(TAG, "🔄 SYNC FILE RECEIVED - Sender address: $senderAddress")
                
                // CRITICAL FIX: Save directly to the synced folder URI
                Log.d(TAG, "🔄 SYNC FILE RECEIVED - Saving directly to synced folder")
                
                val success = fileManager.writeFileToSyncFolder(syncedFolderUri, fileName, fileBytes)
                
                if (success) {
                    Log.d(TAG, "✅ SYNC FILE RECEIVED - File successfully written to sync folder: $fileName")
                    
                    // Clean up temp file
                    try {
                        val tempFile = java.io.File(tempFilePath)
                        if (tempFile.exists()) {
                            tempFile.delete()
                            Log.d(TAG, "🧹 SYNC FILE RECEIVED - Cleaned up temp file: $tempFilePath")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ SYNC FILE RECEIVED - Failed to clean up temp file: ${e.message}")
                    }
                    
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Sync file received: $fileName", Toast.LENGTH_SHORT).show()
                        
                        // Check if we need to refresh the current view
                        if (fileManager.selectedFolderUri == syncedFolderUri) {
                            Log.d(TAG, "🔄 SYNC FILE RECEIVED - Refreshing current folder view")
                            loadFilesFromSelectedFolder()
                        } else {
                            Log.d(TAG, "🔄 SYNC FILE RECEIVED - File saved to different folder, showing navigation option")
                            
                            // Show dialog to navigate to the sync folder
                            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                                .setTitle("Sync File Received")
                                .setMessage("File '$fileName' was saved to the sync folder. Do you want to view it?")
                                .setPositiveButton("View Folder") { _, _ ->
                                    // Navigate to the sync folder
                                    fileManager.setSelectedFolder(syncedFolderUri)
                                    updateFolderDisplay()
                                    loadFilesFromSelectedFolder()
                                    Toast.makeText(this@MainActivity, "Switched to sync folder", Toast.LENGTH_SHORT).show()
                                }
                                .setNegativeButton("Stay") { _, _ ->
                                    // Just stay in current folder
                                }
                                .show()
                        }
                        
                        // Verify file was saved correctly
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            lifecycleScope.launch {
                                val filesInSyncFolder = fileManager.getFilesInFolder(syncedFolderUri)
                                val foundFile = filesInSyncFolder.find { it.name == fileName }
                                if (foundFile != null) {
                                    Log.d(TAG, "✅ SYNC FILE RECEIVED - File verification SUCCESS: $fileName found in sync folder")
                                    Log.d(TAG, "✅ SYNC FILE RECEIVED - File size: ${foundFile.size} bytes")
                                } else {
                                    Log.w(TAG, "⚠️ SYNC FILE RECEIVED - File verification FAILED: $fileName not found in sync folder")
                                    Log.d(TAG, "🔍 SYNC FILE RECEIVED - Files in sync folder: ${filesInSyncFolder.map { "${it.name} (${it.size}B)" }}")
                                }
                            }
                        }, 500)
                    }
                } else {
                    Log.e(TAG, "❌ SYNC FILE RECEIVED - Failed to save file to sync folder: $fileName")
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Failed to save sync file: $fileName", Toast.LENGTH_LONG).show()
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ SYNC FILE RECEIVED - Exception processing sync file", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Error receiving sync file: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onSyncRequestReceived(requestJson: String, senderAddress: String) {
        Log.d(TAG, "🚨 SYNC REQUEST RECEIVED IN MAINACTIVITY 🚨")
        Log.d(TAG, "From: $senderAddress")
        Log.d(TAG, "Request JSON: $requestJson")
        
        try {
            // Check if this is a folder structure request
            val jsonObj = JSONObject(requestJson)
            if (jsonObj.has("type") && jsonObj.getString("type") == "FOLDER_STRUCTURE_REQUEST") {
                Log.d(TAG, "🗂️ FOLDER STRUCTURE REQUEST detected")
                // Handle folder structure request
                syncManager.handleFolderStructureRequest(requestJson, senderAddress)
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking request type", e)
        }
        
        // Parse as a full sync request
        val request = syncManager.parseSyncRequestFromJson(requestJson)
        if (request != null) {
            runOnUiThread {
                // Show accept/reject dialog
                showSyncRequestDialog(request)
            }
              // Also handle it in sync manager for logging
            syncManager.handleSyncRequest(request)
        } else {
            Log.e(TAG, "Failed to parse sync request JSON")
            runOnUiThread {
                Toast.makeText(this, "Received folder structure request", Toast.LENGTH_SHORT).show()
            }
        }
    }override fun onSyncResponseReceived(response: String, senderAddress: String) {
        Log.d(TAG, "Sync response received from $senderAddress: $response")
        syncManager.handleSyncResponse(response)
    }

    override fun onSyncProgressReceived(progressJson: String, senderAddress: String) {
        Log.d(TAG, "Sync progress received from $senderAddress")
        syncManager.handleSyncProgress(progressJson)
    }

    override fun onSyncStartTransferReceived(message: String, senderAddress: String) {
        Log.d(TAG, "🚀 SYNC START TRANSFER RECEIVED: $message")
        // Parse the message: "SYNC_START_TRANSFER:folderId:folderName"
        val parts = message.split(":")
        if (parts.size >= 3) {
            val folderId = parts[1]
            val folderName = parts[2]
            Log.d(TAG, "Starting sync transfer for folder: $folderName (ID: $folderId)")
            
            // Handle the start transfer request in SyncManager
            syncManager.handleSyncStartTransfer(folderId, folderName, senderAddress)
        } else {
            Log.e(TAG, "Invalid SYNC_START_TRANSFER message format: $message")
        }
    }

    override fun onSyncRequestFilesListReceived(folderPath: String, senderAddress: String) {
        Log.d(TAG, "📋 SYNC REQUEST FILES LIST RECEIVED for path: $folderPath")
        // Handle the files list request in SyncManager
        syncManager.handleSyncRequestFilesList(folderPath, senderAddress)
    }

    override fun onSyncRequestFileReceived(message: String, senderAddress: String) {
        Log.d(TAG, "📁 SYNC REQUEST FILE RECEIVED: $message")
        // Parse the message and handle file request
        syncManager.handleSyncRequestFile(message, senderAddress)
    }

    override fun onSyncFilesListResponseReceived(filesListJson: String, senderAddress: String) {
        Log.d(TAG, "📋 SYNC FILES LIST RESPONSE RECEIVED")
        // Handle the files list response in SyncManager
        syncManager.handleSyncFilesListResponse(filesListJson, senderAddress)
    }

    override fun onError(error: String) {
        runOnUiThread {
            Log.e(TAG, error)
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
        }
    }

    override fun onStatusChanged(status: String) {
        runOnUiThread {
            binding.tvStatus.text = status
            Log.d(TAG, "Status: $status")
        }
    }
    
    /**
     * Debug method to verify storage permissions and folder access
     */
    private fun debugStorageState() {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "🔍 STORAGE DEBUG - Checking storage state")
                
                // Check selected folder
                val selectedUri = fileManager.selectedFolderUri
                Log.d(TAG, "🔍 STORAGE DEBUG - Selected folder URI: $selectedUri")
                
                if (selectedUri != null) {
                    val documentFile = DocumentFile.fromTreeUri(this@MainActivity, selectedUri)
                    Log.d(TAG, "🔍 STORAGE DEBUG - DocumentFile exists: ${documentFile?.exists()}")
                    Log.d(TAG, "🔍 STORAGE DEBUG - DocumentFile canRead: ${documentFile?.canRead()}")
                    Log.d(TAG, "🔍 STORAGE DEBUG - DocumentFile canWrite: ${documentFile?.canWrite()}")
                    Log.d(TAG, "🔍 STORAGE DEBUG - DocumentFile isDirectory: ${documentFile?.isDirectory}")
                    
                    // Check persistent permissions
                    val persistedUris = contentResolver.persistedUriPermissions
                    val hasPermission = persistedUris.any { it.uri == selectedUri && it.isWritePermission }
                    Log.d(TAG, "🔍 STORAGE DEBUG - Has persistent write permission: $hasPermission")
                      // Try to list files
                    val files = fileManager.getFilesInFolder(selectedUri)
                    Log.d(TAG, "🔍 STORAGE DEBUG - Found ${files.size} files in folder")
                    files.forEach { file ->
                        Log.d(TAG, "🔍 STORAGE DEBUG - File: ${file.name} (${file.size} bytes)")
                    }
                }
                
                // Check external storage state
                val externalState = Environment.getExternalStorageState()
                Log.d(TAG, "🔍 STORAGE DEBUG - External storage state: $externalState")
                
                // Check app's external files directory
                val externalFilesDir = getExternalFilesDir(null)
                Log.d(TAG, "🔍 STORAGE DEBUG - External files dir: ${externalFilesDir?.absolutePath}")
                Log.d(TAG, "🔍 STORAGE DEBUG - External files dir exists: ${externalFilesDir?.exists()}")
                Log.d(TAG, "🔍 STORAGE DEBUG - External files dir canWrite: ${externalFilesDir?.canWrite()}")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ STORAGE DEBUG - Error during storage debug", e)
            }
        }
    }

    private fun startFolderSyncWithMode() {
        if (!fileManager.hasSelectedFolder()) {
            Toast.makeText(this, "Please select a folder first", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Show dialog to select sync mode
        val modes = arrayOf("Two-Way Sync", "One-Way Backup", "One-Way Mirror")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Select Sync Mode")
            .setItems(modes) { _, which ->
                val mode = when (which) {
                    0 -> SyncMode.TWO_WAY
                    1 -> SyncMode.ONE_WAY_BACKUP
                    2 -> SyncMode.ONE_WAY_MIRROR
                    else -> SyncMode.TWO_WAY
                }
                
                val targetDevice = getConnectedDeviceAddress()
                if (targetDevice != null) {
                    lifecycleScope.launch {
                        val result = syncManager.startFolderSync(
                            fileManager.selectedFolderUri!!,
                            targetDevice,
                            mode
                        )
                        
                        result.onSuccess { syncId ->
                            Toast.makeText(this@MainActivity, "Folder sync started: $syncId", Toast.LENGTH_SHORT).show()
                        }.onFailure { error ->
                            Toast.makeText(this@MainActivity, "Sync failed: ${error.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    Toast.makeText(this, "No connected device found", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun getConnectedDeviceAddress(): String? {
        // Get the address of the currently connected device
        return wifiDirectManager.connectionInfo.value?.groupOwnerAddress
    }

}
