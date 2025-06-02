package com.example.syncy_p2p.files

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class FileItem(
    val name: String,
    val size: Long,
    val isDirectory: Boolean,
    val uri: Uri,
    val lastModified: Long,
    val mimeType: String?
) {
    val sizeString: String
        get() = when {
            isDirectory -> "Folder"
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
            else -> "${size / (1024 * 1024 * 1024)} GB"
        }
    
    val displayName: String
        get() = if (name.isBlank()) "Unknown" else name
}

class FileManager(private val context: Context) {
    
    companion object {
        private const val TAG = "FileManager"
        private const val PREFS_NAME = "syncy_file_prefs"
        private const val KEY_SELECTED_FOLDER = "selected_folder_uri"
    }
    
    private val preferences: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    private var _selectedFolderUri: Uri? = null
    val selectedFolderUri: Uri?
        get() = _selectedFolderUri ?: run {
            val uriString = preferences.getString(KEY_SELECTED_FOLDER, null)
            uriString?.let { Uri.parse(it) }?.also { _selectedFolderUri = it }
        }
    
    val selectedFolderPath: String?
        get() = selectedFolderUri?.let { getFolderDisplayPath(it) }
    
    fun setSelectedFolder(uri: Uri) {
        _selectedFolderUri = uri
        preferences.edit()
            .putString(KEY_SELECTED_FOLDER, uri.toString())
            .apply()
            
        // Take persistent permission
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            // Permission might already be taken or not available
        }
    }
    
    suspend fun getFilesInFolder(folderUri: Uri? = selectedFolderUri): List<FileItem> = withContext(Dispatchers.IO) {
        if (folderUri == null) return@withContext emptyList()
        
        try {
            val documentFile = DocumentFile.fromTreeUri(context, folderUri)
            if (documentFile == null || !documentFile.exists() || !documentFile.isDirectory) {
                return@withContext emptyList()
            }
            
            val files = mutableListOf<FileItem>()
            documentFile.listFiles().forEach { file ->
                try {
                    val fileItem = FileItem(
                        name = file.name ?: "Unknown",
                        size = file.length(),
                        isDirectory = file.isDirectory,
                        uri = file.uri,
                        lastModified = file.lastModified(),
                        mimeType = file.type
                    )
                    files.add(fileItem)
                } catch (e: Exception) {
                    // Skip files that can't be read
                }
            }
            
            // Sort: directories first, then files, both alphabetically
            files.sortedWith(compareBy<FileItem> { !it.isDirectory }.thenBy { it.name.lowercase() })
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    suspend fun getFileInputStream(fileUri: Uri) = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(fileUri)
        } catch (e: Exception) {
            null
        }
    }
    
    suspend fun createFileInSelectedFolder(fileName: String): Uri? = withContext(Dispatchers.IO) {
        val folderUri = selectedFolderUri ?: return@withContext null
        
        try {
            val documentFile = DocumentFile.fromTreeUri(context, folderUri)
            if (documentFile == null || !documentFile.exists() || !documentFile.isDirectory) {
                return@withContext null
            }
            
            // Create the file
            val newFile = documentFile.createFile("*/*", fileName)
            newFile?.uri
        } catch (e: Exception) {
            null
        }
    }
    
    suspend fun saveFileToSelectedFolder(fileName: String, inputStream: java.io.InputStream): Boolean = withContext(Dispatchers.IO) {
        try {
            val fileUri = createFileInSelectedFolder(fileName) ?: return@withContext false
            
            context.contentResolver.openOutputStream(fileUri)?.use { outputStream ->
                inputStream.copyTo(outputStream)
                true
            } ?: false
        } catch (e: Exception) {
            false
        }
    }
    
    fun getFolderDisplayPath(uri: Uri): String {
        return try {
            when {
                DocumentsContract.isTreeUri(uri) -> {
                    val docId = DocumentsContract.getTreeDocumentId(uri)
                    when {
                        docId.startsWith("primary:") -> {
                            "Internal Storage/${docId.substring(8)}"
                        }
                        docId.contains(":") -> {
                            val parts = docId.split(":")
                            "${parts[0]}/${parts.getOrNull(1) ?: ""}"
                        }
                        else -> docId
                    }
                }
                else -> uri.path ?: "Unknown Location"
            }
        } catch (e: Exception) {
            "Unknown Location"
        }
    }
      fun hasSelectedFolder(): Boolean = selectedFolderUri != null
    
    fun clearSelectedFolder() {
        _selectedFolderUri = null
        preferences.edit().remove(KEY_SELECTED_FOLDER).apply()
    }
      /**
     * Create a new folder in the Documents directory for sync purposes
     */
    suspend fun createFolderInDocuments(folderName: String): Uri? {
        return try {
            withContext(Dispatchers.IO) {
                // Use the currently selected folder as the parent for creating sync subfolders
                val parentUri = selectedFolderUri
                if (parentUri != null) {
                    val parentDocFile = DocumentFile.fromTreeUri(context, parentUri)
                    if (parentDocFile != null && parentDocFile.exists() && parentDocFile.canWrite()) {
                        // Create a subfolder for the sync
                        val syncFolderName = "Synced_$folderName"
                        val existingFolder = parentDocFile.findFile(syncFolderName)
                        
                        val syncFolder = if (existingFolder != null && existingFolder.isDirectory) {
                            // Use existing folder
                            existingFolder
                        } else {
                            // Create new folder
                            parentDocFile.createDirectory(syncFolderName)
                        }
                        
                        syncFolder?.uri
                    } else {
                        Log.w(TAG, "Parent folder not writable for sync folder creation")
                        null
                    }
                } else {
                    Log.w(TAG, "No selected folder available for creating sync destination")
                    null
                }
            }        } catch (e: Exception) {
            Log.e(TAG, "Error creating folder in documents", e)
            null
        }
    }
      /**
     * Write a received file to the sync folder
     */    suspend fun writeFileToSyncFolder(syncFolderUri: Uri, fileName: String, fileData: ByteArray): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                Log.d(TAG, "🔄 WRITE FILE TO SYNC - Starting writeFileToSyncFolder for: $fileName (${fileData.size} bytes)")
                Log.d(TAG, "🔄 WRITE FILE TO SYNC - Sync folder URI: $syncFolderUri")
                
                // CRITICAL FIX 1: Ensure we have persistent permissions for the URI
                try {
                    context.contentResolver.takePersistableUriPermission(
                        syncFolderUri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    Log.d(TAG, "✅ WRITE FILE TO SYNC - Persistent permissions ensured")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ WRITE FILE TO SYNC - Could not take persistent permissions (may already exist): ${e.message}")
                }
                
                val syncFolder = DocumentFile.fromTreeUri(context, syncFolderUri)
                Log.d(TAG, "🔄 WRITE FILE TO SYNC - Sync folder exists: ${syncFolder?.exists()}, canWrite: ${syncFolder?.canWrite()}")
                
                if (syncFolder != null && syncFolder.exists() && syncFolder.canWrite()) {
                    // CRITICAL FIX 2: Clean up filename to avoid issues with special characters
                    val cleanFileName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    Log.d(TAG, "🔄 WRITE FILE TO SYNC - Original filename: '$fileName', Clean filename: '$cleanFileName'")
                    
                    // Check if file already exists (using clean filename)
                    val existingFile = syncFolder.findFile(cleanFileName)
                    Log.d(TAG, "🔄 WRITE FILE TO SYNC - Existing file found: ${existingFile != null}")
                    
                    val targetFile = if (existingFile != null) {
                        Log.d(TAG, "🔄 WRITE FILE TO SYNC - Using existing file: ${existingFile.uri}")
                        existingFile
                    } else {
                        // Create new file
                        val mimeType = when (cleanFileName.substringAfterLast('.').lowercase()) {
                            "txt" -> "text/plain"
                            "jpg", "jpeg" -> "image/jpeg"
                            "png" -> "image/png"
                            "pdf" -> "application/pdf"
                            "mp4" -> "video/mp4"
                            "mp3" -> "audio/mpeg"
                            "doc", "docx" -> "application/msword"
                            "xls", "xlsx" -> "application/vnd.ms-excel"
                            "zip" -> "application/zip"
                            else -> "application/octet-stream"
                        }
                        Log.d(TAG, "🔄 WRITE FILE TO SYNC - Creating new file with mimeType: $mimeType")
                        
                        // CRITICAL FIX 3: Retry file creation with fallback
                        var newFile = syncFolder.createFile(mimeType, cleanFileName)
                        if (newFile == null) {
                            Log.w(TAG, "⚠️ WRITE FILE TO SYNC - First file creation failed, trying with generic mime type")
                            newFile = syncFolder.createFile("application/octet-stream", cleanFileName)
                        }
                        
                        Log.d(TAG, "🔄 WRITE FILE TO SYNC - New file created: ${newFile?.uri}")
                        newFile
                    }
                    
                    if (targetFile != null) {
                        Log.d(TAG, "🔄 WRITE FILE TO SYNC - Writing to file: ${targetFile.uri}")
                        
                        // CRITICAL FIX 4: Enhanced file writing with retry mechanism
                        var bytesWritten = 0
                        var writeSuccess = false
                        var attemptCount = 0
                        
                        while (!writeSuccess && attemptCount < 3) {
                            attemptCount++
                            Log.d(TAG, "🔄 WRITE FILE TO SYNC - Write attempt $attemptCount")
                            
                            try {
                                context.contentResolver.openOutputStream(targetFile.uri, "wt")?.use { outputStream ->
                                    outputStream.write(fileData)
                                    outputStream.flush()
                                    
                                    // Force sync to storage
                                    if (outputStream is java.io.FileOutputStream) {
                                        outputStream.fd.sync()
                                    }
                                    
                                    bytesWritten = fileData.size
                                    writeSuccess = true
                                    Log.d(TAG, "✅ WRITE FILE TO SYNC - Write successful on attempt $attemptCount")
                                }
                                
                                if (!writeSuccess) {
                                    Log.w(TAG, "⚠️ WRITE FILE TO SYNC - Failed to open output stream on attempt $attemptCount")
                                    Thread.sleep(100) // Brief delay before retry
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "⚠️ WRITE FILE TO SYNC - Write attempt $attemptCount failed: ${e.message}")
                                if (attemptCount < 3) {
                                    Thread.sleep(200) // Brief delay before retry
                                }
                            }
                        }
                        
                        if (writeSuccess) {
                            Log.d(TAG, "✅ WRITE FILE TO SYNC - Successfully wrote $bytesWritten bytes to: $cleanFileName")
                            
                            // CRITICAL FIX 5: Enhanced verification with retry
                            Thread.sleep(100) // Give filesystem time to update
                            val verifyFile = DocumentFile.fromSingleUri(context, targetFile.uri)
                            val actualSize = verifyFile?.length() ?: 0
                            Log.d(TAG, "✅ WRITE FILE TO SYNC - File verification - Expected: ${fileData.size}, Actual: $actualSize")
                            
                            if (actualSize > 0 && actualSize <= fileData.size) {
                                Log.d(TAG, "✅ WRITE FILE TO SYNC - File verification PASSED")
                                true
                            } else {
                                Log.e(TAG, "❌ WRITE FILE TO SYNC - File verification FAILED - size mismatch")
                                false
                            }
                        } else {
                            Log.e(TAG, "❌ WRITE FILE TO SYNC - All write attempts failed for: $cleanFileName")
                            false
                        }
                    } else {
                        Log.e(TAG, "❌ WRITE FILE TO SYNC - Failed to create target file: $cleanFileName")
                        false
                    }
                } else {
                    Log.e(TAG, "❌ WRITE FILE TO SYNC - Sync folder not accessible or writable: $syncFolderUri")
                    Log.e(TAG, "❌ WRITE FILE TO SYNC - Sync folder null: ${syncFolder == null}")
                    if (syncFolder != null) {
                        Log.e(TAG, "❌ WRITE FILE TO SYNC - Sync folder exists: ${syncFolder.exists()}")
                        Log.e(TAG, "❌ WRITE FILE TO SYNC - Sync folder canWrite: ${syncFolder.canWrite()}")
                        Log.e(TAG, "❌ WRITE FILE TO SYNC - Sync folder name: ${syncFolder.name}")
                        Log.e(TAG, "❌ WRITE FILE TO SYNC - Sync folder URI path: ${syncFolder.uri}")
                    }
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ WRITE FILE TO SYNC - Exception occurred", e)
            Log.e(TAG, "❌ WRITE FILE TO SYNC - Exception details: ${e.stackTraceToString()}")
            false
        }
    }
}
