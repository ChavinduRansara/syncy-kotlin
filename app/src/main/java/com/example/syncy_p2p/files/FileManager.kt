package com.example.syncy_p2p.files

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
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
    
    private fun getFolderDisplayPath(uri: Uri): String {
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
}
