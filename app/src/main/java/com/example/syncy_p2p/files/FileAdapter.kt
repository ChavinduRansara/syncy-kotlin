package com.example.syncy_p2p.files

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.syncy_p2p.R
import com.example.syncy_p2p.databinding.ItemFileBinding
import java.text.SimpleDateFormat
import java.util.*

class FileAdapter(
    private val onFileClick: (FileItem) -> Unit,
    private val onSendClick: (FileItem) -> Unit
) : ListAdapter<FileItem, FileAdapter.FileViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val binding = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class FileViewHolder(
        private val binding: ItemFileBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(fileItem: FileItem) {
            binding.apply {
                tvFileName.text = fileItem.displayName
                tvFileSize.text = fileItem.sizeString
                
                // Format last modified date
                val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                tvLastModified.text = dateFormat.format(Date(fileItem.lastModified))
                
                // Set appropriate icon based on file type
                ivFileIcon.setImageResource(getFileIcon(fileItem))
                
                // Show/hide send button based on file type
                btnSendFile.visibility = if (fileItem.isDirectory) {
                    android.view.View.GONE
                } else {
                    android.view.View.VISIBLE
                }
                
                // Set click listeners
                root.setOnClickListener {
                    onFileClick(fileItem)
                }
                
                btnSendFile.setOnClickListener {
                    if (!fileItem.isDirectory) {
                        onSendClick(fileItem)
                    }
                }
            }
        }
        
        private fun getFileIcon(fileItem: FileItem): Int {
            return when {
                fileItem.isDirectory -> R.drawable.ic_folder
                fileItem.mimeType?.startsWith("image/") == true -> R.drawable.ic_image
                fileItem.mimeType?.startsWith("video/") == true -> R.drawable.ic_video
                fileItem.mimeType?.startsWith("audio/") == true -> R.drawable.ic_audio
                fileItem.mimeType?.startsWith("text/") == true -> R.drawable.ic_text
                fileItem.mimeType == "application/pdf" -> R.drawable.ic_pdf
                fileItem.mimeType?.contains("document") == true -> R.drawable.ic_document
                fileItem.mimeType?.contains("spreadsheet") == true -> R.drawable.ic_spreadsheet
                fileItem.mimeType?.contains("presentation") == true -> R.drawable.ic_presentation
                fileItem.mimeType?.contains("zip") == true || 
                fileItem.mimeType?.contains("archive") == true -> R.drawable.ic_archive
                else -> R.drawable.ic_file
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<FileItem>() {
        override fun areItemsTheSame(oldItem: FileItem, newItem: FileItem): Boolean {
            return oldItem.uri == newItem.uri
        }

        override fun areContentsTheSame(oldItem: FileItem, newItem: FileItem): Boolean {
            return oldItem == newItem
        }
    }
}
