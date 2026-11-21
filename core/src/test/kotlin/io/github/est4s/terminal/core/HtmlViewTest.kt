package io.github.est4s.terminal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HtmlViewTest {
    private val page = "https://$HTML_FILE_HOST"

    @Test
    fun `the page's folder is the file's, the root for a file at the top`() {
        assertEquals("/root/site", htmlViewFolder("/root/site/index.html"))
        assertEquals("/", htmlViewFolder("/index.html"))
    }

    @Test
    fun `requests to the page's host are the folder's, the web's go out, other schemes are refused`() {
        assertEquals(HtmlRoute.FOLDER, htmlRequestRoute("$page/style.css"))
        assertEquals(HtmlRoute.FOLDER, htmlRequestRoute("https://PAGE.files.mynx.invalid/a.png"))
        assertEquals(HtmlRoute.NETWORK, htmlRequestRoute("https://cdn.jsdelivr.net/npm/x@1.0.0/x.js"))
        assertEquals(HtmlRoute.NETWORK, htmlRequestRoute("http://127.0.0.1:8000/api"))
        // In the page itself: no files behind them.
        assertEquals(HtmlRoute.NETWORK, htmlRequestRoute("data:image/png;base64,AAAA"))
        assertEquals(HtmlRoute.NETWORK, htmlRequestRoute("blob:$page/0b5c"))
        assertEquals(HtmlRoute.BLOCKED, htmlRequestRoute("http://$HTML_FILE_HOST/style.css"))
        assertEquals(HtmlRoute.BLOCKED, htmlRequestRoute("file:///data/data/x/files/rootfs/root/.ssh/id_rsa"))
        assertEquals(HtmlRoute.BLOCKED, htmlRequestRoute("content://media/external/1"))
        assertEquals(HtmlRoute.BLOCKED, htmlRequestRoute("https://sketch.mynx.invalid/index.html"))
        assertEquals(HtmlRoute.BLOCKED, htmlRequestRoute("https://mynx.invalid/mynx.js"))
        assertEquals(HtmlRoute.BLOCKED, htmlRequestRoute("not a url at all"))
    }

    @Test
    fun `links in the folder stay, web links, mail and calls go to other apps, the rest go nowhere`() {
        assertEquals(HtmlNavigation.STAY, htmlNavigation("$page/other.html"))
        assertEquals(HtmlNavigation.STAY, htmlNavigation("$page/docs/#intro"))
        assertEquals(HtmlNavigation.STAY, htmlNavigation("about:blank"))
        assertEquals(HtmlNavigation.OUTSIDE, htmlNavigation("https://example.com/"))
        assertEquals(HtmlNavigation.OUTSIDE, htmlNavigation("HTTP://example.com/a?b=c"))
        assertEquals(HtmlNavigation.OUTSIDE, htmlNavigation("mailto:someone@example.com"))
        assertEquals(HtmlNavigation.OUTSIDE, htmlNavigation("tel:+15551234"))
        assertEquals(HtmlNavigation.BLOCKED, htmlNavigation("javascript:alert(1)"))
        assertEquals(HtmlNavigation.BLOCKED, htmlNavigation("intent://scan/#Intent;scheme=zxing;end"))
        assertEquals(HtmlNavigation.BLOCKED, htmlNavigation("file:///sdcard/x.html"))
        assertEquals(HtmlNavigation.BLOCKED, htmlNavigation("http://$HTML_FILE_HOST/x.html"))
        assertEquals(HtmlNavigation.BLOCKED, htmlNavigation("https://sketch.mynx.invalid/"))
        assertEquals(HtmlNavigation.BLOCKED, htmlNavigation("data:text/html,<p>hi"))
    }

    @Test
    fun `the page shown is a Debian path in the folder, decoded, a folder meaning its index`() {
        assertEquals("/root/site/index.html", htmlPagePath("/root/site", htmlFileUrl("/root/site/index.html")))
        assertEquals("/root/site/my page ü.html", htmlPagePath("/root/site", htmlFileUrl("/root/site/my page ü.html")))
        assertEquals("/root/site/docs/a.html", htmlPagePath("/root/site", "$page/docs/a.html?x=1#top"))
        assertEquals("/root/site/docs/index.html", htmlPagePath("/root/site", "$page/docs/"))
        assertEquals("/root/site/index.html", htmlPagePath("/root/site", page))
        assertEquals("/a+b.html", htmlPagePath("/", "$page/a+b.html"))
        assertNull(htmlPagePath("/root/site", "https://example.com/a.html"))
        assertNull(htmlPagePath("/root/site", "$page/%zz.html"))
        assertNull(htmlPagePath("/root/site", null))
    }

    @Test
    fun `the title is the page's, else its file's name`() {
        val url = htmlFileUrl("/root/site/report.html")
        assertEquals("Q3 report", htmlViewTitle("  Q3\n  report ", url, "/root/site/report.html"))
        assertEquals("report.html", htmlViewTitle(null, url, "/root/site/report.html"))
        assertEquals("report.html", htmlViewTitle("   ", url, "/root/site/report.html"))
        // With no <title>, WebView reports the address.
        assertEquals("report.html", htmlViewTitle(url, url, "/root/site/report.html"))
        assertEquals("report.html", htmlViewTitle("$HTML_FILE_HOST/report.html", url, "/root/site/report.html"))
    }
}
