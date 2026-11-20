package io.github.est4s.terminal.core

/** A file the user added to a prompt, copied into Debian: [path] as Debian sees it, [name] to show. */
data class ChatAttachment(val path: String, val name: String, val image: Boolean)

private val ATTACHED = Regex("\\[Attached (?:image|file): (.+)]")

/**
 * The prompt Claude gets: [text], then after a blank line one line a file,
 * `[Attached image: PATH]` or `[Attached file: PATH]`, which Claude reads
 * with its Read tool (images too). It's the text sent, so the queue's echo
 * matches it and the transcript keeps it: [splitAttachments] reads it back.
 */
fun promptWithAttachments(text: String, attachments: List<ChatAttachment>): String {
    if (attachments.isEmpty()) return text
    val lines = attachments.joinToString("\n") {
        val kind = if (it.image) "image" else "file"
        "[Attached $kind: ${it.path.replace(Regex("[\r\n]+"), " ")}]"
    }
    return if (text.isBlank()) lines else "$text\n\n$lines"
}

/** [promptWithAttachments] undone: the text and the paths, from the attachment lines that end it. */
fun splitAttachments(text: String): Pair<String, List<String>> {
    val lines = text.split('\n')
    var start = lines.size
    while (start > 0 && ATTACHED.matches(lines[start - 1])) start--
    if (start == lines.size) return text to emptyList()
    val paths = lines.subList(start, lines.size).map { ATTACHED.matchEntire(it)!!.groupValues[1] }
    return when {
        start == 0 -> "" to paths
        start >= 2 && lines[start - 1].isEmpty() -> lines.subList(0, start - 1).joinToString("\n") to paths
        else -> text to emptyList()
    }
}

/** A prompt as a title: its text, else the first file's name. */
internal fun promptTitleText(prompt: String): String {
    val (text, paths) = splitAttachments(prompt)
    return text.ifBlank { paths.firstOrNull()?.substringAfterLast('/') ?: text }
}

/**
 * The decoder's sample size (a power of two) for a [width]×[height] image
 * shown cropped to a [size]-pixel square: the largest that keeps the
 * short side at least [size], so the thumbnail is sharp but small.
 */
fun thumbnailSampleSize(width: Int, height: Int, size: Int): Int {
    if (size <= 0) return 1
    val short = minOf(width, height)
    var sample = 1
    while (short / (sample * 2) >= size) sample *= 2
    return sample
}

// What Android's decoder reads (HEIC from Android 9); SVG isn't a picture it can decode.
private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp")

/** Whether [name] (a file's name or path) is a picture the chat can show. */
fun chatImageFile(name: String): Boolean {
    val file = name.substringAfterLast('/')
    return file.contains('.') && file.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS
}

/**
 * The decoder's sample size (a power of two) for a [width]×[height] image
 * shown full screen: the smallest that keeps the long side within
 * [maxSide] pixels (as the decoder rounds, up), so a huge picture can't
 * run the app out of memory or pass what the screen can draw.
 */
fun viewerSampleSize(width: Int, height: Int, maxSide: Int): Int {
    if (maxSide <= 0) return 1
    val long = maxOf(width, height)
    var sample = 1
    while ((long + sample - 1) / sample > maxSide) sample *= 2
    return sample
}
