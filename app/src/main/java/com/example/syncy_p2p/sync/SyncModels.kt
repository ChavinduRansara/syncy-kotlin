package com.example.syncy_p2p.sync

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.io.Serializable

/**
 * Represents a sync request sent from one device to another
 */
@Parcelize
data class SyncRequest(
    val requestId: String,
    val sourceDeviceId: String,
    val sourceDeviceName: String,
    val folderName: String,
    val folderPath: String,
    val totalFiles: Int,
    val totalSize: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val status: SyncRequestStatus = SyncRequestStatus.PENDING
) : Parcelable

/**
 * Status of a sync request
 */
enum class SyncRequestStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Represents a synchronized folder on a device
 */
@Parcelize
data class SyncedFolder(
    val id: String,
    val name: String,
    val localPath: String,
    val localUri: Uri?,
    val remoteDeviceId: String,
    val remoteDeviceName: String,
    val remotePath: String,
    val lastSyncTime: Long,
    val status: SyncStatus,
    val autoSync: Boolean = true,
    val conflictResolution: ConflictResolution = ConflictResolution.ASK_USER
) : Parcelable

/**
 * Status of a synced folder
 */
enum class SyncStatus {
    SYNCED,
    PENDING_SYNC,
    SYNCING,
    CONFLICT,
    ERROR,
    DISCONNECTED
}

/**
 * How to handle file conflicts during sync
 */
enum class ConflictResolution {
    KEEP_BOTH,
    KEEP_NEWER,
    KEEP_LARGER,
    OVERWRITE_LOCAL,
    OVERWRITE_REMOTE,
    ASK_USER
}

/**
 * Represents a file comparison result
 */
data class FileComparison(
    val fileName: String,
    val localFile: FileMetadata?,
    val remoteFile: FileMetadata?,
    val action: SyncAction,
    val conflict: ConflictType = ConflictType.NONE
)

/**
 * Metadata for a file in sync comparison
 */
@Parcelize
data class FileMetadata(
    val name: String,
    val path: String,
    val size: Long,
    val lastModified: Long,
    val checksum: String?,
    val isDirectory: Boolean = false
) : Parcelable

/**
 * What action should be taken for a file during sync
 */
enum class SyncAction {
    NONE,           // File is identical
    UPLOAD,         // Send to remote
    DOWNLOAD,       // Receive from remote
    CONFLICT,       // Conflict needs resolution
    DELETE_LOCAL,   // Delete local file
    DELETE_REMOTE   // Delete remote file
}

/**
 * Type of conflict detected
 */
enum class ConflictType {
    NONE,
    BOTH_MODIFIED,
    SIZE_MISMATCH,
    TYPE_MISMATCH   // One is file, other is directory
}

/**
 * Progress information for sync operations
 */
data class SyncProgress(
    val folderName: String,
    val currentFile: String?,
    val filesProcessed: Int,
    val totalFiles: Int,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val percentage: Int = if (totalFiles > 0) ((filesProcessed * 100) / totalFiles) else 0,
    val status: String = "",
    val error: String? = null
)

/**
 * Log entry for sync operations
 */
@Parcelize
data class SyncLogEntry(
    val id: String,
    val folderName: String,
    val timestamp: Long,
    val action: String,
    val fileName: String?,
    val status: SyncLogStatus,
    val message: String,
    val deviceName: String
) : Parcelable

/**
 * Represents a file conflict that needs user resolution
 */
@Parcelize
data class FileConflict(
    val fileName: String,
    val filePath: String,
    val localFileSize: Long,
    val localLastModified: Long,
    val remoteFileSize: Long,
    val remoteLastModified: Long,
    val conflictType: ConflictType
) : Parcelable

/**
 * Status of a sync log entry
 */
enum class SyncLogStatus {
    INFO,
    SUCCESS,
    WARNING,
    ERROR
}

/**
 * Callback interface for sync operations
 */
interface SyncCallback {
    fun onSyncRequestReceived(request: SyncRequest)
    fun onSyncStarted(folderId: String)
    fun onSyncProgress(folderId: String, progress: SyncProgress)
    fun onSyncCompleted(folderId: String, success: Boolean, error: String? = null)
    fun onSyncConflict(folderId: String, conflicts: List<FileComparison>)
    fun onSyncLogUpdate(entry: SyncLogEntry)
}
