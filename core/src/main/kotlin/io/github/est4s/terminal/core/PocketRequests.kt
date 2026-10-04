package io.github.est4s.terminal.core

import java.io.File

private val REQUEST_FILE = Regex("[A-Za-z0-9_-]{1,64}\\.req")

/** A request from `pocket`: its [name] and the lines after it. */
data class PocketRequest(val name: String, val args: List<String>)

private class Refused(message: String) : Exception(message)

/**
 * Answers the `pocket` command. It writes a request to [dir] as `ID.req`
 * (renamed into place when complete): the request name on the first
 * line, one argument per line after it. The answer goes to `ID.reply` as
 * JSON, also renamed into place, and the request is removed. [home] is
 * root's home in Debian. The app applies what changed (see the names
 * returned by [processPending]).
 */
class PocketRequests(private val dir: File, private val home: File) {
    private val config = File(home, CONFIG_DIR)
    private val settingsFile = File(config, "settings.conf")
    private val colorsFile = File(config, "colors.properties")
    private val themesDir = File(home, THEMES_DIR)
    private val keyBarsDir = File(config, "keybars")

    /** Answers every waiting request; returns those handled, in order. */
    fun processPending(): List<PocketRequest> {
        val pending = dir.listFiles { f -> f.isFile && REQUEST_FILE.matches(f.name) }.orEmpty().sortedBy { it.name }
        return pending.map { file ->
            val lines = runCatching { file.readText().lines().dropLastWhile { it.isEmpty() } }.getOrDefault(emptyList())
            val request = PocketRequest(lines.firstOrNull()?.trim().orEmpty(), lines.drop(1))
            val reply = try {
                answer(request)
            } catch (e: Refused) {
                """{"ok":false,"error":${json(e.message!!)}}"""
            }
            val id = file.name.removeSuffix(".req")
            val tmp = File(dir, "$id.reply.tmp")
            tmp.writeText(reply)
            tmp.renameTo(File(dir, "$id.reply"))
            file.delete()
            request
        }
    }

    private fun answer(request: PocketRequest): String {
        val args = request.args
        fun need(count: Int) {
            if (args.size < count) throw Refused("${request.name} needs $count argument${if (count > 1) "s" else ""}")
        }
        return when (request.name) {
            "check" -> ok("problems" to problemsJson(checkConfig(home)))
            "settings" -> settings()
            "set" -> {
                need(2)
                val (key, value) = args
                val text = setSetting(settingsFile.takeIf { it.isFile }?.readText(), key, value)
                    .getOrElse { throw Refused(it.message!!) }
                writeAtomically(settingsFile, text)
                ok("key" to json(key), "value" to json(value))
            }
            "reset" -> {
                need(1)
                val key = args[0]
                if (key == "all") {
                    settingsFile.delete()
                    ok("key" to json("all"))
                } else {
                    val def = SETTINGS.firstOrNull { it.key == key }
                        ?: throw Refused("unknown setting '$key' (pocket settings lists them)")
                    if (settingsFile.isFile) {
                        writeAtomically(settingsFile, unsetSetting(settingsFile.readText(), key).getOrThrow())
                    }
                    ok("key" to json(key), "value" to json(def.default))
                }
            }
            "theme-reset" -> {
                colorsFile.delete()
                ok("name" to json(BUILT_IN_THEMES.first()))
            }
            "themes" -> themes()
            "theme-show" -> {
                need(1)
                val (text, source) = theme(args[0])
                ok("name" to json(args[0]), "source" to json(source), "text" to json(text))
            }
            "theme-set" -> {
                need(1)
                writeAtomically(colorsFile, colorsFileFor(args[0], theme(args[0]).first))
                ok("name" to json(args[0]))
            }
            "preview-colors" -> ok("problems" to array(parseColorScheme(args.joinToString("\n"), NEON).problems.map(::json)))
            "preview-end" -> ok()
            "keybars" -> keyBars()
            "keybar-show" -> {
                need(1)
                val name = args[0]
                val user = userKeyBar(name)
                val text = user?.readText() ?: builtInKeyBarText(name) ?: throw Refused(noKeyBar(name))
                ok("name" to json(name), "file" to (user?.let { json(debianPath(it)) } ?: "null"), "text" to json(text))
            }
            "keybar-edit" -> {
                need(1)
                val name = args[0]
                if (!isKeyBarName(name)) throw Refused("'$name' can't be a bar name: use letters, digits, - and _")
                val file = File(keyBarsDir, "$name.conf")
                if (!file.isFile) writeAtomically(file, builtInKeyBarText(name) ?: NEW_KEY_BAR)
                ok("file" to json(debianPath(file)))
            }
            "keybar-reset" -> {
                need(1)
                val name = args[0]
                val user = userKeyBar(name)
                when {
                    builtInKeyBarText(name) == null && user == null -> throw Refused(noKeyBar(name))
                    builtInKeyBarText(name) == null ->
                        throw Refused("$name has no built-in bar to go back to; delete ${debianPath(user!!)} to remove it")
                    user == null -> throw Refused("$name is already the built-in bar")
                }
                user!!.delete()
                ok()
            }
            else -> throw Refused("unknown request '${request.name}'")
        }
    }

    private fun settings(): String {
        val parsed = loadSettings(settingsFile)
        val values = parsed.settings.values()
        val list = SETTINGS.map { def ->
            obj(
                "key" to json(def.key),
                "value" to json(values.getValue(def.key)),
                "default" to json(def.default),
                "description" to json(def.description),
                "choices" to (def.choices?.let { array(it.map(::json)) } ?: "null"),
            )
        }
        return ok("settings" to array(list), "problems" to array(parsed.problems.map(::json)))
    }

    private fun themes(): String {
        val colors = colorsFile.takeIf { it.isFile }?.readText()
        // No colours file: the app shows Neon.
        val current = if (colors == null) "neon" else themeNameOf(colors)
        val user = userThemes()
        val list = BUILT_IN_THEMES.filter { it !in user }.map { obj("name" to json(it), "source" to json("built-in")) } +
            user.map { (name, file) -> obj("name" to json(name), "source" to json(debianPath(file))) }
        return ok("current" to (current?.let(::json) ?: "null"), "themes" to array(list))
    }

    private fun userThemes(): Map<String, File> =
        themesDir.listFiles { f -> f.isFile && f.name.endsWith(THEME_SUFFIX) }.orEmpty()
            .associateBy { it.name.removeSuffix(THEME_SUFFIX) }
            .filterKeys { isKeyBarName(it) }
            .toSortedMap()

    /** A theme's text and where it comes from; the user's wins. */
    private fun theme(name: String): Pair<String, String> {
        userThemes()[name]?.let { return it.readText() to debianPath(it) }
        val builtIn = builtInThemeText(name) ?: throw Refused("no theme '$name' (pocket theme list shows them)")
        return builtIn to "built-in"
    }

    private fun keyBars(): String {
        val user = keyBarsDir.listFiles { f -> f.isFile && f.name.endsWith(".conf") }.orEmpty()
            .map { it.name.removeSuffix(".conf") }.filter { isKeyBarName(it) }
        val list = (BUILT_IN_KEY_BARS + user).distinct().sorted().map { name ->
            obj(
                "name" to json(name),
                "builtIn" to (name in BUILT_IN_KEY_BARS).toString(),
                "file" to (userKeyBar(name)?.let { json(debianPath(it)) } ?: "null"),
            )
        }
        return ok("keybars" to array(list))
    }

    private fun userKeyBar(name: String): File? =
        if (isKeyBarName(name)) File(keyBarsDir, "$name.conf").takeIf { it.isFile } else null

    private fun noKeyBar(name: String) = "no key bar '$name' (pocket keybar list shows them)"

    private fun debianPath(file: File) = "~/" + file.relativeTo(home).invariantSeparatorsPath

    private fun writeAtomically(file: File, text: String) {
        file.parentFile.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) throw Refused("couldn't write ${debianPath(file)}")
    }
}

private const val NEW_KEY_BAR = """# A key bar: one button per line, "label = keys". Buttons fill two
# rows in this order, the first half on top. Format: see ~/AGENTS.md.
Esc  = Esc
Tab  = Tab
Ctrl = Ctrl
Quit = q
←    = Left
↓    = Down
↑    = Up
→    = Right
"""

private fun problemsJson(found: List<ConfigProblems>) = array(found.map {
    obj("file" to json(it.file), "problems" to array(it.problems.map(::json)))
})

private fun ok(vararg fields: Pair<String, String>) = obj("ok" to "true", *fields)

private fun obj(vararg fields: Pair<String, String>) =
    fields.joinToString(",", "{", "}") { (key, value) -> "${json(key)}:$value" }

private fun array(items: List<String>) = items.joinToString(",", "[", "]")

internal fun json(text: String): String = buildString {
    append('"')
    for (c in text) {
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
    append('"')
}
