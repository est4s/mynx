package io.github.est4s.terminal.core

import java.io.File

/**
 * Android hides some system-wide `/proc` files from apps, which breaks tools
 * like htop, ps and free. Writes plausible stand-ins into [dir] for each one
 * that [canRead] says is blocked, and returns `/proc` path -> stand-in path,
 * ready for [prootLaunch]. The numbers are static, like proot-distro's.
 */
fun writeFakeProc(dir: File, cpus: Int, canRead: (String) -> Boolean): Map<String, String> {
    val contents = mapOf(
        "loadavg" to "0.12 0.07 0.02 2/165 765\n",
        "stat" to fakeStat(cpus),
        "uptime" to "124.08 932.80\n",
        "version" to "Linux version 6.1.0 (mynx@localhost) #1 SMP PREEMPT\n",
        "vmstat" to FAKE_VMSTAT,
    )
    dir.mkdirs()
    return contents
        .filterKeys { !canRead("/proc/$it") }
        .map { (name, content) ->
            val file = File(dir, name).apply { writeText(content) }
            "/proc/$name" to file.path
        }
        .toMap()
}

private fun fakeStat(cpus: Int) = buildString {
    append("cpu  ${1957 * cpus} 0 ${2877 * cpus} ${93280 * cpus} 262 342 254 87 0 0\n")
    repeat(cpus) { append("cpu$it 1957 0 2877 93280 33 43 32 11 0 0\n") }
    append("intr 63361 0 0 0\n")
    append("ctxt 38014093\n")
    append("btime 1694292441\n")
    append("processes 26442\n")
    append("procs_running 1\n")
    append("procs_blocked 0\n")
    append("softirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677\n")
}

private val FAKE_VMSTAT = """
    nr_free_pages 1743136
    nr_zone_inactive_anon 179281
    nr_zone_active_anon 7183
    nr_zone_inactive_file 22858
    nr_zone_active_file 51328
    nr_zone_unevictable 642
    nr_zone_write_pending 0
    nr_mlock 0
    nr_bounce 0
    nr_zspages 0
    nr_free_cma 0
    nr_inactive_anon 179281
    nr_active_anon 7183
    nr_inactive_file 22858
    nr_active_file 51328
    nr_unevictable 642
    nr_slab_reclaimable 8091
    nr_slab_unreclaimable 7804
    nr_anon_pages 15168
    nr_mapped 22383
    nr_file_pages 245453
    nr_dirty 0
    nr_writeback 0
    nr_shmem 171631
    nr_kernel_stack 2128
    nr_page_table_pages 1132
    pgpgin 890508
    pgpgout 0
    pswpin 0
    pswpout 0
    pgalloc_normal 2453285
    pgfree 4201431
    pgfault 1715433
    pgmajfault 2944
    oom_kill 0
""".trimIndent() + "\n"
