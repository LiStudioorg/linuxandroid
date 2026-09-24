package com.li63050a.linuxandroid

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

/**
 * 发行版家族卡片（仅 3 张：Alpine / Debian / Ubuntu）。
 * 点击进入版本选择页。
 */
class DistroAdapter(
    private val items: List<DistroFamily>,
    private val onClick: (DistroFamily) -> Unit
) : RecyclerView.Adapter<DistroAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_distro, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val family = items[position]
        holder.icon.text = family.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        holder.name.text = family.name
        holder.desc.text = holder.itemView.context.getString(
            R.string.family_versions_fmt,
            family.versions.size
        )
        holder.btnAction.text = holder.itemView.context.getString(R.string.btn_pick_version)
        holder.itemView.setOnClickListener { onClick(family) }
        holder.btnAction.setOnClickListener { onClick(family) }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: TextView = view.findViewById(R.id.tv_icon)
        val name: TextView = view.findViewById(R.id.tv_name)
        val desc: TextView = view.findViewById(R.id.tv_desc)
        val btnAction: MaterialButton = view.findViewById(R.id.btn_action)
    }
}
