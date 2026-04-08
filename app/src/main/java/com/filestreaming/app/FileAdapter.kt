package com.filestreaming.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Adapter RecyclerView do wyświetlania listy plików/katalogów z serwera.
 */
class FileAdapter(
    private val items: List<FileItem>,
    private val onClick: (FileItem) -> Unit
) : RecyclerView.Adapter<FileAdapter.FileViewHolder>() {

    class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val iconLabel: TextView = view.findViewById(R.id.iconLabel)
        val nameLabel: TextView = view.findViewById(R.id.nameLabel)
        val sizeLabel: TextView = view.findViewById(R.id.sizeLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val item = items[position]

        holder.nameLabel.text = item.name
        holder.sizeLabel.text = item.formattedSize()

        // Ikona
        holder.iconLabel.text = when {
            item.isDirectory -> "📁"
            item.isVideo() -> "🎬"
            item.isMedia() -> "🎵"
            else -> "📄"
        }

        // Kolor tekstu
        val context = holder.itemView.context
        holder.nameLabel.setTextColor(
            if (item.isDirectory) context.getColor(R.color.accent_blue)
            else if (item.isMedia()) context.getColor(R.color.text_primary)
            else context.getColor(R.color.text_secondary)
        )

        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size
}

