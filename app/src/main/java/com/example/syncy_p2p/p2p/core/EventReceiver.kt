package com.example.syncy_p2p.p2p.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.*
import android.util.Log
import com.example.syncy_p2p.p2p.core.Event

class EventReceiver(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val listener: WiFiDirectEventListener
) : BroadcastReceiver() {

    companion object {
        private const val TAG = "SyncyP2P"
    }

    interface WiFiDirectEventListener {
        fun onPeersChanged(deviceList: WifiP2pDeviceList)
        fun onConnectionInfoChanged(info: WifiP2pInfo)
        fun onThisDeviceChanged(device: WifiP2pDevice)
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return

        when (action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                Log.d(TAG, "Wi-Fi P2P state changed: $state")
                val isEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                Log.d(TAG, "Wi-Fi P2P enabled: $isEnabled")
            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                Log.d(TAG, "Peers changed")
                manager.requestPeers(channel) { peers ->
                    Log.d(TAG, "Peers list updated: ${peers.deviceList.size} devices")
                    listener.onPeersChanged(peers)
                }
            }

            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                Log.d(TAG, "Connection changed")
                manager.requestConnectionInfo(channel) { info ->
                    Log.d(TAG, "Connection info updated: groupFormed=${info.groupFormed}, isGroupOwner=${info.isGroupOwner}")
                    listener.onConnectionInfoChanged(info)
                }
            }

            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                Log.d(TAG, "This device changed")
                val device = intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                device?.let {
                    Log.d(TAG, "Device info updated: ${it.deviceName}")
                    listener.onThisDeviceChanged(it)
                }
            }

            else -> Log.d(TAG, "Unhandled action: $action")
        }
    }
}
