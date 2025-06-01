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
import com.example.syncy_p2p.databinding.ActivityMainBinding
import com.example.syncy_p2p.p2p.WiFiDirectManager
import com.example.syncy_p2p.ui.SyncManagementActivity
import com.example.syncy_p2p.ui.ConflictResolutionDialog
import com.example.syncy_p2p.sync.FileConflict
import com.example.syncy_p2p.sync.ConflictResolution
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.syncy_p2p.p2p.core.DeviceInfo
import com.example.syncy_p2p.ui.PeerAdapter
import com.example.syncy_p2p.files.FileManager
import com.example.syncy_p2p.files.FileAdapter
import com.example.syncy_p2p.files.FileItem
import com.example.syncy_p2p.files.FileTransferProgress
import com.example.syncy_p2p.sync.SyncManager
import java.io.File

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
    }

    private fun loadFilesFromSelectedFolder() {
        lifecycleScope.launch {
            try {
                val files = fileManager.getFilesInFolder()
                fileAdapter.submitList(files)
            } catch (e: Exception) {
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
        
        val folderPath = fileManager.selectedFolderPath ?: return
        
        lifecycleScope.launch {
            try {
                val success = syncManager.initiateFolderSync(folderPath, "Remote Device")
                if (success) {
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
                        syncManager.resolveConflict(conflict, resolution)
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
        }
    }    override fun onSyncRequestReceived(requestJson: String, senderAddress: String) {
        Log.d(TAG, "🚨 SYNC REQUEST RECEIVED IN MAINACTIVITY 🚨")
        Log.d(TAG, "From: $senderAddress")
        Log.d(TAG, "Request JSON: $requestJson")
        
        runOnUiThread {
            Toast.makeText(this, "🔄 Sync request received from $senderAddress", Toast.LENGTH_LONG).show()
        }
        
        syncManager.handleSyncRequest(requestJson, senderAddress)
    }

    override fun onSyncResponseReceived(response: String, senderAddress: String) {
        Log.d(TAG, "Sync response received from $senderAddress: $response")
        syncManager.handleSyncResponse(response, senderAddress)
    }

    override fun onSyncProgressReceived(progressJson: String, senderAddress: String) {
        Log.d(TAG, "Sync progress received from $senderAddress")
        syncManager.handleSyncProgress(progressJson, senderAddress)
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
}
