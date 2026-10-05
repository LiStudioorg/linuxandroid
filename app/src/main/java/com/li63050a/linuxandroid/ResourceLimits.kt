package com.li63050a.linuxandroid

import org.json.JSONObject

/**
 * 单个实例的资源限制（ulimit 语义）。
 *
 * 为什么是 ulimit 而不是 cgroup：非 root 的 Android 应用无法写入 cgroup v2 的
 * `cpu.max` / `memory.max`（属 root 与 system_server 管辖），所以「硬限额」在免 root
 * 前提下拿不到。`ulimit` 是进程自身就能设置的资源上限，覆盖了实际最有价值的几项。
 *
 * **取值约定：0 = 不限制**，此时不会生成任何 `ulimit` 语句（见 [toGuestCommand]）。
 *
 * @property memoryMb    虚拟内存上限（MB）。对应 `ulimit -v`（KB 单位）。
 *                       注意限制的是**地址空间**，不是 RSS；动态链接器在地址空间被
 *                       压得过低时会直接失败，故有效下限约 64MB。
 * @property maxProcesses 最大进程/线程数，对应 `ulimit -u`。
 * @property maxOpenFiles 最大文件描述符数，对应 `ulimit -n`。
 * @property cpuSeconds   **CPU 时间**上限（秒），对应 `ulimit -t`。
 *                       这不是「CPU 核数」也不是「CPU 占用百分比」——它是进程累计
 *                       消耗的 CPU 时间，超限后内核直接 SIGKILL。UI 文案必须如实说明，
 *                       否则用户会以为这是限速。
 * @property stackMb     栈大小上限（MB），对应 `ulimit -s`（KB 单位）。
 */
data class ResourceLimits(
    val memoryMb: Int = 0,
    val maxProcesses: Int = 0,
    val maxOpenFiles: Int = 0,
    val cpuSeconds: Int = 0,
    val stackMb: Int = 0
) {

    /** 是否没有任何限制——为 true 时不应生成 ulimit 包装 */
    val isUnlimited: Boolean
        get() = memoryMb <= 0 && maxProcesses <= 0 &&
            maxOpenFiles <= 0 && cpuSeconds <= 0 && stackMb <= 0

    /**
     * 生成传给 proot 的 **guest 命令 argv**。
     *
     * ⚠️ **绝不要把整段脚本当成一个 argv 传给 proot。** proot 不像 `env`/`nice`
     * 那样会替你调用 shell：它把「最后一个参数」当作**可执行文件路径**解析
     * （见 proot 源码 `src/path/path.c` 的 `which()`：字符串含 `/` 时按显式路径
     * `realpath`，失败即报 `'%s' not found (root = …, $PATH=…)`），随后
     * `launch_process()` 直接 `execvp(tracee->exe, argv)`。
     * 因此形如 `ulimit -v 1024; exec "/bin/sh"` 的**单个字符串**会被当成文件名，
     * 结果是**设置了任何资源限制的会话全部启动失败**。
     *
     * 正确做法是传 3 个独立 argv：`/bin/sh`、`-c`、以及脚本文本。
     *
     * @param shellPath guest 内 shell 的绝对路径
     * @return 追加到 proot 命令行末尾的 argv 列表；无限制时返回 `listOf(shellPath)`
     */
    fun toGuestCommand(shellPath: String): List<String> {
        val script = toScript(shellPath) ?: return listOf(shellPath)
        // 用 /bin/sh -c 执行脚本；脚本末尾的 exec 会让真正的 shell 替换掉这个 sh
        return listOf(SH_PATH, "-c", script)
    }

    /**
     * 生成 shell 脚本正文（**不是**可直接交给 proot 的单个 argv）。
     *
     * **必须用 `exec`**（§8.18）：不用 exec 的话进程树是
     * `proot → sh -c → sh(真正的 shell)`，`ProotSession.destroy()` 只能杀掉中间那层
     * `sh -c`，真正的 shell 会变成孤儿继续持有 rootfs。用 exec 让真正的 shell
     * **替换**包装进程，进程树保持两层，destroy 才是有效的。
     *
     * @return 脚本文本；无限制时返回 null
     */
    fun toScript(shellPath: String): String? {
        if (isUnlimited) return null
        val stmts = ArrayList<String>(5)
        // -v / -s 的单位是 KB，配置项是 MB，必须换算，否则 512MB 会被当成 512KB
        if (memoryMb > 0) stmts.add("ulimit -v ${memoryMb.toLong() * 1024}")
        if (stackMb > 0) stmts.add("ulimit -s ${stackMb.toLong() * 1024}")
        if (maxProcesses > 0) stmts.add("ulimit -u $maxProcesses")
        if (maxOpenFiles > 0) stmts.add("ulimit -n $maxOpenFiles")
        if (cpuSeconds > 0) stmts.add("ulimit -t $cpuSeconds")
        if (stmts.isEmpty()) return null
        return stmts.joinToString("; ") + "; exec \"$shellPath\""
    }

    /** 人类可读摘要，供列表卡片与日志使用；无限制时返回「不限制」 */
    fun summary(): String {
        if (isUnlimited) return "不限制"
        val parts = ArrayList<String>(5)
        if (memoryMb > 0) parts.add("内存 ${memoryMb}MB")
        if (stackMb > 0) parts.add("栈 ${stackMb}MB")
        if (maxProcesses > 0) parts.add("进程 $maxProcesses")
        if (maxOpenFiles > 0) parts.add("FD $maxOpenFiles")
        if (cpuSeconds > 0) parts.add("CPU 时间 ${cpuSeconds}s")
        return parts.joinToString(" · ")
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_MEMORY, memoryMb)
        put(KEY_PROCESSES, maxProcesses)
        put(KEY_OPEN_FILES, maxOpenFiles)
        put(KEY_CPU_SECONDS, cpuSeconds)
        put(KEY_STACK, stackMb)
    }

    companion object {
        const val KEY_MEMORY = "memory_mb"
        const val KEY_PROCESSES = "max_processes"
        const val KEY_OPEN_FILES = "max_open_files"
        const val KEY_CPU_SECONDS = "cpu_seconds"
        const val KEY_STACK = "stack_mb"

        val UNLIMITED = ResourceLimits()

        /**
         * 包装脚本用的解释器。guest 内各发行版都保证存在 `/bin/sh`
         * （Debian/Ubuntu 是 dash，Alpine 是 busybox ash），
         * 不能假设一定有 bash——Alpine 默认就没有。
         */
        const val SH_PATH = "/bin/sh"

        /** 若干预设，UI 用下拉即可选，避免用户填出会导致 shell 起不来的极端值 */
        val PRESETS: List<Pair<String, ResourceLimits>> = listOf(
            "不限制" to UNLIMITED,
            "轻量（512MB 内存 / 128 进程）" to ResourceLimits(memoryMb = 512, maxProcesses = 128),
            "标准（1GB 内存 / 256 进程 / 1024 FD）" to
                ResourceLimits(memoryMb = 1024, maxProcesses = 256, maxOpenFiles = 1024),
            "编译用（2GB 内存 / 512 进程 / 4096 FD）" to
                ResourceLimits(memoryMb = 2048, maxProcesses = 512, maxOpenFiles = 4096)
        )

        fun fromJson(o: JSONObject?): ResourceLimits {
            if (o == null) return UNLIMITED
            return ResourceLimits(
                memoryMb = o.optInt(KEY_MEMORY, 0),
                maxProcesses = o.optInt(KEY_PROCESSES, 0),
                maxOpenFiles = o.optInt(KEY_OPEN_FILES, 0),
                cpuSeconds = o.optInt(KEY_CPU_SECONDS, 0),
                stackMb = o.optInt(KEY_STACK, 0)
            )
        }
    }
}
