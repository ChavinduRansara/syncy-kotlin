package com.example.syncy_p2p.p2p.core

import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import com.example.syncy_p2p.files.FileTransferMetadata
import com.example.syncy_p2p.sync.SyncedFolder
import com.example.syncy_p2p.sync.SyncLogEntry
import com.example.syncy_p2p.sync.SyncStatus
import com.example.syncy_p2p.sync.ConflictResolution
import com.example.syncy_p2p.sync.SyncLogStatus
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset

object Utils {
    const val CHARSET = "UTF-8"
    private const val TAG = "SyncyP2P"
    const val BUFFER_SIZE = 4096

    fun copyBytes(inputStream: InputStream?, outputStream: OutputStream?): Boolean {
        if (inputStream == null || outputStream == null) {
            Log.e(TAG, "copyBytes error: input or output stream is null")
            return false
        }

        return try {
            inputStream.use { input ->
                outputStream.use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                    output.flush()
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "copyBytes error", e)
            false
        }
    }

    fun inputStreamToString(inputStream: InputStream): String {
        return inputStream.bufferedReader(Charset.forName(CHARSET)).use { it.readText() }
    }
    
    fun getErrorMessage(reason: Int): String {
        return when (reason) {
            0 -> "Error"
            1 -> "P2P unsupported"
            2 -> "Busy"
            else -> "Unknown error ($reason)"
        }
    }    fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "")
        return if (extension.isNotEmpty()) {
            when (extension.lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "mp4" -> "video/mp4"
                "avi" -> "video/avi"
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                "txt" -> "text/plain"
                "pdf" -> "application/pdf"
                "zip" -> "application/zip"
                "rar" -> "application/x-rar-compressed"
                "doc", "docx" -> "application/msword"
                "xls", "xlsx" -> "application/vnd.ms-excel"
                "ppt", "pptx" -> "application/vnd.ms-powerpoint"
                else -> "application/octet-stream"
            }
        } else {
            "application/octet-stream"
        }
    }
    
    fun sendMetadata(outputStream: OutputStream, metadata: FileTransferMetadata) {
        try {
            // Send metadata as JSON string with length prefix
            val metadataJson = """
                {
                    "fileName": "${metadata.fileName}",
                    "fileSize": ${metadata.fileSize},
                    "mimeType": "${metadata.mimeType ?: ""}"
                }
            """.trimIndent()
            
            val metadataBytes = metadataJson.toByteArray(Charset.forName(CHARSET))
            val lengthBuffer = ByteBuffer.allocate(4).putInt(metadataBytes.size).array()
            
            outputStream.write(lengthBuffer)
            outputStream.write(metadataBytes)
            outputStream.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending metadata", e)
            throw e
        }
    }

    fun receiveMetadata(inputStream: InputStream): FileTransferMetadata? {
        return try {
            // Read metadata length
            val lengthBuffer = ByteArray(4)
            val bytesRead = inputStream.read(lengthBuffer)
            if (bytesRead != 4) return null
            
            val metadataLength = ByteBuffer.wrap(lengthBuffer).int
            if (metadataLength <= 0 || metadataLength > 1024) return null // Sanity check
            
            // Read metadata JSON
            val metadataBuffer = ByteArray(metadataLength)
            val totalRead = inputStream.read(metadataBuffer)
            if (totalRead != metadataLength) return null
            
            val metadataJson = String(metadataBuffer, Charset.forName(CHARSET))
            
            // Simple JSON parsing (for production, use a proper JSON library)
            val fileName = extractJsonValue(metadataJson, "fileName")
            val fileSize = extractJsonValue(metadataJson, "fileSize")?.toLongOrNull() ?: 0L
            val mimeType = extractJsonValue(metadataJson, "mimeType")?.takeIf { it.isNotEmpty() }
            
            if (fileName != null) {
                FileTransferMetadata(fileName, fileSize, mimeType)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error receiving metadata", e)
            null
        }
    }    fun extractJsonValue(json: String, key: String): String? {
        val pattern = "\"$key\":\\s*\"([^\"]*)\"|\"$key\":\\s*([^,}\\s]*)"
        val regex = Regex(pattern)
        val matchResult = regex.find(json)
        return matchResult?.groups?.get(1)?.value ?: matchResult?.groups?.get(2)?.value
    }

    fun serializeMetadata(metadata: FileTransferMetadata): String {
        return """
            {
                "fileName": "${metadata.fileName}",
                "fileSize": ${metadata.fileSize},
                "mimeType": "${metadata.mimeType ?: ""}",
                "checksum": "${metadata.checksum ?: ""}"
            }
        """.trimIndent()
    }
    
    fun deserializeMetadata(json: String): FileTransferMetadata? {
        return try {
            val fileName = extractJsonValue(json, "fileName") ?: return null
            val fileSize = extractJsonValue(json, "fileSize")?.toLongOrNull() ?: 0L
            val mimeType = extractJsonValue(json, "mimeType")?.takeIf { it.isNotEmpty() }
            val checksum = extractJsonValue(json, "checksum")?.takeIf { it.isNotEmpty() }
            
            FileTransferMetadata(fileName, fileSize, mimeType, checksum)
        } catch (e: Exception) {
            Log.e(TAG, "Error deserializing metadata", e)
            null
        }
    }
    
    /**
     * Serialize a list of SyncedFolder objects to JSON
     */
    fun serializeSyncedFolders(folders: List<SyncedFolder>): String {
        val foldersJson = folders.joinToString(",") { folder ->
            """
                {
                    "id": "${folder.id}",
                    "name": "${escapeJsonString(folder.name)}",
                    "localPath": "${escapeJsonString(folder.localPath)}",
                    "localUri": "${folder.localUri?.toString() ?: ""}",
                    "remoteDeviceId": "${folder.remoteDeviceId}",
                    "remoteDeviceName": "${escapeJsonString(folder.remoteDeviceName)}",
                    "remotePath": "${escapeJsonString(folder.remotePath)}",
                    "lastSyncTime": ${folder.lastSyncTime},
                    "status": "${folder.status.name}",
                    "autoSync": ${folder.autoSync},
                    "conflictResolution": "${folder.conflictResolution.name}"
                }
            """.trimIndent()
        }
        return "[$foldersJson]"
    }
    
    /**
     * Deserialize JSON to a list of SyncedFolder objects
     */
    fun deserializeSyncedFolders(json: String): List<SyncedFolder> {
        if (json.isBlank() || json == "[]") return emptyList()
        
        return try {
            val folders = mutableListOf<SyncedFolder>()
            
            // Simple JSON array parsing
            val trimmed = json.trim()
            if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return emptyList()
            
            val content = trimmed.substring(1, trimmed.length - 1)
            if (content.isBlank()) return emptyList()
            
            // Split by objects (look for },{ pattern)
            val objectStrings = splitJsonObjects(content)
            
            for (objectStr in objectStrings) {
                val folder = parseSyncedFolder(objectStr.trim())
                if (folder != null) folders.add(folder)
            }
            
            folders
        } catch (e: Exception) {
            Log.e(TAG, "Error deserializing synced folders", e)
            emptyList()
        }
    }
    
    /**
     * Serialize a list of SyncLogEntry objects to JSON
     */
    fun serializeSyncLogs(logs: List<SyncLogEntry>): String {
        val logsJson = logs.joinToString(",") { log ->
            """
                {
                    "id": "${log.id}",
                    "folderName": "${escapeJsonString(log.folderName)}",
                    "timestamp": ${log.timestamp},
                    "action": "${escapeJsonString(log.action)}",
                    "fileName": "${escapeJsonString(log.fileName ?: "")}",
                    "status": "${log.status.name}",
                    "message": "${escapeJsonString(log.message)}",
                    "deviceName": "${escapeJsonString(log.deviceName)}"
                }
            """.trimIndent()
        }
        return "[$logsJson]"
    }
    
    /**
     * Deserialize JSON to a list of SyncLogEntry objects
     */
    fun deserializeSyncLogs(json: String): List<SyncLogEntry> {
        if (json.isBlank() || json == "[]") return emptyList()
        
        return try {
            val logs = mutableListOf<SyncLogEntry>()
            
            // Simple JSON array parsing
            val trimmed = json.trim()
            if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return emptyList()
            
            val content = trimmed.substring(1, trimmed.length - 1)
            if (content.isBlank()) return emptyList()
            
            // Split by objects (look for },{ pattern)
            val objectStrings = splitJsonObjects(content)
            
            for (objectStr in objectStrings) {
                val log = parseSyncLogEntry(objectStr.trim())
                if (log != null) logs.add(log)
            }
            
            logs
        } catch (e: Exception) {
            Log.e(TAG, "Error deserializing sync logs", e)
            emptyList()
        }
    }
    
    private fun splitJsonObjects(content: String): List<String> {
        val objects = mutableListOf<String>()
        var braceCount = 0
        var start = 0
        
        for (i in content.indices) {
            when (content[i]) {
                '{' -> braceCount++
                '}' -> {
                    braceCount--
                    if (braceCount == 0) {
                        objects.add(content.substring(start, i + 1))
                        start = i + 1
                        // Skip comma and whitespace
                        while (start < content.length && (content[start] == ',' || content[start].isWhitespace())) {
                            start++
                        }
                    }
                }
            }
        }
        
        return objects
    }
    
    private fun parseSyncedFolder(json: String): SyncedFolder? {
        return try {
            val id = extractJsonValue(json, "id") ?: return null
            val name = extractJsonValue(json, "name") ?: return null
            val localPath = extractJsonValue(json, "localPath") ?: return null
            val localUriStr = extractJsonValue(json, "localUri")
            val localUri = if (localUriStr.isNullOrBlank()) null else Uri.parse(localUriStr)
            val remoteDeviceId = extractJsonValue(json, "remoteDeviceId") ?: return null
            val remoteDeviceName = extractJsonValue(json, "remoteDeviceName") ?: return null
            val remotePath = extractJsonValue(json, "remotePath") ?: return null
            val lastSyncTime = extractJsonValue(json, "lastSyncTime")?.toLongOrNull() ?: 0L
            val statusStr = extractJsonValue(json, "status") ?: "DISCONNECTED"
            val status = try { SyncStatus.valueOf(statusStr) } catch (e: Exception) { SyncStatus.DISCONNECTED }
            val autoSync = extractJsonValue(json, "autoSync")?.toBooleanStrictOrNull() ?: true
            val conflictResolutionStr = extractJsonValue(json, "conflictResolution") ?: "ASK_USER"
            val conflictResolution = try { ConflictResolution.valueOf(conflictResolutionStr) } catch (e: Exception) { ConflictResolution.ASK_USER }
            
            SyncedFolder(
                id = id,
                name = name,
                localPath = localPath,
                localUri = localUri,
                remoteDeviceId = remoteDeviceId,
                remoteDeviceName = remoteDeviceName,
                remotePath = remotePath,
                lastSyncTime = lastSyncTime,
                status = status,
                autoSync = autoSync,
                conflictResolution = conflictResolution
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing SyncedFolder", e)
            null
        }
    }
    
    private fun parseSyncLogEntry(json: String): SyncLogEntry? {
        return try {
            val id = extractJsonValue(json, "id") ?: return null
            val folderName = extractJsonValue(json, "folderName") ?: return null
            val timestamp = extractJsonValue(json, "timestamp")?.toLongOrNull() ?: return null
            val action = extractJsonValue(json, "action") ?: return null
            val fileName = extractJsonValue(json, "fileName")?.takeIf { it.isNotBlank() }
            val statusStr = extractJsonValue(json, "status") ?: "INFO"
            val status = try { SyncLogStatus.valueOf(statusStr) } catch (e: Exception) { SyncLogStatus.INFO }
            val message = extractJsonValue(json, "message") ?: return null
            val deviceName = extractJsonValue(json, "deviceName") ?: return null
            
            SyncLogEntry(
                id = id,
                folderName = folderName,
                timestamp = timestamp,
                action = action,
                fileName = fileName,
                status = status,
                message = message,
                deviceName = deviceName
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing SyncLogEntry", e)
            null
        }
    }
    
    private fun escapeJsonString(str: String): String {
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t")
    }
}
