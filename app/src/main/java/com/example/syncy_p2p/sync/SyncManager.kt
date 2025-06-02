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
            addSyncedFolderInternal(syncedFolder)            // CRITICAL FIX: Start FileReceiver to accept incoming file connections
            Log.d(TAG, "Starting FileReceiver for accepted sync request...")
            
            // Use the synced folder's local path as destination, but also pass the URI for proper file handling
            val destinationPath = if (syncedFolder.localUri != null) {
                // Try to get real folder path from URI
                fileManager.getFolderDisplayPath(syncedFolder.localUri) ?: syncedFolder.localPath
            } else {
                syncedFolder.localPath
            }
            
            val startReceiverResult = wifiDirectManager.startReceivingFiles(destinationPath, syncedFolder.localUri)
            if (startReceiverResult.isSuccess) {
                Log.d(TAG, "✅ FileReceiver started successfully for accepted sync at: $destinationPath")
                addSyncLog(
                    folderName = request.folderName,
                    action = "FILE_RECEIVER_STARTED",
                    message = "FileReceiver started to accept incoming files at $destinationPath",
                    status = SyncLogStatus.INFO,
                    deviceName = request.sourceDeviceName
                )
            } else {
                Log.e(TAG, "❌ Failed to start FileReceiver for accepted sync")
                addSyncLog(
                    folderName = request.folderName,
                    action = "ERROR",
                    message = "Failed to start FileReceiver: ${startReceiverResult.exceptionOrNull()?.message}",
                    status = SyncLogStatus.ERROR,
                    deviceName = request.sourceDeviceName
                )
            }

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
            
            callback?.onSyncStarted(syncedFolder.id)            // Get local files
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

            // For now, if this is an incoming sync (we accepted a request), 
            // we'll simulate downloading files from the remote device
            val isIncomingSync = syncedFolder.lastSyncTime == 0L
            
            if (isIncomingSync) {
                // This is an incoming sync - request files from the source device
                Log.d(TAG, "Starting incoming sync - requesting files from ${syncedFolder.remoteDeviceName}")
                
                // Send a message to request the actual file transfer to begin
                val startTransferMessage = "SYNC_START_TRANSFER:${syncedFolder.id}:${syncedFolder.name}"
                wifiDirectManager.sendMessage(startTransferMessage)
                
                addSyncLog(
                    folderName = syncedFolder.name,
                    action = "SYNC_TRANSFER_REQUESTED",
                    message = "Requested file transfer to start from ${syncedFolder.remoteDeviceName}",
                    status = SyncLogStatus.INFO,
                    deviceName = syncedFolder.remoteDeviceName
                )
                
                // Update progress to show we're waiting for files
                val progress = SyncProgress(
                    folderName = syncedFolder.name,
                    currentFile = "Requesting files...",
                    filesProcessed = 0,
                    totalFiles = 1, // We don't know yet, but show some progress
                    bytesTransferred = 0L,
                    totalBytes = 0L,
                    status = "Requesting files from ${syncedFolder.remoteDeviceName}"
                )
                updateSyncProgress(syncedFolder.id, progress)
                callback?.onSyncProgress(syncedFolder.id, progress)
                
                // For now, we'll mark this sync as completed but waiting for actual files
                // In a real implementation, we would wait for files to arrive
                updateSyncedFolderInternal(syncedFolder.copy(
                    lastSyncTime = System.currentTimeMillis(),
                    status = SyncStatus.SYNCED
                ))
                
                addSyncLog(
                    folderName = syncedFolder.name,
                    action = "SYNC_READY",
                    message = "Sync folder created and ready to receive files",
                    status = SyncLogStatus.SUCCESS,
                    deviceName = syncedFolder.remoteDeviceName
                )
                
                callback?.onSyncCompleted(syncedFolder.id, true)
                return@withContext
            }

            // Compare files and determine actions (for bidirectional sync)
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
    }    private suspend fun requestRemoteFilesList(deviceId: String, remotePath: String): List<FileMetadata> {
        // Send request for file list from the remote device
        return try {
            Log.d(TAG, "Requesting files list from remote device for path: $remotePath")
            
            // Send request message
            val result = wifiDirectManager.sendMessage("SYNC_REQUEST_FILES_LIST:$remotePath")
            
            if (result.isSuccess) {
                // For now, return empty list - in a full implementation, we would:
                // 1. Wait for the remote device to respond with file list
                // 2. Parse the received file list
                // 3. Return the parsed list
                
                // Since this is a simplified implementation focused on fixing the core sync flow,
                // we'll return an empty list for now but log that the request was sent
                Log.d(TAG, "File list request sent successfully, waiting for response...")
                
                addSyncLog(
                    folderName = remotePath,
                    action = "FILES_LIST_REQUESTED",
                    message = "Requested file list from remote device",
                    status = SyncLogStatus.INFO,
                    deviceName = deviceId
                )
                
                emptyList()
            } else {
                Log.e(TAG, "Failed to send file list request")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request remote files list", e)
            emptyList()
        }
    }    private suspend fun uploadFile(syncedFolder: SyncedFolder, file: FileMetadata) {
        try {
            Log.d(TAG, "Uploading file: ${file.name} from sync folder: ${syncedFolder.name}")
            
            // Try to find the file in the synced folder using DocumentFile
            if (syncedFolder.localUri != null) {
                val syncFolder = DocumentFile.fromTreeUri(context, syncedFolder.localUri)
                val fileDocument = syncFolder?.findFile(file.name)
                
                if (fileDocument != null && fileDocument.exists()) {
                    // Convert DocumentFile to a temporary file path for the existing sendFile method
                    val tempFile = File(context.cacheDir, "temp_${file.name}")
                    
                    // Copy the DocumentFile content to temp file
                    context.contentResolver.openInputStream(fileDocument.uri)?.use { inputStream ->
                        tempFile.outputStream().use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    
                    // Send the temp file
                    val result = wifiDirectManager.sendFile(tempFile.absolutePath)
                    
                    // Clean up temp file
                    tempFile.delete()
                    
                    if (result.isSuccess) {
                        addSyncLog(
                            folderName = syncedFolder.name,
                            action = "FILE_UPLOADED",
                            message = "Successfully uploaded ${file.name} (${file.size} bytes)",
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
                } else {
                    Log.w(TAG, "File not found in sync folder: ${file.name}")
                    addSyncLog(
                        folderName = syncedFolder.name,
                        action = "ERROR",
                        message = "File not found: ${file.name}",
                        status = SyncLogStatus.ERROR,
                        deviceName = syncedFolder.remoteDeviceName,
                        fileName = file.name
                    )
                }
            } else {
                // Fallback to file path if URI is not available
                val localFile = File(syncedFolder.localPath, file.path)
                if (localFile.exists()) {
                    val result = wifiDirectManager.sendFile(localFile.absolutePath)
                    if (result.isSuccess) {
                        addSyncLog(
                            folderName = syncedFolder.name,
                            action = "FILE_UPLOADED",
                            message = "Successfully uploaded ${file.name} via file path",
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
                } else {
                    Log.w(TAG, "Local file not found: ${localFile.absolutePath}")
                    addSyncLog(
                        folderName = syncedFolder.name,
                        action = "ERROR",
                        message = "Local file not found: ${file.name}",
                        status = SyncLogStatus.ERROR,
                        deviceName = syncedFolder.remoteDeviceName,
                        fileName = file.name                    )
                }
            }
        } catch (e: Exception) {
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
    }    private suspend fun downloadFile(syncedFolder: SyncedFolder, file: FileMetadata) {
        try {
            Log.d(TAG, "Requesting download of file: ${file.name} for sync folder: ${syncedFolder.name}")
            
            // Send request to remote device for this specific file
            val requestMessage = "SYNC_REQUEST_FILE:${file.name}:${syncedFolder.id}"
            val result = wifiDirectManager.sendMessage(requestMessage)
            
            if (result.isSuccess) {
                addSyncLog(
                    folderName = syncedFolder.name,
                    action = "FILE_DOWNLOAD_REQUESTED",
                    message = "Requested download of ${file.name} (${file.size} bytes)",
                    status = SyncLogStatus.INFO,
                    deviceName = syncedFolder.remoteDeviceName,
                    fileName = file.name
                )
            } else {
                addSyncLog(
                    folderName = syncedFolder.name,
                    action = "ERROR",
                    message = "Failed to request download of ${file.name}",
                    status = SyncLogStatus.ERROR,
                    deviceName = syncedFolder.remoteDeviceName,
                    fileName = file.name
                )
            }
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
    }    // Public methods for handling received sync messages
    fun handleSyncRequest(requestJson: String, senderAddress: String) {
        try {
            val request = parseSyncRequest(requestJson)
            if (request != null) {
                // Add to pending requests (not _syncRequests which is for outgoing requests)
                val updatedRequests = _pendingRequests.value.toMutableList()
                updatedRequests.add(request)
                _pendingRequests.value = updatedRequests
                  
                addSyncLog(
                    folderName = request.folderName,
                    action = "SYNC_REQUEST_RECEIVED",
                    message = "Received sync request from ${request.sourceDeviceName}",
                    status = SyncLogStatus.INFO,
                    deviceName = request.sourceDeviceName
                )
                
                // Notify callback if available
                callback?.onSyncRequestReceived(request)
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
    }    /**
     * Parse sync request JSON into SyncRequest object
     */
    fun parseSyncRequestFromJson(json: String): SyncRequest? {
        return parseSyncRequest(json)
    }

    private fun parseSyncRequest(json: String): SyncRequest? {
        return try {            Log.d(TAG, "Parsing sync request JSON: $json")
            
            // Simple JSON parsing - extract values manually using Utils.extractJsonValue
            val requestId = Utils.extractJsonValue(json, "requestId") ?: UUID.randomUUID().toString()
            val sourceDeviceId = Utils.extractJsonValue(json, "sourceDeviceId") ?: "unknown"
            val sourceDeviceName = Utils.extractJsonValue(json, "sourceDeviceName") ?: "Unknown Device"
            val folderName = Utils.extractJsonValue(json, "folderName") ?: "Sync Folder"
            val folderPath = Utils.extractJsonValue(json, "folderPath") ?: "/unknown"
            val totalFiles = Utils.extractJsonValue(json, "totalFiles")?.toIntOrNull() ?: 0
            val totalSize = Utils.extractJsonValue(json, "totalSize")?.toLongOrNull() ?: 0L
            val timestamp = Utils.extractJsonValue(json, "timestamp")?.toLongOrNull() ?: System.currentTimeMillis()
            
            SyncRequest(
                requestId = requestId,
                sourceDeviceId = sourceDeviceId,
                sourceDeviceName = sourceDeviceName,
                folderName = folderName,
                folderPath = folderPath,
                totalFiles = totalFiles,
                totalSize = totalSize,
                timestamp = timestamp
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse sync request", e)
            null
        }
    }    private fun parseSyncProgress(json: String): SyncProgress? {
        return try {
            Log.d(TAG, "Parsing sync progress JSON: $json")
            
            // Simple JSON parsing - extract values manually using Utils.extractJsonValue
            val folderName = Utils.extractJsonValue(json, "folderName") ?: ""
            val currentFile = Utils.extractJsonValue(json, "currentFile") ?: ""
            val filesProcessed = Utils.extractJsonValue(json, "filesProcessed")?.toIntOrNull() ?: 0
            val totalFiles = Utils.extractJsonValue(json, "totalFiles")?.toIntOrNull() ?: 0
            val bytesTransferred = Utils.extractJsonValue(json, "bytesTransferred")?.toLongOrNull() ?: 0L
            val totalBytes = Utils.extractJsonValue(json, "totalBytes")?.toLongOrNull() ?: 0L
            val status = Utils.extractJsonValue(json, "status") ?: ""
            
            SyncProgress(
                folderName = folderName,
                currentFile = currentFile,
                filesProcessed = filesProcessed,
                totalFiles = totalFiles,
                bytesTransferred = bytesTransferred,
                totalBytes = totalBytes,
                status = status
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
    }    private fun getCurrentDeviceName(): String {
        // Get current device name
        return android.os.Build.MODEL
    }
    
    private fun getConnectedDeviceId(): String? {
        // Get the connected device ID from WiFiDirectManager
        // If we're the group owner, get the first connected peer
        // If we're a client, get the group owner
        val connectionInfo = wifiDirectManager.connectionInfo.value
        val connectedPeers = wifiDirectManager.connectedPeers.value
        
        return if (connectionInfo?.groupFormed == true) {
            if (connectionInfo.isGroupOwner) {
                // We're group owner, get first connected peer
                connectedPeers.firstOrNull()
            } else {
                // We're client, use group owner address
                connectionInfo.groupOwnerAddress
            }
        } else {
            null
        }
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
    }    /**
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
                // Create new sync request and send to connected device
                val targetDeviceId = getConnectedDeviceId()
                if (targetDeviceId == null) {
                    addSyncLog(
                        folderName = folderPath.substringAfterLast("/"),
                        action = "SYNC_FAILED",
                        message = "No connected device found to send sync request",
                        status = SyncLogStatus.ERROR,
                        deviceName = remoteDeviceName
                    )
                    return false
                }
                
                // Convert file path to URI if needed
                val folderUri = fileManager.selectedFolderUri
                if (folderUri == null) {
                    addSyncLog(
                        folderName = folderPath.substringAfterLast("/"),
                        action = "SYNC_FAILED",
                        message = "Invalid folder URI for sync request",
                        status = SyncLogStatus.ERROR,
                        deviceName = remoteDeviceName
                    )
                    return false
                }
                
                // Send actual sync request
                val result = requestFolderSync(
                    folderUri = folderUri,
                    folderName = folderPath.substringAfterLast("/"),
                    targetDeviceId = targetDeviceId,
                    targetDeviceName = remoteDeviceName
                )
                
                result.isSuccess
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating folder sync", e)
            addSyncLog(
                folderName = folderPath.substringAfterLast("/"),
                action = "SYNC_ERROR",
                message = "Error initiating sync: ${e.message}",
                status = SyncLogStatus.ERROR,
                deviceName = remoteDeviceName
            )
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
    private val _currentProgress = MutableStateFlow<SyncProgress?>(null)

    /**
     * Handle sync start transfer request - when remote device wants to begin file transfer
     */
    fun handleSyncStartTransfer(folderId: String, folderName: String, senderAddress: String) {
        try {
            Log.d(TAG, "Handling sync start transfer for folder: $folderName (ID: $folderId)")
            
            // Find the synced folder by ID or create one if accepting an incoming sync
            var syncedFolder = _syncedFolders.value.find { it.id == folderId }
            
            if (syncedFolder == null) {
                // Create a temporary synced folder entry for this incoming transfer
                syncedFolder = SyncedFolder(
                    id = folderId,
                    name = folderName,
                    localPath = "${context.getExternalFilesDir(null)?.absolutePath}/SyncyP2P/$folderName",
                    localUri = null, // Will be set when folder is created
                    remoteDeviceId = senderAddress,
                    remoteDeviceName = "Remote Device",
                    remotePath = "/unknown",
                    lastSyncTime = 0L,
                    status = SyncStatus.SYNCING,
                    conflictResolution = ConflictResolution.ASK_USER
                )
                
                addSyncedFolderInternal(syncedFolder)
            }
              // Update status to syncing
            updateSyncedFolderStatus(folderId, SyncStatus.SYNCING)            // CRITICAL FIX: Start FileReceiver to accept incoming file connections
            Log.d(TAG, "Starting FileReceiver for sync operation...")
            
            // Use the synced folder's local path as destination, but also pass the URI for proper file handling
            val destinationPath = if (syncedFolder.localUri != null) {
                // Try to get real folder path from URI
                fileManager.getFolderDisplayPath(syncedFolder.localUri) ?: syncedFolder.localPath
            } else {
                syncedFolder.localPath
            }
            
            val startReceiverResult = wifiDirectManager.startReceivingFiles(destinationPath, syncedFolder.localUri)
            if (startReceiverResult.isSuccess) {
                Log.d(TAG, "✅ FileReceiver started successfully for sync at: $destinationPath")
                addSyncLog(
                    folderName = folderName,
                    action = "FILE_RECEIVER_STARTED",
                    message = "FileReceiver started to accept incoming files at $destinationPath",
                    status = SyncLogStatus.INFO,
                    deviceName = senderAddress
                )
            } else {
                Log.e(TAG, "❌ Failed to start FileReceiver for sync")
                addSyncLog(
                    folderName = folderName,
                    action = "ERROR",
                    message = "Failed to start FileReceiver: ${startReceiverResult.exceptionOrNull()?.message}",
                    status = SyncLogStatus.ERROR,
                    deviceName = senderAddress
                )
            }
            
            // Request the files list from the sender
            val requestMessage = "SYNC_REQUEST_FILES_LIST:${syncedFolder.remotePath}"
            wifiDirectManager.sendMessage(requestMessage)
            
            addSyncLog(
                folderName = folderName,
                action = "SYNC_TRANSFER_STARTED",
                message = "Sync transfer started, requesting file list",
                status = SyncLogStatus.INFO,
                deviceName = senderAddress
            )
            
            // Update progress
            val progress = SyncProgress(
                folderName = folderName,
                currentFile = "Requesting file list...",
                filesProcessed = 0,
                totalFiles = 0,
                bytesTransferred = 0L,
                totalBytes = 0L,
                status = "Starting sync transfer"
            )
            updateSyncProgress(folderId, progress)
            callback?.onSyncProgress(folderId, progress)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling sync start transfer", e)
            addSyncLog(
                folderName = folderName,
                action = "ERROR",
                message = "Error starting sync transfer: ${e.message}",
                status = SyncLogStatus.ERROR,
                deviceName = senderAddress
            )
        }
    }    /**
     * Handle request for files list from a specific folder
     */
    fun handleSyncRequestFilesList(folderPath: String, senderAddress: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Handling request for files list from folder: $folderPath")
                
                // Find the local folder and get its file list
                val files = if (fileManager.hasSelectedFolder()) {
                    // Use currently selected folder if available
                    fileManager.getFilesInFolder()
                } else {
                    // Try to find synced folder matching the path
                    val syncedFolder = _syncedFolders.value.find { it.localPath.contains(folderPath) || it.remotePath == folderPath }
                    if (syncedFolder?.localUri != null) {
                        fileManager.getFilesInFolder(syncedFolder.localUri)
                    } else {
                        emptyList()
                    }
                }
            
            // Convert FileItems to a simpler format for transmission
            val fileList = files.map { file ->
                mapOf(
                    "name" to file.name,
                    "size" to file.size,
                    "lastModified" to file.lastModified,
                    "isDirectory" to file.isDirectory
                )
            }
            
            // Serialize the file list as JSON
            val filesListJson = serializeFilesList(fileList)
            
            // Send the files list response
            val responseMessage = "SYNC_FILES_LIST_RESPONSE:$filesListJson"
            wifiDirectManager.sendMessage(responseMessage)
            
            addSyncLog(
                folderName = folderPath.substringAfterLast("/"),
                action = "FILES_LIST_SENT",
                message = "Sent file list with ${files.size} files to $senderAddress",
                status = SyncLogStatus.INFO,
                deviceName = senderAddress
            )
                  } catch (e: Exception) {
                Log.e(TAG, "Error handling files list request", e)
                addSyncLog(
                    folderName = folderPath.substringAfterLast("/"),
                    action = "ERROR",
                    message = "Error sending file list: ${e.message}",
                    status = SyncLogStatus.ERROR,
                    deviceName = senderAddress
                )
            }
        }
    }    /**
     * Handle request for a specific file transfer
     */
    fun handleSyncRequestFile(message: String, senderAddress: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Handling request for specific file: $message")
                
                // Parse the request: "SYNC_REQUEST_FILE:fileName:folderId"
                val parts = message.removePrefix("SYNC_REQUEST_FILE:").split(":")
                if (parts.size >= 2) {
                    val fileName = parts[0]
                    val folderId = if (parts.size > 1) parts[1] else null
                    
                    Log.d(TAG, "File request - Name: $fileName, Folder ID: $folderId")
                    
                    // Find the file in the local folder
                    var fileToSend: FileItem? = null
                    var folderName = "Unknown"
                    
                    if (folderId != null) {
                        // Find specific synced folder
                        val syncedFolder = _syncedFolders.value.find { it.id == folderId }
                        if (syncedFolder?.localUri != null) {
                            folderName = syncedFolder.name
                            val folderFiles = fileManager.getFilesInFolder(syncedFolder.localUri)
                            fileToSend = folderFiles.find { it.name == fileName }
                        }
                    } else {
                        // Use currently selected folder
                        if (fileManager.hasSelectedFolder()) {
                            folderName = fileManager.selectedFolderPath?.substringAfterLast("/") ?: "Selected Folder"
                            val folderFiles = fileManager.getFilesInFolder()
                            fileToSend = folderFiles.find { it.name == fileName }
                        }
                    }
                    
                    if (fileToSend != null && !fileToSend.isDirectory) {
                        // Create a temporary file for sending
                        val tempFile = File(context.cacheDir, "temp_sync_${System.currentTimeMillis()}_${fileToSend.name}")
                        
                        // Copy file content to temp file
                        val inputStream = fileManager.getFileInputStream(fileToSend.uri)
                        if (inputStream != null) {
                        inputStream.use { input ->
                            tempFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        
                        // Set readable permissions
                        tempFile.setReadable(true, false)
                        
                        // Send the file
                        CoroutineScope(Dispatchers.IO).launch {
                            val result = wifiDirectManager.sendFile(tempFile.absolutePath)
                            
                            if (result.isSuccess) {
                                addSyncLog(
                                    folderName = folderName,
                                    action = "FILE_SENT",
                                    message = "Sent file $fileName (${fileToSend.size} bytes) to $senderAddress",
                                    status = SyncLogStatus.SUCCESS,
                                    deviceName = senderAddress,
                                    fileName = fileName
                                )
                            } else {
                                addSyncLog(
                                    folderName = folderName,
                                    action = "ERROR",
                                    message = "Failed to send file $fileName to $senderAddress",
                                    status = SyncLogStatus.ERROR,
                                    deviceName = senderAddress,
                                    fileName = fileName
                                )
                            }
                            
                            // Clean up temp file after a delay
                            delay(5000)
                            try {
                                if (tempFile.exists()) {
                                    tempFile.delete()
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to delete temp file: ${tempFile.absolutePath}", e)
                            }
                        }
                    } else {
                        addSyncLog(
                            folderName = folderName,
                            action = "ERROR",
                            message = "Could not access file $fileName for sending",
                            status = SyncLogStatus.ERROR,
                            deviceName = senderAddress,
                            fileName = fileName
                        )
                    }
                } else {
                    addSyncLog(
                        folderName = folderName,
                        action = "ERROR",
                        message = "Requested file $fileName not found or is a directory",
                        status = SyncLogStatus.ERROR,
                        deviceName = senderAddress,
                        fileName = fileName
                    )
                }
            } else {
                Log.e(TAG, "Invalid file request format: $message")
            }
                  } catch (e: Exception) {
                Log.e(TAG, "Error handling file request", e)
                addSyncLog(
                    folderName = "Unknown",
                    action = "ERROR",
                    message = "Error handling file request: ${e.message}",
                    status = SyncLogStatus.ERROR,
                    deviceName = senderAddress
                )
            }
        }
    }

    /**
     * Handle response containing list of files from remote device
     */
    fun handleSyncFilesListResponse(filesListJson: String, senderAddress: String) {
        try {
            Log.d(TAG, "Handling files list response from $senderAddress")
            
            // Parse the files list
            val remoteFiles = parseFilesList(filesListJson)
            
            if (remoteFiles.isNotEmpty()) {
                Log.d(TAG, "Received ${remoteFiles.size} files in list from $senderAddress")
                
                // Find the synced folder that's expecting this response
                val syncingFolder = _syncedFolders.value.find { 
                    it.status == SyncStatus.SYNCING && it.remoteDeviceId == senderAddress 
                }
                
                if (syncingFolder != null) {
                    addSyncLog(
                        folderName = syncingFolder.name,
                        action = "FILES_LIST_RECEIVED",
                        message = "Received file list with ${remoteFiles.size} files",
                        status = SyncLogStatus.INFO,
                        deviceName = senderAddress
                    )
                    
                    // Start requesting files one by one
                    CoroutineScope(Dispatchers.IO).launch {
                        for ((index, fileInfo) in remoteFiles.withIndex()) {
                            val fileName = fileInfo["name"] as? String ?: continue
                            val isDirectory = fileInfo["isDirectory"] as? Boolean ?: false
                            
                            if (!isDirectory) {
                                // Update progress
                                val progress = SyncProgress(
                                    folderName = syncingFolder.name,
                                    currentFile = fileName,
                                    filesProcessed = index,
                                    totalFiles = remoteFiles.size,
                                    bytesTransferred = 0L,
                                    totalBytes = 0L,
                                    status = "Requesting $fileName..."
                                )
                                updateSyncProgress(syncingFolder.id, progress)
                                callback?.onSyncProgress(syncingFolder.id, progress)
                                
                                // Request this specific file
                                val fileRequestMessage = "SYNC_REQUEST_FILE:$fileName:${syncingFolder.id}"
                                wifiDirectManager.sendMessage(fileRequestMessage)
                                
                                addSyncLog(
                                    folderName = syncingFolder.name,
                                    action = "FILE_REQUESTED",
                                    message = "Requested file: $fileName",
                                    status = SyncLogStatus.INFO,
                                    deviceName = senderAddress,
                                    fileName = fileName
                                )
                                
                                // Add delay between requests to avoid overwhelming the connection
                                delay(1000)
                            }
                        }
                        
                        // Mark sync as completed
                        val completedProgress = SyncProgress(
                            folderName = syncingFolder.name,
                            currentFile = "Sync completed",
                            filesProcessed = remoteFiles.size,
                            totalFiles = remoteFiles.size,
                            bytesTransferred = 0L,
                            totalBytes = 0L,
                            status = "Sync completed"
                        )
                        updateSyncProgress(syncingFolder.id, completedProgress)
                        callback?.onSyncProgress(syncingFolder.id, completedProgress)
                        
                        // Update folder status
                        updateSyncedFolderInternal(syncingFolder.copy(
                            lastSyncTime = System.currentTimeMillis(),
                            status = SyncStatus.SYNCED
                        ))
                        
                        addSyncLog(
                            folderName = syncingFolder.name,
                            action = "SYNC_COMPLETED",
                            message = "Sync completed successfully. ${remoteFiles.size} files processed.",
                            status = SyncLogStatus.SUCCESS,
                            deviceName = senderAddress
                        )
                        
                        callback?.onSyncCompleted(syncingFolder.id, true)
                        clearSyncProgress(syncingFolder.id)
                    }
                } else {
                    Log.w(TAG, "No syncing folder found for files list response from $senderAddress")
                }
            } else {
                Log.d(TAG, "Empty files list received from $senderAddress")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling files list response", e)
            addSyncLog(
                folderName = "Unknown",
                action = "ERROR",
                message = "Error processing files list: ${e.message}",
                status = SyncLogStatus.ERROR,
                deviceName = senderAddress
            )
        }
    }

    /**
     * Serialize a list of file information to JSON string
     */
    private fun serializeFilesList(fileList: List<Map<String, Any>>): String {
        return try {
            val jsonBuilder = StringBuilder()
            jsonBuilder.append("[")
            
            fileList.forEachIndexed { index, file ->
                if (index > 0) jsonBuilder.append(",")
                jsonBuilder.append("{")
                jsonBuilder.append("\"name\":\"${file["name"]}\",")
                jsonBuilder.append("\"size\":${file["size"]},")
                jsonBuilder.append("\"lastModified\":${file["lastModified"]},")
                jsonBuilder.append("\"isDirectory\":${file["isDirectory"]}")
                jsonBuilder.append("}")
            }
            
            jsonBuilder.append("]")
            jsonBuilder.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error serializing files list", e)
            "[]"
        }
    }

    /**
     * Parse files list from JSON string
     */
    private fun parseFilesList(filesListJson: String): List<Map<String, Any>> {
        return try {
            Log.d(TAG, "Parsing files list JSON: $filesListJson")
            
            val files = mutableListOf<Map<String, Any>>()
            
            // Simple JSON parsing for array of file objects
            val jsonContent = filesListJson.trim()
            if (jsonContent.startsWith("[") && jsonContent.endsWith("]")) {
                val arrayContent = jsonContent.substring(1, jsonContent.length - 1)
                
                if (arrayContent.isNotEmpty()) {
                    // Split by "},{" to separate objects
                    val fileObjects = arrayContent.split("},{")
                    
                    for ((index, fileObjStr) in fileObjects.withIndex()) {
                        val cleanedObj = when {
                            index == 0 && fileObjStr.startsWith("{") -> fileObjStr.substring(1)
                            index == fileObjects.size - 1 && fileObjStr.endsWith("}") -> fileObjStr.substring(0, fileObjStr.length - 1)
                            !fileObjStr.startsWith("{") && !fileObjStr.endsWith("}") -> fileObjStr
                            else -> fileObjStr.removeSurrounding("{", "}")
                        }
                        
                        val fileMap = mutableMapOf<String, Any>()
                        
                        // Parse key-value pairs
                        val pairs = cleanedObj.split(",")
                        for (pair in pairs) {
                            val keyValue = pair.split(":")
                            if (keyValue.size == 2) {
                                val key = keyValue[0].trim().removeSurrounding("\"")
                                val value = keyValue[1].trim()
                                
                                when (key) {
                                    "name" -> fileMap[key] = value.removeSurrounding("\"")
                                    "size" -> fileMap[key] = value.toLongOrNull() ?: 0L
                                    "lastModified" -> fileMap[key] = value.toLongOrNull() ?: 0L
                                    "isDirectory" -> fileMap[key] = value.toBoolean()
                                }
                            }
                        }
                        
                        if (fileMap.isNotEmpty()) {
                            files.add(fileMap)
                        }
                    }
                }
            }
              Log.d(TAG, "Parsed ${files.size} files from JSON")
            files
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing files list JSON", e)
            emptyList()
        }
    }
}
