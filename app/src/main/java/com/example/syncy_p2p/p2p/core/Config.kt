package com.example.syncy_p2p.p2p.core

object Config {
    var notificationTitle: String = "Syncy P2P"
    var sendingMessageText: String = "Sending message..."
    var sendingFileText: String = "Sending file..."
    var channelName: String = "Syncy P2P Messages"
    var fileChannelName: String = "Syncy P2P Files"
    
    const val DEFAULT_PORT = 8988
    const val SOCKET_TIMEOUT = 30000        // Increased to 30 seconds for file transfers
    const val SOCKET_READ_TIMEOUT = 60000   // 60 seconds for reading data during transfer
    const val BUFFER_SIZE = 8192
    const val MAX_RETRIES = 3
    const val RETRY_DELAY_BASE = 500        // Base delay in ms for exponential backoff
}
