package com.li63050a.linuxandroid

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

/**
 * 「我的系统」列表适配器：已安装的 rootfs + 运行状态。
 *
 * 与 [VersionAdapter] 的区别：这里只出现**已安装**的系统，
 * 没有下载进度，操作只有「启动 / 打开终端 / 卸载」。
 * 运行中的那一项按钮变为「打开终端」，在列表里就能看出哪个系统在跑。
 *
 * **多实例（§8.17）**：可以同时有多个实例在跑，因此运行状态是**集合**而不是
 * 单个 id。用 `HashSet` 而不是 List：`setRunningIds` 每次状态回调都会调用，
 * 集合查找是 O(1)，且天然去重。
 */
class MyAppAdapter(
    private val onLaunch: (MyApp) -> Unit,
    private val onOpenTerminal: (MyApp) -> Unit,
    private val onUninstall: (MyApp) -> Unit,
    private val onShowInfo: (MyApp) -> Unit
) : RecyclerView.Adapter<MyAppAdapter.VH>() {

    private var items: List<MyApp> = emptyList()

    /** 当前正在运行的全部实例 id；空集表示没有实例在跑 */
    private var runningIds: Set<String> = emptySet()

    fun submit(newItems: List<MyApp>) {
        items = newItems
        notifyDataSetChanged()
    }

    /**
     * 更新运行中的实例集合。只刷新状态**发生变化**的那几项，
     * 避免每次输出回调都整表重绘（那会让列表滚动位置跳动）。
     */
    fun setRunningIds(ids: Set<String>) {
        if (runningIds == ids) return
        // Kotlin 的 Set.symmetricDifference 是 1.9+ 的实验性 API，此处按语义手写：
        // 旧有新无（变为未运行）∪ 新有旧无（变为运行中），即状态真正变化的那些项。
        val changed = (runningIds - ids) + (ids - runningIds)
        runningIds = ids
        for (target in changed) {
            val index = items.indexOfFirst { it.id == target }
            if (index >= 0) notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_myapp, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = items[position]
        val ctx = holder.itemView.context
        val isRunning = app.id in runningIds

        holder.icon.text = app.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        holder.name.text = app.name
        holder.badge.visibility = if (isRunning) View.VISIBLE else View.GONE
        holder.badge.text = ctx.getString(R.string.myapps_running)

        holder.meta.text = buildString {
            append(app.shell)
            // 只有「清单读到了、但确实没这个版本」才叫自定义；
            // 清单没加载成功时不能这么写，否则会把自己装的系统说成用户手工放入的
            if (!app.fromManifest) {
                append(
                    if (app.manifestLoaded) ctx.getString(R.string.myapps_custom_suffix)
                    else ctx.getString(R.string.myapps_unknown_suffix)
                )
            }
            val mb = app.sizeBytes / (1024.0 * 1024.0)
            if (mb >= 0.1) append(" · %.1f MB".format(mb))
            if (isRunning) {
                append(" · ")
                append(ctx.getString(R.string.state_installed))
            }
        }

        holder.btnAction.text =
            ctx.getString(if (isRunning) R.string.btn_open_terminal else R.string.btn_start)
        holder.btnAction.setOnClickListener {
            if (isRunning) onOpenTerminal(app) else onLaunch(app)
        }
        holder.btnUninstall.setOnClickListener { onUninstall(app) }
        holder.itemView.setOnClickListener { onShowInfo(app) }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: TextView = view.findViewById(R.id.tv_icon)
        val name: TextView = view.findViewById(R.id.tv_name)
        val badge: TextView = view.findViewById(R.id.tv_badge)
        val meta: TextView = view.findViewById(R.id.tv_meta)
        val btnAction: MaterialButton = view.findViewById(R.id.btn_action)
        val btnUninstall: MaterialButton = view.findViewById(R.id.btn_uninstall)
    }
}
