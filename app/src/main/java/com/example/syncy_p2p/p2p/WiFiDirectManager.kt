package com.example.syncy_p2p.p2p

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.*
import android.util.Log
import com.example.syncy_p2p.p2p.core.*
import com.example.syncy_p2p.p2p.receiver.FileReceiver
import com.example.syncy_p2p.p2p.receiver.MessageReceiver
import com.example.syncy_p2p.p2p.sender.FileSender
import com.example.syncy_p2p.p2p.sender.MessageSender
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class WiFiDirectManager(private val context: Context) : EventReceiver.WiFiDirectEventListener {

    companion object {
        private const val TAG = "SyncyP2P"
    }

    interface WiFiDirectCallback {
        fun onMessageReceived(message: String, senderAddress: String)
        fun onFileReceived(filePath: String, senderAddress: String)
        fun onError(error: String)
        fun onStatusChanged(status: String)
    }

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var eventReceiver: EventReceiver? = null
    private var messageReceiver: MessageReceiver? = null
    private var callback: WiFiDirectCallback? = null

    // State flows for UI observation
    private val _peers = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val peers: StateFlow<List<DeviceInfo>> = _peers.asStateFlow()
    
    private val _connectionInfo = MutableStateFlow<ConnectionInfo?>(null)
    val connectionInfo: StateFlow<ConnectionInfo?> = _connectionInfo.asStateFlow()

    private val _thisDevice = MutableStateFlow<DeviceInfo?>(null)
    val thisDevice: StateFlow<DeviceInfo?> = _thisDevice.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    // Track connected peer addresses for proper routing
    private val _connectedPeers = MutableStateFlow<Set<String>>(emptySet())
    val connectedPeers: StateFlow<Set<String>> = _connectedPeers.asStateFlow()

    fun setCallback(callback: WiFiDirectCallback) {
        this.callback = callback
    }

    @SuppressLint("MissingPermission")
    fun initialize(): Result<Unit> {
        return try {
            if (manager != null) {
                return Result.failure(Exception("Already initialized"))
            }

            manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
                ?: return Result.failure(Exception("Wi-Fi P2P not supported"))

            channel = manager?.initialize(context, context.mainLooper, null)
                ?: return Result.failure(Exception("Failed to initialize channel"))

            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            }

            eventReceiver = EventReceiver(manager!!, channel!!, this)
            context.registerReceiver(eventReceiver, filter)

            _isInitialized.value = true
            callback?.onStatusChanged("Wi-Fi Direct initialized")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize", e)
            Result.failure(e)        }
    }

    fun cleanup() {
        try {
            stopReceivingMessages()
            eventReceiver?.let { context.unregisterReceiver(it) }
            eventReceiver = null
            channel = null
            manager = null
            _isInitialized.value = false
            _peers.value = emptyList()
            _connectionInfo.value = null
            _thisDevice.value = null
            _connectedPeers.value = emptySet() // Clear connected peers
        } catch (e: Exception) {
            Log.w(TAG, "Error during cleanup", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun discoverPeers(): Result<Unit> {
        return try {
            manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Peer discovery started")
                    callback?.onStatusChanged("Discovering peers...")
                }

                override fun onFailure(reason: Int) {
                    val error = "Peer discovery failed: ${Utils.getErrorMessage(reason)}"
                    Log.e(TAG, error)
                    callback?.onError(error)
                }
            })
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start peer discovery", e)
            Result.failure(e)
        } ?: Result.failure(Exception("Manager not initialized"))
    }

    @SuppressLint("MissingPermission")
    fun stopPeerDiscovery(): Result<Unit> {
        return try {
            manager?.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Peer discovery stopped")
                    callback?.onStatusChanged("Peer discovery stopped")
                }

                override fun onFailure(reason: Int) {
                    val error = "Failed to stop peer discovery: ${Utils.getErrorMessage(reason)}"
                    Log.e(TAG, error)
                    callback?.onError(error)
                }
            })
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } ?: Result.failure(Exception("Manager not initialized"))
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(deviceAddress: String, groupOwnerIntent: Int = -1): Result<Unit> {
        return try {
            val config = WifiP2pConfig().apply {
                this.deviceAddress = deviceAddress
                wps.setup = WpsInfo.PBC
                if (groupOwnerIntent >= 0) {
                    this.groupOwnerIntent = groupOwnerIntent
                }
            }

            manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Connection initiated to $deviceAddress")
                    callback?.onStatusChanged("Connecting to device...")
                }

                override fun onFailure(reason: Int) {
                    val error = "Connection failed: ${Utils.getErrorMessage(reason)}"
                    Log.e(TAG, error)
                    callback?.onError(error)
                }
            })
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } ?: Result.failure(Exception("Manager not initialized"))
    }

    @SuppressLint("MissingPermission")
    fun disconnect(): Result<Unit> {
        return try {
            manager?.cancelConnect(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Disconnection initiated")
                    callback?.onStatusChanged("Disconnected")
                }

                override fun onFailure(reason: Int) {
                    val error = "Disconnection failed: ${Utils.getErrorMessage(reason)}"
                    Log.e(TAG, error)
                    callback?.onError(error)
                }
            })
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } ?: Result.failure(Exception("Manager not initialized"))
    }

    @SuppressLint("MissingPermission")
    fun createGroup(): Result<Unit> {
        return try {
            manager?.createGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Group creation initiated")
                    callback?.onStatusChanged("Creating group...")
                }

                override fun onFailure(reason: Int) {
                    val error = "Group creation failed: ${Utils.getErrorMessage(reason)}"
                    Log.e(TAG, error)
                    callback?.onError(error)
                }
            })
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } ?: Result.failure(Exception("Manager not initialized"))
    }

    @SuppressLint("MissingPermission")
    fun removeGroup(): Result<Unit> {
        return try {
            manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Group removal initiated")
                    callback?.onStatusChanged("Group removed")
                }

                override fun onFailure(reason: Int) {
                    val error = "Group removal failed: ${Utils.getErrorMessage(reason)}"
                    Log.e(TAG, error)
                    callback?.onError(error)
                }
            })
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } ?: Result.failure(Exception("Manager not initialized"))
    }    fun sendMessage(message: String, targetAddress: String? = null): Result<Unit> {
        val address = targetAddress ?: getTargetAddress()
        if (address == null) {
            return Result.failure(Exception("No peer connected to send message to"))
        }

        Log.d(TAG, "Sending message to: $address")
        return try {
            val intent = Intent(context, MessageSender::class.java).apply {
                action = MessageSender.ACTION_SEND_MESSAGE
                putExtra(MessageSender.EXTRAS_DATA, message)
                putExtra(MessageSender.EXTRAS_ADDRESS, address)
                putExtra(MessageSender.EXTRAS_PORT, Config.DEFAULT_PORT)
            }

            context.startForegroundService(intent)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
            Result.failure(e)
        }
    }    fun sendFile(filePath: String, targetAddress: String? = null): Result<Unit> {
        val address = targetAddress ?: getTargetAddress()
        if (address == null) {
            return Result.failure(Exception("No peer connected to send file to"))
        }

        Log.d(TAG, "Sending file to: $address")
        return try {
            val intent = Intent(context, FileSender::class.java).apply {
                action = FileSender.ACTION_SEND_FILE
                putExtra(FileSender.EXTRAS_FILE_PATH, filePath)
                putExtra(FileSender.EXTRAS_ADDRESS, address)
                putExtra(FileSender.EXTRAS_PORT, Config.DEFAULT_PORT)
            }

            context.startForegroundService(intent)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send file", e)
            Result.failure(e)
        }
    }

    fun startReceivingMessages(): Result<Unit> {
        return try {            if (messageReceiver?.isRunning == true) {
                return Result.success(Unit)
            }

            messageReceiver = MessageReceiver { message, senderAddress ->
                // Track the peer address when receiving messages
                addConnectedPeer(senderAddress)
                callback?.onMessageReceived(message, senderAddress)
            }
            messageReceiver?.start()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start message receiver", e)
            Result.failure(e)
        }
    }

    fun stopReceivingMessages() {
        messageReceiver?.stop()
        messageReceiver = null
    }    fun receiveFile(destinationDir: String, fileName: String): Result<Unit> {        return try {
            val fileReceiver = FileReceiver(
                context = context,
                destinationPath = destinationDir,
                onComplete = { filePath, metadata, senderAddress ->
                    // Track the peer address when receiving files
                    addConnectedPeer(senderAddress)
                    callback?.onFileReceived(filePath, senderAddress)
                },
                onError = { error ->
                    callback?.onError("File receive error: $error")
                },
                onProgress = { progress ->
                    // Progress callback can be added here if needed
                    Log.d(TAG, "File transfer progress: ${progress.percentage}%")
                }
            )
            fileReceiver.start()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start file receiver", e)
            Result.failure(e)
        }
    }    /**
     * Determines the correct target address for sending data based on device role
     * - If this device is Group Owner: send to client's address (need to track from connections)
     * - If this device is Client: send to Group Owner's address
     */
    private fun getTargetAddress(): String? {
        val connectionInfo = _connectionInfo.value ?: return null
        val connectedPeerAddresses = _connectedPeers.value
        
        Log.d(TAG, "Determining target address:")
        Log.d(TAG, "  - Is Group Owner: ${connectionInfo.isGroupOwner}")
        Log.d(TAG, "  - Group Owner Address: ${connectionInfo.groupOwnerAddress}")
        Log.d(TAG, "  - Connected Peers: $connectedPeerAddresses")
        
        return if (connectionInfo.isGroupOwner) {
            // This device is Group Owner - send to connected client
            val targetAddress = connectedPeerAddresses.firstOrNull()
            Log.d(TAG, "  - Group Owner sending to client: $targetAddress")
            targetAddress
        } else {
            // This device is Client - send to Group Owner
            val targetAddress = connectionInfo.groupOwnerAddress
            Log.d(TAG, "  - Client sending to Group Owner: $targetAddress")
            targetAddress
        }
    }

    /**
     * Add a peer address when connection is established
     */
    fun addConnectedPeer(address: String) {
        _connectedPeers.value = _connectedPeers.value + address
        Log.d(TAG, "Added connected peer: $address")
    }

    /**
     * Remove a peer address when connection is lost
     */
    fun removeConnectedPeer(address: String) {
        _connectedPeers.value = _connectedPeers.value - address
        Log.d(TAG, "Removed connected peer: $address")
    }

    // EventReceiver.WiFiDirectEventListener implementation
    override fun onPeersChanged(deviceList: WifiP2pDeviceList) {
        val devices = deviceList.deviceList.map { DeviceInfo.fromWifiP2pDevice(it) }
        _peers.value = devices
        Log.d(TAG, "Peers updated: ${devices.size} devices")
    }

    override fun onConnectionInfoChanged(info: WifiP2pInfo) {
        val connectionInfo = ConnectionInfo.fromWifiP2pInfo(info)
        _connectionInfo.value = connectionInfo
        Log.d(TAG, "Connection info updated: $connectionInfo")
        
        if (info.groupFormed) {
            callback?.onStatusChanged("Connected to group")
            startReceivingMessages()
        } else {
            callback?.onStatusChanged("Disconnected from group")
            stopReceivingMessages()
        }
    }

    override fun onThisDeviceChanged(device: WifiP2pDevice) {
        val deviceInfo = DeviceInfo.fromWifiP2pDevice(device)
        _thisDevice.value = deviceInfo
        Log.d(TAG, "This device updated: $deviceInfo")
    }
}
