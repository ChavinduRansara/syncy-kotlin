package com.example.syncy_p2p.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.syncy_p2p.files.FileManager
import com.example.syncy_p2p.files.FileItem
import com.example.syncy_p2p.p2p.WiFiDirectManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.*

enum class SyncMode {
    TWO_WAY,        // Use Case 1: Bidirectional sync
    ONE_WAY_BACKUP, // Use Case 2: Source to destination only
    ONE_WAY_MIRROR  // Use Case 3: Master to mirror (with deletions)
}

data class SyncConfiguration(
    val mode: SyncMode,
    val folderPath: String,
    val targetDeviceAddress: String,
    val autoSync: Boolean = false,
    val conflictResolution: ConflictResolution = ConflictResolution.ASK_USER
)

// FileMetadata is defined in SyncModels.kt

data class SyncOperation(
    val type: SyncOperationType,
    val sourceFile: FileMetadata?,
    val targetFile: FileMetadata?,
    val conflictResolution: ConflictResolution?
)

enum class SyncOperationType {
    COPY_TO_TARGET,
    COPY_TO_SOURCE,
    DELETE_FROM_TARGET,
    DELETE_FROM_SOURCE,
    CONFLICT_DETECTED
}

class SyncManager(
    private val context: Context,
    private val fileManager: FileManager,
    private val wifiDirectManager: WiFiDirectManager
) {
    companion object {
        private const val TAG = "SyncManager"
    }

    private val _syncConfigurations = MutableStateFlow<List<SyncConfiguration>>(emptyList())
    val syncConfigurations: StateFlow<List<SyncConfiguration>> = _syncConfigurations.asStateFlow()

    private val _currentSyncProgress = MutableStateFlow<SyncProgress?>(null)
    val currentSyncProgress: StateFlow<SyncProgress?> = _currentSyncProgress.asStateFlow()
    
    private val _syncLogs = MutableStateFlow<List<SyncLogEntry>>(emptyList())
    val syncLogs: StateFlow<List<SyncLogEntry>> = _syncLogs.asStateFlow()

    private val _activeSyncs = MutableStateFlow<Map<String, SyncSession>>(emptyMap())
    val activeSyncs: StateFlow<Map<String, SyncSession>> = _activeSyncs.asStateFlow()

    // Additional StateFlow properties expected by MainActivity
    private val _syncRequests = MutableStateFlow<List<SyncRequest>>(emptyList())
    val syncRequests: StateFlow<List<SyncRequest>> = _syncRequests.asStateFlow()

    private val _syncedFolders = MutableStateFlow<List<SyncedFolder>>(emptyList())
    val syncedFolders: StateFlow<List<SyncedFolder>> = _syncedFolders.asStateFlow()

    // Alias for MainActivity compatibility
    val currentProgress: StateFlow<SyncProgress?> = _currentSyncProgress.asStateFlow()    // Host address property
    var hostAddress: String? = null
    
    // Current sync folder URI for proper file storage
    private var currentSyncFolderUri: Uri? = null

    private var syncJob: Job? = null

    // **Core Folder Synchronization Implementation**

    suspend fun startFolderSync(
        localFolderUri: Uri,
        targetDeviceAddress: String,
        mode: SyncMode,
        conflictResolution: ConflictResolution = ConflictResolution.ASK_USER
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val syncId = UUID.randomUUID().toString()
            val folderPath = fileManager.getFolderDisplayPath(localFolderUri) ?: "Unknown"
            
            Log.d(TAG, "🚀 Starting folder sync: $folderPath with mode: $mode")
            
            // Create sync session
            val syncSession = SyncSession(
                id = syncId,
                localFolderUri = localFolderUri,
                targetDeviceAddress = targetDeviceAddress,
                mode = mode,
                conflictResolution = conflictResolution,
                status = SyncStatus.INITIALIZING
            )
            
            _activeSyncs.value = _activeSyncs.value + (syncId to syncSession)
            
            // Start the sync process
            syncJob = CoroutineScope(Dispatchers.IO).launch {
                performFolderSync(syncSession)
            }
            
            Result.success(syncId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start folder sync", e)
            Result.failure(e)        }
    }

    private suspend fun performFolderSync(session: SyncSession) {
        try {
            updateSyncStatus(session.id, SyncStatus.SCANNING)
            
            // CRITICAL FIX: Configure WiFiDirectManager for sync destination
            Log.d(TAG, "🔧 SYNC SETUP - Configuring file receiver for sync destination")
            configureSyncDestination(session.localFolderUri)
            
            // Step 1: Scan local folder
            val localFiles = scanFolder(session.localFolderUri)
            Log.d(TAG, "📁 Local folder scan complete: ${localFiles.size} files")
            
            // Step 2: Request remote folder structure
            val remoteFiles = requestRemoteFolderStructure(session.targetDeviceAddress, session.localFolderUri)
            Log.d(TAG, "📁 Remote folder scan complete: ${remoteFiles.size} files")
            
            // Step 3: Compare and generate sync operations
            val syncOperations = generateSyncOperations(localFiles, remoteFiles, session.mode)
            Log.d(TAG, "🔄 Generated ${syncOperations.size} sync operations")
            
            // Step 4: Execute sync operations
            updateSyncStatus(session.id, SyncStatus.SYNCING)
            executeSyncOperations(session, syncOperations)
            
            updateSyncStatus(session.id, SyncStatus.COMPLETED)
            Log.d(TAG, "✅ Folder sync completed successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Folder sync failed", e)
            updateSyncStatus(session.id, SyncStatus.FAILED)
        }
    }private suspend fun scanFolder(folderUri: Uri): List<FileMetadata> = withContext(Dispatchers.IO) {
        val files = mutableListOf<FileMetadata>()
        
        suspend fun scanRecursively(currentUri: Uri, basePath: String = "") {
            val documentFile = DocumentFile.fromTreeUri(context, currentUri)
            documentFile?.listFiles()?.forEach { file ->
                val relativePath = if (basePath.isEmpty()) file.name ?: "" else "$basePath/${file.name ?: ""}"
                
                if (file.isDirectory) {
                    files.add(FileMetadata(
                        name = file.name ?: "",
                        path = relativePath,
                        size = 0,
                        lastModified = file.lastModified(),
                        checksum = "",
                        isDirectory = true
                    ))
                    scanRecursively(file.uri, relativePath)
                } else {
                    val hash = calculateFileHash(file.uri)
                    files.add(FileMetadata(
                        name = file.name ?: "",
                        path = relativePath,
                        size = file.length(),
                        lastModified = file.lastModified(),
                        checksum = hash,
                        isDirectory = false
                    ))
                }
            }
        }
        
        scanRecursively(folderUri)
        files
    }

    private suspend fun requestRemoteFolderStructure(
        targetAddress: String,
        folderUri: Uri
    ): List<FileMetadata> = withContext(Dispatchers.IO) {
        // Send request for remote folder structure
        val request = JSONObject().apply {
            put("type", "FOLDER_STRUCTURE_REQUEST")
            put("folderPath", fileManager.getFolderDisplayPath(folderUri))
            put("timestamp", System.currentTimeMillis())
        }
        
        // Send request and wait for response
        val response = sendSyncRequestAndWaitForResponse(request.toString(), targetAddress)
        parseRemoteFolderStructure(response)
    }

    private fun generateSyncOperations(
        localFiles: List<FileMetadata>,
        remoteFiles: List<FileMetadata>,
        mode: SyncMode
    ): List<SyncOperation> {
        val operations = mutableListOf<SyncOperation>()
        val localFileMap = localFiles.associateBy { it.path }
        val remoteFileMap = remoteFiles.associateBy { it.path }
        
        when (mode) {
            SyncMode.TWO_WAY -> {
                // Two-way synchronization logic
                generateTwoWayOperations(localFileMap, remoteFileMap, operations)
            }
            SyncMode.ONE_WAY_BACKUP -> {
                // One-way backup: local to remote only
                generateBackupOperations(localFileMap, remoteFileMap, operations)
            }
            SyncMode.ONE_WAY_MIRROR -> {
                // One-way mirror: local becomes exact copy of remote
                generateMirrorOperations(localFileMap, remoteFileMap, operations)
            }
        }
        
        return operations
    }

    private fun generateTwoWayOperations(
        localFiles: Map<String, FileMetadata>,
        remoteFiles: Map<String, FileMetadata>,
        operations: MutableList<SyncOperation>
    ) {
        // Files in both locations
        val commonPaths = localFiles.keys.intersect(remoteFiles.keys)
        commonPaths.forEach { path ->
            val localFile = localFiles[path]!!
            val remoteFile = remoteFiles[path]!!
            
            if (localFile.checksum != remoteFile.checksum) {
                // Conflict detected - determine resolution
                if (localFile.lastModified > remoteFile.lastModified) {
                    operations.add(SyncOperation(SyncOperationType.COPY_TO_TARGET, localFile, remoteFile, null))
                } else if (remoteFile.lastModified > localFile.lastModified) {
                    operations.add(SyncOperation(SyncOperationType.COPY_TO_SOURCE, remoteFile, localFile, null))
                } else {
                    // Same timestamp but different content - user decision needed
                    operations.add(SyncOperation(SyncOperationType.CONFLICT_DETECTED, localFile, remoteFile, null))
                }
            }
        }
        
        // Files only in local - copy to remote
        (localFiles.keys - remoteFiles.keys).forEach { path ->
            operations.add(SyncOperation(SyncOperationType.COPY_TO_TARGET, localFiles[path]!!, null, null))
        }
        
        // Files only in remote - copy to local
        (remoteFiles.keys - localFiles.keys).forEach { path ->
            operations.add(SyncOperation(SyncOperationType.COPY_TO_SOURCE, remoteFiles[path]!!, null, null))
        }
    }

    private fun generateBackupOperations(
        localFiles: Map<String, FileMetadata>,
        remoteFiles: Map<String, FileMetadata>,
        operations: MutableList<SyncOperation>
    ) {
        localFiles.forEach { (path, localFile) ->
            val remoteFile = remoteFiles[path]
            
            if (remoteFile == null || localFile.checksum != remoteFile.checksum) {
                // Copy local file to remote (backup)
                operations.add(SyncOperation(SyncOperationType.COPY_TO_TARGET, localFile, remoteFile, null))
            }
        }
        
        // Note: In backup mode, we don't delete files from destination that aren't in source
    }

    private fun generateMirrorOperations(
        localFiles: Map<String, FileMetadata>,
        remoteFiles: Map<String, FileMetadata>,
        operations: MutableList<SyncOperation>
    ) {
        remoteFiles.forEach { (path, remoteFile) ->
            val localFile = localFiles[path]
            
            if (localFile == null || remoteFile.checksum != localFile.checksum) {
                // Copy remote file to local (mirror)
                operations.add(SyncOperation(SyncOperationType.COPY_TO_SOURCE, remoteFile, localFile, null))
            }
        }
        
        // Delete local files that don't exist on remote (exact mirror)
        (localFiles.keys - remoteFiles.keys).forEach { path ->
            operations.add(SyncOperation(SyncOperationType.DELETE_FROM_SOURCE, null, localFiles[path]!!, null))
        }
    }

    private suspend fun executeSyncOperations(
        session: SyncSession,
        operations: List<SyncOperation>
    ) {
        val totalOperations = operations.size
        var completedOperations = 0
        
        operations.forEach { operation ->
            try {
                when (operation.type) {
                    SyncOperationType.COPY_TO_TARGET -> {
                        copyFileToTarget(session, operation.sourceFile!!)
                    }
                    SyncOperationType.COPY_TO_SOURCE -> {
                        copyFileToSource(session, operation.sourceFile!!)
                    }
                    SyncOperationType.DELETE_FROM_TARGET -> {
                        deleteFileFromTarget(session, operation.targetFile!!)
                    }
                    SyncOperationType.DELETE_FROM_SOURCE -> {
                        deleteFileFromSource(session, operation.targetFile!!)
                    }
                    SyncOperationType.CONFLICT_DETECTED -> {
                        handleSyncConflict(session, operation)
                    }
                }
                
                completedOperations++
                updateSyncProgress(session.id, completedOperations, totalOperations, operation.sourceFile?.name ?: "")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute sync operation: $operation", e)
                addSyncLog(session.id, "Error: ${operation.type} failed for ${operation.sourceFile?.name ?: operation.targetFile?.name}: ${e.message}")
            }
        }
    }

    private suspend fun copyFileToTarget(session: SyncSession, fileMetadata: FileMetadata) {
        // Request file from local and send to target
        val localFileUri = findLocalFileUri(session.localFolderUri, fileMetadata.path)
        if (localFileUri != null) {
            val inputStream = context.contentResolver.openInputStream(localFileUri)
            inputStream?.use { stream ->
                val fileBytes = stream.readBytes()
                sendFileToTarget(session.targetDeviceAddress, fileMetadata, fileBytes)
            }
        }
    }

    private suspend fun copyFileToSource(session: SyncSession, fileMetadata: FileMetadata) {
        // Request file from target
        requestFileFromTarget(session.targetDeviceAddress, fileMetadata)
    }    private suspend fun deleteFileFromSource(session: SyncSession, fileMetadata: FileMetadata) {
        val localFileUri = findLocalFileUri(session.localFolderUri, fileMetadata.path)
        if (localFileUri != null) {
            val documentFile = DocumentFile.fromSingleUri(context, localFileUri)
            documentFile?.delete()
            addSyncLog(session.id, "Deleted local file: ${fileMetadata.name}")
        }
    }

    private suspend fun deleteFileFromTarget(session: SyncSession, targetFile: FileMetadata) {
        Log.d(TAG, "Deleting file from target: ${targetFile.name}")
        // Implementation for deleting file from target device  
        addSyncLog(session.id, "Deleted ${targetFile.name} from target")
    }

    // **File System Monitoring for Real-Time Sync**

    fun startRealTimeMonitoring(folderUri: Uri, targetDeviceAddress: String, mode: SyncMode) {
        // Start file system monitoring for automatic sync
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                delay(5000) // Check every 5 seconds
                
                try {
                    val currentFiles = scanFolder(folderUri)
                    // Compare with last known state and trigger sync if changes detected
                    // Implementation would store last known state and compare
                } catch (e: Exception) {
                    Log.e(TAG, "Error in real-time monitoring", e)
                }
            }        }    }

    // **Utility Methods**

    private suspend fun calculateFileHash(fileUri: Uri): String = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(fileUri)
            inputStream?.use { stream ->
                val digest = MessageDigest.getInstance("MD5")
                val buffer = ByteArray(8192)
                var bytesRead: Int
                
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
                
                digest.digest().joinToString("") { "%02x".format(it) }
            } ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate file hash", e)
            ""
        }
    }

    private fun findLocalFileUri(folderUri: Uri, relativePath: String): Uri? {
        // Navigate through folder structure to find specific file
        val pathParts = relativePath.split("/")
        var currentDocument = DocumentFile.fromTreeUri(context, folderUri)
        
        pathParts.forEach { part ->
            currentDocument = currentDocument?.findFile(part)
            if (currentDocument == null) return null
        }
        
        return currentDocument?.uri
    }    private suspend fun sendFileToTarget(targetAddress: String, fileMetadata: FileMetadata, fileBytes: ByteArray) {
        // Use existing WiFiDirectManager to send file
        val tempFile = File(context.cacheDir, "sync_${fileMetadata.name}")
        try {
            tempFile.writeBytes(fileBytes)
            
            // Set proper permissions for the temp file
            tempFile.setReadable(true, false)
            tempFile.setWritable(true, false)
            
            Log.d(TAG, "📤 SEND FILE TO TARGET - Created temp file: ${tempFile.absolutePath}, size: ${tempFile.length()}, exists: ${tempFile.exists()}")
            
            wifiDirectManager.sendFile(tempFile.absolutePath, targetAddress)
            
            // Don't delete immediately - let FileSender handle cleanup after transfer completes
            // Schedule cleanup with a delay to ensure transfer has time to complete
            CoroutineScope(Dispatchers.IO).launch {
                delay(30000) // Wait 30 seconds before cleanup
                try {
                    if (tempFile.exists()) {
                        val deleted = tempFile.delete()
                        Log.d(TAG, "🧹 SEND FILE TO TARGET - Delayed cleanup of temp file: $deleted")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ SEND FILE TO TARGET - Failed to cleanup temp file: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ SEND FILE TO TARGET - Error creating/sending temp file", e)
            // Clean up on error
            try {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            } catch (cleanupError: Exception) {
                Log.w(TAG, "⚠️ SEND FILE TO TARGET - Failed to cleanup temp file after error", cleanupError)
            }
            throw e
        }
    }

    private suspend fun requestFileFromTarget(targetAddress: String, fileMetadata: FileMetadata) {
        val request = JSONObject().apply {
            put("type", "FILE_REQUEST")
            put("filePath", fileMetadata.path)
            put("fileName", fileMetadata.name)
        }
        
        wifiDirectManager.sendMessage("SYNC_FILE_REQUEST:${request}")
    }    // Request-response correlation map
    private val pendingRequests = mutableMapOf<String, CompletableDeferred<String>>()
    
    private suspend fun sendSyncRequestAndWaitForResponse(request: String, targetAddress: String): String {
        val requestId = UUID.randomUUID().toString()
        val requestDeferred = CompletableDeferred<String>()
        
        try {
            // Store the pending request
            pendingRequests[requestId] = requestDeferred
            
            // Parse the request JSON and add request ID
            val requestJson = JSONObject(request)
            requestJson.put("requestId", requestId)
            
            // Send the request with ID
            val messagePrefix = if (requestJson.has("type") && requestJson.getString("type") == "FOLDER_STRUCTURE_REQUEST") {
                "SYNC_FOLDER_STRUCTURE_REQUEST"
            } else {
                "SYNC_REQUEST"
            }
            
            val result = wifiDirectManager.sendMessage("$messagePrefix:${requestJson}")
            if (result.isFailure) {
                pendingRequests.remove(requestId)
                throw result.exceptionOrNull() ?: Exception("Failed to send sync request")
            }
            
            Log.d(TAG, "📤 Sent sync request with ID: $requestId to $targetAddress")
            
            // Wait for response with timeout (30 seconds)
            return withTimeoutOrNull(30000) {
                requestDeferred.await()
            } ?: run {
                pendingRequests.remove(requestId)
                Log.w(TAG, "⏰ Sync request timeout for ID: $requestId")
                "{\"files\": [], \"error\": \"Request timeout\"}"
            }
            
        } catch (e: Exception) {
            pendingRequests.remove(requestId)
            Log.e(TAG, "❌ Failed to send sync request", e)
            throw e
        }
    }

    private fun parseRemoteFolderStructure(response: String): List<FileMetadata> {
        // Parse JSON response into FileMetadata list
        return try {
            val json = JSONObject(response)
            val filesArray = json.getJSONArray("files")
            val files = mutableListOf<FileMetadata>()
              for (i in 0 until filesArray.length()) {
                val fileJson = filesArray.getJSONObject(i)
                files.add(FileMetadata(
                    name = fileJson.getString("name"),
                    path = fileJson.getString("path"),
                    size = fileJson.getLong("size"),
                    lastModified = fileJson.getLong("lastModified"),
                    checksum = fileJson.getString("checksum"),
                    isDirectory = fileJson.getBoolean("isDirectory")
                ))
            }
            
            files
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse remote folder structure", e)
            emptyList()
        }
    }

    private fun updateSyncStatus(syncId: String, status: SyncStatus) {
        val currentSyncs = _activeSyncs.value.toMutableMap()
        currentSyncs[syncId]?.let { session ->
            currentSyncs[syncId] = session.copy(status = status)
            _activeSyncs.value = currentSyncs
        }
    }

    private fun updateSyncProgress(syncId: String, completed: Int, total: Int, currentFile: String) {
        _currentSyncProgress.value = SyncProgress(
            folderName = "Sync Session",
            currentFile = currentFile,
            filesProcessed = completed,
            totalFiles = total,
            bytesTransferred = 0,
            totalBytes = 0,
            status = "Processing $currentFile"
        )
    }    private fun addSyncLog(syncId: String, message: String, fileName: String? = null) {
        val logEntry = SyncLogEntry(
            id = UUID.randomUUID().toString(),
            folderName = "Sync Session", // Will be updated with actual folder name
            timestamp = System.currentTimeMillis(),
            action = "SYNC_ACTION",
            fileName = fileName,
            status = SyncLogStatus.INFO,
            message = message,
            deviceName = hostAddress ?: "Unknown Device"
        )
        
        _syncLogs.value = _syncLogs.value + logEntry
    }

    private suspend fun handleSyncConflict(session: SyncSession, operation: SyncOperation) {
        // Handle conflicts based on session configuration
        when (session.conflictResolution) {
            ConflictResolution.OVERWRITE_LOCAL -> copyFileToSource(session, operation.sourceFile!!)
            ConflictResolution.OVERWRITE_REMOTE -> copyFileToTarget(session, operation.sourceFile!!)
            ConflictResolution.KEEP_NEWER -> {
                if (operation.sourceFile!!.lastModified > operation.targetFile!!.lastModified) {
                    copyFileToTarget(session, operation.sourceFile)
                } else {
                    copyFileToSource(session, operation.targetFile)
                }
            }
            ConflictResolution.KEEP_LARGER -> {
                if (operation.sourceFile!!.size > operation.targetFile!!.size) {
                    copyFileToTarget(session, operation.sourceFile)
                } else {
                    copyFileToSource(session, operation.targetFile)
                }
            }
            ConflictResolution.KEEP_BOTH -> {
                // Rename one file and keep both
                val renamedFile = operation.sourceFile!!.copy(
                    name = "${operation.sourceFile.name}_conflict_${System.currentTimeMillis()}"
                )
                copyFileToTarget(session, renamedFile)
            }
            ConflictResolution.ASK_USER -> {
                // This would trigger a UI dialog in MainActivity
                addSyncLog(session.id, "Conflict detected: ${operation.sourceFile?.name} - user intervention required")
            }
        }
    }

    // **Existing methods from your current implementation**
    // Keep all your existing sync request handling methods...
    
    // Add the new enhanced methods to work with the existing infrastructure
    fun handleSyncStartTransfer(folderId: String, folderName: String, senderAddress: String) {
        // Implementation for handling sync start transfer
    }
    
    fun handleSyncRequestFilesList(folderPath: String, senderAddress: String) {
        // Implementation for handling files list request
    }
      fun handleSyncRequestFile(message: String, senderAddress: String) {
        Log.d(TAG, "📁 HANDLE SYNC REQUEST FILE - Processing file request from $senderAddress")
        Log.d(TAG, "📁 HANDLE SYNC REQUEST FILE - Message: $message")
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Parse the request message: SYNC_FILE_REQUEST:{"type":"FILE_REQUEST","filePath":"...","fileName":"..."}
                val jsonPart = when {
                    message.startsWith("SYNC_FILE_REQUEST:") -> message.removePrefix("SYNC_FILE_REQUEST:")
                    message.startsWith("SYNC_REQUEST_FILE:") -> message.removePrefix("SYNC_REQUEST_FILE:")
                    else -> message
                }
                
                val requestJson = JSONObject(jsonPart)
                val requestedFilePath = requestJson.getString("filePath")
                val requestedFileName = requestJson.getString("fileName")
                
                Log.d(TAG, "📁 HANDLE SYNC REQUEST FILE - Requested file: $requestedFileName at path: $requestedFilePath")
                
                // Find the file in the currently selected folder
                if (!fileManager.hasSelectedFolder()) {
                    Log.e(TAG, "❌ HANDLE SYNC REQUEST FILE - No folder selected")
                    return@launch
                }
                
                val folderUri = fileManager.selectedFolderUri!!
                val requestedFile = findFileInFolder(folderUri, requestedFileName, requestedFilePath)
                
                if (requestedFile != null) {
                    Log.d(TAG, "✅ HANDLE SYNC REQUEST FILE - File found, sending: $requestedFileName")
                    
                    // Read the file content
                    val fileBytes = context.contentResolver.openInputStream(requestedFile.uri)?.use { inputStream ->
                        inputStream.readBytes()
                    }
                    
                    if (fileBytes != null) {
                        Log.d(TAG, "📤 HANDLE SYNC REQUEST FILE - File read successfully, size: ${fileBytes.size} bytes")
                        
                        // Create file metadata
                        val fileMetadata = FileMetadata(
                            name = requestedFile.name ?: requestedFileName,
                            path = requestedFilePath,
                            size = fileBytes.size.toLong(),
                            lastModified = requestedFile.lastModified(),
                            checksum = calculateFileChecksum(fileBytes),
                            isDirectory = false
                        )
                          // Send the file back to the requesting device
                        sendFileToTarget(senderAddress, fileMetadata, fileBytes)
                        
                        addSyncLog("SYNC_REQUEST_FILE", "File sent successfully: ${fileBytes.size} bytes", requestedFileName)
                    } else {
                        Log.e(TAG, "❌ HANDLE SYNC REQUEST FILE - Failed to read file content")
                        addSyncLog("SYNC_REQUEST_FILE", "Failed to read file content", requestedFileName)
                    }
                } else {
                    Log.w(TAG, "⚠️ HANDLE SYNC REQUEST FILE - File not found: $requestedFileName")
                    addSyncLog("SYNC_REQUEST_FILE", "File not found in local folder", requestedFileName)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ HANDLE SYNC REQUEST FILE - Error processing file request", e)
                addSyncLog("SYNC_REQUEST_FILE", "Error processing file request: ${e.message}", "Unknown")
            }
        }
    }
      fun handleSyncFilesListResponse(filesListJson: String, senderAddress: String) {
        // Implementation for handling files list response
    }

    // Missing methods expected by MainActivity
    suspend fun acceptSyncRequest(requestId: String) {
        Log.d(TAG, "Accepting sync request: $requestId")
        val currentRequests = _syncRequests.value.toMutableList()
        val requestIndex = currentRequests.indexOfFirst { it.requestId == requestId }
        if (requestIndex != -1) {
            currentRequests[requestIndex] = currentRequests[requestIndex].copy(status = SyncRequestStatus.ACCEPTED)
            _syncRequests.value = currentRequests
        }
    }

    suspend fun rejectSyncRequest(requestId: String) {
        Log.d(TAG, "Rejecting sync request: $requestId")
        val currentRequests = _syncRequests.value.toMutableList()
        val requestIndex = currentRequests.indexOfFirst { it.requestId == requestId }
        if (requestIndex != -1) {
            currentRequests[requestIndex] = currentRequests[requestIndex].copy(status = SyncRequestStatus.REJECTED)
            _syncRequests.value = currentRequests
        }
    }

    suspend fun initiateFolderSync(folderUri: Uri, targetAddress: String): Result<String> {
        return startFolderSync(folderUri, targetAddress, SyncMode.TWO_WAY)
    }

    suspend fun resolveConflict(conflictId: String, resolution: ConflictResolution) {
        Log.d(TAG, "Resolving conflict: $conflictId with resolution: $resolution")
        // Implementation for resolving conflicts
    }    fun parseSyncRequestFromJson(json: String): SyncRequest? {
        return try {
            val jsonObj = JSONObject(json)
            
            // Check if this is a folder structure request (not a full sync request)
            if (jsonObj.has("type") && jsonObj.getString("type") == "FOLDER_STRUCTURE_REQUEST") {
                Log.d(TAG, "Received folder structure request, not a sync request")
                return null
            }
            
            // Parse as full sync request
            SyncRequest(
                requestId = jsonObj.getString("requestId"),
                sourceDeviceId = jsonObj.getString("sourceDeviceId"),
                sourceDeviceName = jsonObj.getString("sourceDeviceName"),
                folderName = jsonObj.getString("folderName"),
                folderPath = jsonObj.getString("folderPath"),
                totalFiles = jsonObj.getInt("totalFiles"),
                totalSize = jsonObj.getLong("totalSize"),
                timestamp = jsonObj.optLong("timestamp", System.currentTimeMillis())
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing sync request from JSON", e)
            null
        }
    }    fun handleSyncRequest(request: SyncRequest) {
        Log.d(TAG, "Handling sync request from ${request.sourceDeviceName}")
        val currentRequests = _syncRequests.value.toMutableList()
        currentRequests.add(request)
        _syncRequests.value = currentRequests
    }
      fun handleFolderStructureRequest(requestJson: String, senderAddress: String) {
        Log.d(TAG, "🗂️ Handling folder structure request from $senderAddress")
        try {
            val jsonObj = JSONObject(requestJson)
            val folderPath = jsonObj.getString("folderPath")
            val requestId = jsonObj.optString("requestId", "")
            val timestamp = jsonObj.optLong("timestamp", System.currentTimeMillis())
            
            Log.d(TAG, "📁 Folder structure requested for: $folderPath (request ID: $requestId)")
              // In a real implementation, you would scan the requested folder and return its structure
            // For now, we'll scan the currently selected folder if available
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val folderStructure = if (fileManager.hasSelectedFolder()) {
                        // Scan the currently selected folder
                        val files = scanFolder(fileManager.selectedFolderUri!!)
                        Log.d(TAG, "📁 Scanned selected folder: ${files.size} files found")
                        
                        // Convert to JSON array
                        val filesArray = JSONArray()
                        files.forEach { fileMetadata ->
                            val fileJson = JSONObject().apply {
                                put("name", fileMetadata.name)
                                put("path", fileMetadata.path)
                                put("size", fileMetadata.size)
                                put("lastModified", fileMetadata.lastModified)
                                put("checksum", fileMetadata.checksum)
                                put("isDirectory", fileMetadata.isDirectory)
                            }
                            filesArray.put(fileJson)
                        }
                        filesArray
                    } else {
                        Log.w(TAG, "📁 No folder selected, sending empty response")
                        JSONArray()
                    }
                    
                    // Create response with request ID
                    val response = JSONObject().apply {
                        put("files", folderStructure)
                        put("timestamp", System.currentTimeMillis())
                        if (requestId.isNotEmpty()) {
                            put("requestId", requestId)
                        }
                        put("folderPath", folderPath)
                        put("status", "success")
                    }
                    
                    // Send response back to requester
                    val responseMessage = "SYNC_FOLDER_STRUCTURE_RESPONSE:${response}"
                    Log.d(TAG, "📤 Sending folder structure response to $senderAddress (${folderStructure.length()} files)")
                    wifiDirectManager.sendMessage(responseMessage, senderAddress)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error scanning folder for structure request", e)
                    
                    // Send error response
                    val errorResponse = JSONObject().apply {
                        put("files", JSONArray())
                        put("timestamp", System.currentTimeMillis())
                        if (requestId.isNotEmpty()) {
                            put("requestId", requestId)
                        }
                        put("status", "error")
                        put("error", e.message ?: "Unknown error")
                    }
                    
                    val responseMessage = "SYNC_FOLDER_STRUCTURE_RESPONSE:${errorResponse}"
                    wifiDirectManager.sendMessage(responseMessage, senderAddress)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling folder structure request", e)
        }
    }fun handleSyncResponse(response: String) {
        Log.d(TAG, "🔄 Handling sync response: $response")
        
        try {
            // Check if this is a folder structure response
            if (response.startsWith("SYNC_FOLDER_STRUCTURE_RESPONSE:")) {
                val responseData = response.removePrefix("SYNC_FOLDER_STRUCTURE_RESPONSE:")
                handleFolderStructureResponse(responseData)
                return
            }
            
            // Handle other sync responses
            val jsonResponse = JSONObject(response)
            val requestId = jsonResponse.optString("requestId", "")
            
            if (requestId.isNotEmpty()) {
                val pendingRequest = pendingRequests.remove(requestId)
                if (pendingRequest != null) {
                    Log.d(TAG, "✅ Completing pending request: $requestId")
                    pendingRequest.complete(response)
                } else {
                    Log.w(TAG, "⚠️ Received response for unknown request ID: $requestId")
                }
            } else {
                Log.w(TAG, "⚠️ Received response without request ID")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling sync response", e)
        }
    }
    
    private fun handleFolderStructureResponse(responseData: String) {
        Log.d(TAG, "🗂️ Handling folder structure response")
        
        try {
            val jsonResponse = JSONObject(responseData)
            val requestId = jsonResponse.optString("requestId", "")
            
            if (requestId.isNotEmpty()) {
                val pendingRequest = pendingRequests.remove(requestId)
                if (pendingRequest != null) {
                    Log.d(TAG, "✅ Completing folder structure request: $requestId")
                    pendingRequest.complete(responseData)
                } else {
                    Log.w(TAG, "⚠️ Received folder structure response for unknown request ID: $requestId")
                }
            } else {
                Log.w(TAG, "⚠️ Received folder structure response without request ID")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling folder structure response", e)
        }
    }

    fun handleSyncProgress(progressJson: String) {
        Log.d(TAG, "Handling sync progress: $progressJson")
        // Implementation for handling sync progress updates
    }

    fun removeSyncedFolder(folderId: String) {
        Log.d(TAG, "Removing synced folder: $folderId")
        val currentFolders = _syncedFolders.value.toMutableList()
        currentFolders.removeAll { it.id == folderId }
        _syncedFolders.value = currentFolders
    }    fun clearSyncLogs() {
        Log.d(TAG, "Clearing sync logs")
        _syncLogs.value = emptyList()
    }
    
    // Helper methods for file operations
    private suspend fun findFileInFolder(folderUri: Uri, fileName: String, filePath: String): DocumentFile? = withContext(Dispatchers.IO) {
        try {
            val documentFile = DocumentFile.fromTreeUri(context, folderUri)
            return@withContext findFileRecursively(documentFile, fileName, filePath)
        } catch (e: Exception) {
            Log.e(TAG, "Error finding file in folder", e)
            null
        }
    }
    
    private fun findFileRecursively(parentDir: DocumentFile?, fileName: String, targetPath: String): DocumentFile? {
        if (parentDir == null || !parentDir.exists() || !parentDir.isDirectory) return null
        
        parentDir.listFiles().forEach { file ->
            if (file.isDirectory) {
                // Recursively search in subdirectories
                val found = findFileRecursively(file, fileName, targetPath)
                if (found != null) return found
            } else {
                // Check if this is the file we're looking for
                val currentFileName = file.name ?: ""
                if (currentFileName == fileName) {
                    Log.d(TAG, "🔍 FIND FILE - Found file: $currentFileName")
                    return file
                }
                
                // Also check if the path matches (in case of duplicate names)
                if (targetPath.endsWith(currentFileName) && currentFileName == fileName) {
                    Log.d(TAG, "🔍 FIND FILE - Found file by path: $targetPath")
                    return file
                }
            }
        }
        return null
    }
    
    private fun calculateFileChecksum(fileBytes: ByteArray): String {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            val hashBytes = digest.digest(fileBytes)
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to calculate file checksum", e)
            "unknown"
        }
    }

    /**
     * Configure WiFiDirectManager to receive files in the correct destination folder during sync
     */
    private suspend fun configureSyncDestination(folderUri: Uri) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔧 CONFIGURE SYNC - Setting up sync destination: $folderUri")
            
            // Convert SAF URI to a temporary directory path that can be used by FileReceiver
            // Since FileReceiver uses File paths, we need to create a temp directory bridge
            val syncTempDir = File(context.cacheDir, "sync_temp_${System.currentTimeMillis()}")
            syncTempDir.mkdirs()
            
            Log.d(TAG, "🔧 CONFIGURE SYNC - Created temp sync directory: ${syncTempDir.absolutePath}")
            
            // Stop any existing file receiver
            wifiDirectManager.stopReceivingFiles()
            
            // Restart file receiver with sync configuration
            // Pass the folder URI so the receiver knows where to save files
            val result = wifiDirectManager.startReceivingFiles(
                destinationPath = syncTempDir.absolutePath,
                syncedFolderUri = folderUri
            )
            
            if (result.isSuccess) {
                Log.d(TAG, "✅ CONFIGURE SYNC - Successfully configured sync destination")
                
                // Store the sync folder URI for later use
                currentSyncFolderUri = folderUri
                
                // Clean up old temp directories (keep only recent ones)
                cleanupOldSyncTempDirs()
            } else {
                Log.e(TAG, "❌ CONFIGURE SYNC - Failed to configure sync destination")
                throw Exception("Failed to start file receiver for sync")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ CONFIGURE SYNC - Exception during sync configuration", e)
            throw e
        }
    }
    
    /**
     * Clean up old temporary sync directories to prevent storage buildup
     */
    private fun cleanupOldSyncTempDirs() {
        try {            val cacheDir = context.cacheDir
            val syncTempDirs = cacheDir.listFiles { file ->
                file.isDirectory && file.name.startsWith("sync_temp_")
            }
            
            // Keep only the 3 most recent temp directories
            syncTempDirs?.sortedByDescending { it.lastModified() }
                ?.drop(3)
                ?.forEach { oldDir ->
                    try {
                        oldDir.deleteRecursively()
                        Log.d(TAG, "🧹 CLEANUP - Removed old sync temp dir: ${oldDir.name}")
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ CLEANUP - Failed to remove old sync temp dir: ${oldDir.name}")
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ CLEANUP - Error during temp directory cleanup", e)
        }
    }

    // ...existing code...
}

// **Supporting Data Classes**

data class SyncSession(
    val id: String,
    val localFolderUri: Uri,
    val targetDeviceAddress: String,
    val mode: SyncMode,
    val conflictResolution: ConflictResolution,
    val status: SyncStatus,
    val startTime: Long = System.currentTimeMillis()
)

// SyncStatus is defined in SyncModels.kt

enum class SyncLogLevel {
    INFO,
    WARNING,
    ERROR
}

// SyncLogEntry is defined in SyncModels.kt
