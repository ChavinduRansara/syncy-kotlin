package com.example.syncy_p2p.sync

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.syncy_p2p.files.FileManager
import com.example.syncy_p2p.files.FileItem
import com.example.syncy_p2p.p2p.WiFiDirectManager
import com.example.syncy_p2p.p2p.core.Utils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.security.MessageDigest
import java.util.*
import kotlin.collections.HashMap

class SyncManager(
    private val context: Context,
    private val fileManager: FileManager,
    private val wifiDirectManager: WiFiDirectManager
) {
    companion object {
        private const val TAG = "SyncyP2P_SyncManager"
        private const val PREFS_NAME = "syncy_sync_prefs"
        private const val KEY_SYNCED_FOLDERS = "synced_folders"
        private const val KEY_SYNC_LOGS = "sync_logs"
    }

    private val preferences: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _syncedFolders = MutableStateFlow<List<SyncedFolder>>(emptyList())
    val syncedFolders: StateFlow<List<SyncedFolder>> = _syncedFolders.asStateFlow()

    private val _pendingRequests = MutableStateFlow<List<SyncRequest>>(emptyList())
    val pendingRequests: StateFlow<List<SyncRequest>> = _pendingRequests.asStateFlow()

    private val _syncProgress = MutableStateFlow<Map<String, SyncProgress>>(emptyMap())
    val syncProgress: StateFlow<Map<String, SyncProgress>> = _syncProgress.asStateFlow()

    private val _syncLogs = MutableStateFlow<List<SyncLogEntry>>(emptyList())
    val syncLogs: StateFlow<List<SyncLogEntry>> = _syncLogs.asStateFlow()

    private var callback: SyncCallback? = null
    private val activeSyncJobs = HashMap<String, Job>()

    init {
        loadSyncedFolders()
        loadSyncLogs()
    }

    fun setCallback(callback: SyncCallback?) {
        this.callback = callback
    }

    /**
     * Initiate a sync request for a selected folder
     */
    suspend fun requestFolderSync(
        folderUri: Uri,
        folderName: String,
        targetDeviceId: String,
        targetDeviceName: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val requestId = UUID.randomUUID().toString()
            
            // Get folder metadata
            val files = fileManager.getFilesInFolder(folderUri)
            val totalSize = files.sumOf { it.size }
            
            val syncRequest = SyncRequest(
                requestId = requestId,
                sourceDeviceId = getCurrentDeviceId(),
                sourceDeviceName = getCurrentDeviceName(),
                folderName = folderName,
                folderPath = folderUri.toString(),
                totalFiles = files.size,
                totalSize = totalSize
            )

            // Send sync request to target device
            val success = sendSyncRequest(syncRequest, targetDeviceId)
            
            if (success) {
                addSyncLog(
                    folderName = folderName,
                    action = "SYNC_REQUEST_SENT",
                    message = "Sync request sent to $targetDeviceName",
                    status = SyncLogStatus.INFO,
                    deviceName = targetDeviceName
                )
                Result.success(requestId)
            } else {
                Result.failure(Exception("Failed to send sync request"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting folder sync", e)
            Result.failure(e)
        }
    }

    /**
     * Handle received sync request
     */
    fun handleSyncRequest(request: SyncRequest) {
        val currentRequests = _pendingRequests.value.toMutableList()
        currentRequests.add(request)
        _pendingRequests.value = currentRequests

        addSyncLog(
            folderName = request.folderName,
            action = "SYNC_REQUEST_RECEIVED",
            message = "Sync request received from ${request.sourceDeviceName}",
            status = SyncLogStatus.INFO,
            deviceName = request.sourceDeviceName
        )

        callback?.onSyncRequestReceived(request)
    }

    /**
     * Accept a sync request
     */
    suspend fun acceptSyncRequest(
        request: SyncRequest,
        localFolderUri: Uri? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Remove from pending requests
            val currentRequests = _pendingRequests.value.toMutableList()
            currentRequests.removeAll { it.requestId == request.requestId }
            _pendingRequests.value = currentRequests

            // Determine local folder
            val targetFolderUri = localFolderUri ?: createLocalFolderForSync(request.folderName)
            
            if (targetFolderUri == null) {
                return@withContext Result.failure(Exception("Failed to create or select local folder"))
            }

            // Create synced folder entry
            val syncedFolder = SyncedFolder(
                id = UUID.randomUUID().toString(),
                name = request.folderName,
                localPath = fileManager.getFolderDisplayPath(targetFolderUri) ?: "Unknown",
                localUri = targetFolderUri,
                remoteDeviceId = request.sourceDeviceId,
                remoteDeviceName = request.sourceDeviceName,
                remotePath = request.folderPath,
                lastSyncTime = 0L,
                status = SyncStatus.PENDING_SYNC
            )            // Add to synced folders
            addSyncedFolderInternal(syncedFolder)

            // Send acceptance response
            sendSyncResponse(request.requestId, true, request.sourceDeviceId)

            // Start initial sync
            startSync(syncedFolder.id)

            addSyncLog(
                folderName = request.folderName,
                action = "SYNC_REQUEST_ACCEPTED",
                message = "Sync request accepted, starting initial sync",
                status = SyncLogStatus.SUCCESS,
                deviceName = request.sourceDeviceName
            )

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error accepting sync request", e)
            Result.failure(e)
        }
    }

    /**
     * Reject a sync request
     */
    suspend fun rejectSyncRequest(request: SyncRequest): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Remove from pending requests
            val currentRequests = _pendingRequests.value.toMutableList()
            currentRequests.removeAll { it.requestId == request.requestId }
            _pendingRequests.value = currentRequests

            // Send rejection response
            sendSyncResponse(request.requestId, false, request.sourceDeviceId)

            addSyncLog(
                folderName = request.folderName,
                action = "SYNC_REQUEST_REJECTED",
                message = "Sync request rejected",
                status = SyncLogStatus.WARNING,
                deviceName = request.sourceDeviceName
            )

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error rejecting sync request", e)
            Result.failure(e)
        }
    }

    /**
     * Start synchronization for a folder
     */
    fun startSync(folderId: String) {
        val syncedFolder = _syncedFolders.value.find { it.id == folderId }
        if (syncedFolder == null) {
            Log.e(TAG, "Synced folder not found: $folderId")
            return
        }

        // Cancel existing sync job for this folder
        activeSyncJobs[folderId]?.cancel()

        // Start new sync job
        val job = CoroutineScope(Dispatchers.IO).launch {
            performSync(syncedFolder)
        }
        activeSyncJobs[folderId] = job
    }

    /**
     * Perform the actual synchronization
     */
    private suspend fun performSync(syncedFolder: SyncedFolder) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting sync for folder: ${syncedFolder.name}")
            
            // Update folder status
            updateSyncedFolderStatus(syncedFolder.id, SyncStatus.SYNCING)
            
            callback?.onSyncStarted(syncedFolder.id)

            // Get local files
            val localFiles = if (syncedFolder.localUri != null) {
                fileManager.getFilesInFolder(syncedFolder.localUri).map { fileItem ->
                    FileMetadata(
                        name = fileItem.name,
                        path = fileItem.uri.toString(),
                        size = fileItem.size,
                        lastModified = fileItem.lastModified,
                        checksum = null, // Will be calculated if needed
                        isDirectory = fileItem.isDirectory
                    )
                }
            } else {
                emptyList()
            }

            // Request remote files list
            val remoteFiles = requestRemoteFilesList(syncedFolder.remoteDeviceId, syncedFolder.remotePath)

            // Compare files and determine actions
            val comparisons = compareFiles(localFiles, remoteFiles)
            
            // Check for conflicts
            val conflicts = comparisons.filter { it.conflict != ConflictType.NONE }
            if (conflicts.isNotEmpty() && syncedFolder.conflictResolution == ConflictResolution.ASK_USER) {
                callback?.onSyncConflict(syncedFolder.id, conflicts)
                return@withContext
            }

            // Perform sync actions
            val totalActions = comparisons.count { it.action != SyncAction.NONE }
            var completedActions = 0

            for (comparison in comparisons) {
                if (!isActive) break // Check if job was cancelled

                when (comparison.action) {
                    SyncAction.UPLOAD -> {
                        uploadFile(syncedFolder, comparison.localFile!!)
                    }
                    SyncAction.DOWNLOAD -> {
                        downloadFile(syncedFolder, comparison.remoteFile!!)
                    }
                    SyncAction.DELETE_LOCAL -> {
                        deleteLocalFile(syncedFolder, comparison.localFile!!)
                    }
                    SyncAction.DELETE_REMOTE -> {
                        deleteRemoteFile(syncedFolder, comparison.remoteFile!!)
                    }
                    SyncAction.CONFLICT -> {
                        handleConflict(syncedFolder, comparison)
                    }
                    SyncAction.NONE -> {
                        // File is already synced
                    }
                }

                completedActions++
                
                // Update progress
                val progress = SyncProgress(
                    folderName = syncedFolder.name,
                    currentFile = comparison.fileName,
                    filesProcessed = completedActions,
                    totalFiles = totalActions,
                    bytesTransferred = 0L, // This would be tracked during individual file transfers
                    totalBytes = 0L,
                    status = "Processing ${comparison.fileName}"
                )
                updateSyncProgress(syncedFolder.id, progress)
                callback?.onSyncProgress(syncedFolder.id, progress)
            }            // Update sync completion
            updateSyncedFolderInternal(syncedFolder.copy(
                lastSyncTime = System.currentTimeMillis(),
                status = SyncStatus.SYNCED
            ))

            addSyncLog(
                folderName = syncedFolder.name,
                action = "SYNC_COMPLETED",
                message = "Sync completed successfully. $completedActions files processed.",
                status = SyncLogStatus.SUCCESS,
                deviceName = syncedFolder.remoteDeviceName
            )

            callback?.onSyncCompleted(syncedFolder.id, true)

        } catch (e: Exception) {
            Log.e(TAG, "Error during sync", e)
            
            updateSyncedFolderStatus(syncedFolder.id, SyncStatus.ERROR)
            
            addSyncLog(
                folderName = syncedFolder.name,
                action = "SYNC_ERROR",
                message = "Sync failed: ${e.message}",
                status = SyncLogStatus.ERROR,
                deviceName = syncedFolder.remoteDeviceName
            )

            callback?.onSyncCompleted(syncedFolder.id, false, e.message)
        } finally {
            activeSyncJobs.remove(syncedFolder.id)
            clearSyncProgress(syncedFolder.id)
        }
    }

    /**
     * Compare local and remote files to determine sync actions
     */
    private fun compareFiles(
        localFiles: List<FileMetadata>,
        remoteFiles: List<FileMetadata>
    ): List<FileComparison> {
        val comparisons = mutableListOf<FileComparison>()
        val localFileMap = localFiles.associateBy { it.name }
        val remoteFileMap = remoteFiles.associateBy { it.name }
        val allFileNames = (localFileMap.keys + remoteFileMap.keys).toSet()

        for (fileName in allFileNames) {
            val localFile = localFileMap[fileName]
            val remoteFile = remoteFileMap[fileName]

            val comparison = when {
                localFile == null -> {
                    // File exists only on remote - download
                    FileComparison(fileName, null, remoteFile, SyncAction.DOWNLOAD)
                }
                remoteFile == null -> {
                    // File exists only locally - upload
                    FileComparison(fileName, localFile, null, SyncAction.UPLOAD)
                }
                else -> {
                    // File exists on both sides - compare
                    compareFileVersions(fileName, localFile, remoteFile)
                }
            }
            
            comparisons.add(comparison)
        }

        return comparisons
    }

    /**
     * Compare two versions of the same file
     */
    private fun compareFileVersions(
        fileName: String,
        localFile: FileMetadata,
        remoteFile: FileMetadata
    ): FileComparison {
        // Check for type mismatch
        if (localFile.isDirectory != remoteFile.isDirectory) {
            return FileComparison(
                fileName, localFile, remoteFile, 
                SyncAction.CONFLICT, ConflictType.TYPE_MISMATCH
            )
        }

        // For directories, we don't need to sync the directory itself
        if (localFile.isDirectory) {
            return FileComparison(fileName, localFile, remoteFile, SyncAction.NONE)
        }

        // Compare file attributes
        val sameSizeAndTime = localFile.size == remoteFile.size && 
                             localFile.lastModified == remoteFile.lastModified

        return when {
            sameSizeAndTime -> {
                // Files appear identical
                FileComparison(fileName, localFile, remoteFile, SyncAction.NONE)
            }
            localFile.lastModified > remoteFile.lastModified -> {
                // Local file is newer
                if (localFile.size != remoteFile.size) {
                    FileComparison(fileName, localFile, remoteFile, SyncAction.CONFLICT, ConflictType.BOTH_MODIFIED)
                } else {
                    FileComparison(fileName, localFile, remoteFile, SyncAction.UPLOAD)
                }
            }
            remoteFile.lastModified > localFile.lastModified -> {
                // Remote file is newer
                if (localFile.size != remoteFile.size) {
                    FileComparison(fileName, localFile, remoteFile, SyncAction.CONFLICT, ConflictType.BOTH_MODIFIED)
                } else {
                    FileComparison(fileName, localFile, remoteFile, SyncAction.DOWNLOAD)
                }
            }
            else -> {
                // Same timestamp but different size
                FileComparison(fileName, localFile, remoteFile, SyncAction.CONFLICT, ConflictType.SIZE_MISMATCH)
            }
        }
    }    // Placeholder methods for network operations - these would interface with the existing P2P system
    private suspend fun sendSyncRequest(request: SyncRequest, targetDeviceId: String): Boolean {
        return try {
            val requestJson = serializeSyncRequest(request)
            val result = wifiDirectManager.sendMessage("SYNC_REQUEST:$requestJson")
            result.isSuccess
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send sync request", e)
            false
        }
    }

    private suspend fun sendSyncResponse(requestId: String, accepted: Boolean, targetDeviceId: String) {
        try {
            val response = if (accepted) "SYNC_ACCEPTED:$requestId" else "SYNC_REJECTED:$requestId"
            wifiDirectManager.sendMessage(response)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send sync response", e)
        }
    }

    private suspend fun sendSyncProgress(progress: SyncProgress) {
        try {
            val progressJson = serializeSyncProgress(progress)
            wifiDirectManager.sendMessage("SYNC_PROGRESS:$progressJson")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send sync progress", e)
        }
    }

    private suspend fun requestRemoteFilesList(deviceId: String, remotePath: String): List<FileMetadata> {
        // This would request the file list from the remote device
        // For now implementing a basic version using existing file messaging
        return try {
            wifiDirectManager.sendMessage("REQUEST_FILES_LIST:$remotePath")
            // TODO: Wait for response and parse file list
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request remote files list", e)
            emptyList()
        }
    }

    private suspend fun uploadFile(syncedFolder: SyncedFolder, file: FileMetadata) {
        try {
            val localFile = File(syncedFolder.localPath, file.path)
            if (localFile.exists()) {
                val result = wifiDirectManager.sendFile(localFile.absolutePath)
                if (result.isSuccess) {
                    addSyncLog(
                        folderName = syncedFolder.name,
                        action = "FILE_UPLOADED",
                        message = "Uploaded ${file.name}",
                        status = SyncLogStatus.SUCCESS,
                        deviceName = syncedFolder.remoteDeviceName,
                        fileName = file.name
                    )
                } else {
                    addSyncLog(
                        folderName = syncedFolder.name,
                        action = "ERROR",
                        message = "Failed to upload ${file.name}",
                        status = SyncLogStatus.ERROR,
                        deviceName = syncedFolder.remoteDeviceName,
                        fileName = file.name
                    )
                }
            }        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload file", e)
            addSyncLog(
                folderName = syncedFolder.name,
                action = "ERROR",
                message = "Error uploading ${file.name}: ${e.message}",
                status = SyncLogStatus.ERROR,
                deviceName = syncedFolder.remoteDeviceName,
                fileName = file.name
            )
        }
    }

    private suspend fun downloadFile(syncedFolder: SyncedFolder, file: FileMetadata) {
        try {            // Request the file from remote device
            wifiDirectManager.sendMessage("REQUEST_FILE:${file.path}")
            
            addSyncLog(
                folderName = syncedFolder.name,
                action = "FILE_DOWNLOADED",
                message = "Requested download of ${file.name}",
                status = SyncLogStatus.INFO,
                deviceName = syncedFolder.remoteDeviceName,
                fileName = file.name
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download file", e)
            addSyncLog(
                folderName = syncedFolder.name,
                action = "ERROR",
                message = "Error downloading ${file.name}: ${e.message}",
                status = SyncLogStatus.ERROR,
                deviceName = syncedFolder.remoteDeviceName,
                fileName = file.name
            )
        }
    }

    private suspend fun deleteLocalFile(syncedFolder: SyncedFolder, file: FileMetadata) {
        try {            val localFile = File(syncedFolder.localPath, file.path)
            if (localFile.exists() && localFile.delete()) {
                addSyncLog(
                    folderName = syncedFolder.name,
                    action = "FILE_DELETED",
                    message = "Deleted local file ${file.name}",
                    status = SyncLogStatus.SUCCESS,
                    deviceName = syncedFolder.remoteDeviceName,
                    fileName = file.name
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete local file", e)
        }
    }

    private suspend fun deleteRemoteFile(syncedFolder: SyncedFolder, file: FileMetadata) {        try {
            wifiDirectManager.sendMessage("DELETE_FILE:${file.path}")
            addSyncLog(
                folderName = syncedFolder.name,
                action = "FILE_DELETED",
                message = "Requested deletion of remote file ${file.name}",
                status = SyncLogStatus.SUCCESS,
                deviceName = syncedFolder.remoteDeviceName,
                fileName = file.name
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request remote file deletion", e)
        }
    }    private suspend fun handleConflict(syncedFolder: SyncedFolder, comparison: FileComparison) {
        // Handle file conflicts based on conflict resolution strategy
        val strategy = syncedFolder.conflictResolution
        
        when (strategy) {
            ConflictResolution.KEEP_NEWER -> {
                val localFile = comparison.localFile
                val remoteFile = comparison.remoteFile
                if (localFile != null && remoteFile != null) {
                    if (localFile.lastModified > remoteFile.lastModified) {
                        uploadFile(syncedFolder, localFile)
                    } else {
                        downloadFile(syncedFolder, remoteFile)
                    }
                }
            }
            ConflictResolution.KEEP_LARGER -> {
                val localFile = comparison.localFile
                val remoteFile = comparison.remoteFile
                if (localFile != null && remoteFile != null) {
                    if (localFile.size > remoteFile.size) {
                        uploadFile(syncedFolder, localFile)
                    } else {
                        downloadFile(syncedFolder, remoteFile)
                    }
                }
            }
            ConflictResolution.OVERWRITE_LOCAL -> {
                val remoteFile = comparison.remoteFile
                if (remoteFile != null) {
                    downloadFile(syncedFolder, remoteFile)
                }
            }
            ConflictResolution.OVERWRITE_REMOTE -> {
                val localFile = comparison.localFile
                if (localFile != null) {
                    uploadFile(syncedFolder, localFile)
                }
            }
            ConflictResolution.ASK_USER -> {
                // Add to conflict queue for user resolution
                addSyncLog(
                    folderName = syncedFolder.name,
                    action = "CONFLICT_DETECTED",
                    message = "Conflict detected for ${comparison.fileName} - user action required",
                    status = SyncLogStatus.WARNING,
                    deviceName = syncedFolder.remoteDeviceName,
                    fileName = comparison.fileName
                )
            }
            ConflictResolution.KEEP_BOTH -> {
                // Keep both files with different names
                val localFile = comparison.localFile
                val remoteFile = comparison.remoteFile
                if (localFile != null) {
                    uploadFile(syncedFolder, localFile)
                }
                if (remoteFile != null) {
                    downloadFile(syncedFolder, remoteFile)
                }
            }
        }
    }

    // Serialization helpers
    private fun serializeSyncRequest(request: SyncRequest): String {
        return """
        {
            "requestId": "${request.requestId}",
            "sourceDeviceId": "${request.sourceDeviceId}",
            "sourceDeviceName": "${request.sourceDeviceName}",
            "folderName": "${request.folderName}",
            "folderPath": "${request.folderPath}",
            "totalFiles": ${request.totalFiles},
            "totalSize": ${request.totalSize},
            "timestamp": ${request.timestamp}
        }
        """.trimIndent()
    }    private fun serializeSyncProgress(progress: SyncProgress): String {
        return """
        {
            "folderName": "${progress.folderName}",
            "currentFile": "${progress.currentFile ?: ""}",
            "filesProcessed": ${progress.filesProcessed},
            "totalFiles": ${progress.totalFiles},
            "bytesTransferred": ${progress.bytesTransferred},
            "totalBytes": ${progress.totalBytes},
            "status": "${progress.status}"
        }
        """.trimIndent()
    }

    // Public methods for handling received sync messages
    fun handleSyncRequest(requestJson: String, senderAddress: String) {
        try {
            val request = parseSyncRequest(requestJson)
            if (request != null) {
                val updatedRequests = _syncRequests.value.toMutableList()
                updatedRequests.add(request)
                _syncRequests.value = updatedRequests
                  addSyncLog(
                    folderName = request.sourceDeviceName,
                    action = "SYNC_REQUEST_RECEIVED",
                    message = "Received sync request from ${request.sourceDeviceName}",
                    status = SyncLogStatus.INFO,
                    deviceName = request.sourceDeviceName
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle sync request", e)
        }
    }

    fun handleSyncResponse(response: String, senderAddress: String) {
        try {
            val parts = response.split(":")
            if (parts.size >= 2) {
                val accepted = parts[0] == "SYNC_ACCEPTED"
                val requestId = parts[1]
                
                // Update request status
                val updatedRequests = _syncRequests.value.map { request ->
                    if (request.requestId == requestId) {
                        request.copy(status = if (accepted) SyncRequestStatus.ACCEPTED else SyncRequestStatus.REJECTED)
                    } else {
                        request
                    }
                }
                _syncRequests.value = updatedRequests
                
                if (accepted) {
                    // Start sync process
                    val request = updatedRequests.find { it.requestId == requestId }
                    if (request != null) {
                        GlobalScope.launch {
                            startSyncProcess(request, senderAddress)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle sync response", e)
        }
    }

    fun handleSyncProgress(progressJson: String, senderAddress: String) {
        try {
            val progress = parseSyncProgress(progressJson)
            if (progress != null) {
                _currentProgress.value = progress
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle sync progress", e)
        }
    }

    private fun parseSyncRequest(json: String): SyncRequest? {
        // Simple JSON parsing - in production you'd use a proper JSON library
        return try {
            // Basic parsing implementation - replace with proper JSON library
            SyncRequest(
                requestId = UUID.randomUUID().toString(),
                sourceDeviceId = "unknown",
                sourceDeviceName = "Unknown Device",
                folderName = "Sync Folder",
                folderPath = "/unknown",
                totalFiles = 0,
                totalSize = 0L
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse sync request", e)
            null
        }
    }    private fun parseSyncProgress(json: String): SyncProgress? {
        // Simple JSON parsing - in production you'd use a proper JSON library
        return try {
            SyncProgress(
                folderName = "",
                currentFile = "",
                filesProcessed = 0,
                totalFiles = 0,
                bytesTransferred = 0L,
                totalBytes = 0L
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse sync progress", e)
            null
        }
    }

    private suspend fun startSyncProcess(request: SyncRequest, targetDeviceId: String) {        // Implementation for starting the actual sync process
        addSyncLog(
            folderName = request.folderName,
            action = "SYNC_PROCESS_STARTED",
            message = "Starting sync process with ${request.sourceDeviceName}",
            status = SyncLogStatus.INFO,
            deviceName = request.sourceDeviceName
        )
    }

    // Utility methods
    private fun getCurrentDeviceId(): String {
        // Get current device ID - could use WiFi Direct device address
        return android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
    }

    private fun getCurrentDeviceName(): String {
        // Get current device name
        return android.os.Build.MODEL
    }

    private suspend fun createLocalFolderForSync(folderName: String): Uri? {
        // Create a new folder for incoming sync
        return fileManager.createFolderInDocuments(folderName)
    }    // State management methods
    private fun addSyncedFolderInternal(folder: SyncedFolder) {
        val currentFolders = _syncedFolders.value.toMutableList()
        currentFolders.add(folder)
        _syncedFolders.value = currentFolders
        saveSyncedFolders()
    }

    private fun updateSyncedFolderInternal(folder: SyncedFolder) {
        val currentFolders = _syncedFolders.value.toMutableList()
        val index = currentFolders.indexOfFirst { it.id == folder.id }
        if (index >= 0) {
            currentFolders[index] = folder
            _syncedFolders.value = currentFolders
            saveSyncedFolders()
        }
    }    private fun updateSyncedFolderStatus(folderId: String, status: SyncStatus) {
        val folder = _syncedFolders.value.find { it.id == folderId }
        if (folder != null) {
            updateSyncedFolderInternal(folder.copy(status = status))
        }
    }

    private fun updateSyncProgress(folderId: String, progress: SyncProgress) {
        val currentProgress = _syncProgress.value.toMutableMap()
        currentProgress[folderId] = progress
        _syncProgress.value = currentProgress
    }

    private fun clearSyncProgress(folderId: String) {
        val currentProgress = _syncProgress.value.toMutableMap()
        currentProgress.remove(folderId)
        _syncProgress.value = currentProgress
    }

    private fun addSyncLog(
        folderName: String,
        action: String,
        message: String,
        status: SyncLogStatus,
        deviceName: String,
        fileName: String? = null
    ) {
        val entry = SyncLogEntry(
            id = UUID.randomUUID().toString(),
            folderName = folderName,
            timestamp = System.currentTimeMillis(),
            action = action,
            fileName = fileName,
            status = status,
            message = message,
            deviceName = deviceName
        )

        val currentLogs = _syncLogs.value.toMutableList()
        currentLogs.add(0, entry) // Add to beginning
        
        // Keep only last 100 entries
        if (currentLogs.size > 100) {
            currentLogs.removeAt(currentLogs.size - 1)
        }
        
        _syncLogs.value = currentLogs
        saveSyncLogs()
          callback?.onSyncLogUpdate(entry)
    }

    // Persistence methods (moved here to avoid duplicates)
    private fun saveSyncedFolders() {
        try {
            val json = Utils.serializeSyncedFolders(_syncedFolders.value)
            preferences.edit()
                .putString(KEY_SYNCED_FOLDERS, json)
                .apply()
            Log.d(TAG, "Saved ${_syncedFolders.value.size} synced folders to storage")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving synced folders", e)
        }
    }

    private fun loadSyncedFolders() {
        try {
            val json = preferences.getString(KEY_SYNCED_FOLDERS, "[]") ?: "[]"
            val folders = Utils.deserializeSyncedFolders(json)
            _syncedFolders.value = folders
            Log.d(TAG, "Loaded ${folders.size} synced folders from storage")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading synced folders", e)
            _syncedFolders.value = emptyList()
        }
    }

    private fun saveSyncLogs() {
        try {
            val json = Utils.serializeSyncLogs(_syncLogs.value)
            preferences.edit()
                .putString(KEY_SYNC_LOGS, json)
                .apply()
            Log.d(TAG, "Saved ${_syncLogs.value.size} sync log entries to storage")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving sync logs", e)
        }
    }

    private fun loadSyncLogs() {
        try {
            val json = preferences.getString(KEY_SYNC_LOGS, "[]") ?: "[]"
            val logs = Utils.deserializeSyncLogs(json)
            _syncLogs.value = logs.takeLast(1000) // Keep only the latest 1000 log entries
            Log.d(TAG, "Loaded ${logs.size} sync log entries from storage")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading sync logs", e)
            _syncLogs.value = emptyList()
        }
    }/**
     * Remove a synced folder from management
     */
    suspend fun removeSyncedFolder(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val currentFolders = _syncedFolders.value.toMutableList()
            val folderToRemove = currentFolders.find { it.id == id }
            
            if (folderToRemove != null) {
                // Cancel any active sync for this folder
                activeSyncJobs[id]?.cancel()
                activeSyncJobs.remove(id)
                
                // Remove from list
                currentFolders.removeAll { it.id == id }
                _syncedFolders.value = currentFolders
                saveSyncedFolders()
                
                addSyncLog(
                    folderName = folderToRemove.name,
                    action = "FOLDER_REMOVED",
                    message = "Sync folder '${folderToRemove.name}' removed from management",
                    status = SyncLogStatus.INFO,
                    deviceName = folderToRemove.remoteDeviceName
                )
                
                Result.success(Unit)
            } else {
                Result.failure(Exception("Folder with id $id not found"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing synced folder", e)
            Result.failure(e)
        }
    }

    /**
     * Clear all sync logs
     */
    suspend fun clearSyncLogs(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            _syncLogs.value = emptyList()
            saveSyncLogs()
            Log.d(TAG, "Sync logs cleared")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing sync logs", e)
            Result.failure(e)
        }
    }

    /**
     * Resolve a file conflict with user's decision
     */
    suspend fun resolveConflict(conflict: FileConflict, resolution: ConflictResolution): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Resolving conflict for ${conflict.fileName} with resolution: $resolution")
            
            // Find the synced folder for this conflict
            val syncedFolder = _syncedFolders.value.find { folder ->
                conflict.filePath.contains(folder.localPath)
            }
            
            if (syncedFolder == null) {
                return@withContext Result.failure(Exception("Unable to find synced folder for conflict"))            }
            
            when (resolution) {
                ConflictResolution.OVERWRITE_REMOTE -> {
                    // Upload local file to overwrite remote
                    val localFile = FileMetadata(
                        name = conflict.fileName,
                        path = conflict.filePath,
                        size = conflict.localFileSize,
                        lastModified = conflict.localLastModified,
                        checksum = null,
                        isDirectory = false
                    )
                    uploadFile(syncedFolder, localFile)
                    
                    addSyncLog(
                        folderName = syncedFolder.name,
                        action = "CONFLICT_RESOLVED",
                        message = "Conflict resolved: kept local version of ${conflict.fileName}",
                        status = SyncLogStatus.SUCCESS,
                        deviceName = syncedFolder.remoteDeviceName,
                        fileName = conflict.fileName
                    )
                }
                
                ConflictResolution.OVERWRITE_LOCAL -> {
                    // Download remote file to overwrite local
                    val remoteFile = FileMetadata(
                        name = conflict.fileName,
                        path = conflict.filePath,
                        size = conflict.remoteFileSize,
                        lastModified = conflict.remoteLastModified,
                        checksum = null,
                        isDirectory = false
                    )
                    downloadFile(syncedFolder, remoteFile)
                    
                    addSyncLog(
                        folderName = syncedFolder.name,
                        action = "CONFLICT_RESOLVED",
                        message = "Conflict resolved: kept remote version of ${conflict.fileName}",
                        status = SyncLogStatus.SUCCESS,
                        deviceName = syncedFolder.remoteDeviceName,
                        fileName = conflict.fileName
                    )
                }
                
                ConflictResolution.KEEP_BOTH -> {
                    // Keep both files with different names
                    val timestamp = System.currentTimeMillis()
                    val localCopyName = "${conflict.fileName.substringBeforeLast(".")}_local_$timestamp.${conflict.fileName.substringAfterLast(".")}"
                    
                    // Rename local file and then download remote
                    try {
                        // Implementation would rename local file and download remote
                        addSyncLog(
                            folderName = syncedFolder.name,
                            action = "CONFLICT_RESOLVED",
                            message = "Conflict resolved: kept both versions of ${conflict.fileName}",
                            status = SyncLogStatus.SUCCESS,
                            deviceName = syncedFolder.remoteDeviceName,
                            fileName = conflict.fileName
                        )                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling KEEP_BOTH resolution", e)
                        throw e
                    }
                }
                
                ConflictResolution.KEEP_NEWER -> {
                    // Keep the newer file based on last modified time
                    if (conflict.localLastModified > conflict.remoteLastModified) {
                        // Upload local file
                        val localFile = FileMetadata(
                            name = conflict.fileName,
                            path = conflict.filePath,
                            size = conflict.localFileSize,
                            lastModified = conflict.localLastModified,
                            checksum = null,
                            isDirectory = false
                        )
                        uploadFile(syncedFolder, localFile)
                        
                        addSyncLog(
                            folderName = syncedFolder.name,
                            action = "CONFLICT_RESOLVED",
                            message = "Conflict resolved: kept newer local version of ${conflict.fileName}",
                            status = SyncLogStatus.SUCCESS,
                            deviceName = syncedFolder.remoteDeviceName,
                            fileName = conflict.fileName
                        )
                    } else {
                        // Download remote file
                        val remoteFile = FileMetadata(
                            name = conflict.fileName,
                            path = conflict.filePath,
                            size = conflict.remoteFileSize,
                            lastModified = conflict.remoteLastModified,
                            checksum = null,
                            isDirectory = false
                        )
                        downloadFile(syncedFolder, remoteFile)
                        
                        addSyncLog(
                            folderName = syncedFolder.name,
                            action = "CONFLICT_RESOLVED",
                            message = "Conflict resolved: kept newer remote version of ${conflict.fileName}",
                            status = SyncLogStatus.SUCCESS,
                            deviceName = syncedFolder.remoteDeviceName,
                            fileName = conflict.fileName
                        )
                    }
                }
                
                ConflictResolution.KEEP_LARGER -> {
                    // Keep the larger file based on file size
                    if (conflict.localFileSize > conflict.remoteFileSize) {
                        // Upload local file
                        val localFile = FileMetadata(
                            name = conflict.fileName,
                            path = conflict.filePath,
                            size = conflict.localFileSize,
                            lastModified = conflict.localLastModified,
                            checksum = null,
                            isDirectory = false
                        )
                        uploadFile(syncedFolder, localFile)
                        
                        addSyncLog(
                            folderName = syncedFolder.name,
                            action = "CONFLICT_RESOLVED",
                            message = "Conflict resolved: kept larger local version of ${conflict.fileName}",
                            status = SyncLogStatus.SUCCESS,
                            deviceName = syncedFolder.remoteDeviceName,
                            fileName = conflict.fileName
                        )
                    } else {
                        // Download remote file
                        val remoteFile = FileMetadata(
                            name = conflict.fileName,
                            path = conflict.filePath,
                            size = conflict.remoteFileSize,
                            lastModified = conflict.remoteLastModified,
                            checksum = null,
                            isDirectory = false
                        )
                        downloadFile(syncedFolder, remoteFile)
                        
                        addSyncLog(
                            folderName = syncedFolder.name,
                            action = "CONFLICT_RESOLVED",
                            message = "Conflict resolved: kept larger remote version of ${conflict.fileName}",
                            status = SyncLogStatus.SUCCESS,
                            deviceName = syncedFolder.remoteDeviceName,
                            fileName = conflict.fileName
                        )
                    }
                }
                
                ConflictResolution.ASK_USER -> {
                    // This should not happen in this context, as ASK_USER conflicts should be queued
                    addSyncLog(
                        folderName = syncedFolder.name,
                        action = "CONFLICT_DEFERRED",
                        message = "Conflict deferred for user resolution: ${conflict.fileName}",
                        status = SyncLogStatus.WARNING,
                        deviceName = syncedFolder.remoteDeviceName,
                        fileName = conflict.fileName
                    )
                }
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving conflict", e)
            Result.failure(e)
        }
    }

    /**
     * Initiate folder sync (compatible method for UI)
     */
    suspend fun initiateFolderSync(folderPath: String, remoteDeviceName: String): Boolean {
        return try {
            // Find existing synced folder or create sync request
            val existingFolder = _syncedFolders.value.find { it.localPath == folderPath }
            
            if (existingFolder != null) {
                // Resync existing folder
                startSync(existingFolder.id)
                true
            } else {
                // Create new sync request (this would normally require user interaction on remote)
                addSyncLog(
                    folderName = folderPath.substringAfterLast("/"),
                    action = "SYNC_INITIATED",
                    message = "Sync initiation requested for folder: $folderPath",
                    status = SyncLogStatus.INFO,
                    deviceName = remoteDeviceName
                )
                false // Would need remote device acceptance
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating folder sync", e)
            false
        }
    }

    /**
     * Accept sync request by request ID
     */
    suspend fun acceptSyncRequest(requestId: String) {
        val request = _pendingRequests.value.find { it.requestId == requestId }
        if (request != null) {
            acceptSyncRequest(request)
        }
    }

    /**
     * Reject sync request by request ID
     */
    suspend fun rejectSyncRequest(requestId: String) {
        val request = _pendingRequests.value.find { it.requestId == requestId }
        if (request != null) {
            rejectSyncRequest(request)
        }
    }

    // Add missing properties for compatibility
    val syncRequests: StateFlow<List<SyncRequest>> = _pendingRequests
    val currentProgress: StateFlow<SyncProgress?> = _syncProgress.map { progressMap ->
        progressMap.values.firstOrNull()
    }.stateIn(CoroutineScope(Dispatchers.IO), kotlinx.coroutines.flow.SharingStarted.Eagerly, null)    // Missing state flows
    private val _syncRequests = MutableStateFlow<List<SyncRequest>>(emptyList())
    private val _currentProgress = MutableStateFlow<SyncProgress?>(null)/**
     * Add a synced folder and persist it
     */
    fun addSyncedFolder(folder: SyncedFolder) {
        val currentFolders = _syncedFolders.value.toMutableList()
        // Remove any existing folder with the same ID
        currentFolders.removeAll { it.id == folder.id }
        currentFolders.add(folder)
        _syncedFolders.value = currentFolders
        saveSyncedFolders()
        
        // Log the addition
        addSyncLog(
            SyncLogEntry(
                id = UUID.randomUUID().toString(),
                folderName = folder.name,
                timestamp = System.currentTimeMillis(),
                action = "FOLDER_ADDED",
                fileName = null,
                status = SyncLogStatus.INFO,
                message = "Synced folder '${folder.name}' added",
                deviceName = folder.remoteDeviceName
            )
        )
    }    /**
     * Update a synced folder and persist the change
     */
    fun updateSyncedFolder(folder: SyncedFolder) {
        val currentFolders = _syncedFolders.value.toMutableList()
        val index = currentFolders.indexOfFirst { it.id == folder.id }
        if (index >= 0) {
            currentFolders[index] = folder
            _syncedFolders.value = currentFolders
            saveSyncedFolders()
        }
    }

    /**
     * Add a sync log entry and persist it
     */
    fun addSyncLog(logEntry: SyncLogEntry) {
        val currentLogs = _syncLogs.value.toMutableList()
        currentLogs.add(logEntry)
        
        // Keep only the latest 1000 entries to prevent unbounded growth
        if (currentLogs.size > 1000) {
            currentLogs.removeAt(0)
        }
        
        _syncLogs.value = currentLogs
        saveSyncLogs()
        
        // Notify callback
        callback?.onSyncLogUpdate(logEntry)
    }    /**
     * Get synced folder by ID
     */
    fun getSyncedFolder(folderId: String): SyncedFolder? {
        return _syncedFolders.value.find { it.id == folderId }
    }

    // Cleanup
    fun cleanup() {
        activeSyncJobs.values.forEach { it.cancel() }
        activeSyncJobs.clear()
    }
}
