package com.example.syncy_p2p.p2p.core

import android.util.Log
import android.webkit.MimeTypeMap
import com.example.syncy_p2p.files.FileTransferMetadata
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
    }

    private fun extractJsonValue(json: String, key: String): String? {
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
}
