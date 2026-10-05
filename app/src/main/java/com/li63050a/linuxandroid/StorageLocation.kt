package com.li63050a.linuxandroid

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * rootfs 的物理存放位置。两个选项都是**应用私有沙箱**，因此两者都可以
 * 在不申请任何存储权限的前提下读写（AGENTS.md §8.2 依然成立）。
 *
 * - [INTERNAL]：`/data/data/<pkg>`（即 `context.filesDir`，设备上通常就是
 *   `/data/user/0/<pkg>`）。内置存储，速度快，卸载即清除。
 * - [EXTERNAL]：`/storage/emulated/0/Android/data/<pkg>`（即
 *   `context.getExternalFilesDir(null)`）。属于「外置共享存储」分区，
 *   容量通常更大；Android 4.4 起应用对该目录**无需任何权限**即可读写，
 *   且 Android 11+ 的分区存储依然保留此豁免。
 *
 * 两个位置的目录布局完全一致（见 [RootfsManager]），因此切换存储位置
 * 只是换一个根目录；proot 的所有参数都是运行期拼出的绝对路径，
 * 不依赖任何编译期固定值。
 *
 * ⚠️ 已知限制：外部位置仍属于应用私有沙箱，**卸载应用时会被系统一并清除**，
 * 因此它解决的是「容量」而不是「持久性」。若要让 rootfs 在卸载后仍然保留，
 * 必须改用共享存储的「所有文件访问」或文档选择器，两者都会违反 AGENTS.md
 * §8.2（不申请存储权限、不使用 SAF），故本轮不做——这是有意识的取舍。
 */
enum class StorageLocation(val key: String) {
    INTERNAL("internal"),
    EXTERNAL("external");

    /**
     * 该位置在当前设备上是否可用。
     * @param sandboxDir 候选沙箱根目录；null 表示系统 API 已明确告知不可用
     */
    fun isAvailable(sandboxDir: File?): Boolean = when (this) {
        // 内置存储永远可用（filesDir 一定存在且可写）
        INTERNAL -> true
        // 外部位置需要：外置分区已挂载 + 系统确实提供了该应用的外部目录。
        // 后者在存储未挂载、或极少数不提供模拟外置分区的 ROM 上为 null。
        EXTERNAL -> sandboxDir != null &&
            Environment.getExternalStorageState(sandboxDir) == Environment.MEDIA_MOUNTED
    }

    /** 该位置下 rootfs 的根目录 */
    fun rootfsRoot(sandboxDir: File): File = File(sandboxDir, DIR_ROOTFS)

    companion object {
        /** 两种位置共用同一套布局 */
        const val DIR_ROOTFS = "rootfs"

        /** 从持久化的字符串还原；未知值退回 INTERNAL */
        fun fromKey(key: String?): StorageLocation =
            entries.firstOrNull { it.key == key } ?: INTERNAL

        /**
         * 解析某位置的沙箱根目录。
         *
         * 外部位置显式调用 `getExternalFilesDir(null)`：它在存储未挂载时返回 null，
         * 比用 `filesDir.parentFile` 猜测更可靠（后者无论挂不挂载都返回同一路径）。
         */
        fun sandboxDir(context: Context, location: StorageLocation): File? =
            when (location) {
                INTERNAL -> context.filesDir
                EXTERNAL -> context.getExternalFilesDir(null)
            }
    }
}
