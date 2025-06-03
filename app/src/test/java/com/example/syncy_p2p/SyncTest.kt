package com.example.syncy_p2p

import com.example.syncy_p2p.p2p.core.Utils
import com.example.syncy_p2p.sync.SyncRequest
import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for sync functionality
 */
class SyncTest {
    
    @Test
    fun extractJsonValue_works() {
        val json = """{"requestId": "test123", "folderName": "Test Folder", "totalFiles": 42}"""
        
        assertEquals("test123", Utils.extractJsonValue(json, "requestId"))
        assertEquals("Test Folder", Utils.extractJsonValue(json, "folderName"))
        assertEquals("42", Utils.extractJsonValue(json, "totalFiles"))
        assertNull(Utils.extractJsonValue(json, "nonExistent"))
    }
      @Test
    fun extractJsonValue_handles_escaped_strings() {
        val json = """{"folderName": "Test Folder with Spaces", "path": "/test/path"}"""
        
        assertEquals("Test Folder with Spaces", Utils.extractJsonValue(json, "folderName"))
        assertEquals("/test/path", Utils.extractJsonValue(json, "path"))
    }
    
    @Test
    fun extractJsonValue_handles_numbers() {
        val json = """{"totalFiles": 123, "totalSize": 456789, "timestamp": 1234567890}"""
        
        assertEquals("123", Utils.extractJsonValue(json, "totalFiles"))
        assertEquals("456789", Utils.extractJsonValue(json, "totalSize"))
        assertEquals("1234567890", Utils.extractJsonValue(json, "timestamp"))
    }
    
    @Test
    fun sync_request_serialization_format() {
        // Test that our expected JSON format matches what we can parse
        val expectedJson = """
        {
            "requestId": "test-id-123",
            "sourceDeviceId": "device-456",
            "sourceDeviceName": "Test Device",
            "folderName": "My Documents",
            "folderPath": "/storage/documents",
            "totalFiles": 25,
            "totalSize": 1048576,
            "timestamp": 1609459200000
        }
        """.trimIndent()
        
        // Verify we can extract all required fields
        assertEquals("test-id-123", Utils.extractJsonValue(expectedJson, "requestId"))
        assertEquals("device-456", Utils.extractJsonValue(expectedJson, "sourceDeviceId"))
        assertEquals("Test Device", Utils.extractJsonValue(expectedJson, "sourceDeviceName"))
        assertEquals("My Documents", Utils.extractJsonValue(expectedJson, "folderName"))
        assertEquals("/storage/documents", Utils.extractJsonValue(expectedJson, "folderPath"))
        assertEquals("25", Utils.extractJsonValue(expectedJson, "totalFiles"))
        assertEquals("1048576", Utils.extractJsonValue(expectedJson, "totalSize"))
        assertEquals("1609459200000", Utils.extractJsonValue(expectedJson, "timestamp"))
    }
    
    @Test
    fun sync_progress_serialization_format() {
        // Test sync progress JSON format
        val progressJson = """
        {
            "folderName": "My Documents",
            "currentFile": "document.pdf",
            "filesProcessed": 5,
            "totalFiles": 25,
            "bytesTransferred": 524288,
            "totalBytes": 1048576,
            "status": "Transferring files"
        }
        """.trimIndent()
        
        // Verify we can extract all progress fields
        assertEquals("My Documents", Utils.extractJsonValue(progressJson, "folderName"))
        assertEquals("document.pdf", Utils.extractJsonValue(progressJson, "currentFile"))
        assertEquals("5", Utils.extractJsonValue(progressJson, "filesProcessed"))
        assertEquals("25", Utils.extractJsonValue(progressJson, "totalFiles"))
        assertEquals("524288", Utils.extractJsonValue(progressJson, "bytesTransferred"))
        assertEquals("1048576", Utils.extractJsonValue(progressJson, "totalBytes"))
        assertEquals("Transferring files", Utils.extractJsonValue(progressJson, "status"))
    }
}
