package io.github.est4s.terminal.core

import java.net.URI

/*
 * The page viewer (`HtmlViewActivity`, docs/TOUCH-DESIGN.md "HTML
 * files"): an HTML file rendered in a WebView that loads [htmlFileUrl]
 * and answers [HTML_FILE_HOST] from the file's folder
 * ([WebApps.respondFolder], see HTML FILES there). What the view decides
 * about each URL is here.
 */

/** Where a request of the page goes ([htmlRequestRoute]). */
enum class HtmlRoute {
    /** [HTML_FILE_HOST]: answered from the folder. */
    FOLDER,

    /** The web (http, https), and `data:`/`blob:` (the page's own): left to WebView. */
    NETWORK,

    /** Anything else (`file:`, `content:`, mynx's other hosts): refused. */
    BLOCKED,
}

/** What a link the page follows does ([htmlNavigation]). */
enum class HtmlNavigation {
    /** Another page in the folder (or `about:blank`): stays in the view. */
    STAY,

    /** A web link, `mailto:` or `tel:`: opens in the browser or the app for it. */
    OUTSIDE,

    /** Nothing happens (`javascript:`, `intent:`, `file:`, …). */
    BLOCKED,
}

/** The folder the page viewer serves for the HTML file [path] (a Debian path): its parent. */
fun htmlViewFolder(path: String): String = path.substringBeforeLast('/', "").ifEmpty { "/" }

/** Where the request for [url] goes. */
fun htmlRequestRoute(url: String): HtmlRoute {
    val (scheme, host) = schemeAndHost(url) ?: return HtmlRoute.BLOCKED
    return when (scheme) {
        "data", "blob" -> HtmlRoute.NETWORK
        "https" -> when {
            host == HTML_FILE_HOST -> HtmlRoute.FOLDER
            isMynxHost(host) -> HtmlRoute.BLOCKED
            else -> HtmlRoute.NETWORK
        }
        "http" -> if (isMynxHost(host)) HtmlRoute.BLOCKED else HtmlRoute.NETWORK
        else -> HtmlRoute.BLOCKED
    }
}

/** What following a link to [url] in the page does (the main frame's navigations). */
fun htmlNavigation(url: String): HtmlNavigation {
    if (url == "about:blank") return HtmlNavigation.STAY
    val (scheme, host) = schemeAndHost(url) ?: return HtmlNavigation.BLOCKED
    return when (scheme) {
        "mailto", "tel" -> HtmlNavigation.OUTSIDE
        "https" -> when {
            host == HTML_FILE_HOST -> HtmlNavigation.STAY
            isMynxHost(host) -> HtmlNavigation.BLOCKED
            else -> HtmlNavigation.OUTSIDE
        }
        "http" -> if (isMynxHost(host)) HtmlNavigation.BLOCKED else HtmlNavigation.OUTSIDE
        else -> HtmlNavigation.BLOCKED
    }
}

/**
 * The Debian path of the page at [url] in [folder] (as served: a path
 * ending in `/` is its `index.html`); null when [url] isn't on
 * [HTML_FILE_HOST] or its path doesn't decode.
 */
fun htmlPagePath(folder: String, url: String?): String? {
    if (url == null) return null
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() != "https" || uri.host?.lowercase() != HTML_FILE_HOST) return null
    // URI decodes the path as UTF-8 and leaves `+` alone; bad escapes threw above.
    val decoded = uri.path.orEmpty().ifEmpty { "/" }
    val path = if (decoded.endsWith("/")) decoded + "index.html" else decoded
    return folder.trimEnd('/') + path
}

/**
 * The top bar's title: the page's [pageTitle] (whitespace made single
 * spaces), else the name of [pagePath]. WebView reports a page without a
 * `<title>` by its address ([url], with or without the scheme), which
 * counts as none.
 */
fun htmlViewTitle(pageTitle: String?, url: String?, pagePath: String): String {
    val title = pageTitle?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
    val address = title.isNotEmpty() && url != null &&
        (title == url || title == url.substringAfter("://") || title.startsWith("$HTML_FILE_HOST/"))
    return if (title.isEmpty() || address) pagePath.substringAfterLast('/') else title
}

// The scheme and host of [url], lower case (host empty for `mailto:` and the like); null if it isn't a URL.
private fun schemeAndHost(url: String): Pair<String, String>? {
    val scheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*):").find(url)?.groupValues?.get(1)?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return scheme to ""
    val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return null
    return scheme to host
}

private fun isMynxHost(host: String): Boolean = host == WEB_APP_DOMAIN || host.endsWith(".$WEB_APP_DOMAIN")
