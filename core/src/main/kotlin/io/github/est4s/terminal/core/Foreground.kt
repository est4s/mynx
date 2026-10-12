package io.github.est4s.terminal.core

import java.io.File

private val PLAIN_WORD = Regex("""[A-Za-z0-9_./:=@%+,-]+""")

/**
 * What a tab's shell runs in the foreground, as a command line for the
 * shell: proot [prootPid]'s first program (the tab's shell) knows its
 * terminal's foreground process group (`tpgid`, field 8 of its `stat`),
 * and that group's leader's `cmdline` is the command. Null when the shell
 * itself is in the foreground, or nothing can be read.
 */
fun foregroundCommand(prootPid: Int, proc: File = File("/proc")): String? {
    val shell = prootTracees(prootPid, proc).filter { parentOf(it, proc) == prootPid }.minOrNull() ?: return null
    val fields = statFields(shell, proc) ?: return null
    // After the name: state, ppid, pgrp, session, tty, tpgid.
    val group = fields.getOrNull(2)?.toIntOrNull() ?: return null
    val foreground = fields.getOrNull(5)?.toIntOrNull()?.takeIf { it > 0 } ?: return null
    if (foreground == group) return null
    val argv = runCatching { File(proc, "$foreground/cmdline").readBytes() }.getOrNull() ?: return null
    val words = String(argv).split('\u0000').dropLastWhile { it.isEmpty() }
    return words.takeIf { it.isNotEmpty() }?.joinToString(" ") { if (PLAIN_WORD.matches(it)) it else shellQuote(it) }
}

private fun parentOf(pid: Int, proc: File): Int? =
    runCatching { File(proc, "$pid/status").readText() }.getOrNull()
        ?.let { Regex("""^PPid:\s*(\d+)""", RegexOption.MULTILINE).find(it) }?.groupValues?.get(1)?.toIntOrNull()

// The fields after the name in parentheses, which may hold spaces and parentheses itself.
private fun statFields(pid: Int, proc: File): List<String>? =
    runCatching { File(proc, "$pid/stat").readText() }.getOrNull()
        ?.substringAfterLast(')', "")?.trim()?.split(Regex("\\s+"))

/** A service name from [command]'s program (`/usr/sbin/sshd -D` → `sshd`), or "" if it doesn't make one. */
fun suggestServiceName(command: String?): String {
    val program = command?.trim()?.substringBefore(' ')?.substringAfterLast('/')?.lowercase() ?: return ""
    return program.takeIf(::isServiceName) ?: ""
}
