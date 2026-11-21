package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileKindsTest {
    @Test
    fun `pictures are the ones chat shows`() {
        for (name in listOf("a.jpg", "b.PNG", "/root/c.webp", "d.heic")) assertEquals(FileKind.PICTURE, fileKindOf(name), name)
        assertEquals(FileKind.CODE, fileKindOf("logo.svg"))
    }

    @Test
    fun `Markdown files are Markdown, not code`() {
        assertEquals(FileKind.MARKDOWN, fileKindOf("notes.md"))
        assertEquals(FileKind.MARKDOWN, fileKindOf("/root/README.markdown"))
        assertEquals(FileKind.MARKDOWN, fileKindOf("TODO.MD"))
    }

    @Test
    fun `source files are code`() {
        for (name in listOf("main.py", "build.gradle.kts", "App.java", "x.rs", "Makefile", "Dockerfile", ".bashrc", "settings.conf"))
            assertEquals(FileKind.CODE, fileKindOf(name), name)
    }

    @Test
    fun `plain text files are text`() {
        for (name in listOf("notes.txt", "build.log", "data.csv", "data.tsv", "README", "LICENSE", ".gitignore"))
            assertEquals(FileKind.TEXT, fileKindOf(name), name)
    }

    @Test
    fun `audio and video by their extensions`() {
        for (ext in listOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav", "mid")) assertEquals(FileKind.AUDIO, fileKindOf("a.$ext"), ext)
        for (ext in listOf("mp4", "webm", "mkv", "3gp", "mov")) assertEquals(FileKind.VIDEO, fileKindOf("v.${ext.uppercase()}"), ext)
    }

    @Test
    fun `PDF and HTML files by their extensions, HTML no longer code`() {
        for (name in listOf("a.pdf", "/root/Report.PDF")) assertEquals(FileKind.PDF, fileKindOf(name), name)
        for (name in listOf("index.html", "/root/site/page.htm", "A.HTML")) assertEquals(FileKind.HTML, fileKindOf(name), name)
        assertEquals("html", codeLanguageOf("index.html"), "View source still highlights it")
        assertEquals(FileKind.CODE, fileKindOf("page.xhtml"))
    }

    @Test
    fun `other files have no kind by name`() {
        for (name in listOf("app.apk", "data.bin", "archive.tar.gz", "noext", "", "folder/")) assertNull(fileKindOf(name), name)
    }

    @Test
    fun `code languages by extension`() {
        val expected = mapOf(
            "a.py" to "python", "a.sh" to "shell", "a.bash" to "shell", "a.kt" to "kotlin", "a.kts" to "kotlin",
            "A.java" to "java", "a.js" to "javascript", "a.mjs" to "javascript", "a.jsx" to "javascript",
            "a.ts" to "typescript", "a.tsx" to "typescript", "a.c" to "c", "a.h" to "c", "a.cpp" to "cpp",
            "a.hpp" to "cpp", "a.cc" to "cpp", "sketch.ino" to "cpp", "a.rs" to "rust", "a.go" to "go", "a.lua" to "lua",
            "a.rb" to "ruby", "a.php" to "php", "a.sql" to "sql", "a.json" to "json", "a.yaml" to "yaml",
            "a.yml" to "yaml", "Cargo.toml" to "toml", "a.ini" to "ini", "a.conf" to "ini", "a.cfg" to "ini",
            "colors.properties" to "ini", "a.html" to "html", "a.htm" to "html", "a.xml" to "xml", "a.svg" to "xml",
            "a.css" to "css", "a.diff" to "diff", "a.patch" to "diff", "a.md" to "markdown", "a.mk" to "makefile",
            "x.dockerfile" to "dockerfile", "A.PY" to "python",
        )
        for ((name, language) in expected) assertEquals(language, codeLanguageOf(name), name)
    }

    @Test
    fun `code languages by well-known names`() {
        val expected = mapOf(
            "Makefile" to "makefile", "makefile" to "makefile", "GNUmakefile" to "makefile",
            "Dockerfile" to "dockerfile", "Containerfile" to "dockerfile", "Dockerfile.dev" to "dockerfile",
            "/root/.bashrc" to "shell", ".profile" to "shell", ".bash_profile" to "shell", ".zshrc" to "shell",
            "PKGBUILD" to "shell", ".gitconfig" to "ini", ".editorconfig" to "ini", "Gemfile" to "ruby", "Rakefile" to "ruby",
        )
        for ((name, language) in expected) assertEquals(language, codeLanguageOf(name), name)
    }

    @Test
    fun `no language for what isn't code`() {
        for (name in listOf("notes.txt", "a.mp3", "README", "a.png", "noext", "")) assertNull(codeLanguageOf(name), name)
    }

    @Test
    fun `text is UTF-8 without NUL bytes`() {
        assertTrue(looksLikeText(ByteArray(0)))
        assertTrue(looksLikeText("hello\nwörld ✓\r\n\ttab".toByteArray()))
        assertFalse(looksLikeText(byteArrayOf(0x68, 0x00, 0x69)))
        assertFalse(looksLikeText(byteArrayOf(0x68, 0xff.toByte(), 0x69)))
        assertFalse(looksLikeText(byteArrayOf(0xc3.toByte(), 0x28)))
    }

    @Test
    fun `a character cut by the 8 KB head still counts as text`() {
        val bytes = "ab✓".toByteArray()
        assertTrue(looksLikeText(bytes.copyOf(bytes.size - 1)))
        assertTrue(looksLikeText(bytes.copyOf(bytes.size - 2)))
    }

    @Test
    fun `each kind follows its setting, mynx's viewer by default`() {
        for (kind in FileKind.entries) assertEquals(OpenWith.MYNX, Settings().openWith(kind), kind.name)
        val settings = Settings(
            openPictures = OpenWith.ANDROID, openMarkdown = OpenWith.MYNX, openCode = OpenWith.ANDROID,
            openText = OpenWith.MYNX, openAudio = OpenWith.ANDROID, openVideo = OpenWith.MYNX,
            openPdf = OpenWith.ANDROID, openHtml = OpenWith.MYNX,
        )
        assertEquals(
            listOf(OpenWith.ANDROID, OpenWith.MYNX, OpenWith.ANDROID, OpenWith.MYNX, OpenWith.ANDROID, OpenWith.MYNX, OpenWith.ANDROID, OpenWith.MYNX),
            listOf(FileKind.PICTURE, FileKind.MARKDOWN, FileKind.CODE, FileKind.TEXT, FileKind.AUDIO, FileKind.VIDEO, FileKind.PDF, FileKind.HTML)
                .map { settings.openWith(it) },
        )
    }

    @Test
    fun `the kinds and choices have the words mynx uses`() {
        assertEquals(listOf("picture", "markdown", "code", "text", "audio", "video", "pdf", "html"), FileKind.entries.map { it.word })
        assertEquals(listOf("mynx", "android"), OpenWith.entries.map { it.word })
    }
}
