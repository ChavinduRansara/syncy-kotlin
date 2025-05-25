package com.example.syncy_p2p.p2p.core

import android.net.wifi.p2p.*

data class DeviceInfo(
    val deviceName: String,
    val deviceAddress: String,
    val primaryDeviceType: String,
    val secondaryDeviceType: String,
    val isGroupOwner: Boolean,
    val status: Int,
    val statusText: String
) {
    companion object {
        fun fromWifiP2pDevice(device: WifiP2pDevice): DeviceInfo {
            return DeviceInfo(
                deviceName = device.deviceName ?: "Unknown Device",
                deviceAddress = device.deviceAddress,
                primaryDeviceType = device.primaryDeviceType ?: "",
                secondaryDeviceType = device.secondaryDeviceType ?: "",
                isGroupOwner = device.isGroupOwner,
                status = device.status,
                statusText = getStatusText(device.status)
            )
        }

        private fun getStatusText(status: Int): String {
            return when (status) {
                WifiP2pDevice.AVAILABLE -> "Available"
                WifiP2pDevice.INVITED -> "Invited"
                WifiP2pDevice.CONNECTED -> "Connected"
                WifiP2pDevice.FAILED -> "Failed"
                WifiP2pDevice.UNAVAILABLE -> "Unavailable"
                else -> "Unknown"
            }
        }
    }
}

data class ConnectionInfo(
    val groupOwnerAddress: String?,
    val groupFormed: Boolean,
    val isGroupOwner: Boolean
) {
    companion object {
        fun fromWifiP2pInfo(info: WifiP2pInfo): ConnectionInfo {
            return ConnectionInfo(
                groupOwnerAddress = info.groupOwnerAddress?.hostAddress,
                groupFormed = info.groupFormed,
                isGroupOwner = info.isGroupOwner
            )
        }
    }
}

data class GroupInfo(
    val `interface`: String?,
    val networkName: String?,
    val passphrase: String?,
    val clientList: List<DeviceInfo>,
    val owner: DeviceInfo?
) {
    companion object {
        fun fromWifiP2pGroup(group: WifiP2pGroup): GroupInfo {
            return GroupInfo(
                `interface` = group.`interface`,
                networkName = group.networkName,
                passphrase = group.passphrase,
                clientList = group.clientList.map { DeviceInfo.fromWifiP2pDevice(it) },
                owner = group.owner?.let { DeviceInfo.fromWifiP2pDevice(it) }
            )
        }
    }
}
