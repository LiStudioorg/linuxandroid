package com.li63050a.linuxandroid

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

/**
 * 发行版卡片列表适配器。
 * 卡片：左侧圆形字母图标 + 名称/描述 + 右侧操作按钮（下载/启动），
 * 下载中隐藏按钮、展示进度条与百分比。
 */
class DistroAdapter(
    private val items: List<DistroInfo>,
    private val onClick: (DistroInfo) -> Unit
) : RecyclerView.Adapter<DistroAdapter.VH>() {

    sealed class State {
        data object Idle : State()
        data class Downloading(val percent: Int) : State()
        data object Installed : State()
    }

    private val states = HashMap<String, State>()

    /** 外部（MainActivity）驱动卡片状态刷新 */
    fun setState(id: String, state: State) {
        states[id] = state
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_distro, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val distro = items[position]
        val state = states[distro.id] ?: State.Idle

        // 圆形字母图标
        holder.icon.text = distro.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ICON_COLORS[position % ICON_COLORS.size])
        }
        holder.icon.background = bg

        holder.name.text = distro.name
        val size = humanSize(distro.size)
        holder.desc.text =
            if (size.isEmpty()) distro.description
            else "${distro.description} · $size"

        when (state) {
            State.Idle -> {
                holder.btnAction.visibility = View.VISIBLE
                holder.btnAction.text = holder.itemView.context.getString(R.string.btn_download)
                holder.btnAction.isEnabled = true
                holder.progressGroup.visibility = View.GONE
            }
            is State.Downloading -> {
                holder.btnAction.visibility = View.GONE
                holder.progressGroup.visibility = View.VISIBLE
                holder.progressBar.progress = state.percent.coerceIn(0, 100)
                holder.percent.text = "${state.percent.coerceIn(0, 100)}%"
            }
            State.Installed -> {
                holder.btnAction.visibility = View.VISIBLE
                holder.btnAction.text = holder.itemView.context.getString(R.string.btn_start)
                holder.btnAction.isEnabled = true
                holder.progressGroup.visibility = View.GONE
            }
        }

        holder.itemView.setOnClickListener { onClick(distro) }
        holder.btnAction.setOnClickListener { onClick(distro) }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: TextView = view.findViewById(R.id.tv_icon)
        val name: TextView = view.findViewById(R.id.tv_name)
        val desc: TextView = view.findViewById(R.id.tv_desc)
        val progressGroup: View = view.findViewById(R.id.progress_group)
        val progressBar: ProgressBar = view.findViewById(R.id.progress_bar)
        val percent: TextView = view.findViewById(R.id.tv_percent)
        val btnAction: MaterialButton = view.findViewById(R.id.btn_action)
    }

    private fun humanSize(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000L -> "%.1f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }

    companion object {
        private val ICON_COLORS = intArrayOf(
            0xFFADD8E6.toInt(), // 浅蓝
            0xFFFFB6C1.toInt(), // 粉
            0xFFF8BBD0.toInt(), // 玫粉
            0xFFB5EAD7.toInt(), // 薄荷
            0xFFFFE0B2.toInt()  // 杏
        )
    }
}
