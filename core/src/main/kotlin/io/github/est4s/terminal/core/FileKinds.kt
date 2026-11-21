package io.github.est4s.terminal.core

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/*
 * What a file is, so it opens in the right viewer (docs/TOUCH-DESIGN.md,
 * "Viewers"): by its name, or, for a file whose name says nothing, by its
 * first bytes (text or not).
 */

/** A kind of file mynx has a viewer for; [word] is how `mynx open --json` and the settings name it. */
enum class FileKind(val word: String) {
    PICTURE("picture"), MARKDOWN("markdown"), CODE("code"), TEXT("text"), AUDIO("audio"), VIDEO("video"),
    PDF("pdf"), HTML("html"),
}

/** Where a file opens: mynx's own viewer, or another Android app (Android's "open with"). */
enum class OpenWith(val word: String) { MYNX("mynx"), ANDROID("android") }

/** Where a file of [kind] opens, as its `open-…` setting says. */
fun Settings.openWith(kind: FileKind): OpenWith = when (kind) {
    FileKind.PICTURE -> openPictures
    FileKind.MARKDOWN -> openMarkdown
    FileKind.CODE -> openCode
    FileKind.TEXT -> openText
    FileKind.AUDIO -> openAudio
    FileKind.VIDEO -> openVideo
    FileKind.PDF -> openPdf
    FileKind.HTML -> openHtml
}

private val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "mdown", "mkd")
private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "mid", "midi")
private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "webm", "mkv", "3gp", "mov")
private val HTML_EXTENSIONS = setOf("html", "htm")
private val TEXT_EXTENSIONS = setOf("txt", "text", "log", "csv", "tsv", "out", "err", "rst", "adoc", "org", "srt", "vtt", "nfo")
private val TEXT_NAMES = setOf(
    "readme", "license", "licence", "copying", "authors", "changelog", "news", "todo", "install", "contributors",
    ".gitignore", ".dockerignore", ".gitattributes",
)

/** The kind of the file [name] (a name or a path), by its name alone; null when the name doesn't say. */
fun fileKindOf(name: String): FileKind? {
    val file = name.substringAfterLast('/')
    if (file.isEmpty()) return null
    val ext = extensionOf(file)
    return when {
        chatImageFile(file) -> FileKind.PICTURE
        ext in MARKDOWN_EXTENSIONS -> FileKind.MARKDOWN
        ext in AUDIO_EXTENSIONS -> FileKind.AUDIO
        ext in VIDEO_EXTENSIONS -> FileKind.VIDEO
        ext == "pdf" -> FileKind.PDF
        // Rendered, not code: the page viewer's View source still highlights it (codeLanguageOf).
        ext in HTML_EXTENSIONS -> FileKind.HTML
        codeLanguageOf(file) != null -> FileKind.CODE
        ext in TEXT_EXTENSIONS || file.lowercase() in TEXT_NAMES -> FileKind.TEXT
        else -> null
    }
}

private val LANGUAGE_EXTENSIONS: Map<String, String> = buildMap {
    fun language(id: String, vararg extensions: String) = extensions.forEach { put(it, id) }
    language("python", "py", "pyw", "pyi")
    language("shell", "sh", "bash", "zsh", "ksh", "bats")
    language("kotlin", "kt", "kts")
    language("java", "java")
    language("javascript", "js", "mjs", "cjs", "jsx")
    language("typescript", "ts", "tsx", "mts", "cts")
    language("c", "c", "h")
    language("cpp", "cpp", "cc", "cxx", "c++", "hpp", "hh", "hxx", "ino")
    language("rust", "rs")
    language("go", "go")
    language("lua", "lua")
    language("ruby", "rb", "rake", "gemspec")
    language("php", "php")
    language("sql", "sql")
    language("json", "json", "jsonc", "geojson", "webmanifest")
    language("yaml", "yaml", "yml")
    language("toml", "toml")
    language("ini", "ini", "conf", "cfg", "properties", "desktop", "service")
    language("html", "html", "htm", "xhtml")
    language("xml", "xml", "svg", "plist", "xsd", "xsl", "kml", "gpx")
    language("css", "css")
    language("dockerfile", "dockerfile")
    language("makefile", "mk", "mak")
    language("diff", "diff", "patch")
    language("markdown", "md", "markdown", "mdown", "mkd")
}

private val LANGUAGE_NAMES = mapOf(
    "makefile" to "makefile", "gnumakefile" to "makefile",
    "dockerfile" to "dockerfile", "containerfile" to "dockerfile",
    ".bashrc" to "shell", ".bash_profile" to "shell", ".bash_login" to "shell", ".bash_logout" to "shell",
    ".bash_aliases" to "shell", ".profile" to "shell", ".zshrc" to "shell", ".zprofile" to "shell",
    ".zshenv" to "shell", ".kshrc" to "shell", ".envrc" to "shell", "pkgbuild" to "shell",
    ".gitconfig" to "ini", ".gitmodules" to "ini", ".editorconfig" to "ini", ".npmrc" to "ini",
    "gemfile" to "ruby", "rakefile" to "ruby", "pipfile" to "toml",
)

/** The language of the source file [name] (a name or a path), as the highlighter names it, or null. */
fun codeLanguageOf(name: String): String? {
    val file = name.substringAfterLast('/')
    val lower = file.lowercase()
    LANGUAGE_NAMES[lower]?.let { return it }
    if (lower.startsWith("dockerfile.")) return "dockerfile"
    return LANGUAGE_EXTENSIONS[extensionOf(file)]
}

private fun extensionOf(file: String): String? =
    file.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() && file.lastIndexOf('.') > 0 }

/**
 * Whether [head] (a file's first bytes, up to 8 KB) is text: UTF-8 with no
 * NUL byte. A character cut off at the end of the head still counts.
 */
fun looksLikeText(head: ByteArray): Boolean {
    if (head.any { it == 0.toByte() }) return false
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val result = decoder.decode(ByteBuffer.wrap(head), CharBuffer.allocate(head.size + 1), false)
    return !result.isError
}
