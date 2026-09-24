package com.li63050a.linuxandroid

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

/**
 * 版本列表适配器。
 * 每项：版本名 + 大小 + 操作区（下载 / 进度条 / 启动+卸载）。
 * 进度用 payload 局部刷新，避免整行重绘闪烁。
 */
class VersionAdapter(
    private val items: List<DistroInfo>,
    private val onDownload: (DistroInfo) -> Unit,
    private val onStart: (DistroInfo) -> Unit,
    private val onUninstall: (DistroInfo) -> Unit
) : RecyclerView.Adapter<VersionAdapter.VH>() {

    sealed class State {
        data object Idle : State()
        data class Downloading(val percent: Int) : State()
        data object Installed : State()
    }

    private val states = HashMap<String, State>()

    fun setState(id: String, state: State, payload: String? = null) {
        val prev = states[id]
        states[id] = state
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return
        if (payload == PAYLOAD_PROGRESS && prev is State.Downloading && state is State.Downloading) {
            if (prev.percent == state.percent) return
            notifyItemChanged(index, payload)
        } else {
            notifyItemChanged(index)
        }
    }

    fun stateOf(id: String): State = states[id] ?: State.Idle

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_version, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val v = items[position]
        val state = states[v.id] ?: State.Idle

        holder.name.text = v.name
        holder.icon.text = v.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        holder.meta.text = buildString {
            append(humanSize(v.size))
            if (v.size > 0) append(" · ")
            append(v.format)
        }

        when (state) {
            State.Idle -> {
                holder.progressGroup.visibility = View.GONE
                holder.btnAction.visibility = View.VISIBLE
                holder.btnAction.isEnabled = true
                holder.btnAction.text =
                    holder.itemView.context.getString(R.string.btn_download)
                holder.btnUninstall.visibility = View.GONE
            }
            is State.Downloading -> {
                holder.progressGroup.visibility = View.VISIBLE
                holder.btnAction.visibility = View.GONE
                holder.btnUninstall.visibility = View.GONE
                val pct = state.percent.coerceIn(0, 100)
                holder.progressBar.progress = pct
                holder.percent.text = "$pct%"
            }
            State.Installed -> {
                holder.progressGroup.visibility = View.GONE
                holder.btnAction.visibility = View.VISIBLE
                holder.btnAction.isEnabled = true
                holder.btnAction.text =
                    holder.itemView.context.getString(R.string.btn_start)
                holder.btnUninstall.visibility = View.VISIBLE
            }
        }

        holder.btnAction.setOnClickListener {
            if ((states[v.id] ?: State.Idle) is State.Installed) onStart(v) else onDownload(v)
        }
        holder.btnUninstall.setOnClickListener { onUninstall(v) }
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }
        val state = states[items[position].id]
        if (state is State.Downloading) {
            val pct = state.percent.coerceIn(0, 100)
            holder.progressBar.progress = pct
            holder.percent.text = "$pct%"
        } else {
            onBindViewHolder(holder, position)
        }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: TextView = view.findViewById(R.id.tv_icon)
        val name: TextView = view.findViewById(R.id.tv_name)
        val meta: TextView = view.findViewById(R.id.tv_meta)
        val progressGroup: View = view.findViewById(R.id.progress_group)
        val progressBar: ProgressBar = view.findViewById(R.id.progress_bar)
        val percent: TextView = view.findViewById(R.id.tv_percent)
        val btnAction: MaterialButton = view.findViewById(R.id.btn_action)
        val btnUninstall: MaterialButton = view.findViewById(R.id.btn_uninstall)
    }

    private fun humanSize(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000L -> "%.1f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }

    companion object {
        const val PAYLOAD_PROGRESS = "payload_progress"
    }
}
