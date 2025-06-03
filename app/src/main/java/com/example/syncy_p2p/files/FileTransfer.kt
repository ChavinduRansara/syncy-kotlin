package com.example.syncy_p2p.files

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.io.Serializable

@Parcelize
data class FileTransferMetadata(
    val fileName: String,
    val fileSize: Long,
    val mimeType: String?,
    val checksum: String? = null
) : Parcelable

data class FileTransferProgress(
    val fileName: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val percentage: Int = if (totalBytes > 0) ((bytesTransferred * 100) / totalBytes).toInt() else 0,
    val isCompleted: Boolean = bytesTransferred >= totalBytes,
    val error: String? = null
)

interface FileTransferCallback {
    fun onTransferStarted(metadata: FileTransferMetadata)
    fun onProgressUpdate(progress: FileTransferProgress)
    fun onTransferCompleted(metadata: FileTransferMetadata, success: Boolean, error: String? = null)
}

class FileTransferException(message: String, cause: Throwable? = null) : Exception(message, cause)
