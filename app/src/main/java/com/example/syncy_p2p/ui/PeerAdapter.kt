package com.example.syncy_p2p.ui

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.syncy_p2p.R
import com.example.syncy_p2p.databinding.ItemPeerBinding
import com.example.syncy_p2p.p2p.core.DeviceInfo

class PeerAdapter(
    private val onConnectClick: (DeviceInfo) -> Unit,
    private val onDisconnectClick: (DeviceInfo) -> Unit
) : ListAdapter<DeviceInfo, PeerAdapter.PeerViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeerViewHolder {
        val binding = ItemPeerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PeerViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PeerViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PeerViewHolder(
        private val binding: ItemPeerBinding
    ) : RecyclerView.ViewHolder(binding.root) {        fun bind(device: DeviceInfo) {
            binding.apply {
                tvDeviceName.text = device.deviceName.ifEmpty { "Unknown Device" }
                tvDeviceAddress.text = device.deviceAddress
                tvStatus.text = device.statusText
                
                // Set status color based on device status
                tvStatus.setTextColor(
                    when (device.statusText) {
                        "Available" -> binding.root.context.getColor(android.R.color.holo_green_dark)
                        "Connected" -> binding.root.context.getColor(android.R.color.holo_blue_dark)
                        "Invited" -> binding.root.context.getColor(android.R.color.holo_orange_dark)
                        else -> binding.root.context.getColor(android.R.color.darker_gray)
                    }
                )
                
                // Configure buttons based on connection status
                when (device.statusText) {
                    "Available" -> {
                        btnConnect.apply {
                            isEnabled = true
                            text = "Connect"
                            visibility = android.view.View.VISIBLE
                        }
                        btnDisconnect.visibility = android.view.View.GONE
                    }
                    "Connected" -> {
                        btnConnect.apply {
                            isEnabled = false
                            text = "Connected"
                            visibility = android.view.View.VISIBLE
                        }
                        btnDisconnect.apply {
                            visibility = android.view.View.VISIBLE
                            isEnabled = true
                        }
                    }
                    "Invited" -> {
                        btnConnect.apply {
                            isEnabled = false
                            text = "Connecting..."
                            visibility = android.view.View.VISIBLE
                        }
                        btnDisconnect.visibility = android.view.View.GONE
                    }
                    else -> {
                        btnConnect.apply {
                            isEnabled = false
                            text = "Unavailable"
                            visibility = android.view.View.VISIBLE
                        }
                        btnDisconnect.visibility = android.view.View.GONE
                    }
                }
                
                // Set click listeners
                btnConnect.setOnClickListener {
                    if (device.statusText == "Available") {
                        onConnectClick(device)
                    }
                }
                
                btnDisconnect.setOnClickListener {
                    // Show confirmation dialog before disconnecting
                    AlertDialog.Builder(binding.root.context)
                        .setTitle("Disconnect")
                        .setMessage("Are you sure you want to disconnect from ${device.deviceName}?")
                        .setPositiveButton("Yes") { _, _ ->
                            onDisconnectClick(device)
                        }
                        .setNegativeButton("No", null)
                        .show()
                }
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<DeviceInfo>() {
        override fun areItemsTheSame(oldItem: DeviceInfo, newItem: DeviceInfo): Boolean {
            return oldItem.deviceAddress == newItem.deviceAddress
        }

        override fun areContentsTheSame(oldItem: DeviceInfo, newItem: DeviceInfo): Boolean {
            return oldItem == newItem
        }
    }
}
