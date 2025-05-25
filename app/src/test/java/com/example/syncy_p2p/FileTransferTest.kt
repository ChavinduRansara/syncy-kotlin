package com.example.syncy_p2p

import com.example.syncy_p2p.files.FileTransferMetadata
import com.example.syncy_p2p.files.FileTransferProgress
import com.example.syncy_p2p.p2p.core.Utils
import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for file transfer functionality
 */
class FileTransferTest {

    @Test
    fun fileTransferMetadata_creation_isCorrect() {
        val metadata = FileTransferMetadata(
            fileName = "test.txt",
            fileSize = 1024L,
            mimeType = "text/plain",
            checksum = "abcd1234"
        )
        
        assertEquals("test.txt", metadata.fileName)
        assertEquals(1024L, metadata.fileSize)
        assertEquals("text/plain", metadata.mimeType)
        assertEquals("abcd1234", metadata.checksum)
    }

    @Test
    fun fileTransferProgress_calculation_isCorrect() {
        val progress = FileTransferProgress(
            fileName = "test.txt",
            bytesTransferred = 512L,
            totalBytes = 1024L
        )
        
        assertEquals("test.txt", progress.fileName)
        assertEquals(512L, progress.bytesTransferred)
        assertEquals(1024L, progress.totalBytes)
        assertEquals(50, progress.percentage)
    }

    @Test
    fun utils_mimeTypeDetection_worksCorrectly() {
        assertEquals("image/jpeg", Utils.getMimeType("photo.jpg"))
        assertEquals("image/png", Utils.getMimeType("screenshot.png"))
        assertEquals("video/mp4", Utils.getMimeType("movie.mp4"))
        assertEquals("audio/mpeg", Utils.getMimeType("song.mp3"))
        assertEquals("text/plain", Utils.getMimeType("document.txt"))
        assertEquals("application/pdf", Utils.getMimeType("manual.pdf"))
        assertEquals("application/zip", Utils.getMimeType("archive.zip"))
        assertEquals("application/octet-stream", Utils.getMimeType("unknown.xyz"))
    }

    @Test
    fun metadata_serialization_roundTrip_isCorrect() {
        val originalMetadata = FileTransferMetadata(
            fileName = "test_file.pdf",
            fileSize = 2048L,
            mimeType = "application/pdf",
            checksum = "efgh5678"
        )
        
        val json = Utils.serializeMetadata(originalMetadata)
        assertNotNull(json)
        assertFalse(json.isEmpty())
        
        val deserializedMetadata = Utils.deserializeMetadata(json)
        assertNotNull(deserializedMetadata)
        assertEquals(originalMetadata.fileName, deserializedMetadata!!.fileName)
        assertEquals(originalMetadata.fileSize, deserializedMetadata.fileSize)
        assertEquals(originalMetadata.mimeType, deserializedMetadata.mimeType)
        assertEquals(originalMetadata.checksum, deserializedMetadata.checksum)
    }

    @Test
    fun progress_percentage_calculation_edgeCases() {
        // Test 0% progress
        val zeroProgress = FileTransferProgress(
            fileName = "test.txt",
            bytesTransferred = 0L,
            totalBytes = 1000L
        )
        assertEquals(0, zeroProgress.percentage)
        
        // Test 100% progress
        val fullProgress = FileTransferProgress(
            fileName = "test.txt",
            bytesTransferred = 1000L,
            totalBytes = 1000L
        )
        assertEquals(100, fullProgress.percentage)
    }
}
